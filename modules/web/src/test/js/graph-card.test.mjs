// 080 §A — the v2 node CARD: the mock's anatomy (icon tile, id + type eyebrow,
// mono facts, footer with state dot + run numbers, ports, hover expand, progress
// line), driven from node DATA.
//
// Runs on Node's built-in runner (`node --test`, the 027b harness), same loader
// convention as graph-stylesheet / sse-node-failure: graph.js and sse.js are
// browser IIFEs publishing on `window` / module.exports, and the pure functions
// are driven with sentinel data. What is pinned here:
//
//   1. A `node_completed` frame POPULATES THE RUN NUMBERS — the event carries the
//      node's stats FLAT (`duration_ms`, `rows_out`, and `context_value` for a
//      CALCULATOR), and the footer's format is the mock's `rows · ms`.
//   2. The card's structure is the mock's: tile in the type accent pair, the id,
//      the uppercase eyebrow in the type colour, up to three mono facts, the
//      footer (state label · run), ports, the expand affordance, the progress line.
//   3. Every interpolated value is HTML-escaped (pipeline JSON is user-authored).
//   4. The 059b rule survives: exactly ONE glyph svg per card (the tile's) plus
//      the expand button's icon — fact lines carry NO icons (the vendored sprite
//      has no globe/brackets/arrow and is fenced; reusing #db would duplicate the
//      DQL tile — the trap 059b removed).

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

const DQL_CARD = {
  id: "trips_by_borough",
  type: "DQL",
  typeIcon: "db",
  state: "idle",
  run: null,
  facts: [
    { kind: "source", icon: "db", text: "sample-trips · POSTGRES" },
    { kind: "template", icon: "file", text: "nyc/mobility/trips_by_borough @ v2" },
    { kind: "output", icon: "table", text: "tempdb.trips_by_borough" },
  ],
};

