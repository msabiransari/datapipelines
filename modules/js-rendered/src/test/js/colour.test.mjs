// Brief 1050 A.3, A.4 and A.5: the colour scale (19-colour.js), its legend, and the bar coloured by value. The steps
// are checked against values this test chooses so that every border is a whole number (no float arithmetic decides an
// expectation); the legend and the bar are read from the shaded bar fixtures' primitives; the I3 oracle of D7 is tested
// on its own, independent of the engine.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";
import { expectedBarMarks, markBox } from "./geometry.mjs";

const R = engine();
const C = R.colour;
const FIXTURES = chartFixtures();
const fx = (name) => FIXTURES.find((f) => f.wire === "bar" && f.name === name).fixture;
const SHADED = FIXTURES.filter((f) => f.wire === "bar" && f.name.startsWith("shade-"));
const agg = (num, exact = null) => ({ num, exact });
const scaleOf = (lo, hi) => C.sequential([agg(lo), agg(hi)]);
const SEQ = [1, 2, 3, 4, 5, 6, 7].map((n) => "--chart-seq-" + n);

/** The picture of a fixture at a frame, with the document edited by `edit` (a copy) and the input by `extra`. */
function draw(f, w, h, edit = null, extra = {}) {
  const fixture = JSON.parse(JSON.stringify(f));
  if (edit) edit(fixture.document);
  // A plain copy: the engine's objects come from its own realm, which deepStrictEqual tells apart from this one's.
  return JSON.parse(JSON.stringify(R.render(inputOf(fixture, w, h, extra))));
}
const legendOf = (r) => r.primitives.filter((p) => p.role === "legend");
const legendRects = (r) => legendOf(r).filter((p) => p.k === "rect");
const legendTexts = (r) => legendOf(r).filter((p) => p.k === "text");
const marksOf = (r) => r.primitives.filter((p) => p.role === "mark" && p.k !== "group");
/** The extent of primitives along x or y: {min, max} over their boxes (a rounded bar is a path, so its box is read). */
const extent = (ps, axis) => {
  const boxes = ps.map(markBox), lo = axis === "x" ? 0 : 1;
  return { min: Math.min(...boxes.map((b) => b[lo])), max: Math.max(...boxes.map((b) => b[lo + 2])) };
};

// ---------------------------------------------------------------- A.3 the scale

test("A.3: the step at lo and at hi, at each of the six borders (the higher step) and just below each", () => {
  const s = scaleOf(0, 7);
  assert.equal(C.step(s, 0), 1);
  assert.equal(C.step(s, 7), 7);
  for (let k = 1; k <= 6; k++) {
    assert.equal(C.step(s, k), k + 1, "on border " + k);
    assert.equal(C.step(s, k - 1e-9), k, "just below border " + k);
  }
});

test("A.3: hi = lo gives every value the middle step (4), with no division", () => {
  const s = C.sequential([agg(5), agg(5), agg(5)]);
  assert.deepEqual([s.lo, s.hi], [5, 5]);
  assert.equal(C.step(s, 5), 4);
  assert.equal(C.token(s, 5), "--chart-seq-4");
});

test("A.3: a range below zero runs from its lowest to its highest value", () => {
  const s = scaleOf(-14, -7);
  assert.equal(C.step(s, -14), 1);
  assert.equal(C.step(s, -7), 7);
  assert.equal(C.step(s, -10), 5);
  assert.equal(C.step(s, -11), 4);
});

test("A.3: a value that is null or not finite has no step and the null fill; no scale gives no step", () => {
  const s = scaleOf(0, 7);
  for (const v of [null, undefined, NaN, Infinity, -Infinity]) {
    assert.equal(C.step(s, v), null, String(v));
    assert.equal(C.token(s, v), "--chart-grid", String(v));
  }
  assert.equal(C.NONE, "--chart-grid");
  assert.equal(C.step(null, 3), null);
  assert.equal(C.sequential([]), null);
  assert.equal(C.sequential([null, agg(null), agg(Infinity), agg(NaN)]), null);
});

test("A.3: the extreme ranges of D4 take their steps with no intermediate overflow", () => {
  const wide = scaleOf(-1e308, 1e308);
  assert.equal(C.step(wide, 1e308), 7);
  assert.equal(C.step(wide, -1e308), 1);
  assert.equal(C.step(wide, 0), 4);
  assert.equal(C.step(scaleOf(0, 1e308), 5e307), 4);
  assert.equal(C.step(scaleOf(-Number.MAX_VALUE, Number.MAX_VALUE), Number.MAX_VALUE), 7);
  // Two neighbouring floats: the range is one unit in the last place.
  const next = 1 + Number.EPSILON, tiny = scaleOf(1, next);
  assert.equal(C.step(tiny, 1), 1);
  assert.equal(C.step(tiny, next), 7);
});

