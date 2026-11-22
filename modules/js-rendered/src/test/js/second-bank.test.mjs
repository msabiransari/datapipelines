// Brief 1051 round 4 (the second correction round; landing verdict specs/js-rendered-types/landings/1051-r3.md): the
// second value axis (D5) at the ends of the number range.
//   R4 a log right axis over a valid finite domain gives finite, positive ticks on the left ticks' positions, the
//      authored ends exactly: [1e308, MAX] threw (the inverse of the logarithm overshot the largest double) and
//      [MIN_VALUE, 1e-322] gave a first tick 0 at position Infinity (the inverse cut a positive end to 0).
//   The boundary bank: ONE invariant of the second axis run over every ordered pair of the boundary values, as a
//      domain and as an extent, linear and log, beside each left axis form the second axis accepts. A failing member
//      not on the NAMED, closed list is red, and a listed member that passes is a stale entry and red. The list held
//      260 members that failed by single-axis limits until lane 1063 repaired them; it is empty.
//   R5 rounding to significant digits is floating arithmetic, not exact decimal rounding: one boundary pinned, and a
//      bound, with its reason, on how far a result may stand from the exact decimal rounding.
import { test } from "node:test";
import assert from "node:assert/strict";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";
import { engineWith } from "./engine.mjs";
import { formsEngine, plant, fixture, draw } from "./forms.mjs";

const R = engineWith([]);
const MIN = Number.MIN_VALUE, MAX = Number.MAX_VALUE;
const L3 = { lo: 0, hi: 1, ticks: [0, 0.5, 1], log: false };

// ---- the positions the template's own scales give (A.scaleOf: S.linear, or S.log on the engine's log10) ----

/**
 * A value's share of an axis {lo, hi, log}, unrounded, from lo (0) to hi (1); halved where hi - lo or v - lo overflows
 * (round 5: a value past the top of [-MAX, -1], 1.8e298, has v - lo = Infinity on a finite span).
 */
function shareOf(E, ax, v) {
  if (ax.log === true) {
    const lg = E.scales.log10;
    return (lg(v) - lg(ax.lo)) / (lg(ax.hi) - lg(ax.lo));
  }
  const d = ax.hi - ax.lo, n = v - ax.lo;
  return Number.isFinite(d) && Number.isFinite(n) ? n / d : (v / 2 - ax.lo / 2) / (ax.hi / 2 - ax.lo / 2);
}

const BITS = new DataView(new ArrayBuffer(8));
/** The double next to v, above (up) or below it. */
function beside(v, up) {
  if (v === 0) return up ? MIN : -MIN;
  BITS.setFloat64(0, v);
  BITS.setBigInt64(0, BITS.getBigInt64(0) + ((v > 0) === up ? 1n : -1n));
  return BITS.getFloat64(0);
}

/**
 * How far a right tick's position may stand from its left tick's: the engine's own tolerance SAME (1e-9 of the axis,
 * the share within which 11-axes.js calls two positions one), widened by the share of the double next to the tick on
 * each side. A tick is a double: the exact value at a left position is in general not one, and the nearest double can
 * stand one step away. For a normal number that step is a share near 1e-16 of a value's size; a subnormal has few
 * doubles near it (a log domain [MIN_VALUE, 1e-322] holds 20, the nearest to its middle 7.45 px of 200 away), so there
 * the format's own step is the bound. The left share must lie between the shares of the tick's two neighbours.
 */
const SAME = 1e-9;
function onPosition(E, ax, t, want) {
  const s = shareOf(E, ax, t), below = shareOf(E, ax, beside(t, false)), above = shareOf(E, ax, beside(t, true));
  const a = Math.min(s, below), b = Math.max(s, above);
  return want >= (Number.isNaN(a) ? s : a) - SAME && want <= (Number.isNaN(b) ? s : b) + SAME;
}

// ---- R4: the verdict's three inputs ----

const logSecond = (E, domain, left = L3) => E.axes.secondAxis("full", { domain, format: null, log: true }, domain, left, 200);

test("R4: the log right domain [1e308, MAX] beside 0, 0.5, 1 (the verdict's input 1): finite positive ticks, the ends exactly, on the left positions", () => {
  const domain = [1e308, MAX];
  assert.doesNotThrow(() => R.axes.logAxis("full", domain, domain, 200, null), "the single axis takes this domain");
  const ax = logSecond(R, domain);
  assert.equal(ax.ticks.length, 3);
  assert.ok(ax.ticks.every((t) => Number.isFinite(t) && t > 0), String(ax.ticks));
  assert.equal(ax.ticks[0], 1e308);
  assert.equal(ax.ticks[2], MAX);
  assert.ok(ax.ticks[1] > 1e308 && ax.ticks[1] < MAX, String(ax.ticks[1]));
  assert.deepEqual([ax.lo, ax.hi], domain);
  assert.deepEqual([...ax.ticks].map(R.axes.scaleOf(ax, 200, 0)), [200, 100, 0]);
  ax.ticks.forEach((t, j) => assert.ok(onPosition(R, ax, t, L3.ticks[j]), `tick ${t} at the share ${L3.ticks[j]}`));
});

