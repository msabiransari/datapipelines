// Brief 1051 A.3 (D5): the second value axis at the right, over test_two_axes, on the left axis's one grid. In EVERY
// fixture and frame: as many right ticks as left ticks, each at a left tick's position; every grid line at a left
// tick; the right labels right of the plot and inside the frame; the right title at `full` only; value2.lo and hi the
// scale's values at the plot's edges; and the left axis's TWIN (the same input without `second`, with `right` set to
// the column read from the picture) has the same plot, left ticks and x labels. By case, the expected values are the
// test's own: (a) the ladder step the test finds itself, (b) the powers from the record's lower bound, (c) the right
// range's value at each left position (Math.pow; the engine has no power function) and its three significant digits.
// G3 is falsified here: each check run over a planted copy must fail, naming what it checks.
import { test } from "node:test";
import assert from "node:assert/strict";
import { typeFixtures } from "./engine.mjs";
import { textBox, TOL, WIDTHS, HEIGHTS } from "./geometry.mjs";
import { formsEngine, plant, fixture, draw, plotRect, leftLabels, rightLabels, underLabels, near, SIX } from "./forms.mjs";

const R = formsEngine();
const TYPE = "test_two_axes";
const SWEEP = WIDTHS.flatMap((w) => HEIGHTS.map((h) => [w, h]));
const AXES = "11-axes.js", CART = "12-cartesian.js";
const LADDER = [1, 2, 2.5, 5];

/** The twin's model: no second axis (and no line, the only marks that read it), `right` px kept right of the plot. */
const twin = (right) => (m) => {
  delete m.layout.second;
  m.layout.right = right;
  m.line = m.line.map(() => null);
};

/** The share of v on an axis; ends whose difference overflows a double are quartered first (the test's own way). */
function share(ax, v) {
  if (ax.log) return (Math.log10(v) - Math.log10(ax.lo)) / (Math.log10(ax.hi) - Math.log10(ax.lo));
  return Number.isFinite(ax.hi - ax.lo) ? (v - ax.lo) / (ax.hi - ax.lo) : (v / 4 - ax.lo / 4) / (ax.hi / 4 - ax.lo / 4);
}

/** The value at share t of a linear range, quartered where hi - lo overflows (the test's own way, not the engine's). */
function valueAt(lo, hi, t) {
  return Number.isFinite(hi - lo) ? lo + t * (hi - lo) : 4 * (lo / 4 + t * (hi / 4 - lo / 4));
}

/** The line's extent as the test reads the data (numbers; on a log axis positive ones), widened as a nice range widens one value. */
function extentOf(fx) {
  const e2 = fx.document.config.encoding.y2, Y2 = fx.columns[e2.field].filter((v) => typeof v === "number" && (!e2.log || v > 0));
  if (Y2.length === 0) return e2.log ? null : [0, 1];
  return [Math.min(...Y2), Math.max(...Y2)];
}

/**
 * Case (a)'s step and its first multiple, found by the test: the smallest step of the ladder whose range holds the
 * extent. "Holds" is judged against the DATA: within a share 1e-9 of the extent's own span (rounding noise), never of
 * the candidate range's span, which grows with the step and once let 0..1e9 "hold" [-1, 1] (round 3, R1).
 */
function ladderA(ext, f0, df) {
  let [e0, e1] = ext;
  if (!(e1 > e0)) [e0, e1] = e0 === 0 ? [0, 1] : e0 > 0 ? [0, e0] : [e0, 0];
  const need = (e1 - e0) * df, tol = 1e-9 * (e1 - e0);
  for (let p = Math.floor(Math.log10(need)) - 1; p < Math.floor(Math.log10(need)) + 14; p++) {
    for (const f of LADDER) {
      const s = f * 10 ** p;
      if (s < need * (1 - 1e-12)) continue;
      const span = s / df, k = Math.floor((e0 + tol + f0 * span) / s), lo = k * s - f0 * span;
      if (lo <= e0 + tol && lo + span >= e1 - tol) return { s, k, lo, hi: lo + span, e0, e1 };
    }
  }
  return null;
}

