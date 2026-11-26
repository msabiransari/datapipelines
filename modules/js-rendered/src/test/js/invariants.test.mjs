// A.3: invariants I1 to I5 and I7 (record §8) for every chart fixture at every width and height of D7 (widths 240 to
// 1,600 in steps of 40, heights 100, 140, 200, 240, 480: the level heights 140 and 240 since #985 R1), each count
// floored so a checker that saw nothing is red.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";
import { violations, expectedBarMarks, expectedLineMarks, expectedScatterMarks, expectedPieMarks, expectedHeatmapMarks, expectedBoxMarks, expectedWaterfallMarks, expectedFunnelMarks, expectedGaugeMarks, expectedTreemapMarks, WIDTHS, HEIGHTS } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();

/**
 * I3's oracle by wire (brief 1062 D11): the expected count from the rows, and how the held marks are counted in a
 * rendered result. A bar's marks are its rectangles (the primitives the checker saw); a line's and an area's are their
 * points, which the index holds every one of (§4.9); a type that finds its marks by their own shape (the index's
 * second form) holds them in index.marks. A type lane adds one entry; a wire missing here fails, naming it.
 */
const fromPrimitives = (r, v) => v.marks;
const fromPoints = (r) => r.index.series.reduce((n, s) => n + s.at.length, 0);
const fromLookup = (r) => r.index.marks.length;
const ORACLE = {
  treemap: { expected: expectedTreemapMarks, held: fromLookup, what: "index's marks" },
  bar: { expected: expectedBarMarks, held: fromPrimitives, what: "primitives" },
  line: { expected: expectedLineMarks, held: fromPoints, what: "index" },
  area: { expected: expectedLineMarks, held: fromPoints, what: "index" },
  scatter: { expected: expectedScatterMarks, held: fromLookup, what: "index's marks" },
  pie: { expected: expectedPieMarks, held: fromLookup, what: "index's marks" },
  heatmap: { expected: expectedHeatmapMarks, held: fromLookup, what: "index's marks" },
  box: { expected: expectedBoxMarks, held: (r) => r.index.marks.filter(m => m.shape.kind === "box").length, what: "index boxes" },
  waterfall: { expected: expectedWaterfallMarks, held: fromPrimitives, what: "primitives" },
  funnel: { expected: expectedFunnelMarks, held: fromLookup, what: "index's marks" },
  gauge: { expected: expectedGaugeMarks, held: fromLookup, what: "index's marks" },
};

test("I3's oracle table holds every wire of the fixtures", () => {
  for (const wire of new Set(FIXTURES.map((f) => f.wire))) assert.ok(Object.prototype.hasOwnProperty.call(ORACLE, wire), "no I3 oracle for the wire " + wire);
});

/** Non-vacuity floors: the fixture set, the frames, and what the checker saw. */
const FLOOR = { fixtures: 297, widths: 35, heights: 5, texts: 50000, marks: 33000, levels: 3 };

test("the invariant sweep is not vacuous (floors)", () => {
  assert.ok(FIXTURES.length >= FLOOR.fixtures, "chart fixtures: " + FIXTURES.length);
  assert.equal(WIDTHS.length, FLOOR.widths);
  assert.equal(WIDTHS[0], 240);
  assert.equal(WIDTHS[WIDTHS.length - 1], 1600);
  assert.equal(HEIGHTS.length, FLOOR.heights);
  assert.deepEqual(HEIGHTS, [100, 140, 200, 240, 480]);
});

const seen = { texts: 0, marks: 0, levels: new Set(), pictures: 0 };
for (const f of FIXTURES) {
  test(`I1-I5 at every frame: ${f.wire}/${f.name}`, () => {
    const bad = [];
    for (const w of WIDTHS) {
      for (const h of HEIGHTS) {
        const r = R.render(inputOf(f.fixture, w, h));
        seen.pictures++;
        seen.levels.add(r.level);
        const v = violations(r.primitives, w, h);
        seen.texts += v.texts;
        seen.marks += v.marks;
        const hidden = r.summary.series.filter((s) => s.hidden).map((s) => s.id);
        const oracle = ORACLE[f.wire];
        if (oracle === undefined) throw new Error("no I3 oracle for the wire " + f.wire);
        const expected = r.state === "rendered" ? oracle.expected(f.fixture, hidden) : 0;
        if (r.summary.marks !== expected) v.violations.push({ inv: "I3", what: "summary.marks " + r.summary.marks + ", the rows give " + expected });
        const held = r.state === "rendered" ? oracle.held(r, v) : 0;
        if (r.state === "rendered" && held !== r.summary.marks) v.violations.push({ inv: "I3", what: "summary.marks " + r.summary.marks + ", the " + oracle.what + " hold " + held });
        for (const x of v.violations) bad.push(`${x.inv} at ${w}x${h}: ${x.what}`);
      }
    }
    assert.deepEqual(bad.slice(0, 12), [], bad.length + " violations");
  });
}

test("the sweep checked texts and marks at every level (floors, after the sweep)", () => {
  assert.equal(seen.pictures, FIXTURES.length * WIDTHS.length * HEIGHTS.length);
  assert.ok(seen.texts >= FLOOR.texts, "texts checked: " + seen.texts);
  assert.ok(seen.marks >= FLOOR.marks, "marks checked: " + seen.marks);
  assert.deepEqual([...seen.levels].sort(), ["compact", "full", "minimal"]);
});

/** The colour of every mark and legend key, by series. */
function colours(prims) {
  const out = {};
  for (const p of prims) {
    if (p.series === null || p.series === undefined || p.k === "group" || p.k === "text" || p.color === undefined) continue;
    (out[p.series] = out[p.series] || new Set()).add(p.color);
  }
  return out;
}

test("I7: hiding a series changes no other series' colour", () => {
  let checked = 0;
  for (const f of FIXTURES) {
    const base = R.render(inputOf(f.fixture, 960, 540, { hidden: [] }));
    if (base.summary.series.length < 2) continue;
    const before = colours(base.primitives);
    for (const s of base.summary.series) {
      const r = R.render(inputOf(f.fixture, 960, 540, { hidden: [s.id] }));
      // D2: an id on the list hides its series only in a draw that draws that series' legend entry.
      const entry = base.primitives.some((p) => p.k === "group" && p.role === "legend" && p.legend === s.id);
      const hidden = r.summary.series.find((x) => x.id === s.id);
      assert.equal(hidden.hidden, entry, f.name + ": " + s.id + (entry ? " has a legend entry and is listed, so it is hidden" : " has no legend entry, so the list does not hide it"));
      const after = colours(r.primitives);
      for (const other of base.summary.series) {
        if (other.id === s.id || before[other.id] === undefined) continue;
        assert.deepEqual([...(after[other.id] || [])].sort(), [...before[other.id]].sort(), f.name + ": hiding " + s.id + " changed " + other.id);
        checked++;
      }
    }
  }
  assert.ok(checked >= 40, "series pairs checked: " + checked);
});
