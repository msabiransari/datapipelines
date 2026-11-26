// Funnel contract: expected widths and counts are derived from input rows, never renderer intermediates.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, MODULE } from "./engine.mjs";
import { violations, width, expectedFunnelMarks } from "./geometry.mjs";

const R = engine();
const fixtures = Object.fromEntries(chartFixtures().filter(f => f.wire === "funnel").map(f => [f.name, f.fixture]));
const plain = x => JSON.parse(JSON.stringify(x));
function input(values, labels = "value", w = 480, h = 320) {
  const f = structuredClone(fixtures.rising);
  delete f.document.config.title;
  f.columns = { s: values.map((_, i) => "s" + i), v: values };
  f.document.config.mark.labels = labels;
  return inputOf(f, w, h);
}
const bars = r => r.primitives.filter(p => p.k === "rect" && p.role === "mark");
const names = r => r.primitives.filter(p => p.k === "text" && p.role === "tick");
const shares = r => r.primitives.filter(p => p.k === "text" && p.fill === "--chart-muted").map(p => p.text);
const near = (a, b, why) => assert.ok(Math.abs(a - b) <= 0.025, `${why}: ${a} vs ${b}`);

test("the fixed semantic cap belongs to this type", () => {
  const c = R.tables.CAPS["funnel.marks"];
  assert.deepEqual([c.kind, c.limit, c.basis, c.unit], ["semantic", 12, "fixed", "stages"]);
  assert.match(c.note, /R19/); assert.match(c.note, /§4\.5/);
  assert.doesNotMatch(c.note, /bar|line|area|scatter|pie|combo|heatmap|histogram|box|waterfall|gauge|sparkline|radar|treemap/i);
  assert.equal(R.render(input(Array(12).fill(1))).summary.marks, 12);
  assert.deepEqual(plain(R.render(input(Array(13).fill(1))).summary.refusal), { code: "chart.too_many_marks", details: { marks: 13, limit: 12 } });
});

test("group, aggregate, omit null and non-finite values, retain zeros, refuse negatives", () => {
  const i = input([3, 4, null, 0, 5]); i.columns.s = ["A", "A", "B", "C", null];
  const r = R.render(i);
  assert.equal(r.summary.marks, 2);
  assert.deepEqual(plain(r.index.marks.map(m => [m.card.heading, m.card.rows[0].value])), [["A", "7"], ["C", "0"]]);
  assert.deepEqual(plain(r.summary.warnings), [{ code: "chart.rule.null-category" }]);
  i.columns.s[2] = null;
  assert.equal(R.render(i).summary.warnings.length, 1);
  assert.equal(R.render(input([1, -1, 2])).summary.refusal.code, "chart.rule.negative-part");
  const over = input([1e308, 1e308, 1]); over.columns.s = ["A", "A", "B"];
  assert.equal(R.render(over).summary.marks, 1);
  assert.deepEqual(plain(R.render(over).summary.warnings), [{ code: "chart.rule.non-finite" }]);
  assert.equal(R.render(input([null, null])).state, "no-data");
  for (const [sort, expected] of [["descending", ["s2", "s1", "s0"]], [["s1", "s0", "s2"], ["s1", "s0", "s2"]]]) {
    const q = input([10, 20, 30]); q.document.config.encoding.stage.sort = sort;
    assert.deepEqual(plain(R.render(q).index.marks.map(m => m.card.heading)), expected);
  }
  assert.equal(R.render(inputOf(fixtures.count, 480, 320)).index.marks[0].card.rows[0].value, "3");
  const q = input([9]); q.document.config.encoding.value.title = null;
  assert.equal(R.render(q).index.marks[0].card.rows[0].name, "");
  q.document.config.encoding.value.title = "Orders"; q.document.config.encoding.value.unit = "orders";
  assert.equal(R.render(q).index.marks[0].card.rows[0].name, "Orders (orders)");
  q.columns.v = ["9007199254740993"]; q.types.v = "BIGINTEGER";
  assert.match(R.render(q).index.marks[0].card.rows[0].value, /9,007,199,254,740,993/);
  q.columns.v = ["1.23456789"]; q.types.v = "BIGDECIMAL";
  assert.equal(R.render(q).index.marks[0].card.rows[0].value, "1.23 orders");
  for (const [vs, agg, count] of [[["-9007199254740993", "9007199254740992"], "sum", 0], [["9007199254740993", "-9007199254740992"], "sum", 1], [Array(2).fill("1" + "0".repeat(308)), "mean", 1]]) {
    const x = input(vs); x.columns.s = ["A", "A"]; x.types.v = "BIGDECIMAL"; x.document.config.encoding.value.aggregate = agg;
    assert.equal(expectedFunnelMarks(x, []), count); assert.equal(bars(R.render(x)).length, count);
  }
});

