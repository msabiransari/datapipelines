// Brief 1051 round 3 (the correction round; landing verdict specs/js-rendered-types/landings/1051-r2.md): the second value
// axis (D5) on the three defects the lander found, one group each, every expectation the test's own.
//   R1 an engine-chosen right range holds the data, both signs of it: judged against the DATA (the line's smallest and
//      largest number, within a share 1e-9 of the data's own span), never with a tolerance that grows with the range.
//   R2 finite authored ends whose difference overflows a double (-1e308..1e308) give finite shares, finite values and
//      the authored ends back exactly, on the left and on the right, as the single axis already does.
//   R3 rounding to significant digits is total over finite values: a finite value rounds to a finite one, the
//      expectation is toPrecision's (the test's oracle; the engine may not call it, O-16).
// And the Node half of the identity on these paths: second-axis-probes.js evaluated here equals second-axis-probes.json
// byte for byte; JsRenderedTestTypesTest compares the sandbox's text with the same file.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";
import { engineWith } from "./engine.mjs";
import { formsEngine, plant, fixture, draw, rightLabels } from "./forms.mjs";

const R = formsEngine();
const HERE = path.dirname(fileURLToPath(import.meta.url));
const SWEEP = WIDTHS.flatMap((w) => HEIGHTS.map((h) => [w, h]));
const linear = (domain) => ({ domain, format: null, log: false });
const L2 = { lo: 0, hi: 1, ticks: [0, 1], log: false };
const L3 = { lo: 0, hi: 1, ticks: [0, 0.5, 1], log: false };
const L5 = { lo: 0, hi: 1, ticks: [0, 0.25, 0.5, 0.75, 1], log: false };
const OFFSET = { lo: 0, hi: 1, ticks: [0.2, 0.4, 0.6, 0.8], log: false };
const NEG = { lo: -1, hi: 1, ticks: [-1, 0, 1], log: false };
const WIDE = { lo: 0, hi: 100, ticks: [0, 100], log: false };
const LOG125 = { lo: 1, hi: 10, ticks: [1, 2, 5, 10], log: true };

/** The data held by [lo, hi] within a share 1e-9 of the DATA's span (one value: exactly). */
const holds = (ax, e0, e1) => ax.lo <= e0 + 1e-9 * (e1 - e0) && ax.hi >= e1 - 1e-9 * (e1 - e0);

/** A test_two_axes fixture of the verdict's shape: categories, left values and domain, right values and domain. */
function twoAxes({ x, y, yDomain = null, yLog = false, y2, y2Domain = null }) {
  const fx = structuredClone(fixture("test_two_axes", "case-a-linear"));
  delete fx.document.config.title;
  fx.document.config.encoding.y = { field: "y", ...(yDomain ? { domain: yDomain } : {}), ...(yLog ? { log: true } : {}) };
  fx.document.config.encoding.y2 = { field: "y2", ...(y2Domain ? { domain: y2Domain } : {}) };
  fx.columns = { x, y, y2 };
  return fx;
}

/** The line's dots, in category order: {cy, datum}. */
const dots = (r) => r.primitives.filter((p) => p.k === "circle" && p.series === "line");

// ---- R1: the chosen right range holds the data ----

const SIGNED = [[-1, 1], [-0.001, 0.003], [-1, 1e-6], [-1e-6, 1], [-3, 0.2], [-0.5, 2], [-7, 7.5], [-1e6, 1], [-2.5, 2.5], [-123.4, 5678.9], [-1, 0], [0, 1], [3, 4], [-40, -39]];

