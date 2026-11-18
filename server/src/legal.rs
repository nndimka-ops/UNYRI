//! Public fixed policy resources. No account/session/database access.
use crate::state::AppState;
use axum::{
    body::Body,
    http::{header, HeaderValue, StatusCode},
    response::Response,
    routing::get,
    Router,
};

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/privacy", get(privacy))
        .route("/support", get(support))
        .route("/terms", get(terms))
        .route("/legal.css", get(styles))
        .route("/brand-icon.png", get(brand_icon))
}

fn document(body: &'static str, content_type: &'static str) -> Response {
    resource(body.as_bytes(), content_type)
}

fn resource(body: &'static [u8], content_type: &'static str) -> Response {
    let mut response = Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .body(Body::from(body))
        .expect("static policy response");
    for (name, value) in [
        ("content-security-policy", "default-src 'none'; style-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"),
        ("referrer-policy", "no-referrer"), ("x-content-type-options", "nosniff"), ("x-frame-options", "DENY"),
        ("permissions-policy", "camera=(), microphone=(), geolocation=()"), ("cache-control", "no-cache"),
    ] { response.headers_mut().insert(axum::http::HeaderName::from_static(name), HeaderValue::from_static(value)); }
    response
}
async fn privacy() -> Response {
    document(
        include_str!("../legal/privacy.html"),
        "text/html; charset=utf-8",
    )
}
async fn support() -> Response {
    document(
        include_str!("../legal/support.html"),
        "text/html; charset=utf-8",
    )
}
async fn terms() -> Response {
    document(
        include_str!("../legal/terms.html"),
        "text/html; charset=utf-8",
    )
}
async fn styles() -> Response {
    document(
        include_str!("../legal/legal.css"),
        "text/css; charset=utf-8",
    )
}

async fn brand_icon() -> Response {
    resource(include_bytes!("../legal/brand-icon.png"), "image/png")
}
