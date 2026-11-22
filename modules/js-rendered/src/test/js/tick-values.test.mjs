// The range and the ticks an axis chooses at the ends of the number range (brief 1063: #1063, #1061, the numeric half of
// #1060; design record 2026-11-14-js-rendered-charts §4.6 and its note of 2026-11-22): invariants read over a grid of
// extents by this file's own arithmetic (decimal texts, exact comparisons of doubles, BigInt), never by R.scales, and
// the worked examples. The test names carry the brief's checks, A.1 to A.8 (A.7 is the whole suite and the comparison
// of every picture; the boundary bank of A.6 is second-bank.test.mjs).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { engine, engineWith } from "./engine.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const R = engine(), S = R.scales, A = R.axes;
const MAX = Number.MAX_VALUE, MIN = Number.MIN_VALUE;

/** The next double above a positive finite v, by this test alone (a typed array's bits, never the engine). */
function up(v, k = 1) {
  const f = new Float64Array([v]), u = new BigUint64Array(f.buffer);
  u[0] += BigInt(k);
  return f[0];
}
/** Significant digits of a double's shortest text. */
function digits(v) {
  const m = String(Math.abs(v)).split("e")[0].replace(".", "").replace(/^0+/, "").replace(/0+$/, "");
  return m.length === 0 ? 1 : m.length;
}

/** A finite double as an exact whole number of 2^-1074. */
function exact(v) {
  const u = new BigUint64Array(new Float64Array([Math.abs(v)]).buffer)[0], e = Number((u >> 52n) & 0x7ffn), m = u & 0xfffffffffffffn;
  const n = e === 0 ? m : (m | (1n << 52n)) << BigInt(e - 1);
  return v < 0 ? -n : n;
}
const UNIT = 1n << 1074n, pow = (e) => 10n ** BigInt(e);
const floorDiv = (a, b) => (a >= 0n ? a / b : -((-a + b - 1n) / b));
/** Significant digits of a whole number: its zeros at the end are not digits it needs. */
const need = (k) => String(k < 0n ? -k : k).replace(/0+$/, "").length;
/**
 * The verdict on two ends a < b by exact arithmetic (BigInt) and Number's reading of a decimal text, nothing of the
 * engine: "two" when the smallest
 * step of 1, 2 or 5 × a power of ten at or above their span writes every tick from the one at or below a to the one at
 * or above b in at most 15 significant digits, "one" when a tick needs a 16th, null where the answer hangs on the
 * engine's tolerances (a span within a millionth above a step, an end within a millionth of a step below a tick).
 */
function verdict(a, b) {
  const A = exact(a), B = exact(b), span = B - A;
  // The steps 1, 2, 5 × 10^e in rising order, each as a fraction num / den of 2^-1074: the first at or above the span
  // is the judge; the one before it too where the span is within a millionth above it (the engine may take either).
  const steps = [];
  let before = null;
  for (let e = Math.floor(Math.log10(b - a)) - 2; steps.length === 0; e++) for (const m of [1n, 2n, 5n]) {
    const step = { m, e, num: m * (e >= 0 ? pow(e) : 1n) * UNIT, den: e >= 0 ? 1n : pow(-e) };
    if (step.num >= span * step.den) {
      steps.push(step);
      if (before !== null && before.num * 1000001n >= span * before.den * 1000000n) steps.push(before);
      break;
    }
    before = step;
  }
  const answers = new Set();
  for (const s of steps) {
    // a / step and b / step as exact fractions: (A × den) / num. A tick is the double its decimal text reads as (D1),
    // so where an end is the double of a tick's decimal, or lies between that decimal and its double, the tick decides.
    let lo = floorDiv(A * s.den, s.num), hi = -floorDiv(-B * s.den, s.num);
    const tick = (i) => Number(`${i * s.m}e${s.e}`);
    while (tick(lo + 1n) <= a) lo++;
    while (tick(lo) > a) lo--;
    while (tick(hi - 1n) >= b) hi--;
    while (tick(hi) < b) hi++;
    const los = [lo], his = [hi];
    if (((lo + 1n) * s.num - A * s.den) * 1000000n <= s.num) los.push(lo + 1n);
    if ((B * s.den - (hi - 1n) * s.num) * 1000000n <= s.num) his.push(hi - 1n);
    for (const f of los) for (const l of his) {
      let wide = false;
      for (let i = f; i <= l; i++) if (need(i * s.m) > 15) wide = true;
      answers.add(wide ? "one" : "two");
    }
  }
  return answers.size === 1 ? [...answers][0] : null;
}

