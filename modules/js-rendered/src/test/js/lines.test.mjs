// The line family's shared code and templates (brief 989 A.1, A.3; record §4.4 G4, §4.5 rows line and area, §4.7,
// §4.9 Thinning; look contract §2, §4.2 L5, §4.5, §10 LA7 and LA10). A logarithm here is the TEST's (Math.log10): the
// engine's own is checked against it and against decimal text, never the other way.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";

const R = engine();
const FIXTURES = chartFixtures();
const LINES = FIXTURES.filter((f) => f.wire === "line" || f.wire === "area");
const fx = (wire, name) => FIXTURES.find((f) => f.wire === wire && f.name === name).fixture;
const ARITY = { M: 2, L: 2, A: 3, C: 6, Z: 0 };
/** Deep equality across the engine's context (its arrays and objects have that context's prototypes). */
const same = (actual, expected, msg) => assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, msg);
const DAY = 86400;

/** A path's commands: [{c, args}]. */
function commands(d) {
  const out = [];
  for (let i = 0; i < d.length; ) {
    const c = d[i], n = ARITY[c];
    out.push({ c, args: d.slice(i + 1, i + 1 + n) });
    i += 1 + n;
  }
  return out;
}

/** Each drawn series' line: the stroked path of its mark group (an area's upper boundary is that line too). */
function seriesLines(prims) {
  const out = new Map();
  let series = null;
  for (const p of prims) {
    if (p.k === "group" && p.role === "mark") series = p.series;
    else if (p.k === "path" && p.role === "mark" && p.stroke !== null && series !== null) out.set(series, p);
    else if (p.k === "group") series = null;
  }
  return out;
}

/** Each drawn series' faded fill (an area's), by series. */
function seriesFills(prims) {
  const out = new Map();
  let series = null;
  for (const p of prims) {
    if (p.k === "group" && p.role === "mark") series = p.series;
    else if (p.k === "path" && p.role === "mark" && p.fill !== null && p.fill !== undefined && series !== null) out.set(series, p);
    else if (p.k === "group") series = null;
  }
  return out;
}

/** The data points a line path stands on: M and each C or L end, a step's two Ls counting one point. */
function vertices(path, curve) {
  const out = [];
  for (const { c, args } of commands(path.d)) {
    if (c === "M") out.push({ x: args[0], y: args[1], run: true });
    else if (c === "C") out.push({ x: args[4], y: args[5] });
    else if (c === "L") out.push({ x: args[0], y: args[1] });
  }
  if (curve !== "step") return out;
  // A step holds the value to the next point's x, then moves: of each pair of L the second is the point.
  const kept = [];
  for (let i = 0; i < out.length; i++) {
    if (out[i].run) kept.push(out[i]);
    else {
      kept.push(out[i + 1]);
      i++;
    }
  }
  return kept;
}

/** A fresh engine whose cap table holds other values (its own copy; the tables are read at render time). */
function engineWithCaps(caps) {
  const E = engine();
  for (const [name, value] of Object.entries(caps)) {
    assert.ok(Object.prototype.hasOwnProperty.call(E.tables.CAPS, name), "cap " + name);
    E.tables.CAPS[name].limit = value;
  }
  return E;
}

// ---------------------------------------------------------------- the logarithm (D4, G4)

test("log10 is exact on every power of ten the table holds and within 1e-12 of Math.log10 at the decade edges", () => {
  const S = R.scales;
  assert.equal(S.log10(0.001), -3);
  assert.equal(S.log10(1), 0);
  assert.equal(S.log10(1e15), 15);
  let powers = 0;
  for (let k = -300; k <= 300; k++) {
    assert.equal(S.log10(S.tenths(1, k)), k, "10^" + k);
    powers++;
  }
  assert.equal(powers, 601);
  const nines = [0.000999999999, 0.999999999999, 999.999999999, 9999999999.99];
  for (const v of nines) {
    const l = S.log10(v);
    assert.ok(Math.abs(l - Math.log10(v)) < 1e-12, v + ": " + l);
    assert.ok(l < Math.round(Math.log10(v)), v + " stays below its decade: " + l);
  }
  // Within half an ulp of a decade both logarithms round to the decade itself.
  for (const v of [999999999999999.9, 99999999999999.9]) assert.equal(S.log10(v), Math.log10(v), String(v));
  {
  }
  let swept = 0;
  for (let v = 1e-6; v < 1e16; v *= 1.37) {
    assert.ok(Math.abs(S.log10(v) - Math.log10(v)) < 1e-12, String(v));
    swept++;
  }
  assert.ok(swept > 100, "values swept: " + swept);
});

