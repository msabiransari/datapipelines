// Brief 1052 A.1, A.2, A.7 (its test_marks floors) and the engine's gates G1 to G4 and G10: the index's second form
// (21-lookup.js) over the test type test_marks. Every expected mark, anchor, box and answer is computed HERE from the
// fixture's own numbers, with Math.hypot, Math.atan2, Math.sin and Math.cos: the oracle is never R.lookup. Each named
// check is a function of an engine, so the same check runs on the clean engine (green) and on a planted copy (red):
// the plants live in copies of the sources (engineWith's edit), never in the tree.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engineWith, testTypes, testTypeKey, typeFixtures, typeInputOf, chartFixtures, inputOf } from "./engine.mjs";
import { violations, WIDTHS, HEIGHTS } from "./geometry.mjs";

const NAMES = testTypes();
const R = engineWith(NAMES);
const FX = Object.fromEntries(typeFixtures("test_marks").map((f) => [f.name, f.fixture]));
const REACH = R.tables.look("markReach");
const FRAMES = [[960, 540], [480, 360], [300, 180]];
const LOOKUP = "21-lookup.js";
const r2 = (v) => Math.round(v * 100) / 100 + 0;
const DEG = Math.PI / 180;
/** A value built in the engine's own context, as plain data of this one (deepEqual compares prototypes across contexts). */
const plain = (v) => JSON.parse(JSON.stringify(v));

// ------------------------------------------------------------------------------------------ the test's own model

/** The fixture's rows as objects: every column by name, a row per index of the longest column. */
function rowsOf(fx) {
  const c = fx.columns, n = Math.max(0, ...Object.values(c).map((a) => a.length));
  const at = (k, i) => (c[k] && c[k][i] !== undefined && c[k][i] !== null ? c[k][i] : null);
  return Array.from({ length: n }, (_, i) => Object.fromEntries(Object.keys(c).map((k) => [k, at(k, i)])));
}

/** A copy of a fixture without the rows whose id is one of [ids]; or with its rows in the order [order] (ids). */
function edited(fx, { drop = [], order = null } = {}) {
  const rows = rowsOf(fx).filter((r) => !drop.includes(r.id));
  const sorted = order === null ? rows : [...rows].sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
  const columns = Object.fromEntries(Object.keys(fx.columns).map((k) => [k, sorted.map((r) => r[k])]));
  return { ...fx, columns, expect: { marks: sorted.filter((r) => r.kind !== null && r.id !== null).length } };
}

/** The marks the test expects in a draw's index, from the fixture and the draw's plot (D2, D3, D10). */
function expectedMarks(fx, out) {
  const plot = out.index.plot, on = new Map(out.index.series.map((s) => [s.id, s.on]));
  const theme = R.theme.resolve(fx.theme === undefined ? "light" : fx.theme);
  const cx = r2(plot.x + plot.w / 2), cy = r2(plot.y + plot.h / 2), radius = r2(Math.min(plot.w, plot.h) / 2);
  const big = Math.min(plot.w, plot.h) / 3, cards = fx.document.config.cards || {};
  const tokens = new Map(), titles = new Map();
  const out2 = [];
  for (const r of rowsOf(fx)) {
    if (r.kind === null || r.id === null) continue;
    const sid = r.s === null ? null : typeof r.s + ":" + String(r.s);
    if (sid !== null && !tokens.has(sid)) {
      tokens.set(sid, "--chart-series-" + (tokens.size + 1));
      titles.set(sid, String(r.s));
    }
    if (sid !== null && on.get(sid) !== true) continue;
    const token = sid === null ? "--chart-seq-5" : tokens.get(sid);
    let shape;
    if (r.kind === "box") shape = { kind: "box", x: r2(plot.x + r.a * plot.w), y: r2(plot.y + r.b * plot.h), w: r2(r.c * plot.w), h: r2(r.d * plot.h) };
    else if (r.kind === "point") shape = { kind: "point", x: r2(plot.x + r.a * plot.w + (r.e || 0)), y: r2(plot.y + r.b * plot.h + (r.f || 0)), r: r2(Math.min(r.c, big)) };
    else shape = { kind: "sector", cx, cy, r0: r2(r.c * radius), r1: r2(r.d * radius), from: r.a, to: r.b };
    const c = cards[String(r.id)];
    const card = c
      ? {
          heading: c.heading === undefined ? null : c.heading,
          rows: c.rows.map((w) => {
            const t = w.colour === "series" ? token : w.colour;
            return { name: w.name, value: w.value, colour: t, color: t === null ? null : theme.css(t), bold: w.bold === true };
          }),
          keep: c.keep,
          extra: c.extra || [],
        }
      : { heading: null, rows: [{ name: sid === null ? "Mark" : titles.get(sid), value: String(r.id), colour: token, color: theme.css(token), bold: false }], keep: 0, extra: [] };
    const m = { series: sid, datum: r.id, shape, row: r.row === null ? 0 : r.row, key: r.key === null ? true : r.key, near: r.near === null ? false : r.near, emphasis: r.emph, card };
    Object.assign(m, geometry(shape));
    out2.push(m);
  }
  return out2;
}

