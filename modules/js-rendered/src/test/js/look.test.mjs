// A.7: the look table and the look contract's rows F1 carries (D6, D6a): A14's `render` half, LA1 (the engine's half),
// LA2, LA3, LA5, LA7, LA8, LA11. A contrast here is computed by THIS test with the WCAG formula and Math.pow (a test
// may call it; the engine may not), so the engine's sRGB table is checked against an independent oracle.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, chartFixtures, inputOf, canonical, typeFixtures, typeInputOf } from "./engine.mjs";
import { violations, WIDTHS, HEIGHTS } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();
// Bar's fixtures by name: a name is unique within its type's directory only (line/ and area/ have their own `hostile`).
const fx = (name) => FIXTURES.find((f) => f.wire === "bar" && f.name === name).fixture;
const plain = (v) => JSON.parse(JSON.stringify(v));

// ---------------------------------------------------------------- A14 and LA5: the look table

/** LA5: every non-token value of the contract's §3.8 that bar and the shared layout use, by field (closed list). */
const LA5 = ["pieInnerDefault", "pieLabelsDefault", "holeTotalMin", "sliceLabelPad", "holeUnitGap", "pieLabelRadius", "pieOtherText", "pieMoveAllowance", "gridValue", "gridCategory", "frame", "zeroLine", "barShare", "stackShare", "radiusMaxShare", "roundOuterOnly", "legendKeyBar", "labelsDefault", "stackTotalFormat",
  "levelFull", "levelCompact", "levelMinimal", "tickSpacing", "tickSpacingCompact", "rotateAngle", "rotateHiddenShare", "labelMinEm", "rotateMaxEm", "bandLabelMaxEm", "legendSideMaxEm",
  "textAscent", "textDescent", "edge", "lineHeight", "titleGap", "tickGap", "labelGap", "cornerClear", "axisTitleGap", "legendKey", "legendKeyGap", "legendEntryGap", "legendGap", "valueLabelGap",
  "emptyText", "morePrefix",
  // The line family's (brief 989 D6, D7, D10a): its defaults, its legend keys, the direct legend's bounds and gap.
  "curveDefault", "pointsDefault", "lineLabelsDefault", "lastLabelFormat", "lineRound", "areaFade", "legendKeyLine", "legendKeyArea", "directMin", "directMax", "directGap",
  // The radial axis's (brief 1055 D8): its rings' form, its tick rule's span, its names' column and the rule of no room.
  "radialRings", "radialTickSpan", "radialNameShare", "radialRadiusFloor",
  // Colour by value's (brief 1050 D5): the width of a step of a colour scale's legend, in keys.
  "legendStepRatio",
  // The legend's forms (brief 1054 D8): a point's key, a dot.
  "legendKeyPoint"];

/**
 * The interaction layer's fields (brief 990 D4a): consumer `interaction`, read on the page by the layer and never by
 * render. Their A14 half is the browser's (a placed pointer sees each field's own result change); here they are only
 * required to exist, to name their consumer, and to leave every picture render draws unchanged.
 */
const INTERACTION = ["tipDistance", "tipLine", "tipPad", "tipKey", "tipGap", "tipMax", "tipTotalJoin", "tipValueWeight", "crosshairDot", "focusGap", "markReach", "markMove", "markRing"];

test("LA5: the look table is closed and holds exactly the fixed values bar, the line family, the shared layout and the interaction layer use", () => {
  assert.deepEqual(Object.keys(R.tables.LOOK).sort(), [...LA5, ...INTERACTION].sort());
  for (const [name, f] of Object.entries(R.tables.LOOK)) {
    assert.deepEqual(Object.keys(f), ["value", "consumer", "note"], name);
    assert.equal(f.consumer, INTERACTION.includes(name) ? "interaction" : "render", name + ": its one consumer");
    assert.ok(f.note.length > 10, name);
  }
  assert.throws(() => R.tables.look("nope"), /no look-table field nope/);
});

