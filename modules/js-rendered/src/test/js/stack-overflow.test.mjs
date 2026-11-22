// #1011 (owner 2026-11-22, "not drawn + warning"; record §4.6, the note of 2026-11-22 "a stack beyond the number range"):
// a stack at zero whose sum, above zero or below it, is beyond float64 is not drawn at that category (a bar) or that
// position (a stacked area); the existing warning chart.rule.non-finite is given once and the rest of the chart draws.
// It is decided over every series, shown or hidden, so a legend switch never brings the stack back (D3). Every expected
// fact here is read from the picture (primitives, index, summary) against the rows this file writes, never from the
// engine's own sums.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine } from "./engine.mjs";
import { markBox, textBox, drawn } from "./geometry.mjs";

const R = engine();
const W = 960, H = 540;
const NON_FINITE = "chart.rule.non-finite";

/** A document over x (STRING), s (STRING) and v (DOUBLE): `type` bar or area, value on y (vertical) or x (horizontal). */
function doc(type, { stack = "zero", horizontal = false, color = true } = {}) {
  const band = { field: "x", type: "nominal" };
  const value = Object.assign({ field: "v", type: "quantitative", aggregate: "sum" }, stack === undefined ? {} : { stack });
  const encoding = horizontal ? { y: band, x: value } : { x: band, y: value };
  if (color) encoding.color = { field: "s", type: "nominal" };
  const columns = [{ name: "x", type: "STRING", nullable: true }, { name: "s", type: "STRING", nullable: true }, { name: "v", type: "DOUBLE", nullable: true }];
  return { display_name: "t", renderer: { kind: "chart", version: "1" }, inputs: { result: { columns } }, config: { mark: { type, labels: "values" }, encoding } };
}

/** An area over two measures p and q (`fields`, no colour), stacked at zero. */
function fieldsDoc() {
  const columns = [{ name: "x", type: "STRING", nullable: true }, { name: "p", type: "DOUBLE", nullable: true }, { name: "q", type: "DOUBLE", nullable: true }];
  const y = { type: "quantitative", aggregate: "sum", stack: "zero", fields: [{ field: "p", title: "P" }, { field: "q", title: "Q" }] };
  return { display_name: "t", renderer: { kind: "chart", version: "1" }, inputs: { result: { columns } }, config: { mark: { type: "area" }, encoding: { x: { field: "x", type: "nominal" }, y } } };
}

function draw(document, columns, hidden = []) {
  const types = {};
  for (const c of document.inputs.result.columns) types[c.name] = c.type;
  const r = R.render({ type: document.config.mark.type, document, columns, types, frame: { w: W, h: H }, theme: "light", measure: null, hidden });
  assert.equal(r.state, "rendered", JSON.stringify(r.summary && r.summary.refusal));
  // The engine runs in its own realm: a clone in this one lets deepEqual compare its arrays (non-finite numbers kept).
  return structuredClone(r);
}

const rows = (x, s, v) => ({ x, s, v });
const codes = (r) => r.summary.warnings.map((w) => w.code);

/** The largest x any primitive reaches: a mark's box (a path's arcs and control points included), a text's box. */
function largestX(r) {
  let max = -Infinity;
  for (const p of r.primitives) {
    if (p.k === "group") continue;
    if (p.k === "text") {
      if (drawn(p) === "") continue;
      for (const [x] of textBox(p)) max = Math.max(max, x);
    } else max = Math.max(max, markBox(p)[2]);
  }
  return max;
}

const longestTick = (r) => Math.max(0, ...r.primitives.filter((p) => p.k === "text" && p.role === "tick").map((p) => drawn(p).length));

/** The band coordinate (x on a vertical chart, y on a horizontal one) of the category tick that reads `name`. */
function bandAt(r, name, horizontal) {
  const tick = r.primitives.find((p) => p.k === "text" && p.role === "tick" && p.text === name);
  assert.ok(tick, "a tick reads " + name);
  if (!horizontal) return tick.x;
  const ys = textBox(tick).map(([, y]) => y);
  return (Math.min(...ys) + Math.max(...ys)) / 2;
}

/** The mark primitives (not groups) whose band extent holds coordinate c (within 1 px). */
function marksAt(r, c, horizontal) {
  return r.primitives.filter((p) => {
    if (p.role !== "mark" || p.k === "group" || p.k === "text") return false;
    const [x0, y0, x1, y1] = markBox(p);
    return horizontal ? y0 - 1 <= c && c <= y1 + 1 : x0 - 1 <= c && c <= x1 + 1;
  });
}

