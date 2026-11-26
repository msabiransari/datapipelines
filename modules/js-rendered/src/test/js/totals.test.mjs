// Brief 990 r2 D1, D3 (engine): a stacked bar's index carries `totals`, by datum the stack's total as the card's text
// (look contract §4.4 "the whole stack, top segment first, with the total in the heading"). Every expected total is
// computed HERE from the fixture's rows: the rows of a category and a visible series summed exactly into a cell, a cell
// beyond float64's range not drawn (as the template draws it), the drawn cells summed, then written by the engine's
// value formatter (R.text.value) with the chart's format and unit. Nothing is read from the function under test.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";
import { plant } from "./forms.mjs";

// A spy on the cartesian value-axis allocator (the pattern of forms.mjs's D8 spy), so the label tests read the room the
// layout actually reserved for the end labels and the exact text it measured — the endLabels array the allocator
// consumed — rather than calling the model's producer after render. With no spy set the allocator is the file's own.
const BAR = "types/bar.js";
const CART = "12-cartesian.js";
const ROOM_SPY_FROM = "  function valueAxisOf(lvl, L, ext, length, room) {";
const ROOM_SPY_TO = "  function valueAxisOf(lvl, L, ext, length, room) { if (R.spyRoom) R.spyRoom(room, L.endLabels === null ? null : L.endLabels.slice());";
const R = engine((s) => plant(s, CART, ROOM_SPY_FROM, ROOM_SPY_TO));
const plain = (v) => JSON.parse(JSON.stringify(v));
const FRAME = [960, 540];

// A fixed-width measurer (six px a code point, the pattern of forms.mjs's SIX) so a reserved room is a pinned number.
const SIX = (text) => 6 * [...text].length;
// The gap the value axis keeps between a bar's end and its label: 0.25 em of --chart-text-small (11 px at the light
// default), so a horizontal draw's reservation is the measured text's width plus 2.75 px.
const LABEL_GAP = R.tables.look("valueLabelGap") * R.theme.resolve("light").n("--chart-text-small");
const roomOf = (text) => SIX(text) + LABEL_GAP;

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

/** The render input of a stacked bar of rows [[x, series, value]]; opts = {format, unit, types, hidden, horizontal}. */
function barInput(rows, opts = {}) {
  const value = { field: "v", type: "quantitative", aggregate: "sum", stack: "zero", format: opts.format || "compact" };
  if (opts.unit) value.unit = opts.unit;
  const band = { field: "x", type: "nominal", sort: "ascending" };
  const color = { field: "s", type: "nominal", sort: "ascending" };
  const document = { display_name: "t", config: { mark: { type: "bar" }, encoding: opts.horizontal ? { x: value, y: band, color } : { x: band, y: value, color } } };
  const columns = { x: rows.map((r) => r[0]), s: rows.map((r) => r[1]), v: rows.map((r) => r[2]) };
  return { type: "bar", document, columns, types: opts.types || { x: "STRING", s: "STRING", v: "DECIMAL" }, frame: { w: FRAME[0], h: FRAME[1] }, theme: "light", measure: opts.measure === undefined ? null : opts.measure, hidden: opts.hidden || [] };
}

/** A stacked bar of rows [[x, series, value]] at the default frame; opts = {format, unit, types, hidden}. */
function hand(rows, opts = {}) {
  return plain(R.render(barInput(rows, opts)));
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
  const others = chartFixtures().filter((f) => (f.wire === "bar" && channels(f.fixture.document).value.stack !== "zero") || f.wire === "line" || f.wire === "area" || f.wire === "scatter");
  assert.ok(others.some((f) => unstacked.includes(f.name)));
  for (const f of others) {
    const out = plain(R.render(inputOf(f.fixture, ...FRAME)));
    assert.equal(Object.prototype.hasOwnProperty.call(out.index, "totals"), false, f.wire + "/" + f.name);
  }
  const oneSeries = hand([["a", "P", "1"]]);
  assert.ok(Array.isArray(oneSeries.index.totals), "a stack of one series still carries its total");
});

// ---------------------------------------------------------------- #1118: the stack-total LABEL (owner 2026-11-24)

