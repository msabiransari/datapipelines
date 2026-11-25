// #985 round 2, the lander's refusal 985-r1. R1: the advertised compact and full frames fit (I1, I2, I5 at the levels
// the engine returns, the lowest value tick's number kept), at the level boundaries and their neighbours, at the default
// text sizes and at larger valid ones with their scaled boundaries. R2: tick text never loses the tick's value. The
// oracle of R2 reads the labels with THIS file's own decimal reader, never R.text (the formatter under test).
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf, canonical } from "./engine.mjs";
import { violations, markBox } from "./geometry.mjs";

const R = engine();
const FIXTURES = chartFixtures();
// Bar's fixtures by name: a name is unique within its type's directory only (line/ and area/ have their own `hostile`).
const fx = (name) => FIXTURES.find((f) => f.wire === "bar" && f.name === name).fixture;
const MINUS = "−";

/** The value axis's tick labels of a vertical bar, bottom to top: [{text, y}] (anchor end, left of the plot). */
function valueTicks(r) {
  const plot = r.index.plot;
  return r.primitives
    .filter((p) => p.k === "text" && p.role === "tick" && p.anchor === "end" && !p.rotate && p.x < plot.x)
    .map((p) => ({ text: p.shown ?? p.text, y: p.y }))
    .sort((a, b) => b.y - a.y);
}

// ---------------------------------------------------------------- R1

for (const name of ["look-long-labels", "parity-bar-stacked"]) {
  test(`R1: ${name} at 200 x 140 is compact and meets I1, I2, I5; the lowest tick's number is drawn`, () => {
    const r = R.render(inputOf(fx(name), 200, 140));
    assert.equal(r.level, "compact");
    assert.deepEqual([...violations(r.primitives, 200, 140).violations].map((v) => v.inv + " " + v.what), []);
    const ticks = valueTicks(r);
    assert.equal(ticks[0].text, "0", "the lowest value tick keeps its number (the corner rule)");
    assert.ok(ticks.length >= 2, "the plot keeps the room of two value tick labels: " + JSON.stringify(ticks));
  });
}

test("R1: the crowded eight-series chart at 360 x 240 is full and meets I1, I2, I5; the legend folds into +n", () => {
  const r = R.render(inputOf(fx("fit-crowded-eight-series"), 360, 240));
  assert.equal(r.level, "full");
  assert.deepEqual([...violations(r.primitives, 360, 240).violations].map((v) => v.inv + " " + v.what), []);
  const ticks = valueTicks(r);
  assert.equal(ticks[0].text, "0");
  assert.ok(ticks.length >= 2, JSON.stringify(ticks));
  const more = r.primitives.filter((p) => p.k === "text" && p.role === "legend" && /^\+[0-9]+$/.test(p.text));
  assert.equal(more.length, 1, "the rows that do not fit are folded into one +n");
  const entries = r.primitives.filter((p) => p.k === "group" && p.role === "legend").length;
  assert.equal(entries + Number(more[0].text.slice(1)), 8, "every series is an entry or counted in +n");
  assert.ok(r.primitives.some((p) => p.k === "text" && p.role === "axis" && p.text === "Category"), "the band title is drawn, inside the frame");
});

test("R1: a folded entry's series is drawn even when its id is on the hidden list (look contract §4.2)", () => {
  const f = fx("fit-crowded-eight-series"), field = f.document.config.encoding.color.field;
  const last = f.columns[field][7];
  const r = R.render(inputOf(f, 360, 240, { hidden: ["string:" + last] }));
  const s = r.summary.series.find((x) => x.title === last);
  assert.equal(s.hidden, false, "no entry drawn for it, so the list does not hide it");
  assert.ok(r.primitives.some((p) => p.role === "mark" && p.series === s.id));
});

