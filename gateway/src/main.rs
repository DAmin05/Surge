use std::{net::SocketAddr, sync::Arc, time::Duration};

use axum::serve::ListenerExt;
use metrics_exporter_prometheus::{Matcher, PrometheusBuilder};
use surge_gateway::{
    config::Config,
    fanout::Hub,
    kafka,
    limits::Limits,
    routes::{router, AppState},
    snapshot::Snapshots,
    telemetry,
    tokens::{load_keys, Verifier},
};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

#[tokio::main]
async fn main() {
    // `surge-gateway healthcheck` lets the container probe itself without curl.
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        std::process::exit(healthcheck());
    }

    let tracer = telemetry::tracer_provider();
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with(tracing_subscriber::fmt::layer().json())
        .with(tracer.as_ref().map(telemetry::layer))
        .init();

    let metrics = PrometheusBuilder::new()
        .set_buckets_for_metric(
            Matcher::Suffix("_seconds".into()),
            &[
                0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.5, 1.0, 2.5, 5.0,
            ],
        )
        .expect("buckets")
        .install_recorder()
        .expect("install Prometheus recorder");

    let config = Config::from_env();
    let keys = load_keys(&config.keys_dir).unwrap_or_else(|e| panic!("signing keys: {e}"));
    let verifier = Arc::new(Verifier::new(keys));
    let hub = Arc::new(Hub::default());
    let http = reqwest::Client::builder()
        .pool_max_idle_per_host(256)
        .build()
        .expect("http client");

    let state = Arc::new(AppState {
        limits: Limits::new(config.ip_rate, config.user_rate, config.ws_connect_rate),
        snapshots: Snapshots::new(
            http.clone(),
            config.inventory_url.clone(),
            config.order_url.clone(),
            config.snapshot_cache,
        ),
        verifier: verifier.clone(),
        hub: hub.clone(),
        http,
        metrics,
        config: config.clone(),
    });

    if let Some(bootstrap) = config.kafka_bootstrap.clone() {
        tokio::spawn(kafka::run(bootstrap, hub.clone(), verifier.clone()));
    } else {
        tracing::warn!("KAFKA_BOOTSTRAP not set: no live seat events");
    }

    // Housekeeping: pick up rotated keys, forget idle limiter keys and dead channels.
    {
        let state = state.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(30));
            loop {
                tick.tick().await;
                match load_keys(&state.config.keys_dir) {
                    Ok(keys) => state.verifier.replace_keys(keys),
                    Err(e) => tracing::warn!(error = %e, "key reload failed; keeping current keys"),
                }
                state.verifier.purge_revocations();
                state.limits.prune();
                state.hub.prune();
            }
        });
    }

    let addr = SocketAddr::from(([0, 0, 0, 0], config.port));
    // Small, latency-sensitive frames: don't let Nagle hold them back.
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("bind")
        .tap_io(|tcp| {
            let _ = tcp.set_nodelay(true);
        });
    tracing::info!(%addr, "gateway listening");
    axum::serve(
        listener,
        router(state).into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    .expect("server");
}

fn healthcheck() -> i32 {
    use std::io::{Read, Write};
    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".into());
    let probe = || -> std::io::Result<bool> {
        let mut s = std::net::TcpStream::connect(format!("127.0.0.1:{port}"))?;
        s.set_read_timeout(Some(Duration::from_secs(2)))?;
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