test("R4: the log right domain [MIN_VALUE, 1e-322] (the verdict's input 2): the first tick is MIN_VALUE, not 0; the middle is the double nearest the exact value", () => {
  const domain = [MIN, 1e-322];
  const ax = logSecond(R, domain), scale = R.axes.scaleOf(ax, 200, 0), pos = ax.ticks.map(scale);
  assert.deepEqual([...ax.ticks], [MIN, 4 * MIN, 1e-322]);
  assert.ok(pos.every(Number.isFinite), String(pos));
  assert.equal(pos[0], 200);
  assert.equal(pos[2], 0);
  // The exact value at the middle is sqrt(MIN x 1e-322) = 4.47 MIN; the domain holds the 20 doubles n x MIN, and none
  // stands nearer 100 px than the tick (4 MIN at 107.45, 5 MIN at 92.55): no double meets 100 within 2 px.
  let nearest = Infinity;
  for (let n = 1; n * MIN <= 1e-322; n++) nearest = Math.min(nearest, Math.abs(scale(n * MIN) - 100));
  assert.equal(Math.abs(pos[1] - 100), nearest);
  assert.ok(nearest > 7, "no double of the domain is within 7 px of the middle: " + nearest);
  ax.ticks.forEach((t, j) => assert.ok(onPosition(R, ax, t, L3.ticks[j]), `tick ${t} at the share ${L3.ticks[j]}`));
});

/** A test_two_axes fixture whose right axis is a log domain (the verdict's input 3: left values and domain 0..1). */
function logRight(domain) {
  const fx = structuredClone(fixture("test_two_axes", "case-a-linear"));
  delete fx.document.config.title;
  fx.document.config.encoding.y = { field: "y", domain: [0, 1] };
  fx.document.config.encoding.y2 = { field: "y2", log: true, domain };
  fx.columns = { x: ["A", "B"], y: [0, 1], y2: domain };
  return fx;
}

test("R4 rendered: the existing test type with a log right domain [1e308, MAX] or [MIN_VALUE, 1e-322] at 80 x 24 (the verdict's input 3) and every frame of the sweep: no throw, finite positive right ticks, the ends", () => {
  const E = formsEngine();
  assert.equal(draw(E, logRight([1e308, MAX]), 80, 24).r.state, "rendered");
  let pictures = 0;
  for (const domain of [[1e308, MAX], [MIN, 1e-322]]) {
    const fx = logRight(domain);
    for (const w of WIDTHS) {
      for (const h of HEIGHTS) {
        let res;
        assert.doesNotThrow(() => (res = draw(E, fx, w, h)), `${domain} at ${w}x${h}`);
        if (res.r.state !== "rendered") continue;
        const v2 = res.hand.value2;
        assert.deepEqual([v2.lo, v2.hi], domain, `${w}x${h}`);
        assert.ok(v2.ticks.every((t) => Number.isFinite(t) && t > 0), `${v2.ticks} at ${w}x${h}`);
        assert.equal(v2.ticks.length, res.hand.value.ticks.length, `${w}x${h}`);
        pictures++;
      }
    }
  }
  console.log(`R4 rendered: ${pictures} pictures`);
  assert.ok(pictures >= 2 * WIDTHS.length * HEIGHTS.length * 0.5, "pictures " + pictures);
});

test("R4: exp10 is log10's inverse on the power table from the smallest subnormal to the largest double; below it 0, past it Infinity", () => {
  const lg = R.scales.log10, ex = R.scales.exp10;
  let n = 0;
  for (let x = lg(MIN); x <= lg(MAX); x += 0.0731) {
    const v = ex(x);
    assert.ok(Number.isFinite(v) && v > 0, `exp10(${x}) = ${v}`);
    // The double next to v on each side brackets x on the engine's own log scale (within the series' last places).
    const tol = 1e-12 * Math.max(1, Math.abs(x));
    assert.ok(lg(beside(v, false)) - tol <= x && x <= lg(beside(v, true)) + tol, `log10(exp10(${x})) = ${lg(v)}`);
    n++;
  }
  assert.equal(ex(lg(MIN)), MIN);
  assert.ok(ex(-323.2) > 0, "a value under 10^-323 is not cut to 0: " + ex(-323.2));
  assert.equal(ex(-324.5), 0);
  assert.equal(ex(309), Infinity);
  assert.equal(ex(-323), R.scales.pow10(-323));
  assert.ok(Number.isNaN(ex(NaN)));
  console.log(`R4 exp10: ${n} values from log10(MIN_VALUE) to log10(MAX)`);
  assert.ok(n > 8000, "n " + n);
});

test("R4 linear: an author's top end comes back as written where lo + (hi - lo) does not read it ([-MAX, -1]: -1, not 0)", () => {
  const S2 = (domain) => ({ domain, format: null, log: false });
  for (const domain of [[-MAX, -1], [-MAX, -1e300], [0.1, 0.3], [-1e308, 1e-300]]) {
    const ax = R.axes.secondAxis("full", S2(domain), domain, L3, 200);
    assert.equal(ax.ticks[0], domain[0], String(domain));
    assert.equal(ax.ticks[2], domain[1], String(domain));
  }
});

test("R4 linear: case (a) keeps a step below 10^-307 as it is ([-2.2e-308, 2.2e-308] beside 0, 0.5, 1: the ticks on the positions, never 3e-308)", () => {
  const e = [-2.2250738585072014e-308, 2.2250738585072014e-308];
  const ax = R.axes.secondAxis("full", { domain: null, format: null, log: false }, e, L3, 200);
  assert.equal(ax.rule, "a");
  assert.ok(ax.lo <= e[0] && ax.hi >= e[1], `${ax.lo}..${ax.hi}`);
  ax.ticks.forEach((t, j) => assert.ok(onPosition(R, ax, t, L3.ticks[j]), `${ax.ticks}: tick ${t} at the share ${L3.ticks[j]}`));
  const sub = R.axes.secondAxis("full", { domain: null, format: null, log: false }, [-1e-322, -5e-324], L3, 200);
  assert.ok(sub.ticks.some((t) => t !== 0), "a subnormal extent's case (a) ticks are not all 0: " + sub.ticks);
});

// ---- R6 (round 5): a left tick a hair past an edge of its domain ----

