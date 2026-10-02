// 058 — the templates explorer's keyboard POLICY (static/js/template-explorer.js).
//
// The wiring (delegated listeners, roving tabindex, aria ownership) is DOM glue around a
// small set of DOM-free decisions, and those decisions are what this file pins — the same
// shape template-ref-text.test.mjs established: stub the globals the script touches at
// require time, then exercise what it exposes on window.
//
// The spec is the owner's words: "just like Windows file explorer" — up/down moves
// selection, right/left expands/collapses, Enter opens the editor.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
globalThis.document = {
  readyState: "complete",
  addEventListener: function () {},
  // 067: the script finds its pane by the `data-explorer-pane` MARKER (one file, two
  // explorers) rather than by a hard-coded id, so the stub answers querySelector. A stub
  // that lags the production lookup does not fail loudly here — `init()` runs at require
  // time and threw `document.querySelector is not a function` into node:test's output while
  // the assertions below still passed, which is exactly the shape that hides a real break.
  querySelector: function () { return null; },
  // #350: the engine finds EVERY context (`[data-explorer-pane]`, `[data-nav-tree]`) rather
  // than the first pane, so the stub answers querySelectorAll too.
  querySelectorAll: function () { return []; },
  getElementById: function () { return null; },
};
require(path.resolve(here, "../../main/resources/static/js/template-explorer.js"));
const explorer = globalThis.window.templateExplorer;

test("arrow movement clamps at the ends — the first and last row are stops, not wraps", () => {
  assert.equal(explorer.nextIndex(3, 0, -1), 0);
  assert.equal(explorer.nextIndex(3, 2, 1), 2);
  assert.equal(explorer.nextIndex(3, 1, 1), 2);
  assert.equal(explorer.nextIndex(3, 1, -1), 0);
  // Home is ArrowDown from before the first row; an empty pane has nowhere to go.
  assert.equal(explorer.nextIndex(3, -1, 1), 0);
  assert.equal(explorer.nextIndex(0, 0, 1), -1);
});

test("ArrowRight opens only a COLLAPSED folder — everything else moves down a row", () => {
  assert.equal(explorer.arrowRightOpens("folder", false), true);
  assert.equal(explorer.arrowRightOpens("folder", true), false);
  assert.equal(explorer.arrowRightOpens("leaf", false), false);
  assert.equal(explorer.arrowRightOpens("leaf", true), false);
  assert.equal(explorer.arrowRightOpens("result", false), false);
});

test("ArrowLeft closes only an EXPANDED folder — a leaf or child goes to its parent", () => {
  assert.equal(explorer.arrowLeftCloses("folder", true), true);
  assert.equal(explorer.arrowLeftCloses("folder", false), false);
  assert.equal(explorer.arrowLeftCloses("leaf", true), false);
  assert.equal(explorer.arrowLeftCloses("result", false), false);
});

test("only leaves and search results load the detail pane — folders have no detail", () => {
  assert.equal(explorer.loadsDetail("leaf"), true);
  assert.equal(explorer.loadsDetail("result"), true);
  assert.equal(explorer.loadsDetail("folder"), false);
  assert.equal(explorer.loadsDetail(null), false);
});

test("#350 — Enter and keyboard moves per CONTEXT: the page selects and opens the editor, the sidebar only follows its links", () => {
  // Page explorer: a keyboard move onto a leaf/result selects it (and loads its detail);
  // Enter opens the editor. A folder toggles in both contexts.
  assert.equal(explorer.selectionFollowsFocus("pane", "leaf"), true);
  assert.equal(explorer.selectionFollowsFocus("pane", "result"), true);
  assert.equal(explorer.selectionFollowsFocus("pane", "folder"), false);
  assert.equal(explorer.enterAction("pane", "leaf"), "open-editor");
  assert.equal(explorer.enterAction("pane", "result"), "open-editor");
  // Sidebar: a row is a LINK — moving onto it must never navigate (L3b's reason for leaving
  // the dashboards tree unwired), and Enter is the link's own activation.
  assert.equal(explorer.selectionFollowsFocus("nav", "leaf"), false);
  assert.equal(explorer.selectionFollowsFocus("nav", "result"), false);
  assert.equal(explorer.enterAction("nav", "leaf"), "follow");
  assert.equal(explorer.enterAction("nav", "result"), "follow");
  assert.equal(explorer.enterAction("nav", "folder"), "toggle");
  assert.equal(explorer.enterAction("pane", "folder"), "toggle");
  assert.equal(explorer.enterAction("nav", null), "none");
});

function freshDocument(contexts, docListeners) {
  return {
    readyState: "complete",
    addEventListener: (t) => docListeners.push(t),
    querySelector: () => null,
    querySelectorAll: (sel) => (sel === "[data-explorer-pane], [data-nav-tree]" ? contexts() : []),
    getElementById: () => null,
  };
}

function fakeContext(listeners) {
  return {
    addEventListener: (t) => listeners.push(t),
    querySelectorAll: () => [],
    hasAttribute: () => false,
  };
}

test("076 §B — repeated init wires each context once and never doubles document listeners", () => {
  const docListeners = [];
  const paneListeners = [];
  const pane = fakeContext(paneListeners);
  const prevDoc = globalThis.document;
  const prevWindow = globalThis.window;
  globalThis.window = {};
  globalThis.document = freshDocument(() => [pane], docListeners);
  const freshPath = path.resolve(here, "../../main/resources/static/js/template-explorer.js");
  delete require.cache[require.resolve(freshPath)];
  require(freshPath); // init() at load, as a fresh full page would

  const fresh = globalThis.window.templateExplorer;
  fresh.init(); // a redundant call (belt)
  fresh.init();

  assert.deepEqual(docListeners.slice().sort(), ["htmx:afterSwap", "toggle"],
    "document listeners installed exactly once across repeated init");
  assert.deepEqual(paneListeners.slice().sort(), ["click", "keydown"],
    "context listeners installed exactly once on the same context");

  globalThis.document = prevDoc;
  globalThis.window = prevWindow;
});

test("#350 — RE-EXECUTING the file (an explorer page's own tag on a boosted visit) re-inits the one module: no second document pair", () => {
  // The layout loads the engine once; templates/list.html still carries its own tag inside
  // #app-main, which htmx re-executes on every boosted visit. Before #350 each execution was a
  // fresh closure with its own `docWired = false`, so every visit stacked another
  // toggle/afterSwap pair on the document. The re-execution must now add NOTHING at the
  // document level and still wire the visit's NEW pane.
  const docListeners = [];
  const firstPane = [];
  const secondPane = [];
  let contexts = [fakeContext(firstPane)];
  const prevDoc = globalThis.document;
  const prevWindow = globalThis.window;
  globalThis.window = {};
  globalThis.document = freshDocument(() => contexts, docListeners);
  const freshPath = path.resolve(here, "../../main/resources/static/js/template-explorer.js");
  delete require.cache[require.resolve(freshPath)];
  require(freshPath); // the layout's footer tag

  contexts = [fakeContext(secondPane)]; // the boosted visit swapped in a fresh pane
  delete require.cache[require.resolve(freshPath)];
  require(freshPath); // the page's own tag, re-executed by htmx

  assert.deepEqual(docListeners.slice().sort(), ["htmx:afterSwap", "toggle"],
    "a re-executed file adds no document listener");
  assert.deepEqual(firstPane.slice().sort(), ["click", "keydown"]);
  assert.deepEqual(secondPane.slice().sort(), ["click", "keydown"],
    "the re-execution still wires the visit's new pane, once");

  globalThis.document = prevDoc;
  globalThis.window = prevWindow;
});
