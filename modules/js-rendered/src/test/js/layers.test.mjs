// Lane 1101 (#1101): a bar layer is selectable where bars and points share one crosshair. The test type test_layers
// (types/test_layers.js) draws bars and a line over one crosshair, the shape combo (#1069) draws; each bar layer's index
// entry carries `spans` (the bars' own boxes: x, w, foot, end), which the interaction layer reads.
// Every expected layer, box and anchor below is computed HERE from the drawn primitives and the document's columns, never
// from `spans` and never by the layer under test. Every check is a function over a layer and an engine that returns its
// failures, so the gates G1 to G6 run the same check on a planted copy of the layer or of the engine and see it go red; the
// check's own test on the real pair is the green direction.
//   A.2 the engine: `spans` is written for a bar layer and for nothing else, equals the drawn boxes, and a bad entry is refused.
//   A.3 hover: the hovered layer at a grid of places, against the rule (point, bar, nearest end); the control without spans.
//   A.4 the focus box and the anchor of a bar layer's mark; a dot's are as before.
//   A.5 the keys: the plan is the plan of the same chart without spans, and every layer's row is reached.
//   A.6 the card: every layer listed, the hovered row kept where lines are short.
//   A.7 hostile names are text.
import { test } from "node:test";
import assert from "node:assert/strict";
import Module from "node:module";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { engineWith, testTypeKey, typeFixtures, typeInputOf, MODULE } from "./engine.mjs";
import { plant } from "./forms.mjs";

const KEY = testTypeKey("test_layers");
const LAYER = join(MODULE, "../web/src/main/resources/static/js/datapipelines-chart-interactions.js");
const MANGLE_FROM = "      perId[s.id] = ix;\n", MANGLE_TO = "      if (R.mangle) R.mangle(ix);\n      perId[s.id] = ix;\n";
/** test_layers with a hook that may change each layer's index entry (the refusals); `edit` plants further. */
const engineOf = (edit) => engineWith(["test_layers"], (s) => { plant(s, KEY, MANGLE_FROM, MANGLE_TO); if (edit) edit(s); });
/** The interaction layer's pure half, from the file or from a copy of its text changed by `edit` (a planted copy). */
function layerOf(edit) {
  const text = readFileSync(LAYER, "utf8"), m = new Module(LAYER);
  m.filename = LAYER;
  m.paths = Module._nodeModulePaths(dirname(LAYER));
  m._compile(edit ? edit(text) : text, LAYER);
  return m.exports._internal;
}
const plantText = (text, from, to) => { assert.equal(text.split(from).length, 2, "the anchor occurs once: " + JSON.stringify(from.slice(0, 70))); return text.replace(from, () => to); };

const FX = Object.fromEntries(typeFixtures("test_layers").map((f) => [f.name, f.fixture]));
const WITH_SPANS = Object.keys(FX).filter((n) => n !== "empty" && n !== "no-spans");
const FRAMES = [[480, 300], [960, 540], [320, 240], [240, 140]];
const SAME = 0.005, TOL = 0.011;
const plain = (v) => JSON.parse(JSON.stringify(v));
const ARITY_OF = (R) => R.prim.ARITY;

/** One drawn picture with what the layer needs: {R, out, model, M, WIDTH, frame}. */
function drawn(E, I, name, w, h, hidden = [], fx = FX[name]) {
  const out = E.render(typeInputOf(fx, w, h, { hidden }));
  if (out.state !== "rendered") return null;
  const WIDTH = E.measure.measurer(null).fn, M = I.measures(E, "light");
  const model = I.modelOf(E, out, WIDTH);
  model.theme = M.theme;
  // A series is off only where its legend entry is drawn (the engine's rule): what the summary says is hidden, not what was asked.
  const off = out.summary.series.filter((s) => s.hidden).map((s) => s.id);
  return { R: E, out, model, M, WIDTH, frame: { w, h }, fx, hidden: off };
}

