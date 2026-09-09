// 105 — which way does the data flow? Two claims, one file:
//
// 1. EDGE DIRECTION from a pipeline body: every edge's source is the depends_on
//    TARGET and its target the DEPENDENT, for every node kind — including the
//    caller node (DQL with the output block omitted, and explicit target:
//    "caller") and CALCULATOR (whose depends_on is declared like any other
//    node's, never derived from its inputs). A reversed builder is the bug the
//    owner's screenshot was one candidate for; this pins it cannot return.
//
// 2. The ROUTING of a single edge as a pure function of the two card boxes
//    (graph.js edgeRouteFor — what applyEdgeCurves writes onto the canvas):
//      dx > 60   the mock's bezier — leaves the source port HORIZONTALLY,
//                enters the target port HORIZONTALLY, k = max(60, dx/2);
//      dx <= 60  INCLUDING EVERY NEGATIVE dx — an orthogonal detour BELOW both
//                cards: OUT right from the source port, DOWN past the lower
//                card's bottom, LEFT/RIGHT at the detour depth, UP, IN from
//                the left of the target port. The arrowhead lands in the clear
//                and the line never passes behind a card. This regime exists
//                because nodes are draggable: a user can put a dependent left
//                of its source, and the owner photographed what the old single
//                k = max(60, dx/2) formula did there (dx < 0 clamps k to 60
//                and the curve loops over the cards — handbacks/105).

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

/* ---------------------------------------------------------------- direction */

test("buildElements: every edge runs dependency -> dependent, for every node kind", () => {
  const { buildElements } = loadGraph();
  const nodes = [
    { id: "src_dql", type: "DQL", source: "sample-trips", depends_on: [], output: { target: "tempdb", table: "t1" } },
    { id: "src_dml", type: "DML", source: "sample-trips", depends_on: [] },
    { id: "src_ddl", type: "DDL", source: "tempdb", depends_on: [] },
    { id: "src_pipeline", type: "PIPELINE", pipeline: { name: "child", version: 1 }, depends_on: [] },
    { id: "src_calc", type: "CALCULATOR", kind: "fiscal_quarter", context_key: "q", depends_on: [] },
    // the caller node, both shapes contract §9 permits: output omitted (the DQL
    // default) and an explicit output.target "caller" (also legal on PIPELINE §4.9).
    { id: "answer", type: "DQL", source: "tempdb", depends_on: ["src_dql", "src_dml"] },
    { id: "answer2", type: "DQL", source: "tempdb", output: { target: "caller" }, depends_on: ["src_ddl"] },
    { id: "answer3", type: "PIPELINE", pipeline: { name: "child2", version: 1 }, output: { target: "caller" }, depends_on: ["src_pipeline"] },
    { id: "uses_calc", type: "DQL", source: "tempdb", depends_on: ["src_calc"], output: { target: "tempdb", table: "t2" } },
  ];
  const edges = buildElements(nodes).filter((e) => e.group === "edges");
  assert.equal(edges.length, 5, "one edge per depends_on entry");
  const byId = Object.fromEntries(edges.map((e) => [e.data.id, e.data]));
  assert.deepEqual(byId["src_dql->answer"], { id: "src_dql->answer", source: "src_dql", target: "answer" });
  assert.deepEqual(byId["src_dml->answer"], { id: "src_dml->answer", source: "src_dml", target: "answer" });
  // DQL caller (omitted output), DQL caller (explicit), PIPELINE caller:
  assert.equal(byId["src_ddl->answer2"].source, "src_ddl");
  assert.equal(byId["src_ddl->answer2"].target, "answer2");
  assert.equal(byId["src_pipeline->answer3"].source, "src_pipeline");
  assert.equal(byId["src_pipeline->answer3"].target, "answer3");
  // CALCULATOR: a declared edge is an edge; inputs alone never emit one.
  assert.equal(byId["src_calc->uses_calc"].source, "src_calc");
  assert.equal(byId["src_calc->uses_calc"].target, "uses_calc");
  for (const e of edges) {
    const dep = nodes.find((n) => n.id === e.data.target);
    assert.ok(
      dep.depends_on.includes(e.data.source),
      `${e.data.source} -> ${e.data.target} must mirror a depends_on entry`,
    );
  }
});

