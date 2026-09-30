"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

interface Action {
  id: string;
  name: string;
  description: string;
  defaultDurationS: number | null;
  active: boolean;
}

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
      const res = await fetch(`/x/chaos/${path}`, { method, headers: { "Content-Type": "application/json" }, body: method === "POST" ? "{}" : undefined });
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

  return (
    <div className="stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div>
          <h1>Chaos</h1>
          <p className="secondary" style={{ margin: "4px 0 0" }}>
            Each action undoes itself after its duration. Keep the <Link href="/war-room">war room</Link> open in
            another tab.
          </p>
        </div>
        <button className="ghost" disabled={busy !== null} onClick={() => act("reset", "POST", "reset")}>
          Undo everything
        </button>
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {!actions && !error && <p className="muted">Loading…</p>}
      <div className="grid-2">
        {actions?.map((a) => (
          <section key={a.id} className="card stack" data-action={a.id} data-active={a.active}>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <h2>{a.name}</h2>
              {a.active && (
                <span className="pill">
                  <span className="status-dot" style={{ background: "var(--critical)" }} />
                  Active
                </span>
              )}
            </div>
            <p className="secondary" style={{ margin: 0 }}>
              {a.description}
            </p>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <span className="muted" style={{ fontSize: 13 }}>
                {a.defaultDurationS ? `Undoes itself after ${a.defaultDurationS} s` : "One-shot"}
              </span>
              {a.active ? (
                <button className="ghost" disabled={busy === a.id} onClick={() => act(a.id, "DELETE")}>
                  Stop
                </button>
              ) : (
                <button className="danger" disabled={busy === a.id} onClick={() => act(a.id, "POST")}>
                  {busy === a.id ? "Running…" : "Run"}
                </button>
              )}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
