// Lane 1059 (#1059, the label half of #1060; record §4.7 and its note of 2026-11-22 "Axis numbers at the ends of the
// number range"): an axis writes a very large or very small tick in the e form (2.5e18, 7.5e−10), from 10^15 up and
// below 10^-5; everything between is written as it was. Every label is read back by THIS file's decimal reader (exact,
// BigInt), never by the engine's readLabel: the oracle is not the thing under test. Each check is a function over an
// engine's text module (or a whole engine) that returns its failures, so the gates G1 to G4 and G6 read the same check
// on a planted copy of the engine and see it go red naming the thing; the check's own test is the green direction.
// A.1 the ruling's texts, A.2 a tick written alone, A.3 every decade and the ends of the number range, A.4 the value
// form unchanged, A.7 whole charts at the ends of the range; A.5 and A.6 live in the tests that pinned the old texts
// and in the JVM identity tests. G5 is radial.test.mjs's.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { engine, engineWith, inputOf, typeInputOf, FIXTURES, TYPE_FIXTURES } from "./engine.mjs";
import { violations } from "./geometry.mjs";

const MINUS = "−";
const MAX = Number.MAX_VALUE;
const FILE = "01-text.js";
const R = engine();
const T = R.text;

// ---------------------------------------------------------------- the test's own reader

/** A label or a double as an exact {n, e} = n × 10^e, or null when it is no label: `,` groups, U+2212, K M B T, %, the e form. */
function exact(text) {
  let s = String(text).replace(/,/g, "").replace(/−/g, "-"), e = 0;
  if (s.endsWith("%")) {
    s = s.slice(0, -1);
    e -= 2;
  }
  const unit = "KMBT".indexOf(s.slice(-1));
  if (unit >= 0) {
    s = s.slice(0, -1);
    e += 3 * (unit + 1);
  }
  const m = /^(-?)([0-9]+)(?:\.([0-9]+))?(?:e([+-]?[0-9]+))?$/.exec(s);
  if (!m) return null;
  if (m[4] !== undefined) {
    if (unit >= 0) return null;
    e += Number(m[4]);
  }
  const frac = m[3] || "";
  return { n: BigInt(m[1] + m[2] + frac), e: e - frac.length };
}
const minus = (a, b) => {
  const e = Math.min(a.e, b.e);
  return { n: a.n * 10n ** BigInt(a.e - e) - b.n * 10n ** BigInt(b.e - e), e };
};
const abs = (a) => (a.n < 0n ? { n: -a.n, e: a.e } : a);
/** The label states the tick within a millionth of an exact distance {n, e}. */
const within = (label, tick, dist) => {
  const x = exact(label);
  return x !== null && minus({ n: dist.n, e: dist.e - 6 }, abs(minus(x, exact(tick)))).n >= 0n;
};
/** The label states the tick within a millionth of `step` (a double, read by its shortest digits). */
const states = (label, tick, step) => within(label, tick, abs(exact(step)));
/** The exact value is a finite double's size (at most the largest double). */
const finite = (x) => x !== null && minus(exact(MAX), abs(x)).n >= 0n;
/** The e form as D1 writes it: one digit, a point and digits only when needed, e, the power with U+2212 (and %). */
const E_FORM = /^−?[1-9](\.[0-9]*[1-9])?e(−?[1-9][0-9]*|0)%?$/;
const isE = (l) => E_FORM.test(l);
const isZero = (l) => l === "0" || l === "0%";

/** A planted copy of the engine: `from` occurs exactly once in 01-text.js and becomes `to`. */
const plant = (from, to) => (s) => {
  assert.equal(s[FILE].split(from).length, 2, "the plant's anchor occurs once: " + from);
  s[FILE] = s[FILE].replace(from, to);
};
const combine = (...edits) => (s) => edits.forEach((e) => e(s));
const START_POINTS_OFF = plant("var SCI_FROM = 15, SCI_BELOW = -5;", "var SCI_FROM = 400, SCI_BELOW = -400;");
const HYPHEN = plant("(power < 0 ? MINUS + -power : String(power))", '(power < 0 ? "-" + -power : String(power))');
const LONE_HELD_TO_ONE = plant("if (step === 0) step = least > 0 && least < 1 ? least : 1;", "if (step === 0) step = 1;");
/** G4: only the ticks that reach a start point take the e form; the others are written as the short form writes them. */
const PER_TICK = plant(
  "var sl = scientific(values[j], sd, pct);",
  "var xj = decimal(values[j]), pj = xj.p - 1 + (pct ? 2 : 0);\n          var sl = xj.d !== \"0\" && (pj >= SCI_FROM || pj < SCI_BELOW) ? scientific(values[j], sd, pct) : pct ? percent(values[j], 20) : fixed(values[j], 0, 20);",
);
const FAR_AS_IT_COMES = plant("if (gap === Infinity) gap = Number.MAX_VALUE;", "");

// ---------------------------------------------------------------- A.1 the ruling, by its texts

