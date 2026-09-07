// 082 addendum P1 — the card sizes to its CONTENT, and Cytoscape is told.
//
// `--pe-card-h` was a FIXED `height` on `.pe-card`. A three-fact card overflows it and
// the footer paints below the node's own border (the owner's screenshot). The card is
// `min-height` now — but the Cytoscape node underneath still paints a box, and that box
// is the chrome (surface, border, selection ring) AND the anchor the edge ports sit at
// the vertical centre of. If it keeps the old number the card and its own chrome come
// apart, which is a worse defect than the one being fixed and a silent one.
//
// So the height travels: syncCardHeights measures each rendered card and writes it onto
// the node as `cardH`; the stylesheet reads it per element. These tests own both ends.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");
const cssPath = path.resolve(here, "../../main/resources/static/css/pipeline-editor.css");

const TOKENS = {
  brand: "#f01", brandSoft: "#f02",
  edgeIdle: "#f03", edgeActive: "#f04", edgeDone: "#f05",
  nodeSurface: "#f06", nodeBorder: "#f07",
  nodeSuccess: "#f08", nodeFailed: "#f09", nodeAborted: "#f0a",
  edgeLabelText: "#f0b", edgeLabelBg: "#f0c",
  cardW: 236, cardH: 148, cardRadius: "12px",
};

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

function nodeStyle(sheet) {
  return sheet.find((e) => e.selector === "node").style;
}

/** A node double: `data()` and `style()` read and write like Cytoscape's. */
function fakeNode(id, data) {
  const store = Object.assign({}, data);
  const bypass = {};
  return {
    bypass,
    id: () => id,
    data(key, value) {
      if (arguments.length === 0) return store;
      if (arguments.length === 1) return store[key];
      store[key] = value;
      return this;
    },
    style(key, value) {
      if (arguments.length === 1) return bypass[key];
      bypass[key] = value;
      return this;
    },
  };
}

/**
 * A graph whose cards are DOM doubles with a chosen `offsetHeight`, plus a Cytoscape
 * double whose layout runs its `layoutstop` handler synchronously.
 */
function graphWith(cardHeights) {
  const nodes = Object.keys(cardHeights).map((id) => fakeNode(id, {}));
  const layouts = [];
  const cards = Object.entries(cardHeights).map(([id, h]) => ({
    getAttribute: (k) => (k === "data-node-id" ? id : null),
    offsetHeight: h,
    classList: { add() {}, remove() {} },
  }));

  globalThis.document = {
    documentElement: { __vars: {} },
    body: { appendChild() {}, removeChild() {} },
    createElement: () => ({ style: {}, setAttribute() {}, parentNode: null }),
    getElementById: () => null,
    querySelectorAll: (sel) => (sel === ".pe-card" ? cards : []),
  };
  globalThis.getComputedStyle = () => ({ getPropertyValue: () => "", color: "" });

  const { PipelineGraph } = loadGraph();
  const graph = new PipelineGraph("cy-canvas", [], null);
  graph.tokens = Object.assign({}, TOKENS);
  graph.cy = {
    nodes: () => {
      const coll = nodes.slice();
      coll.forEach = Array.prototype.forEach.bind(nodes);
      coll.map = Array.prototype.map.bind(nodes);
      return coll;
    },
    edges: () => ({ forEach() {} }),
    elements: () => ({
      layout: () => {
        const l = {
          one(_evt, fn) { l._stop = fn; return l; },
          run() { layouts.push(l); l._stop && l._stop(); },
        };
        return l;
      },
    }),
    style() {},
    fit() {},
    zoom: () => 1,
    center() {},
  };
  // The three post-layout steps are exercised by their own tests; stub them here so
  // this one is about the measurement and the re-layout, nothing else.
  graph.applyEdgeCurves = () => {};
  graph.fitToView = () => {};
  graph.renderMinimap = () => {};
  return { graph, nodes, layouts, cards };
}

test("the stylesheet's height is the token FLOOR, and it is JSON — never a function", () => {
  const sheet = loadGraph().buildStylesheet(TOKENS);
  assert.equal(nodeStyle(sheet).height, 148, "the token is the floor a node starts at");

  // The invariant behind that, and the reason the per-node height is a BYPASS: a live
  // re-apply goes through `cy.style().fromJson(sheet).update()`, which silently DROPS
  // every function value. Measured on Chrome 148: with a function height, one theme
  // switch collapsed every node to Cytoscape's default 30px box. A sheet that is not
  // JSON cannot survive a theme switch, so no entry in it may be a function.
  const functions = [];
  sheet.forEach((entry) =>
    Object.entries(entry.style).forEach(([k, v]) => {
      if (typeof v === "function") functions.push(`${entry.selector} { ${k} }`);
    }));
  assert.deepEqual(functions, [], "a function value here does not survive fromJson()");
});

test("syncCardHeights writes each rendered height onto its node and re-lays out once", () => {
  const { graph, nodes, layouts } = graphWith({ a: 191, b: 148 });

  const started = graph.syncCardHeights();

  assert.equal(started, true, "a changed box needs new positions — dagre is tuned to the card");
  assert.equal(nodes[0].data("cardH"), 191);
  assert.equal(nodes[1].data("cardH"), 148);
  // The style BYPASS is what Cytoscape actually paints, and what survives a re-apply.
  assert.equal(nodes[0].style("height"), 191);
  assert.equal(nodes[1].style("height"), 148);
  // One re-layout, and the pass it triggers finds nothing left to change.
  assert.equal(layouts.length, 1);
});

test("a second call with the same cards is a no-op — no restyle, no relayout", () => {
  const { graph, layouts } = graphWith({ a: 191 });
  graph.syncCardHeights();
  const before = layouts.length;

  assert.equal(graph.syncCardHeights(), false);
  assert.equal(layouts.length, before, "measuring twice must not spin the layout");
});

test("a card that measures zero is ignored rather than collapsing the node", () => {
  // The html-label overlay can be mid-write; a 0 is "not ready", never a height.
  const { graph, nodes } = graphWith({ a: 0 });

  assert.equal(graph.syncCardHeights(), false);
  assert.equal(nodes[0].data("cardH"), undefined);
  assert.equal(nodes[0].style("height"), undefined, "and nothing is painted from a 0");
});

test("a DESTROYED graph measures nothing — the deferred pass can outlive its instance", () => {
  // syncCardHeights runs a frame after layoutstop, and a boosted navigation can destroy
  // Cytoscape in between: init.js's teardown nulls the COMPONENT's cy while this object
  // still holds the destroyed one. Touching it throws "Cannot read properties of null
  // (reading 'isHeadless')" out of Cytoscape's own headless() — seen once in the 082
  // walk, as a console error on an otherwise clean run.
  const { graph, nodes, layouts } = graphWith({ a: 191 });
  graph.cy.destroyed = () => true;

  assert.equal(graph.syncCardHeights(), false);
  assert.equal(graph.refreshCardMetrics(), false);
  assert.equal(nodes[0].data("cardH"), undefined, "nothing was read off a destroyed graph");
  assert.equal(layouts.length, 0, "…and nothing was laid out on one");
});

test("the CSS gives the card a FLOOR, not a fixed height", () => {
  const css = require("node:fs").readFileSync(cssPath, "utf8");
  const rule = css.slice(css.indexOf("\n.pe-card {"), css.indexOf("\n.pe-card-hover {"));

  assert.ok(/min-height:\s*var\(--pe-card-h/.test(rule), "the mock's box is the minimum");
  assert.ok(!/^\s{2}height:/m.test(rule), "a fixed height is what pushed the footer outside the card");
});
