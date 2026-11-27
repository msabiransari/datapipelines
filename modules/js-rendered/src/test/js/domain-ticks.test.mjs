// An author's scale.domain draws at least two value ticks at every frame that draws ticks (#1104): the nice values inside
// the domain, or the domain's two ends where fewer than two nice values are inside (07-scales.js within). Every check has
// its plant: the same property run on an engine with the one-line fault, which must fail it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();
const fixture = (wire, name) => FIXTURES.find((f) => f.wire === wire && f.name === name).fixture;

/** The value axis's tick texts of a picture: end-anchored on a vertical value axis, middle-anchored on a horizontal bar's. */
function valueTicks(out, horizontal) {
  const t = out.primitives.filter((p) => p.k === "text" && p.role === "tick");
  return Array.from((horizontal ? t.filter((p) => p.anchor === "middle") : t.filter((p) => p.anchor === "end")).map((p) => p.text));
}

/** A bar, line or area document over a band field k and a summed value v with an author's domain on the value. */
function valueDoc(type, domain, horizontal) {
  const y = { field: "v", type: "quantitative", aggregate: "sum", scale: { domain } };
  const band = { field: "k", type: "nominal", sort: null };
  const encoding = type === "bar" && horizontal ? { y: band, x: y } : { x: band, y };
  return { display_name: "d", renderer: { kind: "chart", version: "1" }, config: { mark: { type }, encoding, title: { text: "d" } } };
}
/** Three rows that sit inside the domain (a share of its span each). */
const rowsIn = (d) => ({ columns: { k: ["a", "b", "c"], v: [0.9, 0.3, 0.6].map((s) => d[0] + s * (d[1] - d[0])) }, types: { k: "STRING", v: "DECIMAL" } });
const scatterDoc = (domain) => ({ display_name: "d", renderer: { kind: "chart", version: "1" }, config: { mark: { type: "scatter" }, encoding: { x: { field: "x", type: "quantitative" }, y: { field: "y", type: "quantitative", scale: { domain } } }, title: { text: "d" } } });
const scatterRows = (d) => ({ columns: { x: [1, 2, 3, 4], y: [0, 1 / 3, 1 / 2, 1].map((s) => d[0] + s * (d[1] - d[0])) }, types: { x: "DECIMAL", y: "DECIMAL" } });

// Domains an author writes. Holding zero where the axis holds it (a bar and an area; the save check refuses the others).
const HOLD_ZERO = [[0, 1], [0, 2], [0, 3], [0, 5], [0, 7], [0, 10], [0, 137], [0, 0.3], [0, 2.5], [0, 1e6], [-5, 5], [-10, 40], [-100, 0], [-3, 0.5]];
const AWAY = [[10, 50], [100, 200], [1000, 1010], [0.1, 0.9], [-5, -1]];
// The horizontal bar's list leaves out [0, 1] and [0, 2]: at the full level its last tick's label can be dropped by a rounding of the plot's right edge (#1105), a separate fault.
const HORIZONTAL = [[0, 3], [0, 10], [0, 137], [-10, 40], [-100, 0], [-3, 0.5], [0, 1e6]];
const FORMS = [
  ["bar", HOLD_ZERO, false, (d, w, h, E) => render(E, "bar", valueDoc("bar", d, false), rowsIn(d), w, h)],
  ["bar horizontal", HORIZONTAL, true, (d, w, h, E) => render(E, "bar", valueDoc("bar", d, true), rowsIn(d), w, h)],
  ["line", HOLD_ZERO.concat(AWAY), false, (d, w, h, E) => render(E, "line", valueDoc("line", d), rowsIn(d), w, h)],
  ["area", HOLD_ZERO, false, (d, w, h, E) => render(E, "area", valueDoc("area", d), rowsIn(d), w, h)],
  ["scatter y", HOLD_ZERO.concat(AWAY), false, (d, w, h, E) => render(E, "scatter", scatterDoc(d), scatterRows(d), w, h)],
];
function render(E, type, document, r, w, h) {
  return E.render({ type, document, columns: r.columns, types: r.types, frame: { w, h }, theme: "light", measure: null, hidden: [] });
}

