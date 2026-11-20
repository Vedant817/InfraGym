// Type-only imports are erased at runtime, so they need no extension for the Node
// test runner; only value imports (lib/naming.ts -> here) must name ".ts".
import type { CanvasEdge, CanvasNode, JsonObject, Topology, ValidationFinding } from "./contracts";

/**
 * The node types the graph compiler accepts, mirrored from the wire contract's
 * `NodeType` enum. This is also the palette.
 *
 * A wider palette used to offer `metrics`, `tracing`, and other names the compiler
 * rejects with `INVALID_NODE_TYPE`, so a node drawn from the palette could never
 * run. Saved topologies can still contain those types; {@link preflight} names the
 * offending node rather than pretending the palette still offers them. Mirrored
 * here because the frontend cannot import the server enum.
 */
export const compilableComponentTypes = [
  "client", "cdn", "load_balancer", "gateway", "auth", "service", "worker", "function",
  "queue", "cache", "database", "storage", "monitoring", "external",
] as const;
export const componentTypes = compilableComponentTypes;
const compilable = new Set<string>(compilableComponentTypes);

export function componentLabel(type: string): string { return type.replaceAll("_", " "); }
export function isCompilableType(type: string): boolean { return compilable.has(type); }

/**
 * The name to show for a node.
 *
 * The wire stores the label at `data.label`, so that is the only place it can come
 * from. Reading a top-level `label` instead yields `undefined` for every node the
 * service has ever stored — which is why every card, dropdown and connection row
 * rendered blank and a typed label vanished on save, because the design service
 * discards unknown top-level fields when it deserialises the topology.
 *
 * The fallback is the node type, then the id, and that order is deliberate:
 *
 * - Every node this editor creates gets a `crypto.randomUUID()` id, so the id is
 *   not a name and must be the last resort, not the first.
 * - The type is the one thing the canvas always has and a person can read
 *   ("load balancer"), so it is a better answer than a UUID.
 * - Falling through to the id matches the server's own `NodeData.displayLabel`, so
 *   the editor and the compiler never disagree about what a node is called.
 *
 * Ambiguity is handled by always rendering the short id alongside the name (node
 * cards and the inspector dropdowns already do), so a type-only fallback stays
 * unambiguous when several nodes share a type.
 */
export function nodeLabel(node: Pick<CanvasNode, "id" | "type" | "data">): string {
  const stored = node.data?.label;
  if (typeof stored === "string" && stored.trim()) return stored.trim();
  if (node.type) return componentLabel(node.type);
  return node.id;
}

/** The node's display name resolved against a topology, for ids seen only in results. */
export function labelById(topology: Topology | null | undefined, id: string | null | undefined): string | null {
  if (!id || !topology) return null;
  const node = topology.nodes.find((candidate) => candidate.id === id);
  return node ? nodeLabel(node) : null;
}

/**
 * The node's parameters with the label removed.
 *
 * The inspector edits the label in its own input, so leaving it in the parameters
 * JSON would give the user two places to change one thing and no indication which
 * one wins.
 */
export function dataWithoutLabel(node: Pick<CanvasNode, "data">): JsonObject {
  const rest: JsonObject = { ...(node.data ?? {}) };
  delete rest.label;
  return rest;
}

/** A node carrying this display name, preserving every other parameter. */
export function withLabel(node: CanvasNode, label: string): CanvasNode {
  return { ...node, data: { ...node.data, label } };
}

export function parseParameters(text: string): JsonObject {
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("Parameters must be a JSON object.");
  return parsed as JsonObject;
}
/** New nodes are named for their type, written to `data.label` where the wire reads it. */
export function newNode(type: string, id: string, position: { x: number; y: number }): CanvasNode {
  return { id, type, data: { label: componentLabel(type) }, position };
}

/**
 * Checks a topology the way the compiler will, before it is submitted.
 *
 * Only rules the compiler treats as blocking are reported as `blocking`. Warnings
 * the compiler emits without blocking (orphan components, unreachable components,
 * no entry point) are advisory: they belong in front of the user as advice, and
 * turning them into a disabled button would stop designs the pipeline would happily
 * run.
 *
 * The mirrored bounds are the compiler's defaults. If a deployment raises them the
 * pre-flight is stricter than the server, which errs towards showing a warning the
 * server would have accepted — never towards permitting a run the server will reject.
 */