test("a node_completed frame reaches the graph as run numbers — red where sse.js dropped them", () => {
  const SseHandler = loadSseHandler();
  const calls = [];
  const editor = {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    graph: {
      setNodeState(id, s) { calls.push(["state", id, s]); },
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
  assert.deepEqual(statsCall[2], { duration_ms: 1234, rows_out: 366, context_value: undefined });
});

test("formatRunLine: the mock's `rows · ms`, NOT_MEASURED honoured, a calculator shows its value", () => {
  const g = loadGraph();
  assert.equal(g.formatRunLine({ duration_ms: 1234, rows_out: 366 }), "366 rows · 1.2 s");
  assert.equal(g.formatRunLine({ duration_ms: 823, rows_out: -1 }), "823 ms");
  assert.equal(g.formatRunLine({ duration_ms: 74500, rows_out: 4480 }), "4,480 rows · 1m 15s");
  // rows without a duration (a wire that someday omits it) is still legal
  assert.equal(g.formatRunLine({ rows_out: 12 }), "12 rows");
  // A CALCULATOR's completion: the value it wrote, quoted
  assert.equal(g.formatRunLine({ duration_ms: 1, context_value: "2026-Q3" }), '= "2026-Q3" · 1 ms');
  assert.equal(g.formatRunLine(null), null);
  assert.equal(g.formatRunLine({ duration_ms: -1, rows_out: -1 }), null);
});

test("the card is the mock's anatomy: tile, id, eyebrow, facts, footer, ports, progress", () => {
  const g = loadGraph();
  const card = g.buildCardHtml(DQL_CARD);
  // The type accent pair rides inline — the tile and the eyebrow read it back.
  assert.match(card, /style="--type:var\(--type-dql\);--type-bg:var\(--type-dql-bg\)"/);
  assert.match(card, /class="pe-card-tile"><svg class="ds-icon ds-icon-sm"/, "the icon TILE carries the type glyph");
  assert.match(card, /class="pe-card-id"[^>]*>trips_by_borough</);
  assert.match(card, /class="pe-card-kind">dql</, "the type eyebrow, lowercase for the CSS uppercase");
  // Up to three mono facts.
  assert.equal((card.match(/class="pe-card-fact"/g) || []).length, 3);
  assert.match(card, /sample-trips · POSTGRES/);
  assert.match(card, /nyc\/mobility\/trips_by_borough @ v2/);
  assert.match(card, /tempdb\.trips_by_borough/);
  // The footer: state dot + label left, run numbers right — empty before any run.
  assert.match(card, /class="pe-card-st">Pending</);
  assert.match(card, /class="pe-card-rt"><\/span>/, "no run numbers before a completion — never a placeholder");
  // Ports, both edges; the progress line exists but is opacity-gated on running.
  assert.match(card, /pe-card-port-in/);
  assert.match(card, /pe-card-port-out/);
  assert.match(card, /class="pe-card-progress"/);
});

test("the footer's state label and run numbers follow the state", () => {
  const g = loadGraph();
  assert.match(g.buildCardHtml({ ...DQL_CARD, state: "running" }), /pe-card pe-card-running/);
  assert.match(g.buildCardHtml({ ...DQL_CARD, state: "running" }), /class="pe-card-st">Running…</);
  assert.match(g.buildCardHtml({ ...DQL_CARD, state: "success", run: "5 rows · 37 ms" }), /class="pe-card-st">Done</);
  assert.match(g.buildCardHtml({ ...DQL_CARD, state: "success", run: "5 rows · 37 ms" }), /class="pe-card-rt">5 rows · 37 ms</);
  assert.match(g.buildCardHtml({ ...DQL_CARD, state: "failed" }), /class="pe-card-st">Failed</);
  assert.match(g.buildCardHtml({ ...DQL_CARD, state: "aborted" }), /class="pe-card-st">Aborted</);
});

test("a 60-character hierarchical template name truncates from the LEFT, keeping the leaf", () => {
  const g = loadGraph();
  const leaf = "monthly_revenue_aggregation.sql"; // 31 chars
  const deep = "acme/finance/" + "x".repeat(60 - 14 - leaf.length) + "/" + leaf;
  assert.equal(deep.length, 60);
  const shown = g.truncateLeft(deep);
  assert.notEqual(shown, deep, "a 60-char name must not render whole");
  assert.ok(shown.startsWith("…"), "the ellipsis leads — the ancestry collapses, not the leaf");
  assert.ok(shown.endsWith(leaf), "the leaf stays visible");
  // buildElements seeds the template fact with the truncation and the FULL reference on title
  const els = g.buildElements([{ id: "n1", type: "DQL", source: "pg", template: { id: deep, version: 3 } }]);
  const fact = els[0].data.facts.find((f) => f.kind === "template");
  assert.equal(fact.text, "…/" + leaf + " @ v3");
  assert.equal(fact.title, deep + " @ v3", "the full path rides on the fact's title");
  // Short names pass through untouched
  assert.equal(g.truncateLeft("sample_trips_daily.sql"), "sample_trips_daily.sql");
});

test("the id is one line with a right-edge ellipsis — the mock's single-line title", () => {
  const css = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/css/pipeline-editor.css"),
    "utf8",
  );
  const block = css.slice(css.indexOf(".pe-card-id"));
  const rule = block.slice(0, block.indexOf("}"));
  assert.match(rule, /white-space:\s*nowrap/, "the id never wraps");
  assert.match(rule, /text-overflow:\s*ellipsis/, "…it ellipsises at the card's edge");
});

test("buildElements seeds the card facts: tempdb engine, PIPELINE child, dialect placeholder", () => {
  const g = loadGraph();
  const els = g.buildElements(
    [
      { id: "staged", type: "DQL", source: "tempdb", template: { id: "sample_calendar.sql", version: 1 } },
      { id: "child", type: "PIPELINE", source: "", pipeline: { name: "revenue_by_borough", version: 2 } },
      { id: "plain", type: "DQL", source: "sample-trips", template: { id: "t.sql", version: 1 } },
    ],
    { tempdb: { engine: "H2" } },
  );
  const sourceFact = (data) => (data.facts.find((f) => f.kind === "source") || {}).text;
  assert.equal(sourceFact(els[0].data), "tempdb · H2");
  assert.equal(els[0].data.state, "idle");
  assert.equal(els[0].data.run, null);
  assert.equal(sourceFact(els[1].data), "revenue_by_borough @ v2", "a PIPELINE card names the child pipeline");
  assert.equal(els[1].data.template, null);
  assert.equal(sourceFact(els[2].data), "sample-trips", "dialect lands later from the registry listing");
  assert.equal(els[2].data.sourceName, "sample-trips", "the registry lookup key survives for applyDialects");
  // The output fact: caller default on an output-less DQL, tempdb.table otherwise.
  const outFact = (data) => (data.facts.find((f) => f.kind === "output") || {}).text;
  assert.equal(outFact(els[0].data), "→ caller");
});

test("every card carries exactly one open-details button: sized icon, non-empty label, node id attached", () => {
  const g = loadGraph();
  const card = g.buildCardHtml({ id: "stage_daily_trips", type: "DQL", state: "idle", facts: [] });
  const buttons = card.match(/<button[^>]*class="pe-card-open"[^>]*>/g) || [];
  assert.equal(buttons.length, 1, "exactly one open button per card");
  const btn = buttons[0];
  assert.match(btn, /data-node-open="stage_daily_trips"/, "the node id rides on the button for the delegated handler");
  const label = /aria-label="([^"]*)"/.exec(btn);
  assert.ok(label && label[1].trim().length > 0, "the button has a non-empty aria-label");
  assert.match(label[1], /stage_daily_trips/, "…that names the node it opens");
  const inner = card.slice(card.indexOf(btn), card.indexOf("</button>", card.indexOf(btn)));
  assert.match(inner, /<svg class="ds-icon ds-icon-xs"/, "the button's glyph is explicitly sized (059b's rule)");
  // Escaping holds on the label too: ids come from user-authored pipeline JSON.
  const nasty = g.buildCardHtml({ id: '"><script>alert(1)</script>', type: "DQL", state: "idle", facts: [] });
  assert.ok(!nasty.includes("<script>"), "the aria-label is escaped like every other interpolated value");
});

