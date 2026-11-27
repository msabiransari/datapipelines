// category-density.test.mjs — the category-axis density refusal (owner 2026-11-24, #1117). A picture whose occupied
// mark's final category-band allocation (after grouping and gaps, before coordinate rounding) is under one CSS pixel
// refuses the whole draw with chart.rule.category-density, naming the displayed axis, the category count and the group
// count; exactly one pixel passes and the same document renders in a larger frame. The decision is shared: every
// band-axis cartesian type writes its own final allocation to hand.density, and R.cartesian.density decides.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine } from "./engine.mjs";
import { plant } from "./forms.mjs";

const R = engine();
const plain = (v) => JSON.parse(JSON.stringify(v));
const same = (a, b) => assert.deepEqual(plain(a), plain(b));

// The engines the falsifications plant into: the same sources with the named decision changed, never the shared table.
const DENSITY_ANCHOR = "var dens = R.cartesian.density(hand);";
const densityOff = () => engine((s) => plant(s, "20-render.js", DENSITY_ANCHOR, "var dens = null;"));
const densityLe = () => engine((s) => plant(s, "12-cartesian.js", 'typeof a[i].width === "number" && a[i].width < 1', 'typeof a[i].width === "number" && a[i].width <= 1'));

// ---------------------------------------------------------------- the shared decision and its sentence

test("the shared decision returns the first allocation strictly below one pixel, x before y", () => {
  const decide = (a) => R.cartesian.density({ density: a });
  assert.equal(decide([]), null, "no allocation, no refusal");
  assert.equal(decide(undefined), null, "a type that wrote none (an excluded type) decides nothing");
  assert.equal(decide([{ axis: "x", categories: 3, groups: 1, width: 1 }]), null, "exactly one pixel passes");
  assert.equal(decide([{ axis: "x", categories: 3, groups: 1, width: 1.0001 }]), null, "above one pixel passes");
  same(decide([{ axis: "x", categories: 3, groups: 1, width: 0.9999 }]), { axis: "x", categories: 3, groups: 1, width: 0.9999 }, "below one pixel fails");
  same(decide([{ axis: "x", categories: 3, groups: 1, width: 0.5 }, { axis: "y", categories: 4, groups: 1, width: 0.4 }]), { axis: "x", categories: 3, groups: 1, width: 0.5 }, "x before y");
  same(decide([{ axis: "x", categories: 3, groups: 1, width: 2 }, { axis: "y", categories: 4, groups: 1, width: 0.4 }]), { axis: "y", categories: 4, groups: 1, width: 0.4 }, "a later failing axis is found");
});

test("the refusal sentence names the axis and counts; the axis string is not number-formatted", () => {
  assert.equal(R.sentence({ code: "chart.rule.category-density", details: { axis: "x", categories: 20000, groups: 8 } }), "Too many categories to draw along the x-axis (20,000 categories, 8 groups). Widen the frame or aggregate in the query.");
  assert.equal(R.sentence({ code: "chart.rule.category-density", details: { axis: "y", categories: 3, groups: 1 } }), "Too many categories to draw along the y-axis (3 categories, 1 group). Widen the frame or aggregate in the query.");
});

// ---------------------------------------------------------------- inputs

/** A vertical (or horizontal) bar: `n` categories × `nSer` series, the first `nonNull` cells non-null, the rest null. */
function barInput(n, nSer, nonNull, w = 960, h = 540, horizontal = false) {
  const total = n * nSer;
  const k = [], v = [], c = [];
  for (let i = 0; i < total; i++) {
    k.push("g" + Math.floor(i / nSer));
    if (nSer > 1) c.push("s" + (i % nSer));
    v.push(i < nonNull ? 1 : null);
  }
  const columns = { k, v };
  const types = { k: "STRING", v: "DOUBLE" };
  const enc = {};
  if (nSer > 1) {
    columns.c = c;
    types.c = "STRING";
    enc.color = { field: "c", type: "nominal" };
  }
  if (horizontal) {
    enc.x = { field: "v", type: "quantitative", aggregate: "sum", title: null };
    enc.y = { field: "k", type: "nominal", title: null };
  } else {
    enc.x = { field: "k", type: "nominal", title: null };
    enc.y = { field: "v", type: "quantitative", aggregate: "sum", title: null };
  }
  return { type: "bar", document: { display_name: "c", config: { mark: { type: "bar" }, encoding: enc } }, columns, types, frame: { w, h }, theme: "light", measure: null, hidden: [] };
}

/** A line or area over `n` nominal categories (a band axis) or quantitative positions (excluded). */
function lineInput(n, area = false, position = false, w = 960, h = 540) {
  const x = position ? Array.from({ length: n }, (_, i) => i) : Array.from({ length: n }, (_, i) => "c" + i);
  const enc = { x: { field: "x", type: position ? "quantitative" : "nominal", sort: null, title: null }, y: { field: "v", type: "quantitative", aggregate: "sum", title: null } };
  return { type: area ? "area" : "line", document: { display_name: "l", config: { mark: { type: area ? "area" : "line" }, encoding: enc } }, columns: { x, v: Array(n).fill(1) }, types: { x: position ? "DOUBLE" : "STRING", v: "DOUBLE" }, frame: { w, h }, theme: "light", measure: null, hidden: [] };
}

