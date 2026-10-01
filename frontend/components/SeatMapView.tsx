"use client";

import { useMemo, useState } from "react";
import type { Section } from "@/lib/api";
import type { SeatMap } from "@/lib/seatmap";
import { money } from "@/lib/api";

const LABEL = 34;
const MAX_WIDTH = 760;

interface Props {
  sections: Section[];
  active: string;
  onSection: (section: string) => void;
  map: SeatMap;
  version: number;
  selected: Set<number>;
  mine: Set<number>;
  interactive: boolean;
  onToggle: (seatId: number) => void;
}

const STATE_LABEL: Record<string, string> = {
  available: "Available",
  selected: "Selected",
  mine: "Held for you",
  held: "Held by someone",
  sold: "Sold",
};

/**
 * Section picker (each with live availability) plus one section at full detail: an
 * SVG grid of rows × seats facing the stage. Seats are colored by state and also carry
 * it in a <title> and data attributes, so state is never color alone.
 */
export function SeatMapView({ sections, active, onSection, map, version, selected, mine, interactive, onToggle }: Props) {
  const section = sections.find((s) => s.section === active) ?? sections[0];
  const [hover, setHover] = useState<number | null>(null);

  const rows = useMemo(() => {
    const byRow = new Map<string, Section["seats"]>();
    for (const seat of section?.seats ?? []) {
      const list = byRow.get(seat.row) ?? [];
      list.push(seat);
      byRow.set(seat.row, list);
    }
    return [...byRow.entries()];
  }, [section]);

  const availability = useMemo(() => {
    const out = new Map<string, number>();
    for (const s of sections) {
      out.set(s.section, s.seats.filter((seat) => map.status(s.section, seat.id) === "available").length);
    }
    return out;
    // version: the map mutates in place; recount when it changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sections, map, version]);

  if (!section) return null;

  const perRow = Math.max(1, ...rows.map(([, seats]) => seats.length));
  // Seats shrink for long rows so a whole row fits; short rows get comfortable targets.
  const pitch = Math.max(13, Math.min(30, Math.floor((MAX_WIDTH - 2 * LABEL) / perRow)));
  const gap = Math.max(3, Math.round(pitch * 0.2));
  const size = pitch - gap;
  const width = 2 * LABEL + perRow * pitch - gap;
  const height = rows.length * pitch - gap;
  const hovered = hover == null ? null : section.seats.find((s) => s.id === hover);

  const stateOf = (id: number) => {
    const status = map.status(section.section, id);
    return mine.has(id) ? "mine" : selected.has(id) ? "selected" : status;
  };

  return (
    <div className="stack">
      <div className="section-tabs" role="tablist" aria-label="Sections">
        {sections.map((s) => {
          const left = availability.get(s.section) ?? 0;
          const share = s.seats.length ? left / s.seats.length : 0;
          return (
            <button
              key={s.section}
              role="tab"
              className="section-tab"
              aria-selected={s.section === section.section}
              onClick={() => onSection(s.section)}
              data-section={s.section}
            >
              <span className="top">
                <span className="name">Section {s.section}</span>
                <span className="price num">{money(s.priceCents)}</span>
              </span>
              <span className={`meter ${left === 0 ? "out" : share < 0.2 ? "low" : ""}`} aria-hidden>
                <span style={{ width: `${Math.max(left ? 3 : 0, share * 100)}%` }} />
              </span>
              <span className="left num">{left === 0 ? "Sold out" : `${left.toLocaleString()} of ${s.seats.length.toLocaleString()} left`}</span>
            </button>
          );
        })}
      </div>

      <div className="venue">
        <div className="stage" aria-hidden>
          STAGE
        </div>
        <div className="seat-scroll">
          <svg
            viewBox={`0 0 ${width} ${height}`}
            width="100%"
            role="grid"
            aria-label={`Section ${section.section} seats`}
            // Scales down to fit, but never below ~15 px per seat: past that it scrolls.
            style={{ display: "block", margin: "0 auto", maxWidth: width, minWidth: Math.min(width, 2 * LABEL + perRow * 15) }}
            onPointerLeave={() => setHover(null)}
          >
            {rows.map(([row, seats], r) => {
              const offset = ((perRow - seats.length) * pitch) / 2;
              return (
                <g key={row} transform={`translate(0 ${r * pitch})`}>
                  <text x={LABEL - 8} y={size / 2 + 4} fontSize={10} fill="var(--muted)" textAnchor="end" className="num">
                    {row}
                  </text>
                  <text x={width - LABEL + 8} y={size / 2 + 4} fontSize={10} fill="var(--muted)" className="num">
                    {row}
                  </text>
                  {seats.map((seat, i) => {
                    const status = map.status(section.section, seat.id);
                    const state = stateOf(seat.id);
                    const fill =
                      state === "mine" || state === "selected"
                        ? "var(--seat-mine)"
                        : state === "sold"
                          ? "var(--seat-sold)"
                          : state === "held"
                            ? "var(--seat-held)"
                            : "var(--seat-free)";
                    const clickable = interactive && (status === "available" || selected.has(seat.id)) && !mine.has(seat.id);
                    return (
                      <rect
                        key={seat.id}
                        className="seat"
                        x={LABEL + offset + i * pitch}
                        y={0}
                        width={size}
                        height={size}
                        rx={Math.max(2, size * 0.28)}
                        fill={fill}
                        fillOpacity={state === "selected" ? 0.55 : 1}
                        stroke={state === "available" ? "var(--seat-free-stroke)" : state === "selected" ? "var(--seat-mine)" : "none"}
                        strokeWidth={state === "selected" ? 2 : 1}
                        data-seat={seat.id}
                        data-status={state}
                        data-clickable={clickable}
                        onClick={clickable ? () => onToggle(seat.id) : undefined}
                        onPointerEnter={() => setHover(seat.id)}
                      >
                        <title>{`Row ${seat.row}, seat ${seat.number}: ${STATE_LABEL[state] ?? state}`}</title>
                      </rect>
                    );
                  })}
                </g>
              );
            })}
          </svg>
        </div>
        <div className="seat-tip" aria-live="polite">
          {hovered ? (
            <>
              Section {section.section} · Row {hovered.row} · Seat {hovered.number} · {money(section.priceCents)} ·{" "}
              <strong>{STATE_LABEL[stateOf(hovered.id)]}</strong>
            </>
          ) : interactive ? (
            "Tap a seat to select it. Up to four, all in one section."
          ) : (
            " "
          )}
        </div>
        <div className="legend">
          {[
            ["var(--seat-free)", "Available", "var(--seat-free-stroke)", 1],
            ["var(--seat-mine)", "Yours", "none", 1],
            ["var(--seat-held)", "Held by someone", "none", 1],
            ["var(--seat-sold)", "Sold", "none", 1],
          ].map(([color, label, stroke]) => (
            <span key={label as string}>
              <svg width={14} height={14} aria-hidden>
                <rect x={0.5} y={0.5} width={13} height={13} rx={4} fill={color as string} stroke={stroke as string} />
              </svg>
              {label}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
