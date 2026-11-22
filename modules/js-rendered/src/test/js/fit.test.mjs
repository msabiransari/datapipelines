// #985 round 2, the lander's refusal 985-r1. R1: the advertised compact and full frames fit (I1, I2, I5 at the levels
// the engine returns, the lowest value tick's number kept), at the level boundaries and their neighbours, at the default
// text sizes and at larger valid ones with their scaled boundaries. R2: tick text never loses the tick's value. The
// oracle of R2 reads the labels with THIS file's own decimal reader, never R.text (the formatter under test).
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";
import { violations } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();
// Bar's fixtures by name: a name is unique within its type's directory only (line/ and area/ have their own `hostile`).
const fx = (name) => FIXTURES.find((f) => f.wire === "bar" && f.name === name).fixture;
const MINUS = "−";

/** The value axis's tick labels of a vertical bar, bottom to top: [{text, y}] (anchor end, left of the plot). */
function valueTicks(r) {
  const plot = r.index.plot;
  return r.primitives
    .filter((p) => p.k === "text" && p.role === "tick" && p.anchor === "end" && !p.rotate && p.x < plot.x)
    .map((p) => ({ text: p.shown ?? p.text, y: p.y }))
    .sort((a, b) => b.y - a.y);
}

// ---------------------------------------------------------------- R1

for (const name of ["look-long-labels", "parity-bar-stacked"]) {
  test(`R1: ${name} at 200 x 140 is compact and meets I1, I2, I5; the lowest tick's number is drawn`, () => {
    const r = R.render(inputOf(fx(name), 200, 140));
    assert.equal(r.level, "compact");
    assert.deepEqual([...violations(r.primitives, 200, 140).violations].map((v) => v.inv + " " + v.what), []);
    const ticks = valueTicks(r);
    assert.equal(ticks[0].text, "0", "the lowest value tick keeps its number (the corner rule)");
    assert.ok(ticks.length >= 2, "the plot keeps the room of two value tick labels: " + JSON.stringify(ticks));
  });
}

test("R1: the crowded eight-series chart at 360 x 240 is full and meets I1, I2, I5; the legend folds into +n", () => {
  const r = R.render(inputOf(fx("fit-crowded-eight-series"), 360, 240));
  assert.equal(r.level, "full");
  assert.deepEqual([...violations(r.primitives, 360, 240).violations].map((v) => v.inv + " " + v.what), []);
  const ticks = valueTicks(r);
  assert.equal(ticks[0].text, "0");
  assert.ok(ticks.length >= 2, JSON.stringify(ticks));
  const more = r.primitives.filter((p) => p.k === "text" && p.role === "legend" && /^\+[0-9]+$/.test(p.text));
  assert.equal(more.length, 1, "the rows that do not fit are folded into one +n");
  const entries = r.primitives.filter((p) => p.k === "group" && p.role === "legend").length;
  assert.equal(entries + Number(more[0].text.slice(1)), 8, "every series is an entry or counted in +n");
  assert.ok(r.primitives.some((p) => p.k === "text" && p.role === "axis" && p.text === "Category"), "the band title is drawn, inside the frame");
});

test("R1: a folded entry's series is drawn even when its id is on the hidden list (look contract §4.2)", () => {
  const f = fx("fit-crowded-eight-series"), field = f.document.config.encoding.color.field;
  const last = f.columns[field][7];
  const r = R.render(inputOf(f, 360, 240, { hidden: ["string:" + last] }));
  const s = r.summary.series.find((x) => x.title === last);
  assert.equal(s.hidden, false, "no entry drawn for it, so the list does not hide it");
  assert.ok(r.primitives.some((p) => p.role === "mark" && p.series === s.id));
});

/** The text-size themes of the boundary sweep: the defaults, and larger valid sizes (each raises the level numbers). */
const SIZES = [
  {},
  { "--chart-text": 16 },
  { "--chart-text": 24 },
  { "--chart-title": 28 },
  { "--chart-text-small": 20 },
  { "--chart-text": 20, "--chart-text-small": 20, "--chart-title": 28 },
  { "--chart-text": 32, "--chart-text-small": 32, "--chart-title": 32 },
];
const NEIGHBOURS = [0, 1, 20, 40, 80];

/** The level numbers scaled the way 10-chrome.js `level` scales them: by the largest ratio of a text size to its default. */
function scale(tokens) {
  const T = R.theme.TOKENS;
  return Math.max(1, ...["--chart-text", "--chart-text-small", "--chart-title"].map((t) => (tokens[t] ?? T[t][1]) / T[t][1]));
}

