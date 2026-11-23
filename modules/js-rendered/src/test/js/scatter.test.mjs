// Lane 1062 (#1062, js-rendered T1): the scatter type, over its fixtures (src/test/fixtures/scatter/) and a few
// variants of them made here. A copy of the template with a spy (the pattern of bubbles.test.mjs) hands the test the
// model and the geometry the frame gave the marks, so every expected place, diameter and width below is computed by
// this test from the rows and that handed geometry, never read from the marks being checked.
//   A.2 the model: the rows drawn and each warning once (D3); the value side's extent over the shown series and the
//       x ends over every series (D4); authored domains; the axis titles and their unit; the two caps (D6, D10).
//   A.3 the marks: centres at the handed scales' places of the clamped values; diameters by D5 (the extreme sizes as
//       literal numbers); the ring; no circle crossing the plot's edge over the sweep; larger marks first.
//   A.4 the lookup and the card (D9).   A.5 the legend, the scale and a hidden series (D6, D7, D8; I7).
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, inputOf, chartFixtures } from "./engine.mjs";
import { plant } from "./forms.mjs";
import { expectedScatterMarks, WIDTHS, HEIGHTS, TOL } from "./geometry.mjs";

const SCATTER = "types/scatter.js";
const SPY_FROM = 'R.type("scatter", { channels: CHANNELS, model: model, marks: marks,';
const SPY_TO = 'R.type("scatter", { channels: CHANNELS, model: model, marks: function (m, hand, h) { if (R.spy) R.spy(m, hand); return marks(m, hand, h); },';
const E = engine((s) => plant(s, SCATTER, SPY_FROM, SPY_TO));
const FX = Object.fromEntries(chartFixtures().filter((f) => f.wire === "scatter").map((f) => [f.name, f.fixture]));

/** A render with what the template was handed: {r, m, hand} (m and hand are null when marks was not called). */
function draw(fx, w = 960, h = 540, extra = {}) {
  let seen = null;
  E.spy = (m, hand) => { seen = { m, hand }; };
  try {
    const r = E.render(inputOf(fx, w, h, extra));
    return { r, m: seen && seen.m, hand: seen && seen.hand };
  } finally {
    E.spy = null;
  }
}
/** A fixture copied and changed by `edit`. */
function variant(name, edit) {
  const c = structuredClone(FX[name]);
  edit(c, c.document.config.encoding);
  return c;
}
const circles = (r) => r.primitives.filter((p) => p.role === "mark" && p.k === "circle");
const byDatum = (r) => circles(r).slice().sort((a, b) => a.datum - b.datum);
const rules = (r) => r.summary.warnings.map((x) => x.code).filter((c) => c.startsWith("chart.rule."));
const num = (v) => (typeof v === "number" ? v : Number(v));
const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
/** A row's place on x, from the delivered column: a number, or a date text as the engine's seconds since 1970 UTC. */
const xOf = (fx, i) => {
  const e = fx.document.config.encoding, v = fx.columns[e.x.field][i];
  return e.x.type === "temporal" ? Date.parse(String(v).slice(0, 10) + "T00:00:00Z") / 1000 : num(v);
};
/** deepEqual over plain copies: the engine's arrays and objects come from another realm (the vm context). */
const plain = (v) => JSON.parse(JSON.stringify(v));
const same = (got, want, what) => assert.deepEqual(plain(got), plain(want), what);
function near(got, want, tol, what) {
  assert.equal(got.length, want.length, what + ": " + JSON.stringify(got));
  got.forEach((g, i) => assert.ok(Math.abs(g - want[i]) <= tol, `${what}[${i}]: ${g}, expected ${want[i]} within ${tol}`));
}
/** Every drawn circle at the handed scales' places of its row's values, clamped to the axes' ends (D4). */
function centreFailures(fx, d) {
  const e = fx.document.config.encoding, Y = fx.columns[e.y.field], bad = [];
  for (const c of circles(d.r)) {
    const wx = d.hand.x.scale(clamp(xOf(fx, c.datum), d.hand.x.lo, d.hand.x.hi));
    const wy = d.hand.value.scale(clamp(num(Y[c.datum]), d.hand.value.lo, d.hand.value.hi));
    if (Math.abs(c.cx - wx) > 0.006 || Math.abs(c.cy - wy) > 0.006) bad.push(`row ${c.datum}: (${c.cx}, ${c.cy}), expected (${wx}, ${wy})`);
  }
  return bad;
}