/** anchor, box (and out) by the test's own arithmetic. */
function geometry(s) {
  if (s.kind === "box") return { anchor: { x: s.x + s.w / 2, y: s.y + s.h / 2 }, box: { x: s.x, y: s.y, w: s.w, h: s.h } };
  if (s.kind === "point") return { anchor: { x: s.x, y: s.y }, box: { x: s.x - s.r, y: s.y - s.r, w: 2 * s.r, h: 2 * s.r } };
  const mid = (s.from + s.to) / 2, rm = (s.r0 + s.r1) / 2;
  const at = (r, a) => [s.cx + r * Math.sin(a * DEG), s.cy - r * Math.cos(a * DEG)];
  const pts = [at(s.r0, s.from), at(s.r0, s.to), at(s.r1, s.from), at(s.r1, s.to)];
  for (let d = -720; d <= 1080; d += 90) if (d >= s.from && d <= s.to) pts.push(at(s.r1, d));
  const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
  return {
    anchor: { x: at(rm, mid)[0], y: at(rm, mid)[1] },
    box: { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) },
    out: { x: Math.sin(mid * DEG), y: -Math.cos(mid * DEG) },
  };
}

/** Whether [m] (an expected mark) holds (x, y) by D4 step 3: the test's own rule, with hypot and atan2. */
function holdsO(m, x, y) {
  const s = m.shape;
  if (s.kind === "box") return x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h;
  if (s.kind === "point") return Math.hypot(x - s.x, y - s.y) <= s.r;
  const dx = x - s.cx, dy = y - s.cy, d = Math.hypot(dx, dy);
  if (d < s.r0 || d > s.r1) return false;
  if (s.to - s.from >= 360 || d === 0) return true;
  const ang = Math.atan2(dx, -dy) / DEG;
  return (((ang - s.from) % 360) + 360) % 360 <= s.to - s.from;
}

/** The test's answer for a place (D4): −1 outside the plot; the last shape holding it; else the nearest near point. */
function oracle(marks, plot, x, y, reach) {
  if (marks.length === 0 || x < plot.x || x > plot.x + plot.w || y < plot.y || y > plot.y + plot.h) return -1;
  let found = -1;
  marks.forEach((m, i) => {
    if (!m.near && holdsO(m, x, y)) found = i;
  });
  if (found >= 0) return found;
  let best = Infinity;
  marks.forEach((m, i) => {
    if (!m.near) return;
    const d = Math.hypot(x - m.shape.x, y - m.shape.y);
    if (d <= reach && d < best) {
      best = d;
      found = i;
    }
  });
  return found;
}

const draw = (E, fx, w, h, extra = {}) => E.render(typeInputOf(fx, w, h, extra));
const pos = (out, id) => out.index.marks.findIndex((m) => m.datum === id);
const markOf = (out, id) => out.index.marks[pos(out, id)];

function close(actual, expected, what) {
  for (const k of Object.keys(expected)) assert.ok(Math.abs(actual[k] - expected[k]) <= 0.011, `${what}.${k}: ${actual[k]} vs ${expected[k]}`);
  assert.deepEqual(plain(Object.keys(actual)), Object.keys(expected), what + " members");
}

// ------------------------------------------------------------------------------------------------- A.1, the grid

test("A.1: every fixture at three frames, a grid of places 8 px apart over the whole frame, each answer the test's own", () => {
  let places = 0, finds = 0;
  const bad = [];
  for (const [name, fx] of Object.entries(FX)) {
    for (const [w, h] of FRAMES) {
      const out = draw(R, fx, w, h);
      const marks = Array.isArray(out.index.marks) ? expectedMarks(fx, out) : [];
      for (let x = 0; x <= w; x += 8) {
        for (let y = 0; y <= h; y += 8) {
          places++;
          const got = R.lookup.at(out.index, x, y, REACH), want = marks.length === 0 ? -1 : oracle(marks, out.index.plot, x, y, REACH);
          if (got >= 0) finds++;
          if (got !== want) bad.push(`${name} ${w}x${h} at ${x},${y}: ${got} vs ${want}`);
        }
      }
    }
  }
  console.log(`A.1 grid: ${Object.keys(FX).length} fixtures x ${FRAMES.length} frames, ${places} places, ${finds} found a mark`);
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " differences");
  assert.ok(places >= 2000, "places: " + places);
  assert.ok(finds >= 200, "finds: " + finds);
});

// --------------------------------------------------------------------------- A.1 by name, each a check of an engine

const W = 960, H = 540;

