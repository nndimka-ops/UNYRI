//! Display-name sanitising and the reserved-name check. The server is
//! authoritative; Android mirrors write validation. iOS write validation is
//! maintained separately; incoming-name presentation is independent of writes.
use unicode_normalization::char::is_combining_mark;
use unicode_normalization::UnicodeNormalization;

pub const MAX_CHARS: usize = 32;

/// Brand fragments: any name whose skeleton contains one is reserved.
const BRAND: &[&str] = &["unyri", "юнири", "унири", "юнайри"];

/// Staff words: reserved only as the whole name ("Support Team" stays free).
const STAFF_EXACT: &[&str] = &[
    "support", "techsupport", "customersupport", "helpdesk", "admin", "administrator", "administration",
    "moderator", "official", "staff", "поддержка", "техподдержка", "службаподдержки", "администратор",
    "администрация", "модератор", "официальный", "підтримка", "службапідтримки", "адміністратор",
    "адміністрація",
    "служба безопасности", "безопасность", "security", "system", "система", "системное сообщение",
    "уведомление", "notification", "bank", "банк", "оплата", "payment", "verification", "верификация", "unyri team",
    "служба безпеки", "безпека", "системне повідомлення", "сповіщення", "платіж", "верифікація",
];

const ZWJ: char = '\u{200D}';
const ZWNJ: char = '\u{200C}';

fn is_tag(c: char) -> bool {
    ('\u{E0020}'..='\u{E007F}').contains(&c)
}

fn is_variation_selector(c: char) -> bool {
    c == '\u{FE0E}' || c == '\u{FE0F}'
}

fn emoji_like(c: char) -> bool {
    matches!(c as u32,
        0x00A9 | 0x00AE | 0x203C | 0x2049 | 0x2122 | 0x2139 | 0x2194..=0x21AA | 0x231A..=0x23FF
        | 0x24C2 | 0x25AA..=0x25FE | 0x2600..=0x27BF | 0x2934..=0x2935 | 0x2B05..=0x2B55 | 0x3030
        | 0x303D | 0x3297 | 0x3299 | 0x1F000..=0x1FAFF | 0xFE0F | 0x20E3)
}

fn joining_script(c: char) -> bool {
    matches!(c as u32,
        0x0600..=0x06FF | 0x0700..=0x074F | 0x0750..=0x077F | 0x08A0..=0x08FF | 0x0900..=0x0DFF
        | 0xFB50..=0xFDFF | 0xFE70..=0xFEFE)
}

/// Invisible, direction-changing, filler and private-use scalars. ZWJ, ZWNJ,
/// tags and U+FE0E/U+FE0F have their own context rules in `sanitize`.
fn removed(c: char) -> bool {
    let u = c as u32;
    matches!(u,
        0x0000..=0x001F | 0x007F..=0x009F
        | 0x00AD | 0x034F | 0x0600..=0x0605 | 0x061C | 0x06DD | 0x070F | 0x0890..=0x0891 | 0x08E2
        | 0x115F | 0x1160 | 0x17B4 | 0x17B5 | 0x180B..=0x180F
        | 0x200B..=0x200F | 0x202A..=0x202E | 0x2060..=0x206F
        | 0x2800 | 0x3164 | 0xFE00..=0xFE0F | 0xFEFF | 0xFFA0 | 0xFFF0..=0xFFFC
        | 0x110BD | 0x110CD | 0x13430..=0x13440 | 0x1BCA0..=0x1BCA3 | 0x1D173..=0x1D17A
        | 0xE0000..=0xE0FFF
        | 0xE000..=0xF8FF | 0xF0000..=0xFFFFD | 0x100000..=0x10FFFD
        | 0xFDD0..=0xFDEF)
        || (u & 0xFFFE) == 0xFFFE
}