/** A combo over `n` categories with `nBar` bar layers. */
function comboInput(n, nBar, w = 960, h = 540) {
  const layers = [];
  const columns = { m: Array.from({ length: n }, (_, i) => "c" + i) };
  const types = { m: "STRING" };
  for (let l = 0; l < nBar; l++) {
    layers.push({ mark: "bar", y: { field: "f" + l, type: "quantitative", aggregate: "sum" } });
    columns["f" + l] = Array(n).fill(2);
    types["f" + l] = "DOUBLE";
  }
  return { type: "combo", document: { display_name: "c", config: { mark: { type: "combo" }, encoding: { x: { field: "m", type: "nominal", sort: null }, layers } } }, columns, types, frame: { w, h }, theme: "light", measure: null, hidden: [] };
}

/** A heatmap over an n × n domain with `nonNull` occupied cells (the first diagonal). */
function heatmapInput(n, nonNull = 1, w = 960, h = 540) {
  const x = Array.from({ length: n }, (_, i) => "x" + i);
  const y = Array.from({ length: n }, (_, i) => "y" + i);
  const v = Array.from({ length: n }, (_, i) => (i < nonNull ? 5 : null));
  return { type: "heatmap", document: { display_name: "h", config: { mark: { type: "heatmap" }, encoding: { x: { field: "x", type: "nominal", sort: null }, y: { field: "y", type: "nominal", sort: null }, color: { field: "v", aggregate: "sum", type: "quantitative", scale: "sequential" } } } }, columns: { x, y, v }, types: { x: "STRING", y: "STRING", v: "DOUBLE" }, frame: { w, h }, theme: "light", measure: null, hidden: [] };
}

/** A box over `n` categories (one value each). */
function boxInput(n, w = 960, h = 540) {
  const c = Array.from({ length: n }, (_, i) => "c" + i);
  const v = Array.from({ length: n }, (_, i) => i + 1);
  return { type: "box", document: { display_name: "b", config: { mark: { type: "box" }, encoding: { x: { field: "c", type: "nominal", sort: null }, y: { field: "v", type: "quantitative" } } } }, columns: { c, v }, types: { c: "STRING", v: "DOUBLE" }, frame: { w, h }, theme: "light", measure: null, hidden: [] };
}

/** A waterfall over `n` steps and a closing total (`total: "end"` adds one bar). */
function waterfallInput(n, total = "end", w = 960, h = 540) {
  const s = Array.from({ length: n }, (_, i) => "s" + i);
  const v = Array(n).fill(1);
  return { type: "waterfall", document: { display_name: "w", config: { mark: { type: "waterfall", total }, encoding: { x: { field: "s", type: "nominal", sort: null }, y: { field: "v", type: "quantitative", aggregate: "sum" } } } }, columns: { s, v }, types: { s: "STRING", v: "INTEGER" }, frame: { w, h }, theme: "light", measure: null, hidden: [] };
}

const refusal = (r) => r.summary.refusal;

// ---------------------------------------------------------------- bar

test("bar: 20,000 categories with one non-null cell refuse at the smallest and a normal frame, no marks", () => {
  for (const [w, h] of [[80, 24], [960, 540]]) {
    const r = R.render(barInput(20000, 1, 1, w, h));
    assert.equal(r.state, "refused", `${w}x${h}`);
    same(refusal(r).details, { axis: "x", categories: 20000, groups: 1 });
    assert.equal(r.summary.marks, 0, `${w}x${h}: no selectable marks`);
    assert.deepEqual([...r.primitives], [], `${w}x${h}: no primitives`);
  }
});

test("bar: a grouped picture reads the final #1130 width, not the band step", () => {
  // 3 categories, 8 series at 80 × 24, default gap: each bar is 0.1 px (the grouped width), the band step is 20 px.
  const r = R.render(barInput(3, 8, 24, 80, 24));
  assert.equal(r.state, "refused");
  same(refusal(r).details, { axis: "x", categories: 3, groups: 8 });
  assert.equal(densityOff().render(barInput(3, 8, 24, 80, 24)).state, "rendered", "density off renders the grouped bars");
});

test("bar: horizontal layout reports the y axis; all-null data is no-data", () => {
  const r = R.render(barInput(20000, 1, 1, 80, 24, true));
  assert.equal(r.state, "refused");
  same(refusal(r).details, { axis: "y", categories: 20000, groups: 1 });
  assert.equal(R.render(barInput(20000, 1, 0, 80, 24)).state, "no-data");
});

test("bar: recovery in a larger frame", () => {
  assert.equal(R.render(barInput(1000, 1, 1000, 960, 540)).state, "refused");
  const roomy = R.render(barInput(1000, 1, 1000, 1920, 540));
  assert.equal(roomy.state, "rendered", "the same 1,000 bars draw in a wider frame");
  assert.equal(roomy.summary.marks, 1000);
});

// ---------------------------------------------------------------- line and area

