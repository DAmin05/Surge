"use client";

import { useEffect, useState } from "react";

/** mm:ss until `untilMs`; calls onExpire once when it reaches zero. */
export function Countdown({ untilMs, onExpire }: { untilMs: number; onExpire?: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, []);
  const left = Math.max(0, untilMs - now);
  useEffect(() => {
    if (left === 0) onExpire?.();
  }, [left === 0]); // eslint-disable-line react-hooks/exhaustive-deps
  const s = Math.ceil(left / 1000);
  const urgent = s <= 30;
  return (
    <span className="num" style={{ color: urgent ? "var(--critical)" : "inherit", fontWeight: 600 }} aria-live="polite">
      {String(Math.floor(s / 60)).padStart(2, "0")}:{String(s % 60).padStart(2, "0")}
    </span>
  );
}
