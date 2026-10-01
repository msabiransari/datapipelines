// 080 §B — THE exactly-once toast, #358 revision. The owner's report: "many success
// toasts on completion". The 080 cause was the afterSettle rescue stacking a second
// Alpine component on a restored root (one Execute click fired once per stacked
// component, N streams, N toasts). The #358 fix moved restore activation to the
// runtime (pipeline-editor/runtime.js — x-ignore + ONE mutateDom init), and the
// rescue that competed with replayed Alpine boots is GONE from init.js.
//
// What stays testable here at the module level:
//   1. init.js's boost wiring stays SINGULAR even when the file executes twice
//      (the pre-#358 world replayed it on every restore — the guard is what kept
//      the document-level pair singular, and it must keep holding);
//   2. init.js wires NO afterSettle initializer (the ownership contract — the
//      runtime is the only thing that may bind a restored root);
//   3. one stream's terminal event is ONE toast (sse.js's side of the contract,
//      unchanged).
// The destroy-before-bind ordering of the runtime's activation is owned by
// runtime-activation.test.mjs; the browser suite proves the counts on the real app.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const initPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/init.js");
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");

function loadEditor() {
  const docListeners = {};
  const doc = {
    readyState: "complete",
    addEventListener: (t, fn) => (docListeners[t] ||= []).push(fn),
    removeEventListener: () => {},
    body: { addEventListener: () => {}, removeEventListener: () => {} },
    getElementById: (id) => {
      if (id === "pipeline-data") {
        return { textContent: JSON.stringify({ id: "p1", name: "demo", nodes: [] }) };
      }
      return null;
    },
    querySelector: () => null,
    querySelectorAll: () => [],
  };

  globalThis.window = {};
  globalThis.document = doc;
  globalThis.window.PEDock = { createDock: () => ({}) };
  globalThis.window.PEEvents = { createEventsLog: () => ({}) };
  globalThis.ResultPanel = class {};
  globalThis.SseHandler = class {};
  globalThis.PipelineGraph = class {
    constructor() {
      this.cy = { destroy: () => {}, on: () => {} };
    }
    render() {}
  };
  globalThis.setupA11y = () => {};
  globalThis.a11ySyncNode = () => {};
  globalThis.executePipeline = () => {};
  globalThis.announceStatus = () => {};

  delete require.cache[require.resolve(initPath)];
  require(initPath);
  return { docListeners };
}

test("the boost lifecycle wiring stays singular across a second execution of init.js", () => {
  const first = loadEditor();
  assert.equal((first.docListeners["htmx:beforeSwap"] || []).length, 1);
  assert.equal(
    (first.docListeners["htmx:afterSettle"] || []).length,
    0,
    "no afterSettle initializer — the runtime owns restore activation",
  );

  // A replayed/re-required init.js (what a script replay used to do per restore)
  // must not stack a second document-level pair.
  delete require.cache[require.resolve(initPath)];
  require(initPath);
  assert.equal(
    (first.docListeners["htmx:beforeSwap"] || []).length,
    1,
    "the __peBoostWired guard keeps the pair singular",
  );
});

test("one stream's terminal event is ONE toast", () => {
  loadEditor();
  delete require.cache[require.resolve(ssePath)];
  require(ssePath);
  const RealSseHandler = globalThis.window.SseHandler;
  const toasts = [];
  globalThis.window.DpToast = { show: (variant, title) => toasts.push([variant, title]) };
  const editor = {
    isExecuting: true,
    nodeStates: {},
    pipeline: { name: "demo" },
    setBanner() {},
    announceStatus() {},
    stopRunClock() {},
  };
  const handler = new RealSseHandler(editor);
  handler.dispatch("pipeline_completed", JSON.stringify({ execution_id: "e1" }));
  assert.equal(toasts.length, 1, "one stream, one terminal event, ONE toast");
  assert.deepEqual(toasts[0], ["success", "Pipeline completed"]);
});
