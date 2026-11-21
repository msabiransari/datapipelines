// Brief 1049 A.3 (D4, O-16): the engine's own sine and cosine and the arc helper, and the shared checker's arc bounds
// (D5). The test may call Math.sin and Math.cos as the oracle: it is not engine code (R-BAN scans the engine and the
// test types only). Each gate is falsified here too (G4, G5): the same check run over a planted copy must fail.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine } from "./engine.mjs";
import { violations, markBox } from "./geometry.mjs";

const R = engine();
const T = R.trig;
const RAD = Math.PI / 180;
/** Every multiple of 0.25° from −720° to 720°: 5,761 angles (the grid trig.json carries). */
const GRID = Array.from({ length: 5761 }, (_, i) => -720 + i / 4);

/** The angles of the grid where the engine's value is more than 1e-12 from the runtime's, as "sin(a)" texts. */
function farFromRuntime(trig) {
  const bad = [];
  for (const a of GRID) {
    if (Math.abs(trig.sin(a) - Math.sin(a * RAD)) > 1e-12) bad.push(`sin(${a})`);
    if (Math.abs(trig.cos(a) - Math.cos(a * RAD)) > 1e-12) bad.push(`cos(${a})`);
  }
  return bad;
}

/** Bit equality, a zero's sign aside (every zero the engine returns is +0; −sin(a) of a zero is −0). */
function sameBits(a, b) {
  return a === 0 && b === 0 ? true : Object.is(a, b);
}

test("the grid has 5,761 angles from -720 to 720 by 0.25 (non-vacuity)", () => {
  assert.equal(GRID.length, 5761);
  assert.equal(GRID[0], -720);
  assert.equal(GRID[GRID.length - 1], 720);
});

test("D4: the exact values", () => {
  for (const a of [0, 180, 360]) assert.equal(T.sin(a), 0, `sin(${a})`);
  for (const a of [90, 270]) assert.equal(T.cos(a), 0, `cos(${a})`);
  assert.equal(T.sin(90), 1);
  assert.equal(T.cos(0), 1);
  assert.equal(T.sin(30), 0.5);
  assert.equal(T.cos(60), 0.5);
  // assert.equal is Object.is: each zero above is +0, never -0.
  assert.ok(Object.is(T.sin(-180), 0) && Object.is(T.cos(-90), 0), "every zero is +0");
});

test("D4: within 1e-12 of the runtime's own sine and cosine at every angle of the grid, and sin² + cos² within 1e-12 of 1", () => {
  assert.deepEqual(farFromRuntime(T).slice(0, 10), []);
  let worst = 0;
  for (const a of GRID) worst = Math.max(worst, Math.abs(T.sin(a) * T.sin(a) + T.cos(a) * T.cos(a) - 1));
  assert.ok(worst <= 1e-12, "largest |sin² + cos² − 1|: " + worst);
});

test("D4: sin(−a) = −sin(a) and sin(180 − a) = sin(a) bit for bit over the grid", () => {
  const bad = [];
  for (const a of GRID) {
    if (!sameBits(T.sin(-a), -T.sin(a))) bad.push(`sin(-${a})`);
    if (!sameBits(T.sin(180 - a), T.sin(a))) bad.push(`sin(180-${a})`);
  }
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " angles break a symmetry");
});

test("D4: any finite angle is reduced; an angle that is not finite throws", () => {
  assert.ok(Math.abs(T.sin(1e6 + 30) - Math.sin(((1e6 + 30) % 360) * RAD)) <= 1e-12);
  assert.equal(T.sin(36030), 0.5);
  for (const bad of [NaN, Infinity, -Infinity, "30", null]) {
    assert.throws(() => T.sin(bad), /trig: sin takes a finite angle/);
    assert.throws(() => T.cos(bad), /trig: cos takes a finite angle/);
  }
});

test("D4: point(cx, cy, r, deg) is (cx + r sin, cy − r cos), rounded; 0° is 12 o'clock, 90° is 3 o'clock", () => {
  assert.deepEqual({ ...T.point(50, 50, 40, 0) }, { x: 50, y: 10 });
  assert.deepEqual({ ...T.point(50, 50, 40, 90) }, { x: 90, y: 50 });
  assert.deepEqual({ ...T.point(50, 50, 40, 180) }, { x: 50, y: 90 });
  assert.deepEqual({ ...T.point(50, 50, 40, 270) }, { x: 10, y: 50 });
  assert.deepEqual({ ...T.point(0, 0, 10, 30) }, { x: 5, y: -8.66 });
});

