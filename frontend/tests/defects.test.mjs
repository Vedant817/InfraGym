// Behavioural tests for the QA-reported defects. Every assertion is on observable
// output or returned data, never on the shape of an implementation detail.
import assert from "node:assert/strict";
import test from "node:test";

import { rejectionNotice, terminal } from "../lib/format.ts";
import { initialScenarioValue } from "../lib/scenario.ts";
import {
  compilableComponentTypes, componentLabel, componentTypes, dataWithoutLabel, isCompilableType,
  labelById, newNode, nodeLabel, parseParameters, preflight, serializeTopology, withLabel,
} from "../lib/topology.ts";
import { nameMeasurements, rejectionFindings, withComponentNames } from "../lib/naming.ts";
import { parseSession, serverSessionSnapshot, SESSION_KEY } from "../lib/session-store.ts";

// ---------------------------------------------------------------------------------
// Defect 1: labels live at data.label on the wire, and are read from nowhere else.
// ---------------------------------------------------------------------------------

test("a node's label is read from data.label, which is where the wire stores it", () => {
  // The shape the design service actually returns for a stored node.
  const stored = { id: "api", type: "service", data: { label: "Orders API", config: {} }, position: { x: 0, y: 0 } };
  assert.equal(nodeLabel(stored), "Orders API");
  // The shape the editor used to read, whose top-level label does not exist on the wire.
  const noWireLabel = { id: "9444430a-842b-479d-bf3d-b77633369b8f", type: "client", data: {}, position: { x: 0, y: 0 } };
  assert.notEqual(nodeLabel(noWireLabel), "");
});

test("a node without a label falls back to its type, then to its id", () => {
  // Type first: ids are crypto.randomUUID() in this editor, so the id is not a name.
  assert.equal(nodeLabel({ id: "9444430a-842b-479d-bf3d-b77633369b8f", type: "load_balancer", data: {} }), "load balancer");
  // Blank and whitespace-only labels are treated as absent, not rendered as blank.
  assert.equal(nodeLabel({ id: "x", type: "cache", data: { label: "   " } }), "cache");
  assert.equal(nodeLabel({ id: "x", type: "cache", data: { label: 7 } }), "cache");
  // Id last, for a node with no type either.
  assert.equal(nodeLabel({ id: "orphan-id", type: "", data: {} }), "orphan-id");
  assert.equal(nodeLabel({ id: "orphan-id", type: "", data: {} }), "orphan-id");
});

test("editing a label writes it to data.label, so the server keeps it", () => {
  const node = newNode("service", "api", { x: 0, y: 0 });
  const typed = withLabel(node, "FRONT DOOR");
  assert.equal(typed.data.label, "FRONT DOOR");
  // The wire shape must survive a save/load round trip through the compiler's contract.
  const round = JSON.parse(JSON.stringify(typed));
  assert.equal(nodeLabel(round), "FRONT DOOR");
  // No top-level label: the server discards unknown top-level fields, which is why a
  // label typed into one was silently lost.
  assert.equal("label" in round, false);
});

test("the parameters editor and the label editor do not both claim the label", () => {
  const node = withLabel(newNode("service", "api", { x: 0, y: 0 }), "Orders API");
  const shown = dataWithoutLabel(node);
  assert.equal("label" in shown, false);
  assert.deepEqual(shown, {});
  // Parameters the user typed are preserved and the label is still there.
  const withParams = { ...node, data: { ...parseParameters('{"replicas":3}'), label: "Orders API" } };
  assert.equal(withParams.data.replicas, 3);
  assert.equal(nodeLabel(withParams), "Orders API");
});

test("names resolve for results that report components by id alone", () => {
  const topology = {
    nodes: [
      { id: "9444430a-842b-479d-bf3d-b77633369b8f", type: "database", data: { label: "Orders DB" }, position: { x: 0, y: 0 } },
      { id: "api", type: "service", data: { label: "Orders API" }, position: { x: 1, y: 0 } },
    ],
    edges: [],
  };
  assert.equal(labelById(topology, "api"), "Orders API");
  assert.equal(labelById(topology, "9444430a-842b-479d-bf3d-b77633369b8f"), "Orders DB");
  // An unknown id resolves to null so the reader falls back to the id rather than
  // being shown another component's name.
  assert.equal(labelById(topology, "architecture"), null);
  assert.equal(labelById(null, "api"), null);
});