/// One pass keeps only scalars that render something in context, then
/// whitespace collapses, stacked marks are capped and the result is NFC.
pub fn sanitize(raw: &str) -> String {
    let input: Vec<char> = raw.nfd().collect();
    let mut kept: Vec<char> = Vec::with_capacity(input.len());
    for (index, &scalar) in input.iter().enumerate() {
        let c = if scalar.is_whitespace() { ' ' } else { scalar };
        let prev = kept.last().copied();
        let next = input.get(index + 1).copied();
        let keep = match c {
            // The review retains only the subdivision-flag tag exception.
            c if is_tag(c) => matches!(prev, Some(p) if p == '\u{1F3F4}' || is_tag(p)),
            c => !removed(c),
        };
        if keep {
            kept.push(c);
        }
    }

    // Baseless marks go before the whitespace collapse, so a dropped mark
    // never leaves two spaces behind (same result as collapse-then-drop).
    let mut marked: Vec<char> = Vec::with_capacity(kept.len());
    let mut run = 0usize;
    for c in kept {
        if is_combining_mark(c) {
            let has_base = matches!(marked.last(), Some(&p) if p != ' ');
            if !has_base || run >= 4 {
                continue;
            }
            run += 1;
        } else {
            run = 0;
        }
        marked.push(c);
    }

    let mut collapsed = String::with_capacity(marked.len());
    for c in marked {
        if c == ' ' && (collapsed.is_empty() || collapsed.ends_with(' ')) {
            continue;
        }
        collapsed.push(c);
    }
    collapsed.trim_end_matches(' ').nfc().collect()
}

/// 1-32 scalars with at least one that is visible on its own.
pub fn is_valid(s: &str) -> bool {
    let count = s.chars().count();
    (1..=MAX_CHARS).contains(&count)
        && s.chars().any(|c| c != ' ' && !is_combining_mark(c) && c != ZWJ && c != ZWNJ && !is_variation_selector(c) && !is_tag(c))
}

fn map_upper(c: char) -> Option<char> {
    Some(match c {
        'И' => 'n', 'Υ' => 'y', 'Η' => 'h', 'Ν' => 'n', 'Μ' => 'm',
        _ => return None,
    })
}

fn map_lower(c: char) -> char {
    match c {
        // Cyrillic
        'а' => 'a', 'в' => 'b', 'г' => 'r', 'е' => 'e', 'и' => 'u', 'і' => 'i', 'к' => 'k', 'м' => 'm',
        'н' => 'h', 'о' => 'o', 'п' => 'n', 'р' => 'p', 'с' => 'c', 'т' => 't', 'у' => 'y', 'х' => 'x',
        'ц' => 'u', 'ь' => 'b', 'ѕ' => 's', 'ј' => 'j', 'ԁ' => 'd', 'ԛ' => 'q', 'ԝ' => 'w', 'ү' => 'y',
        'һ' => 'h', 'ӏ' => 'i',
        // Greek
        'α' => 'a', 'β' => 'b', 'γ' => 'y', 'ε' => 'e', 'η' => 'n', 'ι' => 'i', 'κ' => 'k', 'μ' => 'u',
        'ν' => 'v', 'ο' => 'o', 'ρ' => 'p', 'τ' => 't', 'υ' => 'u', 'χ' => 'x', 'ω' => 'w',
        // Armenian
        'ս' => 'u', 'ո' => 'n', 'օ' => 'o', 'հ' => 'h',
        // Latin small capitals and look-alike letters
        'ı' => 'i', 'ȷ' => 'j', 'ɪ' => 'i', 'ʏ' => 'y', 'ɴ' => 'n', 'ʀ' => 'r', 'ᴜ' => 'u', 'ᴀ' => 'a',
        'ʙ' => 'b', 'ᴄ' => 'c', 'ᴅ' => 'd', 'ᴇ' => 'e', 'ɢ' => 'g', 'ʜ' => 'h', 'ᴊ' => 'j', 'ᴋ' => 'k',
        'ʟ' => 'l', 'ᴍ' => 'm', 'ᴏ' => 'o', 'ᴘ' => 'p', 'ꜱ' => 's', 'ᴛ' => 't', 'ᴠ' => 'v', 'ᴡ' => 'w',
        'ᴢ' => 'z', 'ł' => 'l', 'ø' => 'o', 'đ' => 'd', 'ħ' => 'h', 'ŧ' => 't', 'ſ' => 's', 'ƒ' => 'f',
        other => other,
    }
}

