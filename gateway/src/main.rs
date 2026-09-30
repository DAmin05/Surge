//! Surge edge gateway: admission-token verification, rate limiting, proxying to the
//! Java services, and WebSocket fan-out of seat events. Week 0 is the skeleton:
//! health and Prometheus metrics.

use std::net::SocketAddr;

use axum::{routing::get, Router};
use metrics_exporter_prometheus::{PrometheusBuilder, PrometheusHandle};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    // `surge-gateway healthcheck` lets the container probe itself without curl.
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        std::process::exit(healthcheck());
    }

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .json()
        .init();

    let metrics = PrometheusBuilder::new()
        .install_recorder()
        .expect("install Prometheus recorder");

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    tracing::info!(%addr, "gateway listening");

    axum::serve(listener, app(metrics))
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("server");
}

fn app(metrics: PrometheusHandle) -> Router {
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route(
            "/metrics",
            get(move || std::future::ready(metrics.render())),
        )
}

fn healthcheck() -> i32 {
    use std::io::{Read, Write};
    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".into());
    let probe = || -> std::io::Result<bool> {
        let mut s = std::net::TcpStream::connect(format!("127.0.0.1:{port}"))?;
        s.set_read_timeout(Some(std::time::Duration::from_secs(2)))?;
        s.write_all(b"GET /health HTTP/1.0\r\n\r\n")?;
        let mut buf = String::new();
        s.read_to_string(&mut buf)?;
        Ok(buf.starts_with("HTTP/1.0 200") || buf.starts_with("HTTP/1.1 200"))
    };
    match probe() {
        Ok(true) => 0,
        _ => 1,
    }
}

async fn shutdown_signal() {
    let ctrl_c = async { tokio::signal::ctrl_c().await.expect("ctrl-c handler") };
    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("SIGTERM handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! { _ = ctrl_c => {}, _ = terminate => {} }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{body::Body, http::Request};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_is_ok() {
        let metrics = PrometheusBuilder::new().build_recorder().handle();
        let res = app(metrics)
            .oneshot(Request::get("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), 200);
        assert_eq!(
            &res.into_body().collect().await.unwrap().to_bytes()[..],
            b"ok"
        );
    }
}
