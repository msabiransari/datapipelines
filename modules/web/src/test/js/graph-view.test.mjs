// 080 §A — the canvas's live behaviour: the edge class transitions per node state
// (active while the target runs, done after, cleared on failure), the row labels
// riding the edge behind the `rows` class, and the flow animation's
// reduced-motion guard. Driven through PipelineGraph.prototype with hand-rolled
// fakes — the constructor wants getComputedStyle, the prototype wants only `cy`.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

function fakeEdge(id) {
  const classes = new Set();
  const data = {};
  return {
    id,
    length: 1,
    classes: () => [...classes],
    hasClass: (c) => classes.has(c),
    addClass(c) { classes.add(c); },
    removeClass(c) { classes.delete(c); },
    data(k, v) { if (v === undefined) return data[k]; data[k] = v; },
  };
}

function fakeNode(id, incomers, outgoers) {
  const classes = new Set();
  const data = {};
  return {
    id: () => id,
    length: 1,
    removeClass(c) { classes.delete(c); },
    addClass(c) { classes.add(c); },
    hasClass: (c) => classes.has(c),
    data(k, v) { if (v === undefined) return data[k]; data[k] = v; },
    incomers: () => incomers,
    outgoers: () => outgoers,
  };
}

/** A prototype instance with a one-node fake cy: a --edge--> b. */
function graphWithEdge() {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const edge = fakeEdge("a->b");
  const a = fakeNode("a", [], [edge]);
  const b = fakeNode("b", [edge], []);
  const byId = { a, b };
  g.cy = {
    getElementById: (id) => byId[id] || { length: 0 },
    nodes: () => [a, b],
    edges: (sel) => {
      if (sel === ".active") return [edge].filter((e) => e.hasClass("active"));
      return [edge];
    },
  };
  g.editor = { nodeStates: {} };
  g._mm = null;
  g._flowRunning = false;
  return { g, edge, a, b };
}

test("running: the curve INTO the node flows (active); the state mirrors to data and the editor", () => {
  const { g, edge, b } = graphWithEdge();
  g.setNodeState("b", "running");
  assert.ok(edge.hasClass("active"), "the edge into a running target is active");
  assert.ok(b.hasClass("running"));
  assert.equal(b.data("state"), "running", "the html-label card re-renders from this");
  assert.equal(g.editor.nodeStates.b, "running");
});

test("success of the TARGET: consumer-running clears; the edge takes no `done` (151 retired it)", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("b", "running");
  g.setNodeState("b", "success");
  assert.ok(!edge.hasClass("active"), "consumer-running clears at completion");
  assert.ok(!edge.hasClass("done"), "`done` (target ran) is retired — the edge's states are facts about its SOURCE");
});

test("success of the SOURCE: the outgoing dependency is satisfied", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("a", "success");
  assert.ok(edge.hasClass("satisfied"), "b may start: a is done");
});

test("failed: consumer-running clears and nothing else survives on the incoming edge", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("b", "running");
  g.setNodeState("b", "failed");
  assert.ok(!edge.hasClass("active"));
  assert.ok(!edge.hasClass("done") && !edge.hasClass("satisfied"));
});

test("resetAll: every edge state clears for the next run", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("a", "success");
  g.setNodeState("b", "running");
  g.setNodeStats("a", { duration_ms: 10, rows_out: 5 });
  g.resetAll();
  assert.deepEqual(edge.classes(), []);
  assert.equal(g.editor.nodeStates.b, "idle");
});

test("setNodeStats: the footer takes the run line; the OUTGOING edge takes nothing (151)", () => {
  const { g, edge, a } = graphWithEdge();
  g.setNodeStats("a", { duration_ms: 842, rows_out: 1203552 });
  assert.equal(a.data("run"), "1,203,552 rows · 842 ms");
  assert.equal(edge.data("rowLabel"), undefined, "the count is what a WROTE once — it does not travel along the arrow");
  assert.ok(!edge.hasClass("rows"));
});

test("setNodeStats with NOT_MEASURED rows: the footer shows the time alone", () => {
  const { g, a } = graphWithEdge();
  g.setNodeStats("a", { duration_ms: 12, rows_out: -1 });
  assert.equal(a.data("run"), "12 ms");
});

test("the pulse gate honours reduced motion (the canvas has no moving edge any more)", () => {
  const graph = loadGraph();
  assert.equal(graph.pulseEnabled({ matches: true }), false);
  assert.equal(typeof graph.PipelineGraph.prototype.ensureFlow, "undefined", "151: the rAF dash loop is retired");
});