/** A left axis {lo, hi, ticks, log} as the real producer (valueAxis or logAxis) made it. */
const producedLeft = (ax, log) => ({ lo: ax.lo, hi: ax.hi, ticks: [...ax.ticks], log });
/** Left domains whose real valueAxis keeps a tick past an end (S.within's tolerance), with the share past it. */
const R6_LEFTS = [[0, 0.9999999999], [1e-10, 1], [1e-10, 0.9999999999], [0, 0.9999999991], [9e-10, 1]];
const r6Left = (d) => producedLeft(R.axes.valueAxis("full", d, d, 200, null, 0), false);
/** Right domains the single axis accepts with finite ticks: positive, negative and mixed-sign, at the doubles' ends. */
const R6_LINEAR = [[-MAX, MAX], [1e308, MAX], [-MAX, -1e308], [-MAX, -1], [-MAX, 1], [-1, MAX], [-1e308, 1e308], [-1, 1], [0, 100]];
const R6_LOG = [[1e308, MAX], [MIN, 1e-322], [MIN, MAX], [1, 10]];

test("R6 helper: beside left ticks valueAxis keeps past an edge ([0, 0.9999999999]: share 1.0000000001; [1e-10, 1]: share -1e-10), every finite right domain gives finite ticks, its ends exactly, the left's count, and each tick on its left position", () => {
  let axes = 0, past = 0;
  for (const ld of R6_LEFTS) {
    const left = r6Left(ld), shares = left.ticks.map((t) => shareOf(R, left, t));
    assert.ok(shares.some((sh) => sh < 0 || sh > 1), `${ld}: a tick past an edge ${shares}`);
    for (const [log, list] of [[false, R6_LINEAR], [true, R6_LOG]]) {
      for (const rd of list) {
        const at = `${log ? "log" : "linear"} [${rd}] beside [${ld}]`;
        if (log) R.axes.logAxis("full", rd, rd, 200, null);
        else assert.ok(R.axes.valueAxis("full", rd, rd, 200, null, 0).ticks.every(Number.isFinite), "the single axis takes " + at);
        let ax;
        assert.doesNotThrow(() => (ax = R.axes.secondAxis("full", { domain: rd, format: null, log }, rd, left, 200)), at);
        assert.equal(ax.ticks.length, left.ticks.length, at);
        assert.ok([ax.lo, ax.hi, ...ax.ticks].every(Number.isFinite), `${at}: ${ax.ticks}`);
        if (log) assert.ok(ax.ticks.every((t) => t > 0), `${at}: ${ax.ticks}`);
        assert.deepEqual([ax.lo, ax.hi], rd, at);
        shares.forEach((sh, j) => {
          const t = ax.ticks[j];
          if (sh === 0) assert.equal(t, rd[0], at);
          if (sh === 1) assert.equal(t, rd[1], at);
          // Past an end, the value lies past that end too (or is the double at the end of the number range).
          if (sh > 1) assert.ok(t >= rd[1], `${at}: tick ${j} = ${t} below the top ${rd[1]}`);
          if (sh < 0) assert.ok(t <= rd[0], `${at}: tick ${j} = ${t} above the bottom ${rd[0]}`);
          if (sh < 0 || sh > 1) past++;
          assert.ok(onPosition(R, ax, t, sh), `${at}: tick ${j} = ${t} off the share ${sh}`);
        });
        axes++;
      }
    }
  }
  // The verdict's three, exactly: the top tick of [-MAX, MAX] and [1e308, MAX] is MAX (no double past it), the bottom
  // tick of [-MAX, MAX] beside [1e-10, 1] is -MAX; [-MAX, -1] extends past -1 to the right scale's own value there.
  const up = r6Left([0, 0.9999999999]), down = r6Left([1e-10, 1]), lin = (rd, left) => R.axes.secondAxis("full", { domain: rd, format: null, log: false }, rd, left, 200);
  assert.equal(lin([-MAX, MAX], up).ticks[4], MAX);
  assert.equal(lin([1e308, MAX], up).ticks[4], MAX);
  assert.equal(lin([-MAX, MAX], down).ticks[0], -MAX);
  const ext = lin([-MAX, -1], up).ticks[4], want = -1 + (shareOf(R, up, 1) - 1) * (MAX - 1);
  assert.ok(Math.abs(ext - want) <= 1e-12 * want, `[-MAX, -1] past its top: ${ext}, the scale's ${want}`);
  console.log(`R6 helper: ${axes} axes beside ${R6_LEFTS.length} produced left forms, ${past} ticks past an edge`);
  assert.equal(axes, R6_LEFTS.length * (R6_LINEAR.length + R6_LOG.length));
  assert.ok(past >= axes, "past " + past);
});

/**
 * D3 row 4, the share's own arithmetic: a finite left tick past hi whose distance from lo overflows on a finite span.
 * No producer hands one today (valueAxis's ticks past an end stand within 1e-9 of the span), so this left axis is
 * written by hand on purpose: it pins shareOf's halving for v - lo, which the second axis must not leave to the
 * producer. Without it the share reads Infinity and the right tick is the end of the number range, not its position.
 */
test("R6 share: a left tick past hi whose distance from lo overflows on a finite span ([-MAX, 0], tick 1e299) keeps its own position on a linear and a log right domain", () => {
  const left = { lo: -MAX, hi: 0, ticks: [-MAX, 0, 1e299], log: false }, want = shareOf(R, left, 1e299);
  assert.ok(want > 1 && want < 1 + 1e-9, "the oracle's share " + want);
  for (const [log, rd] of [[false, [0, 1]], [true, [1, 10]]]) {
    const ax = R.axes.secondAxis("full", { domain: rd, format: null, log }, rd, left, 200), at = `${log ? "log" : "linear"} [${rd}]`;
    assert.deepEqual([ax.lo, ax.hi, ax.ticks.length], [rd[0], rd[1], 3], at);
    assert.deepEqual([...ax.ticks.slice(0, 2)], rd, at);
    assert.ok(Number.isFinite(ax.ticks[2]) && ax.ticks[2] > rd[1] && ax.ticks[2] < rd[1] * 1.0001, `${at}: tick 2 = ${ax.ticks[2]}`);
    assert.ok(onPosition(R, ax, ax.ticks[2], want), `${at}: tick 2 = ${ax.ticks[2]} off the share ${want}`);
  }
});

