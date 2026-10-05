"use client";
import Link from "next/link";
import { useCallback } from "react";
import { api } from "@/lib/api";
import { date } from "@/lib/format";
import { useResource } from "@/lib/use-resource";
import { useSession } from "./session-provider";
import { Empty, Failure, Loading } from "./resource-state";

export function SubmissionList({ designId }: { designId?: string }) {
  const { authorization } = useSession();
  const load = useCallback((signal: AbortSignal) => api.submissions(authorization, signal), [authorization]);
  const resource = useResource(load);
  const submissions = resource.data?.filter((submission) => !designId || submission.designId === designId);
  return <section className="panel" aria-labelledby="runs-title">
    <div className="section-heading"><h2 id="runs-title">{designId ? "Design runs" : "All runs"}</h2><button disabled={resource.loading} onClick={resource.reload}>Refresh runs</button></div>
    {resource.loading && <Loading>Loading run history…</Loading>}
    {resource.error && <Failure error={resource.error} retry={resource.reload} />}
    {submissions?.length === 0 && <Empty>No runs recorded{designId ? " for this design" : ""}. Start a run from a saved design.</Empty>}
    {!!submissions?.length && <div className="table-scroll"><table>
      <caption className="sr-only">Submission history</caption>
      <thead><tr><th scope="col">Run</th><th scope="col">Design</th><th scope="col">Scenario</th><th scope="col">Status</th><th scope="col">Current stage</th><th scope="col">Submitted / completed</th></tr></thead>
      <tbody>{submissions.map((submission) => <tr key={submission.id}>
        <td><Link className="mono" href={`/submissions/${encodeURIComponent(submission.id)}`}>{submission.id}</Link></td>
        <td><Link className="mono" href={`/designs/${encodeURIComponent(submission.designId)}`}>{submission.designId}</Link></td>
        <td>{submission.scenario}</td><td><span className={`badge ${submission.status === "failed" ? "error" : ""}`}>{submission.status}</span></td>
        <td>{submission.currentStage || "Not recorded"}{submission.currentMessage && <p className="muted">{submission.currentMessage}</p>}</td>
        <td>{date(submission.submittedAt)}<p className="muted">{submission.completedAt ? date(submission.completedAt) : "Not completed"}</p></td>
      </tr>)}</tbody>
    </table></div>}
  </section>;
}