/** [values, format, the labels the ruling writes]: the owner's two rows, the start points from both sides, the middle. */
const RULING = [
  [[0, 2.5e17, 5e17, 7.5e17, 1e18], null, ["0", "2.5e17", "5e17", "7.5e17", "1e18"]],
  [[0, 2.5e-10, 5e-10, 7.5e-10, 1e-9], null, ["0", `2.5e${MINUS}10`, `5e${MINUS}10`, `7.5e${MINUS}10`, `1e${MINUS}9`]],
  [[-1e18, -5e17, 0], null, [MINUS + "1e18", MINUS + "5e17", "0"]],
  [[-1e-9, -5e-10, 0], null, [`${MINUS}1e${MINUS}9`, `${MINUS}5e${MINUS}10`, "0"]],
  [[0, 5e99, 1e100], null, ["0", "5e99", "1e100"]],
  [[0, 2.5e-9, 5e-9], "percent", ["0%", `2.5e${MINUS}7%`, `5e${MINUS}7%`]],
  [[0, 5e12, 1e13], "percent", ["0%", "5e14%", "1e15%"]],
  [[0, 5e17, 1e18], "compact", ["0", "5e17", "1e18"]],
  [[0, 5e17, 1e18], "integer", ["0", "5e17", "1e18"]],
  // The start points from both sides: 800T and 0.00001 are not e forms; 1e15 and 2.5e−6 are, and switch the whole axis.
  [[0, 2e14, 4e14, 6e14, 8e14], null, ["0", "200T", "400T", "600T", "800T"]],
  [[0, 2.5e14, 5e14, 7.5e14, 1e15], null, ["0", "2.5e14", "5e14", "7.5e14", "1e15"]],
  [[0, 0.00001, 0.00002, 0.00003], null, ["0", "0.00001", "0.00002", "0.00003"]],
  [[0, 0.0000025, 0.000005, 0.0000075, 0.00001], null, ["0", `2.5e${MINUS}6`, `5e${MINUS}6`, `7.5e${MINUS}6`, `1e${MINUS}5`]],
  [[0, 9e-6], null, ["0", `9e${MINUS}6`]],
  [[0, 9e14, 999e12], null, ["0", "900T", "999T"]],
  // The middle, unchanged but for 1.25K (the short form with two digits, lane 1089).
  [[0, 0.0001, 0.0002], null, ["0", "0.0001", "0.0002"]],
  [[0, 250000, 500000, 750000, 1000000], null, ["0", "250K", "500K", "750K", "1M"]],
  [[0, 1250, 2500], null, ["0", "1.25K", "2.5K"]],
  [[0, 0.25, 0.5], "percent", ["0%", "25%", "50%"]],
];

function rulingFailures(TX) {
  const out = [];
  for (const [values, format, want] of RULING) {
    let got;
    try {
      got = [...TX.ticks(values, format)];
    } catch (e) {
      got = ["THROWS " + e.message];
    }
    if (JSON.stringify(got) !== JSON.stringify(want)) out.push(`${values.join(", ")} (${format}): ${got.join(" | ")}, the ruling writes ${want.join(" | ")}`);
  }
  return out;
}

test("A.1 the ruling's texts: from 10^15 up and below 10^-5 the e form with the true minus, zero is 0, the middle as it was", () => {
  assert.deepEqual(rulingFailures(T), []);
  // The fewest digits within a millionth of the step: 9.999999999999999e29 against 0 is 1e30; 9.6e15 keeps its digit.
  assert.deepEqual([...T.ticks([0, 9.999999999999999e29])], ["0", "1e30"]);
  assert.deepEqual([...T.ticks([0, 9.6e15, 1e16])], ["0", "9.6e15", "1e16"]);
});

test("A.1 the engine's readLabel reads the e form and refuses an exponent with a unit letter (D5)", () => {
  assert.equal(T.readLabel("2.5e18"), 2.5e18);
  assert.equal(T.readLabel(`1e${MINUS}9`), 1e-9);
  assert.equal(T.readLabel(`${MINUS}7.5e${MINUS}10`), -7.5e-10);
  assert.equal(T.readLabel(`5e${MINUS}7%`), 5e-9);
  assert.equal(T.readLabel("1.797693e308"), 1.797693e308);
  assert.ok(Number.isNaN(T.readLabel("1e5K")), "1e5K is no label");
  assert.ok(Number.isNaN(T.readLabel("1e")), "1e is no label");
  assert.ok(Number.isNaN(T.readLabel("1e+5")), "a + is never written");
  // The labels of the middle read as before.
  assert.equal(T.readLabel("2.5K"), 2500);
  assert.equal(T.readLabel("0.0025%"), 0.000025);
});

test("A.1 the e form's refusal is the existing message, byte for byte (D8)", () => {
  assert.throws(() => T.ticks([1e20, NaN]), (e) => e.message === "ticks: no fraction digits write every tick as its own value: 100000000000000000000, NaN");
});

// ---------------------------------------------------------------- A.2 a tick written alone

/** A tick written alone (the log axis writes each tick alone): [value, format, label]. */
const LONE = [
  [1e-9, null, `1e${MINUS}9`],
  [1e-6, null, `1e${MINUS}6`],
  [0.00001, null, "0.00001"],
  [0.001, null, "0.001"],
  [1, null, "1"],
  [5000, null, "5K"],
  [1e15, null, "1e15"],
  [1e20, null, "1e20"],
  [0.0000015, null, `1.5e${MINUS}6`],
  [1.5e-9, null, `1.5e${MINUS}9`],
  // Between the start points, held to its own size (D4, D6: before this lane 0.00001, 0.012346, 0.0123%).
  [0.0000105, null, "0.0000105"],
  [0.01234567, null, "0.01234567"],
  [0.000123456, "percent", "0.0123456%"],
  [0.5000004, null, "0.5"],
  [5e-8, "percent", `5e${MINUS}6%`],
  // Alone at 1 or more: as it was.
  [1.0000004, null, "1"],
  [1234.567, null, "1,234.567"],
  [12.5, "percent", "1,250%"],
];

