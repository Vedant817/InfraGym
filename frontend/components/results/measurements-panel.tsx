"use client";
import { useState } from "react";
import type { Measurements, NamedNodeMeasurement, Topology } from "@/lib/contracts";
import { nameMeasurements } from "@/lib/naming";
import { labelById } from "@/lib/topology";
import { date, latency, number, percent } from "@/lib/format";
import { LatencySeries } from "./latency-series";
function measuredValue(node: NamedNodeMeasurement, value: number | null, asPercent = false): string {
  return !node.measured ? "Unknown" : asPercent ? percent(value) : number(value);
}
export function MeasurementsPanel({ measurements, submissionId, topology }: { measurements: Measurements; submissionId: string; topology?: Topology | null }) {
  const { simulation } = measurements;
  const nodes = nameMeasurements(measurements, topology ?? null);
  const [chosen, setChosen] = useState("");
  const selected = nodes.find((node) => node.nodeId === chosen) ?? nodes.find((node) => node.measured) ?? nodes[0];
  return <section className="panel stack" aria-labelledby="measurements-title">
    <div className="section-heading"><div><p className="eyebrow">Measured simulation</p><h2 id="measurements-title">Component performance</h2></div><span className="badge">{simulation.status}</span></div>
    {!simulation.reconciles && <p className="notice warning" role="status"><strong>Run accounting does not reconcile.</strong> The reported totals do not add up. Interpret the measurements with this caveat.</p>}
    {simulation.truncated && <p className="notice warning" role="status"><strong>Truncated run.</strong> Executed {number(simulation.tickCount, 0)} of {number(simulation.totalTicks, 0)} requested ticks.</p>}
    {simulation.warnings.length > 0 && <div className="notice warning"><h3>Simulation warnings</h3><ul>{simulation.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></div>}
    <dl className="run-stats">
      <div><dt>Offered</dt><dd>{number(simulation.offered, 0)}</dd></div><div><dt>Served</dt><dd>{number(simulation.served, 0)}</dd></div>
      <div><dt>Failed</dt><dd>{number(simulation.failed, 0)}</dd></div><div><dt>Queued</dt><dd>{number(simulation.queued, 0)}</dd></div>
      <div><dt>Completion</dt><dd>{percent(simulation.completionRate)}</dd></div><div><dt>Executed ticks</dt><dd>{simulation.tickCount} / {simulation.totalTicks}</dd></div>
      <div><dt>Tick duration</dt><dd>{number(simulation.tickMillis)} ms</dd></div><div><dt>Accounting</dt><dd>{simulation.reconciles ? "Reconciles" : "Does not reconcile"}</dd></div>
    </dl>
    <p className="muted">Scenario: {simulation.scenario} · Started {date(simulation.startedAt)} · Completed {date(simulation.completedAt)} · Simulation <code>{simulation.id}</code></p>
    <p>Worst P99: {measurements.worstNodeId ? <>{labelById(topology ?? null, measurements.worstNodeId) ?? <code>{measurements.worstNodeId}</code>} · {latency(nodes.some((node) => node.nodeId === measurements.worstNodeId && node.measured), measurements.worstP99Ms)}</> : "No worst component reported."}</p>
    {nodes.length === 0 ? <p className="notice">No component measurements were returned.</p> : <div className="table-scroll"><table>
      <caption>Per-component measurements. No samples means unknown latency and rates.</caption>
      <thead><tr><th scope="col">Component</th><th scope="col">Samples</th><th scope="col">P50</th><th scope="col">P95</th><th scope="col">P99</th><th scope="col">Maximum</th><th scope="col">Error avg / peak</th><th scope="col">RPS avg / peak</th><th scope="col">Peak utilization</th><th scope="col">Cache hit avg</th><th scope="col">Queue peak</th><th scope="col">Saturated</th></tr></thead>
      <tbody>{nodes.map((node) => <tr key={node.nodeId}>
        <th scope="row"><button className="text-button mono" onClick={() => setChosen(node.nodeId)} aria-label={`Inspect latency series for ${node.label ?? node.nodeId}`}>{node.label ?? node.nodeId}</button><p className="muted">{node.nodeType}{!node.measured && " · No samples"}</p></th>
        <td className="numeric">{number(node.sampleCount, 0)}</td>
        {[node.p50Ms, node.p95Ms, node.p99Ms, node.maxMs].map((value, index) => <td className="numeric" key={index}>{latency(node.measured, value)}</td>)}
        <td className="numeric">{measuredValue(node, node.avgErrorRate, true)} / {measuredValue(node, node.peakErrorRate, true)}</td>
        <td className="numeric">{measuredValue(node, node.avgRps)} / {measuredValue(node, node.peakRps)}</td>
        <td className="numeric">{measuredValue(node, node.peakUtilization, true)}</td><td className="numeric">{measuredValue(node, node.avgCacheHitRate, true)}</td>
        <td className="numeric">{measuredValue(node, node.peakQueueDepth)}</td><td>{node.measured ? node.saturated ? "Yes" : "No" : "Unknown"}</td>
      </tr>)}</tbody>
    </table></div>}
    {selected && <section className="stack" aria-labelledby="series-title"><h3 id="series-title">Latency across simulation windows</h3>
      <label className="series-select">Component<select value={selected.nodeId} onChange={(event) => setChosen(event.target.value)}>{nodes.map((node) => <option key={node.nodeId} value={node.nodeId}>{node.label ?? node.nodeId} · {node.nodeType}{!node.measured ? " · No samples" : ""}</option>)}</select></label>
      <LatencySeries key={selected.nodeId} submissionId={submissionId} nodeId={selected.nodeId} measured={selected.measured} />
    </section>}
  </section>;
}
