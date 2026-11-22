// Brief 1051 A.5 (D7): the frame `bare` over test_bare. The fit rule of the last value is computed HERE from the
// tests' fixed-width measurer (SIX) and the theme's numbers (the inset max(edge, reach), the gap directGap, one text
// line lineHeight × the text size), never read from the engine, and the picture must agree: the value's text where the
// rule holds and not where it does not, both outcomes in the table. Then the rule's borders on the pure function, the
// four cases with no value to write, and the chrome the frame never draws. G5 and G6 are falsified here.
import { test } from "node:test";
import assert from "node:assert/strict";
import { testTypeKey, typeInputOf } from "./engine.mjs";
import { textBox, TOL } from "./geometry.mjs";
import { formsEngine, plant, fixture, draw, SIX, near } from "./forms.mjs";

const R = formsEngine();
const BARE = "18a-bare.js";
const SIZES = [[80, 24], [160, 32], [400, 200]];
const LASTS = ["value", "dot", "none"];
const VALUES = [["short-value", "42"], ["long-value", "1,234,567,890"]];
/** The look numbers the rule reads (06-tables.js: edge 0.5 em, directGap 0.5 em, lineHeight 1.4), and the theme's. */
const EDGE_EM = 0.5, GAP_EM = 0.5, LINE = 1.4;

/** A fixture with its options' `last` set, and the theme's text size when given. */
function input(name, last, text) {
  const fx = JSON.parse(JSON.stringify(fixture("test_bare", name)));
  fx.document.config.options.last = last;
  if (text !== undefined) fx.theme = { tokens: { "--chart-text": text } };
  return fx;
}

/** The rule as the test computes it: [holds, its numbers]. */
function rule(W, H, value, text = 12) {
  const point = 4, gapToken = 2, reach = point - 0.5 + gapToken / 2, inset = Math.max(EDGE_EM * text, reach), gap = GAP_EM * text, line = LINE * text, w = SIX(value);
  return [H >= line && W - 2 * inset - gap - w >= W / 2, { inset, gap, line, w }];
}

const guides = (r) => r.primitives.filter((p) => p.k === "text" && p.role === "guide");

/** A.5's table: one row per size, last and value; problems where the picture and the test's rule disagree. */
function table(E = R, sizes = SIZES, text = undefined) {
  const rows = [], problems = [];
  for (const [W, H] of sizes) {
    for (const last of LASTS) {
      for (const [name, value] of VALUES) {
        const { r, hand } = draw(E, input(name, last, text), W, H, { measure: SIX });
        const [holds, n] = rule(W, H, value, text);
        const written = guides(r).filter((t) => t.text === value);
        const expected = last === "value" && holds;
        rows.push({ size: `${W}x${H}`, last, value, holds, written: written.length > 0 });
        if (written.length !== (expected ? 1 : 0)) problems.push(`${W}x${H} ${last} ${value}: rule ${holds}, ${written.length} texts`);
        if (guides(r).length !== written.length) problems.push(`${W}x${H} ${last}: another guide text`);
        for (const t of written) {
          const line = r.primitives.find((p) => p.k === "path" && p.role === "mark");
          const end = line.d[line.d.length - 2];
          if (!near(t.x - end, n.gap, 0.011)) problems.push(`${W}x${H}: the text stands ${t.x - end} px right of the line's last point, the gap ${n.gap}`);
          if (!near(end, hand.plot.x + hand.plot.w, 0.011)) problems.push(`${W}x${H}: the line ends at ${end}, the plot at ${hand.plot.x + hand.plot.w}`);
          for (const [x, y] of textBox(t)) if (x < -TOL || y < -TOL || x > W + TOL || y > H + TOL) problems.push(`${W}x${H}: the value's box leaves the frame at ${x},${y}`);
        }
      }
    }
  }
  return { rows, problems };
}