export function preflight(topology: Topology): { blocking: ValidationFinding[]; advisory: ValidationFinding[] } {
  const blocking: ValidationFinding[] = [];
  const advisory: ValidationFinding[] = [];
  const nodes = topology.nodes ?? [];
  const edges = topology.edges ?? [];
  const find = (code: string, field: string, message: string) => ({ code, field, message });
  const names = displayNames(nodes);
  const nameOf = (node: CanvasNode) => names.get(node.id) ?? nodeLabel(node);

  if (nodes.length === 0) {
    blocking.push(find("EMPTY_TOPOLOGY", "nodes", "Add at least one component before running."));
    return { blocking, advisory };
  }

  const ids = new Set<string>();
  for (const [index, node] of nodes.entries()) {
    const where = `nodes[${index}]`;
    if (!node.id) {
      blocking.push(find("MISSING_NODE_ID", `${where}.id`, `The ${nodeLabel(node)} has no identifier.`));
    } else if (ids.has(node.id)) {
      blocking.push(find("DUPLICATE_NODE_ID", `${where}.id`, `Two components share the id "${node.id}".`));
    } else {
      ids.add(node.id);
    }
    if (!isCompilableType(node.type)) {
      const suggestion = closestType(node.type);
      blocking.push(find("INVALID_NODE_TYPE", `${where}.type`,
        `${nameOf(node)} has type "${node.type}", which the compiler does not accept.${suggestion ? ` Did you mean "${suggestion}"?` : ""} Supported types: ${compilableComponentTypes.join(", ")}.`));
    }
    const label = node.data?.label;
    if (typeof label === "string" && label.length > 200) {
      blocking.push(find("NODE_LABEL_TOO_LONG", `${where}.data.label`, `${nameOf(node)} has a label over 200 characters.`));
    }
  }

  const edgeIds = new Set<string>();
  for (const [index, edge] of edges.entries()) {
    const where = `edges[${index}]`;
    if (!edge.source || !ids.has(edge.source)) {
      blocking.push(find("INVALID_EDGE_SOURCE", `${where}.source`, `A connection points at "${edge.source ?? ""}", which is not a component on the canvas.`));
    }
    if (!edge.target || !ids.has(edge.target)) {
      blocking.push(find("INVALID_EDGE_TARGET", `${where}.target`, `A connection points at "${edge.target ?? ""}", which is not a component on the canvas.`));
    }
    if (edge.source && edge.source === edge.target) {
      blocking.push(find("SELF_LOOP", where, `${names.get(edge.source) ?? edge.source} is connected to itself. A topology must be a directed acyclic graph.`));
    }
    if (edge.id) {
      if (edgeIds.has(edge.id)) blocking.push(find("DUPLICATE_EDGE_ID", `${where}.id`, `Two connections share the id "${edge.id}".`));
      edgeIds.add(edge.id);
    }
  }

  for (const cycle of cycles(nodes, edges)) {
    // Each step is named; the ids are appended so a cycle through two same-typed
    // nodes is still distinguishable, which is exactly the case the type fallback
    // cannot resolve on its own.
    const path = cycle.map((id) => `${names.get(id) ?? id} (${id})`);
    blocking.push(find("CYCLE_DETECTED", "topology",
      `${path.join(" → ")}. Remove the circular dependency.`));
  }

  // Mirror of the compiler's non-blocking design advice, so an isolated component is
  // visible before submitting rather than only in the compiler's log.
  if (nodes.length > 1) {
    const connected = new Set<string>();
    for (const edge of edges) {
      if (edge.source && edge.target && edge.source !== edge.target && ids.has(edge.source) && ids.has(edge.target)) {
        connected.add(edge.source); connected.add(edge.target);
      }
    }
    for (const node of nodes) {
      if (node.id && !connected.has(node.id)) {
        advisory.push(find("ORPHAN_NODE", "nodes", `${nameOf(node)} is not connected to any other component. The run will include it with no traffic.`));
      }
    }
  }
  const entryPoints = nodes.filter((node) => ["client", "cdn", "load_balancer", "gateway"].includes(node.type)).map((node) => node.id);
  if (!entryPoints.length) {
    advisory.push(find("NO_ENTRY_POINT", "topology", "No client, CDN, load balancer or gateway: the simulation has no entry point to generate traffic from."));
  }
  return { blocking, advisory };
}

