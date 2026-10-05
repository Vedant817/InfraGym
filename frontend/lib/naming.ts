import type { Evaluation, Measurements, NamedBottleneck, NamedFinding, NamedNodeMeasurement, StageEvent, Topology, ValidationFinding } from "./contracts";
// Written with an explicit extension because this module is loaded directly by the
// Node test runner, which resolves imports itself and will not infer ".ts".
import { labelById } from "./topology.ts";

/**
 * Resolves component names on a measurement view against the submitted topology.
 *
 * The measurements endpoint reports only `nodeId`. Without this, every row of the
 * per-component table and every option in the component selector is a raw UUID,
 * because that is the id the editor assigns to each node.
 *
 * An id the topology does not contain keeps a null label, so the caller falls back to
 * the id rather than showing another component's name against these numbers.
 */
export function nameMeasurements(measurements: Measurements, topology: Topology | null): NamedNodeMeasurement[] {
  return measurements.nodes.map((node) => ({ ...node, label: labelById(topology, node.nodeId) }));
}

/**
 * Resolves component names on bottlenecks and findings.
 *
 * Both report their component by id alone, and those ids are UUIDs the editor
 * generated, so the narrative would otherwise read as a wall of hex.
 */
export function withComponentNames(evaluation: Evaluation, topology: Topology | null): { bottlenecks: NamedBottleneck[]; findings: NamedFinding[] } {
  return {
    bottlenecks: evaluation.bottlenecks.map((bottleneck) => ({ ...bottleneck, label: labelById(topology, bottleneck.nodeId) })),
    findings: evaluation.findings.map((finding) => ({
      ...finding,
      label: labelById(topology, finding.nodeId),
      relatedLabels: finding.relatedNodeIds.map((id) => labelById(topology, id)),
    })),
  };
}

/**
 * The compiler's per-node reasons for a rejection.
 *
 * A rejected run's headline message is prose — "Topology failed validation" — which
 * names no node and no cause. The structured findings travel on the lifecycle event's
 * `details` as `findings: [{ field, code, message }]`, so they are read from the
 * rejecting stage rather than scraped from that prose.
 *
 * Stages without findings yield an empty list, which the run page reports as "no
 * detail reported" rather than silently rendering nothing. An entry that is not an
 * object with a string message is skipped, so a malformed payload cannot render as
 * `undefined`.
 */
export function rejectionFindings(stages: readonly StageEvent[] | undefined): ValidationFinding[] {
  const collected: ValidationFinding[] = [];
  for (const stage of stages ?? []) {
    if (stage.status !== "rejected") continue;
    const findings = stage.details?.findings;
    if (!Array.isArray(findings)) continue;
    for (const finding of findings) {
      if (finding && typeof finding === "object" && typeof (finding as { message?: unknown }).message === "string") {
        collected.push(finding as unknown as ValidationFinding);
      }
    }
  }
  return collected;
}