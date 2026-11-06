//! Local server operator tool. Database credentials never enter the mobile app.
use anyhow::{bail, Context};
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();
    let args: Vec<String> = std::env::args().skip(1).collect();
    let full_reset = args
        .first()
        .is_some_and(|action| action == "reset-authentication");
    if (!full_reset
        && (args.len() != 2
            || !["grant-owner", "grant-moderator", "revoke", "reset-2fa"]
                .contains(&args[0].as_str())))
        || (full_reset && (args.len() != 3 || args[2] != "--confirm-reset-factors"))
    {
        bail!("Usage: coco-admin <grant-owner|grant-moderator|revoke|reset-2fa> <username>\nLost all administration factors: coco-admin reset-authentication <username> --confirm-reset-factors");
    }
    let db =
        sqlx::PgPool::connect(&std::env::var("DATABASE_URL").context("DATABASE_URL is required")?)
            .await?;
    sqlx::migrate!().run(&db).await?;
    let mut tx = db.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(2392001)")
        .execute(&mut *tx)
        .await?;
    let user: uuid::Uuid = sqlx::query_scalar(
        "SELECT id FROM users WHERE username_normalized=lower($1) AND deleted_at IS NULL",
    )
    .bind(args[1].trim())
    .fetch_optional(&mut *tx)
    .await?
    .context("Account not found")?;
    let current: Option<String> =
        sqlx::query_scalar("SELECT role FROM server_administrators WHERE user_id=$1 FOR UPDATE")
            .bind(user)
            .fetch_optional(&mut *tx)
            .await?;
    if current.as_deref() == Some("owner")
        && ["revoke", "grant-moderator"].contains(&args[0].as_str())
    {
        let owners: i64 =
            sqlx::query_scalar("SELECT count(*) FROM server_administrators WHERE role='owner'")
                .fetch_one(&mut *tx)
                .await?;
        if owners <= 1 {
            bail!("The last owner cannot be removed or demoted.");
        }
    }
    match args[0].as_str() {
        "grant-owner" | "grant-moderator" => {
            let role = if args[0] == "grant-owner" {
                "owner"
            } else {
                "moderator"
            };
            // Updating roles invalidates existing administrative sessions.
            sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,$2) ON CONFLICT(user_id) DO UPDATE SET role=excluded.role")
                .bind(user).bind(role).execute(&mut *tx).await?;
        }
        "revoke" => {
            sqlx::query("DELETE FROM server_administrators WHERE user_id=$1")
                .bind(user)
                .execute(&mut *tx)
                .await?;
        }
        "reset-2fa" => {
            // Reset the authenticator only. Never silently replace the paper key.
            sqlx::query("UPDATE server_administrators SET secret=NULL,pending_secret=NULL,pending_session=NULL,pending_expires=NULL,pending_policy=NULL,pending_revision=NULL,auth_revision=auth_revision+1,last_step=-1 WHERE user_id=$1").bind(user).execute(&mut *tx).await?;
            sqlx::query("DELETE FROM admin_recovery_codes WHERE user_id=$1")
                .bind(user)
                .execute(&mut *tx)
                .await?;
        }
        "reset-authentication" => {
            // This explicit offline procedure is for a verified server owner
            // who has lost the paper key. It never runs as an API fallback.
            let changed=sqlx::query("UPDATE server_administrators SET secret=NULL,signing_public_key=NULL,auth_policy='both',auth_revision=auth_revision+1,pending_secret=NULL,pending_session=NULL,pending_expires=NULL,pending_policy=NULL,pending_revision=NULL,last_step=-1 WHERE user_id=$1")
                .bind(user).execute(&mut *tx).await?;
            if changed.rows_affected() != 1 {
                bail!("Account has no administration role.");
            }
            sqlx::query("DELETE FROM admin_recovery_codes WHERE user_id=$1")
                .bind(user)
                .execute(&mut *tx)
                .await?;
        }
        _ => unreachable!(),
    }
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO admin_audit(action,target) VALUES($1,$2)")
        .bind(format!("local:{}", args[0]))
        .bind(user)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    println!("Administrative access updated. Existing admin sessions revoked.");
    Ok(())
}
