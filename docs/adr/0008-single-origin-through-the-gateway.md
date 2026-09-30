# 0008 — The gateway serves the frontend: one origin for pages, API and WebSocket

**Status:** Accepted

## Context

The frontend needs the API, the seat-map WebSocket and the HttpOnly session cookie.
Serving it from a separate origin brings CORS, `SameSite` cookie rules and a second
public entry point that bypasses the gateway's rate limits. The war room and chaos
panel also need Prometheus and the chaos controller, which should not be public.

## Decision

- The gateway's fallback route proxies every path it doesn't own to `FRONTEND_URL`.
  It drops `cookie`, `authorization`, `x-user-id`, `host` and hop-by-hop headers on the
  way in and hop-by-hop/length headers on the way out. The frontend never sees
  identity; the browser talks to `/api` and `/ws` directly with the cookie.
- Page requests pass the per-IP rate limit like any other request.
- The frontend's own server routes (`/x/metrics`, `/x/chaos/*`) reach Prometheus and
  the chaos controller on the internal network. `/x/chaos` passes only the
  controller's own routes.
- The frontend container publishes no port.

## Consequences

- No CORS, no cookie configuration, one TLS terminator in production.
- Every page load costs a hop through the gateway. Next.js static assets are small and
  cacheable, and the gateway is already on the path for the API.
- The chaos panel is reachable by anyone who can reach the gateway. That's fine for the
  development stack it's meant for; a public deployment must drop the `chaos` service
  or put `/x/chaos` behind auth.