test("line and area: a nominal band axis refuses under a pixel; a continuous position axis is excluded", () => {
  for (const area of [false, true]) {
    const r = R.render(lineInput(5000, area, false, 960, 540));
    assert.equal(r.state, "refused", area ? "area" : "line");
    same(refusal(r).details, { axis: "x", categories: 5000, groups: 1 });
    const p = R.render(lineInput(5000, area, true, 960, 540));
    assert.equal(p.state, "rendered", (area ? "area" : "line") + " position x renders");
    assert.equal(refusal(p), null, (area ? "area" : "line") + " position x is not refused on density");
  }
});

// ---------------------------------------------------------------- combo

test("combo: 60 categories with three bar layers refuse at a small frame; a wider frame recovers", () => {
  const r = R.render(comboInput(60, 3, 240, 140));
  assert.equal(r.state, "refused");
  same(refusal(r).details, { axis: "x", categories: 60, groups: 3 });
  assert.equal(R.render(comboInput(60, 3, 960, 540)).state, "rendered");
});

// ---------------------------------------------------------------- heatmap

test("heatmap: a sparse 20,000 × 20,000 domain with one cell refuses on x first; y fails independently", () => {
  const r = R.render(heatmapInput(20000, 1, 960, 540));
  assert.equal(r.state, "refused");
  same(refusal(r).details, { axis: "x", categories: 20000, groups: 1 });
  // A wide-but-minimal frame keeps the x cells above a pixel while the y cells stay under one.
  const wide = R.render(heatmapInput(20000, 1, 40000, 24));
  assert.equal(wide.state, "refused");
  same(refusal(wide).details, { axis: "y", categories: 20000, groups: 1 });
});

test("heatmap: an all-null grid is no-data, not a density refusal", () => {
  assert.equal(R.render(heatmapInput(20000, 0, 960, 540)).state, "no-data");
});

// ---------------------------------------------------------------- box and waterfall

test("box and waterfall: box's dense category axis refuses; a roomier frame recovers", () => {
  const b = R.render(boxInput(50, 80, 24));
  assert.equal(b.state, "refused");
  same(refusal(b).details, { axis: "x", categories: 50, groups: 1 });
  assert.equal(R.render(boxInput(50, 960, 540)).state, "rendered");

  // Waterfall's 50-bar cap at the smallest frame (80 × 24) gives each bar 1.01 px — just above the threshold, so it
  // draws: the shared decision reads the actual width, so an allocation that is not below one pixel never refuses.
  const wf = R.render(waterfallInput(49, "end", 80, 24));
  assert.equal(wf.state, "rendered");
  assert.equal(wf.summary.marks, 50);
  assert.equal(R.render(waterfallInput(49, "end", 960, 540)).state, "rendered");
});

// ---------------------------------------------------------------- higher-priority refusals win

test("the row and mark caps win over density", () => {
  assert.equal(R.render(barInput(20001, 1, 1, 960, 540)).summary.refusal.code, "chart.too_many_rows");
  assert.equal(R.render(barInput(1001, 1, 1001, 960, 540)).summary.refusal.code, "chart.too_many_marks");
});

// ---------------------------------------------------------------- falsifications (the gates)

test("G1: with the shared decision disabled a sparse picture renders where refusal was expected", () => {
  const off = densityOff();
  assert.equal(off.render(barInput(20000, 1, 1, 80, 24)).state, "rendered", "density off renders the sparse bar");
  assert.equal(off.render(heatmapInput(20000, 1, 960, 540)).state, "rendered", "density off renders the sparse heatmap");
});

test("G2: the exact-one control goes red when < becomes <=", () => {
  const hand = { density: [{ axis: "x", categories: 3, groups: 1, width: 1 }] };
  assert.equal(R.cartesian.density(hand), null, "exactly one pixel passes");
  same(densityLe().cartesian.density(hand), { axis: "x", categories: 3, groups: 1, width: 1 }, "<= refuses exactly one pixel");
});

test("G3: the grouped case reads the grouped width, not the raw band step", () => {
  // 3 categories, 8 series at 80 × 24: the per-bar width is 0.1 px, the band step is 20 px. The grouped width refuses;
  // a copy that reads the raw band step would not.
  const raw = engine((s) => plant(s, "types/bar.js", "width: stacked ? groupW : barW }", "width: plot.band.step }"));
  assert.equal(R.render(barInput(3, 8, 24, 80, 24)).state, "refused", "the grouped width is under a pixel");
  assert.equal(raw.render(barInput(3, 8, 24, 80, 24)).state, "rendered", "the raw band step alone would not refuse");
});

test("G4: the heatmap row case is skipped when only x is checked", () => {
  const skipY = engine((s) => plant(s, "types/heatmap.js", '{ axis: "y", categories: hand.rows.n, groups: 1, width: hand.rows.step - 2 * gy },', ""));
  const f = heatmapInput(20000, 1, 40000, 24);
  assert.equal(R.render(f).state, "refused", "the y cells are under a pixel");
  assert.equal(skipY.render(f).state, "rendered", "skipping y hides the row failure");
});