const CHECKS = {
  "nearest in two dimensions": (E) => {
    const out = draw(E, FX.points, W, H), p = markOf(out, "x-near").shape, q = markOf(out, "xy-near").shape;
    const x = p.x + 1, y = q.y;
    assert.ok(Math.abs(x - p.x) < Math.abs(x - q.x), "along x the nearest is x-near");
    assert.ok(Math.hypot(x - q.x, y - q.y) < Math.hypot(x - p.x, y - p.y), "in two dimensions the nearest is xy-near");
    assert.equal(E.lookup.at(out.index, x, y, REACH), pos(out, "xy-near"), `place ${x},${y}: the mark at that position, xy-near (${pos(out, "xy-near")}), not x-near (${pos(out, "x-near")})`);
  },
  "ties: two equally near, the first; two at one place, the first": (E) => {
    const out = draw(E, FX.points, W, H), a = markOf(out, "tie-a").shape, b = markOf(out, "tie-b").shape;
    const x = (a.x + b.x) / 2, y = a.y;
    assert.equal(Math.hypot(x - a.x, y - a.y), Math.hypot(x - b.x, y - b.y), "equally near");
    assert.ok(Math.hypot(x - a.x, y - a.y) <= REACH);
    assert.equal(E.lookup.at(out.index, x, y, REACH), pos(out, "tie-a"));
    const s = markOf(out, "same-1").shape, t = markOf(out, "same-2").shape;
    assert.deepEqual([s.x, s.y], [t.x, t.y], "one place");
    assert.equal(E.lookup.at(out.index, s.x, s.y, REACH), pos(out, "same-1"));
  },
  "overlapping circles 3 px apart: the one whose centre the place is on, in either order": (E) => {
    for (const order of [null, ["lap-2", "lap-1"]]) {
      const fx = order === null ? FX.points : edited(FX.points, { order: [...rowsOf(FX.points).map((r) => r.id).filter((i) => !order.includes(i)), ...order] });
      const out = draw(E, fx, W, H), one = markOf(out, "lap-1").shape, two = markOf(out, "lap-2").shape;
      assert.equal(two.x - one.x, 3, "centres 3 px apart");
      assert.ok(Math.hypot(one.x - two.x, one.y - two.y) <= two.r, "the second's circle holds the place");
      assert.equal(E.lookup.at(out.index, one.x, one.y, REACH), pos(out, "lap-1"), "order " + JSON.stringify(order));
      assert.equal(E.lookup.at(out.index, two.x, two.y, REACH), pos(out, "lap-2"));
    }
  },
  "two boxes holding the place: the last (over)": (E) => {
    const out = draw(E, FX.over, W, H), small = markOf(out, "small");
    assert.ok(holdsO(markOf(out, "large"), small.anchor.x, small.anchor.y));
    assert.equal(E.lookup.at(out.index, small.anchor.x, small.anchor.y, REACH), pos(out, "small"), `place ${small.anchor.x},${small.anchor.y}: small (${pos(out, "small")}), not large (${pos(out, "large")})`);
  },
  "on a mark before near a point": (E) => {
    const out = draw(E, FX.points, W, H), b = markOf(out, "box").shape, p = markOf(out, "by-box").shape;
    const gap = p.x - p.r - (b.x + b.w);
    assert.ok(gap > 0 && gap < REACH, "the near point stands just outside the box: gap " + gap);
    const x = b.x + b.w - 2, y = p.y;
    assert.ok(Math.hypot(x - p.x, y - p.y) <= REACH, "the place is within the near point's reach");
    assert.equal(E.lookup.at(out.index, x, y, REACH), pos(out, "box"), `place ${x},${y}: box (${pos(out, "box")}), not by-box (${pos(out, "by-box")})`);
    const gone = draw(E, edited(FX.points, { drop: ["box"] }), W, H);
    assert.equal(E.lookup.at(gone.index, x, y, REACH), pos(gone, "by-box"), "without the box, the point");
  },
  "the reach's edge": (E) => {
    const out = draw(E, FX.points, W, H), e = markOf(out, "edge").shape;
    const x = e.x + REACH;
    assert.equal(x - e.x, REACH, "exactly reach");
    assert.equal(E.lookup.at(out.index, x, e.y, REACH), pos(out, "edge"), `exactly reach from edge, at ${x},${e.y}`);
    assert.equal(E.lookup.at(out.index, x + 0.01, e.y, REACH), -1);
    const big = markOf(out, "big").shape;
    assert.equal(big.r, 32, "the wide point is drawn with a radius of 32 at 960 x 540");
    const marks = expectedMarks(FX.points, out);
    for (const d of [24, 25]) {
      const px = big.x + d;
      assert.equal(px - big.x, d);
      const others = marks.filter((m) => m.datum !== "big" && (holdsO(m, px, big.y) || (m.near && Math.hypot(px - m.shape.x, big.y - m.shape.y) <= REACH)));
      assert.deepEqual(others.map((m) => m.datum), [], "no other mark within reach of the place");
      assert.ok(Math.hypot(px - big.x, 0) < big.r, "inside its circle");
    }
    assert.equal(E.lookup.at(out.index, big.x + 25, big.y, REACH), -1, "25 px from the centre, inside the circle: none");
    assert.equal(E.lookup.at(out.index, big.x + 24, big.y, REACH), pos(out, "big"), "24 px: the point");
    assert.equal(E.lookup.at(out.index, big.x, big.y, 0), pos(out, "big"), "reach 0: at the centre");
    assert.equal(E.lookup.at(out.index, big.x + 0.01, big.y, 0), -1, "reach 0: 0.01 off the centre none");
    const boxes = draw(E, FX["box-and-outside"], W, H), o = markOf(boxes, "out-north-high").shape;
    assert.equal(markOf(boxes, "out-north-high").near, false);
    assert.equal(o.x + o.r - o.x, o.r);
    for (const reach of [0, REACH, 100]) {
      assert.equal(E.lookup.at(boxes.index, o.x + o.r, o.y, reach), pos(boxes, "out-north-high"), "on the circle's edge, reach " + reach);
      assert.equal(E.lookup.at(boxes.index, o.x + o.r + 0.01, o.y, reach), -1, "0.01 outside, reach " + reach);
    }
  },
  "the plot's edges": (E) => {
    const plot = { x: 10, y: 20, w: 100, h: 50 };
    const series = [{ id: "s" }], visible = { s: true };
    const full = { series: "s", datum: "all", shape: { kind: "box", x: 10, y: 20, w: 100, h: 50 }, card: { rows: [{ name: "a", value: "1" }], keep: 0 } };
    const prims = [E.prim.rect({ role: "mark", x: 10, y: 20, w: 100, h: 50, series: "s", datum: "all" })];
    const ix = E.lookup.index(plot, series, visible, [full], prims);
    const edges = [[10, 45, -0.01, 0], [110, 45, 0.01, 0], [60, 20, 0, -0.01], [60, 70, 0, 0.01]];
    for (const [x, y, dx, dy] of edges) {
      assert.equal(E.lookup.at(ix, x, y, REACH), 0, `on the edge ${x},${y}`);
      assert.equal(E.lookup.at(ix, x + dx, y + dy, REACH), -1, `0.01 beyond ${x},${y}`);
    }
    const near = edges.map(([x, y, dx, dy], i) => ({ series: "s", datum: i, shape: { kind: "point", x: x - 100 * dx, y: y - 100 * dy, r: 0 }, near: true, card: { rows: [{ name: "a", value: "1" }], keep: 0 } }));
    const nprims = near.map((n) => E.prim.circle({ role: "mark", cx: n.shape.x, cy: n.shape.y, r: 0, series: "s", datum: n.datum }));
    const nix = E.lookup.index(plot, series, visible, near, nprims);
    edges.forEach(([x, y, dx, dy], i) => {
      assert.equal(E.lookup.at(nix, x, y, REACH), i, "the near point 1 px inside is found from the edge");
      assert.equal(E.lookup.at(nix, x + dx, y + dy, REACH), -1, "0.01 beyond the edge, a near point 1 px away: none");
    });
  },
  "a sector's edges on the four axis directions; spans of 30, 180, 240 and 360": (E) => {
    const plot = { x: 0, y: 0, w: 200, h: 200 };
    const at = (r, a) => [100 + r * Math.sin(a * DEG), 100 - r * Math.cos(a * DEG)];
    const index = (from, to) => {
      const e = { series: null, datum: "s", shape: { kind: "sector", cx: 100, cy: 100, r0: 20, r1: 60, from, to }, card: { rows: [{ name: "a", value: "1" }], keep: 0 } };
      return E.lookup.index(plot, [], {}, [e], [E.prim.circle({ role: "mark", cx: 100, cy: 100, r: 60, datum: "s" })]);
    };
    const yes = (ix, [x, y], what) => assert.equal(E.lookup.at(ix, x, y, REACH), 0, what + ` (${x}, ${y})`);
    const no = (ix, [x, y], what) => assert.equal(E.lookup.at(ix, x, y, REACH), -1, what + ` (${x}, ${y})`);
    // 30°: its edge at 0 (from) and, for [60, 90], at 90 (to).
    const a = index(0, 30), b = index(60, 90);
    yes(a, [100, 60], "30: on the edge at from");
    no(a, at(40, -0.5), "30: 0.5° before from");
    yes(b, [140, 100], "30: on the edge at to");
    no(b, at(40, 90.5), "30: 0.5° after to");
    yes(a, [100, 80], "on r0 at from");
    no(a, [100, 80.01], "0.01 inside r0");
    yes(a, [100, 40], "on r1 at from");
    no(a, [100, 39.99], "0.01 beyond r1");
    // 180°, [0, 180]: both edges on axis directions.
    const c = index(0, 180);
    yes(c, [100, 60], "180: on from");
    yes(c, [100, 140], "180: on to");
    yes(c, [140, 100], "180: the middle");
    no(c, [60, 100], "180: the opposite side");
    no(c, at(40, -0.5), "180: 0.5° before from");
    no(c, at(40, 180.5), "180: 0.5° after to");
    // 240°, [90, 330]: the part left out is (330, 450).
    const d = index(90, 330);
    yes(d, [140, 100], "240: on from");
    no(d, at(40, 89.5), "240: 0.5° before from");
    yes(d, [100, 140], "240: at 180");
    yes(d, [60, 100], "240: at 270");
    no(d, [100, 60], "240: at 0, in the part left out");
    yes(d, [100, 160], "240: on r1 at 180");
    no(d, [100, 160.01], "240: 0.01 beyond r1");
    // 360°: every direction.
    const f = index(0, 360);
    for (let k = 0; k < 8; k++) yes(f, at(40, 45 * k).map(r2), "360: direction " + 45 * k);
    no(f, [100, 39.99], "360: 0.01 beyond r1");
    no(f, [100, 80.01], "360: 0.01 inside r0");
  },
  "the empty index in its two forms answers -1 everywhere": (E) => {
    const forms = [["empty", draw(E, FX.empty, W, H)], ["all-null", draw(E, FX["all-null"], W, H)]];
    const bar = chartFixtures().find((f) => f.wire === "bar");
    forms.push(["a product draw", E.render(inputOf(bar.fixture, W, H))]);
    const hidden = ["string:Small", "string:Half", "string:Rest"];
    forms.push(["slices, every series hidden", draw(E, FX.slices, W, H, { hidden })]);
    for (const [what, out] of forms) {
      for (let x = 0; x <= W; x += 8) for (let y = 0; y <= H; y += 8) if (E.lookup.at(out.index, x, y, REACH) !== -1) assert.fail(`${what} at ${x},${y}`);
    }
    assert.deepEqual(plain(forms[3][1].index.marks), []);
  },
  "a reach of -1, NaN or a text throws": (E) => {
    const out = draw(E, FX.points, W, H);
    for (const reach of [-1, NaN, "24"]) assert.throws(() => E.lookup.at(out.index, 100, 100, reach), /reach is not a finite number of 0 or more/, String(reach));
  },
};