/**
 * Where the engine chose the right range (no domain), it holds the data: the line's smallest and largest number (a log
 * axis: its positive ones) lie inside value2.lo..hi, within a share 1e-9 of the DATA's span on a linear axis (one value:
 * exactly) and a relative 1e-12 on a log one. Independent of the case and of the ladder (round 3, R1).
 */
function holdsData(fx, v2, at) {
  const e2 = fx.document.config.encoding.y2;
  if (e2.domain) return [];
  const Y2 = fx.columns[e2.field].filter((v) => typeof v === "number" && (!e2.log || v > 0));
  if (Y2.length === 0) return [];
  const min = Math.min(...Y2), max = Math.max(...Y2);
  const ok = e2.log ? v2.lo <= min * (1 + 1e-12) && v2.hi >= max * (1 - 1e-12) : v2.lo <= min + 1e-9 * (max - min) && v2.hi >= max - 1e-9 * (max - min);
  return ok ? [] : [`the right range ${v2.lo}..${v2.hi} leaves out data of ${min}..${max} ${at}`];
}

/** Three significant digits, one more for all while two neighbours share one (the test's own rounding, by toPrecision). */
function digits(vals) {
  for (let d = 3; d <= 17; d++) {
    const r = vals.map((v) => Number(v.toPrecision(d)));
    if (r.every((v, i) => i === 0 || v !== r[i - 1])) return r;
  }
  return vals;
}

