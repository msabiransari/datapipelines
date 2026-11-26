// A.2: lane 977's engine cases kept (D/engine/test/cases.js, adapted to this engine's input and primitives), then the
// four states of §4.3 and the fixed sentences. Positions are worked out BY HAND from the layout rules with a 6 px per
// character stand-in for the width (SIX), so a reader can redo every number:
//   frame 402 × 306, text 12 (edge 6, tick gap 6), no title (display_name only) and no axis titles, so the box is
//   x 6…396, y 6…300; the plot top is 6 + half a text box (16.344 / 2 = 8.172) = 14.172; the label row reserves
//   6 + 16.344 = 22.344, so the plot bottom is 277.656 and its height 263.484; 263.484 / 48 → 5 intervals at most.
//   Value labels are on by default (look labelsDefault), so the axis keeps a label's room (14.982 + 2.75 = 17.732 px)
//   above the tallest bar: [0, 40] grows to the nice range [0, 50].
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine } from "./engine.mjs";
import { markBox } from "./geometry.mjs";

const R = engine();
/** Deep equality across realms: the engine's objects come from a vm context, whose Array and Object are not this one's. */
const same = (actual, expected, msg) => assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, msg);
const SIX = (text) => 6 * [...text].length;
const FRAME = { w: 402, h: 306 };

function doc(enc, mark = {}, extra = {}) {
  return Object.assign({ display_name: "t", config: { mark: Object.assign({ type: "bar" }, mark), encoding: enc } }, extra);
}
const X = { field: "k", type: "nominal", title: null };
const Y = { field: "v", type: "quantitative", aggregate: "sum", title: null };
function draw(rows, opts = {}) {
  const fields = ["k", "v", "c"];
  const columns = {};
  for (const f of fields) if (rows.some((r) => f in r)) columns[f] = rows.map((r) => (f in r ? r[f] : null));
  const enc = { x: X, y: Y };
  if (opts.color) enc.color = opts.color;
  return R.render({ type: "bar", document: opts.document || doc(enc, opts.mark), columns, types: { k: "STRING", v: "DOUBLE", c: "STRING" }, frame: opts.frame || FRAME, theme: opts.theme || "light", measure: opts.measure === undefined ? SIX : opts.measure, hidden: opts.hidden || [] });
}
const all = (p, k, role) => p.filter((x) => x.k === k && (role === undefined || x.role === role));
const marks = (p) => p.filter((x) => x.role === "mark" && x.k !== "group" && x.k !== "text");
const textsOf = (p, role) => all(p, "text", role).map((t) => (t.shown !== null ? t.shown : t.text));

test("tick values for six ranges and the degenerate one (977)", () => {
  const n = R.scales.nice;
  same(n(0, 40, 6).ticks, [0, 10, 20, 30, 40]);
  same(n(0, 1, 5).ticks, [0, 0.2, 0.4, 0.6, 0.8, 1]);
  same(n(-20, 10, 6).ticks, [-20, -15, -10, -5, 0, 5, 10]);
  same(n(3, 97, 5).ticks, [0, 20, 40, 60, 80, 100]);
  same(n(1200, 9800, 4).ticks, [0, 2500, 5000, 7500, 10000]);
  same(n(0.0012, 0.0049, 4).ticks, [0.001, 0.002, 0.003, 0.004, 0.005]);
  same(n(5, 5, 4).ticks, [0, 2, 4, 6], "one value: the range opens to zero");
});

test("powers of ten and decades without a logarithm (977)", () => {
  const S = R.scales;
  assert.equal(S.decade(1000), 3);
  assert.equal(S.decade(999.9999999999999), 2);
  assert.equal(S.decade(0.001), -3);
  assert.equal(S.decade(0.0009999999999999998), -4);
  assert.equal(S.decade(1.7976931348623157e308), 308);
  assert.equal(S.decade(5e-324), -323);
  assert.ok(S.pow10(23) === 1e23 && S.pow10(-7) === 1e-7 && S.pow10(0) === 1);
  assert.equal(S.niceStep(1e23 * 3), 5e23);
});