for (const [name, check] of Object.entries(CHECKS)) test("A.1: " + name, () => check(R));

test("A.1: a zero-span sector holds its own ray and not the opposite ray", () => {
  const plot = { x: 0, y: 0, w: 200, h: 200 };
  for (const [angle, dx, dy] of [[0, 0, -1], [90, 1, 0], [180, 0, 1], [270, -1, 0], [-90, -1, 0], [360, 0, -1]]) {
    for (const r0 of [0, 20]) {
      const entry = { series: null, datum: "zero", shape: { kind: "sector", cx: 100, cy: 100, r0, r1: 60, from: angle, to: angle }, card: { rows: [{ name: "Zero", value: "0" }], keep: 0 } };
      const ix = R.lookup.index(plot, [], {}, [entry], [R.prim.circle({ role: "mark", cx: 100, cy: 100, r: 60, datum: "zero" })]);
      for (const radius of [20, 40, 60]) {
        assert.equal(R.lookup.at(ix, 100 + dx * radius, 100 + dy * radius, REACH), 0, `${angle}: on its ray at radius ${radius}`);
        assert.equal(R.lookup.at(ix, 100 - dx * radius, 100 - dy * radius, REACH), -1, `${angle}: opposite ray at radius ${radius}`);
      }
      assert.equal(R.lookup.at(ix, 100, 100, REACH), r0 === 0 ? 0 : -1, `${angle}: the centre follows the inner radius`);
      assert.equal(R.lookup.at(ix, 100 + dx * 60.01, 100 + dy * 60.01, REACH), -1, `${angle}: beyond the outer radius`);
      assert.equal(R.lookup.at(ix, 100 - dy * 40, 100 + dx * 40, REACH), -1, `${angle}: beside the ray`);
    }
  }
});