/** Another value of a field: its other form for a word, the other truth value, a number moved by a quarter. */
const OTHER = { pieLabelsDefault: "none", pieOtherText: "Rest", legendSideMaxEm: 2, legendKeyBar: "line", labelsDefault: "none", stackTotalFormat: "integer", emptyText: "Nothing to show", morePrefix: "and ", rotateAngle: 60,
  curveDefault: "step", pointsDefault: "markers", lineLabelsDefault: "last", lastLabelFormat: "integer", legendKeyLine: "square", legendKeyArea: "line",
  // A quarter down, not up: no fixture has five series, so 5 shows nothing; at 3 the four-line fixture loses its names on the lines.
  directMax: 3,
  // The ring's other form (brief 1055 D8; the contract's LA15 plant for the radar's rings).
  radialRings: "circle",
  // A point's key as a square (brief 1054 D8).
  legendKeyPoint: "square" };
function other(name, v) {
  if (Object.prototype.hasOwnProperty.call(OTHER, name)) return OTHER[name];
  if (typeof v === "boolean") return !v;
  if (typeof v === "number") return v <= 1 ? v * 0.8 : v * 1.25;
  if (Array.isArray(v)) return v.map((x) => x * 1.25);
  throw new Error("A14 has no other value for " + name);
}
const A14_FRAMES = [[960, 540], [480, 360], [372, 250], [300, 180], [240, 160], [200, 140], [150, 60], [90, 26], [80, 24], [1600, 480]];
/**
 * The fields only a frame no product type uses reads (brief 1055 D8): each is proven on the fixtures of the test type
 * named here (the engine loaded with it by engineWith, the field changed in that copy's table, the same frames) and is
 * dead when none of those pictures changes. The test type's fixtures never join chartFixtures(); and with each of these
 * fields changed, every chart fixture's picture equals its base (no product picture reads it).
 */
// legendKeyPoint left this table with brief 1062: scatter's legend reads it, so a product picture proves it (D8).
const A14_ON_TYPE = { radialRings: "test_web", radialTickSpan: "test_web", radialNameShare: "test_web", radialRadiusFloor: "test_web" };

test("A14 (render half): changing any one look field changes the primitives of at least one chart fixture", () => {
  const base = new Map();
  // A fixture's name is unique within its type only (line/, area/ and bar/ each have an `empty` and a `hostile`): the
  // key carries the type, or one type's picture would be compared with another's and every field would look alive.
  const key = (f, w, h) => f.wire + "/" + f.name + "@" + w + "x" + h;
  for (const f of FIXTURES) for (const [w, h] of A14_FRAMES) base.set(key(f, w, h), canonical(R.render(inputOf(f.fixture, w, h))));
  const dead = [];
  const typeBase = new Map();
  let onType = 0, productKept = 0;
  for (const name of Object.keys(R.tables.LOOK).filter((n) => R.tables.LOOK[n].consumer === "render")) {
    if (Object.prototype.hasOwnProperty.call(A14_ON_TYPE, name)) {
      const type = A14_ON_TYPE[name], fxs = typeFixtures(type), T0 = engineWith([type]), T = engineWith([type]);
      for (const f of fxs) for (const [w, h] of A14_FRAMES) if (!typeBase.has(key(f, w, h))) typeBase.set(key(f, w, h), canonical(T0.render(typeInputOf(f.fixture, w, h))));
      T.tables.LOOK[name].value = other(name, T.tables.LOOK[name].value);
      const first = fxs.flatMap((f) => A14_FRAMES.map(([w, h]) => [f, w, h])).find(([f, w, h]) => canonical(T.render(typeInputOf(f.fixture, w, h))) !== typeBase.get(key(f, w, h)));
      if (first === undefined) dead.push(name);
      else console.log(`A14 ${name}: first changed on ${type}/${first[0].name} at ${first[1]} x ${first[2]}`);
      const E = engine();
      E.tables.LOOK[name].value = other(name, E.tables.LOOK[name].value);
      for (const f of FIXTURES) {
        for (const [w, h] of A14_FRAMES) {
          assert.equal(canonical(E.render(inputOf(f.fixture, w, h))), base.get(key(f, w, h)), name + " reached a product picture: " + key(f, w, h));
          productKept++;
        }
      }
      onType++;
      continue;
    }
    const E = engine();
    E.tables.LOOK[name].value = other(name, E.tables.LOOK[name].value);
    let changed = false;
    for (const f of FIXTURES) {
      for (const [w, h] of A14_FRAMES) {
        if (canonical(E.render(inputOf(f.fixture, w, h))) !== base.get(key(f, w, h))) {
          changed = true;
          break;
        }
      }
      if (changed) break;
    }
    if (!changed) dead.push(name);
  }
  assert.deepEqual(dead, [], "look fields whose change no chart fixture (or, for A14_ON_TYPE, no fixture of its test type) shows");
  assert.ok(Object.keys(R.tables.LOOK).length >= 37, "fields checked");
  assert.equal(onType, Object.keys(A14_ON_TYPE).length, "every field of A14_ON_TYPE is in the table and proven on its type");
  assert.equal(productKept, onType * FIXTURES.length * A14_FRAMES.length);
  console.log(`A14 on a test type: ${onType} fields; ${productKept} chart pictures compared with each changed, 0 differ`);
});

