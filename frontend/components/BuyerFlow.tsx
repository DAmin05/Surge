"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, money, type Catalog, type Hold, type OrderView } from "@/lib/api";
import { useSeatMap } from "@/lib/useSeatMap";
import { SeatMapView } from "./SeatMapView";
import { HoldTimer } from "./Countdown";
import { Icon } from "./Icon";

const MAX_SEATS = 4;
const TERMINAL = new Set(["CONFIRMED", "CANCELLED", "FAILED"]);

type Stage =
  | { kind: "queue"; position?: number; wait?: number }
  | { kind: "pick" }
  | { kind: "held"; hold: Hold; heldAt: number; section: string; seats: number[] }
  | { kind: "paying"; hold: Hold; heldAt: number; section: string; seats: number[] }
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

const STEPS = [
  { key: "queue", label: "Waiting room" },
  { key: "pick", label: "Choose seats" },
  { key: "held", label: "Pay" },
  { key: "order", label: "Tickets" },
];
const stepIndex = (kind: Stage["kind"]) => (kind === "paying" ? 2 : STEPS.findIndex((s) => s.key === kind));

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
      setStage({ kind: "held", hold: h, heldAt: Date.now(), section, seats });
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

  if (error && !catalog)
    return (
      <div className="alert" role="alert">
        <Icon name="alert" />
        {error}
      </div>
    );
  if (!catalog)
    return (
      <div className="stack" aria-busy>
        <div className="skeleton shimmer" style={{ height: 64 }} />
        <div className="buy-layout">
          <div className="skeleton shimmer" style={{ height: 420 }} />
          <div className="skeleton shimmer" style={{ height: 260 }} />
        </div>
      </div>
    );

  const held = stage.kind === "held" || stage.kind === "paying" ? stage : null;
  const mine = new Set(held?.seats ?? []);
  const priceOf = (s: string) => catalog.sections.find((x) => x.section === s)?.priceCents ?? 0;
  const seatOf = (s: string, id: number) => catalog.sections.find((x) => x.section === s)?.seats.find((x) => x.id === id);
  const starts = new Date(catalog.startsAt);
  const current = stepIndex(stage.kind);

  return (
    <div className="stack-lg">
      <div className="stack">
        <Link href="/" className="back">
          <Icon name="arrowLeft" size={14} /> All events
        </Link>
        <div className="event-head">
          <div className="title">
            <div className="date-block" aria-hidden>
              <div className="m">{starts.toLocaleString(undefined, { month: "short" })}</div>
              <div className="d num">{starts.getDate()}</div>
            </div>
            <div style={{ minWidth: 0 }}>
              <h1>{catalog.name}</h1>
              <p className="secondary small" style={{ marginTop: 4 }}>
                {starts.toLocaleString(undefined, { weekday: "long", month: "long", day: "numeric", hour: "numeric", minute: "2-digit" })}
                {" · "}
                <span className="num">{catalog.capacity.toLocaleString()}</span> seats
              </p>
            </div>
          </div>
          <span className={`pill ${connected ? "good" : "warn"}`} data-testid="live" data-connected={connected}>
            <span
              className={`status-dot ${connected ? "pulse" : ""}`}
              style={{ background: connected ? "var(--good)" : "var(--warning)", color: "var(--good)" }}
            />
            {connected ? "Live seat map" : "Reconnecting…"}
          </span>
        </div>
        <ol className="steps" aria-label="Progress">
          {STEPS.map((s, i) => (
            <li key={s.key} data-state={i < current ? "done" : i === current ? "current" : "todo"} aria-current={i === current ? "step" : undefined}>
              <span className="bar" />
              {s.label}
            </li>
          ))}
        </ol>
      </div>

      <div className="buy-layout">
        <section className="card" aria-label="Seat map">
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

        <aside className="buy-aside">
          <section className="card stack summary" aria-live="polite" data-testid="stage" data-stage={stage.kind}>
            {stage.kind === "queue" && <Queue position={stage.position} wait={stage.wait} />}

            {stage.kind === "pick" && (
              <>
                <div className="card-head">
                  <h2>Your seats</h2>
                  <span className="pill accent num">
                    {selected.size} / {MAX_SEATS}
                  </span>
                </div>
                {selected.size === 0 ? (
                  <div className="stack" style={{ gap: 10 }}>
                    <div className="placeholder-seats" aria-hidden>
                      {Array.from({ length: MAX_SEATS }, (_, i) => (
                        <span key={i} />
                      ))}
                    </div>
                    <p className="secondary small">Pick up to four seats in one section on the map.</p>
                  </div>
                ) : (
                  <SeatLines
                    section={section}
                    seats={[...selected].sort((a, b) => a - b)}
                    seatOf={seatOf}
                    price={priceOf(section)}
                  />
                )}
                <div className="total">
                  <span>Total</span>
                  <span className="num">{money(priceOf(section) * selected.size)}</span>
                </div>
                <button className="lg block" data-testid="hold" disabled={busy || selected.size === 0} onClick={hold}>
                  {busy ? <Icon name="spinner" className="spin" /> : <Icon name="ticket" />}
                  {busy ? "Holding…" : selected.size === 0 ? "Select seats to continue" : `Hold ${selected.size} seat${selected.size > 1 ? "s" : ""}`}
                </button>
                <p className="muted small" style={{ textAlign: "center" }}>
                  Holding reserves them for you while you pay.
                </p>
              </>
            )}

            {held && (
              <>
                <div className="card-head">
                  <h2>
                    {held.seats.length} seat{held.seats.length > 1 ? "s" : ""} held for you
                  </h2>
                  <span className="pill accent">Section {held.section}</span>
                </div>
                {stage.kind === "held" ? (
                  <HoldTimer fromMs={held.heldAt} untilMs={held.hold.expiresAtMs} onExpire={expired} />
                ) : (
                  <div className="timer">
                    <Icon name="spinner" size={40} className="spin" />
                    <div>
                      <div style={{ fontWeight: 650 }}>Processing payment…</div>
                      <div className="small muted">Confirming your seats. Don’t close this page.</div>
                    </div>
                  </div>
                )}
                <SeatLines section={held.section} seats={held.seats} seatOf={seatOf} price={priceOf(held.section)} />
                <div className="total">
                  <span>Total</span>
                  <span className="num">{money(priceOf(held.section) * held.seats.length)}</span>
                </div>
                <button className="lg block" data-testid="pay" disabled={busy || stage.kind === "paying"} onClick={pay}>
                  {stage.kind === "paying" ? <Icon name="spinner" className="spin" /> : <Icon name="card" />}
                  {stage.kind === "paying" ? "Paying…" : `Pay ${money(priceOf(held.section) * held.seats.length)}`}
                </button>
                <button className="ghost block" disabled={busy || stage.kind === "paying"} onClick={release}>
                  Release seats
                </button>
              </>
            )}

            {stage.kind === "order" && <OrderResult order={stage.order} name={catalog.name} starts={starts} />}

            {error && (
              <div className="alert" role="alert" data-testid="error">
                <Icon name="alert" />
                <span>{error}</span>
              </div>
            )}
          </section>
          <div className="callout small">
            <Icon name="shield" size={18} />
            <span style={{ flex: 1 }}>Every seat is checked against the database at checkout, so no seat is ever sold twice.</span>
          </div>
        </aside>
      </div>
    </div>
  );
}

