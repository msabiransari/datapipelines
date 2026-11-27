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
const reject = (fn, pattern) => assert.throws(fn, e => { console.log("plant rejected: " + e.message); return pattern ? pattern.test(e.message) : e.code === "ERR_ASSERTION"; });
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
  const check = R => { const m = model(structuredClone(f), R); assert.equal(sum(m), 4); same(m.warnings.map(w => w.code), ["chart.rule.null-position", "chart.rule.non-finite", "chart.rule.null-category"]); };
  check(E);
  for (const R of [make('if (D.isNull(X[i])) { warn("chart.rule.null-position"); continue; }', 'if (D.isNull(X[i])) { warn("chart.rule.null-position"); X[i] = 0; }'), make('if (warned[code] === true) return;', '')]) reject(() => check(R));
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
  reject(() => check(make('if (v >= edges[hi]) return hi - 1;', 'if (v >= edges[hi]) return 0;')));
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
  same(all.m.layout.extent(no), [0, 0]); const none = draw("share", 960, 540, all.m.series.map(s => s.id)).r; assert.equal(none.state, "rendered"); assert.equal(none.summary.marks, 0);
  const check = R => {
    const a = draw("share", 960, 540, ["string:P"], R);
    for (const e of a.r.index.marks) assert.equal(e.card.rows[0].value, E.text.value(a.m.cells[1][e.datum] / 60, "percent"));
    assert.ok(Math.abs(a.m.layout.extent({ "string:Q": true })[1] - Math.max(...a.m.cells[1]) / 60) < 1e-12);
  };
  check(E); reject(() => check(make('if (visible[m.series[s].id] === true)', 'if (true)')));
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
    assert.ok(tickTexts(a.r).every(t => /^[0-9,]+$/.test(t))); assert.equal(a.m.layout.whole, true);
  };
  whole(E); reject(() => whole(make('whole: !share', 'whole: false')));
});
/** Derive each segment from row counts and the scales handed to the template, not from its primitives. */
function geometry(R, gap = 2) {
  R.theme.TOKENS["--chart-gap"][1] = gap;
  let n = 0, thin = 0; const thinByFrame = {};
  for (const [w, h] of [...WIDTHS.flatMap(w => HEIGHTS.map(h => [w, h])), [80, 24], [480, 300], [960, 540]]) {
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
          assert.ok(got[2] > 0, "zero-width bar " + name + " " + w + "x" + h); got.forEach((v, i) => assert.ok(Math.abs(v - want[i]) < 0.025, `${name} ${w}x${h} ${s}/${b} dim ${i}: ${v} vs ${want[i]}`));
          assert.ok(got[0] >= hand.plot.x - 0.02 && got[0] + got[2] <= hand.plot.x + hand.plot.w + 0.02);
          assert.equal(p.k === "path", s === occupied.at(-1) && length - shift > 0);
          if (p.k === "path") assert.ok(Math.abs(p.d[7] - Math.min(4, bw * 0.5, length - shift)) < 0.011);
          const mi = r.index.marks.indexOf(entry), sh = entry.shape;
          assert.equal(R.lookup.at(r.index, sh.x + sh.w / 2, sh.y + sh.h / 2, 24), mi);
          if (from > 0 && length < 1.95 && w === 240 && h === 100 && name === "many-series") { thin++; assert.equal(got[3], 0); }
          if (from > 0 && length < 1.95 && name === "many-series") thinByFrame[`${w}x${h}`] = (thinByFrame[`${w}x${h}`] || 0) + 1;
          n++;
        }
      }
    }
  }
  assert.ok(n > 15000); assert.equal(thin, 22); assert.equal(thinByFrame["480x300"], 5); assert.equal(thinByFrame["960x540"] || 0, 0); console.log(`A3 geometry gap=${gap}: ${n} segments; ${thin} thin at 240x100`);
}
test("A3/G4/G5: geometry, cap the gap, outer radius, shift and full hit boxes", () => {
  geometry(E); geometry(make(), 8);
  for (const [a, b] of [['Math.min(gap, wide / 2)', 'gap'], ['si === outer ? Math.min', 'true ? Math.min'], ['from > 0 ? Math.min(gap, length) : 0', '0']]) reject(() => geometry(make(a, b), 8));
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
  const pristine = check(E), layer = keyModel(pristine);
  for (const e of pristine.index.marks) same(I.lookup(E, layer, { x: e.shape.x + e.shape.w / 2, y: e.shape.y + e.shape.h / 2 }).mark, { id: e.series, datum: e.datum });
  reject(() => check(make('x: r2(x0), y: sg.y, w: r2(wide), h: sg.hit', 'x: bx, y: sg.y, w: bw, h: sg.h')));
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
      let every = I.keyTarget(m, null, "ArrowRight").target; for (const name of ["South", "East", "West", "Central", "Coast", "Hills", "Delta"]) { every = I.keyTarget(m, every, "ArrowDown").target; assert.equal(target(every), name + "/2"); }
      for (const hidden of [[], ["string:North"]]) {
        const rr = draw("many-series", w, h, hidden, R).r, mm = keyModel(rr);
        assert.equal(target(I.keyTarget(mm, { kind: "mark", id: "string:South", datum: 7 }, "ArrowRight").target), w === 80 ? "South/7" : "entry:string:South");
      }
      const rr = draw("many-series", w, h, ["string:South"], R).r, mm = keyModel(rr);
      const first = I.keyTarget(mm, null, "ArrowRight").target;
      // D7 recovery rule; A.4 corrected by Maestro's round-2 ruling: hidden ids only apply with a drawn switch.
      assert.equal(target(I.keyTarget(mm, first, "ArrowDown").target), w === 80 ? "South/2" : "East/2");
    }
  };
  check(E);
  for (const [a, b] of [['row: sg.si, key: true', 'row: 0, key: true'], ['row: sg.si, key: true', 'row: b, key: true'], ['rank[a.series] - rank[b.series] || a.datum - b.datum', 'a.datum - b.datum || rank[a.series] - rank[b.series]']]) reject(() => check(make(a, b)));
});

