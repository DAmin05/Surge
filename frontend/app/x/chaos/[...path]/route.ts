// Same-origin proxy to the chaos controller (it isn't exposed on its own). Only the
// controller's own API passes: GET actions/events, POST/DELETE actions/{id}, POST reset.

export const dynamic = "force-dynamic";

const CHAOS = process.env.CHAOS_URL ?? "http://chaos:8002";
const ALLOWED = /^(actions|events|reset|actions\/[a-z0-9-]+)$/;

async function forward(req: Request, { params }: { params: Promise<{ path: string[] }> }) {
  const path = (await params).path.join("/");
  if (!ALLOWED.test(path)) return Response.json({ error: "NOT_FOUND" }, { status: 404 });
  const body = req.method === "POST" ? await req.text() : undefined;
  try {
    const res = await fetch(`${CHAOS}/${path}`, {
      method: req.method,
      headers: body ? { "Content-Type": "application/json" } : undefined,
      body: body || undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(60_000),
    });
    return new Response(await res.text(), {
      status: res.status,
      headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" },
    });
  } catch {
    return Response.json({ error: "CHAOS_UNAVAILABLE" }, { status: 503 });
  }
}

export { forward as GET, forward as POST, forward as DELETE };
