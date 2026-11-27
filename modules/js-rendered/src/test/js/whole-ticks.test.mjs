// A count axis reads whole numbers (#1102): a cartesian layout part that carries `whole: true` gets a value axis whose
// ticks are all whole numbers (11-axes.js wholeTicks), and the bar, line and area templates carry it for a count that is
// not a share. Every check has its plant: the same property run on an engine with the one-line fault, which must fail it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, ENGINE } from "./engine.mjs";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();
const fixture = (wire, name) => FIXTURES.find((f) => f.wire === wire && f.name === name).fixture;
const WHOLE_TEXT = /^[0-9,]+$/;

/** The value axis's tick texts of a picture: end-anchored on a vertical value axis, middle-anchored on a horizontal bar's. */
function valueTicks(out, horizontal) {
  const t = out.primitives.filter((p) => p.k === "text" && p.role === "tick");
  return Array.from((horizontal ? t.filter((p) => p.anchor === "middle") : t.filter((p) => p.anchor === "end")).map((p) => p.text));
}

/** A count document of one family and rows with the given count per category (and per series when `series` is given). */
function countPicture(E, wire, counts, w, h, o = {}) {
  const y = { type: "quantitative", aggregate: "count" };
  if (o.stack !== undefined) y.stack = o.stack;
  const band = { field: "k", type: "nominal", sort: null };
  const enc = o.horizontal ? { y: band, x: y } : { x: band, y };
  if (o.series) enc.color = { field: "s", type: "nominal", sort: null };
  const mark = { type: wire };
  if (o.labels) mark.labels = o.labels;
  const k = [], s = [];
  counts.forEach((n, i) => { for (let j = 0; j < n; j++) { k.push("c" + i); s.push(o.series ? o.series[j % o.series.length] : "a"); } });
  const columns = { k }, types = { k: "STRING" };
  if (o.series) { columns.s = s; types.s = "STRING"; }
  const document = { display_name: "c", renderer: { kind: "chart", version: "1" }, config: { mark, encoding: enc, title: { text: "count" } } };
  return E.render({ type: wire, document, columns, types, frame: { w, h }, theme: "light", measure: null, hidden: [] });
}

/** The faults found in the value axes of the count fixtures at every width and height the sweep uses. */
function fixtureFaults(E) {
  const faults = [];
  const rows = [["bar", "count-small", false, 3], ["bar", "count-horizontal-one", true, 1], ["line", "count-small", false, 3], ["area", "count-small", false, 3]];
  for (const [wire, name, horizontal, tallest] of rows) for (const w of WIDTHS) for (const h of HEIGHTS) {
    const out = E.render(inputOf(fixture(wire, name), w, h));
    if (out.state !== "rendered") continue;
    const t = valueTicks(out, horizontal);
    const where = `${wire}/${name} ${w}x${h}`;
    if (t.some((x) => !WHOLE_TEXT.test(x))) faults.push(`${where}: a tick that is not a whole number: ${t.join(" ")}`);
    if (out.level !== "minimal" && t.length < 2) faults.push(`${where}: under two ticks: ${t.join(" ")}`);
    if (t.length > 0 && Number(t[t.length - 1].replace(/,/g, "")) < tallest) faults.push(`${where}: the top tick is below the tallest count ${tallest}: ${t.join(" ")}`);
  }
  return faults;
}

/** An engine with one file's one occurrence of `from` replaced by `to` (the plant: the anchor occurs exactly once). */
function planted(file, from, to) {
  return engine((s) => {
    const n = s[file].split(from).length - 1;
    if (n !== 1) throw new Error(`${file}: the plant's anchor occurs ${n} times: ${from}`);
    s[file] = s[file].replace(from, to);
  });
}
const BAR_LINE = "        whole: agg === \"count\" && !normalize,\n";
const noBar = () => planted("types/bar.js", BAR_LINE, "");
const noLines = () => planted("16-lines.js", BAR_LINE, "");
const noCall = () => planted("12-cartesian.js", "room, L.whole)", "room)");

test("G1: bar, line and area count fixtures draw whole-number ticks, at least two, the top tick holding the tallest count, at every width and height", () => {
  const faults = fixtureFaults(R);
  assert.deepEqual(faults, []);
  // non-vacuity: the sweep drew pictures with ticks (175 per fixture, four fixtures)
  let drawn = 0;
  for (const [wire, name, horizontal] of [["bar", "count-small", false], ["bar", "count-horizontal-one", true], ["line", "count-small", false], ["area", "count-small", false]])
    for (const w of WIDTHS) for (const h of HEIGHTS) if (valueTicks(R.render(inputOf(fixture(wire, name), w, h)), horizontal).length >= 2) drawn++;
  assert.ok(drawn >= 400, "pictures with a value axis: " + drawn);
});