function loneFailures(TX) {
  const out = [];
  for (const [v, f, want] of LONE) {
    const got = [...TX.ticks([v], f)][0];
    if (got !== want) out.push(`${v} alone (${f}): ${got}, wants ${want}${states(got, v, v) ? "" : "; the test's reader refuses it"}`);
  }
  return out;
}

/** The longest lone label per format over every 1, 2 and 5 times a power of ten a double holds (measured). */
const LONGEST_LONE = { null: 7, percent: 20, compact: 7, integer: 7 };

test("A.2 a tick written alone keeps its value: 1e−9 is not 0, 1.5e−6 is not 2e−6, and between the start points its own digits", () => {
  assert.deepEqual(loneFailures(T), []);
  // A log axis with an authored domain writes each tick alone: its two ticks no longer share one text.
  assert.deepEqual([...R.axes.logAxis("full", [0.0000105, 0.0000106], [0.0000105, 0.0000106], 200, null).texts], ["0.0000105", "0.0000106"]);
  // Two or more ticks between the start points: as they were.
  assert.deepEqual([...T.ticks([0.0000105, 0.0000106])], ["0.0000105", "0.0000106"]);
  assert.deepEqual([...T.ticks([0, 0.012345, 0.02469])], ["0", "0.012345", "0.02469"]);
});

test("A.2 every 1, 2 and 5 times a power of ten a double holds, alone, four formats: each label states its tick (floor 7,000)", () => {
  let n = 0;
  const longest = {}, bad = [];
  for (let d = -323; d <= 308; d++) {
    for (const lead of [1, 2, 5]) {
      const v = Number(lead + "e" + d);
      if (!(v > 0 && v < Infinity)) continue;
      for (const f of [null, "percent", "compact", "integer"]) {
        if (f === "percent" && d > 305) continue;
        const label = T.ticks([v], f)[0];
        if (!states(label, v, v)) bad.push(`${label} does not state ${v} (${f})`);
        longest[f] = Math.max(longest[f] || 0, label.length);
        n++;
      }
    }
  }
  console.log("A.2 lone ticks", n, "longest label per format", JSON.stringify(longest));
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " lone labels");
  assert.ok(n >= 7000, "lone ticks " + n);
  assert.deepEqual(longest, LONGEST_LONE);
});

// ---------------------------------------------------------------- A.3 every decade, and the ends of the number range

/**
 * The longest label per format, as measured (a change in either direction is read). A tick that needs two digits after
 * the point in the short form was written in full (−1,250,000,000,000, 18 characters) until lane 1089 gave the short
 * form two digits (−1.25T); the longest is now an e form of the smallest decades (−1.25e−319, 10). A percent has no
 * K M B T, so below 10^15% it is written in full (−100,000,000,000,000%, 21). Before lane 1059 the same sweep's
 * longest label had hundreds of characters.
 */
const LONGEST_NICE = { null: 10, percent: 21, compact: 10, integer: 10 };

/** Nice ticks 0, s, 2s, … built by this test: each the double its decimal text reads as (never the engine's scales). */
function ladder(lead, d, count, sign) {
  const [m, shift] = lead === 2.5 ? [25, 1] : [lead, 0], out = [];
  for (let i = 0; i <= count; i++) out.push(Number(sign * i * m + "e" + (d - shift)) + 0);
  return sign < 0 ? out.reverse() : out;
}

/** A.3's sweep over a text module and a range of decades: counts, the longest label per format, its first 60 failures. */
function niceSweep(TX, from = -320, to = 305) {
  const s = { axes: 0, sci: 0, labels: 0, longest: {}, where: {}, failures: [] };
  const fail = (x) => s.failures.length < 60 && s.failures.push(x);
  for (let d = from; d <= to; d++) {
    for (const lead of [1, 2, 2.5, 5]) {
      for (const count of [2, 4, 5, 10]) {
        for (const sign of [1, -1]) {
          const ticks = ladder(lead, d, count, sign), step = Math.abs(ticks[1] - ticks[0]);
          if (!ticks.every(Number.isFinite) || !(step > 0) || new Set(ticks).size !== ticks.length) continue;
          for (const f of [null, "percent", "compact", "integer"]) {
            if (f === "percent" && d > 300) continue;
            let labels;
            try {
              labels = [...TX.ticks(ticks, f)];
            } catch (e) {
              fail(e.message);
              continue;
            }
            s.axes++;
            if (new Set(labels).size !== labels.length) fail("two ticks share a label: " + labels.join(" | "));
            if (labels.some(isE)) {
              s.sci++;
              if (!labels.every((l) => isE(l) || isZero(l))) fail("an axis in the e form has a label in another form: " + labels.join(" | "));
            }
            labels.forEach((l, i) => {
              if (!states(l, ticks[i], step)) fail(`${l} does not state ${ticks[i]} (${f}, step ${step})`);
              s.labels++;
              if (l.length > (s.longest[f] || 0)) {
                s.longest[f] = l.length;
                s.where[f] = labels.join(" | ");
              }
            });
          }
        }
      }
    }
  }
  return s;
}