/** The existing test type with a left domain ld (its values the domain's ends) and a right domain rd. */
function edgeFixture(ld, rd, log) {
  const fx = structuredClone(fixture("test_two_axes", "case-a-linear"));
  delete fx.document.config.title;
  fx.document.config.encoding.y = { field: "y", domain: ld };
  fx.document.config.encoding.y2 = log ? { field: "y2", log: true, domain: rd } : { field: "y2", domain: rd };
  fx.columns = { x: ["A", "B"], y: ld, y2: rd };
  return fx;
}

test("R6 rendered: the existing test type with left domains next to a tick beside [-MAX, MAX], [1e308, MAX], [-MAX, -1] and the log [1e308, MAX], at 80 x 24, 240 x 140, 960 x 540 (the verdict's frames) and every frame of the sweep: no throw, the right ends, finite right ticks, one per left tick", () => {
  const E = formsEngine();
  let pictures = 0;
  const cases = [];
  for (const ld of [[0, 0.9999999999], [1e-10, 1]]) {
    for (const rd of [[-MAX, MAX], [1e308, MAX], [-MAX, -1]]) cases.push([ld, rd, false]);
    cases.push([ld, [1e308, MAX], true]);
  }
  for (const [ld, rd, log] of cases) {
    const fx = edgeFixture(ld, rd, log);
    for (const [w, h] of [[80, 24], [240, 140], [960, 540]]) assert.equal(draw(E, fx, w, h).r.state, "rendered", `[${ld}] beside [${rd}] at ${w}x${h}`);
    for (const w of WIDTHS) {
      for (const h of HEIGHTS) {
        let res;
        assert.doesNotThrow(() => (res = draw(E, fx, w, h)), `[${ld}] beside [${rd}] at ${w}x${h}`);
        if (res.r.state !== "rendered") continue;
        const v2 = res.hand.value2;
        assert.deepEqual([v2.lo, v2.hi], rd, `${w}x${h}`);
        assert.ok(v2.ticks.every((t) => Number.isFinite(t) && (!log || t > 0)), `${v2.ticks} at ${w}x${h}`);
        assert.equal(v2.ticks.length, res.hand.value.ticks.length, `${w}x${h}`);
        pictures++;
      }
    }
  }
  console.log(`R6 rendered: ${pictures} pictures over ${cases.length} cases`);
  assert.ok(pictures >= cases.length * WIDTHS.length * HEIGHTS.length * 0.5, "pictures " + pictures);
});

// ---- the boundary bank ----

/** The boundary values (brief 1051 r4 D2); a linear right axis takes their negatives and 0 as well. */
const POSITIVE = [MIN, 1e-322, 2.2250738585072014e-308, 1e-300, 1, 1e300, 1e308, MAX];
const SIGNED = [...POSITIVE.map((v) => -v).reverse(), 0, ...POSITIVE];
/** Round 4's left axis forms: two edge ticks, three ticks, a log left axis, an inset left axis (written by hand). */
const HAND_LEFTS = {
  "two edge ticks": { lo: 0, hi: 1, ticks: [0, 1], log: false },
  "three ticks": L3,
  "log 1, 2, 5, 10": { lo: 1, hi: 10, ticks: [1, 2, 5, 10], log: true },
  "inset 0.2 to 0.8": { lo: 0, hi: 1, ticks: [0.2, 0.4, 0.6, 0.8], log: false },
};
/**
 * Round 5 (R6, D3): left domains next to a nice tick, each axis made by the real producer and never written by hand.
 * S.within keeps a tick within 1e-9 of the domain's span past an end, so a linear left axis hands the second axis
 * shares a little below 0 ([1e-10, 1]: tick 0), above 1 ([0, 0.9999999999]: tick 1), or both; 0.9999999991 and 9e-10
 * stand at that tolerance's limit (shares 1 + 9e-10 and -9e-10); [0, 9.999999999e307] and its mirror put the tick 1e308
 * (-1e308) past an end at the top of the number range. A log left domain next to a tick keeps its ticks
 * inside (logWithin has no tolerance): [0.9999999999, 10.0000000001] holds 1 and 10 a hair inside both edges, and
 * [1.0000000001, 9.9999999999] leaves them out (an inset log axis, 2 and 5).
 */
const EDGE_LINEAR = [[0, 0.9999999999], [1e-10, 1], [1e-10, 0.9999999999], [0, 0.9999999991], [9e-10, 1], [-1, 0.9999999999], [-0.9999999999, 1], [0, 9.999999999e307], [-9.999999999e307, 0]];
const EDGE_LOG = [[0.9999999999, 10.0000000001], [1.0000000001, 9.9999999999]];
const LEFTS = { ...HAND_LEFTS };
for (const d of EDGE_LINEAR) LEFTS[`valueAxis [${d}]`] = producedLeft(R.axes.valueAxis("full", d, d, 200, null, 0), false);
for (const d of EDGE_LOG) LEFTS[`logAxis [${d}]`] = producedLeft(R.axes.logAxis("full", d, d, 200, null), true);
const pairs = (n) => (n * (n - 1)) / 2;
/**
 * The bank's size from the lists: (17 signed values' pairs + 8 positive values' pairs) x domain or extent x the left
 * forms (round 4: 4 forms, 1312 members; round 5: 4 + 9 + 2 = 15 forms).
 */
const BANK_FLOOR = (pairs(SIGNED.length) + pairs(POSITIVE.length)) * 2 * (Object.keys(HAND_LEFTS).length + EDGE_LINEAR.length + EDGE_LOG.length);

