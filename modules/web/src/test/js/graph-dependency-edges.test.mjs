// 151 (#127) — a dependency arrow is an ORDERING, never a transfer.
//
// What this file pins, per the lane's visual/state model (recorded before coding in
// the lane's evidence, `visual-state-model.md`):
//
//   1. KIND — every depends_on edge is `kind: "dependency"` (class `dependency`);
//      boundary connectors say which side they attach to (`boundary-start` /
//      `boundary-end`) so the stylesheet can anchor them to the marker shapes.
//   2. NO COPIED COUNTS (the falsification #127 asks for) — a completing producer
//      with two dependents labels NEITHER dependency edge; `rows`/`rowLabel` are
//      gone from the element model, and the stylesheet has no edge label rule.
//   3. STATIC STATES — the edge into a running consumer is `active` and STILL (no
//      rAF dash loop ever starts); a completed source turns its outgoing
//      dependencies `satisfied`; a failed/aborted source turns them `unmet`; the
//      retired `done` (target ran) never appears.
//   4. CONSUMER-RUNNING IS NOT A WRITE — starting a consumer touches no port data
//      and no writing class on the producer.
//   5. RESET — resetAll clears every dependency state for the next run.
//
// Same fake family as graph-view.test.mjs: a prototype instance over hand-rolled
// cy fakes, no DOM.

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

/* ------------------------------------------------------------------- kinds */

test("buildElements: every depends_on edge is kind dependency; boundary connectors name their side", () => {
  const { buildElements } = loadGraph();
  const nodes = [
    { id: "p", type: "DQL", source: "pg", output: { target: "tempdb", table: "stg" } },
    { id: "d", type: "DDL", source: "tempdb" },
    { id: "c1", type: "DQL", source: "tempdb", depends_on: ["p", "d"], output: { target: "tempdb", table: "t1" } },
    { id: "c2", type: "DQL", source: "tempdb", depends_on: ["p"] },
  ];
  const edges = buildElements(nodes).filter((e) => e.group === "edges");
  const deps = edges.filter((e) => e.data.kind === "dependency");
  assert.equal(deps.length, 3, "one dependency edge per depends_on entry, the DDL ordering included");
  for (const e of deps) {
    assert.ok(e.classes.split(" ").includes("dependency"), `${e.data.id} carries the dependency class`);
    assert.equal(e.data.rowLabel, undefined, "no label slot is ever created on a dependency");
  }
  const bounds = edges.filter((e) => e.data.kind === "boundary");
  const starts = bounds.filter((e) => e.data.side === "start");
  const ends = bounds.filter((e) => e.data.side === "end");
  assert.equal(starts.length, 2, "Start → p, Start → d");
  assert.equal(ends.length, 2, "c1 → End, c2 → End");
  for (const e of starts) assert.ok(e.classes.split(" ").includes("boundary-start"), e.data.id);
  for (const e of ends) assert.ok(e.classes.split(" ").includes("boundary-end"), e.data.id);
  assert.equal(edges.length, deps.length + bounds.length, "no other edge kind exists");
});

test("buildElements: the markers are not selectable — nothing to select into Details", () => {
  const { buildElements } = loadGraph();
  const markers = buildElements([{ id: "solo", type: "DQL", source: "pg" }]).filter(
    (e) => e.group === "nodes" && e.data.kind === "boundary",
  );
  assert.equal(markers.length, 2);
  for (const m of markers) assert.equal(m.selectable, false, `${m.data.boundary} marker is unselectable`);
});

/* -------------------------------------------------------------- the fakes */

function fakeEdge(id, kind) {
  const classes = new Set(kind ? [kind] : []);
  const data = { id };
  if (kind) data.kind = kind;
  return {
    id,
    length: 1,
    classes: () => [...classes],
    hasClass: (c) => classes.has(c),
    addClass(c) { classes.add(c); },
    removeClass(c) { classes.delete(c); },
    data(k, v) { if (v === undefined) return data[k]; data[k] = v; },
  };
}

