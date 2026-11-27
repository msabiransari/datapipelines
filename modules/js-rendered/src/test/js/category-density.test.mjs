// category-density.test.mjs — the category-axis density refusal (owner 2026-11-24, #1117). A picture whose occupied
// mark's final category-band allocation (after grouping and gaps, before coordinate rounding) is under one CSS pixel
// refuses the whole draw with chart.rule.category-density, naming the displayed axis, the category count and the group
// count; exactly one pixel passes and the same document renders in a larger frame. The decision is shared: every
// band-axis cartesian type writes its own final allocation to hand.density, and R.cartesian.density decides.
//
// One registry-derived inventory (INVENTORY) keys every registered type name and states, for each, its density
// applicability — which axis and encoding, or why it is excluded. The key set is asserted equal to R.types(), every
// entry is driven through a real render (an applicable type refuses with the right details, an excluded type does not
// refuse on density), and the exact < 1 boundary is rendered just below, exactly at and just above one pixel for every
// applicable allocation, with visibility, grouping, the 60-category matrix, cap controls and the gate falsifications.
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
function barInput(n, nSer, nonNull, w = 960, h = 540, { horizontal = false, stack = false, tokens = {}, hidden = [] } = {}) {
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
  if (stack) enc.y.stack = "zero";
  return { type: "bar", document: { display_name: "c", config: { mark: { type: "bar" }, encoding: enc } }, columns, types, frame: { w, h }, theme: { base: "light", tokens }, measure: null, hidden };
}

/** A line or area over `n` nominal categories (a band axis) or quantitative positions (excluded). */
function lineInput(n, area = false, position = false, w = 960, h = 540) {
  const x = position ? Array.from({ length: n }, (_, i) => i) : Array.from({ length: n }, (_, i) => "c" + i);
  const enc = { x: { field: "x", type: position ? "quantitative" : "nominal", sort: null, title: null }, y: { field: "v", type: "quantitative", aggregate: "sum", title: null } };
  return { type: area ? "area" : "line", document: { display_name: "l", config: { mark: { type: area ? "area" : "line" }, encoding: enc } }, columns: { x, v: Array(n).fill(1) }, types: { x: position ? "DOUBLE" : "STRING", v: "DOUBLE" }, frame: { w, h }, theme: { base: "light", tokens: {} }, measure: null, hidden: [] };
}

/** A combo over `n` categories with `nBar` bar layers and, when `nLine` is given, a trailing line layer. */
function comboInput(n, nBar, w = 960, h = 540, { tokens = {}, hidden = [], line = false } = {}) {
  const layers = [];
  const columns = { m: Array.from({ length: n }, (_, i) => "c" + i) };
  const types = { m: "STRING" };
  for (let l = 0; l < nBar; l++) {
    layers.push({ mark: "bar", y: { field: "f" + l, type: "quantitative", aggregate: "sum" } });
    columns["f" + l] = Array(n).fill(2);
    types["f" + l] = "DOUBLE";
  }
  if (line) {
    layers.push({ mark: "line", y: { field: "f" + nBar, type: "quantitative", aggregate: "sum" } });
    columns["f" + nBar] = Array(n).fill(3);
    types["f" + nBar] = "DOUBLE";
  }
  return { type: "combo", document: { display_name: "c", config: { mark: { type: "combo" }, encoding: { x: { field: "m", type: "nominal", sort: null }, layers } } }, columns, types, frame: { w, h }, theme: { base: "light", tokens }, measure: null, hidden };
}

/** A heatmap over an n × n domain with `nonNull` occupied cells (the first diagonal). */
function heatmapInput(n, nonNull = 1, w = 960, h = 540, tokens = {}) {
  const x = Array.from({ length: n }, (_, i) => "x" + i);
  const y = Array.from({ length: n }, (_, i) => "y" + i);
  const v = Array.from({ length: n }, (_, i) => (i < nonNull ? 5 : null));
  return { type: "heatmap", document: { display_name: "h", config: { mark: { type: "heatmap" }, encoding: { x: { field: "x", type: "nominal", sort: null }, y: { field: "y", type: "nominal", sort: null }, color: { field: "v", aggregate: "sum", type: "quantitative", scale: "sequential" } } } }, columns: { x, y, v }, types: { x: "STRING", y: "STRING", v: "DOUBLE" }, frame: { w, h }, theme: { base: "light", tokens }, measure: null, hidden: [] };
}

