// 150 — the execution boundaries (#126): view-only Start and End markers derived
// from the authored graph, with the boundary connector kind 151 will style.
//
// What this file pins, per the lane's element model (recorded before coding in
// the lane's evidence):
//
//   1. DETERMINISTIC DERIVATION — Start connects to every node with no
//      depends_on, every leaf connects to End; one-node pipelines get Start →
//      node → End; empty drafts get nothing.
//   2. COLLISION SAFETY — an authored node literally named "__execution_start__"
//      shifts the synthetic id; the KIND is data (`kind: "boundary"`), never id
//      text; the authored array is never mutated, so nothing synthetic can leak
//      into a save path, a count or an API request.
//   3. LIFECYCLE — Start arms on execution_started; End moves ONLY on the
//      authoritative execution terminal event (Finished / Failed / Stopped),
//      never on a node event; a first completed leaf leaves End non-terminal;
//      eligible roots are NOT running roots (flow appears only on node_started);
//      the 135 sweep skips the markers; a fresh run resets them.
//   4. NOT A TRANSFER — a completing leaf never labels its leaf→End connector
//      with its row count (151 styles these lines as boundaries and retired edge
//      counts altogether — graph-dependency-edges.test.mjs).
//
// Same loader family as sse-abort-pulse.test.mjs: real sse.js + node-ops.js +
// graph.js modules over hand-rolled cy fakes.

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

/* ------------------------------------------------------------ derivation */

test("buildElements: Start connects to every root, every leaf connects to End", () => {
  const { buildElements } = loadAll().PEGraphUtil;
  const nodes = [
    { id: "root_a", type: "DQL", source: "pg" },
    { id: "root_b", type: "DML", source: "pg" },
    { id: "mid", type: "DQL", source: "tempdb", depends_on: ["root_a"] },
    { id: "leaf_a", type: "DQL", source: "tempdb", depends_on: ["mid"] },
    { id: "leaf_b", type: "DDL", source: "tempdb", depends_on: ["root_b", "mid"] },
  ];
  const els = buildElements(nodes);
  const markers = els.filter((e) => e.group === "nodes" && e.data.kind === "boundary");
  assert.equal(markers.length, 2, "one Start and one End");
  const bySide = Object.fromEntries(markers.map((m) => [m.data.boundary, m]));
  assert.deepEqual([bySide.start.data.id, bySide.end.data.id], ["__execution_start__", "__execution_end__"]);
  assert.equal(bySide.start.classes.includes("boundary"), true, "the marker node carries the boundary class too");

  const boundaryEdges = els.filter((e) => e.group === "edges" && e.data.kind === "boundary");
  const pairs = boundaryEdges.map((e) => [e.data.source, e.data.target]).sort();
  // roots: root_a, root_b — leaves: leaf_a, leaf_b ("_" sorts before letters)
  assert.deepEqual(pairs, [
    ["__execution_start__", "root_a"],
    ["__execution_start__", "root_b"],
    ["leaf_a", "__execution_end__"],
    ["leaf_b", "__execution_end__"],
  ]);
  for (const e of boundaryEdges) {
    assert.equal(e.classes.includes("boundary"), true, "the connector carries the boundary class for 151");
  }
});

test("buildElements: a one-node pipeline gets Start → node → End", () => {
  const { buildElements } = loadAll().PEGraphUtil;
  const els = buildElements([{ id: "solo", type: "DQL", source: "pg" }]);
  const boundaryEdges = els.filter((e) => e.group === "edges" && e.data.kind === "boundary");
  assert.deepEqual(
    boundaryEdges.map((e) => [e.data.source, e.data.target]).sort(),
    [["__execution_start__", "solo"], ["solo", "__execution_end__"]],
  );
});

test("buildElements: an empty draft renders no boundaries — the honest empty view", () => {
  const { buildElements } = loadAll().PEGraphUtil;
  assert.deepEqual(buildElements([]), []);
});