/**
 * Display names keyed by node id, disambiguated by short id where they collide.
 *
 * Two unlabelled components of the same type both resolve to the same type name
 * under {@link nodeLabel}, so a message naming one of them identifies neither. Where
 * a name is claimed more than once the short id is appended, which keeps the finding
 * actionable without changing how a node is named everywhere else.
 */
function displayNames(nodes: CanvasNode[]): Map<string, string> {
  const base = new Map<string, string>();
  const counts = new Map<string, number>();
  for (const node of nodes) {
    if (!node.id) continue;
    const label = nodeLabel(node);
    base.set(node.id, label);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  const names = new Map<string, string>();
  for (const node of nodes) {
    if (!node.id) continue;
    const label = base.get(node.id)!;
    names.set(node.id, (counts.get(label) ?? 0) > 1 ? `${label} (${node.id.slice(0, 8)})` : label);
  }
  return names;
}

/** Elementary cycles, capped so a dense graph cannot produce an unbounded report. */
function cycles(nodes: CanvasNode[], edges: { source: string; target: string }[]): string[][] {
  const outgoing = new Map<string, string[]>();
  for (const node of nodes) if (node.id) outgoing.set(node.id, []);
  for (const edge of edges) {
    if (!edge.source || !edge.target || edge.source === edge.target) continue;
    if (!outgoing.has(edge.source) || !outgoing.has(edge.target)) continue;
    outgoing.get(edge.source)!.push(edge.target);
  }
  const found: string[][] = [];
  const seen = new Set<string>();
  const visiting = new Set<string>();
  const visit = (id: string, path: string[]) => {
    if (found.length >= 5) return;
    if (visiting.has(id)) {
      const start = path.indexOf(id);
      const cycle = path.slice(start >= 0 ? start : 0);
      const key = [...cycle].sort().join("|");
      if (!seen.has(key)) { seen.add(key); found.push([...cycle, cycle[0] ?? id]); }
      return;
    }
    visiting.add(id);
    path.push(id);
    for (const next of outgoing.get(id) ?? []) visit(next, path);
    path.pop();
    visiting.delete(id);
  };
  for (const id of outgoing.keys()) visit(id, []);
  return found;
}

/** Nearest compilable type within edit distance 3, matching the compiler's suggestion. */
function closestType(type: string): string | null {
  let best: string | null = null;
  let bestDistance = 4;
  for (const candidate of compilableComponentTypes) {
    const distance = editDistance(type.toLowerCase(), candidate);
    if (distance < bestDistance) { bestDistance = distance; best = candidate; }
  }
  return best;
}

function editDistance(a: string, b: string): number {
  let previous = Array.from({ length: b.length + 1 }, (_, index) => index);
  for (let i = 1; i <= a.length; i += 1) {
    const current = [i];
    for (let j = 1; j <= b.length; j += 1) {
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    previous = current;
  }
  return previous[b.length];
}
interface EditorNode { position: { x: number; y: number }; data: { wire: CanvasNode } }
interface EditorEdge { id: string; source: string; target: string; label?: unknown; data?: JsonObject }
export function serializeTopology(nodes: EditorNode[], edges: EditorEdge[], original: Topology): Topology {
  return {
    nodes: nodes.map((node) => ({ ...node.data.wire, position: node.position })),
    edges: edges.map((edge): CanvasEdge => ({
      ...original.edges.find((candidate) => candidate.id === edge.id),
      id: edge.id, source: edge.source, target: edge.target,
      ...(typeof edge.label === "string" ? { label: edge.label } : {}),
      ...(edge.data ? { data: edge.data } : {}),
    })),
  };
}
