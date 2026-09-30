//! Surge edge gateway: stateless token verification, per-IP and per-user rate limits,
//! proxying to the Java services, and WebSocket fan-out of seat events.

pub mod config;
pub mod fanout;
pub mod kafka;
pub mod limits;
pub mod routes;
pub mod snapshot;
pub mod telemetry;
pub mod tokens;

pub fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