test("A.3 nice ticks of every decade from 10^-320 to 10^305: each label states its tick, one form per axis, no label is long (floors)", () => {
  const s = niceSweep(T);
  console.log("A.3 axes", s.axes, "in the e form", s.sci, "labels", s.labels, "longest per format", JSON.stringify(s.longest));
  for (const f of Object.keys(s.where)) console.log("    longest", f, s.where[f].slice(0, 160));
  assert.deepEqual(s.failures.slice(0, 10), [], s.failures.length + " failures");
  assert.ok(s.axes >= 70000 && s.sci >= 60000 && s.labels >= 400000, `floors: ${s.axes} ${s.sci} ${s.labels}`);
  assert.deepEqual(s.longest, LONGEST_NICE);
});

test("A.3 ticks that are any doubles (the two ends of a range with no nice ticks): every label states its tick, at most 25 characters", () => {
  let n = 0, longest = "";
  const vals = [1.2345678901234567e30, 9.87654321e-250, MAX, Number.MIN_VALUE, 2.2250738585072014e-308, 1.7e18 + 1024, 123456789012345680000, 3.0000000000000004e-7, 1.0000000000000002e15];
  for (const a of vals) {
    for (const b of vals) {
      for (const sign of [1, -1]) {
        if (!(a < b)) continue;
        const ticks = sign > 0 ? [a, b] : [-b, -a], labels = [...T.ticks(ticks)];
        labels.forEach((l, i) => {
          assert.ok(states(l, ticks[i], Math.abs(ticks[1] - ticks[0])), `${l} states ${ticks[i]}`);
          n++;
          if (l.length > longest.length) longest = l;
        });
        assert.notEqual(labels[0], labels[1]);
      }
    }
  }
  console.log("A.3 any doubles: labels", n, "longest", longest.length, longest);
  assert.ok(n >= 100 && longest.length <= 25, n + " " + longest);
});

/** Axes whose neighbours can stand more than the largest double apart (D4). */
const FAR = [[-MAX, MAX], [-MAX, 1e308], [-1e308, MAX], [-1.5e308, 1.5e308], [-MAX, 9e307], [-MAX, 1], [-MAX, 0, MAX], [-1e308, 1e308], [-MAX, -1e308, 1e308, MAX]];

/** A.3's ends of the number range over a text module: each label reads finite and states its tick within a millionth of the EXACT distance. */
function farFailures(TX) {
  const out = [];
  let labelsRead = 0, beyond = 0;
  for (const ticks of FAR) {
    for (const f of [null, "percent", "compact", "integer"]) {
      let least = null;
      for (let i = 1; i < ticks.length; i++) {
        const dist = minus(exact(ticks[i]), exact(ticks[i - 1]));
        if (least === null || minus(least, dist).n > 0n) least = dist;
        if (ticks[i] - ticks[i - 1] === Infinity) beyond++;
      }
      const labels = [...TX.ticks(ticks, f)];
      if (new Set(labels).size !== labels.length) out.push("two ticks share a label: " + labels.join(" | "));
      labels.forEach((l, i) => {
        labelsRead++;
        // A percent label is a hundred times its value: the value it states (read with its %) is what must be finite.
        if (!finite(exact(l))) out.push(`${l} reads back as no finite number (${f}; ${labels.join(" | ")})`);
        if (!within(l, ticks[i], least)) out.push(`${l} does not state ${ticks[i]} within a millionth of the exact distance (${f}; ${labels.join(" | ")})`);
        if (l.length > 25) out.push(l + " is longer than 25 characters");
      });
    }
  }
  return { out, labelsRead, beyond };
}

test("A.3 ticks more than the largest double apart: every label reads back finite and states its tick within a millionth of the exact distance", () => {
  const { out, labelsRead, beyond } = farFailures(T);
  console.log("A.3 far ticks: labels", labelsRead, "pairs of neighbours whose subtraction is not finite", beyond, "e.g.", [...T.ticks([-MAX, MAX])].join(" | "), ";", [...T.ticks([-MAX, MAX], "percent")].join(" | "));
  assert.deepEqual(out, []);
  assert.ok(labelsRead >= 80 && beyond >= 20, labelsRead + " " + beyond);
  assert.deepEqual([...T.ticks([-MAX, MAX])], [MINUS + "1.797693e308", "1.797693e308"]);
  assert.deepEqual([...T.ticks([-MAX, 1e308])], [MINUS + "1.797693e308", "1e308"]);
});

test("A.3 the same ends through the second axis (case c, three significant digits): finite, within a hundredth, at most 10 characters", () => {
  const second = (lo, hi) => R.axes.secondAxis("full", { domain: [lo, hi], format: null, log: false }, [lo, hi], { lo: 0, hi: 1, ticks: [0, 1], log: false }, 200);
  for (const [lo, hi] of [[-MAX, MAX], [-MAX, 1e308], [-1e308, MAX], [-1e308, 1e308]]) {
    const ax = second(lo, hi), texts = [...ax.texts], at = [...ax.ticks];
    assert.equal(texts.length, 2);
    assert.notEqual(texts[0], texts[1]);
    texts.forEach((l, i) => {
      const x = exact(l), v = exact(at[i]);
      assert.ok(finite(x), `second axis ${lo} to ${hi}: ${l} reads back finite`);
      assert.ok(minus({ n: abs(v).n, e: v.e - 2 }, abs(minus(x, v))).n >= 0n, `second axis: ${l} is within a hundredth of ${at[i]}`);
      assert.ok(l.length <= 10, l);
    });
  }
  assert.deepEqual([...second(-MAX, MAX).texts], [MINUS + "1.79e308", "1.79e308"]);
});

