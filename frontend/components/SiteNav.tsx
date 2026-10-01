"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, type IconName } from "./Icon";

const LINKS: { href: string; label: string; short: string; icon: IconName; match: (p: string) => boolean }[] = [
  { href: "/", label: "Events", short: "Events", icon: "ticket", match: (p) => p === "/" || p.startsWith("/events") },
  { href: "/war-room", label: "War room", short: "War room", icon: "activity", match: (p) => p.startsWith("/war-room") },
  { href: "/chaos", label: "Chaos panel", short: "Chaos", icon: "flame", match: (p) => p.startsWith("/chaos") },
];

export function SiteNav() {
  const path = usePathname() ?? "/";
  return (
    <header className="site-header">
      <nav className="nav" aria-label="Main">
        <Link href="/" className="brand" aria-label="Surge home">
          <span className="brand-mark">
            <Icon name="bolt" size={16} />
          </span>
          Surge
        </Link>
        {LINKS.slice(0, 1).map((l) => (
          <NavLink key={l.href} link={l} current={l.match(path)} />
        ))}
        <span className="spacer" />
        <span className="nav-sep" aria-hidden />
        {LINKS.slice(1).map((l) => (
          <NavLink key={l.href} link={l} current={l.match(path)} />
        ))}
      </nav>
    </header>
  );
}

function NavLink({ link, current }: { link: (typeof LINKS)[number]; current: boolean }) {
  return (
    <Link href={link.href} className="nav-link" aria-current={current ? "page" : undefined}>
      <Icon name={link.icon} size={15} />
      <span className="label-long">{link.label}</span>
    </Link>
  );
}
