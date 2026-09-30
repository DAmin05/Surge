# frontend

Next.js + TypeScript: buyer flow (`/events/{id}`), war room (`/war-room`) and chaos
panel (`/chaos`). Served through the gateway, so pages, API and WebSocket share one
origin ([ADR 0008](../docs/adr/0008-single-origin-through-the-gateway.md)).

```bash
npm ci
npm run typecheck && npm test   # seat-map client unit tests (vitest)
npm run build
npx playwright test             # e2e against a running stack (make up); seeds its own event
```

Set `CHROMIUM_PATH` to use a preinstalled Chromium instead of `npx playwright install`.