/** The text-size themes of the boundary sweep: the defaults, and larger valid sizes (each raises the level numbers). */
const SIZES = [
  {},
  { "--chart-text": 16 },
  { "--chart-text": 24 },
  { "--chart-title": 28 },
  { "--chart-text-small": 20 },
  { "--chart-text": 20, "--chart-text-small": 20, "--chart-title": 28 },
  { "--chart-text": 32, "--chart-text-small": 32, "--chart-title": 32 },
];
const NEIGHBOURS = [0, 1, 20, 40, 80];

/** The level numbers scaled the way 10-chrome.js `level` scales them: by the largest ratio of a text size to its default. */
function scale(tokens) {
  const T = R.theme.TOKENS;
  return Math.max(1, ...["--chart-text", "--chart-text-small", "--chart-title"].map((t) => (tokens[t] ?? T[t][1]) / T[t][1]));
}

test("R1: every chart fixture at the compact and full boundaries and their neighbours, each text size, meets I1, I2, I5", () => {
  const look = R.tables.look, bad = [], levels = new Set();
  let pictures = 0, texts = 0;
  for (const tokens of SIZES) {
    const k = scale(tokens), ws = new Set(), hs = new Set();
    for (const [bw, bh] of [look("levelCompact"), look("levelFull")]) {
      for (const d of NEIGHBOURS) {
        ws.add(Math.ceil(bw * k) + d);
        hs.add(Math.ceil(bh * k) + d);
      }
    }
    for (const f of FIXTURES) {
      for (const w of ws) {
        for (const h of hs) {
          const r = R.render(inputOf(f.fixture, w, h, { theme: { base: "light", tokens } }));
          const v = violations(r.primitives, w, h);
          pictures++;
          texts += v.texts;
          levels.add(r.level);
          for (const x of v.violations) bad.push(`${JSON.stringify(tokens)} ${f.name} ${w}x${h} ${r.level} ${x.inv}: ${x.what}`);
        }
      }
    }
  }
  assert.ok(pictures >= SIZES.length * 43 * 100 && texts > 100000, `pictures ${pictures}, texts ${texts}`);
  assert.deepEqual([...levels].sort(), ["compact", "full"], "the boundaries meet both text levels");
  assert.deepEqual(bad.slice(0, 10), [], bad.length + " violations");
});

test("R1: the level boundary itself, each text size: the returned level is the advertised one", () => {
  const look = R.tables.look;
  for (const tokens of SIZES) {
    const k = scale(tokens), [cw, ch] = look("levelCompact"), [fw, fh] = look("levelFull");
    const at = (w, h) => R.render(inputOf(fx("look-eight-series"), w, h, { theme: { base: "light", tokens } })).level;
    assert.equal(at(Math.ceil(cw * k), Math.ceil(ch * k)), "compact", JSON.stringify(tokens));
    assert.equal(at(Math.ceil(fw * k), Math.ceil(fh * k)), "full", JSON.stringify(tokens));
  }
});

// ---------------------------------------------------------------- R2

/** This test's own reader: a label's exact value as [BigInt numerator, power of ten], or null when it is no number. It
 * reads the e form an axis writes from 10^15 up and below 10^-5 (#1059: 2.5e18, 1e−12), never with a unit letter. */
function exact(label) {
  let t = label.replace(/,/g, "").replaceAll(MINUS, "-"), shift = 0;
  if (t.endsWith("%")) {
    t = t.slice(0, -1);
    shift = -2;
  } else {
    const units = { K: 3, M: 6, B: 9, T: 12 };
    if (units[t.slice(-1)] !== undefined) {
      shift = units[t.slice(-1)];
      t = t.slice(0, -1);
    }
  }
  const m = /^(-?)([0-9]+)(?:\.([0-9]+))?(?:e(-?[0-9]+))?$/.exec(t);
  if (!m || (m[4] !== undefined && shift > 0)) return null;
  const frac = m[3] || "";
  return { n: BigInt(m[1] + m[2] + frac), e: shift + (m[4] === undefined ? 0 : Number(m[4])) - frac.length };
}

