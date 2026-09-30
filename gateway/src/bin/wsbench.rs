//! WebSocket fan-out benchmark: the week-3 exit criterion.
//!
//! Connects CLIENTS WebSocket clients to the gateway's seat map for one event, then
//! makes HOLDS single-seat holds (one seat event each) at HOLD_RATE per second. Every
//! client should receive every event. Latency is measured per delivery, from the
//! moment Inventory made the change (`occurredAtMs`) to the moment the client read it:
//! Inventory -> Redpanda -> gateway consumer -> fan-out -> socket.
//!
//! Exits non-zero if p99 exceeds P99_BUDGET_MS or any client missed an event.
//! Runs inside the Compose network (same host clock as Inventory).

use std::{
    env,
    sync::{
        atomic::{AtomicU64, AtomicUsize, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

use futures::StreamExt;
use hdrhistogram::Histogram;
use serde_json::Value;
use surge_gateway::now_millis;
use tokio::sync::Semaphore;
use tokio_tungstenite::tungstenite::{protocol::WebSocketConfig, Message};

fn var<T: std::str::FromStr>(name: &str, default: T) -> T {
    env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

fn text(name: &str, default: &str) -> String {
    env::var(name).unwrap_or_else(|_| default.to_string())
}

fn occurred_at(frame: &str) -> Option<u64> {
    const KEY: &str = r#""occurredAtMs":"#;
    let start = frame.find(KEY)? + KEY.len();
    let digits = &frame[start..];
    let end = digits
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(digits.len());
    digits[..end].parse().ok()
}

struct Results {
    histogram: std::sync::Mutex<Histogram<u64>>,
    delivered: AtomicU64,
    complete: AtomicUsize,
}

#[tokio::main]
async fn main() {
    let gateway = text("GATEWAY", "gateway:8080");
    let inventory = text("INVENTORY", "http://inventory:8082");
    let event_id: i64 = var("EVENT_ID", 1);
    let clients: usize = var("CLIENTS", 10_000);
    let holds: usize = var("HOLDS", 100);
    let rate: f64 = var("HOLD_RATE", 20.0);
    let budget_ms: u64 = var("P99_BUDGET_MS", 200);
    let concurrency: usize = var("CONNECT_CONCURRENCY", 500);

    let http = reqwest::Client::new();
    let catalog: Value = http
        .get(format!("http://{gateway}/api/events/{event_id}"))
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .expect("catalog")
        .json()
        .await
        .expect("catalog json");
    let seats: Vec<(String, i64)> = catalog["sections"]
        .as_array()
        .expect("sections")
        .iter()
        .flat_map(|s| {
            let name = s["section"].as_str().unwrap_or_default().to_string();
            s["seats"]
                .as_array()
                .cloned()
                .unwrap_or_default()
                .into_iter()
                .map(move |seat| (name.clone(), seat["id"].as_i64().unwrap_or(0)))
        })
        .collect();
    assert!(
        seats.len() >= holds,
        "event {event_id} has {} seats, need {holds}",
        seats.len()
    );

    let results = Arc::new(Results {
        histogram: std::sync::Mutex::new(
            Histogram::new_with_bounds(1, 60_000, 3).expect("histogram"),
        ),
        delivered: AtomicU64::new(0),
        complete: AtomicUsize::new(0),
    });
    let ready = Arc::new(AtomicUsize::new(0));
    let failed = Arc::new(AtomicUsize::new(0));
    let gate = Arc::new(Semaphore::new(concurrency));

    // 1. Connect every client and wait for its snapshot.
    let started = Instant::now();
    let mut tasks = Vec::with_capacity(clients);
    for _ in 0..clients {
        let url = format!("ws://{gateway}/ws/events/{event_id}");
        let (results, ready, failed, gate) =
            (results.clone(), ready.clone(), failed.clone(), gate.clone());
        tasks.push(tokio::spawn(async move {
            let permit = gate.acquire_owned().await.expect("semaphore");
            let config = WebSocketConfig::default()
                .read_buffer_size(16 * 1024)
                .write_buffer_size(4 * 1024);
            let Ok((mut ws, _)) =
                tokio_tungstenite::connect_async_with_config(&url, Some(config), true).await
            else {
                failed.fetch_add(1, Ordering::Relaxed);
                return;
            };
            match ws.next().await {
                Some(Ok(Message::Text(t))) if t.contains("\"snapshot\"") => {}
                _ => {
                    failed.fetch_add(1, Ordering::Relaxed);
                    return;
                }
            }
            drop(permit);
            ready.fetch_add(1, Ordering::Relaxed);

            let mut local = Histogram::<u64>::new_with_bounds(1, 60_000, 3).expect("histogram");
            let mut got = 0usize;
            while got < holds {
                match tokio::time::timeout(Duration::from_secs(120), ws.next()).await {
                    Ok(Some(Ok(Message::Text(t)))) => {
                        // 200k messages/s: find the timestamp without a full JSON parse,
                        // so the load generator isn't the bottleneck it measures.
                        if t.starts_with(r#"{"type":"seat""#) {
                            if let Some(at) = occurred_at(&t) {
                                local.saturating_record(now_millis().saturating_sub(at).max(1));
                            }
                            got += 1;
                        }
                    }
                    Ok(Some(Ok(_))) => {}
                    _ => break,
                }
            }
            results.delivered.fetch_add(got as u64, Ordering::Relaxed);
            if got == holds {
                results.complete.fetch_add(1, Ordering::Relaxed);
            }
            results
                .histogram
                .lock()
                .expect("histogram")
                .add(&local)
                .expect("merge");
            let _ = ws.close(None).await;
        }));
    }
    while ready.load(Ordering::Relaxed) + failed.load(Ordering::Relaxed) < clients {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let connected = ready.load(Ordering::Relaxed);
    println!(
        "connected {connected}/{clients} clients in {:.1}s ({} failed)",
        started.elapsed().as_secs_f64(),
        failed.load(Ordering::Relaxed)
    );

    // 2. Make HOLDS changes at a steady rate: one seat event each.
    let interval = Duration::from_secs_f64(1.0 / rate.max(0.1));
    let mut tick = tokio::time::interval(interval);
    for (i, (section, seat)) in seats.iter().take(holds).enumerate() {
        tick.tick().await;
        let res = http
            .post(format!("{inventory}/holds"))
            .header("X-User-Id", format!("bench-{i}"))
            .json(&serde_json::json!({"eventId": event_id, "section": section, "seatIds": [seat]}))
            .send()
            .await;
        match res {
            Ok(r) if r.status().as_u16() == 201 => {}
            Ok(r) => panic!("hold {i} failed: {}", r.status()),
            Err(e) => panic!("hold {i} failed: {e}"),
        }
    }

    // 3. Wait for every client to see every event (or give up).
    for t in tasks {
        let _ = t.await;
    }
    let h = results.histogram.lock().expect("histogram");
    let expected = connected as u64 * holds as u64;
    let delivered = results.delivered.load(Ordering::Relaxed);
    let complete = results.complete.load(Ordering::Relaxed);
    println!("holds {holds} at {rate}/s -> {delivered}/{expected} deliveries, {complete}/{connected} clients saw every event");
    println!(
        "latency ms: p50 {} | p90 {} | p99 {} | p99.9 {} | max {}",
        h.value_at_quantile(0.50),
        h.value_at_quantile(0.90),
        h.value_at_quantile(0.99),
        h.value_at_quantile(0.999),
        h.max()
    );
    let p99 = h.value_at_quantile(0.99);
    let ok = connected == clients && delivered == expected && p99 < budget_ms;
    if ok {
        println!("WSBENCH OK: {clients} clients, p99 {p99} ms < {budget_ms} ms, no missed events");
    } else {
        println!("WSBENCH FAIL: connected {connected}/{clients}, delivered {delivered}/{expected}, p99 {p99} ms (budget {budget_ms} ms)");
        std::process::exit(1);
    }
}