test("buildElements: a CALCULATOR's $ref inputs emit NO edge on their own", () => {
  const { buildElements } = loadGraph();
  const nodes = [
    { id: "fiscal", type: "CALCULATOR", kind: "fiscal_quarter", context_key: "f", depends_on: [] },
    { id: "reads_ref_only", type: "CALCULATOR", kind: "const", context_key: "c", inputs: { q: "$f" }, depends_on: [] },
    { id: "reads_ref_declared", type: "CALCULATOR", kind: "const", context_key: "c2", inputs: { q: "$f" }, depends_on: ["fiscal"] },
  ];
  const edges = buildElements(nodes).filter((e) => e.group === "edges");
  assert.equal(edges.length, 1);
  assert.equal(edges[0].data.id, "fiscal->reads_ref_declared");
});

/* ----------------------------------------------------------------- routing */

/** A 236x148 card box centred at (x, y) — the tokens' card the minimap uses. */
const box = (x, y, w = 236, h = 148) => ({ x, y, w, h });
const portR = (b) => ({ x: b.x + b.w / 2, y: b.y });
const portL = (b) => ({ x: b.x - b.w / 2, y: b.y });

test("forward edge (dx > 60): the mock's bezier — horizontal leave and enter, k = max(60, dx/2)", () => {
  const { edgeRouteFor } = loadGraph();
  const s = box(0, 0);
  const t = box(514, 1240); // the measured owner-body geometry: rank separation
  const route = edgeRouteFor(s, t);
  assert.equal(route.kind, "bezier");
  assert.equal(route.points.length, 2);
  const [c1, c2] = route.points;
  const dx = portL(t).x - portR(s).x; // 514-118-118 = 278
  const k = Math.max(60, dx / 2);
  assert.equal(c1.y, portR(s).y, "leaves the source port horizontally");
  assert.equal(c2.y, portL(t).y, "enters the target port horizontally");
  assert.equal(c1.x, portR(s).x + k);
  assert.equal(c2.x, portL(t).x - k);
  // the (weight, distance) form the canvas reads round-trips to the same points
  // (the inverse of projectControlPoints' projection, with CYTOSCAPE's perp
  // (−Δy, Δx)/|Δ| — the sign the vendored renderer applies)
  const sp = portR(s), tp = portL(t);
  const ddx = tp.x - sp.x, ddy = tp.y - sp.y, len2 = ddx * ddx + ddy * ddy, len = Math.sqrt(len2);
  route.points.forEach((p, i) => {
    const back = {
      x: sp.x + route.weights[i] * ddx - (route.distances[i] * ddy) / len,
      y: sp.y + route.weights[i] * ddy + (route.distances[i] * ddx) / len,
    };
    assert.ok(Math.abs(back.x - p.x) < 1e-9 && Math.abs(back.y - p.y) < 1e-9, `point ${i} round-trips`);
  });
});

test("short forward edge (0 <= dx < 60): no crossing controls — the detour takes it below", () => {
  const { edgeRouteFor } = loadGraph();
  // cards nearly touching: centres 260 apart -> port dx = 260-236 = 24
  const s = box(0, 0);
  const t = box(260, 0);
  assert.ok(portL(t).x - portR(s).x < 60);
  const route = edgeRouteFor(s, t);
  assert.equal(route.kind, "detour", "the S-curve's controls would cross here; route below instead");
  assert.ok(route.points[3].y === t.y, "enters the target port horizontally");
});