/** The value labels (role guide texts) whose box holds band coordinate c. */
function labelsAt(r, c, horizontal) {
  return r.primitives.filter((p) => {
    if (p.k !== "text" || p.role !== "guide" || drawn(p) === "") return false;
    const box = textBox(p), vs = box.map(([x, y]) => (horizontal ? y : x));
    return Math.min(...vs) - 1 <= c && c <= Math.max(...vs) + 1;
  });
}

/** The headings (first tip line) of the points or bars the index holds. */
const headings = (r) => new Set(r.index.series.flatMap((s) => s.tip.map((t) => t[0])));
const indexPoints = (r) => r.index.series.reduce((n, s) => n + s.at.length, 0);

/**
 * The ruling's facts for a picture whose category (position) `over` overflows and `kept` draws: one warning, nothing at
 * `over` (no mark, no label, no index entry, no total), something at `kept`, the ticks short, the picture in its frame.
 */
function assertNotDrawn(r, { over = "a", kept = "b", horizontal = false, bar = true } = {}) {
  assert.deepEqual(codes(r), [NON_FINITE]);
  const ca = bandAt(r, over, horizontal), cb = bandAt(r, kept, horizontal);
  assert.deepEqual(marksAt(r, ca, horizontal).map((p) => p.series), [], "no mark at " + over);
  assert.ok(marksAt(r, cb, horizontal).length > 0, "a mark at " + kept);
  assert.ok(!headings(r).has(over), "the index holds nothing at " + over);
  assert.ok(headings(r).has(kept), "the index holds " + kept);
  if (bar) {
    assert.deepEqual(labelsAt(r, ca, horizontal).map(drawn), [], "no label at " + over);
    assert.ok(labelsAt(r, cb, horizontal).length > 0, "a label at " + kept);
    assert.ok(Array.isArray(r.index.totals), "a stack at zero has totals");
    assert.equal(r.index.totals.filter((t) => t === null).length, 1, "one category has no total: " + JSON.stringify(r.index.totals));
    assert.equal(r.index.totals.filter((t) => t !== null).length, r.index.totals.length - 1);
  }
  assert.ok(longestTick(r) <= 12, "the longest tick text has " + longestTick(r) + " characters");
  assert.ok(largestX(r) <= W, "the largest x is " + largestX(r));
}

const CASES = [
  ["1e308 + 1e308", [1e308, 1e308, 1]],
  ["−1e308 + −1e308", [-1e308, -1e308, 1]],
  ["9e307 + 9e307 (each finite, the sum is not)", [9e307, 9e307, 1]],
];

for (const type of ["bar", "area"]) {
  for (const [name, v] of CASES) {
    test(`a ${type} stack of ${name} is not drawn at its category, with one warning; the other category draws`, () => {
      const r = draw(doc(type), rows(["a", "a", "b"], ["P", "Q", "P"], v));
      assertNotDrawn(r, { bar: type === "bar" });
      assert.equal(r.summary.marks, 1);
    });
  }

  test(`a ${type} stack of 1e308, −1e308, 1e308 overflows above zero although a running sum over every sign stays finite (D1)`, () => {
    const r = draw(doc(type), rows(["a", "a", "a", "b"], ["P", "Q", "S", "P"], [1e308, -1e308, 1e308, 1]));
    assertNotDrawn(r, { bar: type === "bar" });
    assert.equal(r.summary.marks, 1);
  });

  test(`a ${type} stack of 1e308 and −1e308 does not overflow: each side is finite (D1, control)`, () => {
    const r = draw(doc(type), rows(["a", "a", "b"], ["P", "Q", "P"], [1e308, -1e308, 1]));
    assert.deepEqual(codes(r), []);
    assert.equal(r.summary.marks, 3);
  });

  test(`a ${type} stack of 1, 2 and 1 draws every mark with no warning (control)`, () => {
    const r = draw(doc(type), rows(["a", "a", "b"], ["P", "Q", "P"], [1, 2, 1]));
    assert.deepEqual(codes(r), []);
    assert.equal(r.summary.marks, 3);
  });
}

test("a horizontal bar stack of 1e308 + 1e308 is not drawn at its category, with one warning", () => {
  const r = draw(doc("bar", { horizontal: true }), rows(["a", "a", "b"], ["P", "Q", "P"], [1e308, 1e308, 1]));
  assertNotDrawn(r, { horizontal: true });
  assert.equal(r.summary.marks, 1);
});

