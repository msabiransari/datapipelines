// Lane 1066 (#1066, js-rendered G7; record §9's note of 2026-11-21): room inside the plot for a mark's size. A layout
// part with `x` and `vertical: true` may hand `pad(h)`; each of the two scales then places its axis's lowest and highest
// value the kept distance inside the plot's edge, the kept distance is never more than a quarter of the plot's side, and
// the template receives it as `hand.pad = {x, y}`. The test type test_bubbles (types/test_bubbles.js) hands `pad` = its
// largest radius and scales every radius by what was kept, so a circle never crosses the plot's edge.
//
// Every check is a function over an engine that returns its failures, so the gates G1 to G5 read the same check on a
// planted copy of the engine (plant(): the anchor must occur exactly once) and see it go red naming the thing; the
// check's own test on the real engine is the green direction.
//   A.1 entryFailures: the bad values and forms throw their sentences; absentFailures: a part without `pad` draws the
//       picture of an engine whose new path is switched off (keptOf → null), at every frame.
//   A.2/A.3 sweepInto: per frame of the sweep (geometry.mjs's 35 widths × 5 heights, and 80 × 24): I1 I2 I4 I5
//       (geometry.js), I3; hand.pad = min(pad, side / 4) computed HERE from the document; the four scale ends at the
//       plot's edge ± hand.pad, named by edge; every mark circle inside the plot by its whole radius (pad-zero is the
//       control, counted apart); every tick label carries the text of the tick at its place, read against the axis
//       module's own answers recorded as the layout asks for them (R.axes.valueAxis, R.axes.logAxis, R.time.ticks:
//       files this lane does not change), and the ticks were chosen for the plot's width and height (D2).
//   D3 the same document at a pad of 0: level, state, value ticks and value labels equal; the x row's counts printed.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engineWith, testTypeKey, typeFixtures, typeInputOf, canonical } from "./engine.mjs";
import { violations, textBox, WIDTHS, HEIGHTS } from "./geometry.mjs";
import { plant } from "./forms.mjs";

const CART = "12-cartesian.js";
const BUBBLES = testTypeKey("test_bubbles");
const SPY_FROM = "    model: model,\n    marks: marks,\n";
const SPY_TO = "    model: model,\n    marks: function (m, hand, h) { if (R.spy) R.spy(hand, m); return marks(m, hand, h); },\n";
const PAD_MEMBER = "      pad: function () {\n        return pad;\n      },\n";
/** The engine's new path switched off: keptOf answers null, so every scale is built by today's expression. */
const OFF = (s) => plant(s, CART, "function keptOf(L, h, plot) {\n", "function keptOf(L, h, plot) {\n    return null;\n");

/** test_bubbles (with its spy) on an engine whose sources `edit` may plant first. */
export const mk = (edit) => engineWith(["test_bubbles"], (s) => {
  plant(s, BUBBLES, SPY_FROM, SPY_TO);
  if (edit) edit(s);
});
/** Records every answer of the axis module while `E` renders: [{kind, length, pairs: [{at, text, under}], ticks}]. */
function recording(E) {
  const rec = [];
  const wrap = (mod, fn, kind, lengthAt, pairsOf) => {
    const orig = E[mod][fn];
    E[mod][fn] = function (...args) {
      const out = orig.apply(this, args);
      rec.push({ kind, length: args[lengthAt], pairs: pairsOf(out), ticks: out.ticks });
      return out;
    };
  };
  const axis = (out) => out.ticks.map((at, i) => ({ at, text: out.texts[i], under: null }));
  wrap("axes", "valueAxis", "linear", 3, axis);
  wrap("axes", "logAxis", "log", 3, axis);
  wrap("time", "ticks", "time", 2, (out) => out.map((x) => ({ at: x.at, text: x.text, under: x.under })));
  return rec;
}
const R = mk(), REC = recording(R);
const FRAMES = WIDTHS.flatMap((w) => HEIGHTS.map((h) => [w, h])).concat([[80, 24]]);
const FIXTURES = typeFixtures("test_bubbles");

function draw(E, fx, w, h, extra = {}, rec = REC) {
  let hand = null;
  rec.length = 0;
  E.spy = (x) => {
    hand = x;
  };
  try {
    return { r: E.render(typeInputOf(fx, w, h, extra)), hand };
  } finally {
    E.spy = null;
  }
}