/** A box over `n` categories (one value each). */
function boxInput(n, w = 960, h = 540) {
  const c = Array.from({ length: n }, (_, i) => "c" + i);
  const v = Array.from({ length: n }, (_, i) => i + 1);
  return { type: "box", document: { display_name: "b", config: { mark: { type: "box" }, encoding: { x: { field: "c", type: "nominal", sort: null }, y: { field: "v", type: "quantitative" } } } }, columns: { c, v }, types: { c: "STRING", v: "DOUBLE" }, frame: { w, h }, theme: { base: "light", tokens: {} }, measure: null, hidden: [] };
}

/** A waterfall over `n` steps and a closing total (`total: "end"` adds one bar). */
function waterfallInput(n, total = "end", w = 960, h = 540) {
  const s = Array.from({ length: n }, (_, i) => "s" + i);
  const v = Array(n).fill(1);
  return { type: "waterfall", document: { display_name: "w", config: { mark: { type: "waterfall", total }, encoding: { x: { field: "s", type: "nominal", sort: null }, y: { field: "v", type: "quantitative", aggregate: "sum" } } } }, columns: { s, v }, types: { s: "STRING", v: "INTEGER" }, frame: { w, h }, theme: { base: "light", tokens: {} }, measure: null, hidden: [] };
}

/** The dense inputs of the excluded types: as many categories as the type admits, none of which is a category band. */
function excludedInput(type) {
  if (type === "scatter") {
    const n = 500, x = Array.from({ length: n }, (_, i) => i), y = Array.from({ length: n }, (_, i) => i % 7);
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { x: { field: "x", type: "quantitative" }, y: { field: "y", type: "quantitative" } } } }, columns: { x, y }, types: { x: "DOUBLE", y: "DOUBLE" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "pie") {
    const n = 8, s = Array.from({ length: n }, (_, i) => "s" + i), v = Array(n).fill(5);
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { theta: { field: "v", type: "quantitative", aggregate: "sum" }, color: { field: "s", type: "nominal", sort: null } } } }, columns: { s, v }, types: { s: "STRING", v: "INTEGER" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "histogram") {
    const n = 40, v = Array.from({ length: n }, (_, i) => i);
    return { type, document: { display_name: "x", config: { mark: { type, bins: 40 }, encoding: { x: { field: "v", type: "quantitative" }, y: { aggregate: "count" } } } }, columns: { v }, types: { v: "DOUBLE" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "funnel") {
    const n = 12, s = Array.from({ length: n }, (_, i) => "s" + i), v = Array.from({ length: n }, (_, i) => i + 1);
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { stage: { field: "s", type: "nominal", sort: null }, value: { field: "v", type: "quantitative", aggregate: "sum" } } } }, columns: { s, v }, types: { s: "STRING", v: "INTEGER" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "gauge") {
    const v = [42];
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { value: { field: "v", type: "quantitative", aggregate: "sum", scale: { domain: [0, 100] } } } } }, columns: { v }, types: { v: "INTEGER" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "sparkline") {
    const n = 500, x = Array.from({ length: n }, (_, i) => "c" + i), v = Array.from({ length: n }, (_, i) => i % 9);
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { x: { field: "x", type: "nominal", sort: null }, y: { field: "v", type: "quantitative", aggregate: "sum" } } } }, columns: { x, v }, types: { x: "STRING", v: "DOUBLE" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "radar") {
    const n = 12, a = Array.from({ length: n }, (_, i) => "a" + i), v = Array.from({ length: n }, (_, i) => i + 1);
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { theta: { field: "a", type: "nominal", sort: null }, r: { field: "v", type: "quantitative", aggregate: "sum" } } } }, columns: { a, v }, types: { a: "STRING", v: "INTEGER" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  if (type === "treemap") {
    const n = 200, a = Array.from({ length: n }, (_, i) => "a" + (i % 8)), b = Array.from({ length: n }, (_, i) => "b" + i), v = Array.from({ length: n }, (_, i) => i + 1);
    return { type, document: { display_name: "x", config: { mark: { type }, encoding: { path: [{ field: "a" }, { field: "b" }], size: { field: "v", aggregate: "sum", type: "quantitative" } } } }, columns: { a, b, v }, types: { a: "STRING", b: "STRING", v: "INTEGER" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] };
  }
  throw new Error("no dense input for " + type);
}