/** A render with the value-axis allocator spy: {r, rooms}, rooms = every {room, els} the allocator was handed. */
function drawRoom(rows, opts = {}) {
  const rooms = [];
  R.spyRoom = (room, els) => rooms.push({ room, els: els === null ? null : plain(els) });
  try {
    return { r: plain(R.render(barInput(rows, opts))), rooms };
  } finally {
    R.spyRoom = null;
  }
}

/** The painted stack-total labels, in draw order. */
const guides = (r) => r.primitives.filter((p) => p.k === "text" && p.role === "guide").map((p) => p.text);

/** The one reservation a draw makes (its passes repeat the same room): the allocator's last {room, els}. */
function reservation(d) {
  assert.ok(d.rooms.length >= 1, "the value-axis allocator ran");
  const last = d.rooms[d.rooms.length - 1];
  for (const k of d.rooms) assert.equal(k.room, last.room, "the reserved room is stable across the allocator's passes");
  return last;
}

/** The pinned BIGINT cases' wire types: exact decimal columns, not the helper's DECIMAL default. */
const BIGINT = { x: "STRING", s: "STRING", v: "BIGINTEGER" };

test("#1118: a BIGINT stack total is its exact sum in the short form, painted and measured", () => {
  assert.equal(roomOf("9,007.2T"), 50.75, "pinned: 6 px × 8 code points + 2.75 px gap");
  assert.equal(roomOf("10,007.2T"), 56.75, "pinned: 6 px × 9 code points + 2.75 px gap");
  for (const [second, want] of [["2", "9,007.2T"], ["1000000000000001", "10,007.2T"]]) {
    const v = drawRoom([["a", "P", "9007199254740993"], ["a", "Q", second]], { types: BIGINT });
    assert.deepEqual(guides(v.r), [want], "painted label for +" + second);
  }
  const both = drawRoom([["a", "P", "9007199254740993"], ["a", "Q", "1000000000000001"]], { horizontal: true, types: BIGINT, measure: SIX });
  assert.deepEqual(guides(both.r), ["10,007.2T"], "painted, horizontal");
  const res = reservation(both);
  assert.deepEqual(res.els, ["10,007.2T"], "the layout measured the exact-sum text");
  assert.equal(res.room, roomOf("10,007.2T"), "the room passed to the value-axis allocator is the exact text's width");
});

test("#1118: hiding and restoring a contributor moves the measured reservation to the visible exact sum", () => {
  const rows = [["a", "P", "9007199254740993"], ["a", "Q", "1000000000000001"]];
  const both = drawRoom(rows, { horizontal: true, types: BIGINT, measure: SIX });
  assert.equal(reservation(both).room, roomOf("10,007.2T"), "both visible");
  const q = both.r.summary.series.find((s) => s.title === "Q").id;
  const hid = drawRoom(rows, { horizontal: true, types: BIGINT, measure: SIX, hidden: [q] });
  assert.equal(hid.r.summary.series.find((s) => s.title === "Q").hidden, true, "Q is hidden in this draw");
  assert.deepEqual(guides(hid.r), ["9,007.2T"], "painted follows the visible series");
  const hidRes = reservation(hid);
  assert.deepEqual(hidRes.els, ["9,007.2T"], "the layout measured the visible exact sum");
  assert.equal(hidRes.room, roomOf("9,007.2T"), "the reserved room follows the visible series");
  const back = drawRoom(rows, { horizontal: true, types: BIGINT, measure: SIX });
  assert.equal(reservation(back).room, roomOf("10,007.2T"), "restoring the contributor restores the reservation");
});

test("#1118: DECIMAL fractions and mixed signs sum exactly; null and nonexact keep their forms", () => {
  const frac = drawRoom([["a", "P", "1.5"], ["a", "Q", "2.25"]]);
  assert.deepEqual(guides(frac.r), [R.text.value("3.75", "compact")], "fractional sum");
  const neg = drawRoom([["a", "P", "-5"], ["a", "Q", "2"]]);
  assert.deepEqual(guides(neg.r), ["−3"], "the sign is the engine's minus");
  const nulls = drawRoom([["a", "P", null], ["b", "P", "5"]]);
  assert.deepEqual(guides(nulls.r), ["5"], "only the drawn category carries a label");
  const float = drawRoom([["a", "P", 1e15], ["a", "Q", 2]], { types: { x: "STRING", s: "STRING", v: "DOUBLE" } });
  assert.deepEqual(guides(float.r), [R.text.value(1e15 + 2, "compact")], "a nonexact stack keeps the numeric total format");
});

