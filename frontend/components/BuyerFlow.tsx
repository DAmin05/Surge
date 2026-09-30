"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, money, type Catalog, type Hold, type OrderView } from "@/lib/api";
import { useSeatMap } from "@/lib/useSeatMap";
import { SeatMapView } from "./SeatMapView";
import { Countdown } from "./Countdown";

const MAX_SEATS = 4;
const TERMINAL = new Set(["CONFIRMED", "CANCELLED", "FAILED"]);

type Stage =
  | { kind: "queue"; position?: number; wait?: number }
  | { kind: "pick" }
  | { kind: "held"; hold: Hold; section: string; seats: number[] }
  | { kind: "paying"; hold: Hold; section: string; seats: number[] }
  | { kind: "order"; order: OrderView };

const MESSAGES: Record<string, string> = {
  SEAT_TAKEN: "Someone got one of those seats first. Pick again.",
  SEAT_SOLD: "One of those seats has just sold. Pick again.",
  USER_LIMIT: "You already hold or bought the maximum of four seats for this event.",
  HOLD_EXPIRED: "Your hold ran out and the seats went back on sale.",
  REBUILDING: "Seat availability is being rebuilt. Try again in a moment.",
  NOT_YOUR_HOLD: "That hold belongs to someone else.",
};
const message = (e: unknown) =>
  e instanceof ApiError ? (MESSAGES[e.code] ?? `Something went wrong (${e.code}).`) : "Network error. Try again.";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Waiting room → pick seats → hold (with countdown) → checkout → order outcome.
 * The seat map is live the whole time, including while waiting in the queue.
 */