/** The whole refusal shape the brief pins: refused, the code, the counts, zero marks, no primitives, no selectable index. */
function assertRefusal(r, axis, categories, groups) {
  assert.equal(r.state, "refused");
  assert.equal(r.summary.refusal.code, "chart.rule.category-density");
  same(r.summary.refusal.details, { axis, categories, groups });
  assert.equal(r.summary.marks, 0, "no marks are kept on refusal");
  assert.deepEqual([...r.primitives], [], "no primitives are kept on refusal");
  same(r.index.series, [], "no selectable index entry on refusal");
}

// ---------------------------------------------------------------- the registry inventory

/** One entry per registered type, in R.types() order: how the type allocates a category band, or why it is excluded. */
const INVENTORY = {
  bar: { applicable: true, axis: "x or y", note: "grouped/stacked category band" },
  line: { applicable: true, axis: "x (nominal band only)", note: "a continuous position x is excluded" },
  area: { applicable: true, axis: "x (nominal band only)", note: "a continuous position x is excluded" },
  scatter: { applicable: false, why: "continuous x and y positions, no category axis" },
  pie: { applicable: false, why: "sector layout, no cartesian category axis" },
  combo: { applicable: true, axis: "x (band)", note: "bar layers split the band; a non-bar layer uses the whole slot" },
  heatmap: { applicable: true, axis: "x and y", note: "each axis checked independently" },
  histogram: { applicable: false, why: "numeric bins, no category axis" },
  box: { applicable: true, axis: "x", note: "category band with box statistics" },
  waterfall: { applicable: true, axis: "x", note: "each step and shown total" },
  funnel: { applicable: false, why: "stage layout, no cartesian category axis" },
  gauge: { applicable: false, why: "gauge layout, no cartesian category axis" },
  sparkline: { applicable: false, why: "axis-free point span" },
  radar: { applicable: false, why: "theta layout, no cartesian category axis" },
  treemap: { applicable: false, why: "hierarchy layout, no cartesian category axis" },
};

test("the inventory is one table keyed by every registered type, in registry order", () => {
  same(Object.keys(INVENTORY), R.types().map((t) => t.name));
  for (const entry of Object.values(INVENTORY)) {
    if (entry.applicable) {
      assert.equal(typeof entry.axis, "string", "an applicable entry names its axis");
    } else {
      assert.equal(typeof entry.why, "string", "an excluded entry says why");
    }
  }
});

test("the inventory key-set is red for a stand-in type, green restored (the gate)", () => {
  const E = engine();
  E.type("stand_in", { channels: [], model: () => null, marks: () => [], axes: "cartesian" });
  assert.throws(() => same(Object.keys(INVENTORY), E.types().map((t) => t.name)), "a stand-in type breaks the key set");
  same(Object.keys(INVENTORY), R.types().map((t) => t.name), "the real registry still matches");
});

test("every inventory entry is driven through a real render", () => {
  // An applicable type: a dense band input refuses with the family's own counts. An excluded type: a dense input
  // (as many categories as the type admits) never refuses on density.
  const applicable = {
    bar: () => barInput(20000, 1, 1, 80, 24),
    line: () => lineInput(5000, false, false, 960, 540),
    area: () => lineInput(5000, true, false, 960, 540),
    combo: () => comboInput(60, 3, 240, 140),
    heatmap: () => heatmapInput(20000, 1, 960, 540),
    box: () => boxInput(50, 80, 24),
    waterfall: () => waterfallInput(49, "end", 80, 24),
  };
  const expect = {
    bar: { axis: "x", categories: 20000, groups: 1 },
    line: { axis: "x", categories: 5000, groups: 1 },
    area: { axis: "x", categories: 5000, groups: 1 },
    combo: { axis: "x", categories: 60, groups: 3 },
    heatmap: { axis: "x", categories: 20000, groups: 1 },
    box: { axis: "x", categories: 50, groups: 1 },
    // waterfall cannot cross one pixel (see the boundary test): it renders, never refuses on density.
    waterfall: null,
  };
  for (const [type, entry] of Object.entries(INVENTORY)) {
    if (entry.applicable) {
      const r = R.render(applicable[type]());
      if (expect[type] === null) {
        assert.equal(r.state, "rendered", type + " renders (the cap and floor keep it above one pixel)");
        assert.equal(r.summary.refusal, null, type);
      } else {
        assertRefusal(r, expect[type].axis, expect[type].categories, expect[type].groups);
      }
    } else {
      const r = R.render(excludedInput(type));
      assert.equal(r.state, "rendered", type + " renders its dense input");
      assert.equal(r.summary.refusal, null, type + " never refuses on density");
    }
  }
  // Continuous-x line and area are the explicit continuous exclusions: dense position inputs render, not refused.
  for (const area of [false, true]) {
    const r = R.render(lineInput(5000, area, true, 960, 540));
    assert.equal(r.state, "rendered", (area ? "area" : "line") + " continuous x renders");
    assert.equal(r.summary.refusal, null, (area ? "area" : "line") + " continuous x is not refused on density");
  }
});

