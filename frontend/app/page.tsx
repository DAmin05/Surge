import { EventList } from "@/components/EventList";

export default function Home() {
  return (
    <div className="stack">
      <div>
        <h1>On sale now</h1>
        <p className="secondary">
          Join the waiting room, pick up to four seats in one section, and check out before your hold runs out.
        </p>
      </div>
      <EventList />
    </div>
  );
}
