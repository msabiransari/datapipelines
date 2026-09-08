// Graph stylesheet and element-building coverage for
// static/js/pipeline-editor/graph.js (031: graph design; 080 §A: the v2 canvas).
//
// Runs on Node's BUILT-IN runner (`node --test`), wired into Gradle by
// modules/web's `editorJsTest` task — same harness as toast.test.mjs. graph.js
// is an IIFE that exports {PipelineGraph, buildStylesheet, buildElements,
// readDesignTokens, layoutOptions, pulseEnabled, clampFitZoom, ...} via
// module.exports when `window` is absent; these tests drive the PURE functions
// with sentinel tokens, never the constructor (which calls getComputedStyle).

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

/** The stylesheet is a flat array; find the entry for a selector. */
function styleFor(sheet, selector) {
  const entry = sheet.find((e) => e.selector === selector);
  assert.ok(entry, `no stylesheet entry for selector ${selector}`);
  return entry.style;
}

// Distinct sentinel values per key: a token the stylesheet forgets to read then
// asserts as undefined === undefined and the test passes vacuously. The keys are
// the 080 canvas token set (readDesignTokens); keep every value unique.
const TOKENS = {
  brand: "#f01", brandSoft: "#f02",
  edgeIdle: "#f03", edgeActive: "#f04", edgeDone: "#f05",
  nodeSurface: "#f06", nodeBorder: "#f07",
  nodeSuccess: "#f08", nodeFailed: "#f09", nodeAborted: "#f0a",
  edgeLabelText: "#f0b", edgeLabelBg: "#f0c",
  cardW: 236, cardH: 148, cardRadius: "12px",
};

test("the node is the mock's CARD — 236px, chrome only, the text is the HTML overlay", () => {
  const sheet = loadGraph().buildStylesheet(TOKENS);
  const style = styleFor(sheet, "node");
  // The canvas box carries the chrome only — it must NOT emit a canvas label to
  // fight the HTML overlay (059's contract, kept).
  assert.equal(style["label"], undefined, "no canvas label — the HTML overlay owns the text");
  assert.equal(style.width, 236, "the mock's card width");
  // 082 addendum P1: the card sizes to its content, so this token is the FLOOR; the
  // measured per-node height is an element bypass (card-height.test.mjs).
  assert.equal(style.height, 148);
  assert.equal(style["corner-radius"], "12px", "the mock's --r-lg maps to the design system's --radius-lg");
  assert.equal(style["border-color"], TOKENS.nodeBorder);
});

test("a long display name survives buildElements un-truncated", () => {
  const name = "a_very_long_node_display_name_beyond_twenty";
  const els = loadGraph().buildElements([{ id: "n1", display_name: name, type: "DQL" }]);
  assert.equal(els[0].data.id, "n1"); // the card renders the id; truncation is a stylesheet concern
});

test("buildElements emits a type class per node type", () => {
  const els = loadGraph().buildElements([
    { id: "a", type: "DQL" }, { id: "b", type: "DML" },
    { id: "c", type: "DDL" }, { id: "d", type: "PIPELINE" },
  ]);
  assert.match(els[0].classes, /\btype-dql\b/);
  assert.match(els[1].classes, /\btype-dml\b/);
  assert.match(els[2].classes, /\btype-ddl\b/);
  assert.match(els[3].classes, /\bpipeline-node\b/);
  els.forEach((e) => assert.match(e.classes, /\bidle\b/));   // §6.2: idle is explicit
});

test("the caller node is marked — omitted output means caller (contract §4.7)", () => {
  const els = loadGraph().buildElements([
    { id: "staged", type: "DQL", output: { target: "tempdb", table: "t" } },
    { id: "result", type: "DQL" },
  ]);
  assert.ok(!/\bcaller\b/.test(els[0].classes));
  assert.match(els[1].classes, /\bcaller\b/);
});

test("a PIPELINE node with an explicit caller output is marked (contract §4.9) — DML/DDL never are", () => {
  // Mirrors the server's Node.isCallerNode, which has no type guard: §4.9 permits a
  // standard §4.7 output block — {"target":"caller"} included — on a PIPELINE node.
  // The DQL guard on the omitted arm is load-bearing: §4.7 forbids DML/DDL an output
  // block, so their output is ALWAYS omitted and an unguarded omitted arm would mark
  // every one of them caller (034 E1).
  const els = loadGraph().buildElements([
    { id: "child", type: "PIPELINE", output: { target: "caller" } },
    { id: "sideeffect", type: "PIPELINE" },
    { id: "writer", type: "DML" },
    { id: "migrator", type: "DDL" },
    { id: "explicit", type: "DQL", output: { target: "caller" } },
  ]);
  assert.match(els[0].classes, /\bcaller\b/);
  assert.ok(!/\bcaller\b/.test(els[1].classes));
  assert.ok(!/\bcaller\b/.test(els[2].classes));
  assert.ok(!/\bcaller\b/.test(els[3].classes));
  assert.match(els[4].classes, /\bcaller\b/);
  // 080 §A: the class stays (the dock and tests read it) but the canvas's
  // double-border caller style is retired — the mock's caller card carries no marker.
  const sheet = loadGraph().buildStylesheet(TOKENS);
  assert.equal(sheet.find((e) => e.selector === "node.caller"), undefined, "no caller chrome on the v2 canvas");
});