test("R1: every engine-chosen right range holds the data beside two, three, five, inset, signed and wide left axes, at distinct positions", () => {
  let checked = 0, least = Infinity;
  for (const left of [L2, L3, L5, OFFSET, NEG, WIDE]) {
    for (const [e0, e1] of SIGNED) {
      const ax = R.axes.secondAxis("full", linear(null), [e0, e1], left, 200), at = `${JSON.stringify(left.ticks)} beside ${e0}..${e1}`;
      assert.equal(ax.ticks.length, left.ticks.length, at);
      assert.ok([ax.lo, ax.hi, ...ax.ticks].every(Number.isFinite), at);
      assert.ok(holds(ax, e0, e1), `the range ${ax.lo}..${ax.hi} leaves out data of ${e0}..${e1}: ${at}`);
      ax.ticks.forEach((t, j) => j === 0 || assert.ok(t > ax.ticks[j - 1], `right ticks increase: ${ax.ticks} ${at}`));
      // The data's two ends stand at two positions of a 200 px axis that a reader can tell apart.
      const scale = R.scales.linear(ax.lo, ax.hi, 200, 0);
      assert.ok(scale(e0) - scale(e1) >= 10, `the data ${e0}..${e1} spans ${scale(e0) - scale(e1)} px of the range ${ax.lo}..${ax.hi}: ${at}`);
      least = Math.min(least, (e1 - e0) / (ax.hi - ax.lo));
      checked++;
    }
  }
  console.log(`R1 helper: ${checked} extents held; the data's least share of its range ${least.toFixed(3)}`);
  assert.equal(checked, 6 * SIGNED.length);
});

test("R1: two left ticks on the plot's edges beside -1..1 (the verdict's input): the range holds both signs, the ticks -1 and 1", () => {
  const ax = R.axes.secondAxis("full", linear(null), [-1, 1], L2, 200);
  assert.ok(ax.lo <= -1 && ax.hi >= 1, `${ax.lo}..${ax.hi}`);
  assert.deepEqual([...ax.ticks], [-1, 1]);
  assert.notEqual(ax.lo, 0);
});

test("R1: the range starts at the LARGEST multiple that holds the low end, also where the division rounds below it (0.3 / 0.1)", () => {
  // 0.3..0.7 beside five equally spaced left ticks: the step 0.1 holds it from 0.3 (= 3 x 0.1 up to rounding) to 0.7.
  const ax = R.axes.secondAxis("full", linear(null), [0.3, 0.7], L5, 200);
  assert.equal(ax.rule, "a");
  assert.deepEqual([...ax.ticks], [0.3, 0.4, 0.5, 0.6, 0.7]);
  assert.ok(holds(ax, 0.3, 0.7), `${ax.lo}..${ax.hi}`);
});

test("R1 rendered: the verdict's 240 x 140 picture (A, B; left domain and values 0..1; right values -1, 1): both dots inside the plot, apart, the lower value lower", () => {
  const { r, hand } = draw(R, twoAxes({ x: ["A", "B"], y: [0, 1], yDomain: [0, 1], y2: [-1, 1] }), 240, 140);
  assert.equal(r.state, "rendered");
  assert.deepEqual([...hand.value.ticks], [0, 1]);
  assert.ok(hand.value2.lo <= -1 && hand.value2.hi >= 1, `${hand.value2.lo}..${hand.value2.hi}`);
  const [a, b] = dots(r), plot = hand.plot;
  for (const d of [a, b]) assert.ok(d.cy >= plot.y - 0.011 && d.cy <= plot.y + plot.h + 0.011, `dot ${d.cy} inside ${plot.y}..${plot.y + plot.h}`);
  assert.ok(a.cy - b.cy >= 10, `the dot of -1 at ${a.cy}, of 1 at ${b.cy}`);
});