test("log ticks are 1, 2, 5 × 10^k as decimal text, then the powers, then every s-th power, from G4's lower bound", () => {
  const S = R.scales;
  const text = (n) => n.ticks.map(String);
  // 0.001 to 999.999…: the lower bound is the largest power of ten at or below the smallest value.
  same(text(S.logNice(0.001, 999.999, 20, null)), ["0.001", "0.002", "0.005", "0.01", "0.02", "0.05", "0.1", "0.2", "0.5", "1", "2", "5", "10", "20", "50", "100", "200", "500", "1000"]);
  same(text(S.logNice(0.001, 999.999, 8, null)), ["0.001", "0.01", "0.1", "1", "10", "100", "1000"]);
  same(text(S.logNice(1, 1e15, 6, null)), ["1", "1000", "1000000", "1000000000", "1000000000000", "1000000000000000"]);
  assert.equal(S.logNice(0.0015, 3, 20, null).lo, 0.001);
  // A domain: its own ends, the ticks inside it.
  const d = S.logNice(1, 50, 20, [2, 900]);
  same([d.lo, d.hi], [2, 900]);
  same(text(d), ["2", "5", "10", "20", "50", "100", "200", "500"]);
  // No positive value: the axis is 1 to 10.
  same([S.logNice(null, null, 5, null).lo, S.logNice(null, null, 5, null).hi], [1, 10]);
});

test("a log line draws no value at or below zero and warns chart.rule.log-nonpositive; its axis starts at G4's bound", () => {
  const r = R.render(inputOf(fx("line", "log"), 960, 540));
  assert.equal(r.state, "rendered");
  assert.ok(r.summary.warnings.some((w) => w.code === "chart.rule.log-nonpositive"), JSON.stringify(r.summary.warnings));
  assert.ok(r.index.axis !== null);
});

// ---------------------------------------------------------------- time (D5)

test("time ticks per granularity across a year change and a leap day: each on its unit, ascending, labelled once", () => {
  const T = R.time;
  const at = (s) => T.parse(s).at;
  const cases = [
    ["datetime", "2023-12-31T22:00:00Z", "2024-01-01T02:00:00Z", (t) => t % 60 === 0],
    ["date", "2024-02-20", "2024-03-05", (t) => t % DAY === 0],
    ["date", "2023-12-20", "2024-01-10", (t) => t % DAY === 0],
    ["month", "2023-10-01", "2024-04-01", (t) => T.wire(t, false).endsWith("-01")],
    ["year", "2019-01-01", "2026-06-01", (t) => T.wire(t, false).endsWith("-01-01")],
  ];
  for (const [gran, a, b, onUnit] of cases) {
    const ticks = T.ticks(at(a), at(b), 960, gran, "full");
    assert.ok(ticks.length >= 3, gran + " " + a + ": " + ticks.length + " ticks");
    for (let i = 0; i < ticks.length; i++) {
      assert.ok(onUnit(ticks[i].at), gran + ": " + T.wire(ticks[i].at, true));
      if (i > 0) assert.ok(ticks[i].at > ticks[i - 1].at);
    }
    const labels = ticks.map((t) => t.text + "|" + (t.under || ""));
    assert.equal(new Set(labels).size, labels.length, gran + ": a label twice " + labels.join(", "));
  }
  // The leap day is a day of its own; the day after the 28th of February 2023 is the 1st of March.
  const leap = T.ticks(at("2024-02-27"), at("2024-03-02"), 960, "date", "full").map((t) => T.wire(t.at, false));
  same(leap, ["2024-02-27", "2024-02-28", "2024-02-29", "2024-03-01", "2024-03-02"]);
  assert.equal(at("2023-03-01") - at("2023-02-28"), DAY);
  assert.equal(T.parse("2023-02-29"), null);
  assert.equal(at("2024-01-01") - at("2023-12-31"), DAY);
  assert.equal(T.wire(at("2024-12-31T23:59:00Z"), true), "2024-12-31T23:59:00Z");
});

test("a temporal x is a position on the line family: the descriptor has no temporal band", () => {
  const seen = [];
  for (const t of R.types()) {
    if (t.name !== "line" && t.name !== "area") continue;
    const x = t.channels.find((c) => c.key === "x").node;
    assert.equal(x.node, "oneOf");
    same(x.of, [{ shape: "band", types: ["nominal", "ordinal"] }, { shape: "position", types: ["temporal", "quantitative"] }]);
    seen.push(t.name);
  }
  assert.deepEqual(seen, ["line", "area"]);
});

// ---------------------------------------------------------------- the curve (D6, G5)