const BASES = [MIN, 1e-323, 1e-322, 1e-320, 2.2250738585072014e-308, 1e-300, 1e-220, 1e-100, 1e-9, 0.1, 0.3, 0.8, 1, 3.3, 99.99999999999999, 100, 435, 1234.56, 1e9, 1e15, 1.7e18, 1e30, 1e98, 1e300, 1e308, MAX];
const INTERVALS = [1, 2, 3, 4, 5, 7, 10, 12];

/** Every extent of the grid: [min, max, kind]. */
function extents() {
  const out = [];
  for (const b of BASES) {
    out.push([0, b, "from zero"], [-b, 0, "to zero"], [-b, b, "across zero"]);
    for (const k of [1, 2, 3, 8, 100, 1000, 1e6]) if (up(b, k) < Infinity && up(b, k) > b) out.push([b, up(b, k), "doubles apart"], [-up(b, k), -b, "doubles apart"]);
    for (const rel of [1e-12, 1e-9, 1e-6, 1e-3, 0.1, 0.5]) if (b * (1 + rel) > b && b * (1 + rel) < Infinity) out.push([b, b * (1 + rel), "narrow"], [-b * (1 + rel), -b, "narrow"]);
  }
  for (let i = 0; i < BASES.length; i++) for (let j = i + 1; j < BASES.length; j++) out.push([BASES[i], BASES[j], "pair"], [-BASES[j], -BASES[i], "pair"], [-BASES[i], BASES[j], "pair"]);
  return out;
}

test("A.1 a linear axis's range: at least two finite ticks that rise, the range the ticks' range, both ends of the data inside it, no tick of more than 15 significant digits", () => {
  const counts = { members: 0, bounded: 0, oneValue: 0, subnormal: 0, beyond: 0, kinds: {} }, wrong = [];
  for (const [min, max, kind] of extents()) for (const n of INTERVALS) {
    counts.members++; counts.kinds[kind] = (counts.kinds[kind] || 0) + 1;
    const r = S.nice(min, max, n), where = `nice(${min}, ${max}, ${n}) = ${JSON.stringify(r.ticks)}`, bad = (m) => wrong.push(m + ": " + where);
    if (!(r.ticks.length >= 2)) { bad("fewer than two ticks"); continue; }
    if (!r.ticks.every(Number.isFinite)) bad("a tick is not finite");
    // Never more ticks than asked plus one, but for the one fallback that has three: a span beyond the largest double
    // is its two ends and their middle (bounded, as before this lane), at every count asked.
    const beyond = max - min === Infinity;
    if (beyond) { counts.beyond++; if (!(r.ticks.length === 3 && r.ticks[0] === min && r.ticks[1] === min / 2 + max / 2 && r.ticks[2] === max)) bad("a span beyond the largest double is not its two ends and their middle"); }
    else if (r.ticks.length > n + 1) bad("more ticks than asked plus one");
    if (!r.ticks.every((t, i) => i === 0 || t > r.ticks[i - 1])) bad("ticks do not rise");
    if (r.lo !== r.ticks[0] || r.hi !== r.ticks[r.ticks.length - 1]) bad("the range is not the ticks' range");
    const gap = Math.min(...r.ticks.slice(1).map((t, i) => t / 2 - r.ticks[i] / 2)) * 2, tol = 1e-9 * gap;
    if (!(r.lo <= min + tol)) bad("the low end is left out");
    if (!(r.hi >= max - tol)) bad("the high end is left out");
    const plain = r.ticks.every((t) => digits(t) <= 15);
    if (!plain) { if (r.ticks.every((t) => t === min || t === max || t === min / 2 + max / 2)) counts.bounded++; else bad("a tick of more than 15 significant digits"); }
    if (min > 0 && r.lo === 0) counts.oneValue++;
    if (max < 0 && r.hi === 0) counts.oneValue++;
    if (Math.max(Math.abs(min), Math.abs(max)) < 2.2250738585072014e-308) counts.subnormal++;
  }
  console.log("A.1", JSON.stringify(counts), "wrong", wrong.length);
  const kinds = {}; for (const w of wrong) { const k = w.split(":")[0]; kinds[k] = (kinds[k] || 0) + 1; } console.log("A.1 kinds", JSON.stringify(kinds));
  assert.deepEqual(wrong.slice(0, 12), [], wrong.length + " wrong");
  assert.ok(counts.members >= 5000 && counts.oneValue >= 300 && counts.subnormal >= 200 && counts.beyond >= 24, "floors: " + JSON.stringify(counts));
  for (const n of INTERVALS) assert.deepEqual([...S.nice(-MAX, MAX, n).ticks], [-MAX, 0, MAX], "the span beyond the largest double, asked " + n);
  assert.deepEqual([...S.nice(-1e308, 1e308, 1).ticks], [-1e308, 0, 1e308]);
  // A span over 10^308 inside the doubles keeps the nice ticks the base drew: its judge's step (2e308) is past the
  // largest double and judges nothing.
  assert.deepEqual([...S.nice(0, 1.5e308, 4).ticks], [0, 5e307, 1e308, 1.5e308]);
  assert.deepEqual([...S.nice(-1e308, 5e307, 4).ticks], [-1e308, -5e307, 0, 5e307]);
  assert.deepEqual([...S.nice(1e307, 1.5e308, 3).ticks], [0, 5e307, 1e308, 1.5e308]);
});

