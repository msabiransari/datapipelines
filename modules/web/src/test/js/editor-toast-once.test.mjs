// 080 §B — THE exactly-once toast. The owner's report: "many success toasts on
// completion". The cause (found in init.js's 076 boost lifecycle): a history
// restore brings the editor's DOM back with the PREVIOUS component's Alpine state
// (`_x_dataStack`) and its @click listeners still attached; the afterSettle
// rescue then ran a bare `Alpine.initTree(root)`, and Alpine's x-data re-init
// guard (`data-has-alpine-state`) is only ever set by Alpine.clone — never by a
// history restore — so initTree STACKED a second component on the same root.
// Every @click registered again: one Execute click fired executePipeline() once
// per stacked component, N executions streamed N terminal events, and
// sse.js's (correct, single) pipeline_completed toast fired N times. Another
// restore stacked a third.
//
// The fix is at the source: destroy the stale tree BEFORE re-binding. This test
// is the falsifier the brief asks for — simulate two lifecycle passes and one
// stream, and assert exactly one toast. Pre-fix, the ops log reads
// ["initTree", "initTree"] (two stacked components) and the destroyTree
// assertion goes red.
//
// Same harness as editor-teardown.test.mjs: node --test, globals stubbed at
// require time, hand-rolled doubles.

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
  const bodyListeners = {};
  const spies = { ops: [] };

  const main = {
    id: "app-main",
    querySelector: (sel) => (sel === ".pe-root" ? spies.peRoot : null),
  };
  const doc = {
    readyState: "complete",
    addEventListener: (t, fn) => (docListeners[t] ||= []).push(fn),
    removeEventListener: () => {},
    body: {
      addEventListener: (t, fn) => (bodyListeners[t] ||= []).push(fn),
      removeEventListener: () => {},
    },
    getElementById: (id) => {
      if (id === "app-main") return main;
      if (id === "pipeline-data") {
        return { textContent: JSON.stringify({ id: "p1", name: "demo", nodes: [] }) };
      }
      return null;
    },
    querySelectorAll: () => [],
  };

  globalThis.window = {};
  globalThis.document = doc;
  globalThis.window.PEDock = { createDock: () => ({}) };
  globalThis.window.PEEvents = { createEventsLog: () => ({}) };
  globalThis.ResultPanel = class {
    constructor() { this.cursorEndpoint = null; }
  };
  globalThis.SseHandler = class {
    constructor() { this.abortController = { abort: () => {} }; }
  };
  globalThis.PipelineGraph = class {
    constructor() {
      this.cy = { destroy: () => {}, on: () => {}, elements: () => ({ unselect: () => {} }), getElementById: () => ({ length: 0 }) };
    }
    render() {}
  };
  globalThis.setupA11y = () => {};
  globalThis.a11ySyncNode = () => {};
  globalThis.executePipeline = () => {};
  globalThis.announceStatus = () => {};

  delete require.cache[require.resolve(initPath)];
  require(initPath);
  return { spies, docListeners, bodyListeners };
}

function fire(listeners, type, event) {
  (listeners[type] || []).forEach((fn) => fn(event));
}

test("two lifecycle passes and one stream produce EXACTLY ONE toast", () => {
  const { spies, docListeners } = loadEditor();
  const component = globalThis.window.pipelineEditor();
  component.init();

  // The Alpine double: initTree models a bind by leaving _x_dataStack on the
  // root (what real Alpine does), destroyTree models its removal.
  globalThis.window.Alpine = {
    initTree(root) {
      spies.ops.push("initTree");
      root._x_dataStack = [{}];
    },
    destroyTree(root) {
      spies.ops.push("destroyTree");
      delete root._x_dataStack;
    },
  };

  // Pass 1: navigate away (boosted) and back (history restore, scripts not
  // re-executed) — the rescue binds the restored root.
  fire(docListeners, "htmx:beforeSwap", { detail: { boosted: true } });
  spies.peRoot = {};
  fire(docListeners, "htmx:afterSettle", { detail: {} });
  assert.equal(globalThis.window.__peInstance, null, "the rescue itself does not fake a live instance");

  // Pass 2: away and back AGAIN. The restored root still carries pass 1's
  // _x_dataStack — the stale tree must be destroyed BEFORE the re-bind, or the
  // two components stack and one Execute click fires twice.
  fire(docListeners, "htmx:beforeSwap", { detail: { boosted: true } });
  fire(docListeners, "htmx:afterSettle", { detail: {} });

  assert.deepEqual(
    spies.ops,
    ["initTree", "destroyTree", "initTree"],
    "each re-bind first destroys the stale tree — pre-fix this reads [initTree, initTree] and components stack",
  );

  // …and with exactly ONE live component, one stream's terminal event is ONE
  // toast. (Pre-fix, N stacked components each handled the same click: N
  // streams, N terminal events, N toasts.)
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

test("a re-bind with NO stale tree binds directly — the first restore is not destroyed-then-bound", () => {
  const { spies, docListeners } = loadEditor();
  globalThis.window.Alpine = {
    initTree() { spies.ops.push("initTree"); },
    destroyTree() { spies.ops.push("destroyTree"); },
  };
  spies.peRoot = {}; // no _x_dataStack: nothing was ever bound here
  fire(docListeners, "htmx:afterSettle", { detail: {} });
  assert.deepEqual(spies.ops, ["initTree"], "destroyTree only runs when a stale tree exists");
});
