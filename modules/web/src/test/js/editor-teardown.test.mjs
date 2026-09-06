// 076 §B — the pipeline editor's teardown under hx-boost
// (static/js/pipeline-editor/init.js).
//
// Boost swaps only #app-main, so the editor's Alpine component must be torn
// down when a swap replaces its host region and re-armed when one brings the
// editor back. The one real bug boost can introduce is an execution stream
// living past the section change — teardown ABORTS the stream's reader (never
// cancel(): the run continues server-side) and destroys the Cytoscape
// instance. Same harness as the house JS tests: node --test, globals stubbed
// at require time, hand-rolled doubles.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const initPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/init.js");

/**
 * Load init.js fresh with the browser surfaces it touches at module scope:
 * window (publish target + boost-wired flag), document (listener install),
 * and the bare-global factories init() resolves (ResultPanel, SseHandler,
 * PipelineGraph, setupA11y). Returns the captured listeners plus spies.
 */
function loadEditor() {
  const docListeners = {};
  const bodyListeners = {};
  const removed = [];
  const spies = { abort: 0, destroy: 0, initTree: [] };

  const main = {
    id: "app-main",
    querySelector: (sel) => (sel === ".pe-root" ? spies.peRoot : null),
  };
  const doc = {
    readyState: "complete",
    addEventListener: (t, fn) => (docListeners[t] ||= []).push(fn),
    removeEventListener: (t, fn) => removed.push(t),
    body: {
      addEventListener: (t, fn) => (bodyListeners[t] ||= []).push(fn),
      removeEventListener: (t, fn) => removed.push("body:" + t),
    },
    getElementById: (id) => {
      if (id === "app-main") return main;
      if (id === "pipeline-data") {
        return { textContent: JSON.stringify({ id: "p1", name: "demo", nodes: [] }) };
      }
      return null;
    },
    querySelector: () => null,
  };

  globalThis.window = {};
  globalThis.document = doc;
  globalThis.PEDockShim = true;
  globalThis.window.PEDock = { createDock: () => ({}) };
  globalThis.window.PEEvents = { createEventsLog: () => ({}) };
  globalThis.ResultPanel = class {
    constructor() {
      this.cursorEndpoint = null;
    }
  };
  globalThis.SseHandler = class {
    constructor() {
      this.abortController = { abort: () => spies.abort++ };
    }
  };
  globalThis.PipelineGraph = class {
    constructor() {
      this.cy = {
        destroy: () => spies.destroy++,
        on: () => {},
        elements: () => ({ unselect: () => {} }),
        getElementById: () => ({ length: 0, select: () => {} }),
      };
    }
    render() {}
  };
  globalThis.setupA11y = () => {};
  globalThis.a11ySyncNode = () => {};
  globalThis.executePipeline = () => {};
  globalThis.announceStatus = () => {};

  delete require.cache[require.resolve(initPath)];
  require(initPath);

  return { spies, docListeners, bodyListeners, removed, main };
}

function fire(listeners, type, event) {
  (listeners[type] || []).forEach((fn) => fn(event));
}

test("teardown aborts the stream reader, destroys cytoscape, clears timers and listeners", () => {
  const { spies, removed } = loadEditor();
  const component = globalThis.window.pipelineEditor();
  component.init();
  component.resultPanel.ttlInterval = setInterval(() => {}, 60_000);
  component.sqlReloadTimer = setTimeout(() => {}, 60_000);

  component.teardown();

  assert.equal(spies.abort, 1, "the SSE reader is aborted — never cancel()");
  assert.equal(spies.destroy, 1, "cytoscape is destroyed");
  assert.equal(component.cy, null);
  assert.equal(component.graph, null);
  assert.equal(component.resultPanel.ttlInterval, null, "TTL countdown cleared");
  assert.equal(component.sqlReloadTimer, null, "SQL reload debounce cleared");
  assert.ok(removed.includes("click"), "the delegated copy listener comes off");
  assert.ok(removed.includes("body:htmx:afterSwap"), "the SQL-highlight listener comes off");
});

test("a BOOSTED beforeSwap tears the live component down and clears the handles", () => {
  const { spies, docListeners } = loadEditor();
  const component = globalThis.window.pipelineEditor();
  component.init();
  globalThis.window.PEDraft = { version: 4, bodyHash: "h" };

  fire(docListeners, "htmx:beforeSwap", { detail: { boosted: true } });

  assert.equal(spies.abort + spies.destroy, 2, "teardown ran");
  assert.equal(globalThis.window.__peInstance, null);
  assert.equal(globalThis.window.PEDraft, null);
});

test("a PARTIAL swap inside the editor never tears the component down", () => {
  const { spies, docListeners } = loadEditor();
  const component = globalThis.window.pipelineEditor();
  component.init();

  // A node-SQL load targets an inner node, not #app-main, and is not boosted.
  fire(docListeners, "htmx:beforeSwap", {
    detail: { boosted: false, target: { id: "pe-node-sql", contains: () => false } },
  });

  assert.equal(spies.abort, 0);
  assert.equal(spies.destroy, 0);
  assert.equal(globalThis.window.__peInstance, component, "the component survives partials");
});

test("a swap whose target IS #app-main (history restore) tears down too", () => {
  const { spies, docListeners, main } = loadEditor();
  const component = globalThis.window.pipelineEditor();
  component.init();

  fire(docListeners, "htmx:beforeSwap", { detail: { boosted: false, target: main } });

  assert.equal(spies.destroy, 1);
  assert.equal(globalThis.window.__peInstance, null);
});

test("afterSettle re-binds the editor root through Alpine when no component is live", () => {
  const { spies, docListeners } = loadEditor();
  spies.peRoot = { id: "pe-root-fake" };
  globalThis.window.Alpine = { initTree: (el) => spies.initTree.push(el) };

  // No component was ever initialised (the history-restore shape: cached DOM,
  // scripts not re-executed) — the rescue binds one.
  fire(docListeners, "htmx:afterSettle", { detail: {} });
  assert.deepEqual(spies.initTree, [spies.peRoot]);

  // With a live component the rescue is a no-op (fresh boosted visit).
  const component = globalThis.window.pipelineEditor();
  component.init();
  fire(docListeners, "htmx:afterSettle", { detail: {} });
  assert.equal(spies.initTree.length, 1, "no double bind when a component is live");
});