test("three bars to the pixel (977, worked by hand)", () => {
  const out = draw([{ k: "a", v: 10 }, { k: "b", v: 20 }, { k: "c", v: 40 }]);
  assert.equal(out.state, "rendered");
  assert.equal(out.level, "full");
  // the widest tick label "50" is 12 px: plot x = 6 + 12 + 6 = 24, width 396 − 24 = 372; three bands of 124
  same(out.index.plot, { x: 24, y: 14.17, w: 372, h: 263.48 });
  same(textsOf(out.primitives, "tick"), ["0", "10", "20", "30", "40", "50", "a", "b", "c"]);
  // a bar fills 74% of its band: 91.76 wide, offset (124 − 91.76) / 2 = 16.12; scale 0 → 277.65, 50 → 14.17
  const bars = marks(out.primitives);
  assert.equal(bars.length, 3);
  same(bars.map((b) => b.k), ["path", "path", "path"], "rounded data ends (radius 4) are paths");
  same(bars[0].d, ["M", 40.12, 277.65, "L", 40.12, 228.95, "A", 4, 44.12, 224.95, "L", 127.88, 224.95, "A", 4, 131.88, 228.95, "L", 131.88, 277.65, "Z"]);
  same(bars[1].d.slice(0, 6), ["M", 164.12, 277.65, "L", 164.12, 176.26]);
  same(bars[2].d.slice(7, 10), [4, 292.12, 66.87]);
  same(bars[0].tip, ["a", "10"]);
  assert.equal(bars[0].series, "measure");
  // tick labels end 6 px left of the plot, centred on their tick: 277.65 + (1.069 − 0.293) / 2 × 12 = 282.31
  const ticks = all(out.primitives, "text", "tick");
  assert.equal(ticks[0].x, 18);
  assert.equal(ticks[0].y, 282.31);
  assert.equal(ticks[0].anchor, "end");
  // a category label sits under the middle of its band: 24 + 62 = 86; baseline 277.65 + 6 + 1.069 × 12 = 296.48
  assert.equal(ticks[6].x, 86);
  assert.equal(ticks[6].y, 296.48);
  assert.equal(ticks[6].anchor, "middle");
  // value labels above each bar, ink-2, small: 224.95 − 2.75 − 14.982 + 1.069 × 11 = 218.98 (66.87 → 60.9)
  const labels = all(out.primitives, "text", "guide");
  same(labels.map((l) => [l.text, l.x, l.y, l.size, l.fill]), [["10", 86, 218.98, 11, "--chart-ink-2"], ["20", 210, 166.29, 11, "--chart-ink-2"], ["40", 334, 60.9, 11, "--chart-ink-2"]]);
  assert.equal(out.summary.marks, 3);
});

test("a negative bar hangs from the zero line (977, worked by hand)", () => {
  const out = draw([{ k: "a", v: 10 }, { k: "b", v: -20 }]);
  // [−20, 10] with a label's room both ways → [−30, 20]; "−30" is 18 px: plot x 30, width 366, bands of 183
  same(textsOf(out.primitives, "tick").slice(0, 6), ["−30", "−20", "−10", "0", "10", "20"]);
  const bars = marks(out.primitives);
  same(bars[0].d.slice(0, 6), ["M", 53.79, 119.56, "L", 53.79, 70.87]);
  same(bars[1].d, ["M", 372.21, 119.56, "L", 372.21, 220.95, "A", 4, 368.21, 224.95, "L", 240.79, 224.95, "A", 4, 236.79, 220.95, "L", 236.79, 119.56, "Z"]);
  const zero = all(out.primitives, "line", "axis");
  assert.equal(zero.length, 1, "one zero line");
  same([zero[0].x1, zero[0].y1, zero[0].x2, zero[0].y2], [30, 119.56, 396, 119.56]);
  same(bars[1].tip, ["b", "−20"]);
});

test("the value scale is not inverted (G1's target)", () => {
  const bars = marks(draw([{ k: "a", v: 1 }, { k: "b", v: 2 }]).primitives);
  const [a, b] = bars.map(markBox);
  assert.ok(b[1] < a[1] && b[3] - b[1] > a[3] - a[1], "a larger value is a taller bar whose end is higher");
});

test("no rows is the no-data state: the title and the one text, no mark (977)", () => {
  const out = draw([], { document: doc({ x: X, y: Y }, {}, { config: { mark: { type: "bar" }, encoding: { x: X, y: Y }, title: { text: "Sales" } } }) });
  assert.equal(out.state, "no-data");
  assert.equal(out.summary.marks, 0);
  same(textsOf(out.primitives, "empty"), ["No data"]);
  same(textsOf(out.primitives, "title"), ["Sales"]);
  assert.equal(marks(out.primitives).length, 0);
  assert.equal(out.primitives[0].role, "guide", "the canvas comes first");
});