/** The document's pad as this test reads it: encoding.pad when given, else the largest radius of the usable rows. */
function padOf(fx) {
  const e = fx.document.config.encoding;
  if (typeof e.pad === "number") return e.pad;
  return fx.columns[e.size.field].reduce((m, v) => Math.max(m, v), 0);
}

const near = (a, b, tol = 0.01) => Math.abs(a - b) <= tol;
const EDGES = ["the x scale's lowest value (left edge)", "the x scale's highest value (right edge)", "the value scale's lowest value (bottom edge)", "the value scale's highest value (top edge)"];
const newAssoc = () => ({ value: { linear: 0, log: 0 }, x: { linear: 0, log: 0, time: 0, under: 0 }, wrong: 0, unclassified: 0, lengthOff: 0 });
const newTally = () => ({
  frames: 0, rendered: 0, levels: new Set(), circles: 0, crossExact: 0, crossTol: 0, controlCross: 0, crossFrames: new Set(), ends: Object.fromEntries(EDGES.map((e) => [e, 0])),
  endsCrossed: 0, padRule: 0, noPad: 0, byLevel: {}, byInv: {}, cut: 0, assoc: newAssoc(),
});

/**
 * The association of every tick label of one rendered frame (not the level minimal, which draws none): the value
 * labels against the recorded value axis (the one whose ticks ARE hand.value.ticks), the x labels against the LAST
 * recorded axis of the document's x kind that is not the value axis, which must have been asked for the plot's width
 * (D2: the ticks are chosen for today's length).
 */
function association(T, fail, fx, r, hand, rec) {
  const p = hand.plot, kind = fx.document.config.encoding.x.kind || "linear";
  const valueRec = rec.find((q) => q.ticks === hand.value.ticks);
  const xRecs = rec.filter((q) => q.kind === kind && q !== valueRec), xRec = xRecs[xRecs.length - 1];
  if (!valueRec || !xRec) { T.unclassified++; fail("no recorded axis"); return; }
  if (xRec.length !== p.w || Math.abs(valueRec.length - p.h) > 0.006) { T.lengthOff++; fail(`ticks chosen for ${xRec.length} x ${valueRec.length}, the plot is ${p.w} x ${p.h}`); }
  const ticks = r.primitives.filter((q) => q.k === "text" && q.role === "tick");
  const row = ticks.filter((t) => t.anchor === "middle" && t.rotate === 0 && t.y > p.y + p.h);
  const base = row.reduce((m, t) => Math.min(m, t.y), Infinity);
  for (const t of ticks) {
    if (t.anchor === "end" && t.rotate === 0 && t.x <= p.x + 0.01) {
      const b = textBox(t), cy = (b[0][1] + b[2][1]) / 2;
      T.value[hand.value.log ? "log" : "linear"]++;
      if (!valueRec.pairs.some((q) => q.text === t.text && near(hand.value.scale(q.at), cy, 0.02))) { T.wrong++; fail(`value label ${t.text} at y ${cy.toFixed(2)} names no tick at its place`); }
    } else if (row.includes(t)) {
      const under = t.y > base + 1;
      T.x[under ? "under" : kind]++;
      if (!xRec.pairs.some((q) => (under ? q.under : q.text) === t.text && near(hand.x.scale(q.at), t.x, 0.02))) { T.wrong++; fail(`x ${under ? "under-" : ""}label ${t.text} at x ${t.x.toFixed(2)} names no tick at its place`); }
    } else { T.unclassified++; fail(`a tick label that is neither a value label nor in the x row: ${t.text}`); }
  }
}