test("A.3: every step over a sweep of ranges and values is a whole number from 1 to 7", () => {
  const ranges = [[0, 7], [-14, -7], [-1e308, 1e308], [0, 1e308], [1, 1 + Number.EPSILON], [-3.3, 1e-9], [2, 2], [-Number.MAX_VALUE, Number.MAX_VALUE]];
  let n = 0;
  for (const [lo, hi] of ranges) {
    const s = scaleOf(lo, hi);
    for (let i = 0; i <= 64; i++) {
      const v = lo + (hi / 64 - lo / 64) * i;
      const step = C.step(s, Math.min(hi, Math.max(lo, v)));
      assert.ok(Number.isInteger(step) && step >= 1 && step <= 7, lo + ".." + hi + " at " + v + ": " + step);
      n++;
    }
  }
  assert.equal(n, ranges.length * 65);
});

test("A.3: the end texts come from the exact text beyond 2^53, and of two aggregates with one num the further one wins", () => {
  // The sum of BIGINT ['9007199254740993'] has num 9007199254740992 and that exact text.
  const big = C.sequential([agg(5, "5"), agg(9007199254740992, "9007199254740993")]);
  assert.equal(big.hiText, "9,007,199,254,740,993");
  assert.equal(big.loText, "5");
  const tieHi = C.sequential([agg(9007199254740992, "9007199254740992"), agg(9007199254740992, "9007199254740993"), agg(1, "1")]);
  assert.equal(tieHi.hiText, "9,007,199,254,740,993");
  const tieLo = C.sequential([agg(-9007199254740992, "-9007199254740992"), agg(-9007199254740992, "-9007199254740993"), agg(1, "1")]);
  assert.equal(tieLo.loText, "−9,007,199,254,740,993");
  // An exact text beats a float with the same num at either end; a float end is written from the float.
  assert.equal(C.sequential([agg(2), agg(2, "2.00")]).hiText, "2");
  assert.equal(C.sequential([agg(0.5), agg(2.25)]).loText, "0.5");
});

// ---------------------------------------------------------------- A.4 the legend

test("A.4: the legend holds the seven steps in order and the two end texts, at the top by default", () => {
  for (const f of SHADED) {
    if (f.fixture.document.config.encoding.color.legend !== undefined) continue;
    // The two-sided fixtures (brief 1053) have their own legend's tests (diverging.test.mjs).
    if (f.fixture.document.config.encoding.color.scale !== "sequential") continue;
    const r = draw(f.fixture, 960, 540);
    const rects = legendRects(r), texts = legendTexts(r);
    assert.deepEqual(rects.map((p) => p.fill), SEQ, f.name);
    assert.equal(texts.length, 2, f.name);
    // A row: one y for the seven steps, left to right, each sharing its left edge with the previous step's right one.
    assert.equal(new Set(rects.map((p) => p.y)).size, 1, f.name);
    for (let i = 1; i < 7; i++) assert.ok(Math.abs(rects[i].x - (rects[i - 1].x + rects[i - 1].w)) < 0.011, f.name + " step " + (i + 1));
    assert.ok(texts[0].x < rects[0].x && texts[1].x > rects[6].x, f.name + ": lo left of the steps, hi right of them");
    assert.ok(extent(rects, "y").max < extent(marksOf(r), "y").min, f.name + ": above the bars");
  }
  const v = draw(fx("shade-vertical"), 960, 540);
  assert.deepEqual(legendTexts(v).map((p) => p.text), ["−0.05", "0.4"]);
  const b = draw(fx("shade-bigint"), 960, 540);
  assert.deepEqual(legendTexts(b).map((p) => p.text), [fx("shade-bigint").expect.loText, fx("shade-bigint").expect.hiText]);
});