test("R1 rendered, every frame of the sweep with two left ticks: signed right values are held, their dots inside the plot and in the data's order", () => {
  let twoTick = 0, pictures = 0;
  for (const y2 of [[-1, 1], [-0.5, 2], [-3, 0.2], [-0.001, 0.001], [-1e6, 1]]) {
    const fx = twoAxes({ x: ["A", "B", "C"], y: [0, 1, 0.5], yDomain: [0, 1], y2: [y2[0], y2[1], (y2[0] + y2[1]) / 2] });
    for (const [w, h] of SWEEP) {
      const { r, hand } = draw(R, fx, w, h);
      if (r.state !== "rendered") continue;
      pictures++;
      const at = `${y2} at ${w}x${h}`, v2 = hand.value2, plot = hand.plot;
      assert.ok(holds(v2, y2[0], y2[1]), `the range ${v2.lo}..${v2.hi} leaves out ${y2}: ${at}`);
      const d = dots(r);
      assert.equal(d.length, 3, at);
      for (const p of d) assert.ok(p.cy >= plot.y - 0.011 && p.cy <= plot.y + plot.h + 0.011, `dot at ${p.cy} outside ${plot.y}..${plot.y + plot.h}: ${at}`);
      assert.ok(d[0].cy > d[2].cy && d[2].cy > d[1].cy, `dots ${d.map((p) => p.cy)} not in the data's order: ${at}`);
      if (hand.value.ticks.length === 2) twoTick++;
    }
  }
  console.log(`R1 rendered: ${pictures} pictures, ${twoTick} with two left ticks`);
  assert.ok(twoTick >= 50, "the sweep reaches frames with two left ticks: " + twoTick);
});

// ---- R2: finite ends whose difference overflows ----

/** The test's oracle for a share: the ends scaled down by 1e300 first (exact enough, 1e-12, and independent of the engine). */
const shareScaled = (lo, hi, v) => (v / 1e300 - lo / 1e300) / (hi / 1e300 - lo / 1e300);

test("R2: a left axis whose ends overflow a double's difference (-1e308..1e308, mirrored, uneven, chosen by the engine): finite shares, the right ticks on them", () => {
  const cases = [
    [{ lo: -1e308, hi: 1e308, ticks: [-1e308, 0, 1e308], log: false }, [0, 10]],
    [{ lo: -1e308, hi: 1e308, ticks: [-1e308, 0, 1e308], log: false }, [-10, 0]],
    [{ lo: -1.5e308, hi: 1e308, ticks: [-1e308, 0, 1e308], log: false }, [0, 10]],
    [{ lo: -Number.MAX_VALUE, hi: Number.MAX_VALUE, ticks: [-1e308, -5e307, 0, 5e307, 1e308], log: false }, [-5, 5]],
  ];
  for (const [left, domain] of cases) {
    const at = `left ${left.lo}..${left.hi} beside ${domain}`;
    assert.doesNotThrow(() => R.axes.scaleOf(left, 200, 0)(left.ticks[0]), "the single axis's scale takes these ends");
    const ax = R.axes.secondAxis("full", linear(domain), domain, left, 200);
    assert.equal(ax.ticks.length, left.ticks.length, at);
    assert.deepEqual([ax.lo, ax.hi], domain, at);
    const want = left.ticks.map((t) => domain[0] + shareScaled(left.lo, left.hi, t) * (domain[1] - domain[0]));
    ax.ticks.forEach((t, j) => assert.ok(Number.isFinite(t) && Math.abs(t - want[j]) <= 1e-9 * (domain[1] - domain[0]), `tick ${j}: ${t}, want ${want[j]}: ${at}`));
    assert.equal(new Set(ax.texts).size, ax.texts.length, at);
  }
  // The engine's own range over the same extent, as the left axis, beside a right axis it chooses too (case a).
  const own = R.axes.valueAxis("full", [-1e308, 1e308], null, 200, null, 0);
  const ax = R.axes.secondAxis("full", linear(null), [0, 10], { lo: own.lo, hi: own.hi, ticks: own.ticks, log: false }, 200);
  assert.equal(ax.ticks.length, own.ticks.length);
  assert.ok(ax.ticks.every(Number.isFinite) && holds(ax, 0, 10), `${ax.lo}..${ax.hi}: ${ax.ticks}`);
});