test("A2/G9: asked-bin refusal on the model, cap row used, 50 draws, row and series caps", () => {
  same(model(FX["bins-51"]).rule, { code: "chart.too_many_marks", details: { marks: 51, limit: 50 } });
  assert.equal(draw("bins-50").r.state, "rendered");
  const f = structuredClone(FX["many-series"]); f.document.config.mark.bins = 50; f.columns.v = Array.from({ length: 400 }, (_, i) => i % 50 + 0.5); f.columns.s = Array.from({ length: 400 }, (_, i) => String(Math.floor(i / 50))); assert.equal(draw(f, 1600, 480).r.summary.marks, 400);
  assert.equal(draw("nine-series").r.summary.refusal.code, "chart.too_many_series");
  assert.equal(draw(values(Array(20001).fill(1))).r.summary.refusal.code, "chart.too_many_rows");
  assert.equal(draw(values(Array(20000).fill(1))).r.state, "rendered");
  const missing = make(); delete missing.tables.CAPS["histogram.marks"];
  reject(() => model(FX["one-series"], missing), /no cap-table entry histogram.marks/);
  const bypass = make('if (asked > cap) return', 'if (false) return');
  assert.equal(draw("bins-51", 960, 540, [], bypass).r.state, "rendered");
  const inflated = make('markCount: occupied,', 'markCount: occupied + 100,');
  same(draw(values([0, 1, 2, 3, 4, 5], 5), 960, 540, [], inflated).r.summary.refusal, { code: "chart.too_many_marks", details: { marks: 105, limit: 50 } });
});

test("A6: fixture counts checked by independent row oracle, both themes", () => {
  for (const [name, fx] of Object.entries(FX)) for (const theme of ["light", "dark"]) {
    const r = E.render(inputOf(fx, 960, 540, { theme }));
    if (r.state === "rendered") assert.equal(r.summary.marks, expectedHistogram(fx, r.summary.series.filter(s => s.hidden).map(s => s.id)), name);
  }
});