/** Sweeps one fixture on engine E (rec = its recording) into tally t; returns this fixture's failures, each named. */
function sweepInto(t, E, rec, f) {
  const out = [], pad = padOf(f.fixture);
  for (const [w, h] of FRAMES) {
    const { r, hand } = draw(E, f.fixture, w, h, {}, rec);
    const where = `${f.name} ${w}x${h} ${r.level}`;
    const fail = (what) => {
      out.push(`${where}: ${what}`);
      t.byLevel[r.level] = (t.byLevel[r.level] || 0) + 1;
    };
    t.frames++;
    t.levels.add(r.level);
    for (const x of violations(r.primitives, w, h).violations) { t.byInv[x.inv] = (t.byInv[x.inv] || 0) + 1; fail(`${x.inv} ${x.what}`); }
    if (r.state !== "rendered") continue;
    t.rendered++;
    const circles = r.primitives.filter((p) => p.k === "circle" && p.role === "mark");
    if (r.summary.marks !== f.fixture.expect.marks || circles.length !== r.summary.marks) { t.byInv.I3 = (t.byInv.I3 || 0) + 1; fail(`I3 summary ${r.summary.marks}, expected ${f.fixture.expect.marks}, circles ${circles.length}`); }
    const p = hand.plot, kx = Math.min(pad, p.w / 4), ky = Math.min(pad, p.h / 4);
    if (!hand.pad) { t.noPad++; fail("hand.pad absent"); }
    else if (!near(hand.pad.x, kx, 0.0001) || !near(hand.pad.y, ky, 0.0001)) { t.padRule++; fail(`hand.pad ${JSON.stringify(hand.pad)}, the quarter rule keeps ${kx}, ${ky}`); }
    const got = [hand.x.scale(hand.x.lo), hand.x.scale(hand.x.hi), hand.value.scale(hand.value.lo), hand.value.scale(hand.value.hi)];
    const want = [p.x + kx, p.x + p.w - kx, p.y + p.h - ky, p.y + ky];
    for (let i = 0; i < 4; i++) if (!near(got[i], want[i])) { t.ends[EDGES[i]]++; fail(`${EDGES[i]} at ${got[i]}, the plot's edge ± pad is ${want[i]}`); }
    if (got[0] > got[1] + 1e-9 || got[2] < got[3] - 1e-9) { t.endsCrossed++; fail(`a scale's two ends cross: x ${got[0]}..${got[1]}, value ${got[2]}..${got[3]}`); }
    for (const c of circles) {
      t.circles++;
      const by = Math.max(p.x - (c.cx - c.r), c.cx + c.r - (p.x + p.w), p.y - (c.cy - c.r), c.cy + c.r - (p.y + p.h));
      // pad-zero is the control: the part hands 0, so a circle crosses the plot on purpose (today's picture).
      if (pad === 0) {
        if (by > 0.011) t.controlCross++;
        continue;
      }
      if (by > 1e-9) t.crossExact++;
      if (by > 0.011) { t.crossTol++; t.crossFrames.add(`${w}x${h}`); fail(`a circle of r ${c.r} at ${c.cx},${c.cy} crosses the plot by ${by.toFixed(3)} px`); }
    }
    if (r.level !== "minimal") {
      association(t.assoc, fail, f.fixture, r, hand, rec);
      for (const q of r.primitives) if (q.k === "text" && q.role === "tick" && q.shown !== null && q.shown !== q.text) { t.cut++; fail(`tick label ${q.text} cut to ${q.shown}`); }
    }
  }
  return out;
}

/** The whole sweep on a planted copy of the engine: its tally and its failures. */
function sweepOn(edit) {
  const E = mk(edit), rec = recording(E), t = newTally(), bad = [];
  for (const f of FIXTURES) bad.push(...sweepInto(t, E, rec, f));
  return { t, bad };
}

const TOTAL = newTally();
for (const f of FIXTURES) {
  test(`A.2/A.3: I1-I5, hand.pad by the quarter rule, the four scale ends at the plot's edge ± pad, circles inside the plot, tick labels at their ticks: ${f.name}`, () => {
    const bad = sweepInto(TOTAL, R, REC, f);
    assert.deepEqual(bad.slice(0, 12), [], bad.length + " failures");
  });
}