/** a − b of two exact values, as an exact value. */
function minus(a, b) {
  const e = Math.min(a.e, b.e);
  return { n: a.n * 10n ** BigInt(a.e - e) - b.n * 10n ** BigInt(b.e - e), e };
}

const same = (a, b) => minus(a, b).n === 0n;

/** Labels of nice ticks: each a number, all distinct, evenly spaced (the step of the first two), from the lowest up. */
function assertNiceLabels(labels, where) {
  const xs = labels.map(exact);
  assert.ok(xs.every((x) => x !== null), where + ": every tick label is a number: " + labels.join(" | "));
  assert.ok(xs.length >= 2, where + ": " + labels.join(" | "));
  const step = minus(xs[1], xs[0]);
  assert.ok(step.n > 0n, where + ": the labels rise: " + labels.join(" | "));
  for (let i = 2; i < xs.length; i++) assert.ok(same(minus(xs[i], xs[i - 1]), step), where + ": evenly spaced: " + labels.join(" | "));
  return { xs, step };
}

test("R2: small-value ticks keep their values (the refusal's case, 0.00001 and 0.0000025 steps, a percent axis)", () => {
  const t = (xs, f) => [...R.text.ticks(xs, f)];
  assert.deepEqual(t([0, 0.0001, 0.0002]), ["0", "0.0001", "0.0002"]);
  assert.deepEqual(t([0, 0.00001, 0.00002, 0.00003]), ["0", "0.00001", "0.00002", "0.00003"]);
  // Below 10^-5 the axis writes the e form (#1059, owner 2026-11-21).
  assert.deepEqual(t([0, 0.0000025, 0.000005, 0.0000075, 0.00001]), ["0", "2.5e" + MINUS + "6", "5e" + MINUS + "6", "7.5e" + MINUS + "6", "1e" + MINUS + "5"]);
  assert.deepEqual(t([-0.0002, -0.0001, 0]), [MINUS + "0.0002", MINUS + "0.0001", "0"], "the true minus sign stays (§4.7)");
  assert.deepEqual(t([0, 0.0001, 0.0002], "percent"), ["0%", "0.01%", "0.02%"]);
  assert.deepEqual(t([0, 0.000025, 0.00005], "percent"), ["0%", "0.0025%", "0.005%"]);
  // The fewest digits still win where they suffice, and the compact form carries up to two fraction digits (§4.7, #1089).
  assert.deepEqual(t([0, 0.25, 0.5]), ["0", "0.25", "0.5"]);
  assert.deepEqual(t([0, 2500, 5000]), ["0", "2.5K", "5K"]);
  assert.deepEqual(t([0, 1250, 2500]), ["0", "1.25K", "2.5K"]);
});

test("R2: nice ticks of every decade from 1e-12 to 1e15, plain and percent, read back as their own values", () => {
  let checked = 0;
  for (let d = -12; d <= 15; d++) {
    for (const lead of [1, 2, 2.5, 5]) {
      const step = Number(lead + "e" + d);
      for (const intervals of [2, 5, 10]) {
        const n = R.scales.nice(0, step * intervals, intervals);
        for (const format of [null, "percent"]) {
          const labels = [...R.text.ticks(n.ticks, format)];
          const { xs } = assertNiceLabels(labels, `step ${step} ${format}`);
          // Each label states its tick: the tick's own shortest digits, read by this test, within a millionth of a step.
          n.ticks.forEach((tick, i) => {
            const want = exact(BigIntFree(tick));
            const diff = minus(xs[i], want);
            const tol = exact(BigIntFree(step * 1e-6));
            const abs = diff.n < 0n ? { n: -diff.n, e: diff.e } : diff;
            assert.ok(minus(tol, abs).n >= 0n, `${labels[i]} states ${tick}`);
            checked++;
          });
        }
      }
    }
  }
  assert.ok(checked > 3000, "ticks checked: " + checked);
});

