"use client";

import { useEffect, useRef, useState } from "react";
import { SeatMap, type SeatEvent, type SectionSnapshot } from "./seatmap";

type Frame =
  | { type: "snapshot"; eventId: number; sections: SectionSnapshot[] }
  | { type: "seat"; event: SeatEvent }
  | { type: "resync" };

/**
 * The live seat map for one event: snapshot + seat events over the gateway's
 * WebSocket, with re-snapshots on gaps and a fresh start on reconnect.
 */
export function useSeatMap(eventId: number) {
  const mapRef = useRef(new SeatMap());
  const [version, setVersion] = useState(0);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    let socket: WebSocket | null = null;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let frame = 0;
    // Many events can arrive in one frame; re-render at most once per animation frame.
    const bump = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        setVersion((v) => v + 1);
      });
    };

    const connect = () => {
      mapRef.current = new SeatMap();
      const proto = location.protocol === "https:" ? "wss:" : "ws:";
      socket = new WebSocket(`${proto}//${location.host}/ws/events/${eventId}`);
      const ask = (section?: string) =>
        socket?.readyState === WebSocket.OPEN && socket.send(JSON.stringify({ type: "resnapshot", section }));
      socket.onopen = () => setConnected(true);
      socket.onmessage = (msg) => {
        const f = JSON.parse(msg.data as string) as Frame;
        const map = mapRef.current;
        if (f.type === "snapshot") {
          for (const s of map.applySnapshot(f.sections)) ask(s);
        } else if (f.type === "seat") {
          const again = map.applyEvent(f.event);
          if (again) ask(again);
        } else if (f.type === "resync") {
          map.resyncAll();
          ask();
        }
        bump();
      };
      socket.onclose = () => {
        setConnected(false);
        if (!closed) retry = setTimeout(connect, 1000);
      };
    };
    connect();
    return () => {
      closed = true;
      clearTimeout(retry);
      if (frame) cancelAnimationFrame(frame);
      socket?.close();
    };
  }, [eventId]);

  return { map: mapRef.current, version, connected };
}