test("an area over two measures stacks them: a position whose sum overflows is not drawn, with one warning", () => {
  const r = draw(fieldsDoc(), { x: ["a", "b"], p: [1e308, 1], q: [1e308, 2] });
  assertNotDrawn(r, { bar: false });
  assert.equal(r.summary.marks, 2);
  assert.equal(indexPoints(r), 2);
});

test("an area over two measures with finite sums draws every point (control)", () => {
  const r = draw(fieldsDoc(), { x: ["a", "b"], p: [5, 1], q: [7, 2] });
  assert.deepEqual(codes(r), []);
  assert.equal(r.summary.marks, 4);
});

/** Per series, how many of its mark paths (a fill or a line) have points on both sides of band coordinate c. */
function pathsAcross(r, c) {
  const out = {};
  for (const s of r.summary.series) out[s.id] = 0;
  for (const p of r.primitives) {
    if (p.k !== "path" || p.role !== "mark") continue;
    const [x0, , x1] = markBox(p);
    if (x0 < c - 1 && x1 > c + 1) out[p.series]++;
  }
  return out;
}

test("a series with no row at the overflowing position has a gap there too: no line and no fill runs across it (D2)", () => {
  const columns = (atB) => rows(["a", "a", "a", "b", "b", "c", "c", "c"], ["P", "Q", "R", "P", "Q", "P", "Q", "R"], [1, 1, 1, atB, atB, 1, 1, 1]);
  const over = draw(doc("area"), columns(1e308));
  assert.deepEqual(codes(over), [NON_FINITE]);
  assert.deepEqual(over.index.series.map((s) => [s.id, s.at.length]), [["string:P", 2], ["string:Q", 2], ["string:R", 2]]);
  assert.equal(indexPoints(over), 6);
  assert.deepEqual(pathsAcross(over, bandAt(over, "b", false)), { "string:P": 0, "string:Q": 0, "string:R": 0 });
  assert.ok(largestX(over) <= W);
  // The control: b does not overflow, so every series' fill and line run across it, R's too (R has no row at b and
  // its line runs across a position with no row, as it does today). This proves the reading of the paths sees a
  // crossing, and that only an overflowing position is a gap for every series.
  const control = draw(doc("area"), columns(1));
  assert.deepEqual(codes(control), []);
  assert.equal(indexPoints(control), 8);
  const across = pathsAcross(control, bandAt(control, "b", false));
  for (const id of ["string:P", "string:Q", "string:R"]) assert.ok(across[id] >= 2, id + " runs across b: " + JSON.stringify(across));
});

for (const type of ["bar", "area"]) {
  test(`a legend switch on a ${type} never brings the overflowing stack back and never changes the warning (D3)`, () => {
    const columns = rows(["a", "a", "b", "b"], ["P", "Q", "P", "Q"], [1e308, 1e308, 1, 2]);
    const shown = draw(doc(type), columns);
    assertNotDrawn(shown, { bar: type === "bar" });
    assert.deepEqual(shown.summary.series.map((s) => s.id), ["string:P", "string:Q"]);
    for (const id of ["string:P", "string:Q"]) {
      const r = draw(doc(type), columns, [id]);
      assert.deepEqual(r.summary.series.map((s) => [s.id, s.hidden]), [["string:P", id === "string:P"], ["string:Q", id === "string:Q"]]);
      assertNotDrawn(r, { bar: type === "bar" });
      const visible = id === "string:P" ? "string:Q" : "string:P";
      assert.deepEqual(marksAt(r, bandAt(r, "b", false), false).filter((p) => p.k !== "line").map((p) => p.series).filter((s, i, a) => a.indexOf(s) === i), [visible], "b keeps " + visible);
      assert.equal(r.summary.marks, 1);
    }
    // Not vacuous: on a stack that does not overflow, hiding a series takes its marks away.
    const control = rows(["a", "a", "b", "b"], ["P", "Q", "P", "Q"], [5, 7, 1, 2]);
    const all = draw(doc(type), control), oneHidden = draw(doc(type), control, ["string:P"]);
    assert.deepEqual(codes(all), []);
    assert.equal(all.summary.marks, 4);
    assert.equal(oneHidden.summary.marks, 2);
    assert.ok(marksAt(oneHidden, bandAt(oneHidden, "a", false), false).length < marksAt(all, bandAt(all, "a", false), false).length);
  });
}
