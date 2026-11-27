// The horizontal bar draws the label of its last value tick, whole (#1105). The layout reserves half that label's width
// at the right of the plot; the plot's left edge and its width are each rounded to a hundredth, so the last tick can
// stand up to 0.01 px short of the reserved edge, and the room labelRow gives it (twice that distance) up to 0.02 px
// short: the label is cut ("15" as "...") or left out. 12-cartesian.js gives the box's edge a grain of 0.015 px for it.
// Every check has its plant: the same property run on an engine with the one-line fault, which must fail it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();
const fixture = (name) => FIXTURES.find((f) => f.wire === "bar" && f.name === name).fixture;
/** The four fixtures, with the data's extent (zero included): the label of the first and of the last tick must hold it. */
const CASES = [["horizontal-last-tick-count", 0, 7], ["horizontal-last-tick-negative", -7, 0], ["horizontal-last-tick-large", 0, 123450], ["horizontal-last-tick-small", 0, 0.013]];
const GRAIN = "x1: b.right + 0.015 }, plot: plot, corner: last";

/** A tick's text as a number: "8", "1,200", "10K", "−7" (U+2212 is the minus the engine writes). NaN for anything else. */
function read(text) {
  const m = /^(−?)([0-9][0-9,]*(?:\.[0-9]+)?)([KMBT]?)$/.exec(text);
  if (m === null) return NaN;
  return (m[1] === "" ? 1 : -1) * Number(m[2].replace(/,/g, "")) * { "": 1, K: 1e3, M: 1e6, B: 1e9, T: 1e12 }[m[3]];
}

/** The value-tick text primitives of a horizontal bar's picture: middle-anchored (the category names are end-anchored). */
const valueTicks = (out) => out.primitives.filter((p) => p.k === "text" && p.role === "tick" && p.anchor === "middle");

/** What is wrong with the value axis of one picture of a horizontal bar whose data runs from lo to hi (zero included). */
function faultsOf(out, lo, hi, where) {
  const faults = [];
  if (out.state !== "rendered" || out.level === "minimal") return faults;
  const t = valueTicks(out);
  for (const p of t) if (p.shown !== null && p.shown !== undefined) faults.push(`${where}: the label of the tick ${p.text} is drawn as ${p.shown}`);
  const v = t.map((p) => read(p.text));
  if (v.length < 2 || v.some(Number.isNaN)) faults.push(`${where}: value ticks ${t.map((p) => p.text).join(" ")}`);
  else if (v[v.length - 1] < hi) faults.push(`${where}: the last tick ${t[t.length - 1].text} is below the largest value ${hi}: ${t.map((p) => p.text).join(" ")}`);
  else if (v[0] > lo) faults.push(`${where}: the first tick ${t[0].text} is above the smallest value ${lo}: ${t.map((p) => p.text).join(" ")}`);
  return faults;
}

/** An engine with one file's one occurrence of `from` replaced by `to` (the plant: the anchor occurs exactly once). */
function planted(from, to) {
  return engine((s) => {
    const n = s["12-cartesian.js"].split(from).length - 1;
    if (n !== 1) throw new Error(`12-cartesian.js: the plant's anchor occurs ${n} times: ${from}`);
    s["12-cartesian.js"] = s["12-cartesian.js"].replace(from, to);
  });
}
const noGrain = () => planted(GRAIN, "x1: b.right }, plot: plot, corner: last");
const smallGrain = () => planted(GRAIN, "x1: b.right + 0.005 }, plot: plot, corner: last");

/** The faults of the three fixtures at the 175 widths and heights of the invariant sweep. */
function fixtureFaults(E) {
  const faults = [];
  for (const [name, lo, hi] of CASES) for (const w of WIDTHS) for (const h of HEIGHTS) faults.push(...faultsOf(E.render(inputOf(fixture(name), w, h)), lo, hi, `${name} ${w}x${h}`));
  return faults;
}

