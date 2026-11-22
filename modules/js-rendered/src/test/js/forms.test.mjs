// Brief 1051 A.1, A.2, A.4, A.6 (D2 to D4, D6): two continuous axes (test_points), two category axes (test_grid, the
// layout part's `rows`), marks over intervals (test_bins, `x.edges`), and the refusals of a wrong layout part. Every
// expected number is the test's own: a circle's centre from the axis's lo and hi and the row's value (a time from the
// date text, by Date.UTC), a bin's sides from the edges. G2, G4 and G7 are falsified here: each check run over a planted
// copy of the engine must fail, naming what it checks.
import { test } from "node:test";
import assert from "node:assert/strict";
import { testTypeKey, typeFixtures, typeInputOf } from "./engine.mjs";
import { violations, meet, textBox, WIDTHS, HEIGHTS } from "./geometry.mjs";
import { formsEngine, plant, fixture, draw, plotRect, leftLabels, underLabels, shownText, near, SIX } from "./forms.mjs";

const R = formsEngine();
/** emit.mjs's seven frames. */
const FRAMES = [[960, 540], [480, 360], [300, 180], [120, 40], [60, 20], [360, 240], [200, 140]];
const SWEEP = WIDTHS.flatMap((w) => HEIGHTS.map((h) => [w, h]));

function clamp(v, a, b) {
  return Math.min(Math.max(v, a), b);
}

// ---- A.1 two continuous axes ----

/** A position on an axis {lo, hi} of kind linear, time or log, from its low end at p0 to its high end at p1. */
function at(kind, lo, hi, v, p0, p1) {
  const t = kind === "log" ? (Math.log10(v) - Math.log10(lo)) / (Math.log10(hi) - Math.log10(lo)) : (v - lo) / (hi - lo);
  return p0 + t * (p1 - p0);
}

/** A row's x as the test reads it: a number, or a date text "YYYY-MM-DD" in seconds (Date.UTC; the engine never uses it). */
function xOf(kind, v) {
  if (kind !== "time") return v;
  const [y, m, d] = v.split("-").map(Number);
  return Date.UTC(y, m - 1, d) / 1000;
}

/** A.1's checks over one picture; [] when they hold. */
function pointsProblems(fx, w, h, E = R) {
  const { r, hand } = draw(E, fx, w, h), out = [];
  if (r.state !== "rendered") return out;
  const ex = fx.document.config.encoding.x, kind = ex.kind || "linear", logY = fx.document.config.encoding.y.log === true;
  const X = fx.columns[ex.field], Y = fx.columns[fx.document.config.encoding.y.field], plot = hand.plot;
  for (const c of r.primitives.filter((p) => p.k === "circle" && p.role === "mark")) {
    const x = xOf(kind, X[c.datum]), y = Y[c.datum];
    const cx = clamp(at(kind, hand.x.lo, hand.x.hi, x, plot.x, plot.x + plot.w), plot.x, plot.x + plot.w);
    const cy = clamp(at(logY ? "log" : "linear", hand.value.lo, hand.value.hi, y, plot.y + plot.h, plot.y), plot.y, plot.y + plot.h);
    if (!near(c.cx, cx, 0.006) || !near(c.cy, cy, 0.006)) out.push(`row ${c.datum} at ${c.cx},${c.cy}, the test's scale ${cx},${cy} (${w}x${h})`);
  }
  // A label is what one position writes: an x label with its second line (a time's year under its month) is one.
  const columns = new Map();
  for (const t of underLabels(r, plot).filter((u) => shownText(u) !== "").sort((a, b) => a.y - b.y)) {
    const key = t.x.toFixed(2);
    columns.set(key, (columns.has(key) ? columns.get(key) + "\n" : "") + t.text);
  }
  const lefts = leftLabels(r, plot).filter((t) => shownText(t) !== "");
  const xs = [...columns].map(([x, text]) => ({ at: Number(x), text }));
  for (const [name, labels] of [["value", lefts.map((t) => ({ at: -t.y, text: t.text }))], ["x", xs]]) {
    const texts = labels.map((l) => l.text);
    if (new Set(texts).size !== texts.length) out.push(`${name} tick texts repeat: ${texts} (${w}x${h})`);
    const sorted = labels.slice().sort((a, b) => a.at - b.at);
    for (let i = 1; i < sorted.length; i++) if (!(sorted[i].at > sorted[i - 1].at)) out.push(`${name} ticks at one position (${w}x${h})`);
  }
  for (const t of leftLabels(r, plot).concat(underLabels(r, plot))) if (t.shown !== null && t.shown !== "") out.push(`tick ${JSON.stringify(t.text)} cut to ${JSON.stringify(t.shown)} (${w}x${h})`);
  return out;
}

