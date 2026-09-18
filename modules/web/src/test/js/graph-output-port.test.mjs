// 151 (#127) — the OUTPUT PORT: a node's one configured output destination, on its own
// card, carrying 149's MEASURED write. The arrows out of a node are orderings
// (graph-dependency-edges.test.mjs); this row is where "what did this node write, and
// where" lives — once per node, never per consumer.
//
// Pinned here, per the lane's visual/state model:
//   1. CONFIGURED PORT — `nodeCardData` derives one port from the node's shape exactly
//      as the server's NodeOperations.operationFor does: DQL → tempdb/datasource/caller;
//      DML → its source (no table: no SQL lineage); PIPELINE → its declared output;
//      DDL, an output-less PIPELINE and CALCULATOR → NO port (no row write to show).
//   2. MEASURED STATES — `describe(op).port` (node-ops.js, the 149 view model) maps the
//      reducer's state to a port state + one honest line: waiting is not writing,
//      fetching is not writing, CTAS keeps its combined label, a completion without a
//      terminal sample says "commit not observed", zero rows says "0 rows".
//   3. THE CARD — exactly one `.pe-port` per node with an output, class `pe-port-<state>`,
//      the flow element only while WRITING, an aria-label usable without colour.
//   4. STREAM LOSS — `markStreamLost` freezes every live indicator (`stale`) without
//      inventing an outcome; resetAll returns the port to idle.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");
const opsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/node-ops.js");

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}
function loadOps() {
  delete require.cache[require.resolve(opsPath)];
  return require(opsPath);
}

function cardDataFor(node, settings) {
  const { buildElements } = loadGraph();
  return buildElements([node], settings).find((e) => e.group === "nodes" && e.data.id === node.id).data;
}

/* ------------------------------------------------------- configured port */

test("nodeCardData: the port mirrors the server's node-shape → destination mapping", () => {
  const port = (n, settings) => cardDataFor(n, settings).output;
  assert.deepEqual(port({ id: "s", type: "DQL", source: "pg", output: { target: "tempdb", table: "stg" } }),
    { kind: "tempdb", table: "stg", text: "tempdb.stg" });
  assert.deepEqual(port({ id: "w", type: "DQL", source: "pg", output: { target: "datasource", datasource: "warehouse", table: "facts" } }),
    { kind: "datasource", datasource: "warehouse", table: "facts", text: "warehouse.facts" });
  assert.deepEqual(port({ id: "c", type: "DQL", source: "tempdb", output: { target: "caller" } }), { kind: "caller", text: "caller" });
  assert.deepEqual(port({ id: "c2", type: "DQL", source: "tempdb" }), { kind: "caller", text: "caller" }, "an omitted output block is the caller (contract §4.7)");
  // A DML statement writes INTO its source; no table is inferred from the SQL.
  assert.deepEqual(port({ id: "u", type: "DML", source: "pg" }), { kind: "datasource", datasource: "pg", text: "pg" });
  assert.deepEqual(port({ id: "u2", type: "DML", source: "tempdb" }), { kind: "tempdb", text: "tempdb" });
  // A PIPELINE node's port is the output it declared for the child's caller rows.
  assert.deepEqual(port({ id: "p", type: "PIPELINE", pipeline: { name: "child", version: 1 }, output: { target: "tempdb", table: "child_rows" } }),
    { kind: "tempdb", table: "child_rows", text: "tempdb.child_rows" });
});

test("nodeCardData: DDL, an output-less PIPELINE and a CALCULATOR have NO port — nothing they do is a row write", () => {
  assert.equal(cardDataFor({ id: "d", type: "DDL", source: "tempdb" }).output, null);
  assert.equal(cardDataFor({ id: "p", type: "PIPELINE", pipeline: { name: "child", version: 1 } }).output, null);
  assert.equal(cardDataFor({ id: "k", type: "CALCULATOR", kind: "fiscal_quarter", context_key: "q" }).output, null);
});

test("nodeCardData: the port REPLACES the output fact line — the destination appears once on the card", () => {
  const data = cardDataFor({ id: "s", type: "DQL", source: "pg", output: { target: "tempdb", table: "stg" } });
  assert.ok(!data.facts.some((f) => f.kind === "output"), "no separate output fact");
  assert.equal(data.facts.filter((f) => f.kind === "source").length, 1, "the source fact stays");
});

/* --------------------------------------------------------- measured view */

function sampled(kind, state, extra) {
  const ops = loadOps().createNodeOps();
  ops.reduce("node_started", { node_id: "n" });
  ops.reduce("node_progress", Object.assign({ node_id: "n", sequence: 1, operation: kind, state, destination: { kind: "tempdb", table: "stg" } }, extra || {}));
  return loadOps().describe(ops.get("n")).port;
}