/** What a series drew at one category, from the primitives and the columns: bars' rects and dots' circles in row order. */
function layersAt(d, datum) {
  const e = d.fx.document.config.encoding, o = d.fx.document.config.options || {};
  const defs = [["bars", e.y.field, "bar"]];
  if (e.y3) defs.push(["bars2", e.y3.field, "bar"]);
  if (o.lineFirst === true) defs.unshift(["line", e.y2.field, "line"]); else defs.push(["line", e.y2.field, "line"]);
  const out = [];
  for (const [id, field, kind] of defs) {
    if (d.hidden.includes(id)) continue;
    const col = d.fx.columns[field], v = col[datum];
    if (typeof v !== "number") continue;
    let before = 0;
    for (let i = 0; i < datum; i++) if (typeof col[i] === "number") before++;
    const prims = d.out.primitives.filter((p) => p.role === "mark" && p.series === id && p.k === (kind === "bar" ? "rect" : "circle"));
    const p = prims[before];
    if (kind === "bar") out.push({ id, bar: { x: p.x, y: p.y, w: p.w, h: p.h }, end: v < 0 ? p.y + p.h : p.y, up: !(v < 0) });
    else out.push({ id, dot: { x: p.cx, y: p.cy } });
  }
  return out;
}
/** The categories that have a mark, by the x of their centre: from the dots and the bars' own centres. */
function centres(d) {
  const seen = new Map();
  for (const s of d.out.index.series) if (s.on) s.datum.forEach((k, i) => { if (!seen.has(k)) seen.set(k, s.at[i]); });
  return [...seen].map(([datum, at]) => ({ datum, at }));
}
/** The rule (D1) computed from the picture: the layer a pointer at pos selects, or null. */
function expectedHit(d, pos) {
  const cs = centres(d);
  if (cs.length === 0) return null;
  let best = null;
  for (const c of cs) if (best === null || Math.abs(c.at - pos.x) < Math.abs(best.at - pos.x) - 1e-9) best = c;
  const here = layersAt(d, best.datum), reach = d.M.dot;
  let pick = null, pickD = Infinity;
  for (const l of here) if (l.dot) {
    const dist = Math.hypot(l.dot.x - pos.x, l.dot.y - pos.y);
    if (dist <= reach + 1e-6 && (pick === null || dist < pickD - SAME)) { pick = l.id; pickD = dist; }
  }
  if (pick !== null) return { id: pick, datum: best.datum, rule: "point" };
  for (const l of here) if (l.bar && pos.x >= l.bar.x - SAME && pos.x <= l.bar.x + l.bar.w + SAME && pos.y >= l.bar.y - SAME && pos.y <= l.bar.y + l.bar.h + SAME) { // an edge counts as inside, as for every box of the layer
    const near = Math.abs(l.end - pos.y);
    if (pick === null || near < pickD - SAME) { pick = l.id; pickD = near; }
  }
  if (pick !== null) return { id: pick, datum: best.datum, rule: "bar" };
  for (const l of here) {
    const away = Math.abs((l.bar ? l.end : l.dot.y) - pos.y);
    if (pick === null || away < pickD - SAME) { pick = l.id; pickD = away; }
  }
  return pick === null ? null : { id: pick, datum: best.datum, rule: "nearest" };
}
/** A grid over the plot, plus every bar's centre and every dot's centre. */
function places(d, nx = 40, ny = 30) {
  const p = d.out.index.plot, out = [];
  for (let a = 0; a < nx; a++) for (let b = 0; b < ny; b++) out.push({ x: p.x + (p.w * (a + 0.5)) / nx, y: p.y + (p.h * (b + 0.5)) / ny });
  for (const q of d.out.primitives) {
    if (q.role !== "mark") continue;
    if (q.k === "rect") out.push({ x: q.x + q.w / 2, y: q.y + q.h / 2 });
    if (q.k === "circle") out.push({ x: q.cx, y: q.cy });
  }
  return out;
}
const HIDE = [[], ["bars"], ["line"]];

