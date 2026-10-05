"use client";
import { useCallback } from "react";
import type { Topology } from "@/lib/contracts";
import { api } from "@/lib/api";
import { useResource } from "@/lib/use-resource";
import { useSession } from "../session-provider";
import { Failure, Loading } from "../resource-state";
import { EvaluationPanel } from "./evaluation-panel";
import { MeasurementsPanel } from "./measurements-panel";

export function RunResults({ id, status, topology }: { id: string; status: string; topology?: Topology | null }) {
  const { authorization } = useSession();
  const loadMeasurements = useCallback((signal: AbortSignal) => api.measurements(authorization, id, signal), [authorization, id]);
  const measurements = useResource(loadMeasurements);
  const loadEvaluation = useCallback((signal: AbortSignal) => api.evaluation(authorization, id, signal), [authorization, id]);
  const evaluation = useResource(loadEvaluation);
  return <div className="stack">
    <section aria-label="Measurement request status">
      {measurements.loading && <Loading>Loading run measurements…</Loading>}
      {measurements.error && <><p className="muted">{status === "failed" ? "The failed run may have no measurements." : "Measurements may not yet be available for this stage."}</p><Failure error={measurements.error} retry={measurements.reload} /></>}
      {measurements.data && <MeasurementsPanel key={measurements.data.simulation.id} submissionId={id} measurements={measurements.data} topology={topology} />}
    </section>
    <section aria-label="Evaluation request status">
      {evaluation.loading && <Loading>Loading evaluation…</Loading>}
      {evaluation.error && <><p className="muted">{status === "failed" ? "The failed run may have no evaluation." : status === "completed" ? "The run is complete, but the evaluation request failed." : "Evaluation may not yet be available. This does not imply a zero score."}</p><Failure error={evaluation.error} retry={evaluation.reload} /></>}
      {evaluation.data && <EvaluationPanel evaluation={evaluation.data} topology={topology} />}
    </section>
  </div>;
}