/** The pieces of an arc's commands: [{r, x, y}]. */
function pieces(d) {
  const out = [];
  for (let i = 0; i < d.length; i += 4) {
    assert.equal(d[i], "A");
    out.push({ r: d[i + 1], x: d[i + 2], y: d[i + 3] });
  }
  return out;
}

/** The angle a piece spans from (x0, y0) to its end, on the circle (cx, cy), by the runtime's atan2 (the oracle). */
function spanOf(cx, cy, x0, y0, x1, y1) {
  const a0 = Math.atan2(x0 - cx, cy - y0) / RAD, a1 = Math.atan2(x1 - cx, cy - y1) / RAD;
  let s = a1 - a0;
  while (s <= 0) s += 360;
  return s;
}

const SPANS = [[0, 0], [90, 1], [179, 1], [180, 2], [240, 2], [359, 2], [360, 2]];

test("D4: arc at 0°, 90°, 179°, 180°, 240°, 359° and 360°: 0, 1, 1, 2, 2, 2, 2 pieces; none over 180°, exactly 180° only for a full circle's halves", () => {
  const cx = 100, cy = 100, r = 80;
  for (const from of [0, 17.5, -45]) {
    for (const [span, count] of SPANS) {
      const d = T.arc(cx, cy, r, from, from + span), ps = pieces(d);
      assert.equal(ps.length, count, `arc of ${span}° from ${from}°`);
      let at = T.point(cx, cy, r, from);
      for (const p of ps) {
        assert.equal(p.r, r);
        // Every end point on the circle within the rounding (two decimals: half a hundredth on each axis).
        assert.ok(Math.abs(Math.hypot(p.x - cx, p.y - cy) - r) <= 0.01, `end ${p.x},${p.y} on the circle`);
        const s = spanOf(cx, cy, at.x, at.y, p.x, p.y);
        assert.ok(s <= 180 + 0.02, `a piece of ${s}° in an arc of ${span}°`);
        if (span < 360) assert.ok(s < 180 - 0.01, `only a full circle's halves span 180° (a piece of ${s}° in ${span}°)`);
        at = { x: p.x, y: p.y };
      }
      if (count > 0) assert.deepEqual({ x: ps[ps.length - 1].x, y: ps[ps.length - 1].y }, { ...T.point(cx, cy, r, from + span) }, "the last end point is point(to)");
    }
  }
});

test("D4: the full circle ends where it starts, both writers write it as two arcs, and the path primitive takes it", () => {
  const start = T.point(60, 60, 50, 0), d = ["M", start.x, start.y].concat(T.arc(60, 60, 50, 0, 360));
  assert.deepEqual([d[d.length - 2], d[d.length - 1]], [start.x, start.y]);
  const p = R.prim.path({ role: "mark", d, stroke: "--chart-series-1", strokeColor: "#3b5bdb", strokeWidth: 2, series: "s" });
  assert.deepEqual([...p.d], d, "the path primitive takes the commands unchanged");
  const svg = R.writeString([R.prim.rect({ role: "guide", x: 0, y: 0, w: 120, h: 120 }), p], "standalone", "light");
  const dText = /<path[^>]* d="([^"]*)"/.exec(svg)[1];
  assert.equal(dText, "M60 10 A50 50 0 0 1 60 110 A50 50 0 0 1 60 10");
  assert.equal((dText.match(/A/g) || []).length, 2, "the string writer writes two arcs");
  const plan = R.write.plan([R.prim.rect({ role: "guide", x: 0, y: 0, w: 120, h: 120 }), p], "page", "x");
  const flat = JSON.stringify(plan);
  assert.ok(flat.includes("M60 10 A50 50 0 0 1 60 110 A50 50 0 0 1 60 10"), "the DOM writer's plan holds the same two arcs");
});

test("D4: arc refuses from > to, more than a turn and an angle that is not finite", () => {
  assert.throws(() => T.arc(0, 0, 1, 10, 5), /from ≤ to ≤ from \+ 360/);
  assert.throws(() => T.arc(0, 0, 1, 0, 360.25), /from ≤ to ≤ from \+ 360/);
  assert.throws(() => T.arc(0, 0, 1, NaN, 10), /finite angle/);
});