test("A.2/A.3 sweep totals and non-vacuity floors (after the sweep)", () => {
  const t = TOTAL, A = t.assoc;
  console.log(`bubbles: ${FIXTURES.length} fixtures, ${t.frames} frames (${t.rendered} rendered), levels ${[...t.levels].sort()}, circles ${t.circles} (+ control crossings ${t.controlCross}), ` +
    `crossings exact ${t.crossExact} / beyond 0.011 px ${t.crossTol}, ends off ${JSON.stringify(Object.values(t.ends))}, ends crossed ${t.endsCrossed}, pad rule off ${t.padRule}, hand.pad absent ${t.noPad}, cut tick labels ${t.cut}, violations ${JSON.stringify(t.byInv)}`);
  console.log(`bubbles tick association: value labels linear ${A.value.linear} / log ${A.value.log}; x labels linear ${A.x.linear} / log ${A.x.log} / time ${A.x.time} / time under-line ${A.x.under}; ` +
    `wrong ${A.wrong}, unclassified ${A.unclassified}, chosen for another length ${A.lengthOff}`);
  assert.equal(t.frames, FIXTURES.length * 176);
  assert.ok(FIXTURES.length >= 9, "fixtures: " + FIXTURES.length);
  for (const [k, n] of Object.entries({ ...A.value, xLinear: A.x.linear, xLog: A.x.log, xTime: A.x.time, xUnder: A.x.under })) assert.ok(n >= 100, `tick labels of the form ${k}: ${n}`);
  assert.equal(A.wrong + A.unclassified + A.lengthOff, 0);
  assert.deepEqual([...t.levels].sort(), ["compact", "full", "minimal"]);
  assert.ok(t.circles >= 5000, "circles: " + t.circles);
  assert.ok(t.controlCross > 0, "the control pad-zero crosses the plot (today's picture): " + t.controlCross);
  assert.equal(t.crossTol + t.crossExact + t.padRule + t.noPad + t.endsCrossed + t.cut, 0);
  assert.deepEqual(Object.values(t.ends), [0, 0, 0, 0]);
  assert.deepEqual(t.byInv, {});
});

test("D3 at its policy: mixed-radii's radii are drawn smaller on a frame too small for the distance asked", () => {
  const fx = FIXTURES.find((f) => f.name === "mixed-radii").fixture, out = {};
  for (const [w, h] of [[960, 540], [240, 100], [80, 24]]) {
    const rs = draw(R, fx, w, h).r.primitives.filter((p) => p.k === "circle" && p.role === "mark").map((c) => c.r);
    out[`${w}x${h}`] = [Math.min(...rs), Math.max(...rs)].map((v) => Math.round(v * 100) / 100);
  }
  console.log("mixed-radii drawn radii (least, greatest): " + JSON.stringify(out) + " (brief: 2-32, 1.38-22, 0.19-3)");
  assert.deepEqual(out, { "960x540": [2, 32], "240x100": [1.38, 22], "80x24": [0.19, 3] });
});

test("pad 0 is today's picture: the pad-zero document with and without the member has the same canonical text at every frame", () => {
  const Without = mk((s) => plant(s, BUBBLES, PAD_MEMBER, ""));
  const fx = FIXTURES.find((f) => f.name === "pad-zero").fixture;
  let n = 0;
  for (const [w, h] of FRAMES) {
    assert.equal(canonical(R.render(typeInputOf(fx, w, h))), canonical(Without.render(typeInputOf(fx, w, h))), `${w}x${h}`);
    n++;
  }
  assert.equal(n, 176);
});

const FINITE = "cartesian: pad is a finite number of px, 0 or more";
const FORM = "cartesian: pad is for a layout part with x and vertical: true";
/** A.1's entry checks on engines planted by `base` first: the cases whose two frames (960 x 540, 80 x 24) do not answer as D1 says. */
function entryFailures(base) {
  const fx = FIXTURES.find((f) => f.name === "mixed-radii").fixture;
  const run = (edit) => {
    const E = mk((s) => {
      if (base) base(s);
      edit((from, to) => plant(s, BUBBLES, from, to));
    });
    return [[960, 540], [80, 24]].map(([w, h]) => {
      try { E.render(typeInputOf(fx, w, h)); return "no throw"; } catch (e) { return String(e.message); }
    });
  };
  const cases = [];
  for (const v of ["NaN", "-1", '"3"', "Infinity", "null"]) cases.push([`pad returns ${v}`, (p) => p(PAD_MEMBER, "      pad: function () {\n        return " + v + ";\n      },\n"), FINITE]);
  cases.push(["pad is not a function", (p) => p(PAD_MEMBER, "      pad: 5,\n"), "cartesian: pad is a function of the helper"]);
  cases.push(["pad beside second", (p) => p(PAD_MEMBER, PAD_MEMBER + "      second: { extent: function () { return [0, 1]; } },\n"), "cartesian: pad cannot stand beside second"]);
  cases.push(["pad on a part without vertical: true", (p) => p("      vertical: true,\n      bands: [],", "      vertical: false,\n      bands: [],"), FORM]);
  cases.push(["pad on a part without x", (p) => p("      x: { kind: kind,", "      xx: { kind: kind,"), FORM]);
  cases.push(["no pad", (p) => p(PAD_MEMBER, ""), "no throw"]);
  const bad = [];
  for (const [name, edit, want] of cases) {
    const got = run(edit);
    if (got[0] !== want || got[1] !== want) bad.push(`${name}: ${JSON.stringify(got)}, D1 says ${JSON.stringify(want)}`);
  }
  return bad;
}