/** Every picture of every form at the 175 frames of the invariant sweep: the faults, and how many pictures drew a value axis. */
function sweep(E) {
  const faults = [];
  let axes = 0, oneTickBase = 0;
  for (const [name, domains, horizontal, draw] of FORMS) for (const d of domains) for (const w of WIDTHS) for (const h of HEIGHTS) {
    const out = draw(d, w, h, E);
    if (out.state !== "rendered" || out.level === "minimal") continue;
    axes++;
    const t = valueTicks(out, horizontal);
    if (t.length < 2) faults.push(`${name} domain ${JSON.stringify(d)} ${w}x${h} ${out.level}: ${t.length} tick(s) [${t.join(" ")}]`);
  }
  return { faults, axes };
}

/** An engine with one file's one occurrence of `from` replaced by `to` (the plant: the anchor occurs exactly once). */
function planted(file, from, to) {
  return engine((s) => {
    const n = s[file].split(from).length - 1;
    if (n !== 1) throw new Error(`${file}: the plant's anchor occurs ${n} times: ${from}`);
    s[file] = s[file].replace(from, to);
  });
}
const RULE = "if (ticks.length === 0 || (ticks.length === 1 && hi > lo)) ticks = [lo, hi];";
const noRule = () => planted("07-scales.js", RULE, "if (ticks.length === 0) ticks = [lo, hi];");

test("G1: bar, horizontal bar, line, area and the scatter's y with an author's domain draw two ticks or more at every frame that draws a value axis", () => {
  const { faults, axes } = sweep(R);
  assert.deepEqual(faults.slice(0, 12), [], faults.length + " pictures with fewer than two ticks");
  assert.ok(axes >= 10000, "pictures with a value axis: " + axes); // non-vacuity: the sweep drew them
});

test("G1 plant: without the one-tick rule the same sweep finds the one-tick pictures", () => {
  const { faults } = sweep(noRule());
  assert.ok(faults.length >= 1000, "plant found " + faults.length);
  assert.ok(faults.every((f) => /compact|full/.test(f)), "every fault names its level");
  assert.ok(faults.some((f) => f.startsWith("bar domain [0,3] 240x140 compact: 1 tick(s) [0]")), "the issue's own picture: 240x140, domain [0, 3], the zero tick alone");
});

/** The value axis alone: the ticks of a call of R.axes.valueAxis. */
const axis = (E, lvl, domain, length, o = {}) => JSON.parse(JSON.stringify(E.axes.valueAxis(lvl, o.ext || [Math.min(0, domain ? domain[0] : 0), Math.max(0, domain ? domain[1] : 1)], domain, length, undefined, o.room || 0, o.whole).ticks));

test("G2: the axis alone: a table of domains and lengths, the nice values inside where two or more fit, else the two ends", () => {
  const TABLE = [
    // [level, domain, length px, ticks]
    ["compact", [0, 3], 71.88, [0, 3]], // the issue's picture: 240x140 compact, plot 71.88 px (one tick spacing is 72)
    ["compact", [0, 3], 131.88, [0, 3]], // 240x200: plot 131.88 px, still one interval
    ["compact", [0, 3], 144, [0, 2]], // two intervals: the nice values inside, as before
    ["full", [0, 3], 400, [0, 0.5, 1, 1.5, 2, 2.5, 3]],
    ["full", [0, 10], 300, [0, 2, 4, 6, 8, 10]],
    ["compact", [-10, 40], 120, [-10, 40]], // one nice value (0) inside a plot of one interval: the ends
    ["compact", [-10, 40], 150, [-10, 40]], // two intervals and still one nice value inside (-50, 0, 50): the ends
    ["full", [-10, 40], 300, [-10, 0, 10, 20, 30, 40]],
    ["compact", [10, 50], 71.88, [10, 50]], // a domain away from zero: the ticks inside were [50] alone
    ["compact", [0.55, 1.45], 71.88, [0.55, 1.45]], // the second axis's left-one-tick fixture
    ["full", [3, 97], 40, [3, 97]], // no nice value inside at all: the ends, as before the change
  ];
  for (const [lvl, domain, length, want] of TABLE) assert.deepEqual(axis(R, lvl, domain, length), want, `${lvl} ${JSON.stringify(domain)} ${length}`);
  // the plant: without the one-tick rule the one-tick rows are the one nice value
  const bad = noRule();
  assert.deepEqual(axis(bad, "compact", [0, 3], 71.88), [0], "plant");
  assert.deepEqual(axis(bad, "compact", [10, 50], 71.88), [50], "plant");
  assert.deepEqual(axis(bad, "compact", [-10, 40], 150), [0], "plant");
});