/** A number's decimal text without an exponent (the test's own expansion of String(v)'s digits). */
function BigIntFree(v) {
  const s = String(v), m = /^(-?)([0-9])(?:\.([0-9]+))?e([+-][0-9]+)$/.exec(s);
  if (!m) return s;
  const digits = m[2] + (m[3] || ""), p = 1 + Number(m[4]);
  if (p <= 0) return m[1] + "0." + "0".repeat(-p) + digits;
  if (p >= digits.length) return m[1] + digits + "0".repeat(p - digits.length);
  return m[1] + digits.slice(0, p) + "." + digits.slice(p);
}

test("R2: a tick search that cannot keep a value refuses instead of returning the last attempt", () => {
  assert.throws(() => R.text.ticks([0, NaN]), /no fraction digits write every tick/);
});

for (const [name, w, h] of [["ticks-small-decimal", 960, 540], ["ticks-small-decimal", 300, 180], ["ticks-small-percent", 960, 540], ["ticks-small-percent", 480, 360]]) {
  test(`R2: ${name} at ${w} x ${h}: the value axis states distinct, evenly spaced values, linear in position, covering the data`, () => {
    const f = fx(name), r = R.render(inputOf(f, w, h));
    assert.equal(r.state, "rendered");
    const ticks = valueTicks(r);
    const { xs, step } = assertNiceLabels(ticks.map((t) => t.text), name);
    assert.equal(xs[0].n, 0n, "the axis starts at 0");
    // Position oracle: equal value steps are equal pixel steps (coordinates are rounded to 0.01 px).
    const px = ticks[1].y - ticks[0].y;
    for (let i = 2; i < ticks.length; i++) assert.ok(Math.abs(ticks[i].y - ticks[i - 1].y - px) < 0.03, "linear in position: " + JSON.stringify(ticks));
    // The highest label is at least the largest value delivered (read from the fixture, not from the engine); a
    // percent label is read as its fraction, so 0.0035% is 0.000035.
    const most = Math.max(...f.columns.v);
    assert.ok(minus(xs[xs.length - 1], exact(BigIntFree(most))).n >= 0n, "the axis covers " + most + ": " + ticks[ticks.length - 1].text);
    assert.ok(step.n > 0n);
  });
}

test("R2: the small-value bar's value labels state the delivered values at the document's format", () => {
  const r = R.render(inputOf(fx("ticks-small-decimal"), 960, 540));
  const labels = r.primitives.filter((p) => p.k === "text" && p.role === "guide").map((p) => p.text);
  assert.deepEqual([...labels].sort(), ["0.000100", "0.000200"]);
});

// ---------------------------------------------------------------- #1130: the grouped-bar gap

/** The mark primitives that paint a bar: a rect, or the rounded path (§2 "corners"), both role "mark". */
function barMarks(r) {
  return r.primitives.filter((p) => p.role === "mark" && (p.k === "rect" || p.k === "path"));
}

/** A bar's category-axis extent (its painted width) and its category-axis start, for either orientation. */
const catExtent = (b, vertical) => (vertical ? b[2] - b[0] : b[3] - b[1]);
const catStart = (b, vertical) => (vertical ? b[0] : b[1]);

/** A horizontal grouped bar from a vertical eight-series fixture: the same data with x and y swapped (brief 1130 §A.1). */
function horizontalOf(f) {
  const d = JSON.parse(JSON.stringify(f.document));
  const x = d.config.encoding.x, y = d.config.encoding.y;
  d.config.encoding.x = y;
  d.config.encoding.y = x;
  return { document: d, columns: f.columns, types: f.types, hidden: f.hidden || [] };
}

