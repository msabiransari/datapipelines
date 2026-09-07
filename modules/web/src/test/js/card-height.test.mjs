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

/** A node double: `data()` reads and writes like Cytoscape's. */
function fakeNode(id, data) {
  const store = Object.assign({}, data);
  return {
    id: () => id,
    data(key, value) {
      if (arguments.length === 0) return store;
      if (arguments.length === 1) return store[key];
      store[key] = value;
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

test("the node's painted height is its OWN measured card, not the token", () => {
  const style = nodeStyle(loadGraph().buildStylesheet(TOKENS));
  assert.equal(typeof style.height, "function", "a fixed number cannot vary per node");

  assert.equal(style.height(fakeNode("a", { cardH: 191 })), 191, "a measured card wins");
  assert.equal(style.height(fakeNode("b", {})), 148, "…and the token is the floor until it is measured");
});

test("syncCardHeights writes each rendered height onto its node and re-lays out once", () => {
  const { graph, nodes, layouts } = graphWith({ a: 191, b: 148 });

  const started = graph.syncCardHeights();

  assert.equal(started, true, "a changed box needs new positions — dagre is tuned to the card");
  assert.equal(nodes[0].data("cardH"), 191);
  assert.equal(nodes[1].data("cardH"), 148);
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
});

test("the CSS gives the card a FLOOR, not a fixed height", () => {
  const css = require("node:fs").readFileSync(cssPath, "utf8");
  const rule = css.slice(css.indexOf("\n.pe-card {"), css.indexOf("\n.pe-card-hover {"));

  assert.ok(/min-height:\s*var\(--pe-card-h/.test(rule), "the mock's box is the minimum");
  assert.ok(!/^\s{2}height:/m.test(rule), "a fixed height is what pushed the footer outside the card");
});