test("R1: every chart fixture at the compact and full boundaries and their neighbours, each text size, meets I1, I2, I5", () => {
  const look = R.tables.look, bad = [], levels = new Set();
  let pictures = 0, texts = 0;
  for (const tokens of SIZES) {
    const k = scale(tokens), ws = new Set(), hs = new Set();
    for (const [bw, bh] of [look("levelCompact"), look("levelFull")]) {
      for (const d of NEIGHBOURS) {
        ws.add(Math.ceil(bw * k) + d);
        hs.add(Math.ceil(bh * k) + d);
      }
    }
    for (const f of FIXTURES) {
      for (const w of ws) {
        for (const h of hs) {
          const r = R.render(inputOf(f.fixture, w, h, { theme: { base: "light", tokens } }));
          const v = violations(r.primitives, w, h);
          pictures++;
          texts += v.texts;
          levels.add(r.level);
          for (const x of v.violations) bad.push(`${JSON.stringify(tokens)} ${f.name} ${w}x${h} ${r.level} ${x.inv}: ${x.what}`);
        }
      }
    }
  }
  assert.ok(pictures >= SIZES.length * 43 * 100 && texts > 100000, `pictures ${pictures}, texts ${texts}`);
  assert.deepEqual([...levels].sort(), ["compact", "full"], "the boundaries meet both text levels");
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " violations");
});

test("R1: the level boundary itself, each text size: the returned level is the advertised one", () => {
  const look = R.tables.look;
  for (const tokens of SIZES) {
    const k = scale(tokens), [cw, ch] = look("levelCompact"), [fw, fh] = look("levelFull");
    const at = (w, h) => R.render(inputOf(fx("look-eight-series"), w, h, { theme: { base: "light", tokens } })).level;
    assert.equal(at(Math.ceil(cw * k), Math.ceil(ch * k)), "compact", JSON.stringify(tokens));
    assert.equal(at(Math.ceil(fw * k), Math.ceil(fh * k)), "full", JSON.stringify(tokens));
  }
});

// ---------------------------------------------------------------- R2

/** This test's own reader: a label's exact value as [BigInt numerator, power of ten], or null when it is no number. It
 * reads the e form an axis writes from 10^15 up and below 10^-5 (#1059: 2.5e18, 1e−12), never with a unit letter. */
function exact(label) {
  let t = label.replace(/,/g, "").replaceAll(MINUS, "-"), shift = 0;
  if (t.endsWith("%")) {
    t = t.slice(0, -1);
    shift = -2;
  } else {
    const units = { K: 3, M: 6, B: 9, T: 12 };
    if (units[t.slice(-1)] !== undefined) {
      shift = units[t.slice(-1)];
      t = t.slice(0, -1);
    }
  }
  const m = /^(-?)([0-9]+)(?:\.([0-9]+))?(?:e(-?[0-9]+))?$/.exec(t);
  if (!m || (m[4] !== undefined && shift > 0)) return null;
  const frac = m[3] || "";
  return { n: BigInt(m[1] + m[2] + frac), e: shift + (m[4] === undefined ? 0 : Number(m[4])) - frac.length };
}

/** a − b of two exact values, as an exact value. */
function minus(a, b) {
  const e = Math.min(a.e, b.e);
  return { n: a.n * 10n ** BigInt(a.e - e) - b.n * 10n ** BigInt(b.e - e), e };
}

const same = (a, b) => minus(a, b).n === 0n;

/** Labels of nice ticks: each a number, all distinct, evenly spaced (the step of the first two), from the lowest up. */
function assertNiceLabels(labels, where) {
  const xs = labels.map(exact);
  assert.ok(xs.every((x) => x !== null), where + ": every tick label is a number: " + labels.join(" | "));
  assert.ok(xs.length >= 2, where + ": " + labels.join(" | "));
  const step = minus(xs[1], xs[0]);
  assert.ok(step.n > 0n, where + ": the labels rise: " + labels.join(" | "));
  for (let i = 2; i < xs.length; i++) assert.ok(same(minus(xs[i], xs[i - 1]), step), where + ": evenly spaced: " + labels.join(" | "));
  return { xs, step };
}