test("measurement and evaluation panels show component names, not raw UUIDs", () => {
  const topology = { nodes: [{ id: "u1", type: "database", data: { label: "Orders DB" }, position: { x: 0, y: 0 } }], edges: [] };
  const base = {
    nodeId: "u1", nodeType: "database", measured: true, sampleCount: 10,
    p50Ms: 1, p95Ms: 2, p99Ms: 3, maxMs: 4, avgErrorRate: 0, peakErrorRate: 0,
    peakRps: 1, avgRps: 1, peakUtilization: 0.5, avgCacheHitRate: null, peakQueueDepth: 0, saturated: false,
  };
  const measurements = {
    simulation: {
      id: "s", scenario: "normal", status: "completed", tickCount: 1, totalTicks: 1, tickMillis: 1,
      truncated: false, offered: 1, served: 1, failed: 0, queued: 0, completionRate: 1,
      reconciles: true, warnings: [], startedAt: null, completedAt: null,
    },
    nodes: [base, { ...base, nodeId: "ghost", label: undefined }],
    worstNodeId: "u1", worstP99Ms: 3,
  };
  const named = nameMeasurements(measurements, topology);
  assert.equal(named[0].label, "Orders DB");
  assert.equal(named[1].label, null);

  const evaluation = {
    id: "e", overallScore: 50, verdict: "risky", summary: null, degraded: false, degradedReason: null,
    model: null, latencyMs: null, worstFinding: null, strengths: [], weaknesses: [], recommendations: [],
    bottlenecks: [{ nodeId: "u1", metric: "redundancy", observedValue: 1, unit: "instances", thresholdValue: 2, explanation: "x" }],
    findings: [{ nodeId: "u1", severity: "HIGH", category: "availability", title: "t", detail: "d", evidence: "e", recommendation: "r", relatedNodeIds: ["ghost"] }],
    references: [], warnings: [], evaluatedAt: "",
  };
  const resolved = withComponentNames(evaluation, topology);
  assert.equal(resolved.bottlenecks[0].label, "Orders DB");
  assert.equal(resolved.findings[0].label, "Orders DB");
  // A related id the topology does not contain stays null rather than borrowing a name.
  assert.deepEqual(resolved.findings[0].relatedLabels, [null]);
});

// ---------------------------------------------------------------------------------
// Defect 2: a rejected run is terminal and is styled as an error.
// ---------------------------------------------------------------------------------

test("a rejected run is terminal, so polling stops", () => {
  for (const status of ["received", "validating", "compiling", "compiled", "simulating", "aggregating", "evaluating"]) {
    assert.equal(terminal(status), false, status);
  }
  assert.equal(terminal("completed"), true);
  assert.equal(terminal("failed"), true);
  assert.equal(terminal("rejected"), true);
});

test("a rejected run does not promise measurements that will never arrive", () => {
  const findings = [{ code: "INVALID_NODE_TYPE", message: "Node 'obs' has unknown type 'metrics'." }];
  const notice = rejectionNotice(findings, true);
  assert.match(notice, /rejected before a simulation started/);
  assert.match(notice, /no measurements or evaluation/);
  // The specific cause is named, because "rejected" alone is not actionable.
  assert.match(notice, /unknown type 'metrics'/);
  assert.doesNotMatch(notice, /will appear after/);
  // Multiple findings: the count is stated rather than the list silently truncated.
  assert.match(rejectionNotice([...findings, findings[0]], true), /and 1 more/);
  // A rejection with no structured detail still explains itself.
  assert.match(rejectionNotice([], true), /rejected before a simulation started/);
  // A run that is not rejected makes no claim.
  assert.equal(rejectionNotice(findings, false), null);
});

// ---------------------------------------------------------------------------------
// Defect 3: rejection detail is carried and rendered; a bad topology is caught first.
// ---------------------------------------------------------------------------------

test("per-node rejection detail is read from the lifecycle event", () => {
  const stages = [
    { stage: "gateway", status: "received", message: "queued", occurredAt: "", receivedAt: "" },
    {
      stage: "graph-compiler", status: "rejected",
      message: "Topology failed validation: Node 'obs' has unknown type 'metrics'.",
      occurredAt: "", receivedAt: "",
      details: {
        errors: ["Node 'obs' has unknown type 'metrics'."],
        findings: [
          { field: "nodes[3].type", code: "INVALID_NODE_TYPE", message: "Node 'obs' has unknown type 'metrics'." },
          { field: "edges[4]", code: "SELF_LOOP", message: "Edge 'e4' connects node 'lb' to itself." },
        ],
      },
    },
  ];
  const findings = rejectionFindings(stages);
  assert.equal(findings.length, 2);
  assert.equal(findings[0].code, "INVALID_NODE_TYPE");
  assert.equal(findings[0].field, "nodes[3].type");
  assert.equal(findings[1].message, "Edge 'e4' connects node 'lb' to itself.");
  // Older events with no details yield nothing rather than throwing.
  assert.deepEqual(rejectionFindings([{ stage: "x", status: "rejected", message: "m", occurredAt: "", receivedAt: "" }]), []);
  assert.deepEqual(rejectionFindings(undefined), []);
});