test("A14 (the interaction fields): render never reads one; a change of each leaves every picture as it was (their check is the browser's)", () => {
  const base = new Map();
  const frames = [[960, 540], [200, 140], [80, 24]];
  for (const f of FIXTURES) for (const [w, h] of frames) base.set(f.wire + "/" + f.name + "@" + w, canonical(R.render(inputOf(f.fixture, w, h))));
  let pictures = 0;
  for (const name of INTERACTION) {
    const E = engine();
    E.tables.LOOK[name].value = E.tables.LOOK[name].value * 2;
    for (const f of FIXTURES) {
      for (const [w, h] of frames) {
        assert.equal(canonical(E.render(inputOf(f.fixture, w, h))), base.get(f.wire + "/" + f.name + "@" + w), name + " reached render: " + f.wire + "/" + f.name);
        pictures++;
      }
    }
  }
  assert.ok(pictures >= INTERACTION.length * 100, "pictures compared: " + pictures);
});

test("LA13 (the table's half): with the default tokens 2p + h is no more than 24, so 80 × 24 holds one line of the card", () => {
  const s = R.theme.resolve("light").n("--chart-text"), look = R.tables.look;
  const h = look("tipLine") * s, p = look("tipPad") * s;
  assert.ok(2 * p + h <= 24, "2p + h = " + (2 * p + h));
  assert.equal(Math.floor((24 - 2 * p) / h), 1, "k at a height of 24");
  assert.ok(80 - 2 * p > look("tipKey") * s + look("tipGap") * s, "the content area at 80 holds a key, a gap and a value");
});

// ---------------------------------------------------------------- LA1: the engine's defaults