test("G1 plants: without the bar's line, without the lines' line, without the call's argument the same sweep finds faults", () => {
  for (const [label, E] of [["bar.js", noBar()], ["16-lines.js", noLines()], ["12-cartesian.js", noCall()]]) {
    const faults = fixtureFaults(E);
    assert.ok(faults.length > 0, label + " plant found nothing");
    // the plant of one template leaves the other families whole
  }
  assert.ok(fixtureFaults(noBar()).every((f) => f.startsWith("bar/")), "the bar plant breaks only bar pictures");
  assert.ok(fixtureFaults(noLines()).every((f) => !f.startsWith("bar/")), "the lines plant breaks only line and area pictures");
});

test("G2: a stacked count and a coloured count draw whole ticks (a plain document, not a product fixture)", () => {
  for (const wire of ["bar", "area"]) {
    for (const h of [200, 240, 480, 540]) {
      const out = countPicture(R, wire, [4, 2, 1], 960, h, { series: ["x", "y"], stack: "zero" });
      assert.equal(out.state, "rendered");
      const t = valueTicks(out, false);
      assert.ok(t.length >= 2 && t.every((x) => WHOLE_TEXT.test(x)), `${wire} ${h}: ${t.join(" ")}`);
    }
  }
  const labelled = countPicture(R, "bar", [1, 1], 480, 360, { labels: "values" });
  assert.ok(valueTicks(labelled, false).every((x) => WHOLE_TEXT.test(x)), "value labels widen the axis range, the ticks stay whole");
  // the plant: the bar template without whole draws quarter ticks in some of these pictures
  const bad = [];
  for (const h of HEIGHTS) bad.push(...valueTicks(countPicture(noBar(), "bar", [4, 2, 1], 960, h, { series: ["x", "y"], stack: "zero" }), false));
  assert.ok(bad.some((x) => !WHOLE_TEXT.test(x)), "plant: " + bad.join(" "));
});

test("G3: a sum, a mean and a share keep the ticks they had: whole is a count's alone", () => {
  // a bar over rows with the values 1 and 2 summed: the ticks of a measure are the nice ticks, quarter steps and all
  const document = { display_name: "s", renderer: { kind: "chart", version: "1" }, config: { mark: { type: "bar" }, encoding: { x: { field: "k", type: "nominal", sort: null }, y: { field: "v", type: "quantitative", aggregate: "sum" } }, title: { text: "s" } } };
  const out = R.render({ type: "bar", document, columns: { k: ["a", "b"], v: [1, 2] }, types: { k: "STRING", v: "DOUBLE" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] });
  const t = valueTicks(out, false);
  assert.ok(t.some((x) => !WHOLE_TEXT.test(x)), "a sum of 1 and 2 keeps its fractional ticks: " + t.join(" "));
  // a count stacked to 100 percent is a share: percent ticks, as many as before
  const share = valueTicks(R.render(inputOf(fixture("bar", "count-normalized"), 960, 540)), false);
  assert.deepEqual(share, ["0%", "20%", "40%", "60%", "80%", "100%"]);
  // the plant: whole on the share too (the histogram's `!share` rule, here `!normalize`)
  const bad = planted("types/bar.js", BAR_LINE, "        whole: agg === \"count\",\n");
  const wrong = valueTicks(bad.render(inputOf(fixture("bar", "count-normalized"), 960, 540)), false);
  assert.notDeepEqual(wrong, share, "plant: " + wrong.join(" "));
});

/** The value axis alone: [lo, hi, ticks] for a call of R.axes.valueAxis at the full level. */
const axis = (E, ext, length, o = {}) => E.axes.valueAxis(o.level || "full", ext, o.domain || null, length, undefined, o.room || 0, o.whole);
const ticksOf = (E, ext, length, o) => JSON.parse(JSON.stringify(axis(E, ext, length, o).ticks));