test("describe().port: the reducer's states map to the port's honest states", () => {
  assert.deepEqual(sampled("stage", "executing"), { state: "pending", text: "", kindLabel: "stage", a11y: "Output to tempdb.stg: not writing yet" });
  assert.equal(sampled("stage", "fetching", { rows_fetched: 500 }).state, "pending", "fetching is not writing");
  assert.deepEqual(sampled("stage", "waiting_output"), { state: "waiting", text: "waiting for tempdb connection", kindLabel: "stage", a11y: "Output to tempdb.stg: waiting for tempdb connection" });
  assert.deepEqual(sampled("stage", "writing", { rows_written: 11000 }), { state: "writing", text: "writing · 11,000 written", kindLabel: "stage", a11y: "Output to tempdb.stg: writing, 11,000 written" });
  assert.equal(sampled("stage", "fetching", { rows_written: 11000 }).text, "11,000 written", "between batches the count stays, the flow does not");
  assert.deepEqual(sampled("ctas", "executing"), { state: "combined", text: "one statement", kindLabel: "one statement", a11y: "Output to tempdb.stg: querying and materializing in one statement" });
  assert.equal(sampled("writeback", "finalizing", { rows_written: 9 }).text, "committing · 9 written");
  assert.equal(sampled("stage", "finalizing", { rows_written: 9 }).text, "finalizing · 9 written");
});

test("describe().port: terminal states — committed, rolled back, unknown, zero rows", () => {
  assert.deepEqual(sampled("stage", "completed", { rows_written: 128026, committed: true }),
    { state: "done", text: "committed · 128,026 rows", kindLabel: "stage", a11y: "Output to tempdb.stg: committed, 128,026 rows" });
  assert.equal(sampled("stage", "completed", { rows_written: 0, committed: true }).text, "committed · 0 rows", "it ran and wrote nothing — a fact");
  assert.equal(sampled("stage", "completed", { rows_written: 12 }).text, "12 written · commit not observed", "a terminal sample WITHOUT commit evidence is unknown, never committed");
  assert.deepEqual(sampled("writeback", "failed", { rows_written: 3, rolled_back: true, committed: false }).state, "failed");
  assert.equal(sampled("writeback", "failed", { rows_written: 3, rolled_back: true, committed: false }).text, "failed · rolled back");
  assert.equal(sampled("writeback", "failed", { rows_written: 3 }).text, "failed · 3 written · commit not observed", "the accepted count is a fact; the commit is the unknown");
  assert.equal(sampled("stage", "aborted", {}).text, "aborted · commit not observed");
  assert.equal(sampled("stage", "aborted", { committed: true, rows_written: 5 }).text, "aborted · committed · 5 rows", "a confirmed commit survives an aborted node (R149-1)");
});

test("describe().port: a node_completed that arrives with NO terminal sample says commit not observed", () => {
  const ops = loadOps().createNodeOps();
  ops.reduce("node_started", { node_id: "n" });
  ops.reduce("node_progress", { node_id: "n", sequence: 1, operation: "stage", state: "writing", destination: { kind: "tempdb", table: "stg" }, rows_written: 40 });
  ops.reduce("node_completed", { node_id: "n" });
  const port = loadOps().describe(ops.get("n")).port;
  assert.equal(port.state, "done");
  assert.equal(port.text, "40 written · commit not observed");
});

test("describe().port: kind words — the wire's operation, in the reader's words", () => {
  assert.equal(sampled("materialize", "writing", { rows_written: 1 }).kindLabel, "result");
  assert.equal(sampled("writeback", "writing", { rows_written: 1 }).kindLabel, "write-back");
  assert.equal(sampled("statement", "executing").kindLabel, "statement");
  assert.equal(sampled("child", "executing").kindLabel, "child result");
});

/* --------------------------------------------------------------- the card */

const idleCard = { id: "s", type: "DQL", state: "idle", facts: [], output: { kind: "tempdb", table: "stg", text: "tempdb.stg" } };

