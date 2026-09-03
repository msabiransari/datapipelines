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
