// Brief 1128 (look contract §4.5, L10): a dot the frame cannot hold is drawn smaller, never left out and never past the
// frame. The rule's arithmetic is written HERE (asked, kept = the smaller of asked and a quarter of the frame's smaller
// side, share = kept / asked) and the pictures must agree: the line family's dots at the minimal level (K1), the bare
// frame's plot, dot and last value (K2), the hand and plot of the frames without axes (K3), and the reach kept at the
// left where the axes are drawn (K4), where the dot keeps its size. Brief 1131 (the owner's ruling of 2026-11-24): the
// minimal cartesian inset caps each side at a quarter of that side, min(max(edge, kept), side / 4), so a wide small
// frame keeps the plot's height (K6). Each test is falsified in its own plant test.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, chartFixtures, inputOf, testTypeKey, typeInputOf, typeFixtures } from "./engine.mjs";
import { violations } from "./geometry.mjs";
import { formsEngine, plant, fixture, draw, near } from "./forms.mjs";

const r2 = (v) => Math.round(v * 100) / 100;
const TOL = 0.02;
const FLOOR_PICTURES = 7000, FLOOR_WHOLE = 12000, FLOOR_SMALLER = 15000;
const SMALL = [[80, 24], [160, 24], [81, 25], [100, 31], [120, 40]];
const CARTESIAN = "12-cartesian.js", LINES = "16-lines.js", BARE = "18a-bare.js", FRAMES = "18-frames.js";
/** The rule, as this file computes it. */
const keep = (asked, W, H) => ({ asked, kept: Math.min(asked, Math.min(W, H) / 4) });
const shareOf = (k) => (k.asked > 0 ? k.kept / k.asked : 1);
const themeOf = (point, gap, text) => ({ base: "light", tokens: { "--chart-point": point, "--chart-gap": gap, "--chart-text": text } });
const flat = (prims) => {
  const out = [];
  const each = (p) => (p.k === "group" && Array.isArray(p.kids) ? p.kids.forEach(each) : out.push(p));
  prims.forEach(each);
  return out;
};
const dotsOf = (r) => flat(r.primitives).filter((p) => p.role === "mark" && p.k === "circle");
/** How far a circle's ink (its radius and half its ring) is past the frame; 0 or less is inside. */
const past = (c, W, H) => {
  const e = c.r + (c.strokeWidth || 0) / 2;
  return Math.max(e - c.cx, e - c.cy, c.cx + e - W, c.cy + e - H);
};

// ---------------------------------------------------------------- K1: line and area at the minimal level

/** Every line and area fixture under the tokens at the small frames: the counts and the pictures that break the rule. */
function minimalDots(R, points = [1, 4, 6, 11, 16], gaps = [0, 2, 8], texts = [8, 12], sizes = SMALL) {
  const seen = { pictures: 0, dots: 0, whole: 0, smaller: 0 }, problems = [];
  for (const f of chartFixtures()) {
    if (f.wire !== "line" && f.wire !== "area") continue;
    for (const point of points) for (const gap of gaps) for (const text of texts) for (const [W, H] of sizes) {
      const r = R.render(inputOf(f.fixture, W, H, { theme: themeOf(point, gap, text) }));
      if (r.state !== "rendered" || r.level !== "minimal") continue;
      seen.pictures++;
      const at = `${f.wire}/${f.name} ${W}x${H} point ${point} gap ${gap} text ${text}`;
      const share = shareOf(keep(point + gap / 2, W, H));
      for (const c of dotsOf(r)) {
        seen.dots++;
        if (share < 1) seen.smaller++;
        else seen.whole++;
        const wantR = share < 1 ? r2(point * share) : r2(point), wantRing = share < 1 ? r2(gap * share) : gap;
        if (c.r !== wantR || c.strokeWidth !== wantRing) problems.push(`${at}: a dot of radius ${c.r} and ring ${c.strokeWidth}, the rule gives ${wantR} and ${wantRing}`);
        if (past(c, W, H) > TOL) problems.push(`${at}: a dot's ink is ${r2(past(c, W, H))} px past the frame`);
      }
      const v = violations(r.primitives, W, H).violations;
      if (v.length > 0) problems.push(`${at}: ${v[0].inv} ${v[0].what}`);
    }
  }
  return { seen, problems };
}

