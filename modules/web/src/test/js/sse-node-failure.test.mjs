// 057 — failure transparency on the editor's node_failed / pipeline_failed paths.
//
// The defect (T85): sse.js:194-207 handled `node_failed` by colouring the node and
// announcing "Node X failed"; it never read `payload.error`. Three demo executions
// failed with `datasource_connection_failed` / "FATAL: password authentication
// failed" and the screen showed a red node and a banner reading "Pipeline failed."
// The owner opened the database to learn why.
//
// These run on Node's built-in runner (`node --test`, the 027b harness) and follow
// sse-parser.test.mjs's loader: sse.js is a browser IIFE publishing on `window`, so
// each test shims `globalThis.window` and requires the file. What is asserted is the
// INSPECTOR data path — the editor state and view-model Alpine renders from —
// because the DOM itself is Alpine templates in editor.html, which node --test does
// not execute. The contract pinned here:
//
//   1. `node_failed` stores `payload.error` on `editor.nodeErrors[node_id]`, so the
//      inspector's Failure section (editor.html) can render it for the failed node.
//   2. `pipeline_failed` hands the full payload to the result panel's failure mode,
//      so the execution-level failure detail is on screen, not only a modal string.
//   3. PEErrorDetails.build(error) produces the inspector's view-model: code,
//      message, correlation id, and the exception chain rendered ROOT-CAUSE-FIRST
//      (the wire's caused_by is outermost-first; humans read the root cause first).
//
// Red on the pre-057 sse.js: nodeErrors is never populated there — the error object
// is dropped on the floor, which is exactly the reported defect.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");
const resultPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/result.js");
const detailsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/details.js");

function loadSseHandler() {
  globalThis.window = {};
  delete require.cache[require.resolve(ssePath)];
  require(ssePath);
  return globalThis.window.SseHandler;
}

function loadResultPanel() {
  globalThis.window = {};
  delete require.cache[require.resolve(resultPath)];
  require(resultPath);
  return globalThis.window.ResultPanel;
}

function loadErrorDetails() {
  globalThis.window = {};
  delete require.cache[require.resolve(detailsPath)];
  require(detailsPath);
  return globalThis.window.PEErrorDetails;
}

function fakeEditor() {
  return {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    graph: null,
    setBanner() {},
    showError() {},
    announceStatus() {},
    handleDataReady: null,
    handlePipelineFailed: null,
  };
}

// The owner's exact failure (T85), as the wire carries it after 057: the error
// object inside node_failed / pipeline_failed, with the driver's cause chain.
const CONNECTION_FAILURE = {
  execution_id: "e1",
  node_id: "stage_daily_trips",
  correlation_id: "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21",
  error: {
    code: "pipeline.node.datasource_connection_failed",
    message: 'Failed to initialize pool: FATAL: password authentication failed for user "dp_demo_ro"',
    details: { phase: "connect", node_id: "stage_daily_trips" },
    correlation_id: "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21",
    node: {
      id: "stage_daily_trips",
      type: "DQL",
      datasource: "sample-trips",
      dialect: "POSTGRES",
      template: "sample_trips_daily.sql",
      template_version: 1,
    },
    sql: "SELECT * FROM trips WHERE borough = :borough",
    exception: {
      class: "co.datapipelines.datasources.pooling.PoolInitException",
      message: "Failed to initialize pool",
      frames: ["co.datapipelines.datasources.pooling.PoolInitException.<init>(PoolInitException.kt:12)"],
      caused_by: [
        {
          class: "org.postgresql.util.PSQLException",
          message: 'FATAL: password authentication failed for user "dp_demo_ro"',
          frames: ["org.postgresql.util.PSQLException.parseServerError(PSQLException.java:285)"],
          caused_by: [],
        },
      ],
    },
  },
};

