// Brief 1055 (js-rendered G3b): the frame `radial` over the test type test_web. Every expected point, ring corner, name
// place, centre and radius is computed HERE with Math.sin and Math.cos and the checker's own text widths (geometry.mjs),
// never with R.trig or the frame's code: the oracle is not the thing under test. A.1 the rings and the spokes, A.2 the
// ring numbers and their fit rule, A.3 the names and the radius, A.4 the levels, the title and the legend, A.5 the
// refusals of a wrong layout part, A.6 the sweep with floors; the gates G1 to G6 plant a copy of the engine (engineWith's
// edit) and see the check that names them go red, and the same check green without the plant.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, testTypeKey, typeFixtures, typeInputOf } from "./engine.mjs";
import { violations, width, textBox, meet, WIDTHS, HEIGHTS } from "./geometry.mjs";

const TYPE = "test_web";
const KEY = testTypeKey(TYPE);
const FILE = "18b-radial.js";
/** Where the probe records what the template receives (the copy draws the same picture: asserted below). */
const PROBE_ANCHOR = "  function marks(m, hand, h) {\n";
const EPS = 0.011;
const PRISTINE = engine();
const look = (f) => PRISTINE.tables.look(f);
const LIGHT = PRISTINE.theme.resolve("light");
const TEXT = LIGHT.n("--chart-text"), SMALL = LIGHT.n("--chart-text-small"), GAP = LIGHT.n("--chart-gap");
const ASC = look("textAscent"), DESC = look("textDescent");
const boxH = (size) => (ASC + DESC) * size;
const round2 = (v) => Math.round(v * 100) / 100 + 0;
const rad = (deg) => (deg * Math.PI) / 180;
/** The test's own point at a distance and an angle (degrees, 0 at 12 o'clock, clockwise). */
const at = (cx, cy, d, deg) => ({ x: cx + d * Math.sin(rad(deg)), y: cy - d * Math.cos(rad(deg)) });
const near = (a, b) => Math.abs(a - b) <= EPS;

/** The engine with test_web, a probe that keeps the template's hand, and an optional plant applied to the sources. */
function eng(edit) {
  return engineWith([TYPE], (s) => {
    assert.equal(s[KEY].split(PROBE_ANCHOR).length, 2, "the probe's anchor occurs once");
    s[KEY] = s[KEY].replace(PROBE_ANCHOR, PROBE_ANCHOR + "    R.seen = hand;\n");
    if (edit) edit(s);
  });
}

/** A plant: `from` occurs exactly once in `file` (an engine file or test_web's key) and becomes `to`. */
function plant(file, from, to) {
  return (s) => {
    assert.equal(s[file].split(from).length, 2, "the plant's anchor occurs once in " + file + ": " + from);
    s[file] = s[file].replace(from, to);
  };
}

const R = eng();
const CLEAN = engineWith([TYPE]);
/**
 * #1059 (D9): an axis writes 5e99 where the compact fixture wrote 118 characters, so no fixture reaches condition (a).
 * The rule stays the frame's and is read on a copy whose e-form start points are planted out of reach, built for the engine
 * under test (LONG) and for this test's own oracle (LONG_ORACLE), where extent-1e100 writes its 118 characters again.
 */
const LONG_TEXTS = plant("01-text.js", "var SCI_FROM = 15, SCI_BELOW = -5;", "var SCI_FROM = 400, SCI_BELOW = -400;");
const LONG = eng(LONG_TEXTS);
const LONG_ORACLE = engine(LONG_TEXTS);
const FX = typeFixtures(TYPE);
const fx = (name) => FX.find((f) => f.name === name).fixture;
const plain = (v) => JSON.parse(JSON.stringify(v));

function draw(E, f, w, h, extra) {
  E.seen = null;
  const r = E.render(typeInputOf(f, w, h, extra));
  return { r, hand: E.seen === null ? null : plain(E.seen), scale: E.seen === null ? null : E.seen.r.scale };
}

/** The fixture's own reading: the spokes in first order, the largest value, the domain and the format. */
function reading(f) {
  const e = f.document.config.encoding, K = f.columns[e.category.field], V = f.columns[e.value.field];
  const spokes = [];
  let hi = 0;
  for (let i = 0; i < K.length; i++) {
    if (!spokes.includes(String(K[i]))) spokes.push(String(K[i]));
    hi = Math.max(hi, V[i]);
  }
  return { spokes, hi, domain: (e.value.scale && e.value.scale.domain) || null, format: e.value.format || null };
}

/** The primitives by part: the plot's background, spokes, rings, names (the text size), numbers (the small size), plates. */
function parts(prims) {
  const idx = (p) => prims.indexOf(p);
  return {
    spokes: prims.filter((p) => p.k === "line" && p.role === "grid"),
    rings: prims.filter((p) => (p.k === "path" || p.k === "circle") && p.role === "grid"),
    names: prims.filter((p) => p.k === "text" && p.role === "tick" && p.size === TEXT),
    numbers: prims.filter((p) => p.k === "text" && p.role === "tick" && p.size === SMALL),
    plates: prims.filter((p) => p.k === "rect" && p.role === "tick"),
    marks: prims.filter((p) => p.role === "mark"),
    idx,
  };
}