test("G3: nothing else moves: no domain, a domain with two nice values inside, and a domain that is no range keep their ticks", () => {
  // no author's domain: the ticks of the nice range, two at one interval ("0" and "5" over 0 to 3), never one
  assert.deepEqual(axis(R, "compact", null, 71.88, { ext: [0, 3] }), [0, 5]);
  assert.deepEqual(axis(R, "full", null, 400, { ext: [0, 3] }), [0, 0.5, 1, 1.5, 2, 2.5, 3]);
  // two or more nice values inside: the base's own answer
  assert.deepEqual(axis(R, "full", [0, 1e6], 300), [0, 200000, 400000, 600000, 800000, 1000000]);
  // a domain that is no range (the save check refuses it; the engine keeps its answer) keeps its one tick, never two equal ticks
  assert.deepEqual(axis(R, "compact", [5, 5], 71.88, { ext: [0, 5] }), [5]);
  assert.deepEqual(axis(R, "compact", [5, 3], 71.88, { ext: [0, 5] }), axis(planted("07-scales.js", RULE, "if (ticks.length === 0) ticks = [lo, hi];"), "compact", [5, 3], 71.88, { ext: [0, 5] }));
  // the plant: without the `hi > lo` guard a domain of one value draws its tick twice
  const noGuard = planted("07-scales.js", RULE, "if (ticks.length < 2) ticks = [lo, hi];");
  assert.deepEqual(axis(noGuard, "compact", [5, 5], 71.88, { ext: [0, 5] }), [5, 5], "plant");
});

test("G4: with whole (a count axis, #1102): whole ends keep whole ticks at one interval; a half-unit end is drawn as written", () => {
  assert.deepEqual(axis(R, "compact", [0, 3], 71.88, { whole: true }), [0, 3]);
  assert.deepEqual(axis(R, "compact", [0, 7], 71.88, { whole: true }), [0, 7]);
  assert.deepEqual(axis(R, "compact", [0, 2.5], 71.88, { whole: true }), [0, 2.5]);
  assert.deepEqual(axis(R, "full", [0, 3], 400, { whole: true }), [0, 1, 2, 3]);
});

test("G5: the five new fixtures read the ticks their notes say at the frames of the sandbox identity test", () => {
  const ticks = (wire, name, w, h, horizontal) => valueTicks(R.render(inputOf(fixture(wire, name), w, h)), horizontal);
  assert.deepEqual(ticks("bar", "domain-compact", 200, 140, false), ["0", "3"]);
  assert.deepEqual(ticks("bar", "domain-compact", 300, 180, false), ["0", "3"]);
  assert.deepEqual(ticks("bar", "domain-compact", 360, 240, false), ["0", "1", "2", "3"]);
  assert.deepEqual(ticks("bar", "domain-horizontal-signed", 200, 140, true), ["−10", "40"]);
  assert.deepEqual(ticks("line", "domain-away", 200, 140, false), ["10", "50"]);
  assert.deepEqual(ticks("line", "domain-away", 360, 240, false), ["20", "40"]);
  assert.deepEqual(ticks("area", "domain-compact", 200, 140, false), ["0", "3"]);
  assert.deepEqual(ticks("scatter", "domain-few", 200, 140, false), ["0", "3"]);
  const bad = noRule();
  const was = (wire, name, w, h, horizontal) => valueTicks(bad.render(inputOf(fixture(wire, name), w, h)), horizontal);
  assert.deepEqual(was("bar", "domain-compact", 200, 140, false), ["0"], "plant");
  assert.deepEqual(was("line", "domain-away", 200, 140, false), ["50"], "plant");
  assert.deepEqual(was("bar", "domain-horizontal-signed", 200, 140, true), ["0"], "plant");
});