export function BuyerFlow({ eventId }: { eventId: number }) {
  const { map, version, connected } = useSeatMap(eventId);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [section, setSection] = useState("");
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [stage, setStage] = useState<Stage>({ kind: "queue" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const token = useRef<string | null>(null);

  // Session, catalog and the waiting room.
  useEffect(() => {
    let stop = false;
    (async () => {
      try {
        await api.session();
        const cat = await api.catalog(eventId);
        if (stop) return;
        setCatalog(cat);
        setSection(cat.sections[0]?.section ?? "");
        let q = await api.join(eventId);
        while (!stop && q.status !== "ADMITTED") {
          if (q.status === "WAITING") setStage({ kind: "queue", position: q.position, wait: q.estimatedWaitSeconds });
          await sleep(1000);
          q = await api.position(eventId);
          if (q.status === "NOT_IN_QUEUE") q = await api.join(eventId);
        }
        if (stop || q.status !== "ADMITTED") return;
        token.current = q.token;
        setStage({ kind: "pick" });
      } catch (e) {
        if (!stop) setError(message(e));
      }
    })();
    return () => {
      stop = true;
    };
  }, [eventId]);

  const onSection = (s: string) => {
    setSection(s);
    setSelected(new Set());
  };
  const onToggle = (seatId: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(seatId)) next.delete(seatId);
      else if (next.size < MAX_SEATS) next.add(seatId);
      return next;
    });

  // Selected seats someone else just took drop out of the selection.
  useEffect(() => {
    if (selected.size === 0) return;
    const still = [...selected].filter((id) => map.status(section, id) === "available");
    if (still.length !== selected.size) setSelected(new Set(still));
  }, [version]); // eslint-disable-line react-hooks/exhaustive-deps

  const hold = async () => {
    if (!token.current) return;
    setBusy(true);
    setError(null);
    const seats = [...selected].sort((a, b) => a - b);
    try {
      let h: Hold | null = null;
      // REBUILDING (503): the section's sold set is being restored; it's brief.
      for (let attempt = 0; h === null; attempt++) {
        try {
          h = await api.hold(token.current, eventId, section, seats);
        } catch (e) {
          if (!(e instanceof ApiError && e.code === "REBUILDING") || attempt >= 10) throw e;
          await sleep(500);
        }
      }
      setSelected(new Set());
      setStage({ kind: "held", hold: h, section, seats });
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };

  const release = async () => {
    if (stage.kind !== "held" || !token.current) return;
    setBusy(true);
    await api.release(token.current, stage.hold.holdId).catch(() => undefined);
    setBusy(false);
    setStage({ kind: "pick" });
  };

  const expired = useCallback(() => {
    setStage((s) => (s.kind === "held" ? { kind: "pick" } : s));
    setError(MESSAGES.HOLD_EXPIRED);
  }, []);

  const pay = async () => {
    if (stage.kind !== "held" || !token.current) return;
    const { hold: h } = stage;
    setStage({ ...stage, kind: "paying" });
    setError(null);
    // One key per hold: retries of a lost response replay the same order.
    const key = crypto.randomUUID();
    try {
      let res: { orderId: number } | null = null;
      for (let attempt = 0; res === null; attempt++) {
        try {
          res = await api.checkout(token.current, h.holdId, key);
        } catch (e) {
          const retryable =
            !(e instanceof ApiError) || e.status >= 500 || e.code === "REQUEST_IN_PROGRESS";
          if (!retryable || attempt >= 5) throw e;
          await sleep(500 * 2 ** attempt);
        }
      }
      let order = await api.order(res.orderId);
      while (!TERMINAL.has(order.state)) {
        await sleep(500);
        order = await api.order(res.orderId);
      }
      setStage({ kind: "order", order });
    } catch (e) {
      setError(message(e));
      setStage({ kind: "pick" });
    }
  };

  if (error && !catalog) return <p className="error">{error}</p>;
  if (!catalog) return <p className="muted">Loading…</p>;

  const held = stage.kind === "held" || stage.kind === "paying" ? stage : null;
  const mine = new Set(held?.seats ?? []);
  const price = catalog.sections.find((s) => s.section === section)?.priceCents ?? 0;

  return (
    <div className="stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div>
          <h1>{catalog.name}</h1>
          <p className="secondary" style={{ margin: "4px 0 0" }}>
            {new Date(catalog.startsAt).toLocaleString()}
          </p>
        </div>
        <span className="pill" data-testid="live" data-connected={connected}>
          <span className="status-dot" style={{ background: connected ? "var(--good)" : "var(--critical)" }} />
          {connected ? "Live" : "Reconnecting"}
        </span>
      </div>

      <section className="card stack" aria-live="polite" data-testid="stage" data-stage={stage.kind}>
        {stage.kind === "queue" && (
          <div>
            <h2>You’re in the waiting room</h2>
            <p className="secondary" style={{ marginBottom: 0 }}>
              {stage.position === undefined ? (
                "Joining…"
              ) : (
                <>
                  Position <span className="num">{stage.position.toLocaleString()}</span> · about{" "}
                  <span className="num">{stage.wait}</span>s. The map below is live; you can pick seats once
                  you’re in.
                </>
              )}
            </p>
          </div>
        )}
        {stage.kind === "pick" && (
          <div className="row" style={{ justifyContent: "space-between" }}>
            <div>
              <h2>Pick up to four seats in one section</h2>
              <p className="secondary" style={{ margin: "4px 0 0" }}>
                <span className="num">{selected.size}</span> selected · {money(price * selected.size)}
              </p>
            </div>
            <button data-testid="hold" disabled={busy || selected.size === 0} onClick={hold}>
              Hold seats
            </button>
          </div>
        )}
        {held && (
          <div className="row" style={{ justifyContent: "space-between" }}>
            <div>
              <h2>
                {held.seats.length} seat{held.seats.length > 1 ? "s" : ""} held in {held.section}
              </h2>
              <p className="secondary" style={{ margin: "4px 0 0" }}>
                {money((catalog.sections.find((s) => s.section === held.section)?.priceCents ?? 0) * held.seats.length)}{" "}
                · pay within{" "}
                {stage.kind === "held" ? <Countdown untilMs={held.hold.expiresAtMs} onExpire={expired} /> : "—"}
              </p>
            </div>
            <div className="row">
              <button className="ghost" disabled={busy || stage.kind === "paying"} onClick={release}>
                Release
              </button>
              <button data-testid="pay" disabled={busy || stage.kind === "paying"} onClick={pay}>
                {stage.kind === "paying" ? "Paying…" : "Pay"}
              </button>
            </div>
          </div>
        )}
        {stage.kind === "order" && <OrderResult order={stage.order} />}
        {error && (
          <p className="error" role="alert" data-testid="error" style={{ margin: 0 }}>
            {error}
          </p>
        )}
      </section>

      <section className="card">
        <SeatMapView
          sections={catalog.sections}
          active={held?.section ?? section}
          onSection={onSection}
          map={map}
          version={version}
          selected={selected}
          mine={mine}
          interactive={stage.kind === "pick" && !busy}
          onToggle={onToggle}
        />
      </section>
    </div>
  );
}

function OrderResult({ order }: { order: OrderView }) {
  if (order.state === "CONFIRMED") {
    return (
      <div data-testid="order" data-state={order.state}>
        <h2>You’re going. Order #{order.orderId} confirmed.</h2>
        <ul style={{ margin: "8px 0 0", paddingLeft: 18 }}>
          {order.seats.map((s) => (
            <li key={s.seatId}>
              {s.section}, row {s.row}, seat {s.number} · {money(s.unitPriceCents)}
              {s.issued ? "" : " · ticket issuing"}
            </li>
          ))}
        </ul>
        <p className="secondary">
          Total {money(order.amountCents)} · <Link href="/">Back to events</Link>
        </p>
      </div>
    );
  }
  return (
    <div data-testid="order" data-state={order.state}>
      <h2>Payment didn’t go through</h2>
      <p className="secondary" style={{ marginBottom: 0 }}>
        Order #{order.orderId} is {order.state.toLowerCase()}; your seats went back on sale. If you were charged, it’s refunded automatically.
      </p>
    </div>
  );
}
