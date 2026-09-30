import { describe, expect, it } from "vitest";
import { SeatMap, type SeatEvent } from "./seatmap";

const ev = (type: SeatEvent["type"], seatId: number, seq: number, epoch = "e1", section = "A"): SeatEvent => ({
  type,
  eventId: 1,
  section,
  seatId,
  epoch,
  seq,
});

describe("SeatMap", () => {
  it("applies events after the snapshot in seq order", () => {
    const m = new SeatMap();
    m.applySnapshot([{ section: "A", epoch: "e1", seq: 3, sold: [1], held: [2] }]);
    expect(m.applyEvent(ev("SEAT_HELD", 5, 4))).toBeNull();
    expect(m.applyEvent(ev("SEAT_SOLD", 2, 5))).toBeNull();
    expect(m.status("A", 1)).toBe("sold");
    expect(m.status("A", 2)).toBe("sold");
    expect(m.status("A", 5)).toBe("held");
    expect(m.applyEvent(ev("SEAT_RELEASED", 5, 6))).toBeNull();
    expect(m.status("A", 5)).toBe("available");
  });

  it("ignores duplicates and events already in the snapshot", () => {
    const m = new SeatMap();
    m.applySnapshot([{ section: "A", epoch: "e1", seq: 3, sold: [], held: [] }]);
    expect(m.applyEvent(ev("SEAT_HELD", 9, 2))).toBeNull();
    expect(m.status("A", 9)).toBe("available");
    m.applyEvent(ev("SEAT_HELD", 9, 4));
    expect(m.applyEvent(ev("SEAT_HELD", 9, 4))).toBeNull();
    expect(m.status("A", 9)).toBe("held");
  });

  it("buffers events that arrive before the snapshot, then applies only the newer ones", () => {
    const m = new SeatMap();
    m.applyEvent(ev("SEAT_HELD", 7, 3)); // already in the snapshot
    m.applyEvent(ev("SEAT_HELD", 8, 5)); // newer
    m.applyEvent(ev("SEAT_HELD", 6, 4)); // newer, out of order
    const again = m.applySnapshot([{ section: "A", epoch: "e1", seq: 3, sold: [], held: [7] }]);
    expect(again).toEqual([]);
    expect(m.status("A", 6)).toBe("held");
    expect(m.status("A", 8)).toBe("held");
  });

  it("a gap asks for a snapshot and nothing is applied until it arrives", () => {
    const m = new SeatMap();
    m.applySnapshot([{ section: "A", epoch: "e1", seq: 1, sold: [], held: [] }]);
    expect(m.applyEvent(ev("SEAT_HELD", 3, 3))).toBe("A");
    expect(m.isReady("A")).toBe(false);
    expect(m.applyEvent(ev("SEAT_HELD", 4, 4))).toBeNull(); // buffered
    expect(m.status("A", 3)).toBe("available");
    m.applySnapshot([{ section: "A", epoch: "e1", seq: 3, sold: [], held: [2, 3] }]);
    expect(m.status("A", 2)).toBe("held");
    expect(m.status("A", 4)).toBe("held");
    expect(m.isReady("A")).toBe(true);
  });

  it("a new epoch (Redis state rebuilt) forces a snapshot", () => {
    const m = new SeatMap();
    m.applySnapshot([{ section: "A", epoch: "e1", seq: 40, sold: [], held: [1] }]);
    expect(m.applyEvent(ev("SEAT_HELD", 2, 1, "e2"))).toBe("A");
    m.applySnapshot([{ section: "A", epoch: "e2", seq: 1, sold: [], held: [2] }]);
    expect(m.status("A", 1)).toBe("available");
    expect(m.status("A", 2)).toBe("held");
  });

  it("an untouched section's first event (epoch was empty) triggers one snapshot", () => {
    const m = new SeatMap();
    m.applySnapshot([{ section: "B", epoch: "", seq: 0, sold: [], held: [] }]);
    expect(m.applyEvent(ev("SEAT_HELD", 1, 1, "e9", "B"))).toBe("B");
  });

  it("sections are independent", () => {
    const m = new SeatMap();
    m.applySnapshot([
      { section: "A", epoch: "a", seq: 1, sold: [], held: [] },
      { section: "B", epoch: "b", seq: 1, sold: [], held: [] },
    ]);
    expect(m.applyEvent(ev("SEAT_HELD", 1, 5, "a", "A"))).toBe("A");
    expect(m.applyEvent(ev("SEAT_HELD", 2, 2, "b", "B"))).toBeNull();
    expect(m.status("B", 2)).toBe("held");
  });
});