test("exactly ONE glyph svg per card, PLUS the open-details button's icon — facts carry none (080 revises the count)", () => {
  const g = loadGraph();
  const card = g.buildCardHtml(DQL_CARD);
  assert.equal((card.match(/<svg/g) || []).length, 2, "the tile's type glyph + the expand button's icon — nothing else");
  const outsideButton = card.replace(/<button[^>]*class="pe-card-open"[\s\S]*?<\/button>/, "");
  assert.equal((outsideButton.match(/<svg/g) || []).length, 1, "exactly one GLYPH — the tile's type icon");
  // The seeded data carries no engine glyph slot; applyDialects upgrades TEXT only.
  const els = g.buildElements([{ id: "n", type: "DQL", source: "pg" }], {});
  assert.ok(!("engineIcon" in els[0].data), "no engine glyph key in the seeded card data");
  assert.equal(g.iconForType("DQL"), "db");
  assert.equal(g.iconForType("DML"), "table");
  assert.equal(g.iconForType("DDL"), "boxes");
  assert.equal(g.iconForType("PIPELINE"), "workflow");
  assert.equal(g.iconForType("CALCULATOR"), "calculator");
});

test("card values are HTML-escaped — ids and template paths come from user-authored pipeline JSON", () => {
  const g = loadGraph();
  const card = g.buildCardHtml({
    id: '"><script>alert(1)</script>',
    type: "DQL",
    state: "success",
    run: "1 ms · <script>rows</script>",
    facts: [{ kind: "template", icon: "file", text: 'a"/b.sql @ v1', title: 'a"/b.sql @ v1' }],
  });
  assert.ok(!card.includes("<script>"), "raw script markup must not survive into the card HTML");
  assert.ok(card.includes("&quot;"), "quote escaping closes the attribute-injection hole");
});

