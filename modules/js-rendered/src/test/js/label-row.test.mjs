// label-row.test.mjs : the x label row of a position axis (lane 1087; the design record's note of 2026-11-21 "The label
// row of a position axis"). On such an axis a tick label is drawn whole or not at all (brief 989 D5: the layout asks
// labelRow for `whole: true`), so the row's step rule asks for the whole width of the widest label:
//   D1 every k-th label is kept, k the least number with k × (the least distance between two labels) >= the widest
//      label's whole width + the label gap; a row whose labels may be cut keeps min(widest, labelMinEm) + gap (D4);
//   D2 when the rule ends at k = n the first label is kept alone, and its room is the frame's edges alone;
//   D3 a kept label is left out only where the frame's edge leaves it less than its width (twice its distance to an
//      edge of the bounds), or where the corner rule cuts the first kept label.
// The expected row is computed HERE from what the layout handed the row (the labels' texts and places, the bounds, the
// helper's widths and gap) by D1 to D3's sentence; the row's own answer (`drawn`) is the thing under test. Before the
// lane the rule asked for min(widest, 6 em) + gap on every row, and a row of ten-digit labels came out empty in a band
// of label distances.
// D9 (what the rule does not promise): the rule counts every k-th label from the FIRST and has no second choice, so a
// row is still empty when every kept label stands where the frame's edge leaves it less than its width, although a
// label the rule did not keep would fit (issue #1092). The boundary test pins that case with its control.
//
// The gates read the same checks on a planted copy of the engine (forms.mjs plant(): the anchor occurs exactly once):
// UNDO_D1 is the rule of the base on a whole-label row (G1), UNDO_D2 keeps the neighbours' term for a label kept alone
// (G2), both together are the engine of the base, which "nothing else moved" compares every picture with.
import test from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, testTypes, typeFixtures, typeInputOf, chartFixtures, inputOf, canonical } from "./engine.mjs";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";
import { plant } from "./forms.mjs";

const AXES = "11-axes.js";
const WANT_NOW = 'var k = 1, want = (o.whole === true ? widest : Math.min(widest, look("labelMinEm") * s)) + gap;';
const WANT_BASE = 'var k = 1, want = Math.min(widest, look("labelMinEm") * s) + gap;';
const ROOM_NOW = "var roomJ = Math.min(alone ? Infinity : k * step - gap, 2 * (x - x0), 2 * (x1 - x));";
const ROOM_BASE = "var roomJ = Math.min(k * step - gap, 2 * (x - x0), 2 * (x1 - x));";
/** G1's plant: the rule of the base (min(widest, 6 em) + gap) on a whole-label row. */
const UNDO_D1 = (s) => plant(s, AXES, WANT_NOW, WANT_BASE);
/** G2's plant: a label kept alone keeps the neighbours' term of its room. */
const UNDO_D2 = (s) => plant(s, AXES, ROOM_NOW, ROOM_BASE);
/** The engine of the base: both lines of the lane reverted (the `alone` declaration is then read by nothing). */
const BASE = (s) => {
  UNDO_D1(s);
  UNDO_D2(s);
};

const NAMES = testTypes();
/** The sweep's frames: the harness's 35 widths × 5 heights, and the minimal and compact frames 80 × 24 and 200 × 140. */
const FRAMES = WIDTHS.flatMap((w) => HEIGHTS.map((h) => [w, h])).concat([[80, 24], [200, 140]]);
/** The comparison's frames: the sweep's, and the frames src/test/js/emit.mjs writes for the JVM side, each once. */
const EMITTED = [[960, 540], [480, 360], [300, 180], [120, 40], [60, 20], [360, 240], [200, 140]];
const COMPARED = [...new Map(FRAMES.concat(EMITTED).map((f) => [f.join("x"), f])).values()];

/**
 * An engine with every test type (planted by `edit` first) that records the LAST whole-label row of a render with what
 * D1 to D3 expect of it, computed from the arguments the layout handed the row.
 */
