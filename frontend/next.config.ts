import type { NextConfig } from "next";

// Served behind the gateway (same origin as /api and /ws): no CORS, the session
// cookie just works. `standalone` keeps the runtime image small.
const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
};

export default config;
