pub mod admin;
pub mod admin_operations;
pub mod api;
pub mod attachments;
pub mod auth;
pub mod calls;
pub mod chats;
pub mod conferences;
pub mod config;
pub mod console;
pub mod devices;
pub mod direct_transfers;
pub mod database_payload;
pub mod diagnostics;
pub mod error;
pub mod feedback;
pub mod history_backups;
pub mod history_transfer;
pub mod keys;
pub mod legal;
pub mod messages;
pub mod net;
pub mod performance;
pub mod presence;
pub mod push;
pub mod server_management;
pub mod state;
pub mod resources;
pub mod storage;
pub mod sync;
pub mod users;
pub mod validation;
pub mod websocket;

use axum::routing::{get, patch, post, put};
use axum::Router;
use tower_http::trace::TraceLayer;

use crate::state::AppState;

pub fn app(state: AppState) -> Router {
    Router::new()
        .merge(legal::routes())
        .nest("/admin", admin::routes())
        .nest("/administration", console::routes())
        .nest("/auth/recovery", auth::recovery::routes())
        .merge(conferences::routes())
        .merge(direct_transfers::routes())
        .route(
            "/feedback",
            post(feedback::submit).layer(axum::extract::DefaultBodyLimit::max(5 * 1024 * 1024)),
        )
        .route(
            "/diagnostics",
            post(diagnostics::ingest).layer(axum::extract::DefaultBodyLimit::max(512)),
        )
        .route(
            "/performance",
            post(performance::ingest).layer(axum::extract::DefaultBodyLimit::max(40 * 1024)),
        )
        .route("/server-info", get(server_management::public_policy))
        .route("/history-backup", get(history_backups::status))
        .merge(history_transfer::routes())
        .route(
            "/history-backup/restore-complete",
            post(history_backups::restore_complete),
        )
        .route(
            "/history-backup/restore-check",
            post(history_backups::restore_check).layer(axum::extract::DefaultBodyLimit::max(32768)),
        )
        .route(
            "/history-backup/source-check",
            post(history_backups::source_check)
                .layer(axum::extract::DefaultBodyLimit::max(3 * 1024 * 1024)),
        )
        .route(
            "/history-backup/media/start",
            post(history_backups::media::start),
        )
        .route(
            "/history-backup/media/:id/seal",
            post(history_backups::media::seal),
        )
        .route(
            "/history-backup/media/:id",
            get(history_backups::media::download),
        )
        // 8 MiB ciphertext in base64 plus up to 50k UUID media references.
        .route(
            "/history-backup/stage",
            post(history_backups::stage)
                .layer(axum::extract::DefaultBodyLimit::max(14 * 1024 * 1024)),
        )
        .route("/history-backup/confirm", post(history_backups::confirm))
        .route(
            "/history-backup/preference",
            post(history_backups::preference),
        )
        .route("/history-backup/delete", post(history_backups::delete))
        .route(
            "/history-backup/objects/:id",
            get(history_backups::download),
        )
        .route("/health", get(api::health::health_handler))
        .route("/ws", get(websocket::handler::ws_handler))
        .route("/auth/register", post(auth::handlers::register))
        .route("/auth/installations", post(auth::installations::create))
        .route(
            "/devices/me/installation",
            post(auth::installations::bind).layer(axum::extract::DefaultBodyLimit::max(1024)),
        )
        .route("/auth/login", post(auth::handlers::login))
        .route("/auth/refresh", post(auth::handlers::refresh))
        .route("/auth/logout", post(auth::handlers::logout))
        .route("/auth/logout-pending", post(auth::handlers::logout_pending))
        .route(
            "/auth/history-context",
            get(auth::handlers::history_context),
        )
        .route(
            "/users/me/delete",
            post(users::deletion::delete_account).layer(axum::extract::DefaultBodyLimit::max(4096)),
        )
        .route(
            "/users/me",
            get(users::handlers::get_me).patch(users::handlers::patch_me),
        )
        .route(
            "/users/me/appearance",
            get(users::appearance::get)
                .patch(users::appearance::patch)
                .layer(axum::extract::DefaultBodyLimit::max(16384)),
        )
        .route(
            "/users/me/avatar",
            post(users::handlers::create_avatar)
                .get(users::handlers::get_avatar)
                .delete(users::handlers::delete_avatar),
        )
        .route(
            "/users/me/avatar/complete",
            post(users::handlers::complete_avatar),
        )
        .route(
            "/users/:user_id/avatar",
            get(users::handlers::get_user_avatar),
        )
        .route(
            "/devices/me/push-token",
            put(push::handlers::register_push_token).delete(push::handlers::delete_push_token),
        )
        .route("/devices", get(devices::handlers::list_devices))
        .route(
            "/devices/revoke-others",
            post(devices::handlers::revoke_other_devices),
        )
        .route(
            "/devices/:device_id",
            patch(devices::handlers::rename_device)
                .delete(devices::handlers::revoke_device_handler),
        )
        .route(
            "/security/events",
            get(devices::handlers::list_security_events),
        )
        .route("/users/search", get(users::handlers::search))
        .route("/users/me/privacy", get(users::privacy::get).put(users::privacy::put))
        .route("/users/me/auto-delete", get(users::auto_delete::get).put(users::auto_delete::put)
            .layer(axum::extract::DefaultBodyLimit::max(4096)))
        .route("/users/me/activity", post(users::auto_delete::activity)
            .layer(axum::extract::DefaultBodyLimit::max(0)))
        .route("/users/blocked", get(users::blocks::list))
        .route(
            "/users/:user_id/block",
            post(users::blocks::block).delete(users::blocks::unblock),
        )
        .route("/users/directory", get(presence::directory))
        .route("/chats/direct", post(chats::handlers::create_direct))
        .route("/chats/saved", get(chats::handlers::get_or_create_saved))
        .route("/chats", get(chats::handlers::list_chats))
        .route("/chats/known", get(chats::handlers::known_chats))
        .route("/chats/peer/:user_id", get(chats::handlers::peer_chat))
        .route(
            "/chats/:chat_id/delete",
            post(chats::management::delete_chat),
        )
        .route(
            "/chats/:chat_id/clear-history",
            post(chats::management::clear_history),
        )
        .route("/chats/cleared", get(chats::management::personal_clears))
        .route(
            "/chats/pins",
            get(chats::management::pins).post(chats::management::set_pins),
        )
        .route(
            "/chats/archives",
            get(chats::management::archives).put(chats::management::set_archives),
        )
        .route("/chats/:chat_id", get(chats::handlers::get_chat))
        .route(
            "/chats/:chat_id/presence",
            get(chats::handlers::peer_presence),
        )
        .route("/chats/:chat_id/mute", post(chats::handlers::set_mute))
        .route("/chats/:chat_id/unmute", post(chats::handlers::clear_mute))
        .route("/chats/:chat_id/block", post(chats::handlers::block_chat))
        .route(
            "/chats/:chat_id/unblock",
            post(chats::handlers::clear_block),
        )
        .route(
            "/chats/:chat_id/report",
            post(chats::handlers::report_chat).layer(axum::extract::DefaultBodyLimit::max(32768)),
        )
        .route(
            "/chats/:chat_id/messages",
            post(messages::handlers::send_message).get(messages::handlers::list_messages),
        )
        .route("/chats/:chat_id/read", post(messages::handlers::mark_read))
        .route(
            "/chats/:chat_id/delivered",
            post(messages::handlers::mark_delivered),
        )
        .route("/sync", get(sync::handlers::sync))
        .route("/keys", post(keys::handlers::upload_keys))
        .route("/keys/bundles/:user_id", get(keys::handlers::get_bundles))
        .route(
            "/keys/bundles/:user_id/:device_id",
            get(keys::handlers::get_target_bundle),
        )
        .route(
            "/keys/devices/:user_id",
            get(keys::handlers::get_device_bundles),
        )
        .route(
            "/keys/one-time-count",
            get(keys::handlers::one_time_prekey_count),
        )
        .route(
            "/attachments",
            post(attachments::handlers::create_attachment),
        )
        .route(
            "/attachments/:attachment_id/multipart",
            post(attachments::multipart::start),
        )
        .route(
            "/attachments/:attachment_id/parts/:part",
            post(attachments::multipart::acknowledge),
        )
        .route(
            "/attachments/:attachment_id/complete",
            post(attachments::handlers::complete_attachment),
        )
        .route(
            "/attachments/:attachment_id/download",
            get(attachments::handlers::download_attachment),
        )
        .route("/calls", post(calls::handlers::create_call))
        .route("/calls/active", get(calls::handlers::get_active_call))
        .route("/calls/history", get(calls::handlers::history))
        .route(
            "/calls/history/deletions",
            get(calls::handlers::history_deletions),
        )
        .route(
            "/calls/:call_id/history/delete",
            post(calls::handlers::delete_history),
        )
        .route("/calls/ice-servers", get(calls::handlers::ice_servers))
        .route("/calls/:call_id", get(calls::handlers::get_call))
        .route("/calls/:call_id/read", post(calls::handlers::mark_read))
        .route("/calls/:call_id/accept", post(calls::handlers::accept_call))
        .route("/calls/:call_id/reject", post(calls::handlers::reject_call))
        .route("/calls/:call_id/cancel", post(calls::handlers::cancel_call))
        .route("/calls/:call_id/hangup", post(calls::handlers::hangup_call))
        .route(
            "/calls/:call_id/connected",
            post(calls::handlers::mark_connected),
        )
        .route("/calls/:call_id/failed", post(calls::handlers::fail_call))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}
