// Issue 1094 (js-rendered, the line family): a unit on a number x of a line or an area is written after the x title in
// the axis title (the form the value title has) and after the x value where a tooltip writes it, never on a tick; a
// time x and a band x take none. Every expected text is written HERE as a literal, never by the engine's own text
// functions: the oracle is not the thing under test. A.1 the axis title in its three forms, A.2 the tooltip's x text,
// A.3 no tick and nothing else moves (the sweep, with floors), A.4 a time x and a band x, A.5 the writers, A.6 two series;
// the gates G1 to G5 plant a copy of the engine (engine's edit) and see the check that names them go red, and the same
// check green without the plant.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf, canonical } from "./engine.mjs";

const LIVE = engine();
const FIXTURES = chartFixtures();
const WIRES = ["line", "area"];
const fixtureOf = (wire) => {
  const f = FIXTURES.find((x) => x.wire === wire && x.name === "quantitative-x-unit");
  assert.ok(f, "the fixture " + wire + "/quantitative-x-unit exists");
  return f.fixture;
};
const clone = (o) => JSON.parse(JSON.stringify(o));
/** The engine runs in its own realm: an array it returns is compared as plain data. */
const eq = (actual, expected, message) => assert.deepEqual(clone(actual), expected, message);
const draw = (R, fx, w, h) => R.render(inputOf(fx, w, h));
const axisTexts = (r) => r.primitives.filter((p) => p.k === "text" && p.role === "axis");
const xTitles = (r) => axisTexts(r).filter((p) => p.rotate === 0).map((p) => p.text);
const yTitles = (r) => axisTexts(r).filter((p) => p.rotate !== 0).map((p) => p.text);
const ticks = (r) => r.primitives.filter((p) => p.k === "text" && p.role === "tick").map((p) => p.text);
const heads = (r) => r.index.series.flatMap((s) => s.tip.map((t) => t[0]));
/** The frames of the sweep: 35 widths × 5 heights and the eight level frames, as the other sweeps of this suite (182 distinct). */
const FRAMES = [...new Set([].concat(...Array.from({ length: 35 }, (_, i) => [100, 140, 200, 240, 480].map((h) => `${240 + 40 * i}x${h}`))).concat(["80x24", "960x540", "480x360", "300x180", "120x40", "60x20", "360x240", "200x140"]))].map((s) => s.split("x").map(Number));

// ------------------------------------------------------------------------------------------------ the checks, over any engine

/** A.1: the x axis title of the fixture (title "Distance", unit "km") and its other forms; the y title is untouched. */
function checkTitles(R) {
  for (const wire of WIRES) {
    const fx = fixtureOf(wire);
    const r = draw(R, fx, 960, 540);
    eq(xTitles(r), ["Distance (km)"], wire + ": the x title ends with its unit");
    eq(yTitles(r), ["Value (t)"], wire + ": the y title is as built");
    const nul = clone(fx);
    nul.document.config.encoding.x.title = null;
    const rn = draw(R, nul, 960, 540);
    eq(xTitles(rn), [], wire + ": title null writes neither title nor unit");
    eq(yTitles(rn), ["Value (t)"], wire + ": title null on x leaves y's");
    const none = clone(fx);
    delete none.document.config.encoding.x.title;
    eq(xTitles(draw(R, none, 960, 540)), ["x (km)"], wire + ": no title member: the field name, then the unit");
    const bare = clone(fx);
    delete bare.document.config.encoding.x.unit;
    eq(xTitles(draw(R, bare, 960, 540)), ["Distance"], wire + ": no unit: the title alone");
    const both = clone(fx);
    both.document.config.encoding.x.title = "Distance (km)";
    both.document.config.encoding.x.unit = "mi";
    eq(xTitles(draw(R, both, 960, 540)), ["Distance (km) (mi)"], wire + ": the unit is appended to whatever the title says, as y's is");
  }
}

/** A.2: the x value of every tooltip, in the x format, then the unit after one space; y keeps its own unit. */
function checkTooltips(R) {
  for (const wire of WIRES) {
    const r = draw(R, fixtureOf(wire), 960, 540);
    assert.equal(r.index.series.length, 1);
    eq(r.index.series[0].tip.map((t) => t[0]), ["1 km", "2 km", "3 km", "5 km", "8 km", "13 km", "21 km"], wire + ": the x value then the unit, in x order");
    eq(r.index.series[0].tip.map((t) => t[1]), ["4 t", "5 t", "7 t", "10 t", "12 t", "15 t", "18 t"], wire + ": y keeps its own unit (one series: no series name before it)");
    const cases = [
      [undefined, ["0.5 km", "1,250.5 km", "3,000,000 km"]],
      ["integer", ["0 km", "1,250 km", "3,000,000 km"]],
      ["compact", ["0.5 km", "1.3K km", "3M km"]],
      ["decimal(2)", ["0.50 km", "1,250.50 km", "3,000,000.00 km"]],
      ["percent", ["50% km", "125,050% km", "300,000,000% km"]],
      ["currency(USD)", ["USD 0.50 km", "USD 1,250.50 km", "USD 3,000,000.00 km"]],
    ];
    for (const [format, want] of cases) {
      const fx = clone(fixtureOf(wire));
      if (format !== undefined) fx.document.config.encoding.x.format = format;
      fx.columns.x = [0.5, 1250.5, 3000000, 7, 8, 9, 10];
      const got = heads(draw(R, fx, 960, 540));
      eq([got[0], got[5], got[6]], want, wire + ": x format " + format);
    }
    const log = clone(fixtureOf(wire));
    log.document.config.encoding.x.scale = { type: "log" };
    log.columns.x = [1, 10, 100, 1000, 10000, 100000, 1000000];
    const lr = draw(R, log, 960, 540);
    eq(heads(lr), ["1 km", "10 km", "100 km", "1,000 km", "10,000 km", "100,000 km", "1,000,000 km"], wire + ": a log x");
    eq(xTitles(lr), ["Distance (km)"]);
  }
}