/** A.3's checks over one picture: {problems, rule} where rule is the case the test expected. */
function secondProblems(fx, w, h, E = R) {
  let res;
  try {
    res = draw(E, fx, w, h);
  } catch (err) {
    return { problems: [`render threw at ${w}x${h}: ${err.message}`], rule: null };
  }
  const { r, hand } = res, out = [], at = `(${w}x${h})`;
  if (r.state !== "rendered") return { problems: out, rule: null };
  const v = hand.value, v2 = hand.value2, plot = hand.plot, bottom = plot.y + plot.h, n = v.ticks.length;
  const pos = v.ticks.map((t) => v.scale(t));
  if (v2.ticks.length !== n) out.push(`right tick count ${v2.ticks.length}, left ${n} ${at}`);
  for (let k = 0; k < v2.ticks.length; k++) {
    const y = v2.scale(v2.ticks[k]);
    if (!pos.some((p) => near(p, y, 0.01))) out.push(`right tick ${v2.ticks[k]} at ${y} lies on no left tick ${at}`);
  }
  if (!near(v2.scale(v2.lo), bottom, 0.011) || !near(v2.scale(v2.hi), plot.y, 0.011)) out.push(`value2 ${v2.lo}..${v2.hi} is not the scale at the plot's edges ${at}`);
  for (const g of r.primitives.filter((p) => p.k === "line" && p.role === "grid" && p.y1 === p.y2)) if (!pos.some((p) => near(p, g.y1, 0.011))) out.push(`grid line at ${g.y1} on no left tick ${at}`);
  for (const t of rightLabels(r, plot)) {
    const xs = textBox(t).map(([x]) => x);
    if (Math.min(...xs) < plot.x + plot.w) out.push(`right label ${t.text} starts inside the plot ${at}`);
    if (Math.max(...xs) > w + TOL) out.push(`right label ${t.text} leaves the frame at ${Math.max(...xs)} ${at}`);
  }
  const title = r.primitives.filter((p) => p.k === "text" && p.role === "axis" && p.x > plot.x + plot.w);
  if (r.level !== "full" && title.length > 0) out.push(`a right title at ${r.level} ${at}`);

  // The twin: the same input without second, with right = the column read from the picture (the frame's right edge
  // less its edge, less the plot's right side). The minimal level draws no plot background and keeps no column.
  if (r.level !== "minimal") {
    const pr = plotRect(r), column = w - 0.5 * 12 - (pr.x + pr.w), t = draw(E, fx, w, h, {}, twin(column));
    const tp = t.hand.plot;
    if (!["x", "y", "w", "h"].every((k) => near(tp[k], plot[k], 0.011))) out.push(`twin plot ${JSON.stringify(tp)}, with second ${JSON.stringify(plot)} ${at}`);
    const same = (a, b) => a.length === b.length && a.every((p, i) => p.text === b[i].text && p.shown === b[i].shown && p.rotate === b[i].rotate && near(p.x, b[i].x, 0.011) && near(p.y, b[i].y, 0.011));
    if (!same(leftLabels(r, plot), leftLabels(t.r, tp))) out.push(`twin left ticks differ ${at}`);
    if (!same(underLabels(r, plot), underLabels(t.r, tp))) out.push(`twin x labels differ ${at}`);
  }

  out.push(...holdsData(fx, v2, at));

  // The case the test expects, and its values.
  const e2 = fx.document.config.encoding.y2, sh = v.ticks.map((x) => share(v, x)), df = n >= 2 ? sh[1] - sh[0] : 0;
  const even = n >= 2 && df > 1e-9 && sh.every((s, j) => Math.abs(s - sh[0] - j * df) <= 1e-9), ext = extentOf(fx);
  let rule = "c";
  if (even && !e2.domain && !e2.log) {
    const a = ladderA(ext, sh[0], df);
    if (a !== null) {
      rule = "a";
      const want = v.ticks.map((_, j) => (a.k + j) * a.s);
      if (!v2.ticks.every((x, j) => Math.abs(x - want[j]) <= 1e-9 * a.s)) out.push(`(a) right ticks ${v2.ticks}, the ladder step ${a.s} gives ${want} ${at}`);
      if (!(v2.lo <= a.e0 + 1e-9 * (a.hi - a.lo) && v2.hi >= a.e1 - 1e-9 * (a.hi - a.lo))) out.push(`(a) the range ${v2.lo}..${v2.hi} does not hold the extent ${a.e0}..${a.e1} ${at}`);
    }
  } else if (even && !e2.domain && e2.log && Math.abs(sh[0]) <= 1e-9 && Math.abs(sh[n - 1] - 1) <= 1e-9) {
    rule = "b";
    const [min, max] = ext === null ? [1, 10] : ext, k0 = Math.floor(Math.log10(min));
    let m = 1;
    while (10 ** (k0 + (n - 1) * m) < max) m++;
    const want = v.ticks.map((_, j) => 10 ** (k0 + j * m));
    if (!v2.ticks.every((x, j) => Math.abs(x / want[j] - 1) <= 1e-12)) out.push(`(b) right ticks ${v2.ticks}, the powers ${want} ${at}`);
    if (Math.abs(v2.lo / 10 ** k0 - 1) > 1e-12) out.push(`(b) value2.lo ${v2.lo}, the bound ${10 ** k0} ${at}`);
  }
  if (rule === "c") {
    const S2 = e2.log ? R.axes.logAxis(r.level, ext === null ? [null, null] : ext, e2.domain || null, plot.h, e2.format || null) : R.axes.valueAxis(r.level, ext, e2.domain || null, plot.h, e2.format || null, 0);
    if (v2.lo !== S2.lo || v2.hi !== S2.hi) out.push(`(c) value2 ${v2.lo}..${v2.hi}, the right axis's own range ${S2.lo}..${S2.hi} ${at}`);
    const want = sh.map((s) => (e2.log ? 10 ** (Math.log10(S2.lo) + s * (Math.log10(S2.hi) - Math.log10(S2.lo))) : valueAt(S2.lo, S2.hi, s)));
    const tol = (x) => (e2.log ? 1e-9 * x : 1e-9 * (Math.abs(S2.hi) + Math.abs(S2.lo)));
    if (!v2.ticks.every((x, j) => Math.abs(x - want[j]) <= tol(want[j]))) out.push(`(c) right values ${v2.ticks}, the scale at the left positions ${want} ${at}`);
    const texts = R.text.ticks(digits(want), e2.format || null), drawn = rightLabels(r, plot).map((l) => l.text);
    for (const d of drawn) if (!texts.includes(d)) out.push(`(c) right text ${d} is none of ${texts} ${at}`);
    if (new Set(texts).size !== texts.length) out.push(`(c) two right texts are equal: ${texts} ${at}`);
  }
  return { problems: out, rule };
}