/** A.3: the hovered layer at every place against the rule. {compared, bad: [text], classes}. */
function hoverFailures(E, I, names = WITH_SPANS) {
  const r = { compared: 0, bad: [], classes: { point: 0, bar: 0, nearest: 0 } };
  for (const name of names) for (const [w, h] of FRAMES) for (const hidden of HIDE) {
    const d = drawn(E, I, name, w, h, hidden);
    if (d === null || d.out.index.plot === null) continue;
    for (const pos of places(d)) {
      const want = expectedHit(d, pos), hit = I.lookup(E, d.model, pos), got = hit.mark ? { id: hit.mark.id, datum: hit.mark.datum } : null;
      r.compared++;
      if (want) r.classes[want.rule]++;
      if (want === null ? got !== null : got === null || got.id !== want.id || got.datum !== want.datum) {
        if (r.bad.length < 5) r.bad.push(`${name} ${w}x${h} hidden=${hidden} at ${pos.x.toFixed(1)},${pos.y.toFixed(1)}: got ${got && got.id}, expected ${want && want.id} (${want && want.rule})`);
        else r.bad.length = Math.min(r.bad.length, 5);
        r.count = (r.count || 0) + 1;
      }
    }
  }
  return r;
}
const badCount = (r) => r.count || 0;

/** A.4: for every bar mark, the focus box is the drawn box, the anchor is the bar's end centre, the ring holds the box. */
function boxFailures(E, I) {
  const r = { marks: 0, bad: [] };
  for (const name of WITH_SPANS) for (const [w, h] of FRAMES) {
    const d = drawn(E, I, name, w, h);
    if (d === null) continue;
    for (const s of d.out.index.series) s.datum.forEach((datum) => {
      const l = layersAt(d, datum).find((q) => q.id === s.id);
      if (!l || !l.bar) return;
      r.marks++;
      const target = { kind: "mark", id: s.id, datum };
      const tb = I.targetBox(d.model, target, d.M), a = I.anchorOf(d.model, s.id, datum);
      const sc = I.scene(E, d.model, { output: d.out, theme: "light", measure: null, frame: d.frame }, d.M, d.WIDTH, { last: "key", target, hit: null, pointer: null });
      const ring = sc.prims.find((p) => p.role === "focus"), b = l.bar;
      const okBox = tb && Math.abs(tb.x - b.x) <= TOL && Math.abs(tb.y - b.y) <= TOL && Math.abs(tb.w - b.w) <= TOL && Math.abs(tb.h - b.h) <= TOL;
      const okAnchor = a && Math.abs(a.x - (b.x + b.w / 2)) <= TOL && Math.abs(a.y - l.end) <= TOL;
      const okRing = ring && ring.x <= b.x + TOL && ring.y <= b.y + TOL && ring.x + ring.w >= b.x + b.w - TOL && ring.y + ring.h >= b.y + b.h - TOL;
      if (!okBox || !okAnchor || !okRing) r.bad.push(`${name} ${w}x${h} ${s.id}/${datum}: box ${!!okBox} anchor ${!!okAnchor} ring ${!!okRing}`);
    });
    // a dot's box and anchor are what they were: the dot's centre, a square of the crosshair dot's radius
    for (const s of d.out.index.series) s.datum.forEach((datum) => {
      const l = layersAt(d, datum).find((q) => q.id === s.id);
      if (!l || !l.dot) return;
      r.marks++;
      const tb = I.targetBox(d.model, { kind: "mark", id: s.id, datum }, d.M), a = I.anchorOf(d.model, s.id, datum);
      if (!tb || Math.abs(tb.w - 2 * d.M.dot) > TOL || Math.abs(a.x - l.dot.x) > TOL || Math.abs(a.y - l.dot.y) > TOL) r.bad.push(`${name} ${w}x${h} ${s.id}/${datum}: the dot's box or anchor moved`);
    });
  }
  return r;
}