/** A.3: the unit is on no tick; with the unit or without, a picture differs only in the x title and each tooltip's first line. */
function checkNothingElseMoves(R) {
  let frames = 0, titled = 0, tipped = 0;
  const strip = (r) => {
    const o = clone(r);
    o.primitives = o.primitives.filter((p) => !(p.k === "text" && p.role === "axis" && p.rotate === 0));
    for (const s of o.index.series) s.tip = s.tip.map((t) => t.slice(1));
    return canonical(o);
  };
  for (const wire of WIRES) {
    const fx = fixtureOf(wire), bare = clone(fx);
    delete bare.document.config.encoding.x.unit;
    for (const [w, h] of FRAMES) {
      const a = draw(R, fx, w, h), b = draw(R, bare, w, h);
      frames++;
      eq(ticks(a).filter((t) => t.includes("km")), [], wire + " " + w + "x" + h + ": no tick holds the unit");
      assert.equal(strip(a), strip(b), wire + " " + w + "x" + h + ": nothing but the x title and the tooltips' first lines differs");
      if (xTitles(a).length > 0) titled++;
      for (const s of a.index.series) for (const t of s.tip) { tipped++; assert.ok(t[0].endsWith(" km"), wire + " " + w + "x" + h + ": the tooltip's x text ends with the unit: " + t[0]); }
    }
  }
  assert.equal(frames, 2 * FRAMES.length);
  assert.ok(titled >= 100, "non-vacuity: the x title was drawn in at least 100 of the pictures: " + titled);
  assert.ok(tipped >= 1000, "non-vacuity: at least 1,000 tooltip lines checked: " + tipped);
}

/** A.4: a time x and a band x take no unit, whatever the document says: the result is the document's without it. */
function checkNoUnitOnTimeOrBand(R) {
  for (const wire of WIRES) {
    const t = clone(fixtureOf(wire));
    t.document.config.encoding.x = { field: "d", type: "temporal", format: "month", unit: "km" };
    t.document.inputs.result.columns[0] = { name: "d", type: "DATE", nullable: true };
    t.columns.d = ["2024-01-01", "2024-02-01", "2024-03-01", "2024-04-01", "2024-05-01", "2024-06-01", "2024-07-01"];
    t.types.d = "DATE";
    delete t.columns.x;
    delete t.types.x;
    const t0 = clone(t);
    delete t0.document.config.encoding.x.unit;
    const a = draw(R, t, 960, 540);
    assert.equal(a.state, "rendered");
    assert.equal(canonical(a), canonical(draw(R, t0, 960, 540)), wire + ": a time x with a unit draws as without");
    eq(xTitles(a), ["d"]);
    eq(heads(a).slice(0, 2), ["2024-01", "2024-02"]);
    const n = clone(fixtureOf(wire));
    n.document.config.encoding.x = { field: "x", type: "nominal", sort: "ascending", unit: "km" };
    const n0 = clone(n);
    delete n0.document.config.encoding.x.unit;
    assert.equal(canonical(draw(R, n, 960, 540)), canonical(draw(R, n0, 960, 540)), wire + ": a band x with a unit draws as without");
  }
}

// ------------------------------------------------------------------------------------------------ the tests

test("A.1 the x axis title ends with the unit; title null writes neither; no title member writes the field name; y is untouched", () => checkTitles(LIVE));
test("A.2 the tooltip's x text is the x value in its format and then the unit, for every format, a log x and both types", () => checkTooltips(LIVE));
test("A.3 no tick holds the unit and nothing else of a picture moves, at every frame of the sweep (floors)", () => checkNothingElseMoves(LIVE));
test("A.4 a time x and a band x take no unit: the engine draws them as without", () => checkNoUnitOnTimeOrBand(LIVE));

test("A.5 a hostile unit on x is text in the picture: the string writer escapes it in the title and the engine keeps it a plain string in the tooltip", () => {
  const hostile = "<b onclick=\"x\">&'";
  for (const wire of WIRES) {
    const fx = clone(fixtureOf(wire));
    fx.document.config.encoding.x.unit = hostile;
    const r = draw(LIVE, fx, 960, 540);
    eq(xTitles(r), ["Distance (" + hostile + ")"]);
    assert.equal(heads(r)[0], "1 " + hostile);
    const svg = LIVE.writeString(r.primitives, "page", "light", LIVE.write.describe(r, fx.document));
    assert.equal(svg.split("<b").length - 1, 0, wire + ": no markup from the unit");
    assert.equal(svg.split("&lt;b onclick=&quot;x&quot;&gt;&amp;&#39;").length - 1, 1, wire + ": the title holds it once, escaped");
  }
});

