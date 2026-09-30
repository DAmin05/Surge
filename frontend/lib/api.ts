// Browser-side API client. Everything goes to the same origin: the gateway serves
// the API, the WebSocket and this app. Identity is the HttpOnly session cookie; the
// admission token (for holds and checkout) comes from the waiting room.

export interface Seat {
  id: number;
  row: string;
  number: number;
}
export interface Section {
  section: string;
  priceCents: number;
  seats: Seat[];
}
export interface Catalog {
  eventId: number;
  name: string;
  startsAt: string;
  capacity: number;
  sections: Section[];
}
export interface EventSummary {
  eventId: number;
  name: string;
  startsAt: string;
  capacity: number;
}
export type QueueStatus =
  | { status: "WAITING"; position: number; ahead: number; estimatedWaitSeconds: number }
  | { status: "ADMITTED"; token: string; expiresAt: string }
  | { status: "NOT_IN_QUEUE" };
export interface Hold {
  holdId: string;
  expiresAtMs: number;
}
export interface OrderView {
  orderId: number;
  eventId: number;
  section: string;
  state: string;
  amountCents: number;
  seats: { seatId: number; section: string; row: string; number: number; unitPriceCents: number; issued: boolean }[];
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(code);
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, { credentials: "same-origin", ...init });
  const text = await res.text();
  const body = text ? JSON.parse(text) : {};
  if (!res.ok) throw new ApiError(res.status, body.error ?? body.status ?? `HTTP ${res.status}`);
  return body as T;
}

const json = (body: unknown, token?: string, extra: Record<string, string> = {}): RequestInit => ({
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  },
  body: JSON.stringify(body),
});

export const api = {
  session: () => call<{ userId: string }>("/api/session", { method: "POST" }),
  events: () => call<EventSummary[]>("/api/events"),
  catalog: (eventId: number) => call<Catalog>(`/api/events/${eventId}`),
  join: (eventId: number) => call<QueueStatus>(`/api/queue/${eventId}/join`, { method: "POST" }),
  position: (eventId: number) => call<QueueStatus>(`/api/queue/${eventId}/position`),
  hold: (token: string, eventId: number, section: string, seatIds: number[]) =>
    call<Hold>("/api/holds", json({ eventId, section, seatIds }, token)),
  release: (token: string, holdId: string) =>
    call<void>(`/api/holds/${encodeURIComponent(holdId)}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
    }),
  checkout: (token: string, holdId: string, idempotencyKey: string) =>
    call<{ orderId: number; state: string; amountCents: number }>(
      "/api/checkout",
      json({ holdId }, token, { "Idempotency-Key": idempotencyKey }),
    ),
  order: (orderId: number) => call<OrderView>(`/api/orders/${orderId}`),
};

export const money = (cents: number) =>
  (cents / 100).toLocaleString(undefined, { style: "currency", currency: "USD" });
