//! The public HTTP surface. Every request is rate-limited per IP; authenticated routes
//! also per user. The gateway, never the client, sets `X-User-Id` for the services
//! behind it, from a verified token.
//!
//! | Route | Token | Upstream |
//! |---|---|---|
//! | `POST /api/session` | none | Admission `/session` |
//! | `POST /api/queue/{e}/join`, `GET /api/queue/{e}/position` | session cookie | Admission |
//! | `GET /api/events/{e}` | none | Order catalog |
//! | `POST /api/holds`, `DELETE /api/holds/{id}` | admission, for that event | Inventory |
//! | `POST /api/checkout` | admission, for the hold's event | Order |
//! | `GET /api/events` | none | Order: events on sale |
//! | `GET /api/orders/{id}` | session cookie | Order: the buyer's own order |
//! | `GET /ws/events/{e}` | none | seat map (snapshot + live events) |
//! | anything else | none | the Next.js frontend (same origin: no CORS) |

use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::{Duration, Instant},
};

use axum::{
    body::{Body, Bytes},
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Path, State,
    },
    http::{header, HeaderMap, HeaderName, HeaderValue, Method, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
    Json, Router,
};
use futures::{SinkExt, StreamExt};
use metrics_exporter_prometheus::PrometheusHandle;
use serde::Deserialize;
use serde_json::json;
use tokio::sync::broadcast::error::{RecvError, TryRecvError};

use crate::{
    config::Config,
    fanout::Hub,
    limits::Limits,
    snapshot::Snapshots,
    tokens::{Claims, TokenType, Verifier},
};

pub struct AppState {
    pub config: Config,
    pub verifier: Arc<Verifier>,
    pub limits: Limits,
    pub hub: Arc<Hub>,
    pub snapshots: Snapshots,
    pub http: reqwest::Client,
    pub metrics: PrometheusHandle,
}

type St = State<Arc<AppState>>;

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route(
            "/metrics",
            get(|State(s): St| async move { s.metrics.render() }),
        )
        .route("/api/session", post(session))
        .route("/api/queue/{event_id}/join", post(queue))
        .route("/api/queue/{event_id}/position", get(queue))
        .route("/api/events", get(events))
        .route("/api/events/{event_id}", get(catalog))
        .route("/api/orders/{order_id}", get(order))
        .route("/api/holds", post(hold))
        .route("/api/holds/{hold_id}", delete(release))
        .route("/api/checkout", post(checkout))
        .route("/ws/events/{event_id}", get(ws))
        .fallback(frontend)
        .route_layer(axum::middleware::from_fn(crate::telemetry::trace_requests))
        .with_state(state)
}

// ------------------------------------------------------------------ helpers

fn error(status: StatusCode, code: &str) -> Response {
    (status, Json(json!({ "error": code }))).into_response()
}

fn too_many() -> Response {
    let mut r = error(StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED");
    r.headers_mut()
        .insert(header::RETRY_AFTER, HeaderValue::from_static("1"));
    r
}

fn client_ip(state: &AppState, headers: &HeaderMap, peer: SocketAddr) -> IpAddr {
    if state.config.trust_forwarded {
        if let Some(ip) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.split(',').next())
            .and_then(|v| v.trim().parse().ok())
        {
            return ip;
        }
    }
    peer.ip()
}

fn cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == name)
        .map(|(_, v)| v.to_string())
}

fn bearer(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
}

/// A request turned away before reaching a service (boxed: responses are large).
type Rejected = Box<Response>;

/// Per-IP limit, then the route's token, then the per-user limit.
fn admit(
    state: &AppState,
    headers: &HeaderMap,
    peer: SocketAddr,
    typ: Option<TokenType>,
) -> Result<Option<Claims>, Rejected> {
    if !state.limits.ip(client_ip(state, headers, peer)) {
        metrics::counter!("gateway_rate_limited_total", "by" => "ip").increment(1);
        return Err(Box::new(too_many()));
    }
    let Some(typ) = typ else { return Ok(None) };
    let token = match typ {
        TokenType::Session => cookie(headers, "surge_session"),
        TokenType::Admission => bearer(headers).map(str::to_string),
    };
    let claims = token
        .ok_or(())
        .and_then(|t| state.verifier.verify(typ, &t).map_err(|_| ()))
        .map_err(|_| {
            metrics::counter!("gateway_auth_rejected_total").increment(1);
            Box::new(error(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))
        })?;
    if !state.limits.user(&claims.sub) {
        metrics::counter!("gateway_rate_limited_total", "by" => "user").increment(1);
        return Err(Box::new(too_many()));
    }
    Ok(Some(claims))
}

