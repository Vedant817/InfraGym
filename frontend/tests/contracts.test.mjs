import assert from "node:assert/strict";
import test from "node:test";
import { latency, number, percent, terminal } from "../lib/format.ts";
import { allowedRoute } from "../lib/proxy-policy.ts";
import { newNode, parseParameters, serializeTopology } from "../lib/topology.ts";

test("unmeasured and missing latency never become zero", () => {
  assert.equal(latency(false, 0), "Unknown");
  assert.equal(latency(false, 87), "Unknown");
  assert.equal(latency(true, null), "Unknown");
  assert.equal(latency(true, Number.NaN), "Unknown");
  assert.equal(latency(true, 0), "0 ms");
  assert.equal(number(undefined), "Unknown");
  assert.equal(percent(null), "Unknown");
  assert.equal(percent(0.25), "25%");
});
test("only terminal submissions stop polling", () => {
  for (const status of ["received", "validating", "compiled", "simulating", "aggregating", "evaluating"]) assert.equal(terminal(status), false);
  assert.equal(terminal("completed"), true);
  assert.equal(terminal("failed"), true);
  // A rejected run never advances; polling it forever was the defect.
  assert.equal(terminal("rejected"), true);
});
test("proxy accepts only verified endpoint and method combinations", () => {
  for (const [method, path] of [
    ["GET", "designs"], ["POST", "designs"], ["GET", "designs/123"], ["DELETE", "designs/123"],
    ["PUT", "designs/123/topology"], ["POST", "designs/123/submissions"],
    ["GET", "submissions"], ["GET", "submissions/456"], ["GET", "submissions/456/measurements"],
    ["GET", "submissions/456/evaluation"], ["GET", "submissions/456/metrics/service-1/series"],
  ]) assert.equal(allowedRoute(path.split("/"), method), true, path);
  for (const [method, path] of [["POST", "compile"], ["PUT", "designs/123"], ["GET", "designs/123/topology"],
    ["POST", "submissions"], ["GET", "submissions/456/metrics"], ["GET", "submissions/456/metrics/node/series/extra"],
    ["PATCH", "designs/123"], ["GET", "users"]]) assert.equal(allowedRoute(path.split("/"), method), false, path);
});
test("node creation retains wire types and does not invent parameters", () => {
  // The label is written to data.label, which is where the wire contract stores it.
  // A top-level label is discarded when the design service deserialises the topology.
  const node = newNode("load_balancer", "lb", { x: 1, y: 2 });
  assert.deepEqual(node, { id: "lb", type: "load_balancer", data: { label: "load balancer" }, position: { x: 1, y: 2 } });
  const parameters = parseParameters('{"replicas":3,"nested":{"enabled":true},"limits":[1,2]}');
  assert.deepEqual(parameters.nested, { enabled: true });
  for (const invalid of ["null", "[]", "42", '"text"', "{broken}"]) assert.throws(() => parseParameters(invalid));
});
test("serialization retains wire labels, nested parameters, and edge metadata without editor fields", () => {
  const node = { ...newNode("load_balancer", "lb", { x: 0, y: 0 }), data: { label: "Ingress", replicas: 3, nested: { enabled: true } } };
  const edge = { id: "e1", source: "lb", target: "db", data: { weight: 0.5 }, protocol: "http", label: "requests" };
  const original = { nodes: [node], edges: [edge] };
  const saved = serializeTopology(
    [{ id: "lb", type: "component", selected: true, width: 150, position: { x: 125, y: 40 }, data: { wire: node } }],
    [{ ...edge, type: "smoothstep", markerEnd: { type: "arrowclosed" }, selected: true }], original);
  assert.deepEqual(saved.nodes[0], { ...node, position: { x: 125, y: 40 } });
  assert.deepEqual(saved.edges[0], edge);
  assert.equal("selected" in saved.nodes[0], false);
  assert.equal("markerEnd" in saved.edges[0], false);
  assert.deepEqual(serializeTopology([], [], original), { nodes: [], edges: [] });
});