test("the edge curve is the mock's bezier — control offset max(60, dx/2), horizontal leave/enter", () => {
  const g = loadGraph();
  // Same-rank-y edge: both control points sit ON the line (distance 0).
  const flat = g.edgeControlPoints(132, 0, 500, 0);
  assert.deepEqual(flat.distances, [0, 0]);
  assert.ok(flat.weights[0] > 0 && flat.weights[0] <= 0.5, "the first control point extends from the SOURCE port");
  assert.ok(flat.weights[1] >= 0.5 && flat.weights[1] < 1, "the second comes back into the TARGET port");
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
  assert.equal(Math.round(c1x), sx + Math.max(60, dx / 2), "the mock's offset: max(60, dx/2) from the source port");
  assert.equal(Math.round(c2x), tx - Math.max(60, dx / 2), "…and back into the target port");
  // A SHORT edge floors the offset at 60 — the mock's max(60, dx/2).
  const short = g.edgeControlPoints(0, 0, 100, 0);
  assert.equal(Math.round(short.weights[0] * 100), 60, "a 100px edge still gets the 60px offset");
  assert.equal(g.edgeControlPoints(10, 10, 10, 10), null, "a zero-length edge has no curve to shape");
});

test("the layout breathes for cards, and fit is fitToView's job so the clamps can apply", () => {
  const opts = loadGraph().layoutOptions();
  assert.equal(opts.name, "dagre");
  assert.equal(opts.rankDir, "LR");
  assert.ok(opts.nodeSep >= 60, "cards are 236px wide — the old 50px separation would stack them");
  assert.ok(opts.rankSep >= 150, "ranks need card-width-plus-curve clearance");
  assert.equal(opts.fit, false, "layout must not fit — fitToView() applies the padding AND both clamps");
});