test("LA19 largest-stage ratios, least width, all zero, and a full span of four", () => {
  for (const full of [4, 100]) {
    const C = engine(s => { s["18-frames.js"] = s["18-frames.js"].replace('var plot =', 'var plot =');
      // Edit the none-frame hand after layout, with a precise type-local model of the narrow span.
      s["types/funnel.js"] = s["types/funnel.js"].replace('var plot = hand.plot', `hand.plot.w = ${full}; var plot = hand.plot`);
    });
    for (const values of [[10, 20], [0, 10], [10, 0, 5], [0, 0, 0], [1000, 1], [100, 7], [100, 6], [100, 5]]) {
      for (const label of ["value", "percent_of_first", "percent_of_previous"]) {
        const i = input(values, label, Math.max(80, full + 12), 24), r = C.render(i);
        const mx = Math.max(...values);
        bars(r).forEach((p, j) => near(p.w, mx === 0 ? Math.min(6, full) : Math.max(Math.min(6, full), full * values[j] / mx), "LA19 width"));
        assert.equal(violations(r.primitives, i.frame.w, i.frame.h).violations.length, 0);
      }
    }
  }
});

test("width stays proportional near the number range's end, with a BigInt oracle", () => {
  let seed = 1074, lists = 0, stages = 0;
  const next = () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0);
  const all = [["1" + "0".repeat(308), "5" + "0".repeat(307)], ["17" + "0".repeat(307), "1" + "0".repeat(308), "3" + "0".repeat(307)]];
  for (let j = 0; j < 400; j++) {
    const digits = j < 220 ? 304 + next() % 5 : 1 + next() % 308;
    all.push(Array.from({ length: 2 + next() % 5 }, () => String(1 + next() % 8) + String(next()).padStart(10, "0").slice(0, Math.min(10, digits - 1)) + "0".repeat(Math.max(0, digits - 11))));
  }
  for (const strings of all) {
    const values = strings.map(Number), r = R.render(input(values, "value", 80, 24));
    assert.equal(bars(r).length, strings.length);
    const big = strings.map(BigInt), mx = big.reduce((a, b) => a > b ? a : b);
    bars(r).forEach((p, j) => near(p.w, Math.max(6, 68 * Number(big[j] * 1000000000000n / mx) / 1e12), "large-number width"));
    lists++; stages += bars(r).length;
  }
  console.log(`funnel large widths: lists=${lists}, stages=${stages}, >=304-digit lists=222`);
});

test("LA15 one colour, centred rounded rectangles, short rows and minimal marks only", () => {
  let pictures = 0;
  for (let n = 1; n <= 12; n++) for (const h of [24, 39, 60, 100, 140, 200, 240, 320, 480]) {
    const i = input(Array.from({ length: n }, (_, j) => n - j), "percent_of_first", 480, h), r = R.render(i), bs = bars(r);
    assert.equal(bs.length, n);
    bs.forEach((p, j) => {
      assert.equal(p.fill, "--chart-series-1"); assert.ok(p.rx > 0); near(p.rx * 100, Math.round(p.rx * 100), "two decimal rx");
      near(p.x + p.w / 2, bs[0].x + bs[0].w / 2, "centre");
      assert.ok(p.h > 0); if (j) assert.ok(p.y >= bs[j - 1].y + bs[j - 1].h);
    });
    if (r.level === "minimal") assert.equal(r.primitives.filter(p => p.k === "text").length, 0);
    assert.equal(violations(r.primitives, 480, h).violations.length, 0);
    pictures++;
  }
  console.log(`funnel short-row pictures=${pictures}`);
});