/** Every member: {key, log, mode, pair, leftName, left}. */
function bank() {
  const out = [];
  for (const log of [false, true]) {
    const vals = log ? POSITIVE : SIGNED;
    for (let a = 0; a < vals.length; a++) {
      for (let b = a + 1; b < vals.length; b++) {
        for (const mode of ["domain", "extent"]) {
          for (const [leftName, left] of Object.entries(LEFTS)) {
            const pair = [vals[a], vals[b]];
            out.push({ key: `${log ? "log" : "linear"} ${mode} ${pair[0]}..${pair[1]} beside ${leftName}`, log, mode, pair, leftName, left });
          }
        }
      }
    }
  }
  return out;
}

/**
 * The invariant of the second axis on one member: [] when it holds, else what broke, each failure {kind, text} so that
 * the named list can excuse one kind and no other (round 5, R7). No throw (`throws`); one tick per left tick
 * (`count`); every value finite (`finite`); a log axis's range and ticks positive (`positive`); an authored domain's
 * ends returned exactly (`ends`), and as the ticks at the edge shares (`edge`); a chosen range holds the extent within
 * SAME of the extent's own span, the tolerance case (a) uses (`range-low`, `range-high`, one per end); on a usable
 * range (hi > lo) every tick on its left tick's position (`position`, onPosition); on a range of one value every tick
 * that value (`collapsed`: the only value such a range has at any position).
 */
function broken(E, m) {
  const domain = m.mode === "domain" ? m.pair : null;
  let ax;
  try {
    ax = E.axes.secondAxis("full", { domain, format: null, log: m.log }, m.pair, m.left, 200);
  } catch (e) {
    return [{ kind: "throws", text: "throws: " + e.message.slice(0, 120) }];
  }
  const out = [], fail = (kind, text) => out.push({ kind, text });
  if (ax.ticks.length !== m.left.ticks.length) fail("count", `${ax.ticks.length} ticks beside ${m.left.ticks.length}`);
  if (![ax.lo, ax.hi, ...ax.ticks].every(Number.isFinite)) fail("finite", `not finite: ${ax.lo}..${ax.hi} ${ax.ticks}`);
  if (m.log && !(ax.lo > 0 && ax.ticks.every((t) => t > 0))) fail("positive", `not positive: ${ax.lo}..${ax.hi} ${ax.ticks}`);
  if (out.length) return out;
  const want = m.left.ticks.map((t) => shareOf(E, m.left, t));
  if (domain) {
    if (ax.lo !== domain[0] || ax.hi !== domain[1]) fail("ends", `ends ${ax.lo}..${ax.hi}`);
    want.forEach((s, j) => {
      if (s === 0 && ax.ticks[j] !== ax.lo) fail("edge", `the bottom-edge tick ${ax.ticks[j]}, not ${ax.lo}`);
      if (s === 1 && ax.ticks[j] !== ax.hi) fail("edge", `the top-edge tick ${ax.ticks[j]}, not ${ax.hi}`);
    });
  } else if (m.log) {
    // A log extent is held on the log scale: within SAME of its own span in decades (the engine's log10, the scale the
    // template's positions use). Round 4 read it on the linear scale, where SAME of a span to 1e300 is 1e291 and hid the
    // #1060 floor's low end (1e-323 above 5e-324, 0.3 decades of 623).
    const lg = E.scales.log10, [l0, l1] = m.pair.map(lg), tol = SAME * (l1 - l0);
    if (!(lg(ax.lo) <= l0 + tol)) fail("range-low", `the range ${ax.lo}..${ax.hi} leaves out the low end of ${m.pair[0]}..${m.pair[1]}`);
    if (!(lg(ax.hi) >= l1 - tol)) fail("range-high", `the range ${ax.lo}..${ax.hi} leaves out the high end of ${m.pair[0]}..${m.pair[1]}`);
  } else {
    const [e0, e1] = m.pair, tol = SAME * 2 * (e1 / 2 - e0 / 2);
    if (!(ax.lo <= e0 + tol)) fail("range-low", `the range ${ax.lo}..${ax.hi} leaves out the low end of ${e0}..${e1}`);
    if (!(ax.hi >= e1 - tol)) fail("range-high", `the range ${ax.lo}..${ax.hi} leaves out the high end of ${e0}..${e1}`);
  }
  if (ax.hi > ax.lo) ax.ticks.forEach((t, j) => onPosition(E, ax, t, want[j]) || fail("position", `tick ${j} = ${t} (rule ${ax.rule}) off the share ${want[j]}`));
  else ax.ticks.forEach((t, j) => t === ax.lo || fail("collapsed", `tick ${j} = ${t} on the one-value range ${ax.lo}..${ax.hi}`));
  return out;
}

/**
 * The named list: every member that fails. Closed: a failing member not on it is red, a member on it that holds is red
 * (a stale entry), and an entry that is no member is red. Rounds 4 and 5 of brief 1051 listed 260 members, each failing
 * by a single-axis limit (#1060's collapse and floor, #1061's top); lane 1063 repaired those limits, every entry came
 * off in the same change (#1061's acceptance), and the list is empty. The excuse rules that read an entry (round 5's
 * R7: an entry excused one kind of failure and no other) went with the entries, since no line of them ran on an empty
 * list; a member that ever needs an entry again needs that narrowness again, written with it.
 */
const NAMED = {};

/** The bank's verdict against a named list: {size, failing, problems: [text]}; problems empty when the bank holds. */
function audit(E, named) {
  const members = bank(), keys = new Set(members.map((m) => m.key)), problems = [];
  let failing = 0;
  for (const m of members) {
    const why = broken(E, m), entry = named[m.key];
    if (why.length) failing++;
    if (why.length && !entry) problems.push(`not on the named list: ${m.key}: ${why.map((f) => f.text).join("; ")}`);
    if (!why.length && entry) problems.push(`stale entry (the member holds): ${m.key}`);
  }
  for (const k of Object.keys(named)) if (!keys.has(k)) problems.push(`a named entry is no member: ${k}`);
  return { size: members.length, failing, problems };
}