/** The ring values the test expects: the value axis's ticks above 0 for twice the radius, and hi where it is no tick. */
function ringValues(f, hand, oracle = PRISTINE) {
  const rd = reading(f), ax = oracle.axes.valueAxis(hand.level, [0, rd.hi], rd.domain, 2 * hand.radius, rd.format, 0);
  const vals = ax.ticks.filter((t) => t > 0 && t < ax.hi).map((t) => ({ v: t, text: ax.texts[ax.ticks.indexOf(t)] }));
  vals.push({ v: ax.hi, text: null });
  return { ax, vals };
}

// ---------------------------------------------------------------- A.1 the frame, the scale, the rings and the spokes

/** A.1's failures for one picture at full or compact: [] when every ring, spoke and order rule holds. */
function ringFailures(f, d, oracle) {
  const out = [], { r, hand, scale } = d, n = hand.angles.length, c = hand.centre, P = parts(r.primitives);
  const { ax, vals } = ringValues(f, hand, oracle);
  if (JSON.stringify(hand.r.ticks) !== JSON.stringify(ax.ticks)) out.push("ticks " + JSON.stringify(hand.r.ticks) + " are not the value axis's " + JSON.stringify(ax.ticks));
  if (hand.r.lo !== 0 || hand.r.hi !== ax.hi) out.push("r.lo/hi " + hand.r.lo + "/" + hand.r.hi);
  if (scale(0) !== 0) out.push("r.scale(0) is " + scale(0) + ", not 0");
  if (scale(hand.r.hi) !== hand.radius) out.push("r.scale(hi) is " + scale(hand.r.hi) + ", not the radius " + hand.radius);
  for (let i = 0; i < n; i++) if (!near(hand.angles[i], (i * 360) / n)) out.push("angle " + i + " is " + hand.angles[i]);
  if (P.rings.some((p) => p.k === "circle")) out.push("a ring is a circle, not a path of " + n + " corners");
  const paths = P.rings.filter((p) => p.k === "path");
  if (paths.length !== vals.length) out.push(paths.length + " rings, the test expects " + vals.length);
  paths.forEach((p, k) => {
    const cmds = p.d.filter((x) => typeof x === "string");
    if (cmds.some((x) => x !== "M" && x !== "L" && x !== "Z")) out.push("ring " + k + " holds " + cmds.filter((x) => x !== "M" && x !== "L" && x !== "Z").join("") + ", not M, L and Z only");
    const corners = [];
    for (let i = 0; i < p.d.length; i++) if (p.d[i] === "M" || p.d[i] === "L") corners.push({ x: p.d[i + 1], y: p.d[i + 2] });
    if (corners.length !== n || p.d[p.d.length - 1] !== "Z") out.push("ring " + k + " has " + corners.length + " corners, not " + n + " closed");
    const D = vals[k] ? (vals[k].v / hand.r.hi) * hand.radius : NaN;
    corners.forEach((q, i) => {
      const e = at(c.x, c.y, D, (i * 360) / n);
      if (!near(q.x, e.x) || !near(q.y, e.y)) out.push(`ring ${k} corner ${i} at ${q.x},${q.y}, the test's ${e.x.toFixed(3)},${e.y.toFixed(3)}`);
    });
    if (corners.length >= 2) {
      if (!near(corners[0].x, c.x) || !(corners[0].y < c.y)) out.push("ring " + k + ": the first corner is not straight above the centre");
      if (!(corners[1].x > c.x)) out.push("ring " + k + ": the second corner is not to the right of the first (clockwise)");
    }
    const outer = k === paths.length - 1, token = outer ? "--chart-axis" : "--chart-grid";
    if (p.stroke !== token || p.strokeWidth !== LIGHT.n("--chart-stroke") || p.fill !== null) out.push(`ring ${k} stroke ${p.stroke} ${p.strokeWidth} fill ${p.fill}, not ${token}`);
  });
  if (P.spokes.length !== n) out.push(P.spokes.length + " spokes, not " + n);
  P.spokes.forEach((s, i) => {
    const e = at(c.x, c.y, hand.radius, (i * 360) / n);
    if (s.x1 !== c.x || s.y1 !== c.y || !near(s.x2, e.x) || !near(s.y2, e.y)) out.push("spoke " + i + " does not run from the centre to its outermost corner");
    if (s.stroke !== "--chart-grid" || s.strokeWidth !== LIGHT.n("--chart-stroke")) out.push("spoke " + i + " stroke " + s.stroke);
  });
  const firstMark = Math.min(...P.marks.map(P.idx)), lastMark = Math.max(...P.marks.map(P.idx));
  for (const p of [...P.rings, ...P.spokes]) if (P.idx(p) > firstMark) out.push("a ring or spoke after the first mark");
  for (const p of [...P.names, ...P.numbers, ...P.plates]) if (P.idx(p) < lastMark) out.push("a name, number or plate before the last mark");
  return out;
}

// ---------------------------------------------------------------- A.2 the ring numbers

const overlap = (a, b) => a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1;
const boxOf = (t) => {
  const q = textBox(t), xs = q.map((p) => p[0]), ys = q.map((p) => p[1]);
  return { x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys) };
};