/**
 * The independent grouped-allocation oracle (brief 1130 §A.3): the widths and offsets recomputed from the rendered plot
 * box and the documented rule (§2 "bars": a group fills 74% of its band), never from the engine's barW/off. A nondefault
 * gap whose total separation G = (k−1)g reaches the group width W scales the separation to gW/(W+G); otherwise the
 * requested gap stands. Width = max(0, (W−(k−1)e)/k); bar j of a group starts at the group start + j·(width+e).
 */
function groupedGeometry(f, r, gap) {
  const vertical = f.document.config.encoding.y.type === "quantitative";
  const band = vertical ? f.document.config.encoding.x : f.document.config.encoding.y;
  const color = f.document.config.encoding.color;
  const cats = new Set(f.columns[band.field].map((v) => typeof v + ":" + String(v)));
  const sers = new Set(color ? f.columns[color.field].map((v) => typeof v + ":" + String(v)) : [""]);
  const n = cats.size, k = Math.max(1, sers.size);
  const length = vertical ? r.index.plot.w : r.index.plot.h;
  const step = length / Math.max(1, n);
  const W = step * 0.74;
  const G = (k - 1) * gap;
  const e = gap !== 2 && G >= W ? (W > 0 ? gap * W / (W + G) : 0) : gap;
  const w = Math.max(0, (W - (k - 1) * e) / k);
  return { vertical, n, k, W, e, w };
}

/** The engine with the grouped-gap compression removed: "requested-gap placement alone" (brief 1130 §Gates). It
 * reproduces the base's overfull-group behaviour, so the fix must differ from it under an overfull group and equal it
 * wherever the requested layout fits. */
const requestedGapOnly = engine((srcs) => {
  const anchor = "sep = groupW > 0 ? gap * groupW / (groupW + gap * (k - 1)) : 0;";
  if (!srcs["types/bar.js"].includes(anchor)) throw new Error("grouped-gap compression anchor missing from bar.js");
  srcs["types/bar.js"] = srcs["types/bar.js"].replace(anchor, "sep = gap;");
});

test("gap 8 at 80 x 24: 24 painted bars of the compressed width, zero frame violations; requested-gap placement alone overflows", () => {
  for (const name of ["fit-crowded-eight-series", "look-eight-series"]) {
    const f = fx(name), tokens = { "--chart-gap": 8 };
    const r = R.render(inputOf(f, 80, 24, { theme: { base: "light", tokens } }));
    const boxes = barMarks(r).map(markBox);
    const g = groupedGeometry(f, r, 8);
    assert.equal(boxes.length, 24, name + ": 24 painted bars");
    assert.ok(g.w >= 0.48 && g.w <= 0.49, name + ": compressed width " + g.w + " is the measured 0.48–0.49");
    for (const b of boxes) assert.ok(Math.abs(catExtent(b, g.vertical) - g.w) <= 0.011, name + ": each bar is the compressed width");
    const xs = boxes.map((b) => catStart(b, g.vertical)).sort((a, b) => a - b);
    for (let j = 1; j < 24; j++) if (j % 8) assert.ok(Math.abs(xs[j] - xs[j - 1] - (g.w + g.e)) <= 0.011, name + ": starts are spaced width+separation");
    assert.deepEqual([...violations(r.primitives, 80, 24).violations].map((v) => v.inv + " " + v.what), []);
    const r0 = requestedGapOnly.render(inputOf(f, 80, 24, { theme: { base: "light", tokens } }));
    assert.ok(violations(r0.primitives, 80, 24).violations.length > 0, name + ": requested-gap placement alone violates I2");
  }
});