test("G5: the smooth curve never leaves the range of the two points it joins (the spike fixture and every smooth fixture)", () => {
  let segments = 0, spike = 0;
  for (const f of LINES) {
    const curve = f.fixture.document.config.mark.curve || "smooth";
    if (curve !== "smooth") continue;
    for (const [w, h] of [[960, 540], [480, 360], [320, 200]]) {
      const r = R.render(inputOf(f.fixture, w, h));
      for (const path of seriesLines(r.primitives).values()) {
        let px = null, py = null;
        for (const { c, args } of commands(path.d)) {
          if (c === "C") {
            const lo = Math.min(py, args[5]) - 1e-9, hi = Math.max(py, args[5]) + 1e-9;
            assert.ok(args[1] >= lo && args[1] <= hi && args[3] >= lo && args[3] <= hi, f.wire + "/" + f.name + " " + w + "×" + h + ": control y outside [" + py + ", " + args[5] + "]: " + args);
            assert.ok(args[0] >= px - 1e-9 && args[2] <= args[4] + 1e-9, f.name + ": control x outside the segment");
            segments++;
            if (f.name === "spike-smooth") spike++;
          }
          if (c === "M" || c === "L" || c === "C") {
            px = c === "C" ? args[4] : args[0];
            py = c === "C" ? args[5] : args[1];
          }
        }
      }
    }
  }
  assert.ok(spike > 10, "spike segments checked: " + spike);
  assert.ok(segments > 1000, "segments checked: " + segments);
});

test("the curve module: one point draws nothing, two smooth points join straight, step holds then moves", () => {
  const C = R.curve;
  const run = [{ x: 0, y: 10 }, { x: 10, y: 0 }, { x: 20, y: 5 }];
  same(C.segments([run[0]], "smooth"), []);
  same(C.segments(run.slice(0, 2), "smooth"), [{ c: "L", x: 10, y: 0 }]);
  same(C.segments(run.slice(0, 2), "step"), [{ c: "L", x: 10, y: 10 }, { c: "L", x: 10, y: 0 }]);
  same(C.segments(run, "step"), [{ c: "L", x: 10, y: 10 }, { c: "L", x: 10, y: 0 }, { c: "L", x: 20, y: 0 }, { c: "L", x: 20, y: 5 }]);
  same(C.segments(run, "linear"), [{ c: "L", x: 10, y: 0 }, { c: "L", x: 20, y: 5 }]);
  assert.throws(() => C.segments(run, "basis"), /unknown curve/);
});

for (const wire of ["line", "area"]) {
  for (const values of [[2, 4], [2, 4, null, 3, 6]]) {
    test(wire + ": two-point step runs hold then move, including across null gaps (" + values.length + " rows)", () => {
      const fixture = structuredClone(fx("line", "curve-step"));
      fixture.document.config.mark.type = wire;
      fixture.columns.x = fixture.columns.x.slice(0, values.length);
      fixture.columns.v = values;
      const result = R.render(inputOf(fixture, 960, 540));
      assert.equal(result.state, "rendered");
      assert.equal(result.summary.marks, values.filter((v) => v !== null).length);
      const paths = [...seriesLines(result.primitives).values()];
      assert.equal(paths.length, 1);
      const drawn = commands(paths[0].d), runs = values.length === 2 ? 1 : 2;
      same(drawn.map((c) => c.c), Array.from({ length: runs }, () => ["M", "L", "L"]).flat());
      for (let i = 0; i < drawn.length; i += 3) {
        const [start, held, next] = drawn.slice(i, i + 3).map((c) => c.args);
        assert.ok(held[0] > start[0], "hold until the next x");
        assert.equal(held[1], start[1], "horizontal hold");
        assert.equal(next[0], held[0], "vertical move at the next x");
        assert.notEqual(next[1], held[1], "the fixture changes value");
      }
      if (wire === "area") {
        const fills = result.primitives.filter((p) => p.k === "path" && p.role === "mark" && p.fill !== null && p.fill !== undefined);
        assert.equal(fills.length, runs, "one closed fill per non-null run");
        fills.forEach((fill, i) => same(commands(fill.d).slice(0, 3), JSON.parse(JSON.stringify(drawn.slice(i * 3, i * 3 + 3)))));
      }
    });
  }
}

// ---------------------------------------------------------------- thinning (D8, G4 of the brief's gates)

/** The same fixture drawn with every point (a trial drawing cap above its size) and with the shipped cap. */
function fullAndThinned(wire, name, w = 960, h = 540) {
  const f = fx(wire, name);
  const full = engineWithCaps({ [wire + ".points"]: 1000000 }).render(inputOf(f, w, h));
  const thin = R.render(inputOf(f, w, h));
  return { f, full, thin };
}

/** At most four kept points per pixel column inside each run (§4.9). */
function perColumn(vs) {
  const counts = new Map();
  let run = 0;
  for (const v of vs) {
    if (v.run) run++;
    const k = run + ":" + Math.floor(v.x);
    counts.set(k, (counts.get(k) || 0) + 1);
  }
  return Math.max(...counts.values());
}

