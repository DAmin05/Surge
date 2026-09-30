import { SAGA_GROUPS, type ChaosSpan, type Point, type WarRoom } from "@/lib/warroom";

export const dynamic = "force-dynamic";

const PROMETHEUS = process.env.PROMETHEUS_URL ?? "http://prometheus:9090";
const CHAOS = process.env.CHAOS_URL ?? "http://chaos:8002";
const STEP = 5;

type Sample = { metric: Record<string, string>; value?: [number, string]; values?: [number, string][] };

async function prom(path: string, params: Record<string, string>): Promise<Sample[]> {
  const res = await fetch(`${PROMETHEUS}/api/v1/${path}?${new URLSearchParams(params)}`, {
    cache: "no-store",
    signal: AbortSignal.timeout(4000),
  });
  if (!res.ok) throw new Error(`prometheus ${res.status}`);
  const body = (await res.json()) as { data: { result: Sample[] } };
  return body.data.result;
}

const num = (s: string) => {
  const v = Number(s);
  return Number.isFinite(v) ? v : null;
};

export async function GET(req: Request) {
  const minutes = Math.min(60, Math.max(1, Number(new URL(req.url).searchParams.get("minutes") ?? 10)));
  const end = Math.floor(Date.now() / 1000);
  const start = end - minutes * 60;
  const errors: string[] = [];

  // One series per query, aligned to the same [start, end, step] grid; gaps stay null.
  const range = async (query: string, scale = 1): Promise<Point[]> => {
    try {
      const [first] = await prom("query_range", { query, start: `${start}`, end: `${end}`, step: `${STEP}` });
      const got = new Map((first?.values ?? []).map(([t, v]) => [Math.round(t), num(v)]));
      const out: Point[] = [];
      for (let t = start; t <= end; t += STEP) {
        const v = got.get(t);
        out.push([t, v == null ? null : v * scale]);
      }
      return out;
    } catch (e) {
      errors.push(`${query}: ${(e as Error).message}`);
      return [];
    }
  };
  const instant = async (query: string): Promise<Sample[]> => {
    try {
      return await prom("query", { query });
    } catch (e) {
      errors.push(`${query}: ${(e as Error).message}`);
      return [];
    }
  };
  const scalar = async (query: string) => {
    const [s] = await instant(query);
    return s?.value ? num(s.value[1]) : null;
  };
  const quantile = (q: number, metric: string, filter = "") =>
    `histogram_quantile(${q}, sum by (le) (rate(${metric}_bucket${filter}[30s])))`;

  const chaos = fetch(`${CHAOS}/events`, { cache: "no-store", signal: AbortSignal.timeout(3000) })
    .then((r) => (r.ok ? (r.json() as Promise<{ action: string; name: string; started_at: number; ended_at: number | null }[]>) : []))
    .then((events) =>
      events
        .filter((e) => (e.ended_at ?? end) >= start)
        .map<ChaosSpan>((e) => ({ action: e.action, name: e.name, startedAt: e.started_at, endedAt: e.ended_at })),
    )
    .catch(() => {
      errors.push("chaos controller unreachable");
      return [] as ChaosSpan[];
    });

  const [p50, p99, fanout, holds, orders, rejected, ws, queue, confirmed, expired, states, violations, lastRun, spans] =
    await Promise.all([
      range(quantile(0.5, "gateway_upstream_seconds", `{route="checkout"}`), 1000),
      range(quantile(0.99, "gateway_upstream_seconds", `{route="checkout"}`), 1000),
      range(quantile(0.99, "gateway_fanout_seconds"), 1000),
      range(`sum(rate(holds_total{result="held"}[30s]))`),
      range(`sum(rate(claims_total{result="claimed"}[30s]))`),
      range(`sum(rate(holds_total{result!="held"}[30s]))`),
      scalar("sum(gateway_ws_connections)"),
      scalar("sum(queue_depth)"),
      scalar(`sum(orders_by_state{state="CONFIRMED"})`),
      scalar("sum(holds_expired_while_reserved_total) or vector(0)"),
      instant("sum by (state) (orders_by_state)"),
      instant("reconciler_invariant_violations"),
      scalar("time() - max(reconciler_last_run_timestamp_seconds)"),
      chaos,
    ]);

  const saga: WarRoom["saga"] = { confirmed: 0, inFlight: 0, compensating: 0, cancelled: 0 };
  for (const s of states) {
    const group = SAGA_GROUPS[s.metric.state];
    if (group && s.value) saga[group] += num(s.value[1]) ?? 0;
  }
  const byInvariant: Record<string, number> = {};
  for (const s of violations) if (s.value) byInvariant[s.metric.invariant] = num(s.value[1]) ?? 0;

  const body: WarRoom = {
    start,
    end,
    latencyMs: { checkoutP50: p50, checkoutP99: p99, fanoutP99: fanout },
    perSecond: { holds, orders, rejected },
    kpis: { wsConnections: ws, queueDepth: queue, confirmed, expiredWhileReserved: expired },
    saga,
    violations: violations.length ? byInvariant : null,
    reconcilerAgeS: lastRun,
    chaos: spans,
    errors,
  };
  return Response.json(body, { headers: { "Cache-Control": "no-store" } });
}