test("every text and attribute is escaped (977)", () => {
  const hostile = "<script>alert(1)</script> & \"q\" 'a'";
  const out = draw([{ k: hostile, v: 3 }], { frame: { w: 960, h: 540 } });
  for (const form of ["page", "standalone"]) {
    const svg = R.writeString(out.primitives, form, "light");
    assert.ok(!svg.includes("<script"), "a value opened an element");
    assert.ok(svg.includes("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot; &#39;a&#39;"), form);
  }
});

test("the index finds the nearest mark per series (977)", () => {
  const out = draw([{ k: "a", v: 10 }, { k: "b", v: 20 }, { k: "c", v: 40 }]);
  assert.equal(out.index.axis, "x");
  same(out.index.series[0].at, [86, 210, 334], "bar centres");
  assert.equal(R.nearest(out.index, "measure", 0).i, 0);
  assert.equal(R.nearest(out.index, "measure", 149).i, 1, "149 is 61 from 210 and 63 from 86");
  assert.equal(R.nearest(out.index, "measure", 147).i, 0, "147 is 61 from 86 and 63 from 210");
  same(R.nearest(out.index, "measure", 1e9).tip, ["c", "40"]);
  assert.equal(R.nearest(out.index, "nope", 10), null, "an unknown series has no mark");
});

test("hiding a series refits the value axis (977)", () => {
  const color = { field: "c", type: "nominal" };
  const rows = [{ k: "a", c: "u", v: 10 }, { k: "b", c: "u", v: 20 }, { k: "a", c: "w", v: 40 }];
  const both = draw(rows, { color, measure: null });
  const one = draw(rows, { color, hidden: ["string:w"], measure: null });
  const values = (p) => textsOf(p, "tick").filter((x) => x !== "a" && x !== "b");
  assert.equal(Number(values(both.primitives).at(-1)) > 40, true, "both: the axis covers 40");
  assert.ok(Number(values(one.primitives).at(-1)) < 40, "one: the axis ends near the visible maximum, 20");
  assert.equal(marks(one.primitives).length, 2, "the hidden series draws nothing");
  const legend = all(one.primitives, "group", "legend");
  same(legend.map((g) => [g.legend, g.on]), [["string:u", true], ["string:w", false]], "the legend keeps the switch, off");
  same(one.summary.series.map((s) => [s.id, s.hidden]), [["string:u", false], ["string:w", true]]);
  assert.equal(one.index.series[1].on, false);
  same(one.index.series[1].at, []);
});

test("the output is pure: the same input twice gives the same text, with the version (977)", () => {
  const a = JSON.stringify(draw([{ k: "a", v: 1 }]));
  assert.equal(a, JSON.stringify(draw([{ k: "a", v: 1 }])));
  assert.equal(JSON.parse(a).version, R.version);
});

test("the four states of §4.3, first applicable row decides", () => {
  const rows = [{ k: "a", v: 1 }];
  const small = draw(rows, { frame: { w: 79, h: 400 } });
  assert.equal(small.state, "too-small");
  assert.equal(small.level, null);
  same(small.primitives, []);
  same(small.summary.warnings, [{ code: "chart.frame-too-small" }]);
  assert.equal(draw(rows, { frame: { w: 80, h: 24 } }).level, "minimal");
  assert.equal(draw(rows, { frame: { w: 200, h: 140 } }).level, "compact");
  assert.equal(draw(rows, { frame: { w: 359, h: 400 } }).level, "compact");
  assert.equal(draw(rows, { frame: { w: 360, h: 240 } }).level, "full");
  assert.equal(draw([{ k: "a", v: null }]).state, "no-data", "every cell null: no mark");
  assert.equal(draw(rows).state, "rendered");
  const refused = draw(Array.from({ length: 9 }, (_, i) => ({ k: "a", c: "s" + i, v: 1 })), { color: { field: "c", type: "nominal" } });
  assert.equal(refused.state, "refused");
  same(refused.primitives, []);
  same(refused.summary.refusal, { code: "chart.too_many_series", details: { series: 9, limit: 8 } });
  // too-small wins over a refusal: the frame is the first row
  const both = draw(Array.from({ length: 9 }, (_, i) => ({ k: "a", c: "s" + i, v: 1 })), { color: { field: "c", type: "nominal" }, frame: { w: 10, h: 10 } });
  assert.equal(both.state, "too-small");
});

