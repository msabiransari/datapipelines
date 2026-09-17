// 149 — the node-operation reducer (node-ops.js): the reusable view model the node
// cards, the Details pane and 151's output connector read. PURE: every node_progress
// sample reduces to one operation view per node; stale/late samples are ignored;
// node and execution terminal events close what they must without inventing a
// commit; labels never fabricate a percentage.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const modulePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/node-ops.js");

function load() {
  delete require.cache[require.resolve(modulePath)];
  return require(modulePath);
}

function sample(over) {
  return Object.assign(
    {
      execution_id: "e",
      node_id: "stage_trips",
      attempt: 1,
      sequence: 1,
      operation: "stage",
      destination: { kind: "tempdb", table: "trips" },
      state: "executing",
      started_at: "2026-09-16T10:00:00.100Z",
      observed_at: "2026-09-16T10:00:00.350Z",
      elapsed_ms: 250,
      timings_ms: { connecting: 12, executing: 238 },
      batches_written: 0,
    },
    over
  );
}

test("a node_progress sample becomes the node's operation view", () => {
  const ops = load().createNodeOps();
  ops.reduce("execution_started", { execution_id: "e" });
  ops.reduce("node_started", { node_id: "stage_trips" });
  ops.reduce("node_progress", sample());
  const op = ops.get("stage_trips");
  assert.equal(op.kind, "stage");
  assert.equal(op.state, "executing");
  assert.deepEqual(op.destination, { kind: "tempdb", table: "trips" });
  assert.equal(op.rowsFetched, null);
  assert.equal(op.rowsWritten, null);
  assert.equal(op.elapsedMs, 250);
  assert.equal(op.terminal, false);
  assert.equal(op.committed, null);
  assert.equal(op.sequence, 1);
});

test("samples apply in sequence order; a late lower sequence is ignored", () => {
  const ops = load().createNodeOps();
  ops.reduce("node_progress", sample({ sequence: 3, state: "writing", rows_fetched: 12000, rows_written: 11000, batches_written: 11 }));
  ops.reduce("node_progress", sample({ sequence: 2, state: "fetching", rows_fetched: 6000, rows_written: 5000 }));
  const op = ops.get("stage_trips");
  assert.equal(op.sequence, 3);
  assert.equal(op.state, "writing");
  assert.equal(op.rowsWritten, 11000);
});

test("the terminal sample seals the operation: later samples and node events change nothing", () => {
  const ops = load().createNodeOps();
  ops.reduce("node_progress", sample({ sequence: 4, state: "completed", rows_fetched: 12453, rows_written: 12453, committed: true, batches_written: 13, timings_ms: { executing: 410, fetching: 3400, writing: 1457, finalizing: 9 } }));
  ops.reduce("node_progress", sample({ sequence: 5, state: "writing" }));
  ops.reduce("node_completed", { node_id: "stage_trips", rows_out: 12453 });
  const op = ops.get("stage_trips");
  assert.equal(op.state, "completed");
  assert.equal(op.terminal, true);
  assert.equal(op.committed, true);
  assert.equal(op.sequence, 4);
});

test("a node terminal event without a terminal sample closes the operation with committed UNKNOWN", () => {
  const ops = load().createNodeOps();
  ops.reduce("node_progress", sample({ sequence: 2, state: "writing", rows_written: 100 }));
  ops.reduce("node_completed", { node_id: "stage_trips", rows_out: 100 });
  const op = ops.get("stage_trips");
  assert.equal(op.state, "completed");
  assert.equal(op.terminal, true);
  assert.equal(op.committed, null);
  assert.equal(op.observed, false);
  const failed = load().createNodeOps();
  failed.reduce("node_progress", sample({ sequence: 2, state: "writing", rows_written: 100 }));
  failed.reduce("node_failed", { node_id: "stage_trips" });
  assert.equal(failed.get("stage_trips").state, "failed");
  assert.equal(failed.get("stage_trips").committed, null);
});

test("an execution terminal event aborts every open operation without claiming an observation", () => {
  const ops = load().createNodeOps();
  ops.reduce("node_progress", sample({ node_id: "a", sequence: 1, state: "writing", rows_written: 100 }));
  ops.reduce("node_progress", sample({ node_id: "b", sequence: 3, state: "completed", committed: true, rows_written: 5 }));
  ops.reduce("execution_aborted", { execution_id: "e", reason: "cancelled" });
  assert.equal(ops.get("a").state, "aborted");
  assert.equal(ops.get("a").committed, false);
  assert.equal(ops.get("a").observed, false);
  assert.equal(ops.get("a").rowsWritten, 100);
  assert.equal(ops.get("b").state, "completed");
  assert.equal(ops.get("b").committed, true);
});