test("whole shares, zero denominator, overflow and underflow are distinct in picture, card and warnings", () => {
  const cases = [
    [[1, 1e307], "1e309%", ["1e309%", "1e309%"], 0],
    [[1e-300, 1e300], null, [], 1],
    [[1e300, 1e-300], "<1%", ["<1%", "<1%"], 0],
    [[0, 5], null, [], 0],
    [[1e6, 4999], "<1%", ["0.5%", "0.5%"], 0],
    [[1e6, 5001], "1%", ["0.5%", "0.5%"], 0],
    [[10, 20], "200%", ["200%", "200%"], 0],
  ];
  for (const [values, text, card, warnings] of cases) for (const labels of ["percent_of_first", "percent_of_previous"]) {
    const r = R.render(input(values, labels));
    assert.deepEqual(plain(r.index.marks.at(-1).card.rows.slice(1).map(x => x.value)), card);
    assert.equal(r.summary.warnings.length, warnings);
    if (text !== null) assert.ok(shares(r).includes(text), text);
    else assert.ok(shares(r).length <= (labels === "percent_of_first" && values[0] > 0 ? 1 : 0));
    assert.ok(r.primitives.filter(p => p.k === "text").every(p => p.text !== ""));
  }
  const r = R.render(input([1e-300, 1e300, 1e300], "percent_of_first"));
  assert.deepEqual(plain(r.index.marks[2].card.rows.slice(1).map(({name,value}) => ({name,value}))), [{ name: "Of the step before", value: "100%" }]);
  const i = input([1e-300, 1e300, 1e308, 1e308]); i.columns.s = ["a", "b", "c", "c"];
  assert.equal(R.render(i).summary.warnings.length, 1);
  assert.equal(shares(R.render(input([10, 20], "value"))).length, 0);
});

const I = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const measure = R.measure.measurer(null).fn;
const model = r => I.modelOf(R, r, measure);
const key = (m, t, k) => I.keyTarget(m, t, k).target;
const target = i => ({ kind: "mark", id: "measure", datum: i });

test("whole-row lookup includes name, share, tiny bar and gap; keys follow rows and cut texts", () => {
  for (const n of [1, 2, 5, 12]) {
    const r = R.render(input(Array.from({ length: n }, (_, i) => 1000 - i), "value", 80, 24)), m = model(r);
    for (const k of ["ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight"]) assert.deepEqual(plain(key(m, null, k)), target(0));
    for (let j = 0; j < n; j++) {
      const e = r.index.marks[j]; assert.equal(e.row, j); assert.equal(e.emphasis, null);
      assert.equal(R.lookup.at(r.index, e.shape.x + 1, e.shape.y + e.shape.h / 2, 0), j);
      assert.equal(R.lookup.at(r.index, e.shape.x + e.shape.w - 1, e.shape.y + e.shape.h / 2, 0), j);
      for (const k of ["ArrowLeft", "Home", "End", "ArrowRight"]) assert.deepEqual(plain(key(m, target(j), k)), target(j));
      assert.deepEqual(plain(key(m, target(j), "ArrowDown")), target(Math.min(n - 1, j + 1)));
      assert.deepEqual(plain(key(m, target(j), "ArrowUp")), target(Math.max(0, j - 1)));
      const card = I.markCard(R, measure, I.markLines(m, j), null, { w: 80, h: 24 }, I.measures(R, "light"));
      assert.equal(card.lines.length, 1); assert.equal(card.lines[0].line.value, R.text.value(1000 - j));
      if (j === 0) console.log(`funnel minimal n=${n} card=${JSON.stringify([card.lines[0].label, card.lines[0].line.value])}`);
    }
    assert.equal(R.lookup.at(r.index, -1, -1, 0), -1);
    assert.ok(r.index.series.every(s => s.at.length === 0));
  }
  const r = R.render(inputOf(fixtures["long-names"], 360, 240)), m = model(r);
  assert.deepEqual(plain(key(m, target(2), "ArrowRight")), { kind: "cut", n: 0 });
  assert.deepEqual(plain(key(m, { kind: "cut", n: 0 }, "ArrowRight")), { kind: "cut", n: 1 });
  assert.deepEqual(plain(key(m, { kind: "cut", n: 1 }, "ArrowRight")), { kind: "cut", n: 1 });
  assert.deepEqual(plain(key(m, { kind: "cut", n: 0 }, "ArrowLeft")), target(0));
  const titleInput = input([10, 5]); titleInput.document.config.title = { text: "An exceptionally long chart title ".repeat(5) };
  const tm = model(R.render(titleInput)); assert.deepEqual(plain(key(tm, target(1), "ArrowRight")), { kind: "cut", n: 0 });
  const tiny = R.render(input([1000, 1]));
  for (const p of bars(tiny)) assert.equal(R.lookup.at(tiny.index, p.x + p.w / 2, p.y + p.h / 2, 0), p.datum);
  const a = tiny.index.marks[0].shape, b = tiny.index.marks[1].shape;
  assert.equal(R.lookup.at(tiny.index, a.x + 1, b.y, 0), 1, "later row wins its shared boundary");
});