// ------------------------------------------------------------------------------------------------- A.2 the model

test("A.2: the rows drawn and each warning once, per D3's cases (the I3 oracle, summary.marks and markCount agree)", () => {
  const CASES = [
    ["null-rows", FX["null-rows"], 2, ["chart.rule.null-position"]],
    ["log-x", FX["log-x"], 5, ["chart.rule.log-nonpositive"]],
    ["log-y", FX["log-y"], 5, ["chart.rule.log-nonpositive"]],
    ["time-x", FX["time-x"], 5, ["chart.rule.null-position"]],
    ["exact-columns", FX["exact-columns"], 3, []],
    ["one-point", FX["one-point"], 1, []],
    ["same-place", FX["same-place"], 3, []],
    ["shade-null-sum (a null shade is drawn)", FX["shade-null-sum"], 3, []],
    ["shade-null-count", FX["shade-null-count"], 3, []],
    ["thousand", FX["thousand"], 1000, []],
    // An exact column's text past the number range is not finite: two such rows, one warning.
    ["non-finite", variant("one-series", (c) => {
      c.columns.y = c.columns.y.map(String);
      c.columns.y[1] = "1e400";
      c.columns.y[4] = "-1e400";
      c.types.y = "BIGDECIMAL";
    }), 10, ["chart.rule.non-finite"]],
    ["null series", variant("eight-series", (c) => {
      c.columns.s[0] = null;
      c.columns.s[9] = null;
    }), 22, ["chart.rule.null-category"]],
    ["every case at once, each warned once in the order met", variant("null-rows", (c, e) => {
      e.y.scale = { type: "log" };
      c.columns.x.push(6, 7, null);
      c.columns.y.push(0, -3, 8);
      c.columns.z.push(1, 1, 1);
    }), 2, ["chart.rule.null-position", "chart.rule.log-nonpositive"]],
  ];
  for (const [what, fx, marks, warnings] of CASES) {
    const d = draw(fx);
    assert.equal(d.r.state, "rendered", what);
    assert.equal(d.r.summary.marks, marks, what + ": summary.marks");
    assert.equal(d.m.markCount, marks, what + ": markCount");
    assert.equal(expectedScatterMarks(fx, []), marks, what + ": the I3 oracle");
    same(rules(d.r), warnings, what + ": the warnings");
    console.log(`A.2 ${what}: ${marks} drawn; ${JSON.stringify(warnings)}`);
  }
  assert.equal(draw(FX.empty).r.state, "no-data");
});

test("A.2: the value side's extent is over the SHOWN series; the x ends over every series, whatever is hidden (D4)", () => {
  const fx = FX["hidden-owner"];
  const all = draw(fx, 960, 540, { hidden: [] });
  same(all.m.layout.extent({ "string:A": true, "string:B": true }), [0, 100]);
  same(all.m.layout.extent({ "string:B": true }), [40, 50]);
  same(all.m.layout.extent({}), [0, 0]);
  for (const hidden of [[], ["string:A"], ["string:A", "string:B"]]) {
    const d = draw(fx, 960, 540, { hidden });
    assert.equal(d.m.layout.x.lo, 0, "x.lo with " + hidden);
    assert.equal(d.m.layout.x.hi, 100, "x.hi with " + hidden);
    assert.equal(d.m.markCount, 5, "markCount counts the hidden rows too");
    console.log(`A.2 hidden ${JSON.stringify(hidden)}: x ${d.hand.x.lo} to ${d.hand.x.hi}, value ${d.hand.value.lo} to ${d.hand.value.hi}, ${circles(d.r).length} marks`);
  }
  const a = draw(fx, 960, 540, { hidden: ["string:A"] });
  assert.equal(a.hand.value.lo, 40);
  assert.equal(a.hand.value.hi, 50);
  assert.equal(circles(a.r).length, 3);
  assert.equal(circles(draw(fx, 960, 540, { hidden: ["string:A", "string:B"] }).r).length, 0);
  const logY = draw(FX["log-y"]).m;
  same(logY.layout.extent({}), [null, null]);
  same(logY.layout.extent({ measure: true }), [2, 2000]);
  same(draw(FX["zero-y"]).m.layout.extent({ measure: true }), [0, 8]);
  same(draw(FX["one-series"]).m.layout.extent({ measure: true }).length, 2);
  const zx = draw(FX["zero-x"]);
  assert.equal(zx.m.layout.x.zero, true);
  assert.ok(zx.hand.x.lo <= 0, "scale.zero on x: the handed x axis reaches 0, " + zx.hand.x.lo);
});