test("thinning keeps a spike up and a spike down inside one pixel column (thin-extremes-one-column)", () => {
  const { full, thin } = fullAndThinned("line", "thin-extremes-one-column");
  const fl = [...seriesLines(full.primitives).values()][0], tl = [...seriesLines(thin.primitives).values()][0];
  const fv = vertices(fl, "smooth"), tv = vertices(tl, "smooth");
  assert.ok(tv.length < fv.length, "thinned: " + tv.length + " of " + fv.length);
  assert.equal(Math.min(...tv.map((v) => v.y)), Math.min(...fv.map((v) => v.y)), "the highest point kept");
  assert.equal(Math.max(...tv.map((v) => v.y)), Math.max(...fv.map((v) => v.y)), "the lowest point kept");
  assert.equal(tv[0].x, fv[0].x);
  assert.equal(tv[tv.length - 1].x, fv[fv.length - 1].x, "the last point kept");
  assert.ok(perColumn(tv) <= 4, "at most four per column: " + perColumn(tv));
  assert.equal(thin.summary.marks, full.summary.marks, "mark_count counts the data");
});

test("thinning never joins across a null inside a pixel column (thin-null-in-column)", () => {
  const { full, thin } = fullAndThinned("line", "thin-null-in-column");
  const runs = (p) => commands(p.d).filter((c) => c.c === "M").length;
  const fl = [...seriesLines(full.primitives).values()][0], tl = [...seriesLines(thin.primitives).values()][0];
  assert.ok(runs(fl) >= 2, "the data has a gap: " + runs(fl));
  assert.equal(runs(tl), runs(fl), "the gap stays");
  assert.ok(vertices(tl, "smooth").length < vertices(fl, "smooth").length);
  assert.ok(perColumn(vertices(tl, "smooth")) <= 4);
});

test("a thinned step series still steps: kept points step to the next kept point (thin-step)", () => {
  const { full, thin } = fullAndThinned("line", "thin-step");
  const tl = [...seriesLines(thin.primitives).values()][0];
  const cs = commands(tl.d);
  assert.ok(cs.length > 100);
  let x = cs[0].args[0], y = cs[0].args[1], pairs = 0;
  for (let i = 1; i + 1 < cs.length; i += 2) {
    assert.equal(cs[i].c, "L");
    assert.equal(cs[i].args[1], y, "a step holds the value first");
    assert.equal(cs[i + 1].args[0], cs[i].args[0], "then moves straight up or down");
    x = cs[i + 1].args[0];
    y = cs[i + 1].args[1];
    pairs++;
  }
  void x;
  assert.ok(pairs < vertices([...seriesLines(full.primitives).values()][0], "step").length, "thinned");
  assert.ok(perColumn(vertices(tl, "step")) <= 4);
});

test("a thinned stack keeps one set of x positions, so its boundaries still meet (thin-stacked)", () => {
  const { full, thin } = fullAndThinned("area", "thin-stacked");
  const lines = [...seriesLines(thin.primitives).values()];
  assert.equal(lines.length, 2);
  const xs = lines.map((p) => vertices(p, "smooth").map((v) => v.x).join(","));
  assert.equal(xs[0], xs[1], "the two series share their kept x positions");
  const fills = [...seriesFills(thin.primitives).values()];
  // The upper series' fill closes along the lower series' line: its lower boundary is that line, reversed.
  const lowerLine = vertices(lines[0], "smooth"), upperFill = commands(fills[1].d);
  const ends = upperFill.filter((c) => c.c === "C" || c.c === "L").map((c) => (c.c === "C" ? c.args[4] + "," + c.args[5] : c.args[0] + "," + c.args[1]));
  for (const v of lowerLine) assert.ok(ends.includes(v.x + "," + v.y), "the lower boundary meets the series below at " + v.x);
  assert.ok(lowerLine.length < vertices([...seriesLines(full.primitives).values()][0], "smooth").length, "thinned");
  assert.equal(thin.summary.marks, full.summary.marks);
});

/**
 * The test's own reading of §4.9's rule (the oracle, written apart from 15-thin.js): per run between nulls and per pixel
 * column (Math.floor of x) inside it, the first, the lowest (its first occurrence), the highest (its first occurrence)
 * and the last index. Returns the kept indices, ascending.
 */
function keptByRule(xs, ys) {
  const keep = new Set();
  let i = 0;
  while (i < xs.length) {
    if (ys[i] === null) {
      i++;
      continue;
    }
    const col = Math.floor(xs[i]), idx = [];
    while (i < xs.length && ys[i] !== null && Math.floor(xs[i]) === col) idx.push(i++);
    let low = idx[0], high = idx[0];
    for (const j of idx) {
      if (ys[j] < ys[low]) low = j;
      if (ys[j] > ys[high]) high = j;
    }
    for (const j of [idx[0], low, high, idx[idx.length - 1]]) keep.add(j);
  }
  return [...keep].sort((a, b) => a - b);
}