test("R2: a right domain whose ends overflow a double's difference (-1e308..1e308, -MAX..MAX, uneven): its ends exactly, finite values at every left position", () => {
  const cases = [
    [[-1e308, 1e308], L3, [-1e308, 0, 1e308]],
    [[-1e308, 1e308], L5, [-1e308, -5e307, 0, 5e307, 1e308]],
    [[-Number.MAX_VALUE, Number.MAX_VALUE], L3, [-Number.MAX_VALUE, 0, Number.MAX_VALUE]],
    [[-1.5e308, 1e308], L3, [-1.5e308, -2.5e307, 1e308]],
    [[-1e308, 1e308], LOG125, null],
  ];
  for (const [domain, left, want] of cases) {
    const at = `right ${domain} beside ${left.ticks}`;
    assert.doesNotThrow(() => R.axes.valueAxis("full", domain, domain, 200, null, 0), "the single axis takes these ends: " + at);
    const ax = R.axes.secondAxis("full", linear(domain), domain, left, 200);
    assert.deepEqual([ax.lo, ax.hi], domain, at);
    assert.equal(ax.ticks.length, left.ticks.length, at);
    assert.ok(ax.ticks.every(Number.isFinite), `${ax.ticks}: ${at}`);
    assert.equal(ax.ticks[0], domain[0], "the first tick is the domain's low end, exactly: " + at);
    assert.equal(ax.ticks[ax.ticks.length - 1], domain[1], "the last tick is the domain's high end, exactly: " + at);
    if (want) ax.ticks.forEach((t, j) => assert.ok(Math.abs(t - want[j]) <= 1e-12 * Math.abs(domain[1]), `tick ${j}: ${t}, want ${want[j]}: ${at}`));
    assert.equal(ax.texts.length, ax.ticks.length, at);
    assert.equal(new Set(ax.texts).size, ax.texts.length, at);
  }
});

test("R2: a right extent -1e308..1e308 with no domain (the range the engine chooses itself): held, finite, both ends", () => {
  for (const left of [L2, L3, L5]) {
    const ax = R.axes.secondAxis("full", linear(null), [-1e308, 1e308], left, 200);
    assert.ok(ax.lo <= -1e308 && ax.hi >= 1e308 && ax.ticks.every(Number.isFinite), `${ax.lo}..${ax.hi}: ${ax.ticks}`);
    assert.equal(ax.ticks.length, left.ticks.length);
  }
});

test("R2 rendered: large left and large right domains, mirrored, and a large right extent: no throw, the right ticks on the left positions, the ends", () => {
  const cases = [
    ["left -1e308..1e308", twoAxes({ x: ["A", "B", "C"], y: [-5e307, 2e307, 8e307], yDomain: [-1e308, 1e308], y2: [1, 4, 10], y2Domain: [0, 10] }), [0, 10]],
    ["left mirrored", twoAxes({ x: ["A", "B", "C"], y: [5e307, -2e307, -8e307], yDomain: [-1e308, 1e308], y2: [-1, -4, -10], y2Domain: [-10, 0] }), [-10, 0]],
    ["right -1e308..1e308", twoAxes({ x: ["A", "B", "C"], y: [0.2, 0.5, 0.9], yDomain: [0, 1], y2: [-9e307, 3e307, 9e307], y2Domain: [-1e308, 1e308] }), [-1e308, 1e308]],
    ["right extent chosen", twoAxes({ x: ["A", "B", "C"], y: [0.2, 0.5, 0.9], yDomain: [0, 1], y2: [-1e308, 0, 1e308] }), null],
  ];
  let pictures = 0;
  for (const [name, fx, ends] of cases) {
    for (const [w, h] of [[960, 540], [400, 300], [240, 140], [80, 24]]) {
      const at = `${name} at ${w}x${h}`;
      let res;
      assert.doesNotThrow(() => (res = draw(R, fx, w, h)), at);
      const { r, hand } = res;
      assert.equal(r.state, "rendered", at);
      pictures++;
      const v = hand.value, v2 = hand.value2;
      assert.equal(v2.ticks.length, v.ticks.length, at);
      assert.ok([v2.lo, v2.hi, ...v2.ticks].every(Number.isFinite), at);
      if (ends) assert.deepEqual([v2.lo, v2.hi], ends, at);
      else assert.ok(v2.lo <= -1e308 && v2.hi >= 1e308, at);
      v2.ticks.forEach((t, j) => assert.ok(Math.abs(v2.scale(t) - v.scale(v.ticks[j])) <= 0.011, `right tick ${t} at ${v2.scale(t)}, left at ${v.scale(v.ticks[j])}: ${at}`));
      for (const d of dots(r)) assert.ok(Number.isFinite(d.cy) && Number.isFinite(d.cx), `dot ${d.cx},${d.cy}: ${at}`);
    }
  }
  console.log(`R2 rendered: ${pictures} pictures`);
});