test("the boundary bank: the second axis's invariant over every ordered pair of the boundary values, domain and extent, linear and log, beside round 4's four left forms and round 5's eleven produced ones; every member holds, and the closed named list is empty", () => {
  const { size, failing, problems } = audit(R, NAMED);
  console.log(`boundary bank: ${size} members (floor ${BANK_FLOOR}; round 4: 1312), ${failing} failing, ${Object.keys(NAMED).length} on the named list`);
  assert.ok(size >= BANK_FLOOR, `the bank shrank: ${size} < ${BANK_FLOOR}`);
  assert.equal(BANK_FLOOR, 4920);
  assert.deepEqual(problems, []);
  assert.equal(failing, 0);
  assert.equal(Object.keys(NAMED).length, 0);
  // Every authored domain holds the whole invariant beside every left form: none is on the list.
  const members = bank(), authored = members.filter((m) => m.mode === "domain");
  assert.equal(authored.length, BANK_FLOOR / 2);
  assert.ok(authored.every((m) => !NAMED[m.key]), "an authored domain on the named list");
  // Not vacuous: the produced left forms really hand shares past both ends (S.within's tolerance), up to its limit.
  const past = { below: 0, above: 0, most: 0 };
  for (const name of Object.keys(LEFTS)) {
    if (HAND_LEFTS[name]) continue;
    for (const t of LEFTS[name].ticks) {
      const sh = shareOf(R, LEFTS[name], t), o = sh < 0 ? -sh : sh > 1 ? sh - 1 : 0;
      if (sh < 0) past.below++;
      if (sh > 1) past.above++;
      past.most = Math.max(past.most, o);
    }
  }
  console.log(`boundary bank: produced left ticks past an end ${JSON.stringify(past)}`);
  assert.ok(past.below >= 4 && past.above >= 4 && past.most > 8e-10 && past.most < 1.000001e-9, JSON.stringify(past));
});

/**
 * Lane 1063's fixes, each taken out alone (the plants of brief 1063 D10): the members it repaired, all from the 260
 * entries the named list held before it, are red as not on the list. `tick` takes out the decimal tick (D1) with the
 * two checks that also keep a collapsed range from being returned (two ticks that do not rise are one value, D2; a
 * range that leaves out an end is no range, D5): with any one of the three in place, a subnormal extent's range does not
 * collapse to 0..0 (measured: the tick alone, or it with one check, turns no member red). The rest take out one line:
 * the log floor (D6), the ladder that stops below the largest value (D7), the fallback's top as the table gives it
 * (D7), the second axis's guard below the power table (D9 a). Each with the count of members it turns red and one of
 * them by name.
 */
const SCALES = "07-scales.js";
const LANE_1063_PLANTS = {
  tick: {
    edit: [
      SCALES,
      'var t = Number((x.first + i) * parts.m + "e" + parts.e) + 0;\n        if (!(t > -Infinity && t < Infinity)) return null;\n        if (i > 0 && !(t > ticks[i - 1])) return SAME;\n        ticks.push(t);\n      }\n      // The range holds both ends, or it is no nice range (below the normal doubles a decimal step is not a double,\n      // and its multiples can miss an end): the bounded fallback then.\n      if (ticks[0] > min + x.step * 1e-9 || ticks[count] < max - x.step * 1e-9) return null;',
      "var dp = Math.max(0, 1 - decade(x.step)), sc = pow10(dp > 308 ? 308 : dp), t = Math.round((x.first + i) * x.step * sc) / sc + 0;\n        if (!(t > -Infinity && t < Infinity)) return null;\n        ticks.push(t);\n      }",
    ],
    red: 24,
    member: "linear extent -5e-324..5e-324 beside two edge ticks",
    text: "the range 0..0 leaves out the low end of -5e-324..5e-324",
  },
  floor: { edit: [SCALES, "    if (lo > min) lo = min;\n", ""], red: 105, member: "log extent 5e-324..1e-322 beside two edge ticks", text: "the range 1e-323..1e-322 leaves out the low end of 5e-324..1e-322" },
  ladder: {
    edit: [SCALES, "    return null;\n  }\n\n  /** The ticks inside a fixed log domain", "    return out;\n  }\n\n  /** The ticks inside a fixed log domain"],
    red: 176,
    member: "log extent 1..1e+300 beside log 1, 2, 5, 10",
    text: "leaves out the high end of 1..1e+300",
  },
  top: {
    edit: [SCALES, "var kt = decade(max), top = pow10(kt);\n    if (top < max) top = kt < 308 ? pow10(kt + 1) : MAX;\n    if (!(top > lo)) top = MAX;", "var top = pow10(308);"],
    red: 105,
    member: "log extent 1e+308..1.7976931348623157e+308 beside three ticks",
    text: "leaves out the high end of 1e+308..1.7976931348623157e+308",
  },
  guard: { edit: ["11-axes.js", "    if (S.pow10(k0) > min) return null;\n", ""], red: 56, member: "log extent 5e-324..1e-322 beside three ticks", text: "leaves out the low end of 5e-324..1e-322" },
};

