"use client";

import { useEffect, useState } from "react";

function useNow(intervalMs = 250) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}

const mmss = (ms: number) => {
  const s = Math.ceil(ms / 1000);
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};

/** mm:ss until `untilMs`; calls onExpire once when it reaches zero. */
export function Countdown({ untilMs, onExpire }: { untilMs: number; onExpire?: () => void }) {
  const now = useNow();
  const left = Math.max(0, untilMs - now);
  useEffect(() => {
    if (left === 0) onExpire?.();
  }, [left === 0]); // eslint-disable-line react-hooks/exhaustive-deps
  const urgent = left <= 30_000;
  return (
    <span className="num" style={{ color: urgent ? "var(--critical)" : "inherit", fontWeight: 600 }} aria-live="polite">
      {mmss(left)}
    </span>
  );
}

/** A ring that drains from `fromMs` to `untilMs`, with the time left in the middle row. */
export function HoldTimer({ fromMs, untilMs, onExpire }: { fromMs: number; untilMs: number; onExpire?: () => void }) {
  const now = useNow();
  const total = Math.max(1, untilMs - fromMs);
  const left = Math.max(0, untilMs - now);
  const share = left / total;
  const urgent = left <= 30_000;
  const r = 20;
  const c = 2 * Math.PI * r;
  const color = urgent ? "var(--critical)" : "var(--accent)";
  return (
    <div className="timer">
      <svg width={52} height={52} viewBox="0 0 52 52" aria-hidden>
        <circle cx={26} cy={26} r={r} fill="none" stroke="var(--surface-3)" strokeWidth={5} />
        <circle
          cx={26}
          cy={26}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={5}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - share)}
          transform="rotate(-90 26 26)"
          style={{ transition: "stroke-dashoffset 250ms linear, stroke 200ms" }}
        />
      </svg>
      <div>
        <div className="t">
          <Countdown untilMs={untilMs} onExpire={onExpire} />
        </div>
        <div className="small muted">{urgent ? "Hurry, your seats are about to be released" : "left to pay before your seats are released"}</div>
      </div>
    </div>
  );
}
