// Histogram contract (#1071): independent row counts and handed scales, plus faulted copies of the template.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { join } from "node:path";
import { engine, chartFixtures, inputOf, MODULE } from "./engine.mjs";
import { plant } from "./forms.mjs";
import { expectedHistogram, WIDTHS, HEIGHTS } from "./geometry.mjs";

const FILE = "types/histogram.js", REGISTER = 'R.type("histogram", { channels: CHANNELS, model: model, marks: marks,';
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === "histogram").map(f => [f.name, f.fixture]));
const I = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const plain = v => JSON.parse(JSON.stringify(v));
const same = (a, b, msg) => assert.deepEqual(plain(a), plain(b), msg);
function make(from, to) {
  return engine(s => {
    if (from) plant(s, FILE, from, to);
    plant(s, FILE, REGISTER, 'R.probe = model; R.type("histogram", { channels: CHANNELS, model: model, marks: function(m, hand, h) { R.seen = { m: m, hand: hand }; return marks(m, hand, h); },');
  });
}
const E = make();
function draw(name, w = 960, h = 540, hidden = [], R = E) {
  R.seen = null;
  const f = typeof name === "string" ? FX[name] : name;
  const r = R.render(inputOf(f, w, h, { hidden }));
  return { r, ...R.seen };
}
function values(v, bins) {
  const f = structuredClone(FX["one-series"]);
  f.columns = { v }; f.types = { v: "DOUBLE" };
  if (bins !== undefined) f.document.config.mark.bins = bins;
  return f;
}
const sum = m => m.cells.reduce((a, row) => a + row.reduce((a, b) => a + b, 0), 0);
const segs = r => r.primitives.filter(p => p.role === "mark" && ["rect", "path"].includes(p.k));
const model = (f, R = E) => R.probe(f.document, f.columns, f.types);
const keyModel = r => I.modelOf(E, r, E.measure.measurer(null).fn);
const target = t => t.kind === "mark" ? t.id.replace("string:", "") + "/" + t.datum : t.kind + ":" + t.id;
const tickTexts = r => r.primitives.filter(p => p.k === "text" && p.role === "tick" && p.anchor === "end").map(p => p.text);

test("A2/G2: rows, exact columns, nulls and one warning per code, in series/x/finite order", () => {
  const f = structuredClone(FX["nulls-dropped"]);
  f.columns.v = [1, 2, null, "abc", 4, 5, null, "bad", 9];
  f.columns.s = ["a", "a", "a", "a", null, "a", "a", "a", "a"];
  const check = R => { const m = model(f, R); assert.equal(sum(m), 4); same(m.warnings.map(w => w.code), ["chart.rule.null-position", "chart.rule.non-finite", "chart.rule.null-category"]); };
  check(E);
  for (const R of [make('if (D.isNull(X[i])) { warn("chart.rule.null-position"); continue; }', 'if (D.isNull(X[i])) { warn("chart.rule.null-position"); X[i] = 0; }'), make('if (warned[code] === true) return;', '')]) assert.throws(() => check(R));
  assert.equal(sum(model(FX["exact-columns"])), 5);
  assert.equal(sum(model(values(["1", "2", "3", "x"]))), 3);
  assert.equal(draw("empty").r.state, "no-data");
  assert.equal(draw(values([null, null])).r.state, "no-data");
});