/**
 * D5's fit rule decided by the test: for each numbered ring from the outermost in, its plate measured and built here at
 * the test's own midpoint of the ring's first edge, and the first of the four conditions it fails (a: inside the plot,
 * b: no drawn name, c: no plate placed, d: not over the centre) or null when the number must be drawn.
 */
function expectedNumbers(f, d, oracle) {
  const { r, hand, scale } = d, n = hand.angles.length, c = hand.centre, plot = hand.plot;
  const names = parts(r.primitives).names.map(boxOf), vals = ringValues(f, hand, oracle).vals, plates = [], out = [];
  for (let i = vals.length - 2; i >= 0; i--) {
    const D = scale(vals[i].v), a = at(c.x, c.y, D, 0), b = at(c.x, c.y, D, 360 / n);
    const exact = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, mid = { x: round2(exact.x), y: round2(exact.y) }, w = width(vals[i].text, SMALL), bh = boxH(SMALL);
    const plate = { x0: mid.x - w / 2 - GAP, y0: mid.y - bh / 2 - GAP, x1: mid.x + w / 2 + GAP, y1: mid.y + bh / 2 + GAP };
    let why = null;
    if (plate.x0 < plot.x || plate.y0 < plot.y || plate.x1 > plot.x + plot.w || plate.y1 > plot.y + plot.h) why = "a";
    else if (names.some((q) => overlap(plate, q))) why = "b";
    else if (plates.some((q) => overlap(plate, q))) why = "c";
    else if (plate.x0 <= c.x && c.x <= plate.x1 && plate.y0 <= c.y && c.y <= plate.y1) why = "d";
    if (why === null) plates.push(plate);
    out.push({ text: vals[i].text, exact, mid, plate, why });
  }
  return out;
}

/** A.2's failures for one picture at full or compact: the drawn numbers are exactly the test's, each on its plate. */
function numberFailures(f, d, oracle) {
  const out = [], P = parts(d.r.primitives), want = expectedNumbers(f, d, oracle).filter((e) => e.why === null);
  const got = P.numbers.map((t) => t.text);
  if (JSON.stringify(got) !== JSON.stringify(want.map((e) => e.text))) out.push("numbers " + JSON.stringify(got) + ", the fit rule wants " + JSON.stringify(want.map((e) => e.text)));
  if (P.plates.length !== P.numbers.length) out.push(P.plates.length + " plates for " + P.numbers.length + " numbers");
  P.numbers.forEach((t, k) => {
    const e = want[k], prev = d.r.primitives[P.idx(t) - 1];
    if (e && (!near(t.x, e.exact.x) || !near(t.y, e.exact.y + ((ASC - DESC) / 2) * SMALL))) out.push(`number ${t.text} at ${t.x},${t.y}, not at the midpoint of the first edge`);
    if (t.anchor !== "middle" || t.fill !== "--chart-muted" || t.shown !== null) out.push("number " + t.text + ": anchor " + t.anchor + ", ink " + t.fill + ", shown " + t.shown);
    if (!prev || prev.k !== "rect" || prev.role !== "tick" || prev.fill !== "--chart-canvas") return out.push("number " + t.text + " has no plate directly before it");
    const b = boxOf(t);
    const holds = prev.x <= b.x0 - GAP + EPS && prev.y <= b.y0 - GAP + EPS && prev.x + prev.w >= b.x1 + GAP - EPS && prev.y + prev.h >= b.y1 + GAP - EPS;
    if (!holds) out.push("the plate of " + t.text + " does not hold its box with --chart-gap around it");
  });
  return out;
}

// ---------------------------------------------------------------- A.3 the names and the radius

/** D4 by the test: {names, centre, radius, nw} from the polar frame's plot and the checker's widths. */
function expectedRoom(f, hand) {
  const p = hand.plot, tg = look("tickGap") * TEXT, bh = boxH(TEXT), sp = reading(f).spokes;
  const widest = Math.max(...sp.map((s) => width(s, TEXT)));
  const nw = Math.min(widest, look("bandLabelMaxEm") * TEXT, look("radialNameShare") * p.w);
  const L = p.x + nw + tg, Rt = p.x + p.w - nw - tg, T = p.y + bh + tg, B = p.y + p.h - bh - tg;
  const radius = round2(Math.min(Rt - L, B - T) / 2), polar = { centre: { x: round2(p.x + p.w / 2), y: round2(p.y + p.h / 2) }, radius: round2(Math.min(p.w, p.h) / 2) };
  if (!(radius >= look("radialRadiusFloor") * polar.radius)) return { names: false, centre: polar.centre, radius: polar.radius, polar, nw };
  return { names: true, centre: { x: round2((L + Rt) / 2), y: round2((T + B) / 2) }, radius, polar, nw };
}