test("A.2 values that differ only past the 15th significant digit are one value: the range a single value takes (owner 2026-11-21), at every axis length", () => {
  const same = (a, b) => { for (const n of INTERVALS) { const r = S.nice(a, b, n), one = b > 0 ? S.nice(b, b, n) : S.nice(a, a, n); assert.deepEqual(r, one, `nice(${a}, ${b}, ${n})`); } };
  same(0.3, 0.1 + 0.2); same(3.3, 1.1 * 3); same(99.99999999999999, 100); same(100, 100.00000000000001); same(1234.56, 1234.5600000000002); same(434.99999999999994, 435);
  same(-(0.1 + 0.2), -0.3); same(-1, -0.9999999999999998); same(1.7e18, 1.7e18 + 1024); same(-5e98, -4.9999999999999986e98);
  // One value next to the largest double, whose single value takes the fallback 0 … the value: the pair takes it too.
  same(1.7e308, 1.7000000000000001e308); same(MAX - 2 ** 971, MAX); same(-MAX, -(MAX - 2 ** 971));
  assert.deepEqual([...S.nice(1.7e308, 1.7000000000000001e308, 4).ticks], [0, 1.7000000000000001e308]);
  assert.deepEqual([...S.nice(0.3, 0.1 + 0.2, 4).ticks], [0, 0.1, 0.2, 0.3]);
  assert.deepEqual([...S.nice(3.3, 1.1 * 3, 4).ticks], [0, 1, 2, 3, 4]);
  // Not one value: the ends differ within 15 digits, and the ticks are decimals a reader can tell apart.
  assert.deepEqual([...S.nice(99.5, 99.7, 4).ticks], [99.5, 99.55, 99.6, 99.65, 99.7]);
  assert.deepEqual([...S.nice(1e9, 1e9 + 1, 4).ticks], [1e9, 1000000000.25, 1000000000.5, 1000000000.75, 1000000001]);
  assert.deepEqual([...S.nice(1, 1.0000000000001, 4).ticks].map(digits).every((d) => d <= 15), true);
  // The answer does not depend on the axis's length: one value at one length is one value at all.
  let flips = 0, pairs = 0;
  for (const [a, b] of extents().filter((e) => (e[2] === "doubles apart" || e[2] === "narrow") && e[1] - e[0] < Math.min(Math.abs(e[0]), Math.abs(e[1])) * 1e-3)) {
    const one = INTERVALS.map((n) => { const r = S.nice(a, b, n); return a > 0 ? r.lo === 0 : r.hi === 0; });
    pairs++; if (new Set(one).size > 1) { flips++; if (flips <= 6) console.log("  flip", a, b, JSON.stringify(one), JSON.stringify(INTERVALS.map((n) => S.nice(a, b, n).ticks.length))); }
  }
  console.log("A.2 pairs", pairs, "that are one value at some lengths only", flips);
  assert.equal(flips, 0);
  assert.ok(pairs >= 400);
});

