//! Tracing: every request gets a server span that continues an incoming `traceparent`
//! (or starts a trace), and every upstream call carries the context on, so one trace
//! follows a checkout from the gateway through Order, Inventory, Kafka and Payment.
//! Exported over OTLP/HTTP when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.

use std::collections::HashMap;

use axum::{
    extract::{MatchedPath, Request},
    http::{HeaderMap, HeaderValue},
    middleware::Next,
    response::Response,
};
use opentelemetry::{
    global,
    propagation::Extractor,
    trace::{TraceContextExt, TraceId, TracerProvider as _},
};
use opentelemetry_otlp::{SpanExporter, WithExportConfig};
use opentelemetry_sdk::{propagation::TraceContextPropagator, trace::SdkTracerProvider, Resource};
use tracing::Instrument;
use tracing_opentelemetry::OpenTelemetrySpanExt;

/// Builds the OTLP tracer, or `None` when no endpoint is configured.
pub fn tracer_provider() -> Option<SdkTracerProvider> {
    let endpoint = std::env::var("OTEL_EXPORTER_OTLP_ENDPOINT")
        .ok()
        .filter(|e| !e.is_empty())?;
    let exporter = SpanExporter::builder()
        .with_http()
        .with_endpoint(format!("{}/v1/traces", endpoint.trim_end_matches('/')))
        .build()
        .map_err(|e| eprintln!("OTLP exporter disabled: {e}"))
        .ok()?;
    let service = std::env::var("OTEL_SERVICE_NAME").unwrap_or_else(|_| "gateway".into());
    let provider = SdkTracerProvider::builder()
        .with_batch_exporter(exporter)
        .with_resource(Resource::builder().with_service_name(service).build())
        .build();
    global::set_text_map_propagator(TraceContextPropagator::new());
    global::set_tracer_provider(provider.clone());
    Some(provider)
}

/// The `tracing` layer that turns spans into OpenTelemetry spans.
pub fn layer<S>(provider: &SdkTracerProvider) -> impl tracing_subscriber::Layer<S>
where
    S: tracing::Subscriber + for<'a> tracing_subscriber::registry::LookupSpan<'a>,
{
    tracing_opentelemetry::layer().with_tracer(provider.tracer("surge-gateway"))
}

struct HeaderExtractor<'a>(&'a HeaderMap);

impl Extractor for HeaderExtractor<'_> {
    fn get(&self, key: &str) -> Option<&str> {
        self.0.get(key).and_then(|v| v.to_str().ok())
    }

    fn keys(&self) -> Vec<&str> {
        self.0.keys().map(|k| k.as_str()).collect()
    }
}

/// W3C trace headers for an upstream call made inside the current span.
pub fn outgoing_headers() -> HashMap<String, String> {
    let cx = tracing::Span::current().context();
    let mut carrier = HashMap::new();
    global::get_text_map_propagator(|p| p.inject_context(&cx, &mut carrier));
    carrier
}

/// Middleware: one server span per request; the trace id comes back in `x-trace-id`
/// so a client (or a test) can find the trace.
pub async fn trace_requests(req: Request, next: Next) -> Response {
    let route = req
        .extensions()
        .get::<MatchedPath>()
        .map(|p| p.as_str().to_owned())
        .unwrap_or_else(|| req.uri().path().to_owned());
    if route == "/health" || route == "/metrics" {
        return next.run(req).await;
    }
    let method = req.method().clone();
    let parent = global::get_text_map_propagator(|p| p.extract(&HeaderExtractor(req.headers())));
    let span = tracing::info_span!(
        "request",
        otel.name = %format!("{method} {route}"),
        otel.kind = "server",
        http.request.method = %method,
        http.route = %route,
        http.response.status_code = tracing::field::Empty,
    );
    let _ = span.set_parent(parent);
    let trace_id = span.context().span().span_context().trace_id();

    let mut res = next.run(req).instrument(span.clone()).await;
    span.record("http.response.status_code", res.status().as_u16());
    if trace_id != TraceId::INVALID {
        if let Ok(v) = HeaderValue::from_str(&trace_id.to_string()) {
            res.headers_mut().insert("x-trace-id", v);
        }
    }
    res
}
