// jsRenderedEmit (brief D1, D11; record A3 and I6): Node's side of the identity test. For every chart fixture at each
// frame of FRAMES it writes the exact input text it gives `render` and the canonical JSON text of the result; the JVM
// test (JsRenderedIdentityTest) sends the same input text through the sandbox and compares the result byte for byte.
// It also writes LA11's helper choices (on-fill.json), which the JVM test recomputes in the sandbox.
// Brief 1049 D5 adds, for the JVM guard JsRenderedTestTypesTest: every fixture of every test type at the same frames,
// _types/<type>/<fixture>/<w>x<h>.{input,output}.json, listed in types-index.txt (never in index.txt: the product's
// identity test and its counts do not see them); trig.json, the engine's sine and cosine of every multiple of 0.25°
// from −720° to 720° as [deg, sin, cos] in canonical number text; sweep.json, the invariant sweep's widths and heights
// (geometry.mjs), the one list both runtimes sweep.
// Brief 1050 A.3 adds colour.json for the JVM test JsRenderedColourScaleTest: the colour scale's steps and tokens over a
// table of ranges and values, and its end texts over sets of aggregates, each row holding its inputs, so the sandbox
// recomputes the same rows from them (COLOUR below, the test's copy of it) and compares the text byte for byte.
// Usage: node emit.mjs <out dir>. The out dir is emptied first, so a removed fixture leaves no file behind.
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { engine, engineWith, chartFixtures, inputOf, canonical, testTypes, typeFixtures, typeInputOf } from "./engine.mjs";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";

/**
 * One frame per level and the empty below it: full, compact, minimal, too small; and the full and compact level
 * boundaries themselves (#985 R1: the frames the levels advertise must fit, so the sandbox checks them too).
 */
const FRAMES = [[960, 540], [480, 360], [300, 180], [120, 40], [60, 20], [360, 240], [200, 140]];

const out = process.argv[2];
if (!out) throw new Error("usage: node emit.mjs <out dir>");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

const R = engine();
const fixtures = chartFixtures();
if (fixtures.length === 0) throw new Error("no chart fixture found");
const index = [];
for (const f of fixtures) {
  const dir = join(out, f.wire, f.name);
  mkdirSync(dir, { recursive: true });
  for (const [w, h] of FRAMES) {
    // The input crosses as text: JSON.parse of this text is what both runtimes render.
    const text = JSON.stringify(inputOf(f.fixture, w, h));
    writeFileSync(join(dir, `${w}x${h}.input.json`), text);
    writeFileSync(join(dir, `${w}x${h}.output.json`), canonical(R.render(JSON.parse(text))));
    index.push(`${f.wire}/${f.name}/${w}x${h}`);
  }
}

// LA11: the helper's choice for every default series colour and ramp step, both themes, and with the inks swapped.
const choices = [];
for (const theme of ["light", "dark"]) {
  const d = R.theme.defaults(theme), light = [...d["--chart-on-fill-light"]], dark = [...d["--chart-on-fill-dark"]];
  for (const [prefix, count] of [["--chart-series-", 8], ["--chart-seq-", 7]]) {
    for (let i = 1; i <= count; i++) {
      const fill = [...d[prefix + i]];
      choices.push({ theme, token: prefix + i, fill, light, dark, ink: R.theme.onFill(fill, light, dark), swapped: R.theme.onFill(fill, dark, light) });
    }
  }
}
{
  const d = R.theme.defaults("light"), light = [...d["--chart-on-fill-light"]], dark = [...d["--chart-on-fill-dark"]], grey = [128, 128, 128];
  choices.push({ theme: "light", token: "grey-128", fill: grey, light, dark, ink: R.theme.onFill(grey, light, dark), swapped: R.theme.onFill(grey, dark, light) });
}
writeFileSync(join(out, "on-fill.json"), JSON.stringify(choices));