// ------------------------------------------------------------------------------------------ A.2, the second form

test("A.2: for every fixture at three frames the index's marks are the test's own, member by member; series and summary", () => {
  let compared = 0;
  for (const [name, fx] of Object.entries(FX)) {
    for (const [w, h] of FRAMES) {
      const out = draw(R, fx, w, h);
      if (out.state !== "rendered") continue;
      assert.equal(out.summary.marks, fx.expect.marks, `${name} ${w}x${h}: summary.marks`);
      const want = expectedMarks(fx, out), got = out.index.marks;
      assert.equal(got.length, want.length, name);
      got.forEach((m, i) => {
        const where = `${name} ${w}x${h} mark ${i}`;
        for (const k of ["series", "datum", "shape", "row", "key", "near", "emphasis", "card"]) assert.deepEqual(plain(m[k]), want[i][k], `${where}: ${k}`);
        close(m.anchor, want[i].anchor, where + " anchor");
        close(m.box, want[i].box, where + " box");
        if (m.shape.kind === "sector") close(m.out, want[i].out, where + " out");
        else assert.equal(m.out, undefined, where + ": only a sector has out");
        compared++;
      });
      assert.equal(out.index.axis, null);
      assert.deepEqual(plain(out.index.series.map((s) => [s.at, s.datum, s.tip])), plain(out.index.series.map(() => [[], [], []])), "three empty lists per series");
      assert.deepEqual(plain(out.index.series.map((s) => s.id)), plain(out.summary.series.map((s) => s.id)), "every series of the draw");
      for (const s of out.index.series) assert.equal(R.nearest(out.index, s.id, 100), null);
    }
  }
  console.log(`A.2: ${compared} marks compared member by member`);
  assert.ok(compared >= 150, "marks compared: " + compared);
});

test("A.2: the two empty forms are told apart", () => {
  for (const name of ["empty", "all-null"]) {
    const out = draw(R, FX[name], W, H);
    assert.equal(out.state, "no-data", name);
    assert.deepEqual(plain(Object.keys(out.index)), ["axis", "plot", "series"], name + ": no member marks");
  }
  const hidden = draw(R, FX.slices, W, H, { hidden: ["string:Small", "string:Half", "string:Rest"] });
  assert.equal(hidden.state, "rendered");
  assert.deepEqual(plain(hidden.index.marks), []);
  assert.equal(hidden.summary.marks, 0);
  assert.ok(hidden.primitives.some((p) => p.k === "group" && p.role === "legend"), "its legend is drawn");
});

