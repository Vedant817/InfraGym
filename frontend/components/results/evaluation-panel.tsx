import type { Evaluation, JsonValue, Topology } from "@/lib/contracts";
import { withComponentNames } from "@/lib/naming";
import { date, number } from "@/lib/format";
function Evidence({ value }: { value: JsonValue }) {
  return typeof value === "string" ? <p className="evidence">{value || "No evidence supplied."}</p>
    : <pre className="evidence">{value == null ? "No evidence supplied." : JSON.stringify(value, null, 2)}</pre>;
}
function TextList({ title, items }: { title: string; items: string[] }) {
  return <section><h3>{title}</h3>{items.length ? <ul className="text-list">{items.map((item, index) => <li key={index}>{item}</li>)}</ul> : <p className="muted">No {title.toLowerCase()} reported.</p>}</section>;
}
export function EvaluationPanel({ evaluation, topology }: { evaluation: Evaluation; topology?: Topology | null }) {
  const { bottlenecks, findings } = withComponentNames(evaluation, topology ?? null);
  return <section className="panel stack" aria-labelledby="evaluation-title">
    <div className="section-heading"><div><p className="eyebrow">Evaluation</p><h2 id="evaluation-title">Score and findings</h2></div><div className="score"><strong>{number(evaluation.overallScore)}</strong><span>/ 100 · arithmetic score</span></div></div>
    <p className="notice">The score is calculated from run measurements. It is an arithmetic result, not a model opinion.</p>
    {evaluation.degraded && <div className="notice warning" role="status"><strong>Degraded evaluation: model narrative unavailable</strong><p>The arithmetic score is complete; no model narrative was added.</p><p>{evaluation.degradedReason || "The service did not provide a degradation reason."}</p></div>}
    <div><span className="badge">{evaluation.verdict}</span><p>{evaluation.summary || "No narrative summary was provided."}</p></div>
    <p className="muted">Evaluated {date(evaluation.evaluatedAt)} · Model: {evaluation.model || "Not recorded"} · Evaluation latency: {evaluation.latencyMs == null ? "Unknown" : `${number(evaluation.latencyMs)} ms`}</p>
    {evaluation.warnings.length > 0 && <div className="notice warning"><h3>Evaluation warnings</h3><ul>{evaluation.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></div>}
    <section><h3>Bottlenecks</h3>{evaluation.bottlenecks.length === 0 ? <p className="muted">No bottlenecks reported.</p> : <div className="table-scroll"><table>
      <caption className="sr-only">Observed measurements and threshold values for bottlenecks</caption>
      <thead><tr><th scope="col">Component / metric</th><th scope="col">Observed</th><th scope="col">Threshold</th><th scope="col">Explanation</th></tr></thead>
      <tbody>{bottlenecks.map((bottleneck, index) => <tr key={index}><th scope="row">{bottleneck.label ?? <code>{bottleneck.nodeId}</code>}<p>{bottleneck.metric}</p></th><td className="numeric">{number(bottleneck.observedValue)} {bottleneck.unit}</td><td className="numeric">{number(bottleneck.thresholdValue)} {bottleneck.unit}</td><td>{bottleneck.explanation}</td></tr>)}</tbody>
    </table></div>}</section>
    <section><h3>Findings · service severity order</h3>{findings.length === 0 ? <p className="muted">No findings reported.</p> : <ol className="findings">{findings.map((finding, index) => <li className="finding" key={index}>
      <div className="actions"><span className="badge">{finding.severity}</span><span className="muted">{finding.category}</span>{finding.label ?? <code>{finding.nodeId}</code>}</div>
      <h4>{finding.title}</h4><p>{finding.detail}</p><h5>Evidence</h5><Evidence value={finding.evidence} /><h5>Recommendation</h5><p>{finding.recommendation || "No recommendation supplied."}</p>
      {finding.relatedLabels.length > 0 && <p className="muted">Related components: {finding.relatedLabels.map((label, related) => label ?? finding.relatedNodeIds[related]).join(", ")}</p>}
    </li>)}</ol>}</section>
    {evaluation.worstFinding != null && <details><summary>Worst finding reported by the service</summary><Evidence value={evaluation.worstFinding} /></details>}
    <div className="three-column"><TextList title="Strengths" items={evaluation.strengths} /><TextList title="Weaknesses" items={evaluation.weaknesses} /><TextList title="Recommendations" items={evaluation.recommendations} /></div>
    <section><h3>References</h3>{evaluation.references.length ? <ul className="text-list">{evaluation.references.map((reference, index) => <li key={index}><Evidence value={reference} /></li>)}</ul> : <p className="muted">No references supplied.</p>}</section>
  </section>;
}
