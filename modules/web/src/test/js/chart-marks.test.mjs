// The interaction layer over a draw whose marks are found by their own shape (brief 1052 A.3 to A.6; design record
// 2026-11-14 §4.8; look contract §4.4, §4.7, LA17's shared part): the model and the pointer (D5), the card of one mark
// (D6), the highlight (D7) and the keys (D8). The engine is loaded as the page loads it, then the test-only type
// test_marks from the engine module's test tree. Every expected place, line, width, k and move is computed HERE
// from the fixtures' own numbers and the look table's fields, never by the function under test. The gates G5 to G9 and
// G13 run the same checks on a copy of the layer (or of a fixture) with one plant: red there, green on the real file.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync, mkdtempSync, mkdirSync, copyFileSync, writeFileSync, symlinkSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import vm from "node:vm";
import { loadEngine, fakeBrowser, descendants, STATIC_JS, ENGINE_DIR } from "./support/js-rendered.mjs";

const require = createRequire(import.meta.url);
const R = loadEngine();
const ENGINE_TESTS = path.resolve(ENGINE_DIR, "../../../../../../test");
vm.runInThisContext(readFileSync(path.join(ENGINE_TESTS, "js/types/test_marks.js"), "utf8"), { filename: "test_marks.js" });
globalThis.JsRendered = R;
const LAYER_FILE = path.join(STATIC_JS, "datapipelines-chart-interactions.js");
const LAYER_TEXT = readFileSync(LAYER_FILE, "utf8");
const layer = require(LAYER_FILE);

// ------------------------------------------------------------------------------------------------ helpers

const look = R.tables.look;
const S = R.theme.resolve("light").n("--chart-text");
const H = look("tipLine") * S;
const P = look("tipPad") * S;
const KEY = look("tipKey") * S;
const GAP = look("tipGap") * S;
const G = look("tipMax") * S;
const BOLD = look("tipValueWeight");
const REACH = look("markReach");
const DEG = Math.PI / 180;
const r2 = (v) => Math.round(v * 100) / 100 + 0;
const WIDTH = R.measure.measurer(null).fn;
/** A measurer in which the weight counts: a character is half the size at 400, 0.6 of it at the bold weight. */
const FW = (text, size, weight) => [...text].length * size * (weight >= 600 ? 0.6 : 0.5);

function expectBounds(frame) {
  const W = Math.min(frame.w, G);
  const w = W - 2 * P;
  const k = frame.h - 2 * P < H ? 0 : Math.floor((frame.h - 2 * P) / H + 1e-9);
  return { W, w, k };
}

const fixture = (name) => JSON.parse(readFileSync(path.join(ENGINE_TESTS, "fixtures/_types/test_marks", name + ".json"), "utf8"));
const NAMES = ["all-null", "box-and-outside", "cells", "empty", "full-ring", "long-card", "over", "points", "ring", "slices", "spokes"];
const draw = (fx, w, h, extra = {}) => R.render(Object.assign({ type: "test_marks", document: fx.document, columns: fx.columns, types: fx.types, frame: { w, h }, theme: "light", measure: null, hidden: [] }, extra));
const modelOf = (L, out, width = WIDTH) => {
  const m = L._internal.modelOf(R, out, width);
  m.theme = R.theme.resolve("light");
  return m;
};
const at = (out, datum) => out.index.marks.findIndex((m) => m.datum === datum);

/** A copy of the layer with each [from, to] replaced once (the anchor asserted to occur exactly once). */
function layerWith(pairs) {
  let text = LAYER_TEXT;
  for (const [from, to] of pairs) {
    assert.equal(text.split(from).length, 2, "the plant's anchor occurs once: " + from);
    text = text.split(from).join(to);
  }
  const mod = { exports: {} };
  vm.runInThisContext("(function (module) {\n" + text + "\n})", { filename: "planted-layer.js" })(mod);
  return mod.exports;
}

/** Runs [check] on a planted copy (it must go red) and on the real layer (green); returns the red message. */
function redThenGreen(name, check, planted, clean = layer) {
  let err = null;
  try {
    check(planted);
  } catch (e) {
    err = e;
  }
  assert.ok(err !== null, name + ": stayed green on the plant");
  const first = String(err.message).split("\n")[0];
  console.log(`${name}: red: ${first}`);
  check(clean);
  return first;
}

// ------------------------------------------------------------------------- the test's own lookup rule (D4)

function holds(m, x, y) {
  const s = m.shape;
  if (s.kind === "box") return x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h;
  if (s.kind === "point") return Math.hypot(x - s.x, y - s.y) <= s.r;
  const dx = x - s.cx, dy = y - s.cy, d = Math.hypot(dx, dy);
  if (d < s.r0 || d > s.r1) return false;
  if (s.to - s.from >= 360 || d === 0) return true;
  return (((Math.atan2(dx, -dy) / DEG - s.from) % 360) + 360) % 360 <= s.to - s.from;
}

function oracle(marks, plot, x, y, reach) {
  if (marks.length === 0 || x < plot.x || x > plot.x + plot.w || y < plot.y || y > plot.y + plot.h) return -1;
  let found = -1;
  marks.forEach((m, i) => {
    if (!m.near && holds(m, x, y)) found = i;
  });
  if (found >= 0) return found;
  let best = Infinity;
  marks.forEach((m, i) => {
    if (!m.near) return;
    const d = Math.hypot(x - m.shape.x, y - m.shape.y);
    if (d <= reach && d < best) {
      best = d;
      found = i;
    }
  });
  return found;
}

// ---------------------------------------------------------------------------- the test's own card (D6, L8)

/** The card of one mark as the FIXTURE says it: {heading, rows: [{name, value, plain, bold}], keep, extra}. */
function cardOf(fx, out, datum) {
  const c = (fx.document.config.cards || {})[String(datum)];
  if (c) return { heading: c.heading === undefined ? null : c.heading, rows: c.rows.map((r) => ({ name: r.name, value: r.value, plain: r.colour === null, bold: r.bold === true })), keep: c.keep, extra: c.extra || [] };
  const m = out.index.marks[at(out, datum)];
  const title = m.series === null ? "Mark" : out.summary.series.find((s) => s.id === m.series).title;
  return { heading: null, rows: [{ name: title, value: String(datum), plain: false, bold: false }], keep: 0, extra: [] };
}

