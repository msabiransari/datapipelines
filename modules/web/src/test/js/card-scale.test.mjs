// 082 §A — the card's wide-stage step-up, at the seam that can silently break it.
//
// The step-up is a CONTAINER query on `.pe-stage` whose declarations land on
// `#cy-canvas` (a container cannot style itself). A container query's result is
// invisible on `document.documentElement`, so `readDesignTokens` had to start
// reading the geometry from the graph's own container: reading the root would
// return the 236px base for ever and the Cytoscape node box would disagree with
// the HTML card on every wide screen — cards overlapping their own edges, with
// nothing red anywhere. That is the regression these tests own.
//
// Same harness as the other graph tests: node --test, graph.js required
// directly, hand-rolled `document`/`getComputedStyle`.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

const ROOT_VARS = {
  "--pe-card-w": "236px",
  "--pe-card-h": "148px",
  "--radius-lg": "12px",
  "--brand": "#abc123",
};

/**
 * `canvasVars` models what a REAL getComputedStyle returns on `#cy-canvas`:
 * custom properties inherit, so the canvas sees the root's values unless the
 * container query overrode them. Only the overrides are passed in.
 */
function withDom(canvasOverrides) {
  const canvasVars = Object.assign({}, ROOT_VARS, canvasOverrides || {});
  const root = { __vars: ROOT_VARS };
  const canvas = { __vars: canvasVars, closest: () => null };
  globalThis.document = {
    documentElement: root,
    getElementById: (id) => (id === "cy-canvas" ? canvas : null),
    querySelectorAll: () => [],
  };
  globalThis.getComputedStyle = (el) => ({
    getPropertyValue: (k) => (el.__vars[k] === undefined ? "" : el.__vars[k]),
  });
  return { canvas, canvasVars };
}

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

test("with no container the geometry still comes from the root — the pure callers are unchanged", () => {
  withDom();
  const { readDesignTokens } = loadGraph();

  const tokens = readDesignTokens();
  assert.equal(tokens.cardW, 236);
  assert.equal(tokens.cardH, 148);
});

test("the container query's box is read from the CANVAS, not from documentElement", () => {
  // Exactly the wide-stage state: the root still says 236px, the canvas says 272px.
  withDom({ "--pe-card-w": "272px", "--pe-card-h": "170px" });
  const { readDesignTokens } = loadGraph();

  const tokens = readDesignTokens("cy-canvas");
  assert.equal(tokens.cardW, 272, "reading the root here would give 236 and split the card in two");
  assert.equal(tokens.cardH, 170);
});

test("colours stay a :root read — the container carries geometry only", () => {
  // The canvas has NO --brand of its own; the value must come from documentElement.
  withDom({ "--pe-card-w": "272px" });
  const { readDesignTokens } = loadGraph();

  assert.equal(readDesignTokens("cy-canvas").brand, "#abc123");
});

/**
 * A Cytoscape double that records the stylesheet it is given and defers layout. The
 * re-apply goes through `cy.style().fromJson(sheet).update()` — `cy.style(sheet)`
 * resets a live graph to Cytoscape's defaults (082 addendum P1, measured live) — so
 * the double mirrors that shape rather than the setter.
 */
function fakeCy() {
  const applied = [];
  const styleApi = {
    fromJson(sheet) {
      applied.push(sheet);
      return styleApi;
    },
    update() {},
  };
  return {
    applied,
    style: () => styleApi,
    nodes: () => [],
    elements: () => ({ layout: () => ({ one: () => {}, run: () => {} }) }),
  };
}

test("refreshCardMetrics re-applies the box when the stage crosses the threshold — and only then", () => {
  const dom = withDom();
  const { PipelineGraph } = loadGraph();

  const graph = new PipelineGraph("cy-canvas", [], null);
  assert.equal(graph.tokens.cardW, 236);
  graph.cy = fakeCy();

  // Nothing moved: no restyle, no relayout. (A resize observer fires constantly.)
  assert.equal(graph.refreshCardMetrics(), false);
  assert.equal(graph.cy.applied.length, 0);

  // The stage widened past the container query's threshold.
  dom.canvasVars["--pe-card-w"] = "272px";
  dom.canvasVars["--pe-card-h"] = "170px";

  assert.equal(graph.refreshCardMetrics(), true);
  assert.equal(graph.tokens.cardW, 272);
  const node = graph.cy.applied[0].find((e) => e.selector === "node");
  assert.equal(node.style.width, 272, "the Cytoscape box follows the HTML card");
  assert.equal(node.style.height, 170, "the stepped-up token is the new floor");

  // Idempotent at the new width.
  assert.equal(graph.refreshCardMetrics(), false);
  assert.equal(graph.cy.applied.length, 1);
});