test("A.2: a product draw (bar, line, area) has no member marks in its index", () => {
  for (const wire of ["bar", "line", "area"]) {
    const f = chartFixtures().find((x) => x.wire === wire && R.render(inputOf(x.fixture, W, H)).state === "rendered");
    const out = R.render(inputOf(f.fixture, W, H));
    assert.equal(Object.prototype.hasOwnProperty.call(out.index, "marks"), false, `${wire}/${f.name}`);
  }
});

test("A.2: a template's result with both lookup and index throws naming both", () => {
  const anchor = "return { primitives: loose.concat(out), lookup: lookup, count: lookup.length };";
  const E = engineWith(NAMES, (s) => {
    const k = testTypeKey("test_marks");
    assert.equal(s[k].split(anchor).length, 2);
    s[k] = s[k].replace(anchor, "return { primitives: loose.concat(out), lookup: lookup, count: lookup.length, index: [] };");
  });
  assert.throws(() => draw(E, FX.cells, W, H), /lookup or index, not both/);
});

// The refusals (D3's table), on R.lookup.index directly.
const PLOT = { x: 0, y: 0, w: 100, h: 100 };
const SERIES = [{ id: "s" }];
const VISIBLE = { s: true };
function base() {
  return { series: "s", datum: 1, shape: { kind: "box", x: 10, y: 10, w: 20, h: 20 }, row: 0, key: true, near: false, emphasis: null, card: { heading: "H", rows: [{ name: "n", value: "v", colour: "--chart-series-1", color: "#000000", bold: false }], keep: 0, extra: [] } };
}
const PRIMS = () => [R.prim.rect({ role: "mark", x: 10, y: 10, w: 20, h: 20, series: "s", datum: 1 }), R.prim.circle({ role: "mark", cx: 50, cy: 50, r: 3, series: "s", datum: 2 }), R.prim.rect({ role: "mark", x: 0, y: 0, w: 1, h: 1, series: null, datum: "c" })];
const build = (list) => R.lookup.index(PLOT, SERIES, VISIBLE, list, PRIMS());

/** [what, edit(entry, list) → list, member named] */
const REFUSALS = [];
const refuse = (what, member, edit) => REFUSALS.push({ what, member, edit });
const set = (path, v) => (e) => {
  const keys = path.split(".");
  let o = e;
  for (const k of keys.slice(0, -1)) o = o[k];
  o[keys[keys.length - 1]] = v;
};
const drop = (path) => (e) => {
  const keys = path.split(".");
  let o = e;
  for (const k of keys.slice(0, -1)) o = o[k];
  delete o[keys[keys.length - 1]];
};
// a wrong kind, member by member
refuse("series a number", "series", set("series", 7));
refuse("series not of the draw", "series", set("series", "other"));
refuse("datum an object", "datum", set("datum", { a: 1 }));
refuse("datum NaN", "datum", set("datum", NaN));
refuse("datum true", "datum", set("datum", true));
refuse("shape a list", "shape", set("shape", []));
refuse("shape kind unknown", "shape.kind", set("shape.kind", "circle"));
refuse("shape a member too many", "shape.r", set("shape.r", 3));
refuse("shape a member missing", "shape.h", drop("shape.h"));
refuse("shape a member a text", "shape.x", set("shape.x", "10"));
refuse("shape a member Infinity", "shape.w", set("shape.w", Infinity));
refuse("row 0.5", "row", set("row", 0.5));
refuse("row -1", "row", set("row", -1));
refuse("row a text", "row", set("row", "0"));
refuse("key \"false\"", "key", set("key", "false"));
refuse("key null", "key", set("key", null));
refuse("near 1", "near", set("near", 1));
refuse("emphasis unknown", "emphasis", set("emphasis", "glow"));
refuse("emphasis true", "emphasis", set("emphasis", true));
refuse("card a list", "card", set("card", []));
refuse("card null", "card", set("card", null));
refuse("heading 7", "card.heading", set("card.heading", 7));
refuse("rows an object", "card.rows", set("card.rows", { a: 1 }));
refuse("rows empty", "card.rows", set("card.rows", []));
refuse("a row null", "card.rows[0]", set("card.rows", [null]));
refuse("keep 1.5", "card.keep", set("card.keep", 1.5));
refuse("keep beyond the rows", "card.keep", set("card.keep", 1));
refuse("keep a text", "card.keep", set("card.keep", "0"));
refuse("extra [42]", "card.extra[0]", set("card.extra", [42]));
refuse("extra a text", "card.extra", set("card.extra", "a"));
refuse("name a number", "card.rows[0].name", set("card.rows.0.name", 3));
refuse("value a number", "card.rows[0].value", set("card.rows.0.value", 3));
refuse("colour a number", "card.rows[0].colour", set("card.rows.0.colour", 3));
refuse("color without colour", "card.rows[0].colour", set("card.rows.0.colour", null));
refuse("colour without color", "card.rows[0].colour", set("card.rows.0.color", null));
refuse("bold \"yes\"", "card.rows[0].bold", set("card.rows.0.bold", "yes"));
// a member whose last column says "throws", left out
for (const m of ["series", "datum", "shape", "card"]) refuse(m + " left out", m, drop(m));
for (const m of ["rows", "keep"]) refuse("card." + m + " left out", "card." + m, drop("card." + m));
for (const m of ["name", "value"]) refuse("a row's " + m + " left out", "card.rows[0]." + m, drop("card.rows.0." + m));
refuse("shape.kind left out", "shape.kind", drop("shape.kind"));
// an unknown member on each structure
refuse("unknown on an entry", "colour", set("colour", 1));
refuse("unknown on a shape", "shape.depth", set("shape.depth", 1));
refuse("unknown on a card", "card.footer", set("card.footer", "x"));
refuse("unknown on a row", "card.rows[0].key", set("card.rows.0.key", true));
// across members
refuse("negative w", "shape.w", set("shape.w", -1));
refuse("negative h", "shape.h", set("shape.h", -1));
refuse("negative r", "shape.r", (e) => {
  e.shape = { kind: "point", x: 50, y: 50, r: -1 };
  e.datum = 2;
});
const sector = (more) => (e) => {
  e.shape = Object.assign({ kind: "sector", cx: 50, cy: 50, r0: 0, r1: 10, from: 0, to: 90 }, more);
  e.emphasis = null;
};
refuse("r0 below 0", "shape.r0", sector({ r0: -1 }));
refuse("r0 above r1", "shape.r0", sector({ r0: 11 }));
refuse("to before from", "shape.to", sector({ to: -1 }));
refuse("to beyond from + 360", "shape.to", sector({ to: 360.5 }));
refuse("near on a box", "near", set("near", true));
refuse("ring on a sector", "emphasis", (e) => {
  sector({})(e);
  e.emphasis = "ring";
});
refuse("move on a box", "emphasis", set("emphasis", "move"));
refuse("move on a point", "emphasis", (e) => {
  e.shape = { kind: "point", x: 50, y: 50, r: 3 };
  e.datum = 2;
  e.emphasis = "move";
});
refuse("no mark primitive with the pair", "datum", set("datum", 99));
refuse("no mark primitive of that series", "datum", set("series", null));