const descendants = el => el.childNodes.flatMap(c => [c, ...descendants(c)]);
function fakeBrowser() {
  const doc = { createElementNS: (_, tag) => element(tag) };
  function element(tag) {
    const attrs = new Map(), listeners = new Map();
    const el = { tagName: tag, localName: tag, nodeType: 1, ownerDocument: doc, parentNode: null, childNodes: [],
      setAttribute: (k, v) => attrs.set(k, String(v)), getAttribute: k => attrs.get(k) ?? null, removeAttribute: k => attrs.delete(k),
      appendChild(c) { c.parentNode = el; el.childNodes.push(c); return c; },
      removeChild(c) { el.childNodes.splice(el.childNodes.indexOf(c), 1); c.parentNode = null; return c; },
      get firstChild() { return el.childNodes[0] ?? null; },
      get nextSibling() { return el.parentNode?.childNodes[el.parentNode.childNodes.indexOf(el) + 1] ?? null; },
      set textContent(v) { el.text = String(v); el.childNodes = []; },
      get textContent() { return el.text ?? el.childNodes.map(c => c.textContent).join(""); },
      addEventListener: (t, fn) => listeners.set(t, fn), removeEventListener: t => listeners.delete(t),
      dispatch(t, event) { listeners.get(t)?.({ preventDefault() {}, ...event }); },
      getBoundingClientRect() { return { left: 0, top: 0, width: Number(attrs.get("width")), height: Number(attrs.get("height")) }; },
    };
    return el;
  }
  return { doc, host: element("div") };
}

test("A4/A5: fitted card budgets, full legend dimming and hidden-series geometry", () => {
  const { r, m, hand } = draw("many-series"), lm = keyModel(r), lines = I.markLines(lm, 0);
  same(lines.map(l => l.kind === "heading" ? l.text : `${l.name}=${l.value}`), ["from 40 ms to 60 ms", "Delta=5", "Hills=3", "Coast=4", "Central=2", "West=4", "East=1", "South=3", "North=5"]);
  const measures = I.measures(E, "light"), width = E.measure.measurer(null).fn;
  for (const [budget, want] of [[9, ["heading", "row", "row", "row", "row", "row", "row", "row", "row"]], [3, ["heading", "row", "more"]], [1, ["row"]]]) {
    const card = I.markCard(E, width, lines, null, { w: 960, h: 2 * measures.p + budget * measures.h }, measures);
    same(card.lines.map(l => l.line.kind), want);
    if (budget <= 3) { assert.equal(card.lines.find(l => l.line.kind === "row").line.name, "North"); assert.equal(card.lines.find(l => l.line.kind === "row").line.value, "5"); }
    if (budget === 3) assert.equal(card.lines.at(-1).text, "+7 more");
    console.log(`A4 budget ${budget}: ${JSON.stringify(card.lines)}`);
  }
  for (const hidden of [[], ["string:P"]]) {
    const d = draw("share", 960, 540, hidden), total = hidden.length ? 60 : 160; let shares = 0;
    for (const e of d.r.index.marks) { const si = d.m.series.findIndex(s => s.id === e.series); shares += d.m.cells[si][e.datum] / total; for (const row of e.card.rows) assert.equal(row.value, E.text.value(d.m.cells[d.m.series.findIndex(s => s.title === row.name)][e.datum] / total, "percent")); }
    assert.ok(Math.abs(shares - 1) < 1e-12);
  }
  const off = draw("many-series", 960, 540, ["string:North"]);
  same(m.edges, off.m.edges);
  assert.ok(off.hand.value.hi < hand.value.hi);
  for (const e of off.r.index.marks) {
    assert.notEqual(e.series, "string:North");
    const s = off.m.series.findIndex(s => s.id === e.series);
    const total = off.m.cells.slice(1, s + 1).reduce((n, c) => n + c[e.datum], 0);
    assert.ok(Math.abs(e.shape.y - off.hand.value.scale(total)) < 0.011);
    const p = segs(off.r).find(p => p.series === e.series && p.datum === e.datum);
    assert.equal(p.fill, segs(r).find(q => q.series === p.series && q.datum === p.datum).fill);
  }
  assert.equal(r.primitives.filter(p => p.k === "rect" && p.role === "legend").length, 8);
  const browser = fakeBrowser(), svg = browser.doc.createElementNS(E.write.NS, "svg");
  browser.host.appendChild(svg); E.writeDom(r.primitives, svg, "light");
  const before = globalThis.JsRendered; globalThis.JsRendered = E;
  try {
    const layer = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"));
    const h = layer.attach(svg, { output: r, theme: "light", measure: null, frame: { w: 960, h: 540 } });
    const entry = descendants(svg).find(e => e.tagName === "g" && e.getAttribute("data-legend") === "string:North");
    const box = lm.entries[0].box;
    svg.dispatch("pointermove", { clientX: box.x + box.w / 2, clientY: box.y + box.h / 2 });
    assert.match(svg.getAttribute("class"), /dp-dimmed/);
    const active = descendants(svg).filter(e => /dp-active/.test(e.getAttribute("class") || ""));
    assert.equal(active.length, 2);
    h.detach();
  } finally { globalThis.JsRendered = before; }
});