test("A.2: an authored domain fixes its axis, linear and log, switch or no switch (D4)", () => {
  for (const [name, x, y] of [["domain-linear", [10, 20], [100, 200]], ["domain-log", [10, 1000], [1, 100]]]) {
    const d = draw(FX[name]);
    same([d.hand.x.lo, d.hand.x.hi], x, name + " x");
    same([d.hand.value.lo, d.hand.value.hi], y, name + " y");
    assert.equal(circles(d.r).length, 7, name + ": the rows outside the domain are still marks");
    same(rules(d.r), [], name + ": no warning for a row outside the domain");
  }
});

test("A.2: the axis titles carry the unit after them; a title of null writes neither; no title is the field name", () => {
  const titles = (fx) => {
    const d = draw(fx);
    return [d.m.layout.bandTitle, d.m.layout.valueTitle, d.r.primitives.filter((p) => p.role === "axis" && p.k === "text").map((p) => p.text).sort()];
  };
  same(titles(FX.units), ["Distance (km)", "Speed (km/h)", ["Distance (km)", "Speed (km/h)"]]);
  same(titles(FX["one-series"]), ["X", "Y", ["X", "Y"]]);
  same(titles(variant("units", (c, e) => { e.x.title = null; e.y.title = null; })), [null, null, []]);
  same(titles(variant("units", (c, e) => { delete e.x.title; delete e.y.title; })), ["x (km)", "y (km/h)", ["x (km)", "y (km/h)"]]);
});

test("A.2: the cap: 1,000 rows drawn are drawn, 1,001 refused; the series cap: 8 drawn, 9 refused (D6, D10)", () => {
  const k = draw(FX.thousand);
  assert.equal(k.r.state, "rendered");
  assert.equal(circles(k.r).length, 1000);
  const over = draw(FX["thousand-one"]).r;
  assert.equal(over.state, "refused");
  assert.equal(over.summary.refusal.code, "chart.too_many_marks");
  const eight = draw(FX["eight-series"]).r;
  assert.equal(eight.state, "rendered");
  assert.equal(eight.summary.series.length, 8);
  const nine = draw(FX["nine-series"]).r;
  assert.equal(nine.state, "refused");
  assert.equal(nine.summary.refusal.code, "chart.too_many_series");
  // A hidden series' rows still count: 1,001 rows with some hidden are refused (the cap is on the rows drawn by the model).
  const hiddenOver = variant("thousand-one", (c, e) => {
    e.color = { field: "s", type: "nominal", sort: null, title: "Series" };
    c.columns.s = c.columns.x.map((v, i) => (i % 2 === 0 ? "a" : "b"));
    c.types.s = "STRING";
  });
  assert.equal(draw(hiddenOver, 960, 540, { hidden: ["string:b"] }).r.summary.refusal.code, "chart.too_many_marks");
});

// ------------------------------------------------------------------------------------------------- A.3 the marks

