# ArchForge frontend

The App Router pages use the API gateway through a same-origin, allowlisted
`/api/v1/[...path]` Route Handler. Set `API_GATEWAY_URL` in `.env.local` for a remote
gateway; the default is `http://localhost:8080`. Run `npm run dev` (Next.js defaults
to port 3000; use `npm run dev -- --port <port>` to override). Sign in with a real Basic-auth
account. Credentials survive a refresh in the tab's `sessionStorage` and are forwarded per
request. See `lib/session-store.ts` for the security tradeoff, which is a real one:
the value stored is the user's actual password, base64-encoded rather than encrypted,
and any script on the origin can read it. Use HTTPS when hosting this application.
There is no runtime fixture source.

## Component names

A node's label lives at `data.label`, which is where the wire contract stores it
(`NodeData.label` on the server). A top-level `label` is silently discarded when the
design service deserialises a topology, so every renderer reads the name through
`nodeLabel()` in `lib/topology.ts` and a typed label is written back to `data.label`.
The fallback is the node type, then the id: node ids are `crypto.randomUUID()` in this
editor, so an id is not a name. The parameters editor excludes `label` so the Label
input is the only place it is changed.

Measurements, bottlenecks and findings report components by id alone, so
`lib/naming.ts` resolves those ids against the submitted topology. An id the topology
does not contain keeps its own text, because the evaluator also reports a pseudo-node
(`architecture`) that was never drawn.

## Rejection and pre-flight

`preflight()` in `lib/topology.ts` mirrors the rules the graph compiler blocks on, so a
topology that will be refused is reported before submission rather than after. Only
blocking rules are blocking here: the compiler's own non-blocking advice (orphan
components, no entry point) is shown as advisory, because blocking it would prevent runs
the pipeline would accept.

The palette offers only the compiler's `NodeType` values, including `monitoring`.
A saved topology can still contain a type the compiler rejects. The pre-flight
names the offending node, its type, and the accepted types. On rejection the run page
renders the compiler's per-node findings, which the compiler publishes as
`findings: [{field, code, message}]` on the lifecycle event and the design service
exposes as stage `details`.

## Not-ready versus not-found

`GET /submissions/{id}/measurements` and `/evaluation` return 404 with a
machine-readable `code` when the run exists but has not produced results, because the
same status is used for an id that is not the caller's and the client must not be told
"not found" about its own run. Ownership stays an unexplained 404 so an id cannot be
enumerated.

## Editing and submission

The palette supports all supplied wire component types. Drag or click to add, connect
handles or use the labelled connection form, and inspect components using the selector.
Node parameters are JSON objects because no per-type parameter schema was supplied.
Apply or discard parameter edits before saving or starting a run. Existing parameters
and edge metadata are retained.

Saving sends `PUT /api/v1/designs/{id}/topology` with the unchanged body shape
`{ "topology": ... }` and mandatory `If-Match: "<revision>"`, using the revision
loaded by this editor. The typed client takes `expectedRevision` and serializes it
as that header; it is not a body field. The proxy forwards `If-Match` and returns
the upstream ETag. Create, read, and successful saves return the quoted revision
as ETag as well as the numeric `revision` body field.

MongoDB atomically matches design, owner, and revision, updates the topology, and
increments revision with `findAndModify` (no upsert). Success returns 200 and the
new revision. A stale revision returns 409 `application/problem+json` with numeric
`expectedRevision`, numeric `storedRevision`, `reloadRequired: true`, and a `detail`
explaining the rejection and reload. `ApiError` retains these fields; the editor
preserves local edits, blocks saves/submission, and offers JSON export or explicit
reload. Missing If-Match returns 428; malformed, weak, wildcard, or multiple tags
return 400. No preflight GET/PUT race remains. The topology CAS covers the MongoDB
document; PostgreSQL list counts are a separate cache, not a cross-database transaction.

Submissions save changed topology before starting a run. The run page polls every two
seconds, backs off after failures, preserves the last successful audit snapshot, and
stops on completed or failed. Results are independently loaded so a failed evaluation
request does not hide measurements. Failed runs can show partial results.

## Evidence and missing data

The audit timeline preserves append order and duplicate events. Degraded evaluations
show their reason and explain that the arithmetic score is complete without model
narrative. Run accounting failures, truncated ticks, and service warnings are explicit.
Unmeasured components show unknown latency and rates, never substituted zero values.
Bottlenecks show observed and threshold values with units. Findings retain service
severity order and show evidence, recommendations, and related components. Latency
charts use real per-window percentiles and preserve missing-sample gaps, with an exact
data table available alongside.

No scenario catalog or per-component compiler parameter contract was provided, so
scenario identifiers and parameters remain user-entered. No invented discovery APIs,
automatic layout, import workflow, or authentication persistence are implemented.

## Validation

Run `npm run typecheck`, `npm run lint`, `npm test`, and `npm run build`.
Tests use Node's built-in runner and type stripping; validated with Node 24.13.1.
Contract tests do not contact the backend. A build is not live API or browser evidence.

## Next.js documentation preflight

Before writing frontend code, read the installed Next.js 16.3.5 docs:
- `01-app/01-getting-started/03-layouts-and-pages.md`
- `01-app/01-getting-started/05-server-and-client-components.md`
- `01-app/01-getting-started/06-fetching-data.md`
- `01-app/01-getting-started/10-error-handling.md`
- `01-app/01-getting-started/14-metadata-and-og-images.md`
- `01-app/01-getting-started/15-route-handlers.md`
- `01-app/03-api-reference/03-file-conventions/loading.md`

