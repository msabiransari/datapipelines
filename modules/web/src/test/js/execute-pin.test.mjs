// #348 — the execute pin: a run ALWAYS POSTs the version the page is viewing, and a
// page whose version context is missing or malformed refuses with a visible error and
// ZERO requests (workspace spec §3.4, A4). The counter is the positive control: the
// valid case proves the harness sees the real POST, so a zero in the refusal cases is
// evidence and not a broken probe.
//
// Both gates are exercised: execute.js refuses BEFORE connecting, and sse.js — the
// second gate — refuses a connect() that somehow arrives without a pin. Neither falls
// back to the server's execute-default, which can be a different body than the one the
// person is looking at.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const executePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/execute.js");
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");

/** A hanging stream: connect() resolves its fetch, then the reader never yields. */
function hangingResponse() {
  return {
    ok: true,
    body: {
      getReader: () => ({ read: () => new Promise(() => {}) }),
    },
  };
}

/**
 * Load execute.js + sse.js fresh against a recording fetch and an editor stub.
 * Returns { posts, editor, sseHandler } — posts carries { url, method, body } per call.
 */
function loadEditorPin(workspace) {
  const posts = [];
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: function () {},
    cookie: "dp_csrf=tok",
    getElementById: () => null,
    body: { addEventListener: () => {}, removeEventListener: () => {} },
  };
  globalThis.fetch = (url, init) => {
    posts.push({ url, method: init && init.method, body: init && init.body });
    return Promise.resolve(hangingResponse());
  };
  globalThis.window.PEWorkspace = workspace;
  globalThis.window.PEWorkspaceInvalid = workspace === undefined;
  globalThis.window.PEWorkspaceLogic = undefined;
  globalThis.window.collectParameters = () => ({});

  delete require.cache[require.resolve(executePath)];
  delete require.cache[require.resolve(ssePath)];
  require(executePath);
  require(ssePath);
  globalThis.window.PEWorkspaceLogic = { executeVersion: windowWorkspaceRule() };

  const editor = {
    pipeline: { id: "p1", name: "p" },
    isExecuting: false,
    parameterOverrides: {},
    // execute.js re-owns collectParameters when it loads; the panel's keys are empty here.
    paramKeys: [],
    banner: { text: "", type: "info" },
    shownErrors: [],
    showError(message) {
      this.shownErrors.push(message);
    },
    setBanner() {},
    nodeStates: {},
    nodeValues: {},
    childExecutions: {},
    nodeErrors: {},
    handleExecutionStarted() {},
    graph: { resetAll() {} },
    announceStatus() {},
    runStatus: {},
  };
  editor.sseHandler = new globalThis.window.SseHandler(editor);

  return { posts, editor, sseHandler: editor.sseHandler };
}

/* The pure rule under test, mirrored from workspace.js — the tests above own its table;
   this file only needs the same contract to arm the double gate. */
function windowWorkspaceRule() {
  return (state) => (state && typeof state.viewedVersion === "number" && state.viewedVersion > 0 ? state.viewedVersion : null);
}

test("a valid workspace pins the run to the VIEWED version - released included", async () => {
  const { posts, editor, sseHandler } = loadEditorPin({ pipelineId: "p1", viewedVersion: 2, hasBody: true, canExecute: true });

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 1, "exactly one execute POST (the positive control)");
  assert.equal(posts[0].method, "POST");
  assert.match(posts[0].url, /\/api\/v1\/pipelines\/p1\/execute$/);
  const body = JSON.parse(posts[0].body);
  assert.equal(body.version, 2, "the viewed version travels on the wire — v2 is a RELEASE, not a draft");
  assert.equal(editor.isExecuting, true);

  // The second gate, entered directly: a reconnect with the same pin sends it again.
  await sseHandler.connect(null, "p1");
  assert.equal(posts.length, 2);
  assert.equal(JSON.parse(posts[1].body).version, 2);
});

test("a missing version context refuses visibly and sends NOTHING", () => {
  const { posts, editor } = loadEditorPin(undefined);

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0, "zero execute POSTs — the server never picks a body by accident");
  assert.equal(editor.isExecuting, false);
  assert.equal(editor.shownErrors.length, 1, "the refusal is visible");
  assert.match(editor.shownErrors[0], /version state/);
});

test("a choose-a-version page (no viewed body) refuses too", () => {
  const { posts, editor } = loadEditorPin({ pipelineId: "p1", viewedVersion: null, hasBody: false, canExecute: false });

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0);
  assert.equal(editor.shownErrors.length, 1);
});

test("the sse gate alone refuses a pinless connect - a versionless POST cannot fire", () => {
  const { posts, editor, sseHandler } = loadEditorPin(undefined);

  sseHandler.connect(null, "p1");

  assert.equal(posts.length, 0, "the second gate holds even without execute.js in front of it");
  assert.equal(editor.isExecuting, false);
  assert.equal(editor.shownErrors.length, 1);
});