test("LA1 (the engine's half): a light and a dark default for every token it receives, none for the stylesheet's two", () => {
  const light = plain(R.theme.defaults("light")), dark = plain(R.theme.defaults("dark"));
  assert.deepEqual(Object.keys(light), Object.keys(dark));
  // 56: the contract's valued tokens (§3.1 to §3.6) less the stylesheet's two. §3.6's thirteen (the colours with a
  // meaning and the two-sided ramp, the owner's pick L9) are defaults since brief 1053 D4, compared below with the
  // contract's table written here as numbers; series 9 to 12 are still a later lane's and are not invented here.
  assert.equal(Object.keys(light).length, 56);
  for (const t of ["--chart-series-9"]) assert.ok(!(t in light), t + " is a later lane's");
  const MEANING = {
    "--chart-positive": [[25, 113, 194], [77, 171, 247]],
    "--chart-negative": [[217, 72, 15], [255, 146, 43]],
    "--chart-total": [[52, 58, 64], [222, 226, 230]],
    "--chart-good": [[0, 117, 90], [66, 174, 142]],
    "--chart-warn": [[206, 124, 4], [250, 183, 42]],
    "--chart-bad": [[205, 23, 30], [212, 35, 37]],
    "--chart-div-1": [[40, 62, 149], [131, 155, 251]],
    "--chart-div-2": [[98, 121, 182], [99, 117, 181]],
    "--chart-div-3": [[165, 179, 213], [70, 81, 116]],
    "--chart-div-4": [[236, 238, 242], [42, 47, 56]],
    "--chart-div-5": [[215, 166, 153], [103, 76, 59]],
    "--chart-div-6": [[185, 93, 66], [168, 103, 52]],
    "--chart-div-7": [[127, 36, 0], [238, 130, 11]],
  };
  assert.equal(Object.keys(MEANING).length, 13);
  for (const [t, [l, d]] of Object.entries(MEANING)) {
    assert.deepEqual(light[t], l, t + " light");
    assert.deepEqual(dark[t], d, t + " dark");
  }
  for (const t of ["--chart-pad", "--chart-tip-shadow"]) {
    assert.ok(!(t in light), t + " is the stylesheet's alone");
    assert.throws(() => R.theme.resolve({ base: "light", tokens: { [t]: 1 } }), new RegExp("unknown token " + t));
  }
  for (let i = 1; i <= 8; i++) assert.ok("--chart-series-" + i in light);
  for (let i = 1; i <= 7; i++) assert.ok("--chart-seq-" + i in light);
  assert.equal(light["--font-sans"], "Noto Sans");
  assert.throws(() => R.theme.resolve("sepia"), /unknown theme name sepia/);
  assert.throws(() => R.theme.resolve({ base: "light", tokens: { "--chart-text": 40 } }), /--chart-text is outside 8 to 32/);
  assert.throws(() => R.theme.resolve({ base: "light", tokens: { "--chart-series-1": [1, 2, 3, 0.5] } }), /--chart-series-1 is not an opaque colour/);
  assert.throws(() => R.theme.resolve({ base: "light", tokens: { "--chart-ink": [1.5, 2, 3] } }), /--chart-ink is not a colour/);
});

// ---------------------------------------------------------------- LA2: a token reaches the picture

const LA2_FIXTURES = ["look-eight-series", "look-hidden-series", "look-horizontal-stacked", "look-negative", "hostile", "look-empty-zero-rows", "parity-bar-grouped", "corner-production-balance_by_partner--real_window_balance"];
const drawWith = (name, tokens) => R.render(inputOf(fx(name), 960, 540, { theme: { base: "light", tokens } })).primitives;

/** The indices and changed field names of two primitive lists of the same length. */
function diff(a, b) {
  assert.equal(a.length, b.length, "the same number of primitives");
  const out = [];
  for (let i = 0; i < a.length; i++) {
    const fields = Object.keys(a[i]).filter((k) => JSON.stringify(a[i][k]) !== JSON.stringify(b[i][k]));
    if (fields.length) out.push({ i, p: a[i], fields });
  }
  return out;
}

const COLOURS = ["--chart-canvas", "--chart-plot", "--chart-grid", "--chart-axis", "--chart-ink", "--chart-ink-2", "--chart-muted", "--chart-series-1", "--chart-series-2", "--chart-series-3",
  "--chart-series-4", "--chart-series-5", "--chart-series-6", "--chart-series-7", "--chart-series-8"];

test("LA2: each colour token bar and the layout draw with changes exactly the primitives naming it, and only their colour", () => {
  for (const token of COLOURS) {
    let changed = 0;
    for (const name of LA2_FIXTURES) {
      const d = diff(drawWith(name, {}), drawWith(name, { [token]: [1, 2, 3] }));
      for (const { p, fields } of d) {
        const governs = (p.fill === token && fields.join() === "color") || (p.stroke === token && fields.join() === "strokeColor");
        assert.ok(governs, token + " changed " + p.k + "/" + p.role + " fields " + fields.join());
        changed++;
      }
    }
    assert.ok(changed > 0, token + " reached no primitive");
  }
});

test("LA2: --chart-dim fades a hidden series' legend key and nothing else", () => {
  const d = diff(drawWith("look-hidden-series", {}), drawWith("look-hidden-series", { "--chart-dim": 0.5 }));
  assert.equal(d.length, 1);
  assert.equal(d[0].p.role, "legend");
  assert.deepEqual(d[0].fields, ["opacity"]);
});