fn map_symbol(c: char) -> char {
    match c {
        '0' => 'o', '1' => 'i', '3' => 'e', '4' => 'a', '5' => 's', '7' => 't', '8' => 'b',
        '!' | '|' | '¡' => 'i', '@' => 'a', '$' => 's',
        other => other,
    }
}

fn push_skeleton(out: &mut String, c: char) {
    let c = map_symbol(c);
    if c.is_alphabetic() {
        out.push(c);
    }
}

/// Comparison skeleton: look-alikes, styled letters, digits used as letters,
/// spacing and punctuation all fold away.
pub fn key(s: &str) -> String {
    let folded: String = s.nfkc().collect::<String>().nfd().filter(|c| !is_combining_mark(*c)).collect();
    let mut out = String::with_capacity(folded.len());
    for c in folded.chars() {
        if let Some(mapped) = map_upper(c) {
            push_skeleton(&mut out, mapped);
            continue;
        }
        for lower in c.to_lowercase() {
            let mapped = map_lower(lower);
            push_skeleton(&mut out, if mapped == 'l' { 'i' } else { mapped });
        }
    }
    out
}

// Real root-zone labels, including Unicode IDNs and their xn-- spellings.
// This same file is packaged directly as an Android resource.
const ROOT_TLDS: &str = include_str!("../../shared/iana-root-tlds.txt");

const SCAM_WORDS: &[&str] = &[
    "support", "techsupport", "customersupport", "helpdesk", "admin", "administrator", "administration",
    "moderator", "official", "staff", "security", "system", "notification", "bank", "payment", "verification", "service",
    "message", "notifications", "payments", "verifications",
    "поддержка", "техподдержка", "служба", "службы", "поддержки", "администратор", "администрация", "модератор",
    "официальный", "безопасность", "безопасности", "система", "системное", "сообщение", "уведомление", "уведомления",
    "банк", "банка", "банку", "оплата", "оплаты", "платеж", "платежа", "верификация", "верификации",
    "підтримка", "підтримки", "служби", "адміністратор", "адміністрація", "безпека", "безпеки",
    "системне", "повідомлення", "сповіщення", "сповіщень", "платіж", "платежу", "оплати", "верифікація", "верифікації",
];

fn only_scam_words(skeleton: &str) -> bool {
    static WORDS: std::sync::OnceLock<Vec<String>> = std::sync::OnceLock::new();
    let words = WORDS.get_or_init(|| SCAM_WORDS.iter().map(|w| key(w)).collect());
    let mut counts = vec![None; skeleton.len() + 1];
    counts[0] = Some(0usize);
    for i in 0..skeleton.len() {
        let Some(count) = counts[i] else { continue };
        for word in words {
            if skeleton[i..].starts_with(word) {
                let end = i + word.len();
                counts[end] = Some(counts[end].unwrap_or(0).max(count + 1));
            }
        }
    }
    counts[skeleton.len()].is_some_and(|count| count >= 2)
}

pub fn is_reserved(name: &str) -> bool {
    [key(name), key(&name.to_lowercase())].iter().any(|skeleton| {
        !skeleton.is_empty() && (BRAND.iter().any(|brand| skeleton.contains(&key(brand)))
            || STAFF_EXACT.iter().any(|word| *skeleton == key(word)) || only_scam_words(skeleton))
    })
}

