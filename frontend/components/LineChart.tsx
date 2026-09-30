"use client";

import { useMemo, useRef, useState } from "react";
import type { ChaosSpan, Point } from "@/lib/warroom";

export interface Series {
  key: string;
  label: string;
  color: string;
  points: Point[];
}

interface Props {
  title: string;
  unit: string;
  series: Series[];
  start: number;
  end: number;
  chaos: ChaosSpan[];
  format?: (v: number) => string;
  /** Optional reference line (e.g. an SLO), drawn dashed and labeled. */
  target?: { value: number; label: string };
}

const W = 520;
const H = 220;
const M = { top: 24, right: 104, bottom: 26, left: 48 };

function niceMax(v: number) {
  if (v <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (v <= m * p) return m * p;
  return 10 * p;
}

const clock = (t: number) =>
  new Date(t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });

/**
 * One-axis time series: 2px lines, recessive grid, a legend plus direct end labels,
 * chaos windows as shaded annotation bands, and a crosshair tooltip on hover.
 */
export function LineChart({ title, unit, series, start, end, chaos, format = (v) => v.toFixed(0), target }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const svg = useRef<SVGSVGElement>(null);
  const iw = W - M.left - M.right;
  const ih = H - M.top - M.bottom;

  const max = useMemo(() => {
    let m = target?.value ?? 0;
    for (const s of series) for (const [, v] of s.points) if (v != null && v > m) m = v;
    return niceMax(m * 1.1);
  }, [series, target]);

  const x = (t: number) => M.left + ((t - start) / Math.max(1, end - start)) * iw;
  const y = (v: number) => M.top + ih - (v / max) * ih;
  const ticks = [0, max / 2, max];

  const path = (pts: Point[]) => {
    let d = "";
    let pen = false;
    for (const [t, v] of pts) {
      if (v == null) {
        pen = false;
        continue;
      }
      d += `${pen ? "L" : "M"}${x(t).toFixed(1)},${y(v).toFixed(1)}`;
      pen = true;
    }
    return d;
  };
  const last = (pts: Point[]) => [...pts].reverse().find(([, v]) => v != null);

  const times = series[0]?.points.map(([t]) => t) ?? [];
  const onMove = (e: React.PointerEvent) => {
    const box = svg.current?.getBoundingClientRect();
    if (!box || times.length === 0) return;
    const t = start + ((((e.clientX - box.left) / box.width) * W - M.left) / iw) * (end - start);
    let best = 0;
    for (let i = 1; i < times.length; i++) if (Math.abs(times[i] - t) < Math.abs(times[best] - t)) best = i;
    setHover(best);
  };

  // End labels: nudge apart so they don't collide.
  const labels = series
    .map((s) => ({ s, p: last(s.points) }))
    .filter((l): l is { s: Series; p: Point } => !!l.p)
    .map((l) => ({ ...l, y: y(l.p[1] as number) }))
    .sort((a, b) => a.y - b.y);
  for (let i = 1; i < labels.length; i++) if (labels[i].y - labels[i - 1].y < 14) labels[i].y = labels[i - 1].y + 14;

  return (
    <figure className="card" style={{ margin: 0 }}>
      <figcaption className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}>
        <h2>{title}</h2>
        {series.length > 1 && (
          <span className="row" style={{ gap: 14, fontSize: 13 }} aria-label="Legend">
            {series.map((s) => (
              <span key={s.key} className="row secondary" style={{ gap: 6 }}>
                <svg width={14} height={4} aria-hidden>
                  <rect width={14} height={3} y={0.5} rx={1.5} fill={s.color} />
                </svg>
                {s.label}
              </span>
            ))}
          </span>
        )}
      </figcaption>
      <svg
        ref={svg}
        viewBox={`0 0 ${W} ${H}`}
        width="100%"
        role="img"
        aria-label={`${title}, ${unit}`}
        onPointerMove={onMove}
        onPointerLeave={() => setHover(null)}
        style={{ display: "block", touchAction: "none" }}
      >
        {chaos.map((c, i) => {
          const x0 = x(Math.max(start, c.startedAt));
          const x1 = x(Math.min(end, c.endedAt ?? end));
          return (
            <g key={i}>
              <rect x={x0} y={M.top} width={Math.max(2, x1 - x0)} height={ih} fill="var(--annotation)" />
              <text
                x={x0 > M.left + iw / 2 ? x1 : x0}
                y={M.top - 8}
                fontSize={12}
                fill="var(--ink-2)"
                textAnchor={x0 > M.left + iw / 2 ? "end" : "start"}
              >
                {c.name}
              </text>
            </g>
          );
        })}
        {ticks.map((v) => (
          <g key={v}>
            <line x1={M.left} x2={M.left + iw} y1={y(v)} y2={y(v)} stroke="var(--grid)" strokeWidth={1} />
            <text x={M.left - 6} y={y(v) + 3} fontSize={12} textAnchor="end" fill="var(--muted)" className="num">
              {format(v)}
            </text>
          </g>
        ))}
        <text x={M.left} y={H - 6} fontSize={12} fill="var(--muted)">
          {clock(start)}
        </text>
        <text x={M.left + iw} y={H - 6} fontSize={12} textAnchor="end" fill="var(--muted)">
          {clock(end)}
        </text>
        {target && (
          <g>
            <line
              x1={M.left}
              x2={M.left + iw}
              y1={y(target.value)}
              y2={y(target.value)}
              stroke="var(--ink-2)"
              strokeDasharray="4 4"
              strokeWidth={1}
            />
            <text x={M.left + iw + 6} y={y(target.value) + 3} fontSize={12} fill="var(--ink-2)">
              {target.label}
            </text>
          </g>
        )}
        {series.map((s) => (
          <path key={s.key} d={path(s.points)} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" />
        ))}
        {labels.map(({ s, p, y: ly }) => (
          <g key={s.key}>
            <circle cx={x(p[0])} cy={y(p[1] as number)} r={3} fill={s.color} stroke="var(--surface)" strokeWidth={2} />
            <text x={M.left + iw + 6} y={ly + 3} fontSize={12} fill="var(--ink-2)" className="num">
              {format(p[1] as number)} {series.length > 1 ? s.label : ""}
            </text>
          </g>
        ))}
        {hover != null && times[hover] != null && (
          <g pointerEvents="none">
            <line x1={x(times[hover])} x2={x(times[hover])} y1={M.top} y2={M.top + ih} stroke="var(--axis)" />
            {series.map((s) => {
              const v = s.points[hover]?.[1];
              return v == null ? null : (
                <circle key={s.key} cx={x(times[hover])} cy={y(v)} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />
              );
            })}
          </g>
        )}
      </svg>
      {hover != null && times[hover] != null && (
        <div className="tooltip" role="status">
          <div className="muted">{clock(times[hover])}</div>
          {series.map((s) => {
            const v = s.points[hover]?.[1];
            return (
              <div key={s.key} className="row" style={{ gap: 6 }}>
                <span className="status-dot" style={{ background: s.color }} />
                {s.label}: <strong className="num">{v == null ? "—" : `${format(v)} ${unit}`}</strong>
              </div>
            );
          })}
        </div>
      )}
      {series.every((s) => s.points.every(([, v]) => v == null)) && (
        <p className="muted" style={{ margin: "4px 0 0", fontSize: 13 }}>
          No data in this window yet.
        </p>
      )}
    </figure>
  );
}