test("FALSIFY synthetic collision: an authored node named like the reserved base", () => {
  const { buildElements, boundaryIdsFor } = loadAll().PEGraphUtil;
  const nodes = [
    { id: "__execution_start__", type: "DQL", source: "pg" },
    { id: "plain", type: "DQL", source: "pg", depends_on: ["__execution_start__"] },
  ];
  const els = buildElements(nodes);
  const ids = els.filter((e) => e.group === "nodes").map((e) => e.data.id);
  assert.equal(new Set(ids).size, ids.length, "no duplicate element ids");
  assert.equal(boundaryIdsFor(nodes).start, "___execution_start__", "the synthetic id grows out of the collision deterministically");
  // The KIND stays data-driven: the authored node is NOT a boundary even though
  // its id text matches the reserved name.
  const authored = els.find((e) => e.group === "nodes" && e.data.id === "__execution_start__");
  assert.equal(authored.data.kind, undefined);
  assert.equal(authored.data.kind === "boundary", false);
  const marker = els.find((e) => e.group === "nodes" && e.data.kind === "boundary" && e.data.boundary === "start");
  assert.equal(marker.data.id, "___execution_start__");
  // And the Start connector reaches for the real root, through the shifted id.
  const startEdge = els.find((e) => e.group === "edges" && e.data.kind === "boundary" && e.data.target === "__execution_start__");
  assert.equal(startEdge.data.source, "___execution_start__");
});

test("FALSIFY synthetic leak: buildElements never mutates the authored array", () => {
  const { buildElements } = loadAll().PEGraphUtil;
  const nodes = [
    { id: "a", type: "DQL", source: "pg" },
    { id: "b", type: "DQL", source: "pg", depends_on: ["a"] },
  ];
  const snapshot = JSON.stringify(nodes);
  buildElements(nodes);
  assert.equal(JSON.stringify(nodes), snapshot, "the authored array the save path reads is untouched");
  assert.equal(nodes.length, 2);
});

/* ------------------------------------------------------- the marker pill */