/** A.3's failures for one picture at full or compact. */
function nameFailures(f, d) {
  const out = [], { r, hand } = d, P = parts(r.primitives), e = expectedRoom(f, hand), n = hand.angles.length, tg = look("tickGap") * TEXT;
  const c = hand.centre, sp = reading(f).spokes;
  if (!near(c.x, e.centre.x) || !near(c.y, e.centre.y) || !near(hand.radius, e.radius)) out.push(`centre ${c.x},${c.y} radius ${hand.radius}; the test's D4 gives ${e.centre.x},${e.centre.y} radius ${e.radius} (names ${e.names})`);
  if (!e.names && P.names.length > 0) out.push(P.names.length + " names where the rule of no room applies");
  if (e.names && !(hand.radius < e.polar.radius)) out.push("names are written and the radius " + hand.radius + " is not below the polar frame's " + e.polar.radius);
  const corners = hand.angles.map((a) => at(c.x, c.y, hand.radius, a));
  for (const t of P.names) {
    const i = sp.indexOf(t.text);
    if (i < 0) {
      out.push("a name that is no spoke: " + t.text);
      continue;
    }
    const a = (i * 360) / n, q = at(c.x, c.y, hand.radius + tg, a), b = boxOf(t);
    const side = a === 0 ? "top" : a === 180 ? "bottom" : Math.sin(rad(a)) > 1e-9 ? "right" : "left";
    const want = { top: ["middle", q.y - DESC * TEXT], bottom: ["middle", q.y + ASC * TEXT], right: ["start", q.y + ((ASC - DESC) / 2) * TEXT], left: ["end", q.y + ((ASC - DESC) / 2) * TEXT] }[side];
    if (t.anchor !== want[0] || !near(t.x, q.x) || !near(t.y, want[1])) out.push(`name ${i} (${side}) anchored ${t.anchor} at ${t.x},${t.y}; the test wants ${want[0]} at ${q.x.toFixed(2)},${want[1].toFixed(2)}`);
    if (width(t.text, TEXT) > e.nw + 1e-9 && (t.shown === null || t.shown === t.text)) out.push("name " + i + " is longer than nw and not cut");
    if (b.x1 - b.x0 > e.nw + EPS) out.push("name " + i + " is wider than nw");
    if (t.fill !== "--chart-muted") out.push("name " + i + " ink " + t.fill);
    for (const k of corners) if (k.x > b.x0 + EPS && k.x < b.x1 - EPS && k.y > b.y0 + EPS && k.y < b.y1 - EPS) out.push("name " + i + " covers a corner of the outermost ring");
  }
  for (let i = 0; i < P.names.length; i++) for (let j = i + 1; j < P.names.length; j++) if (meet(textBox(P.names[i]), textBox(P.names[j]))) out.push("two names meet: " + i + ", " + j);
  return out;
}

// ---------------------------------------------------------------- the sweep: A.6, with A.1 to A.3 at every frame

const FLOOR = { fixtures: 10, pictures: 10 * 175, rendered: 1350, texts: 9500, marks: 22000, numbers: 1150, names: 5300 };
const seen = { pictures: 0, rendered: 0, texts: 0, marks: 0, numbers: 0, names: 0, levels: new Set(), states: new Set(), room: { names: 0, none: 0 }, reasons: {}, largest: {} };

test("A.6: test_web's fixtures, the probe draws the same pictures, and the four hands of the template", () => {
  assert.ok(FX.length >= FLOOR.fixtures, "fixtures: " + FX.length);
  for (const f of FX) for (const [w, h] of [[960, 540], [300, 180], [120, 40]]) assert.equal(JSON.stringify(draw(R, f.fixture, w, h).r), JSON.stringify(CLEAN.render(typeInputOf(f.fixture, w, h))), f.name);
  draw(R, fx("five-spokes-two-series"), 960, 540);
  assert.deepEqual(Object.keys(R.seen).sort(), ["angles", "centre", "level", "plot", "r", "radius", "reach", "visible"]);
  assert.deepEqual(Object.keys(R.seen.r).sort(), ["hi", "lo", "scale", "ticks"]);
  assert.equal(typeof R.seen.r.scale, "function");
  assert.deepEqual(plain(R.seen.angles), [0, 72, 144, 216, 288]);
});

for (const f of FX) {
  test(`A.1-A.3 and I1-I5 at every frame of the sweep: ${f.name}`, () => {
    const bad = [];
    for (const w of WIDTHS) {
      for (const h of HEIGHTS) {
        const d = draw(R, f.fixture, w, h), r = d.r;
        seen.pictures++;
        seen.levels.add(r.level);
        seen.states.add(r.state);
        const v = violations(r.primitives, w, h);
        seen.texts += v.texts;
        seen.marks += v.marks;
        for (const x of v.violations) bad.push(`${x.inv} at ${w}x${h}: ${x.what}`);
        if (r.state !== "rendered") continue;
        seen.rendered++;
        if (r.summary.marks !== f.fixture.expect.marks) bad.push(`I3 at ${w}x${h}: summary.marks ${r.summary.marks}, expected ${f.fixture.expect.marks}`);
        if (v.marks !== r.summary.marks) bad.push(`I3 at ${w}x${h}: the primitives hold ${v.marks} marks`);
        if (r.level === "minimal") continue;
        for (const x of ringFailures(f.fixture, d)) bad.push(`A.1 at ${w}x${h}: ${x}`);
        for (const x of numberFailures(f.fixture, d)) bad.push(`A.2 at ${w}x${h}: ${x}`);
        for (const x of nameFailures(f.fixture, d)) bad.push(`A.3 at ${w}x${h}: ${x}`);
        const P = parts(r.primitives);
        seen.numbers += P.numbers.length;
        seen.names += P.names.length;
        seen.room[expectedRoom(f.fixture, d.hand).names ? "names" : "none"]++;
        for (const e of expectedNumbers(f.fixture, d)) if (e.why !== null && seen.reasons[e.why] === undefined) seen.reasons[e.why] = `${f.name} at ${w}x${h}: ${e.text}`;
        if (w === 1600 && h === 480) seen.largest[f.name] = P.numbers.length;
      }
    }
    assert.deepEqual(bad.slice(0, 12), [], bad.length + " failures");
  });
}

