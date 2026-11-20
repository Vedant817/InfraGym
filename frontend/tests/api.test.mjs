import assert from "node:assert/strict";
import test from "node:test";
import { api, ApiError } from "../lib/api.ts";

test("requests use the real topology and submission endpoints and forward Basic auth", async () => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (String(url).endsWith("/designs/scenarios")) return Response.json([{ value: "normal", displayName: "Steady state", description: "Baseline." }]);
    return Response.json({ id: "saved", revision: 8, topology: { nodes: [], edges: [] } });
  };
  try {
    const topology = { nodes: [{ id: "lb", type: "load_balancer", label: "Ingress", data: { replicas: 2 }, position: { x: 1, y: 2 } }], edges: [] };
    const saved = await api.save("Basic test", "design/1", { topology, expectedRevision: 7 });
    assert.equal(saved.revision, 8);
    assert.equal(calls[0].url, "/api/v1/designs/design%2F1/topology");
    assert.equal(calls[0].options.method, "PUT");
    assert.equal(calls[0].options.cache, "no-store");
    assert.equal(calls[0].options.headers.Authorization, "Basic test");
    assert.equal(calls[0].options.headers["If-Match"], '"7"');
    assert.deepEqual(JSON.parse(calls[0].options.body), { topology });
    await api.submit("Basic test", "d1", { scenario: "burst", scenarioNotes: "queue pressure", sessionId: "session1" });
    assert.equal(calls[1].url, "/api/v1/designs/d1/submissions");
    assert.equal(calls[1].options.method, "POST");
    assert.deepEqual(JSON.parse(calls[1].options.body), { scenario: "burst", scenarioNotes: "queue pressure", sessionId: "session1" });
    const scenarios = await api.scenarios("Basic test");
    assert.equal(scenarios[0].value, "normal");
    assert.equal(calls[2].url, "/api/v1/designs/scenarios");
    assert.equal(calls[2].options.headers.Authorization, "Basic test");
    await api.series("Basic test", "s1", "node/with space");
    assert.equal(calls[3].url, "/api/v1/submissions/s1/metrics/node%2Fwith%20space/series?metric=latency_ms");
  } finally { globalThis.fetch = original; }
});

test("stale saves expose the stored revision and reload requirement", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    detail: "Expected revision 7, stored revision 8. Reload the design.",
    storedRevision: 8, expectedRevision: 7, reloadRequired: true,
  }, { status: 409 });
  try {
    await assert.rejects(api.save("Basic test", "d1", { topology: { nodes: [], edges: [] }, expectedRevision: 7 }), (error) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 409);
      assert.equal(error.storedRevision, 8);
      assert.equal(error.reloadRequired, true);
      assert.match(error.message, /stored revision 8/);
      return true;
    });
  } finally { globalThis.fetch = original; }
});

test("204 deletion requires no JSON response body", async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(null, { status: 204 });
  try { assert.equal(await api.remove("Basic test", "d1"), undefined); }
  finally { globalThis.fetch = original; }
});

test("authentication, unavailable ownership, conflicts, and outages stay errors", async () => {
  const original = globalThis.fetch;
  try {
    for (const [status, expected] of [[401, /Authentication failed/], [404, /not found or is unavailable to your account/], [409, /changed on the server/], [502, /502/]]) {
      globalThis.fetch = async () => Response.json({ message: "Service detail" }, { status });
      await assert.rejects(api.design("Basic test", "d1"), (error) => {
        assert.ok(error instanceof ApiError);
        assert.equal(error.status, status);
        assert.match(error.message, expected);
        assert.match(error.message, /Service detail/);
        return true;
      });
    }
    globalThis.fetch = async () => new Response("Not JSON", { status: 500 });
    await assert.rejects(api.designs("Basic test"), /500/);
  } finally { globalThis.fetch = original; }
});
