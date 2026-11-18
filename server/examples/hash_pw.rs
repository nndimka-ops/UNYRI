use argon2::{
    password_hash::{rand_core::OsRng, PasswordHasher, SaltString},
    Argon2,
};

fn main() {
    let pw = std::env::args().nth(1).unwrap_or_else(|| "password123".to_string());
    let salt = SaltString::generate(&mut OsRng);
    let hash = Argon2::default()
        .hash_password(pw.as_bytes(), &salt)
        .expect("hash")
        .to_string();
    println!("{hash}");
}