test("execution_started resets the previous run's operations", () => {
  const ops = load().createNodeOps();
  ops.reduce("node_progress", sample({ sequence: 2, state: "writing" }));
  ops.reduce("execution_started", { execution_id: "e2" });
  assert.equal(ops.get("stage_trips"), null);
  assert.deepEqual(ops.all(), {});
});

test("describe: honest labels, counts, destination, phase share — never a percent", () => {
  const { createNodeOps, describe } = load();
  const ops = createNodeOps();
  ops.reduce("node_progress", sample({ sequence: 3, state: "writing", rows_fetched: 12000, rows_written: 11000, batches_written: 11, elapsed_ms: 5301, timings_ms: { connecting: 12, executing: 410, fetching: 3400, waiting_output: 22, writing: 1457 } }));
  const d = describe(ops.get("stage_trips"));
  assert.equal(d.stateLabel, "Writing");
  assert.equal(d.destinationText, "tempdb.trips");
  assert.equal(d.countsText, "12,000 fetched · 11,000 written");
  assert.equal(d.phaseText, "connect 12 ms · query 410 ms · fetch 3.4 s · wait 22 ms · write 1.5 s");
  assert.equal(d.commitText, null);
  assert.equal(d.percent, undefined);
  assert.equal(d.cardLine, "Writing");
  assert.equal(d.cardCounts, "11,000 written");
  assert.match(d.a11yText, /Writing to tempdb\.trips/);
});

test("describe: waiting for an output connection reads as waiting, not writing", () => {
  const { createNodeOps, describe } = load();
  const ops = createNodeOps();
  ops.reduce("node_progress", sample({ sequence: 2, state: "waiting_output", rows_fetched: 100 }));
  const d = describe(ops.get("stage_trips"));
  assert.equal(d.stateLabel, "Waiting for tempdb connection");
  assert.equal(d.cardLine, "Waiting for tempdb");
  assert.equal(d.cardCounts, "100 fetched");
});

test("describe: committed only when the terminal sample said so; rolled back names itself", () => {
  const { createNodeOps, describe } = load();
  const ops = createNodeOps();
  ops.reduce("node_progress", sample({ node_id: "wb", operation: "writeback", destination: { kind: "datasource", datasource: "pg", table: "out" }, sequence: 3, state: "failed", rows_written: 3000, committed: false, rolled_back: true }));
  const d = describe(ops.get("wb"));
  assert.equal(d.stateLabel, "Failed");
  assert.equal(d.commitText, "Not committed · rolled back");
  assert.equal(d.destinationText, "pg.out");
  const done = createNodeOps();
  done.reduce("node_progress", sample({ sequence: 4, state: "completed", rows_written: 42, committed: true }));
  assert.equal(describe(done.get("stage_trips")).commitText, "Committed · 42 rows");
  const unknown = createNodeOps();
  unknown.reduce("node_progress", sample({ sequence: 1, state: "executing" }));
  unknown.reduce("node_completed", { node_id: "stage_trips" });
  assert.equal(describe(unknown.get("stage_trips")).commitText, "Commit not observed");
});

test("describe: a CTAS is one combined step; a child names its execution; DDL writes nothing", () => {
  const { createNodeOps, describe } = load();
  const ops = createNodeOps();
  ops.reduce("node_progress", sample({ node_id: "c", operation: "ctas", sequence: 1, state: "executing" }));
  assert.equal(describe(ops.get("c")).stateLabel, "Querying and materializing (one statement)");
  ops.reduce("node_progress", sample({ node_id: "p", operation: "child", destination: { kind: "none" }, sequence: 1, state: "executing", child_execution_id: "11111111-2222-3333-4444-555555555555" }));
  const child = describe(ops.get("p"));
  assert.equal(child.stateLabel, "Child execution running");
  assert.equal(child.destinationText, "no output");
  assert.match(child.a11yText, /11111111/);
  ops.reduce("node_progress", sample({ node_id: "d", operation: "statement", destination: { kind: "none" }, sequence: 2, state: "completed", committed: undefined }));
  assert.equal(describe(ops.get("d")).countsText, null);
  assert.equal(describe(ops.get("d")).commitText, null);
});

test("describe: a node that only started has a started view and no invented state", () => {
  const { createNodeOps, describe } = load();
  const ops = createNodeOps();
  ops.reduce("node_started", { node_id: "x" });
  const d = describe(ops.get("x"));
  assert.equal(d.stateLabel, "Running");
  assert.equal(d.countsText, null);
  assert.equal(d.cardLine, null);
  assert.equal(d.cardCounts, null);
});