// ---------------------------------------------------------------- A.4 the value form is not the axis's text

test("A.4 a value read elsewhere (tooltip, value label) follows the axis's start points: T.value writes the e form at the ends (lane 1088's D1, D3)", () => {
  assert.equal(T.value(1e18, null), "1e18");
  assert.equal(T.value(1234.5, null), "1,234.5");
  assert.equal(T.value(1e-9, null), "1e" + MINUS + "9");
  assert.equal(T.value(2.5e18, "compact"), "2.5e18");
});

// ---------------------------------------------------------------- A.7 whole charts at the ends of the range

const FRAMES = [[240, 140], [300, 180], [360, 240], [480, 360], [640, 360], [960, 540], [1600, 480]];
const load = (p) => JSON.parse(readFileSync(p, "utf8"));
const BAR = load(FIXTURES + "/bar/parity-bar-vertical.json");
const LINE = load(FIXTURES + "/line/one-series.json");
const TWO = load(TYPE_FIXTURES + "/test_two_axes/case-c-right-domain.json");
const fill = (n, vals) => Array.from({ length: n }, (_, i) => vals[i % vals.length]);
/** The bar fixture (it sums its rows by region) with its values replaced in the test, at a format or none. */
function barWith(vals, format) {
  const f = structuredClone(BAR), y = f.document.config.encoding.y;
  f.columns[y.field] = fill(f.columns[y.field].length, vals);
  if (format) y.format = format;
  else delete y.format;
  return f;
}
function lineWith(vals, log) {
  const f = structuredClone(LINE), y = f.document.config.encoding.y;
  f.columns[y.field] = fill(f.columns[y.field].length, vals);
  delete y.format;
  if (log) y.scale = { type: "log" };
  return f;
}
function twoWith(vals, domain, log) {
  const f = structuredClone(TWO), e2 = f.document.config.encoding.y2;
  f.columns.y2 = fill(f.columns.y2.length, vals);
  if (domain) e2.domain = domain;
  else delete e2.domain;
  if (log) e2.log = true;
  return f;
}
const single = (fx) => ({ fx, second: false });
const right = (fx) => ({ fx, second: true });

/** [name, case, whether the case asserts the count of ticks and the e form (false: lane 1063's ranges, invariants and length only)]. */
const CHARTS = [
  ["bar to 1e30", single(barWith([1e30, 5e29, 2.5e29])), true],
  ["bar to 1e30, percent", single(barWith([1e30, 5e29, 2.5e29], "percent")), true],
  ["bar to 1e30, compact", single(barWith([1e30, 5e29, 2.5e29], "compact")), true],
  ["bar to 1e100", single(barWith([1e100, 5e99])), true],
  ["bar -1e30 to 1e30", single(barWith([-1e30, 1e30, 5e29])), true],
  ["bar to 1e-100", single(barWith([1e-100, 5e-101])), true],
  ["bar to 1e-300", single(barWith([1e-300, 5e-301])), true],
  ["line 1e307 to 1e308", single(lineWith([1e307, 1e308, 5e307])), true],
  ["log line 1, 1e150, 1e300", single(lineWith([1, 1e150, 1e300], true)), true],
  ["log line 1e-300, 1, 1e300", single(lineWith([1e-300, 1, 1e300], true)), true],
  ["log line 1e-322, 2e-322, 1e-321 (subnormal)", single(lineWith([1e-322, 2e-322, 1e-321], true)), true],
  ["log line 1e305, 1e306, 1e308", single(lineWith([1e305, 1e306, 1e308], true)), true],
  ["second, extent 0 to 1e30", right(twoWith([0, 1e30, 5e29])), true],
  ["second, extent -1e30 to 1e30", right(twoWith([-1e30, 1e30])), true],
  ["second, extent -1e308 to 1e308", right(twoWith([-1e308, 1e308])), true],
  ["second, extent 0 to 1e-300", right(twoWith([0, 1e-300, 5e-301])), true],
  ["second, extent 1e-322 to 1e-321", right(twoWith([1e-322, 1e-321])), true],
  ["second, domain 0 to 1e30", right(twoWith([0, 1e30], [0, 1e30])), true],
  ["second, domain -1e308 to 1e308", right(twoWith([-1e308, 1e308], [-1e308, 1e308])), true],
  ["second, domain -MAX to MAX", right(twoWith([-MAX, MAX], [-MAX, MAX])), true],
  ["second, domain 1e-322 to 1e-321", right(twoWith([1e-322, 1e-321], [1e-322, 1e-321])), true],
  ["second, log extent 1, 1e150, 1e300", right(twoWith([1, 1e150, 1e300], null, true)), true],
  ["second, log domain 1e308 to MAX", right(twoWith([1e308, MAX], [1e308, MAX], true)), true],
  ["second, log domain 1e-322 to 1e-321", right(twoWith([1e-322, 1e-321], [1e-322, 1e-321], true)), true],
  // Which ticks the axis chooses here is wrong at the base (the chooser collapses the range and writes 0): lane 1063's.
  ["1063's part: bar to 1e-321", single(barWith([1e-321, 5e-322])), false],
  ["1063's part: bar -1e-321 to 1e-321", single(barWith([-1e-321, 1e-321])), false],
  ["1063's part: line 1e-322 to 1e-321", single(lineWith([1e-322, 1e-321])), false],
];