test("backward edge (dx < 0): orthogonal detour below both cards, arrow in the clear", () => {
  const { edgeRouteFor } = loadGraph();
  // the owner's photograph, as geometry: stage_tmax at (514,1240), the owner
  // dragged answer to 400px left of it and up.
  const s = box(514, 1240);
  const t = box(-100, 700);
  const depth = 44;
  const route = edgeRouteFor(s, t, depth);
  assert.equal(route.kind, "detour");
  assert.equal(route.points.length, 4, "out, down, across, in");
  const [p1, p2, p3, p4] = route.points;
  const sp = portR(s), tp = portL(t);
  const yDet = Math.max(s.y + s.h / 2, t.y + t.h / 2) + depth;
  // leaves horizontally out of the source port, into the clear RIGHT of the card
  assert.equal(p1.y, sp.y);
  assert.ok(p1.x > sp.x, "the first leg goes OUT, away from the source card");
  // drops below BOTH cards' bottom edges and runs across at that depth
  assert.equal(p2.x, p1.x);
  assert.ok(p2.y > s.y + s.h / 2 && p2.y > t.y + t.h / 2, "the across-leg is below both cards");
  assert.equal(p2.y, yDet);
  assert.equal(p3.y, yDet);
  assert.ok(p3.x < tp.x, "the rise back up happens LEFT of the target card, in the clear");
  // enters horizontally into the target port — the arrowhead points INTO the
  // left port from the left, exactly like a forward edge
  assert.equal(p4.y, tp.y);
  assert.ok(p4.x < tp.x);
  // depth is caller-controlled so parallel backward edges can stagger
  const deeper = edgeRouteFor(s, t, depth + 24);
  assert.equal(deeper.points[2].y, yDet + 24);
});

test("detour never crosses a card box: the control polygon stays in the clear", () => {
  const { edgeRouteFor } = loadGraph();
  // adversarial: target left of source AND lower — the across-leg must still
  // clear the LOWER card, and the verticals must stay outside both cards.
  const s = box(0, 0);
  const t = box(-500, 260);
  const route = edgeRouteFor(s, t, 44);
  const cards = [
    { x1: s.x - s.w / 2, x2: s.x + s.w / 2, y1: s.y - s.h / 2, y2: s.y + s.h / 2 },
    { x1: t.x - t.w / 2, x2: t.x + t.w / 2, y1: t.y - t.h / 2, y2: t.y + t.h / 2 },
  ];
  const crosses = (a, b) => a.x1 < b.x2 && b.x1 < a.x2 && a.y1 < b.y2 && b.y1 < a.y2;
  for (const p of route.points) {
    for (const c of cards) {
      assert.ok(
        !crosses({ x1: p.x - 1, x2: p.x + 1, y1: p.y - 1, y2: p.y + 1 }, c),
        `control (${p.x},${p.y}) sits inside a card box`,
      );
    }
  }
});

test("degenerate: coincident ports answer null (no curve to draw)", () => {
  const { edgeRouteFor } = loadGraph();
  const s = box(0, 0);
  const t = box(236, 0); // left port of t == right port of s
  // NOT degenerate — dx=0, dy=0 means portR(s) == portL(t): the detour has nowhere to go
  assert.equal(edgeRouteFor(s, t), null);
});

/* ------------------------------------------------------------- arrowhead */

test("the stylesheet's arrow scale is the exported constant and clears 8px at zoom 1", () => {
  const { buildStylesheet, ARROW_SCALE, ARROW_BASE_PX } = loadGraph();
  const tokens = {
    nodeSurface: "#fff", nodeBorder: "#b2b7bf", brand: "#2563eb", brandSoft: "#dbeafe",
    edgeIdle: "#94a3b8", edgeActive: "#2563eb", edgeDone: "#15803d",
    nodeSuccess: "#15803d", nodeFailed: "#dc2626", nodeAborted: "#a16207",
    edgeLabelText: "#64748b", edgeLabelBg: "#f8f9fb",
  };
  const sheet = buildStylesheet(tokens);
  const edge = sheet.find((r) => r.selector === "edge");
  assert.equal(edge.style["arrow-scale"], ARROW_SCALE);
  assert.ok(
    ARROW_SCALE * ARROW_BASE_PX >= 8,
    `arrow ${ARROW_SCALE}x base ${ARROW_BASE_PX}px must be >= 8px at zoom 1`,
  );
  // 105: the control-point pairs are computed against the PORTS (edgeRouteFor);
  // without "endpoints" cytoscape lerps them against the shape-intersection
  // baseline and the rendered curve is not the computed one (found live).
  assert.equal(edge.style["edge-distances"], "endpoints");
});
