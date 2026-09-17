// 135 — the abort pulse: a node cancelled because a sibling failed keeps its
// running pulse while its footer reads "Aborted".
//
// The witness (execution f637891a-ba47-4cd2-99b8-8dfb25f9b33f, 2026-09-17, on
// issue #135): stg_rideshare_base started (event 3), sampled connecting (14)
// and executing (18); the sibling stg_rideshare_comp failed (38); base then got
// ONLY a node_progress terminal sample with state "aborted" (39) — the executor
// deliberately emits no node-level terminal event for a cancellation — followed
// by pipeline_failed (40). node_stats records base ABORTED.
//
// The client bug: no `case "node_progress"` in sse.js's switch maps a terminal
// sample to graph.setNodeState, and the pipeline_failed handler does not sweep
// nodes (only execution_aborted does), so base stayed in graph state `running`:
// pe-card-running with its infinite pe-pulse dot, an active edge flow, and
// minimap/a11y mirrors still saying running. A never-started node the server
// records ABORTED stayed "Pending" for the same reason.
//
// This fixture replays the witness stream through the REAL modules (sse.js's
// dispatch, node-ops.js's reducer, graph.js's setNodeState/buildCardHtml) over
// hand-rolled cy fakes — the sse-node-failure.test.mjs shape — and asserts what
// the owner must see: the aborted card class, the "Aborted" footer word, the
// cleared incoming edge flow, the agreeing a11y state, and the pipeline_failed
// sweep moving still-idle/running nodes to aborted without touching the ones
// that reached a real terminal state. Red on the pre-135 client, by construction.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const opsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/node-ops.js");
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");

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
    style() { return "0"; },
  };
}

function fakeNode(id, incomers, outgoers) {
  const classes = new Set(["idle"]);
  const data = { id };
  return {
    id: () => id,
    length: 1,
    removeClass(c) { classes.delete(c); },
    addClass(c) { classes.add(c); },
    hasClass: (c) => classes.has(c),
    classes: () => [...classes],
    data(k, v) { if (v === undefined) return data[k]; data[k] = v; },
    incomers: () => incomers || [],
    outgoers: () => outgoers || [],
  };
}

/** The witness graph: upstream feeds two parallel branches; `pending` never starts. */
function buildWorld() {
  const edgeUpBase = fakeEdge("upstream->stg_rideshare_base");
  const edgeUpComp = fakeEdge("upstream->stg_rideshare_comp");
  const upstream = fakeNode("upstream", [], [edgeUpBase, edgeUpComp]);
  const base = fakeNode("stg_rideshare_base", [edgeUpBase], []);
  const comp = fakeNode("stg_rideshare_comp", [edgeUpComp], []);
  const pending = fakeNode("pending_side_effect", [], []);
  const nodes = [upstream, base, comp, pending];
  const byId = {};
  nodes.forEach((n) => { byId[n.id()] = n; });
  const edges = [edgeUpBase, edgeUpComp];
  const cy = {
    getElementById: (id) => byId[id] || { length: 0 },
    nodes: () => nodes,
    edges: (sel) => (sel === ".active" ? edges.filter((e) => e.hasClass("active")) : edges),
  };
  return { cy, nodes, byId, edgeUpBase, edgeUpComp };
}

function loadAll() {
  globalThis.window = {};
  delete require.cache[require.resolve(opsPath)];
  delete require.cache[require.resolve(graphPath)];
  delete require.cache[require.resolve(ssePath)];
  require(opsPath);
  require(graphPath);
  require(ssePath);
  return globalThis.window;
}

/**
 * The editor as the live page wires it (init.js): the real reducer, the real
 * PipelineGraph prototype over the fake cy, and an a11y recorder where the page
 * has the DOM list.
 */
function editorFor(win, world) {
  const graph = Object.create(win.PipelineGraph.prototype);
  graph.cy = world.cy;
  graph.tokens = { cardW: 236, cardH: 148 };
  graph._mm = null;
  graph._flowRunning = false;
  const a11y = [];
  win.a11yNodeState = (id, state) => a11y.push([id, state]);
  const editor = {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    nodeValues: {},
    childExecutions: {},
    nodeOps: win.PENodeOps.createNodeOps(),
    graph,
    a11y,
    failures: [],
    setBanner() {},
    showError() {},
    announceStatus() {},
    stopRunClock() {},
    recordFailure(nodeId, error) { this.failures.push([nodeId, error]); },
    handlePipelineFailed(payload) { this.lastFailure = payload; },
    handleDataReady: null,
  };
  // The real constructor wires this back-reference (PipelineGraph(containerId,
  // nodes, editor)); Object.create skips it, and setNodeState's editor mirror
  // writes depend on it.
  graph.editor = editor;
  return editor;
}