test("A.2: every refusal of D3's table throws naming the entry's position and the member", () => {
  for (const r of REFUSALS) {
    const e = base();
    r.edit(e);
    const list = [base(), e];
    list[0].datum = "c";
    list[0].series = null;
    assert.throws(() => build(list), (err) => {
      assert.ok(err.message.startsWith("lookup: entry 1: " + r.member + " "), `${r.what}: ${err.message}`);
      return true;
    }, r.what);
  }
  // a repeated (series, datum); the template's lookup that is no list; an entry that is null
  assert.throws(() => build([base(), base()]), /^Error: lookup: entry 1: datum repeats/);
  assert.throws(() => build({}), /lookup: the template's result: lookup is not a list/);
  assert.throws(() => build([null]), /lookup: entry 0 is not an object/);
  assert.throws(() => build([base(), 7]), /lookup: entry 1 is not an object/);
  const count = REFUSALS.length + 4;
  console.log(`A.2 refusals: ${count} refusal tests`);
  assert.ok(count >= 40, "refusal tests: " + count);
});

test("A.2: every member with a default, left out and supplied as undefined, takes its default in the built index", () => {
  const defaults = { row: 0, key: true, near: false, emphasis: null, "card.heading": null, "card.extra": [], "card.rows.0.colour": null, "card.rows.0.color": null, "card.rows.0.bold": false };
  for (const [path, want] of Object.entries(defaults)) {
    for (const how of ["left out", "undefined"]) {
      const e = base();
      if (path.startsWith("card.rows.0.colour") || path.startsWith("card.rows.0.color")) {
        if (how === "left out") {
          delete e.card.rows[0].colour;
          delete e.card.rows[0].color;
        } else {
          e.card.rows[0].colour = undefined;
          e.card.rows[0].color = undefined;
        }
      } else if (how === "left out") drop(path)(e);
      else set(path, undefined)(e);
      const m = build([e]).marks[0];
      const got = path.split(".").reduce((o, k) => o[k], m);
      assert.deepEqual(plain(got), want, `${path} ${how}`);
    }
  }
});

// ------------------------------------------------------------------------------- A.7: the sweep's test_marks floors

test("A.7: test_marks at the sweep's 35 widths and 5 heights: I1, I2, I4, I5 and I3, with floors", () => {
  const seen = { pictures: 0, rendered: 0, texts: 0, marks: 0 };
  const bad = [];
  for (const [name, fx] of Object.entries(FX)) {
    for (const w of WIDTHS) {
      for (const h of HEIGHTS) {
        const out = draw(R, fx, w, h), v = violations(out.primitives, w, h);
        seen.pictures++;
        seen.texts += v.texts;
        seen.marks += v.marks;
        if (out.state === "rendered") {
          seen.rendered++;
          if (out.summary.marks !== fx.expect.marks) bad.push(`${name} ${w}x${h}: summary.marks ${out.summary.marks}`);
          if (out.index.marks.length !== fx.expect.marks) bad.push(`${name} ${w}x${h}: index.marks ${out.index.marks.length}`);
        }
        for (const x of v.violations) bad.push(`${name} ${x.inv} at ${w}x${h}: ${x.what}`);
      }
    }
  }
  console.log(`A.7 test_marks sweep: ${Object.keys(FX).length} fixtures x 175 frames = ${seen.pictures} pictures (${seen.rendered} rendered), ${seen.texts} texts, ${seen.marks} marks`);
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " violations");
  assert.ok(Object.keys(FX).length >= 11, "fixtures");
  assert.equal(seen.pictures, Object.keys(FX).length * 175);
  assert.ok(seen.rendered >= 1500, "rendered: " + seen.rendered);
  assert.ok(seen.texts >= 2000, "texts: " + seen.texts);
  assert.ok(seen.marks >= 9000, "marks: " + seen.marks);
});

