"use client";

import { useMemo } from "react";
import type { Section } from "@/lib/api";
import type { SeatMap } from "@/lib/seatmap";
import { money } from "@/lib/api";

const SEAT = 16;
const GAP = 4;
const LABEL = 28;

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

/**
 * One section at full detail (an SVG grid of rows × seats) plus a strip of section
 * tabs with live availability. Seats are colored by state and also carry it in a
 * <title> and data attributes, so state is never color alone.
 */
export function SeatMapView({ sections, active, onSection, map, version, selected, mine, interactive, onToggle }: Props) {
  const section = sections.find((s) => s.section === active) ?? sections[0];

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
  const width = LABEL + Math.max(...rows.map(([, seats]) => seats.length)) * (SEAT + GAP);
  const height = rows.length * (SEAT + GAP);

  return (
    <div className="stack">
      <div className="row" role="tablist" aria-label="Sections">
        {sections.map((s) => (
          <button
            key={s.section}
            role="tab"
            aria-selected={s.section === section.section}
            className={s.section === section.section ? "" : "ghost"}
            onClick={() => onSection(s.section)}
            data-section={s.section}
          >
            {s.section} · {money(s.priceCents)} ·{" "}
            <span className="num">{availability.get(s.section)?.toLocaleString()}</span> left
          </button>
        ))}
      </div>
      <div style={{ overflowX: "auto" }}>
        <svg
          width={width}
          height={height}
          role="grid"
          aria-label={`Section ${section.section} seats`}
          style={{ display: "block" }}
        >
          {rows.map(([row, seats], r) => (
            <g key={row} transform={`translate(0 ${r * (SEAT + GAP)})`}>
              <text x={0} y={SEAT - 4} fontSize={10} fill="var(--muted)">
                {row}
              </text>
              {seats.map((seat, i) => {
                const status = map.status(section.section, seat.id);
                const isMine = mine.has(seat.id);
                const isSelected = selected.has(seat.id);
                const state = isMine ? "mine" : isSelected ? "selected" : status;
                const fill =
                  state === "mine" || state === "selected"
                    ? "var(--seat-mine)"
                    : state === "sold"
                      ? "var(--seat-sold)"
                      : state === "held"
                        ? "var(--seat-held)"
                        : "var(--seat-free)";
                const clickable = interactive && (status === "available" || isSelected) && !isMine;
                return (
                  <rect
                    key={seat.id}
                    x={LABEL + i * (SEAT + GAP)}
                    y={0}
                    width={SEAT}
                    height={SEAT}
                    rx={4}
                    fill={fill}
                    stroke={state === "available" ? "var(--axis)" : "none"}
                    strokeWidth={1}
                    opacity={state === "selected" ? 0.75 : 1}
                    style={{ cursor: clickable ? "pointer" : "default" }}
                    data-seat={seat.id}
                    data-status={state}
                    onClick={clickable ? () => onToggle(seat.id) : undefined}
                  >
                    <title>{`Row ${seat.row}, seat ${seat.number}: ${state}`}</title>
                  </rect>
                );
              })}
            </g>
          ))}
        </svg>
      </div>
      <div className="row muted" style={{ fontSize: 13 }}>
        {[
          ["var(--seat-free)", "Available", true],
          ["var(--seat-mine)", "Yours", false],
          ["var(--seat-held)", "Held by someone", false],
          ["var(--seat-sold)", "Sold", false],
        ].map(([color, label, outlined]) => (
          <span key={label as string} className="row" style={{ gap: 6 }}>
            <svg width={12} height={12} aria-hidden>
              <rect width={12} height={12} rx={3} fill={color as string} stroke={outlined ? "var(--axis)" : "none"} />
            </svg>
            {label}
          </span>
        ))}
      </div>
    </div>
  );
}
