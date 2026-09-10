// 080 §B — the dock's Details tab: the per-type meta rows and the non-SQL pane
// content (a CALCULATOR's evaluation, a PIPELINE node's child mapping), built by
// init.js's pure-ish helpers. The floor asks for Details content for each of the
// three node types; this drives pipelineEditor()'s own functions so the test and
// the template can never drift.
//
// Same loader convention as template-ref-text.test.mjs: the state literal builds
// the dock and events machines from their modules' globals, so those load first.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const main = (rel) => path.resolve(here, "../../main/resources/static/js/pipeline-editor/" + rel);

globalThis.window = {};
globalThis.document = {
  readyState: "complete",
  addEventListener: function () {},
  cookie: "",
  getElementById: function () { return null; },
  querySelectorAll: function () { return []; },
};
// The run clock's interval must not pin the test process open.
const realSetInterval = globalThis.setInterval;
globalThis.setInterval = () => 0;

require(main("dock.js"));
require(main("events.js"));
require(main("graph.js")); // PEGraphUtil (iconForType / typeToken / escapeHtml)
require(main("init.js"));
const editor = globalThis.window.pipelineEditor();
globalThis.setInterval = realSetInterval;

const DQL = {
  id: "trips_by_borough",
  type: "DQL",
  source: "sample-trips",
  template: { id: "nyc/mobility/trips_by_borough", version: 2 },
  output: { target: "tempdb", table: "trips_by_borough" },
};
const CALC = {
  id: "fiscal_quarter",
  type: "CALCULATOR",
  kind: "fiscal_quarter",
  context_key: "run_fiscal_quarter",
  inputs: { date: "$current_date", fiscal_start: "$org_fiscal_start_date" },
};
const CHILD = {
  id: "rainy_vs_dry",
  type: "PIPELINE",
  pipeline: { name: "nyc/mobility/rainy_vs_dry_ridership", version: 1 },
  parameters: { window_start: "${window_start}" },
  output: { target: "tempdb", table: "rainy_vs_dry" },
};

test("a DQL node's Details: source, template, output, parameters", () => {
  editor.parameters = { run_fiscal_quarter: { type: "STRING" } };
  editor.paramKeys = ["run_fiscal_quarter"];
  editor.pipeline = { settings: {} };
  editor.nodeStates = {};
  const meta = editor.detailsMeta(DQL);
  const map = Object.fromEntries(meta);
  assert.equal(map.Source, "sample-trips");
  assert.equal(map.Template, "nyc/mobility/trips_by_borough @ v2");
  assert.equal(map.Output, "tempdb → table trips_by_borough");
  assert.equal(map.Parameters, "run_fiscal_quarter");
  // 108 §A: a node that declares no timeout says WHERE the number comes from, so an author
  // reading a `pipeline.node.timeout` knows whether this node set the budget or the operator did.
  assert.equal(map.Timeout, "default (datapipelines.executor.node-timeout-seconds)");
  assert.equal(editor.isSqlNode(DQL), true, "a SQL-backed node fetches the rendered statement");
  assert.equal(editor.detailsSqlHead(DQL).includes("Rendered SQL"), true);
});