// ------------------------------------------------------------------------------------------- the gates, both ways

/** A copy of the engine with one plant in 21-lookup.js (the anchor occurs exactly once). */
function planted(pairs) {
  return engineWith(NAMES, (s) => {
    for (const [from, to] of pairs) {
      assert.equal(s[LOOKUP].split(from).length, 2, "the plant's anchor occurs once: " + from);
      s[LOOKUP] = s[LOOKUP].replace(from, to);
    }
  });
}

const STEP3 = "for (var i = 0; i < marks.length; i++) if (marks[i].near !== true && holds(marks[i], x, y)) found = i;\n    if (found >= 0) return found;\n";
const GATES = [
  ["G1 the distance along x only", [["var d = distance(x - marks[j].shape.x, y - marks[j].shape.y);", "var d = distance(x - marks[j].shape.x, 0);"]], ["nearest in two dimensions"]],
  ["G1 the reach exclusive", [["if (d <= reach && d < best)", "if (d < reach && d < best)"]], ["the reach's edge"]],
  ["G1 the reach applied to a point with near false", [["if (s.kind === \"point\") return distance(x - s.x, y - s.y) <= s.r;", "if (s.kind === \"point\") return distance(x - s.x, y - s.y) <= Math.max(s.r, 24);"]], ["the reach's edge"]],
  ["G1 a near point also taken by its circle", [["if (marks[i].near !== true && holds(marks[i], x, y)) found = i;", "if (holds(marks[i], x, y)) found = i;"]], ["the reach's edge", "overlapping circles 3 px apart: the one whose centre the place is on, in either order"]],
  ["G2 step 4 before step 3", [[STEP3, ""], ["    return found;\n  }\n\n  R.lookup", "    if (found >= 0) return found;\n    " + STEP3 + "    return found;\n  }\n\n  R.lookup"]], ["on a mark before near a point"]],
  ["G2 the first holder instead of the last", [["if (marks[i].near !== true && holds(marks[i], x, y)) found = i;", "if (marks[i].near !== true && holds(marks[i], x, y) && found < 0) found = i;"]], ["two boxes holding the place: the last (over)"]],
  ["G3 the rule for a span above 180 dropped", [["if (span <= 180) return", "if (span < 360) return"]], ["a sector's edges on the four axis directions; spans of 30, 180, 240 and 360"]],
  ["G3 an edge exclusive", [["return side(s.from, vx, vy) >= 0 && side(s.to, vx, vy) <= 0;", "return side(s.from, vx, vy) > 0 && side(s.to, vx, vy) < 0;"]], ["a sector's edges on the four axis directions; spans of 30, 180, 240 and 360"]],
  ["G4 step 2 removed", [["if (!plot || x < plot.x || x > plot.x + plot.w || y < plot.y || y > plot.y + plot.h) return -1;", ""]], ["the plot's edges"]],
];

for (const [name, pairs, checks] of GATES) {
  test(name + ": red on the planted copy, green on the clean engine", () => {
    const E = planted(pairs);
    for (const c of checks) {
      let err = null;
      try {
        CHECKS[c](E);
      } catch (e) {
        err = e;
      }
      assert.ok(err !== null, `${name}: "${c}" stayed green on the plant`);
      console.log(`${name}: "${c}" red: ${String(err.message).split("\n")[0]}`);
      CHECKS[c](R);
    }
  });
}

/** G10: test_marks' "edge" point moved to x = 302 in a 300 × 180 frame is an I2 violation naming the mark. */
const G10_ANCHOR = "      if (r.series === null) loose.push(prim);";
const G10_PLANT = '      if (r.datum === "edge" && plot.x + plot.w === 294) prim = P.circle({ role: "mark", cx: 302, cy: prim.cy, r: prim.r, fill: prim.fill, color: prim.color, series: prim.series, datum: prim.datum });\n' + G10_ANCHOR;

test("G10: a point of test_marks at x = 302 in a 300 x 180 frame is an I2 violation naming the mark; without the plant none", () => {
  const E = engineWith(NAMES, (s) => {
    const k = testTypeKey("test_marks");
    assert.equal(s[k].split(G10_ANCHOR).length, 2, "the plant's anchor occurs once");
    s[k] = s[k].replace(G10_ANCHOR, G10_PLANT);
  });
  const bad = violations(draw(E, FX.points, 300, 180).primitives, 300, 180).violations;
  assert.equal(bad.length, 1, JSON.stringify(bad));
  assert.equal(bad[0].inv, "I2");
  assert.match(bad[0].what, /^mark of string:B leaves the frame: 298,[\d.]+,306,[\d.]+$/);
  console.log("G10 red: " + bad[0].what);
  assert.equal(violations(draw(R, FX.points, 300, 180).primitives, 300, 180).violations.length, 0);
});