test("A2/G3: Sturges targets, nice edges, inclusive last edge, degenerate and extreme ranges", () => {
  for (const [n, bins] of [[1, 5], [17, 6], [100, 8], [1000, 11], [20000, 16]]) {
    const f = values(Array.from({ length: n }, (_, i) => i));
    same(model(f).edges, E.scales.nice(0, n - 1, bins).ticks);
    assert.ok(model(f).edges.length - 1 <= bins);
  }
  for (const [v, edges] of [[[5], [0, 1, 2, 3, 4, 5]], [[0], [0, 0.2, 0.4, 0.6, 0.8, 1]], [[-3], [-3, -2, -1, 0]]]) same(model(values(v)).edges, edges);
  const check = R => { const m = model(FX["edge-values"], R); same(m.cells[0], [1, 1, 1, 1, 1, 1, 1, 1, 1, 2]); assert.equal(sum(m), 11); };
  check(E);
  assert.throws(() => check(make('if (v >= edges[hi]) return hi - 1;', 'if (v >= edges[hi]) return 0;')));
  const sturges = make('return k;\n  }', 'return n;\n  }');
  assert.notDeepEqual(plain(model(values(Array.from({ length: 100 }, (_, i) => i)), sturges).edges), plain(model(values(Array.from({ length: 100 }, (_, i) => i))).edges));
  for (const [v, occupied] of [[[-1.7e308, 0, 1.7e308], 2], [[0, Number.MIN_VALUE], 1], [[-3.2, 7.9], 2], [[1e300, 1.1e300], 2]]) {
    const d = draw(values(v)); assert.equal(d.r.state, "rendered"); assert.equal(sum(d.m), v.length); assert.equal(d.m.markCount, occupied);
  }
});

test("A2/A5/G8/G13: visible extents, share denominator, count ticks and one-series recovery", () => {
  const all = draw("share"), hidden = draw("share", 960, 540, ["string:P"]);
  same(all.m.edges, hidden.m.edges); assert.equal(all.m.layout.whole, false);
  const on = Object.fromEntries(all.m.series.map(s => [s.id, true]));
  const no = Object.fromEntries(all.m.series.map(s => [s.id, false]));
  same(all.m.layout.extent(no), [0, 0]);
  const check = R => {
    const a = draw("share", 960, 540, ["string:P"], R);
    assert.ok(Math.abs(a.m.layout.extent({ "string:Q": true })[1] - Math.max(...a.m.cells[1]) / 60) < 1e-12);
  };
  check(E); assert.throws(() => check(make('if (visible[m.series[s].id] === true)', 'if (true)')));
  assert.equal(all.r.index.marks[0].card.rows.at(-1).value, "0.6%");
  assert.ok(all.m.layout.extent(on)[1] > 0);
  for (const [w, h] of [[480, 320], [240, 160], [80, 24]]) {
    const a = draw("one-series", w, h), b = draw("one-series", w, h, ["measure"]);
    assert.equal(a.r.summary.marks, 8);
    const switches = r => r.primitives.filter(p => p.k === "group" && p.role === "legend").length;
    assert.equal(switches(a.r), 0); assert.equal(b.r.summary.marks, w === 80 ? 8 : 0); assert.equal(switches(b.r), w === 80 ? 0 : 1);
  }
  const whole = R => {
    const a = draw("integers-small", 960, 540, [], R);
    assert.equal(a.m.layout.whole, true); assert.ok(tickTexts(a.r).every(t => /^[0-9,]+$/.test(t)));
  };
  whole(E); assert.throws(() => whole(make('whole: !share', 'whole: false')));
});