test("the editor page wires the icon system — icons.css linked, controls at md, never a bare svg", () => {
  const g = loadGraph();
  const tpl = fs.readFileSync(
    path.resolve(here, "../../main/resources/templates/pipelines/editor.html"),
    "utf8",
  );
  assert.ok(tpl.includes("/vendor/design-system/icons.css"), "the editor page must load icons.css");
  const iconsCss = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/vendor/design-system/icons.css"),
    "utf8",
  );
  assert.match(iconsCss, /\.ds-icon\s*\{[^}]*width:\s*var\(--icon-size/, "the .ds-icon rule carries the width");
  assert.match(iconsCss, /\.ds-icon\s*\{[^}]*height:\s*var\(--icon-size/, "…and the height");
  // The canvas controls are md glyphs (059b fix §3), four buttons.
  const controls = tpl.slice(tpl.indexOf("pe-graph-controls"), tpl.indexOf("pe-node-list"));
  assert.equal((controls.match(/ds-icon-md/g) || []).length, 4, "four control buttons at ds-icon-md");
  // Every svg the card template emits carries the class pair — never bare.
  const card = g.buildCardHtml({ ...DQL_CARD, state: "success", run: "1 ms" });
  const svgs = card.match(/<svg[^>]*>/g) || [];
  assert.ok(svgs.length >= 2, "a card carries the tile glyph and the open button's icon");
  svgs.forEach((s) => assert.match(s, /class="[^"]*ds-icon ds-icon-(xs|sm|md)[^"]*"/, `every svg is sized: ${s}`));
});

test("a CALCULATOR node's card facts are kind → context_key, and its eyebrow reads calculator", () => {
  const g = loadGraph();
  const els = g.buildElements(
    [
      {
        id: "fiscal_q",
        type: "CALCULATOR",
        kind: "fiscal_quarter",
        context_key: "run_fiscal_quarter",
        inputs: { date: "$current_date", fiscal_start: "$org_fiscal_start_date" },
        depends_on: [],
      },
    ],
    { tempdb: { engine: "H2" } },
  );

  assert.equal(els[0].classes.includes("calculator-node"), true, "the stylesheet and the a11y sweep read the class");
  const sourceFact = els[0].data.facts.find((f) => f.kind === "source");
  assert.equal(sourceFact.text, "fiscal_quarter → run_fiscal_quarter");
  assert.equal(els[0].data.template, null, "a calculator pins no template");
  assert.ok(!els[0].data.facts.some((f) => f.kind === "output"), "a calculator's output IS its context key — no output fact");

  const card = g.buildCardHtml(els[0].data);
  assert.match(card, /class="pe-card-kind">calculator</);
  assert.match(card, /--type:var\(--type-calc\)/, "the calc accent pair");
  assert.match(card, /fiscal_quarter → run_fiscal_quarter/);
  assert.match(card, /lucide-sprite\.svg#calculator/,
    "085 §B: the honest glyph — the `file` stand-in is retired with the full icon set");
  assert.equal(card.includes("lucide-sprite.svg#db"), false,
    "a calculator touches no database; #db is iconForType's fallback and would be actively misleading");
});

test("an UNKNOWN node type still renders a card rather than breaking the graph", () => {
  const g = loadGraph();
  // The forward-compatibility property, pinned. A pipeline body can outlive this editor
  // build, and the failure mode must be a plain card, never a throw or an empty box.
  const els = g.buildElements([{ id: "future", type: "HTTP", depends_on: [] }], {});
  const card = g.buildCardHtml(els[0].data);
  assert.match(card, /class="pe-card-kind">http</);
  assert.match(card, /lucide-sprite\.svg#db/, "the documented fallback glyph");
  assert.match(card, /pe-card-port-in/);
});

test("a node_completed frame for a calculator records its value and merges it into the Context", () => {
  const SseHandler = loadSseHandler();
  const editor = {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    contextValues: {},
    nodeValues: {},
    graph: { setNodeState() {}, setNodeStats() {}, resetAll() {} },
    setBanner() {},
    announceStatus() {},
  };
  const handler = new SseHandler(editor);

  handler.dispatch(
    "execution_started",
    JSON.stringify({ execution_id: "e1", parameters: { org_fiscal_start_date: "09-15", current_date: "2026-08-14" } }),
  );
  handler.dispatch(
    "node_completed",
    JSON.stringify({ node_id: "fiscal_q", duration_ms: 2, rows_out: 0, context_key: "run_fiscal_quarter", context_value: "4" }),
  );

  assert.equal(editor.nodeValues.fiscal_q, "4", "the Details pane's Value row reads this");
  assert.equal(
    editor.contextValues.run_fiscal_quarter,
    "4",
    "and a LATER node's $run_fiscal_quarter resolves on screen the way it resolved in the run",
  );
  assert.equal(editor.contextValues.org_fiscal_start_date, "09-15", "execution_started seeded the org tier");
});

test("a node_completed frame for a PIPELINE node records the child execution it spawned", () => {
  const SseHandler = loadSseHandler();
  const editor = {
    isExecuting: true,
    nodeStates: {},
    nodeErrors: {},
    childExecutions: {},
    graph: { setNodeState() {}, setNodeStats() {} },
    setBanner() {},
    announceStatus() {},
  };
  new SseHandler(editor).dispatch(
    "node_completed",
    JSON.stringify({ node_id: "rainy_vs_dry", duration_ms: 1310, rows_out: 2, child_execution_id: "a91f0c2e-1234" }),
  );
  assert.equal(editor.childExecutions.rainy_vs_dry, "a91f0c2e-1234", "the Details pane's Execution row reads this");
});