test("an unknown stored scenario is not replaced by the first workload", () => {
  const options = [
    { value: "normal", displayName: "Steady state" },
    { value: "flash_sale", displayName: "Flash sale" },
  ];
  assert.equal(initialScenarioValue("flash_sale", options), "flash_sale");
  assert.equal(initialScenarioValue("  flash_sale  ", options), "flash_sale");
  // A name the catalog does not list stays empty, so the required control cannot
  // submit steady state in its place.
  assert.equal(initialScenarioValue("burst", options), "");
  assert.equal(initialScenarioValue("FLASH-SALE", options), "");
  // A new design has no stored name. The control shows the first declared workload.
  assert.equal(initialScenarioValue(undefined, options), "normal");
  assert.equal(initialScenarioValue("   ", options), "normal");
  assert.equal(initialScenarioValue("burst", []), "");
});

test("the palette offers only types the compiler accepts", () => {
  assert.deepEqual([...componentTypes], [...compilableComponentTypes]);
  assert.equal(componentTypes.includes("monitoring"), true);
  assert.equal(componentTypes.includes("metrics"), false);
  assert.equal(componentTypes.includes("tracing"), false);
});

test("the two isolated-node topologies the QA run was rejected for are caught before submitting", () => {
  // Both shapes the compiler refuses, reproduced exactly: a valid chain plus isolated
  // observability nodes a saved topology can still contain. The palette no longer
  // offers them. Pre-flight still has to name the node, because old designs do.
  for (const type of ["metrics", "tracing"]) {
    const topology = {
      nodes: [
        { id: "lb", type: "load_balancer", data: { label: "Ingress" }, position: { x: 0, y: 0 } },
        { id: "api", type: "service", data: { label: "Orders API" }, position: { x: 1, y: 0 } },
        { id: "db", type: "database", data: { label: "Orders DB" }, position: { x: 2, y: 0 } },
        { id: "obs", type, data: { label: "Observability" }, position: { x: 3, y: 0 } },
      ],
      edges: [
        { id: "e1", source: "lb", target: "api" },
        { id: "e2", source: "api", target: "db" },
      ],
    };
    const { blocking, advisory } = preflight(topology);
    const codes = blocking.map((finding) => finding.code);
    assert.ok(codes.includes("INVALID_NODE_TYPE"), `${type} should be reported as uncompilable`);
    const named = blocking.find((finding) => finding.code === "INVALID_NODE_TYPE");
    // It names the offending node and the offending type, so the user can act rather
    // than just being told the run will fail.
    assert.match(named.message, /Observability/, "the node's own name is stated");
    assert.match(named.message, new RegExp(`"${type}"`), "the offending type is stated");
    assert.match(named.message, /monitoring/, "the accepted types are listed");
    // The isolated node is also flagged as advisory: it is not what blocks the run.
    assert.ok(advisory.some((finding) => finding.code === "ORPHAN_NODE"));
  }
  // The observability type the compiler does accept is not reported.
  assert.equal(isCompilableType("monitoring"), true);
  assert.equal(isCompilableType("metrics"), false);
});