/** A count document of three categories (k, ceil(k/2) and 1 rows), horizontal or vertical, as a picture at a frame. */
function countPicture(E, k, w, h, horizontal) {
  const y = { type: "quantitative", aggregate: "count" }, band = { field: "k", type: "nominal", sort: null };
  const document = { display_name: "c", renderer: { kind: "chart", version: "1" }, config: { mark: { type: "bar" }, encoding: horizontal ? { y: band, x: y } : { x: band, y }, title: { text: "count" } } };
  const rows = [k, Math.ceil(k / 2), 1], c = [];
  rows.forEach((n, i) => { for (let j = 0; j < n; j++) c.push("c" + i); });
  return E.render({ type: "bar", document, columns: { k: c }, types: { k: "STRING" }, frame: { w, h }, theme: "light", measure: null, hidden: [] });
}

/** A sum document (sums 0.013, 0.007 and 0.001) as a horizontal bar at a frame: the data on which a grain of 0.005 is still too small. */
function smallSumPicture(E, w, h) {
  const document = { display_name: "c", renderer: { kind: "chart", version: "1" }, config: { mark: { type: "bar" }, encoding: { y: { field: "k", type: "nominal", sort: null }, x: { type: "quantitative", aggregate: "sum", field: "v" } }, title: { text: "t" } } };
  return E.render({ type: "bar", document, columns: { k: ["c0", "c1", "c2"], v: [0.013, 0.007, 0.001] }, types: { k: "STRING", v: "DOUBLE" }, frame: { w, h }, theme: "light", measure: null, hidden: [] });
}

/** The faults of counts 1 to 60 at the 175 frames; the vertical bar is the control (it has no label to lose). */
function sweepFaults(E, horizontal) {
  const faults = [];
  let drawn = 0;
  for (let k = 1; k <= 60; k++) for (const w of WIDTHS) for (const h of HEIGHTS) {
    const out = countPicture(E, k, w, h, horizontal);
    if (horizontal) { faults.push(...faultsOf(out, 0, k, `count ${k} ${w}x${h}`)); if (out.level !== "minimal" && valueTicks(out).length >= 2) drawn++; }
    else {
      const t = out.primitives.filter((p) => p.k === "text" && p.role === "tick" && p.anchor === "end").map((p) => p.text);
      if (out.level !== "minimal" && (t.length < 2 || read(t[t.length - 1]) < k)) faults.push(`vertical count ${k} ${w}x${h}: ${t.join(" ")}`);
      if (t.length >= 2) drawn++;
    }
  }
  return { faults, drawn };
}

test("G1: the four fixtures draw every value tick label whole and hold the data between the first and the last tick, at every width and height", () => {
  assert.deepEqual(fixtureFaults(R), []);
  let drawn = 0;
  for (const [name] of CASES) for (const w of WIDTHS) for (const h of HEIGHTS) if (valueTicks(R.render(inputOf(fixture(name), w, h))).length >= 2) drawn++;
  assert.ok(drawn >= 550, "pictures with a value axis: " + drawn);
});

test("G1 plants: without the grain the same check finds a fault in each fixture", () => {
  const faults = fixtureFaults(noGrain());
  for (const [name] of CASES) assert.ok(faults.some((f) => f.startsWith(name + " ")), "no fault found in " + name + ": " + faults.length + " in all");
});

test("G2: the five pictures of the issue: counts 7, 4 and 1 at 360x240, 360x480, 400x240 and 400x480 draw the last tick, 8", () => {
  for (const [w, h] of [[360, 240], [360, 480], [400, 240], [400, 480]]) {
    assert.deepEqual(Array.from(valueTicks(countPicture(R, 7, w, h, true)), (p) => p.text), ["0", "2", "4", "6", "8"], `${w}x${h}`);
    assert.deepEqual(Array.from(valueTicks(countPicture(noGrain(), 7, w, h, true)), (p) => p.text), ["0", "2", "4", "6"], `plant ${w}x${h}`);
  }
});

