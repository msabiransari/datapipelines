// 149 — the editor's node_progress path: sse.js reduces EVERY lifecycle event into
// the node-ops view model, hands the card its operation line, records the a11y
// text, and closes operations honestly on node/execution terminal events. Same
// loader as sse-node-failure.test.mjs: sse.js and node-ops.js are browser IIFEs
// publishing on `window`.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");
const opsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/node-ops.js");

function loadHandler() {
  globalThis.window = {};
  delete require.cache[require.resolve(opsPath)];
  delete require.cache[require.resolve(ssePath)];
  require(opsPath);
  require(ssePath);
  return globalThis.window;
}

function fakeEditor(win) {
  const cardOps = [];
  const announced = [];
  return {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    nodeOps: win.PENodeOps.createNodeOps(),
    graph: {
      resetAll() {},
      setNodeState() {},
      setNodeStats() {},
      setNodeOperation(id, view) { cardOps.push([id, view ? view.cardLine : null]); },
      cy: { nodes: () => [] },
    },
    cardOps,
    announced,
    setBanner() {},
    showError() {},
    announceStatus(m) { announced.push(m); },
    handleDataReady: null,
    handlePipelineFailed: null,
  };
}

const SAMPLE = {
  execution_id: "e1",
  node_id: "stage_trips",
  attempt: 1,
  sequence: 2,
  operation: "stage",
  destination: { kind: "tempdb", table: "trips" },
  state: "writing",
  started_at: "2026-09-16T10:00:00.100Z",
  observed_at: "2026-09-16T10:00:05.401Z",
  elapsed_ms: 5301,
  timings_ms: { executing: 410, fetching: 3400, waiting_output: 22, writing: 1457 },
  rows_fetched: 12000,
  rows_written: 11000,
  batches_written: 11,
  correlation_id: "c",
};

test("node_progress reduces into editor.nodeOps and hands the card its operation line", () => {
  const win = loadHandler();
  const editor = fakeEditor(win);
  const handler = new win.SseHandler(editor);
  handler.dispatch("node_started", JSON.stringify({ execution_id: "e1", node_id: "stage_trips" }));
  handler.dispatch("node_progress", JSON.stringify(SAMPLE));
  const op = editor.nodeOps.get("stage_trips");
  assert.equal(op.state, "writing");
  assert.equal(op.rowsWritten, 11000);
  assert.deepEqual(editor.cardOps.at(-1), ["stage_trips", "Writing → tempdb.trips · 11,000 written"]);
  // Progress is not announced per sample — the live region is for node lifecycle only.
  assert.equal(editor.announced.some((m) => /Writing/.test(m)), false);
});

test("node_completed without a terminal sample closes the operation with commit unknown, and the card sees it", () => {
  const win = loadHandler();
  const editor = fakeEditor(win);
  const handler = new win.SseHandler(editor);
  handler.dispatch("node_progress", JSON.stringify(SAMPLE));
  handler.dispatch("node_completed", JSON.stringify({ execution_id: "e1", node_id: "stage_trips", rows_out: 11000, duration_ms: 6000 }));
  const op = editor.nodeOps.get("stage_trips");
  assert.equal(op.terminal, true);
  assert.equal(op.committed, null);
  assert.equal(editor.cardOps.at(-1)[0], "stage_trips");
});

test("execution_aborted aborts every open operation and execution_started resets them", () => {
  const win = loadHandler();
  const editor = fakeEditor(win);
  const handler = new win.SseHandler(editor);
  handler.dispatch("node_progress", JSON.stringify(SAMPLE));
  handler.dispatch("execution_aborted", JSON.stringify({ execution_id: "e1", reason: "cancelled" }));
  assert.equal(editor.nodeOps.get("stage_trips").state, "aborted");
  assert.equal(editor.nodeOps.get("stage_trips").committed, false);
  handler.dispatch("execution_started", JSON.stringify({ execution_id: "e2", parameters: {} }));
  assert.equal(editor.nodeOps.get("stage_trips"), null);
});

test("an editor without the reducer (older page) ignores node_progress without throwing", () => {
  const win = loadHandler();
  const editor = fakeEditor(win);
  editor.nodeOps = null;
  editor.graph = null;
  const handler = new win.SseHandler(editor);
  assert.doesNotThrow(() => handler.dispatch("node_progress", JSON.stringify(SAMPLE)));
});
