// 098 §B — "Fit graph to view" must actually fit.
//
// The defect, measured on the demo stack (2026-09-08, nyc/mobility/
// weather_sensitivity_by_borough at 1440x900 with the dock open, after clicking Fit):
//
//     canvas #cy-canvas   y 136..604   h 468   w 880
//     cy.zoom()           0.75         <- FIT_MIN_ZOOM, exactly
//     content bounding box 638 x 828 model units
//     card stage_daily_by_zone  y  61..178   75 px ABOVE the canvas (clipped by the top bar)
//     card stage_calendar       y 562..679   75 px BELOW  the canvas
//
// `fitToView` used to take Cytoscape's own fit zoom and then clamp it into
// [FIT_MIN_ZOOM, FIT_MAX_ZOOM]. The ceiling is a ruling (082/085: fit never zooms IN past
// 1.0). The FLOOR could only ever bind when the content did not fit — so it turned "fit"
// into "don't fit", and the following `cy.center()` split the overflow evenly, which is why
// the overhang was symmetric.
//
// These tests own the invariant directly, because `fitZoomFor` is now the WHOLE decision:
// the geometry comes from the design tokens (`--pe-card-w` / `--pe-card-h`) and the dagre
// rank separation, and the assertion is that every card box lands inside the canvas box.
// Falsification: put the 0.75 floor back (`Math.max(0.75, …)` in fitZoomFor) and the
// "…fits five stacked cards" and "the retired 0.75 floor is what pushed…" tests go red.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");
const { fitZoomFor, FIT_PADDING, FIT_MAX_ZOOM } = require(graphPath);

/** The token geometry, as `readDesignTokens` reads it off #cy-canvas below the 1200px step-up. */
const CARD_W = 236; // --pe-card-w
const CARD_H = 156; // the rendered card at 1440x900 — --pe-card-h 148 is its min-height floor
/** Dagre's separations between two cards' CENTRES, measured in the same run. */
const RANK_SEP = 223; // stacked in one column
const COL_SEP = 402; // between columns

/** The canvas box the editor gives Cytoscape at 1440x900 with the rail expanded and the dock open. */
const CANVAS_W = 880;
const CANVAS_H = 468;

/**
 * The model-space content box of a column of `n` cards, and (for a wide graph) `cols`
 * columns — the same shape `cy.elements().boundingBox()` returns, built from tokens rather
 * than from a fixture, so a token change moves the test with the product.
 */
function contentBox(rows, cols = 1) {
  return { w: CARD_W + COL_SEP * (cols - 1), h: CARD_H + RANK_SEP * (rows - 1) };
}

/**
 * The showcase pipeline the defect was measured on: dagre puts its four staging nodes in one
 * column and the join in a second, so the model box is 638 x 825 — the 638 x 828 that run's
 * `cy.elements().boundingBox()` reported, less the few px of edge overlay it also covers.
 */
const SHOWCASE = { rows: 4, cols: 2 };

/**
 * Every card's rendered top/bottom under `zoom`, centred in the canvas the way `cy.center()`
 * leaves them. Mirrors what the browser test reads out of `getBoundingClientRect()`.
 */
function cardRows(rows, zoom, canvasH = CANVAS_H) {
  const content = contentBox(rows);
  const top = (canvasH - content.h * zoom) / 2;
  return Array.from({ length: rows }, (_, i) => {
    const y = top + i * RANK_SEP * zoom;
    return { top: y, bottom: y + CARD_H * zoom };
  });
}

test("fit never zooms IN past 1.0 — the 082/085 ruling, unchanged", () => {
  // One card in a big canvas: the natural fit is ~2.5x and must be refused.
  const one = contentBox(1);
  assert.equal(fitZoomFor(CANVAS_W, CANVAS_H, one.w, one.h, FIT_PADDING), FIT_MAX_ZOOM);
  assert.equal(FIT_MAX_ZOOM, 1.0);
});

test("fit zooms OUT far enough that the showcase's cards fit, padding included", () => {
  const content = contentBox(SHOWCASE.rows, SHOWCASE.cols);
  const z = fitZoomFor(CANVAS_W, CANVAS_H, content.w, content.h, FIT_PADDING);

  assert.ok(z < FIT_MAX_ZOOM, `a graph taller than the canvas must shrink, got ${z}`);
  assert.ok(
    content.h * z + FIT_PADDING * 2 <= CANVAS_H + 1e-9,
    `content ${content.h} at ${z} plus padding must fit ${CANVAS_H}`,
  );

  // The browser assertion, in numbers: every card inside the canvas box.
  for (const [i, card] of cardRows(SHOWCASE.rows, z).entries()) {
    assert.ok(card.top >= -1e-9, `card ${i} starts above the canvas (${card.top})`);
    assert.ok(card.bottom <= CANVAS_H + 1e-9, `card ${i} ends below the canvas (${card.bottom})`);
  }
});

test("the retired 0.75 floor is what pushed the first and last card off the canvas", () => {
  // The defect reproduced arithmetically from the same geometry: at the old floor the four
  // stacked cards are 151 px taller than the canvas, split evenly by cy.center() — the 75 px above
  // and 75 px below that 093 measured on screen.
  const content = contentBox(SHOWCASE.rows, SHOWCASE.cols);
  const overflow = content.h * 0.75 - CANVAS_H;
  assert.ok(overflow > 0, "the floor over-zooms this graph");
  assert.equal(Math.round(overflow / 2), 75);

  const clipped = cardRows(SHOWCASE.rows, 0.75).filter((c) => c.top < 0 || c.bottom > CANVAS_H);
  assert.equal(clipped.length, 2, "the top and the bottom card are the two that leave the canvas");
});

test("a canvas with no room for its own padding, and an empty graph, answer the ceiling", () => {
  assert.equal(fitZoomFor(60, 60, 638, 828, FIT_PADDING), FIT_MAX_ZOOM);
  assert.equal(fitZoomFor(CANVAS_W, CANVAS_H, 0, 0, FIT_PADDING), FIT_MAX_ZOOM);
});

test("the width can be the binding dimension, not only the height", () => {
  const wide = contentBox(1, 6);
  const z = fitZoomFor(CANVAS_W, CANVAS_H, wide.w, wide.h, FIT_PADDING);
  assert.ok(
    wide.w * z + FIT_PADDING * 2 <= CANVAS_W + 1e-9,
    `content ${wide.w} at ${z} plus padding must fit ${CANVAS_W}`,
  );
  assert.equal(z, (CANVAS_W - FIT_PADDING * 2) / wide.w);
});
