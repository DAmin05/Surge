"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Icon, type IconName } from "./Icon";

interface Action {
  id: string;
  name: string;
  description: string;
  defaultDurationS: number | null;
  active: boolean;
}

/** What each action breaks, so the panel reads by blast radius rather than as a flat list. */
const GROUPS: { key: string; title: string; icon: IconName; ids: string[] }[] = [
  { key: "redis", title: "Seat holds (Redis Cluster)", icon: "database", ids: ["kill-redis-primary"] },
  { key: "services", title: "Services", icon: "server", ids: ["kill-inventory", "pause-outbox-relay"] },
  { key: "payment", title: "Payments", icon: "card", ids: ["payment-failures", "payment-timeouts", "duplicate-callbacks"] },
  { key: "network", title: "Order ↔ Postgres network", icon: "network", ids: ["partition-order-postgres", "slow-order-postgres"] },
  { key: "stream", title: "Event stream (Redpanda)", icon: "stream", ids: ["restart-redpanda"] },
];

/** Break things on purpose; watch the war room keep the invariants at zero. */
export function ChaosPanel() {
  const [actions, setActions] = useState<Action[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/x/chaos/actions", { cache: "no-store" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setActions(await res.json());
    } catch (e) {
      setError(`Chaos controller unavailable (${(e as Error).message}).`);
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [load]);

  const act = async (id: string, method: "POST" | "DELETE", path = `actions/${id}`) => {
    setBusy(id);
    setError(null);
    try {
      const res = await fetch(`/x/chaos/${path}`, {
        method,
        headers: { "Content-Type": "application/json" },
        body: method === "POST" ? "{}" : undefined,
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.detail ?? body.error ?? `HTTP ${res.status}`);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
      load();
    }
  };

  const active = actions?.filter((a) => a.active) ?? [];
  const known = new Set(GROUPS.flatMap((g) => g.ids));
  const groups = [
    ...GROUPS,
    { key: "other", title: "Other", icon: "flame" as IconName, ids: (actions ?? []).map((a) => a.id).filter((id) => !known.has(id)) },
  ]
    .map((g) => ({ ...g, actions: g.ids.map((id) => actions?.find((a) => a.id === id)).filter((a): a is Action => !!a) }))
    .filter((g) => g.actions.length > 0);

  return (
    <div className="stack-lg">
      <div className="page-head">
        <div>
          <div className="eyebrow" style={{ color: "var(--critical)" }}>
            Operations
          </div>
          <h1>Chaos panel</h1>
          <p>
            Break the system mid-sale. Every action undoes itself after its duration. Keep the{" "}
            <Link href="/war-room">war room</Link> open in another tab: the violations count should stay at zero.
          </p>
        </div>
        <button className="ghost" disabled={busy !== null || active.length === 0} onClick={() => act("reset", "POST", "reset")}>
          <Icon name="undo" /> Undo everything
        </button>
      </div>

      <div className="callout" role="status">
        <span className="row" style={{ gap: 10 }}>
          {active.length > 0 ? (
            <>
              <span className="status-dot pulse" style={{ background: "var(--critical)", color: "var(--critical)" }} />
              <strong style={{ color: "var(--ink)" }}>
                {active.length} fault{active.length > 1 ? "s" : ""} active:
              </strong>
              {active.map((a) => a.name).join(", ")}
            </>
          ) : (
            <>
              <span className="status-dot" style={{ background: "var(--good)" }} />
              All systems normal. Nothing is broken right now.
            </>
          )}
        </span>
        <Link href="/war-room" className="row" style={{ gap: 6, fontWeight: 600 }}>
          Open war room <Icon name="arrowRight" size={14} />
        </Link>
      </div>

      {error && (
        <div className="alert" role="alert">
          <Icon name="alert" />
          {error}
        </div>
      )}
      {!actions && !error && (
        <div className="grid-3" aria-busy>
          {[0, 1, 2].map((i) => (
            <div key={i} className="skeleton shimmer" style={{ height: 180 }} />
          ))}
        </div>
      )}

      {groups.map((g) => (
        <section key={g.key} className="chaos-group" aria-label={g.title}>
          <div className="gh">
            <span className="gi">
              <Icon name={g.icon} size={15} />
            </span>
            <h3>{g.title}</h3>
          </div>
          <div className="grid-3">
            {g.actions.map((a) => (
              <section key={a.id} className="card chaos-card" data-action={a.id} data-active={a.active}>
                <div className="card-head">
                  <h2>{a.name}</h2>
                  {a.active && (
                    <span className="pill bad">
                      <span className="status-dot pulse" style={{ background: "var(--critical)", color: "var(--critical)" }} />
                      Active
                    </span>
                  )}
                </div>
                <p className="desc">{a.description}</p>
                <div className="foot">
                  <span className="muted small row" style={{ gap: 6 }}>
                    <Icon name="clock" size={14} />
                    {a.defaultDurationS ? `Undoes itself after ${a.defaultDurationS} s` : "One-shot"}
                  </span>
                  {a.active ? (
                    <button className="ghost" disabled={busy === a.id} onClick={() => act(a.id, "DELETE")}>
                      <Icon name="stop" size={14} /> Stop
                    </button>
                  ) : (
                    <button className="danger" disabled={busy === a.id} onClick={() => act(a.id, "POST")}>
                      {busy === a.id ? <Icon name="spinner" size={14} className="spin" /> : <Icon name="play" size={14} />}
                      {busy === a.id ? "Running…" : "Run"}
                    </button>
                  )}
                </div>
              </section>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