test("A.3: every fixture, every frame: one right tick per left tick at its position, one grid, labels outside the plot and inside the frame, the twin", () => {
  const rules = { a: 0, b: 0, c: 0 };
  let pictures = 0;
  for (const f of typeFixtures(TYPE)) {
    const bad = [];
    for (const [w, h] of SWEEP) {
      const { problems, rule } = secondProblems(f.fixture, w, h);
      bad.push(...problems);
      if (rule !== null) rules[rule]++;
      pictures++;
    }
    assert.deepEqual(bad.slice(0, 8), [], `${f.name}: ${bad.length} problems`);
  }
  assert.ok(pictures >= 15 * 175, "pictures: " + pictures);
  for (const k of ["a", "b", "c"]) assert.ok(rules[k] >= 100, `case (${k}) pictures: ${rules[k]}`);
  console.log(`A.3 sweep: ${pictures} pictures, by case (a) ${rules.a}, (b) ${rules.b}, (c) ${rules.c}`);
});

test("A.3: each fixture meets the case it was written for at 960 x 540", () => {
  const want = { "case-a-linear": "a", "case-a-one-value": "a", "case-a-left-domain": "a", "case-b-linear-left": "b", "case-b-log-left": "b", "case-c-log-left-125": "c", "case-c-right-domain": "c", "case-c-log-domain": "c", column: "c", inset: "b", "signed-two-ticks": "a" };
  // inset at 960 x 540: its left ticks are 1 to 9, edge to edge, so (b); the inset case is its own test below.
  for (const [name, rule] of Object.entries(want)) assert.equal(secondProblems(fixture(TYPE, name), 960, 540).rule, rule, name);
  // signed-two-ticks (round 3, R1) at the verdict's 240 x 140: two left ticks on the edges beside -1..1, no ladder
  // step holds both signs, so (c), and the picture's checks above (the data held) find nothing.
  const signed = secondProblems(fixture(TYPE, "signed-two-ticks"), 240, 140);
  assert.deepEqual([signed.rule, signed.problems], ["c", []]);
  // left-one-tick: its one tick where the plot is short.
  const one = SWEEP.filter(([w, h]) => {
    const { hand, r } = draw(R, fixture(TYPE, "left-one-tick"), w, h);
    return r.state === "rendered" && r.level !== "minimal" && hand.value.ticks.length === 1;
  });
  assert.ok(one.length >= 1, "a frame where the left axis has one tick");
  for (const [w, h] of one) assert.equal(secondProblems(fixture(TYPE, "left-one-tick"), w, h).rule, "c");
  const domainLeft = draw(R, fixture(TYPE, "case-a-left-domain"), 960, 540).hand.value;
  assert.ok(share(domainLeft, domainLeft.ticks[0]) > 0.001, "the left domain's first tick is above the plot's bottom edge");
});