// A.3: the colour scale's table. The ranges: whole borders, below zero, the extremes of D4, two neighbouring floats,
// no spread, and ranges whose borders are not whole. Per range 22 values from lo to hi (computed without overflow and
// kept inside the range) and a null. The end texts: exact texts beyond 2^53, ties on one num at either end, floats.
const COLOUR_RANGES = [[0, 7], [-14, -7], [-1e308, 1e308], [0, 1e308], [1, 1 + Number.EPSILON], [-3.3, 1e-9], [2, 2], [-Number.MAX_VALUE, Number.MAX_VALUE], [0.1, 0.7], [-0.05, 0.4]];
const colourSteps = [];
for (const [lo, hi] of COLOUR_RANGES) {
  for (let i = 0; i <= 21; i++) colourSteps.push([lo, hi, Math.min(hi, Math.max(lo, lo + (hi / 2 - lo / 2) * 2 * (i / 21)))]);
  colourSteps.push([lo, hi, null]);
}
const ex = (num, exact) => ({ num, exact });
const colourEnds = [
  [ex(5, "5"), ex(9007199254740992, "9007199254740993")],
  [ex(9007199254740992, "9007199254740992"), ex(9007199254740992, "9007199254740993"), ex(1, "1")],
  [ex(-9007199254740992, "-9007199254740992"), ex(-9007199254740992, "-9007199254740993"), ex(1, "1")],
  [ex(2, null), ex(2, "2.00")],
  [ex(0.5, null), ex(2.25, null), null, ex(null, null)],
  [ex(-1234567.125, "-1234567.125"), ex(0.000001, "0.000001")],
];
/** COLOUR: the rows of colour.json from their inputs (JsRenderedColourScaleTest runs its copy of this in the sandbox). */
const COLOUR = (C, t) => JSON.stringify({
  steps: t.steps.map((r) => {
    const s = C.sequential([{ num: r[0], exact: null }, { num: r[1], exact: null }]);
    return [r[0], r[1], r[2], C.step(s, r[2]), C.token(s, r[2])];
  }),
  ends: t.ends.map((e) => {
    const s = C.sequential(e);
    return [e, s.loText, s.hiText];
  }),
});
const colour = COLOUR(R.colour, { steps: colourSteps, ends: colourEnds });
writeFileSync(join(out, "colour.json"), colour);
writeFileSync(join(out, "index.txt"), index.join("\n") + "\n");

// The test types (brief 1049 D5): their pictures under _types/, their own index.
const names = testTypes();
if (names.length === 0) throw new Error("no test type found");
const T = engineWith(names), typesIndex = [];
let typeFixtureCount = 0;
for (const name of names) {
  const fxs = typeFixtures(name);
  if (fxs.length === 0) throw new Error("test type " + name + " has no fixture");
  for (const f of fxs) {
    typeFixtureCount++;
    const dir = join(out, "_types", name, f.name);
    mkdirSync(dir, { recursive: true });
    for (const [w, h] of FRAMES) {
      const text = JSON.stringify(typeInputOf(f.fixture, w, h));
      writeFileSync(join(dir, `${w}x${h}.input.json`), text);
      writeFileSync(join(dir, `${w}x${h}.output.json`), canonical(T.render(JSON.parse(text))));
      typesIndex.push(`${name}/${f.name}/${w}x${h}`);
    }
  }
}
writeFileSync(join(out, "types-index.txt"), typesIndex.join("\n") + "\n");

// The engine's own sine and cosine (D4): 5,761 angles.
const trig = [];
for (let i = 0; i <= 5760; i++) {
  const a = -720 + i / 4;
  trig.push([a, R.trig.sin(a), R.trig.cos(a)]);
}
writeFileSync(join(out, "trig.json"), JSON.stringify(trig));
writeFileSync(join(out, "sweep.json"), JSON.stringify({ widths: WIDTHS, heights: HEIGHTS }));
console.log(`test types: ${names.length} types, ${typeFixtureCount} fixtures x ${FRAMES.length} frames = ${typesIndex.length} pictures; trig ${trig.length} angles; sweep ${WIDTHS.length} x ${HEIGHTS.length}`);
console.log(`emitted ${fixtures.length} chart fixtures x ${FRAMES.length} frames = ${index.length} pictures, ${choices.length} on-fill choices, ${colourSteps.length} colour steps and ${colourEnds.length} end texts`);
