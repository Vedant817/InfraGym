"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import type { DesignDetail, Topology } from "@/lib/contracts";
import { date, message } from "@/lib/format";
import { preflight } from "@/lib/topology";
import { useResource } from "@/lib/use-resource";
import { SystemDesignCanvas } from "./canvas/SystemDesignCanvas";
import { Failure, Loading } from "./resource-state";
import { useSession } from "./session-provider";
import { SubmissionList } from "./submission-list";

function Preflight({ topology }: { topology: Topology }) {
  const { blocking, advisory } = preflight(topology);
  if (blocking.length === 0 && advisory.length === 0) return null;
  return <div className="stack">
    {blocking.length > 0 && <div className="notice error" role="alert">
      <strong>This topology will be rejected.</strong>
      <p>Fix these before starting a run. The compiler refuses the submission outright, so there will be no measurements.</p>
      <ul>{blocking.map((finding, index) => <li key={`${finding.code}-${index}`}>{finding.message}</li>)}</ul>
    </div>}
    {advisory.length > 0 && <div className="notice warning">
      <strong>Worth checking before this runs.</strong>
      <p>These do not stop the run, but they change what the simulation will measure.</p>
      <ul>{advisory.map((finding, index) => <li key={`${finding.code}-${index}`}>{finding.message}</li>)}</ul>
    </div>}
  </div>;
}