const KEYS = ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End", "Enter", " ", "Escape"];
/** The plan of every key from every reachable target (breadth first), as JSON. */
function planOf(I, model) {
  const seen = new Map([["null", true]]), queue = [null], rows = [];
  while (queue.length > 0) {
    const t = queue.shift();
    for (const key of KEYS) {
      const r = I.keyTarget(model, t, key);
      rows.push([t, key, plain(r)]);
      const k = JSON.stringify(r.target);
      if (!seen.has(k)) { seen.set(k, true); queue.push(r.target); }
    }
  }
  return { rows, targets: [...seen.keys()] };
}

// ------------------------------------------------------------------------------------------------------- A.2 the engine

test("A.2: spans is written for a bar layer only, and equals the drawn bars' boxes; a series without it has none", () => {
  const E = engineOf(), I = layerOf();
  let bars = 0;
  for (const name of WITH_SPANS) for (const [w, h] of FRAMES) {
    const d = drawn(E, I, name, w, h);
    if (d === null) continue;
    for (const s of d.out.index.series) {
      if (s.id === "line") { assert.equal(s.spans, undefined, name + ": the line has no spans"); continue; }
      assert.equal(s.spans.length, s.at.length, name + ": one span per mark");
      s.datum.forEach((datum, i) => {
        const l = layersAt(d, datum).find((q) => q.id === s.id), sp = s.spans[i];
        const foot = l.up ? l.bar.y + l.bar.h : l.bar.y;
        for (const [got, want, what] of [[sp.x, l.bar.x, "x"], [sp.w, l.bar.w, "w"], [sp.foot, foot, "foot"], [sp.end, l.end, "end"]]) assert.ok(Math.abs(got - want) <= TOL, `${name} ${w}x${h} ${s.id}/${datum} ${what}: ${got}, drawn ${want}`);
        bars++;
      });
    }
  }
  assert.ok(bars >= 200, "bars checked: " + bars);
  // the control: the same chart without spans has the same index but for them
  const a = drawn(E, I, "bars-line", 480, 300), b = drawn(E, I, "no-spans", 480, 300);
  const strip = (x) => plain(x.out.index.series.map((s) => { const c = { ...s }; delete c.spans; return c; }));
  assert.deepEqual(strip(a), strip(b));
  assert.equal(b.out.index.series.filter((s) => s.spans !== undefined).length, 0);
  console.log(`A.2 spans equal the drawn boxes: ${bars} bars`);
});

test("A.2: a bad spans is refused whole with its sentence; a null entry is a mark that is no bar", () => {
  const CASES = [
    ["not a list", () => ({}), /series bars: spans is not a list of one entry per mark/],
    ["too short", (ix) => ix.spans.slice(1), /series bars: spans is not a list of one entry per mark/],
    ["an entry that is no box", (ix) => { const s = ix.spans.slice(); s[0] = 7; return s; }, /spans\[0\] is not null or a box with a width of 0 or more/],
    ["a negative width", (ix) => { const s = ix.spans.slice(); s[1] = { ...s[1], w: -1 }; return s; }, /spans\[1\] is not null or a box with a width of 0 or more/],
    ["a width that is NaN", (ix) => { const s = ix.spans.slice(); s[1] = { ...s[1], w: NaN }; return s; }, /spans\[1\] is not null or a box with a width of 0 or more/],
    ["an x that is a text", (ix) => { const s = ix.spans.slice(); s[2] = { ...s[2], x: "3" }; return s; }, /spans\[2\]\.x is not a finite number/],
    ["an end that is infinite", (ix) => { const s = ix.spans.slice(); s[2] = { ...s[2], end: Infinity }; return s; }, /spans\[2\]\.end is not a finite number/],
    ["a foot that is missing", (ix) => { const s = ix.spans.slice(); s[3] = { x: 1, w: 1, end: 1 }; return s; }, /spans\[3\]\.foot is not a finite number/],
  ];
  for (const [what, make, sentence] of CASES) {
    const E = engineOf();
    E.mangle = (ix) => { if (ix.series === "bars") ix.spans = make(ix); };
    assert.throws(() => E.render(typeInputOf(FX["bars-line"], 480, 300)), sentence, what);
  }
  const E = engineOf();
  E.mangle = (ix) => { if (ix.series === "bars") ix.spans[0] = null; };
  const out = E.render(typeInputOf(FX["bars-line"], 480, 300));
  assert.equal(out.state, "rendered");
  assert.equal(out.index.series[0].spans[0], null);
  // a null entry is no bar: the layer treats that mark as a point, as a series without spans is
  const I = layerOf(), model = I.modelOf(E, out, E.measure.measurer(null).fn);
  assert.equal(model.series[0].marks[0].span, undefined);
  assert.ok(model.series[0].marks[1].span);
});