test("the grouped-gap matrix: custom gaps paint positive bars inside the frame; the eight dense default rows keep base geometry", () => {
  const frames = [[80, 24], [81, 25], [100, 31], [120, 40], [160, 24], [960, 540]];
  const preserved = new Set(["80x24", "81x25", "100x31", "160x24"]);
  for (const name of ["fit-crowded-eight-series", "look-eight-series"]) {
    const f = fx(name);
    for (const horiz of [false, true]) {
      const src = horiz ? horizontalOf(f) : f;
      const vertical = !horiz;
      for (const [w, h] of frames) {
        for (const gap of [0, 2, 4, 8]) {
          const tokens = gap === 2 ? {} : { "--chart-gap": gap };
          const r = R.render(inputOf(src, w, h, { theme: { base: "light", tokens } }));
          const where = name + (horiz ? " horizontal" : " vertical") + " " + w + "x" + h + " gap " + gap;
          if (horiz && gap === 2 && preserved.has(w + "x" + h)) {
            // The eight dense default rows keep the base's overflow (a subpixel allocation #1117 later refuses):
            // byte-identical to requested-gap placement, so this lane changed nothing about them.
            const r0 = requestedGapOnly.render(inputOf(src, w, h, { theme: { base: "light", tokens } }));
            assert.equal(canonical(r), canonical(r0), where + " is byte-equal to the base");
          } else {
            assert.deepEqual([...violations(r.primitives, w, h).violations].map((v) => v.inv + " " + v.what), [], where);
          }
          if (gap === 4 || gap === 8) for (const b of barMarks(r).map(markBox)) {
            assert.ok(catExtent(b, vertical) > 0, where + ": positive painted width");
          }
        }
      }
    }
  }
});

test("the grouped-gap compression is inert at the default gap: every bar picture is byte-identical to the base", () => {
  let compared = 0;
  for (const f of FIXTURES.filter((x) => x.wire === "bar")) {
    for (const [w, h] of [[80, 24], [200, 140], [360, 240], [960, 540]]) {
      const a = canonical(R.render(inputOf(f.fixture, w, h)));
      const b = canonical(requestedGapOnly.render(inputOf(f.fixture, w, h)));
      assert.equal(a, b, f.name + " at " + w + "x" + h);
      compared++;
    }
  }
  assert.ok(compared > 100, "default-gap pictures compared: " + compared);
});

test("a legend switch hides exactly the requested series at full level; the hidden list is ignored where no switch is drawn", () => {
  const ids = ["Series 1", "Series 2", "Series 3", "Series 4", "Series 5", "Series 6", "Series 7", "Series 8"].map((s) => "string:" + s);
  const visible = (r) => r.summary.series.filter((s) => !s.hidden).map((s) => s.id);
  for (const [w, h] of [[360, 240], [960, 540]]) {
    const rMid = R.render(inputOf(fx("look-eight-series"), w, h, { hidden: ["string:Series 4"] }));
    assert.deepEqual([...visible(rMid)], ids.filter((id) => id !== "string:Series 4"), w + "x" + h + ": the middle series is hidden");
    const rSeven = R.render(inputOf(fx("look-eight-series"), w, h, { hidden: ids.slice(0, 7) }));
    assert.deepEqual([...visible(rSeven)], ["string:Series 8"], w + "x" + h + ": seven of eight hidden");
  }
  // At the minimal level no legend entry is drawn, so the hidden list hides nothing (the rule of §4.3).
  const rMin = R.render(inputOf(fx("look-eight-series"), 80, 24, { hidden: ids.slice(0, 7) }));
  assert.equal(visible(rMin).length, 8, "minimal: the hidden list is ignored");
});

test("a genuine single-series chart at the minimal level keeps its full group width and ignores the hidden list", () => {
  const f = fx("look-one-series"), vertical = f.document.config.encoding.y.type === "quantitative";
  const r = R.render(inputOf(f, 80, 24, { hidden: ["measure"] }));
  const boxes = barMarks(r).map(markBox);
  assert.equal(boxes.length, 4, "one bar per category");
  const g = groupedGeometry(f, r, 2);
  assert.equal(g.k, 1, "a single series");
  for (const b of boxes) assert.ok(Math.abs(catExtent(b, vertical) - g.W) <= 0.011, "each bar takes the full group width");
  assert.deepEqual([...violations(r.primitives, 80, 24).violations].map((v) => v.inv + " " + v.what), []);
});