test("LA2: --chart-stroke changes stroke widths and nothing else", () => {
  let n = 0;
  for (const name of LA2_FIXTURES) for (const { fields } of diff(drawWith(name, {}), drawWith(name, { "--chart-stroke": 2 }))) {
    assert.deepEqual(fields, ["strokeWidth"]);
    n++;
  }
  assert.ok(n > 0);
});

test("LA2: --chart-bar-radius changes the marks and nothing else; --chart-gap the marks and their value labels", () => {
  for (const [token, value, roles] of [["--chart-bar-radius", 7, ["mark"]], ["--chart-gap", 5, ["mark", "guide"]]]) {
    let n = 0;
    for (const name of LA2_FIXTURES) for (const { p } of diff(drawWith(name, {}), drawWith(name, { [token]: value }))) {
      assert.ok(roles.includes(p.role), token + " changed a " + p.role);
      n++;
    }
    assert.ok(n > 0, token);
  }
});

test("LA2: the text sizes and the title's size and weight (two cases) reach the texts they govern", () => {
  const sizes = (prims, role) => [...new Set(prims.filter((p) => p.k === "text" && p.role === role).map((p) => p.size))];
  const name = "look-hidden-series";
  const t13 = drawWith(name, { "--chart-text": 13 });
  for (const role of ["tick", "axis", "legend"]) assert.deepEqual(sizes(t13, role), [13], role);
  assert.deepEqual(sizes(t13, "guide"), [11], "value labels keep the small size");
  assert.deepEqual(sizes(t13, "title"), [14], "the title keeps its size");
  const s10 = drawWith(name, { "--chart-text-small": 10 });
  assert.deepEqual(sizes(s10, "guide"), [10]);
  assert.deepEqual(sizes(s10, "tick"), [12]);
  const title = drawWith(name, { "--chart-title": 18 });
  assert.deepEqual(sizes(title, "title"), [18]);
  const d = diff(drawWith(name, {}), drawWith(name, { "--chart-title-weight": 700 }));
  assert.equal(d.length, 1, "the weight changes one primitive");
  assert.equal(d[0].p.role, "title");
  assert.deepEqual(d[0].fields, ["weight"]);
  const empty = drawWith("look-empty-zero-rows", { "--chart-text": 13 });
  assert.deepEqual(sizes(empty, "empty"), [13], "the empty state's text");
});

test("LA2: --font-sans changes no primitive; it reaches the written picture's font", () => {
  const a = drawWith("look-one-series", {}), b = drawWith("look-one-series", { "--font-sans": "Inter" });
  assert.equal(canonical(a), canonical(b));
  assert.ok(R.writeString(b, "standalone", { base: "light", tokens: { "--font-sans": "Inter" } }).includes('font-family="Inter"'));
  assert.ok(R.writeString(a, "standalone", "light").includes('font-family="Noto Sans"'));
});

// ---------------------------------------------------------------- LA3: the layout holds across the ranges

const LENGTHS = Object.entries(R.theme.TOKENS).filter(([, s]) => s[0] === "length");

for (const [end, at] of [["low", 3], ["high", 4]]) {
  test(`LA3: every length token at the ${end} end of its range, every chart fixture meets I1, I2, I5 at every frame`, () => {
    const tokens = Object.fromEntries(LENGTHS.map(([n, s]) => [n, s[at]]));
    assert.ok(LENGTHS.length >= 10, "length tokens: " + LENGTHS.length);
    const bad = [], levels = new Set();
    let pictures = 0, texts = 0;
    for (const f of FIXTURES) {
      for (const w of WIDTHS) {
        // 900: at the high end the level numbers rise with the text (10-chrome.js level), so the full level needs it.
        for (const h of [...HEIGHTS, 900]) {
          const r = R.render(inputOf(f.fixture, w, h, { theme: { base: "light", tokens } }));
          const v = violations(r.primitives, w, h);
          pictures++;
          texts += v.texts;
          levels.add(r.level);
          for (const x of v.violations) if (x.inv !== "I4") bad.push(`${x.inv} ${f.name} ${w}x${h}: ${x.what}`);
        }
      }
    }
    assert.ok(pictures >= 43 * 35 * 6 && texts > 10000, `pictures ${pictures}, texts ${texts}`);
    assert.deepEqual([...levels].sort(), ["compact", "full", "minimal"], "every level is met");
    assert.deepEqual(bad.slice(0, 10), [], bad.length + " violations");
  });
}