// ------------------------------------------------------------------------------------------------------------ A.3 hover

test("A.3: the hovered layer at every place is the rule's (a point under the pointer, else the bar holding it, else the nearest end)", () => {
  const E = engineOf(), I = layerOf(), r = hoverFailures(E, I);
  assert.deepEqual(r.bad, [], "disagreements: " + badCount(r));
  assert.ok(r.compared >= 120000, "places compared: " + r.compared);
  for (const k of Object.keys(r.classes)) assert.ok(r.classes[k] >= 1000, `the ${k} case was met ${r.classes[k]} times`);
  console.log(`A.3 ${r.compared} places over ${WITH_SPANS.length} fixtures x ${FRAMES.length} frames x 3 hidings: 0 disagree; point ${r.classes.point}, bar ${r.classes.bar}, nearest ${r.classes.nearest}`);
});

test("A.3: the control - a chart whose bar layers carry no spans never selects a bar layer inside a bar (the defect, as it was)", () => {
  const E = engineOf(), I = layerOf();
  let inside = 0, line = 0, bar = 0;
  for (const [w, h] of FRAMES) {
    const d = drawn(E, I, "no-spans", w, h);
    if (d === null) continue;
    for (const q of d.out.primitives) {
      if (q.role !== "mark" || q.k !== "rect") continue;
      for (let a = 0; a < 20; a++) for (let b = 0; b < 20; b++) {
        const pos = { x: q.x + (q.w * (a + 0.5)) / 20, y: q.y + (q.h * (b + 0.5)) / 20 };
        const hit = I.lookup(E, d.model, pos);
        inside++;
        if (hit.mark.id === "line") line++; else bar++;
      }
    }
  }
  assert.ok(inside >= 4000, "places inside a bar: " + inside);
  assert.equal(bar, 0, "a bar layer hovered");
  assert.equal(line, inside);
  console.log(`A.3 control (no spans): ${inside} places inside a bar, hovered layer = line ${line}, bar ${bar}`);
});

// -------------------------------------------------------------------------------------------- A.4 the focus box and anchor

test("A.4: a bar layer's focus box is the drawn bar, its anchor the bar's end, its ring holds the bar; a dot's are unchanged", () => {
  const E = engineOf(), I = layerOf(), r = boxFailures(E, I);
  assert.deepEqual(r.bad.slice(0, 5), []);
  assert.ok(r.marks >= 350, "marks checked: " + r.marks);
  console.log(`A.4 ${r.marks} marks (bars and dots): box, anchor and ring as drawn`);
});

// ------------------------------------------------------------------------------------------------------------- A.5 keys