test("R2: small-value ticks keep their values (the refusal's case, 0.00001 and 0.0000025 steps, a percent axis)", () => {
  const t = (xs, f) => [...R.text.ticks(xs, f)];
  assert.deepEqual(t([0, 0.0001, 0.0002]), ["0", "0.0001", "0.0002"]);
  assert.deepEqual(t([0, 0.00001, 0.00002, 0.00003]), ["0", "0.00001", "0.00002", "0.00003"]);
  // Below 10^-5 the axis writes the e form (#1059, owner 2026-11-21).
  assert.deepEqual(t([0, 0.0000025, 0.000005, 0.0000075, 0.00001]), ["0", "2.5e" + MINUS + "6", "5e" + MINUS + "6", "7.5e" + MINUS + "6", "1e" + MINUS + "5"]);
  assert.deepEqual(t([-0.0002, -0.0001, 0]), [MINUS + "0.0002", MINUS + "0.0001", "0"], "the true minus sign stays (§4.7)");
  assert.deepEqual(t([0, 0.0001, 0.0002], "percent"), ["0%", "0.01%", "0.02%"]);
  assert.deepEqual(t([0, 0.000025, 0.00005], "percent"), ["0%", "0.0025%", "0.005%"]);
  // The fewest digits still win where they suffice, and the compact form carries up to two fraction digits (§4.7, #1089).
  assert.deepEqual(t([0, 0.25, 0.5]), ["0", "0.25", "0.5"]);
  assert.deepEqual(t([0, 2500, 5000]), ["0", "2.5K", "5K"]);
  assert.deepEqual(t([0, 1250, 2500]), ["0", "1.25K", "2.5K"]);
});

test("R2: nice ticks of every decade from 1e-12 to 1e15, plain and percent, read back as their own values", () => {
  let checked = 0;
  for (let d = -12; d <= 15; d++) {
    for (const lead of [1, 2, 2.5, 5]) {
      const step = Number(lead + "e" + d);
      for (const intervals of [2, 5, 10]) {
        const n = R.scales.nice(0, step * intervals, intervals);
        for (const format of [null, "percent"]) {
          const labels = [...R.text.ticks(n.ticks, format)];
          const { xs } = assertNiceLabels(labels, `step ${step} ${format}`);
          // Each label states its tick: the tick's own shortest digits, read by this test, within a millionth of a step.
          n.ticks.forEach((tick, i) => {
            const want = exact(BigIntFree(tick));
            const diff = minus(xs[i], want);
            const tol = exact(BigIntFree(step * 1e-6));
            const abs = diff.n < 0n ? { n: -diff.n, e: diff.e } : diff;
            assert.ok(minus(tol, abs).n >= 0n, `${labels[i]} states ${tick}`);
            checked++;
          });
        }
      }
    }
  }
  assert.ok(checked > 3000, "ticks checked: " + checked);
});

/** A number's decimal text without an exponent (the test's own expansion of String(v)'s digits). */
function BigIntFree(v) {
  const s = String(v), m = /^(-?)([0-9])(?:\.([0-9]+))?e([+-][0-9]+)$/.exec(s);
  if (!m) return s;
  const digits = m[2] + (m[3] || ""), p = 1 + Number(m[4]);
  if (p <= 0) return m[1] + "0." + "0".repeat(-p) + digits;
  if (p >= digits.length) return m[1] + digits + "0".repeat(p - digits.length);
  return m[1] + digits.slice(0, p) + "." + digits.slice(p);
}

test("R2: a tick search that cannot keep a value refuses instead of returning the last attempt", () => {
  assert.throws(() => R.text.ticks([0, NaN]), /no fraction digits write every tick/);
});

for (const [name, w, h] of [["ticks-small-decimal", 960, 540], ["ticks-small-decimal", 300, 180], ["ticks-small-percent", 960, 540], ["ticks-small-percent", 480, 360]]) {
  test(`R2: ${name} at ${w} x ${h}: the value axis states distinct, evenly spaced values, linear in position, covering the data`, () => {
    const f = fx(name), r = R.render(inputOf(f, w, h));
    assert.equal(r.state, "rendered");
    const ticks = valueTicks(r);
    const { xs, step } = assertNiceLabels(ticks.map((t) => t.text), name);
    assert.equal(xs[0].n, 0n, "the axis starts at 0");
    // Position oracle: equal value steps are equal pixel steps (coordinates are rounded to 0.01 px).
    const px = ticks[1].y - ticks[0].y;
    for (let i = 2; i < ticks.length; i++) assert.ok(Math.abs(ticks[i].y - ticks[i - 1].y - px) < 0.03, "linear in position: " + JSON.stringify(ticks));
    // The highest label is at least the largest value delivered (read from the fixture, not from the engine); a
    // percent label is read as its fraction, so 0.0035% is 0.000035.
    const most = Math.max(...f.columns.v);
    assert.ok(minus(xs[xs.length - 1], exact(BigIntFree(most))).n >= 0n, "the axis covers " + most + ": " + ticks[ticks.length - 1].text);
    assert.ok(step.n > 0n);
  });
}

test("R2: the small-value bar's value labels state the delivered values at the document's format", () => {
  const r = R.render(inputOf(fx("ticks-small-decimal"), 960, 540));
  const labels = r.primitives.filter((p) => p.k === "text" && p.role === "guide").map((p) => p.text);
  assert.deepEqual([...labels].sort(), ["0.000100", "0.000200"]);
});