/** Derive each segment from row counts and the scales handed to the template, not from its primitives. */
function geometry(R, gap = 2) {
  R.theme.TOKENS["--chart-gap"][1] = gap;
  let n = 0, thin = 0;
  for (const [w, h] of [...WIDTHS.flatMap(w => HEIGHTS.map(h => [w, h])), [80, 24], [960, 540]]) {
    for (const name of ["bins-50", "many-series"]) {
      const { r, m, hand } = draw(name, w, h, [], R), ps = segs(r);
      for (let b = 0; b < m.edges.length - 1; b++) {
        const x = hand.x.scale(m.edges[b]), wide = hand.x.scale(m.edges[b + 1]) - x;
        const g = Math.min(gap, wide / 2), bw = E.scales.r2(wide - g);
        const occupied = m.series.map((_, s) => s).filter(s => m.cells[s][b] > 0); let acc = 0;
        for (const s of occupied) {
          const from = acc; acc += m.cells[s][b];
          const y = hand.value.scale(acc), length = Math.abs(hand.value.scale(from) - y), shift = from > 0 ? Math.min(gap, length) : 0;
          const p = ps.find(p => p.datum === b && p.series === m.series[s].id), entry = r.index.marks.find(e => e.datum === b && e.series === m.series[s].id);
          const box = p.k === "rect" ? [p.x, p.y, p.w, p.h] : [p.d[1], p.d[9], p.d[12] - p.d[1] + p.d[7], p.d[2] - p.d[9]];
          // The path is M x bottom L x y+r A r x+r y L right-r y A r right y+r L right bottom Z.
          const got = p.k === "path" ? [p.d[1], p.d[9], p.d[18] - p.d[1], p.d[2] - p.d[9]] : box;
          const want = [x + g / 2, y, bw, length - shift];
          got.forEach((v, i) => assert.ok(Math.abs(v - want[i]) < 0.025, `${name} ${w}x${h} ${s}/${b} dim ${i}: ${v} vs ${want[i]}`));
          assert.ok(got[2] > 0); assert.ok(got[0] >= hand.plot.x - 0.02 && got[0] + got[2] <= hand.plot.x + hand.plot.w + 0.02);
          assert.equal(p.k === "path", s === occupied.at(-1) && length - shift > 0);
          if (p.k === "path") assert.ok(Math.abs(p.d[7] - Math.min(4, bw * 0.5, length - shift)) < 0.011);
          const mi = r.index.marks.indexOf(entry), sh = entry.shape;
          assert.equal(R.lookup.at(r.index, sh.x + sh.w / 2, sh.y + sh.h / 2, 24), mi);
          if (from > 0 && length < 1.95 && w === 240 && h === 100 && name === "many-series") { thin++; assert.equal(got[3], 0); }
          n++;
        }
      }
    }
  }
  assert.ok(n > 15000); assert.equal(thin, 22); console.log(`A3 geometry gap=${gap}: ${n} segments; ${thin} thin at 240x100`);
}
test("A3/G4/G5: geometry, cap the gap, outer radius, shift and full hit boxes", () => {
  geometry(E); geometry(make(), 8);
  for (const [a, b] of [['Math.min(gap, wide / 2)', 'gap'], ['si === outer ? Math.min', 'true ? Math.min'], ['from > 0 ? Math.min(gap, length) : 0', '0']]) assert.throws(() => geometry(make(a, b), 8));
});

test("A4/G6/G14: full-bin hit shape, unique cells, card counts and edge texts", () => {
  const check = R => {
    const { r, m, hand } = draw("many-series", 960, 540, [], R), entries = r.index.marks;
    assert.equal(new Set(entries.map(e => JSON.stringify([e.series, e.datum]))).size, entries.length);
    let gaps = 0;
    for (const e of entries) {
      const counts = m.cells.map(c => c[e.datum]).filter(c => c > 0).reverse();
      same(e.card.rows.map(r => Number(r.value)), counts);
      const next = entries.find(n => n.series === e.series && n.datum === e.datum + 1);
      if (next) {
        const y = Math.max(e.shape.y, next.shape.y) + 0.1, bottom = Math.min(e.shape.y + e.shape.h, next.shape.y + next.shape.h);
        if (y < bottom) { assert.ok(R.lookup.at(r.index, hand.x.scale(m.edges[e.datum + 1]) - 0.01, y, 24) >= 0); gaps++; }
      }
      assert.equal(e.row, m.series.findIndex(s => s.id === e.series));
    }
    assert.ok(gaps >= 8);
    assert.equal(R.lookup.at(r.index, entries[0].shape.x + 1, r.index.plot.y - 1, 24), -1);
    return r;
  };
  check(E);
  assert.throws(() => check(make('x: r2(x0), y: sg.y, w: r2(wide), h: sg.hit', 'x: bx, y: sg.y, w: bw, h: sg.h')));
  const f = values([1e9, 1.2e9], 20); f.document.config.encoding.x.format = "compact";
  const heads = R => draw(f, 960, 540, [], R).r.index.marks.map(e => e.card.heading);
  assert.notDeepEqual(plain(heads(E)), plain(heads(make('m.edgeTexts[i], m.enc.x.unit', 'T.value(m.edges[i], m.enc.x.format), m.enc.x.unit'))));
});

