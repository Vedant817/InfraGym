import type { CreateDesignRequest, DesignDetail, DesignSummary, Evaluation, LatencyWindow, Measurements, SaveTopologyRequest, ScenarioOption, Submission, SubmissionDetail, SubmitRequest } from "./contracts";

export class ApiError extends Error {
  public status: number;
  public storedRevision?: number;
  public reloadRequired: boolean;
  constructor(status: number, message: string, storedRevision?: number, reloadRequired = false) {
    super(message); this.status = status; this.name = "ApiError";
    this.storedRevision = storedRevision; this.reloadRequired = reloadRequired;
  }
}
export async function request<T>(authorization: string, path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...options, cache: "no-store", headers: { "Content-Type": "application/json", ...options.headers, Authorization: authorization },
  });
  if (!response.ok) {
    let detail = "";
    let storedRevision: number | undefined;
    let reloadRequired = false;
    // Any string-valued `code` on an error body, so the not-ready case can be told
    // apart from a genuinely absent one without matching on prose.
    const bodyCodes = new Set<string>();
    try {
      const body: unknown = await response.json();
      if (body && typeof body === "object" && "message" in body && typeof body.message === "string") detail = body.message;
      else if (body && typeof body === "object" && "detail" in body && typeof body.detail === "string") detail = body.detail;
      if (body && typeof body === "object") {
        if ("storedRevision" in body && typeof body.storedRevision === "number") storedRevision = body.storedRevision;
        if ("reloadRequired" in body) reloadRequired = body.reloadRequired === true;
        if ("code" in body && typeof body.code === "string") bodyCodes.add(body.code);
      }
    } catch { /* HTTP status remains available when the server sends no JSON. */ }
    // 404 conflates two different things for the run endpoints: a run that has not
    // produced a simulation yet, and an id that does not exist or is not this
    // account's. The design service marks the first with an explicit not-ready code,
    // so the message says "not ready" instead of alarming the reader that their own
    // run has vanished.
    const notReady = response.status === 404 && bodyCodes.has("MEASUREMENTS_NOT_READY");
    const reason = response.status === 401 ? "Authentication failed. Sign out and check your credentials."
      : notReady ? "This run has not produced measurements yet. It will appear once the simulation reaches aggregation."
      : response.status === 404 ? "This resource was not found or is unavailable to your account."
      : response.status === 409 ? "The design changed on the server. Reload before saving again."
      : response.status === 428 ? "A revision is required to save. Reload the server version first."
      : `Request failed (${response.status}).`;
    throw new ApiError(response.status, detail ? `${reason} ${detail}` : reason, storedRevision, reloadRequired);
  }
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}
const id = encodeURIComponent;
export const api = {
  scenarios: (auth: string, signal?: AbortSignal) => request<ScenarioOption[]>(auth, "/designs/scenarios", { signal }),
  designs: (auth: string, signal?: AbortSignal) => request<DesignSummary[]>(auth, "/designs", { signal }),
  design: (auth: string, designId: string, signal?: AbortSignal) => request<DesignDetail>(auth, `/designs/${id(designId)}`, { signal }),
  create: (auth: string, body: CreateDesignRequest) => request<DesignDetail>(auth, "/designs", { method: "POST", body: JSON.stringify(body) }),
  save: (auth: string, designId: string, body: SaveTopologyRequest) => request<DesignDetail>(auth, `/designs/${id(designId)}/topology`, {
    method: "PUT", headers: { "If-Match": `"${body.expectedRevision}"` }, body: JSON.stringify({ topology: body.topology }),
  }),
  remove: (auth: string, designId: string) => request<void>(auth, `/designs/${id(designId)}`, { method: "DELETE" }),
  submit: (auth: string, designId: string, body: SubmitRequest) => request<Submission>(auth, `/designs/${id(designId)}/submissions`, { method: "POST", body: JSON.stringify(body) }),
  submissions: (auth: string, signal?: AbortSignal) => request<Submission[]>(auth, "/submissions", { signal }),
  submission: (auth: string, submissionId: string, signal?: AbortSignal) => request<SubmissionDetail>(auth, `/submissions/${id(submissionId)}`, { signal }),
  measurements: (auth: string, submissionId: string, signal?: AbortSignal) => request<Measurements>(auth, `/submissions/${id(submissionId)}/measurements`, { signal }),
  evaluation: (auth: string, submissionId: string, signal?: AbortSignal) => request<Evaluation>(auth, `/submissions/${id(submissionId)}/evaluation`, { signal }),
  series: (auth: string, submissionId: string, nodeId: string, signal?: AbortSignal) => request<LatencyWindow[]>(auth, `/submissions/${id(submissionId)}/metrics/${id(nodeId)}/series?metric=latency_ms`, { signal }),
};