function fakeNode(id, incomers, outgoers) {
  const classes = new Set(["idle"]);
  const data = { id };
  return {
    id: () => id,
    length: 1,
    removeClass(c) { classes.delete(c); },
    addClass(c) { classes.add(c); },
    hasClass: (c) => classes.has(c),
    classes: () => [...classes],
    data(k, v) { if (v === undefined) return data[k]; data[k] = v; },
    incomers: () => incomers,
    outgoers: () => outgoers,
  };
}

/** One producer, two consumers, one DDL ordering into the first consumer. */
function fanOut() {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const pc1 = fakeEdge("p->c1", "dependency");
  const pc2 = fakeEdge("p->c2", "dependency");
  const dc1 = fakeEdge("d->c1", "dependency");
  const p = fakeNode("p", [], [pc1, pc2]);
  const d = fakeNode("d", [], [dc1]);
  const c1 = fakeNode("c1", [pc1, dc1], []);
  const c2 = fakeNode("c2", [pc2], []);
  const byId = { p, d, c1, c2 };
  const edges = [pc1, pc2, dc1];
  g.cy = {
    getElementById: (id) => byId[id] || { length: 0 },
    nodes: () => [p, d, c1, c2],
    edges: (sel) => (sel === ".active" ? edges.filter((e) => e.hasClass("active")) : edges),
  };
  g.editor = { nodeStates: {} };
  g._mm = null;
  g._flowRunning = false;
  return { g, p, d, c1, c2, pc1, pc2, dc1 };
}

/* ----------------------------------------------------- copied counts, gone */

test("FALSIFY copied counts: a completing producer with two dependents labels NEITHER dependency", () => {
  const { g, p, pc1, pc2 } = fanOut();
  g.setNodeState("p", "success");
  g.setNodeStats("p", { duration_ms: 842, rows_out: 128026 });
  assert.equal(p.data("run"), "128,026 rows · 842 ms", "the producer's own footer keeps the count");
  for (const e of [pc1, pc2]) {
    assert.ok(!e.hasClass("rows"), `${e.id} carries no rows class`);
    assert.equal(e.data("rowLabel"), undefined, `${e.id} carries no row label — 128,026 rows is what p WROTE once, not what travels to each consumer`);
  }
});

test("a calculator's key count never rides a dependency either", () => {
  const { g, pc1, pc2 } = fanOut();
  g.setNodeStats("p", { duration_ms: 2, rows_out: 0, context_values: { a: 1, b: 2 } });
  assert.equal(pc1.data("rowLabel"), undefined);
  assert.equal(pc2.data("rowLabel"), undefined);
});

test("the stylesheet has no edge label rule and no rows class", () => {
  const sheet = loadGraph().buildStylesheet({ cardW: 236, cardH: 148 });
  for (const rule of sheet) {
    assert.ok(!/\.rows\b/.test(rule.selector), `retired selector ${rule.selector}`);
    assert.equal(rule.style.label, undefined, `${rule.selector} paints no label on the canvas`);
  }
});

/* ------------------------------------------------------------ static states */

test("running consumer: its incoming dependencies go active and STILL — no dash loop is scheduled", () => {
  const { g, pc1, dc1, pc2 } = fanOut();
  let rafCalls = 0;
  globalThis.window = { matchMedia: () => ({ matches: false }) };
  globalThis.requestAnimationFrame = () => { rafCalls++; };
  try {
    g.setNodeState("c1", "running");
    assert.ok(pc1.hasClass("active") && dc1.hasClass("active"), "both orderings into c1 read consumer-running");
    assert.ok(!pc2.hasClass("active"), "the sibling's ordering is untouched");
    assert.equal(rafCalls, 0, "no requestAnimationFrame: the arrow does not move");
    assert.equal(g._flowRunning, false);
    assert.equal(typeof g.ensureFlow, "undefined", "the rAF flow loop is retired with the transfer reading");
  } finally {
    delete globalThis.window;
    delete globalThis.requestAnimationFrame;
  }
});

