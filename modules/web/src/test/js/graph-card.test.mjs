// 059 — the node CARD: the facts inside the box, the stats the wire already carried.
//
// Runs on Node's built-in runner (`node --test`, the 027b harness), same loader
// convention as graph-stylesheet.test.mjs / sse-node-failure.test.mjs: graph.js and
// sse.js are browser IIFEs publishing on `window` / module.exports, and the pure
// functions are driven with sentinel data. What is pinned here, per the 059 exit
// gates:
//
//   1. A `node_completed` frame POPULATES THE RUN LINE — the event carries the node's
//      stats FLAT (`duration_ms`, `rows_out`; SseEventProjection), and the pre-059
//      `node_completed` branch dropped them. This test is RED on that sse.js.
//   2. A 60-character hierarchical template name truncates from the LEFT, keeping the
//      leaf (043 made names paths; right-ellipsis hides exactly the identifying part).
//   3. The name wraps to two lines and ellipsises only after that (the CSS clamp class
//      + the full name on `title`).
//   4. The card is built from node DATA — state dot, ports, badge — and every
//      interpolated value is HTML-escaped (names come from user-authored pipeline JSON).
//
// 059b — the icon-sizing round, on the same harness:
//
//   5. The page WIRES the icon system: editor.html links icons.css, icons.css
//      sizes .ds-icon from --icon-* tokens, and every <svg> the card template
//      emits carries the class pair — never bare. RED on 5187efd, where the
//      classes were present but the stylesheet was never loaded and every svg
//      fell to the 300×150 replaced-element default.
//   6. EXACTLY ONE glyph svg per card. 5187efd drew #db twice on every
//      db-backed card (the type glyph AND the engine glyph are the same
//      database drawing); the engine's identity is the source line's TEXT.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

function loadSseHandler() {
  globalThis.window = {};
  delete require.cache[require.resolve(ssePath)];
  require(ssePath);
  return globalThis.window.SseHandler;
}

test("a node_completed frame with stats reaches the graph as a run line — red where sse.js dropped them", () => {
  const SseHandler = loadSseHandler();
  const calls = [];
  const editor = {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    graph: {
      setNodeState(id, s) { calls.push(["state", id, s]); },
      setEdgesToNodeActive() {},
      setEdgesFromNodeActive() {},
      setNodeStats(id, stats) { calls.push(["stats", id, stats]); },
    },
    setBanner() {},
    showError() {},
    announceStatus() {},
  };
  const handler = new SseHandler(editor);
  handler.dispatch("node_completed", JSON.stringify({
    execution_id: "e1",
    node_id: "stage_daily_trips",
    duration_ms: 1234,
    rows_out: 366,
    bytes_out: 20480,
  }));
  const statsCall = calls.find((c) => c[0] === "stats");
  assert.ok(statsCall, "node_completed never delivered the stats — the card's run line stays empty");
  assert.equal(statsCall[1], "stage_daily_trips");
  assert.deepEqual(statsCall[2], { duration_ms: 1234, rows_out: 366 });
});

test("formatRunLine: elapsed first, rows second, NOT_MEASURED honoured, empty is null", () => {
  const g = loadGraph();
  assert.equal(g.formatRunLine({ duration_ms: 1234, rows_out: 366 }), "1.2 s · 366 rows");
  assert.equal(g.formatRunLine({ duration_ms: 823, rows_out: -1 }), "823 ms");
  assert.equal(g.formatRunLine({ duration_ms: 74500, rows_out: 4480 }), "1m 15s · 4480 rows");
  // rows without a duration (a wire that someday omits it) is still legal
  assert.equal(g.formatRunLine({ rows_out: 12 }), "12 rows");
  assert.equal(g.formatRunLine(null), null);
  assert.equal(g.formatRunLine({ duration_ms: -1, rows_out: -1 }), null);
});

