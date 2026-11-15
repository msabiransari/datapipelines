// jsRenderedEmit (brief D1, D11; record A3 and I6): Node's side of the identity test. For every chart fixture at each
// frame of FRAMES it writes the exact input text it gives `render` and the canonical JSON text of the result; the JVM
// test (JsRenderedIdentityTest) sends the same input text through the sandbox and compares the result byte for byte.
// It also writes LA11's helper choices (on-fill.json), which the JVM test recomputes in the sandbox.
// Usage: node emit.mjs <out dir>. The out dir is emptied first, so a removed fixture leaves no file behind.
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, canonical } from "./engine.mjs";

/** One frame per level and the empty below it: full, compact, minimal, too small. */
const FRAMES = [[960, 540], [480, 360], [300, 180], [120, 40], [60, 20]];

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
console.log(`emitted ${fixtures.length} chart fixtures x ${FRAMES.length} frames = ${index.length} pictures, ${choices.length} on-fill choices`);