test("A.6: the sweep is not vacuous (floors, after it), both outcomes of the rule of no room occur, and the four reasons a number is absent", () => {
  console.log(`radial sweep: ${FX.length} fixtures x ${WIDTHS.length * HEIGHTS.length} frames = ${seen.pictures} pictures (${seen.rendered} rendered), ${seen.texts} texts, ${seen.marks} marks, ${seen.names} names, ${seen.numbers} ring numbers`);
  assert.equal(seen.pictures, FX.length * WIDTHS.length * HEIGHTS.length);
  for (const k of ["pictures", "rendered", "texts", "marks", "numbers", "names"]) assert.ok(seen[k] >= FLOOR[k], k + ": " + seen[k]);
  assert.deepEqual([...seen.levels].sort(), ["compact", "full", "minimal"]);
  assert.deepEqual([...seen.states].sort(), ["no-data", "rendered"]);
  assert.ok(seen.room.names > 0 && seen.room.none > 0, JSON.stringify(seen.room));
  assert.ok(Math.max(...Object.values(seen.largest)) >= 2, "numbers at 1600 x 480: " + JSON.stringify(seen.largest));
  console.log("radial rule of no room at full and compact: names " + seen.room.names + ", none " + seen.room.none);
  console.log("radial ring numbers drawn at 1600 x 480: " + JSON.stringify(seen.largest));
  for (const k of ["a", "b", "c", "d"]) console.log("radial number absent by condition " + k + ": " + (seen.reasons[k] || "no fixture and frame reached it"));
  for (const k of ["c", "d"]) assert.ok(seen.reasons[k] !== undefined, "condition " + k + " is the reason in some fixture and frame");
  // Conditions (a) and (b) were reached only by extent-1e100's ring number of 118 characters; with the e form (#1059)
  // its 5e99 fits, so both are read on the copy with the start points out of reach (D9), over the sweep's frames.
  const copy = {};
  for (const w of WIDTHS) {
    for (const h of HEIGHTS) {
      const d = draw(LONG, fx("extent-1e100"), w, h);
      if (d.r.state !== "rendered" || d.r.level === "minimal") continue;
      for (const e of expectedNumbers(fx("extent-1e100"), d, LONG_ORACLE)) if (e.why !== null && copy[e.why] === undefined) copy[e.why] = `extent-1e100 at ${w}x${h}, start points out of reach: ${e.text.length} characters`;
    }
  }
  for (const k of ["a", "b"]) console.log("radial number absent by condition " + k + " on the copy: " + copy[k]);
  for (const k of ["a", "b"]) assert.ok(copy[k] !== undefined, "condition " + k + " is the reason in some frame of extent-1e100 with the start points out of reach");
});

// ---------------------------------------------------------------- A.1 to A.3 at named frames

test("A.1: the domain fixture has a ring at hi = 115 that is no tick, in --chart-axis; the ticks are the value axis's", () => {
  const d = draw(R, fx("domain-not-nice"), 960, 540);
  assert.equal(d.hand.r.hi, 115);
  assert.ok(!d.hand.r.ticks.includes(115), JSON.stringify(d.hand.r.ticks));
  const P = parts(d.r.primitives), outer = P.rings[P.rings.length - 1];
  assert.equal(outer.stroke, "--chart-axis");
  assert.ok(near(outer.d[1], d.hand.centre.x) && near(outer.d[2], d.hand.centre.y - d.hand.radius), "the outermost ring runs through the radius");
  assert.equal(P.rings.length, d.hand.r.ticks.filter((t) => t > 0).length + 1);
});

test("A.2: the extent 0 to 1e100 at 360 x 240: the ring number 5e99 is drawn on its plate (#1059: the e form fits)", () => {
  const d = draw(R, fx("extent-1e100"), 360, 240), P = parts(d.r.primitives);
  assert.deepEqual(d.hand.r.ticks, [0, 5e99, 1e100]);
  const e = expectedNumbers(fx("extent-1e100"), d);
  assert.deepEqual(e.map((x) => [x.text, x.why]), [["5e99", null]], "the test's own rule draws 5e99");
  assert.deepEqual([...P.numbers].map((t) => t.text), ["5e99"]);
  assert.equal(P.plates.length, 1);
  assert.equal(P.rings.length, 2);
  assert.deepEqual(numberFailures(fx("extent-1e100"), d), []);
});

