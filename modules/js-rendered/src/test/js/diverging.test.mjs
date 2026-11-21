// Brief 1053 A.3, A.4 and A.6 (D6 to D8, D10): the two-sided colour scale (19-colour.js, the kind "diverging"), its end
// texts, its legend on the bar, and LA2 for the thirteen tokens of the look contract's §3.6 and the sequential ramp.
// Every expected step is computed HERE from the contract's rule in comparisons (d = the distance from the middle; the
// step moves one out for each of 6 × d ≥ m, 2 × d ≥ m, 6 × d ≥ 5 × m), on values where those products are exact, never
// by the function under test (LA18).
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, chartFixtures, inputOf, typeFixtures, typeInputOf } from "./engine.mjs";

const R = engine();
const C = R.colour;
const FIXTURES = chartFixtures();
const fx = (name) => FIXTURES.find((f) => f.wire === "bar" && f.name === name).fixture;
const DIVERGING = FIXTURES.filter((f) => f.wire === "bar" && f.name.startsWith("shade-diverging-"));
const agg = (num, exact = null) => ({ num, exact });
const DIV = [1, 2, 3, 4, 5, 6, 7].map((n) => "--chart-div-" + n);
const SEQ = [1, 2, 3, 4, 5, 6, 7].map((n) => "--chart-seq-" + n);
/** The two-sided scale over plain numbers around `mid`. */
const scaleOf = (nums, mid) => C.diverging(nums.map((n) => agg(n)), mid);

/** The contract's step of v, computed by the test in comparisons: 4 ± (6d ≥ m) + (2d ≥ m) + (6d ≥ 5m); m of zero → 4. */
function expectStep(v, c, m) {
  if (m === 0 || v === c) return 4;
  const d = v > c ? v - c : c - v;
  const k = (6 * d >= m ? 1 : 0) + (2 * d >= m ? 1 : 0) + (6 * d >= 5 * m ? 1 : 0);
  return v > c ? 4 + k : 4 - k;
}

/** The picture of a fixture at a frame, with the document edited by `edit` (a copy) and the input by `extra`. */
function draw(f, w, h, edit = null, extra = {}, E = R) {
  const fixture = JSON.parse(JSON.stringify(f));
  if (edit) edit(fixture.document);
  return JSON.parse(JSON.stringify(E.render(inputOf(fixture, w, h, extra))));
}
const legendOf = (r) => r.primitives.filter((p) => p.role === "legend");
const legendRects = (r) => legendOf(r).filter((p) => p.k === "rect");
const legendTexts = (r) => legendOf(r).filter((p) => p.k === "text").map((p) => p.text);
const marksOf = (r) => r.primitives.filter((p) => p.role === "mark" && p.k !== "group");

/** Each bar's shade value of a one-row-per-band fixture, in band order (null where the row's value is null). */
const shadesOf = (f) => f.columns[f.document.config.encoding.color.field];

/** Every number anywhere in a value (a primitive, its arrays and objects). */
function numbers(v, out = []) {
  if (typeof v === "number") out.push(v);
  else if (Array.isArray(v)) v.forEach((x) => numbers(x, out));
  else if (v !== null && typeof v === "object") Object.values(v).forEach((x) => numbers(x, out));
  return out;
}

// ---------------------------------------------------------------- A.3 the scale

test("A.3 / LA18: the steps of fixtures (1) to (7), each computed by the test from the contract's rule, on the bars' fills", () => {
  const cases = ["seven", "mid-100", "mid-minus-100", "one-side", "single", "all-middle", "all-mid-5", "borders"];
  let bars = 0;
  for (const name of cases) {
    const f = fx("shade-diverging-" + name), vals = shadesOf(f), mid = f.document.config.encoding.color.mid;
    const c = mid === undefined ? 0 : mid, m = Math.max(...vals.map((v) => (v > c ? v - c : c - v)));
    const want = vals.map((v) => "--chart-div-" + expectStep(v, c, m));
    assert.deepEqual(marksOf(draw(f, 960, 540)).map((p) => p.fill), want, name);
    bars += want.length;
  }
  // The literal steps the brief names, as a second statement of the same rule.
  assert.deepEqual(marksOf(draw(fx("shade-diverging-seven"), 960, 540)).map((p) => p.fill), DIV, "(1): the seven steps, -30 at step 1 and 30 at step 7");
  assert.deepEqual(marksOf(draw(fx("shade-diverging-borders"), 960, 540)).map((p) => p.fill.slice(-1)).join(""), "11235677", "(7): the six borders");
  assert.deepEqual(marksOf(draw(fx("shade-diverging-one-side"), 960, 540)).map((p) => p.fill.slice(-1)).join(""), "4567", "(4): steps 4 to 7 only");
  assert.deepEqual(marksOf(draw(fx("shade-diverging-single"), 960, 540)).map((p) => p.fill), ["--chart-div-7"], "(5): step 7");
  console.log(`LA18: ${cases.length} fixtures, ${bars} bars, each step computed by the test`);
});