test("the card renders the run line only after a completion carried stats — never a placeholder", () => {
  const g = loadGraph();
  const before = g.buildCardHtml({ id: "n1", label: "stage_trips", type: "DQL", state: "idle", run: null });
  assert.ok(!before.includes("pe-card-run"), "an idle card must not render a run line or its placeholder");
  assert.ok(!/rows|ms|s ·/.test(before), "no timing debris on an idle card");

  const after = g.buildCardHtml({ id: "n1", label: "stage_trips", type: "DQL", state: "success", run: "1.2 s · 366 rows" });
  assert.match(after, /class="pe-card-run">1\.2 s · 366 rows</);
});

test("a 60-character hierarchical template name truncates from the LEFT, keeping the leaf", () => {
  const g = loadGraph();
  const leaf = "monthly_revenue_aggregation.sql"; // 31 chars
  const deep = "acme/finance/" + "x".repeat(60 - 14 - leaf.length) + "/" + leaf; // 13 + 15 + 1 + 31
  assert.equal(deep.length, 60);
  const shown = g.truncateLeft(deep);
  assert.notEqual(shown, deep, "a 60-char name must not render whole");
  assert.ok(shown.startsWith("…"), "the ellipsis leads — the ancestry collapses, not the leaf");
  assert.ok(shown.endsWith(leaf), "the leaf stays visible");
  // The card's template line carries the truncation and the FULL reference on title
  const card = g.buildCardHtml({ id: "n1", label: "n1", type: "DQL", state: "idle", template: { id: deep, version: 3 } });
  assert.ok(card.includes(`…/${leaf} @ v3`), "the truncated path plus version is what renders");
  assert.ok(card.includes(`title="${deep} @ v3"`), "the full path rides on title");
  // Short names pass through untouched
  assert.equal(g.truncateLeft("sample_trips_daily.sql"), "sample_trips_daily.sql");
});

test("the name wraps to at most two lines — the clamp class carries the ellipsis, title the full text", () => {
  const g = loadGraph();
  const long = "a_very_long_node_display_name_that_will_wrap_past_two_lines_at_body_size";
  const card = g.buildCardHtml({ id: "n1", label: long, type: "DQL", state: "idle" });
  assert.match(card, /class="pe-card-title"/, "the title element exists");
  assert.ok(card.includes(`title="${long}"`), "the full name rides on title");
  // The clamp itself is a STYLESHEET concern (the card HTML stays structural); pin it
  // where it lives, against the shipped file, so a refactor cannot lose the two-line
  // rule silently.
  const css = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/css/pipeline-editor.css"),
    "utf8",
  );
  const titleBlock = css.slice(css.indexOf(".pe-card-title"));
  assert.match(titleBlock.slice(0, titleBlock.indexOf("}")), /-webkit-line-clamp:\s*2/, "the ellipsis is what a third line would have been");
});

test("buildElements seeds the card data: tempdb engine, PIPELINE child name, dialect placeholder", () => {
  const g = loadGraph();
  const els = g.buildElements(
    [
      { id: "staged", type: "DQL", source: "tempdb", template: { id: "sample_calendar.sql", version: 1 } },
      { id: "child", type: "PIPELINE", source: "", pipeline: { name: "revenue_by_borough", version: 2 } },
      { id: "plain", type: "DQL", source: "sample-trips", template: { id: "t.sql", version: 1 } },
    ],
    { tempdb: { engine: "H2" } },
  );
  assert.equal(els[0].data.sourceLabel, "tempdb · H2");
  assert.equal(els[0].data.state, "idle");
  assert.equal(els[0].data.run, null);
  assert.equal(els[1].data.sourceLabel, "revenue_by_borough", "a PIPELINE card names the child pipeline");
  assert.equal(els[1].data.template, null);
  assert.equal(els[2].data.sourceLabel, "sample-trips", "dialect lands later from the registry listing");
  assert.equal(els[2].data.sourceName, "sample-trips", "the registry lookup key survives for applyDialects");
});

