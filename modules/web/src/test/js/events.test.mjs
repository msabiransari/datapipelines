// 080 §B — the dock's Events tab: per-kind text (the mock's copy), the arrival-
// order log with its live count, the t0-relative timestamps, and the auto-scroll
// pin. PURE module (events.js), so `node --test` owns every row.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const eventsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/events.js");

function loadEvents() {
  delete require.cache[require.resolve(eventsPath)];
  return require(eventsPath);
}

const CTX = {
  pipelineName: "nyc/mobility/mobility_briefing",
  nodeCount: 5,
  nodesById: {
    trips_by_borough: { id: "trips_by_borough", type: "DQL", source: "sample-trips", template: { id: "nyc/mobility/trips_by_borough", version: 2 }, output: { target: "tempdb", table: "trips_by_borough" } },
    fiscal_quarter: { id: "fiscal_quarter", type: "CALCULATOR", kind: "fiscal_quarter", context_key: "run_fiscal_quarter" },
    rainy_vs_dry: { id: "rainy_vs_dry", type: "PIPELINE", pipeline: { name: "nyc/mobility/rainy_vs_dry_ridership", version: 1 } },
  },
  outputText: (n) => (n.output && n.output.target === "tempdb" ? "tempdb." + n.output.table : "caller"),
};

test("execution_started: name@version, the short execution id, node and parameter counts", () => {
  const ev = loadEvents();
  const view = ev.formatEvent("execution_started", {
    execution_id: "7c1e93ab-0000-0000-0000-000000000000",
    pipeline_version: 3,
    parameters: { a: 1, b: 2 },
  }, CTX);
  assert.equal(view.node, null);
  assert.equal(view.text, "nyc/mobility/mobility_briefing@v3 · execution 7c1e93ab · 5 nodes · 2 parameters bound");
});

test("node_started: rendered template + source for SQL, evaluating for a calculator, spawning for a child", () => {
  const ev = loadEvents();
  assert.equal(
    ev.formatEvent("node_started", { node_id: "trips_by_borough" }, CTX).text,
    "rendered nyc/mobility/trips_by_borough, executing on sample-trips",
  );
  assert.equal(
    ev.formatEvent("node_started", { node_id: "fiscal_quarter" }, CTX).text,
    "evaluating fiscal_quarter",
  );
  assert.equal(
    ev.formatEvent("node_started", { node_id: "rainy_vs_dry" }, CTX).text,
    "spawning child nyc/mobility/rainy_vs_dry_ridership@v1",
  );
  // …and every node_started names its node in the row's node column.
  assert.equal(ev.formatEvent("node_started", { node_id: "fiscal_quarter" }, CTX).node, "fiscal_quarter");
});

test("node_completed: rows → output for SQL, key = value for a calculator, child id for a pipeline", () => {
  const ev = loadEvents();
  const sql = ev.formatEvent("node_completed", { node_id: "trips_by_borough", duration_ms: 842, rows_out: 5 }, CTX);
  assert.equal(sql.text, "5 rows → tempdb.trips_by_borough");
  assert.equal(sql.duration, "842 ms");

  const calc = ev.formatEvent("node_completed", { node_id: "fiscal_quarter", duration_ms: 1, context_key: "run_fiscal_quarter", context_value: "2026-Q3" }, CTX);
  assert.equal(calc.text, 'run_fiscal_quarter = "2026-Q3"');

  const child = ev.formatEvent("node_completed", { node_id: "rainy_vs_dry", duration_ms: 1310, rows_out: 2, child_execution_id: "a91f0c2e-0000" }, CTX);
  assert.equal(child.text, "child a91f0c2e completed · 2 rows");
  assert.equal(child.duration, "1.3 s");
});

test("node_failed: the code leads, the message follows, the duration rides along", () => {
  const ev = loadEvents();
  const view = ev.formatEvent("node_failed", {
    node_id: "rainy_vs_dry",
    duration_ms: 1310,
    error: { code: "pipeline.node.child_execution_failed", message: "child a91f0c2e failed at join_weather" },
  }, CTX);
  assert.equal(view.node, "rainy_vs_dry");
  assert.equal(view.text, "pipeline.node.child_execution_failed — child a91f0c2e failed at join_weather");
  assert.equal(view.duration, "1.3 s");
});

test("data_ready: caller result rows · columns; the terminal events carry the summary", () => {
  const ev = loadEvents();
  assert.equal(
    ev.formatEvent("data_ready", { row_count: 5, total_rows: 5, schema: [1, 2, 3, 4, 5] }, CTX).text,
    "caller result available — 5 rows · 5 columns",
  );
  const done = ev.formatEvent("pipeline_completed", { execution_id: "7c1e93ab-x", duration_ms: 2300, node_stats: { a: {}, b: {} } }, CTX);
  assert.equal(done.text, "execution 7c1e93ab completed — 2 nodes");
  assert.equal(done.duration, "2.3 s");
  const failed = ev.formatEvent("pipeline_failed", { execution_id: "7c1e93ab-x", duration_ms: 2500, failed_node_id: "rainy_vs_dry", error: { code: "pipeline.node.child_execution_failed" } }, CTX);
  assert.equal(failed.text, "execution 7c1e93ab failed — pipeline.node.child_execution_failed at rainy_vs_dry");
  assert.equal(
    ev.formatEvent("execution_aborted", { reason: "user_requested" }, CTX).text,
    "execution aborted — user_requested",
  );
});

test("a payload with missing fields degrades the sentence, never throws", () => {
  const ev = loadEvents();
  assert.doesNotThrow(() => ev.formatEvent("node_started", { node_id: "ghost" }, CTX));
  assert.doesNotThrow(() => ev.formatEvent("node_completed", { node_id: "ghost" }, CTX));
  assert.doesNotThrow(() => ev.formatEvent("execution_started", {}, CTX));
  assert.equal(ev.formatEvent("node_failed", { node_id: "n" }, CTX).text, "failed");
  assert.equal(ev.formatEvent("some_future_kind", {}, CTX).text, "some_future_kind");
});

test("the log: arrival order, t0-relative offsets, the live count, reset on a new run", () => {
  const ev = loadEvents();
  const log = ev.createEventsLog();
  log.reset(10_000);
  const e1 = log.append("execution_started", { node: null, text: "…" }, 10_000);
  const e2 = log.append("node_started", { node: "a", text: "…", duration: "" }, 11_203);
  assert.equal(e1.seq, 0);
  assert.equal(e1.offsetMs, 0);
  assert.equal(e2.seq, 1);
  assert.equal(e2.offsetMs, 1203, "the timestamp column is t0-relative");
  assert.equal(log.count(), 2, "the tab badge reads this");

  log.reset(20_000);
  assert.equal(log.count(), 0, "a new run empties the timeline");
  assert.equal(log.userPinned, false, "…and re-arms the auto-scroll");
  const e3 = log.append("execution_started", { text: "…" }, 21_500);
  assert.equal(e3.offsetMs, 1500);
});

test("offsetText renders the mock's +t.tttS column", () => {
  const ev = loadEvents();
  assert.equal(ev.offsetText(0), "+0.000s");
  assert.equal(ev.offsetText(1203), "+1.203s");
  assert.equal(ev.offsetText(23000), "+23.000s");
});

test("the auto-scroll pin: set by the scroll listener, read by the appender", () => {
  const log = loadEvents().createEventsLog();
  assert.equal(log.userPinned, false);
  log.setPinned(true);
  assert.equal(log.userPinned, true, "the user scrolled up — the tail stops chasing");
  log.setPinned(false);
  assert.equal(log.userPinned, false, "back at the bottom — following resumes");
});
