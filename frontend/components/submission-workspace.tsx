"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { DesignDetail, Topology } from "@/lib/contracts";
import { date, rejectionNotice, terminal } from "@/lib/format";
import { rejectionFindings } from "@/lib/naming";
import { useSubmission } from "@/lib/use-submission";
import { useSession } from "./session-provider";
import { Failure, Loading } from "./resource-state";
import { StageTimeline } from "./results/stage-timeline";
import { RunResults } from "./results/run-results";

/**
 * The topology the run was built from, used only to give its components their names back.
 *
 * Loaded here because measurements, bottlenecks and findings report components by id
 * alone, and those ids are the editor's UUIDs. A design that has been deleted, or one
 * this account cannot read, simply yields null and the panels fall back to ids.
 */
function useDesignTopology(designId: string | null | undefined, authorization: string): Topology | null {
  const [topology, setTopology] = useState<Topology | null>(null);
  useEffect(() => {
    if (!designId) return;
    const controller = new AbortController();
    api.design(authorization, designId, controller.signal)
      .then((detail: DesignDetail) => setTopology(detail.topology))
      // A missing design is not a run error; the panels fall back to component ids.
      .catch(() => { if (!controller.signal.aborted) setTopology(null); });
    return () => controller.abort();
  }, [authorization, designId]);
  return topology;
}

export function SubmissionWorkspace({ id }: { id: string }) {
  const { authorization } = useSession();
  const { detail, error, lastUpdated, retry } = useSubmission(authorization, id);
  const submission = detail?.submission;
  const topology = useDesignTopology(submission?.designId, authorization);
  const rejected = submission?.status === "rejected";
  const findings = rejectionFindings(detail?.stages);
  const notice = rejectionNotice(findings, rejected);
  const resultsAvailable = submission && ["aggregating", "evaluating", "completed", "failed"].includes(submission.status);
  return <div className="page stack">
    <div className="section-heading"><div><Link href="/submissions">← All runs</Link><h1>Run inspection</h1><p className="mono">{id}</p></div><button onClick={retry}>Refresh run</button></div>
    {!detail && !error && <Loading>Loading submission and stage events…</Loading>}
    {error && <div><p className="notice warning" role="status">{lastUpdated ? `Progress is stale. Last successful update: ${date(lastUpdated)}. Automatic retries continue.` : "Progress could not be loaded. Automatic retries continue."}</p><Failure error={error} retry={retry} /></div>}
    {submission && <>
      <section className="panel stack" aria-labelledby="progress-title">
        <div className="section-heading"><h2 id="progress-title">Run progress</h2><span className={`badge ${submission.status === "failed" || rejected ? "error" : terminal(submission.status) ? "success" : ""}`} role="status">{submission.status}</span></div>
        <dl className="run-stats"><div><dt>Design</dt><dd><Link href={`/designs/${encodeURIComponent(submission.designId)}`}>{submission.designId}</Link></dd></div><div><dt>Scenario</dt><dd>{submission.scenario}</dd></div><div><dt>Submitted</dt><dd>{date(submission.submittedAt)}</dd></div><div><dt>Completed</dt><dd>{date(submission.completedAt)}</dd></div></dl>
        <div aria-live="polite"><strong>{submission.currentStage || "Waiting for the first stage"}</strong><p>{submission.currentMessage || "No current-stage message reported."}</p></div>
        {submission.status === "failed" && <p className="notice error" role="alert">This run failed. Inspect the current message and stage audit log. Any available partial results appear below.</p>}
        {rejected && <div className="notice error" role="alert">
          <p>{notice}</p>
          {findings.length > 0 && <ul className="findings-list">{findings.map((finding, index) => <li key={`${finding.code ?? "finding"}-${index}`}>
            {finding.field && <code>{finding.field}</code>}{finding.code && <span className="badge">{finding.code}</span>}<span>{finding.message}</span>
          </li>)}</ul>}
          <p><Link href={`/designs/${encodeURIComponent(submission.designId)}`}>Fix the topology in the design editor</Link> and start a new run.</p>
        </div>}
        <p className="muted">{terminal(submission.status) ? "Run reached a terminal state; automatic polling stopped." : "Progress is polled every 2 seconds; retries back off after errors."} Last update: {date(lastUpdated)}.</p>
      </section>
      <StageTimeline stages={detail.stages} />
      {resultsAvailable ? <RunResults key={submission.status} id={id} status={submission.status} topology={topology} />
        : rejected ? <p className="notice">No measurements or evaluation exist for this run. It was rejected before the simulation started.</p>
        : <p className="notice">Measurements and evaluation will appear after simulation reaches aggregation. No score is available yet.</p>}
      <details className="panel"><summary>Submission record and attached simulation</summary><pre className="evidence">{JSON.stringify({ submission, simulation: detail.simulation }, null, 2)}</pre></details>
    </>}
  </div>;
}
