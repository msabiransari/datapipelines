// Brief 1049 A.2's sweep (D5, D6; record §8 A2): every fixture of every test type at the invariant sweep's 35 widths and
// 5 heights (geometry.mjs's WIDTHS and HEIGHTS, the lists emit.mjs hands the JVM guard as sweep.json), each picture
// through I1, I2, I4, I5, and I3 against the fixture's hand-written `expect.marks`; I7 by hiding each series in turn.
// Every count is floored, so a sweep that saw nothing is red. G7 is falsified here: a rectangle planted 2 px beyond
// the frame's right edge in a 300 × 180 frame is an I2 violation naming the mark.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engineWith, testTypes, testTypeKey, typeFixtures, typeInputOf, chartFixtures } from "./engine.mjs";
import { violations, WIDTHS, HEIGHTS } from "./geometry.mjs";

const NAMES = testTypes();
const R = engineWith(NAMES);
const FIXTURES = NAMES.flatMap((n) => typeFixtures(n));

/** Non-vacuity floors: the test types, their fixtures, the frames, and what the checker saw. */
const FLOOR = { types: 3, fixtures: 23, pictures: 23 * 35 * 5, texts: 9000, marks: 15000, rendered: 3000 };

test("the test types' sweep is not vacuous (floors), and the product's fixture set does not see them", () => {
  assert.ok(NAMES.length >= FLOOR.types, "test types: " + NAMES);
  assert.ok(FIXTURES.length >= FLOOR.fixtures, "test type fixtures: " + FIXTURES.length);
  assert.equal(WIDTHS.length * HEIGHTS.length, 175);
  for (const f of FIXTURES) assert.ok(Number.isInteger(f.fixture.expect && f.fixture.expect.marks), `${f.wire}/${f.name}: expect.marks`);
  for (const n of NAMES) assert.ok(n.startsWith("test_"), n + " starts with test_");
  // The underscore directory keeps them out of chartFixtures() (G6's count: 91 at brief 1050's base, and its twelve
  // shaded bars; brief 1053's fifteen two-sided bars and one sequential bar of seven steps; brief 1011's bar and area
  // stacks beyond the number range; brief 1062's thirty-five scatter fixtures).
  const product = chartFixtures();
  assert.equal(product.filter((f) => f.wire.startsWith("test_") || f.wire === "_types").length, 0);
  assert.equal(product.length, 226);
});

const seen = { pictures: 0, rendered: 0, texts: 0, marks: 0, levels: new Set(), states: new Set() };
for (const f of FIXTURES) {
  test(`I1-I5 at every frame: ${f.wire}/${f.name}`, () => {
    const bad = [];
    for (const w of WIDTHS) {
      for (const h of HEIGHTS) {
        const r = R.render(typeInputOf(f.fixture, w, h));
        seen.pictures++;
        seen.levels.add(r.level);
        seen.states.add(r.state);
        const v = violations(r.primitives, w, h);
        seen.texts += v.texts;
        seen.marks += v.marks;
        if (r.state === "rendered") {
          seen.rendered++;
          if (r.summary.marks !== f.fixture.expect.marks) v.violations.push({ inv: "I3", what: "summary.marks " + r.summary.marks + ", the fixture expects " + f.fixture.expect.marks });
          if (v.marks !== r.summary.marks) v.violations.push({ inv: "I3", what: "summary.marks " + r.summary.marks + ", the primitives hold " + v.marks });
        }
        for (const x of v.violations) bad.push(`${x.inv} at ${w}x${h}: ${x.what}`);
      }
    }
    assert.deepEqual(bad.slice(0, 12), [], bad.length + " violations");
  });
}