test("A.1: the five bad values and the three bad forms throw their sentences of D1 at 960 x 540 and 80 x 24; a part without pad throws nothing", () => {
  assert.deepEqual(entryFailures(null), []);
});

/**
 * A.1's last clause: a part without `pad` draws today's picture. Every test_points fixture and every test_bubbles fixture
 * with its member removed, at every frame, on engine E (planted by `base`) against the same engine with the new path
 * switched off (OFF). Returns the frames whose canonical text differs.
 */
function absentFailures(base) {
  const names = ["test_points", "test_bubbles"];
  const build = (off) => engineWith(names, (s) => {
    plant(s, BUBBLES, PAD_MEMBER, "");
    if (base) base(s);
    if (off) OFF(s);
  });
  const E = build(false), Before = build(true), bad = [];
  let n = 0;
  for (const name of names) for (const f of typeFixtures(name)) for (const [w, h] of FRAMES) {
    n++;
    if (canonical(E.render(typeInputOf(f.fixture, w, h))) !== canonical(Before.render(typeInputOf(f.fixture, w, h)))) bad.push(`${name}/${f.name} ${w}x${h}`);
  }
  assert.ok(n >= (8 + 9) * 176, "pictures compared: " + n);
  return bad;
}

test("A.1: a part without pad draws the picture of the engine whose new path is switched off, at every frame", () => {
  const bad = absentFailures(null);
  assert.deepEqual(bad.slice(0, 12), [], bad.length + " differing pictures");
});

test("D3: pad chooses no level and removes no text by a rule of its own; the label row's rules run on the padded places", () => {
  // the same document with encoding.pad = 0 (today's picture) against itself, frame by frame
  const t = { frames: 0, rendered: 0, level: 0, valueTicks: 0, valueLabels: 0, fewer: 0, more: 0, same: 0, plot: 0, fewerBy: {}, emptyRow: { pad: [], zero: [] } };
  for (const f of FIXTURES) {
    if (f.name === "pad-zero") continue;
    const zero = structuredClone(f.fixture);
    zero.document.config.encoding.pad = 0;
    for (const [w, h] of FRAMES) {
      const a = draw(R, zero, w, h), b = draw(R, f.fixture, w, h);
      t.frames++;
      if (a.r.level !== b.r.level || a.r.state !== b.r.state) t.level++;
      if (b.r.state !== "rendered") continue;
      t.rendered++;
      const labels = (x, left) => x.r.primitives.filter((q) => q.k === "text" && q.role === "tick" && (q.anchor === "end") === left).map((q) => q.text);
      if (JSON.stringify(a.hand.value.ticks) !== JSON.stringify(b.hand.value.ticks)) t.valueTicks++;
      if (JSON.stringify(labels(a, true)) !== JSON.stringify(labels(b, true))) t.valueLabels++;
      const na = labels(a, false).length, nb = labels(b, false).length;
      if (nb < na) { t.fewer++; t.fewerBy[f.name] = (t.fewerBy[f.name] || 0) + 1; } else if (nb > na) t.more++; else t.same++;
      if (b.r.level !== "minimal" && nb === 0) t.emptyRow.pad.push(`${f.name} ${w}x${h}`);
      if (a.r.level !== "minimal" && na === 0) t.emptyRow.zero.push(`${f.name} ${w}x${h}`);
      if (JSON.stringify(a.hand.plot) !== JSON.stringify(b.hand.plot)) t.plot++;
    }
  }
  console.log("pad against pad 0: " + JSON.stringify(t) + " (brief: more 759, fewer 22, same 451; plot 410)");
  assert.ok(t.frames >= 8 * 176, "frames compared: " + t.frames);
  assert.equal(t.level, 0, "a frame whose level or state differs");
  assert.equal(t.valueTicks, 0, "a frame whose value ticks differ");
  assert.equal(t.valueLabels, 0, "a frame whose value labels differ");
  // the worked example of the brief: ten-digit x labels at 280 x 140
  const lt = FIXTURES.find((f) => f.name === "long-ticks").fixture, z = structuredClone(lt);
  z.document.config.encoding.pad = 0;
  // the primitives come from the engine's own context: copied into this realm before a deep comparison
  const xs = (fx) => [...draw(R, fx, 280, 140).r.primitives].filter((q) => q.k === "text" && q.role === "tick" && q.anchor === "middle").map((q) => q.text);
  assert.deepEqual(xs(z), ["1,234,567,000", "1,234,568,000"]);
  assert.deepEqual(xs(lt), ["1,234,567,000"]);
});