test("A.2: with the start points out of reach (D9's copy), the extent 0 to 1e100 at 360 x 240: no number and no plate, the ring is there (D5's example)", () => {
  const d = draw(LONG, fx("extent-1e100"), 360, 240), P = parts(d.r.primitives);
  assert.deepEqual(d.hand.r.ticks, [0, 5e99, 1e100]);
  assert.equal(P.numbers.length, 0);
  assert.equal(P.plates.length, 0);
  assert.equal(P.rings.length, 2);
  const e = expectedNumbers(fx("extent-1e100"), d, LONG_ORACLE);
  assert.equal(e.length, 1);
  assert.equal(e[0].why, "a");
  // The fixture's format is compact: 5,000,…,000T, the base's text, wider than the plot.
  assert.equal(e[0].text.length, 118);
  assert.ok(width(e[0].text, SMALL) > d.hand.plot.w, "wider than the plot");
  console.log("radial 1e100 at 360 x 240 on the copy: the ring number's text is " + width(e[0].text, SMALL).toFixed(1) + " px wide, the plot " + d.hand.plot.w + " px");
});

test("A.3: the name with < and & is escaped in the string writer's output, with as many elements as its twin without them", () => {
  const f = fx("twelve-spokes-six-series-long-names"), twin = structuredClone(f);
  twin.columns.k = twin.columns.k.map((k) => k.replace("<&>", "xyz"));
  assert.notDeepEqual(plain(twin.columns.k), plain(f.columns.k));
  const a = CLEAN.render(typeInputOf(f, 960, 540)), b = CLEAN.render(typeInputOf(twin, 960, 540));
  const name = parts(a.primitives).names.find((t) => t.text.includes("<&>"));
  assert.ok(name, "the name is drawn at 960 x 540");
  const sa = CLEAN.writeString(a.primitives, "standalone", "light"), sb = CLEAN.writeString(b.primitives, "standalone", "light");
  const elements = (s) => (s.match(/<[A-Za-z]/g) || []).length;
  assert.ok(sa.includes("&lt;&amp;&gt;"), "escaped");
  assert.ok(!sa.includes("<&>"), "never raw");
  assert.equal(elements(sa), elements(sb));
});

test("A.3: a long name is cut and keeps its full text; with names the radius is below the polar frame's", () => {
  const d = draw(R, fx("twelve-spokes-six-series-long-names"), 960, 540), P = parts(d.r.primitives), e = expectedRoom(fx("twelve-spokes-six-series-long-names"), d.hand);
  assert.ok(e.names && P.names.length > 0);
  assert.ok(P.names.every((t) => t.text.length === 40 && t.shown !== null && t.shown.endsWith("…")), "cut, full text kept");
  assert.ok(d.hand.radius < e.polar.radius);
});

// ---------------------------------------------------------------- A.4 the levels, the title and the legend

test("A.4: at minimal no tick, grid or axis primitive and the polar frame's centre and radius", () => {
  for (const f of FX.filter((x) => x.name !== "empty")) {
    const d = draw(R, f.fixture, 120, 40);
    assert.equal(d.r.level, "minimal");
    assert.deepEqual(plain(d.r.primitives.filter((p) => ["tick", "grid", "axis"].includes(p.role))), [], f.name);
    const p = d.hand.plot;
    assert.equal(d.hand.radius, round2(Math.min(p.w, p.h) / 2), f.name);
    assert.deepEqual(d.hand.centre, { x: round2(p.x + p.w / 2), y: round2(p.y + p.h / 2) }, f.name);
    assert.equal(d.scale(0), 0);
  }
});

test("A.4: at full the title block, the legend at each of its four places and the names meet nothing and stay in the frame; one series has no legend", () => {
  for (const orient of ["top", "bottom", "left", "right"]) {
    const f = structuredClone(fx("five-spokes-two-series"));
    f.document.config.encoding.color.legend = { orient };
    for (const [w, h] of [[960, 540], [480, 360], [360, 240]]) {
      const d = draw(R, f, w, h), P = parts(d.r.primitives);
      assert.equal(d.r.level, "full");
      assert.ok(d.r.primitives.some((p) => p.k === "group" && p.role === "legend"), orient + ": a legend");
      assert.ok(d.r.primitives.some((p) => p.role === "title"), orient + ": a title");
      assert.ok(P.names.length > 0, `${orient} ${w}x${h}: names`);
      assert.deepEqual(plain(violations(d.r.primitives, w, h).violations), [], `${orient} ${w}x${h}`);
      assert.deepEqual(nameFailures(f, d), [], `${orient} ${w}x${h}`);
    }
  }
  for (const name of ["three-spokes-one-series", "every-value-zero"]) assert.equal(CLEAN.render(typeInputOf(fx(name), 960, 540)).primitives.filter((p) => p.role === "legend").length, 0, name);
});

// ---------------------------------------------------------------- A.5 the refusals of a wrong layout part

