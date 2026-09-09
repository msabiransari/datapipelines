// 104 §A — the splitter's PURE core: the clamp, the keyboard steps, the reset and the
// stored-value guard. One module, two panes (the editor's dock by height, the explorers'
// tree by width), so the arithmetic is written once and tested once — the same harness
// decision dock.js's state table got (`node --test`, no DOM, no jsdom).
//
// What is deliberately NOT here: `bind()`, the DOM adapter. Pointer capture, focus and
// localStorage are browser facts, and the browser suites
// (`PipelineEditorDockResizeBrowserTest`, `ExplorerPaneGeometryBrowserTest`) own them with
// real pixel measurements. What IS here is every decision those adapters delegate.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const splitterPath = path.resolve(here, "../../main/resources/static/js/splitter.js");

function load() {
  delete require.cache[require.resolve(splitterPath)];
  return require(splitterPath);
}

const S = load();

/* ------------------------------------------------------------------ sizeFor */

test("x axis: the size follows the pointer's dx from where the drag started", () => {
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: 400, dx: 200, dy: 0 }), 600);
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: 400, dx: -60, dy: 0 }), 340);
});

test("x axis ignores dy, y axis ignores dx — a diagonal drag moves one pane on one axis", () => {
  assert.equal(S.sizeFor({ axis: "x", min: 100, max: 900, start: 400, dx: 50, dy: 300 }), 450);
  assert.equal(S.sizeFor({ axis: "y", min: 100, max: 900, start: 400, dx: 300, dy: 50 }), 450);
});

test("invert: a handle on the pane's TOP edge grows the pane when the pointer goes UP", () => {
  // The dock's handle sits on its top edge: dy = -150 (pointer moved up) is +150 of height.
  assert.equal(S.sizeFor({ axis: "y", min: 120, max: 600, start: 232, dx: 0, dy: -150, invert: true }), 382);
  assert.equal(S.sizeFor({ axis: "y", min: 120, max: 600, start: 232, dx: 0, dy: 80, invert: true }), 152);
});

test("the floor and the ceiling hold however far the pointer travels", () => {
  assert.equal(S.sizeFor({ axis: "y", min: 120, max: 600, start: 232, dx: 0, dy: -5000, invert: true }), 600);
  assert.equal(S.sizeFor({ axis: "y", min: 120, max: 600, start: 232, dx: 0, dy: 5000, invert: true }), 120);
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: 400, dx: 9999, dy: 0 }), 800);
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: 400, dx: -9999, dy: 0 }), 260);
});

test("a ceiling BELOW the floor answers the floor — a short window keeps the pane usable", () => {
  // max = stageHeight - 160 goes negative on a very short viewport. The floor is the
  // contract ("the pane is at least this tall"); a naive clamp would answer the ceiling
  // and collapse the pane to a negative height.
  assert.equal(S.sizeFor({ axis: "y", min: 120, max: 40, start: 232, dx: 0, dy: -300, invert: true }), 120);
  assert.equal(S.sizeFor({ axis: "y", min: 120, max: 40, start: 232, dx: 0, dy: 300, invert: true }), 120);
});

test("a non-finite delta or start is inert — the size does not become NaN", () => {
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: 400, dx: NaN, dy: 0 }), 400);
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: NaN, dx: 20, dy: 0 }), 260);
});

test("the result is a whole number of pixels — a fractional size blurs the border it draws", () => {
  assert.equal(S.sizeFor({ axis: "x", min: 260, max: 800, start: 400.4, dx: 10.2, dy: 0 }), 411);
});

/* ------------------------------------------------------------------ stepFor */

test("arrow keys step 16px along the axis, Shift steps 64px", () => {
  const base = { axis: "x", min: 260, max: 800, current: 400 };
  assert.equal(S.stepFor({ ...base, key: "ArrowRight" }), 416);
  assert.equal(S.stepFor({ ...base, key: "ArrowLeft" }), 384);
  assert.equal(S.stepFor({ ...base, key: "ArrowRight", shiftKey: true }), 464);
  assert.equal(S.stepFor({ ...base, key: "ArrowLeft", shiftKey: true }), 336);
});

test("the y axis steps on Up/Down, and an inverted handle grows on ArrowUp", () => {
  const base = { axis: "y", min: 120, max: 600, current: 232, invert: true };
  assert.equal(S.stepFor({ ...base, key: "ArrowUp" }), 248);
  assert.equal(S.stepFor({ ...base, key: "ArrowDown" }), 216);
  assert.equal(S.stepFor({ ...base, key: "ArrowUp", shiftKey: true }), 296);
});

test("Home is the floor, End is the ceiling", () => {
  const base = { axis: "x", min: 260, max: 800, current: 400 };
  assert.equal(S.stepFor({ ...base, key: "Home" }), 260);
  assert.equal(S.stepFor({ ...base, key: "End" }), 800);
});

test("steps clamp at the ends — arrowing past the floor parks on it", () => {
  assert.equal(S.stepFor({ axis: "x", min: 260, max: 800, current: 268, key: "ArrowLeft" }), 260);
  assert.equal(S.stepFor({ axis: "x", min: 260, max: 800, current: 790, key: "ArrowRight", shiftKey: true }), 800);
});

test("a key the separator does not own returns null, so the handler leaves the event alone", () => {
  const base = { axis: "x", min: 260, max: 800, current: 400 };
  assert.equal(S.stepFor({ ...base, key: "Tab" }), null);
  assert.equal(S.stepFor({ ...base, key: "Enter" }), null);
  // The perpendicular arrows belong to the page, not to a separator on this axis.
  assert.equal(S.stepFor({ ...base, key: "ArrowUp" }), null);
  assert.equal(S.stepFor({ axis: "y", min: 120, max: 600, current: 232, key: "ArrowRight" }), null);
});

/* -------------------------------------------------------------- parseStored */

test("a remembered size is read back as a number, and rubbish is refused", () => {
  assert.equal(S.parseStored("412"), 412);
  assert.equal(S.parseStored("412.7"), 413);
  assert.equal(S.parseStored(null), null);
  assert.equal(S.parseStored(""), null);
  assert.equal(S.parseStored("wide"), null);
  assert.equal(S.parseStored("0"), null);
  assert.equal(S.parseStored("-40"), null);
  assert.equal(S.parseStored("Infinity"), null);
  assert.equal(S.parseStored("1e9"), null);
});

test("the pane keys are the ones the browser suites and the docs name", () => {
  assert.equal(S.KEYS.editorDock, "dp.pane.editor-dock");
  assert.equal(S.KEYS.explorerTree, "dp.pane.explorer-tree");
});

test("the step sizes are the ones §A specifies", () => {
  assert.equal(S.STEP, 16);
  assert.equal(S.SHIFT_STEP, 64);
});