test("thinning keeps exactly the first, the lowest, the highest and the last point of each pixel column of each run (R.thin against the rule)", () => {
  let seed = 4099, inner = 0;
  const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  for (let trial = 0; trial < 200; trial++) {
    const n = 20 + Math.floor(rnd() * 400), xs = [], lists = [[], []];
    let x = rnd() * 3;
    for (let i = 0; i < n; i++) {
      x += rnd() * 0.6;
      xs.push(x);
      for (const ys of lists) ys.push(rnd() < 0.04 ? null : Math.round(rnd() * 50));
    }
    const kept = (ys) => [...R.thin.series(xs, ys, [])].flatMap((k, i) => (k === true ? [i] : []));
    const one = kept(lists[0]), want = keptByRule(xs, lists[0]);
    assert.deepEqual(one, want, "trial " + trial + ": one series");
    // The kept first or last point of a column that is neither its lowest nor its highest (non-vacuity of the rule's ends).
    inner += want.filter((i) => lists[0][i] !== null && lists[0].some((v, j) => j !== i && v !== null && Math.floor(xs[j]) === Math.floor(xs[i]) && v < lists[0][i]) && lists[0].some((v, j) => j !== i && v !== null && Math.floor(xs[j]) === Math.floor(xs[i]) && v > lists[0][i])).length;
    const union = [...new Set([...keptByRule(xs, lists[0]), ...keptByRule(xs, lists[1])])].sort((a, b) => a - b);
    assert.deepEqual([...R.thin.union(xs, lists)], union, "trial " + trial + ": a stack keeps the union");
  }
  assert.ok(inner >= 100, "kept ends that are neither a column's lowest nor its highest: " + inner);
});

test("A10's line half (G7): over the drawing cap and under the row cap a line is thinned with mark_count unchanged", () => {
  const f = fx("line", "thin-extremes-one-column");
  const n = f.columns.x.length;
  assert.ok(n > R.tables.cap("line.points") && n <= R.tables.cap("line.rows"), "the fixture sits between the two caps: " + n);
  const r = R.render(inputOf(f, 960, 540));
  assert.equal(r.state, "rendered");
  assert.equal(r.summary.marks, n);
  assert.equal(r.index.series[0].at.length, n, "the index holds every point");
  assert.ok(vertices([...seriesLines(r.primitives).values()][0], "smooth").length < n);
});

test("A10 (G7): one row over the line family's row cap is refused before the model is entered", () => {
  for (const wire of ["line", "area"]) {
    // The template's model throws when entered (a copy of its file); the row cap is the PRODUCT's table value
    // (brief 989 D9a (4): the shipped caps are proven on the product's table, never on a trial value).
    const E = engine((srcs) => {
      const file = "types/" + wire + ".js", anchor = "function model(doc, columns, types) {";
      assert.equal(srcs[file].split(anchor).length, 2, "the anchor occurs once");
      srcs[file] = srcs[file].replace(anchor, anchor + '\n    throw new Error("model entered");');
    });
    const limit = E.tables.cap(wire + ".rows");
    assert.equal(limit, R.tables.cap(wire + ".rows"), "the product's table");
    const f = fx(wire, "two-series"), rows = f.columns.x.length;
    assert.ok(rows < limit, "two-series has fewer rows than the trial cap: " + rows);
    const grow = (k) => Object.fromEntries(Object.entries(f.columns).map(([c, v]) => [c, Array.from({ length: k }, (_, i) => v[i % v.length])]));
    const over = E.render({ ...inputOf(f, 960, 540), columns: grow(limit + 1) });
    assert.equal(over.state, "refused", wire);
    assert.equal(over.summary.refusal.code, "chart.too_many_rows");
    assert.throws(() => E.render({ ...inputOf(f, 960, 540), columns: grow(limit) }), /model entered/, wire + ": at the cap the model runs");
  }
});

test("in full mode every series' line stands on every one of its data points (no thinning under the cap)", () => {
  let series = 0;
  for (const f of LINES) {
    if (f.name.startsWith("thin-")) continue;
    const r = R.render(inputOf(f.fixture, 960, 540));
    if (r.state !== "rendered") continue;
    const curve = f.fixture.document.config.mark.curve || "smooth";
    const lines = seriesLines(r.primitives);
    for (const s of r.index.series) {
      const p = lines.get(s.id);
      if (p === undefined) continue;
      // A point alone between two nulls draws no line (nothing to join); every other point is a vertex.
      const vs = vertices(p, curve);
      assert.ok(vs.length <= s.at.length, f.wire + "/" + f.name + " " + s.id);
      assert.ok(vs.length >= s.at.length - commands(p.d).filter((c) => c.c === "M").length - 2, f.wire + "/" + f.name + " " + s.id + ": " + vs.length + " of " + s.at.length);
      series++;
    }
  }
  assert.ok(series >= 60, "series checked: " + series);
});

// ---------------------------------------------------------------- direct names (D7, LA7)