/// An admission token only opens its own event's sale.
fn for_event(claims: &Claims, event_id: Option<i64>) -> Result<(), Rejected> {
    match (claims.event_id, event_id) {
        (Some(a), Some(b)) if a == b => Ok(()),
        _ => Err(Box::new(error(StatusCode::FORBIDDEN, "WRONG_EVENT"))),
    }
}

/// Hold ids name their event: `<eventId>:<section>:<uuid>`.
fn hold_event(hold_id: &str) -> Option<i64> {
    hold_id.split(':').next()?.parse().ok()
}

const FORWARD_REQUEST: [HeaderName; 3] = [
    header::CONTENT_TYPE,
    header::ACCEPT,
    HeaderName::from_static("idempotency-key"),
];
const FORWARD_RESPONSE: [HeaderName; 5] = [
    header::CONTENT_TYPE,
    header::SET_COOKIE,
    header::RETRY_AFTER,
    header::LOCATION,
    HeaderName::from_static("idempotent-replayed"),
];

/// Where a request goes and as whom.
struct Upstream<'a> {
    route: &'static str,
    method: Method,
    url: String,
    /// The verified user, sent as `X-User-Id`.
    user: Option<&'a str>,
    /// Pass the client's cookies (only Admission's `/session` needs them).
    with_cookie: bool,
}

/// Forwards to a service. Client-sent identity headers never pass: `X-User-Id` is only
/// ever the gateway's, from a verified token.
async fn forward(state: &AppState, to: Upstream<'_>, headers: &HeaderMap, body: Bytes) -> Response {
    let Upstream {
        route,
        method,
        url,
        user,
        with_cookie,
    } = to;
    let mut req = state
        .http
        .request(method, url)
        .timeout(Duration::from_secs(10))
        .body(body);
    for name in FORWARD_REQUEST.iter() {
        if let Some(v) = headers.get(name) {
            req = req.header(name, v);
        }
    }
    if with_cookie {
        if let Some(v) = headers.get(header::COOKIE) {
            req = req.header(header::COOKIE, v);
        }
    }
    if let Some(user) = user {
        req = req.header("x-user-id", user);
    }
    for (k, v) in crate::telemetry::outgoing_headers() {
        req = req.header(k, v);
    }
    let started = Instant::now();
    let res = match req.send().await {
        Ok(r) => r,
        Err(e) => {
            tracing::warn!(route, error = %e, "upstream unavailable");
            metrics::counter!("gateway_upstream_errors_total", "route" => route).increment(1);
            let mut r = error(StatusCode::SERVICE_UNAVAILABLE, "RETRY_LATER");
            r.headers_mut()
                .insert(header::RETRY_AFTER, HeaderValue::from_static("1"));
            return r;
        }
    };
    let status = res.status();
    let mut out = Response::builder().status(status.as_u16());
    for name in FORWARD_RESPONSE.iter() {
        for v in res.headers().get_all(name) {
            out = out.header(name, v);
        }
    }
    let body = res.bytes().await.unwrap_or_default();
    metrics::histogram!("gateway_upstream_seconds", "route" => route)
        .record(started.elapsed().as_secs_f64());
    metrics::counter!("gateway_requests_total", "route" => route, "status" => status.as_u16().to_string())
        .increment(1);
    out.body(Body::from(body))
        .unwrap_or_else(|_| error(StatusCode::BAD_GATEWAY, "BAD_GATEWAY"))
}

// ------------------------------------------------------------------ routes