test("completed source: its outgoing dependencies are satisfied; the retired `done` never appears", () => {
  const { g, pc1, pc2, dc1 } = fanOut();
  g.setNodeState("p", "success");
  assert.ok(pc1.hasClass("satisfied") && pc2.hasClass("satisfied"), "both dependents may now start");
  assert.ok(!dc1.hasClass("satisfied"), "d has not finished — c1 still waits for it");
  g.setNodeState("c1", "running");
  g.setNodeState("c1", "success");
  assert.ok(!pc1.hasClass("active"), "consumer-running clears at completion");
  assert.ok(!pc1.hasClass("done") && !dc1.hasClass("done"), "`done` is retired");
  assert.ok(pc1.hasClass("satisfied"), "the dependency stays satisfied — that is a fact about p");
});

test("failed or aborted source: its outgoing dependencies are unmet", () => {
  const { g, pc1, pc2, dc1 } = fanOut();
  g.setNodeState("p", "failed");
  assert.ok(pc1.hasClass("unmet") && pc2.hasClass("unmet"));
  assert.ok(!pc1.hasClass("satisfied"));
  g.setNodeState("d", "aborted");
  assert.ok(dc1.hasClass("unmet"), "an aborted ordering can never be met this run");
});

test("FALSIFY consumer-running-as-write: starting a consumer writes nothing on the producer", () => {
  const { g, p, c1 } = fanOut();
  g.setNodeState("p", "success");
  g.setNodeState("c1", "running");
  assert.equal(p.data("port"), undefined, "no port data appears on the producer from a consumer event");
  assert.equal(p.data("opState"), undefined);
  assert.equal(c1.data("state"), "running");
});

test("resetAll clears satisfied / unmet / active for the next run", () => {
  const { g, pc1, pc2, dc1 } = fanOut();
  g.setNodeState("p", "success");
  g.setNodeState("d", "failed");
  g.setNodeState("c2", "running");
  g.resetAll();
  for (const e of [pc1, pc2, dc1]) {
    assert.deepEqual(
      e.classes().filter((c) => c !== "dependency"),
      [],
      `${e.id} carries only its kind class after reset`,
    );
  }
});

/* ------------------------------------------------- the marker's own box */

test("applyEdgeCurves routes a boundary connector from the MARKER's box, not the card's", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  g.tokens = { cardW: 236, cardH: 148 };
  let written = null;
  // A root laid out 260 model px right of Start. From the MARKER's right edge (x = 36) the
  // port-to-port dx is 106 → the forward bezier with k clamped to 60 (weights 60/106 and
  // 46/106). Read with the CARD's width instead, the source port would sit at x = 118,
  // dx = 24 < 60, and the router would take the backward DETOUR — a visibly different,
  // wrong curve for the connector.
  const edge = {
    source: () => ({ id: () => "__execution_start__", position: () => ({ x: 0, y: 0 }), width: () => 72, data: () => undefined }),
    target: () => ({ id: () => "root", position: () => ({ x: 260, y: 0 }), width: () => 236, data: () => undefined }),
    style(v) { written = v; },
  };
  g.cy = { edges: () => [edge] };
  g.applyEdgeCurves();
  assert.ok(written, "the connector got a curve");
  const { edgeRouteFor } = loadGraph();
  const markerRoute = edgeRouteFor({ x: 0, y: 0, w: 72, h: 80 }, { x: 260, y: 0, w: 236, h: 148 });
  const cardRoute = edgeRouteFor({ x: 0, y: 0, w: 236, h: 148 }, { x: 260, y: 0, w: 236, h: 148 });
  assert.equal(markerRoute.kind, "bezier");
  assert.equal(cardRoute.kind, "detour", "the card-width reading would detour under the marker");
  assert.deepEqual(written["control-point-weights"], markerRoute.weights);
  assert.deepEqual(written["control-point-distances"], markerRoute.distances);
});