function Editor({ initial, reload }: { initial: DesignDetail; reload: () => void }) {
  const { authorization } = useSession();
  const router = useRouter();
  const [design, setDesign] = useState(initial);
  const [topology, setTopology] = useState(initial.topology);
  const [dirty, setDirty] = useState(false);
  const [draft, setDraft] = useState(false);
  const [busy, setBusy] = useState<"save" | "submit" | "delete" | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [conflict, setConflict] = useState(false);
  const [canvasVersion, setCanvasVersion] = useState(0);
  const [confirmDelete, setConfirmDelete] = useState(false);
  useEffect(() => {
    if (!dirty && !draft) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    const guard = (event: MouseEvent) => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      const element = event.target instanceof Element ? event.target : null;
      const anchor = element?.closest("a");
      const leavesWorkspace = element?.closest("[data-leave-workspace]");
      if (!leavesWorkspace && (!anchor || anchor.target === "_blank" || anchor.href === window.location.href || anchor.getAttribute("href")?.startsWith("#"))) return;
      if (!window.confirm("Leave this design and discard unsaved changes?")) { event.preventDefault(); event.stopPropagation(); }
    };
    window.addEventListener("beforeunload", warn);
    document.addEventListener("click", guard, true);
    return () => { window.removeEventListener("beforeunload", warn); document.removeEventListener("click", guard, true); };
  }, [dirty, draft]);

  function changed(next: Topology) { setTopology(next); setDirty(true); setNotice(""); }
  async function persist() {
    let saved: DesignDetail;
    try {
      saved = await api.save(authorization, design.id, { topology, expectedRevision: design.revision });
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) {
        setConflict(true);
        throw new Error(`${reason.message} Your local edits are retained. Export them or reload the server version.`);
      }
      if (reason instanceof ApiError && reason.status === 428) {
        // Without the revision the server requires, the save never reaches the CAS and
        // the editor would otherwise report a bare precondition failure.
        throw new Error(`${reason.message} Reload the server version to pick up a usable revision, then save again.`);
      }
      throw reason;
    }
    setDesign(saved); setTopology(saved.topology); setDirty(false); setConflict(false);
    setCanvasVersion((version) => version + 1);
    setNotice(`Saved revision ${saved.revision}.`);
    return saved;
  }
  async function save() {
    setBusy("save"); setError("");
    try { await persist(); } catch (reason) { setError(message(reason)); }
    finally { setBusy(null); }
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy("submit"); setError("");
    try {
      // Submit only a saved topology. A failed save never starts a run.
      if (dirty) await persist();
      const submission = await api.submit(authorization, design.id, {
        scenario: String(data.get("scenario")).trim(), scenarioNotes: String(data.get("notes") ?? ""),
        sessionId: crypto.randomUUID(),
      });
      router.push(`/submissions/${encodeURIComponent(submission.id)}`);
    } catch (reason) { setError(message(reason)); setBusy(null); }
  }
  async function remove() {
    setBusy("delete"); setError("");
    try { await api.remove(authorization, design.id); router.push("/"); }
    catch (reason) { setError(message(reason)); setBusy(null); }
  }
  function reloadServer() {
    if ((dirty || draft) && !window.confirm("Discard local edits and load the server version?")) return;
    reload();
  }
  function exportTopology() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(topology, null, 2)], { type: "application/json" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = `topology-${design.id}.json`; anchor.click(); URL.revokeObjectURL(url);
  }

  return <div className="page stack">
    <div className="section-heading">
      <div><Link href="/">← Designs</Link><h1>{design.name}</h1><p className="muted">{design.description || "No description provided."}</p></div>
      <div className="actions"><span className="badge">Revision {design.revision}</span><span className={dirty || draft ? "badge warning" : "badge"}>{dirty || draft ? "Unsaved edits" : "Saved"}</span><button onClick={reloadServer} disabled={!!busy}>Reload server version</button><button className="primary" onClick={save} disabled={!!busy || !dirty || draft || conflict}>{busy === "save" ? "Saving…" : "Save topology"}</button></div>
    </div>
    <p className="muted">Scenario: {design.scenario} · Created {date(design.createdAt)} · Saved {date(design.updatedAt)}</p>
    {error && <div className="notice error" role="alert"><p>{error}</p>{conflict && <button onClick={exportTopology}>Export local topology</button>}</div>}
    {notice && <p className="notice success" role="status">{notice}</p>}
    <SystemDesignCanvas key={canvasVersion} initialTopology={topology} disabled={!!busy} onChange={changed} onDraft={setDraft} />
    <section className="panel" aria-labelledby="run-title"><h2 id="run-title">Run this design</h2>
      <form onSubmit={submit} className="run-form">
        <label>Scenario<input name="scenario" defaultValue={design.scenario} required pattern={".*\\S.*"} disabled={!!busy} /></label>
        <label>Scenario notes<textarea name="notes" rows={2} disabled={!!busy} /></label>
        <button className="primary" disabled={!!busy || draft || conflict || topology.nodes.length === 0}>{busy === "submit" ? "Saving and submitting…" : dirty ? "Save and start run" : "Start run"}</button>
      </form>
      <Preflight topology={topology} />
      <p className="muted">Runs evaluate the persisted topology. Apply parameter edits before saving or running.</p>
    </section>
    <SubmissionList designId={design.id} />
    <section className="panel danger-zone"><h2>Delete design</h2><p>This permanently removes the saved design.</p>
      {confirmDelete ? <div className="actions"><button className="danger" onClick={remove} disabled={!!busy}>{busy === "delete" ? "Deleting…" : "Confirm deletion"}</button><button onClick={() => setConfirmDelete(false)} disabled={!!busy}>Cancel</button></div>
        : <button className="danger" onClick={() => setConfirmDelete(true)} disabled={!!busy}>Delete design…</button>}
    </section>
  </div>;
}
export function DesignWorkspace({ id }: { id: string }) {
  const { authorization } = useSession();
  const load = useCallback((signal: AbortSignal) => api.design(authorization, id, signal), [authorization, id]);
  const resource = useResource(load);
  if (resource.loading) return <div className="page"><Loading>Loading design topology…</Loading></div>;
  if (resource.error) return <div className="page"><Failure error={resource.error} retry={resource.reload} /><Link href="/">Return to designs</Link></div>;
  return resource.data && <Editor key={resource.data.id + resource.data.revision} initial={resource.data} reload={resource.reload} />;
}