test("LA17 full, three-line and kept-value cards follow the contract table at both budget edges", () => {
  const out = R.render(inputOf(fixtures.basic, 480, 320)), m = model(out), ms = I.measures(R, "light");
  const lines = I.markLines(m, 2);
  assert.deepEqual(plain(lines.map(l => l.kind === "heading" ? l.text : [l.name, l.value])), ["Activated", ["Sum of v", "3,100"], ["Of the first step", "25%"], ["Of the step before", "58.5%"]]);
  for (const h of [71, 320, 55, 70, 24, 39]) {
    const card = I.markCard(R, measure, lines, null, { w: 480, h }, ms);
    const text = card.lines.map(l => l.line.kind === "heading" || l.line.kind === "more" ? l.text : [l.label, l.line.value].filter(Boolean).join(" "));
    assert.deepEqual(plain(text), h >= 71 ? ["Activated", "Sum of v 3,100", "Of the first step 25%", "Of the step before 58.5%"] : h >= 55 ? ["Activated", "Sum of v 3,100", "+2 more"] : ["Sum of v 3,100"]);
    console.log(`funnel L8 height=${h} ${JSON.stringify(text)}`);
  }
  for (const f of Object.values(fixtures)) for (const [w, h] of [[480, 320], [240, 140], [80, 24]]) {
    const before = R.render(inputOf(f, w, h));
    assert.equal(JSON.stringify(R.render(inputOf(f, w, h, { hidden: ["measure"] }))), JSON.stringify(before));
    assert.equal(before.primitives.filter(p => p.role === "legend").length, 0);
  }
});

test("value placement follows the fit rule, with in, after and omitted outcomes", () => {
  let pictures = 0, between = 0, smallest = Infinity;
  const counts = { in: 0, after: 0, none: 0 };
  for (const fixture of ["basic", "steep", "exact", "value-unit", "rising", "hostile", "wide-share", "long-names"]) {
    for (const w of [200, 240, 280, 320, 360, 400, 480, 600, 800, 1600]) for (const h of [240, 320, 480]) {
      const r = R.render(inputOf(fixtures[fixture], w, h)), bs = bars(r), plot = r.index.plot;
      const ns = names(r), ss = r.primitives.filter(p => p.k === "text" && p.fill === "--chart-muted" && p.anchor === "end");
      ns.forEach(p => assert.ok(width(p.shown === null ? p.text : p.shown, p.size, p.weight) <= Math.min(144, plot.w / 2) + 0.02, "name column bound"));
      const left = ns.length ? ns[0].x + 6 : plot.x;
      const right = ss.length ? plot.x + plot.w - Math.max(...ss.map(p => width(p.text, p.size, p.weight))) - 6 : plot.x + plot.w;
      smallest = Math.min(smallest, right - left);
      bs.forEach((p, j) => {
        const value = r.index.marks[j].card.rows[0].value, vw = width(value, 11, 600), room = p.w - vw;
        const texts = r.primitives.filter(t => t.k === "text" && t.role === "guide" && t.fill !== "--chart-muted" && t.text === value && Math.abs(t.y - (p.y + p.h / 2 + (1.069 - 0.293) * 11 / 2)) < 0.03);
        const mode = p.h >= (1.069 + 0.293) * 11 && room >= 12 - 0.01 ? "in" : p.x + p.w + 2.75 + vw <= right + 0.02 ? "after" : "none";
        counts[mode]++; if (mode === "in" && room < 24) between++;
        assert.equal(texts.length, mode === "none" ? 0 : 1, fixture + " " + mode);
        if (mode === "in") assert.equal(texts[0].anchor, "middle");
        if (mode === "after") assert.equal(texts[0].anchor, "start");
      });
      pictures++;
    }
  }
  assert.ok(pictures >= 200); assert.ok(between > 0); assert.ok(Object.values(counts).every(n => n > 0));
  assert.ok(smallest >= 6);
  console.log(`funnel value placement pictures=${pictures} ${JSON.stringify(counts)} between12and24=${between} smallestFull=${smallest}`);
});

