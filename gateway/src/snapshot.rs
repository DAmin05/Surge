//! Snapshots for new WebSocket clients: the event's sections (from Order's catalog) and
//! each section's `(epoch, seq, sold, held)` from Inventory.
//!
//! Short-lived caching and request coalescing keep a reconnect storm from turning into
//! a request storm. A slightly stale snapshot is safe: the client sees a seq gap on the
//! next event and asks again (ADR 0004).

use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};

use serde_json::{json, Value};
use tokio::sync::Mutex;

type Cached = Arc<Mutex<Option<(Instant, Arc<Value>)>>>;

pub struct Snapshots {
    http: reqwest::Client,
    inventory: String,
    order: String,
    ttl: Duration,
    catalog_ttl: Duration,
    cache: std::sync::Mutex<HashMap<String, Cached>>,
}

impl Snapshots {
    pub fn new(http: reqwest::Client, inventory: String, order: String, ttl: Duration) -> Self {
        Self {
            http,
            inventory,
            order,
            ttl,
            catalog_ttl: Duration::from_secs(30),
            cache: Default::default(),
        }
    }

    /// Single-flight + TTL: concurrent callers for one key share one upstream request.
    async fn cached(&self, key: String, ttl: Duration, url: String) -> Result<Arc<Value>, String> {
        let slot = self
            .cache
            .lock()
            .expect("cache lock")
            .entry(key)
            .or_default()
            .clone();
        let mut guard = slot.lock().await;
        if let Some((at, value)) = guard.as_ref() {
            if at.elapsed() < ttl {
                return Ok(value.clone());
            }
        }
        let value: Value = self
            .http
            .get(&url)
            .timeout(Duration::from_secs(3))
            .send()
            .await
            .and_then(|r| r.error_for_status())
            .map_err(|e| e.to_string())?
            .json()
            .await
            .map_err(|e| e.to_string())?;
        let value = Arc::new(value);
        *guard = Some((Instant::now(), value.clone()));
        Ok(value)
    }

    pub async fn catalog(&self, event_id: i64) -> Result<Arc<Value>, String> {
        self.cached(
            format!("catalog:{event_id}"),
            self.catalog_ttl,
            format!("{}/events/{event_id}", self.order),
        )
        .await
    }

    pub async fn section(&self, event_id: i64, section: &str) -> Result<Arc<Value>, String> {
        self.cached(
            format!("section:{event_id}:{section}"),
            self.ttl,
            format!("{}/sections/{event_id}/{section}/snapshot", self.inventory),
        )
        .await
    }

    /// `{"type":"snapshot","eventId":..,"sections":[..]}` for the given sections, or all.
    pub async fn frame(&self, event_id: i64, only: Option<&str>) -> Result<String, String> {
        let names: Vec<String> = match only {
            Some(s) => vec![s.to_string()],
            None => self
                .catalog(event_id)
                .await?
                .get("sections")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter()
                        .filter_map(|s| {
                            s.get("section").and_then(Value::as_str).map(str::to_string)
                        })
                        .collect()
                })
                .unwrap_or_default(),
        };
        let sections =
            futures::future::try_join_all(names.iter().map(|s| self.section(event_id, s))).await?;
        Ok(json!({
            "type": "snapshot",
            "eventId": event_id,
            "sections": sections.iter().map(|v| v.as_ref().clone()).collect::<Vec<_>>(),
        })
        .to_string())
    }
}
