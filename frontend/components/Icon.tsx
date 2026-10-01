// A small inline icon set (24×24, 2px strokes, currentColor), so the UI needs no icon
// font or external assets.

const PATHS: Record<string, React.ReactNode> = {
  bolt: <path d="M13 2 4 14h7l-1 8 9-12h-7l1-8z" />,
  ticket: (
    <>
      <path d="M3 8a2 2 0 0 0 2-2h14a2 2 0 0 0 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 0-2 2H5a2 2 0 0 0-2-2v-2a2 2 0 0 0 0-4z" />
      <path d="M14 6v12" strokeDasharray="2 2.5" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3 5 6v6c0 4.2 2.9 7.8 7 9 4.1-1.2 7-4.8 7-9V6z" />
      <path d="m9 12 2 2 4-4" />
    </>
  ),
  users: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20a6.5 6.5 0 0 1 13 0" />
      <path d="M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5a6.5 6.5 0 0 1 3.5 5.5" />
    </>
  ),
  radio: (
    <>
      <circle cx="12" cy="12" r="2" />
      <path d="M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19 5a10 10 0 0 1 0 14M5 19A10 10 0 0 1 5 5" />
    </>
  ),
  activity: <path d="M3 12h4l3-8 4 16 3-8h4" />,
  flame: <path d="M12 22c4 0 7-2.7 7-7 0-3.5-2.3-5.6-4-8-.5 2-1.5 3-3 3 0-3-1-6-4-8 0 4-5 6.5-5 13 0 4.3 3 7 9 7z" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  check: <path d="m5 12.5 4.5 4.5L19 7.5" />,
  alert: (
    <>
      <path d="M12 3 2 20h20z" />
      <path d="M12 10v4M12 17v.5" />
    </>
  ),
  arrowRight: <path d="M5 12h14M13 6l6 6-6 6" />,
  arrowLeft: <path d="M19 12H5M11 6l-6 6 6 6" />,
  database: (
    <>
      <ellipse cx="12" cy="5.5" rx="7.5" ry="3" />
      <path d="M4.5 5.5v13c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-13M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3" />
    </>
  ),
  server: (
    <>
      <rect x="3" y="4" width="18" height="7" rx="2" />
      <rect x="3" y="13" width="18" height="7" rx="2" />
      <path d="M7 7.5h.01M7 16.5h.01" />
    </>
  ),
  card: (
    <>
      <rect x="2.5" y="5" width="19" height="14" rx="2.5" />
      <path d="M2.5 10h19M6.5 15h4" />
    </>
  ),
  network: (
    <>
      <rect x="9" y="2.5" width="6" height="5" rx="1" />
      <rect x="2.5" y="16.5" width="6" height="5" rx="1" />
      <rect x="15.5" y="16.5" width="6" height="5" rx="1" />
      <path d="M12 7.5V12M5.5 16.5V12h13v4.5" />
    </>
  ),
  stream: (
    <>
      <path d="M3 7h12M3 12h18M3 17h9" />
      <circle cx="18" cy="7" r="1.5" />
      <circle cx="15" cy="17" r="1.5" />
    </>
  ),
  undo: (
    <>
      <path d="M4 9h11a5 5 0 0 1 0 10H8" />
      <path d="m8 5-4 4 4 4" />
    </>
  ),
  play: <path d="M7 4.5v15l12-7.5z" />,
  stop: <rect x="6" y="6" width="12" height="12" rx="1.5" />,
  spinner: <path d="M12 3a9 9 0 1 0 9 9" />,
  calendar: (
    <>
      <rect x="3" y="4.5" width="18" height="16" rx="2.5" />
      <path d="M3 9.5h18M8 2.5v4M16 2.5v4" />
    </>
  ),
  seat: (
    <>
      <path d="M6 11V6a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v5" />
      <path d="M4 11h16v5H4zM6 16v4M18 16v4" />
    </>
  ),
};

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 16, className, title }: { name: IconName; size?: number; className?: string; title?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
      style={{ flex: "none" }}
    >
      {title && <title>{title}</title>}
      {PATHS[name]}
    </svg>
  );
}
