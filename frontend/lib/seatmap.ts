// Live seat map state: a snapshot per section, then seat events applied in order.
//
// Every seat change carries (section, epoch, seq); seq is gap-free within an epoch
// (ADR 0004). Events are applied only when they're the next seq of the current epoch.
// A gap, an epoch change, or a "resync" from the gateway means events were lost:
// the section asks for a fresh snapshot and buffers until it arrives. Events that
// arrive before a section's snapshot are buffered too. The map can miss an event;
// it can't stay wrong.

export type SeatEventType = "SEAT_HELD" | "SEAT_RELEASED" | "SEAT_HOLD_EXTENDED" | "SEAT_SOLD";

export interface SeatEvent {
  type: SeatEventType;
  eventId: number;
  section: string;
  seatId: number;
  epoch: string;
  seq: number;
}

export interface SectionSnapshot {
  section: string;
  epoch: string;
  seq: number;
  sold: number[];
  held: number[];
}

export type SeatStatus = "available" | "held" | "sold";

interface Section {
  epoch: string;
  seq: number;
  held: Set<number>;
  sold: Set<number>;
  /** Waiting for a snapshot: events go to the buffer. */
  stale: boolean;
}

export class SeatMap {
  private sections = new Map<string, Section>();
  private buffers = new Map<string, SeatEvent[]>();
  /** Bumped on every visible change, so a UI can re-render cheaply. */
  version = 0;

  /**
   * Applies a snapshot. Returns sections that still need a fresh snapshot (a buffered
   * event showed the snapshot was already out of date).
   */
  applySnapshot(sections: SectionSnapshot[]): string[] {
    const again: string[] = [];
    for (const snap of sections) {
      const s: Section = {
        epoch: snap.epoch,
        seq: snap.seq,
        held: new Set(snap.held),
        sold: new Set(snap.sold),
        stale: false,
      };
      this.sections.set(snap.section, s);
      const buffered = (this.buffers.get(snap.section) ?? []).sort((a, b) => a.seq - b.seq);
      this.buffers.delete(snap.section);
      for (const e of buffered) {
        if (e.epoch !== s.epoch) {
          if (e.epoch !== "" && !again.includes(snap.section)) again.push(snap.section);
          continue;
        }
        if (e.seq <= s.seq) continue; // already in the snapshot
        if (this.applyEvent(e) !== null && !again.includes(snap.section)) again.push(snap.section);
      }
    }
    this.version++;
    return again;
  }

  /** Returns a section name to re-snapshot, or null. */
  applyEvent(e: SeatEvent): string | null {
    const s = this.sections.get(e.section);
    if (!s || s.stale) {
      this.buffer(e);
      return null;
    }
    if (e.epoch !== s.epoch || e.seq > s.seq + 1) {
      // Lost events, or Redis state was rebuilt: this section can't be trusted.
      s.stale = true;
      this.buffer(e);
      return e.section;
    }
    if (e.seq <= s.seq) return null; // duplicate or old
    s.seq = e.seq;
    switch (e.type) {
      case "SEAT_HELD":
      case "SEAT_HOLD_EXTENDED":
        s.held.add(e.seatId);
        break;
      case "SEAT_RELEASED":
        s.held.delete(e.seatId);
        break;
      case "SEAT_SOLD":
        s.held.delete(e.seatId);
        s.sold.add(e.seatId);
        break;
    }
    this.version++;
    return null;
  }

  /** The gateway fell behind for us: everything must be re-snapshotted. */
  resyncAll(): void {
    for (const s of this.sections.values()) s.stale = true;
  }

  status(section: string, seatId: number): SeatStatus {
    const s = this.sections.get(section);
    if (!s) return "available";
    if (s.sold.has(seatId)) return "sold";
    if (s.held.has(seatId)) return "held";
    return "available";
  }

  isReady(section: string): boolean {
    const s = this.sections.get(section);
    return !!s && !s.stale;
  }

  private buffer(e: SeatEvent): void {
    const list = this.buffers.get(e.section) ?? [];
    // Bounded: if this much piles up, the next snapshot supersedes it anyway.
    if (list.length < 5000) list.push(e);
    this.buffers.set(e.section, list);
  }
}
