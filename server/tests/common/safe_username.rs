/// Fixture suffixes use stable letters g-z, excluding i/l (the i skeleton)
/// and r/y so random suffixes cannot accidentally spell a protected brand.
pub fn unique_username(prefix: &str) -> String {
    const LETTERS: &[u8] = b"gkmnopqstuvwxz";
    let suffix: String = uuid::Uuid::new_v4().as_bytes().iter()
        .map(|byte| LETTERS[usize::from(*byte) % LETTERS.len()] as char).collect();
    format!("{prefix}{suffix}").chars().take(32).collect()
}