test("the sweep checked texts and marks at every level and state (floors, after the sweep)", () => {
  assert.equal(seen.pictures, FIXTURES.length * WIDTHS.length * HEIGHTS.length);
  assert.ok(seen.pictures >= FLOOR.pictures, "pictures: " + seen.pictures);
  assert.ok(seen.rendered >= FLOOR.rendered, "rendered pictures: " + seen.rendered);
  assert.ok(seen.texts >= FLOOR.texts, "texts checked: " + seen.texts);
  assert.ok(seen.marks >= FLOOR.marks, "marks checked: " + seen.marks);
  assert.deepEqual([...seen.levels].sort(), ["compact", "full", "minimal"]);
  assert.deepEqual([...seen.states].sort(), ["no-data", "rendered"]);
  console.log(`test-type sweep: ${FIXTURES.length} fixtures x ${WIDTHS.length * HEIGHTS.length} frames = ${seen.pictures} pictures (${seen.rendered} rendered), ${seen.texts} texts, ${seen.marks} marks`);
});

function colours(prims) {
  const out = {};
  for (const p of prims) {
    if (p.series === null || p.series === undefined || p.k === "group" || p.k === "text") continue;
    const c = p.color !== null && p.color !== undefined ? p.color : p.strokeColor;
    if (c === undefined || c === null) continue;
    (out[p.series] = out[p.series] || new Set()).add(c);
  }
  return out;
}

test("I7: hiding a series changes no other series' colour (test types)", () => {
  let checked = 0;
  for (const f of FIXTURES) {
    const base = R.render(typeInputOf(f.fixture, 960, 540, { hidden: [] }));
    if (base.summary.series.length < 2) continue;
    const before = colours(base.primitives);
    for (const s of base.summary.series) {
      const r = R.render(typeInputOf(f.fixture, 960, 540, { hidden: [s.id] }));
      const entry = base.primitives.some((p) => p.k === "group" && p.role === "legend" && p.legend === s.id);
      assert.equal(r.summary.series.find((x) => x.id === s.id).hidden, entry, `${f.wire}/${f.name}: ${s.id}`);
      const after = colours(r.primitives);
      for (const other of base.summary.series) {
        if (other.id === s.id || before[other.id] === undefined) continue;
        assert.deepEqual([...(after[other.id] || [])].sort(), [...before[other.id]].sort(), `${f.wire}/${f.name}: hiding ${s.id} changed ${other.id}`);
        checked++;
      }
    }
  }
  assert.ok(checked >= 150, "series pairs checked: " + checked);
});

/** The G7 plant, as the JVM guard applies it too: test_plain's last rectangle ends at x = 302 when the plot ends at 294 (a 300-wide frame). */
const G7_ANCHOR = "    return out;\n  }\n\n  R.type(";
const G7_PLANT =
  '    if (out.length > 0 && plot.x + plot.w === 294) { var q = out.pop(); out.push(P.rect({ role: "mark", x: q.x, y: q.y, w: 302 - q.x, h: q.h, fill: q.fill, color: q.color, series: q.series })); }\n' + G7_ANCHOR;

test("G7: a rectangle 2 px beyond the frame's right edge in a 300 x 180 frame is an I2 violation naming the mark; without the plant none", () => {
  const E = engineWith(NAMES, (s) => {
    const k = testTypeKey("test_plain");
    assert.equal(s[k].split(G7_ANCHOR).length, 2, "the plant's anchor occurs once");
    s[k] = s[k].replace(G7_ANCHOR, G7_PLANT);
  });
  const fx = typeFixtures("test_plain").find((f) => f.name === "legend-top").fixture;
  const planted = E.render(typeInputOf(fx, 300, 180)), clean = R.render(typeInputOf(fx, 300, 180));
  const bad = violations(planted.primitives, 300, 180).violations;
  assert.equal(bad.length, 1, JSON.stringify(bad));
  assert.equal(bad[0].inv, "I2");
  assert.match(bad[0].what, /^mark of string:\w+ leaves the frame: [\d.]+,[\d.]+,302,[\d.]+$/);
  assert.equal(violations(clean.primitives, 300, 180).violations.length, 0);
});