/// Brand fragments match anywhere. Staff words match an entire raw '_' / '.'
/// piece, first verbatim (officia1/adm1n), then with trailing ASCII digits removed.
pub fn is_reserved_username(name: &str) -> bool {
    let skeleton = key(name);
    if BRAND.iter().any(|word| skeleton.contains(&key(word))) { return true; }
    name.split(['_', '.']).any(|piece| {
        let trimmed = piece.trim_end_matches(|c: char| c.is_ascii_digit());
        [key(piece), key(trimmed)].iter().any(|candidate|
            STAFF_EXACT.iter().any(|word| *candidate == key(word)))
    })
}

fn link_form(name: &str) -> String {
    let normalized: String = sanitize(name).nfkc().collect::<String>().nfd()
        .filter(|c| !is_combining_mark(*c) && *c != ZWJ && *c != ZWNJ && !is_tag(*c)).collect();
    let normalized = normalized.to_lowercase().replace(" dot ", ".").replace(" точка ", ".").replace(" крапка ", ".");
    let mut folded = Vec::new();
    let mut after_dot = false;
    for c in normalized.chars() {
        let dot = matches!(c, '.' | '․' | '。' | '｡' | '﹒' | '·' | '‧' | '∙' | '⋅' | 'ꓸ' | '܁' | '۔');
        let separator = |x: char| x.is_whitespace() || matches!(x, '[' | ']' | '(' | ')' | '{' | '}');
        if dot {
            while folded.last().is_some_and(|c| separator(*c)) { folded.pop(); }
            folded.push('.');
            after_dot = true;
        } else if !(after_dot && separator(c)) {
            after_dot = false;
            folded.push(map_lower(c));
        }
    }
    folded.into_iter().collect()
}

pub fn starts_with_at(name: &str) -> bool {
    sanitize(name).nfkc().next() == Some('@')
}

pub fn contains_link(name: &str) -> bool {
    // Preserve the existing uppercase spoof mappings as an alternate form;
    // canonical lowercasing still recognizes real uppercase Unicode IDNs.
    let alternate: String = name.nfkc().collect::<String>().nfd().map(|c| map_upper(c).unwrap_or(c)).collect();
    contains_link_form(&link_form(name)) || contains_link_form(&link_form(&alternate))
}