test("LA3 between the ends: each text size low or high on its own (the eight corners and the middle), I1, I2, I5 hold", () => {
  const sizes = ["--chart-text", "--chart-text-small", "--chart-title"], corners = [];
  for (let c = 0; c < 8; c++) corners.push(Object.fromEntries(sizes.map((t, i) => [t, R.theme.TOKENS[t][(c >> i) & 1 ? 4 : 3]])));
  corners.push({ "--chart-text": 20, "--chart-text-small": 20, "--chart-title": 28 });
  const bad = [];
  let pictures = 0;
  for (const tokens of corners) {
    for (const f of FIXTURES) {
      for (let w = 240; w <= 1600; w += 120) {
        for (const h of [100, 200, 300, 480, 700]) {
          const v = violations(R.render(inputOf(f.fixture, w, h, { theme: { base: "light", tokens } })).primitives, w, h);
          pictures++;
          for (const x of v.violations) if (x.inv !== "I4") bad.push(`${JSON.stringify(tokens)} ${x.inv} ${f.name} ${w}x${h}: ${x.what}`);
        }
      }
    }
  }
  assert.ok(pictures >= 9 * 43 * 60, "pictures " + pictures);
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " violations");
});

test("the level numbers hold at the default sizes and rise with a larger text, never fall with a smaller one", () => {
  const lvl = (w, h, tokens) => R.render(inputOf(fx("look-one-series"), w, h, { theme: { base: "light", tokens } })).level;
  assert.equal(lvl(360, 240, {}), "full");
  assert.equal(lvl(359, 240, {}), "compact");
  assert.equal(lvl(200, 140, {}), "compact");
  assert.equal(lvl(200, 139, {}), "minimal");
  assert.equal(lvl(80, 24, {}), "minimal");
  assert.equal(lvl(79, 24, {}), null);
  assert.equal(lvl(360, 240, { "--chart-text": 8, "--chart-text-small": 8, "--chart-title": 8 }), "full");
  assert.equal(lvl(359, 240, { "--chart-text": 8, "--chart-text-small": 8, "--chart-title": 8 }), "compact", "smaller text never lowers the numbers");
  assert.equal(lvl(720, 480, { "--chart-text": 24 }), "full", "twice the text: twice the numbers");
  assert.equal(lvl(719, 480, { "--chart-text": 24 }), "compact");
  assert.equal(lvl(420, 280, { "--chart-title": 28 }), "compact", "the title's ratio counts: 28/14 = 2, full from 720 x 480");
  assert.equal(lvl(80, 24, { "--chart-text": 32 }), "minimal", "the minimal level draws no text and keeps its numbers");
});

// ---------------------------------------------------------------- LA7: options and levels

test("LA7: bar at the minimal level draws marks only", () => {
  const r = R.render(inputOf(fx("look-hidden-series"), 120, 40));
  assert.equal(r.level, "minimal");
  const roles = new Set(r.primitives.slice(1).map((p) => p.role));
  assert.deepEqual([...roles], ["mark"], "after the canvas: marks only");
  assert.equal(r.primitives.filter((p) => p.k === "text").length, 0);
});

test("LA7: bar under legend: null draws no legend and no series name", () => {
  const r = R.render(inputOf(fx("look-legend-null"), 960, 540));
  assert.equal(r.primitives.filter((p) => p.role === "legend").length, 0);
  const names = r.summary.series.map((s) => s.title);
  assert.equal(r.primitives.filter((p) => p.k === "text" && names.includes(p.text)).length, 0);
});

test("LA7: bar at the full level with labels: none draws no value label; the same data without the member draws them", () => {
  const off = R.render(inputOf(fx("look-labels-none"), 960, 540));
  assert.equal(off.level, "full");
  assert.equal(off.primitives.filter((p) => p.k === "text" && p.role === "guide").length, 0);
  const control = structuredClone(fx("look-labels-none"));
  delete control.document.config.mark.labels;
  const on = R.render(inputOf(control, 960, 540));
  assert.ok(on.primitives.filter((p) => p.k === "text" && p.role === "guide").length > 0, "the control's labels fit and are drawn");
});