test("A.1: every circle stands at the test's own scale of its row (linear, log, time), the tick labels distinct, increasing, whole", () => {
  let circles = 0;
  const kinds = new Set();
  for (const f of typeFixtures("test_points")) {
    const bad = [];
    for (const [w, h] of SWEEP) bad.push(...pointsProblems(f.fixture, w, h));
    assert.deepEqual(bad.slice(0, 8), [], f.name + ": " + bad.length + " problems");
    const r = draw(R, f.fixture, 960, 540).r;
    circles += r.primitives.filter((p) => p.k === "circle").length;
    if (r.state === "rendered") kinds.add(f.fixture.document.config.encoding.x.kind + (f.fixture.document.config.encoding.y.log ? "/log" : ""));
  }
  assert.ok(circles >= 100, "circles at 960 x 540: " + circles);
  for (const k of ["linear", "log/log", "time"]) assert.ok(kinds.has(k), "a rendered fixture of x kind " + k + ": " + [...kinds]);
});

// ---- A.2 two category axes ----

/** A.2's checks over one picture of a test_grid fixture; [] when they hold. */
function gridProblems(fx, w, h, E = R) {
  const { r, hand } = draw(E, fx, w, h), out = [];
  if (r.state !== "rendered") return out;
  const plot = hand.plot, e = fx.document.config.encoding;
  const rowNames = [...new Set(fx.columns[e.y.field].filter((v, i) => v !== null && fx.columns[e.x.field][i] !== null && (!e.color || fx.columns[e.color.field][i] !== null)).map(String))];
  if (!near(hand.rows.step * hand.rows.n, plot.h, 0.02)) out.push(`rows: ${hand.rows.step} x ${hand.rows.n} is not the plot's height ${plot.h}`);
  if (!near(hand.band.step * hand.band.n, plot.w, 0.02)) out.push(`band: ${hand.band.step} x ${hand.band.n} is not the plot's width ${plot.w}`);
  if (hand.rows.n !== rowNames.length) out.push(`rows.n ${hand.rows.n}, the data's rows ${rowNames.length}`);
  if (hand.value !== null) out.push("a value axis was handed to the template");
  for (const p of r.primitives.filter((q) => q.role === "mark" && q.k === "rect")) {
    if (p.x < plot.x - 0.01 || p.y < plot.y - 0.01 || p.x + p.w > plot.x + plot.w + 0.01 || p.y + p.h > plot.y + plot.h + 0.01) out.push(`cell of row ${p.datum} outside the plot`);
  }
  for (const p of r.primitives) if (p.k === "line" && (p.role === "grid" || p.role === "axis")) out.push(`a ${p.role} line at ${p.x1},${p.y1} ${p.x2},${p.y2} (${w}x${h})`);
  if (r.level !== "minimal") {
    const names = leftLabels(r, plot).filter((t) => shownText(t) !== "").sort((a, b) => a.y - b.y);
    const order = names.map((t) => rowNames.indexOf(t.text));
    if (order.some((i) => i < 0)) out.push("a name that is no row: " + names.map((t) => t.text));
    for (let i = 1; i < order.length; i++) if (!(order[i] > order[i - 1])) out.push(`row names out of order from the top: ${names.map((t) => t.text)} (${w}x${h})`);
    if (order.includes(0) && order[0] !== 0) out.push(`topmost name ${JSON.stringify(names[0].text)}, the first row ${JSON.stringify(rowNames[0])} (${w}x${h})`);
  }
  return out;
}