test("A.3: every centre at the handed scales' places of its values, clamped to the axes' ends (D4)", () => {
  let n = 0;
  for (const [name, fx] of Object.entries(FX)) {
    for (const [w, h] of [[960, 540], [480, 300], [240, 140]]) {
      const d = draw(fx, w, h);
      if (d.r.state !== "rendered") continue;
      same(centreFailures(fx, d), [], `${name} at ${w} x ${h}`);
      n += circles(d.r).length;
    }
  }
  console.log("A.3 centres checked: " + n);
  assert.ok(n > 3400, "the centre check is not vacuous: " + n);
  // The rows outside an authored domain sit on the axis's end, linear and log.
  for (const name of ["domain-linear", "domain-log"]) {
    const d = draw(FX[name]), c = byDatum(d.r), at = (s, v) => Math.round(s.scale(v) * 100) / 100;
    assert.equal(c[0].cx, at(d.hand.x, d.hand.x.lo), name + ": below x's domain");
    assert.equal(c[4].cx, at(d.hand.x, d.hand.x.hi), name + ": above x's domain");
    assert.equal(c[5].cy, at(d.hand.value, d.hand.value.lo), name + ": below y's domain");
    assert.equal(c[6].cy, at(d.hand.value, d.hand.value.hi), name + ": above y's domain");
    assert.equal(c[1].cx, at(d.hand.x, d.hand.x.lo), name + ": on x's lower end");
  }
});

test("A.3: the diameters: the area follows the value, from the document's range (D5)", () => {
  const diameters = (fx) => byDatum(draw(fx).r).map((c) => 2 * c.r);
  const TOL_D = 0.0101;
  // Written as numbers, never by D5's expression: the middle is sqrt(818), the diameter of the middle area of 6 and 40.
  near(diameters(FX["sizes-extreme"]), [6, 28.60, 40], TOL_D, "sizes −1e308, 0, 1e308");
  near(diameters(FX["sizes-tiny"]), [6, 40], TOL_D, "sizes 0, 5e-324");
  near(diameters(FX["sizes-tiny-three"]), [6, 28.60, 40], TOL_D, "sizes 0, 5e-324, 1e-323");
  near(diameters(FX["size-one-value"]), [28.60, 28.60, 28.60], TOL_D, "one distinct value");
  // The ordinary values by D5's formula.
  const area = (v, lo, hi, a, b) => Math.sqrt(a * a + ((v - lo) / (hi - lo)) * (b * b - a * a));
  const z = FX["size-default"].columns.z;
  near(diameters(FX["size-default"]), z.map((v) => area(v, 1, 10, 6, 40)), TOL_D, "the default range [6, 40]");
  near(diameters(FX["size-range-4-64"]), z.map((v) => area(v, 1, 10, 4, 64)), TOL_D, "the range [4, 64]");
  near(diameters(FX["exact-columns"]), [6, 40, Math.sqrt(36 + (4 / (12345678901234567890 - 1)) * (1600 - 36))], TOL_D, "exact columns");
  // Without size: every radius is --chart-point (4); the lookup's shape carries the drawn radius.
  for (const c of circles(draw(FX["one-series"]).r)) assert.equal(c.r, 4);
  const d = draw(FX["size-default"]);
  for (const e of d.r.index.marks) assert.equal(e.shape.r, byDatum(d.r)[e.datum].r, "the lookup's radius is the drawn one");
});

test("A.3: the ring is 0.75 × --chart-gap (1.5 px) in the canvas colour, scaled with the radii by what the frame kept", () => {
  const T = E.theme.resolve("light");
  for (const name of ["one-series", "size-default", "shade-sequential", "eight-series"]) {
    for (const c of circles(draw(FX[name]).r)) {
      assert.equal(c.strokeWidth, 1.5, name);
      assert.equal(c.stroke, "--chart-canvas");
      assert.equal(c.strokeColor, T.css("--chart-canvas"));
    }
  }
  // At 80 × 24 the frame keeps less than the 21.5 px asked: the radius and the ring shrink by the same factor.
  const fx = FX["size-default"], d = draw(fx, 80, 24);
  if (d.r.state === "rendered") {
    const k = Math.min(1, d.hand.pad.x / 21.5, d.hand.pad.y / 21.5);
    assert.ok(k < 1, "the small frame keeps less than asked: " + k);
    for (const c of circles(d.r)) assert.ok(Math.abs(c.strokeWidth - 1.5 * k) < 1e-9, `ring ${c.strokeWidth}, expected ${1.5 * k}`);
  }
});