test("the boundary bank goes red: on the verdict's inputs with R4's fix taken out, with R6's fix taken out, with each of lane 1063's fixes taken out, on a holding member put on the list", () => {
  // R4's fix out: the log right axis's values from exp10 alone, which cuts below 10^-323 to 0 as round 3's did.
  const old = engineWith([], (s) => {
    plant(s, "11-axes.js", "logLerp(lo, hi, l0, l1, shares[j])", "S.exp10(l0 + shares[j] * (l1 - l0))");
    plant(s, "07-scales.js", "if (!(x >= -324)) return 0;", "if (!(x >= -323)) return 0;");
  });
  const red = audit(old, NAMED).problems.join("\n");
  for (const key of ["log domain 1e+308..1.7976931348623157e+308 beside three ticks", "log domain 5e-324..1e-322 beside three ticks"]) {
    assert.ok(red.includes("not on the named list: " + key), "red on " + key);
  }
  // R6's fix out: a share past an end read by lerp's own formulas, as round 4 did.
  const r4 = engineWith([], (s) => plant(s, "11-axes.js", "if (t < 0 || t > 1) return pastEnd(lo, hi, d, t);", ""));
  const red6 = audit(r4, NAMED).problems.join("\n");
  for (const key of ["linear domain -1.7976931348623157e+308..1.7976931348623157e+308 beside valueAxis [0,0.9999999999]", "linear domain -1.7976931348623157e+308..1.7976931348623157e+308 beside valueAxis [1e-10,1]", "linear domain 1e+308..1.7976931348623157e+308 beside valueAxis [0,0.9999999999]"]) {
    assert.ok(red6.includes("not on the named list: " + key + ": throws"), "red on " + key);
  }
  // Lane 1063's fixes out, one at a time: every problem is a failing member not on the (empty) list.
  const seen = {};
  for (const [name, p] of Object.entries(LANE_1063_PLANTS)) {
    const { failing, problems } = audit(engineWith([], (s) => plant(s, ...p.edit)), NAMED);
    seen[name] = failing;
    assert.ok(problems.length > 0 && problems.every((x) => x.startsWith("not on the named list: ")), name + ": " + problems.slice(0, 3).join("\n"));
    const mine = problems.find((x) => x.startsWith("not on the named list: " + p.member + ": "));
    assert.ok(mine !== undefined && mine.includes(p.text), `${name}: red on ${p.member} with "${p.text}": ${mine}`);
    assert.equal(failing, p.red, `${name}: members red`);
  }
  console.log(`boundary bank: members red with each of lane 1063's fixes out ${JSON.stringify(seen)}`);
  // A holding member put on the list is a stale entry.
  const holding = "log domain 1..1e+300 beside three ticks";
  assert.equal(broken(R, bank().find((m) => m.key === holding)).length, 0);
  assert.deepEqual(audit(R, { ...NAMED, [holding]: ["#1061"] }).problems, ["stale entry (the member holds): " + holding]);
  // An entry that is no member is red.
  assert.deepEqual(audit(R, { ...NAMED, "log extent 1..1e+300 beside no such form": ["#1061"] }).problems, ["a named entry is no member: log extent 1..1e+300 beside no such form"]);
});

/**
 * The landing verdict's extra plant (1051-r4, R7), kept as a permanent negative control with three of its kind: beside
 * the log left axis 1, 2, 5, 10, the no-domain log extent [1, 1e300] gets a second-axis defect of its own, tick 1 moved
 * to the bottom of the range. Lane 1063 repaired the single-axis top the member once failed for (#1061), so the member
 * holds and every planted defect is a member not on the (empty) named list.
 */
const OFF_GRID_PLANT = [
  "11-axes.js",
  "return out !== null ? out : caseC(lvl, S2, ext, length, shares);",
  "var chosen = out !== null ? out : caseC(lvl, S2, ext, length, shares); if (noDomain && S2.log === true && ext[0] === 1 && ext[1] === 1e300 && left.log === true) chosen.ticks[1] = chosen.lo; return chosen;",
];

test("R7: a planted second-axis defect is red on its member: the off-grid tick on log extent 1..1e300, a range's high end, a tick off a one-time collapsed range, a tick count", () => {
  const key = "log extent 1..1e+300 beside log 1, 2, 5, 10", m = bank().find((x) => x.key === key);
  assert.equal(NAMED[key], undefined, "the control's member is on no list");
  assert.deepEqual(broken(R, m), [], "clean, the member holds");
  const planted = engineWith([], (s) => plant(s, ...OFF_GRID_PLANT));
  const why = broken(planted, m);
  assert.deepEqual(why.map((f) => f.kind), ["position"], JSON.stringify(why));
  const { problems } = audit(planted, NAMED);
  // The plant's condition (a log left axis) also holds beside round 5's two produced log forms: three members, each red.
  const hit = ["log 1, 2, 5, 10", "logAxis [0.9999999999,10.0000000001]", "logAxis [1.0000000001,9.9999999999]"].map((l) => "log extent 1..1e+300 beside " + l);
  assert.deepEqual(problems.map((p) => p.slice(0, p.indexOf(": tick 1 = 1 (rule c) off the share "))), hit.map((k) => "not on the named list: " + k), problems.join("\n"));
  assert.ok(problems[0].startsWith(`not on the named list: ${key}: tick 1 = 1 (rule c) off the share 0.30102999566398`), problems[0]);
  // The other defects are as plain: a range's high end, a tick off its position, an inherited range's count.
  const floorKey = "log extent 5e-324..1e-322 beside two edge ticks", collapseKey = "linear extent -5e-324..5e-324 beside two edge ticks";
  for (const k of [floorKey, collapseKey]) assert.deepEqual(broken(R, bank().find((x) => x.key === k)), [], "clean, the member holds: " + k);
  const floorPlant = engineWith([], (s) => plant(s, ...OFF_GRID_PLANT.slice(0, 2), "var chosen = out !== null ? out : caseC(lvl, S2, ext, length, shares); if (noDomain && S2.log === true && ext[0] === 5e-324 && ext[1] === 1e-322 && n === 2) chosen.hi = chosen.ticks[1] = 2e-323; return chosen;"));
  assert.ok(audit(floorPlant, NAMED).problems.some((p) => p.startsWith(`not on the named list: ${floorKey}: the range`) && p.includes("high end")), "a range's high end");
  const collapsePlant = engineWith([], (s) => plant(s, ...OFF_GRID_PLANT.slice(0, 2), "var chosen = out !== null ? out : caseC(lvl, S2, ext, length, shares); if (noDomain && S2.log !== true && ext[0] === -5e-324 && ext[1] === 5e-324 && n === 2) chosen.ticks[1] = 1; return chosen;"));
  assert.ok(audit(collapsePlant, NAMED).problems.some((p) => p.startsWith(`not on the named list: ${collapseKey}: tick 1 = 1 (rule `) && p.includes(" off the share ")), "a tick off its position on a range that once collapsed");
  const countPlant = engineWith([], (s) => plant(s, ...OFF_GRID_PLANT.slice(0, 2), "var chosen = out !== null ? out : caseC(lvl, S2, ext, length, shares); if (noDomain && S2.log === true && ext[0] === 1 && ext[1] === 1e300 && left.log === true) chosen.ticks.pop(); return chosen;"));
  assert.ok(audit(countPlant, NAMED).problems.some((p) => p.startsWith(`not on the named list: ${key}: 3 ticks beside 4`)), "a tick count");
});