test("A.5: the last value is written where the test's own rule holds and not where it does not (both outcomes occur)", () => {
  const { rows, problems } = table();
  assert.deepEqual(problems, []);
  const value = rows.filter((x) => x.last === "value");
  const yes = value.filter((x) => x.written).length, no = value.filter((x) => !x.written).length;
  assert.ok(yes >= 2 && no >= 2, `written ${yes}, not written ${no}`);
  assert.equal(rows.filter((x) => x.last !== "value" && x.written).length, 0, "dot and none write no value");
  console.log("A.5 table (size, last, value, rule holds, text present):\n" + rows.map((x) => `  ${x.size} ${x.last} ${x.value} ${x.holds} ${x.written}`).join("\n"));
  console.log(`A.5 counts: last=value written ${yes}, not written ${no}; dot and none written 0 of ${rows.length - value.length}`);
});

test("A.5: with --chart-text 20 at 80 x 24 the height clause fails and no value is written", () => {
  const [holds, n] = rule(80, 24, "42", 20);
  assert.equal(holds, false);
  assert.ok(24 < n.line, "one line is " + n.line + " px");
  const { r } = draw(R, input("short-value", "value", 20), 80, 24, { measure: SIX });
  assert.equal(r.state, "rendered");
  assert.equal(guides(r).length, 0);
  // At 120 x 24 only the height clause refuses (the half-width clause holds): G5's case for that clause.
  const [h120, n120] = rule(120, 24, "42", 20);
  assert.equal(h120, false);
  assert.ok(120 - 2 * n120.inset - n120.gap - n120.w >= 60, "the half-width clause holds at 120 x 24");
  assert.equal(guides(draw(R, input("short-value", "value", 20), 120, 24, { measure: SIX }).r).length, 0);
});

test("A.5: the rule's borders on the pure function: one text line holds and 0.01 less does not; exactly half the width holds and 0.01 more does not", () => {
  const f = R.bare.fits, line = 16.8, inset = 6, gap = 6, W = 160, w = W - 2 * inset - gap - W / 2;
  assert.equal(f(W, line, 10, inset, gap, line), true);
  assert.equal(f(W, line - 0.01, 10, inset, gap, line), false);
  assert.equal(f(W, 100, w, inset, gap, line), true);
  assert.equal(f(W, 100, w + 0.01, inset, gap, line), false);
});

test("A.5: no title, no legend group and no axis, tick, grid or title primitive, in the rendered and the no-data state, at any size", () => {
  const titled = fixture("test_bare", "titled-three-series");
  const empty = JSON.parse(JSON.stringify(titled));
  empty.columns.y = empty.columns.y.map(() => null);
  const states = new Set();
  for (const fx of [titled, empty]) {
    for (const [W, H] of SIZES.concat([[960, 540], [300, 180]])) {
      const r = R.render(typeInputOf(fx, W, H));
      states.add(r.state);
      assert.equal(r.primitives.filter((p) => ["title", "axis", "tick", "grid", "legend"].includes(p.role)).length, 0, `${r.state} ${W}x${H}`);
    }
  }
  assert.deepEqual([...states].sort(), ["no-data", "rendered"]);
});

test("A.5: no value to write — (1) no value at all is no-data with no guide; (2) lay with last null keeps the full frame less the inset", () => {
  const nv = R.render(typeInputOf(fixture("test_bare", "no-value"), 400, 200));
  assert.equal(nv.state, "no-data");
  assert.equal(guides(nv).length, 0);
  const m = draw(R, fixture("test_bare", "short-value"), 400, 200).model;
  const h = R.chrome.helpers(R.theme.resolve("light"), R.measure.measurer(SIX));
  const geo = R.frames.get("bare").lay(h, 400, 200, "full", 0, Object.assign({}, m, { layout: Object.assign({}, m.layout, { last: null }) }), [], { measure: true }, null);
  assert.deepEqual({ ...geo.plot }, { x: 6, y: 6, w: 388, h: 188 });
  assert.equal(geo.after({ primitives: [], ends: [] }).length, 0, "nothing written after the marks, and no end asked for");
});

test("A.5: (3) a fixture with points and last null: the line runs from the plot's left edge to its right edge, the full frame less the inset", () => {
  const { r } = draw(R, fixture("test_bare", "dot"), 400, 200, { measure: SIX });
  const line = r.primitives.find((p) => p.k === "path" && p.role === "mark");
  assert.equal(line.d[1], 6);
  assert.equal(line.d[line.d.length - 2], 394);
});