/** A.5: for every chart, the plan with spans equals the plan of the same chart without them, and every mark is reached. */
function keyFailures(E, I) {
  const r = { plans: 0, rows: 0, bad: [] };
  for (const name of WITH_SPANS) {
    const withSpans = drawn(E, I, name, 960, 540), fx2 = structuredClone(FX[name]);
    fx2.document.config.options = Object.assign({}, fx2.document.config.options, { spans: false });
    const without = drawn(E, I, name, 960, 540, [], fx2);
    const a = planOf(I, withSpans.model), b = planOf(I, without.model);
    r.plans++; r.rows += a.rows.length;
    if (JSON.stringify(a) !== JSON.stringify(b)) r.bad.push(name + ": the plan moved");
    for (const s of withSpans.out.index.series) for (const datum of s.datum) if (!a.targets.includes(JSON.stringify({ kind: "mark", id: s.id, datum }))) r.bad.push(`${name}: ${s.id}/${datum} is not reached`);
  }
  return r;
}

test("A.5: the key plan equals that of the same chart without spans, and reaches every layer's row", () => {
  const E = engineOf(), I = layerOf(), r = keyFailures(E, I);
  assert.deepEqual(r.bad, []);
  assert.ok(r.plans >= 9 && r.rows >= 1000, `${r.plans} plans, ${r.rows} transitions`);
  console.log(`A.5 ${r.plans} charts: ${r.rows} key transitions identical with and without spans; every mark reached`);
});

// ------------------------------------------------------------------------------------------------------------- A.6 card

test("A.6: the card lists every layer where it fits and keeps the hovered layer's row where two lines fit, one where one fits", () => {
  const E = engineOf(), I = layerOf(), M = I.measures(E, "light");
  let n = 0;
  for (const lines of [4, 3, 2, 1]) {
    const h = Math.ceil(2 * M.p + lines * M.h + 0.5);
    const d = drawn(E, I, "bars-line", 640, h);
    if (d === null) continue;
    const bar = d.out.primitives.filter((p) => p.role === "mark" && p.series === "bars" && p.k === "rect")[2];
    for (const pos of [{ x: bar.x + bar.w / 2, y: bar.y + bar.h - 3 }, { x: d.out.primitives.filter((p) => p.k === "circle" && p.series === "line")[2].cx, y: d.out.primitives.filter((p) => p.k === "circle" && p.series === "line")[2].cy }]) {
      const want = expectedHit(d, pos), hit = I.lookup(E, d.model, pos);
      assert.equal(hit.mark.id, want.id);
      const sc = I.scene(E, d.model, { output: d.out, theme: "light", measure: null, frame: d.frame }, d.M, d.WIDTH, { last: "pointer", hit, pointer: pos, target: null });
      const texts = sc.prims.filter((p) => p.role === "tooltip" && p.k === "text").map((p) => p.text);
      const rowsOf = { bars: ["Bars", "120"], line: ["Line", "0.12"] };
      const hovered = rowsOf[want.id === "bars" ? "bars" : "line"], other = rowsOf[want.id === "bars" ? "line" : "bars"];
      const all = ["Mar", ...rowsOf.bars, ...rowsOf.line];
      const expected = lines >= 3 ? all : lines === 2 ? ["Mar", ...hovered] : hovered;
      assert.deepEqual(texts.map(String), expected.map(String), `${lines} lines, hovered ${want.id}`);
      assert.equal(sc.active, want.id);
      n++;
      void other;
    }
  }
  assert.ok(n >= 6, "cards checked: " + n);
  console.log(`A.6 ${n} cards`);
});

// ------------------------------------------------------------------------------------------------------------ A.7 hostile