/**
 * A.7 over an edit of the engine (none: the engine under test): every case at the seven frames, rendered whole. Per
 * picture: the state, the frame invariants (violations: I1, I2, I4, I5), the ticks of the axis under test (role tick;
 * anchor end = the left value axis, start = the right one): at least 2, at least one in the e form, none longer than 10.
 */
function wholeCharts(edit) {
  const one = engine(edit), two = engineWith(["test_two_axes"], edit);
  const rows = [], failures = [];
  let pictures = 0, invariantPictures = 0, longest = "";
  for (const [name, c, full] of CHARTS) {
    const row = { name, pictures: [] };
    for (const [w, h] of FRAMES) {
      pictures++;
      const r = c.second ? two.render(typeInputOf(c.fx, w, h)) : one.render(inputOf(c.fx, w, h));
      const vs = violations(r.primitives, w, h).violations;
      const ticks = r.primitives.filter((p) => p.k === "text" && p.role === "tick" && p.anchor === (c.second ? "start" : "end")).map((p) => p.text);
      const at = `${name} at ${w}x${h}`;
      if (r.state !== "rendered") failures.push(`${at}: state ${r.state}`);
      if (vs.length > 0) invariantPictures++;
      for (const v of vs) failures.push(`${v.inv} ${at}: ${v.what.slice(0, 120)}`);
      for (const t of ticks) {
        if (t.length > longest.length) longest = t;
        if (t.length > 10) failures.push(`${at}: the tick ${t.slice(0, 40)}… has ${t.length} characters`);
      }
      if (full && ticks.length < 2) failures.push(`${at}: ${ticks.length} ticks on the axis under test`);
      if (full && !ticks.some(isE)) failures.push(`${at}: no tick in the e form: ${ticks.join(" | ")}`);
      row.pictures.push({ w, h, state: r.state, invariants: vs.map((v) => v.inv), ticks });
    }
    rows.push(row);
  }
  return { rows, failures, pictures, invariantPictures, longest };
}

test("A.7 whole charts at the ends of the range, single and second axis, seven frames: the texts stay inside and do not meet (floors 24 cases, 168 pictures)", () => {
  const s = wholeCharts();
  for (const row of s.rows) console.log("A.7", row.name.padEnd(46), row.pictures.map((p) => `${p.w}x${p.h} ${p.state} [${p.invariants.join(",")}] ${p.ticks.join(" | ")}`).join(" ;; "));
  console.log("A.7 cases", s.rows.length, "pictures", s.pictures, "with a broken invariant", s.invariantPictures, "longest tick", s.longest);
  assert.deepEqual(s.failures.slice(0, 12), [], s.failures.length + " failures");
  const full = CHARTS.filter((c) => c[2]).length;
  assert.ok(full >= 24 && full * FRAMES.length >= 168, "cases " + full);
  assert.equal(s.pictures, CHARTS.length * FRAMES.length);
});

test("A.7 a bar chart of 1e308 per row sums beyond the number range: nothing is drawn, as at the base (no case)", () => {
  const r = R.render(inputOf(barWith([1e308]), 960, 540));
  assert.equal(r.state, "no-data");
  assert.ok(JSON.stringify(r).includes("chart.rule.non-finite"), "the existing warning");
});

// ---------------------------------------------------------------- the gates: each plant turns its check red

const textOf = (edit) => engine(edit).text;
const red = (list, re, what) => assert.ok(list.some((x) => re.test(x)), what + ": no failure matching " + re + " in " + JSON.stringify(list.slice(0, 4)));

test("G1 the start points moved out of reach: A.1's rows, A.3's longest label and A.7's whole charts go red", () => {
  const off = textOf(START_POINTS_OFF);
  const a1 = rulingFailures(off);
  red(a1, /^0, 250000000000000000, .*: 0 \| 250,000T \| 500,000T \| 750,000T \| 1,000,000T, the ruling writes 0 \| 2\.5e17/, "A.1 the owner's row");
  red(a1, /^0, 2\.5e-10, .*: 0 \| 0\.00000000025 \|/, "A.1 the small row");
  // The decades at the top of the range (a sweep of all of them on the planted copy writes labels of hundreds of characters slowly).
  const sweep = niceSweep(off, 290, 305);
  assert.ok(Math.max(...Object.values(sweep.longest)) > 300, "A.3's longest label is back to hundreds of characters: " + JSON.stringify(sweep.longest));
  const charts = wholeCharts(START_POINTS_OFF);
  red(charts.failures, /^I2 /, "A.7 a text outside the frame");
  red(charts.failures, /the tick .* has [0-9]{3} characters/, "A.7 a tick of hundreds of characters");
  console.log("G1 planted: A.1", a1.length, "rows red; A.3 longest", JSON.stringify(sweep.longest), "; A.7", charts.invariantPictures, "of", charts.pictures, "pictures with a broken invariant, longest tick", charts.longest.length);
});

test("G2 a hyphen in the power: A.1 goes red on 1e−9 (the label reads 1e-9)", () => {
  const a1 = rulingFailures(textOf(HYPHEN));
  red(a1, /: 0 \| 2\.5e-10 \| 5e-10 \| 7\.5e-10 \| 1e-9, the ruling writes/, "A.1 1e−9");
});

