export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
/**
 * A canvas node exactly as the design service stores it.
 *
 * `label` is deliberately absent. The wire contract keeps it at `data.label`
 * (`NodeData.label` on the server), and a top-level `label` is silently discarded
 * on save because the deserialisation target ignores unknown properties. Declaring
 * it here is what let the whole editor render `undefined` as every node name; use
 * {@link nodeLabel} from `lib/topology` to read a display name instead.
 */
export interface CanvasNode {
  id: string;
  type: string;
  data: JsonObject;
  position: { x: number; y: number };
}
export interface CanvasEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  data?: JsonObject;
  [key: string]: JsonValue | undefined;
}
export interface Topology { nodes: CanvasNode[]; edges: CanvasEdge[] }
export interface DesignSummary {
  id: string;
  name: string;
  description: string | null;
  scenario: string;
  nodeCount: number;
  edgeCount: number;
  revision: number;
  createdAt: string;
  updatedAt: string;
}
export interface DesignDetail extends Omit<DesignSummary, "nodeCount" | "edgeCount"> { topology: Topology }
export interface CreateDesignRequest { name: string; description: string; scenario: string; topology: Topology }
export interface SaveTopologyRequest { topology: Topology; expectedRevision: number }
export interface SubmitRequest { scenario: string; scenarioNotes: string; sessionId: string }
/**
 * A submission status as the design service reports it.
 *
 * `rejected` is present because the compiler marks a topology it refuses
 * `REJECTED` and the service stores it; leaving it out of the union meant the run
 * page could not treat it as terminal and kept polling a run that will never move.
 */
export type SubmissionStatus =
  | "received" | "validating" | "rejected" | "compiling" | "compiled"
  | "simulating" | "aggregating" | "evaluating" | "completed" | "failed";
export interface Submission {
  id: string;
  designId: string;
  simulationId: string | null;
  status: SubmissionStatus;
  scenario: string;
  submittedAt: string;
  completedAt?: string | null;
  currentStage: string | null;
  currentMessage: string | null;
}
/**
 * One stage a submission passed through.
 *
 * `details` is the lifecycle event's structured payload, absent when the producer
 * sent none. The compiler puts per-finding attribution here
 * (`findings: [{ field, code, message }]`) on a rejection, which is the only place
 * the user can learn *which* node was refused and why.
 */
export interface StageEvent {
  stage: string;
  status: string;
  message: string | null;
  occurredAt: string;
  receivedAt: string;
  details?: JsonObject;
}

/** One blocking validation finding, attributed to the node or field that caused it. */
export interface ValidationFinding {
  field?: string | null;
  code?: string | null;
  message: string;
}
export interface SubmissionDetail {
  submission: Submission;
  stages: StageEvent[];
  simulation: JsonObject | null;
  evaluation: Evaluation | null;
}
export interface SimulationMeasurement {
  id: string;
  scenario: string;
  status: string;
  tickCount: number;
  totalTicks: number;
  tickMillis: number;
  truncated: boolean;
  offered: number;
  served: number;
  failed: number;
  queued: number;
  completionRate: number | null;
  reconciles: boolean;
  warnings: string[];
  startedAt: string | null;
  completedAt: string | null;
}
export interface NodeMeasurement {
  nodeId: string;
  nodeType: string;
  measured: boolean;
  sampleCount: number;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  maxMs: number | null;
  avgErrorRate: number | null;
  peakErrorRate: number | null;
  peakRps: number | null;
  avgRps: number | null;
  peakUtilization: number | null;
  avgCacheHitRate: number | null;
  peakQueueDepth: number | null;
  saturated: boolean;
}
export interface Measurements { simulation: SimulationMeasurement; nodes: NodeMeasurement[]; worstNodeId: string | null; worstP99Ms: number | null }

/**
 * A node measurement carrying the component's display name.
 *
 * Measurements report only `nodeId`, and every node this editor creates has a UUID
 * id, so a results table keyed on the id alone shows a hex string where a name
 * belongs. `label` is resolved from the submitted topology; `null` means it could
 * not be resolved and the reader falls back to the id rather than a guess.
 */
export interface NamedNodeMeasurement extends NodeMeasurement { label: string | null }

/**
 * A bottleneck or finding whose component has been resolved to a display name.
 * `label` is null when the id is unknown to the topology, in which case the id is
 * still shown — the evaluator also reports a pseudo-node ("architecture") that was
 * never drawn, and giving it a real component's name would be a lie.
 */
export interface NamedBottleneck extends Bottleneck { label: string | null }
export interface NamedFinding extends Finding { label: string | null; relatedLabels: (string | null)[] }
export interface LatencyWindow {
  windowStartTick: number;
  windowEndTick: number;
  sampleCount: number;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  max: number | null;
  unit: string;
}
export interface Bottleneck { nodeId: string; metric: string; observedValue: number; unit: string; thresholdValue: number; explanation: string }
export interface Finding { nodeId: string; severity: string; category: string; title: string; detail: string; evidence: JsonValue; recommendation: string; relatedNodeIds: string[] }
export interface Evaluation {
  id: string;
  overallScore: number;
  verdict: string;
  summary: string | null;
  degraded: boolean;
  degradedReason: string | null;
  model: string | null;
  latencyMs: number | null;
  worstFinding: JsonValue;
  strengths: string[];
  weaknesses: string[];
  recommendations: string[];
  bottlenecks: Bottleneck[];
  findings: Finding[];
  references: JsonValue[];
  warnings: string[];
  evaluatedAt: string;
}
