"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type EventSummary } from "@/lib/api";
import { EventArt } from "./EventArt";
import { Icon } from "./Icon";

export function EventList() {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.events().then(setEvents, (e) => setError(String(e.message ?? e)));
  }, []);

  if (error)
    return (
      <div className="alert" role="alert">
        <Icon name="alert" />
        Couldn’t load events: {error}
      </div>
    );
  if (!events)
    return (
      <div className="grid-3" aria-busy>
        {[0, 1, 2].map((i) => (
          <div key={i} className="skeleton shimmer" style={{ height: 230 }} />
        ))}
      </div>
    );
  if (events.length === 0)
    return (
      <div className="empty">
        <Icon name="ticket" size={28} />
        <strong>No events on sale yet</strong>
        <span>
          Create one with <code>make seed</code>, then refresh.
        </span>
      </div>
    );

  return (
    <div className="grid-3">
      {events.map((e) => {
        const d = new Date(e.startsAt);
        return (
          <Link key={e.eventId} href={`/events/${e.eventId}`} className="card event-card">
            <div className="event-art">
              <EventArt seed={e.eventId} />
            </div>
            <div className="event-body">
              <div className="date-block" aria-hidden>
                <div className="m">{d.toLocaleString(undefined, { month: "short" })}</div>
                <div className="d num">{d.getDate()}</div>
              </div>
              <div className="event-meta">
                <h2>{e.name}</h2>
                <span className="secondary small row" style={{ gap: 6 }}>
                  <Icon name="clock" size={14} />
                  {d.toLocaleString(undefined, { weekday: "short", hour: "numeric", minute: "2-digit" })}
                </span>
                <span className="secondary small row" style={{ gap: 6 }}>
                  <Icon name="seat" size={14} />
                  <span className="num">{e.capacity.toLocaleString()}</span> seats
                </span>
              </div>
            </div>
            <div className="event-foot">
              <span className="pill good">
                <span className="status-dot" style={{ background: "var(--good)" }} />
                On sale
              </span>
              <span className="cta">
                Get tickets <Icon name="arrowRight" size={15} />
              </span>
            </div>
          </Link>
        );
      })}
    </div>
  );
}