test("buildCardHtml: one port per configured output, idle: destination only, no flow element", () => {
  const html = loadGraph().buildCardHtml(idleCard);
  assert.equal((html.match(/class="pe-port /g) || []).length, 1, "exactly one port");
  assert.match(html, /pe-port pe-port-idle/);
  assert.match(html, /pe-port-dest">tempdb\.stg</);
  assert.match(html, /aria-label="Output to tempdb\.stg"/);
  assert.ok(!html.includes("pe-port-flow"), "no flow element unless writing");
  assert.ok(!html.includes("pe-port-line"), "no state text while idle");
});

test("buildCardHtml: a node without an output renders no port and still has its out-anchor for dependencies", () => {
  const html = loadGraph().buildCardHtml({ id: "d", type: "DDL", state: "idle", facts: [], output: null });
  assert.ok(!html.includes("pe-port "), "no port");
  assert.match(html, /pe-card-port-out/, "the dependency anchor stays");
});

test("buildCardHtml: the measured states — waiting is still, writing flows, done settles", () => {
  const { buildCardHtml } = loadGraph();
  const running = Object.assign({}, idleCard, { state: "running" });
  const waiting = buildCardHtml(Object.assign({}, running, { port: { state: "waiting", text: "waiting for tempdb connection", kindLabel: "stage", a11y: "Output to tempdb.stg: waiting for tempdb connection" } }));
  assert.match(waiting, /pe-port pe-port-waiting/);
  assert.match(waiting, /pe-port-line">waiting for tempdb connection</);
  assert.ok(!waiting.includes("pe-port-flow"), "a wait moves nothing");
  const writing = buildCardHtml(Object.assign({}, running, { port: { state: "writing", text: "writing · 11,000 written", kindLabel: "stage", a11y: "Output to tempdb.stg: writing, 11,000 written" } }));
  assert.match(writing, /pe-port pe-port-writing/);
  assert.match(writing, /pe-port-flow/, "the ONLY write-flow on the canvas");
  assert.match(writing, /pe-port-kind">stage</);
  assert.match(writing, /aria-label="Output to tempdb\.stg: writing, 11,000 written"/);
  const done = buildCardHtml(Object.assign({}, idleCard, { state: "success", port: { state: "done", text: "committed · 128,026 rows", kindLabel: "stage", a11y: "Output to tempdb.stg: committed, 128,026 rows" } }));
  assert.match(done, /pe-port pe-port-done/);
  assert.ok(!done.includes("pe-port-flow"));
  assert.match(done, /pe-port-line">committed · 128,026 rows</);
});

test("buildCardHtml: a stale port (stream lost) says so and carries no flow", () => {
  const html = loadGraph().buildCardHtml(Object.assign({}, idleCard, { state: "running", stale: true, port: { state: "writing", text: "writing · 11,000 written", kindLabel: "stage", a11y: "Output to tempdb.stg: writing, 11,000 written" } }));
  assert.match(html, /pe-port pe-port-writing pe-port-stale/);
  assert.ok(!html.includes("pe-port-flow"), "nothing moves once the stream is gone");
  assert.match(html, /pe-port-line">writing · 11,000 written — stream lost</);
  assert.match(html, /pe-card-stale/);
});

test("buildCardHtml escapes the destination like every other fact", () => {
  const html = loadGraph().buildCardHtml(Object.assign({}, idleCard, { output: { kind: "tempdb", table: "x", text: 'tempdb.<img src=x onerror="1">' } }));
  assert.ok(!html.includes("<img"));
  assert.match(html, /&lt;img/);
});

/* ------------------------------------------------ graph writes port data */

function fakeNode(id) {
  const classes = new Set(["idle"]);
  const data = { id, state: "idle" };
  return {
    id: () => id, length: 1,
    removeClass(c) { classes.delete(c); }, addClass(c) { classes.add(c); },
    hasClass: (c) => classes.has(c), classes: () => [...classes],
    data(k, v) { if (v === undefined) return data[k]; data[k] = v; },
    incomers: () => [], outgoers: () => [],
  };
}

function graphWith(nodes) {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const byId = Object.fromEntries(nodes.map((n) => [n.id(), n]));
  const edge = { classes: new Set(["dependency", "active"]), removeClass(c) { this.classes.delete(c); }, addClass(c) { this.classes.add(c); }, hasClass(c) { return this.classes.has(c); }, data() {} };
  g.cy = { getElementById: (id) => byId[id] || { length: 0 }, nodes: () => nodes, edges: () => [edge] };
  g.editor = { nodeStates: {} };
  g._mm = null;
  return { g, edge };
}

test("setNodeOperation writes the port view onto the node; resetAll clears it", () => {
  const n = fakeNode("s");
  const { g } = graphWith([n]);
  const view = loadOps().describe(Object.assign(loadOps().createNodeOps(), {}) && (() => {
    const ops = loadOps().createNodeOps();
    ops.reduce("node_started", { node_id: "s" });
    ops.reduce("node_progress", { node_id: "s", sequence: 1, operation: "stage", state: "writing", destination: { kind: "tempdb", table: "stg" }, rows_written: 7 });
    return ops.get("s");
  })());
  g.setNodeOperation("s", view);
  assert.deepEqual(n.data("port"), view.port);
  assert.equal(n.data("port").state, "writing");
  g.setNodeOperation("s", null);
  assert.equal(n.data("port"), null, "a null view clears the measured port");
  g.setNodeOperation("s", view);
  g.resetAll();
  assert.equal(n.data("port"), null);
  assert.equal(n.data("stale"), null);
});

test("markStreamLost: every node goes stale and consumer-running clears — no outcome is invented", () => {
  const running = fakeNode("s");
  running.data("state", "running");
  const idle = fakeNode("t");
  const { g, edge } = graphWith([running, idle]);
  g.markStreamLost();
  assert.equal(running.data("stale"), true);
  assert.equal(running.data("state"), "running", "the state word is not rewritten — we do not know");
  assert.equal(idle.data("stale"), true);
  assert.ok(!edge.hasClass("active"), "no edge keeps the consumer-running emphasis");
  g.resetAll();
  assert.equal(running.data("stale"), null, "a fresh run lifts the freeze");
});