function recording(edit, E = engineWith(NAMES, edit)) {
  const orig = E.axes.labelRow, box = { last: null };
  E.axes.labelRow = function (h, o) {
    const res = orig.apply(this, arguments);
    if (o.whole === true) {
      const s = h.text, gap = h.em("labelGap"), n = o.labels.length, w = o.labels.map((l) => h.width(l.text, s));
      let step = o.plot.w;
      for (let i = 1; i < n; i++) step = Math.min(step, o.labels[i].x - o.labels[i - 1].x);
      if (!(step > 0)) step = 1;
      const widest = Math.max(0, ...w);
      let k = 1;
      while (k * step < widest + gap && k < n) k++;
      const edge = (i) => 2 * Math.min(o.labels[i].x - o.bounds.x0, o.bounds.x1 - o.labels[i].x) >= w[i];
      const expected = [];
      for (let j = 0; j < n; j += k) if (edge(j)) expected.push(j);
      box.last = {
        n, k, step, widest, gap, expected, rotated: res.rotated, corner: res.corner,
        drawn: [...res.drawn], texts: [...res.drawn].map((i) => o.labels[i].text), fits: o.labels.map((_, i) => edge(i)),
      };
    }
    return res;
  };
  return { E, box };
}

/** The sweep: every test type, every fixture, every frame; the rows of the levels that draw tick labels. */
function sweep(edit) {
  const { E, box } = recording(edit);
  const t = { rows: 0, labelled: 0, empty: 0, emptyThoughOneFits: 0, wrong: 0, thinned: 0, wider: 0, alone: 0, cornerCut: 0, rotated: 0, byType: {}, wrongAt: [] };
  for (const name of NAMES) for (const f of typeFixtures(name)) for (const [w, h] of FRAMES) {
    box.last = null;
    const r = E.render(typeInputOf(f.fixture, w, h));
    const L = box.last;
    if (!L || r.state !== "rendered" || r.level === "minimal" || L.n === 0) continue;
    t.rows++;
    t.byType[name] = (t.byType[name] || 0) + 1;
    if (L.rotated) t.rotated++;
    if (L.k > 1) t.thinned++;
    if (L.k >= L.n && L.n > 1) t.alone++;
    if (L.widest > 72) t.wider++;
    if (L.corner === "cut") t.cornerCut++;
    if (L.drawn.length > 0) t.labelled++;
    else {
      t.empty++;
      if (L.fits.some(Boolean)) t.emptyThoughOneFits++;
    }
    // D3: the corner rule may cut the first kept label and nothing else
    const want = L.corner === "cut" && L.drawn[0] !== L.expected[0] ? L.expected.slice(1) : L.expected;
    if (JSON.stringify(L.drawn) !== JSON.stringify(want)) {
      t.wrong++;
      t.wrongAt.push(`${name}/${f.name} ${w}x${h}: n ${L.n}, step ${L.step.toFixed(2)}, widest ${L.widest.toFixed(2)}, k ${L.k}: drawn ${JSON.stringify(L.drawn)}, expected ${JSON.stringify(want)}`);
    }
  }
  return t;
}

const print = (tag, t) => {
  console.log(`${tag}: ` + JSON.stringify({ ...t, wrongAt: undefined }));
  for (const e of t.wrongAt.slice(0, 30)) console.log("  wrong: " + e);
};

test("A.1 a position axis's label row keeps every k-th label whole, k from the widest label's whole width; a kept label is left out only by the frame's edge or the corner rule (1087 D1 to D3)", () => {
  const t = sweep(null);
  print("label rows of position axes", t);
  assert.ok(t.rows >= 1000, "rows read: " + t.rows);
  assert.ok(t.thinned >= 100, "rows the rule thinned: " + t.thinned);
  assert.ok(t.wider >= 100, "rows whose widest label is wider than 6 em at the text size 12: " + t.wider);
  assert.deepEqual(t.wrongAt.slice(0, 12), [], t.wrong + " rows not as D1 to D3 say");
  // A count of THESE fixtures, not a property of the rule (D9: see the boundary test).
  assert.equal(t.empty, 0, "empty rows over the test types' fixtures");
});

