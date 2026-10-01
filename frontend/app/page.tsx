import { EventList } from "@/components/EventList";
import { Icon } from "@/components/Icon";

export default function Home() {
  return (
    <div className="stack-lg">
      <section className="hero-banner">
        <div className="eyebrow">Flash sales, done fairly</div>
        <h1>Thousands of fans. One seat each. Zero oversells.</h1>
        <p className="lede">
          Join the waiting room, pick up to four seats in one section on a live map, and check out before your hold
          runs out. If two people tap the same seat, exactly one gets it.
        </p>
        <div className="facts">
          <span className="fact">
            <span className="fact-icon"><Icon name="users" /></span>
            Fair waiting room
          </span>
          <span className="fact">
            <span className="fact-icon"><Icon name="radio" /></span>
            Seats update live
          </span>
          <span className="fact">
            <span className="fact-icon"><Icon name="shield" /></span>
            Never sold twice
          </span>
        </div>
      </section>

      <section className="stack">
        <div className="row between">
          <h2 style={{ fontSize: 20 }}>On sale now</h2>
        </div>
        <EventList />
      </section>
    </div>
  );
}
