// 159 addendum (#151) — a card that GROWS after render is measured again, per node.
//
// 082 addendum P1 made the card `min-height` and taught Cytoscape the rendered height
// (syncCardHeights) — once, after the initial render, and after a theme change. 151's
// output-port block then added lines to cards DURING a run (`one statement`,
// `committed · N rows`, the run line) through data writes the html-label re-renders on;
// nothing re-measured, the node box kept its pre-run height, and the label — centred on the
// box — spilled past both edges (the owner's screenshot, #151).
//
// Pinned here:
//   1. After any height-changing write (setNodeState, setNodeStats, setNodeOperation,
//      setMarkerState, resetAll) the node's `cardH` and its `height` style follow the card's
//      `offsetHeight` — per node, one deferred measure that runs AFTER the html-label's own
//      deferred re-render, coalesced per node so a burst of writes measures the final render.
//   2. No full relayout while the run is in flight (a mid-run relayout moves cards under the
//      reader) — unless the grown card would overlap a neighbour.
//   3. When the run completes (End takes a terminal state) a layout left stale by mid-run
//      growth is re-run once; the oscillation bound never swallows the per-node path.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

/** A node double with the surface graph.js touches on the write paths. */
function fakeNode(id, data, pos) {
  const store = Object.assign({}, data);
  const bypass = {};
  const classes = new Set();
  const edges = { forEach() {} };
  const n = {
    length: 1,
    bypass,
    classes,
    id: () => id,
    position: () => pos,
    data(key, value) {
      if (arguments.length === 0) return store;
      if (arguments.length === 1) return store[key];
      store[key] = value;
      return n;
    },
    style(key, value) {
      if (arguments.length === 1) return bypass[key];
      bypass[key] = value;
      return n;
    },
    addClass: (c) => { classes.add(c); return n; },
    removeClass: (c) => { classes.delete(c); return n; },
    incomers: () => edges,
    outgoers: () => edges,
  };
  return n;
}

/**
 * A graph over card doubles whose `offsetHeight` the test moves, a Cytoscape double whose
 * layout stops synchronously, and QUEUED timers (the html-label's re-render and the measure
 * are both `setTimeout(0)`; `flush` runs them in order).
 */
function harness(spec, editor) {
  const nodes = spec.map((s) => fakeNode(s.id, s.data || {}, s.pos || { x: 0, y: 0 }));
  const byId = {};
  nodes.forEach((n) => { byId[n.id()] = n; });
  const cards = spec.map((s) => ({
    id: s.id,
    getAttribute: (k) => (k === "data-node-id" ? s.id : null),
    offsetHeight: s.h,
    classList: { add() {}, remove() {} },
  }));
  const layouts = [];
  const timers = [];
  let nextId = 1;
  const realSet = globalThis.setTimeout;
  const realClear = globalThis.clearTimeout;
  globalThis.setTimeout = (fn) => { const id = nextId++; timers.push({ id, fn }); return id; };
  globalThis.clearTimeout = (id) => { const i = timers.findIndex((t) => t.id === id); if (i >= 0) timers.splice(i, 1); };
  globalThis.document = {
    documentElement: { __vars: {} },
    body: { appendChild() {}, removeChild() {} },
    createElement: () => ({ style: {}, setAttribute() {}, parentNode: null }),
    getElementById: () => null,
    querySelectorAll: (sel) => (sel === ".pe-card" ? cards : []),
  };
  globalThis.getComputedStyle = () => ({ getPropertyValue: () => "", color: "" });
  const { PipelineGraph } = loadGraph();
  const graph = new PipelineGraph("cy-canvas", spec.map((s) => ({ id: s.id })), editor || null);
  graph.tokens = { cardW: 236, cardH: 148 };
  graph.cy = {
    nodes: () => {
      const coll = nodes.slice();
      coll.forEach = Array.prototype.forEach.bind(nodes);
      coll.map = Array.prototype.map.bind(nodes);
      return coll;
    },
    edges: () => ({ forEach() {} }),
    getElementById: (id) => byId[id] || { length: 0 },
    elements: () => ({
      layout: () => {
        const l = { one(_e, fn) { l._stop = fn; return l; }, run() { layouts.push(l); l._stop && l._stop(); } };
        return l;
      },
    }),
    style() {},
    fit() {},
    zoom: () => 1,
    center() {},
  };
  graph._boundaryIds = { start: "__execution_start__", end: "__execution_end__" };
  graph.applyEdgeCurves = () => {};
  graph.fitToView = () => {};
  let minimapRenders = 0;
  graph.renderMinimap = () => { minimapRenders++; };
  graph.updateMinimapNode = () => {};
  const flush = () => { while (timers.length) timers.shift().fn(); };
  const card = (id) => cards.find((c) => c.id === id);
  const restore = () => { globalThis.setTimeout = realSet; globalThis.clearTimeout = realClear; delete globalThis.document; delete globalThis.getComputedStyle; };
  return { graph, nodes, byId, cards, card, layouts, timers, flush, restore, minimaps: () => minimapRenders };
}

