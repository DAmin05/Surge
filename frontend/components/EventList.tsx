"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type EventSummary } from "@/lib/api";

export function EventList() {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.events().then(setEvents, (e) => setError(String(e.message ?? e)));
  }, []);

  if (error) return <p className="error">Couldn’t load events: {error}</p>;
  if (!events) return <p className="muted">Loading events…</p>;
  if (events.length === 0) return <p className="muted">No events yet. Run `make seed` to create one.</p>;

  return (
    <div className="grid-2">
      {events.map((e) => (
        <Link key={e.eventId} href={`/events/${e.eventId}`} className="card" style={{ color: "inherit" }}>
          <h2>{e.name}</h2>
          <p className="secondary" style={{ margin: "6px 0 0" }}>
            {new Date(e.startsAt).toLocaleString()} · {e.capacity.toLocaleString()} seats
          </p>
        </Link>
      ))}
    </div>
  );
}