test("A.3: a value exactly on each negative border (q = -0.5, -1.5, -2.5) takes the step farther from the middle", () => {
  const s = scaleOf([-6, 6]);
  assert.equal(s.m, 6);
  assert.equal(C.step(s, -1), 3, "q = -0.5");
  assert.equal(C.step(s, -3), 2, "q = -1.5");
  assert.equal(C.step(s, -5), 1, "q = -2.5");
  assert.equal(C.step(s, 1), 5, "q = 0.5");
  assert.equal(C.step(s, 3), 6, "q = 1.5");
  assert.equal(C.step(s, 5), 7, "q = 2.5");
  for (const v of [-6, -5, -3, -1, 0, 1, 3, 5, 6]) assert.equal(C.step(s, v), expectStep(v, 0, 6), "v " + v);
});

test("A.3: a value just inside the first border stays in the middle (m 3, v - c = 0.49999999999999994: step 4)", () => {
  const s = scaleOf([-3, 3]);
  assert.equal(C.step(s, 0.49999999999999994), 4);
  assert.equal(C.step(s, -0.49999999999999994), 4);
  assert.equal(C.step(s, 0.5), 5);
  assert.equal(C.step(s, -0.5), 3);
});

test("A.3: no intermediate overflows: c 0 with -1e308 and 1e308 gives steps 1 and 7; c 1e308 with -1e308 gives step 1", () => {
  const wide = scaleOf([-1e308, 1e308]);
  assert.equal(C.step(wide, -1e308), 1);
  assert.equal(C.step(wide, 1e308), 7);
  assert.equal(C.step(wide, 0), 4);
  const far = scaleOf([-1e308], 1e308);
  assert.equal(far.m, Infinity, "m itself overflows");
  assert.ok(Number.isFinite(far.h), "h is finite");
  assert.equal(C.step(far, -1e308), 1);
  const both = scaleOf([-1e308, 1e308], 1e308);
  assert.deepEqual([C.step(both, -1e308), C.step(both, 1e308)], [1, 4]);
  const max = scaleOf([-Number.MAX_VALUE, Number.MAX_VALUE]);
  assert.deepEqual([C.step(max, -Number.MAX_VALUE), C.step(max, Number.MAX_VALUE)], [1, 7]);
  // Fixture (12) draws two bars, steps 1 and 4.
  assert.deepEqual(marksOf(draw(fx("shade-diverging-extreme"), 960, 540)).map((p) => p.fill), ["--chart-div-1", "--chart-div-4"]);
});

test("A.3: m of zero, with and without a mid: step 4 for every value, no division, no throw", () => {
  const zero = scaleOf([0, 0, 0]);
  assert.deepEqual([zero.c, zero.m], [0, 0]);
  assert.equal(C.step(zero, 0), 4);
  assert.equal(C.token(zero, 0), "--chart-div-4");
  const five = scaleOf([5, 5], 5);
  assert.deepEqual([five.c, five.m], [5, 0]);
  assert.equal(C.step(five, 5), 4);
  for (const name of ["all-middle", "all-mid-5"]) {
    assert.deepEqual(marksOf(draw(fx("shade-diverging-" + name), 960, 540)).map((p) => p.fill), [DIV[3], DIV[3], DIV[3]], name);
  }
});