test("A.2 a difference its ticks can write in 15 digits is two values, to the last of the 15: the pairs at a power of ten, and every pair against an oracle in exact arithmetic", () => {
  const two = (a, b, at4) => {
    for (const n of INTERVALS) {
      const r = S.nice(a, b, n), where = `nice(${a}, ${b}, ${n}) = ${JSON.stringify(r.ticks)}`;
      assert.ok(r.lo <= a && r.hi >= b && (a > 0 ? r.lo > 0 : r.hi < 0), "two values: " + where);
      assert.ok(r.ticks.every((t) => digits(t) <= 15), "15 digits: " + where);
    }
    if (at4) assert.deepEqual([...S.nice(a, b, 4).ticks], at4);
  };
  // A carry across a power of ten: 10^15 is one digit, the number below it fifteen.
  two(999999999999999, 1e15, [999999999999999, 1e15]); two(-1e15, -999999999999999, [-1e15, -999999999999999]);
  two(0.999999999999999, 1, [0.999999999999999, 1]); two(9.99999999999999e-6, 1e-5, [9.99999999999999e-6, 1e-5]);
  two(99999999999999.9, 1e14, [99999999999999.9, 1e14]); two(999999999999998, 1e15); two(999999999999995, 1e15);
  // Two ends on the two sides of a power of ten, 2.5 units of the 14th digit apart: a step of 2.5 is not the judge.
  two(0.999999999999995, 1.00000000000002, [0.99999999999999, 1, 1.00000000000001, 1.00000000000002]);
  two(-1.00000000000002, -0.999999999999995);
  // One digit further is rounding: one value.
  const one = (a, b) => { for (const n of INTERVALS) { const r = S.nice(a, b, n); assert.ok(a > 0 ? r.lo === 0 : r.hi === 0, `one value: nice(${a}, ${b}, ${n}) = ${JSON.stringify(r.ticks)}`); } };
  one(9999999999999998, 1e16); one(0.9999999999999999, 1); one(1e15, up(1e15)); one(1, up(1)); one(-1e16, -9999999999999998);
  // Every pair: the engine's verdict at all eight tick counts against the oracle's.
  const pairs = [];
  for (const [a, b, kind] of extents()) if ((kind === "doubles apart" || kind === "narrow") && Math.abs(a) > 1e-290 && Math.abs(b) < 1e290) pairs.push([a, b]);
  for (const p of [-280, -200, -120, -40, -20, -6, -5, -1, 0, 1, 2, 14, 15, 16, 22, 40, 120, 200, 280]) {
    const ten = Number("1e" + p), add = (a, b) => { if (a < b && b - a < b * 1e-3) pairs.push([a, b], [-b, -a]); };
    for (const j of [1n, 2n, 3n, 5n, 10n, 25n, 50n]) for (const i of [0n, 1n, 2n, 5n, 10n, 20n, 25n]) add(Number(10n ** 15n - j + "e" + (p - 15)), Number(10n ** 14n + i + "e" + (p - 14)));
    for (const j of [1n, 2n, 3n, 5n, 10n]) add(Number(10n ** 16n - j + "e" + (p - 16)), ten);
    for (const i of [1n, 2n, 5n, 10n, 25n]) { add(ten, Number(10n ** 15n + i + "e" + (p - 15))); add(ten, Number(10n ** 14n + i + "e" + (p - 14))); }
  }
  const seen = { pairs: pairs.length, one: 0, two: 0, open: 0 }, wrong = [];
  for (const [a, b] of pairs) {
    const want = verdict(a, b);
    if (want === null) { seen.open++; continue; }
    seen[want]++;
    for (const n of INTERVALS) {
      const r = S.nice(a, b, n), got = (a > 0 ? r.lo === 0 : r.hi === 0) ? "one" : "two";
      if (got !== want) { wrong.push(`nice(${a}, ${b}, ${n}) is ${got} value(s), the oracle says ${want}: ${JSON.stringify(r.ticks)}`); break; }
      if (got === "two" && !(r.ticks.every((t) => digits(t) <= 15) || (r.ticks.length === 2 && r.ticks[0] === a && r.ticks[1] === b))) { wrong.push(`a tick of more than 15 digits: nice(${a}, ${b}, ${n}) = ${JSON.stringify(r.ticks)}`); break; }
    }
  }
  console.log("A.2 oracle", JSON.stringify(seen), "wrong", wrong.length);
  assert.deepEqual(wrong.slice(0, 12), [], wrong.length + " wrong");
  assert.ok(seen.one >= 300 && seen.two >= 300 && seen.open * 20 <= seen.pairs, "floors: " + JSON.stringify(seen));
});