// ---- G4 and G5 falsified: the same checks over planted copies must fail.

test("G4: one coefficient changed in a copy of 17-trig.js fails the 1e-12 check", () => {
  const planted = engine((s) => {
    const before = s["17-trig.js"];
    s["17-trig.js"] = before.replace("(1 - (u / 42) *", "(1 - (u / 43) *");
    assert.notEqual(s["17-trig.js"], before, "the plant's anchor is in the file");
  });
  assert.ok(farFromRuntime(planted.trig).length > 0, "the planted sine stays within 1e-12: the check cannot fail");
});

test("G5: a copy of the helper that returns one piece for 240° fails the piece count", () => {
  const planted = engine((s) => {
    const before = s["17-trig.js"];
    s["17-trig.js"] = before.replace("if (span < 180) return", "if (span < 300) return");
    assert.notEqual(s["17-trig.js"], before, "the plant's anchor is in the file");
  });
  assert.equal(pieces(planted.trig.arc(100, 100, 80, 0, 240)).length, 1, "the plant draws 240° as one piece");
  assert.notEqual(pieces(planted.trig.arc(100, 100, 80, 0, 240)).length, SPANS.find(([s]) => s === 240)[1]);
});

// ---- the checker's arc bounds (D5), in Node; JsRenderedTestTypesTest runs the same cases in the sandbox.

/** A mark's box, copied out of the checker's context (its arrays have that context's prototype). */
function box(p) {
  return [...markBox(p)];
}

const ESCAPING = { k: "path", role: "mark", d: ["M", 60, 10, "A", 50, 60, 90], series: "s" };

test("D5: an arc that bulges out of the frame is an I2 violation; inside it, none; a half circle and a quarter give the boxes computed by hand", () => {
  // Centre (30, 50), radius 50: the clockwise arc from (60, 10) to (60, 90) reaches x = 80.
  assert.deepEqual(box(ESCAPING), [60, 10, 80, 90]);
  assert.deepEqual([...violations([ESCAPING], 70, 100).violations].map((v) => v.inv), ["I2"]);
  assert.equal(violations([ESCAPING], 100, 100).violations.length, 0);
  // A half circle from 12 o'clock to 6 o'clock on (50, 50) r 40: clockwise through 3 o'clock.
  assert.deepEqual(box({ k: "path", d: ["M", 50, 10, "A", 40, 50, 90] }), [50, 10, 90, 90]);
  // The other half: from 6 o'clock back to 12 o'clock through 9 o'clock.
  assert.deepEqual(box({ k: "path", d: ["M", 50, 90, "A", 40, 50, 10] }), [10, 10, 50, 90]);
  // A quarter from 9 o'clock to 12 o'clock: no axis point between, the box is its end points.
  assert.deepEqual(box({ k: "path", d: ["M", 10, 50, "A", 40, 50, 10] }), [10, 10, 50, 50]);
  // A quarter from 1:30 to 4:30 passes 3 o'clock.
  const a = T.point(50, 50, 40, 45), b = T.point(50, 50, 40, 135);
  // Its end points are rounded to two decimals, so the circle through them is the hand's within the checker's TOL.
  const q = box({ k: "path", d: ["M", a.x, a.y, "A", 40, b.x, b.y] }), want = [a.x, a.y, 90, b.y];
  assert.ok(q.every((v, i) => Math.abs(v - want[i]) <= 0.02), `quarter through 3 o'clock: ${q} vs ${want}`);
});

test("G5: a copy of the checker without its arc bounds lets the escaping arc pass I2", async () => {
  const { readFileSync } = await import("node:fs");
  const vm = (await import("node:vm")).default;
  const src = readFileSync(new URL("./geometry.js", import.meta.url), "utf8");
  const anchor = 'if (c === "A" && atX !== null)';
  assert.equal(src.split(anchor).length, 2, "the plant's anchor occurs once");
  const ctx = vm.createContext({ E: engine() });
  vm.runInContext(src.replace(anchor, "if (false)") + "\nthis.G = JsRenderedInvariants(E);", ctx);
  assert.equal(ctx.G.violations([ESCAPING], 70, 100).violations.length, 0, "without the arc bounds the escaping arc passes");
});