test("the card's status dot reads state from data — check/x/minus glyphs, spinner for running, none for idle", () => {
  const g = loadGraph();
  const base = { id: "n1", label: "n1", type: "DQL" };
  assert.match(g.buildCardHtml({ ...base, state: "success" }), /pe-card-dot-success[^>]*>.*#check/s);
  assert.match(g.buildCardHtml({ ...base, state: "failed" }), /pe-card-dot-failed[^>]*>.*#x/s);
  assert.match(g.buildCardHtml({ ...base, state: "aborted" }), /pe-card-dot-aborted[^>]*>.*#minus/s);
  assert.match(g.buildCardHtml({ ...base, state: "running" }), /pe-card-dot-running/);
  assert.ok(!g.buildCardHtml({ ...base, state: "idle" }).includes("pe-card-dot"), "idle has no dot");
  // Ports: one in, one out, on every card
  assert.match(g.buildCardHtml({ ...base, state: "idle" }), /pe-card-port-in/);
  assert.match(g.buildCardHtml({ ...base, state: "idle" }), /pe-card-port-out/);
});

test("card values are HTML-escaped — names and template paths come from user-authored pipeline JSON", () => {
  const g = loadGraph();
  const card = g.buildCardHtml({
    id: '"><script>alert(1)</script>',
    label: "<b>name & 'quotes'</b>",
    type: "DQL",
    state: "success",
    run: "1.2 s · <script>rows</script>",
    template: { id: 'a"/b.sql', version: 1 },
  });
  assert.ok(!card.includes("<script>"), "raw script markup must not survive into the card HTML");
  assert.ok(!card.includes("<b>"), "raw markup in the name must be escaped");
  assert.ok(card.includes("&lt;b&gt;"), "the name renders, escaped");
  assert.ok(card.includes("&quot;"), "quote escaping closes the attribute-injection hole");
});

test("the edge curve leaves and enters horizontally — control points from post-layout positions", () => {
  const g = loadGraph();
  // Same-rank-y edge: both control points sit ON the line (distance 0), between the ports
  const flat = g.edgeControlPoints(132, 0, 500, 0);
  assert.deepEqual(flat.distances, [0, 0]);
  assert.ok(flat.weights[0] > 0 && flat.weights[0] < 0.5, "the first control point extends from the SOURCE port");
  assert.ok(flat.weights[1] > 0.5 && flat.weights[1] < 1, "the second comes back into the TARGET port");
  // A drop between ranks: the control points stay at the ports' y — horizontal leave/enter.
  // Reconstructing ctrl from (weight, distance): point = S + w·(T−S) + d·perp(T−S)/|T−S|.
  const sx = 132, sy = 100, tx = 500, ty = 300;
  const cp = g.edgeControlPoints(sx, sy, tx, ty);
  const dx = tx - sx, dy = ty - sy, len = Math.hypot(dx, dy);
  const reconstruct = (w, d) => [sx + w * dx + (d * dy) / len, sy + w * dy - (d * dx) / len];
  const [c1x, c1y] = reconstruct(cp.weights[0], cp.distances[0]);
  const [c2x, c2y] = reconstruct(cp.weights[1], cp.distances[1]);
  assert.equal(Math.round(c1y), sy, "control point 1 sits at the source port's y — the curve leaves horizontally");
  assert.equal(Math.round(c2y), ty, "control point 2 sits at the target port's y — the curve enters horizontally");
  assert.ok(c1x > sx, "control point 1 extends rightward from the source port");
  assert.ok(c2x < tx, "control point 2 extends leftward into the target port");
  assert.equal(g.edgeControlPoints(10, 10, 10, 10), null, "a zero-length edge has no curve to shape");
});

test("the layout breathes for cards, and fit is fitToView's job so the readable floor can clamp", () => {
  const opts = loadGraph().layoutOptions();
  assert.equal(opts.name, "dagre");
  assert.equal(opts.rankDir, "LR");
  assert.ok(opts.nodeSep >= 60, "cards are 264px wide — the old 50px separation would stack them");
  assert.ok(opts.rankSep >= 150, "ranks need card-width-plus-curve clearance");
  assert.equal(opts.fit, false, "layout must not fit — fitToView() applies the padding AND the min-zoom floor");
  assert.ok(loadGraph().FIT_MIN_ZOOM >= 0.5, "the fit floor keeps three nodes filling the pane");
});

test("the editor page wires the icon system — icons.css linked, toolbar at md, never a bare svg", () => {
  const g = loadGraph();
  const tpl = fs.readFileSync(
    path.resolve(here, "../../main/resources/templates/pipelines/editor.html"),
    "utf8",
  );
  // Three layers, each of which 5187efd got wrong or left unwired: the page
  // loads the stylesheet, the stylesheet sizes .ds-icon, the tokens give the
  // sizes pixels. Any one missing and the svgs render at the 300×150 default.
  assert.ok(tpl.includes("/vendor/design-system/icons.css"), "the editor page must load icons.css");
  const iconsCss = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/vendor/design-system/icons.css"),
    "utf8",
  );
  assert.match(iconsCss, /\.ds-icon\s*\{[^}]*width:\s*var\(--icon-size/, "the .ds-icon rule carries the width");
  assert.match(iconsCss, /\.ds-icon\s*\{[^}]*height:\s*var\(--icon-size/, "…and the height");
  const tokensCss = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/vendor/design-system/tokens.css"),
    "utf8",
  );
  assert.match(tokensCss, /--icon-md:\s*\d+px/, "the size classes resolve to real px");
  // The toolbar is a row of md glyphs at the canvas's top-right (059b fix §3).
  const controls = tpl.slice(tpl.indexOf("pe-graph-controls"), tpl.indexOf("pe-node-list"));
  assert.equal((controls.match(/ds-icon-md/g) || []).length, 4, "four toolbar buttons at ds-icon-md");
  assert.ok(!controls.includes("ds-icon-sm"), "the toolbar no longer uses sm glyphs");
  // Every svg the card template emits carries the class pair — never bare.
  const card = g.buildCardHtml({ id: "n1", label: "n1", type: "DQL", state: "success", run: "1 ms", sourceLabel: "s · P" });
  const svgs = card.match(/<svg[^>]*>/g) || [];
  assert.ok(svgs.length >= 2, "a success card carries the glyph svg and the dot svg");
  svgs.forEach((s) => assert.match(s, /class="[^"]*ds-icon ds-icon-(xs|sm|md)[^"]*"/, `every svg is sized: ${s}`));
});

test("exactly ONE glyph svg per card — the engine line is text, the db glyph no longer paints twice", () => {
  const g = loadGraph();
  const card = g.buildCardHtml({ id: "n1", label: "stage_trips", type: "DQL", state: "idle", sourceLabel: "sample-trips · POSTGRES" });
  assert.equal((card.match(/<svg/g) || []).length, 1, "an idle card carries exactly one svg — the type glyph");
  const sourceAt = card.indexOf("pe-card-source");
  const sourceLine = sourceAt >= 0 ? card.slice(sourceAt, card.indexOf("</div>", sourceAt)) : "";
  assert.ok(sourceAt >= 0, "the source line renders");
  assert.ok(!sourceLine.includes("<svg"), "the source line is text-only");
  // The seeded data no longer carries an engine glyph, and applyDialects
  // upgrades the LABEL only — there is no second glyph slot to fill.
  const els = g.buildElements([{ id: "n", type: "DQL", source: "pg" }], {});
  assert.ok(!("engineIcon" in els[0].data), "no engine glyph key in the seeded card data");
  assert.equal(g.iconForType("DQL"), "db");
  assert.equal(g.iconForType("DML"), "table");
  assert.equal(g.iconForType("DDL"), "boxes");
  assert.equal(g.iconForType("PIPELINE"), "workflow");
});