// ---------------------------------------------------------------- the boundary, rendered

test("grouped bar: just below, exactly at and just above one pixel (112 × 24, plot width 100)", () => {
  assertRefusal(R.render(barInput(75, 1, 75, 112, 24)), "x", 75, 1);
  assert.equal(R.render(barInput(74, 1, 74, 112, 24)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(barInput(73, 1, 73, 112, 24)).state, "rendered", "above one pixel passes");
});

test("stacked bar: just below, exactly at and just above one pixel; groups is 1", () => {
  assertRefusal(R.render(barInput(71, 3, 213, 112, 24, { stack: true })), "x", 71, 1);
  assert.equal(R.render(barInput(70, 3, 210, 112, 24, { stack: true })).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(barInput(69, 3, 207, 112, 24, { stack: true })).state, "rendered", "above one pixel passes");
});

test("line: just below, exactly at and just above one pixel (80 × 24, plot width 68)", () => {
  assertRefusal(R.render(lineInput(69, false, false, 80, 24)), "x", 69, 1);
  assert.equal(R.render(lineInput(68, false, false, 80, 24)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(lineInput(67, false, false, 80, 24)).state, "rendered", "above one pixel passes");
});

test("area: just below, exactly at and just above one pixel (80 × 24, plot width 68)", () => {
  assertRefusal(R.render(lineInput(69, true, false, 80, 24)), "x", 69, 1);
  assert.equal(R.render(lineInput(68, true, false, 80, 24)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(lineInput(67, true, false, 80, 24)).state, "rendered", "above one pixel passes");
});

test("combo bar layer: just below, exactly at and just above one pixel (112 × 24, one bar layer)", () => {
  assertRefusal(R.render(comboInput(75, 1, 112, 24)), "x", 75, 1);
  assert.equal(R.render(comboInput(74, 1, 112, 24)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(comboInput(73, 1, 112, 24)).state, "rendered", "above one pixel passes");
});

test("combo non-bar slot: just below, exactly at and just above one pixel (80 × 24, one line layer)", () => {
  assertRefusal(R.render(comboInput(69, 0, 80, 24, { line: true })), "x", 69, 1);
  assert.equal(R.render(comboInput(68, 0, 80, 24, { line: true })).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(comboInput(67, 0, 80, 24, { line: true })).state, "rendered", "above one pixel passes");
});

test("heatmap x: just below, exactly at and just above one pixel (80 × 200, gap 0, x before y)", () => {
  const gap0 = { "--chart-gap": 0 };
  assertRefusal(R.render(heatmapInput(69, 1, 80, 200, gap0)), "x", 69, 1);
  assert.equal(R.render(heatmapInput(68, 1, 80, 200, gap0)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(heatmapInput(67, 1, 80, 200, gap0)).state, "rendered", "above one pixel passes");
});

test("heatmap y: just below, exactly at and just above one pixel (200 × 24, gap 0, x is roomy)", () => {
  const gap0 = { "--chart-gap": 0 };
  assertRefusal(R.render(heatmapInput(13, 1, 200, 24, gap0)), "y", 13, 1);
  assert.equal(R.render(heatmapInput(12, 1, 200, 24, gap0)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(heatmapInput(11, 1, 200, 24, gap0)).state, "rendered", "above one pixel passes");
});

test("box: just below, exactly at and just above one pixel (80 × 24, plot width 68)", () => {
  assertRefusal(R.render(boxInput(35, 80, 24)), "x", 35, 1);
  assert.equal(R.render(boxInput(34, 80, 24)).state, "rendered", "exactly one pixel passes");
  assert.equal(R.render(boxInput(33, 80, 24)).state, "rendered", "above one pixel passes");
});

test("waterfall: the 50-bar cap and the minimal floor keep every allocation above one pixel", () => {
  // 49 steps plus the closing total is the 50-bar cap; at the minimal frame each bar is 1.0064 px, so it draws. No
  // accepted input can push a waterfall under one pixel: the cap and the floor prevent it, so the below/exact values
  // stay on the shared decision's unit test above and the rendered "above" is pinned here.
  const r = R.render(waterfallInput(49, "end", 80, 24));
  assert.equal(r.state, "rendered");
  assert.equal(r.summary.marks, 50);
});

test("the boundary is rendered, not fabricated: the exact-one controls go red when < becomes <=", () => {
  const le = densityLe();
  // exactly one pixel: renders on the real engine, refuses on a copy that treats one pixel as too small.
  for (const mk of [() => barInput(74, 1, 74, 112, 24), () => lineInput(68, false, false, 80, 24)]) {
    assert.equal(R.render(mk()).state, "rendered");
    assert.equal(le.render(mk()).state, "refused", "<= refuses exactly one pixel");
    assert.equal(le.render(mk()).summary.refusal.code, "chart.rule.category-density");
  }
});

// ---------------------------------------------------------------- visibility and grouping

test("grouped bar: hiding series changes a refused picture to rendered, and changes groups while still refused", () => {
  const base = () => barInput(125, 8, 1000, 640, 360);
  // All eight series visible: each bar is 0 px, refused with eight groups.
  const all = R.render(base());
  assertRefusal(all, "x", 125, 8);
  // Hide one series: the group still refuses (width 0), but now reports seven groups.
  const one = R.render(barInput(125, 8, 1000, 640, 360, { hidden: ["string:s1"] }));
  assertRefusal(one, "x", 125, 7);
  assert.equal(one.summary.series.find((s) => s.id === "string:s1").hidden, true, "the hidden series is really absent");
  // Hide seven: one visible series keeps the whole group width (3.54 px) and renders.
  const many = R.render(barInput(125, 8, 1000, 640, 360, { hidden: ["string:s0", "string:s1", "string:s2", "string:s3", "string:s4", "string:s5", "string:s6"] }));
  assert.equal(many.state, "rendered", "one visible series renders");
  assert.equal(many.summary.marks, 125);
});

test("combo: hiding a bar layer changes groups while still refused, and hiding two renders", () => {
  const mk = (hidden) => comboInput(125, 3, 640, 360, { hidden });
  const all = R.render(mk([]));
  assertRefusal(all, "x", 125, 3);
  const one = R.render(mk(["layer:0"]));
  assertRefusal(one, "x", 125, 2);
  assert.equal(one.summary.series.find((s) => s.id === "layer:0").hidden, true, "the hidden layer is really absent");
  const two = R.render(mk(["layer:0", "layer:1"]));
  assert.equal(two.state, "rendered", "one visible layer renders");
  assert.equal(two.summary.marks, 125);
});

test("stacked bar reports groups 1 on refusal", () => {
  assertRefusal(R.render(barInput(71, 3, 213, 112, 24, { stack: true })), "x", 71, 1);
});

// ---------------------------------------------------------------- the matrix

test("the 60-category three-bar-layer matrix across gaps and widths", () => {
  const cells = {
    0: { 240: "refused", 400: "rendered", 640: "rendered", 960: "rendered", 1600: "rendered" },
    2: { 240: "refused", 400: "refused", 640: "rendered", 960: "rendered", 1600: "rendered" },
    8: { 240: "refused", 400: "refused", 640: "refused", 960: "refused", 1600: "rendered" },
  };
  for (const gap of [0, 2, 8]) {
    for (const w of [240, 400, 640, 960, 1600]) {
      const r = R.render(comboInput(60, 3, w, 140, { tokens: { "--chart-gap": gap } }));
      const expect = cells[gap][w];
      if (expect === "refused") assertRefusal(r, "x", 60, 3);
      else assert.equal(r.state, "rendered", `gap ${gap} at ${w} renders`);
    }
  }
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