/** The names written on the lines: legend groups holding a text and no key. */
function directNames(prims) {
  const out = [];
  for (let i = 0; i < prims.length; i++) {
    const p = prims[i];
    if (p.k === "group" && p.role === "legend" && p.n === 1 && prims[i + 1].k === "text") out.push(prims[i + 1]);
  }
  return out;
}

test("two names that would collide are pushed apart, at least a text line, inside the frame (colliding-labels)", () => {
  let cases = 0;
  for (const [w, h] of [[960, 540], [640, 400], [480, 360]]) {
    const r = R.render(inputOf(fx("line", "colliding-labels"), w, h));
    const names = directNames(r.primitives).sort((a, b) => a.y - b.y);
    assert.equal(names.length, r.summary.series.length, w + "×" + h + ": every name written");
    for (let i = 1; i < names.length; i++) assert.ok(names[i].y - names[i - 1].y >= R.tables.look("lineHeight") * names[i].size - 1e-6, "apart: " + names.map((n) => n.y));
    for (const n of names) assert.ok(n.y > 0 && n.y < h, "inside the frame");
    cases++;
  }
  assert.equal(cases, 3);
});

test("D7: a qualifying full draw writes EVERY name on the lines, at every frame and at LA3's two token ends (else STATUS: QUESTION)", () => {
  const lengths = Object.entries(R.theme.TOKENS).filter(([, t]) => t[0] === "length");
  const themes = [["default", "light"], ["low", { base: "light", tokens: Object.fromEntries(lengths.map(([n, t]) => [n, t[3]])) }], ["high", { base: "light", tokens: Object.fromEntries(lengths.map(([n, t]) => [n, t[4]])) }]];
  const draws = {};
  for (const [end, theme] of themes) {
    draws[end] = 0;
    for (const f of LINES) {
      for (const w of [320, 480, 640, 960, 1280]) for (const h of [200, 300, 400, 540, 900]) {
        const r = R.render(inputOf(f.fixture, w, h, { theme }));
        if (r.state !== "rendered" || r.level !== "full") continue;
        const qualifying = r.summary.series.length >= 2 && r.summary.series.length <= 4 && f.fixture.document.config.encoding.color?.legend === undefined && !(f.fixture.hidden || []).length;
        const named = directNames(r.primitives);
        if (!qualifying) continue;
        assert.equal(named.length, r.summary.series.length, end + " " + f.wire + "/" + f.name + " " + w + "×" + h + ": a name left out");
        draws[end]++;
      }
    }
  }
  // At the high end the text is larger, so fewer frames reach the full level (10-chrome.js level).
  const floors = { default: 100, low: 100, high: 20 };
  for (const end of Object.keys(draws)) assert.ok(draws[end] >= floors[end], end + ": qualifying draws " + draws[end]);
});

test("LA7's line row: labels: last beside names on the lines is ONE label per series, the name first", () => {
  const r = R.render(inputOf(fx("line", "labels-last"), 960, 540));
  const names = directNames(r.primitives);
  assert.equal(names.length, 2);
  for (const n of names) {
    const s = r.summary.series.find((x) => x.id === n.series);
    assert.ok(n.text.startsWith(s.title + " ") && n.text.length > s.title.length + 1, n.text);
  }
  // The legend elsewhere (one series): the value alone.
  const one = R.render(inputOf(fx("line", "labels-last-one"), 960, 540));
  const guides = one.primitives.filter((p) => p.k === "text" && p.role === "guide" && p.series !== null && p.series !== undefined);
  assert.equal(guides.length, 1);
  assert.ok(!guides[0].text.startsWith(one.summary.series[0].title + " "), guides[0].text);
});

// ---------------------------------------------------------------- LA10: the legend's place (D7, G9)

const SERIES_NAMES = ["North", "South", "East", "West", "Central", "Coast", "Hills", "Lakes"];

/** A line of n series over six nominal x values; id of series i = "string:" + its name. */
function lineOf(n, extra = {}) {
  const x = [], s = [], v = [];
  for (let k = 0; k < 6; k++) for (let i = 0; i < n; i++) {
    x.push("Q" + (k + 1));
    s.push(SERIES_NAMES[i]);
    v.push(String(10 + 7 * i + ((k * (i + 3)) % 11)));
  }
  const document = {
    display_name: "legend " + n, renderer: { kind: "chart", version: "1" },
    inputs: { result: { columns: [{ name: "x", type: "STRING" }, { name: "s", type: "STRING" }, { name: "v", type: "DECIMAL" }] } },
    config: { mark: { type: "line" }, encoding: { x: { field: "x", type: "nominal", sort: "ascending" }, y: { field: "v", type: "quantitative", aggregate: "sum" }, color: { field: "s", type: "nominal", sort: null, ...(extra.color || {}) } } },
  };
  return { document, columns: { x, s, v }, types: { x: "STRING", s: "STRING", v: "DECIMAL" } };
}