test("A.3 a tick is its decimal value: the ticks of 0..1e30 end at 1e30, of a subnormal extent keep their values", () => {
  assert.deepEqual([...S.nice(0, 1e30, 4).ticks], [0, 2.5e29, 5e29, 7.5e29, 1e30]);
  assert.deepEqual([...S.nice(1e-322, 1e-321, 4).ticks], [0, 2.5e-322, 5e-322, 7.5e-322, 1e-321]);
  assert.deepEqual([...S.nice(-MIN, MIN, 4).ticks].length >= 2, true);
  const a = A.valueAxis("full", [1e-322, 1e-321], null, 200, null, 0), pos = a.ticks.map(A.scaleOf(a, 200, 0));
  assert.equal(new Set(pos).size, a.ticks.length, "distinct positions: " + pos.join(", "));
});

test("A.4 a log axis's range: finite positive ticks that rise, the smallest and the largest value inside it, to both ends of the doubles (#1060 floor, #1061)", () => {
  const vals = [MIN, 1e-323, 1e-322, 1e-320, 1e-308, 1e-300, 1e-185, 1e-9, 0.001, 0.3, 1, 3, 4, 100, 1e6, 1e18, 1e150, 1e185, 1e248, 1e300, 1.5e300, 9.9e307, 1e308, 1.5e308, MAX];
  let members = 0; const wrong = [], top = { past308: 0, floor: 0 };
  for (let i = 0; i < vals.length; i++) for (let j = i; j < vals.length; j++) for (const n of INTERVALS) {
    members++;
    let r; try { r = S.logNice(vals[i], vals[j], n, null); } catch (e) { wrong.push(`logNice(${vals[i]}, ${vals[j]}, ${n}) throws ${e.message}`); continue; }
    const where = `logNice(${vals[i]}, ${vals[j]}, ${n}) = ${JSON.stringify(r.ticks)}`, bad = (m) => wrong.push(m + ": " + where);
    if (!(r.ticks.length >= 2)) bad("fewer than two ticks");
    if (!r.ticks.every((t) => t > 0 && t < Infinity)) bad("a tick is not a positive finite number");
    if (!r.ticks.every((t, k) => k === 0 || t > r.ticks[k - 1])) bad("ticks do not rise");
    if (r.lo !== r.ticks[0] || r.hi !== r.ticks[r.ticks.length - 1]) bad("the range is not the ticks' range");
    if (!(r.lo <= vals[i])) bad("the smallest value is left out");
    if (!(r.hi >= vals[j])) bad("the largest value is left out");
    if (r.ticks.length > n + 1 && r.ticks.length > 2) bad("more ticks than the axis has room for");
    if (vals[j] > 1e308) top.past308++; if (vals[i] === MIN) top.floor++;
  }
  console.log("A.4 members", members, JSON.stringify(top), "wrong", wrong.length);
  assert.deepEqual(wrong.slice(0, 12), [], wrong.length + " wrong");
  assert.ok(members >= 2000 && top.past308 >= 300 && top.floor >= 150);
  assert.deepEqual([...S.logNice(1e-300, 1e300, 4, null).ticks], [1e-300, 1e-150, 1, 1e150, 1e300]);
  assert.deepEqual([...S.logNice(MIN, 1e-322, 4, null).ticks], [MIN, 1e-323, 2e-323, 5e-323, 1e-322]);
  assert.equal(S.logNice(1e300, MAX, 4, null).hi, MAX);
  assert.deepEqual([...S.logNice(1, 1.5e308, 4, null).ticks], [1, MAX]);
  assert.deepEqual([...S.logNice(1e308, 1e308, 4, null).ticks], [1e308, MAX]);
});

test("A.5 a log domain whose two ends share one logarithm still places them apart (#1063)", () => {
  for (const d of [[8.488316745e130, 8.488316745000002e130], [7.215959968999993e39, 7.215959969e39], [1, up(1)], [5, up(5, 3)]]) {
    const a = A.logAxis("full", d, d, 200, null), at = A.scaleOf(a, 200, 0);
    assert.equal(at(d[0]), 200, String(d)); assert.equal(at(d[1]), 0, String(d));
    assert.equal(new Set(a.ticks.map(at)).size, a.ticks.length, "distinct tick positions for " + d);
  }
  // An ordinary log domain keeps the positions it had.
  const at = S.log(1, 1000, 0, 300);
  assert.deepEqual([1, 10, 100, 1000].map(at), [0, 100, 200, 300]);
});