test("A.5: a wrong layout part throws naming its member: 2 spokes, 13 spokes, an extent from -1, a domain from 5", () => {
  const base = fx("five-spokes-two-series");
  const withRows = (k, v) => Object.assign(structuredClone(base), { columns: { k, v, s: k.map(() => "One") } });
  const cats = (n) => Array.from({ length: n }, (_, i) => "C" + i);
  assert.throws(() => CLEAN.render(typeInputOf(withRows(cats(2), [1, 2]), 960, 540)), /radial: layout\.spokes holds 3 to 12 categories, not 2/);
  assert.throws(() => CLEAN.render(typeInputOf(withRows(cats(13), cats(13).map((_, i) => i + 1)), 960, 540)), /radial: layout\.spokes holds 3 to 12 categories, not 13/);
  assert.throws(() => CLEAN.render(typeInputOf(withRows(cats(3), [-1, 2, 3]), 960, 540)), /radial: layout\.extent starts at -1, below 0/);
  const dom = structuredClone(base);
  dom.document.config.encoding.value.scale = { domain: [5, 100] };
  assert.throws(() => CLEAN.render(typeInputOf(dom, 960, 540)), /radial: layout\.domain starts at 5, not 0/);
  // The same parts at the minimal level throw too: the part is checked before the level decides what is drawn.
  assert.throws(() => CLEAN.render(typeInputOf(withRows(cats(2), [1, 2]), 120, 40)), /layout\.spokes/);
  // The look field's other words throw naming it.
  const E = engineWith([TYPE]);
  E.tables.LOOK.radialRings.value = "round";
  assert.throws(() => E.render(typeInputOf(base, 960, 540)), /radial: the look field radialRings is polygon or circle, not round/);
});

// ---------------------------------------------------------------- the gates G1 to G6 (falsified at birth)

/** Every failure of A.1 to A.3, I1 to I5 for a fixture over the frames given, in an engine (the oracle: its text module's twin). */
function failures(E, name, frames, oracle) {
  const out = [], f = fx(name);
  for (const [w, h] of frames) {
    const d = draw(E, f, w, h);
    for (const x of violations(d.r.primitives, w, h).violations) out.push(`${x.inv} ${w}x${h}: ${x.what}`);
    if (d.r.state !== "rendered" || d.r.level === "minimal") continue;
    for (const x of ringFailures(f, d, oracle)) out.push(`A.1 ${w}x${h}: ${x}`);
    for (const x of numberFailures(f, d, oracle)) out.push(`A.2 ${w}x${h}: ${x}`);
    for (const x of nameFailures(f, d)) out.push(`A.3 ${w}x${h}: ${x}`);
  }
  return out;
}
const FRAMES = [[960, 540], [480, 360], [360, 240], [300, 180], [1600, 480]];
const red = (list, re, what) => assert.ok(list.some((x) => re.test(x)), what + ": no failure matching " + re + " in " + JSON.stringify(list.slice(0, 4)));

test("G0: every gate's fixtures are green without a plant (the other direction of each gate below)", () => {
  for (const name of ["five-spokes-two-series", "extent-1e100", "thousands-two-series", "least-forty", "twelve-spokes-six-series-long-names"]) assert.deepEqual(failures(R, name, FRAMES), [], name);
  assert.deepEqual(failures(LONG, "extent-1e100", FRAMES, LONG_ORACLE), [], "extent-1e100 on the copy with the start points out of reach (G4's condition a)");
});

test("G1: the angles counted the other way turn the clockwise test red; the first spoke at 90 degrees the straight-above test", () => {
  const ccw = eng(plant(FILE, "angles.push((i * 360) / n);", "angles.push((360 - (i * 360) / n) % 360);"));
  red(failures(ccw, "five-spokes-two-series", [[960, 540]]), /not to the right of the first \(clockwise\)/, "counter-clockwise");
  const ninety = eng(plant(FILE, "angles.push((i * 360) / n);", "angles.push(90 + (i * 360) / n);"));
  red(failures(ninety, "five-spokes-two-series", [[960, 540]]), /the first corner is not straight above the centre/, "first spoke at 90");
});

test("G2: radialRings set to circle in a copy of the table turns the corner test red; one ring written with an arc turns the A test red", () => {
  const E = eng();
  E.tables.LOOK.radialRings.value = "circle";
  red(failures(E, "five-spokes-two-series", [[960, 540]]), /a ring is a circle, not a path of 5 corners/, "circle");
  const arc = eng(plant(FILE, '      d.push("Z");\n', '      d.push("Z");\n      if (r === 0) d = ["M", centre.x, centre.y - rings[r].d].concat(T.arc(centre.x, centre.y, rings[r].d, 0, 360));\n'));
  red(failures(arc, "five-spokes-two-series", [[960, 540]]), /ring 0 holds A+, not M, L and Z only/, "arc");
});