/** What a draw did with the legend: its place, the entries (struck or not), the names on the lines, the series drawn. */
function legendOf(r) {
  const prims = r.primitives, entries = [], drawn = new Set();
  for (let i = 0; i < prims.length; i++) {
    const p = prims[i];
    if (p.k === "group" && p.role === "mark" && p.n > 0) drawn.add(p.series);
    if (p.k === "group" && p.role === "legend" && p.n > 1) {
      const t = prims.slice(i + 1, i + 1 + p.n).find((q) => q.k === "text");
      entries.push({ id: p.legend, struck: t.struck === true, on: p.on });
    }
  }
  return { entries, names: directNames(prims).map((t) => t.series), drawn };
}

const ids = (n) => SERIES_NAMES.slice(0, n).map((s) => "string:" + s);

test("LA10 (unit half): the legend's place for 1, 2, 4, 5 and 8 series with none, one and all ids on the list", () => {
  let draws = 0;
  for (const n of [1, 2, 4, 5, 8]) {
    const all = ids(n);
    for (const hidden of [[], [all[0]], all]) {
      const r = R.render({ ...inputOf(lineOf(n), 960, 540), hidden });
      const L = legendOf(r);
      const expectDirect = n >= 2 && n <= 4 && hidden.length === 0;
      const expectNone = n === 1 && hidden.length === 0;
      const label = n + " series, " + hidden.length + " listed";
      if (expectDirect) {
        assert.deepEqual(L.entries, [], label + ": no legend entries");
        assert.deepEqual([...L.names].sort(), [...all].sort(), label + ": every name on the lines");
      } else if (expectNone) {
        assert.deepEqual(L.entries, [], label);
        assert.deepEqual(L.names, [], label);
      } else {
        // Line 5: the legend at the top, one entry per series of the data, the listed ones struck.
        assert.deepEqual(L.names, [], label + ": no names on the lines");
        assert.deepEqual(L.entries.map((e) => e.id), all, label + ": one entry per series in the data");
        for (const e of L.entries) assert.equal(e.struck, hidden.includes(e.id), label + " " + e.id);
        const top = r.primitives.find((p) => p.k === "group" && p.role === "legend");
        const plotTop = r.index.plot.y;
        const entryText = r.primitives[r.primitives.indexOf(top) + 2];
        assert.ok(entryText.y < plotTop, label + ": the legend is above the plot");
      }
      // A series not drawn because of the list has a struck entry; every other series is drawn.
      for (const id of all) {
        if (L.drawn.has(id)) continue;
        assert.ok(L.entries.some((e) => e.id === id && e.struck), label + ": " + id + " hidden with no struck entry");
      }
      same(r.summary.series.filter((s) => s.hidden).map((s) => s.id).sort(), all.filter((id) => !L.drawn.has(id)).sort(), label + ": summary says what the draw did");
      draws++;
    }
  }
  assert.equal(draws, 15);
});

test("LA10: an id on the list that is not in the data changes nothing; minimal and legend: null draw every series", () => {
  const plain = JSON.stringify(R.render(inputOf(lineOf(3), 960, 540)).primitives);
  assert.equal(JSON.stringify(R.render({ ...inputOf(lineOf(3), 960, 540), hidden: ["string:Nowhere"] }).primitives), plain);
  const minimal = R.render({ ...inputOf(lineOf(3), 200, 120), hidden: ids(3) });
  assert.equal(minimal.level, "minimal");
  assert.equal(legendOf(minimal).drawn.size, 3, "minimal: every series drawn");
  const nul = R.render({ ...inputOf(lineOf(3, { color: { legend: null } }), 960, 540), hidden: ids(3) });
  assert.deepEqual(legendOf(nul).entries, []);
  assert.deepEqual(legendOf(nul).names, []);
  assert.equal(legendOf(nul).drawn.size, 3, "legend: null: every series drawn");
});

test("LA10: a compact row that folds entries into +n leaves a listed, folded series drawn", () => {
  const all = ids(8);
  let folded = 0;
  for (const [w, h] of [[330, 220], [360, 240], [400, 260]]) {
    const r = R.render({ ...inputOf(lineOf(8), w, h), hidden: [all[7]] });
    if (r.level !== "compact") continue;
    const L = legendOf(r);
    if (L.entries.some((e) => e.id === all[7])) continue;
    assert.ok(r.primitives.some((p) => p.k === "text" && p.role === "legend" && /^\+\d+$/.test(p.text)), "a +n entry");
    assert.ok(L.drawn.has(all[7]), w + "×" + h + ": the folded series is drawn");
    folded++;
  }
  assert.ok(folded >= 1, "compact draws with the listed entry folded: " + folded);
});