test("#1118: a normalized stack paints no total label", () => {
  const value = { field: "v", type: "quantitative", aggregate: "sum", stack: "normalize", format: "compact" };
  const document = { display_name: "t", config: { mark: { type: "bar" }, encoding: { x: { field: "x", type: "nominal", sort: "ascending" }, y: value, color: { field: "s", type: "nominal", sort: "ascending" } } } };
  const columns = { x: ["a", "a"], s: ["P", "Q"], v: ["1", "3"] };
  const r = plain(R.render({ type: "bar", document, columns, types: BIGINT, frame: { w: FRAME[0], h: FRAME[1] }, theme: "light", measure: null, hidden: [] }));
  assert.deepEqual(guides(r), [], "a normalized stack draws no total label");
});

// ---------------------------------------------------------------- #1118 falsifications (each in its own copy)

/** A horizontal BIGINT draw on a fresh engine carrying the extra plants: {r, last: the allocator's last {room, els}}. */
function falsifiedDraw(edits) {
  const Rf = engine((s) => {
    plant(s, CART, ROOM_SPY_FROM, ROOM_SPY_TO);
    for (const [key, from, to] of edits) plant(s, key, from, to);
  });
  const rooms = [];
  Rf.spyRoom = (room, els) => rooms.push({ room, els: els === null ? null : plain(els) });
  const r = plain(Rf.render(barInput([["a", "P", "9007199254740993"], ["a", "Q", "1000000000000001"]], { horizontal: true, types: BIGINT, measure: SIX })));
  return { r, last: rooms[rooms.length - 1] };
}

const PAINT_FROM = "      var lb = labelAt(m, plot, hp, outerBox, m.stackTotalText(plot.visible, c), groupW, groupW, placed);";
const PAINT_TO = "      var lb = labelAt(m, plot, hp, outerBox, T.value(total, look(\"stackTotalFormat\")), groupW, groupW, placed);";
const RESERVE_FROM = "          var text = stackTotalText(visible, c3);";
const RESERVE_TO = "          var text = null; var fs = 0, fa = false; for (var fx = 0; fx < series.length; fx++) { if (visible[series[fx].id] !== true) continue; var fcv = cells[fx][c3]; if (fcv.num === null) continue; fa = true; fs += fcv.num; } if (fa) text = T.value(fs, look(\"stackTotalFormat\"));";
const CONSUME_FROM = "widestOf(h, L.endLabels, h.small)";
const CONSUME_TO = "0";

test("#1118 falsification: a float paint site paints the float total while the reservation stays exact", () => {
  const d = falsifiedDraw([[BAR, PAINT_FROM, PAINT_TO]]);
  assert.deepEqual(guides(d.r), ["1e16"], "the paint site fell back to the float sum");
  assert.equal(d.last.room, roomOf("10,007.2T"), "the reservation still measures the exact text");
});

test("#1118 falsification: a float reservation producer fails the room assertion while the paint stays exact", () => {
  const d = falsifiedDraw([[BAR, RESERVE_FROM, RESERVE_TO]]);
  assert.deepEqual(guides(d.r), ["10,007.2T"], "the paint site still uses the exact sum");
  assert.equal(d.last.room, roomOf("1e16"), "the reservation fell back to the float text");
  assert.notEqual(d.last.room, roomOf("10,007.2T"), "and no longer reserves the exact text's room");
});

test("#1118 falsification: bypassing the reservation consumer fails its own room assertion", () => {
  const d = falsifiedDraw([[CART, CONSUME_FROM, CONSUME_TO]]);
  assert.deepEqual(guides(d.r), ["10,007.2T"], "the paint site still uses the exact sum");
  assert.deepEqual(d.last.els, ["10,007.2T"], "the measured text still names the exact sum");
  assert.equal(d.last.room, LABEL_GAP, "the consumer was bypassed: only the gap remains");
  assert.notEqual(d.last.room, roomOf("10,007.2T"), "so the room the guard asserts would fail");
});