test("A4/G7: keys by series, entry, all directions, hidden series and last-bin switch", () => {
  const check = R => {
    for (const [w, h] of [[80, 24], [960, 540]]) {
      const { r } = draw("many-series", w, h, [], R), m = keyModel(r); let t = null;
      const keys = ["ArrowRight", "ArrowDown", "ArrowDown", "ArrowUp", "ArrowRight", "ArrowLeft", "End", "Home"];
      const want = ["North/2", "South/2", "East/2", "South/2", "South/3", "South/2", "South/7", "South/1"];
      keys.forEach((key, n) => { t = I.keyTarget(m, t, key).target; assert.equal(target(t), want[n]); console.log(`${w}x${h} ${key} ${target(t)}: ${JSON.stringify(I.markLines(m, r.index.marks.findIndex(e => e.series === t.id && e.datum === t.datum)))}`); });
      for (const hidden of [[], ["string:North"]]) {
        const rr = draw("many-series", w, h, hidden, R).r, mm = keyModel(rr);
        assert.equal(target(I.keyTarget(mm, { kind: "mark", id: "string:South", datum: 7 }, "ArrowRight").target), w === 80 ? "South/7" : "entry:string:South");
      }
      const rr = draw("many-series", w, h, ["string:South"], R).r, mm = keyModel(rr);
      const first = I.keyTarget(mm, null, "ArrowRight").target;
      assert.equal(target(I.keyTarget(mm, first, "ArrowDown").target), "East/2");
    }
  };
  check(E);
  for (const [a, b] of [['row: sg.si, key: true', 'row: 0, key: true'], ['row: sg.si, key: true', 'row: b, key: true'], ['rank[a.series] - rank[b.series] || a.datum - b.datum', 'a.datum - b.datum || rank[a.series] - rank[b.series]']]) assert.throws(() => check(make(a, b)));
});

test("A2/G9: asked-bin refusal on the model, cap row used, 50 draws, row and series caps", () => {
  same(model(FX["bins-51"]).rule, { code: "chart.too_many_marks", details: { marks: 51, limit: 50 } });
  assert.equal(draw("bins-50").r.state, "rendered");
  assert.equal(draw("nine-series").r.summary.refusal.code, "chart.too_many_series");
  assert.equal(draw(values(Array(20001).fill(1))).r.summary.refusal.code, "chart.too_many_rows");
  assert.equal(draw(values(Array(20000).fill(1))).r.state, "rendered");
  const missing = make(); delete missing.tables.CAPS["histogram.marks"];
  assert.throws(() => model(FX["one-series"], missing), /no cap-table entry histogram.marks/);
  const bypass = make('if (asked > cap) return', 'if (false) return');
  assert.equal(draw("bins-51", 960, 540, [], bypass).r.state, "rendered");
  const inflated = make('markCount: occupied,', 'markCount: occupied + 100,');
  assert.equal(draw("one-series", 960, 540, [], inflated).r.summary.refusal.code, "chart.too_many_marks");
});

test("A6: fixture counts checked by independent row oracle, both themes", () => {
  for (const [name, fx] of Object.entries(FX)) for (const theme of ["light", "dark"]) {
    const r = E.render(inputOf(fx, 960, 540, { theme }));
    if (r.state === "rendered") assert.equal(r.summary.marks, expectedHistogram(fx, r.summary.series.filter(s => s.hidden).map(s => s.id)), name);
  }
});