// ---- The gates: each check above on a planted copy of the engine goes red naming the thing ----

const X_SCALE = "sc = A.scaleOf(ax, plot.x + (px || 0), plot.x + plot.w - (px || 0))";
const V_FULL = "kept === null ? A.scaleOf(ax, plot.y + plot.h, plot.y) : A.scaleOf(ax, plot.y + plot.h - kept.y, plot.y + kept.y)";
const V_MIN = "kept !== null ? A.scaleOf(ax, plot.y + plot.h - kept.y, plot.y + kept.y)";
const atLevels = (t) => Object.keys(t.byLevel).sort();

test("G1: the distance is kept at all four edges, at every level (four edge plants and minimal's lines)", () => {
  const edge = (i, edit) => {
    const { t } = sweepOn(edit);
    console.log(`G1 plant ${EDGES[i]}: ends off ${JSON.stringify(t.ends)}, crossings ${t.crossTol}, levels failing ${atLevels(t)}`);
    for (let j = 0; j < 4; j++) if (j === i) assert.ok(t.ends[EDGES[j]] > 0, EDGES[j]); else assert.equal(t.ends[EDGES[j]], 0, EDGES[j]);
    assert.ok(t.crossTol > 0, "crossings with " + EDGES[i] + " planted");
    assert.deepEqual(atLevels(t), ["compact", "full", "minimal"]);
  };
  edge(0, (s) => plant(s, CART, X_SCALE, "sc = A.scaleOf(ax, plot.x, plot.x + plot.w - (px || 0))"));
  edge(1, (s) => plant(s, CART, X_SCALE, "sc = A.scaleOf(ax, plot.x + (px || 0), plot.x + plot.w)"));
  edge(2, (s) => {
    plant(s, CART, V_FULL, "kept === null ? A.scaleOf(ax, plot.y + plot.h, plot.y) : A.scaleOf(ax, plot.y + plot.h, plot.y + kept.y)");
    plant(s, CART, V_MIN, "kept !== null ? A.scaleOf(ax, plot.y + plot.h, plot.y + kept.y)");
  });
  edge(3, (s) => {
    plant(s, CART, V_FULL, "kept === null ? A.scaleOf(ax, plot.y + plot.h, plot.y) : A.scaleOf(ax, plot.y + plot.h - kept.y, plot.y)");
    plant(s, CART, V_MIN, "kept !== null ? A.scaleOf(ax, plot.y + plot.h - kept.y, plot.y)");
  });
  // minimal's lines: its scales built as today's, no hand.pad at that level
  const { t } = sweepOn((s) => plant(s, CART, "kept = keptOf(L, h, plot);\n    var scale = kept !== null", "kept = null;\n    var scale = kept !== null"));
  console.log(`G1 plant minimal's lines: hand.pad absent ${t.noPad}, ends off ${JSON.stringify(t.ends)}, crossings ${t.crossTol}, levels failing ${atLevels(t)}`);
  assert.ok(t.noPad > 0 && t.crossTol > 0);
  assert.deepEqual(atLevels(t), ["minimal"]);
});