test("a CALCULATOR node's Details: kind, inputs with resolved values, writes, value", () => {
  editor.contextValues = { current_date: "2026-09-05", org_fiscal_start_date: "01-01" };
  editor.nodeValues = { fiscal_quarter: "2026-Q3" };
  const map = Object.fromEntries(editor.detailsMeta(CALC));
  assert.equal(map.Kind, "fiscal_quarter");
  assert.equal(
    map.Inputs,
    "date = $current_date → 2026-09-05 · fiscal_start = $org_fiscal_start_date → 01-01",
    "each input beside what the last run resolved it to",
  );
  assert.equal(map.Writes, "run_fiscal_quarter");
  assert.equal(map.Value, "2026-Q3");
  assert.equal(editor.isSqlNode(CALC), false);

  // The evaluation pane: the call, the resolved inputs as comments, the value.
  const html = editor.definitionHtml(CALC);
  assert.match(html, /fiscal_quarter\(/);
  assert.match(html, /date = \$current_date/);
  assert.match(html, /-- 2026-09-05/);
  assert.match(html, /→ run_fiscal_quarter = <span class="pe-sql-tok-parameter">&quot;2026-Q3&quot;<\/span>/);
});

test("a PIPELINE node's Details: child, parameter mapping, output, child execution id", () => {
  editor.childExecutions = { rainy_vs_dry: "a91f0c2e-1234" };
  const map = Object.fromEntries(editor.detailsMeta(CHILD));
  assert.equal(map.Child, "nyc/mobility/rainy_vs_dry_ridership @ v1");
  assert.equal(map.Parameters, "window_start ← ${window_start}");
  assert.equal(map.Output, "tempdb → table rainy_vs_dry");
  assert.equal(map.Execution, "a91f0c2e-1234 (child)");
  assert.equal(editor.isSqlNode(CHILD), false);

  const html = editor.definitionHtml(CHILD);
  assert.match(html, /runs nyc\/mobility\/rainy_vs_dry_ridership@v1 as a child execution/);
  assert.match(html, /window_start: <span class="pe-sql-tok-parameter">\$\{window_start\}<\/span>/);
  assert.match(html, /-- last child execution: a91f0c2e-1234/);
});

test("the Details pane's type accent and icon follow the node type", () => {
  assert.equal(editor.detailsTypeStyle(CALC), "--type:var(--type-calc);--type-bg:var(--type-calc-bg)");
  assert.equal(editor.detailsTypeStyle(DQL), "--type:var(--type-dql);--type-bg:var(--type-dql-bg)");
  assert.match(editor.detailsIcon(CHILD), /lucide-sprite\.svg#workflow/);
});

test("definitionHtml escapes everything — node bodies are user-authored", () => {
  const nasty = {
    id: "x",
    type: "CALCULATOR",
    kind: '"><script>alert(1)</script>',
    context_key: "k",
    inputs: { a: "$current_date" },
  };
  const html = editor.definitionHtml(nasty);
  assert.ok(!html.includes("<script>"), "raw markup must not survive into the pane");
});

test("logEvent appends every kind to the events log and resets it on execution_started", () => {
  editor.pipeline = { name: "demo" };
  editor.nodes = [];
  editor.nodesById = {};
  editor.eventsLog = globalThis.window.PEEvents.createEventsLog();
  editor.startRunClock = () => {}; // the clock is a DOM effect; stubbed here
  editor.logEvent("node_started", { node_id: "a" });
  assert.equal(editor.eventsLog.count(), 1);
  editor.logEvent("execution_started", { execution_id: "e1", parameters: {} });
  assert.equal(editor.eventsLog.count(), 1, "the new run reset the log, then appended its own first event");
  assert.equal(editor.eventsLog.events[0].kind, "execution_started");
});

test("a node's own settings.timeout_seconds shows on Details as this node's budget (108 §A)", () => {
  editor.parameters = {};
  editor.paramKeys = [];
  editor.pipeline = { settings: {} };
  editor.nodeStates = {};
  const withOwn = { ...DQL, settings: { timeout_seconds: 600 } };
  const map = Object.fromEntries(editor.detailsMeta(withOwn));
  assert.equal(map.Timeout, "600s (this node)");
});

test("a PIPELINE node with no timeout says the child's deadline bounds it, not a number (108 §A)", () => {
  editor.nodeStates = {};
  editor.childExecutions = {};
  const map = Object.fromEntries(editor.detailsMeta(CHILD));
  // The node deadline exempts a PIPELINE node that declares none — quoting the operator default
  // here would name a budget that never applies to it.
  assert.equal(map.Timeout, "child execution's own deadline");
});