test("A.2: rows down y from the top, bands across, cells inside, no grid or zero line, no value axis (every fixture, every frame)", () => {
  let pictures = 0;
  for (const f of typeFixtures("test_grid")) {
    const bad = [];
    for (const [w, h] of SWEEP) {
      bad.push(...gridProblems(f.fixture, w, h));
      pictures++;
    }
    assert.deepEqual(bad.slice(0, 8), [], f.name + ": " + bad.length + " problems");
  }
  assert.ok(pictures >= 7 * 175, "pictures: " + pictures);
});

test("A.2: a name longer than the column is cut and keeps its full text; extent is never called, at any level", () => {
  const fx = fixture("test_grid", "long-names");
  const { r, hand } = draw(R, fx, 960, 540);
  const cut = leftLabels(r, hand.plot).filter((t) => t.shown !== null && t.shown !== "" && t.shown !== t.text);
  assert.ok(cut.length >= 1, "a cut row name");
  const Y = fx.columns[fx.document.config.encoding.y.field];
  for (const t of cut) assert.ok(Y.map(String).includes(t.text), "its full text is a row's: " + t.text);
  assert.ok(Y.some((v) => String(v).includes("<") && String(v).includes("&")), "a row name with < and &");
  // test_grid's extent throws: every level and every fixture renders.
  for (const f of typeFixtures("test_grid")) for (const [w, h] of FRAMES) assert.doesNotThrow(() => R.render(typeInputOf(f.fixture, w, h)), f.name);
});

test("A.2: a legend at the top or the bottom leaves the plot at least one row name high, or is not drawn", () => {
  let drawn = 0, left = 0;
  for (const name of ["legend-top", "legend-bottom-titles"]) {
    const fx = fixture("test_grid", name);
    for (const [w, h] of SWEEP) {
      const { r, hand } = draw(R, fx, w, h);
      if (r.state !== "rendered" || r.level === "minimal") continue;
      const legend = r.primitives.some((p) => p.role === "legend" && p.k === "group");
      if (!legend) {
        left++;
        continue;
      }
      drawn++;
      assert.ok(hand.plot.h >= (1.069 + 0.293) * 12 - 0.01, `${name} ${w}x${h}: plot ${hand.plot.h} high under a legend`);
    }
  }
  assert.ok(drawn >= 100, "pictures with the legend drawn: " + drawn);
  console.log(`A.2 legend room: ${drawn} pictures with the legend, ${left} without`);
});

// ---- A.4 intervals ----