// ---- R3: significant digits, total over finite values ----

const significantEngine = () => formsEngine((s) => plant(s, "11-axes.js", "secondAxis: secondAxis };", "secondAxis: secondAxis, significant: significant };"));

/** toPrecision's d digits; where they read past the largest double, the same digits one unit lower (the test's own way). */
function oracle(v, d) {
  const text = v.toPrecision(d), r = Number(text);
  if (Number.isFinite(r)) return r;
  const [mant, exp] = text.replace("-", "").split("e"), digits = mant.replace(".", ""), lower = (BigInt(digits) - 1n).toString();
  const shift = Number(exp) - (digits.length - 1);
  return (v < 0 ? -1 : 1) * Number(lower + "e" + shift);
}

test("R3: rounding to 3..17 significant digits gives a finite value for every finite one, toPrecision's (to 15 digits exactly, 16 and 17 within 1e-15), from the smallest subnormal to the largest double", () => {
  const sig = significantEngine().axes.significant;
  let n = 0, exact = 0;
  for (let k = -324; k <= 308; k++) {
    for (const m of ["1", "1.23456789", "4.94", "9.999", "5.5", "2.2250738585072014", "1.7976931348623157"]) {
      for (const sign of [1, -1]) {
        const v = sign * Number(m + "e" + k);
        if (!(v !== 0 && Number.isFinite(v))) continue;
        for (let d = 3; d <= 17; d++) {
          const r = sig(v, d), o = oracle(v, d), at = `${v} at ${d} digits: ${r}, toPrecision ${o}`;
          assert.ok(Number.isFinite(r), at);
          if (d <= 15) assert.equal(r, o, at);
          else assert.ok(Math.abs(r - o) <= 1e-15 * Math.abs(o), at);
          if (r === o) exact++;
          n++;
        }
      }
    }
  }
  assert.equal(sig(0, 3), 0);
  // The two borders of the raised branch and the largest double, by hand.
  assert.equal(sig(1e-322, 3), 1e-322);
  assert.equal(sig(1.23e-306, 3), 1.23e-306);
  assert.equal(sig(Number.MAX_VALUE, 3), 1.79e308);
  assert.equal(sig(-Number.MAX_VALUE, 3), -1.79e308);
  console.log(`R3 rounding: ${n} values rounded, ${exact} equal to toPrecision's, all finite`);
  assert.ok(n > 60000, "the sweep is not vacuous: " + n);
});

/** Three significant digits, one more for all while two neighbours share one (toPrecision, the test's oracle). */
function digits(vals) {
  for (let d = 3; d <= 17; d++) {
    const r = vals.map((v) => oracle(v, d));
    if (r.every((v, i) => i === 0 || v !== r[i - 1])) return r;
  }
  return vals.map((v) => oracle(v, 17));
}