test("A.3: the inset case: left ticks 2, 4, 6, 8 beside a log right axis over 3..9: value2 1..10, four right ticks, none a power of ten", () => {
  const fx = fixture(TYPE, "inset");
  const frame = SWEEP.find(([w, h]) => {
    const { r, hand } = draw(R, fx, w, h);
    return r.state === "rendered" && r.level !== "minimal" && JSON.stringify(hand.value.ticks) === "[2,4,6,8]";
  });
  assert.ok(frame, "a frame where the left ticks are 2, 4, 6, 8");
  const { hand } = draw(R, fx, ...frame), v2 = hand.value2;
  assert.equal(v2.lo, 1);
  assert.equal(v2.hi, 10);
  assert.ok(v2.lo <= 3 && v2.hi >= 9, "the extent lies inside the range");
  assert.equal(v2.ticks.length, 4);
  for (let k = 0; k < 4; k++) assert.ok(near(v2.scale(v2.ticks[k]), hand.value.scale(hand.value.ticks[k]), 0.01));
  for (const t of v2.ticks) assert.ok(![1, 10, 100].includes(t), "not a power of ten: " + t);
  console.log(`A.3 inset at ${frame.join("x")}: right ticks ${v2.ticks.map((t) => t.toPrecision(6)).join(", ")}`);
});

test("A.3: a domain smaller than the data: value2.lo and hi are the domain's two ends (linear 0..10, log 1..10), at every frame", () => {
  let seen = 0;
  for (const [name, lo, hi] of [["case-c-right-domain", 0, 10], ["case-c-log-domain", 1, 10]]) {
    for (const [w, h] of SWEEP) {
      const { r, hand } = draw(R, fixture(TYPE, name), w, h);
      if (r.state !== "rendered") continue;
      assert.deepEqual([hand.value2.lo, hand.value2.hi], [lo, hi], `${name} ${w}x${h}`);
      seen++;
    }
  }
  assert.ok(seen >= 300, "pictures: " + seen);
});

/** The long-names threshold at 360 x 240: [left tick count with the column, without any right room, the x labels turned]. */
function threshold(E = R) {
  const fx = fixture(TYPE, "eight-long-names"), withCol = draw(E, fx, 360, 240), bare = draw(E, fx, 360, 240, {}, twin(0));
  const turned = underLabels(withCol.r, withCol.hand.plot).some((t) => t.rotate !== 0);
  return [withCol.hand.value.ticks.length, bare.hand.value.ticks.length, turned];
}

test("A.3: eight long band names at 360 x 240: the column turns the x labels and the left ticks become fewer than without right room", () => {
  const [withCol, without, turned] = threshold();
  assert.ok(turned, "the x labels are turned");
  assert.ok(withCol < without, `left ticks: ${withCol} with the column, ${without} without right room`);
  const r = draw(R, fixture(TYPE, "eight-long-names"), 960, 540).r;
  assert.ok(r.primitives.some((p) => p.k === "text" && p.role === "axis" && p.text === "Share & <rate>"), "the right title at full, with < and &");
  console.log(`A.3 threshold at 360x240: ${withCol} left ticks with the column, ${without} without right room`);
});

/** The column test: [plot right without second and right 0 − plot right with second, the theme's reach]. */
function columnShift(E = R) {
  const fx = fixture(TYPE, "column"), withSecond = draw(E, fx, 960, 540, { measure: SIX }), without = draw(E, fx, 960, 540, { measure: SIX }, twin(0));
  const a = withSecond.hand.plot, b = without.hand.plot;
  return { shift: b.x + b.w - (a.x + a.w), withSecond };
}

test("A.3: the column on a hand-written number: the plot's right edge stands expect.column − reach left of the twin's with right 0", () => {
  const fx = fixture(TYPE, "column"), want = fx.expect.column, reach = R.theme.resolve("light").n("--chart-point");
  assert.ok(Number.isFinite(want), "expect.column is written");
  const { shift, withSecond } = columnShift();
  assert.ok(near(shift, want - reach, 0.011), `shift ${shift}, expect.column ${want} less reach ${reach}`);
  // The final texts are not the right axis's own rule's texts over the same length (the number cannot come from them).
  const plot = withSecond.hand.plot, finals = rightLabels(withSecond.r, plot).map((t) => SIX(t.text));
  const own = R.axes.valueAxis(withSecond.r.level, [1, 9], [0, 10], plot.h, null, 0).texts.map(SIX);
  assert.notEqual(Math.max(...finals), Math.max(...own), `final widest ${Math.max(...finals)}, own rule's ${Math.max(...own)}`);
});