test("G3 a lone tick held to the tolerance of a step of 1: A.2 goes red on 1.5e−6 (2e−6) and on the lone 0.0000105 (0.00001)", () => {
  const a2 = loneFailures(textOf(LONE_HELD_TO_ONE));
  red(a2, /^0\.0000015 alone \(null\): 2e−6, wants 1\.5e−6; the test's reader refuses it$/, "A.2 1.5e−6");
  red(a2, /^0\.0000105 alone \(null\): 0\.00001, wants 0\.0000105; the test's reader refuses it$/, "A.2 0.0000105");
});

test("G4 only the ticks that reach a start point take the e form: A.3 (one form per axis) and A.1 (0 | 2.5e14 | … | 1e15) go red", () => {
  const per = textOf(PER_TICK);
  red(rulingFailures(per), /^0, 250000000000000, .*: 0 \| 250,000,000,000,000 \| 500,000,000,000,000 \| 750,000,000,000,000 \| 1e15, the ruling writes 0 \| 2\.5e14/, "A.1 the start point's row");
  red(niceSweep(per).failures, /^an axis in the e form has a label in another form: /, "A.3 one form per axis");
});

test("G6 the distance of ticks more than the largest double apart taken as it comes (not finite): A.3 goes red on −MAX | 1e308", () => {
  const far = textOf(FAR_AS_IT_COMES);
  red(farFailures(far).out, /^−1\.79769e308 does not state -1\.7976931348623157e\+308 within a millionth of the exact distance \(null; /, "A.3 −MAX | 1e308");
});

test("G0 every plant's anchor occurs once and the engine without a plant is the engine under test (the green direction is each check's own test)", () => {
  for (const edit of [START_POINTS_OFF, HYPHEN, LONE_HELD_TO_ONE, PER_TICK, FAR_AS_IT_COMES]) textOf(edit);
  assert.deepEqual(rulingFailures(textOf(combine())), []);
});

// ================================================================ lane 1089: the short form with two digits (1.25B)
// Lane 1089 (#1089; record §4.7 and its note of 2026-11-22 "Numbers outside an axis, the short form with two digits, a
// stack beyond the number range"): the short form (K, M, B, T) of a tick may carry two digits after the point, tried
// before plain grouped digits at each count of digits; a percent axis and the e form do not change. The base's rule is
// a COPY of the base's loop planted into an engine (BASE_RULE), never a parameter of the code under test.

/** [values, format, the labels the ruling writes]: the owner's row, every unit, both signs, and the rows that keep their bytes. */
const SHORT_ROWS = [
  [[0, 250000000, 500000000, 750000000, 1000000000, 1250000000], null, ["0", "250M", "500M", "750M", "1B", "1.25B"]],
  [[0, 1250000, 2500000, 3750000, 5000000], null, ["0", "1.25M", "2.5M", "3.75M", "5M"]],
  [[-1250000000, -1000000000, 0], null, [MINUS + "1.25B", MINUS + "1B", "0"]],
  [[0, 1250, 2500], null, ["0", "1.25K", "2.5K"]],
  [[-1.25e12, 0], null, [MINUS + "1.25T", "0"]],
  // Unchanged: one digit suffices; plain digits where the short form would lose a digit; never three digits (D1).
  [[0, 1500, 3000], null, ["0", "1.5K", "3K"]],
  [[0, 1250.5, 2501], null, ["0", "1,250.5", "2,501"]],
  [[975000000, 1000000000, 1025000000], null, ["975,000,000", "1,000,000,000", "1,025,000,000"]],
  [[1125000000], null, ["1,125,000,000"]],
  [[0, 0.125, 0.25], "percent", ["0%", "12.5%", "25%"]],
  [[0, 5e17, 1e18], null, ["0", "5e17", "1e18"]],
  [[-2790.96, 2311.28], null, [MINUS + "2,790.96", "2,311.28"]],
  // D2's row by the two ends bar/corner-production-balance_by_partner--real_window_balance itself hands the tick writer
  // (12 of its 183 frames): plain digits at two digits, before any third digit. The rounded pair above is no gate.
  [[-2790.9621305348655, 2311.2831141308657], "decimal(2)", [MINUS + "2,790.96", "2,311.28"]],
];

function shortFailures(TX) {
  const out = [];
  for (const [values, format, want] of SHORT_ROWS) {
    let got;
    try {
      got = [...TX.ticks(values, format)];
    } catch (e) {
      got = ["THROWS " + e.message];
    }
    if (JSON.stringify(got) !== JSON.stringify(want)) out.push(`${values.join(", ")} (${format}): ${got.join(" | ")}, the ruling writes ${want.join(" | ")}`);
  }
  return out;
}

test("1089 A.1 the ruling's texts: 0 | 250M | … | 1B | 1.25B, every unit with two digits, never three, D2's row by its inputs", () => {
  assert.deepEqual(shortFailures(T), []);
});

/** The base's loop (before lane 1089), byte for byte, planted over the lane's loop: the oracle of A.2's "never longer". */
const LOOP_FROM = "    var last = Math.max(most, SHORT_DIGITS);\n";
const LOOP_TO = "    throw noDigits(values);\n  }\n";
const BASE_LOOP = `    var last = Math.max(most, 1);
    for (var digits = 0; digits <= last + 1; digits++) {
      var plainOnly = digits > last, d = plainOnly ? most : digits;
      var out = [];
      var exact = true;
      for (var k = 0; k < values.length; k++) {
        var v = values[k], a = v < 0 ? -v : v, label;
        if (pct) label = percent(v, d);
        else label = plainOnly || a < 1000 || d > 1 ? fixed(v, 0, d) : compact(v, d);
        out.push(label);
        var back = readLabel(label) - v;
        if (!(back <= step * 1e-6 && back >= -step * 1e-6)) exact = false;
      }
      if (exact) return out;
    }
`;
const BASE_RULE = (s) => {
  const src = s[FILE], from = src.indexOf(LOOP_FROM);
  assert.equal(src.split(LOOP_FROM).length, 2, "the base plant's start anchor occurs once");
  const to = src.indexOf(LOOP_TO, from);
  assert.ok(from >= 0 && to > from, "the base plant's end anchor follows its start");
  s[FILE] = src.slice(0, from) + BASE_LOOP + src.slice(to);
};
const BASE_T = engine(BASE_RULE).text;

/** Nice ticks i × lead × 10^d built by the test from decimal text (lead 1.25 is 125e(d−2)): each the double its text reads as. */
function shortLadder(lead, d, count, sign) {
  const digits = String(lead).replace(".", ""), shift = digits.length - 1, out = [];
  for (let i = 0; i <= count; i++) out.push(Number(sign * i * Number(digits) + "e" + (d - shift)) + 0);
  return sign < 0 ? out.reverse() : out;
}

/** A.2's sweep: each label exactly its tick (this file's reader), never longer in total than the base, percent byte for byte. */
function shortSweep(TX, BX) {
  const s = { axes: 0, changed: 0, percentAxes: 0, failures: [] };
  const fail = (x) => s.failures.length < 60 && s.failures.push(x);
  // Every decade the short form covers (10^3 to below 10^15) and, beyond the brief's floor, the decades below it down
  // to 10^-6, where a percent axis needs three and more digits (G2's throw) and plain digits meet the short form.
  for (let d = -6; d <= 14; d++) {
    for (const lead of [1, 1.25, 1.5, 2, 2.5, 5, 7.5]) {
      for (const count of [2, 4, 5, 10]) {
        for (const sign of [1, -1]) {
          const ticks = shortLadder(lead, d, count, sign);
          for (const f of [null, "integer", "compact", "percent"]) {
            let got, base;
            try {
              got = [...TX.ticks(ticks, f)];
            } catch (e) {
              fail(`${ticks.join(", ")} (${f}): THROWS ${e.message}`);
              continue;
            }
            base = [...BX.ticks(ticks, f)];
            s.axes++;
            got.forEach((l, i) => {
              const x = exact(l);
              if (x === null || minus(x, exact(ticks[i])).n !== 0n) fail(`${l} is not ${ticks[i]} (${f}): ${got.join(" | ")}`);
            });
            if (f === "percent") {
              s.percentAxes++;
              if (got.join("|") !== base.join("|")) fail(`a percent axis changed (${ticks.join(", ")}): ${got.join(" | ")}, the base writes ${base.join(" | ")}`);
            } else if (got.join("|") !== base.join("|")) {
              s.changed++;
              if (got.join("").length > base.join("").length) fail(`longer than the base (${f}): ${got.join(" | ")}, the base writes ${base.join(" | ")}`);
            }
          }
        }
      }
    }
  }
  return s;
}

test("1089 A.2 nice ticks of every decade, four formats: each label IS its tick, never longer than the base, percent unchanged (floors 2,000 axes, 50 changed)", () => {
  assert.deepEqual([...BASE_T.ticks([0, 1250, 2500])], ["0", "1,250", "2,500"], "the base's copy is the base");
  const s = shortSweep(T, BASE_T);
  console.log("1089 A.2 axes", s.axes, "changed", s.changed, "percent axes", s.percentAxes, "failures", s.failures.length);
  assert.deepEqual(s.failures.slice(0, 10), [], s.failures.length + " failures");
  assert.ok(s.axes >= 2000 && s.changed >= 50, `floors: ${s.axes} axes, ${s.changed} changed`);
});

// ---------------------------------------------------------------- lane 1089's gates

/** G1 (D2): plain digits only from three digits on. G2 (D3): the short form's skip without its percent condition. */
const PLAIN_FROM_THREE = plant("if (!shortForm && (pct || (!plainOnly && d < 2))) continue;", "if (!shortForm && (pct || (!plainOnly && d < 3))) continue;");
const SHORT_FOR_PERCENT = plant("if (shortForm && !pct && (plainOnly || d > SHORT_DIGITS)) continue;", "if (shortForm && (plainOnly || d > SHORT_DIGITS)) continue;");

test("1089 G1 plain digits tried only from three digits on: A.1 goes red on D2's row of the unrounded ends, and only there", () => {
  const a1 = shortFailures(textOf(PLAIN_FROM_THREE));
  console.log("1089 G1 planted: A.1", a1.length, "rows red:", a1.join(" / "));
  assert.equal(a1.length, 1, JSON.stringify(a1));
  red(a1, /^-2790\.9621305348655, 2311\.2831141308657 \(decimal\(2\)\): −2,790\.962 \| 2,311\.283, the ruling writes −2,790\.96 \| 2,311\.28$/, "A.1 D2's row");
});

test("1089 G2 the short form's skip without its percent condition: A.2 goes red (a small percent axis throws)", () => {
  const a2 = shortSweep(textOf(SHORT_FOR_PERCENT), BASE_T).failures;
  console.log("1089 G2 planted: A.2", a2.length, "failures, first:", a2[0]);
  red(a2, /\(percent\): THROWS ticks: no fraction digits write every tick as its own value: /, "A.2 a small percent axis");
});
