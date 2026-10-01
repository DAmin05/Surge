import type { Metadata, Viewport } from "next";
import { SiteNav } from "@/components/SiteNav";
import "./globals.css";

export const metadata: Metadata = {
  title: "Surge",
  description: "Flash-sale ticketing that never oversells",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f6f3" },
    { media: "(prefers-color-scheme: dark)", color: "#0d0d0d" },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <SiteNav />
        <main className="shell">{children}</main>
        <footer className="site-footer">
          <div className="inner">
            <span>Surge · flash-sale ticketing that never oversells</span>
            <span>Redis holds for speed, Postgres decides every seat.</span>
          </div>
        </footer>
      </body>
    </html>
  );
}