test("edges are still built from depends_on", () => {
  const els = loadGraph().buildElements([
    { id: "a", type: "DQL" }, { id: "b", type: "DQL", depends_on: ["a"] },
  ]);
  const edges = els.filter((e) => e.group === "edges");
  assert.equal(edges.length, 1);
  assert.deepEqual([edges[0].data.source, edges[0].data.target], ["a", "b"]);
});

test("selection is the mock's ring: brand border over a brand-soft underlay", () => {
  const style = styleFor(loadGraph().buildStylesheet(TOKENS), "node:selected");
  assert.equal(style["border-color"], TOKENS.brand);
  // underlay, not overlay: an overlay paints over the node and dims its label. The
  // underlay at full opacity and 3px padding is the `0 0 0 3px var(--brand-soft)` ring.
  assert.equal(style["underlay-color"], TOKENS.brandSoft);
  assert.equal(style["underlay-opacity"], 1);
  assert.equal(style["underlay-padding"], 3);
});

test("states are accents, not full fills", () => {
  const sheet = loadGraph().buildStylesheet(TOKENS);
  const accent = {
    running: TOKENS.brand, success: TOKENS.nodeSuccess,
    failed: TOKENS.nodeFailed, aborted: TOKENS.nodeAborted,
  };
  Object.keys(accent).forEach((s) => {
    const style = styleFor(sheet, "node." + s);
    assert.equal(style["background-color"], undefined,
      `state ${s} must not repaint the card background`);
    // Assert the VALUE, not merely presence.
    assert.equal(style["border-color"], accent[s]);
  });
  assert.equal(styleFor(sheet, "node.aborted").opacity, 0.5);   // §6.2 already required this
});

test("the edge's three states are the mock's: --edge at rest, --edge-active dashed while the target runs, --edge-done after", () => {
  const sheet = loadGraph().buildStylesheet(TOKENS);
  const idle = styleFor(sheet, "edge");
  assert.equal(idle["line-color"], TOKENS.edgeIdle);
  assert.equal(idle.width, 2, "the mock's 2px stroke");

  const active = styleFor(sheet, "edge.active");
  assert.equal(active["line-color"], TOKENS.edgeActive);
  assert.equal(active["line-style"], "dashed");
  assert.deepEqual(active["line-dash-pattern"], [6, 8], "the mock's dash — the flow animation steps the offset");

  const done = styleFor(sheet, "edge.done");
  assert.equal(done["line-color"], TOKENS.edgeDone);
  assert.equal(done["target-arrow-color"], TOKENS.edgeDone);
});

test("row counts ride the edge behind the `rows` class — small mono on a page-coloured backing", () => {
  const style = styleFor(loadGraph().buildStylesheet(TOKENS), "edge.rows");
  assert.equal(style.label, "data(rowLabel)");
  assert.equal(style["font-size"], 11, "the mock's small mono label");
  assert.equal(style.color, TOKENS.edgeLabelText);
  assert.equal(style["text-background-color"], TOKENS.edgeLabelBg, "the line must not show through the digits");
});

test("the pulse/flow gate honours the reduced-motion preference", () => {
  const graph = loadGraph();
  assert.equal(graph.pulseEnabled({ matches: true }), false);   // reduce → still
  assert.equal(graph.pulseEnabled({ matches: false }), true);
});

// 098 §B retired `FIT_MIN_ZOOM` and `clampFitZoom`, and this case with them: a zoom FLOOR
// on a FIT can only bind when the content does not fit, so it turned Fit into a no-op that
// then centred the overflow (093 §4 measured the first and last card 75px off the canvas).
// The 080 ceiling is unchanged and is asserted here; everything else fit now decides lives
// in `fitZoomFor`, whose invariant graph-fit.test.mjs owns from the token geometry.
test("fit never zooms IN past the 1.0 ceiling", () => {
  const g = loadGraph();
  assert.equal(g.FIT_MAX_ZOOM, 1.0, "the 080 ceiling — a one-node pipeline used to fit to 3x");
  assert.equal(g.FIT_PADDING, 48, "rendered px on every side, the fit's only padding");
  // A single card in a big canvas: the natural fit is well above 1 and must be refused.
  assert.equal(g.fitZoomFor(880, 468, 236, 156, g.FIT_PADDING), 1.0);
});

test("the layout gives the graph room, and counts labels as part of a node", () => {
  const opts = loadGraph().layoutOptions();
  assert.equal(opts.name, "dagre");
  assert.equal(opts.rankDir, "LR");
  assert.ok(opts.nodeSep >= 40);
  assert.ok(opts.rankSep >= 90);
  assert.ok(opts.edgeSep >= 12);
  assert.ok(opts.padding >= 24);
  // Without this, dagre lays out on the node box alone and the below-shape labels
  // of one rank collide with the next rank (cytoscape-dagre default is false).
  assert.equal(opts.nodeDimensionsIncludeLabels, true);
  assert.equal(opts.marginX, undefined, "dagre has no marginX — do not pin an ignored key");
});