test("G2: the quarter rule (pad used uncut; hand.pad handing the asked value)", () => {
  const uncut = sweepOn((s) => plant(s, CART, "{ x: Math.min(p, plot.w / 4), y: Math.min(p, plot.h / 4) }", "{ x: p, y: p }"));
  console.log(`G2 plant pad uncut: pad rule off ${uncut.t.padRule}, ends crossed ${uncut.t.endsCrossed}; e.g. ${uncut.bad.find((b) => b.includes("80x24") && b.includes("cross"))}`);
  assert.ok(uncut.t.padRule > 0);
  assert.ok(uncut.bad.some((b) => b.includes(" 80x24 ") && b.includes("a scale's two ends cross")), "at 80 x 24 a scale's two ends cross");
  const asked = "{ x: padOf(L, h), y: padOf(L, h) }";
  const handed = sweepOn((s) => {
    plant(s, CART, "x: xs.axis, pad: kept,", `x: xs.axis, pad: kept === null ? null : ${asked},`);
    plant(s, CART, "if (kept !== null) out.pad = kept;", `if (kept !== null) out.pad = ${asked};`);
  });
  console.log(`G2 plant hand.pad = the asked value: crossings ${handed.t.crossTol} on frames ${[...handed.t.crossFrames].slice(0, 8)}…`);
  assert.ok(handed.t.crossTol > 0);
  assert.ok(handed.t.crossFrames.has("80x24"), "the smallest frame crosses");
});

test("G3: absent means today (a default pad of 1 for every part)", () => {
  const bad = absentFailures((s) => plant(s, CART, "if (!has(L.pad)) return null;\n    var p = L.pad(h);", "var p = has(L.pad) ? L.pad(h) : 1;"));
  console.log(`G3 plant a default pad of 1: ${bad.length} differing pictures, e.g. ${bad[0]}`);
  assert.ok(bad.length > 0);
});

test("G4: the entry check (the value check removed; the form check removed)", () => {
  const value = entryFailures((s) => plant(s, CART, '    if (typeof p !== "number" || !(p >= 0 && p < Infinity)) throw new Error("cartesian: pad is a finite number of px, 0 or more");\n', ""));
  console.log("G4 plant value check removed: " + JSON.stringify(value));
  for (const v of ["NaN", "-1", '"3"', "Infinity", "null"]) assert.ok(value.some((b) => b.startsWith(`pad returns ${v}:`)), "red naming pad returns " + v);
  const form = entryFailures((s) => {
    plant(s, CART, '      if (typeof L.pad !== "function") throw new Error("cartesian: pad is a function of the helper");\n', "");
    plant(s, CART, '      if (!has(L.x) || L.vertical !== true) throw new Error("cartesian: pad is for a layout part with x and vertical: true");\n', "");
    plant(s, CART, '      if (has(L.second)) throw new Error("cartesian: pad cannot stand beside second");\n', "");
  });
  console.log("G4 plant form check removed: " + JSON.stringify(form));
  for (const n of ["pad is not a function", "pad beside second", "pad on a part without vertical: true", "pad on a part without x"]) assert.ok(form.some((b) => b.startsWith(n + ":")), "red naming " + n);
});

test("G5: a tick label that names another tick is seen, on each axis form (the oracle can fail)", () => {
  const X_FROM = "out.push({ text: ax.ticks[i].text, under: ax.ticks[i].under, x: sc(ax.ticks[i].at) });";
  const xText = sweepOn((s) => plant(s, CART, X_FROM, "out.push({ text: ax.ticks[(i + 1) % ax.ticks.length].text, under: ax.ticks[i].under, x: sc(ax.ticks[i].at) });")).t.assoc;
  const xUnder = sweepOn((s) => plant(s, CART, X_FROM, "out.push({ text: ax.ticks[i].text, under: ax.ticks[i].under === null ? null : ax.ticks[i].text, x: sc(ax.ticks[i].at) });")).t.assoc;
  const value = sweepOn((s) => plant(s, CART, 'y: h.middle(scale(ax.ticks[k]), s), text: ax.texts[k], anchor: "end"', 'y: h.middle(scale(ax.ticks[k]), s), text: ax.texts[(k + 1) % ax.texts.length], anchor: "end"')).t.assoc;
  const length = sweepOn((s) => plant(s, CART, "var ax = positionAxis(lvl, L.x, plot.w), sc =", "var ax = positionAxis(lvl, L.x, plot.w - 2 * (px || 0)), sc =")).t.assoc;
  console.log(`G5 plants: x text wrong ${xText.wrong}; under-line wrong ${xUnder.wrong}; value text wrong ${value.wrong}; chosen for the shortened length ${length.lengthOff} (brief: 11,854, 1,548, 4,530, 980)`);
  assert.ok(xText.wrong > 0 && xUnder.wrong > 0 && value.wrong > 0 && length.lengthOff > 0);
});
