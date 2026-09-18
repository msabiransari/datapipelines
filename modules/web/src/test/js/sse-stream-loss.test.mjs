// 151 (#127) — clearing motion when the STREAM goes, not only when the run does.
//
// The live terminal events (pipeline_completed / pipeline_failed / execution_aborted)
// already settle every card, port and marker. Two paths did not: the stream dropping
// mid-run left running pulses and consumer-running edges moving forever, and the
// recovery poll that later learned the outcome updated the banner and clock only.
//
//   1. handleConnectionLoss freezes the graph (`markStreamLost`) — no outcome invented.
//   2. A recovery poll that learns the execution ENDED closes every open operation
//      through the reducer (→ "commit not observed"), sets the End marker from the
//      polled status, and sweeps unfinished nodes exactly as the live terminal does.
//
// Real sse.js + node-ops.js + graph.js over the graph-boundaries fakes; `fetch` stubbed.

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

function fakeEdge(id, kind) {
  const classes = new Set(kind ? [kind] : []);
  const data = { id, kind };
  return { id, length: 1, classes: () => [...classes], hasClass: (c) => classes.has(c), addClass(c) { classes.add(c); }, removeClass(c) { classes.delete(c); }, data(k, v) { if (v === undefined) return data[k]; data[k] = v; } };
}
function fakeNode(id, kind, boundary, incomers, outgoers) {
  const classes = new Set(kind === "boundary" ? ["idle", "boundary"] : ["idle"]);
  const data = { id, state: "idle" };
  if (kind) { data.kind = kind; data.boundary = boundary; }
  return { id: () => id, length: 1, removeClass(c) { classes.delete(c); }, addClass(c) { classes.add(c); }, hasClass: (c) => classes.has(c), classes: () => [...classes], data(k, v) { if (v === undefined) return data[k]; data[k] = v; }, incomers: () => incomers || [], outgoers: () => outgoers || [] };
}

function world(win) {
  const eSA = fakeEdge("__execution_start__->a", "boundary");
  const eAB = fakeEdge("a->b", "dependency");
  const eBE = fakeEdge("b->__execution_end__", "boundary");
  const start = fakeNode("__execution_start__", "boundary", "start", [], [eSA]);
  const end = fakeNode("__execution_end__", "boundary", "end", [eBE], []);
  const a = fakeNode("a", null, null, [eSA], [eAB]);
  const b = fakeNode("b", null, null, [eAB], [eBE]);
  const nodes = [start, end, a, b];
  const byId = Object.fromEntries(nodes.map((n) => [n.id(), n]));
  const edges = [eSA, eAB, eBE];
  const graph = Object.create(win.PipelineGraph.prototype);
  graph.cy = { getElementById: (id) => byId[id] || { length: 0 }, nodes: () => nodes, edges: () => edges };
  graph.tokens = { cardW: 236, cardH: 148 };
  graph._mm = null;
  graph._boundaryIds = { start: "__execution_start__", end: "__execution_end__" };
  const editor = {
    isExecuting: true, nodeStates: {}, nodeErrors: {}, nodeValues: {}, childExecutions: {},
    nodeOps: win.PENodeOps.createNodeOps(), graph,
    banner: null, setBanner(t, k) { this.banner = [t, k]; }, showError() {}, announceStatus() {}, stopRunClock() {}, handlePipelineFailed() {},
  };
  graph.editor = editor;
  return { editor, graph, start, end, a, b, eSA, eAB, eBE };
}

function midRun(win, w) {
  const handler = new win.SseHandler(w.editor);
  const send = (kind, payload) => handler.dispatch(kind, JSON.stringify(payload));
  send("execution_started", { execution_id: "e1", parameters: {} });
  send("node_started", { execution_id: "e1", node_id: "a" });
  send("node_progress", { execution_id: "e1", node_id: "a", sequence: 1, operation: "stage", destination: { kind: "tempdb", table: "stg" }, state: "writing", rows_written: 40 });
  return handler;
}

const tick = () => new Promise((r) => setImmediate(r));

test("stream lost mid-write: every node freezes, the port says so, nothing is rewritten", () => {
  const win = loadAll();
  const w = world(win);
  const handler = midRun(win, w);
  assert.equal(w.a.data("port").state, "writing");
  assert.ok(w.eSA.hasClass("active"), "the root's connector was consumer-running");
  handler.pollExecution = () => {}; // the poll is exercised below
  handler.handleConnectionLoss();
  assert.equal(w.a.data("stale"), true);
  assert.equal(w.start.data("stale"), true, "the Start pulse freezes too");
  assert.equal(w.a.data("state"), "running", "not rewritten — nothing was observed");
  assert.equal(w.a.data("port").state, "writing", "the last measured word stands; the card appends — stream lost");
  assert.ok(!w.eSA.hasClass("active"), "consumer-running leaves every edge");
  assert.equal(w.editor.banner[1], "connection-lost");
});

async function polled(status) {
  const win = loadAll();
  const w = world(win);
  const handler = midRun(win, w);
  handler.executionId = "e1";
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ data: { status } }) });
  try {
    handler.pollExecution();
    await tick(); await tick(); await tick();
  } finally {
    delete globalThis.fetch;
  }
  return w;
}

test("recovery poll learns SUCCESS: End reads Finished, Start rests, the open write is closed unobserved", async () => {
  const w = await polled("SUCCESS");
  assert.equal(w.end.data("state"), "success");
  assert.equal(w.end.data("label"), "Finished");
  assert.equal(w.start.data("state"), "idle");
  const port = w.a.data("port");
  assert.equal(port.state, "aborted", "the reducer closes an open operation the client never saw end (149's rule)");
  assert.match(port.text, /commit not observed/);
  assert.equal(w.a.data("state"), "running", "a polled SUCCESS is not a node_completed — the card is not painted Done");
  assert.equal(w.editor.isExecuting, false);
});

test("recovery poll learns FAILED: End reads Failed and unfinished nodes are swept exactly as the live path does", async () => {
  const w = await polled("FAILED");
  assert.equal(w.end.data("label"), "Failed");
  assert.equal(w.a.data("state"), "aborted", "the live pipeline_failed sweep (135) applies to the polled outcome too");
  assert.equal(w.b.data("state"), "aborted");
  assert.ok(!w.eSA.hasClass("active"));
});

test("recovery poll learns ABORTED: End reads Stopped", async () => {
  const w = await polled("ABORTED");
  assert.equal(w.end.data("label"), "Stopped");
  assert.equal(w.a.data("state"), "aborted");
});