function Queue({ position, wait }: { position?: number; wait?: number }) {
  return (
    <div className="queue-hero">
      <span className="pill accent">
        <Icon name="users" size={14} /> Waiting room
      </span>
      {position === undefined ? (
        <>
          <div className="queue-pos" aria-hidden>
            …
          </div>
          <p className="secondary">Getting you a place in line.</p>
        </>
      ) : (
        <>
          <div className="small muted">You’re number</div>
          <div className="queue-pos num">{position.toLocaleString()}</div>
          <p className="secondary small">
            in line · about <span className="num">{wait}</span> s to go
          </p>
        </>
      )}
      <div className="queue-track" aria-hidden>
        <span />
      </div>
      <p className="muted small">Keep this page open. The seat map is live, and you can pick seats as soon as you’re in.</p>
    </div>
  );
}

function SeatLines({
  section,
  seats,
  seatOf,
  price,
}: {
  section: string;
  seats: number[];
  seatOf: (section: string, id: number) => { row: string; number: number } | undefined;
  price: number;
}) {
  return (
    <ul className="line-items">
      {seats.map((id) => {
        const s = seatOf(section, id);
        return (
          <li key={id}>
            <span className="seat-chip">
              <span className="sw" aria-hidden />
              {s ? `Section ${section} · Row ${s.row} · Seat ${s.number}` : `Seat ${id}`}
            </span>
            <span className="num">{money(price)}</span>
          </li>
        );
      })}
    </ul>
  );
}

function OrderResult({ order, name, starts }: { order: OrderView; name: string; starts: Date }) {
  if (order.state === "CONFIRMED") {
    return (
      <div className="stack" data-testid="order" data-state={order.state}>
        <div className="row" style={{ gap: 10 }}>
          <span className="fact-icon" style={{ color: "var(--good)" }}>
            <Icon name="check" />
          </span>
          <div>
            <h2>You’re going!</h2>
            <p className="secondary small">Order #{order.orderId} confirmed.</p>
          </div>
        </div>
        <div className="ticket">
          <div className="top">
            <span className="k">Admit {order.seats.length}</span>
            <strong style={{ fontSize: 17 }}>{name}</strong>
            <span style={{ fontSize: 13, opacity: 0.9 }}>
              {starts.toLocaleString(undefined, { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}
            </span>
          </div>
          <div className="perf" />
          <div className="seats">
            <span className="h">Section</span>
            <span className="h">Row</span>
            <span className="h">Seat</span>
            {order.seats.map((s) => (
              <span key={s.seatId} style={{ display: "contents" }}>
                <span>{s.section}</span>
                <span>{s.row}</span>
                <span>
                  {s.number}
                  {s.issued ? "" : " · issuing"}
                </span>
              </span>
            ))}
          </div>
        </div>
        <div className="total">
          <span>Paid</span>
          <span className="num">{money(order.amountCents)}</span>
        </div>
        <Link href="/" className="small">
          Back to events
        </Link>
      </div>
    );
  }
  return (
    <div className="stack" data-testid="order" data-state={order.state}>
      <div className="row" style={{ gap: 10 }}>
        <span className="fact-icon" style={{ color: "var(--critical)" }}>
          <Icon name="alert" />
        </span>
        <h2>Payment didn’t go through</h2>
      </div>
      <p className="secondary small">
        Order #{order.orderId} is {order.state.toLowerCase()}; your seats went back on sale. If you were charged, it’s
        refunded automatically.
      </p>
      <Link href="/" className="small">
        Back to events
      </Link>
    </div>
  );
}
