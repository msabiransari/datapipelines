// LA14 (brief 1053 A.2, D5): the colours of the look contract's §3.6 are the checked ones. The colours are read from
// the engine's own defaults (R.theme.defaults), so the check is of what the engine holds; the arithmetic is palette.mjs
// (published definitions, written here: the validator of the first study is not in the product, D5). Each set is
// compared all pairs: the colour-blind number at least 8 and the normal one at least 15, and both the contract's to one
// decimal; a number from 6 to 8 (the validator's state `floor`) is red here. Two controls show the check can fail.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine } from "./engine.mjs";
import { check, distances, lightness, CVD_TARGET, NORMAL_FLOOR } from "./palette.mjs";

const R = engine();
const DEFAULTS = { light: R.theme.defaults("light"), dark: R.theme.defaults("dark") };
const colours = (theme, names) => names.map((n) => [...DEFAULTS[theme][n]]);
const one = (x) => Math.round(x * 10) / 10;

const WATERFALL = ["--chart-positive", "--chart-negative", "--chart-total"];
const GAUGE = ["--chart-good", "--chart-warn", "--chart-bad"];
const RAMP = [1, 2, 3, 4, 5, 6, 7].map((n) => "--chart-div-" + n);

/** The contract's §3.6 table: the lowest colour-blind and normal-vision distances of each set, all pairs. */
const SETS = [
  { name: "waterfall", theme: "light", tokens: WATERFALL, cvd: 19.5, normal: 23.9 },
  { name: "waterfall", theme: "dark", tokens: WATERFALL, cvd: 19.5, normal: 23.0 },
  { name: "gauge tones", theme: "light", tokens: GAUGE, cvd: 11.3, normal: 17.7 },
  { name: "gauge tones", theme: "dark", tokens: GAUGE, cvd: 14.1, normal: 24.0 },
];

test("LA14: the thresholds are the contract's (8 and 15)", () => {
  assert.equal(CVD_TARGET, 8);
  assert.equal(NORMAL_FLOOR, 15);
});

for (const s of SETS) {
  test(`LA14: ${s.name}, ${s.theme}: colour-blind at least 8, normal at least 15, both the contract's to one decimal (${s.cvd}, ${s.normal})`, () => {
    const c = check(colours(s.theme, s.tokens));
    console.log(`LA14 ${s.name} ${s.theme}: colour-blind ${c.cvd.toFixed(3)} (pair ${c.cvdPair.map((i) => s.tokens[i]).join(" / ")}), normal ${c.normal.toFixed(3)}`);
    assert.ok(c.cvd >= CVD_TARGET, "colour-blind " + c.cvd);
    assert.ok(c.cvd >= 8, "colour-blind " + c.cvd + " (the target by its number)");
    assert.ok(c.normal >= 15, "normal " + c.normal);
    assert.equal(one(c.cvd), s.cvd);
    assert.equal(one(c.normal), s.normal);
    assert.equal(c.pass, true);
  });
}

for (const theme of ["light", "dark"]) {
  test(`LA14: the two-sided ramp, ${theme}: lightness one way from step 4 to each end, the arms within 0.02, the ends told apart`, () => {
    const L = colours(theme, RAMP).map(lightness);
    const outward = theme === "light" ? (a, b) => b < a : (a, b) => b > a;
    for (let i = 3; i > 0; i--) assert.ok(outward(L[i], L[i - 1]), `step ${i} after step ${i + 1}: ${L[i - 1]} vs ${L[i]}`);
    for (let i = 3; i < 6; i++) assert.ok(outward(L[i], L[i + 1]), `step ${i + 2} after step ${i + 1}: ${L[i + 1]} vs ${L[i]}`);
    for (const [a, b] of [[0, 6], [1, 5], [2, 4]]) assert.ok(Math.abs(L[a] - L[b]) <= 0.02, `steps ${a + 1} and ${b + 1}: ${L[a]} vs ${L[b]}`);
    const ends = distances(...colours(theme, [RAMP[0], RAMP[6]]));
    console.log(`LA14 ramp ${theme}: lightness ${L.map((x) => x.toFixed(4)).join(" ")}; steps 1 and 7 colour-blind ${ends.cvd.toFixed(3)}`);
    assert.ok(ends.cvd >= 8, "steps 1 and 7: " + ends.cvd);
    assert.equal(one(ends.cvd), theme === "light" ? 22.4 : 28.3);
  });
}

/** A copy of the defaults with the gauge's green and red replaced by the plain ones, the theme's amber kept. */
const control = (theme) => {
  const d = Object.assign({}, DEFAULTS[theme], { "--chart-good": [0x2f, 0x9e, 0x44], "--chart-bad": [0xc9, 0x2a, 0x2a] });
  return GAUGE.map((n) => [...d[n]]);
};

test("LA14 control (a): light, plain green #2f9e44 and red #c92a2a beside the light amber: 4.5, the green beside the amber, not a pass", () => {
  const c = check(control("light"));
  console.log(`LA14 control (a): colour-blind ${c.cvd.toFixed(3)} (pair ${c.cvdPair.map((i) => GAUGE[i]).join(" / ")}), pass ${c.pass}`);
  assert.equal(one(c.cvd), 4.5);
  assert.deepEqual(c.cvdPair, [0, 1]);
  assert.equal(c.pass, false);
});

test("LA14 control (b): dark, the same two beside the dark amber: 6.1 (the validator's `floor`), the green beside the red, not a pass", () => {
  const c = check(control("dark"));
  console.log(`LA14 control (b): colour-blind ${c.cvd.toFixed(3)} (pair ${c.cvdPair.map((i) => GAUGE[i]).join(" / ")}), pass ${c.pass}`);
  assert.equal(one(c.cvd), 6.1);
  assert.deepEqual(c.cvdPair, [0, 2]);
  assert.ok(c.cvd >= 6 && c.cvd < 8, "between the floor and the target");
  assert.equal(c.pass, false);
});

test("palette.mjs: a colour is whole channels 0 to 255 or #rrggbb; a set needs two colours", () => {
  assert.deepEqual(distances("#ffffff", [255, 255, 255]), { normal: 0, cvd: 0, protan: 0, deutan: 0 });
  assert.ok(Math.abs(lightness("#ffffff") - 1) < 1e-6, "white has lightness 1");
  assert.ok(Math.abs(lightness([0, 0, 0])) < 1e-12, "black has lightness 0");
  assert.throws(() => distances([256, 0, 0], [0, 0, 0]), /not a colour/);
  assert.throws(() => check([[1, 2, 3]]), /two colours/);
});
