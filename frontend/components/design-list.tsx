"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useState, type FormEvent } from "react";
import { api } from "@/lib/api";
import { date, message } from "@/lib/format";
import { useResource } from "@/lib/use-resource";
import { useSession } from "./session-provider";
import { Empty, Failure, Loading } from "./resource-state";
import { ScenarioField } from "./scenario-field";

export function DesignList() {
  const { authorization } = useSession();
  const router = useRouter();
  const load = useCallback((signal: AbortSignal) => api.designs(authorization, signal), [authorization]);
  const resource = useResource(load);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setPending(true); setError("");
    try {
      const design = await api.create(authorization, {
        name: String(data.get("name")).trim(), description: String(data.get("description")).trim(),
        scenario: String(data.get("scenario")).trim(), topology: { nodes: [], edges: [] },
      });
      router.push(`/designs/${encodeURIComponent(design.id)}`);
    } catch (reason) { setError(message(reason)); setPending(false); }
  }
  return <div className="page stack">
    <div className="section-heading"><div><p className="eyebrow">Workspace</p><h1>System designs</h1></div><button onClick={resource.reload} disabled={resource.loading}>Refresh designs</button></div>
    <div className="library-grid">
      <section className="panel" aria-labelledby="saved-title">
        <h2 id="saved-title">Saved topologies</h2>
        {resource.loading && <Loading>Loading your designs…</Loading>}
        {resource.error && <Failure error={resource.error} retry={resource.reload} />}
        {resource.data?.length === 0 && <Empty>No designs yet. Create a design, then add components to its canvas.</Empty>}
        {!!resource.data?.length && <div className="table-scroll"><table>
          <caption className="sr-only">Your persisted system designs</caption>
          <thead><tr><th scope="col">Design</th><th scope="col">Scenario</th><th scope="col">Nodes / edges</th><th scope="col">Revision</th><th scope="col">Updated</th></tr></thead>
          <tbody>{resource.data.map((design) => <tr key={design.id}>
            <td><Link href={`/designs/${encodeURIComponent(design.id)}`}>{design.name}</Link>{design.description && <p className="muted">{design.description}</p>}</td>
            <td>{design.scenario}</td><td className="numeric">{design.nodeCount} / {design.edgeCount}</td><td className="numeric">{design.revision}</td><td>{date(design.updatedAt)}</td>
          </tr>)}</tbody>
        </table></div>}
      </section>
      <section className="panel" aria-labelledby="create-title">
        <h2 id="create-title">Create a design</h2>
        <form onSubmit={create} className="stack">
          <label>Name<input name="name" required pattern={".*\\S.*"} disabled={pending} /></label>
          <label>Description<textarea name="description" rows={3} disabled={pending} /></label>
          <ScenarioField name="scenario" disabled={pending} />
          {error && <p className="notice error" role="alert">{error}</p>}
          <button className="primary" disabled={pending}>{pending ? "Creating…" : "Create design"}</button>
        </form>
      </section>
    </div>
  </div>;
}
