/**
 * The scenario value a form should submit before the user picks again.
 *
 * The catalog returns the simulator's declared workloads. A stored name that is
 * not one of them used to fall through to the first option, so the next save or
 * run stored steady state while the design still showed the name the user had
 * typed. An empty result keeps the control invalid until they choose. A blank
 * stored name is a new design, and the first declared workload is the choice
 * the control shows.
 */
export function initialScenarioValue(stored: string | undefined, options: readonly { value: string }[]): string {
  const trimmed = stored?.trim() ?? "";
  if (options.some((option) => option.value === trimmed)) return trimmed;
  if (trimmed) return "";
  return options[0]?.value ?? "";
}