/** The witness stream (issue #135's table, plus the surrounding context). */
function replay(handler) {
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));
  send("execution_started", { execution_id: "f637891a-ba47-4cd2-99b8-8dfb25f9b33f", parameters: {} });
  send("node_started", { execution_id: "f637891a", node_id: "upstream" });
  send("node_progress", { execution_id: "f637891a", node_id: "upstream", sequence: 1, operation: "stage", destination: { kind: "tempdb", table: "up" }, state: "writing", rows_written: 10 });
  send("node_completed", { execution_id: "f637891a", node_id: "upstream", duration_ms: 120, rows_out: 10 });
  // event 3
  send("node_started", { execution_id: "f637891a", node_id: "stg_rideshare_base" });
  send("node_started", { execution_id: "f637891a", node_id: "stg_rideshare_comp" });
  // event 14
  send("node_progress", { execution_id: "f637891a", node_id: "stg_rideshare_base", sequence: 2, operation: "stage", destination: { kind: "datasource", datasource: "rideshare", table: "raw" }, state: "connecting" });
  // event 18
  send("node_progress", { execution_id: "f637891a", node_id: "stg_rideshare_base", sequence: 3, operation: "stage", destination: { kind: "tempdb", table: "base" }, state: "executing" });
  // event 38 — the sibling fails; base is NOT in its dependents (it was already running).
  send("node_failed", {
    execution_id: "f637891a",
    node_id: "stg_rideshare_comp",
    error: { code: "pipeline.node.execution_failed", message: "sibling blew up" },
  });
  // event 39 — base's ONLY terminal signal: the #125 operation sample.
  send("node_progress", { execution_id: "f637891a", node_id: "stg_rideshare_base", sequence: 4, operation: "stage", destination: { kind: "tempdb", table: "base" }, state: "aborted" });
  // event 40
  send("pipeline_failed", { execution_id: "f637891a", error: { code: "pipeline.node.execution_failed", message: "sibling blew up", node: { id: "stg_rideshare_comp" } } });
}

/** The card HTML for one node, rendered by the REAL renderer from its recorded data. */
function cardHtml(win, node) {
  return win.PEGraphUtil.buildCardHtml({
    id: node.id(),
    type: "DQL",
    state: node.data("state"),
    op: node.data("op"),
    opCounts: node.data("opCounts"),
    run: node.data("run"),
    facts: [{ kind: "output", text: "tempdb.base" }],
  });
}

test("the aborted terminal sample moves the still-running node to aborted — pulse class gone, footer Aborted", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  replay(new win.SseHandler(editor));

  const base = world.byId.stg_rideshare_base;
  assert.equal(editor.nodeStates.stg_rideshare_base, "aborted", "the nodeStates mirror says aborted");
  const html = cardHtml(win, base);
  assert.match(html, /pe-card-aborted/, "the card renders the aborted class, not the pulsing running one");
  assert.ok(!html.includes("pe-card-running"), "the running pulse class is gone");
  assert.match(html, /pe-card-st">Aborted</, "the footer word is Aborted");
});

test("the incoming edge flow clears and the edge into the failed sibling's neighbour does not read done", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  replay(new win.SseHandler(editor));

  assert.ok(!world.edgeUpBase.hasClass("active"), "the flow into the aborted node stopped");
  assert.ok(!world.edgeUpBase.hasClass("done"), "an aborted node's edge never reads done");
  assert.ok(world.edgeUpComp.hasClass("done") === false && world.edgeUpComp.hasClass("active") === false, "the failed node's edge flow stopped too");
});

test("the a11y mirror agrees: aborted, not running", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  replay(new win.SseHandler(editor));

  const forBase = editor.a11y.filter(([id]) => id === "stg_rideshare_base");
  assert.ok(forBase.length >= 2, "the node's transitions reached the a11y list");
  assert.deepEqual(forBase.at(-1), ["stg_rideshare_base", "aborted"], "the LAST a11y state for the node is aborted — running appeared only while it truly ran");
});

test("pipeline_failed sweeps what never reached a terminal state: the never-started node is aborted, not Pending", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  replay(new win.SseHandler(editor));

  assert.equal(editor.nodeStates.pending_side_effect, "aborted", "the never-started node the server records ABORTED must not stay Pending");
  const html = cardHtml(win, world.byId.pending_side_effect);
  assert.match(html, /pe-card-aborted/);
  // The nodes that DID reach a real terminal state keep it.
  assert.equal(editor.nodeStates.upstream, "success");
  assert.equal(editor.nodeStates.stg_rideshare_comp, "failed");
  const compHtml = cardHtml(win, world.byId.stg_rideshare_comp);
  assert.match(compHtml, /pe-card-failed/, "the sweep must not overwrite the failed node");
  const upHtml = cardHtml(win, world.byId.upstream);
  assert.match(upHtml, /pe-card-success/, "the sweep must not overwrite the completed node");
});

test("a live writing sample keeps the node running — only terminal samples move the graph", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  const handler = new win.SseHandler(editor);
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));
  send("execution_started", { execution_id: "e1", parameters: {} });
  send("node_started", { execution_id: "e1", node_id: "stg_rideshare_base" });
  send("node_progress", { execution_id: "e1", node_id: "stg_rideshare_base", sequence: 2, operation: "stage", destination: { kind: "tempdb", table: "base" }, state: "writing", rows_written: 11000 });
  assert.equal(editor.nodeStates.stg_rideshare_base, "running", "a mid-run sample must not end the node");
  assert.match(cardHtml(win, world.byId.stg_rideshare_base), /pe-card-running/);
});

test("replay parity: a fresh handler over the same events renders the same final states", () => {
  const win = loadAll();
  const first = editorFor(win, buildWorld());
  replay(new win.SseHandler(first));
  const second = editorFor(win, buildWorld());
  replay(new win.SseHandler(second));
  assert.deepEqual(second.nodeStates, first.nodeStates, "a finished execution's replay paints what the live stream painted");
});