test("pre-flight blocks exactly what the compiler blocks, and advises on the rest", () => {
  const chain = (nodes, edges) => ({ nodes, edges });
  const node = (id, type, label = componentLabel(type)) => ({ id, type, data: { label }, position: { x: 0, y: 0 } });

  // A valid design produces nothing.
  const valid = chain(
    [node("lb", "load_balancer"), node("api", "service"), node("db", "database")],
    [{ id: "e1", source: "lb", target: "api" }, { id: "e2", source: "api", target: "db" }]);
  assert.deepEqual(preflight(valid).blocking, []);

  // An empty topology.
  assert.deepEqual(preflight(chain([], [])).blocking.map((f) => f.code), ["EMPTY_TOPOLOGY"]);

  // A cycle is refused by the compiler, so it must be refused here too.
  const cyclic = chain([node("a", "service"), node("b", "service")], [
    { id: "e1", source: "a", target: "b" }, { id: "e2", source: "b", target: "a" }]);
  const cycleFindings = preflight(cyclic).blocking;
  assert.ok(cycleFindings.some((f) => f.code === "CYCLE_DETECTED"));
  // Both nodes are unlabelled services, so each step carries its id too: "service" twice
  // would identify neither component.
  const cycleMessage = cycleFindings.find((f) => f.code === "CYCLE_DETECTED").message;
  assert.match(cycleMessage, /\(a\) → service \(b\)|service \(a\) → service \(b\)/);
  assert.match(cycleMessage, /Remove the circular dependency/);

  // A self-loop, a duplicate id and a dangling endpoint are each blocking.
  assert.ok(preflight(chain([node("a", "service"), node("b", "service")], [{ id: "e", source: "a", target: "a" }]))
    .blocking.some((f) => f.code === "SELF_LOOP"));
  assert.ok(preflight(chain([node("a", "service"), node("a", "service")], [])).blocking.some((f) => f.code === "DUPLICATE_NODE_ID"));
  assert.ok(preflight(chain([node("a", "service"), node("b", "service")], [{ id: "e", source: "a", target: "ghost" }]))
    .blocking.some((f) => f.code === "INVALID_EDGE_TARGET"));

  // Non-blocking compiler advice stays advisory, so it cannot block a run the
  // pipeline would accept.
  const lonely = chain([node("lb", "load_balancer"), node("stray", "service")], []);
  const lonelyResult = preflight(lonely);
  assert.deepEqual(lonelyResult.blocking, []);
  assert.ok(lonelyResult.advisory.some((f) => f.code === "ORPHAN_NODE"));

  // Every supported type the palette offers must be one the compiler accepts.
  for (const type of compilableComponentTypes) {
    assert.equal(isCompilableType(type), true, type);
  }
});

// ---------------------------------------------------------------------------------
// Defect 7: a refresh or deep link keeps the session.
// ---------------------------------------------------------------------------------

test("a stored session is restored after a refresh", () => {
  // The exact entry the provider writes on a successful sign-in.
  const stored = JSON.stringify({ authorization: "Basic cWE6cWE=", username: "qa" });
  assert.deepEqual(parseSession(stored), { authorization: "Basic cWE6cWE=", username: "qa" });
});

test("the server render claims no session, so hydration cannot mismatch", () => {
  // A server render has no storage access. Returning anything but empty would make the
  // first client render disagree with the server HTML for every signed-in refresh.
  assert.equal(serverSessionSnapshot(), "");
  assert.equal(parseSession(serverSessionSnapshot()), null);
});

test("an unusable or tampered storage entry is discarded, not forwarded", () => {
  for (const raw of [
    null, "", "not json", "null", "[]", '"a string"', "42",
    JSON.stringify({ username: "qa" }),
    JSON.stringify({ authorization: "Bearer token", username: "qa" }),
    JSON.stringify({ authorization: "Basic ", username: "qa" }),
    JSON.stringify({ authorization: "Basic cWE6cWE=" }),
    JSON.stringify({ authorization: "Basic cWE6cWE=", username: "" }),
    JSON.stringify({ authorization: 42, username: "qa" }),
  ]) {
    assert.equal(parseSession(raw), null, `expected no session from ${JSON.stringify(raw)}`);
  }
});

test("the session key is scoped to the session, not shared across the origin", () => {
  // A localStorage key would carry a credential between browser sessions; this one is
  // read through sessionStorage, and the key is stable so the provider and the test
  // cannot drift apart.
  assert.equal(SESSION_KEY, "archforge.session");
});

// ---------------------------------------------------------------------------------
// Regression: the editor's own topology shape and round trip still hold.
// ---------------------------------------------------------------------------------

test("new nodes and serialization keep the wire shape the compiler reads", () => {
  const node = newNode("load_balancer", "lb", { x: 1, y: 2 });
  assert.deepEqual(node, { id: "lb", type: "load_balancer", data: { label: "load balancer" }, position: { x: 1, y: 2 } });
  const named = withLabel(node, "Ingress");
  const edge = { id: "e1", source: "lb", target: "db", data: { weight: 0.5 }, protocol: "http", label: "requests" };
  const saved = serializeTopology(
    [{ id: "lb", type: "component", selected: true, width: 150, position: { x: 125, y: 40 }, data: { wire: named } }],
    [{ ...edge, type: "smoothstep", markerEnd: { type: "arrowclosed" }, selected: true }],
    { nodes: [named], edges: [edge] });
  assert.deepEqual(saved.nodes[0], { ...named, position: { x: 125, y: 40 } });
  assert.deepEqual(saved.edges[0], edge);
  assert.equal("selected" in saved.nodes[0], false);
  assert.equal("markerEnd" in saved.edges[0], false);
  assert.equal(nodeLabel(saved.nodes[0]), "Ingress");
});