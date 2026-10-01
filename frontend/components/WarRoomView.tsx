"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import type { WarRoom } from "@/lib/warroom";
import { LineChart } from "./LineChart";
import { Icon, type IconName } from "./Icon";

const REFRESH_MS = 5000;
const count = (v: number | null) => (v == null ? "—" : Math.round(v).toLocaleString());

/** Live view of the sale: latency, throughput, saga states, invariants, chaos windows. */
export function WarRoomView() {
  const [data, setData] = useState<WarRoom | null>(null);
  const [minutes, setMinutes] = useState(10);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let stop = false;
    const load = async () => {
      try {
        const res = await fetch(`/x/metrics?minutes=${minutes}`, { cache: "no-store" });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const body = (await res.json()) as WarRoom;
        if (!stop) {
          setData(body);
          setError(null);
        }
      } catch (e) {
        if (!stop) setError((e as Error).message);
      }
    };
    load();
    const t = setInterval(load, REFRESH_MS);
    return () => {
      stop = true;
      clearInterval(t);
    };
  }, [minutes]);

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <div className="eyebrow">Operations</div>
          <h1>War room</h1>
          <p>
            The sale as the system sees it, refreshed every 5 s. Shaded bands mark chaos actions; the number that
            matters is the one top left.
          </p>
        </div>
        <div className="segmented" role="group" aria-label="Time range">
          {[5, 10, 30].map((m) => (
            <button key={m} aria-pressed={m === minutes} onClick={() => setMinutes(m)}>
              {m} min
            </button>
          ))}
        </div>
      </div>
      {error && (
        <div className="alert" role="alert">
          <Icon name="alert" />
          Metrics unavailable: {error}
        </div>
      )}
      {data && data.errors.length > 0 && (
        <p className="muted" style={{ fontSize: 13 }}>
          Partial data: {data.errors.length} quer{data.errors.length === 1 ? "y" : "ies"} failed.
        </p>
      )}
      {data && (
        <>
          <div className="grid-2">
            <Violations data={data} />
            <Saga saga={data.saga} />
          </div>
          <div className="kpis">
            <Kpi icon="radio" label="Live WebSocket clients" value={count(data.kpis.wsConnections)} hint="Browsers watching a seat map" />
            <Kpi icon="users" label="In the waiting room" value={count(data.kpis.queueDepth)} hint="Buyers queued for admission" />
            <Kpi icon="ticket" label="Orders confirmed" value={count(data.kpis.confirmed)} hint="Paid and ticketed" />
            <Kpi icon="clock" label="Holds expired while reserved" value={count(data.kpis.expiredWhileReserved)} hint="Postgres still decided these seats" />
          </div>
          <div className="charts">
            <LineChart
              title="Checkout latency (ms)"
              unit="ms"
              start={data.start}
              end={data.end}
              chaos={data.chaos}
              series={[
                { key: "p50", label: "p50", color: "var(--series-1)", points: data.latencyMs.checkoutP50 },
                { key: "p99", label: "p99", color: "var(--series-2)", points: data.latencyMs.checkoutP99 },
              ]}
            />
            <LineChart
              title="Seat update fan-out p99 (ms)"
              unit="ms"
              start={data.start}
              end={data.end}
              chaos={data.chaos}
              target={{ value: 200, label: "200 ms goal" }}
              series={[{ key: "fanout", label: "fan-out p99", color: "var(--series-1)", points: data.latencyMs.fanoutP99 }]}
            />
            <LineChart
              title="Throughput (per second)"
              unit="/s"
              start={data.start}
              end={data.end}
              chaos={data.chaos}
              format={(v) => (v < 10 ? v.toFixed(1) : v.toFixed(0))}
              series={[
                { key: "holds", label: "holds", color: "var(--series-1)", points: data.perSecond.holds },
                { key: "orders", label: "orders", color: "var(--series-2)", points: data.perSecond.orders },
                { key: "rejected", label: "rejected", color: "var(--series-3)", points: data.perSecond.rejected },
              ]}
            />
            <ChaosLog data={data} />
          </div>
        </>
      )}
      {!data && !error && (
        <div className="grid-2" aria-busy>
          <div className="skeleton shimmer" style={{ height: 300 }} />
          <div className="skeleton shimmer" style={{ height: 300 }} />
        </div>
      )}
    </div>
  );
}

function Kpi({ icon, label, value, hint }: { icon: IconName; label: string; value: string; hint: string }) {
  return (
    <div className="card kpi">
      <div className="head">
        <span className="icon">
          <Icon name={icon} />
        </span>
        <span className="label">{label}</span>
      </div>
      <span className="value num">{value}</span>
      <span className="hint">{hint}</span>
    </div>
  );
}

