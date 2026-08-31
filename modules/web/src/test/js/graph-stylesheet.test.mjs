// Graph stylesheet and element-building coverage for
// static/js/pipeline-editor/graph.js (031: graph design).
//
// Runs on Node's BUILT-IN runner (`node --test`), wired into Gradle by
// modules/web's `editorJsTest` task — same harness as toast.test.mjs. graph.js
// is an IIFE that exports {PipelineGraph, buildStylesheet, buildElements,
// readDesignTokens, layoutOptions, pulseEnabled} via module.exports when
// `window` is absent; these tests drive the PURE functions with sentinel
// tokens, never the constructor (which calls getComputedStyle).

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
// asserts as undefined === undefined and the test passes vacuously. Task 2 adds
// selection/state keys here — keep every value unique.
const TOKENS = {
  nodeSurface: "#f01", nodeBorder: "#f02", nodeLabelText: "#f03",
  nodeSelectedRing: "#f04", nodeSelectedHalo: "#f05",
  nodeRunningAccent: "#f06", nodeSuccessAccent: "#f07",
  nodeFailedAccent: "#f08", nodeAbortedAccent: "#f09",
  edgeIdleStroke: "#f0a", edgeActiveStroke: "#f0b",
};

test("the node label renders BELOW the shape, not inside it", () => {
  const style = styleFor(loadGraph().buildStylesheet(TOKENS), "node");
  assert.equal(style["text-valign"], "bottom");
  assert.ok(style["text-margin-y"] > 0, "the label needs clearance from the shape");
  // The 80x40 box the redesign replaces.
  assert.notEqual(style.width, 80);
  assert.notEqual(style.height, 40);
});

test("a long display name survives buildElements un-truncated", () => {
  const name = "a_very_long_node_display_name_beyond_twenty";
  const els = loadGraph().buildElements([{ id: "n1", display_name: name, type: "DQL" }]);
  assert.equal(els[0].data.label, name);      // truncation is now a stylesheet concern
  assert.ok(!els[0].data.label.includes("..."));
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

test("edges are still built from depends_on", () => {
  const els = loadGraph().buildElements([
    { id: "a", type: "DQL" }, { id: "b", type: "DQL", depends_on: ["a"] },
  ]);
  const edges = els.filter((e) => e.group === "edges");
  assert.equal(edges.length, 1);
  assert.deepEqual([edges[0].data.source, edges[0].data.target], ["a", "b"]);
});

test("a selected node is unmistakable — ring plus halo", () => {
  const style = styleFor(loadGraph().buildStylesheet(TOKENS), "node:selected");
  assert.ok(style["border-width"] >= 3);
  assert.equal(style["border-color"], TOKENS.nodeSelectedRing);
  // underlay, not overlay: an overlay paints over the node and dims its label.
  assert.ok(style["underlay-opacity"] > 0);
  assert.ok(style["underlay-padding"] > 0);
});

test("states are accents, not full fills", () => {
  const sheet = loadGraph().buildStylesheet(TOKENS);
  const accent = {
    running: TOKENS.nodeRunningAccent, success: TOKENS.nodeSuccessAccent,
    failed: TOKENS.nodeFailedAccent, aborted: TOKENS.nodeAbortedAccent,
  };
  Object.keys(accent).forEach((s) => {
    const style = styleFor(sheet, "node." + s);
    assert.equal(style["background-color"], undefined,
      `state ${s} must not repaint the card background`);
    // Assert the VALUE, not merely presence: a border-color left on the old
    // --node-*-bg token would satisfy a truthiness check and change nothing.
    assert.equal(style["border-color"], accent[s]);
  });
  assert.equal(styleFor(sheet, "node.aborted").opacity, 0.5);   // §6.2 already required this
});

test("the pulse is gated on the reduced-motion preference", () => {
  const graph = loadGraph();
  assert.equal(graph.pulseEnabled({ matches: true }), false);   // reduce → still
  assert.equal(graph.pulseEnabled({ matches: false }), true);
});