/** One line fitted by the test's own reading of §4.4 step 1 and §4.7: {ok, texts, w}. */
function fitOwn(l, w, width) {
  if (l.kind === "heading") {
    const t = R.measure.fit(width, l.text, S, 400, w);
    return { ok: t !== "", texts: [t] };
  }
  const row = l.kind === "row";
  const vw = width(l.value, S, row ? BOLD : 400);
  const lead = row && !l.plain ? KEY + GAP : 0;
  if (lead + vw > w + 1e-9) return { ok: false };
  const nameWeight = row && l.bold ? BOLD : 400;
  const room = w - lead - vw - GAP;
  const shown = l.name === "" || room <= 0 ? "" : R.measure.fit(width, l.name, S, nameWeight, room);
  if ((!row || l.plain) && l.name !== "" && shown === "") return { ok: false };
  return { ok: true, texts: shown === "" ? [l.value] : [shown, l.value], w: lead + (shown === "" ? 0 : width(shown, S, nameWeight) + GAP) + vw };
}

/** The texts of the card the contract shows for [card] in [frame]: a list of lines, each its texts; null = no card. */
function expectCard(card, frame, width) {
  const b = expectBounds(frame);
  if (b.k === 0 || b.w <= 0) return null;
  const lines = [];
  if (typeof card.heading === "string" && card.heading !== "") lines.push({ kind: "heading", text: card.heading });
  card.rows.forEach((r, i) => lines.push({ kind: "row", name: r.name, value: r.value, plain: r.plain, bold: r.bold, kept: i === card.keep }));
  for (const x of card.extra) {
    const i = x.indexOf(": ");
    lines.push({ kind: "field", name: i < 0 ? "" : x.slice(0, i), value: i < 0 ? x : x.slice(i + 2) });
  }
  const fit = lines.map((l) => fitOwn(l, b.w, width));
  const kept = lines.findIndex((l) => l.kept);
  if (!fit[kept].ok) return null;
  if (fit.every((f) => f.ok) && fit.length <= b.k) return fit.map((f) => f.texts);
  if (b.k >= 3) {
    const out = [];
    if (lines[0].kind === "heading" && fit[0].ok) out.push(fit[0]);
    out.push(fit[kept]);
    for (let i = 0; i < lines.length && out.length < b.k - 1; i++) {
      if (i === kept || !fit[i].ok || lines[i].kind === "heading") continue;
      out.push(fit[i]);
    }
    const n = lines.length - out.length;
    let more = "+" + n + " more";
    if (width(more, S, 400) > b.w + 1e-9) more = "+" + n;
    if (width(more, S, 400) <= b.w + 1e-9) return out.map((f) => f.texts).concat([[more]]);
  }
  if (b.k >= 2 && lines[0].kind === "heading" && fit[0].ok) return [fit[0].texts, fit[kept].texts];
  return [fit[kept].texts];
}

/** The texts of a card the layer decided (markCard's result), in the same form. */
function shownTexts(card) {
  if (card === null) return null;
  return card.lines.map((l) => {
    if (l.line.kind === "heading" || l.line.kind === "more") return [l.text];
    return l.label === "" ? [l.line.value] : [l.label, l.line.value];
  });
}

/** The layer's card of mark [datum] of [fx] (drawn at 960 × 540) in [frame], measured by [width]. */
function layerCard(L, fx, datum, frame, width = FW) {
  const out = draw(fx, 960, 540);
  const model = modelOf(L, out);
  const n = at(out, datum);
  return { card: L._internal.markCard(R, width, L._internal.markLines(model, n), null, frame, L._internal.measures(R, "light")), out };
}

// ------------------------------------------------------------------------------- the DOM half on a fake picture

function mounted(L, out, frame) {
  const browser = fakeBrowser();
  const svg = browser.doc.createElementNS(R.write.NS, "svg");
  browser.host.appendChild(svg);
  R.writeDom(out.primitives, svg, "light");
  const written = descendants(svg).length;
  const handle = L.attach(svg, { output: out, theme: "light", measure: null, frame }, { toggle: () => {} });
  return { svg, handle, written, frame, count: out.primitives.length };
}