test("A.6 two series on a number x with a unit: the unit stands once in the title and after the x value on every series' tooltip", () => {
  const rows = [[1, "a", 3], [2, "a", 4], [3, "a", 5], [1, "b", 2], [2, "b", 6], [3, "b", 1]];
  for (const type of WIRES) {
    const document = { display_name: "two", renderer: { kind: "chart", version: "1" }, inputs: { result: { columns: [{ name: "x", type: "DECIMAL" }, { name: "s", type: "STRING" }, { name: "v", type: "DECIMAL" }] } }, config: { mark: { type }, encoding: { x: { field: "x", type: "quantitative", title: "Distance", unit: "km" }, y: { field: "v", type: "quantitative", aggregate: "sum", title: "Value" }, color: { field: "s", type: "nominal", sort: "ascending" } } } };
    const fx = { document, columns: { x: rows.map((r) => r[0]), s: rows.map((r) => r[1]), v: rows.map((r) => String(r[2])) }, types: { x: "DECIMAL", s: "STRING", v: "DECIMAL" } };
    const r = draw(LIVE, fx, 960, 540);
    eq(r.index.series.map((s) => s.tip.map((t) => t[0])), [["1 km", "2 km", "3 km"], ["1 km", "2 km", "3 km"]], type);
    eq(xTitles(r), ["Distance (km)"], type);
    eq(r.index.series[0].tip[0].slice(1), ["a: 3"], type + ": the series row has no unit (y has none)");
  }
});

// ------------------------------------------------------------------------------------------------ the gates (planted copies)

/** A plant: `from` occurs exactly once in `file` and becomes `to`; the engine of the copy is returned. */
function planted(file, from, to) {
  return engine((srcs) => {
    assert.equal(srcs[file].split(from).length, 2, "the plant's anchor occurs once in " + file);
    srcs[file] = srcs[file].replace(from, to);
  });
}

test("G1 the title: without the appended unit A.1 is red (and green on the unplanted engine)", () => {
  checkTitles(LIVE);
  const R = planted("16-lines.js", 'if (bt !== null && xUnit !== null) bt = bt + " (" + xUnit + ")";', "");
  assert.throws(() => checkTitles(R), /the x title ends with its unit/);
});

test("G2 the tooltip: an x text written without the unit is red in A.2 (and green on the unplanted engine)", () => {
  checkTooltips(LIVE);
  const R = planted("16-lines.js", "position ? withUnit(T.value(xe.at, x.format), xUnit)", "position ? T.value(xe.at, x.format)");
  assert.throws(() => checkTooltips(R), /the x value then the unit/);
});

test("G3 never on a tick: a unit appended to the linear x ticks is red in A.3 (and green on the unplanted engine)", () => {
  checkNothingElseMoves(LIVE);
  // the plant needs the unit on the layout part: carry it through the model, then append it to the tick text
  const R = engine((srcs) => {
    const a = 'x: position ? { kind: temporal ? "time" : logX ? "log" : "linear",';
    assert.equal(srcs["16-lines.js"].split(a).length, 2, "the model's anchor occurs once");
    srcs["16-lines.js"] = srcs["16-lines.js"].replace(a, 'x: position ? { unitProbe: xUnit, kind: temporal ? "time" : logX ? "log" : "linear",');
    const b = "out.push({ at: v.ticks[i], text: v.texts[i], under: null });";
    assert.equal(srcs["12-cartesian.js"].split(b).length, 2, "the cartesian anchor occurs once");
    srcs["12-cartesian.js"] = srcs["12-cartesian.js"].replace(b, 'out.push({ at: v.ticks[i], text: v.texts[i] + (X.unitProbe ? " " + X.unitProbe : ""), under: null });');
  });
  assert.throws(() => checkNothingElseMoves(R), /no tick holds the unit/);
});

test("G4 a time or a band x: a unit read without the number-axis condition is red in A.4 (and green on the unplanted engine)", () => {
  checkNoUnitOnTimeOrBand(LIVE);
  const R = planted("16-lines.js", 'var xUnit = position && !temporal && typeof x.unit === "string"', 'var xUnit = typeof x.unit === "string"');
  assert.throws(() => checkNoUnitOnTimeOrBand(R), /draws as without/);
});

test("G5 title null: a unit appended without the null condition is red in A.1 (and green on the unplanted engine)", () => {
  checkTitles(LIVE);
  const R = planted("16-lines.js", 'if (bt !== null && xUnit !== null) bt = bt + " (" + xUnit + ")";', 'if (xUnit !== null) bt = bt + " (" + xUnit + ")";');
  assert.throws(() => checkTitles(R), /title null writes neither title nor unit/);
});