test("G3: the polar frame's radius kept while names are written turns the radius test red (and the sweep's I2 where a name leaves the frame)", () => {
  const E = eng(plant(FILE, "      radius = room0.radius;\n", ""));
  const list = failures(E, "twelve-spokes-six-series-long-names", FRAMES);
  red(list, /^A\.3 .*the test's D4 gives/, "radius");
  console.log("G3 planted: radius test " + list.filter((x) => /D4 gives/.test(x)).length + " red, I2 " + list.filter((x) => /^I2/.test(x)).length + " red");
});

test("G4: the plate left out, the plate after its number, condition (a) removed, condition (c) removed: each red", () => {
  const PLATE = '      prims.push(P.rect({ role: "tick", x: plate.x0, y: plate.y0, w: plate.x1 - plate.x0, h: plate.y1 - plate.y0, fill: "--chart-canvas", color: h.theme.css("--chart-canvas") }));\n';
  const NUMBER = '      prims.push(h.label({ role: "tick", x: p.x, y: h.middle(p.y, s), text: rings[i].text, anchor: "middle", size: s, ink: "--chart-muted" }));\n';
  const none = eng(plant(FILE, PLATE, ""));
  red(failures(none, "thousands-two-series", [[960, 540]]), /has no plate directly before it/, "plate left out");
  const after = eng(plant(FILE, PLATE + NUMBER, NUMBER + PLATE));
  red(failures(after, "thousands-two-series", [[960, 540]]), /has no plate directly before it/, "plate after its number");
  const NO_A = plant(FILE, "      if (plate.x0 < plot.x || plate.y0 < plot.y || plate.x1 > plot.x + plot.w || plate.y1 > plot.y + plot.h) continue;\n", "");
  // Read on the copy with the e form's start points out of reach (#1059, D9), where extent-1e100 writes 118 characters.
  // At 360 x 240 a name and the centre also refuse the number (conditions b and d), so the plant is read at 360 x 480,
  // where condition (a) alone keeps the 645 px text out.
  const noA = eng((s) => {
    LONG_TEXTS(s);
    NO_A(s);
  });
  const a = failures(noA, "extent-1e100", [[360, 480]], LONG_ORACLE);
  red(a, /^A\.2 .*the fit rule wants \[\]/, "condition (a): a number the test's rule forbids");
  red(a, /^I2 .*leaves the frame/, "condition (a): a text outside the frame");
  const noC = eng(plant(FILE, "      for (var q = 0; q < plates.length && !meets; q++) meets = A.overlaps(plate, plates[q]);\n", ""));
  red(failures(noC, "three-spokes-one-series", [[960, 540]]), /^A\.2 .*the fit rule wants/, "condition (c): two plates meet");
});

test("G5: the scale built from the extent's low end (least value 40) turns r.scale(0) red", () => {
  const E = eng((s) => {
    plant(FILE, "A.valueAxis(lvl, [0, L.ext[1]]", "A.valueAxis(lvl, [L.ext[0], L.ext[1]]")(s);
    plant(FILE, "scale = S.linear(0, hi, 0, radius)", "scale = S.linear(ax.lo, hi, 0, radius)")(s);
  });
  red(failures(E, "least-forty", [[960, 540]]), /r\.scale\(0\) is -?[\d.]+, not 0/, "scale from 40");
});

test("G6: a point's circle planted at x = 302 in a 300 x 180 frame is an I2 violation naming the mark; without it none", () => {
  const ANCHOR = "      for (var j = 0; j < at.length; j++) {\n";
  const E = engineWith([TYPE], plant(KEY, ANCHOR, "      if (hand.plot.x + hand.plot.w === 294 && at.length > 0) at[0] = { x: 302, y: at[0].y };\n" + ANCHOR));
  const f = fx("three-spokes-one-series");
  const bad = violations(E.render(typeInputOf(f, 300, 180)).primitives, 300, 180).violations;
  assert.ok(bad.length >= 1 && bad.every((x) => x.inv === "I2"), JSON.stringify(bad));
  assert.match(bad[0].what, /^mark of measure leaves the frame: [\d.]+,[\d.]+,30[0-9.]+,[\d.]+$/);
  assert.deepEqual(plain(violations(CLEAN.render(typeInputOf(f, 300, 180)).primitives, 300, 180).violations), []);
});

// ---------------------------------------------------------------- D8: each look field has the effect it names

test("D8: radialTickSpan 2 -> 2.5 changes a tick list, radialNameShare 0.25 -> 0.2 cuts a name shorter, radialRadiusFloor 0.5 -> 0.4 turns a frame from no names to names", () => {
  const changed = (field, v) => {
    const E = eng();
    E.tables.LOOK[field].value = v;
    return E;
  };
  const span = changed("radialTickSpan", 2.5), share = changed("radialNameShare", 0.2), floor = changed("radialRadiusFloor", 0.4);
  assert.deepEqual(plain(draw(R, fx("domain-not-nice"), 480, 360).hand.r.ticks), [0, 25, 50, 75, 100]);
  assert.deepEqual(plain(draw(span, fx("domain-not-nice"), 480, 360).hand.r.ticks), [0, 20, 40, 60, 80, 100]);
  const before = parts(draw(R, fx("every-value-zero"), 240, 160).r.primitives).names.find((t) => t.text === "Comfort");
  const after = parts(draw(share, fx("every-value-zero"), 240, 160).r.primitives).names.find((t) => t.text === "Comfort");
  assert.equal(before.shown, null, "whole at 0.25");
  assert.equal(after.shown, "Comf…", "cut shorter at 0.2");
  assert.equal(parts(draw(R, fx("five-spokes-two-series"), 200, 140).r.primitives).names.length, 0, "no names at 0.5");
  assert.equal(parts(draw(floor, fx("five-spokes-two-series"), 200, 140).r.primitives).names.length, 5, "names at 0.4");
});