test("G5 (the ratio check's proof): with the guard for m of zero removed in a copy, fixture (6) throws naming the ratio", () => {
  const anchor = "    if (m === 0) return MIDDLE;\n    var d = v - c";
  const E = engine((s) => {
    assert.equal(s["19-colour.js"].split(anchor).length, 2, "the plant's anchor occurs once");
    s["19-colour.js"] = s["19-colour.js"].replace(anchor, "    var d = v - c");
  });
  for (const name of ["all-middle", "all-mid-5"]) {
    assert.throws(() => draw(fx("shade-diverging-" + name), 960, 540, null, {}, E), /ratio 3 × \(v − c\) ÷ m is not a finite number/, name);
  }
  // A scale with a spread is drawn by the planted copy as by the engine: the check fires only on 0 ÷ 0.
  assert.deepEqual(draw(fx("shade-diverging-seven"), 960, 540, null, {}, E), draw(fx("shade-diverging-seven"), 960, 540));
});

test("A.3: a value that is null or not finite has no step and the null fill; no scale where no value is finite", () => {
  const s = scaleOf([-2, 2]);
  for (const v of [null, undefined, NaN, Infinity, -Infinity]) {
    assert.equal(C.step(s, v), null, String(v));
    assert.equal(C.token(s, v), "--chart-grid", String(v));
  }
  assert.equal(C.diverging([null, agg(null), agg(NaN), agg(Infinity)]), null);
  assert.equal(C.diverging([]), null);
  assert.throws(() => C.diverging([agg(1)], Infinity), /middle is not a finite number/);
  assert.deepEqual(marksOf(draw(fx("shade-diverging-null"), 960, 540)).map((p) => p.fill), ["--chart-div-1", "--chart-grid", "--chart-div-6", "--chart-div-7"]);
});

test("A.3: every step over a sweep of middles, spreads and values is a whole number from 1 to 7, and the test's own", () => {
  const sets = [[[-30, 30], 0], [[90, 110], 100], [[-110, -90], -100], [[0, 50], undefined], [[-1e308, 1e308], undefined], [[-1e308], 1e308], [[1, 1 + Number.EPSILON], 1],
    [[-0.3, 0.1], 0.05], [[-Number.MAX_VALUE, Number.MAX_VALUE], 0], [[5e-324, 1e-323], 0], [[7, 7], 7]];
  let n = 0, exact = 0;
  for (const [nums, mid] of sets) {
    const s = scaleOf(nums, mid), lo = Math.min(...nums), hi = Math.max(...nums);
    for (let i = 0; i <= 64; i++) {
      const v = Math.min(hi, Math.max(lo, lo + (hi / 64 - lo / 64) * i));
      const step = C.step(s, v);
      assert.ok(Number.isInteger(step) && step >= 1 && step <= 7, nums + " mid " + mid + " at " + v + ": " + step);
      n++;
      // Where c, m and v are small whole numbers the products of the test's rule are exact: the step is the rule's.
      if ([s.c, s.m, v].every((x) => Number.isInteger(x) && Math.abs(x) < 2 ** 40)) {
        assert.equal(step, expectStep(v, s.c, s.m), nums + " at " + v);
        exact++;
      }
    }
  }
  assert.equal(n, sets.length * 65);
  assert.ok(exact >= 80, "steps compared with the rule: " + exact);
});

// ---------------------------------------------------------------- A.4 the end texts

test("A.4: the end texts of fixtures (2), (3), (4), (5), (6) and (9), (9) to the last digit", () => {
  const ends = (name) => legendTexts(draw(fx("shade-diverging-" + name), 960, 540));
  assert.deepEqual(ends("mid-100"), ["90", "110"]);
  assert.deepEqual(ends("mid-minus-100"), ["−110", "−90"]);
  assert.deepEqual(ends("one-side"), ["−50", "50"]);
  assert.deepEqual(ends("single"), ["−10", "10"]);
  assert.deepEqual(ends("all-middle"), ["0", "0"]);
  assert.deepEqual(ends("all-mid-5"), ["5", "5"]);
  assert.deepEqual(ends("exact"), ["−9,007,199,254,740,993", "9,007,199,254,740,993"]);
  assert.deepEqual(ends("exact-mid-100"), ["−9,007,199,254,740,793", "9,007,199,254,740,993"]);
  assert.deepEqual(ends("seven"), ["−30", "30"]);
});