test("K1: at the minimal level a line's and an area's dots have the token's radius and ring times the share the frame holds, and no mark leaves the frame", () => {
  const { seen, problems } = minimalDots(engine());
  assert.deepEqual(problems.slice(0, 5), [], `${problems.length} problems`);
  console.log(`K1: ${seen.pictures} minimal pictures, ${seen.dots} dots, ${seen.whole} at the token's size, ${seen.smaller} smaller`);
  assert.ok(seen.pictures >= FLOOR_PICTURES && seen.whole >= FLOOR_WHOLE && seen.smaller >= FLOOR_SMALLER, JSON.stringify(seen));
});

test("K1 at the default theme: every dot of a line or area fixture is the token's own, at every small frame", () => {
  const { seen, problems } = minimalDots(engine(), [4], [2], [12]);
  assert.deepEqual(problems, []);
  assert.ok(seen.dots >= 500 && seen.smaller === 0, JSON.stringify(seen));
});

test("K1 with decimals: where the frame holds the reach the two tokens are written as they are, where it does not they are scaled to two decimals", () => {
  const whole = minimalDots(engine(), [4.125], [1.337], [12], [[120, 40]]);
  assert.deepEqual(whole.problems, []);
  assert.ok(whole.seen.dots >= 100 && whole.seen.smaller === 0, JSON.stringify(whole.seen));
  const small = minimalDots(engine(), [15.875], [1.337], [12], [[80, 24]]);
  assert.deepEqual(small.problems, []);
  assert.ok(small.seen.dots >= 100 && small.seen.whole === 0, JSON.stringify(small.seen));
});

test("K1 plants: the dot not scaled, the ring not scaled, the reach kept whole, the ring rounded where nothing is scaled: each breaks K1", () => {
  const run = (key, from, to) => minimalDots(engine((s) => plant(s, key, from, to)), [16], [2], [12], [[80, 24]]).problems;
  assert.deepEqual(minimalDots(engine(), [16], [2], [12], [[80, 24]]).problems, [], "the control");
  const a = run(LINES, "        dotR = S.r2(dotR * share);\n", "");
  assert.ok(a.some((p) => /a dot of radius 16 /.test(p)) && a.some((p) => /past the frame/.test(p)), a[0]);
  const b = run(LINES, "        ring = S.r2(ring * share);\n", "");
  assert.ok(b.some((p) => /and ring 2, the rule gives/.test(p)), b[0]);
  const c = run(CARTESIAN, "kept: Math.min(asked, Math.min(W, H) / 4)", "kept: asked");
  assert.ok(c.some((p) => /past the frame/.test(p)), c[0]);
  const d = minimalDots(engine((s) => plant(s, LINES, "if (share < 1) {", "if (share <= 1) {")), [4.125], [1.337], [12], [[120, 40]]).problems;
  assert.ok(d.some((p) => /and ring 1.34, the rule gives 4.13 and 1.337/.test(p)), d[0]);
});

// ---------------------------------------------------------------- K2: the bare frame

