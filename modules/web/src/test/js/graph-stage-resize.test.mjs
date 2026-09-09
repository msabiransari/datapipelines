// 104 §B — the canvas follows the dock.
//
// The defect to avoid, stated as the owner would see it: the dock is dragged 150px taller,
// the stage loses 150px, and the graph does not move. Cytoscape only re-reads its container
// on a WINDOW resize; a dock drag resizes the container without resizing the window, so
// `cy` keeps painting into a viewport that no longer exists — the bottom cards disappear
// under the dock and `cy.height()` still reports the old number.
//
// `handleStageResize` is the one entry point the stage's ResizeObserver calls, and these
// tests own its two rules:
//
//   1. ALWAYS `cy.resize()` — the container box changed, whatever the view is doing;
//   2. re-fit ONLY if the view was still the fit (082/098: fit never zooms IN, and a user
//      who has panned or zoomed to a corner must not have their view yanked back by a
//      resize they did not ask for).
//
// A fake `cy` rather than a real one: the rule is about which calls happen in which state,
// and Cytoscape in node has no layout. The live pixel numbers are
// `PipelineEditorDockResizeBrowserTest`'s job.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");
const { PipelineGraph } = require(graphPath);

/** Records the calls the rules are about, and fires `pan`/`zoom` the way Cytoscape does. */
function fakeCy() {
  const calls = [];
  const handlers = {};
  const cy = {
    calls,
    resize: () => {
      calls.push("resize");
    },
    width: () => 880,
    height: () => 468,
    zoom(z) {
      if (z === undefined) return cy._zoom;
      cy._zoom = typeof z === "number" ? z : z.level;
      calls.push("zoom");
      cy.emit("zoom");
      return cy;
    },
    center: () => {
      calls.push("center");
      cy.emit("pan");
    },
    pan: () => {
      cy.emit("pan");
    },
    elements: () => ({ boundingBox: () => ({ w: 638, h: 828 }) }),
    destroyed: () => false,
    on(names, fn) {
      names.split(" ").forEach((n) => {
        (handlers[n] = handlers[n] || []).push(fn);
      });
    },
    emit(name) {
      (handlers[name] || []).forEach((fn) => fn());
    },
    _zoom: 1,
  };
  return cy;
}

/**
 * A graph object with only what these rules touch — the real constructor wants a live DOM.
 * `refreshCardMetrics` is replaced by a recorder for the same reason: it reads
 * `getComputedStyle` off `#cy-canvas` and `card-height.test.mjs` already owns it. Recording
 * it rather than deleting it keeps it ASSERTABLE — a stage that changed size has to re-read
 * the card box (082 §A) as well as tell Cytoscape.
 */
function graphWith(cy) {
  const g = Object.create(PipelineGraph.prototype);
  g.cy = cy;
  g.refreshCardMetrics = () => {
    cy.calls.push("cards");
  };
  g.watchViewGestures();
  return g;
}

test("a stage resize always tells Cytoscape its container moved", () => {
  const cy = fakeCy();
  const g = graphWith(cy);
  g.handleStageResize();
  assert.ok(cy.calls.includes("resize"), "cy.resize() was never called: " + cy.calls.join(","));
  assert.ok(cy.calls.includes("cards"), "the card box was not re-read: " + cy.calls.join(","));
});

test("a graph that is still showing the fit is re-fitted after the resize", () => {
  const cy = fakeCy();
  const g = graphWith(cy);
  g.fitToView();
  cy.calls.length = 0;
  g.handleStageResize();
  assert.deepEqual(cy.calls, ["resize", "cards", "zoom", "center"]);
});

test("a graph the user has panned is resized but NOT re-fitted", () => {
  const cy = fakeCy();
  const g = graphWith(cy);
  g.fitToView();
  cy.pan(); // the user drags the canvas
  cy.calls.length = 0;
  g.handleStageResize();
  assert.deepEqual(cy.calls, ["resize", "cards"]);
});

test("a graph the user has zoomed with the controls is resized but NOT re-fitted", () => {
  const cy = fakeCy();
  const g = graphWith(cy);
  g.fitToView();
  g.zoomBy(1.2);
  cy.calls.length = 0;
  g.handleStageResize();
  assert.deepEqual(cy.calls, ["resize", "cards"]);
});

test("Reset view is a user view too — a later resize does not re-fit", () => {
  const cy = fakeCy();
  const g = graphWith(cy);
  g.fitToView();
  g.resetView();
  cy.calls.length = 0;
  g.handleStageResize();
  assert.deepEqual(cy.calls, ["resize", "cards"]);
});

test("clicking Fit again re-arms the follow — the state is the LAST view action, not a latch", () => {
  const cy = fakeCy();
  const g = graphWith(cy);
  g.fitToView();
  cy.pan();
  g.fitToView();
  cy.calls.length = 0;
  g.handleStageResize();
  assert.deepEqual(cy.calls, ["resize", "cards", "zoom", "center"]);
});

test("fit's OWN pan and zoom events do not count as the user moving the view", () => {
  // fitToView calls cy.zoom() and cy.center(), and Cytoscape emits `zoom`/`pan` for both.
  // A naive listener would clear the flag the moment fit set it.
  const cy = fakeCy();
  const g = graphWith(cy);
  g.fitToView();
  cy.calls.length = 0;
  g.handleStageResize();
  assert.ok(cy.calls.includes("center"), "fit's own events cleared the fitted flag");
});

test("a graph whose Cytoscape instance is gone is inert, not a crash", () => {
  const g = Object.create(PipelineGraph.prototype);
  g.cy = null;
  g.handleStageResize();
  const destroyed = Object.create(PipelineGraph.prototype);
  const cy = fakeCy();
  cy.destroyed = () => true;
  destroyed.cy = cy;
  destroyed.handleStageResize();
  assert.deepEqual(cy.calls, []);
});
