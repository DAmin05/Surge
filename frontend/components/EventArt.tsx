// Deterministic cover art for an event: a gradient plus a curved bank of seats facing a
// stage, so every event card gets a distinct, on-theme image without any assets.

const PALETTES: [string, string][] = [
  ["#2a78d6", "#7a5af0"],
  ["#eb6834", "#d03b8a"],
  ["#1baf7a", "#2a78d6"],
  ["#7a5af0", "#eb6834"],
  ["#d03b3b", "#eda100"],
];

export function EventArt({ seed }: { seed: number }) {
  const [a, b] = PALETTES[Math.abs(seed) % PALETTES.length];
  const id = `g${seed}`;
  const rows = 5;
  const dots: React.ReactNode[] = [];
  for (let r = 0; r < rows; r++) {
    const radius = 70 + r * 22;
    const n = 12 + r * 4;
    for (let i = 0; i <= n; i++) {
      const t = Math.PI * (0.12 + (0.76 * i) / n);
      const x = 200 - Math.cos(t) * radius * 1.7;
      const y = 8 + Math.sin(t) * radius * 0.62;
      // A deterministic sprinkle of "sold" seats.
      const sold = ((seed * 31 + r * 17 + i * 7) % 5) === 0;
      dots.push(<circle key={`${r}-${i}`} cx={x} cy={y} r={3.2} fill="#fff" opacity={sold ? 0.25 : 0.8} />);
    }
  }
  return (
    <svg viewBox="0 0 400 112" preserveAspectRatio="xMidYMid slice" aria-hidden>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={a} />
          <stop offset="1" stopColor={b} />
        </linearGradient>
      </defs>
      <rect width="400" height="112" fill={`url(#${id})`} />
      <path d="M150 0h100v10a50 14 0 0 1-100 0z" fill="#fff" opacity={0.85} />
      {dots}
    </svg>
  );
}