async fn session(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Response {
    if let Err(r) = admit(&s, &headers, peer, None) {
        return *r;
    }
    let url = format!("{}/session", s.config.admission_url);
    let to = Upstream {
        route: "session",
        method: Method::POST,
        url,
        user: None,
        with_cookie: true,
    };
    forward(&s, to, &headers, Bytes::new()).await
}

async fn queue(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    method: Method,
    Path(event_id): Path<i64>,
    uri: axum::http::Uri,
    headers: HeaderMap,
) -> Response {
    let claims = match admit(&s, &headers, peer, Some(TokenType::Session)) {
        Ok(c) => c.expect("session claims"),
        Err(r) => return *r,
    };
    let tail = if uri.path().ends_with("/join") {
        "join"
    } else {
        "position"
    };
    let url = format!("{}/queue/{event_id}/{tail}", s.config.admission_url);
    let to = Upstream {
        route: "queue",
        method,
        url,
        user: Some(&claims.sub),
        with_cookie: false,
    };
    forward(&s, to, &headers, Bytes::new()).await
}

async fn catalog(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(event_id): Path<i64>,
    headers: HeaderMap,
) -> Response {
    if let Err(r) = admit(&s, &headers, peer, None) {
        return *r;
    }
    match s.snapshots.catalog(event_id).await {
        Ok(v) => Json(v.as_ref().clone()).into_response(),
        Err(_) => error(StatusCode::NOT_FOUND, "UNKNOWN_EVENT"),
    }
}

async fn events(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Response {
    if let Err(r) = admit(&s, &headers, peer, None) {
        return *r;
    }
    let url = format!("{}/events", s.config.order_url);
    let to = Upstream {
        route: "events",
        method: Method::GET,
        url,
        user: None,
        with_cookie: false,
    };
    forward(&s, to, &headers, Bytes::new()).await
}

async fn order(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(order_id): Path<i64>,
    headers: HeaderMap,
) -> Response {
    let claims = match admit(&s, &headers, peer, Some(TokenType::Session)) {
        Ok(c) => c.expect("session claims"),
        Err(r) => return *r,
    };
    let url = format!("{}/orders/{order_id}", s.config.order_url);
    let to = Upstream {
        route: "order",
        method: Method::GET,
        url,
        user: Some(&claims.sub),
        with_cookie: false,
    };
    forward(&s, to, &headers, Bytes::new()).await
}

/// Request headers never passed to the frontend: hop-by-hop, identity, credentials.
const FRONTEND_DROP_REQUEST: [&str; 8] = [
    "host",
    "connection",
    "keep-alive",
    "transfer-encoding",
    "upgrade",
    "x-user-id",
    "cookie",
    "authorization",
];
/// Response headers rebuilt by the gateway rather than copied.
const FRONTEND_DROP_RESPONSE: [&str; 4] = [
    "connection",
    "keep-alive",
    "transfer-encoding",
    "content-length",
];

/// Everything that isn't the API is the frontend. Pages, assets and the Next.js router's
/// requests (`RSC`, `Next-Router-*` headers) pass through with their headers intact.
async fn frontend(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    method: Method,
    uri: axum::http::Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Err(r) = admit(&s, &headers, peer, None) {
        return *r;
    }
    let Some(base) = s.config.frontend_url.as_deref() else {
        return error(StatusCode::NOT_FOUND, "NOT_FOUND");
    };
    let path = uri.path_and_query().map(|p| p.as_str()).unwrap_or("/");
    let mut req = s
        .http
        .request(method, format!("{base}{path}"))
        .timeout(Duration::from_secs(30))
        .body(body);
    for (name, value) in headers.iter() {
        if !FRONTEND_DROP_REQUEST.contains(&name.as_str()) {
            req = req.header(name, value);
        }
    }
    let res = match req.send().await {
        Ok(r) => r,
        Err(_) => return error(StatusCode::SERVICE_UNAVAILABLE, "FRONTEND_UNAVAILABLE"),
    };
    let mut out = Response::builder().status(res.status().as_u16());
    for (name, value) in res.headers().iter() {
        if !FRONTEND_DROP_RESPONSE.contains(&name.as_str()) {
            out = out.header(name, value);
        }
    }
    let body = res.bytes().await.unwrap_or_default();
    out.body(Body::from(body))
        .unwrap_or_else(|_| error(StatusCode::BAD_GATEWAY, "BAD_GATEWAY"))
}

#[derive(Deserialize)]
struct HoldBody {
    #[serde(rename = "eventId")]
    event_id: i64,
}

#[derive(Deserialize)]
struct CheckoutBody {
    #[serde(rename = "holdId")]
    hold_id: String,
}

async fn hold(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let claims = match admit(&s, &headers, peer, Some(TokenType::Admission)) {
        Ok(c) => c.expect("admission claims"),
        Err(r) => return *r,
    };
    let event = serde_json::from_slice::<HoldBody>(&body)
        .ok()
        .map(|b| b.event_id);
    if let Err(r) = for_event(&claims, event) {
        return *r;
    }
    let url = format!("{}/holds", s.config.inventory_url);
    let to = Upstream {
        route: "hold",
        method: Method::POST,
        url,
        user: Some(&claims.sub),
        with_cookie: false,
    };
    forward(&s, to, &headers, body).await
}

async fn release(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(hold_id): Path<String>,
    headers: HeaderMap,
) -> Response {
    let claims = match admit(&s, &headers, peer, Some(TokenType::Admission)) {
        Ok(c) => c.expect("admission claims"),
        Err(r) => return *r,
    };
    if let Err(r) = for_event(&claims, hold_event(&hold_id)) {
        return *r;
    }
    let url = format!("{}/holds/{hold_id}", s.config.inventory_url);
    let to = Upstream {
        route: "release",
        method: Method::DELETE,
        url,
        user: Some(&claims.sub),
        with_cookie: false,
    };
    forward(&s, to, &headers, Bytes::new()).await
}

async fn checkout(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let claims = match admit(&s, &headers, peer, Some(TokenType::Admission)) {
        Ok(c) => c.expect("admission claims"),
        Err(r) => return *r,
    };
    let event = serde_json::from_slice::<CheckoutBody>(&body)
        .ok()
        .and_then(|b| hold_event(&b.hold_id));
    if let Err(r) = for_event(&claims, event) {
        return *r;
    }
    let url = format!("{}/checkout", s.config.order_url);
    let to = Upstream {
        route: "checkout",
        method: Method::POST,
        url,
        user: Some(&claims.sub),
        with_cookie: false,
    };
    forward(&s, to, &headers, body).await
}

// ------------------------------------------------------------------ WebSocket

#[derive(Deserialize)]
struct ClientMessage {
    #[serde(rename = "type")]
    kind: String,
    section: Option<String>,
}

async fn ws(
    State(s): St,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(event_id): Path<i64>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> Response {
    if !s.limits.ws_connect(client_ip(&s, &headers, peer)) {
        metrics::counter!("gateway_rate_limited_total", "by" => "ws_connect").increment(1);
        return too_many();
    }
    // Clients send almost nothing and receive small frames: tungstenite's 128 KiB
    // default buffers would cost ~1.3 GB per 10k sockets. A client that stops reading
    // hits the write cap and is disconnected instead of growing memory without bound.
    upgrade
        .read_buffer_size(4 * 1024)
        .write_buffer_size(16 * 1024)
        .max_write_buffer_size(1024 * 1024)
        .on_upgrade(move |socket| seat_map(s, event_id, socket))
}

/// Too slow to keep up with the event's buffer: start over from a fresh snapshot.
async fn lagged<S>(s: &AppState, event_id: i64, tx: &mut S) -> Result<(), ()>
where
    S: futures::Sink<Message> + Unpin,
{
    metrics::counter!("gateway_ws_lagged_total").increment(1);
    let snapshot = s.snapshots.frame(event_id, None).await.map_err(|_| ())?;
    tx.send(Message::Text(snapshot.into()))
        .await
        .map_err(|_| ())
}

/// One client's live seat map. Subscribes before taking the snapshot, so nothing that
/// happens during the snapshot is missed; the client applies only events newer than
/// the snapshot's `(epoch, seq)` and asks for a fresh one on any gap.
async fn seat_map(s: Arc<AppState>, event_id: i64, socket: WebSocket) {
    let mut events = s.hub.subscribe(event_id);
    let (mut tx, mut rx) = socket.split();
    metrics::gauge!("gateway_ws_connections").increment(1.0);

    let result: Result<(), ()> = async {
        let snapshot = s.snapshots.frame(event_id, None).await.map_err(|e| {
            tracing::warn!(event_id, error = %e, "snapshot failed");
        })?;
        tx.send(Message::Text(snapshot.into())).await.map_err(|_| ())?;
        loop {
            tokio::select! {
                frame = events.recv() => match frame {
                    Ok(frame) => {
                        // Under load several frames are usually waiting: write them all,
                        // then flush once, instead of one syscall per frame.
                        let oldest = frame.received;
                        let mut sent = 1u64;
                        tx.feed(Message::Text(frame.text)).await.map_err(|_| ())?;
                        loop {
                            match events.try_recv() {
                                Ok(next) => {
                                    tx.feed(Message::Text(next.text)).await.map_err(|_| ())?;
                                    sent += 1;
                                }
                                Err(TryRecvError::Lagged(_)) => {
                                    lagged(&s, event_id, &mut tx).await?;
                                    break;
                                }
                                Err(_) => break,
                            }
                        }
                        tx.flush().await.map_err(|_| ())?;
                        metrics::counter!("gateway_ws_frames_sent_total").increment(sent);
                        // From the event reaching the gateway to its bytes leaving for this client.
                        metrics::histogram!("gateway_fanout_seconds").record(oldest.elapsed().as_secs_f64());
                    }
                    Err(RecvError::Lagged(_)) => lagged(&s, event_id, &mut tx).await?,
                    Err(RecvError::Closed) => return Err(()),
                },
                incoming = rx.next() => match incoming {
                    Some(Ok(Message::Text(text))) => {
                        if let Ok(msg) = serde_json::from_str::<ClientMessage>(&text) {
                            if msg.kind == "resnapshot" {
                                metrics::counter!("gateway_ws_resnapshots_total").increment(1);
                                let frame = s.snapshots.frame(event_id, msg.section.as_deref()).await.map_err(|_| ())?;
                                tx.send(Message::Text(frame.into())).await.map_err(|_| ())?;
                            }
                        }
                    }
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => return Ok(()),
                    Some(Ok(_)) => {}
                },
            }
        }
    }
    .await;
    let _ = result;
    metrics::gauge!("gateway_ws_connections").decrement(1.0);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        config::Config,
        tokens::{now_secs, testing::Keys},
    };
    use axum::{extract::Request, routing::any};
    use futures::SinkExt;
    use serde_json::{json, Value};
    use std::path::PathBuf;
    use tokio_tungstenite::tungstenite;

    /// Records what reached the "services": path, X-User-Id, cookie.
    async fn upstream() -> (String, Arc<std::sync::Mutex<Vec<Value>>>) {
        let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
        let log = seen.clone();
        let app = Router::new()
            .route(
                "/events/{e}",
                get(|| async { Json(json!({"eventId": 42, "sections": [{"section": "A"}, {"section": "B"}]})) }),
            )
            .route(
                "/sections/{e}/{s}/snapshot",
                get(|Path((e, s)): Path<(i64, String)>| async move {
                    Json(json!({"eventId": e, "section": s, "epoch": "ep", "seq": 7, "sold": [], "held": [1]}))
                }),
            )
            .fallback(any(move |req: Request| {
                let log = log.clone();
                async move {
                    let h = req.headers();
                    log.lock().unwrap().push(json!({
                        "path": req.uri().path(),
                        "user": h.get("x-user-id").and_then(|v| v.to_str().ok()),
                        "cookie": h.get("cookie").and_then(|v| v.to_str().ok()),
                        "idem": h.get("idempotency-key").and_then(|v| v.to_str().ok()),
                    }));
                    ([("set-cookie", "surge_session=abc; HttpOnly")], "{}")
                }
            }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (format!("http://{addr}"), seen)
    }

    async fn gateway(keys: &Keys, up: &str, ip_rate: u32) -> (String, Arc<AppState>) {
        let config = Config {
            port: 0,
            keys_dir: PathBuf::from(keys.dir.path()),
            admission_url: up.into(),
            inventory_url: up.into(),
            order_url: up.into(),
            frontend_url: Some(up.into()),
            kafka_bootstrap: None,
            ip_rate,
            user_rate: 1000,
            ws_connect_rate: 1000,
            trust_forwarded: false,
            snapshot_cache: Duration::from_millis(250),
        };
        let http = reqwest::Client::new();
        let state = Arc::new(AppState {
            verifier: Arc::new(keys.verifier()),
            limits: Limits::new(config.ip_rate, config.user_rate, config.ws_connect_rate),
            hub: Arc::new(Hub::default()),
            snapshots: Snapshots::new(http.clone(), up.into(), up.into(), config.snapshot_cache),
            http,
            metrics: metrics_exporter_prometheus::PrometheusBuilder::new()
                .build_recorder()
                .handle(),
            config,
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let app = router(state.clone()).into_make_service_with_connect_info::<SocketAddr>();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (format!("127.0.0.1:{}", addr.port()), state)
    }

    fn admission(keys: &Keys, sub: &str, event: i64) -> String {
        keys.sign(
            TokenType::Admission,
            json!({"sub": sub, "eventId": event, "exp": now_secs() + 600, "jti": sub}),
        )
    }

    #[tokio::test]
    async fn holds_need_an_admission_token_for_that_event_and_identity_comes_from_it() {
        let keys = Keys::generate();
        let (up, seen) = upstream().await;
        let (gw, _) = gateway(&keys, &up, 1000).await;
        let c = reqwest::Client::new();
        let url = format!("http://{gw}/api/holds");
        let body = json!({"eventId": 42, "section": "A", "seatIds": [1]});

        let none = c.post(&url).json(&body).send().await.unwrap();
        assert_eq!(none.status(), 401);

        let session = keys.sign(
            TokenType::Session,
            json!({"sub": "alice", "exp": now_secs() + 60, "jti": "s"}),
        );
        let wrong_type = c
            .post(&url)
            .bearer_auth(&session)
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(wrong_type.status(), 401);

        let other_event = c
            .post(&url)
            .bearer_auth(admission(&keys, "alice", 7))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(other_event.status(), 403);

        // A client can't pick its identity: the gateway overwrites X-User-Id.
        let ok = c
            .post(&url)
            .bearer_auth(admission(&keys, "alice", 42))
            .header("x-user-id", "mallory")
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(ok.status(), 200);
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0]["path"], "/holds");
        assert_eq!(seen[0]["user"], "alice");
    }

    #[tokio::test]
    async fn checkout_is_bound_to_the_holds_event_and_keeps_the_idempotency_key() {
        let keys = Keys::generate();
        let (up, seen) = upstream().await;
        let (gw, _) = gateway(&keys, &up, 1000).await;
        let c = reqwest::Client::new();
        let url = format!("http://{gw}/api/checkout");
        let token = admission(&keys, "bob", 42);

        let wrong = c
            .post(&url)
            .bearer_auth(&token)
            .json(&json!({"holdId": "7:A:x"}))
            .send()
            .await
            .unwrap();
        assert_eq!(wrong.status(), 403);
        let ok = c
            .post(&url)
            .bearer_auth(&token)
            .header("idempotency-key", "k1")
            .json(&json!({"holdId": "42:A:x"}))
            .send()
            .await
            .unwrap();
        assert_eq!(ok.status(), 200);
        assert_eq!(seen.lock().unwrap()[0]["idem"], "k1");
    }

    #[tokio::test]
    async fn queue_uses_the_session_cookie_and_session_passes_cookies_both_ways() {
        let keys = Keys::generate();
        let (up, seen) = upstream().await;
        let (gw, _) = gateway(&keys, &up, 1000).await;
        let c = reqwest::Client::new();
        let session = keys.sign(
            TokenType::Session,
            json!({"sub": "carol", "exp": now_secs() + 60, "jti": "s"}),
        );

        let joined = c
            .post(format!("http://{gw}/api/queue/42/join"))
            .header("cookie", format!("theme=dark; surge_session={session}"))
            .send()
            .await
            .unwrap();
        assert_eq!(joined.status(), 200);
        let anonymous = c
            .get(format!("http://{gw}/api/queue/42/position"))
            .send()
            .await
            .unwrap();
        assert_eq!(anonymous.status(), 401);

        let s = c
            .post(format!("http://{gw}/api/session"))
            .header("cookie", "surge_session=old")
            .send()
            .await
            .unwrap();
        assert_eq!(s.headers()["set-cookie"], "surge_session=abc; HttpOnly");

        let seen = seen.lock().unwrap();
        assert_eq!(seen[0]["path"], "/queue/42/join");
        assert_eq!(seen[0]["user"], "carol");
        // Cookies only travel to /session.
        assert_eq!(seen[0]["cookie"], Value::Null);
        assert_eq!(seen[1]["path"], "/session");
        assert_eq!(seen[1]["cookie"], "surge_session=old");
    }

    #[tokio::test]
    async fn other_paths_are_the_frontend_without_credentials() {
        let keys = Keys::generate();
        let (up, seen) = upstream().await;
        let (gw, _) = gateway(&keys, &up, 1000).await;
        let res = reqwest::Client::new()
            .get(format!("http://{gw}/checkout/42?x=1"))
            .header("rsc", "1")
            .header("cookie", "surge_session=secret")
            .header("x-user-id", "mallory")
            .send()
            .await
            .unwrap();
        assert_eq!(res.status(), 200);
        let seen = seen.lock().unwrap();
        assert_eq!(seen[0]["path"], "/checkout/42");
        assert_eq!(seen[0]["cookie"], Value::Null);
        assert_eq!(seen[0]["user"], Value::Null);
    }

    #[tokio::test]
    async fn per_ip_rate_limit_answers_429() {
        let keys = Keys::generate();
        let (up, _) = upstream().await;
        let (gw, _) = gateway(&keys, &up, 5).await;
        let c = reqwest::Client::new();
        let mut codes = Vec::new();
        for _ in 0..15 {
            codes.push(
                c.post(format!("http://{gw}/api/session"))
                    .send()
                    .await
                    .unwrap()
                    .status()
                    .as_u16(),
            );
        }
        assert_eq!(codes.iter().filter(|&&s| s == 200).count(), 10);
        assert_eq!(codes.iter().filter(|&&s| s == 429).count(), 5);
    }

    #[tokio::test]
    async fn websocket_gets_a_snapshot_then_live_seat_events() {
        let keys = Keys::generate();
        let (up, _) = upstream().await;
        let (gw, state) = gateway(&keys, &up, 1000).await;
        let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{gw}/ws/events/42"))
            .await
            .unwrap();

        let first = ws.next().await.unwrap().unwrap().into_text().unwrap();
        let snap: Value = serde_json::from_str(&first).unwrap();
        assert_eq!(snap["type"], "snapshot");
        assert_eq!(snap["sections"].as_array().unwrap().len(), 2);
        assert_eq!(snap["sections"][0]["seq"], 7);

        state
            .hub
            .publish(42, Hub::seat_frame(r#"{"eventId":42,"seq":8}"#));
        let live = ws.next().await.unwrap().unwrap().into_text().unwrap();
        assert_eq!(
            live.as_str(),
            r#"{"type":"seat","event":{"eventId":42,"seq":8}}"#
        );

        ws.send(tungstenite::Message::text(
            r#"{"type":"resnapshot","section":"B"}"#,
        ))
        .await
        .unwrap();
        let again: Value =
            serde_json::from_str(&ws.next().await.unwrap().unwrap().into_text().unwrap()).unwrap();
        assert_eq!(again["sections"].as_array().unwrap().len(), 1);
        assert_eq!(again["sections"][0]["section"], "B");
    }
}
