//! One broadcast channel per sale event. Seat events arrive once from Kafka and are
//! turned into a ready-to-send WebSocket frame once; every connection gets a cheap
//! reference-counted clone. Each connection writes whatever frames are waiting in one
//! flush (see `routes::seat_map`).
//!
//! Measured alternative, rejected: a few "shard" tasks per event each writing to a
//! slice of the sockets. Fewer task wake-ups, but a shard is sequential, and one shard
//! descheduled on a busy machine delays thousands of clients at once. Per-connection
//! tasks, balanced by Tokio's work stealing, had the better p99 (docs/results).

use std::time::Instant;

use axum::extract::ws::Utf8Bytes;
use dashmap::DashMap;
use tokio::sync::broadcast;

/// Buffered frames per event before a slow connection is told it lagged.
const CAPACITY: usize = 4096;

/// A ready-to-send frame and when the gateway received its event (fan-out latency).
#[derive(Clone)]
pub struct Frame {
    pub text: Utf8Bytes,
    pub received: Instant,
}

#[derive(Default)]
pub struct Hub {
    channels: DashMap<i64, broadcast::Sender<Frame>>,
}

impl Hub {
    pub fn subscribe(&self, event_id: i64) -> broadcast::Receiver<Frame> {
        self.channels
            .entry(event_id)
            .or_insert_with(|| broadcast::channel(CAPACITY).0)
            .subscribe()
    }

    /// Returns how many connections the frame was queued for.
    pub fn publish(&self, event_id: i64, frame: Utf8Bytes) -> usize {
        match self.channels.get(&event_id) {
            Some(tx) => tx
                .send(Frame {
                    text: frame,
                    received: Instant::now(),
                })
                .unwrap_or(0),
            None => 0,
        }
    }

    /// Wraps a raw seat event as `{"type":"seat","event":<raw>}` without re-serializing it.
    pub fn seat_frame(raw: &str) -> Utf8Bytes {
        let mut s = String::with_capacity(raw.len() + 26);
        s.push_str(r#"{"type":"seat","event":"#);
        s.push_str(raw);
        s.push('}');
        Utf8Bytes::from(s)
    }

    /// Drops channels nobody listens to any more.
    pub fn prune(&self) {
        self.channels.retain(|_, tx| tx.receiver_count() > 0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn frames_reach_every_subscriber_of_the_event_only() {
        let hub = Hub::default();
        let mut a = hub.subscribe(1);
        let mut b = hub.subscribe(1);
        let mut other = hub.subscribe(2);
        assert_eq!(hub.publish(1, Hub::seat_frame(r#"{"seq":1}"#)), 2);
        assert_eq!(
            a.recv().await.unwrap().text.as_str(),
            r#"{"type":"seat","event":{"seq":1}}"#
        );
        assert_eq!(
            b.recv().await.unwrap().text.as_str(),
            r#"{"type":"seat","event":{"seq":1}}"#
        );
        assert!(other.try_recv().is_err());
        assert_eq!(hub.publish(99, Hub::seat_frame("{}")), 0);
    }
}