test("G2/G3/G14: independent null oracle, open final bin, nice axis texts and distinct card edges", () => {
  const bad = make('if (D.isNull(X[i])) { warn("chart.rule.null-position"); continue; }', 'if (D.isNull(X[i])) { warn("chart.rule.null-position"); X[i] = 0; }');
  const fx = values([null, 50, 100], 10);
  assert.equal(draw(fx).r.index.marks.length, expectedHistogram(fx));
  assert.notEqual(draw(structuredClone(fx), 960, 540, [], bad).r.index.marks.length, expectedHistogram(fx));
  const open = make('cells[byKey.get(rows[r].key)][binOf(edges, rows[r].x)]++;', 'if (rows[r].x < edges[nb]) cells[byKey.get(rows[r].key)][binOf(edges, rows[r].x)]++;');
  assert.equal(sum(model(FX["edge-values"])), 11); assert.equal(sum(model(FX["edge-values"], open)), 10);
  const raw = make('S.nice(lo, hi, asked).ticks', '(function () { var out = []; for (var i = 0; i <= asked; i++) out.push(lo + (hi - lo) * i / asked); return out; })()');
  const texts = R => draw(values([-3.2, 7.9], 5), 960, 540, [], R).r.primitives.filter(p => p.k === "text" && p.role === "tick" && p.anchor === "middle").map(p => p.text);
  same(texts(E), ["−5", "0", "5"]); reject(() => same(texts(raw), ["−5", "0", "5"]));
  const single = make('m.edgeTexts[i], m.enc.x.unit', 'T.value(m.edges[i], m.enc.x.format), m.enc.x.unit');
  const check = R => {
    for (const format of ["compact", "percent", "decimal(1)", "currency(USD)"]) for (const base of [0.001, 1, 1e9]) {
      const f = values(Array.from({ length: 41 }, (_, i) => base * (1 + i / 200)), 20); f.document.config.encoding.x.format = format;
      for (const e of draw(f, 960, 540, [], R).r.index.marks) { const [a, b] = e.card.heading.slice(5).split(" to "); assert.notEqual(a, b); }
    }
  };
  check(E); reject(() => check(single));
});

test("G12: hostile histogram text rejects a writer copy with unescaped text", () => {
  const check = R => {
    const f = structuredClone(FX.hostile); f.document.config.title.text = "UNESCAPED_TITLE<script>alert(1)</script>";
    const out = draw(f, 960, 540, [], R).r;
    const svg = R.writeString(out.primitives, "standalone", "light");
    assert.ok(svg.includes("&lt;script&gt;")); assert.ok(!svg.includes("<script>"));
  };
  check(E);
  const bad = engine(s => plant(s, "31-write-string.js", 'k.text !== null ? escape(k.text) : ""', 'k.text !== null ? (k.text.indexOf("UNESCAPED_TITLE") === 0 ? k.text : escape(k.text)) : ""'));
  reject(() => check(bad));
});
