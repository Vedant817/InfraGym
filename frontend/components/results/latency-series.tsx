"use client";
import { useCallback } from "react";
import { api } from "@/lib/api";
import type { LatencyWindow } from "@/lib/contracts";
import { number } from "@/lib/format";
import { useResource } from "@/lib/use-resource";
import { useSession } from "../session-provider";
import { Empty, Failure, Loading } from "../resource-state";

const seriesKeys = ["p50", "p95", "p99", "max"] as const;
const colors = { p50: "#24618a", p95: "#695594", p99: "#a13e31", max: "#53616d" };
function SeriesChart({ windows }: { windows: LatencyWindow[] }) {
  const available = windows.filter((window) => window.sampleCount > 0);
  const values = available.flatMap((window) => seriesKeys.map((key) => window[key])).filter((value): value is number => value != null && Number.isFinite(value));
  const units = [...new Set(windows.map((window) => window.unit))];
  if (!values.length) return <Empty>No latency samples were reported in these windows. Latency is unknown.</Empty>;
  if (units.length > 1) return <Empty>Windows use different units. Inspect the data table; a combined chart would be misleading.</Empty>;
  const minimumTick = Math.min(...windows.map((window) => window.windowStartTick));
  const maximumTick = Math.max(...windows.map((window) => window.windowEndTick));
  const maxValue = Math.max(...values, 1) * 1.1;
  const x = (tick: number) => 70 + ((tick - minimumTick) / Math.max(1, maximumTick - minimumTick)) * 790;
  const y = (value: number) => 230 - (value / maxValue) * 200;
  function path(key: typeof seriesKeys[number]) {
    let drawing = false;
    return windows.map((window) => {
      const value = window[key];
      if (!window.sampleCount || value == null || !Number.isFinite(value)) { drawing = false; return ""; }
      const segment = `${drawing ? "L" : "M"}${x((window.windowStartTick + window.windowEndTick) / 2)},${y(value)}`;
      drawing = true; return segment;
    }).join(" ");
  }
  return <figure className="latency-chart">
    <svg viewBox="0 0 900 280" role="img" aria-labelledby="latency-chart-title latency-chart-description">
      <title id="latency-chart-title">Latency percentiles by simulation tick window</title>
      <desc id="latency-chart-description">P50, P95, P99, and maximum measured latency in {units[0]}. Gaps indicate missing samples. Exact values are in the table below.</desc>
      {[0, 0.25, 0.5, 0.75, 1].map((ratio) => <g key={ratio}><line x1="70" x2="860" y1={y(maxValue * ratio)} y2={y(maxValue * ratio)} stroke="#dce2e9" /><text x="60" y={y(maxValue * ratio) + 4} textAnchor="end">{number(maxValue * ratio, 1)}</text></g>)}
      <text x="70" y="17">Latency ({units[0]})</text><text x="70" y="260">Tick {minimumTick}</text><text x="860" y="260" textAnchor="end">Tick {maximumTick}</text>
      {seriesKeys.map((key, index) => <g key={key}><path d={path(key)} stroke={colors[key]} fill="none" strokeWidth="2" strokeDasharray={index ? `${index * 3} 3` : undefined} />{available.map((window) => window[key] != null && Number.isFinite(window[key]) ? <circle key={window.windowStartTick} cx={x((window.windowStartTick + window.windowEndTick) / 2)} cy={y(window[key] as number)} r="2.5" fill={colors[key]} /> : null)}</g>)}
    </svg>
    <figcaption className="actions">{seriesKeys.map((key) => <span className="chart-legend" key={key}><i style={{ backgroundColor: colors[key] }} aria-hidden="true" />{key.toUpperCase()}</span>)}<span className="muted">Empty windows remain gaps.</span></figcaption>
  </figure>;
}
export function LatencySeries({ submissionId, nodeId, measured }: { submissionId: string; nodeId: string; measured: boolean }) {
  const { authorization } = useSession();
  const load = useCallback((signal: AbortSignal) => measured ? api.series(authorization, submissionId, nodeId, signal) : Promise.resolve<LatencyWindow[]>([]), [authorization, submissionId, nodeId, measured]);
  const resource = useResource(load);
  if (!measured) return <Empty>This component reported no samples. Its latency is unknown; no zero-valued series is substituted.</Empty>;
  return <div className="stack">
    {resource.loading && <Loading>Loading measured latency windows…</Loading>}
    {resource.error && <Failure error={resource.error} retry={resource.reload} />}
    {resource.data?.length === 0 && <Empty>No latency windows are available for this component.</Empty>}
    {!!resource.data?.length && <>
      <SeriesChart windows={resource.data} />
      <details><summary>Exact window measurements ({resource.data.length} windows)</summary><div className="table-scroll"><table>
        <caption className="sr-only">Latency percentiles for each simulation tick window</caption>
        <thead><tr><th scope="col">Ticks</th><th scope="col">Samples</th><th scope="col">P50</th><th scope="col">P95</th><th scope="col">P99</th><th scope="col">Maximum</th><th scope="col">Unit</th></tr></thead>
        <tbody>{resource.data.map((window, index) => <tr key={index}><th scope="row">{window.windowStartTick}–{window.windowEndTick}</th><td>{number(window.sampleCount, 0)}</td>{seriesKeys.map((key) => <td className="numeric" key={key}>{window.sampleCount ? number(window[key]) : "Unknown"}</td>)}<td>{window.unit}</td></tr>)}</tbody>
      </table></div></details>
    </>}
  </div>;
}