/** The edges a test_bins fixture names, or the ones it asks the template to compute (the test's own nice step). */
function edgesOf(ex, vals) {
  if (ex.edges) return ex.edges;
  const lo = Math.min(...vals), hi = Math.max(...vals), raw = (hi - lo) / (ex.bins || 10), p = 10 ** Math.floor(Math.log10(raw)), f = raw / p;
  const step = (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p, out = [];
  for (let k = Math.floor(lo / step); out.length < 2 || out[out.length - 1] < hi; k++) out.push(Math.round(k * step * 1e6) / 1e6);
  return out;
}

function binsCheck(r, hand, fx, w, h) {
  const out = [], ex = fx.document.config.encoding.x, edges = edgesOf(ex, fx.columns[ex.field]), plot = hand.plot;
  if (hand.x.lo !== edges[0] || hand.x.hi !== edges[edges.length - 1]) out.push(`x runs ${hand.x.lo}..${hand.x.hi}, the edges ${edges[0]}..${edges[edges.length - 1]}`);
  const texts = R.text.ticks(edges, ex.format || null);
  for (const t of underLabels(r, plot)) {
    if (shownText(t) === "") continue;
    const i = texts.indexOf(t.text);
    if (i < 0) out.push(`label ${JSON.stringify(t.text)} is no edge (${w}x${h})`);
    else if (!near(t.x, plot.x + ((edges[i] - edges[0]) / (edges[edges.length - 1] - edges[0])) * plot.w, 0.02)) out.push(`label ${t.text} at ${t.x}, not under its edge (${w}x${h})`);
  }
  for (const p of r.primitives.filter((q) => q.role === "mark" && q.k === "rect")) {
    const x0 = plot.x + ((edges[p.datum] - edges[0]) / (edges[edges.length - 1] - edges[0])) * plot.w;
    const x1 = plot.x + ((edges[p.datum + 1] - edges[0]) / (edges[edges.length - 1] - edges[0])) * plot.w;
    if (!near(p.x, x0, 0.02) || !near(p.x + p.w, x1, 0.02)) out.push(`bin ${p.datum} spans ${p.x}..${p.x + p.w}, its edges ${x0}..${x1}`);
  }
  return out;
}

test("A.4: the axis runs from the first edge to the last, every label is an edge's under it, every bin spans its edges", () => {
  let labels = 0;
  for (const f of typeFixtures("test_bins")) {
    const bad = [];
    for (const [w, h] of SWEEP) {
      const { r, hand } = draw(R, f.fixture, w, h);
      if (r.state !== "rendered") continue;
      bad.push(...binsCheck(r, hand, f.fixture, w, h));
      labels += underLabels(r, hand.plot).length;
    }
    assert.deepEqual(bad.slice(0, 8), [], f.name + ": " + bad.length + " problems");
  }
  assert.ok(labels >= 1000, "edge labels checked: " + labels);
});

test("A.4: 50 bins at 240 px leave some edges without a label, and no two labels meet", () => {
  const fx = fixture("test_bins", "fifty-bins");
  for (const h of HEIGHTS) {
    const { r, hand } = draw(R, fx, 240, h);
    if (r.level === "minimal") continue;
    const shown = underLabels(r, hand.plot).filter((t) => shownText(t) !== "");
    assert.ok(shown.length >= 2 && shown.length < 51, `240x${h}: ${shown.length} of 51 edges labelled`);
    const boxes = shown.map(textBox);
    for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) assert.ok(!meet(boxes[i], boxes[j]), `${shown[i].text} meets ${shown[j].text}`);
  }
});

// ---- A.6 the refusals of a wrong layout part ----