test("on-fill values meet 4.5 contrast in both themes; radius admission stays funnel-only", () => {
  const luminance = hex => {
    const rgb = hex.match(/[a-f\d]{2}/gi).map(x => parseInt(x, 16) / 255).map(x => x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4);
    return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
  };
  let checked = 0, radii = 0, capped = 0;
  // Same gated expression as CHECK_BODY; the real page's browser run remains the authority.
  const admit = (wire, v, w, h, radius) => Math.abs(v - radius) < 1e-6 || (wire === "funnel" && v < radius && (Math.abs(2 * v - w) < 0.05 || Math.abs(2 * v - h) < 0.05));
  assert.equal(admit("bar", 3, 6, 20, 4), false);
  assert.equal(admit("funnel", 3, 20, 20, 4), false);
  assert.equal(admit("funnel", 3, 6, 20, 4), true);
  for (const theme of ["light", "dark"]) for (const f of Object.values(fixtures)) {
    for (const [w, h] of [[480, 320], [240, 140], [80, 24], [360, 240], [200, 140]]) {
      const r = R.render(inputOf(f, w, h, { theme }));
      for (const p of bars(r)) {
        assert.ok(admit("funnel", p.rx, p.w, p.h, 4)); radii++; if (p.rx !== 4) capped++;
      }
      for (const p of r.primitives.filter(p => p.k === "text" && p.role === "guide" && p.anchor === "middle")) {
        const a = luminance(p.color), b = luminance(R.theme.resolve(theme).css("--chart-series-1"));
        assert.ok((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 4.5); checked++;
      }
    }
  }
  assert.ok(checked > 50 && capped > 50);
  console.log(`funnel contrast texts=${checked}; radius admission ${radii}/${radii}, capped=${capped}; non-funnel and wrong-half controls refused`);
});

test("I7 has rendered coverage at all levels and L8 budgets come from the layer's measures", () => {
  const counts = { full: 0, compact: 0, minimal: 0 };
  for (const f of Object.values(fixtures)) for (const [w, h] of [[480, 320], [240, 140], [80, 24]]) {
    const a = R.render(inputOf(f, w, h)), b = R.render(inputOf(f, w, h, { hidden: ["measure"] }));
    if (a.state !== "rendered") continue;
    assert.deepEqual(plain(b), plain(a)); assert.ok(a.summary.series.every(s => !s.hidden)); counts[a.level]++;
  }
  assert.ok(Object.values(counts).every(n => n >= 14));
  const ms = I.measures(R, "light"), r = R.render(inputOf(fixtures.basic, 480, 320));
  for (const k of [1, 3, 4]) for (const h of [Math.ceil(2 * ms.p + k * ms.h), Math.ceil(2 * ms.p + (k + 1) * ms.h) - 1]) {
    const c = I.markCard(R, measure, I.markLines(model(r), 2), null, { w: 480, h }, ms);
    assert.equal(c.k, k); assert.equal(c.lines.length, Math.min(4, k));
  }
  console.log(`funnel I7 RENDERED pictures by level ${JSON.stringify(counts)}`);
});

test("LA19 rendered columns leave the contract widths for each label option", () => {
  let checked = 0;
  for (const values of [[10, 20], [0, 10], [10, 0, 5], [0, 0, 0], [1e308, 5e307], [10, 5]]) {
    for (const labels of ["value", "percent_of_first", "percent_of_previous"]) {
      const r = R.render(input(values, labels)), plot = r.index.plot, ns = names(r);
      const ss = r.primitives.filter(p => p.k === "text" && p.fill === "--chart-muted" && p.anchor === "end");
      const left = ns.length ? ns[0].x + 6 : plot.x;
      const right = ss.length ? plot.x + plot.w - Math.max(...ss.map(p => width(p.text, 12, 400))) - 6 : plot.x + plot.w;
      const full = right - left, largest = Math.max(...values);
      bars(r).forEach((p, j) => {
        const ratio = largest === 0 ? 0 : values[j] / largest;
        near(p.w, Math.max(Math.min(6, full), full * ratio), "rendered LA19 width");
        near(p.x + p.w / 2, (left + right) / 2, "rendered LA19 centre"); checked++;
      });
    }
  }
  assert.equal(checked, 42); console.log(`funnel rendered LA19: 18 pictures, ${checked} widths and centres`);
});

test("T14 smallest-card walk has two texts unless the value title is null", () => {
  for (const [title, expected] of [[undefined, ["Su…", "900"]], [null, ["9"]]]) {
    const i = input([title === null ? 9 : 900], "value", 80, 24);
    if (title === null) i.document.config.encoding.value.title = null;
    const r = R.render(i), m = model(r);
    const c = I.markCard(R, measure, I.markLines(m, 0), null, i.frame, I.measures(R, "light"));
    const text = c.lines.flatMap(l => [l.label, l.line.value].filter(Boolean));
    assert.deepEqual(plain(text), expected);
    console.log(`funnel T14 title=${title === null ? "null" : "default"} ${JSON.stringify(text)}`);
  }
});