const cls = (e) => e.getAttribute("class") || "";
const cardTexts = (svg) => descendants(svg).filter((e) => e.tagName === "text" && /dp-tooltip/.test(cls(e))).map((e) => e.textContent);
/** The ring elements the LAYER added (the polar frame draws guides of its own): dp-guide beyond the writer's elements. */
const guides = (env) => {
  const mine = new Set(written(env.svg, env.count));
  return descendants(env.svg).filter((e) => e.nodeType === 1 && !mine.has(e) && /dp-guide/.test(cls(e)));
};
const moved = (svg) => descendants(svg).filter((e) => e.getAttribute && e.getAttribute("transform") !== null && !/^rotate\(/.test(e.getAttribute("transform")));
const active = (svg) => descendants(svg).filter((e) => /dp-active/.test(cls(e)));
const dimmed = (svg) => /dp-dimmed/.test(svg.getAttribute("class") || "");
/** The elements the writer made for the primitives, in their order (the layer's own rule: title, desc and gradients skipped). */
function written(svg, count) {
  return descendants(svg).filter((e) => e.nodeType === 1 && !["title", "desc", "linearGradient", "stop"].includes(e.tagName)).slice(0, count);
}
/** The positions of mark n's own primitives (role mark, not a group or text, its series and datum). */
function heldOf(out, n) {
  const mk = out.index.marks[n];
  return out.primitives.map((p, i) => (p.role === "mark" && p.k !== "group" && p.k !== "text" && p.series === mk.series && p.datum === mk.datum ? i : -1)).filter((i) => i >= 0);
}
const pointAt = (env, x, y) => env.svg.dispatch("pointermove", { clientX: x, clientY: y, pointerType: "mouse" });
const leave = (env) => env.svg.dispatch("pointerleave", { pointerType: "mouse" });
const key = (env, k) => env.svg.dispatch("keydown", { key: k });
/** The keys walk to the mark [datum] with ArrowRight from entering (at most 50 presses). */
function keyTo(env, datum) {
  key(env, "ArrowRight");
  for (let i = 0; i < 50 && (env.handle.target() === null || env.handle.target().datum !== datum); i++) key(env, "ArrowRight");
  assert.equal(env.handle.target().datum, datum, "the keys reach " + datum);
}

/** The test's own translate of a sector [mk] by markMove [by]. */
function translateOf(mk, by) {
  const mid = (mk.shape.from + mk.shape.to) / 2;
  return `translate(${String(r2(Math.sin(mid * DEG) * by))} ${String(r2(-Math.cos(mid * DEG) * by))})`;
}

/** The picture shows nothing of a highlight: no card, no ring, no move, no dimming, the writer's element count. */
function nothingLeft(env, what) {
  assert.equal(guides(env).length, 0, what + ": a ring is left");
  assert.equal(moved(env.svg).length, 0, what + ": a move is left");
  assert.equal(dimmed(env.svg), false, what + ": dp-dimmed is left");
  assert.equal(descendants(env.svg).length, env.written, what + ": the picture's element count");
}

/** A ring around mark n of [out]: one dp-guide element of the shape's kind, at the test's own place and size. */
function ringAround(env, out, n, ringEm = look("markRing")) {
  const g = guides(env);
  assert.equal(g.length, 1, "exactly one ring");
  const mk = out.index.marks[n];
  const s = mk.shape, by = ringEm * S;
  const markEl = written(env.svg, out.primitives.length)[heldOf(out, n)[0]];
  assert.equal(g[0].getAttribute("fill"), "none", "no fill");
  assert.equal(g[0].getAttribute("stroke"), markEl.getAttribute("fill"), "the mark's own colour");
  assert.equal(Number(g[0].getAttribute("stroke-width")), R.theme.resolve("light").n("--chart-line"));
  if (s.kind === "point") {
    assert.equal(g[0].tagName, "circle");
    assert.deepEqual([Number(g[0].getAttribute("cx")), Number(g[0].getAttribute("cy")), Number(g[0].getAttribute("r"))], [s.x, s.y, s.r + by]);
  } else {
    assert.equal(g[0].tagName, "rect");
    assert.deepEqual(["x", "y", "width", "height"].map((a) => Number(g[0].getAttribute(a))), [s.x - by, s.y - by, s.w + 2 * by, s.h + 2 * by]);
    assert.equal(Number(g[0].getAttribute("rx")), R.theme.resolve("light").n("--chart-bar-radius"));
  }
  return g[0];
}

/** Exactly mark n's elements carry the test's own translate. */
function movedOnly(env, out, n, by = look("markMove")) {
  const els = written(env.svg, out.primitives.length);
  const held = heldOf(out, n);
  const want = translateOf(out.index.marks[n], by);
  assert.equal(moved(env.svg).length, held.length, "only the mark's elements are moved");
  for (const i of held) assert.equal(els[i].getAttribute("transform"), want, "the mark's element " + i);
}

const W960 = { w: 960, h: 540 };

// ============================================================================================= A.3 the pointer

/** [name:datum -> name:datum] where D4 gives a mark's anchor to another mark; the rest answer with the mark itself. */
const EXCEPTIONS = ["over:large->small", "points:same-2->same-1"];

test("A.3: the lookup at every mark's anchor answers the mark the test's own rule names; the exceptions are listed", () => {
  let places = 0;
  for (const [w, h] of [[960, 540], [480, 360]]) {
    const exceptions = [];
    for (const name of NAMES) {
      const fx = fixture(name), out = draw(fx, w, h);
      if (!Array.isArray(out.index.marks)) continue;
      const model = modelOf(layer, out);
      out.index.marks.forEach((m, n) => {
        const want = oracle(out.index.marks, out.index.plot, m.anchor.x, m.anchor.y, REACH);
        const hit = layer._internal.lookup(R, model, m.anchor);
        assert.deepEqual(hit.mark, { id: out.index.marks[want].series, datum: out.index.marks[want].datum }, `${name} ${w}x${h} ${m.datum}`);
        assert.equal(hit.entry, null);
        if (want !== n) exceptions.push(`${name}:${m.datum}->${out.index.marks[want].datum}`);
        places++;
      });
    }
    assert.deepEqual(exceptions.sort(), EXCEPTIONS, `${w}x${h}: the exceptions`);
  }
  console.log(`A.3: ${places} anchors looked up; exceptions ${EXCEPTIONS.join(", ")}`);
});

test("A.3: a place on a legend entry answers the entry and no mark; 30 px from a near point, at the default reach, none", () => {
  const out = draw(fixture("slices"), 960, 540), model = modelOf(layer, out);
  assert.equal(model.entries.length, 3);
  for (const e of model.entries) {
    const hit = layer._internal.lookup(R, model, { x: e.box.x + e.box.w / 2, y: e.box.y + e.box.h / 2 });
    assert.equal(hit.entry, e.id);
    assert.equal(hit.mark, null);
  }
  const pts = draw(fixture("points"), 960, 540), pm = modelOf(layer, pts), big = pts.index.marks[at(pts, "big")].shape;
  const place = { x: big.x + 30, y: big.y };
  for (const m of pts.index.marks) if (m.datum !== "big") assert.ok(Math.hypot(m.anchor.x - big.x, m.anchor.y - big.y) > 60 + 1e-9 || !m.near, "no other near point within 60 px: " + m.datum);
  assert.equal(REACH, 24);
  assert.equal(layer._internal.lookup(R, pm, place).mark, null);
});

// ============================================================================================== A.4 the card

const BOX = ["box-and-outside", "box-north"];
const OUTSIDE = ["box-and-outside", "out-north-high"];
const SPOKE = ["spokes", "two-2"];
const LONG = ["long-card", "special"];
const FULL = { w: 960, h: 400 };
const K3 = { w: 960, h: 60 };
const K1 = { w: 960, h: 30 };
const K2 = { w: 960, h: 40 };

function withExtra(fx, datum, extra) {
  const copy = structuredClone(fx);
  copy.document.config.cards[datum].extra = extra;
  return copy;
}

/** The card check of one mark in one frame: the layer's texts equal the test's; [kept] names the kept row's name. */
function cardCheck(L, fx, datum, frame, kept) {
  const { card, out } = layerCard(L, fx, datum, frame);
  const want = expectCard(cardOf(fx, out, datum), frame, FW);
  assert.deepEqual(shownTexts(card), want, `${datum} at ${frame.w}x${frame.h}`);
  if (kept !== undefined) {
    const k = card.lines.find((l) => l.line.kept === true);
    assert.equal(k.line.name, kept, datum + ": the kept row");
  }
  return card;
}

const KEPT = { "box-north": "Middle", "out-north-high": "This point", "two-2": "Two", special: "Row 2" };

for (const [fname, datum] of [BOX, OUTSIDE, SPOKE, LONG]) {
  test(`A.4: ${fname} ${datum}: every line fitting, a k of 3, a k of 1, and each again with two extra lines`, () => {
    const fx = fixture(fname);
    assert.equal(expectBounds(K3).k, 3);
    assert.equal(expectBounds(K1).k, 1);
    const full = cardCheck(layer, fx, datum, FULL, KEPT[datum]);
    assert.ok(expectBounds(FULL).k >= full.lines.length);
    const k3 = cardCheck(layer, fx, datum, K3, KEPT[datum]);
    assert.equal(k3.lines[k3.lines.length - 1].line.kind, "more");
    const k1 = cardCheck(layer, fx, datum, K1, KEPT[datum]);
    assert.equal(k1.lines.length, 1);
    if (fname !== "long-card") {
      const ex = withExtra(fx, datum, ["Source: survey", "Note: two lines"]);
      for (const frame of [FULL, K3, K1]) cardCheck(layer, ex, datum, frame, KEPT[datum]);
      assert.deepEqual(shownTexts(layerCard(layer, ex, datum, FULL).card).slice(-2), [["Source", "survey"], ["Note", "two lines"]]);
    }
  });
}

/** The box's kept row is "Middle" at a k of 1 and of 3, and "+n more" counts what it leaves out (G5's checks). */
function keptChecks(L, fx = fixture("box-and-outside")) {
  const k1 = cardCheck(L, fx, "box-north", K1, "Middle");
  assert.deepEqual(shownTexts(k1), [["Middle", "60"]]);
  const k3 = cardCheck(L, fx, "box-north", K3, "Middle");
  assert.deepEqual(shownTexts(k3), [["North"], ["Middle", "60"], ["+5 more"]]);
}

test("A.4: the box keeps Middle, the outside point This point; +n more counts the lines left out", () => {
  keptChecks(layer);
  const k1 = layerCard(layer, fixture("box-and-outside"), "out-north-high", K1).card;
  assert.deepEqual(shownTexts(k1), [["This point", "98"]]);
});

/** A row without a colour (G6's checks): no key's room, its value whole at the bold weight, its name cut. */
function plainChecks(L) {
  const fx = fixture("box-and-outside");
  const card = layerCard(L, fx, "box-north", FULL).card;
  const row = card.lines.find((l) => l.line.kind === "row" && l.line.name === "Highest inside");
  const w = FW("Highest inside", S, 400) + GAP + FW("91", S, BOLD);
  assert.equal(row.w, w, "its width is its name, one gap and its value, measured at the bold weight");
  // its value never cut: a value wider than the card leaves the row out of the card
  const wide = structuredClone(fx);
  wide.document.config.cards["box-north"].rows[0].value = "9".repeat(60);
  const narrow = { w: 200, h: 400 };
  const c = layerCard(L, wide, "box-north", narrow).card;
  assert.deepEqual(shownTexts(c), expectCard(cardOf(wide, draw(wide, 960, 540), "box-north"), narrow, FW));
  assert.ok(!shownTexts(c).flat().some((t) => t.startsWith("999")), "the wide value is not shown cut");
  // its name cut with "…"
  const cut = { w: 80, h: 400 };
  const k = layerCard(L, fx, "box-north", cut).card;
  assert.deepEqual(shownTexts(k), expectCard(cardOf(fx, draw(fx, 960, 540), "box-north"), cut, FW));
  assert.ok(k.lines.some((l) => l.line.kind === "row" && l.label.endsWith(R.measure.ELLIPSIS)), "a name cut with the ellipsis");
}

test("A.4: a row without a colour takes no key's room, its value is never cut and is measured bold, its name is cut", () => plainChecks(layer));

test("A.4: a kept row that cannot fit gives no card; a card without a heading at a k of 2 is the kept row alone", () => {
  const fx = fixture("box-and-outside");
  assert.equal(layerCard(layer, fx, "box-north", { w: 20, h: 400 }).card, null);
  assert.equal(expectCard(cardOf(fx, draw(fx, 960, 540), "box-north"), { w: 20, h: 400 }, FW), null);
  const twin = fixture("long-card");
  twin.document.config.cards.twin.heading = null;
  assert.equal(expectBounds(K2).k, 2);
  const c = layerCard(layer, twin, "twin", K2).card;
  assert.deepEqual(shownTexts(c), expectCard(cardOf(twin, draw(twin, 960, 540), "twin"), K2, FW));
  assert.equal(c.lines.length, 1);
  assert.equal(c.lines[0].line.name, "Row 2");
});

// ============================================================================================== A.5 the keys

/** The test's own key targets and rows: the marks with key true in list order; rows by their number. */
function ownRows(out) {
  const rows = new Map();
  out.index.marks.forEach((m, i) => {
    if (m.key !== true) return;
    if (!rows.has(m.row)) rows.set(m.row, []);
    rows.get(m.row).push(i);
  });
  return [...rows.keys()].sort((a, b) => a - b).map((r) => rows.get(r));
}
const tgt = (out, n) => ({ kind: "mark", id: out.index.marks[n].series, datum: out.index.marks[n].datum });

/** A walk over every row (Home, then ArrowRight along the row; ArrowDown to the next): the targets visited. */
function walkAll(L, out) {
  const model = modelOf(L, out), K = L._internal.keyTarget;
  const seen = [];
  let t = K(model, null, "ArrowRight").target;
  if (t === null || t.kind !== "mark") return seen;
  for (let r = 0; r < 100; r++) {
    t = K(model, t, "Home").target;
    seen.push(t);
    for (let i = 0; i < 1000; i++) {
      const n = K(model, t, "ArrowRight").target;
      const sameRow = n.kind === "mark" && out.index.marks[at(out, n.datum)].row === out.index.marks[at(out, t.datum)].row;
      if (!sameRow || (n.kind === t.kind && n.datum === t.datum)) break;
      t = n;
      seen.push(t);
    }
    const down = K(model, t, "ArrowDown").target;
    if (down.datum === t.datum && down.id === t.id) break;
    t = down;
  }
  return seen;
}

test("A.5: ArrowRight from entering visits the first row in the list's order, then the legend entry D8 names", () => {
  const out = draw(fixture("slices"), 960, 540), model = modelOf(layer, out), K = layer._internal.keyTarget;
  const rows = ownRows(out);
  let t = K(model, null, "ArrowRight").target;
  const seen = [t];
  for (let i = 1; i < rows[0].length; i++) seen.push((t = K(model, t, "ArrowRight").target));
  assert.deepEqual(seen, rows[0].map((n) => tgt(out, n)));
  const last = out.index.marks[rows[0][rows[0].length - 1]];
  assert.deepEqual(K(model, t, "ArrowRight").target, { kind: "entry", id: last.series }, "the entry of the last mark's series");
  // from the first entry ArrowLeft reaches the last target of the first row
  assert.deepEqual(K(model, { kind: "entry", id: model.entries[0].id }, "ArrowLeft").target, tgt(out, rows[0][rows[0].length - 1]));
  // a row's first: ArrowLeft stays; Home and End
  const first = tgt(out, rows[0][0]);
  assert.deepEqual(K(model, first, "ArrowLeft").target, first);
  assert.deepEqual(K(model, first, "End").target, tgt(out, rows[0][rows[0].length - 1]));
  assert.deepEqual(K(model, tgt(out, rows[0][1]), "Home").target, first);
  // with one row Up and Down change nothing; Escape leaves
  assert.equal(rows.length, 1);
  assert.deepEqual(K(model, first, "ArrowDown").target, first);
  assert.deepEqual(K(model, first, "ArrowUp").target, first);
  assert.equal(K(model, first, "Escape").target, null);
});

/** Marks with key false are never a target: box-and-outside's walk visits its boxes only (G8's first check). */
function keyFalseCheck(L) {
  const out = draw(fixture("box-and-outside"), 960, 540);
  const seen = walkAll(L, out);
  assert.equal(seen.length, out.index.marks.filter((m) => m.shape.kind === "box").length, "targets visited: " + seen.map((t) => t.datum).join(","));
  assert.ok(seen.every((t) => t.datum.startsWith("box-")));
}

test("A.5: marks with key false are never a target", () => keyFalseCheck(layer));

/** ArrowDown and ArrowUp in cells reach the target the test's own distance rule names, across the missing cell (G8). */
function upDownCheck(L) {
  const out = draw(fixture("cells"), 960, 540), model = modelOf(L, out), K = L._internal.keyTarget;
  const rows = ownRows(out);
  assert.deepEqual(rows.map((r) => r.length), [4, 3, 4], "the middle row misses one cell");
  let moves = 0;
  rows.forEach((row, r) => {
    for (const n of row) {
      for (const [k, to] of [["ArrowDown", r + 1], ["ArrowUp", r - 1]]) {
        const got = K(model, tgt(out, n), k).target;
        if (to < 0 || to >= rows.length) {
          assert.deepEqual(got, tgt(out, n), "no such row: it stays");
          continue;
        }
        const a = out.index.marks[n].anchor;
        let pick = rows[to][0], best = Infinity;
        for (const c of rows[to]) {
          const b = out.index.marks[c].anchor, d = Math.hypot(b.x - a.x, b.y - a.y);
          if (d < best) {
            best = d;
            pick = c;
          }
        }
        assert.deepEqual(got, tgt(out, pick), `${k} from ${out.index.marks[n].datum}`);
        moves++;
      }
    }
  });
  return moves;
}

test("A.5: ArrowDown and ArrowUp reach the nearest cell of the next and previous row, across the missing cell", () => {
  const moves = upDownCheck(layer);
  assert.ok(moves >= 14, "moves: " + moves);
});

test("A.5: after a redraw without the target's mark the target is D8's", () => {
  const F = layer._internal.findAgain;
  // a slice whose series is hidden: no key target of its series, its entry is drawn
  const hidden = draw(fixture("slices"), 960, 540, { hidden: ["string:Half"] });
  assert.deepEqual(F(modelOf(layer, hidden), { kind: "mark", id: "string:Half", datum: "half" }), { kind: "entry", id: "string:Half" });
  // a still-drawn key target stays
  assert.deepEqual(F(modelOf(layer, hidden), { kind: "mark", id: "string:Small", datum: "small" }), { kind: "mark", id: "string:Small", datum: "small" });
  // a cell taken out: the first key target of the same series (a cell has none: null)
  const fx = fixture("cells");
  const i = fx.columns.id.indexOf("r1c1");
  for (const c of Object.keys(fx.columns)) fx.columns[c].splice(i, 1);
  const out = draw(fx, 960, 540);
  assert.deepEqual(F(modelOf(layer, out), { kind: "mark", id: null, datum: "r1c1" }), tgt(out, 0));
  // a point of series A gone: the first key target of A
  const pts = fixture("points");
  const j = pts.columns.id.indexOf("tie-a");
  for (const c of Object.keys(pts.columns)) pts.columns[c].splice(j, 1);
  const po = draw(pts, 960, 540);
  assert.deepEqual(F(modelOf(layer, po), { kind: "mark", id: "string:A", datum: "tie-a" }), tgt(po, po.index.marks.findIndex((m) => m.series === "string:A")));
  // a draw with nothing: none
  assert.equal(F(modelOf(layer, draw(fixture("empty"), 960, 540)), { kind: "mark", id: "string:A", datum: "tie-a" }), null);
});

test("A.5: in every fixture a walk over all rows visits each key target exactly once", () => {
  let total = 0;
  for (const name of NAMES) {
    const out = draw(fixture(name), 960, 540);
    if (!Array.isArray(out.index.marks)) continue;
    const seen = walkAll(layer, out).map((t) => JSON.stringify(t));
    const want = ownRows(out).flat().map((n) => JSON.stringify(tgt(out, n)));
    assert.equal(new Set(seen).size, seen.length, name + ": each once");
    assert.deepEqual([...seen].sort(), [...want].sort(), name);
    for (const e of EXCEPTIONS.filter((x) => x.startsWith(name + ":"))) {
      const datum = e.split(":")[1].split("->")[0];
      assert.ok(seen.some((s) => JSON.parse(s).datum === datum), `${e}: the pointer cannot reach it, the keys do`);
    }
    total += seen.length;
  }
  console.log(`A.5: ${total} key targets visited, each once`);
  assert.ok(total >= 40, "targets: " + total);
});

// ========================================================================================= A.6 the highlight

test("A.6: a series activates its group and entry; a cell without one activates only its own primitives", () => {
  const out = draw(fixture("slices"), 960, 540), env = mounted(layer, out, W960);
  const half = out.index.marks[at(out, "half")];
  pointAt(env, half.anchor.x, half.anchor.y);
  assert.ok(dimmed(env.svg));
  const act = active(env.svg);
  assert.equal(act.length, 2, "its mark group and its entry");
  assert.ok(act.every((e) => e.getAttribute("data-series") === "string:Half" || e.getAttribute("data-legend") === "string:Half"));
  cellHighlightCheck(layer);
});

function cellHighlightCheck(L) {
  const cells = draw(fixture("cells"), 960, 540), ce = mounted(L, cells, W960);
  const model = modelOf(L, cells);
  const els = descendants(ce.svg).slice(0, cells.primitives.length);
  for (const n of [0, 1]) {
    const c = cells.index.marks[n];
    pointAt(ce, c.anchor.x, c.anchor.y);
    assert.ok(cardTexts(ce.svg).length > 0, "the cell's card");
    assert.equal(dimmed(ce.svg), true, "a cell dims other marks");
    assert.deepEqual(active(ce.svg), model.held[n].map(i => els[i]), "only the selected cell's primitives");
    for (let i = 0; i < cells.primitives.length; i++) {
      const p = cells.primitives[i];
      const other = p.role === "mark" && p.k !== "group" && p.k !== "text" && !model.held[n].includes(i);
      assert.equal(/\bdp-inactive\b/.test(cls(els[i])), other, "inactive primitive " + i);
    }
    ringAround(ce, cells, n);
  }
  for (const how of ["leave", "Escape", "blur", "detach"]) {
    pointAt(ce, cells.index.marks[0].anchor.x, cells.index.marks[0].anchor.y);
    if (how === "leave") leave(ce);
    else if (how === "Escape") key(ce, "Escape");
    else if (how === "blur") ce.svg.dispatch("blur", {});
    else ce.handle.detach();
    nothingLeft(ce, how);
    assert.equal(active(ce.svg).length, 0);
    assert.ok(descendants(ce.svg).every(e => !/\bdp-inactive\b/.test(cls(e))), how + ": no dim markers left");
  }
}

function labelledCellHighlightCheck(L) {
  const fx = JSON.parse(readFileSync(path.join(ENGINE_TESTS, "fixtures/heatmap/labels-values.json"), "utf8"));
  const out = R.render({ type: "heatmap", document: fx.document, columns: fx.columns, types: fx.types,
    frame: W960, theme: "light", hidden: [], measure: null });
  const env = mounted(L, out, W960);
  const cells = descendants(env.svg).filter(e => e.tagName === "rect" && cls(e) === "dp-mark");
  assert.equal(cells.length, 35);
  assert.equal(out.index.series.length, 0);
  assert.equal(out.summary.series.length, 0);
  const owners = cells.map(e => e.parentNode);
  assert.equal(new Set(owners).size, cells.length, "one semantic group per cell");
  for (const n of [0, 1]) {
    const b = out.index.marks[n].shape;
    pointAt(env, b.x + b.w / 2, b.y + b.h / 2);
    cells.forEach((e, i) => {
      const owner = owners[i], kids = descendants(owner);
      assert.equal(owner.tagName, "g");
      assert.equal(kids.length, 2, "cell and fitted value");
      assert.equal(kids[1].tagName, "text");
      assert.equal(kids[1].textContent, out.index.marks[i].card.rows[0].value);
      assert.equal(/\bdp-active\b/.test(cls(owner)), i === n, "active cell-label group " + i);
      assert.equal(/\bdp-inactive\b/.test(cls(owner)), i !== n, "inactive cell-label group " + i);
      assert.ok(kids.every(k => !/\bdp-(active|inactive)\b/.test(cls(k))), "no second fade on a child");
    });
    ringAround(env, out, n);
    assert.ok(cardTexts(env.svg).length > 0);
    assert.ok(guides(env).every(e => !/\bdp-(active|inactive)\b/.test(cls(e))), "unrelated guides stay opaque");
  }
  for (const how of EXITS) {
    key(env, "ArrowRight");
    assert.equal(active(env.svg).length, 1);
    if (how === "leave") { const b=out.index.marks[0].shape; pointAt(env,b.x+b.w/2,b.y+b.h/2); leave(env); }
    else if (how === "Escape") key(env, "Escape");
    else if (how === "blur") env.svg.dispatch("blur", {});
    else env.handle.detach();
    assert.ok(descendants(env.svg).every(e => !/\bdp-(active|inactive|dimmed)\b/.test(cls(e))), how + ": all pairs restored");
  }
}

test("#1139: a cell and its fitted value share one fade; the leaf-only layer goes red", () => {
  const loop = '            while (owner.parentNode !== svg && classes(owner.parentNode).indexOf("dp-mark") >= 0) owner = owner.parentNode;';
  redThenGreen("cell-label ownership", labelledCellHighlightCheck, layerWith([[loop, ""]]));
});

test("A.6: ring on a point and on a box; move on a sector, on exactly the mark's elements", () => {
  const pts = draw(fixture("points"), 960, 540), pe = mounted(layer, pts, W960), n = at(pts, "tie-a");
  pointAt(pe, pts.index.marks[n].shape.x, pts.index.marks[n].shape.y);
  ringAround(pe, pts, n);
  assert.equal(moved(pe.svg).length, 0);
  const cells = draw(fixture("cells"), 960, 540), ce = mounted(layer, cells, W960);
  pointAt(ce, cells.index.marks[5].anchor.x, cells.index.marks[5].anchor.y);
  ringAround(ce, cells, 5);
  const sl = draw(fixture("slices"), 960, 540), se = mounted(layer, sl, W960);
  for (const d of ["small", "half", "rest"]) {
    const k = at(sl, d);
    pointAt(se, sl.index.marks[k].anchor.x, sl.index.marks[k].anchor.y);
    movedOnly(se, sl, k);
    assert.equal(guides(se).length, 0, "a sector has no ring");
  }
});

// The states of D7's "When a highlight goes and when it stays", each a check of a layer (G7 runs them on plants).

const STATE = {
  "(b) the keys keep theirs when the pointer leaves": (L) => {
    const out = draw(fixture("points"), 960, 540), env = mounted(L, out, W960);
    const p = out.index.marks[at(out, "xy-near")].shape;
    pointAt(env, p.x, p.y);
    key(env, "ArrowRight");
    const n = at(out, "tie-a");
    assert.deepEqual(env.handle.target(), tgt(out, n));
    const before = { card: cardTexts(env.svg), ring: ringAround(env, out, n).attrs, dimmed: dimmed(env.svg), active: active(env.svg).length };
    leave(env);
    assert.deepEqual(cardTexts(env.svg), before.card, "the keys' card");
    assert.deepEqual(ringAround(env, out, n).attrs, before.ring, "the keys' ring");
    assert.equal(dimmed(env.svg), true);
    assert.equal(active(env.svg).length, before.active);
    assert.deepEqual(before.card, ["A", "tie-a"], "the card is the keys' mark's, not the pointer's");
  },
  "(c) a redraw with the target draws exactly one fresh ring or move from the new index": (L) => {
    for (const [name, datum, emphasis] of [["points", "tie-a", "ring"], ["slices", "half", "move"]]) {
      const out = draw(fixture(name), 960, 540), env = mounted(L, out, W960);
      keyTo(env, datum);
      const next = draw(fixture(name), 480, 360), frame = { w: 480, h: 360 };
      R.writeDom(next.primitives, env.svg, "light");
      const writtenNext = descendants(env.svg).length;
      env.handle.update({ output: next, theme: "light", measure: null, frame });
      const n = at(next, datum);
      env.written = writtenNext;
      env.count = next.primitives.length;
      if (emphasis === "ring") ringAround(env, next, n);
      else movedOnly(env, next, n);
      // the added elements as after a first paint of the new draw with the same target
      const fresh = mounted(L, next, frame);
      keyTo(fresh, datum);
      assert.equal(descendants(env.svg).length - writtenNext, descendants(fresh.svg).length - fresh.written, name + ": added elements");
      assert.deepEqual(cardTexts(env.svg), cardTexts(fresh.svg), name + ": the target's card");
      assert.ok(cardTexts(env.svg).length > 0);
    }
  },
  "(d) a redraw without the target draws D8's target; with none, nothing": (L) => {
    const out = draw(fixture("slices"), 960, 540), env = mounted(L, out, W960);
    keyTo(env, "half");
    const next = draw(fixture("slices"), 960, 540, { hidden: ["string:Half"] });
    R.writeDom(next.primitives, env.svg, "light");
    env.written = descendants(env.svg).length;
    env.count = next.primitives.length;
    env.handle.update({ output: next, theme: "light", measure: null, frame: W960 });
    assert.deepEqual(env.handle.target(), { kind: "entry", id: "string:Half" });
    assert.equal(moved(env.svg).length, 0);
    assert.equal(guides(env).length, 0);
    assert.ok(dimmed(env.svg), "an entry is drawn as an entry is today");
    assert.ok(active(env.svg).some((e) => e.getAttribute("data-legend") === "string:Half"));
    const none = draw(fixture("empty"), 960, 540);
    R.writeDom(none.primitives, env.svg, "light");
    env.written = descendants(env.svg).length;
    const e2 = mounted(L, out, W960);
    key(e2, "ArrowRight");
    R.writeDom(none.primitives, e2.svg, "light");
    e2.written = descendants(e2.svg).length;
    e2.count = none.primitives.length;
    e2.handle.update({ output: none, theme: "light", measure: null, frame: W960 });
    assert.equal(e2.handle.target(), null);
    nothingLeft(e2, "a redraw with no target");
  },
  "(e) from one mark to the next leaves nothing on the first": (L) => {
    const sl = draw(fixture("slices"), 960, 540), se = mounted(L, sl, W960);
    for (const d of ["small", "half"]) pointAt(se, sl.index.marks[at(sl, d)].anchor.x, sl.index.marks[at(sl, d)].anchor.y);
    movedOnly(se, sl, at(sl, "half"));
    const pts = draw(fixture("points"), 960, 540), pe = mounted(L, pts, W960);
    for (const d of ["tie-a", "x-near"]) pointAt(pe, pts.index.marks[at(pts, d)].shape.x, pts.index.marks[at(pts, d)].shape.y);
    ringAround(pe, pts, at(pts, "x-near"));
  },
};

/** (a) nothing is left after [how]: a slice (move) and a point (ring), highlighted by the pointer or by the keys. */
function nothingAfter(how) {
  return (L) => {
    for (const [name, datum] of [["slices", "half"], ["points", "tie-a"]]) {
      const out = draw(fixture(name), 960, 540), a = out.index.marks[at(out, datum)].anchor;
      const env = mounted(L, out, W960);
      if (how === "leave") pointAt(env, a.x, a.y);
      else keyTo(env, datum);
      assert.ok(moved(env.svg).length + guides(env).length > 0, `${name} ${how}: highlighted before`);
      if (how === "leave") leave(env);
      else if (how === "Escape") key(env, "Escape");
      else if (how === "blur") env.svg.dispatch("blur", {});
      else env.handle.detach();
      nothingLeft(env, `${name} after ${how}`);
    }
  };
}
const EXITS = ["leave", "Escape", "blur", "detach"];
for (const how of EXITS) STATE[`(a) nothing is left after ${how}`] = nothingAfter(how);

for (const [name, check] of Object.entries(STATE)) test("A.6: " + name, () => check(layer));

// ================================================================================================ the gates

const G5_PLANTS = [
  ["kept row taken as the first row", [["kept: r === c.keep", "kept: r === 0"]]],
  ['"+n more" counted one too high', [["var n = fitted.length - out.length;", "var n = fitted.length - out.length + 1;"]]],
];

test("G5: the kept row: the box's keep moved in a copy of the fixture, and two plants in copies of the layer, each red", () => {
  const fx = fixture("box-and-outside");
  fx.document.config.cards["box-north"].keep = 0;
  redThenGreen("G5 keep on Highest inside (fixture copy)", (f) => keptChecks(layer, f), fx, fixture("box-and-outside"));
  for (const [name, pairs] of G5_PLANTS) redThenGreen("G5 " + name, (L) => keptChecks(L), layerWith(pairs));
});

const PLAIN_LEAD = 'var lead = line.kind === "row" && !plain ? m.key + m.gap : 0;';
const G6_PLANTS = [
  ["a key's room kept for a row without a colour", [[PLAIN_LEAD, 'var lead = line.kind === "row" ? m.key + m.gap : 0;']]],
  ["its value measured at 400", [['var weight = line.kind === "row" ? m.bold : 400;\n    var vw = width(line.value, s, weight);\n    // A row', 'var weight = line.kind === "row" && line.plain !== true ? m.bold : 400;\n    var vw = width(line.value, s, weight);\n    // A row']]],
  ["its value cut", [["    if (lead + vw > w + EPS) return { line: line, ok: false };\n    var nameWeight", "    if (lead + vw > w + EPS && plain) {\n      line = Object.assign({}, line, { value: R.measure.fit(width, line.value, s, weight, w / 2) });\n      vw = width(line.value, s, weight);\n    }\n    if (lead + vw > w + EPS) return { line: line, ok: false };\n    var nameWeight"]]],
];

test("G6: a row without a colour: three plants in copies of the layer, each red", () => {
  for (const [name, pairs] of G6_PLANTS) redThenGreen("G6 " + name, plainChecks, layerWith(pairs));
});

const G7_PLANTS = [
  ["clear no longer takes the move away", [['        el.removeAttribute("transform");\n', ""]], EXITS.map((h) => "(a) nothing is left after " + h).concat(["(e) from one mark to the next leaves nothing on the first"])],
  ["the ring not taken away before a repaint", [["        if (el.parentNode === svg) svg.removeChild(el);", '        if (el.parentNode === svg && !/dp-guide/.test(el.getAttribute("class") || "")) svg.removeChild(el);']], EXITS.map((h) => "(a) nothing is left after " + h).concat(["(e) from one mark to the next leaves nothing on the first"])],
  ["the repaint dropped after update", [['        if (st.last === "key" && st.target === null) st.last = null;\n        paint();', '        if (st.last === "key" && st.target === null) st.last = null;']], ["(c) a redraw with the target draws exactly one fresh ring or move from the new index"]],
  ["the keys' ownership cleared by a pointer leave", [['      if (st.last === "pointer") st.last = null;', "      st.last = null;"]], ["(b) the keys keep theirs when the pointer leaves"]],
];

test("G7: the highlight leaves and stays: plants in copies of the layer, each red on its states", () => {
  for (const [name, pairs, states] of G7_PLANTS) {
    const L = layerWith(pairs);
    for (const s of states) redThenGreen(`G7 ${name} → ${s.split(" ").slice(0, 1).concat(s.startsWith("(a)") ? [s.split(" ").pop()] : []).join(" ")}`, STATE[s], L);
  }
  const dims = layerWith([["          if (mk.series === null) activeMark = model.held[n];", ""]]);
  redThenGreen("G7 missing dimming for a mark without a series", cellHighlightCheck, dims);
});

test("G8: the keys: key false ignored; ArrowDown to the same position in the row; each red", () => {
  redThenGreen("G8 key false ignored", keyFalseCheck, layerWith([["if (model.marks[i].key === true) out.push(i);", "out.push(i);"]]));
  redThenGreen("G8 ArrowDown to the same position", upDownCheck, layerWith([["      out.target = markTarget(model, pick);", "      out.target = markTarget(model, next[i] === undefined ? n : next[i]);"]]));
});

test("G9: the test for a draw by mark made true for every draw turns chart-interactions.test.mjs red", () => {
  const plant = [["    if (Array.isArray(output.index.marks)) byMark(model, output.index.marks, prims);", "    byMark(model, Array.isArray(output.index.marks) ? output.index.marks : [], prims);"]];
  const run = (text) => {
    const root = mkdtempSync(path.join(os.tmpdir(), "chart-marks-g9-"));
    try {
      const tests = path.join(root, "modules/web/src/test/js");
      const js = path.join(root, "modules/web/src/main/resources/static/js");
      mkdirSync(path.join(tests, "support"), { recursive: true });
      mkdirSync(js, { recursive: true });
      copyFileSync(path.resolve(STATIC_JS, "../../../../test/js/chart-interactions.test.mjs"), path.join(tests, "chart-interactions.test.mjs"));
      copyFileSync(path.resolve(STATIC_JS, "../../../../test/js/support/js-rendered.mjs"), path.join(tests, "support/js-rendered.mjs"));
      writeFileSync(path.join(js, "datapipelines-chart-interactions.js"), text);
      symlinkSync(path.resolve(ENGINE_DIR, "../../../../../../.."), path.join(root, "modules/js-rendered"));
      const env = Object.assign({}, process.env);
      delete env.NODE_TEST_CONTEXT;
      const r = spawnSync(process.execPath, ["--test", "--test-reporter=spec", path.join(tests, "chart-interactions.test.mjs")], { encoding: "utf8", env, timeout: 300000 });
      const out = r.stdout + r.stderr;
      const count = (k) => Number((new RegExp("ℹ " + k + " (\\d+)").exec(out) || [0, -1])[1]);
      const names = [...new Set([...out.matchAll(/^✖ (.+?) \(\d/gm)].map((m) => m[1]))];
      return { status: r.status, tests: count("tests"), fail: count("fail"), names };
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  };
  let text = LAYER_TEXT;
  for (const [a, b] of plant) {
    assert.equal(text.split(a).length, 2);
    text = text.split(a).join(b);
  }
  const red = run(text);
  console.log(`G9 red: ${red.fail} of ${red.tests} tests of chart-interactions.test.mjs fail; first: ${red.names.slice(0, 3).join(" | ")}`);
  assert.equal(red.tests, 43);
  assert.ok(red.fail > 0 && red.status !== 0, "the plant must turn the old tests red");
  const green = run(LAYER_TEXT);
  assert.deepEqual([green.status, green.tests, green.fail], [0, 43, 0], "the same copy without the plant is green");
});

// The look fields are read (A.9's Node half; G13): each field changed in the look table changes what it governs.

function withLook(name, value, fn) {
  const orig = R.tables.look;
  R.tables.look = (n) => (n === name ? value : orig(n));
  try {
    return fn();
  } finally {
    R.tables.look = orig;
  }
}

const FIELDS = {
  markReach: (L) => {
    const pts = draw(fixture("points"), 960, 540), big = pts.index.marks[at(pts, "big")].shape, place = { x: big.x + 30, y: big.y };
    assert.equal(L._internal.lookup(R, modelOf(L, pts), place).mark, null, "30 px at the default reach");
    withLook("markReach", 40, () => assert.deepEqual(L._internal.lookup(R, modelOf(L, pts), place).mark, { id: "string:A", datum: "big" }, "30 px at a reach of 40"));
  },
  markMove: (L) => {
    const sl = draw(fixture("slices"), 960, 540), k = at(sl, "half"), a = sl.index.marks[k].anchor;
    const env = mounted(L, sl, W960);
    pointAt(env, a.x, a.y);
    movedOnly(env, sl, k, 5);
    withLook("markMove", 10, () => {
      const e2 = mounted(L, sl, W960);
      pointAt(e2, a.x, a.y);
      movedOnly(e2, sl, k, 10);
    });
  },
  markRing: (L) => {
    const pts = draw(fixture("points"), 960, 540), n = at(pts, "tie-a"), p = pts.index.marks[n].shape;
    const env = mounted(L, pts, W960);
    pointAt(env, p.x, p.y);
    const r0 = Number(ringAround(env, pts, n).getAttribute("r"));
    withLook("markRing", 1, () => {
      const e2 = mounted(L, pts, W960);
      pointAt(e2, p.x, p.y);
      const r1 = Number(ringAround(e2, pts, n, 1).getAttribute("r"));
      assert.equal(r1 - r0, 0.75 * S, "the ring grows by 0.75 × s");
    });
  },
};

const G13_PLANTS = {
  markReach: [['R.tables.look("markReach")', "24"]],
  markMove: [['R.tables.look("markMove")', "5"]],
  markRing: [['R.tables.look("markRing")', "0.25"]],
};

for (const [name, check] of Object.entries(FIELDS)) {
  test(`A.9 (Node) and G13: ${name} changed in the look table changes what it governs; read as a constant it does not`, () => {
    redThenGreen("G13 " + name + " as a constant", check, layerWith(G13_PLANTS[name]));
  });
}