test("node_failed stores payload.error on editor.nodeErrors — the inspector's data source", () => {
  const SseHandler = loadSseHandler();
  const editor = fakeEditor();
  const handler = new SseHandler(editor);
  handler.dispatch("node_failed", JSON.stringify(CONNECTION_FAILURE));
  assert.ok(editor.nodeErrors.stage_daily_trips, "payload.error was dropped — the inspector has nothing to render");
  assert.equal(editor.nodeErrors.stage_daily_trips.code, "pipeline.node.datasource_connection_failed");
  assert.equal(
    editor.nodeErrors.stage_daily_trips.message,
    'Failed to initialize pool: FATAL: password authentication failed for user "dp_demo_ro"',
  );
});

test("node_failed with no error object does not throw — pre-057 payloads stay legal", () => {
  const SseHandler = loadSseHandler();
  const editor = fakeEditor();
  const handler = new SseHandler(editor);
  handler.dispatch("node_failed", JSON.stringify({ execution_id: "e1", node_id: "n1" }));
  assert.equal(editor.nodeErrors.n1, undefined);
});

test("pipeline_failed hands the full payload to the result panel's failure mode", () => {
  const SseHandler = loadSseHandler();
  const editor = fakeEditor();
  const handler = new SseHandler(editor);
  let failurePayload = null;
  editor.handlePipelineFailed = (payload) => {
    failurePayload = payload;
  };
  handler.dispatch("pipeline_failed", JSON.stringify(CONNECTION_FAILURE));
  assert.ok(failurePayload, "pipeline_failed never delivered the payload — the result panel cannot show the error");
  assert.equal(failurePayload.error.code, "pipeline.node.datasource_connection_failed");
  assert.equal(failurePayload.error.exception.caused_by[0].class, "org.postgresql.util.PSQLException");
});

test("the failure view-model renders code, message, correlation id and the root cause FIRST", () => {
  const PEErrorDetails = loadErrorDetails();
  assert.ok(PEErrorDetails, "PEErrorDetails is not published — the inspector has no failure renderer");
  const view = PEErrorDetails.build(CONNECTION_FAILURE.error);
  assert.equal(view.code, "pipeline.node.datasource_connection_failed");
  assert.equal(view.message, 'Failed to initialize pool: FATAL: password authentication failed for user "dp_demo_ro"');
  assert.equal(view.correlationId, "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21");
  assert.equal(view.rootCause, 'org.postgresql.util.PSQLException: FATAL: password authentication failed for user "dp_demo_ro"');
  // The chain renders root-cause-FIRST: the wire's caused_by[last] is the root, so
  // the display chain's FIRST entry is the PSQLException, the pool error LAST.
  assert.equal(view.chain[0].cls, "org.postgresql.util.PSQLException");
  assert.equal(view.chain[view.chain.length - 1].cls, "co.datapipelines.datasources.pooling.PoolInitException");
  assert.ok(view.copyText.includes("pipeline.node.datasource_connection_failed"));
  assert.ok(view.copyText.includes("org.postgresql.util.PSQLException"));
});

test("the view-model renders what it has under error-detail=structured — no apology, no empty panel", () => {
  const PEErrorDetails = loadErrorDetails();
  const structured = {
    code: "pipeline.node.datasource_connection_failed",
    message: 'Failed to initialize pool: FATAL: password authentication failed for user "dp_demo_ro"',
    details: { phase: "connect", node_id: "stage_daily_trips" },
    correlation_id: "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21",
    node: CONNECTION_FAILURE.error.node,
  };
  const view = PEErrorDetails.build(structured);
  assert.equal(view.code, "pipeline.node.datasource_connection_failed");
  assert.equal(view.sql, null);
  assert.equal(view.chain, null);
  assert.equal(view.rootCause, null);
  // "details unavailable" is what the round forbids: absent sections stay absent.
  assert.ok(!/unavailable/i.test(view.copyText));
});

test("the result panel opens in failure mode with the same view-model", () => {
  const ResultPanel = loadResultPanel();
  const editor = { resultPanel: {} };
  const panel = new ResultPanel(editor);
  panel.showFailure(CONNECTION_FAILURE);
  assert.equal(panel.visible, true);
  assert.equal(panel.failure.code, "pipeline.node.datasource_connection_failed");
  assert.equal(editor.resultPanel.failure.code, "pipeline.node.datasource_connection_failed");
});