/** The row of one fixture at one frame on an engine, with the tick texts the picture holds. */
function rowOf(rec, type, fx, w, h) {
  rec.box.last = null;
  const r = rec.E.render(typeInputOf(fx, w, h));
  assert.equal(r.state, "rendered");
  return { L: rec.box.last, ticks: new Set([...r.primitives].filter((p) => p.k === "text" && p.role === "tick").map((p) => p.text)) };
}
const fixtureOf = (type, name) => typeFixtures(type).find((x) => x.name === name).fixture;

test("A.2 the worked examples of 1087, by their texts: ten-digit labels, and uneven ticks whose least distance is under 2 px", () => {
  const rec = recording(null);
  const cases = [
    ["test_points", "long-ticks", 520, 140, ["1,234,567,000", "1,234,569,000", "1,234,571,000"]],
    ["test_bins", "long-edges", 240, 140, ["1,234,569,000"]],
    ["test_bins", "uneven-edges", 200, 140, ["0"]],
  ];
  for (const [type, name, w, h, want] of cases) {
    const { L, ticks } = rowOf(rec, type, fixtureOf(type, name), w, h);
    console.log(`${type}/${name} ${w}x${h}: n ${L.n}, step ${L.step.toFixed(2)}, widest ${L.widest.toFixed(2)}, k ${L.k}, drawn ${JSON.stringify(L.texts)}`);
    assert.deepEqual(L.texts, want, `${type}/${name} ${w}x${h}`);
    for (const text of want) assert.ok(ticks.has(text), `the picture holds the tick text ${text}`);
  }
});

// D9: the boundary of the rule, pinned with its control. Built here from test_bins/long-edges (no fixture file: a new
// fixture would enter every count of the brief). Green on the base as well, where the row is empty too: it pins what
// this lane does NOT change (issue #1092), and is no gate of the lane.
test("D9 the boundary of 1087's rule: when every kept label stands at the frame's edge the row is empty, though a label not kept would fit (#1092); the control draws it", () => {
  const rec = recording(null);
  const fx = JSON.parse(JSON.stringify(fixtureOf("test_bins", "long-edges")));
  fx.document.config.encoding.x.edges = [123456700000000, 123456750000000, 123456800000000];
  fx.columns.v = [123456710000000, 123456760000000];
  delete fx.expect;
  const a = rowOf(rec, "test_bins", fx, 200, 140).L;
  console.log(`three fifteen-digit edges 200x140: n ${a.n}, step ${a.step.toFixed(2)}, widest ${a.widest.toFixed(2)}, k ${a.k}, fits the edges ${JSON.stringify(a.fits)}, expected ${JSON.stringify(a.expected)}, drawn ${JSON.stringify(a.texts)}`);
  assert.equal(a.n, 3);
  assert.equal(a.k, 2);
  assert.equal(JSON.stringify(a.fits), "[false,true,false]"); // by text: the row's labels are an array of the engine's realm
  assert.deepEqual(a.expected, []);
  assert.deepEqual(a.drawn, []);
  const b = rowOf(rec, "test_bins", fx, 320, 140).L; // the control: far enough apart for k = 1, the middle label drawn
  console.log(`three fifteen-digit edges 320x140: n ${b.n}, step ${b.step.toFixed(2)}, k ${b.k}, drawn ${JSON.stringify(b.texts)}`);
  assert.equal(b.k, 1);
  assert.deepEqual(b.drawn, [1]);
});

/** The texts of a picture as a multiset {text: count}. */
function textCounts(r) {
  const m = new Map();
  for (const p of r.primitives) if (p.k === "text") m.set(p.text, (m.get(p.text) || 0) + 1);
  return m;
}