const far = [
  { id: "a", h: 148, pos: { x: 0, y: 0 } },
  { id: "b", h: 148, pos: { x: 400, y: 0 } },
];

test("a card that grows after render is measured again: cardH and the height style follow, per node, with no relayout mid-run", () => {
  const h = harness(far, { isExecuting: true, nodeStates: {} });
  try {
    h.graph.syncCardHeights(); // the initial pass, as render() does
    h.flush();
    assert.equal(h.byId.a.data("cardH"), 148);
    const layoutsBefore = h.layouts.length; // the initial pass laid out once
    const minimapsBefore = h.minimaps(); // …and painted the minimap once
    // The run line arrives, the html-label re-renders (its own timer), the card grows.
    h.graph.setNodeStats("a", { rows_out: 66, duration_ms: 328 });
    h.card("a").offsetHeight = 191; // what the re-render measures
    assert.equal(h.byId.a.data("cardH"), 148, "nothing synchronous: the measure waits for the re-render");
    h.flush();
    assert.equal(h.byId.a.data("cardH"), 191, "the node's model height follows the card");
    assert.equal(h.byId.a.style("height"), 191, "and the style bypass — what Cytoscape paints");
    assert.equal(h.byId.b.data("cardH"), 148, "the neighbour is untouched");
    assert.equal(h.layouts.length, layoutsBefore, "no relayout while the run is in flight — the cards stay under the reader");
    assert.equal(h.minimaps(), minimapsBefore + 1, "the minimap's silhouette follows the new box");
  } finally {
    h.restore();
  }
});

test("every height-changing write queues a measure: state, stats, operation (the port lines), the markers, and the reset", () => {
  const spec = far.concat([{ id: "__execution_end__", h: 72, pos: { x: 800, y: 0 }, data: { kind: "boundary", boundary: "end" } }]);
  const h = harness(spec, { isExecuting: true, nodeStates: {} });
  try {
    h.graph.syncCardHeights();
    h.flush();
    const grow = (id, to, write) => {
      write();
      h.card(id).offsetHeight = to;
      h.flush();
      assert.equal(h.byId[id].data("cardH"), to, id + " measured after the write");
    };
    grow("a", 160, () => h.graph.setNodeState("a", "running"));
    grow("a", 172, () => h.graph.setNodeOperation("a", { cardLine: "Writing", port: { state: "writing" } }));
    grow("a", 191, () => h.graph.setNodeStats("a", { rows_out: 66, duration_ms: 328 }));
    grow("__execution_end__", 90, () => h.graph.setMarkerState("end", "running"));
    grow("b", 150, () => h.graph.resetAll());
  } finally {
    h.restore();
  }
});

