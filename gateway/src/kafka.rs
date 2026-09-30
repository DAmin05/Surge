//! Kafka consumers (pure Rust, rskafka: no librdkafka build).
//!
//! Every gateway instance reads every partition of `seat-events` from the latest
//! offset, with no consumer group: each instance must see every event for its own
//! connected clients. Also reads `token-revocations` from the start (it's short-lived).

use std::{sync::Arc, time::Duration};

use futures::StreamExt;
use rskafka::client::{
    consumer::{StartOffset, StreamConsumerBuilder},
    partition::UnknownTopicHandling,
    Client, ClientBuilder,
};
use serde::Deserialize;

use crate::{fanout::Hub, tokens::Verifier};

pub const SEAT_EVENTS: &str = "seat-events";
pub const REVOCATIONS: &str = "token-revocations";

#[derive(Deserialize)]
struct EventId {
    #[serde(rename = "eventId")]
    event_id: i64,
    #[serde(rename = "occurredAtMs")]
    occurred_at_ms: Option<u64>,
}

#[derive(Deserialize)]
struct Revocation {
    jti: String,
    exp: u64,
}

async fn connect(bootstrap: &str) -> Client {
    loop {
        match ClientBuilder::new(vec![bootstrap.to_string()])
            .client_id("gateway")
            .build()
            .await
        {
            Ok(c) => return c,
            Err(e) => {
                tracing::warn!(error = %e, "kafka not reachable, retrying");
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        }
    }
}

async fn partitions(client: &Client, topic: &str) -> Vec<i32> {
    loop {
        if let Ok(topics) = client.list_topics().await {
            if let Some(t) = topics.into_iter().find(|t| t.name == topic) {
                if !t.partitions.is_empty() {
                    return t.partitions.into_iter().collect();
                }
            }
        }
        tracing::warn!(topic, "topic not available yet, retrying");
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

/// Follows one partition forever, resuming after the last offset seen if the broker
/// restarts or the stream errors.
async fn follow<F>(
    client: Arc<Client>,
    topic: &'static str,
    partition: i32,
    start: StartOffset,
    mut handle: F,
) where
    F: FnMut(&[u8]) + Send,
{
    let mut next = start;
    loop {
        let pc = match client
            .partition_client(topic, partition, UnknownTopicHandling::Retry)
            .await
        {
            Ok(pc) => Arc::new(pc),
            Err(e) => {
                tracing::warn!(topic, partition, error = %e, "partition client failed, retrying");
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        let mut stream = StreamConsumerBuilder::new(pc, next)
            .with_min_batch_size(1)
            .with_max_wait_ms(50)
            .build();
        while let Some(item) = stream.next().await {
            match item {
                Ok((record, _high_watermark)) => {
                    next = StartOffset::At(record.offset + 1);
                    if let Some(value) = record.record.value.as_deref() {
                        handle(value);
                    }
                }
                Err(e) => {
                    tracing::warn!(topic, partition, error = %e, "consume failed, resuming");
                    metrics::counter!("gateway_kafka_errors_total", "topic" => topic).increment(1);
                    break;
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

pub async fn run(bootstrap: String, hub: Arc<Hub>, verifier: Arc<Verifier>) {
    let client = Arc::new(connect(&bootstrap).await);

    for partition in partitions(&client, REVOCATIONS).await {
        let verifier = verifier.clone();
        tokio::spawn(follow(
            client.clone(),
            REVOCATIONS,
            partition,
            StartOffset::Earliest,
            move |raw| {
                if let Ok(r) = serde_json::from_slice::<Revocation>(raw) {
                    verifier.revoke(r.jti, r.exp);
                    metrics::counter!("gateway_revocations_total").increment(1);
                }
            },
        ));
    }

    for partition in partitions(&client, SEAT_EVENTS).await {
        let hub = hub.clone();
        tokio::spawn(follow(
            client.clone(),
            SEAT_EVENTS,
            partition,
            StartOffset::Latest,
            move |raw| {
                let Ok(text) = std::str::from_utf8(raw) else {
                    return;
                };
                let Ok(meta) = serde_json::from_str::<EventId>(text) else {
                    return;
                };
                if let Some(at) = meta.occurred_at_ms {
                    let age = crate::now_millis().saturating_sub(at) as f64 / 1000.0;
                    metrics::histogram!("gateway_seat_event_age_at_fanout_seconds").record(age);
                }
                let receivers = hub.publish(meta.event_id, Hub::seat_frame(text));
                metrics::counter!("gateway_seat_events_total").increment(1);
                metrics::counter!("gateway_ws_frames_queued_total").increment(receivers as u64);
            },
        ));
    }
    tracing::info!("kafka consumers running");
}
