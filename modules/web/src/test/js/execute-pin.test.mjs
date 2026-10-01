// #348 — the execute pin: a run ALWAYS POSTs the version the page is viewing, and a
// page whose version context is missing, malformed or unvalidatable refuses with a
// visible error and ZERO requests (workspace spec §3.4, A4). The counter is the positive
// control: the valid case proves the harness sees the real POST, so a zero in the refusal
// cases is evidence and not a broken probe.
//
// #348-b: every case here runs the ACTUAL shared workspace.js (its read path, its
// validation — bounded positive integer, body presence, pipeline identity) against
// execute.js and sse.js. No rule is mirrored into a stub: the module under test is the
// module the page loads.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const executePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/execute.js");
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");
const workspacePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/workspace.js");

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
 * Loads the real workspace.js against document stubs carrying the given blocks (the
 * module's own read path initializes PEWorkspace), then execute.js + sse.js fresh.
 * Returns { posts, editor, sseHandler } — posts carries { url, method, body } per call.
 */
function loadEditorPin(workspaceBlock, dataBlock) {
  const posts = [];
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: function () {},
    cookie: "dp_csrf=tok",
    getElementById: (id) => {
      if (id === "pipeline-workspace") return workspaceBlock === null ? null : { textContent: workspaceBlock };
      if (id === "pipeline-data") return dataBlock === null ? null : { textContent: dataBlock };
      return null;
    },
    body: { addEventListener: () => {}, removeEventListener: () => {} },
  };
  globalThis.fetch = (url, init) => {
    posts.push({ url, method: init && init.method, body: init && init.body });
    return Promise.resolve(hangingResponse());
  };

  delete require.cache[require.resolve(workspacePath)];
  delete require.cache[require.resolve(executePath)];
  delete require.cache[require.resolve(ssePath)];
  require(workspacePath); // its own read() publishes PEWorkspace / PEWorkspaceInvalid
  require(executePath);
  require(ssePath);

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

const DATA = JSON.stringify({ id: "p1", name: "p", nodes: [], parameters: {} });

test("a valid workspace pins the run to the VIEWED version - released included", async () => {
  const { posts, editor, sseHandler } = loadEditorPin(
    JSON.stringify({ pipelineId: "p1", viewedVersion: 2, hasBody: true, canExecute: true }),
    DATA,
  );

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

test("a draft pin is sent exactly as a released pin is", () => {
  const { posts, editor } = loadEditorPin(
    JSON.stringify({ pipelineId: "p1", viewedVersion: 3, hasBody: true, canExecute: true }),
    DATA,
  );

  globalThis.window.executePipeline(editor);

  assert.equal(JSON.parse(posts[0].body).version, 3);
});

test("a missing version context refuses visibly and sends NOTHING", () => {
  const { posts, editor } = loadEditorPin(null, DATA);

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0, "zero execute POSTs — the server never picks a body by accident");
  assert.equal(editor.isExecuting, false);
  assert.equal(editor.shownErrors.length, 1, "the refusal is visible");
  assert.match(editor.shownErrors[0], /version state/);
});

test("a malformed block refuses too", () => {
  const { posts, editor } = loadEditorPin('{"viewedVersion": 2, ', DATA);

  globalThis.window.executePipeline(editor);

  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(posts.length, 0);
  assert.equal(editor.shownErrors.length, 1);
});

test("a FRACTIONAL viewed version is refused - it names no row", () => {
  const { posts, editor } = loadEditorPin(
    JSON.stringify({ pipelineId: "p1", viewedVersion: 2.5, hasBody: true, canExecute: true }),
    DATA,
  );

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0);
  assert.equal(editor.shownErrors.length, 1);
});

test("an OVERFLOWING viewed version (1e400 -> Infinity) is refused - the wire would read null", () => {
  const { posts, editor } = loadEditorPin(
    '{"pipelineId":"p1","viewedVersion":1e400,"hasBody":true,"canExecute":true}',
    DATA,
  );

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0);
  assert.equal(editor.shownErrors.length, 1);
});

test("a choose-a-version page (hasBody false, viewedVersion null) refuses too", () => {
  const { posts, editor } = loadEditorPin(
    JSON.stringify({ pipelineId: "p1", viewedVersion: null, hasBody: false, canExecute: false }),
    DATA,
  );

  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0);
  assert.equal(editor.shownErrors.length, 1);
});

test("an IDENTITY mismatch between the block and the body blob refuses", () => {
  const other = JSON.stringify({ id: "other-pipeline", name: "p", nodes: [], parameters: {} });
  const { posts, editor } = loadEditorPin(
    JSON.stringify({ pipelineId: "p1", viewedVersion: 2, hasBody: true, canExecute: true }),
    other,
  );

  assert.equal(globalThis.window.PEWorkspaceInvalid, true, "the read path judged the identity");
  globalThis.window.executePipeline(editor);

  assert.equal(posts.length, 0);
  assert.equal(editor.shownErrors.length, 1);
});

test("the sse gate alone refuses a pinless connect - a versionless POST cannot fire", () => {
  const { posts, editor, sseHandler } = loadEditorPin(null, DATA);

  sseHandler.connect(null, "p1");

  assert.equal(posts.length, 0, "the second gate holds even without execute.js in front of it");
  assert.equal(editor.isExecuting, false);
  assert.equal(editor.shownErrors.length, 1);
});