test("A.7: names with markup are text: the card and the picture's string hold them escaped", () => {
  const E = engineOf(), I = layerOf();
  let cards = 0;
  for (const [w, h] of [[480, 300], [960, 540]]) {
    const d = drawn(E, I, "hostile-names", w, h);
    for (const q of d.out.primitives.filter((p) => p.role === "mark" && (p.k === "rect" || p.k === "circle"))) {
      const pos = q.k === "rect" ? { x: q.x + q.w / 2, y: q.y + q.h - 2 } : { x: q.cx, y: q.cy };
      const hit = I.lookup(E, d.model, pos);
      const sc = I.scene(E, d.model, { output: d.out, theme: "light", measure: null, frame: d.frame }, d.M, d.WIDTH, { last: "pointer", hit, pointer: pos, target: null });
      const svg = E.writeString(d.out.primitives.concat(sc.prims), "page", "light");
      assert.ok(!/<img|<script|<b>|<\/text><script/.test(svg), "raw markup in the string");
      assert.ok(svg.includes("&lt;"), "the escaped form is there");
      cards++;
    }
  }
  assert.ok(cards >= 12, "cards: " + cards);
  console.log(`A.7 ${cards} cards with hostile names, none raw`);
});

// ---------------------------------------------------------------------------------------------------- gates (red, then green)

test("G1 to G7: each plant turns its check red, the real pair is green", () => {
  const E = engineOf(), I = layerOf();
  // G1: the layer never uses the rule where bars are present (hasSpans false): the bar layer is never hovered
  const g1 = layerOf((t) => plantText(t, "if (hasSpans(model, datum)) return layerUnder(R, model, datum, pos);", "if (false) return layerUnder(R, model, datum, pos);"));
  assert.ok(badCount(hoverFailures(E, g1, ["bars-line", "two-bars-line"])) > 0, "G1 not red");
  // G2: the point comes first with a reach of 0 (a dot under the pointer no longer wins)
  const g2 = layerOf((t) => plantText(t, "if (d <= reach + EPS && (pick === null", "if (d <= 0 && (pick === null"));
  assert.ok(badCount(hoverFailures(E, g2, ["bar-over-line", "bars-line"])) > 0, "G2 not red");
  // G3: the bar is chosen by the nearest end only (the box test is dropped)
  const g3 = layerOf((t) => plantText(t, "if (seen[i].span === null || !inside(spanBox(seen[i].span), pos)) continue;", "if (seen[i].span === null) continue;"));
  assert.ok(badCount(hoverFailures(E, g3, ["bars-line", "two-bars-line", "negative-bars"])) > 0, "G3 not red");
  // G4: the focus box is the dot's square again
  const g4 = layerOf((t) => plantText(t, "if (tm && tm.span) return spanBox(tm.span);", ""));
  assert.ok(boxFailures(E, g4).bad.length > 0, "G4 not red");
  // G5: the anchor is the plot's middle again for a bar (refY gives no y)
  const g5 = layerOf((t) => plantText(t, "return mark.span ? mark.span.end : pointY(model, id, mark.at);", "return mark.span ? null : pointY(model, id, mark.at);"));
  assert.ok(boxFailures(E, g5).bad.length > 0, "G5 not red");
  // G6: the engine drops spans (pointIndex forgets them): the hover check goes red
  const E6 = engineOf((s) => plant(s, "20-render.js", "if (q && q.spans !== undefined) e.spans = spansOf(q.spans, e.at.length, series[s].id);", ""));
  assert.ok(badCount(hoverFailures(E6, I, ["bars-line", "two-bars-line"])) > 0, "G6 not red");
  // G7: the layer's model leaves out a series that carries spans: the keys no longer reach the bar layer
  const g7 = layerOf((t) => plantText(t, "if (!e.on || e.at.length === 0) continue;", "if (!e.on || e.at.length === 0 || Array.isArray(e.spans)) continue;"));
  assert.ok(keyFailures(E, g7).bad.length > 0, "G7 not red");
  // green: the real pair
  assert.equal(keyFailures(E, I).bad.length, 0);
  assert.equal(badCount(hoverFailures(E, I, ["bars-line", "two-bars-line", "negative-bars", "bar-over-line"])), 0);
  assert.equal(boxFailures(E, I).bad.length, 0);
  console.log("G1 to G7: red on every plant, green on the real pair");
});