test("R3: right domains of subnormal and near-subnormal numbers beside log and linear left axes: finite ticks, the oracle's texts, distinct where the values differ", () => {
  const domains = [[1e-322, 1e-321], [-1e-321, -1e-322], [-1e-321, 1e-321], [5e-324, 1e-322], [1e-310, 2e-310], [1e-308, 1e-307], [2.2e-308, 2.3e-308], [1e-306, 9e-306], [1e-305, 1e-304]];
  let checked = 0;
  for (const domain of domains) {
    assert.doesNotThrow(() => R.text.ticks(domain, null), "the text formatter takes these ends: " + domain);
    assert.doesNotThrow(() => R.axes.valueAxis("full", domain, domain, 200, null, 0), "the single axis takes these ends: " + domain);
    for (const left of [LOG125, L3, L5]) {
      const at = `right ${domain} beside ${left.ticks}`;
      let ax;
      assert.doesNotThrow(() => (ax = R.axes.secondAxis("full", linear(domain), domain, left, 200)), at);
      assert.ok(ax.ticks.every(Number.isFinite), `${ax.ticks}: ${at}`);
      assert.deepEqual([...ax.texts], [...R.text.ticks(digits(ax.ticks), null)], at);
      ax.ticks.forEach((t, j) => j === 0 || t === ax.ticks[j - 1] || assert.notEqual(ax.texts[j], ax.texts[j - 1], `two values ${ax.ticks[j - 1]}, ${t} share a text: ${at}`));
      checked++;
    }
  }
  console.log(`R3 helper: ${checked} subnormal axes`);
});

test("R3 rendered: a log left axis 1..10 beside a right domain 1e-322..1e-321, every height at three widths: no throw, finite ticks, distinct texts", () => {
  const fx = twoAxes({ x: ["Jan", "Feb", "Mar", "Apr"], y: [1, 2, 5, 10], yLog: true, y2: [2e-322, 4e-322, 6e-322, 9e-322], y2Domain: [1e-322, 1e-321] });
  let pictures = 0;
  for (const w of [960, 400, 240]) {
    for (const h of HEIGHTS) {
      const at = `${w}x${h}`;
      let res;
      assert.doesNotThrow(() => (res = draw(R, fx, w, h)), at);
      if (res.r.state !== "rendered") continue;
      pictures++;
      const v2 = res.hand.value2;
      assert.deepEqual([v2.lo, v2.hi], [1e-322, 1e-321], at);
      assert.ok(v2.ticks.every(Number.isFinite), at);
      const texts = rightLabels(res.r, res.hand.plot).map((t) => t.text);
      assert.equal(new Set(texts).size, texts.length, at);
    }
  }
  assert.ok(pictures >= 10, "pictures " + pictures);
});

// ---- the Node half of the identity on R1 to R3 ----

test("R1-R3 identity, Node's half: second-axis-probes.js evaluated in Node is second-axis-probes.json byte for byte (the sandbox's half: JsRenderedTestTypesTest)", () => {
  const script = readFileSync(path.join(HERE, "second-axis-probes.js"), "utf8");
  const expected = readFileSync(path.join(HERE, "second-axis-probes.json"), "utf8");
  // The engine without the test types: the sandbox evaluates the product's program alone.
  const text = new Function("R", script + "\nreturn secondAxisProbes(R);")(engineWith([]));
  assert.equal(text + "\n", expected);
  const probes = JSON.parse(text);
  assert.ok(probes.length >= 18, "probes " + probes.length);
  for (const p of probes) assert.ok([p.lo, p.hi, ...p.ticks].every(Number.isFinite), p.name);
  for (const r of ["r1", "r2", "r3"]) assert.ok(probes.some((p) => p.name.startsWith(r + " ")), r);
  console.log(`R1-R3 identity probes: ${probes.length}, ${text.length} characters`);
});

test("Security: a right domain with an end that is not finite is refused as before, with its present message", () => {
  // The messages round 2's engine gave (e5a70c837); the save refuses these domains first (chart.scale.domain_invalid).
  const L3 = { lo: 0, hi: 1, ticks: [0, 0.5, 1], log: false };
  const NO_DIGITS = "ticks: no fraction digits write every tick as its own value: ";
  const cases = [
    [[NaN, 1], "NaN, 1"],
    [[0, Infinity], "NaN, Infinity, Infinity"],
    [[-Infinity, 0], "NaN, NaN, NaN"],
  ];
  for (const [domain, values] of cases) {
    assert.throws(() => R.axes.secondAxis("full", linear(domain), [0, 1], L3, 200), { message: NO_DIGITS + values }, String(domain));
  }
});