Dynamic route params are awaited promises. Metadata and the root layout remain server
components; interactive editing, session state, and polling use client boundaries.
Route handlers and fetch requests are uncached. Loading boundaries wrap child routes,
while client requests also report their own loading/error states. Error boundaries use
the installed version's `retry` prop; event and asynchronous failures are handled
explicitly because route error boundaries do not catch them.

## File handoff

All paths below are relative to `frontend/`.

| File | Purpose |
| --- | --- |
| `app/layout.tsx` | Server layout, product metadata, navigation, and session boundary. |
| `app/page.tsx` | Replaces the scaffold with the authenticated design library. |
| `app/globals.css` | Dense workspace styling, responsive layouts, tables, canvas, and focus states. |
| `app/loading.tsx` | Route navigation loading boundary. |
| `app/error.tsx` | Recoverable route error boundary using the installed `retry` API. |
| `app/not-found.tsx` | Unknown-route recovery link. |
| `app/designs/[id]/page.tsx` | Design detail route with awaited params and server metadata. |
| `app/submissions/page.tsx` | Run history route. |
| `app/submissions/[id]/page.tsx` | Run inspection route with awaited params and server metadata. |
| `app/api/v1/[...path]/route.ts` | Same-origin authenticated gateway proxy, forwarding conditional headers and ETags. |
| `components/session-provider.tsx` | Basic sign-in, tab-memory credentials, and sign-out. |
| `components/resource-state.tsx` | Explicit loading, retryable error, and empty states. |
| `components/design-list.tsx` | Persisted design table and create form. |
| `components/design-workspace.tsx` | Revision-aware saves, unsaved-edit protection, submission, and deletion. |
| `components/canvas/SystemDesignCanvas.tsx` | React Flow editing, drag palette, accessible connections, and wire serialization. |
| `components/canvas/node-inspector.tsx` | Label and JSON parameter editing with apply/discard and validation. |
| `components/nodes/component-node.tsx` | Consistent node rendering for every supplied wire type. |
| `components/submission-list.tsx` | Account run history and design-specific filtering. |
| `components/submission-workspace.tsx` | Live progress, stale-poll feedback, terminal states, and partial results. |
| `components/results/stage-timeline.tsx` | Append-ordered audit events with both event timestamps. |
| `components/results/run-results.tsx` | Independent measurement and evaluation requests and retries. |
| `components/results/measurements-panel.tsx` | Real component percentiles, rates, utilization, and simulation caveats. |
| `components/results/latency-series.tsx` | Per-window percentile chart with missing-data gaps and exact values table. |
| `components/results/evaluation-panel.tsx` | Arithmetic score, degradation reason, thresholds, findings, evidence, and recommendations. |
| `lib/contracts.ts` | Explicit API request and response types and wire topology types. |
| `lib/naming.ts` | Resolves component names for measurements, bottlenecks and findings. |
| `lib/session-store.ts` | Session persistence, its validation, and the security tradeoff. |
| `lib/api.ts` | Typed endpoint clients, Basic auth forwarding, and HTTP error handling. |
| `lib/proxy-policy.ts` | Verified endpoint/method allowlist. |
| `lib/topology.ts` | Wire component palette, parameter parsing, and lossless topology serialization. |
| `lib/format.ts` | Unknown-safe measurements, rate formatting, timestamps, and terminal states. |
| `lib/use-resource.ts` | Abortable resource loading and explicit retry state. |
| `lib/use-submission.ts` | Non-overlapping polling, backoff, cancellation, and last-success retention. |
| `tests/api.test.mjs` | Endpoint, payload, authentication, 204, and error-path contract tests. |
| `tests/contracts.test.mjs` | Unknown-data, terminal-state, allowlist, parameters, and serialization tests. |
| `tests/defects.test.mjs` | Label resolution, rejected-run handling, pre-flight, and session restore. |
| `package.json` | Adds repeatable typecheck and test scripts. |
| `.gitignore` | Keeps the non-secret environment example visible for handoff. |
| `.env.example` | Documents the server-only API gateway connection. |
| `IMPLEMENTATION.md` | Setup, behavior, documentation preflight, caveats, and file manifest. |

Deleted `components/nodes/CacheNode.tsx`, `DatabaseNode.tsx`, `LoadBalancerNode.tsx`,
`QueueNode.tsx`, and `ServiceNode.tsx`: replaced the five scaffold renderers with the
wire-compatible shared component node. The existing `package-lock.json` modification
was present at preflight and was not edited by this implementation.

## Validation results and limits

Typecheck and ESLint pass. All 25 tests pass. The production build passes and emits the
design, run-history, run-inspection, and API-proxy routes. Node emits a non-failing
module-type detection warning during tests. Next.js emits a non-failing warning about an
unrelated home-directory lockfile outside this repository.

The backend changes are verified by their own module tests (graph-compiler 79,
design-service 24, both green against the live Postgres and MongoDB on this machine).
The running services were not restarted, so the live stack still serves the pre-fix
behaviour; the API evidence recorded for the defects was gathered against it before any
change.

No browser-driven verification was performed in this pass: no desktop browser was
connected to the session, so the DOM-level symptoms the QA report measured (blank node
labels, the literal `<span> -> </span>`, the orphaned parens in the dropdowns, the React
console warnings) are addressed by the unit tests above rather than re-observed in a
browser. The next integration check is to sign in on a restarted stack, confirm the node
labels render and a rejected run lists its offending nodes, and confirm the console is
free of React warnings. No mocks are used by the runtime application.
