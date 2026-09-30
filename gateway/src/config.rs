//! Settings, from the environment only.

use std::{env, path::PathBuf, time::Duration};

#[derive(Clone, Debug)]
pub struct Config {
    pub port: u16,
    pub keys_dir: PathBuf,
    pub admission_url: String,
    pub inventory_url: String,
    pub order_url: String,
    /// The Next.js frontend; every path that isn't `/api`, `/ws` or ops goes there.
    pub frontend_url: Option<String>,
    pub kafka_bootstrap: Option<String>,
    /// Requests per second per client IP (burst = 2x).
    pub ip_rate: u32,
    /// Requests per second per authenticated user (burst = 2x).
    pub user_rate: u32,
    /// WebSocket connects per second per client IP (burst = 2x).
    pub ws_connect_rate: u32,
    /// Honour X-Forwarded-For (only behind a trusted load balancer).
    pub trust_forwarded: bool,
    pub snapshot_cache: Duration,
}

fn var(name: &str, default: &str) -> String {
    env::var(name)
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| default.to_string())
}

fn num<T: std::str::FromStr>(name: &str, default: T) -> T {
    env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            port: num("PORT", 8080),
            keys_dir: PathBuf::from(var("JWT_KEYS_DIR", "./secrets/jwt")),
            admission_url: var("ADMISSION_URL", "http://localhost:8081"),
            inventory_url: var("INVENTORY_URL", "http://localhost:8082"),
            order_url: var("ORDER_URL", "http://localhost:8083"),
            frontend_url: env::var("FRONTEND_URL").ok().filter(|v| !v.is_empty()),
            kafka_bootstrap: env::var("KAFKA_BOOTSTRAP").ok().filter(|v| !v.is_empty()),
            ip_rate: num("RATE_LIMIT_IP_PER_SEC", 50),
            user_rate: num("RATE_LIMIT_USER_PER_SEC", 10),
            ws_connect_rate: num("WS_CONNECT_PER_IP_PER_SEC", 20),
            trust_forwarded: var("TRUST_FORWARDED", "false") == "true",
            snapshot_cache: Duration::from_millis(num("SNAPSHOT_CACHE_MS", 250)),
        }
    }
}
