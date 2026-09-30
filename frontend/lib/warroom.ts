// Shape of /x/metrics: what the war room draws. Built server-side from Prometheus
// and the chaos controller, so the browser makes one request per refresh.

export type Point = [number, number | null]; // [unix seconds, value]

export interface ChaosSpan {
  action: string;
  name: string;
  startedAt: number;
  endedAt: number | null;
}

export interface WarRoom {
  start: number;
  end: number;
  latencyMs: { checkoutP50: Point[]; checkoutP99: Point[]; fanoutP99: Point[] };
  perSecond: { holds: Point[]; orders: Point[]; rejected: Point[] };
  kpis: {
    wsConnections: number | null;
    queueDepth: number | null;
    confirmed: number | null;
    expiredWhileReserved: number | null;
  };
  /** Orders by saga state, grouped into four buckets. */
  saga: { confirmed: number; inFlight: number; compensating: number; cancelled: number };
  /** Rows violating each reconciler invariant, last run. null if the reconciler never ran. */
  violations: Record<string, number> | null;
  reconcilerAgeS: number | null;
  chaos: ChaosSpan[];
  errors: string[];
}

export const SAGA_GROUPS: Record<string, keyof WarRoom["saga"]> = {
  CONFIRMED: "confirmed",
  CREATED: "inFlight",
  SEAT_RESERVED: "inFlight",
  PAYMENT_PENDING: "inFlight",
  PAYMENT_FAILED: "compensating",
  SEAT_RELEASED: "compensating",
  CANCELLED: "cancelled",
  FAILED: "cancelled",
};
