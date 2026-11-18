import Foundation

// Pure Swift; same presentation vectors as Android DisplayNameSanitizerTest.
var checks = 0
func check(_ ok: Bool, _ why: String) { precondition(ok, why); checks += 1 }
func display(_ raw: String, fallback: String = "") -> String { DisplayNameSanitizer.display(raw, fallback: fallback) }
let removed: [UInt32] = [0, 0x7F, 0xAD, 0x34F, 0x61C, 0x70F, 0x890, 0x115F, 0x17B4, 0x180F, 0x200B,
    0x200E, 0x202E, 0x2065, 0x2066, 0x2069, 0x2800, 0x3164, 0xFEFF, 0xF8FF, 0xFFFC, 0x13430, 0xE0100, 0x10FFFF]
for code in removed {
    check(display("A" + String(Unicode.Scalar(code)!) + "B") == "AB", "remove \(code)")
}
check(display("\u{202E}Al\u{200B}ice") == "Alice", "bidi/invisible")
check(display(" A\nB\tC ") == "A B C", "single-line whitespace")
check(display("A\u{200D}B") == "AB", "invisible joiner")
check(display(String(repeating: "\u{0301}", count: 10)).isEmpty, "baseless marks")
let marked = display("é" + String(repeating: "\u{0301}", count: 10)).decomposedStringWithCanonicalMapping
check(marked.unicodeScalars.count == 5, "four marks including precomposed accent")
check(display("e\u{0301}") == "é", "NFC")
for (raw, expected) in [("👨‍👩‍👧", "👨👩👧"), ("می‌خواهم", "میخواهم"), ("🏳️‍🌈", "🏳🌈"), ("Марія", "Марія"), ("李明", "李明")] {
    check(display(raw).unicodeScalars.elementsEqual(expected.unicodeScalars), "strip DICP \(raw)")
}
for base in ["a", "😀"] {
    check(display(String(repeating: base, count: 64)) == String(repeating: base, count: 64), "64 scalars unchanged")
    check(display(String(repeating: base, count: 65)) == String(repeating: base, count: 63) + "…", "ellipsis within cap")
    check(display(String(repeating: base, count: 1000)).unicodeScalars.count == 64, "layout cap")
}
check(display("\u{3164}", fallback: "\u{202E}Alice") == "Alice", "fallback sanitised")
check(display("", fallback: String(repeating: "a", count: 100)) == String(repeating: "a", count: 63) + "…", "fallback capped")
let cut = display(String(repeating: "a", count: 61) + "👨‍👩" + String(repeating: "b", count: 10))
check(!cut.unicodeScalars.contains("\u{200D}") && cut.unicodeScalars.count <= 64, "cut joiner removed")
let raw = "\u{202E}" + String(repeating: "a", count: 100)
let shown = display(raw)
check(raw.unicodeScalars.count == 101 && shown == String(repeating: "a", count: 63) + "…", "input unchanged")
check(display(shown) == shown, "idempotent")
print("Display name sanitizer contracts: \(checks) checks passed")

let extraIgnored: [UInt32] = [0xFFF0, 0xFFF8, 0xE01F0, 0xE0FFF]
for cp in extraIgnored {
    check(display("A"+String(Unicode.Scalar(cp)!)+"B") == "AB", "full DICP range")
}
check(DisplayNameSanitizer.peer("\u{FFF0}", username: "alice") == "@alice", "handle fallback")
check(DisplayNameSanitizer.peer("\u{FFF0}") == "Unknown user", "neutral fallback")
let huge = String(repeating: "z", count: 1024 * 1024)
let started = Date()
check(display(huge) == String(repeating: "z", count: 63)+"…", "bounded input")
let computations = DisplayNameSanitizer.computationCount
for _ in 0..<1000 { _ = display(huge) }
check(DisplayNameSanitizer.computationCount == computations, "cached sanitization")
check(Date().timeIntervalSince(started) < 5, "bounded cached calls stay cheap")
print("Fix1 display name contracts: \(checks) checks passed")

let vectorFile = try String(contentsOfFile: "shared/names-contract-vectors.tsv", encoding: .utf8)
for line in vectorFile.split(separator: "\n") {
    let columns = line.split(separator: "\t")
    if columns.count == 3 && columns[0] == "ignorable" {
        let endpoints = columns[2].components(separatedBy: "..").map { UInt32($0, radix: 16)! }
        for cp in endpoints.first!...endpoints.last! {
            check(display("A"+String(Unicode.Scalar(cp)!)+"B") == "AB", "Unicode 17 DICP \(cp)")
        }
    }
}
print("Full Unicode 17 DICP set verified: \(checks) checks")