/** The headline: rows violating any invariant (oversells, double tickets, ...). */
function Violations({ data }: { data: WarRoom }) {
  const rows = Object.entries(data.violations ?? {}).sort(([a], [b]) => a.localeCompare(b));
  const total = rows.reduce((n, [, v]) => n + v, 0);
  const known = data.violations != null;
  const ok = known && total === 0;
  return (
    <section className={`card stack verdict ${known ? (ok ? "ok" : "bad") : ""}`} data-testid="violations" data-total={known ? total : ""}>
      <div className={`hero ${known ? (ok ? "ok" : "bad") : ""}`}>
        <span className="value num">{known ? total : "—"}</span>
        <div>
          <h2 className="row" style={{ gap: 8 }}>
            <StatusIcon ok={ok} unknown={!known} />
            {!known ? "Reconciler hasn’t run yet" : ok ? "Invariants hold" : "Invariant violations"}
          </h2>
          <p className="secondary" style={{ margin: "4px 0 0", fontSize: 13 }}>
            Oversells, duplicate tickets, orphaned holds and payments, checked by the reconciler
            {data.reconcilerAgeS != null && ` ${Math.round(data.reconcilerAgeS)} s ago`}.
          </p>
        </div>
      </div>
      {rows.length > 0 && (
        <table className="data">
          <tbody>
            {rows.map(([name, v]) => (
              <tr key={name}>
                <td>
                  <span className="mono">{name}</span>
                </td>
                <td className={`num ${v === 0 ? "inv-ok" : "inv-bad"}`}>
                  {v === 0 ? (
                    <span className="row" style={{ gap: 4, justifyContent: "flex-end" }}>
                      <Icon name="check" size={14} /> 0
                    </span>
                  ) : (
                    v
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function StatusIcon({ ok, unknown }: { ok: boolean; unknown: boolean }) {
  if (unknown) return null;
  return ok ? (
    <svg width={18} height={18} viewBox="0 0 18 18" aria-label="OK" role="img">
      <circle cx={9} cy={9} r={9} fill="var(--good)" />
      <path d="M5 9.5l2.5 2.5L13 6.5" stroke="#fff" strokeWidth={2} fill="none" strokeLinecap="round" />
    </svg>
  ) : (
    <svg width={18} height={18} viewBox="0 0 18 18" aria-label="Critical" role="img">
      <path d="M9 1l8 15H1z" fill="var(--critical)" />
      <path d="M9 6.5v4.5M9 13v.5" stroke="#fff" strokeWidth={2} strokeLinecap="round" />
    </svg>
  );
}

/** Orders by saga state: one stacked bar, every segment labeled with its count. */
function Saga({ saga }: { saga: WarRoom["saga"] }) {
  const parts = [
    { key: "confirmed", label: "Confirmed", color: "var(--series-1)", ink: "#fff", v: saga.confirmed },
    { key: "inFlight", label: "In flight", color: "var(--series-2)", ink: "#fff", v: saga.inFlight },
    { key: "compensating", label: "Compensating", color: "var(--series-3)", ink: "#0b0b0b", v: saga.compensating },
    { key: "cancelled", label: "Cancelled", color: "var(--series-4)", ink: "#0b0b0b", v: saga.cancelled },
  ];
  const total = parts.reduce((n, p) => n + p.v, 0);
  const [hover, setHover] = useState<string | null>(null);
  let acc = 0;
  return (
    <section className="card stack">
      <div className="card-head">
        <h2>Orders by saga state</h2>
        <span className="pill num">{total.toLocaleString()} total</span>
      </div>
      <svg viewBox="0 0 400 36" width="100%" role="img" aria-label="Orders by saga state" style={{ display: "block" }}>
        {total === 0 ? (
          <rect x={0} y={4} width={400} height={28} rx={4} fill="var(--surface-2)" />
        ) : (
          parts
            .filter((p) => p.v > 0)
            .map((p) => {
              const w = (p.v / total) * 400;
              const x = acc;
              acc += w;
              return (
                <g key={p.key} onPointerEnter={() => setHover(p.key)} onPointerLeave={() => setHover(null)}>
                  <rect x={x} y={4} width={Math.max(0, w - 2)} height={28} rx={4} fill={p.color} opacity={hover && hover !== p.key ? 0.5 : 1}>
                    <title>{`${p.label}: ${p.v.toLocaleString()} (${((p.v / total) * 100).toFixed(1)}%)`}</title>
                  </rect>
                  {w > 36 && (
                    <text x={x + 8} y={22} fontSize={12} fill={p.ink} fontWeight={600} className="num" pointerEvents="none">
                      {p.v.toLocaleString()}
                    </text>
                  )}
                </g>
              );
            })
        )}
      </svg>
      <table className="data" aria-label="Orders by saga state">
        <tbody>
          {parts.map((p) => (
            <tr key={p.key} onPointerEnter={() => setHover(p.key)} onPointerLeave={() => setHover(null)}>
              <td>
                <span className="row" style={{ gap: 8 }}>
                  <span className="status-dot" style={{ background: p.color, borderRadius: 3 }} />
                  {p.label}
                </span>
              </td>
              <td className="num secondary" style={{ textAlign: "right" }}>
                {total ? `${((p.v / total) * 100).toFixed(p.v / total < 0.1 && p.v > 0 ? 1 : 0)}%` : "—"}
              </td>
              <td className="num" style={{ width: 72 }}>
                <strong>{p.v.toLocaleString()}</strong>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small" style={{ marginTop: "auto" }}>
        In flight and compensating orders settle on their own; the reconciler flags any that get stuck.
      </p>
    </section>
  );
}

function ChaosLog({ data }: { data: WarRoom }) {
  const t = (s: number) => new Date(s * 1000).toLocaleTimeString();
  return (
    <section className="card stack">
      <h2>Chaos in this window</h2>
      {data.chaos.length === 0 ? (
        <div className="empty" style={{ flex: 1, justifyContent: "center" }}>
          <Icon name="flame" size={24} />
          <span>
            Nothing broken yet. <Link href="/chaos">Break something</Link> and watch the invariants hold.
          </span>
        </div>
      ) : (
        <table className="data">
          <tbody>
            {[...data.chaos].reverse().map((c, i) => (
              <tr key={i}>
                <td>{c.name}</td>
                <td className="num secondary">{t(c.startedAt)}</td>
                <td className="num">
                  {c.endedAt ? (
                    <span className="secondary">{t(c.endedAt)}</span>
                  ) : (
                    <span className="pill bad">
                      <span className="status-dot pulse" style={{ background: "var(--critical)", color: "var(--critical)" }} />
                      active
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