test("A.4: the legend sits where the author wrote it; left and right are a column with step 7 at the top", () => {
  const f = fx("shade-vertical");
  for (const orient of ["top", "bottom", "left", "right"]) {
    const r = draw(f, 960, 540, (d) => (d.config.encoding.color.legend = { orient }));
    const rects = legendRects(r), marks = marksOf(r), texts = legendTexts(r);
    assert.equal(rects.length, 7, orient);
    if (orient === "top") assert.ok(extent(rects, "y").max < extent(marks, "y").min, orient);
    if (orient === "bottom") assert.ok(extent(rects, "y").min > extent(marks, "y").max, orient);
    if (orient === "left") assert.ok(extent(rects, "x").max < extent(marks, "x").min, orient);
    if (orient === "right") assert.ok(extent(rects, "x").min > extent(marks, "x").max, orient);
    if (orient === "left" || orient === "right") {
      assert.deepEqual(rects.map((p) => p.fill), SEQ.slice().reverse(), orient + ": step 7 first");
      assert.equal(new Set(rects.map((p) => p.x)).size, 1, orient + ": one column");
      for (let i = 1; i < 7; i++) assert.ok(rects[i].y > rects[i - 1].y, orient + ": downwards");
      assert.ok(texts[0].y < rects[0].y && texts[1].y > rects[6].y + rects[6].h, orient + ": hi above, lo below");
      assert.deepEqual(texts.map((p) => p.text), ["0.4", "−0.05"], orient);
    } else assert.deepEqual(rects.map((p) => p.fill), SEQ, orient);
  }
  // The fixture that writes `right` is the same column.
  assert.deepEqual(legendRects(draw(fx("shade-legend-right"), 960, 540)).map((p) => p.fill), SEQ.slice().reverse());
});

test("A.4: at the compact level one row at the bottom; none at the minimal level, none under legend: null", () => {
  const f = fx("shade-vertical");
  const compact = draw(f, 300, 180, (d) => (d.config.encoding.color.legend = { orient: "left" }));
  assert.equal(compact.level, "compact");
  const rects = legendRects(compact);
  assert.deepEqual(rects.map((p) => p.fill), SEQ);
  assert.equal(new Set(rects.map((p) => p.y)).size, 1);
  assert.ok(extent(rects, "y").min > extent(marksOf(compact), "y").max, "below the bars");
  const minimal = draw(f, 150, 60);
  assert.equal(minimal.level, "minimal");
  assert.equal(legendOf(minimal).length, 0);
  const none = draw(fx("shade-legend-null"), 960, 540);
  assert.equal(legendOf(none).length, 0);
  assert.ok(marksOf(none).every((p) => SEQ.includes(p.fill)), "the bars keep their colours");
});

test("A.4: the legend is not a switch: its group's legend is null, no primitive of a shaded picture names one, the hidden list is not read", () => {
  for (const f of SHADED) {
    const r = draw(f.fixture, 960, 540);
    const groups = r.primitives.filter((p) => p.k === "group" && p.role === "legend");
    for (const g of groups) assert.equal(g.legend, null, f.name);
    assert.ok(r.primitives.every((p) => p.legend === undefined || p.legend === null), f.name + ": no legend id anywhere");
    const hidden = draw(f.fixture, 960, 540, null, { hidden: ["measure"] });
    assert.equal(hidden.summary.marks, r.summary.marks, f.name + ": a listed id hides nothing");
    assert.deepEqual(hidden.summary.series.map((s) => s.hidden), [false], f.name);
  }
});

test("A.4: a legend that does not fit its box is not drawn and the bars keep their colours", () => {
  const r = draw(fx("shade-bigint"), 240, 160);
  assert.equal(r.level, "compact");
  assert.equal(legendOf(r).length, 0);
  assert.deepEqual(marksOf(r).map((p) => p.fill), ["--chart-seq-7", "--chart-seq-1", "--chart-seq-7"]);
});

test("A.4: a shade field named with markup is the legend group's name and the card line's text, escaped by the string writer", () => {
  const r = draw(fx("shade-markup-field"), 960, 540);
  const group = r.primitives.find((p) => p.k === "group" && p.role === "legend");
  assert.equal(group.label, "<b>margin</b>");
  assert.equal(marksOf(r)[0].tip[2], "<b>margin</b>: 0.12");
  const svg = R.writeString(r.primitives, "standalone", "light");
  assert.equal((svg.match(/<b>/g) || []).length, 0, "no element of its own");
});

// ---------------------------------------------------------------- A.5 the bar