/** test_bare under the tokens: the hand's reach, the plot's inset, the dot, and the last value clear of the dot. */
function bare(E) {
  const seen = { pictures: 0, written: 0, byReach: 0, smaller: 0 }, problems = [];
  for (const point of [1, 4, 6, 11, 16]) for (const gap of [0, 2, 8]) for (const text of [8, 12]) for (const [W, H] of [[80, 24], [160, 24], [160, 32], [400, 200]]) for (const last of ["value", "dot"]) {
    const fx = JSON.parse(JSON.stringify(fixture("test_bare", "short-value")));
    fx.document.config.options.last = last;
    const { r, hand } = draw(E, fx, W, H, { theme: themeOf(point, gap, text) });
    const at = `${W}x${H} point ${point} gap ${gap} text ${text} last ${last}`;
    const k = keep(point - 0.5 + gap / 2, W, H), share = shareOf(k), inset = Math.max(0.5 * text, k.kept), clear = Math.max(0.5 * text, k.kept);
    seen.pictures++;
    if (share < 1) seen.smaller++;
    if (JSON.stringify(hand.reach) !== JSON.stringify(k)) problems.push(`${at}: the hand's reach is ${JSON.stringify(hand.reach)}, the rule gives ${JSON.stringify(k)}`);
    if (!near(hand.plot.x, r2(inset)) || !near(hand.plot.y, r2(inset))) problems.push(`${at}: the plot starts at ${hand.plot.x},${hand.plot.y}, the inset is ${r2(inset)}`);
    const dots = dotsOf(r);
    if (dots.length !== 1) problems.push(`${at}: ${dots.length} dots`);
    for (const c of dots) {
      if (c.r !== r2((point - 0.5) * share) || c.strokeWidth !== r2(gap * share)) problems.push(`${at}: a dot of radius ${c.r} and ring ${c.strokeWidth}`);
      if (past(c, W, H) > TOL) problems.push(`${at}: the dot's ink is ${r2(past(c, W, H))} px past the frame`);
    }
    for (const t of r.primitives.filter((p) => p.k === "text" && p.role === "guide")) {
      seen.written++;
      if (k.kept > 0.5 * text) seen.byReach++;
      const end = hand.plot.x + hand.plot.w;
      if (!near(t.x - end, clear, 0.011)) problems.push(`${at}: the value starts ${r2(t.x - end)} px after the line's end, the rule gives ${r2(clear)}`);
      for (const c of dots) if (t.x < c.cx + c.r + c.strokeWidth / 2 - TOL) problems.push(`${at}: the value starts inside the dot's ring`);
    }
  }
  return { seen, problems };
}

test("K2: the bare frame hands the reach it kept, insets the plot by it, and writes the last value clear of the dot", () => {
  const { seen, problems } = bare(formsEngine());
  assert.deepEqual(problems.slice(0, 5), [], `${problems.length} problems`);
  console.log(`K2: ${JSON.stringify(seen)}`);
  assert.ok(seen.pictures === 240 && seen.written >= 30 && seen.byReach >= 5 && seen.smaller >= 60, JSON.stringify(seen));
});

