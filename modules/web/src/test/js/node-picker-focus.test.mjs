// 082 §B — the stray node list, pinned at its cause.
//
// `#pe-node-list` is the keyboard node picker: visually clipped, revealed by
// `:focus-within` (031). It floated over the legend on EVERY pointer click
// (080's dark Details shot) because the canvas tap ran `a11ySyncNode(id)` with
// the default `moveFocus`, which calls `.focus()` on the selected row — DOM
// focus lands in the list, `:focus-within` matches, the picker paints.
//
// So the invariant is about the ARGUMENT, not about CSS: every pointer route
// selects with `moveFocus === false`. Same harness as the other editor tests —
// node --test, globals stubbed at require time, hand-rolled doubles.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const initPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/init.js");

/**
 * Load init.js with the surfaces it touches, capturing the Cytoscape `tap`
 * handlers it registers and every `a11ySyncNode` call it makes.
 */
function loadEditor() {
  const syncCalls = [];
  const cyHandlers = { node: [], background: [] };

  const cy = {
    on: (type, selectorOrFn, maybeFn) => {
      if (type !== "tap") return;
      if (typeof selectorOrFn === "function") cyHandlers.background.push(selectorOrFn);
      else if (selectorOrFn === "node") cyHandlers.node.push(maybeFn);
    },
    destroy: () => {},
    elements: () => ({ unselect: () => {} }),
    getElementById: () => ({ length: 0, select: () => {} }),
  };

  const doc = {
    readyState: "complete",
    addEventListener: () => {},
    removeEventListener: () => {},
    body: { addEventListener: () => {}, removeEventListener: () => {} },
    getElementById: (id) => {
      if (id === "pipeline-data") {
        return {
          textContent: JSON.stringify({
            id: "p1",
            name: "demo",
            nodes: [{ id: "a" }, { id: "b" }],
          }),
        };
      }
      return null;
    },
    querySelector: () => null,
  };

  globalThis.window = {};
  globalThis.document = doc;
  globalThis.window.PEDock = { createDock: () => ({ selectNode: () => {}, clearSelection: () => {} }) };
  globalThis.window.PEEvents = { createEventsLog: () => ({}) };
  globalThis.ResultPanel = class {};
  globalThis.SseHandler = class {
    constructor() {
      this.abortController = { abort: () => {} };
    }
  };
  globalThis.PipelineGraph = class {
    constructor() {
      this.cy = cy;
    }
    render() {}
  };
  globalThis.setupA11y = () => {};
  globalThis.a11ySyncNode = (id, moveFocus) => syncCalls.push([id, moveFocus]);
  globalThis.executePipeline = () => {};
  globalThis.announceStatus = () => {};

  delete require.cache[require.resolve(initPath)];
  require(initPath);

  const component = globalThis.window.pipelineEditor();
  component.init();
  // The SQL fetch is not what is under test and needs neither htmx nor a server.
  component.loadNodeSql = () => {};

  return { component, syncCalls, cyHandlers };
}

function tapNode(cyHandlers, id) {
  cyHandlers.node.forEach((fn) => fn({ target: { data: () => ({ id }) } }));
}

test("a pointer tap on a card selects WITHOUT moving focus into the node list", () => {
  const { syncCalls, cyHandlers } = loadEditor();
  assert.equal(cyHandlers.node.length, 1, "the canvas tap handler is registered");

  tapNode(cyHandlers, "a");

  assert.deepEqual(
    syncCalls,
    [["a", false]],
    "moveFocus=false — a mouse click must not focus a row and reveal the picker",
  );
});

test("the card's expand affordance opens Details without moving focus either", () => {
  const { component, syncCalls } = loadEditor();

  component.openNodeDetails("b");

  assert.deepEqual(syncCalls, [["b", false]]);
});

// The keyboard route is unchanged: a click on a VISIBLE row (the picker is only
// visible once focus is already inside it) still carries focus with the selection,
// which is the roving-tabindex behaviour a listbox owes its user.
test("a bare selectNodeById still lets a11ySyncNode take its default", () => {
  const { component, syncCalls } = loadEditor();

  component.selectNodeById("a");

  assert.deepEqual(syncCalls, [["a", undefined]], "no argument — a11ySyncNode's own default applies");
});