test("G4: the value axis alone: whole ticks over a table of extents, the same ticks as before where they were whole already", () => {
  const TABLE = [
    [[0, 1], [0, 1], [0, 0.2, 0.4, 0.6, 0.8, 1]],
    [[0, 2], [0, 1, 2], [0, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]],
    [[0, 3], [0, 1, 2, 3], [0, 0.5, 1, 1.5, 2, 2.5, 3]],
    [[0, 4], [0, 1, 2, 3, 4], [0, 0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4]],
    [[0, 5], [0, 1, 2, 3, 4, 5], [0, 1, 2, 3, 4, 5]],
    [[0, 8], [0, 1, 2, 3, 4, 5, 6, 7, 8], [0, 1, 2, 3, 4, 5, 6, 7, 8]],
    [[0, 9], [0, 2, 4, 6, 8, 10], [0, 2, 4, 6, 8, 10]],
    [[0, 60], [0, 10, 20, 30, 40, 50, 60], [0, 10, 20, 30, 40, 50, 60]],
  ];
  for (const [ext, whole, plain] of TABLE) {
    assert.deepEqual(ticksOf(R, ext, 400, { whole: true }), whole, "whole " + ext);
    for (const off of [undefined, false, null, 0, "yes"]) assert.deepEqual(ticksOf(R, ext, 400, { whole: off }), plain, "whole " + off + " " + ext);
  }
  // the descent: without it the one-step answer stands (the plant): 0 and 5, not 0 1 2 3
  const noDescent = planted("11-axes.js", "w && k >= 1 && !allWhole(n.ticks); k--)", "false && k >= 1 && !allWhole(n.ticks); k--)");
  assert.deepEqual(ticksOf(noDescent, [0, 3], 400, { whole: true }), [0, 5], "plant");
});

test("G5: one step is the last whole answer: an extent that is not whole and a plot of one tick spacing", () => {
  assert.deepEqual(ticksOf(R, [0, 0.5], 300, { whole: true }), [0, 1]);
  assert.deepEqual(ticksOf(R, [0, 0.3], 50, { whole: true }), [0, 1]);
  assert.deepEqual(ticksOf(R, [0, 2.3], 50, { whole: true }), [0, 5]);
  assert.deepEqual(ticksOf(R, [0, 0], 300, { whole: true }), [0, 1], "an extent of nothing");
  // the plant: without the one-step widening the half-step answer stays
  const noStep = planted("11-axes.js", "if (w && !domain && !allWhole(n.ticks)) n = S.nice(Math.floor(lo), Math.ceil(hi), 1);", "");
  assert.deepEqual(ticksOf(noStep, [0, 0.5], 300, { whole: true }), [0, 0.5], "plant");
});

test("G6: the room for value labels grows the range and the ticks stay whole", () => {
  assert.deepEqual(ticksOf(R, [0, 1], 150, { whole: true, room: 14 }), [0, 1, 2]);
  assert.deepEqual(ticksOf(R, [0, 3], 300, { whole: true, room: 20 }), [0, 1, 2, 3, 4]);
  // the plant: the growth loop asks without whole
  const noRoom = planted("11-axes.js", "n = wholeTicks(null, Math.min(lo, n.lo), Math.max(hi, n.hi), intervals, whole);", "n = wholeTicks(null, Math.min(lo, n.lo), Math.max(hi, n.hi), intervals, false);");
  assert.deepEqual(ticksOf(noRoom, [0, 1], 150, { whole: true, room: 14 }), [0, 0.5, 1, 1.5], "plant");
});

test("G7: an author's domain with whole ends gets whole ticks; a domain with a non-whole end is drawn as written", () => {
  assert.deepEqual(ticksOf(R, [0, 3], 400, { whole: true, domain: [0, 3] }), [0, 1, 2, 3]);
  assert.deepEqual(ticksOf(R, [0, 3], 400, { domain: [0, 3] }), [0, 0.5, 1, 1.5, 2, 2.5, 3]);
  assert.deepEqual(ticksOf(R, [0, 3], 400, { whole: true, domain: [0, 2.5] }), ticksOf(R, [0, 3], 400, { domain: [0, 2.5] }));
  assert.deepEqual(ticksOf(R, [0, 3], 400, { whole: true, domain: [0, 2.5] }), [0, 0.5, 1, 1.5, 2, 2.5]);
  // the plant: whole ignores the author's non-whole end
  const noRule = planted("11-axes.js", "var w = whole === true && (!domain || allWhole(domain));", "var w = whole === true;");
  assert.notDeepEqual(ticksOf(noRule, [0, 3], 400, { whole: true, domain: [0, 2.5] }), [0, 0.5, 1, 1.5, 2, 2.5], "plant");
});

test("G8: the contract names the histogram lane checks: allWhole once in 11-axes.js, L.whole once in 12-cartesian.js, each as one line", () => {
  const lines = (file, text) => readFileSync(join(ENGINE, file), "utf8").split("\n").filter((l) => l.includes(text)).length;
  assert.equal(lines("11-axes.js", "function allWhole"), 1);
  assert.equal(lines("12-cartesian.js", "L.whole"), 1);
  // the plant: a comment that repeats a name makes the histogram lane's `grep -c` read 2, so this count catches it
  const withComment = "// L.whole\n" + readFileSync(join(ENGINE, "12-cartesian.js"), "utf8");
  assert.equal(withComment.split("\n").filter((l) => l.includes("L.whole")).length, 2, "plant");
});