test("the marker renders a SHAPE, not a card: no ports, no open button, no facts, no port row, an aria-label (151 redesign)", () => {
  const { buildCardHtml } = loadAll().PEGraphUtil;
  const html = buildCardHtml({ id: "__execution_start__", kind: "boundary", boundary: "start", state: "running" });
  assert.match(html, /pe-card-boundary pe-card-boundary-start/);
  assert.match(html, /pe-card-running/);
  assert.match(html, /class="pe-marker pe-marker-start"/, "the disc — graph-markers.test.mjs owns the button/img table");
  assert.match(html, /aria-label="Execution start — running"/);
  assert.ok(!html.includes("pe-card-open"), "nothing to open — a marker is not a node");
  assert.ok(!html.includes("pe-card-port"), "no ports");
  assert.ok(!html.includes("pe-port "), "no output port row");
  assert.ok(!html.includes("pe-card-fact"), "no facts");
  assert.ok(!html.includes("pe-card-progress"), "no progress line");
  assert.ok(!html.includes("pe-card-rt"), "no run numbers");

  const endIdle = buildCardHtml({ id: "__execution_end__", kind: "boundary", boundary: "end", state: "idle" });
  assert.match(endIdle, /pe-marker-word">End</);
  const endDone = buildCardHtml({ id: "__execution_end__", kind: "boundary", boundary: "end", state: "success" });
  assert.match(endDone, /pe-marker-word">Finished</);
  assert.match(endDone, /aria-label="Execution end — finished"/);
  const endStopped = buildCardHtml({ id: "__execution_end__", kind: "boundary", boundary: "end", state: "aborted" });
  assert.match(endStopped, /pe-marker-word">Stopped</);
  const endFailed = buildCardHtml({ id: "__execution_end__", kind: "boundary", boundary: "end", state: "failed" });
  assert.match(endFailed, /pe-marker-word">Failed</);
});

/* -------------------------------------------------- the cy fake, again */

function fakeEdge(id, kind) {
  const classes = new Set();
  const data = { id };
  if (kind) data.kind = kind;
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

function fakeNode(id, kind, boundary, incomers, outgoers) {
  const classes = new Set(kind === "boundary" ? ["idle", "boundary"] : ["idle"]);
  const data = { id };
  if (kind) {
    data.kind = kind;
    data.boundary = boundary;
    data.label = boundary === "end" ? "End" : "Start";
  }
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

/** A two-root graph under a parallelism limit: only rootA ever starts. */
function buildWorld() {
  const edgeStartA = fakeEdge("__execution_start__->root_a", "boundary");
  const edgeStartB = fakeEdge("__execution_start__->root_b", "boundary");
  const edgeAB = fakeEdge("root_a->leaf");
  const edgeEndLeaf = fakeEdge("leaf->__execution_end__", "boundary");
  const startM = fakeNode("__execution_start__", "boundary", "start", [], [edgeStartA, edgeStartB]);
  const endM = fakeNode("__execution_end__", "boundary", "end", [edgeEndLeaf], []);
  const rootA = fakeNode("root_a", null, null, [edgeStartA], [edgeAB]);
  const rootB = fakeNode("root_b", null, null, [edgeStartB], []);
  const leaf = fakeNode("leaf", null, null, [edgeAB], [edgeEndLeaf]);
  const nodes = [startM, endM, rootA, rootB, leaf];
  const byId = {};
  nodes.forEach((n) => { byId[n.id()] = n; });
  const edges = [edgeStartA, edgeStartB, edgeAB, edgeEndLeaf];
  const cy = {
    getElementById: (id) => byId[id] || { length: 0 },
    nodes: () => nodes,
    edges: (sel) => (sel === ".active" ? edges.filter((e) => e.hasClass("active")) : edges),
  };
  return { cy, byId, edgeStartA, edgeStartB, edgeAB, edgeEndLeaf, startM, endM, rootA, rootB, leaf };
}

function editorFor(win, world) {
  const graph = Object.create(win.PipelineGraph.prototype);
  graph.cy = world.cy;
  graph.tokens = { cardW: 236, cardH: 148 };
  graph._mm = null;
  graph._flowRunning = false;
  graph._boundaryIds = { start: "__execution_start__", end: "__execution_end__" };
  const editor = {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    nodeValues: {},
    childExecutions: {},
    nodeOps: win.PENodeOps.createNodeOps(),
    graph,
    setBanner() {},
    showError() {},
    announceStatus() {},
    stopRunClock() {},
    handlePipelineFailed() {},
    handleDataReady: null,
  };
  graph.editor = editor;
  return editor;
}

/* ------------------------------------------------------- the truth table */

test("eligible is not running: under a parallelism limit only the STARTED root's connector flows", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  const handler = new win.SseHandler(editor);
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));

  send("execution_started", { execution_id: "e1", parameters: {} });
  // Both roots ELIGIBLE: neither connector flows, Start is armed.
  assert.ok(!world.edgeStartA.hasClass("active"), "an eligible root is not a running root");
  assert.ok(!world.edgeStartB.hasClass("active"));
  assert.equal(world.startM.data("state"), "running");
  assert.equal(world.endM.data("state"), "idle");

  send("node_started", { execution_id: "e1", node_id: "root_a" });
  assert.ok(world.edgeStartA.hasClass("active"), "the started root's boundary connector flows");
  assert.ok(!world.edgeStartB.hasClass("active"), "the queued root's connector stands still");
});

test("FALSIFY first-leaf-implies-End: a finished branch leaves End non-terminal", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  const handler = new win.SseHandler(editor);
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));

  send("execution_started", { execution_id: "e1", parameters: {} });
  send("node_started", { execution_id: "e1", node_id: "root_a" });
  send("node_progress", { execution_id: "e1", node_id: "root_a", sequence: 1, operation: "stage", destination: { kind: "tempdb", table: "a" }, state: "writing", rows_written: 500 });
  send("node_completed", { execution_id: "e1", node_id: "root_a", duration_ms: 40, rows_out: 500 });
  send("node_started", { execution_id: "e1", node_id: "leaf" });
  send("node_completed", { execution_id: "e1", node_id: "leaf", duration_ms: 10, rows_out: 500 });

  assert.equal(world.endM.data("state"), "idle", "End is the EXECUTION's word, not the first leaf's");
  assert.equal(world.endM.data("label"), "End");
});