test("K2 plants: the value at the label gap whatever the dot, and the frame's inset by the whole reach: each breaks K2", () => {
  const a = bare(formsEngine((s) => plant(s, BARE, 'gap = Math.max(h.em("directGap"), kept)', 'gap = h.em("directGap")'))).problems;
  assert.ok(a.some((p) => /inside the dot's ring/.test(p)), a[0]);
  const b = bare(formsEngine((s) => plant(s, CARTESIAN, "kept: Math.min(asked, Math.min(W, H) / 4)", "kept: asked"))).problems;
  assert.ok(b.some((p) => /the hand's reach is/.test(p)) && b.some((p) => /past the frame/.test(p)), b[0]);
});

// ---------------------------------------------------------------- K3: the frames without axes (polar, radial)

const WEB = "test_web", NONE = "test_plain", ASK = 'reach: function (h) { return h.theme.n("--chart-point") + h.theme.n("--chart-gap") / 2; }';
/** Where a reach is planted in each type's model: the radial frame's layout part, and a layout part for the `none` frame's type. */
const PARTS = { [WEB]: ["    var layout = {\n", "    var layout = {\n      " + ASK + ",\n"], [NONE]: ["rows: rows };", "rows: rows, layout: { " + ASK + " } };"] };
/** The engine with one test type, the template's hand kept, and (asks) a reach planted in the type's layout part. */
function web(asks, edit, name = WEB) {
  return engineWith([name], (s) => {
    const k = testTypeKey(name);
    plant(s, k, "  function marks(m, hand, h) {\n", "  function marks(m, hand, h) {\n    R.seen = hand;\n");
    if (asks) plant(s, k, PARTS[name][0], PARTS[name][1]);
    if (edit) edit(s);
  });
}

function radial(E, PLAIN, name = WEB) {
  const seen = { pictures: 0, minimal: 0, smaller: 0, deeper: 0 }, problems = [];
  for (const f of typeFixtures(name)) for (const point of [4, 11, 16]) for (const gap of [2, 8]) for (const [W, H] of [[80, 24], [100, 31], [120, 40], [300, 180], [960, 540]]) {
    const input = typeInputOf(f.fixture, W, H, { theme: themeOf(point, gap, 12) });
    E.seen = null;
    PLAIN.seen = null;
    const r = E.render(input), p0 = PLAIN.render(input);
    if (r.state !== "rendered" || E.seen === null) continue;
    seen.pictures++;
    const at = `${name}/${f.name} ${W}x${H} point ${point} gap ${gap}`, asked = point + gap / 2, hand = E.seen, plot = hand.plot;
    const k = r.level === "minimal" ? keep(asked, W, H) : { asked, kept: asked };
    if (JSON.stringify(hand.reach) !== JSON.stringify(k)) problems.push(`${at}: the hand's reach is ${JSON.stringify(hand.reach)}, the rule gives ${JSON.stringify(k)}`);
    if (r.level !== "minimal") {
      if (JSON.stringify(plot) !== JSON.stringify(PLAIN.seen.plot)) problems.push(`${at}: above the minimal level the reach moved the plot`);
      continue;
    }
    seen.minimal++;
    if (k.kept < asked) seen.smaller++;
    const inset = Math.max(6, k.kept);
    if (inset > 6) seen.deeper++;
    if (plot.x < inset - 0.011 || plot.y < inset - 0.011 || plot.x + plot.w > W - inset + 0.011 || plot.y + plot.h > H - inset + 0.011) problems.push(`${at}: the plot ${JSON.stringify(plot)} is not ${r2(inset)} px inside the frame`);
    if (p0.level !== r.level) problems.push(`${at}: the level moved`);
  }
  return { seen, problems };
}

test("K3: the frames without axes hand the reach, keep it inside the frame at the minimal level, and leave the other levels as they were", () => {
  const { seen, problems } = radial(web(true), web(false));
  assert.deepEqual(problems.slice(0, 5), [], `${problems.length} problems`);
  console.log(`K3: ${JSON.stringify(seen)}`);
  assert.ok(seen.pictures >= 200 && seen.minimal >= 100 && seen.smaller >= 50 && seen.deeper >= 50, JSON.stringify(seen));
  const none = radial(web(true, null, NONE), web(false, null, NONE), NONE);
  assert.deepEqual(none.problems.slice(0, 5), [], `${none.problems.length} problems on the none frame`);
  console.log(`K3, the none frame: ${JSON.stringify(none.seen)}`);
  assert.ok(none.seen.pictures >= 200 && none.seen.minimal >= 100 && none.seen.smaller >= 50 && none.seen.deeper >= 50, JSON.stringify(none.seen));
  // A type that asks for no reach is handed {asked: 0, kept: 0} and its plot is the frame inside the edge, as before.
  const P = web(false);
  P.render(typeInputOf(typeFixtures(WEB).find((f) => f.name === "three-spokes-one-series").fixture, 120, 40));
  assert.deepEqual(JSON.parse(JSON.stringify(P.seen.reach)), { asked: 0, kept: 0 });
  assert.deepEqual(JSON.parse(JSON.stringify(P.seen.plot)), { x: 6, y: 6, w: 108, h: 28 });
});

test("K3 plants: the reach kept whole at the minimal level, and the plot not inset by it: each breaks K3", () => {
  const a = radial(web(true, (s) => plant(s, FRAMES, 'lvl === "minimal" ? R.cartesian.keep(asked, W, H) : { asked: asked, kept: asked }', "{ asked: asked, kept: asked }")), web(false)).problems;
  assert.ok(a.some((p) => /the hand's reach is/.test(p)), a[0]);
  const b = radial(web(true, (s) => plant(s, FRAMES, "      e = Math.max(e, keep.kept);\n", "")), web(false)).problems;
  assert.ok(b.some((p) => /px inside the frame/.test(p)), b[0]);
  const c = radial(web(true, (s) => plant(s, FRAMES, "hand: { plot: f.plot, reach: f.reach }", "hand: { plot: f.plot }"), NONE), web(false, null, NONE), NONE).problems;
  assert.ok(c.some((p) => /the hand's reach is undefined/.test(p)), c[0]);
});

// ---------------------------------------------------------------- K4: where the axes are drawn, the reach is kept at the left

function withAxes(E) {
  const seen = { pictures: 0, dots: 0 }, problems = [];
  for (const name of ["one-series", "eight-series", "one-point", "time-negative"]) for (const point of [4, 11, 16]) for (const text of [8, 9, 12]) for (const [W, H] of [[200, 140], [240, 140], [360, 240], [480, 320]]) {
    const { r, hand } = draw(E, fixture("test_points", name), W, H, { theme: themeOf(point, 2, text) });
    if (r.state !== "rendered" || r.level === "minimal") continue;
    seen.pictures++;
    const at = `test_points/${name} ${W}x${H} point ${point} text ${text}`;
    if (hand.reach !== undefined) problems.push(`${at}: a reach is handed above the minimal level`);
    for (const c of dotsOf(r)) {
      seen.dots++;
      if (c.r !== point) problems.push(`${at}: a dot of radius ${c.r} above the minimal level`);
      if (past(c, W, H) > TOL) problems.push(`${at}: a dot's ink is ${r2(past(c, W, H))} px past the frame`);
    }
  }
  return { seen, problems };
}

/** The landed line and area fixtures at the frames and tokens where main draws a dot past the frame's left. */
function lineLeft(R) {
  let pictures = 0;
  const problems = [];
  for (const f of chartFixtures()) {
    if (f.wire !== "line" && f.wire !== "area") continue;
    for (const point of [11, 16]) for (const gap of [0, 8]) for (const text of [8, 9]) for (const [W, H] of [[200, 140], [240, 140]]) {
      const r = R.render(inputOf(f.fixture, W, H, { theme: themeOf(point, gap, text) }));
      if (r.state !== "rendered" || r.level === "minimal") continue;
      pictures++;
      for (const c of dotsOf(r)) if (past(c, W, H) > TOL) problems.push(`${f.wire}/${f.name} ${W}x${H} point ${point} gap ${gap} text ${text}: a dot's ink is ${r2(past(c, W, H))} px past the frame`);
      const v = violations(r.primitives, W, H).violations;
      if (v.length > 0) problems.push(`${f.wire}/${f.name} ${W}x${H} point ${point} gap ${gap} text ${text}: ${v[0].inv} ${v[0].what}`);
    }
  }
  return { pictures, problems };
}

test("K4: with axes no reach is handed, the dot keeps its size, and the reach kept at the left holds every dot inside the frame", () => {
  const { seen, problems } = withAxes(formsEngine());
  assert.deepEqual(problems.slice(0, 5), [], `${problems.length} problems`);
  const l = lineLeft(engine());
  assert.deepEqual(l.problems.slice(0, 5), [], `${l.problems.length} problems`);
  console.log(`K4: ${JSON.stringify(seen)}; line and area pictures with axes ${l.pictures}`);
  assert.ok(seen.pictures >= 100 && seen.dots >= 1000 && l.pictures >= 700, JSON.stringify(seen) + " " + l.pictures);
});

test("K4 plant: the left column without the reach lets a landed line's dot leave the frame", () => {
  const from = "var plotX = Math.max(b.left + titleCol + widestOf(h, ax.texts, s) + tg, reach);", to = "var plotX = b.left + titleCol + widestOf(h, ax.texts, s) + tg;";
  const b = lineLeft(engine((s) => plant(s, CARTESIAN, from, to))).problems;
  console.log(`K4 plant: ${b.length} problems`);
  assert.ok(b.filter((p) => /past the frame/.test(p)).length >= 10, b[0]);
});

// ---------------------------------------------------------------- K5: the rule's one function

test("K5: the rule is one function of the layout, read by every frame", () => {
  const R = engine(), k = (a, W, H) => JSON.parse(JSON.stringify(R.cartesian.keep(a, W, H)));
  assert.deepEqual(k(16.5, 80, 24), { asked: 16.5, kept: 6 });
  assert.deepEqual(k(4.5, 80, 24), { asked: 4.5, kept: 4.5 });
  assert.deepEqual(k(6, 80, 24), { asked: 6, kept: 6 });
  assert.deepEqual(k(0, 80, 24), { asked: 0, kept: 0 });
  assert.deepEqual(k(20, 24, 400), { asked: 20, kept: 6 });
});

// ---------------------------------------------------------------- K6: the minimal per-side inset (#1131)

/**
 * The minimal level's per-side inset as this file computes it (the owner's ruling of 2026-11-24, #1131): the edge or
 * the kept reach, whichever is larger, capped on each side at a quarter of that side — min(max(edge, kept), W/4)
 * horizontally and min(max(edge, kept), H/4) vertically. The line family's asked reach is the end dot's radius and half
 * its ring (16-lines.js): point + gap / 2.
 */
const inset = (e, kept, W, H) => ({ x: Math.min(Math.max(e, kept), W / 4), y: Math.min(Math.max(e, kept), H / 4) });

/** The y-coordinates a stroked mark path stands on (its M, L and C ends), across every drawn series. */
function markYs(prims) {
  const ys = [], ARITY = { M: 2, L: 2, A: 3, C: 6, Z: 0 };
  for (const p of prims) {
    if (p.k !== "path" || p.role !== "mark" || p.stroke === null) continue;
    for (let i = 0; i < p.d.length; ) {
      const c = p.d[i], n = ARITY[c];
      if (c === "M" || c === "L") ys.push(p.d[i + 2]);
      else if (c === "C") ys.push(p.d[i + 6]);
      i += 1 + n;
    }
  }
  return ys;
}

/** The span of a picture's stroked mark y's (0 when the line family drew no line). */
function ySpan(prims) {
  const ys = markYs(prims);
  return ys.length === 0 ? 0 : Math.max.apply(null, ys) - Math.min.apply(null, ys);
}

test("K6: at the minimal level each side's inset is capped at a quarter of that side, and the line and area are not flat", () => {
  const R = engine(), F = chartFixtures();
  // [W, H, text, wantX, wantY] — the edge is text / 2; kept is the default reach 5, never above the caps here.
  const rows = [
    [80, 24, 23, 11.5, 6], [80, 24, 32, 16, 6], [160, 24, 23, 11.5, 6], [160, 24, 32, 16, 6], [120, 40, 32, 16, 10], [120, 40, 23, 11.5, 10],
  ];
  let pictures = 0;
  for (const wire of ["line", "area"]) for (const [W, H, text, wantX, wantY] of rows) {
    const fx = F.find((f) => f.wire === wire && f.name === "one-series").fixture;
    const r = R.render(inputOf(fx, W, H, { theme: themeOf(4, 2, text) }));
    const where = `${wire}/one-series ${W}x${H} text ${text}`;
    assert.equal(r.level, "minimal", where);
    pictures++;
    const p = r.index.plot;
    assert.ok(near(p.x, wantX) && near(p.y, wantY), `${where}: plot ${JSON.stringify(p)}, want x ${wantX} y ${wantY}`);
    assert.ok(p.w >= W / 2 - 0.011 && p.h >= H / 2 - 0.011, `${where}: plot ${JSON.stringify(p)} keeps less than half of a dimension`);
    assert.ok(ySpan(r.primitives) > 1, `${where}: the marks' y span is ${r2(ySpan(r.primitives))} px (flat)`);
    const v = violations(r.primitives, W, H).violations;
    assert.equal(v.length, 0, `${where}: ${v[0] && v[0].inv} ${v[0] && v[0].what}`);
  }
  assert.ok(pictures === rows.length * 2, "pictures checked: " + pictures);
});

test("K6: the per-side inset is the rule's one function, and the same rule holds at the level boundary and every admitted extreme", () => {
  const R = engine(), ins = (e, kept, W, H) => JSON.parse(JSON.stringify(R.cartesian.insets(e, kept, W, H)));
  assert.deepEqual(ins(16, 5, 80, 24), { x: 16, y: 6 });
  assert.deepEqual(ins(11.5, 5, 80, 24), { x: 11.5, y: 6 });
  assert.deepEqual(ins(16, 5, 120, 40), { x: 16, y: 10 });
  assert.deepEqual(ins(6, 5, 80, 24), { x: 6, y: 6 });
  assert.deepEqual(ins(4, 20, 80, 200), { x: 20, y: 20 });
  assert.deepEqual(ins(16, 5, 80, 80), { x: 16, y: 16 });
  const F = chartFixtures();
  let pictures = 0;
  for (const wire of ["line", "area"]) for (const name of ["one-series", "negative"]) {
    const fx = F.find((f) => f.wire === wire && f.name === name).fixture;
    for (const point of [1, 16]) for (const gap of [0, 8]) for (const text of [8, 12, 32]) for (const [W, H] of [[80, 24], [160, 24], [120, 40], [81, 25], [100, 31], [200, 40]]) {
      const r = R.render(inputOf(fx, W, H, { theme: themeOf(point, gap, text) }));
      const where = `${wire}/${name} ${W}x${H} point ${point} gap ${gap} text ${text}`;
      assert.equal(r.level, "minimal", where);
      pictures++;
      const p = r.index.plot;
      assert.ok(p.w >= W / 2 - 0.011 && p.h >= H / 2 - 0.011, `${where}: plot ${JSON.stringify(p)}`);
      const v = violations(r.primitives, W, H).violations;
      assert.equal(v.length, 0, `${where}: ${v[0] && v[0].inv} ${v[0] && v[0].what}`);
    }
  }
  assert.ok(pictures === 2 * 2 * 2 * 2 * 3 * 6, "pictures checked: " + pictures);
  // The level boundary: the smallest frame draws at minimal; just below it nothing is drawn.
  const fx = F.find((f) => f.wire === "line" && f.name === "one-series").fixture;
  assert.equal(R.render(inputOf(fx, 80, 24)).state, "rendered");
  assert.equal(R.render(inputOf(fx, 79, 24)).state, "too-small");
  assert.equal(R.render(inputOf(fx, 80, 23)).state, "too-small");
});

test("K6 plants: the vertical cap taken off, and both caps set to H/4: each breaks K6", () => {
  const want = (E) => {
    const F = chartFixtures(), fx = F.find((f) => f.wire === "line" && f.name === "one-series").fixture;
    return E.render(inputOf(fx, 80, 24, { theme: themeOf(4, 2, 32) })).index.plot;
  };
  const control = want(engine());
  assert.ok(near(control.x, 16) && near(control.y, 6) && near(control.h, 12), "the control: " + JSON.stringify(control));
  // Gate 1: with the vertical cap off (the old max(edge, reach)), the 80×24 case loses its 12 px plot.
  const noY = engine((s) => plant(s, CARTESIAN, "y: Math.min(asked, H / 4)", "y: asked"));
  assert.ok(near(want(noY).h, 1), "the vertical cap off leaves the plot one pixel high: " + JSON.stringify(want(noY)));
  // Gate 2: with both caps set to H/4, the horizontal side no longer keeps its quarter, so x=16 fails.
  const bothH = engine((s) => plant(s, CARTESIAN, "x: Math.min(asked, W / 4)", "x: Math.min(asked, H / 4)"));
  assert.ok(near(want(bothH).x, 6), "both caps at H/4 lose the wide frame's x: " + JSON.stringify(want(bothH)));
});