test("A.6 the second axis draws values the ticks cannot tell apart as one value too: what it gives for a single value, beside every left form; a real difference keeps its ticks", () => {
  const lefts = { "two edge ticks": [0, 1], "three ticks": [0, 0.5, 1], "five ticks": [0, 0.25, 0.5, 0.75, 1], "inset ticks": [0.2, 0.4, 0.6, 0.8] };
  const second = (ext, ticks) => A.secondAxis("full", { domain: null, format: null, log: false }, ext, { lo: 0, hi: 1, ticks, log: false }, 200);
  let read = 0;
  for (const [a, b] of [[0.3, 0.1 + 0.2], [99.99999999999999, 100], [100, 100.00000000000001], [1.7e18, 1.7e18 + 1024], [-(0.1 + 0.2), -0.3], [-1, -0.9999999999999998]]) for (const [name, ticks] of Object.entries(lefts)) {
    const ax = second([a, b], ticks), one = second(a > 0 ? [b, b] : [a, a], ticks), where = `${a} .. ${b} beside ${name}: ${JSON.stringify(ax.ticks)}`;
    assert.deepEqual({ lo: ax.lo, hi: ax.hi, ticks: [...ax.ticks], rule: ax.rule }, { lo: one.lo, hi: one.hi, ticks: [...one.ticks], rule: one.rule }, where);
    assert.ok(ax.ticks.every((t) => digits(t) <= 15) && ax.ticks.every((t, i) => i === 0 || t > ax.ticks[i - 1]), "short ticks that rise: " + where);
    // The range of one value, from 0; it holds both ends as the second axis holds any extent, within a billionth of itself.
    const tol = 1e-9 * (ax.hi - ax.lo);
    assert.ok(ax.lo <= a + tol && ax.hi >= b - tol && (a > 0 ? ax.lo <= 0 : ax.hi >= 0), "the range of one value, from 0: " + where);
    read++;
  }
  assert.equal(read, 24);
  assert.deepEqual([...second([99.5, 99.7], lefts["three ticks"]).ticks], [99.5, 99.6, 99.7]);
  assert.deepEqual([...second([1e9, 1e9 + 1], lefts["three ticks"]).ticks], [1e9, 1000000000.5, 1000000001]);
  assert.equal(S.oneValue(0.3, 0.1 + 0.2), true); assert.equal(S.oneValue(999999999999999, 1e15), false); assert.equal(S.oneValue(1, 10), false); assert.equal(S.oneValue(3, 3), false);
});

test("A.8 the probes of the repaired paths, Node's half: tick-value-probes.js evaluated in Node is tick-value-probes.json byte for byte (the sandbox's half is the JVM test's); and the comparison goes red on one tick changed in one run alone", () => {
  const script = readFileSync(path.join(HERE, "tick-value-probes.js"), "utf8"), expected = readFileSync(path.join(HERE, "tick-value-probes.json"), "utf8");
  const run = (R) => new Function("R", script + "\nreturn tickValueProbes(R);")(R);
  // The engine without the test types: the sandbox evaluates the product's program alone.
  const plain = engineWith([]), text = run(plain);
  assert.equal(text + "\n", expected);
  const probes = JSON.parse(text), groups = {};
  for (const p of probes) { const g = p.name.split(":")[0]; groups[g] = (groups[g] || 0) + 1; }
  console.log(`A.8 probes: ${probes.length}, ${text.length} characters, ${JSON.stringify(groups)}`);
  assert.ok(probes.length >= 78, "probes " + probes.length);
  for (const [g, floor] of Object.entries({ decimal: 15, beyond: 4, "one value": 9, "two values": 13, log: 13, axis: 13, place: 3, second: 12 })) assert.ok(groups[g] >= floor, g + " " + groups[g]);
  for (const p of probes) assert.ok([p.lo, p.hi, ...(p.ticks || []), ...(p.at || []), ...(p.ends || [])].filter((v) => v !== undefined).every(Number.isFinite), p.name);
  assert.equal(new Set(probes.map((p) => p.name)).size, probes.length, "every probe has its own name");
  // The control (tickValueProbesPlanted, in the probe file so both runs evaluate the same code): one tick of one range
  // is the tip's product. Its text is the snapshot but for that one number, in the one probe named here.
  const planted = new Function("R", script + "\nreturn tickValueProbesPlanted(R);")(plain), WAS = "2.4999999999999997e+29", IS = "2.5e+29";
  assert.notEqual(planted, text);
  assert.equal(planted.split(WAS).length - 1, 1); assert.equal(text.split(WAS).length - 1, 0);
  assert.equal(planted.replace(WAS, IS), text);
  const other = JSON.parse(planted);
  assert.deepEqual(probes.filter((p, i) => JSON.stringify(p) !== JSON.stringify(other[i])).map((p) => p.name), ["decimal: nice 0 .. 1e+30 in 4"]);
});