// ---- R5: rounding to significant digits is floating arithmetic ----

const significant = engineWith([], (s) => plant(s, "11-axes.js", "secondAxis: secondAxis };", "secondAxis: secondAxis, significant: significant };")).axes.significant;

/** A finite double as the exact fraction num / den (den a power of two). */
function exact(v) {
  BITS.setFloat64(0, Math.abs(v));
  const bits = BITS.getBigUint64(0), e = Number(bits >> 52n), frac = bits & ((1n << 52n) - 1n);
  const mant = e === 0 ? frac : frac | (1n << 52n), p = (e === 0 ? 1 : e) - 1075;
  return p >= 0 ? { num: mant << BigInt(p), den: 1n } : { num: mant, den: 1n << BigInt(-p) };
}

/**
 * significant(v, d) against exact decimal rounding, d <= 15, |v| below 1e308: {q, at} where q is the exact rounding's
 * digits (half away from zero) of |v| / 10^e, e = decade(|v|) - (d - 1), and at(k) the double the digits k read as.
 * `near(k)` is true when |v| / 10^e lies within 2^-50 of its own size of the half between q and k: the band in which the
 * engine's arithmetic may round to k instead (see the bound below).
 */
function exactRounding(v, d) {
  const a = Math.abs(v), e = R.scales.decade(a) - (d - 1), { num, den } = exact(a);
  const ten = 10n ** BigInt(Math.abs(e)), A = e < 0 ? num * ten : num, B = e < 0 ? den : den * ten;
  const q = (2n * A + B) / (2n * B);
  const at = (k) => (v < 0 ? -1 : 1) * Number(k + "e" + e);
  const near = (k) => {
    const half = k > q ? 2n * q + 1n : 2n * q - 1n, gap = 2n * A - half * B;
    return (gap < 0n ? -gap : gap) * (1n << 50n) <= 2n * A;
  };
  return { q, at, near };
}

test("R5: significant(1.0005e-8, 4) is 1.001e-8 where exact decimal rounding of the stored double gives 1e-8 (the verdict's boundary, pinned)", () => {
  assert.equal(significant(1.0005e-8, 4), 1.001e-8);
  assert.equal(Number((1.0005e-8).toPrecision(4)), 1e-8);
  const r = exactRounding(1.0005e-8, 4);
  assert.equal(r.at(r.q), 1e-8);
  assert.ok(r.near(r.q + 1n), "the stored double is within the bound's band of the half 1000.5");
});

test("R5: the bound, d = 3..15: significant gives the exact decimal rounding, or one unit beside it only for a value within 2^-50 of its size of the half between the two", () => {
  // Why 2^-50: q = round(|v| / 10^e) is formed by at most four roundings, each within 2^-53 of its value (the table's
  // 10^e and 10^300 parsed from decimal text, the division, and the raise |v| x 10^300 below 10^-307), so the quotient
  // stands within 4 x 2^-53 = 2^-51 of |v| / 10^e and Math.round can only cross a half that close; q < 10^15 < 2^53, so
  // q itself is exact, and Number(q + "e" + e) reads the digits as the nearest double. 2^-50 leaves a factor of two.
  const mantissas = ["1.0005", "1.5", "2.5", "1.25", "1.125", "9.9995", "1.00005", "4.4445", "1.2345", "7.65", "3.335", "1.000000000000005", "6.0000000000005", "1", "1.23456789", "4.94", "9.999", "5.5", "2.2250738585072014"];
  let n = 0, beside1 = 0;
  for (let k = -323; k <= 307; k += 3) {
    for (const m of mantissas) {
      for (const sign of [1, -1]) {
        const v = sign * Number(m + "e" + k);
        if (!(v !== 0 && Number.isFinite(v) && Math.abs(v) < 1e308)) continue;
        for (let d = 3; d <= 15; d++) {
          const got = significant(v, d), r = exactRounding(v, d), at = `${v} at ${d} digits: ${got}, exact ${r.at(r.q)}`;
          n++;
          if (got === r.at(r.q)) continue;
          const k1 = [r.q + 1n, r.q - 1n].find((x) => got === r.at(x));
          assert.ok(k1 !== undefined, "more than one unit from the exact rounding: " + at);
          assert.ok(r.near(k1), "one unit beside it, outside the band of the half: " + at);
          beside1++;
        }
      }
    }
  }
  console.log(`R5 bound: ${n} values rounded, ${n - beside1} the exact decimal rounding, ${beside1} one unit beside it within the band`);
  assert.ok(n > 40000, "n " + n);
  assert.ok(beside1 > 0, "the bank holds a boundary the arithmetic rounds the other way (the bound is exercised)");
});