test("A.3: no circle, ring included, crosses the plot's edge at any frame of the sweep (count printed, with a floor)", () => {
  let n = 0;
  const bad = [];
  for (const [name, fx] of Object.entries(FX)) {
    for (const [w, h] of [...WIDTHS.flatMap((w) => HEIGHTS.map((h) => [w, h])), [80, 24]]) {
      const r = E.render(inputOf(fx, w, h));
      if (r.state !== "rendered") continue;
      const p = r.index.plot;
      for (const c of circles(r)) {
        n++;
        const out = c.r + c.strokeWidth / 2;
        if (c.cx - out < p.x - TOL || c.cx + out > p.x + p.w + TOL || c.cy - out < p.y - TOL || c.cy + out > p.y + p.h + TOL) bad.push(`${name} ${w}x${h} row ${c.datum}`);
      }
    }
  }
  console.log(`A.3 crossing sweep: ${n} circles, ${bad.length} crossing`);
  assert.ok(n > 150000, "the sweep is not vacuous: " + n);
  same(bad.slice(0, 10), []);
});

test("A.3: with size the larger marks come first in the primitives; without, the rows' order", () => {
  for (const name of ["size-default", "size-range-4-64", "domain-linear", "sizes-extreme", "exact-columns"]) {
    const r = circles(draw(FX[name]).r).map((c) => c.r);
    for (let i = 1; i < r.length; i++) assert.ok(r[i] <= r[i - 1], `${name}: ${JSON.stringify(r)}`);
  }
  for (const name of ["one-series", "eight-series", "shade-sequential"]) {
    const rows = circles(draw(FX[name]).r).map((c) => c.datum);
    same(rows, rows.slice().sort((a, b) => a - b), name);
  }
});

// -------------------------------------------------------------------------------------------- A.4 lookup and card

test("A.4: one entry per mark, (series, datum) unique and carried by a mark of the same series and datum", () => {
  let n = 0;
  for (const [name, fx] of Object.entries(FX)) {
    const r = draw(fx).r;
    if (r.state !== "rendered") continue;
    const keys = new Set(r.index.marks.map((e) => e.series + "\u0000" + e.datum));
    assert.equal(keys.size, r.index.marks.length, name + ": (series, datum) unique");
    assert.equal(r.index.marks.length, circles(r).length, name + ": one entry per mark");
    for (const e of r.index.marks) {
      assert.ok(circles(r).some((c) => c.series === e.series && c.datum === e.datum), name + ": a mark for " + e.datum);
      assert.equal(e.near, true);
      assert.equal(e.key, true);
      assert.equal(e.emphasis, "ring");
      assert.equal(e.shape.kind, "point");
    }
    // The keys (brief 1052 D8): a key row per series, at the series' position; in a row, the points in the data's order.
    const ids = r.summary.series.map((x) => x.id);
    for (const e of r.index.marks) assert.equal(e.row, ids.indexOf(e.series), name + ": " + e.datum + "'s key row is its series' position");
    for (const row of new Set(r.index.marks.map((e) => e.row))) {
      const datums = r.index.marks.filter((e) => e.row === row).map((e) => e.datum);
      same(datums, datums.slice().sort((a, b) => a - b), name + ": the keys' order in row " + row);
    }
    n += r.index.marks.length;
  }
  assert.ok(n > 1100, "entries: " + n);
});

test("A.4: the lookup finds the nearest point within 24 px of its centre and none at 25", () => {
  const r = draw(FX["one-point"]).r, e = r.index.marks[0], p = r.index.plot;
  const dir = e.shape.x + 30 < p.x + p.w ? 1 : -1;
  assert.equal(E.lookup.at(r.index, e.shape.x + 24 * dir, e.shape.y, 24), 0);
  assert.equal(E.lookup.at(r.index, e.shape.x + 25 * dir, e.shape.y, 24), -1);
  // A large bubble is still found by its centre's distance (a point's rule), not by its disc.
  const b = draw(FX["size-default"]).r, big = b.index.marks.find((m) => m.shape.r > 19);
  assert.equal(E.lookup.at(b.index, big.shape.x, big.shape.y, 24), b.index.marks.indexOf(big));
});