// ---- G3 falsified ----

const PRE = "    var noDomain = S2.domain === null || S2.domain === undefined, out = null;\n";
const GEO = "    var geo = vertical(h, W, H, lvl, b, L, visible, g, column), plot = geo.plot, value = geo.value;";
const OWN_C = "var own = S2.log === true ? logAxis(lvl, ext, S2.domain, length, S2.format) : valueAxis(lvl, ext, S2.domain, length, S2.format, 0);";
/**
 * The own-rule plants keep the layout drawable: the labels are written per LEFT tick, so a right axis with fewer texts
 * gets empty ones (a plant that only crashed would prove nothing about the count or the positions).
 */
const PAD = "    function padded(o, n) { while (o.texts.length < n) o.texts.push(\"\"); return o; }\n";
const WIDE = "(S2.domain ? [Math.min(S2.domain[0], ext[0]), Math.max(S2.domain[1], ext[1])] : null)";

/** The problems of a fixture over the sweep under engine E, as one list. */
function sweepProblems(E, name) {
  return SWEEP.flatMap(([w, h]) => secondProblems(fixture(TYPE, name), w, h, E).problems);
}

test("G3: the right axis given its own nice ticks is red on a right tick that lies on no left tick", () => {
  const E = formsEngine((s) => plant(s, AXES, PRE, "    if (S2.log !== true) return padded(valueAxis(lvl, ext, S2.domain, length, S2.format, 0), n);\n" + PRE + PAD));
  const bad = sweepProblems(E, "case-a-linear");
  assert.ok(bad.some((p) => /lies on no left tick|right tick count/.test(p)), JSON.stringify(bad.slice(0, 3)));
  console.log("G3 own ticks: " + bad.filter((p) => /lies on no left tick/.test(p)).length + " right ticks on no left tick");
});

test("G3: the right range one step too small is red on the extent", () => {
  const E = formsEngine((s) => plant(s, AXES, "      if (lo > e0 + tol || hi < e1 - tol) continue;\n", "      if (false) continue;\n"));
  const bad = ["case-a-linear", "case-a-one-value", "case-a-left-domain", "far-extent", "long-right-ticks"].flatMap((n) => sweepProblems(E, n));
  assert.ok(bad.some((p) => /does not hold the extent/.test(p)), JSON.stringify(bad.slice(0, 3)));
});

test("G3: with a log axis on either side, the right axis given its own rule's ticks is red on the count or on a position", () => {
  const E = formsEngine((s) => plant(s, AXES, PRE, "    if (S2.log === true || left.log === true) return padded(S2.log === true ? logAxis(lvl, ext, S2.domain, length, S2.format) : valueAxis(lvl, ext, S2.domain, length, S2.format, 0), n);\n" + PRE + PAD));
  for (const name of ["case-b-linear-left", "case-b-log-left", "case-c-log-left-125", "inset"]) {
    const bad = sweepProblems(E, name);
    assert.ok(bad.some((p) => /lies on no left tick|right tick count/.test(p)), name + ": " + JSON.stringify(bad.slice(0, 3)));
  }
});

test("G3: the column left at its first pass's width is red on expect.column", () => {
  const E = formsEngine((s) => plant(s, CART, GEO, GEO.replace("g, column)", "g, widestOf(h, own.texts, s) + tg + titleCol)")));
  const want = fixture(TYPE, "column").expect.column, reach = 4;
  const { shift } = columnShift(E);
  assert.ok(!near(shift, want - reach, 0.011), `planted shift ${shift}`);
});

test("G3: the left axis laid out without the column's room is red on the twin of the long-names fixture", () => {
  const E = formsEngine((s) => plant(s, CART, GEO, GEO.replace("g, column)", "g, 0)")));
  const [withCol, without] = threshold(E);
  assert.equal(withCol, without, "under the plant the left ticks do not become fewer");
  const bad = sweepProblems(E, "eight-long-names");
  assert.ok(bad.some((p) => /leaves the frame|twin/.test(p)), JSON.stringify(bad.slice(0, 3)));
});

