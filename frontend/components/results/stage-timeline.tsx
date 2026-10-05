import type { StageEvent } from "@/lib/contracts";
import { date } from "@/lib/format";
export function StageTimeline({ stages }: { stages: StageEvent[] }) {
  return <section className="panel" aria-labelledby="timeline-title">
    <h2 id="timeline-title">Stage audit timeline</h2>
    <p className="muted">Events are shown in the append order reported by the service. Repeated stages are retained.</p>
    {stages.length === 0 ? <p className="notice">No stage events have been recorded yet.</p> : <ol className="timeline">
      {stages.map((event, index) => <li key={`${event.stage}-${event.receivedAt}-${index}`}>
        <div className="timeline-heading"><strong>{event.stage}</strong><span className={`badge ${event.status === "failed" ? "error" : ""}`}>{event.status}</span></div>
        <p>{event.message || "No message recorded."}</p>
        <p className="muted"><time dateTime={event.occurredAt}>Occurred {date(event.occurredAt)}</time> · <time dateTime={event.receivedAt}>Received {date(event.receivedAt)}</time></p>
      </li>)}
    </ol>}
  </section>;
}