const H = () => R.chrome.helpers(R.theme.resolve("light"), R.measure.measurer(SIX));
const BASE = () => ({ vertical: true, bands: [{ text: "a", under: null }], bandTitle: null, valueTitle: null, format: null, domain: null, endLabels: null, log: false, extent: () => [0, 1] });
const ROWS = [{ text: "r", under: null }];
const SECOND = { extent: () => [0, 1], domain: null, format: null, log: false, title: null };
const EDGES = (more) => Object.assign({ kind: "linear", lo: 0, hi: 2, edges: [0, 1, 2], format: null, domain: null, zero: false }, more);
const REFUSALS = [
  ["rows not a list", { rows: "r" }, /rows is a list/],
  ["rows on a horizontal layout", { rows: ROWS, vertical: false }, /rows asks for vertical/],
  ["rows beside x", { rows: ROWS, x: EDGES({ edges: undefined }) }, /rows cannot stand beside x/],
  ["rows beside log", { rows: ROWS, log: true }, /rows cannot stand beside log/],
  ["rows beside second", { rows: ROWS, second: SECOND }, /rows cannot stand beside second/],
  ["rows beside endLabels", { rows: ROWS, endLabels: ["1"] }, /rows cannot stand beside endLabels/],
  ["rows beside right", { rows: ROWS, right: 20 }, /rows cannot stand beside right/],
  ["second on a horizontal layout", { second: SECOND, vertical: false }, /second asks for vertical/],
  ["second beside right", { second: SECOND, right: 20 }, /second cannot stand beside right/],
  ["second without extent", { second: Object.assign({}, SECOND, { extent: null }) }, /second\.extent is a function/],
  ["x.edges of one number", { x: EDGES({ edges: [1] }) }, /x\.edges is a list/],
  ["x.edges not increasing", { x: EDGES({ edges: [0, 2, 1] }) }, /x\.edges is a list/],
  ["x.edges with a NaN", { x: EDGES({ edges: [0, NaN, 2] }) }, /x\.edges is a list/],
  ["x.edges on a log x", { x: EDGES({ kind: "log" }) }, /x\.edges asks for x\.kind linear/],
  ["x.edges beside x.domain", { x: EDGES({ domain: [0, 2] }) }, /x\.edges cannot stand beside x\.domain/],
  ["x.edges beside x.zero", { x: EDGES({ zero: true }) }, /x\.edges cannot stand beside x\.zero/],
];
for (const [what, more, message] of REFUSALS) {
  test(`A.6: ${what} throws, naming the member, at every level`, () => {
    for (const lvl of ["full", "compact", "minimal"]) {
      const L = Object.assign(BASE(), more);
      assert.throws(() => R.cartesian.cartesian(H(), 960, 540, lvl, 0, L, { measure: true }, null), message, lvl);
    }
  });
}

test("A.6: the bare frame refuses a last that is neither null nor a text, naming last", () => {
  const fx = fixture("test_bare", "short-value");
  const m = draw(R, fx, 400, 200).model;
  const lay = (last) => R.frames.get("bare").lay(H(), 400, 200, "full", 0, Object.assign({}, m, { layout: Object.assign({}, m.layout, { last }) }), [], { measure: true }, null);
  assert.throws(() => lay(42), /bare: last is null or a text/);
  assert.doesNotThrow(() => lay(null));
  assert.doesNotThrow(() => lay("42"));
});

test("A.6: the layout parts without the new members are refused by nothing (the existing forms pass the check)", () => {
  for (const lvl of ["full", "compact", "minimal"]) {
    assert.doesNotThrow(() => R.cartesian.cartesian(H(), 960, 540, lvl, 0, BASE(), { measure: true }, null));
    assert.doesNotThrow(() => R.cartesian.cartesian(H(), 960, 540, lvl, 0, Object.assign(BASE(), { vertical: false }), { measure: true }, null));
  }
});

// ---- G2, G4, G7 falsified ----

const CART = "12-cartesian.js";

test("G2: the rows' order reversed is red on the topmost name; a value grid drawn in the form is red on the no-grid check", () => {
  const reversed = formsEngine((s) => plant(s, CART, "names = namesOf(L.rows);", "names = namesOf(L.rows.slice().reverse());"));
  const gridded = formsEngine((s) => plant(s, CART, "    for (var u = 0; u < column.texts.length; u++)", '    g.grid.push(h.rule("grid", "--chart-grid", plot.x, plot.y, plot.x + plot.w, plot.y));\n    for (var u = 0; u < column.texts.length; u++)'));
  const fx = fixture("test_grid", "one-series");
  assert.deepEqual(gridProblems(fx, 960, 540), []);
  assert.ok(gridProblems(fx, 960, 540, reversed).some((p) => /out of order from the top|topmost name/.test(p)), "red on the order");
  assert.ok(gridProblems(fx, 960, 540, gridded).some((p) => /^a grid line/.test(p)), "red on a grid line");
});