// ---------------------------------------------------------------- LA8 and LA11: contrast and the ink on a fill

const linear = (c) => {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
};
const lum = ([r, g, b]) => 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
const ratio = (a, b) => {
  const x = lum(a), y = lum(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
};

test("D6a: the engine's sRGB table is the WCAG curve at each of the 256 channel values", () => {
  const table = [...R.srgbLinear];
  assert.equal(table.length, 256);
  table.forEach((v, c) => assert.ok(Math.abs(v - linear(c)) < 1e-15, "channel " + c + ": " + v + " vs " + linear(c)));
});

test("LA8: ink, ink-2 and muted on the canvas, tip-ink and muted on the tip surface: at least 4.5 : 1, both themes", () => {
  let lowest = Infinity;
  for (const theme of ["light", "dark"]) {
    const d = plain(R.theme.defaults(theme));
    for (const [ink, on] of [["--chart-ink", "--chart-canvas"], ["--chart-ink-2", "--chart-canvas"], ["--chart-muted", "--chart-canvas"], ["--chart-tip-ink", "--chart-tip-surface"], ["--chart-muted", "--chart-tip-surface"]]) {
      const c = ratio(d[ink], d[on]);
      assert.ok(c >= 4.5, `${theme}: ${ink} on ${on} is ${c.toFixed(2)} : 1`);
      lowest = Math.min(lowest, c);
    }
  }
  assert.equal(lowest.toFixed(2), "4.76", "the contract's lowest: muted on the light canvas");
});

/** The contract's §4.6 table: the ink each default fill carries ("L" light, "D" dark), slot by slot. */
const INK = {
  light: { series: "LDDDLDLD", seq: "DDDDDLL" },
  dark: { series: "DDDDDDDD", seq: "LLLLLDD" },
};

test("LA11: the ink on each of the sixteen series colours and fourteen ramp steps is §4.6's", () => {
  let n = 0;
  for (const theme of ["light", "dark"]) {
    const d = plain(R.theme.defaults(theme)), light = d["--chart-on-fill-light"], dark = d["--chart-on-fill-dark"];
    for (const [family, prefix, count] of [["series", "--chart-series-", 8], ["seq", "--chart-seq-", 7]]) {
      for (let i = 1; i <= count; i++) {
        const fill = d[prefix + i], want = INK[theme][family][i - 1] === "L" ? "--chart-on-fill-light" : "--chart-on-fill-dark";
        assert.equal(R.theme.onFill(fill, light, dark), want, `${theme} ${prefix}${i}`);
        const c = ratio(fill, want === "--chart-on-fill-light" ? light : dark);
        assert.ok(c >= (family === "series" ? 4.5 : 4.29), `${theme} ${prefix}${i}: ${c.toFixed(2)} : 1`);
        assert.equal(R.theme.onFill(fill, dark, light), want === "--chart-on-fill-light" ? "--chart-on-fill-dark" : "--chart-on-fill-light", "the inks swapped: the choice follows the colour");
        n++;
      }
    }
  }
  assert.equal(n, 30);
});

test("LA11: grey 128 takes the dark ink; equal contrasts take the light one; a colour with alpha is refused", () => {
  const d = plain(R.theme.defaults("light"));
  assert.equal(R.theme.onFill([128, 128, 128], d["--chart-on-fill-light"], d["--chart-on-fill-dark"]), "--chart-on-fill-dark");
  assert.equal(R.theme.onFill([128, 128, 128], [0, 0, 0], [0, 0, 0]), "--chart-on-fill-light", "equal: the light one");
  assert.throws(() => R.theme.onFill([0, 0, 0, 0.5], [255, 255, 255], [0, 0, 0]), /fill is not an opaque colour/);
  assert.throws(() => R.theme.onFill([0, 0, 0], [255, 255, 255, 0], [0, 0, 0]), /--chart-on-fill-light is not an opaque colour/);
});
