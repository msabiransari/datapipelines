// forms.mjs : what the tests of brief 1051 (the other axis forms and the bare frame) share. The five test types of D8
// are loaded with a SPY on their two functions, added in the copy engineWith makes (an `edit`; the type files are not
// changed): `R.spy(hand, m)` sees what the frame handed the template and the model, `R.spyModel(m)` may change the
// model before the frame lays it out (the second axis's twin, A.3). Neither changes a picture: with no spy set the
// functions are the files' own. The plants of G2 to G7 are further edits of the same copy, each anchored on a text that
// occurs exactly once.
import assert from "node:assert/strict";
import { engineWith, testTypeKey, typeFixtures, typeInputOf } from "./engine.mjs";

/** The test types of brief 1051 D8. */
export const FORMS = ["test_points", "test_grid", "test_two_axes", "test_bins", "test_bare"];

/** The tests' fixed-width measurer: six px a character at every size and weight, so a reader can redo every number. */
export const SIX = (text) => 6 * [...text].length;

const SPY_FROM = "    model: model,\n    marks: marks,\n";
const SPY_TO =
  "    model: function (d, c, t) { var m = model(d, c, t); if (R.spyModel) R.spyModel(m); return m; },\n" +
  "    marks: function (m, hand, h) { if (R.spy) R.spy(hand, m); return marks(m, hand, h); },\n";

/** Replaces the one occurrence of `from` in srcs[key] by `to`; red when the anchor is not there exactly once. */
export function plant(srcs, key, from, to) {
  assert.ok(Object.prototype.hasOwnProperty.call(srcs, key), "a source named " + key);
  assert.equal(srcs[key].split(from).length, 2, "the anchor occurs once in " + key + ": " + JSON.stringify(from.slice(0, 80)));
  srcs[key] = srcs[key].replace(from, () => to);
}

/** The engine with the five test types and their spies; `edit` plants further changes in the same copy. */
export function formsEngine(edit) {
  return engineWith(FORMS, (srcs) => {
    for (const n of FORMS) plant(srcs, testTypeKey(n), SPY_FROM, SPY_TO);
    if (edit) edit(srcs);
  });
}

/** One fixture of a test type, by name. */
export function fixture(type, name) {
  const f = typeFixtures(type).find((x) => x.name === name);
  assert.ok(f, `fixture ${type}/${name}`);
  return f.fixture;
}

/** A render with the spy set: {r, hand, model}; hand and model are null where the draw stopped before the marks. */
export function draw(E, fx, w, h, extra = {}, change = null) {
  let hand = null, model = null;
  E.spy = (x, m) => {
    hand = x;
    model = m;
  };
  E.spyModel = change;
  try {
    return { r: E.render(typeInputOf(fx, w, h, extra)), hand, model };
  } finally {
    E.spy = null;
    E.spyModel = null;
  }
}

/** The plot's rectangle as the picture draws it (the plot background, role grid). */
export function plotRect(r) {
  return r.primitives.find((p) => p.k === "rect" && p.fill === "--chart-plot") || null;
}

export const ticks = (r) => r.primitives.filter((p) => p.k === "text" && p.role === "tick");
export const shownText = (t) => (t.shown !== null ? t.shown : t.text);
/** Tick labels left of the plot, written at their end (value ticks; the rows' names). */
export const leftLabels = (r, plot) => ticks(r).filter((t) => t.anchor === "end" && t.rotate === 0 && t.x <= plot.x + 0.01);
/** Tick labels right of the plot, written from their start (the second axis's). */
export const rightLabels = (r, plot) => ticks(r).filter((t) => t.anchor === "start" && t.rotate === 0 && t.x >= plot.x + plot.w - 0.01);
/** Tick labels under the plot (the x labels, turned or not): not a value axis's lowest label, which may stand lower. */
export const underLabels = (r, plot) => {
  const side = new Set(leftLabels(r, plot).concat(rightLabels(r, plot)));
  return ticks(r).filter((t) => t.y > plot.y + plot.h + 0.01 && !side.has(t));
};

/** Two numbers within the rounding of a coordinate written to two decimals (one rounding on each side). */
export function near(a, b, tol = 0.011) {
  return Math.abs(a - b) <= tol;
}