test("G4: the ticks taken from the nice rule instead of the edges is red naming a label that is no edge", () => {
  const nice = formsEngine((s) => plant(s, CART, 'if (X.kind === "linear" && has(X.edges)) {', 'if (false && X.kind === "linear" && has(X.edges)) {'));
  const fx = fixture("test_bins", "uneven-edges");
  const clean = draw(R, fx, 960, 540), planted = draw(nice, fx, 960, 540);
  assert.deepEqual(binsCheck(clean.r, clean.hand, fx, 960, 540), []);
  const bad = binsCheck(planted.r, planted.hand, fx, 960, 540);
  assert.ok(bad.some((p) => /^label "\d+" is no edge/.test(p)), JSON.stringify(bad.slice(0, 4)));
});

/** G7 on test_grid: its last cell's right side written at x = 302 when the plot ends at 294 (a 300-wide frame). */
const G7_GRID_ANCHOR = "    return out;\n  }\n\n  R.type(";
const G7_GRID_PLANT =
  '    if (out.length > 0 && hand.plot.x + hand.plot.w === 294) { var q = out.pop(); out.push(P.rect({ role: "mark", x: q.x, y: q.y, w: 302 - q.x, h: q.h, fill: q.fill, color: q.color, series: q.series })); }\n' + G7_GRID_ANCHOR;

test("G7: a test_grid cell planted 2 px beyond a 300 x 180 frame is one I2 naming the mark; without the plant none", () => {
  const E = formsEngine((s) => plant(s, testTypeKey("test_grid"), G7_GRID_ANCHOR, G7_GRID_PLANT));
  const fx = fixture("test_grid", "legend-top");
  const planted = E.render(typeInputOf(fx, 300, 180)), clean = R.render(typeInputOf(fx, 300, 180));
  const bad = violations(planted.primitives, 300, 180).violations;
  assert.equal(bad.length, 1, JSON.stringify(bad));
  assert.equal(bad[0].inv, "I2");
  assert.match(bad[0].what, /^mark of string:\w+ leaves the frame: [\d.]+,[\d.]+,302,[\d.]+$/);
  assert.equal(violations(clean.primitives, 300, 180).violations.length, 0);
});

// ---- A.7 this lane's share of the sweep, floored on its own ----

test("A.7: the five test types' sweep is not vacuous: at least five fixtures each, and the floors of what the checker saw", () => {
  const seen = { fixtures: 0, pictures: 0, rendered: 0, texts: 0, marks: 0 }, bad = [];
  for (const name of ["test_points", "test_grid", "test_two_axes", "test_bins", "test_bare"]) {
    const fxs = typeFixtures(name);
    assert.ok(fxs.length >= 5, `${name}: ${fxs.length} fixtures`);
    seen.fixtures += fxs.length;
    for (const f of fxs) {
      for (const [w, h] of SWEEP) {
        const r = R.render(typeInputOf(f.fixture, w, h)), v = violations(r.primitives, w, h);
        seen.pictures++;
        seen.texts += v.texts;
        seen.marks += v.marks;
        if (r.state === "rendered") {
          seen.rendered++;
          if (r.summary.marks !== f.fixture.expect.marks) bad.push(`I3 ${name}/${f.name} ${w}x${h}: ${r.summary.marks}, expected ${f.fixture.expect.marks}`);
        }
        for (const x of v.violations) bad.push(`${x.inv} ${name}/${f.name} ${w}x${h}: ${x.what}`);
      }
    }
  }
  assert.deepEqual(bad.slice(0, 8), [], bad.length + " violations");
  assert.ok(seen.fixtures >= 45, "fixtures: " + seen.fixtures);
  assert.equal(seen.pictures, seen.fixtures * 175);
  assert.ok(seen.rendered >= 5000, "rendered: " + seen.rendered);
  assert.ok(seen.texts >= 40000, "texts: " + seen.texts);
  assert.ok(seen.marks >= 100000, "marks: " + seen.marks);
  console.log(`A.7 the five types: ${seen.fixtures} fixtures x 175 frames = ${seen.pictures} pictures (${seen.rendered} rendered), ${seen.texts} texts, ${seen.marks} marks`);
});