test("A.4: an end where two values share one num takes the one further out; the mirror is then of that text", () => {
  const hi = C.diverging([agg(9007199254740992, "9007199254740992"), agg(9007199254740992, "9007199254740993"), agg(1, "1")]);
  assert.equal(hi.hiText, "9,007,199,254,740,993");
  assert.equal(hi.loText, "−9,007,199,254,740,993");
  const lo = C.diverging([agg(-9007199254740992, "-9007199254740992"), agg(-9007199254740992, "-9007199254740993"), agg(1, "1")]);
  assert.equal(lo.loText, "−9,007,199,254,740,993");
  assert.equal(lo.hiText, "9,007,199,254,740,993");
  // Both ends stand at a drawn value: each is its own source; an exact text beats a float with the same num.
  const both = C.diverging([agg(-2), agg(2, "2.00"), agg(2)]);
  assert.deepEqual([both.loText, both.hiText], ["−2", "2"]);
  // A decimal mirror keeps its digits: mid 0.5, far value 1.25 → 2 × 0.5 − 1.25 = −0.25.
  assert.equal(C.diverging([agg(1.25, "1.25")], 0.5).loText, "−0.25");
  // A far value without an exact text is mirrored from numbers.
  assert.equal(C.diverging([agg(7)], 2).loText, "−3");
});

test("A.4: a mid whose text has an exponent (1e21) beside an exact column: the mirror from numbers, and finite", () => {
  const s = C.diverging([agg(2e21, "2000000000000000000000"), agg(1e21, "1000000000000000000000")], 1e21);
  assert.equal(String(s.c), "1e+21");
  assert.equal(s.hiText, "2,000,000,000,000,000,000,000");
  assert.equal(s.loText, "0");
  const t = C.diverging([agg(1e21 + 2 ** 70, "1001180591620717411303424")], 1e21);
  assert.ok(typeof t.loText === "string" && !/Infinity|NaN/.test(t.loText), t.loText);
});

test("A.4: an end that is not a finite number and has no exact text: no legend; the marks keep their colours; no number that is not finite", () => {
  const s = scaleOf([-1e308, 1e308], 1e308);
  assert.equal(s.hiText, null);
  assert.equal(C.legend(s, { place: "top", label: "x" }), null);
  for (const [w, h] of [[960, 540], [480, 360], [300, 180], [120, 40], [1600, 480]]) {
    const r = draw(fx("shade-diverging-extreme"), w, h);
    assert.equal(legendOf(r).length, 0, w + "x" + h);
    for (const p of r.primitives) {
      for (const n of numbers(p)) assert.ok(Number.isFinite(n), p.k + "/" + p.role + " carries " + n);
      for (const t of [p.text, p.shown, ...(p.tip || [])]) if (typeof t === "string") assert.ok(!/Infinity|NaN/.test(t), t);
    }
  }
});

// ---------------------------------------------------------------- A.4 the legend

test("A.4 / D8: the legend's seven fills are --chart-div-1 to -7 in order, step 1 at the low text, in a row and in a column", () => {
  const row = draw(fx("shade-diverging-seven"), 960, 540);
  const rects = legendRects(row), texts = legendOf(row).filter((p) => p.k === "text");
  assert.deepEqual(rects.map((p) => p.fill), DIV);
  assert.equal(new Set(rects.map((p) => p.y)).size, 1, "one row");
  assert.ok(texts[0].x < rects[0].x && texts[1].x > rects[6].x, "the low text left of step 1, the high text right of step 7");
  assert.deepEqual(texts.map((p) => p.text), ["−30", "30"]);
  for (const p of rects) assert.equal(p.color, R.theme.resolve("light").css(p.fill), p.fill + " in the light default");
  const col = draw(fx("shade-diverging-legend-right"), 960, 540);
  const crects = legendRects(col), ctexts = legendOf(col).filter((p) => p.k === "text");
  assert.deepEqual(crects.map((p) => p.fill), DIV.slice().reverse(), "a column: step 7 at the top");
  assert.equal(new Set(crects.map((p) => p.x)).size, 1);
  assert.ok(ctexts[0].y < crects[0].y && ctexts[1].y > crects[6].y + crects[6].h, "the high text above, the low text below");
  assert.deepEqual(ctexts.map((p) => p.text), ["30", "−30"]);
  // The colours are the theme's: the legend's step and a bar of the same step carry one colour.
  const bars = marksOf(row);
  for (let i = 0; i < 7; i++) assert.equal(rects[i].color, bars[i].color, DIV[i]);
});