test("A.4: the card's rows, their order, the kept row and the extra lines, for each colour and size combination", () => {
  const sized = (name) => variant(name, (c, e) => {
    e.size = { field: "z" };
    c.columns.z = c.columns.x.map((v, i) => i + 1);
    c.types.z = "BIGINT";
  });
  const CASES = [
    ["no colour, no size", FX["one-series"], null, ["X", "Y"]],
    ["series", FX["eight-series"], "Alpha", ["X", "Y"]],
    ["shade", FX["shade-sequential"], null, ["X", "Y", "v"]],
    ["size", FX["size-default"], null, ["X", "Y", "z"]],
    ["series and size", sized("eight-series"), "Alpha", ["X", "Y", "z"]],
    ["shade and size", sized("shade-sequential"), null, ["X", "Y", "z", "v"]],
  ];
  for (const [what, fx, heading, names] of CASES) {
    const r = draw(fx).r, e = r.index.marks.find((m) => m.datum === 0), c = circles(r).find((q) => q.datum === 0);
    assert.equal(e.card.heading, heading, what + ": heading");
    same(e.card.rows.map((x) => x.name), names, what + ": the rows' names and order");
    assert.equal(e.card.keep, 1, what + ": the kept row is y");
    assert.equal(e.card.rows[1].colour, c.fill, what + ": y carries the point's colour");
    assert.equal(e.card.rows[1].color, c.color, what);
    assert.equal(e.card.rows[0].colour, null, what + ": x carries none");
    same(e.card.extra, [], what + ": no tooltip, no extra line");
    console.log(`A.4 ${what}: ${JSON.stringify(e.card.rows.map((x) => [x.name, x.value]))}`);
  }
  // Six tooltip lines, in the document's order.
  const six = draw(FX["tooltip-six"]).r.index.marks[0].card.extra;
  assert.equal(six.length, 6);
  console.log("A.4 tooltip-six extra: " + JSON.stringify(six));
});

test("A.4: the unit follows the x and the y value and a tooltip line's own; a row's name carries none (G6)", () => {
  const e = draw(FX.units).r.index.marks[0];
  // Read once from the base's engine (#1088, #1089 landed) and pinned.
  same(e.card.rows.map((x) => [x.name, x.value]), [["Distance", "1,200 km"], ["Speed", "40 km/h"]]);
  same(e.card.extra, ["Weight: 3 kg"]);
});

test("A.4: a row drawn at an axis's end shows its TRUE values; two points at one place: the first found, both keyed", () => {
  const d = draw(FX["domain-linear"]).r, card = (i) => d.index.marks.find((m) => m.datum === i).card.rows.map((x) => x.value);
  same(card(0).slice(0, 2), ["5", "150"]);
  same(card(4).slice(0, 2), ["25", "150"]);
  same(card(5).slice(0, 2), ["15", "50"]);
  same(card(6).slice(0, 2), ["15", "250"]);
  const s = draw(FX["same-place"]).r, [a, b] = s.index.marks;
  same([a.shape.x, a.shape.y], [b.shape.x, b.shape.y], "rows 0 and 1 share a place");
  assert.equal(E.lookup.at(s.index, a.shape.x, a.shape.y, 24), 0, "the pointer shows the first");
  same(s.index.marks.map((m) => [m.datum, m.row, m.key]), [[0, 0, true], [1, 1, true], [2, 0, true]], "the keys reach each: a's rows 0 and 2 in row 0, b's row 1 in row 1");
});

// ----------------------------------------------------------------------------------------- A.5 legend and scale

test("A.5: two series or more: a switch legend whose keys are dots; one series: no legend", () => {
  const legend = (fx) => draw(fx).r.primitives.filter((p) => p.role === "legend" && p.k !== "group");
  const eight = legend(FX["eight-series"]);
  assert.equal(eight.filter((p) => p.k === "circle").length, 8);
  assert.equal(eight.filter((p) => p.k === "rect" || p.k === "line" || p.k === "path").length, 0, "no other key form");
  assert.equal(eight.filter((p) => p.k === "text").length, 8);
  assert.equal(legend(FX["one-series"]).length, 0);
  assert.equal(legend(FX["size-default"]).length, 0);
  assert.equal(draw(FX["one-series"]).m.legendKey, "legendKeyPoint");
});