test("a burst of writes to one node measures once, after the LAST re-render — never a stale intermediate", () => {
  const h = harness(far, { isExecuting: true, nodeStates: {} });
  try {
    h.graph.syncCardHeights();
    h.flush();
    h.graph.setNodeState("a", "running");
    h.graph.setNodeOperation("a", { cardLine: "Querying" });
    h.graph.setNodeStats("a", { rows_out: 1, duration_ms: 1 });
    // Three writes, ONE pending measure (re-armed each time, so it sits behind every
    // re-render the writes queued).
    assert.equal(h.timers.length, 1, "coalesced per node");
    h.card("a").offsetHeight = 205;
    h.flush();
    assert.equal(h.byId.a.data("cardH"), 205);
  } finally {
    h.restore();
  }
});

test("a grown card that would overlap its neighbour re-lays out even mid-run", () => {
  // Two cards in one rank, 160px apart centre to centre: 148px boxes leave a 12px gap;
  // growing `a` to 191px puts its bottom edge into `b`.
  const near = [
    { id: "a", h: 148, pos: { x: 0, y: 0 } },
    { id: "b", h: 148, pos: { x: 0, y: 160 } },
  ];
  const h = harness(near, { isExecuting: true, nodeStates: {} });
  try {
    h.graph.syncCardHeights();
    h.flush();
    const before = h.layouts.length;
    h.graph.setNodeStats("a", { rows_out: 66, duration_ms: 328 });
    h.card("a").offsetHeight = 191;
    h.flush();
    assert.equal(h.byId.a.data("cardH"), 191);
    assert.equal(h.layouts.length, before + 1, "an overlap is the one thing worth moving cards for mid-run");
  } finally {
    h.restore();
  }
});

test("when the run completes a layout left stale by mid-run growth is re-run once; the oscillation bound is reset, never swallows the measure", () => {
  const spec = far.concat([{ id: "__execution_end__", h: 72, pos: { x: 800, y: 0 }, data: { kind: "boundary", boundary: "end" } }]);
  const h = harness(spec, { isExecuting: true, nodeStates: {} });
  try {
    h.graph.syncCardHeights();
    h.flush();
    const initial = h.layouts.length; // the initial pass laid out once
    h.graph._heightPasses = 3; // the bound exhausted by an earlier oscillation
    h.graph.setNodeStats("a", { rows_out: 66, duration_ms: 328 });
    h.card("a").offsetHeight = 191;
    h.flush();
    assert.equal(h.byId.a.data("cardH"), 191, "the per-node path is not bounded");
    assert.equal(h.layouts.length, initial, "no relayout yet — the run was in flight");
    // The terminal frame: End takes its outcome, the run is over.
    h.graph.editor.isExecuting = false;
    h.graph.setMarkerState("end", "success", { elapsed: "2.4 s" });
    h.flush();
    assert.equal(h.layouts.length, initial + 1, "the stale layout is re-run once at completion");
    assert.equal(h.graph._heightPasses, 0, "the bound is reset for the completion pass");
    // Nothing grew since: a second completion changes nothing.
    h.graph.setMarkerState("end", "success", { elapsed: "2.4 s" });
    h.flush();
    assert.equal(h.layouts.length, initial + 1, "no growth, no relayout");
  } finally {
    h.restore();
  }
});

test("a measure that finds no change writes nothing and lays out nothing; a destroyed graph measures nothing", () => {
  const h = harness(far, { isExecuting: true, nodeStates: {} });
  try {
    h.graph.syncCardHeights();
    h.flush();
    const layouts = h.layouts.length;
    const minimaps = h.minimaps();
    h.graph.setNodeStats("a", { rows_out: 66, duration_ms: 328 });
    h.flush();
    assert.equal(h.byId.a.data("cardH"), 148);
    assert.equal(h.layouts.length, layouts);
    assert.equal(h.minimaps(), minimaps, "no change, no minimap repaint");
    h.graph.cy.destroyed = () => true;
    h.graph.setNodeStats("a", { rows_out: 1, duration_ms: 1 });
    h.card("a").offsetHeight = 300;
    assert.doesNotThrow(() => h.flush());
    assert.equal(h.byId.a.data("cardH"), 148, "nothing read off a destroyed graph");
  } finally {
    h.restore();
  }
});