test("A.4: the legend at each written place, at the compact level one row, none at minimal, under legend: null, in (12) and where it does not fit", () => {
  const f = fx("shade-diverging-seven");
  for (const orient of ["top", "bottom", "left", "right"]) {
    const r = draw(f, 960, 540, (d) => (d.config.encoding.color.legend = { orient }));
    const rects = legendRects(r);
    assert.deepEqual(rects.map((p) => p.fill), orient === "left" || orient === "right" ? DIV.slice().reverse() : DIV, orient);
  }
  const compact = draw(f, 300, 180, (d) => (d.config.encoding.color.legend = { orient: "left" }));
  assert.equal(compact.level, "compact");
  assert.deepEqual(legendRects(compact).map((p) => p.fill), DIV);
  assert.equal(new Set(legendRects(compact).map((p) => p.y)).size, 1);
  const minimal = draw(f, 150, 60);
  assert.equal(minimal.level, "minimal");
  assert.equal(legendOf(minimal).length, 0);
  const none = draw(fx("shade-diverging-legend-null"), 960, 540);
  assert.equal(legendOf(none).length, 0);
  assert.deepEqual(marksOf(none).map((p) => p.fill.slice(-1)).join(""), "13457", "the bars keep their colours");
  assert.equal(legendOf(draw(fx("shade-diverging-extreme"), 960, 540)).length, 0);
  // The exact ends are wide: at 240 × 160 (compact) the row does not fit, so no legend; the bars keep their steps.
  const tight = draw(fx("shade-diverging-exact"), 240, 160);
  assert.equal(tight.level, "compact");
  assert.equal(legendOf(tight).length, 0);
  assert.deepEqual(marksOf(tight).map((p) => p.fill), ["--chart-div-7", "--chart-div-4"]);
});

test("A.4: the legend is not a switch: no group of a two-sided picture names a legend entry, the hidden list is not read", () => {
  for (const f of DIVERGING) {
    const r = draw(f.fixture, 960, 540);
    for (const g of r.primitives.filter((p) => p.k === "group" && p.role === "legend")) assert.equal(g.legend, null, f.name);
    assert.ok(r.primitives.every((p) => p.legend === undefined || p.legend === null), f.name);
    const hidden = draw(f.fixture, 960, 540, null, { hidden: ["measure"] });
    assert.equal(hidden.summary.marks, r.summary.marks, f.name);
  }
  assert.ok(DIVERGING.length >= 12, "two-sided fixtures: " + DIVERGING.length);
});

test("A.4 / G8: a two-sided legend never draws the one-sided ramp; a one-sided legend never draws the two-sided one", () => {
  for (const f of FIXTURES.filter((x) => x.wire === "bar" && x.name.startsWith("shade-"))) {
    const r = draw(f.fixture, 960, 540), fills = legendRects(r).map((p) => p.fill);
    const two = f.fixture.document.config.encoding.color.scale === "diverging";
    for (const t of fills) assert.ok((two ? DIV : SEQ).includes(t), f.name + ": " + t);
    for (const t of marksOf(r).map((p) => p.fill)) assert.ok([...(two ? DIV : SEQ), "--chart-grid"].includes(t), f.name + ": bar " + t);
  }
});

test("D6: R.colour.of chooses the kind from the shade, and refuses a scale it does not know", () => {
  const vals = [agg(-1), agg(3)];
  assert.equal(C.of({ scale: "sequential" }, vals).kind, "sequential");
  assert.deepEqual(C.of({ scale: "sequential" }, vals), C.sequential(vals));
  assert.deepEqual(C.of({ scale: "diverging", mid: 1 }, vals), C.diverging(vals, 1));
  assert.equal(C.of({ scale: "diverging" }, vals).c, 0);
  assert.throws(() => C.of({ scale: "rainbow" }, vals), /no scale rainbow/);
});

