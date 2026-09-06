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

test("success: the flow stops and the edge rests in --edge-done", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("b", "running");
  g.setNodeState("b", "success");
  assert.ok(!edge.hasClass("active"), "the flow stops at completion");
  assert.ok(edge.hasClass("done"), "the edge into a done target turns --edge-done");
});

test("failed: the flow stops and no done marker survives", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("b", "running");
  g.setNodeState("b", "failed");
  assert.ok(!edge.hasClass("active"));
  assert.ok(!edge.hasClass("done"));
});

test("resetAll: every edge state and label clears for the next run", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeState("b", "running");
  g.setNodeState("b", "success");
  g.setNodeStats("a", { duration_ms: 10, rows_out: 5 });
  g.resetAll();
  assert.ok(!edge.hasClass("active") && !edge.hasClass("done") && !edge.hasClass("rows"));
  assert.equal(edge.data("rowLabel"), "");
  assert.equal(g.editor.nodeStates.b, "idle");
});

test("setNodeStats: the footer takes the run line and the OUTGOING edge takes the row label", () => {
  const { g, edge, a } = graphWithEdge();
  g.setNodeStats("a", { duration_ms: 842, rows_out: 1203552 });
  assert.equal(a.data("run"), "1,203,552 rows · 842 ms");
  assert.equal(edge.data("rowLabel"), "1,203,552 rows", "the count flowing OUT of the source rides the edge");
  assert.ok(edge.hasClass("rows"), "…behind the class the stylesheet gates the label on");
});

test("setNodeStats with NOT_MEASURED rows labels no edge", () => {
  const { g, edge } = graphWithEdge();
  g.setNodeStats("a", { duration_ms: 12, rows_out: -1 });
  assert.ok(!edge.hasClass("rows"));
  assert.equal(edge.data("rowLabel"), undefined);
});

test("the flow animation is gated on reduced motion — dashes stand still", () => {
  const { g } = graphWithEdge();
  globalThis.window = { matchMedia: () => ({ matches: true }) };
  g.ensureFlow();
  assert.equal(g._flowRunning, false, "reduced motion: the rAF loop never starts");
  delete globalThis.window;
});

test("the flow loop starts on motion-OK and stops itself when no edge is active", () => {
  const { g, edge } = graphWithEdge();
  let scheduled = null;
  globalThis.window = { matchMedia: () => ({ matches: false }) };
  globalThis.requestAnimationFrame = (cb) => { scheduled = cb; };
  g.setNodeState("b", "running");
  assert.equal(g._flowRunning, true, "an active edge keeps the loop alive");
  edge.style = () => "0";
  edge.removeClass("active");
  const step = scheduled;
  step(); // one tick: no active edges left — the loop must stop, not spin
  assert.equal(g._flowRunning, false, "the loop stops itself when the last flow ends");
  delete globalThis.window;
  delete globalThis.requestAnimationFrame;
});