test("A.3 nothing else moved: every product picture is the base's byte for byte; a test-type picture differs only where its label row was empty at the base, and gains tick labels and loses no text (D4, D5)", () => {
  const Now = engine(), Base = engine(BASE);
  let compared = 0;
  const productDiff = [];
  for (const f of chartFixtures()) for (const [w, h] of COMPARED) {
    compared++;
    if (canonical(Now.render(inputOf(f.fixture, w, h))) !== canonical(Base.render(inputOf(f.fixture, w, h)))) productDiff.push(`${f.wire}/${f.name} ${w}x${h}`);
  }
  const now = recording(null), base = recording(BASE);
  let typeCompared = 0;
  const changed = {}, bad = [];
  for (const name of NAMES) for (const f of typeFixtures(name)) for (const [w, h] of COMPARED) {
    typeCompared++;
    now.box.last = base.box.last = null;
    const a = base.E.render(typeInputOf(f.fixture, w, h)), b = now.E.render(typeInputOf(f.fixture, w, h));
    if (canonical(a) === canonical(b)) continue;
    const id = `${name}/${f.name}`;
    (changed[id] ||= []).push(`${w}x${h}`);
    const A = base.box.last, B = now.box.last;
    if (!A || A.drawn.length !== 0) bad.push(`${id} ${w}x${h}: the base's label row was not empty: ${JSON.stringify(A && A.texts)}`);
    if (!B || B.drawn.length === 0) bad.push(`${id} ${w}x${h}: no label drawn after`);
    const ta = textCounts(a), tb = textCounts(b);
    for (const [text, c] of ta) if ((tb.get(text) || 0) < c) bad.push(`${id} ${w}x${h}: loses the text ${JSON.stringify(text)}`);
    for (const [text, c] of tb) if (c > (ta.get(text) || 0) && !(B && B.texts.includes(text))) bad.push(`${id} ${w}x${h}: gains the text ${JSON.stringify(text)}, not a label of the row`);
  }
  const nChanged = Object.values(changed).reduce((x, l) => x + l.length, 0);
  console.log(`A.3: ${COMPARED.length} frames; product pictures compared ${compared}, differing ${productDiff.length}; test-type pictures compared ${typeCompared}, differing ${nChanged}`);
  for (const [id, frames] of Object.entries(changed)) console.log(`  changed ${id} (${frames.length}): ${frames.join(" ")}`);
  assert.ok(compared >= 20000, "product pictures compared: " + compared);
  assert.deepEqual(productDiff.slice(0, 12), [], productDiff.length + " product pictures differ from the base's");
  assert.deepEqual(bad.slice(0, 12), [], bad.length + " changed test-type pictures not as D5 says");
  assert.ok(nChanged >= 25, "test-type pictures the lane changes (the brief's prototype: 25 over 183 frames): " + nChanged);
});

// ---- The gates: A.1's sweep on a planted copy of the engine goes red, naming the thing ----

test("G1 the whole width (D1): the base's rule on a whole-label row leaves rows empty in which labels fit whole", () => {
  const t = sweep(UNDO_D1);
  print("G1 plant (min(widest, 6 em) + gap on a whole-label row)", t);
  assert.ok(t.wrong > 0, "rows not as D1 to D3 say with D1 reverted");
  assert.ok(t.empty > 0 && t.emptyThoughOneFits > 0, "empty rows in which a label fits, with D1 reverted");
});

test("G2 the label kept alone (D2): the neighbours' term kept for it leaves uneven-edges at 200 x 140 empty", () => {
  const t = sweep(UNDO_D2);
  print("G2 plant (a label kept alone keeps the neighbours' term)", t);
  assert.ok(t.wrong > 0, "rows not as D1 to D3 say with D2 reverted");
  assert.ok(t.wrongAt.some((e) => e.startsWith("test_bins/uneven-edges 200x140:")), "uneven-edges 200x140 among them");
  const rec = recording(UNDO_D2);
  assert.deepEqual(rowOf(rec, "test_bins", fixtureOf("test_bins", "uneven-edges"), 200, 140).L.texts, [], "the third worked example is empty with D2 reverted");
});