// ---------------------------------------------------------------- A.6 / D10: a token reaches the picture (LA2)

/** The indices and changed field names of two primitive lists of the same length (look.test.mjs's LA2 form). */
function diff(a, b) {
  assert.equal(a.length, b.length, "the same number of primitives");
  const out = [];
  for (let i = 0; i < a.length; i++) {
    const fields = Object.keys(a[i]).filter((k) => JSON.stringify(a[i][k]) !== JSON.stringify(b[i][k]));
    if (fields.length) out.push({ i, p: a[i], fields });
  }
  return out;
}

/** For one token: the primitives a second value changes over `draws` (each draw(tokens) → primitives), all in colour only. */
function reach(token, draws) {
  let changed = 0;
  for (const d of draws) {
    for (const { p, fields } of diff(d({}), d({ [token]: [1, 2, 3] }))) {
      const governs = (p.fill === token && fields.join() === "color") || (p.stroke === token && fields.join() === "strokeColor");
      assert.ok(governs, token + " changed " + p.k + "/" + p.role + " fields " + fields.join());
      changed++;
    }
  }
  return changed;
}

const barDraw = (f) => (tokens) => R.render(inputOf(f.fixture, 960, 540, { theme: { base: "light", tokens } })).primitives;
const TONES = ["--chart-positive", "--chart-negative", "--chart-total", "--chart-good", "--chart-warn", "--chart-bad"];

test("LA2 (D10): each of the twenty tokens alone given a second value changes exactly the primitives naming it, in colour only", () => {
  const counts = {};
  const seqFixtures = FIXTURES.filter((f) => f.wire === "bar" && f.name.startsWith("shade-") && !f.name.startsWith("shade-diverging-"));
  for (const t of DIV) counts[t] = reach(t, DIVERGING.map(barDraw));
  for (const t of SEQ) counts[t] = reach(t, seqFixtures.map(barDraw));
  const T = engineWith(["test_tones"]), tones = typeFixtures("test_tones");
  for (const t of TONES) counts[t] = reach(t, tones.map((f) => (tokens) => T.render(typeInputOf(f.fixture, 960, 540, { theme: { base: "light", tokens } })).primitives));
  console.log("LA2 (D10) primitives changed: " + Object.entries(counts).map(([t, n]) => t + " " + n).join(", "));
  assert.equal(Object.keys(counts).length, 20);
  for (const [t, n] of Object.entries(counts)) assert.ok(n > 0, t + " reached no primitive");
  // Fixture (1) draws each of the seven two-sided steps on a bar, and the sequential fixture each one-sided step.
  assert.deepEqual(marksOf(draw(fx("shade-diverging-seven"), 960, 540)).map((p) => p.fill), DIV);
  assert.deepEqual(marksOf(draw(fx("shade-seven-steps"), 960, 540)).map((p) => p.fill), SEQ);
});

test("LA2 (D10): test_tones draws each of the six colours with a meaning once, in its default colour, with no legend", () => {
  const T = engineWith(["test_tones"]);
  for (const theme of ["light", "dark"]) {
    const r = JSON.parse(JSON.stringify(T.render(typeInputOf(typeFixtures("test_tones")[0].fixture, 960, 540, { theme }))));
    const marks = r.primitives.filter((p) => p.role === "mark");
    assert.deepEqual(marks.map((p) => p.fill), TONES, theme);
    assert.deepEqual(marks.map((p) => p.color), TONES.map((t) => T.theme.resolve(theme).css(t)), theme);
    assert.equal(r.primitives.filter((p) => p.role === "legend").length, 0, theme);
  }
});

test("§3.7: each of the thirteen tokens is an opaque colour: given an alpha, the resolver throws", () => {
  for (const t of [...TONES, ...DIV]) {
    assert.throws(() => R.theme.resolve({ base: "light", tokens: { [t]: [1, 2, 3, 0.5] } }), new RegExp(t + " is not an opaque colour"), t);
    assert.doesNotThrow(() => R.theme.resolve({ base: "dark", tokens: { [t]: [1, 2, 3] } }), t);
  }
});
