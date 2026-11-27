// Brief 990 r2 D1, D3 (engine): a stacked bar's index carries `totals`, by datum the stack's total as the card's text
// (look contract §4.4 "the whole stack, top segment first, with the total in the heading"). Every expected total is
// computed HERE from the fixture's rows: the rows of a category and a visible series summed exactly into a cell, a cell
// beyond float64's range not drawn (as the template draws it), the drawn cells summed, then written by the engine's
// value formatter (R.text.value) with the chart's format and unit. Nothing is read from the function under test.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";

const R = engine();
const plain = (v) => JSON.parse(JSON.stringify(v));
const FRAME = [960, 540];

/** The value channel and the band channel of a bar document. */
function channels(doc) {
  const e = doc.config.encoding;
  return e.y.type === "quantitative" ? { value: e.y, band: e.x, color: e.color } : { value: e.x, band: e.y, color: e.color };
}

/** Exact decimal text → [BigInt units, scale]; the sum of several at their largest scale, back to text. */
function exactSum(texts) {
  const parsed = texts.map((t) => {
    const s = String(t).trim();
    const neg = s.startsWith("-");
    const [i, f = ""] = (neg ? s.slice(1) : s).split(".");
    return { neg, digits: BigInt(i + f || "0"), scale: f.length };
  });
  const scale = Math.max(0, ...parsed.map((p) => p.scale));
  let sum = 0n;
  for (const p of parsed) sum += (p.neg ? -1n : 1n) * p.digits * 10n ** BigInt(scale - p.scale);
  const neg = sum < 0n;
  const abs = (neg ? -sum : sum).toString().padStart(scale + 1, "0");
  return (neg ? "-" : "") + (scale === 0 ? abs : abs.slice(0, abs.length - scale) + "." + abs.slice(abs.length - scale));
}

/**
 * The totals the test expects for a fixture's draw: {categoryText: total text | null}. visible = the series titles the
 * draw shows. A cell is a (category, series) sum of the rows' values; a cell whose number is not finite is not drawn.
 */
function expectedTotals(fx, visibleTitles) {
  const { value, band, color } = channels(fx.document);
  const B = fx.columns[band.field], C = fx.columns[color.field], V = fx.columns[value.field];
  const cells = new Map();
  for (let i = 0; i < B.length; i++) {
    if (B[i] === null || C[i] === null || V[i] === null) continue;
    if (!visibleTitles.has(String(C[i]))) continue;
    const cat = band.type === "temporal" ? R.text.temporalFull(B[i], band.format) : String(B[i]);
    const key = cat + "\u0000" + String(C[i]);
    if (!cells.has(key)) cells.set(key, { cat, values: [] });
    cells.get(key).values.push(V[i]);
  }
  const byCat = new Map();
  for (const { cat, values } of cells.values()) {
    const exact = values.every((v) => typeof v === "string");
    const sum = exact ? exactSum(values) : values.reduce((a, b) => a + b, 0);
    if (!Number.isFinite(Number(sum))) continue;
    if (!byCat.has(cat)) byCat.set(cat, []);
    byCat.get(cat).push(sum);
  }
  const out = {};
  for (const [cat, sums] of byCat) {
    const exact = sums.every((s) => typeof s === "string");
    const total = exact ? exactSum(sums) : sums.reduce((a, b) => a + Number(b), 0);
    const text = R.text.value(total, value.format);
    out[cat] = typeof value.unit === "string" && value.unit !== "" ? text + " " + value.unit : text;
  }
  return out;
}

/** {datum: category text} of a draw, read from its drawn marks' first tip line (the category, D2: tip is untouched). */
function categoriesOf(out) {
  const by = {};
  for (const s of out.index.series) for (let k = 0; k < s.datum.length; k++) by[s.datum[k]] = s.tip[k][0];
  return by;
}

/** Asserts a draw's totals against the rows: one entry per category, each the expected text, null where nothing is drawn. */
function assertTotals(fx, out, label) {
  const visible = new Set(out.summary.series.filter((s) => !s.hidden).map((s) => s.title));
  const expected = expectedTotals(fx, visible);
  const cats = categoriesOf(out);
  assert.ok(Array.isArray(out.index.totals), label + ": index.totals is a list");
  const nCats = new Set(fx.columns[channels(fx.document).band.field].filter((v) => v !== null).map(String)).size;
  assert.equal(out.index.totals.length, nCats, label + ": one entry per category");
  let shown = 0;
  for (let d = 0; d < out.index.totals.length; d++) {
    const got = out.index.totals[d];
    if (cats[d] === undefined) {
      assert.equal(got, null, label + ": datum " + d + " has no drawn segment, so no total");
      continue;
    }
    assert.equal(got, expected[cats[d]], label + ": the total of " + JSON.stringify(cats[d]));
    shown++;
  }
  return shown;
}

const stackedAtZero = chartFixtures().filter((f) => f.wire === "bar" && channels(f.fixture.document).value.stack === "zero");