test("the output carries exactly the members of §4.3", () => {
  const out = draw([{ k: "a", v: 1 }]);
  same(Object.keys(out), ["version", "type", "state", "level", "primitives", "index", "summary"]);
  same(Object.keys(out.summary), ["marks", "series", "warnings", "refusal"]);
  same(out.summary.series, [{ id: "measure", title: "v", hidden: false }]);
  assert.equal(out.type, "bar");
});

test("the fixed sentence of every refusal code, numbers grouped", () => {
  assert.equal(R.sentence({ code: "chart.too_many_marks", details: { marks: 1234, limit: 1000 } }), "Too many marks to draw (1,234, the limit is 1,000). Aggregate in the query.");
  assert.equal(R.sentence({ code: "chart.too_many_rows", details: { rows: 100001, limit: 100000 } }), "Too many rows to draw (100,001, the limit is 100,000). Aggregate in the query.");
  assert.equal(R.sentence({ code: "chart.too_many_series", details: { series: 9, limit: 8 } }), "Too many series to draw (9, the limit is 8). Group the smaller ones in the query.");
  assert.equal(R.sentence({ code: "chart.rule.negative-part", details: {} }), "This chart cannot show negative values.");
  assert.throws(() => R.sentence({ code: "chart.nope", details: {} }), /no sentence for chart\.nope/);
});

test("the input is closed: an unknown member, an unknown type or a wrong frame throws", () => {
  const base = { type: "bar", document: doc({ x: X, y: Y }), columns: { k: ["a"], v: [1] }, types: {}, frame: FRAME, theme: "light", measure: null, hidden: [] };
  assert.throws(() => R.render(Object.assign({}, base, { extra: 1 })), /unknown input member extra/);
  assert.throws(() => R.render(Object.assign({}, base, { type: "no_such_type" })), /no type no_such_type/);
  assert.throws(() => R.render(Object.assign({}, base, { frame: { w: Infinity, h: 1 } })), /frame/);
  assert.throws(() => R.render(Object.assign({}, base, { frame: { w: -1, h: 1 } })), /frame/);
  assert.throws(() => R.render(Object.assign({}, base, { hidden: [1] })), /hidden id/);
  assert.throws(() => R.render(Object.assign({}, base, { document: doc({ x: X, y: Y }, { type: "line" }) })), /mark\.type is not bar/);
});

test("the registry: types() lists bar, line, area and scatter with their descriptors; a second registration or a wrong member throws", () => {
  same(R.types().map((t) => t.name), ["bar", "line", "area", "scatter", "pie", "heatmap", "box"]);
  const keys = { box: ["x", "y"], bar: ["x", "y", "color", "tooltip"], line: ["x", "y", "color", "tooltip"], area: ["x", "y", "color", "tooltip"], scatter: ["x", "y", "color", "size", "tooltip"], pie: ["theta", "color"], heatmap: ["x", "y", "color"] };
  for (const t of R.types()) same(t.channels.map((c) => c.key), keys[t.name]);
  const ok = { channels: [], model: () => null, marks: () => [], axes: "cartesian" };
  assert.throws(() => R.type("bar", ok), /already registered/);
  assert.throws(() => R.type("x1", Object.assign({}, ok, { extra: 1 })), /unknown member extra/);
  assert.throws(() => R.type("x2", { channels: [], model: () => null, marks: () => [] }), /missing member axes/);
  assert.throws(() => R.type("Bad", ok), /lower-case/);
});