test("G3: in the inset case, whole powers at the left positions are red on value2 and on the positions", () => {
  const E = formsEngine((s) => plant(s, AXES, "else if (even && noDomain && S2.log === true && Math.abs(shares[0]) <= SAME && Math.abs(shares[n - 1] - 1) <= SAME)", "else if (even && noDomain && S2.log === true)"));
  const bad = sweepProblems(E, "inset");
  assert.ok(bad.some((p) => /\(c\) value2 1\.\.1000/.test(p)), JSON.stringify(bad.slice(0, 3)));
  assert.ok(bad.some((p) => /lies on no left tick/.test(p)), "a right tick on no left tick");
});

test("G3: a right domain widened to hold the extent is red on value2.hi in both cases of a domain smaller than the data", () => {
  const E = formsEngine((s) => plant(s, AXES, OWN_C, OWN_C.split("S2.domain").join(WIDE)));
  for (const [name, hi] of [["case-c-right-domain", 10], ["case-c-log-domain", 10]]) {
    const { hand } = draw(E, fixture(TYPE, name), 960, 540);
    assert.notEqual(hand.value2.hi, hi, name);
    const bad = sweepProblems(E, name);
    assert.ok(bad.some((p) => /\(c\) value2 /.test(p)), name);
  }
});

// Case (c)'s texts where three significant digits are not enough. No fixture of the sweep needs a fourth digit (the
// sweep's case (c) texts all hold at three), so the rule "one more digit for all while two neighbours share a text" is
// asked here of R.axes.secondAxis directly: a log left axis with the ticks 1, 2, 5, 10 (not equally spaced: case (c))
// beside a linear right axis with the domain 1000 to 1001. Its values 1000, 1000.30103, 1000.69897, 1001 share texts at
// three digits and at four; at five they do not. The expected texts are the test's own rounding (digits, toPrecision).
const NARROW = { left: { lo: 1, hi: 10, ticks: [1, 2, 5, 10], log: true }, S2: { domain: [1000, 1001], format: null, log: false } };
const narrowAxis = (E) => E.axes.secondAxis("full", NARROW.S2, [1000, 1001], NARROW.left, 200);

test("A.3 (c): neighbours that share a text at three significant digits get more digits, all of them, until none share one", () => {
  const ax = narrowAxis(R);
  assert.equal(ax.rule, "c");
  const want = NARROW.left.ticks.map((t) => 1000 + Math.log10(t));
  assert.equal(ax.ticks.length, want.length);
  ax.ticks.forEach((v, i) => assert.ok(Math.abs(v - want[i]) <= 1e-9, `tick ${i}: ${v} vs ${want[i]}`));
  // The case is real: at three and at four digits two neighbours share a value.
  for (const d of [3, 4]) {
    const r = want.map((v) => Number(v.toPrecision(d)));
    assert.ok(r.some((v, i) => i > 0 && v === r[i - 1]), `at ${d} digits two neighbours share a value`);
  }
  assert.deepEqual([...ax.texts], [...R.text.ticks(digits(want), null)]);
  assert.equal(new Set(ax.texts).size, ax.texts.length, "no two right texts are equal: " + ax.texts.join(" | "));
});

test("G3 (c): the digits stopped at one more than three leave two right texts equal (red); the clean rule does not", () => {
  const E = formsEngine((s) => plant(s, "11-axes.js", "    for (var d = 3; d <= 17; d++) {", "    for (var d = 3; d <= 4; d++) {"));
  const planted = narrowAxis(E).texts;
  assert.ok(new Set(planted).size < planted.length, "the planted copy writes two equal texts: " + planted.join(" | "));
  const clean = narrowAxis(R).texts;
  assert.equal(new Set(clean).size, clean.length);
});