test("the legend function itself: its five lines in order, counts of the data never of the visible", () => {
  const C = R.chrome, s = (n) => ids(n).map((id) => ({ id, title: id }));
  assert.equal(C.legendPlace(s(3), [], "minimal", undefined, true), null);
  assert.equal(C.legendPlace(s(3), [], "full", null, true), null);
  assert.equal(C.legendPlace(s(1), [], "full", undefined, true), null);
  assert.equal(C.legendPlace(s(1), ids(1), "full", undefined, true), "top");
  assert.equal(C.legendPlace(s(3), [], "full", { orient: "left" }, true), "left");
  assert.equal(C.legendPlace(s(3), [], "full", { orient: "direct" }, true), "direct");
  assert.equal(C.legendPlace(s(3), [], "full", undefined, true), "direct");
  assert.equal(C.legendPlace(s(3), [], "full", undefined, false), "top", "bar writes no names on lines");
  assert.equal(C.legendPlace(s(3), ids(3).slice(0, 1), "full", undefined, true), "top");
  assert.equal(C.legendPlace(s(5), ids(5).slice(0, 1), "full", undefined, true), "top", "five with one off: four visible, still the top");
  assert.equal(C.legendPlace(s(4), [], "compact", undefined, true), "compact");
  assert.equal(C.legendPlace(s(3), ["string:Nowhere"], "full", undefined, true), "direct", "an id not in the data changes nothing");
});

// ---------------------------------------------------------------- bounded work on hostile input (the lane's security pass)

test("log10 is total: 0, a negative, NaN and Infinity end at once, and a log domain past a double's range draws from the data", () => {
  const S = R.scales;
  assert.equal(S.log10(0), -Infinity);
  assert.equal(S.log10(-5), -Infinity);
  assert.equal(S.log10(NaN), -Infinity);
  assert.equal(S.log10(Infinity), Infinity);
  assert.equal(S.log10(5e-324) < -323, true, "the smallest subnormal still ends: " + S.log10(5e-324));
  // A domain the save refuses (chart.scale.domain_log) still reaches the engine from a hand-built input: never a hang.
  const f = fx("line", "log");
  for (const domain of [[1, Infinity], [0, 100], [-10, 100], [100, 10], [NaN, 10]]) {
    const input = inputOf(f, 960, 540);
    input.document = JSON.parse(JSON.stringify(input.document));
    input.document.config.encoding.y.scale = { type: "log", domain };
    const r = R.render(input);
    assert.equal(r.state, "rendered", "domain " + JSON.stringify(domain));
    const free = R.render(Object.assign(inputOf(f, 960, 540), { document: (() => { const d = JSON.parse(JSON.stringify(input.document)); d.config.encoding.y.scale = { type: "log" }; return d; })() }));
    same(r.primitives, JSON.parse(JSON.stringify(free.primitives)), "an unusable domain " + JSON.stringify(domain) + " draws as no domain");
  }
});

/** n rows, each its own series and its own x: n × n cells if the model built them before the series cap. */
function seriesFlood(type, n) {
  const k = [], s = [], v = [];
  for (let i = 0; i < n; i++) {
    k.push("k" + i);
    s.push("s" + i);
    v.push(String(i));
  }
  const y = { field: "v", type: "quantitative", aggregate: "sum" };
  if (type !== "line") y.stack = null;
  const document = {
    display_name: "flood", renderer: { kind: "chart", version: "1" },
    inputs: { result: { columns: [{ name: "k", type: "STRING" }, { name: "s", type: "STRING" }, { name: "v", type: "DECIMAL" }] } },
    config: { mark: { type }, encoding: { x: { field: "k", type: "nominal", sort: null }, y, color: { field: "s", type: "nominal", sort: null } } },
  };
  return { type, document, columns: { k, s, v }, types: { k: "STRING", s: "STRING", v: "DECIMAL" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
}

test("the series cap refuses before any work that grows with series × x: the cells are never built (bar, line, area)", () => {
  const planted = { bar: ["types/bar.js", "var categories = D.order("], line: ["16-lines.js", "var cells = series.map("], area: ["16-lines.js", "var cells = series.map("] };
  const n = 5000;
  for (const [type, [file, anchor]] of Object.entries(planted)) {
    const E = engine((srcs) => {
      assert.equal(srcs[file].split(anchor).length, 2, file + ": the anchor occurs once");
      srcs[file] = srcs[file].replace(anchor, "globalThis.__crossBuilt = true; " + anchor);
    });
    const r = E.render(seriesFlood(type, n));
    assert.equal(r.state, "refused", type);
    same(r.summary.refusal, { code: "chart.too_many_series", details: { series: n, limit: E.tables.cap("series") } }, type);
    assert.equal(E.render.constructor("return globalThis.__crossBuilt")(), undefined, type + ": the series × x work never ran");
    // The control: at the cap the same model reaches that work, so the probe can see it.
    const ok = E.render(seriesFlood(type, E.tables.cap("series")));
    assert.equal(ok.state, "rendered", type + " at the cap");
    assert.equal(E.render.constructor("return globalThis.__crossBuilt")(), true, type + ": the probe sees the work at the cap");
  }
});