test("G10: a constructor given an unknown field, or missing a required one, throws naming the field", () => {
  const P = R.prim;
  assert.throws(() => P.rect({ role: "mark", x: 0, y: 0, w: 1, h: 1, colour: "red" }), /rect: unknown field colour/);
  assert.throws(() => P.rect({ role: "mark", x: 0, y: 0, w: 1 }), /rect: missing field h/);
  assert.throws(() => P.text({ role: "tick", x: 0, y: 0, text: "a", size: 12, fill: "--chart-ink" }), /text: missing field color/);
  assert.throws(() => P.line({ role: "grid", x1: 0, y1: 0, x2: 1, y2: NaN, stroke: "--chart-grid", strokeColor: "#000", strokeWidth: 1 }), /line: field y2 has a value of the wrong kind/);
  assert.throws(() => P.rect([0, 0, 1, 1]), /rect: takes one object of named fields/);
  assert.throws(() => P.rect({ role: "decor", x: 0, y: 0, w: 1, h: 1 }), /rect: unknown role decor/);
  assert.throws(() => P.group([], { role: "mark", n: 2 }, []), /group: field n is counted, never given/);
  assert.equal(P.rect({ role: "mark", x: 0.123, y: 0, w: 1, h: 1 }).x, 0.12, "coordinates leave rounded to two decimals");
});

test("the series id: 'measure' alone, the core's key under a colour channel", () => {
  const out = draw([{ k: "a", c: "North", v: 1 }, { k: "a", c: "South", v: 2 }], { color: { field: "c", type: "nominal" } });
  same(out.summary.series.map((s) => s.id), ["string:North", "string:South"]);
});

test("the legend's place (contract §4.2 lines 1, 2, 3, 5)", () => {
  const two = [{ k: "a", c: "u", v: 1 }, { k: "a", c: "w", v: 2 }];
  const legendOf = (out) => all(out.primitives, "group", "legend");
  // line 1: none at the minimal level and none under legend: null
  assert.equal(legendOf(draw(two, { color: { field: "c", type: "nominal" }, frame: { w: 150, h: 60 } })).length, 0);
  assert.equal(legendOf(draw(two, { color: { field: "c", type: "nominal", legend: null } })).length, 0);
  // line 2: one series whose id is not on the hidden list → none; listed → drawn (so it can be switched back on)
  const one = [{ k: "a", c: "u", v: 1 }];
  assert.equal(legendOf(draw(one, { color: { field: "c", type: "nominal" } })).length, 0);
  assert.equal(legendOf(draw(one, { color: { field: "c", type: "nominal" }, hidden: ["string:u"] })).length, 1);
  // line 3: the author's orient; line 5: otherwise at the top
  const right = draw(two, { color: { field: "c", type: "nominal", legend: { orient: "right" } }, frame: { w: 960, h: 540 } });
  const top = draw(two, { color: { field: "c", type: "nominal" }, frame: { w: 960, h: 540 } });
  const keyX = (out) => all(out.primitives, "rect", "legend")[0].x;
  const keyY = (out) => all(out.primitives, "rect", "legend")[0].y;
  assert.ok(keyX(right) > right.index.plot.x + right.index.plot.w, "right: the key is right of the plot");
  assert.ok(keyY(top) < top.index.plot.y, "top: the key is above the plot");
});

test("the hidden list hides a series only in a draw that draws its legend entry (D2)", () => {
  const two = [{ k: "a", c: "u", v: 1 }, { k: "a", c: "w", v: 2 }];
  const color = { field: "c", type: "nominal" };
  const full = draw(two, { color, hidden: ["string:w"], frame: { w: 960, h: 540 } });
  assert.equal(full.summary.series[1].hidden, true);
  assert.equal(marks(full.primitives).length, 1);
  const minimal = draw(two, { color, hidden: ["string:w"], frame: { w: 150, h: 60 } });
  assert.equal(minimal.level, "minimal");
  assert.equal(minimal.summary.series[1].hidden, false, "no legend at the minimal level: the list hides nothing");
  assert.equal(marks(minimal.primitives).length, 2);
  const unknown = draw(two, { color, hidden: ["string:zzz"], frame: { w: 960, h: 540 } });
  assert.equal(marks(unknown.primitives).length, 2, "an id with no series hides nothing");
});

test("G10 in a template: the bar's named fields reach the constructors (a misspelling throws, never draws)", () => {
  const broken = engine((s) => {
    const a = s["types/bar.js"];
    s["types/bar.js"] = a.replace('P.rect({ role: "mark", x: x, y: y, w: w, h: h,', 'P.rect({ role: "mark", x: x, y: y, width: w, h: h,');
    if (a === s["types/bar.js"]) throw new Error("plant anchor missing");
  });
  assert.throws(() => broken.render({ type: "bar", document: doc({ x: X, y: Y }), columns: { k: ["a"], v: [0] }, types: {}, frame: FRAME, theme: "light", measure: SIX, hidden: [] }), /rect: unknown field width/);
});
