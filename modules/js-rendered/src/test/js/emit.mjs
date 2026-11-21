// jsRenderedEmit (brief D1, D11; record A3 and I6): Node's side of the identity test. For every chart fixture at each
// frame of FRAMES it writes the exact input text it gives `render` and the canonical JSON text of the result; the JVM
// test (JsRenderedIdentityTest) sends the same input text through the sandbox and compares the result byte for byte.
// It also writes LA11's helper choices (on-fill.json), which the JVM test recomputes in the sandbox.
// Brief 1049 D5 adds, for the JVM guard JsRenderedTestTypesTest: every fixture of every test type at the same frames,
// _types/<type>/<fixture>/<w>x<h>.{input,output}.json, listed in types-index.txt (never in index.txt: the product's
// identity test and its counts do not see them); trig.json, the engine's sine and cosine of every multiple of 0.25°
// from −720° to 720° as [deg, sin, cos] in canonical number text; sweep.json, the invariant sweep's widths and heights
// (geometry.mjs), the one list both runtimes sweep.
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
console.log(`emitted ${fixtures.length} chart fixtures x ${FRAMES.length} frames = ${index.length} pictures, ${choices.length} on-fill choices`);