test("A.5: (4) a template handed a last text that returns no end throws naming last", () => {
  const E = formsEngine((s) => plant(s, testTypeKey("test_bare"), "    return { primitives: out, index: index, count: count, ends: ends };", "    return { primitives: out, index: index, count: count, ends: [] };"));
  assert.throws(() => E.render(typeInputOf(fixture("test_bare", "short-value"), 400, 200, { measure: SIX })), /bare: the template was handed last and returned no end/);
  assert.doesNotThrow(() => R.render(typeInputOf(fixture("test_bare", "short-value"), 400, 200, { measure: SIX })));
  // Where the rule does not hold nothing is written, so nothing is asked of the ends.
  assert.doesNotThrow(() => E.render(typeInputOf(fixture("test_bare", "long-value"), 80, 24, { measure: SIX })));
});

// ---- G5, G6 falsified ----

const RULE = "    return H >= line && W - 2 * inset - gap - w >= W / 2;";
const engineWithRule = (body) => formsEngine((s) => plant(s, BARE, RULE, body));

test("G5: the rule always true / always false, each clause removed, ≥ made >: each red on the case only it decides", () => {
  const always = table(engineWithRule("    return true;")).problems;
  assert.ok(always.some((p) => /^80x24 value 1,234,567,890: rule false, 1 texts/.test(p)), "always true: " + always[0]);
  const never = table(engineWithRule("    return false;")).problems;
  assert.ok(never.some((p) => /^400x200 value 42: rule true, 0 texts/.test(p)), "always false: " + never[0]);
  const noHalf = table(engineWithRule("    return H >= line;")).problems;
  assert.ok(noHalf.some((p) => /^80x24 value 1,234,567,890: rule false, 1 texts/.test(p)), "no half-width clause: " + noHalf[0]);
  const noHeight = table(engineWithRule("    return W - 2 * inset - gap - w >= W / 2;"), [[120, 24]], 20).problems;
  assert.ok(noHeight.some((p) => /^120x24 value 42: rule false, 1 texts/.test(p)), "no height clause: " + noHeight[0]);
  for (const [body, which] of [["    return H > line && W - 2 * inset - gap - w >= W / 2;", "height"], ["    return H >= line && W - 2 * inset - gap - w > W / 2;", "half-width"]]) {
    const f = engineWithRule(body).bare.fits, line = 16.8, W = 160;
    const border = which === "height" ? f(W, line, 10, 6, 6, line) : f(W, 100, W - 18 - W / 2, 6, 6, line);
    assert.equal(border, false, "with > the " + which + " border no longer holds");
  }
  // The clean table has no problem in any of these cases.
  assert.deepEqual(table(R, [[120, 24]], 20).problems, []);
});

test("G5: the throw for a missing end removed is red on its test", () => {
  const E = formsEngine((s) => {
    plant(s, testTypeKey("test_bare"), "    return { primitives: out, index: index, count: count, ends: ends };", "    return { primitives: out, index: index, count: count, ends: [] };");
    plant(s, BARE, '          if (Array.isArray(drawn) || !Array.isArray(drawn.ends) || drawn.ends.length === 0) throw new Error("bare: the template was handed last and returned no end to write it at");\n', "");
  });
  assert.throws(() => assert.throws(() => E.render(typeInputOf(fixture("test_bare", "short-value"), 400, 200, { measure: SIX })), /handed last/));
});

test("G6: titles: true in a copy of the frame is red on a title text in the no-data state", () => {
  const E = formsEngine((s) => plant(s, BARE, "    titles: false,", "    titles: true,"));
  const empty = JSON.parse(JSON.stringify(fixture("test_bare", "titled-three-series")));
  empty.columns.y = empty.columns.y.map(() => null);
  const planted = E.render(typeInputOf(empty, 960, 540)), clean = R.render(typeInputOf(empty, 960, 540));
  assert.equal(planted.state, "no-data");
  assert.ok(planted.primitives.some((p) => p.k === "text" && p.role === "title" && p.text === "A title the frame never writes"), "the planted copy writes the title");
  assert.equal(clean.primitives.filter((p) => p.role === "title").length, 0);
});