fn contains_link_form(folded: &str) -> bool {
    if folded.contains("http") || folded.contains("www.") || folded.contains("mailto:") { return true; }
    for (i, _) in folded.match_indices("://") {
        let scheme: String = folded[..i].chars().rev().take_while(char::is_ascii_alphabetic).take(10).collect();
        if (2..=10).contains(&scheme.len()) { return true; }
    }
    for candidate in folded.split(|c: char| !c.is_ascii_digit() && c != '.') {
        let parts: Vec<&str> = candidate.trim_matches('.').split('.').collect();
        if parts.len() == 4 && parts.iter().all(|p| !p.is_empty() && p.parse::<u8>().is_ok()) { return true; }
    }
    static ROOTS: std::sync::OnceLock<std::collections::HashSet<String>> = std::sync::OnceLock::new();
    let roots = ROOTS.get_or_init(|| ROOT_TLDS.lines().filter(|line| !line.is_empty() && !line.starts_with('#')).map(link_form).collect());
    let chars: Vec<char> = folded.chars().collect();
    for (i, &c) in chars.iter().enumerate() {
        if c != '.' || i == 0 { continue; }
        let label: String = chars[..i].iter().rev().take_while(|c| c.is_alphanumeric() || **c == '-').copied().collect::<String>().chars().rev().collect();
        if label.is_empty() || label.starts_with('-') || label.ends_with('-') { continue; }
        let tld: String = chars[i + 1..].iter().take_while(|c| c.is_alphanumeric() || **c == '-').copied().collect();
        // .house is a real TLD, but the lead explicitly keeps Dr.House free.
        let title = ["dr", "mr", "mrs", "ms", "prof"].contains(&label.as_str())
            && !chars[i + 1 + tld.chars().count()..].iter().any(|c| matches!(c, '/' | ':' | '?'));
        if !title && roots.contains(&tld) { return true; }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_vectors() {
        let cases: &[(&str, &str)] = &[
            ("  Bob   Smith  ", "Bob Smith"),
            ("Bob\u{00A0}\u{2003}Smith", "Bob Smith"),
            ("\u{202E}nhoJ", "nhoJ"),
            ("Al\u{200B}ice", "Alice"),
            ("\u{2066}Eve\u{2069}", "Eve"),
            ("A\u{200D}B", "AB"),
            ("a\u{200C}b", "ab"),
            ("A\u{E0041}", "A"),
            ("\u{FE0F}Bob", "Bob"),
            ("e\u{0301}", "\u{00E9}"),
            ("\u{F8FF}Apple", "Apple"),
            ("a \u{0301} b", "a b"),
            ("Tab\tand\nline", "Tab and line"),
        ];
        for (raw, expected) in cases {
            assert_eq!(sanitize(raw), *expected, "{raw:?}");
        }
        for unchanged in [
            "👨\u{200D}👩\u{200D}👧",
            "🏳\u{FE0F}\u{200D}🌈",
            "می\u{200C}خواهم",
            "ශ්\u{200D}රී",
            "🏴\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}",
        ] {
            let expected: String = unchanged.chars().filter(|c| *c != ZWJ && *c != ZWNJ && !is_variation_selector(*c)).collect();
            assert_eq!(sanitize(unchanged), expected, "{unchanged:?}");
        }
        assert_eq!(sanitize("e\u{0301}").chars().count(), 1);
        let stacked = sanitize(&format!("Z{}", "\u{0301}".repeat(10)));
        assert_eq!(stacked.chars().count(), 4);
        assert!(stacked.starts_with('\u{0179}'));
        for code in [0x600, 0x6DD, 0x70F, 0x890, 0x8E2, 0x2065, 0x110BD, 0x110CD, 0x13430] {
            let hidden = char::from_u32(code).unwrap();
            assert_eq!(sanitize(&format!("A{hidden}B")), "AB", "format {code:x}");
        }
    }

    #[test]
    fn validity_vectors() {
        for invalid in ["\u{2800}\u{2800}", "\u{3164}", "\u{0301}", "   ", ""] {
            assert!(!is_valid(&sanitize(invalid)), "{invalid:?}");
        }
        assert!(!is_valid(&sanitize(&"a".repeat(33))));
        assert!(is_valid(&sanitize(&format!("{}\u{200B}", "a".repeat(32)))));
        // Sanitising itself never truncates a legacy stored name; only writes
        // call is_valid. Reads and unrelated profile edits retain the original.
        assert_eq!(sanitize(&"a".repeat(64)), "a".repeat(64));
        assert!(is_valid(&sanitize("🏴\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}")));
    }

    #[test]
    fn reserved_vectors() {
        for name in [
            "UNYRI", "unyri", "Unyri Support", "Поддержка UNYRI", "UNYRI Поддержка", "U N Y R I", "U.N.Y.R.I",
            "u_n_y_r_i", "ＵＮＹＲＩ", "𝐔𝐍𝐘𝐑𝐈", "ᴜɴʏʀɪ", "UNYRİ", "Ünyrí", "UNУRI", "UИYRI", "UNYR1", "UNYRl",
            "unyri.app", "Юнири", "Support", "Поддержка", "Служба поддержки", "Пoддepжкa", "Admin", "official",
        ] {
            assert!(is_reserved(name), "{name} should be reserved");
        }
    }

    #[test]
    fn allowed_vectors() {
        for name in ["Unity", "Yuri", "Sunny Rick", "Bunny Rich", "Support Team", "Dmytro", "Мария", "Администратор Петя"] {
            assert!(!is_reserved(name), "{name} should be allowed");
        }
    }

    #[test]
    fn key_folds_look_alikes() {
        assert_eq!(key("UNYRI"), "unyri");
        assert_eq!(key("UИYRI"), "unyri");
        assert_eq!(key("official"), "officiai");
        assert_eq!(key("Пoддepжкa"), key("Поддержка"));
    }

    #[test]
    fn links_and_at_vectors() {
        for name in ["http", "HTTPS://x", "www.example", "example.com",
            "visit foo-bar.dev!", "example․com", "example。com", "example｡com", "ｅｘａｍｐｌｅ．ｃｏｍ",
            "ex\u{200B}ample.com", "example.c\u{070F}om", "h\u{2065}ttp", "еxample.соm", "Alice (example.org)",
            "пример.рф", "приклад.укр", "example.xn--p1ai"] {
            assert!(contains_link(name), "{name}");
        }
        for name in ["Alice", "Support Team", "A. B.", "Dr. Smith", "a.b", "a.abcdefghijklmnopqrstuvwxyz", "v1.23", "a.com2"] {
            assert!(!contains_link(name), "{name}");
        }
        for name in ["@alice", "  @alice", "\u{202E}@alice", "＠alice"] { assert!(starts_with_at(name)); }
        assert!(!starts_with_at("Alice @home"));
    }

    #[test]
    fn staff_scam_and_username_vectors() {
        for name in STAFF_EXACT {
            assert!(is_reserved(name), "{name}");
            assert!(is_reserved(&name.to_uppercase()), "uppercase {name}");
            assert!(is_reserved_username(name), "{name}");
        }
        for name in ["ＳＥＣＵＲＩＴＹ", "Sуstem", "Служба\u{200B} безопасности", "UNYRI Team"] {
            assert!(is_reserved(name), "{name}");
        }
        for name in ["Support Team", "Security Expert", "Bank Holiday"] { assert!(!is_reserved(name), "{name}"); }
        for name in ["admin123", "support_team", "unyr1_user", "officia1", "security.alert", "system_bot", "payment_help"] {
            assert!(is_reserved_username(name), "{name}");
        }
        for name in ["alice", "sunny_rick", "unity", "yuri"] { assert!(!is_reserved_username(name), "{name}"); }
    }
}

#[cfg(test)]
mod fix1_contracts {
    use super::*;
    #[test]
    fn shared_policy_vectors() {
        for line in include_str!("../../shared/names-contract-vectors.tsv").lines().filter(|s| !s.starts_with('#')) {
            let columns: Vec<&str> = line.splitn(3, '\t').collect();
            if columns.len() != 3 { continue; }
            let actual = match columns[0] {
                "username" => is_reserved_username(columns[2]),
                "link" => contains_link(columns[2]),
                "reserved" => is_reserved(columns[2]),
                "ignorable" => {
                    let range: Vec<u32> = columns[2].split("..").map(|v| u32::from_str_radix(v, 16).unwrap()).collect();
                    (range[0]..=*range.last().unwrap()).all(|u| {
                        let c = char::from_u32(u).unwrap();
                        sanitize(&format!("A{c}B")) == "AB" && starts_with_at(&format!("{c}@Alice"))
                            && contains_link(&format!("example.c{c}om"))
                    })
                },
                _ => panic!("unknown vector"),
            };
            assert_eq!(actual, columns[1] == "block", "{line}");
        }
    }
    #[test]
    fn complete_default_ignorable_ranges_and_nfd_cap() {
        for u in [0xFFF0, 0xFFF8, 0xE0000, 0xE01F0, 0xE0FFF, 0x2065] {
            let c = char::from_u32(u).unwrap();
            assert_eq!(sanitize(&format!("A{c}B")), "AB");
            assert!(starts_with_at(&format!("{c}@Alice")));
            assert!(contains_link(&format!("example.c{c}om")));
        }
        let stored = sanitize(&format!("é{}", "́".repeat(10)));
        assert_eq!(stored.nfd().filter(|c| is_combining_mark(*c)).count(), 4);
        assert_eq!(sanitize(&stored), stored);
    }
}