test("G3: counts 1 to 60 as a horizontal bar: no cut label, the last tick holds the tallest count, at every width and height", () => {
  const { faults, drawn } = sweepFaults(R, true);
  assert.deepEqual(faults.slice(0, 5), []);
  assert.equal(faults.length, 0);
  assert.ok(drawn >= 8000, "pictures with a value axis: " + drawn);
});

test("G3 plants: without the grain the sweep finds the cut and missing labels (at least 1,000)", () => {
  assert.ok(sweepFaults(noGrain(), true).faults.length >= 1000);
});

test("G4: the vertical bar is the control: no fault, and the engine without the grain draws the same vertical pictures byte for byte", () => {
  const { faults, drawn } = sweepFaults(R, false);
  assert.deepEqual(faults.slice(0, 5), []);
  assert.ok(drawn >= 8000, "pictures with a value axis: " + drawn);
  const P = noGrain();
  for (let k = 1; k <= 60; k += 7) for (const w of WIDTHS) for (const h of HEIGHTS) assert.equal(JSON.stringify(countPicture(R, k, w, h, false)), JSON.stringify(countPicture(P, k, w, h, false)), `vertical ${k} ${w}x${h}`);
});

test("G5: every width from 200 to 1,700 px at heights 140 and 240: the four fixtures draw the last tick label whole", () => {
  const bad = [];
  let pictures = 0;
  for (const [name, lo, hi] of CASES) for (let w = 200; w <= 1700; w++) for (const h of [140, 240]) {
    const out = R.render(inputOf(fixture(name), w, h));
    if (out.state === "rendered" && out.level !== "minimal") pictures++;
    bad.push(...faultsOf(out, lo, hi, `${name} ${w}x${h}`));
  }
  assert.deepEqual(bad.slice(0, 5), []);
  assert.ok(pictures >= 10000, "pictures checked: " + pictures);
  let found = 0, whole = 0;
  const S = smallGrain();
  for (let w = 200; w <= 1700; w++) for (const h of [140, 240]) {
    found += faultsOf(smallSumPicture(S, w, h), 0, 0.013, "").length;
    whole += faultsOf(smallSumPicture(R, w, h), 0, 0.013, "").length;
  }
  assert.equal(whole, 0);
  assert.ok(found >= 500, "a grain of 0.005 leaves " + found + " faults: the dense check must be able to tell it from 0.015");
});

test("G6: the grain moves nothing else: against the engine without it a picture differs only by the last tick's label and its grid line", () => {
  const P = noGrain();
  const isTick = (p) => p.k === "text" && p.role === "tick" && p.anchor === "middle";
  const isGridV = (p) => p.k === "line" && p.role === "grid" && p.x1 === p.x2;
  const rest = (o) => JSON.stringify({ ...o, primitives: o.primitives.filter((p) => !isTick(p) && !isGridV(p)) });
  let changed = 0;
  for (const [name] of CASES) for (const w of WIDTHS) for (const h of HEIGHTS) {
    const a = R.render(inputOf(fixture(name), w, h)), b = P.render(inputOf(fixture(name), w, h));
    if (JSON.stringify(a) === JSON.stringify(b)) continue;
    changed++;
    assert.equal(rest(a), rest(b), `${name} ${w}x${h}: something other than a tick label or a vertical grid line moved`);
    const ta = valueTicks(a), tb = valueTicks(b), last = Math.max(...ta.map((p) => p.x));
    for (const p of tb) assert.ok(ta.some((q) => q.x === p.x && q.text === p.text), `${name} ${w}x${h}: the tick ${p.text} is gone`);
    for (const q of ta) { const p = tb.find((t) => t.x === q.x); if (!p || JSON.stringify(p) !== JSON.stringify(q)) assert.equal(q.x, last, `${name} ${w}x${h}: a label other than the last changed`); }
  }
  assert.ok(changed >= 100, "pictures the grain changes: " + changed);
});