test("the terminal events speak at End: Finished / Failed / Stopped — and Start goes neutral", () => {
  const run = (terminalKind, payload) => {
    const win = loadAll();
    const world = buildWorld();
    const editor = editorFor(win, world);
    const handler = new win.SseHandler(editor);
    const send = (kind, p) => handler.dispatch(kind, JSON.stringify(p));
    send("execution_started", { execution_id: "e1", parameters: {} });
    send("node_started", { execution_id: "e1", node_id: "root_a" });
    send(terminalKind, payload);
    return world;
  };
  const done = run("pipeline_completed", { execution_id: "e1", duration_ms: 90 });
  assert.equal(done.endM.data("state"), "success");
  assert.equal(done.endM.data("label"), "Finished");
  assert.equal(done.startM.data("state"), "idle");
  const failed = run("pipeline_failed", { execution_id: "e1", error: { code: "x" } });
  assert.equal(failed.endM.data("state"), "failed");
  assert.equal(failed.endM.data("label"), "Failed");
  assert.equal(failed.startM.data("state"), "idle");
  const stopped = run("execution_aborted", { execution_id: "e1", reason: "cancelled" });
  assert.equal(stopped.endM.data("state"), "aborted");
  assert.equal(stopped.endM.data("label"), "Stopped");
  assert.equal(stopped.startM.data("state"), "idle");
});

test("FALSIFY marker-sweep leak: pipeline_failed sweeps the queued root but never the markers", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  const handler = new win.SseHandler(editor);
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));

  send("execution_started", { execution_id: "e1", parameters: {} });
  send("node_started", { execution_id: "e1", node_id: "root_a" });
  send("node_failed", { execution_id: "e1", node_id: "root_a", error: { code: "x", message: "boom" } });
  send("pipeline_failed", { execution_id: "e1", error: { code: "x" } });

  assert.equal(editor.nodeStates.root_b, "aborted", "the queued root the server recorded ABORTED is swept");
  assert.equal(editor.nodeStates.root_a, "failed");
  assert.equal(world.endM.data("state"), "failed", "End reads the authoritative outcome, not the sweep");
  assert.equal(world.startM.data("state"), "idle");
  // And no synthetic id ever entered the nodeStates mirror:
  assert.equal(editor.nodeStates.__execution_start__, undefined);
  assert.equal(editor.nodeStates.__execution_end__, undefined);
});

test("a fresh run resets the boundaries: End loses the previous outcome, Start re-arms", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  const handler = new win.SseHandler(editor);
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));

  send("execution_started", { execution_id: "e1", parameters: {} });
  send("pipeline_completed", { execution_id: "e1", duration_ms: 9 });
  assert.equal(world.endM.data("label"), "Finished");

  send("execution_started", { execution_id: "e2", parameters: {} });
  assert.equal(world.endM.data("state"), "idle");
  assert.equal(world.endM.data("label"), "End");
  assert.equal(world.startM.data("state"), "running");
});

test("FALSIFY transfer-label leak: a completing leaf never labels its End connector", () => {
  const win = loadAll();
  const world = buildWorld();
  const editor = editorFor(win, world);
  const g = editor.graph;
  g.setNodeState("leaf", "success");
  g.setNodeStats("leaf", { duration_ms: 12, rows_out: 4242 });
  assert.ok(!world.edgeEndLeaf.hasClass("rows"), "the boundary connector carries no row class");
  assert.equal(world.edgeEndLeaf.data("rowLabel"), undefined, "and no row label — a leaf's rows_out is not a transfer into End");
  // 151: the authored edge is a dependency and carries no count either — the
  // producer's footer and output port hold "7 rows", scoped to what it wrote.
  g.setNodeStats("root_a", { duration_ms: 5, rows_out: 7 });
  assert.equal(world.edgeAB.data("rowLabel"), undefined);
  assert.equal(world.rootA.data("run"), "7 rows · 5 ms");
});
