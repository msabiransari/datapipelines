// #1077: radar's model, radial marks and cards, checked independently from the rows.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, inputOf, chartFixtures, MODULE } from "./engine.mjs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { expectedRadarMarks } from "./geometry.mjs";
const R = engine();
test("radar registers band, value and series channels", () => {
  const entry = R.types().find((t) => t.name === "radar");
  assert.ok(entry, "radar registered");
  assert.deepEqual(JSON.parse(JSON.stringify(entry.channels)), [
    { key: "theta", required: true, node: { node: "shape", name: "band" } },
    { key: "r", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: false, node: { node: "shape", name: "series" } },
  ]);
});
const FX = Object.fromEntries(chartFixtures().filter(f => f.wire === "radar").map(f => [f.name, f.fixture]));
const I = createRequire(import.meta.url)(join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js"))._internal;
const plain = v => JSON.parse(JSON.stringify(v));
const same = (a, b) => assert.deepEqual(plain(a), plain(b));
const variant = (name, change) => { const f = structuredClone(FX[name]); change(f, f.document.config.encoding); return f; };
const dots = r => r.primitives.filter(p => p.role === "mark" && p.k === "circle");
const paths = r => r.primitives.filter(p => p.role === "mark" && p.k === "path");
const warnings = r => r.summary.warnings.map(w => w.code);
function probe(edit) {
  return engine(s => {
    s["types/radar.js"] = s["types/radar.js"].replace("function marks(m, hand, h) {", "function marks(m, hand, h) { R.seen = {m: m, hand: hand};");
    if (edit) edit(s);
  });
}
const E = probe();
function draw(f = FX["two-series"], w = 960, h = 540, extra = {}, eng = E) {
  eng.seen = null;
  const r = eng.render(inputOf(f, w, h, extra));
  return { r, ...eng.seen };
}
function checkRefusals(eng = E) {
  const cases = [["negative", "chart.rule.negative-part", {}], ["too-few-axes", "chart.rule.radar-axes", { axes: 2, min: 3, max: 12 }], ["too-many-axes", "chart.rule.radar-axes", { axes: 13, min: 3, max: 12 }], ["too-many-series", "chart.too_many_series", { series: 7, limit: 6 }]];
  for (const [name, code, details] of cases) same(draw(FX[name], 960, 540, {}, eng).r.summary.refusal, { code, details });
  const f = variant("too-many-series", f => {
    f.columns.a = Array.from({ length: 91 }, (_, i) => "A" + i % 13);
    f.columns.s = f.columns.a.map((_, i) => "S" + Math.floor(i / 13));
    f.columns.v = f.columns.a.map(() => 1);
  });
  assert.equal(draw(f, 960, 540, {}, eng).r.summary.refusal.code, "chart.too_many_series");
  f.columns.v[0] = -1;
  assert.equal(draw(f, 960, 540, { hidden: ["string:S0"] }, eng).r.summary.refusal.code, "chart.rule.negative-part");
}
test("D5: negative aggregates, then six series, then 3–12 axes; hidden negatives refuse too", () => checkRefusals());
test("D4: null axes and series warn once, null and non-finite aggregates are gaps", () => {
  for (const col of ["a", "s"]) {
    const f = variant("two-series", f => { f.columns[col][0] = null; f.columns[col][1] = null; });
    const r = draw(f).r;
    assert.equal(r.summary.marks, 10); same(warnings(r), ["chart.rule.null-category"]);
  }
  const f = variant("two-series", f => { f.columns.v[0] = null; f.columns.v[1] = null; });
  assert.equal(draw(f).r.summary.marks, 10);
  const big = variant("two-series", f => { f.columns.v = f.columns.v.map(String); f.columns.v[0] = "9".repeat(400); f.columns.v[1] = "9".repeat(400); f.types.v = "BIGDECIMAL"; });
  assert.equal(draw(big).r.summary.marks, 10); same(warnings(draw(big).r), ["chart.rule.non-finite"]);
  assert.equal(draw(FX.empty).r.state, "no-data");
  assert.equal(draw(FX["all-zero"]).r.summary.marks, 4);
  const allNull = variant("two-series", f => f.columns.v.fill(null));
  assert.equal(draw(allNull).r.state, "no-data");
});
test("D4: aggregate cells, count without field, all-null axes retained and all sort forms", () => {
  for (const [aggregate, want] of [["sum", 8], ["mean", 4], ["min", 3], ["max", 5], ["count", 2]]) {
    const f = variant("three-axes", (f, e) => { f.columns = { a: ["A", "A", "B", "C"], s: ["S1", "S1", "S1", "S1"], v: [3, 5, 2, 1] }; e.r.aggregate = aggregate; if (aggregate === "count") delete e.r.field; });
    assert.equal(draw(f).m.cells[0][0].num, want);
  }
  const large = "1" + "0".repeat(400), exact = variant("one-series", f => { f.columns = { a: ["A", "A", "A", "B", "C"], v: [large, "-" + large, "1", "2", "3"] }; f.types.v = "BIGDECIMAL"; });
  assert.equal(draw(exact).r.summary.marks, 3); assert.equal(expectedRadarMarks(exact), 3);
  for (const [sort, want] of [[null, ["A", "B", "C"]], ["ascending", ["A", "B", "C"]], ["descending", ["C", "B", "A"]], [["B", "A", "C"], ["B", "A", "C"]]]) {
    const f = variant("three-axes", (f, e) => { e.theta.sort = sort; f.columns.v[1] = null; f.columns.v[4] = null; });
    same(draw(f).m.categories.map(c => c.raw), want);
    assert.equal(draw(f).r.summary.marks, 4);
  }
});
test("D4/D6: 0, 1, 2 and 3 points draw nothing, dot, open line, closed fill and line", () => {
  for (let n = 0; n <= 3; n++) {
    const f = variant("three-axes", f => { f.columns.v = f.columns.v.map((x, i) => i < n ? x : null); });
    const r = draw(f).r;
    assert.equal(dots(r).length, n); assert.equal(paths(r).length, n < 2 ? 0 : n === 2 ? 1 : 2);
    if (n === 2) assert.ok(!paths(r)[0].d.includes("Z"));
    if (n === 3) assert.ok(paths(r).every(p => p.d.includes("Z")));
  }
  assert.ok(paths(draw(FX["no-fill"]).r).every(p => !p.fill));
});
function checkGeometry(eng = E) {
  const f = FX["two-series"], { r, m, hand } = draw(f, 960, 540, {}, eng);
  assert.equal(r.level, "full");
  for (const dot of dots(r)) {
    const si = m.series.findIndex(s => s.id === dot.series), ci = m.categories.findIndex(c => c.key === dot.datum);
    const angle = ci * 2 * Math.PI / m.categories.length, distance = hand.r.scale(f.columns.v[si * 6 + ci]);
    assert.ok(Math.abs(dot.cx - (hand.centre.x + distance * Math.sin(angle))) < 0.011);
    assert.ok(Math.abs(dot.cy - (hand.centre.y - distance * Math.cos(angle))) < 0.011);
    assert.equal(dot.r, 3.5); assert.equal(dot.strokeWidth, 1.5); assert.equal(dot.stroke, "--chart-canvas");
  }
  for (const p of paths(r)) {
    if (p.fill) assert.equal(p.opacity, 0.14);
    else { assert.equal(p.strokeWidth, 2); assert.equal(p.round, true); }
    const ds = dots(r).filter(d => d.series === p.series);
    same(p.d, ds.flatMap((d, i) => [i ? "L" : "M", d.cx, d.cy]).concat(["Z"]));
  }
}
test("D6: dot centres from rows and the radial hand; vertices equal centres, fill and ring paint", () => checkGeometry());
function checkReach(eng = E) {
  const f = FX["three-axes"];
  for (const point of [1, 4, 8, 16]) for (const gap of [0, 2, 8]) for (const [w, h] of [[80, 24], [150, 60], [199, 139], [240, 160], [480, 360]]) {
    const { r, hand } = draw(f, w, h, { theme: { base: "light", tokens: { "--chart-point": point, "--chart-gap": gap } } }, eng);
    const asked = point - 0.5 + 0.75 * gap / 2, kept = r.level === "minimal" ? Math.min(asked, Math.min(w, h) / 4) : asked;
    same(hand.reach, { asked, kept });
    for (const d of dots(r)) {
      assert.equal(d.r, Math.round((point - 0.5) * kept / asked * 100) / 100);
      assert.equal(d.strokeWidth, Math.round(0.75 * gap * kept / asked * 100) / 100);
      const ink = d.r + d.strokeWidth / 2;
      assert.ok(d.cx - ink >= -0.02 && d.cy - ink >= -0.02 && d.cx + ink <= w + 0.02 && d.cy + ink <= h + 0.02, JSON.stringify({ w, h, point, gap, d }));
    }
  }
}
test("D6/L10: dot and ring share the kept reach under size tokens at all three levels", () => checkReach());
test("D7: lone hidden series has a struck entry, returns when shown; minimal and legend null draw it", () => {
  for (const name of ["one-series", "three-axes"]) {
    const f = variant(name, (f, e) => { if (e.color) { f.columns.a = f.columns.a.slice(0, 3); f.columns.s = f.columns.s.slice(0, 3); f.columns.v = f.columns.v.slice(0, 3); } });
    const id = draw(f).r.summary.series[0].id;
    for (const [w, h] of [[480, 360], [240, 160]]) {
      const r = draw(f, w, h, { hidden: [id] }).r;
      assert.equal(r.summary.marks, 0); assert.equal(r.summary.series[0].hidden, true);
      assert.equal(r.primitives.filter(p => p.k === "group" && p.role === "legend").length, 1);
      assert.equal(draw(f, w, h).r.summary.marks, f.columns.v.length);
    }
    assert.equal(draw(f, 120, 90, { hidden: [id] }).r.summary.marks, f.columns.v.length);
    if (f.document.config.encoding.color) { f.document.config.encoding.color.legend = null; assert.equal(draw(f, 480, 360, { hidden: [id] }).r.summary.marks, 3); }
  }
});
test("D7: all orient values; hiding refits the shared scale and preserves colours; all hidden draws zero", () => {
  for (const orient of ["top", "bottom", "left", "right"]) {
    const f = variant("three-axes", (f, e) => e.color.legend = { orient });
    assert.equal(draw(f).r.primitives.filter(p => p.k === "group" && p.role === "legend").length, 2);
  }
  const f = variant("three-axes", f => { f.columns.v = [80, 70, 60, 10, 20, 30]; });
  const before = draw(f), after = draw(f, 960, 540, { hidden: ["string:S1"] });
  assert.ok(after.hand.r.hi < before.hand.r.hi); assert.ok(dots(after.r).every(p => p.fill === "--chart-series-2"));
  assert.equal(draw(f, 960, 540, { hidden: ["string:S1", "string:S2"] }).r.summary.marks, 0);
});
test("D8: one unique lookup per dot, series index row, point reach, bold and kept rows, exact and temporal text", () => {
  const { r, m } = draw(); assert.equal(r.index.marks.length, 12);
  assert.equal(new Set(r.index.marks.map(e => JSON.stringify([e.series, e.datum]))).size, 12);
  for (const e of r.index.marks) {
    assert.equal(e.row, m.series.findIndex(s => s.id === e.series)); assert.equal(e.near, true); assert.equal(e.emphasis, "ring");
    assert.equal(e.card.heading, String(e.datum).slice(7)); assert.equal(e.card.keep, e.row);
    same(e.card.rows.map(r => r.bold), [e.row === 0, e.row === 1]);
  }
  const one = variant("one-series", f => f.columns.v = [1, null, null, null, null, null]);
  const o = draw(one).r, p = o.index.marks[0].shape;
  for (const dx of [0, 23]) assert.equal(E.lookup.at(o.index, p.x + dx, p.y, 24), 0);
  assert.equal(E.lookup.at(o.index, p.x + 25, p.y, 24), -1);
  assert.equal(E.lookup.at(r.index, r.index.plot.x + r.index.plot.w / 2, r.index.plot.y + r.index.plot.h / 2, 24), -1);
  assert.equal(draw(FX["exact-bigint"]).r.index.marks[0].card.rows[0].value, "9,007,199,254,740,993");
  assert.equal(draw(FX["unit-format"]).r.index.marks[0].card.rows[0].value, "1,234 USD");
  assert.equal(draw(FX.months).r.index.marks[0].datum, "string:2024-01-01");
  const hidden = draw(FX["two-series"], 480, 360, { hidden: ["string:Last quarter"] }).r;
  assert.equal(hidden.index.marks[0].card.rows.length, 1);
  const missing = draw(variant("two-series", f => f.columns.v[0] = null)).r.index.marks.find(e => e.datum === "string:Speed");
  assert.equal(missing.card.keep, 0); same(missing.card.rows.map(r => [r.name, r.value, r.bold]), [["Last quarter", "70", true]]);
});
const modelOf = r => { const m = I.modelOf(E, r, E.measure.measurer(null).fn); m.theme = E.theme.resolve("light"); return m; };
const target = e => ({ kind: "mark", id: e.series, datum: e.datum });
const next = (m, e, key) => I.keyTarget(m, e, key).target;
test("D8: Right/Left and Home/End stay in a series; Down/Up use nearest anchor; entries follow each own series", () => {
  for (const hidden of [[], ["string:S1"]]) {
    const r = draw(FX["hidden-series"], 480, 360, { hidden }).r, m = modelOf(r);
    for (const sid of new Set(r.index.marks.map(e => e.series))) {
      const row = r.index.marks.filter(e => e.series === sid);
      same(next(m, target(row[0]), "ArrowRight"), target(row[1]));
      same(next(m, target(row[1]), "ArrowLeft"), target(row[0]));
      same(next(m, target(row[1]), "Home"), target(row[0])); same(next(m, target(row[1]), "End"), target(row.at(-1)));
      same(next(m, target(row.at(-1)), "ArrowRight"), { kind: "entry", id: sid });
    }
    const first = r.index.marks[0], lower = r.index.marks.filter(e => e.row === first.row + 1);
    const nearest = lower.slice().sort((a, b) => Math.hypot(a.anchor.x - first.anchor.x, a.anchor.y - first.anchor.y) - Math.hypot(b.anchor.x - first.anchor.x, b.anchor.y - first.anchor.y))[0];
    same(next(m, target(first), "ArrowDown"), target(nearest));
    assert.equal(next(m, target(nearest), "ArrowUp").id, first.series);
  }
});
const lineText = f => f.line.kind === "heading" ? f.line.text : f.line.kind === "row" ? f.label + " " + f.line.value : f.text;
function checkBudgets(eng = E) {
  const f = FX["six-series-twelve-axes"], measures = I.measures(eng, "light"), measure = eng.measure.measurer(null).fn;
  for (const k of [1, 3, 4, 5, 6, 7]) {
    const h = Array.from({ length: 517 }, (_, i) => i + 24).find(h => I.bounds({ w: 480, h }, measures).k === k);
    assert.ok(h, "budget " + k);
    const { r } = draw(f, 480, h, {}, eng), m = I.modelOf(eng, r, measure); m.theme = eng.theme.resolve("light");
    const index = r.index.marks.findIndex(e => e.series === "string:S4" && e.datum === "string:Freshness");
    const card = I.markCard(eng, measure, I.markLines(m, index), null, { w: 480, h }, measures);
    const full = ["Freshness", ...Array.from({ length: 6 }, (_, i) => "S" + (i + 1) + " " + f.columns.v[i * 12 + 2])];
    const kept = full[4], others = full.slice(1).filter(t => t !== kept);
    const want = k >= 7 ? full : k === 1 ? [kept] : ["Freshness", kept, ...others.slice(0, k - 3), "+" + (8 - k) + " more"];
    // At k=1 the row's label is cut; compare the complete row data and the visible value independently.
    if (k === 1) { assert.equal(card.lines[0].line.name, "S4"); assert.equal(card.lines[0].line.value, String(f.columns.v[38])); }
    else same(card.lines.map(lineText), want);
    assert.ok(card.lines.every(l => l.ok)); assert.ok(card.lines.some(l => l.line.kind === "row" && l.line.bold && l.line.name === "S4"));
  }
}
test("LA17: full, k=3/4/5/6 with +n more, and kept row alone at k=1", () => checkBudgets());
test("D8: compact missing entries go to the first drawn entry; cut names follow the last entry", () => {
  for (const [name, w, h] of [["six-series-twelve-axes", 240, 160], ["long-names", 480, 360], ["one-series", 480, 360], ["one-series", 120, 90], ["legend-none", 480, 360], ["long-names", 120, 90]]) {
    const r = draw(FX[name], w, h).r, m = modelOf(r);
    for (const sid of new Set(r.index.marks.map(e => e.series))) {
      let t = target(r.index.marks.filter(e => e.series === sid).at(-1));
      const to = next(m, t, "ArrowRight");
      if (to.kind === "entry") { const ids = r.primitives.filter(p => p.k === "group" && p.role === "legend").map(p => p.legend); assert.equal(to.id, ids.includes(sid) ? sid : ids[0]); }
      const visited = new Set();
      for (let i = 0; i < 40; i++) { const q = next(m, t, "ArrowRight"); if (JSON.stringify(q) === JSON.stringify(t)) break; assert.ok(!visited.has(JSON.stringify(q))); visited.add(JSON.stringify(q)); t = q; }
      if (name === "long-names") assert.equal(t.kind, h < 140 ? "mark" : "cut");
      if (name === "one-series") same(t, target(r.index.marks.at(-1)));
    }
  }
});
function changed(from, to) {
  return s => { assert.equal(s["types/radar.js"].split(from).length, 2, from); s["types/radar.js"] = s["types/radar.js"].replace(from, to); };
}
for (const [name, from, to, check] of [
  ["G2 negative drawn as zero", "if (negative) return", "if (false) return", checkRefusals],
  ["G2 missing as zero", 'if (a.num === null) return { num: null, text: "" };', 'if (a.num === null) { markCount++; return { num: 0, text: "0" }; }', e => assert.equal(draw(variant("two-series", f => f.columns.v[0] = null), 960, 540, {}, e).r.summary.marks, 11)],
  ["G3 axes before series", "if (sers.size > MAX_SERIES)", "if (sers.size > MAX_SERIES && cats.size <= MAX_AXES)", checkRefusals],
  ["G3 seventh series", "MAX_SERIES = 6", "MAX_SERIES = 8", checkRefusals],
  ["G3 axes", "MIN_AXES = 3, MAX_AXES = 12", "MIN_AXES = 2, MAX_AXES = 13", checkRefusals],
  ["G4 vertex moved", 'q.p.x, q.p.y);', 'q.p.x + 1, q.p.y);', checkGeometry],
  ["G4 opacity", 'opacity: look("radarFillShare")', 'opacity: 1', checkGeometry],
  ["G4 dot not scaled", 'r2((theme.n("--chart-point") - look("radarDotLess")) * share)', 'r2(theme.n("--chart-point") - look("radarDotLess"))', checkReach],
  ["G4 reach omitted", 'reach: function (h)', 'unusedReach: function (h)', checkReach],
  ["G6 keep first", 'if (oi === si) keep = rows.length;', '', checkBudgets],
]) test(name + ": the same assertion rejects a mutated copy and accepts clean code", () => {
  const bad = probe(changed(from, to)); assert.throws(() => check(bad)); check(E);
});
function checkLookup(eng = E) {
  const { r, m } = draw(FX["two-series"], 960, 540, {}, eng);
  for (const e of r.index.marks) {
    assert.equal(e.row, m.series.findIndex(s => s.id === e.series)); assert.equal(e.near, true); assert.equal(e.emphasis, "ring");
    assert.equal(e.card.heading, e.datum.slice(7));
  }
  const o = draw(FX["two-series"], 960, 540, { hidden: ["string:Last quarter"] }, eng).r;
  assert.ok(o.index.marks.every(e => e.card.rows.length === 1));
}
for (const [name, from, to, check] of [
  ["G6 row zero", 'row: si, shape:', 'row: 0, shape:', checkLookup],
  ["G6 row axis", 'row: si, shape:', 'row: q.ci, shape:', checkLookup],
  ["G6 near false", 'near: true, emphasis:', 'near: false, emphasis:', checkLookup],
  ["G6 heading", 'heading: cat.full', 'heading: "Scorecard"', checkLookup],
  ["G6 hidden card", 'hand.visible[other.id] !== true || ', '', checkLookup],
  ["G6 emphasis", 'emphasis: "ring", ', '', checkLookup],
  ["G4 first axis rotated", 'hand.angles[ci])', 'hand.angles[ci] + 90)', checkGeometry],
]) test(name + ": the copy fails, the original passes", () => { const bad = probe(changed(from, to)); assert.throws(() => check(bad)); check(E); });
test("LA2: at full point changes only dot radius; LA5 radar fill reads its look field", () => {
  const a = draw().r, b = draw(FX["two-series"], 960, 540, { theme: { base: "light", tokens: { "--chart-point": 8 } } }).r;
  assert.equal(a.level, "full");
  const withoutR = r => r.primitives.map(p => p.k === "circle" && p.role === "mark" ? { ...p, r: 0 } : p);
  same(withoutR(a), withoutR(b)); assert.ok(dots(b).every(d => d.r === 7.5));
  const check = eng => {
    eng.tables.LOOK.radarFillShare.value = 0.112;
    assert.ok(paths(draw(FX["two-series"], 960, 540, {}, eng).r).filter(p => p.fill).every(p => p.opacity === 0.112));
  };
  const literal = probe(changed('opacity: look("radarFillShare")', 'opacity: 0.14')); assert.throws(() => check(literal)); check(probe());
});
test("G3: the refusal needs its sentence; G5: dot-less and consumer fields cannot be hardcoded", () => {
  const noSentence = probe(s => { s["20-render.js"] = s["20-render.js"].replace('"chart.rule.radar-axes": function', '"chart.rule.unused": function'); });
  assert.throws(() => noSentence.sentence(draw(FX["too-few-axes"], 960, 540, {}, noSentence).r.summary.refusal), /no sentence for chart.rule.radar-axes/);
  checkRefusals(E);
  const wrongDot = probe(); wrongDot.tables.LOOK.radarDotLess.value = 0;
  assert.throws(() => checkGeometry(wrongDot)); checkGeometry(E);
});
test("G2 warnings once and G4 extent follows only visible series: plants fail those assertions", () => {
  const f = variant("three-axes", f => { f.columns.v = [80, 70, 60, 10, 20, 30]; });
  const checkExtent = eng => {
    const a = draw(f, 960, 540, {}, eng), b = draw(f, 960, 540, { hidden: ["string:S1"] }, eng);
    assert.ok(b.hand.r.hi < a.hand.r.hi);
  };
  const allSeries = probe(changed('if (visible[ser.id] !== true) return;', '')); assert.throws(() => checkExtent(allSeries)); checkExtent(E);
  const checkWarning = eng => {
    const f = variant("two-series", f => { f.columns.a[0] = null; f.columns.a[1] = null; });
    assert.equal(warnings(draw(f, 960, 540, {}, eng).r).filter(c => c === "chart.rule.null-category").length, 1);
  };
  const repeated = probe(changed('if (dropped) warnings.push({ code: "chart.rule.null-category" });', 'if (dropped) for (var w = 0; w < 2; w++) warnings.push({ code: "chart.rule.null-category" });')); assert.throws(() => checkWarning(repeated)); checkWarning(E);
});
test("D8: long one-series and legend-null names are key targets; every series enters its own entry", () => {
  for (const mode of ["one", "null", "three"]) {
    const f = variant("long-names", (f, e) => {
      if (mode === "null") e.color.legend = null;
      if (mode === "one") { delete e.color; f.columns.a = f.columns.a.slice(0, 6); f.columns.v = f.columns.v.slice(0, 6); delete f.columns.s; }
      if (mode === "three") { f.columns.a.push(...f.columns.a.slice(0, 6)); f.columns.v.push(40, 35, 25, 30, 20, 15); f.columns.s.push(...Array(6).fill("S3")); }
    });
    const r = draw(f, 480, 360).r, m = modelOf(r);
    for (const sid of new Set(r.index.marks.map(e => e.series))) {
      let t = target(r.index.marks.filter(e => e.series === sid).at(-1)); t = next(m, t, "ArrowRight");
      assert.equal(t.kind, mode === "three" ? "entry" : "cut");
      if (mode === "three") { assert.equal(t.id, sid); while (t.kind === "entry") { const q = next(m, t, "ArrowRight"); assert.notDeepEqual(q, t); t = q; } assert.equal(t.kind, "cut"); }
    }
  }
});