test("A.5: a bar's fill is its shade's step, its card adds the shade's line after the value's", () => {
  const r = draw(fx("shade-vertical"), 960, 540);
  // Mean margins: North (0.12, 0.18) 0.15, South (0.31, 0.09) 0.2, East −0.05, West 0.22, Central 0.4; lo −0.05, hi 0.4.
  assert.deepEqual(marksOf(r).map((p) => [p.tip[0], p.fill]), [["North", "--chart-seq-4"], ["South", "--chart-seq-4"], ["East", "--chart-seq-1"], ["West", "--chart-seq-5"], ["Central", "--chart-seq-7"]]);
  assert.deepEqual(marksOf(r)[0].tip, ["North", "150.5", "margin: 0.15"]);
  assert.deepEqual(r.summary.series, [{ id: "measure", title: "amount", hidden: false }]);
});

test("A.5: count of a numeric column: [1, null, 2] counts 2, [null, null] counts 0 and takes the lowest step, not the null fill", () => {
  const f = fx("shade-count-nulls"), r = draw(f, 960, 540), marks = marksOf(r);
  for (const m of marks) {
    const name = m.tip[0];
    assert.equal(m.fill, f.expect.fills[name], name);
    assert.deepEqual(m.tip, f.expect.tips[name], name);
    assert.equal(m.tip[2], "units: " + f.expect.shades[name], name);
  }
  assert.equal(marks.length, 2);
});

test("A.5: the helper's count is unchanged: a value's count counts every row, nulls included", () => {
  assert.equal(R.data.aggregate([1, null, 2], "count").num, 3);
});

test("A.5: a null shade beside a drawn bar is drawn in the null fill, with the null detail's line", () => {
  const r = draw(fx("shade-null-beside-drawn"), 960, 540);
  assert.deepEqual(marksOf(r).map((p) => [p.fill, p.tip[2]]), [["--chart-seq-4", "margin: 1"], ["--chart-grid", "margin: "]]);
  assert.equal(r.summary.marks, 2);
});

test("A.5: a BIGINT shade beyond 2^53 is written from its exact text in the legend and the card", () => {
  const f = fx("shade-bigint"), r = draw(f, 960, 540);
  assert.equal(legendTexts(r)[1].text, f.expect.hiText);
  assert.deepEqual(marksOf(r).find((p) => p.tip[0] === "A").tip, f.expect.tips.A);
});

test("A.5: every bar has the same mark count and position with a shade as without a colour", () => {
  for (const f of SHADED) {
    const shaded = draw(f.fixture, 960, 540), plain = draw(f.fixture, 960, 540, (d) => delete d.config.encoding.color);
    assert.equal(shaded.summary.marks, plain.summary.marks, f.name);
    const at = (r) => marksOf(r).map((p) => [p.k, p.x, p.w, p.d]);
    if (legendOf(shaded).length === 0) assert.deepEqual(at(shaded), at(plain), f.name);
  }
});

test("A.5 / D6, turned round by brief 1053 D9: a diverging scale handed to the engine draws, its bars on the two-sided ramp", () => {
  const r = draw(fx("shade-vertical"), 960, 540, (d) => (d.config.encoding.color.scale = "diverging"));
  assert.equal(r.state, "rendered");
  assert.equal(r.summary.refusal, null);
  const fills = marksOf(r).map((p) => p.fill);
  assert.equal(fills.length, 5);
  for (const f of fills) assert.match(f, /^--chart-div-[1-7]$/);
  assert.deepEqual(legendRects(r).map((p) => p.fill), [1, 2, 3, 4, 5, 6, 7].map((n) => "--chart-div-" + n));
});

test("A.5: the I3 oracle reads a shade from the document alone", () => {
  const doc = (color) => ({
    document: { config: { mark: { type: "bar" }, encoding: { x: { field: "b", type: "nominal" }, y: { field: "v", type: "quantitative", aggregate: "sum" }, color } } },
  });
  const shade = { field: "s", type: "quantitative", aggregate: "sum", scale: "sequential" };
  assert.equal(expectedBarMarks({ ...doc(shade), columns: { b: ["A", "B"], v: [10, 20], s: [1, null] } }, []), 2);
  assert.equal(expectedBarMarks({ ...doc(shade), columns: { b: ["A", "A"], v: [10, 20], s: [1, 2] } }, []), 1);
  // A series colour as at the base: one cell per colour value, a null colour drops its row.
  assert.equal(expectedBarMarks({ ...doc({ field: "s", type: "nominal" }), columns: { b: ["A", "B"], v: [10, 20], s: [1, null] } }, []), 1);
  assert.equal(expectedBarMarks(fx("parity-bar-grouped"), []), 6);
});
