"use client";

import { useCallback, useState } from "react";
import { api } from "@/lib/api";
import { initialScenarioValue } from "@/lib/scenario";
import { useResource } from "@/lib/use-resource";
import { useSession } from "./session-provider";
import { Failure, Loading } from "./resource-state";

/**
 * A scenario is chosen from the workloads the simulator declares.
 *
 * A free-text identifier used to be accepted and, when it was not one of those
 * workloads, stored and run as steady state. The label on the design still showed
 * the typed text, so the result described a different experiment from the one
 * the user asked for.
 */
export function ScenarioField({ name, defaultValue, disabled }: { name: string; defaultValue?: string; disabled?: boolean }) {
  const { authorization } = useSession();
  const load = useCallback((signal: AbortSignal) => api.scenarios(authorization, signal), [authorization]);
  const resource = useResource(load);
  const options = resource.data ?? [];
  const stored = defaultValue?.trim() ?? "";
  const unresolved = stored.length > 0 && !options.some((option) => option.value === stored);
  const [value, setValue] = useState<string | null>(null);
  const selected = value ?? initialScenarioValue(defaultValue, options);
  const description = options.find((option) => option.value === selected)?.description;

  if (resource.loading) return <div><Loading>Loading workloads…</Loading><input className="sr-only" name={name} value="" required readOnly tabIndex={-1} aria-hidden="true" /></div>;
  if (resource.error || options.length === 0) {
    return <div>
      <Failure error={resource.error || "The simulator did not return any workloads."} retry={resource.reload} />
      <input className="sr-only" name={name} value="" required readOnly tabIndex={-1} aria-hidden="true" />
    </div>;
  }
  return <div className="stack">
    <label>Scenario
      <select name={name} value={selected} required disabled={disabled} aria-describedby={description ? `${name}-help` : undefined} onChange={(event) => setValue(event.target.value)}>
        {unresolved && <option value="">Choose a workload</option>}
        {options.map((option) => <option key={option.value} value={option.value}>{option.displayName}</option>)}
      </select>
    </label>
    {description && <p id={`${name}-help`} className="muted">{description}</p>}
    {unresolved && selected === "" && <p className="notice error" role="alert">Stored scenario “{stored}” is not a workload this simulator runs. Choose one from the list before saving or starting a run.</p>}
  </div>;
}
