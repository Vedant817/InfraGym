export function number(value: number | null | undefined, digits = 2): string {
  return value == null || !Number.isFinite(value) ? "Unknown" : value.toLocaleString(undefined, { maximumFractionDigits: digits });
}
export function percent(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? "Unknown" : `${number(value * 100)}%`;
}
export function latency(measured: boolean, value: number | null | undefined): string {
  return !measured || value == null || !Number.isFinite(value) ? "Unknown" : `${number(value)} ms`;
}
export function date(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : "Not recorded";
}
export function terminal(status: string): boolean { return status === "completed" || status === "failed" || status === "rejected"; }

/**
 * A dead run that produced no measurements, and why.
 *
 * A rejected run never reaches aggregation, so promising future measurements is a
 * promise nothing will keep. The compiler's per-node findings are rendered above
 * this, so the message points at them instead of repeating them.
 */
export function rejectionNotice(findings: readonly { message: string }[], terminal: boolean): string | null {
  if (!terminal) return null;
  if (findings.length === 0) return "The topology was rejected before a simulation started, so there are no measurements or evaluation.";
  const first = findings[0];
  const extra = findings.length > 1 ? ` (and ${findings.length - 1} more)` : "";
  return `The topology was rejected before a simulation started, so there are no measurements or evaluation. First problem: ${first.message}${extra}`;
}

export function message(error: unknown): string { return error instanceof Error ? error.message : "The request failed. Try again."; }