test("A.5: a shade: the scale's legend with its two numbers, both scales; a null's colour under sum and count (D3, D7)", () => {
  const legend = (fx) => {
    const L = draw(fx).r.primitives.filter((p) => p.role === "legend" && p.k !== "group");
    return [L.filter((p) => p.k === "text").map((p) => p.text), L.filter((p) => p.k === "rect").map((p) => p.fill), L.filter((p) => p.k === "circle").length];
  };
  const SEQ = [1, 2, 3, 4, 5, 6, 7].map((i) => "--chart-seq-" + i), DIV = [1, 2, 3, 4, 5, 6, 7].map((i) => "--chart-div-" + i);
  // The texts read once from the base's engine (−30 and 60 are the values' ends; 70 is 20 + 50 about the mid of 20).
  same(legend(FX["shade-sequential"]), [["−30", "60"], SEQ, 0]);
  same(legend(FX["shade-diverging-mid"]), [["−30", "70"], DIV, 0]);
  const div = legend(FX["shade-diverging"]);
  same([div[1], div[2]], [DIV, 0]);
  assert.equal(div[0].length, 2);
  const fills = (fx) => byDatum(draw(fx).r).map((c) => c.fill);
  same(fills(FX["shade-null-sum"]), ["--chart-seq-1", "--chart-grid", "--chart-seq-7"]);
  same(fills(FX["shade-null-count"]), ["--chart-seq-7", "--chart-seq-1", "--chart-seq-7"]);
});

test("A.5: a hidden series: its marks gone, the value ends the shown rows', centres by THAT draw's scales, colours kept (I7)", () => {
  for (const [name, gone] of [["hidden-owner", "string:A"], ["hidden-wide", "string:Wide"]]) {
    const fx = FX[name], all = draw(fx, 960, 540, { hidden: [] }), off = draw(fx, 960, 540, { hidden: [gone] });
    assert.ok(circles(off.r).every((c) => c.series !== gone), name + ": the hidden series' marks are gone");
    same(centreFailures(fx, all), [], name + " nothing hidden");
    same(centreFailures(fx, off), [], name + " hidden");
    const colour = (r) => Object.fromEntries(circles(r).map((c) => [c.series, c.fill]));
    for (const [s, f] of Object.entries(colour(off.r))) assert.equal(f, colour(all.r)[s], name + ": the colour of " + s);
    const shown = fx.columns.y.filter((v, i) => "string:" + fx.columns.s[i] !== gone);
    assert.ok(off.hand.value.lo <= Math.min(...shown) && off.hand.value.hi >= Math.max(...shown), name + ": the value axis holds the shown rows");
    assert.ok(off.hand.value.hi < all.hand.value.hi, name + ": and refits to them");
    same(off.r.primitives.filter((p) => p.role === "legend" && p.k === "text" && p.struck).map((p) => p.series), [gone]);
    console.log(`A.5 ${name}: plot.x ${all.r.index.plot.x} → ${off.r.index.plot.x}; value ${all.hand.value.lo}..${all.hand.value.hi} → ${off.hand.value.lo}..${off.hand.value.hi}`);
  }
  // Twelve-digit values hidden: the value labels narrow and the plot's left moves; the checks above still held.
  const w = FX["hidden-wide"];
  assert.notEqual(draw(w, 960, 540, { hidden: [] }).r.index.plot.x, draw(w, 960, 540, { hidden: ["string:Wide"] }).r.index.plot.x);
});

test("A.5: no tick text carries a unit; the unit is in the two axis titles only (G6)", () => {
  const r = draw(FX.units).r, texts = r.primitives.filter((p) => p.k === "text");
  const ticks = texts.filter((p) => p.role === "tick");
  assert.ok(ticks.length > 10, "ticks: " + ticks.length);
  same(ticks.filter((p) => /km/.test(p.text)), []);
  same(texts.filter((p) => /km/.test(p.text)).map((p) => p.text).sort(), ["Distance (km)", "Speed (km/h)"]);
});
