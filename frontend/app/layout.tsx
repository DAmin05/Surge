import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "Surge",
  description: "Flash-sale ticketing that never oversells",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <nav className="nav" aria-label="Main">
          <Link href="/" className="brand">
            Surge
          </Link>
          <Link href="/">Events</Link>
          <span className="spacer" />
          <Link href="/war-room">War room</Link>
          <Link href="/chaos">Chaos</Link>
        </nav>
        <main className="shell">{children}</main>
      </body>
    </html>
  );
}