test("D1: every stacked bar fixture's index has one total per category, each the formatter over its drawn visible cells' sum", () => {
  assert.ok(stackedAtZero.length >= 6, "stacked fixtures found: " + stackedAtZero.map((f) => f.name));
  let checked = 0;
  for (const f of stackedAtZero) {
    const out = plain(R.render(inputOf(f.fixture, ...FRAME)));
    assert.equal(out.state, "rendered", f.name);
    checked += assertTotals(f.fixture, out, f.name);
  }
  assert.ok(checked >= 20, "totals compared: " + checked);
});

test("D1: a negative segment: the total is the sum with its sign (parity-bar-negatives, and a stack that sums below zero)", () => {
  const f = stackedAtZero.find((x) => x.name === "parity-bar-negatives");
  const out = plain(R.render(inputOf(f.fixture, ...FRAME)));
  assertTotals(f.fixture, out, f.name);
  const neg = hand([["a", "P", "-5"], ["a", "Q", "2"], ["b", "P", "3"], ["b", "Q", "-1.5"]]);
  assert.deepEqual(neg.index.totals, [R.text.value("-3", "compact"), R.text.value("1.5", "compact")]);
  assert.equal(neg.index.totals[0], "−3", "the sign is the engine's minus");
});

test("D1: one series hidden: the total follows the visible series only", () => {
  const f = stackedAtZero.find((x) => x.name === "parity-bar-stacked");
  const all = plain(R.render(inputOf(f.fixture, ...FRAME)));
  const first = all.summary.series[0].id;
  const out = plain(R.render(inputOf(f.fixture, ...FRAME, { hidden: [first] })));
  assert.equal(out.summary.series[0].hidden, true, "the series is hidden in this draw");
  assertTotals(f.fixture, out, "parity-bar-stacked with " + first + " hidden");
  assert.notDeepEqual(out.index.totals, all.index.totals, "hiding a series changes the totals");
});

/** A stacked bar of rows [[x, series, value]] at the default frame; opts = {format, unit, types, hidden}. */
function hand(rows, opts = {}) {
  const y = { field: "v", type: "quantitative", aggregate: "sum", stack: "zero", format: opts.format || "compact" };
  if (opts.unit) y.unit = opts.unit;
  const document = { display_name: "t", config: { mark: { type: "bar" }, encoding: { x: { field: "x", type: "nominal", sort: "ascending" }, y, color: { field: "s", type: "nominal", sort: "ascending" } } } };
  const columns = { x: rows.map((r) => r[0]), s: rows.map((r) => r[1]), v: rows.map((r) => r[2]) };
  return plain(R.render({ type: "bar", document, columns, types: opts.types || { x: "STRING", s: "STRING", v: "DECIMAL" }, frame: { w: FRAME[0], h: FRAME[1] }, theme: "light", measure: null, hidden: opts.hidden || [] }));
}

test("D1: the card's form, not the label's: the chart's format and unit, as a row's value carries them", () => {
  const out = hand([["a", "P", "1234.5"], ["a", "Q", "2467.5"]], { format: "decimal(1)", unit: "kg" });
  const tip = out.index.series[0].tip[0][1];
  assert.equal(tip, "P: 1,234.5 kg", "a row's value in tip");
  assert.deepEqual(out.index.totals, ["3,702.0 kg"]);
  const label = out.primitives.find((p) => p.k === "text" && p.role === "guide" && p.text.startsWith("3.7"));
  assert.ok(label, "the end label keeps its short form (stackTotalFormat)");
});

test("D1: exact cells sum exactly (a DECIMAL total past 2^53); a float total beyond float64's range is no total", () => {
  const big = hand([["a", "P", "9007199254740993"], ["a", "Q", "1"]], { format: "integer" });
  assert.deepEqual(big.index.totals, ["9,007,199,254,740,994"], "the float sum would read ...992");
  const far = hand([["a", "P", 1e308], ["a", "Q", 1e308], ["b", "P", 1]], { types: { x: "STRING", s: "STRING", v: "DOUBLE" } });
  assert.deepEqual(far.index.totals, [null, "1"], "two finite cells whose sum is not finite: no total, shown whole or not at all");
});

test("D1.3: an unstacked bar, a normalized stack, a line, an area and a scatter have no totals member", () => {
  const unstacked = ["parity-bar-grouped", "parity-bar-vertical"];
  const normalized = chartFixtures().filter((f) => f.wire === "bar" && channels(f.fixture.document).value.stack === "normalize");
  assert.ok(normalized.length >= 2, "normalized fixtures: " + normalized.length);
  const others = chartFixtures().filter((f) => (f.wire === "bar" && channels(f.fixture.document).value.stack !== "zero") || f.wire === "line" || f.wire === "area" || f.wire === "scatter" || f.wire === "pie" || f.wire === "histogram" || f.wire === "heatmap" || f.wire === "box" || f.wire === "funnel" || f.wire === "gauge" || f.wire === "waterfall" || f.wire === "sparkline" || f.wire === "treemap");
  assert.ok(others.some((f) => unstacked.includes(f.name)));
  for (const f of others) {
    const out = plain(R.render(inputOf(f.fixture, ...FRAME)));
    assert.equal(Object.prototype.hasOwnProperty.call(out.index, "totals"), false, f.wire + "/" + f.name);
  }
  const oneSeries = hand([["a", "P", "1"]]);
  assert.ok(Array.isArray(oneSeries.index.totals), "a stack of one series still carries its total");
});
