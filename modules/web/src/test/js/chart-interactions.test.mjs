// The chart interaction layer's pure half (brief 990 A.1; design record 2026-11-14 §4.8; look contract §4.4, L8): the
// nearest mark for a pointer, a bar's group and stack, the crosshair's rows, the card that shows what fits (D2a, LA13's
// unit cases (a) to (f)), the cut texts and their card (D3a), the card's place, and the keyboard's next target (D5). The
// engine is loaded from its module's resources as the page loads it; every expected W, w and k is computed HERE from the
// look table's fields, never by the function under test. The DOM half (attach, update, detach) runs on the fake browser
// at the end; the browser's own half is the browser suite's.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { loadEngine, fakeBrowser, descendants, STATIC_JS, ENGINE_DIR } from "./support/js-rendered.mjs";

const require = createRequire(import.meta.url);
const R = loadEngine();
globalThis.JsRendered = R;
const layer = require(path.join(STATIC_JS, "datapipelines-chart-interactions.js"));
const I = layer._internal;
const FIXTURES = path.resolve(ENGINE_DIR, "../../../../../../test/fixtures");

// ------------------------------------------------------------------------------------------------ helpers

const look = R.tables.look;
const S = R.theme.resolve("light").n("--chart-text");
/** The card's measures as the contract states them (§4.4), computed here from the look table's fields. */
const H = look("tipLine") * S;
const P = look("tipPad") * S;
const KEY = look("tipKey") * S;
const GAP = look("tipGap") * S;
const G = look("tipMax") * S;
const DIST = look("tipDistance");

function expectBounds(frame) {
  const W = Math.min(frame.w, G);
  const w = W - 2 * P;
  const k = frame.h - 2 * P < H ? 0 : Math.floor((frame.h - 2 * P) / H + 1e-9);
  return { W, w, k };
}

/** A measurer of fixed widths: every character half the size, unless [wide] names its own share of the size. */
function fixed(wide = {}) {
  return (text, size) => [...text].reduce((sum, ch) => sum + (wide[ch] === undefined ? 0.5 : wide[ch]) * size, 0);
}
const HALF = fixed();
const M = I.measures(R, "light");

const heading = (text) => ({ kind: "heading", text });
const row = (id, name, value) => ({ kind: "row", id, name, value, colour: "--chart-series-1", color: "#000000", bold: false });
const field = (title, value) => ({ kind: "field", title, value });
const kinds = (card) => card.lines.map((l) => l.line.kind);

function inputOf(type, document, columns, types, frame, extra = {}) {
  return Object.assign({ type, document, columns, types, frame, theme: "light", measure: null, hidden: [] }, extra);
}

function fixture(dir, name) {
  return JSON.parse(readFileSync(path.join(FIXTURES, dir, name + ".json"), "utf8"));
}

function renderFixture(dir, name, frame, extra = {}) {
  const fx = fixture(dir, name);
  return R.render(inputOf(fx.document.config.mark.type, fx.document, fx.columns, fx.types, frame, extra));
}

const ENC = { x: { field: "x", type: "nominal", sort: "ascending" }, y: { field: "v", type: "quantitative", aggregate: "sum" }, color: { field: "s", type: "nominal", sort: null } };
const TYPES = { x: "STRING", s: "STRING", v: "DECIMAL" };

/** A chart of [type] whose rows are [[x, series, value]]. */
function chart(type, rows, frame, extra = {}) {
  const columns = { x: rows.map((r) => r[0]), s: rows.map((r) => r[1]), v: rows.map((r) => (r[2] === null ? null : String(r[2]))) };
  const document = { display_name: "Interactions", config: { mark: { type }, encoding: extra.encoding || ENC } };
  return R.render(inputOf(type, document, columns, TYPES, frame, extra.input || {}));
}

const WIDTH = R.measure.measurer(null).fn;
const model = (out, width = WIDTH) => {
  const m = I.modelOf(R, out, width);
  m.theme = M.theme;
  return m;
};

/** A.1's sparse line: x 0 to 3; A drawn at 0 and 1 only, B at 2 and 3 only. */
const SPARSE = [["x0", "A", 1], ["x1", "A", 2], ["x2", "B", 3], ["x3", "B", 4]];
const A = "string:A";
const B = "string:B";
const mark = (id, datum) => ({ kind: "mark", id, datum });

// ------------------------------------------------------------------------------------------- the measures

test("the default tokens give 2p + h no more than 24, and the one-row card is drawn at 80 × 24 (LA13)", () => {
  assert.ok(2 * P + H <= 24, `2p + h = ${2 * P + H}`);
  const frame = { w: 80, h: 24 };
  const b = expectBounds(frame);
  assert.equal(b.k, 1);
  const card = I.markCard(R, WIDTH, [heading("x0"), row(A, "A", "1"), row(B, "B", "2")], A, frame, M);
  assert.ok(card !== null);
  assert.deepEqual(kinds(card), ["row"]);
  assert.equal(card.lines[0].line.id, A);
  assert.ok(Math.abs(card.box.h - (2 * P + H)) < 1e-9, `card height ${card.box.h}`);
  assert.ok(card.box.h <= 24);
});

test("the layer's measures are the look table's interaction fields × --chart-text, read by name", () => {
  assert.equal(M.h, H);
  assert.equal(M.p, P);
  assert.equal(M.key, KEY);
  assert.equal(M.gap, GAP);
  assert.equal(M.G, G);
  assert.equal(M.dist, DIST);
  assert.equal(M.bold, look("tipValueWeight"));
});

// ---------------------------------------------------------------------- D2a: a mark's card (LA13 unit half)

/** Asserts a card: its box inside the frame at every anchor, every key and text inside the content area. */
function assertInside(card, frame, width = HALF, textual = false) {
  const anchors = [{ x: 0, y: 0 }, { x: frame.w, y: 0 }, { x: 0, y: frame.h }, { x: frame.w, y: frame.h }, { x: frame.w / 2, y: frame.h / 2 }];
  for (const anchor of anchors) {
    const at = I.place(card.box, anchor, frame, DIST);
    const where = `anchor ${anchor.x},${anchor.y}`;
    assert.ok(at.x >= -1e-9 && at.x + card.box.w <= frame.w + 1e-9, `${where}: box x ${at.x} + ${card.box.w} in ${frame.w}`);
    assert.ok(at.y >= -1e-9 && at.y + card.box.h <= frame.h + 1e-9, `${where}: box y ${at.y} + ${card.box.h} in ${frame.h}`);
    const prims = I.cardPrims(R, card, at, M, textual);
    const left = at.x + P - 0.01;
    const right = at.x + card.box.w - P + 0.01;
    for (const p of prims) {
      if (p.k === "text") {
        const tw = width(p.text, p.size, p.weight);
        const x0 = p.anchor === "end" ? p.x - tw : p.x;
        assert.ok(x0 >= left && x0 + tw <= right, `${where}: text "${p.text}" ${x0}..${x0 + tw} outside ${left}..${right}`);
        assert.ok(p.y > at.y + P - 0.01 && p.y < at.y + card.box.h - P + 0.01, `${where}: text "${p.text}" baseline ${p.y}`);
      } else if (p.k === "rect" && p.series !== null) {
        assert.ok(p.x >= left && p.x + p.w <= right && p.y >= at.y + P - 0.01 && p.y + p.h <= at.y + card.box.h - P + 0.01, `${where}: a key outside`);
      }
    }
  }
}

test("D2a (a): every line fits: the full card in its own order", () => {
  const frame = { w: 300, h: 200 };
  const lines = [heading("Q1"), row(A, "North", "12"), row(B, "South", "7"), field("Region", "East")];
  const card = I.markCard(R, HALF, lines, A, frame, M);
  assert.deepEqual(kinds(card), ["heading", "row", "row", "field"]);
  assert.deepEqual(card.lines.map((l) => l.text || l.label), ["Q1", "North", "South", "Region"]);
  assertInside(card, frame);
});

test("D2a (b): more lines than k with k of 3 or more: the heading, the hovered row FIRST, the others in order, then +n more", () => {
  const rows = ["A", "B", "C", "D", "E", "F", "G", "H"].map((n, i) => row("string:" + n, n, String(i + 1)));
  const fields = [1, 2, 3, 4, 5, 6].map((i) => field("f" + i, "v" + i));
  const lines = [heading("Q1")].concat(rows, fields);
  const frame = { w: 300, h: 90 };
  const { k } = expectBounds(frame);
  assert.equal(k, 5);
  const card = I.markCard(R, HALF, lines, "string:E", frame, M);
  // the test's own count: heading + hovered + (k - 3) others are shown, the last line is the count.
  const shown = 1 + 1 + (k - 3);
  const n = lines.length - shown;
  assert.deepEqual(kinds(card), ["heading", "row", "row", "row", "more"]);
  assert.equal(card.lines[1].line.id, "string:E");
  assert.deepEqual(card.lines.slice(2, 4).map((l) => l.line.id), ["string:A", "string:B"]);
  assert.equal(card.lines[4].text, "+" + n + " more");
  assert.equal(n, 11);
  assertInside(card, frame);
});

test("D2a (c): k of 2, 1 and 0, and the three ways to a k of 0", () => {
  const lines = [heading("Q1"), row(A, "A", "1"), row(B, "B", "2"), row("string:C", "C", "3")];
  const two = { w: 300, h: 2 * P + 2 * H + 1 };
  assert.equal(expectBounds(two).k, 2);
  const c2 = I.markCard(R, HALF, lines, B, two, M);
  assert.deepEqual(kinds(c2), ["heading", "row"]);
  assert.equal(c2.lines[1].line.id, B);
  assertInside(c2, two);
  const one = { w: 300, h: 2 * P + H + 1 };
  assert.equal(expectBounds(one).k, 1);
  const c1 = I.markCard(R, HALF, lines, B, one, M);
  assert.deepEqual(kinds(c1), ["row"]);
  assert.equal(c1.lines[0].line.id, B);
  assertInside(c1, one);
  const zero = [
    ["2p more than the height", { w: 300, h: 2 * P - 1 }],
    ["2p equal to the height", { w: 300, h: 2 * P }],
    ["the padding but no line", { w: 300, h: 2 * P + H - 1 }],
  ];
  for (const [why, frame] of zero) {
    assert.equal(expectBounds(frame).k, 0, why);
    assert.equal(I.markCard(R, HALF, lines, B, frame, M), null, why);
  }
  assert.equal(I.markCard(R, HALF, lines, B, { w: 2 * P, h: 300 }, M), null, "w of 0");
});

test("D2a (d): a key, gap and value that fit W but not w: another series' row left out and counted; the hovered one's: no card", () => {
  const value = "123456789";
  const need = KEY + GAP + HALF(value, S);
  const frame = { w: Math.ceil(need + P), h: 90 };
  const { W, w, k } = expectBounds(frame);
  assert.ok(need <= W && need > w && k >= 3, `need ${need}, W ${W}, w ${w}`);
  const lines = [heading("Q1"), row(A, "A", "1"), row(B, "B", value)];
  const card = I.markCard(R, HALF, lines, A, frame, M);
  assert.deepEqual(kinds(card), ["heading", "row", "more"]);
  assert.equal(card.lines[2].text, "+1 more");
  assertInside(card, frame);
  assert.equal(I.markCard(R, HALF, lines, B, frame, M), null);
});

test("D2a (e): a name with room for the ellipsis only, and one with none", () => {
  const value = "12";
  const ell = HALF(R.measure.ELLIPSIS, S);
  // room for the name = w - key - gap - value - gap; the frame is chosen so the room is the ellipsis and a little.
  const room = (frame) => expectBounds(frame).w - KEY - GAP - HALF(value, S) - GAP;
  const only = { w: 2 * P + KEY + GAP + HALF(value, S) + GAP + ell + 2, h: 100 };
  assert.ok(room(only) >= ell && room(only) < HALF("N" + R.measure.ELLIPSIS, S));
  const c1 = I.markCard(R, HALF, [heading("Q"), row(A, "North", value)], A, only, M);
  assert.equal(c1.lines[1].label, R.measure.ELLIPSIS);
  assertInside(c1, only);
  const none = { w: 2 * P + KEY + GAP + HALF(value, S) + GAP + ell - 3, h: 100 };
  assert.ok(room(none) < ell);
  const c2 = I.markCard(R, HALF, [heading("Q"), row(A, "North", value)], A, none, M);
  assert.equal(c2.lines[1].label, "");
  assert.equal(c2.lines[1].line.value, value);
  assertInside(c2, none);
});

test("D2a (f): a heading cut; a field's title cut; a field with no room for the ellipsis; +n more and +n wider than w", () => {
  const frame = { w: 100, h: 300 };
  const { w } = expectBounds(frame);
  const long = "A heading far longer than the card is wide";
  const c = I.markCard(R, HALF, [heading(long), row(A, "A", "1"), field("A long field title", "9")], A, frame, M);
  assert.equal(c.lines[0].text, R.measure.fit(HALF, long, S, 400, w));
  assert.ok(c.lines[0].text.endsWith(R.measure.ELLIPSIS) && c.lines[0].text.length < long.length);
  assert.equal(c.lines[2].label, R.measure.fit(HALF, "A long field title", S, 400, w - HALF("9", S) - GAP));
  assert.ok(c.lines[2].label.endsWith(R.measure.ELLIPSIS));
  assertInside(c, frame);
  // A field whose value leaves no room for its title's ellipsis cannot be shown: left out and counted.
  const tight = { w: 100, h: 300 };
  const bigValue = "x".repeat(Math.floor((w - GAP) / HALF("x", S)));
  const c2 = I.markCard(R, HALF, [heading("Q"), row(A, "A", "1"), field("Title", bigValue)], A, tight, M);
  assert.deepEqual(kinds(c2), ["heading", "row", "more"]);
  assert.equal(c2.lines[2].text, "+1 more");
  // "+n more" wider than w: written "+n".
  const narrow = { w: Math.ceil(2 * P + KEY + GAP + HALF("1", S) + 4), h: 300 };
  const nw = expectBounds(narrow).w;
  assert.ok(HALF("+1 more", S) > nw && HALF("+1", S) <= nw);
  const c3 = I.markCard(R, HALF, [heading("Q"), row(A, "A", "1"), row(B, "B", "12345678")], A, narrow, M);
  assert.equal(c3.lines[c3.lines.length - 1].text, "+1");
  assertInside(c3, narrow);
  // "+n" wider too (a measurer with a wide "+"): the card is the one for a k of 2.
  const plusWide = fixed({ "+": 4 });
  assert.ok(plusWide("+1", S) > nw);
  const c4 = I.markCard(R, plusWide, [heading("Q"), row(A, "A", "1"), row(B, "B", "12345678")], A, narrow, M);
  assert.deepEqual(kinds(c4), ["heading", "row"]);
  assertInside(c4, narrow, plusWide);
});

// -------------------------------------------------------------------------------------- D3a: the text card

test("D3a: a cut text's card: fits; more wrapped lines than k; a word wider than w; guards", () => {
  const big = { w: 300, h: 100 };
  assert.deepEqual(I.textCard(R, HALF, "hello world", big, M).lines, ["hello world"]);
  assertInside(I.textCard(R, HALF, "hello world", big, M), big, HALF, true);
  // each four-letter word fills a line; k of 2: the second line cut with the ellipsis.
  const four = { w: 2 * P + HALF("aaaa", S), h: 2 * P + 2 * H + 1 };
  assert.equal(expectBounds(four).k, 2);
  const cut = I.textCard(R, HALF, "aaaa bbbb cccc dddd", four, M);
  assert.deepEqual(cut.lines, ["aaaa", "bbb" + R.measure.ELLIPSIS]);
  assertInside(cut, four, HALF, true);
  // an unbroken word wider than w is broken where its line is full.
  const tall = { w: 2 * P + HALF("abcd", S), h: 200 };
  assert.deepEqual(I.textCard(R, HALF, "abcdefghij", tall, M).lines, ["abcd", "efgh", "ij"]);
  // a w narrower than one character: no card, and no wrapping is tried.
  let calls = 0;
  const counting = (t, s) => (calls++, HALF(t, s));
  assert.equal(I.textCard(R, counting, "abc", { w: 2 * P + HALF("a", S) - 1, h: 200 }, M), null);
  assert.ok(calls <= 3, `measured ${calls} times: the guard measures each character once and wraps nothing`);
  // a one-character text that fits where the ellipsis would not: shown whole.
  const wideEllipsis = fixed({ [R.measure.ELLIPSIS]: 2 });
  const oneChar = { w: 2 * P + HALF("x", S) + 1, h: 100 };
  assert.deepEqual(I.textCard(R, wideEllipsis, "x", oneChar, M).lines, ["x"]);
  // more lines than k, and no room for one character with the ellipsis: no card.
  const kOne = { w: 2 * P + HALF("x", S) + 1, h: 2 * P + H + 1 };
  assert.equal(I.textCard(R, HALF, "xx xx", kOne, M), null);
  // the guards of every card: k of 0 or w of 0.
  assert.equal(I.textCard(R, HALF, "hello", { w: 300, h: 2 * P }, M), null);
  assert.equal(I.textCard(R, HALF, "hello", { w: 2 * P, h: 300 }, M), null);
});

test("D3a: which texts of an output were cut is read from its primitives; a text not cut is none", () => {
  const out = renderFixture("bar", "look-long-subtitle", { w: 360, h: 260 });
  const cuts = model(out).cuts;
  const fromPrims = out.primitives.filter((p) => p.k === "text" && p.shown !== null);
  assert.ok(fromPrims.length > 0, "the fixture cuts a text at this frame");
  assert.deepEqual(cuts.map((c) => c.text), fromPrims.map((p) => p.text));
  for (const c of cuts) assert.notEqual(c.text, out.primitives[c.i].shown);
  const plain = chart("bar", [["a", "P", 1], ["b", "P", 2]], { w: 600, h: 400 });
  assert.equal(model(plain).cuts.length, 0);
});

// ------------------------------------------------------------------------------------------------ the place

test("the card's place: 14 px from the anchor, flipped at the frame's edges, moved inside when neither side has room", () => {
  const frame = { w: 200, h: 100 };
  const box = { w: 50, h: 30 };
  assert.deepEqual(I.place(box, { x: 20, y: 60 }, frame, DIST), { x: 20 + DIST, y: 60 - DIST - 30 });
  assert.deepEqual(I.place(box, { x: 190, y: 60 }, frame, DIST), { x: 190 - DIST - 50, y: 60 - DIST - 30 });
  assert.deepEqual(I.place(box, { x: 20, y: 5 }, frame, DIST), { x: 20 + DIST, y: 5 + DIST });
  // at the minimal level's frame, neither side has room at 14 px: moved until inside, over the anchor.
  const minimal = { w: 80, h: 24 };
  const one = { w: 40, h: 2 * P + H };
  for (const anchor of [{ x: 0, y: 0 }, { x: 80, y: 0 }, { x: 0, y: 24 }, { x: 80, y: 24 }, { x: 40, y: 12 }]) {
    const at = I.place(one, anchor, minimal, DIST);
    assert.ok(at.x >= 0 && at.x + one.w <= 80 && at.y >= 0 && at.y + one.h <= 24 + 1e-9, JSON.stringify([anchor, at]));
  }
});

// ------------------------------------------------------------------------------------- the pointer's lookup

test("the nearest mark: between two marks, at the frame's edges, outside the plot nothing", () => {
  const out = chart("line", [["a", "P", 1], ["b", "P", 2], ["c", "P", 3]], { w: 600, h: 400 });
  const m = model(out);
  const at = out.index.series[0].at;
  const plot = out.index.plot;
  const y = plot.y + plot.h / 2;
  assert.equal(I.lookup(R, m, { x: at[0] + (at[1] - at[0]) * 0.4, y }).mark.datum, 0);
  assert.equal(I.lookup(R, m, { x: at[0] + (at[1] - at[0]) * 0.6, y }).mark.datum, 1);
  assert.equal(I.lookup(R, m, { x: plot.x, y }).mark.datum, 0);
  assert.equal(I.lookup(R, m, { x: plot.x + plot.w, y }).mark.datum, 2);
  assert.equal(I.lookup(R, m, { x: 1, y: 1 }).mark, null);
});

test("a bar's group: the hovered bar's card holds every series of its category, the hovered one bold", () => {
  const out = chart("bar", [["a", "P", 1], ["a", "Q", 2], ["a", "R", 3], ["b", "P", 4], ["b", "Q", 5], ["b", "R", 6]], { w: 600, h: 400 });
  const m = model(out);
  const q = out.index.series[1];
  const hit = I.lookup(R, m, { x: q.at[1], y: out.index.plot.y + out.index.plot.h - 2 });
  assert.deepEqual(hit.mark, { id: "string:Q", datum: 1 });
  const lines = I.cardLines(m, "string:Q", 1);
  assert.deepEqual(lines.map((l) => l.kind), ["heading", "row", "row", "row"]);
  assert.equal(lines[0].text, "b");
  assert.deepEqual(lines.slice(1).map((l) => [l.name, l.value, l.bold]), [["P", "4", false], ["Q", "5", true], ["R", "6", false]]);
});

test("a bar's stack: the segment under the pointer is the hovered one; the card lists the top segment first", () => {
  const out = renderFixture("bar", "parity-bar-stacked", { w: 600, h: 400 });
  const m = model(out);
  const ix = out.index.series.filter((s) => s.on && s.at.length > 0);
  assert.ok(ix.length >= 2);
  const datum = ix[0].datum[0];
  for (const s of ix) {
    const box = m.bars[s.id + "\u0000" + datum];
    const pos = out.index.axis === "y" ? { x: box.x + box.w / 2, y: box.y + box.h / 2 } : { x: box.x + box.w / 2, y: box.y + box.h / 2 };
    assert.deepEqual(I.lookup(R, m, pos).mark, { id: s.id, datum }, s.id);
  }
  const lines = I.cardLines(m, ix[0].id, datum).filter((l) => l.kind === "row");
  const tops = lines.map((l) => m.bars[l.id + "\u0000" + datum]);
  for (let i = 1; i < tops.length; i++) {
    if (out.index.axis === "x") assert.ok(tops[i - 1].y <= tops[i].y, "top segment first");
    else assert.ok(tops[i - 1].x + tops[i - 1].w >= tops[i].x + tops[i].w, "outer segment first");
  }
});

// Brief 990 r2 D2: a stacked bar's heading is the category, the look table's join, then the engine's total text.
const JOIN = look("tipTotalJoin");

test("r2 D2: a stacked bar's card heads with the category, the join and the stack's total; tip keeps the category", () => {
  assert.equal(JOIN, " · ", "the join is the look table's interaction field, a middle dot with its spaces");
  const out = renderFixture("bar", "parity-bar-stacked", { w: 600, h: 400 });
  const m = model(out);
  const on = out.index.series.filter((s) => s.on && s.at.length > 0);
  let cards = 0;
  for (const s of on) {
    for (let k = 0; k < s.datum.length; k++) {
      const datum = s.datum[k];
      const category = s.tip[k][0];
      const total = out.index.totals[datum];
      assert.equal(typeof total, "string", "the engine gives this stack a total");
      assert.ok(!category.includes(total) || category === total, "tip[k][0] stays the category: " + category);
      const lines = I.cardLines(m, s.id, datum);
      assert.equal(lines[0].text, category, "the heading line's text is the category");
      const card = I.markCard(R, WIDTH, lines, s.id, { w: 600, h: 400 }, M);
      assert.equal(card.lines[0].line.kind, "heading");
      assert.equal(card.lines[0].text, category + JOIN + total, "the heading carries the stack's total (index.totals) after the category");
      cards++;
    }
  }
  assert.ok(cards >= 6, "stacked cards read: " + cards);
});

test("r2 D2: a grouped bar's heading is the category alone (no totals member)", () => {
  const out = chart("bar", [["a", "P", 1], ["a", "Q", 2], ["b", "P", 4], ["b", "Q", 5]], { w: 600, h: 400 });
  assert.equal(Object.prototype.hasOwnProperty.call(out.index, "totals"), false);
  const m = model(out);
  const lines = I.cardLines(m, "string:Q", 1);
  assert.equal(lines[0].total, null);
  const card = I.markCard(R, WIDTH, lines, "string:Q", { w: 600, h: 400 }, M);
  assert.equal(card.lines[0].text, "b");
});

test("r2 D2: the total is shown whole or not at all: too wide for w, the heading falls back to the category, then cut", () => {
  const cat = "Category";
  const total = "3,702 kg";
  const lines = [{ kind: "heading", text: cat, total }, row(A, "A", "1")];
  const joinedW = HALF(cat + JOIN + total, S);
  const catW = HALF(cat, S);
  const ell = HALF("Cat" + R.measure.ELLIPSIS, S);
  // Three widths computed here: the joined heading fits exactly; one px less; the category alone one px less.
  const at = (w) => I.markCard(R, HALF, lines, A, { w: w + 2 * P, h: 200 }, M);
  assert.equal(at(joinedW).lines[0].text, cat + JOIN + total, "w = the joined width: whole");
  const fallback = at(joinedW - 1);
  assert.equal(fallback.lines[0].text, cat, "w one px under the joined width: the category alone, never a cut total");
  assert.equal(fallback.lines.length, 2);
  const cut = at(catW - 1).lines[0].text;
  assert.ok(cut.endsWith(R.measure.ELLIPSIS) && !cut.includes("3,702"), "narrower than the category: the category cut (L8), no total: " + cut);
  assert.equal(at(ell).lines[0].text, "Cat" + R.measure.ELLIPSIS);
});

test("the crosshair's rows: one series hidden, and a null at that x, are no rows", () => {
  const rows = [["a", "P", 1], ["a", "Q", 2], ["a", "R", 3], ["b", "P", 4], ["b", "R", 6], ["c", "P", 7], ["c", "Q", 8], ["c", "R", 9]];
  const out = chart("line", rows, { w: 600, h: 400 }, { input: { hidden: ["string:R"] } });
  assert.equal(out.summary.series.find((s) => s.id === "string:R").hidden, true);
  const m = model(out);
  assert.deepEqual(I.cardLines(m, "string:P", 0).filter((l) => l.kind === "row").map((l) => l.name), ["P", "Q"]);
  assert.deepEqual(I.cardLines(m, "string:P", 1).filter((l) => l.kind === "row").map((l) => l.name), ["P"], "Q is null at b, R hidden");
  const plot = out.index.plot;
  const x = out.index.series[0].at[1];
  const sc = I.scene(R, m, { frame: { w: 600, h: 400 } }, M, WIDTH, { last: "key", target: mark("string:P", 1), hit: null, pointer: null });
  const guide = sc.prims.filter((p) => p.role === "guide");
  assert.equal(guide.filter((p) => p.k === "line").length, 1);
  assert.equal(guide.find((p) => p.k === "line").x1, x);
  assert.equal(guide.find((p) => p.k === "line").y1, plot.y);
  assert.deepEqual(guide.filter((p) => p.k === "circle").map((p) => p.series), ["string:P"]);
});

test("under the crosshair the pointer's series is the one whose point is nearest, the first in the data's order on a tie", () => {
  const out = chart("line", [["a", "P", 1], ["a", "Q", 5], ["b", "P", 1], ["b", "Q", 5]], { w: 600, h: 400 });
  const m = model(out);
  const x = out.index.series[0].at[0];
  const yP = I.anchorOf(m, "string:P", 0).y;
  const yQ = I.anchorOf(m, "string:Q", 0).y;
  assert.equal(I.lookup(R, m, { x, y: yP - 1 }).mark.id, "string:P");
  assert.equal(I.lookup(R, m, { x, y: yQ + 1 }).mark.id, "string:Q");
  assert.equal(I.lookup(R, m, { x, y: (yP + yQ) / 2 }).mark.id, "string:P");
  const same = chart("line", [["a", "P", 3], ["a", "Q", 3], ["b", "P", 3], ["b", "Q", 3]], { w: 600, h: 400 });
  const ms = model(same);
  assert.equal(I.lookup(R, ms, { x: same.index.series[0].at[0], y: I.anchorOf(ms, "string:Q", 0).y }).mark.id, "string:P");
});

test("which input decides: after a key the target's card at the target's mark, whatever the pointer's last place", () => {
  const frame = { w: 80, h: 24 };
  const out = chart("line", SPARSE, frame);
  const m = model(out);
  const draw = { frame };
  const pointerOnA = { x: out.index.series[0].at[0], y: I.anchorOf(m, A, 0).y };
  const hit = I.lookup(R, m, pointerOnA);
  assert.equal(hit.mark.id, A);
  for (const pointer of [pointerOnA, null]) {
    const sc = I.scene(R, m, draw, M, WIDTH, { last: "key", target: mark(B, 2), hit: pointer ? hit : null, pointer });
    const texts = sc.prims.filter((p) => p.role === "tooltip" && p.k === "text").map((p) => p.text);
    assert.ok(texts.includes("3"), "B's value: " + texts.join("|"));
    assert.equal(sc.active, B);
  }
  const byPointer = I.scene(R, m, draw, M, WIDTH, { last: "pointer", target: mark(B, 2), hit, pointer: pointerOnA });
  assert.equal(byPointer.active, A);
  assert.ok(byPointer.prims.some((p) => p.role === "focus"), "a pointer move keeps the focus ring");
});

// ---------------------------------------------------------------------------------------------- D5: the keys

/** Every target reachable from no target by the six moving keys (the D5 graph), as "id@datum" strings. */
function reachable(m) {
  const seen = new Map();
  const queue = [null];
  const keyOf = (t) => (t === null ? "none" : t.kind === "mark" ? t.id + "@" + t.datum : t.kind + ":" + (t.id || t.n));
  while (queue.length > 0) {
    const t = queue.shift();
    for (const key of ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End"]) {
      const next = I.keyTarget(m, t, key).target;
      if (next !== null && !seen.has(keyOf(next))) {
        seen.set(keyOf(next), next);
        queue.push(next);
      }
    }
  }
  return [...seen.values()];
}

test("D5: the sparse line at 80 × 24: one result for each key at each of its four marks, all four reached, no legend", () => {
  const out = chart("line", SPARSE, { w: 80, h: 24 });
  assert.equal(out.level, "minimal");
  const m = model(out);
  assert.equal(m.entries.length, 0, "no legend is drawn at the minimal level");
  const table = {
    [A + "@0"]: { ArrowLeft: [A, 0], ArrowRight: [A, 1], ArrowUp: [A, 0], ArrowDown: [B, 2], Home: [A, 0], End: [A, 1] },
    [A + "@1"]: { ArrowLeft: [A, 0], ArrowRight: [A, 1], ArrowUp: [A, 1], ArrowDown: [B, 2], Home: [A, 0], End: [A, 1] },
    [B + "@2"]: { ArrowLeft: [B, 2], ArrowRight: [B, 3], ArrowUp: [A, 1], ArrowDown: [B, 2], Home: [B, 2], End: [B, 3] },
    [B + "@3"]: { ArrowLeft: [B, 2], ArrowRight: [B, 3], ArrowUp: [A, 1], ArrowDown: [B, 3], Home: [B, 2], End: [B, 3] },
  };
  let cells = 0;
  for (const [from, keys] of Object.entries(table)) {
    const [id, datum] = from.split("@");
    for (const [key, [toId, toDatum]] of Object.entries(keys)) {
      assert.deepEqual(I.keyTarget(m, mark(id, Number(datum)), key).target, mark(toId, toDatum), `${from} ${key}`);
      cells++;
    }
  }
  assert.equal(cells, 24);
  const marks = reachable(m).filter((t) => t.kind === "mark");
  assert.equal(marks.length, 4, "every mark reached from no target by keys");
});

test("D5: entering: the first mark; with no mark the first legend entry; with neither the first cut text; with nothing none", () => {
  const frame = { w: 600, h: 400 };
  const rows = [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3], ["b", "Q", 4]];
  const withMarks = model(chart("bar", rows, frame));
  for (const key of ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End"]) {
    assert.deepEqual(I.keyTarget(withMarks, null, key).target, mark("string:P", 0), key);
  }
  assert.deepEqual(I.keyTarget(withMarks, null, "Enter"), { target: null, toggle: null, handled: false }, "Enter enters nothing");
  const allOff = model(chart("bar", rows, frame, { input: { hidden: ["string:P", "string:Q"] } }));
  assert.equal(allOff.series.length, 0);
  assert.deepEqual(I.keyTarget(allOff, null, "ArrowRight").target, { kind: "entry", id: allOff.entries[0].id });
  const cutsOnly = model(renderFixture("bar", "look-long-subtitle", { w: 360, h: 260 }));
  cutsOnly.series = [];
  cutsOnly.entries = [];
  assert.ok(cutsOnly.cuts.length > 0);
  assert.deepEqual(I.keyTarget(cutsOnly, null, "ArrowDown").target, { kind: "cut", n: 0 });
  const nothing = model(chart("bar", [], frame));
  assert.equal(I.keyTarget(nothing, null, "ArrowRight").target, null);
});

test("D5: each group's ends: marks to entries to cut texts and back; Enter and Space switch an entry", () => {
  const out = renderFixture("bar", "look-long-labels", { w: 600, h: 400 });
  const m = model(out);
  assert.ok(m.series.length >= 1 && m.cuts.length >= 1, "the fixture draws marks and cut labels");
  if (m.entries.length === 0) {
    // one series: Right from the last mark goes to the first cut text, Left from it back to the last mark.
    const s = m.series[0];
    const last = mark(s.id, s.marks[s.marks.length - 1].datum);
    assert.deepEqual(I.keyTarget(m, last, "ArrowRight").target, { kind: "cut", n: 0 });
    assert.deepEqual(I.keyTarget(m, { kind: "cut", n: 0 }, "ArrowLeft").target, last);
  }
  const multi = chart("bar", [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3], ["b", "Q", 4]], { w: 600, h: 400 });
  const mm = model(multi);
  const ids = mm.entries.map((e) => e.id);
  assert.equal(ids.length, 2);
  const lastP = mark("string:P", 1);
  assert.deepEqual(I.keyTarget(mm, lastP, "ArrowRight").target, { kind: "entry", id: "string:P" }, "to its own entry");
  assert.deepEqual(I.keyTarget(mm, { kind: "entry", id: ids[0] }, "ArrowLeft").target, mark("string:P", 1), "back to the last mark of the first series");
  assert.deepEqual(I.keyTarget(mm, { kind: "entry", id: ids[0] }, "ArrowRight").target, { kind: "entry", id: ids[1] });
  assert.deepEqual(I.keyTarget(mm, { kind: "entry", id: ids[1] }, "ArrowRight").target, { kind: "entry", id: ids[1] }, "no cut text: it stays");
  for (const key of ["ArrowUp", "ArrowDown", "Home", "End"]) assert.deepEqual(I.keyTarget(mm, { kind: "entry", id: ids[1] }, key).target, { kind: "entry", id: ids[1] });
  assert.equal(I.keyTarget(mm, { kind: "entry", id: ids[1] }, "Enter").toggle, ids[1]);
  assert.equal(I.keyTarget(mm, { kind: "entry", id: ids[1] }, " ").toggle, ids[1]);
  assert.deepEqual(I.keyTarget(mm, mark("string:P", 0), "ArrowLeft").target, mark("string:P", 0), "Left on the first mark stays");
  // Escape clears the target; with no target it is still answered (it closes a pointer's card).
  assert.deepEqual(I.keyTarget(mm, lastP, "Escape"), { target: null, toggle: null, handled: true });
  // Tab is never answered: the chart is left.
  assert.equal(I.keyTarget(mm, lastP, "Tab").handled, false);
  // cut texts: Left on the first goes to the last entry; Right on the last stays.
  const both = model(renderFixture("bar", "look-long-labels", { w: 600, h: 400 }));
  both.entries = mm.entries;
  assert.deepEqual(I.keyTarget(both, { kind: "cut", n: 0 }, "ArrowLeft").target, { kind: "entry", id: ids[1] });
  const lastCut = both.cuts.length - 1;
  assert.deepEqual(I.keyTarget(both, { kind: "cut", n: lastCut }, "ArrowRight").target, { kind: "cut", n: lastCut });
});

test("D5: across a null gap Left and Right skip the null; a mark equally near on both sides resolves to the lower x", () => {
  const gap = model(chart("line", [["a", "P", 1], ["b", "Q", 9], ["c", "P", 3]], { w: 600, h: 400 }));
  assert.deepEqual(I.keyTarget(gap, mark("string:P", 0), "ArrowRight").target, mark("string:P", 2));
  assert.deepEqual(I.keyTarget(gap, mark("string:P", 2), "ArrowLeft").target, mark("string:P", 0));
  assert.deepEqual(I.keyTarget(gap, mark("string:Q", 1), "ArrowUp").target, mark("string:P", 0), "x 0 and x 2 are equally near x 1");
});

test("D5: a 3-series grouped bar: Up and Down within one category reach the three series", () => {
  const out = chart("bar", [["a", "P", 1], ["a", "Q", 2], ["a", "R", 3], ["b", "P", 4], ["b", "Q", 5], ["b", "R", 6]], { w: 80, h: 24 });
  const m = model(out);
  let t = mark("string:P", 1);
  const seen = [t.id];
  for (let i = 0; i < 2; i++) {
    t = I.keyTarget(m, t, "ArrowDown").target;
    seen.push(t.id);
    assert.equal(t.datum, 1, "the same category");
  }
  assert.deepEqual(seen, ["string:P", "string:Q", "string:R"]);
  assert.deepEqual(I.keyTarget(m, t, "ArrowDown").target, t, "Down on the last series stays");
  assert.deepEqual(I.keyTarget(m, I.keyTarget(m, t, "ArrowUp").target, "ArrowUp").target, mark("string:P", 1));
});

test("D5: the target found again after a redraw, in each of D5's cases", () => {
  const frame = { w: 600, h: 400 };
  const rows = [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3], ["b", "Q", 4]];
  const all = model(chart("bar", rows, frame));
  assert.deepEqual(I.findAgain(all, mark("string:Q", 1)), mark("string:Q", 1), "a mark still drawn stays");
  assert.equal(I.findAgain(all, null), null, "no target stays no target");
  const fewer = model(chart("bar", [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3]], frame));
  assert.deepEqual(I.findAgain(fewer, mark("string:Q", 1)), mark("string:Q", 0), "its series' first mark");
  const qOff = model(chart("bar", rows, frame, { input: { hidden: ["string:Q"] } }));
  assert.deepEqual(I.findAgain(qOff, mark("string:Q", 1)), { kind: "entry", id: "string:Q" }, "its series' entry");
  const qGone = model(chart("bar", [["a", "P", 1], ["b", "P", 3]], frame));
  assert.deepEqual(I.findAgain(qGone, mark("string:Q", 1)), mark("string:P", 0), "as on entering");
  assert.deepEqual(I.findAgain(all, { kind: "entry", id: "string:Q" }), { kind: "entry", id: "string:Q" }, "an entry still drawn stays");
  const one = model(chart("bar", [["a", "Q", 2], ["b", "Q", 4]], frame));
  assert.equal(one.entries.length, 0, "one series draws no legend");
  assert.deepEqual(I.findAgain(one, { kind: "entry", id: "string:Q" }), mark("string:Q", 0), "an entry gone: its series' first mark");
  assert.deepEqual(I.findAgain(qGone, { kind: "entry", id: "string:R" }), mark("string:P", 0), "else as on entering");
  const cuts = model(renderFixture("bar", "look-long-labels", { w: 600, h: 400 }));
  assert.deepEqual(I.findAgain(cuts, { kind: "cut", n: 0 }), { kind: "cut", n: 0 }, "a cut text at the same place");
  assert.deepEqual(I.findAgain(all, { kind: "cut", n: 0 }), mark("string:P", 0), "no cut text there: as on entering");
});

// ------------------------------------------------------------------------------------- the DOM half (fake)

function mounted(out, frame, options = {}) {
  const browser = fakeBrowser();
  const svg = browser.doc.createElementNS(R.write.NS, "svg");
  browser.host.appendChild(svg);
  R.writeDom(out.primitives, svg, "light");
  const toggles = [];
  const handle = layer.attach(svg, { output: out, theme: "light", measure: null, frame }, { toggle: (id) => toggles.push(id), ...options });
  return { browser, svg, handle, toggles };
}

const cardTexts = (svg) => descendants(svg).filter((e) => e.tagName === "text" && /dp-tooltip/.test(e.getAttribute("class") || "")).map((e) => e.textContent);

test("attach: one tab stop on the svg, listeners on the svg only, a key draws the card and the ring, detach leaves nothing", () => {
  const frame = { w: 600, h: 400 };
  const out = chart("bar", [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3], ["b", "Q", 4]], frame);
  const { svg, handle } = mounted(out, frame);
  assert.equal(svg.getAttribute("tabindex"), "0");
  assert.equal(descendants(svg).filter((e) => e.getAttribute("tabindex") !== null).length, 0, "no mark, entry or text carries one");
  const types = svg.listeners.map((l) => l.type).sort();
  assert.deepEqual(types, ["blur", "click", "keydown", "pointerdown", "pointerleave", "pointermove"]);
  const before = descendants(svg).length;
  assert.equal(svg.dispatch("keydown", { key: "ArrowRight" }), true, "an answered key prevents the page's own action");
  assert.ok(cardTexts(svg).includes("a"), "the heading");
  assert.ok(descendants(svg).some((e) => /dp-focus/.test(e.getAttribute("class") || "")), "the focus ring");
  assert.match(svg.getAttribute("class"), /dp-dimmed/);
  assert.equal(descendants(svg).filter((e) => /dp-active/.test(e.getAttribute("class") || "")).length, 2, "the active series' mark group and entry");
  handle.detach();
  assert.equal(svg.listeners.length, 0);
  assert.equal(descendants(svg).length, before, "card, guide and ring gone");
  assert.equal(svg.getAttribute("tabindex"), null);
  assert.doesNotMatch(svg.getAttribute("class") || "", /dp-dimmed/);
});

test("attach: a click on an entry asks the host to switch it; Enter on an entry does too; update finds the target again", () => {
  const frame = { w: 600, h: 400 };
  const rows = [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3], ["b", "Q", 4]];
  const out = chart("bar", rows, frame);
  const { svg, handle, toggles } = mounted(out, frame);
  const entry = model(out).entries[1];
  svg.dispatch("click", { clientX: entry.box.x + entry.box.w / 2, clientY: entry.box.y + entry.box.h / 2 });
  assert.deepEqual(toggles, [entry.id]);
  svg.dispatch("keydown", { key: "ArrowRight" });
  svg.dispatch("keydown", { key: "End" });
  svg.dispatch("keydown", { key: "ArrowRight" });
  assert.deepEqual(handle.target(), { kind: "entry", id: "string:P" });
  svg.dispatch("keydown", { key: "Enter" });
  assert.deepEqual(toggles, [entry.id, "string:P"]);
  const redrawn = chart("bar", rows, frame, { input: { hidden: ["string:P"] } });
  R.writeDom(redrawn.primitives, svg, "light");
  handle.update({ output: redrawn, theme: "light", measure: null, frame });
  assert.deepEqual(handle.target(), { kind: "entry", id: "string:P" }, "the struck entry is still the target");
  assert.equal(svg.listeners.length, 6, "the same listeners");
  svg.dispatch("keydown", { key: "Escape" });
  assert.equal(handle.target(), null);
  assert.equal(cardTexts(svg).length, 0);
});

test("attach: the draw context: an update's theme and measure draw the next card; the svg and its listeners are the same", () => {
  const frame = { w: 600, h: 400 };
  const rows = [["a", "A long series name", 1], ["a", "B", 2]];
  const out = chart("bar", rows, frame);
  const { svg, handle } = mounted(out, frame);
  svg.dispatch("keydown", { key: "ArrowRight" });
  const surfaceOf = () => descendants(svg).find((e) => e.tagName === "rect" && /dp-tooltip/.test(e.getAttribute("class") || "")).getAttribute("fill");
  const first = surfaceOf();
  const theme = { base: "light", tokens: { "--chart-tip-surface": [254, 250, 224] } };
  const double = (text, size) => WIDTH(text, size) * 2;
  const narrow = { w: 120, h: 400 };
  const again = R.render(inputOf("bar", { display_name: "Interactions", config: { mark: { type: "bar" }, encoding: ENC } }, { x: ["a", "a"], s: ["A long series name", "B"], v: ["1", "2"] }, TYPES, narrow, { theme, measure: double }));
  R.writeDom(again.primitives, svg, theme);
  handle.update({ output: again, theme, measure: double, frame: narrow });
  assert.notEqual(surfaceOf(), first);
  assert.equal(surfaceOf(), R.theme.resolve(theme).css("--chart-tip-surface"));
  const name = cardTexts(svg).find((t) => t.startsWith("A"));
  assert.ok(name === undefined || name.endsWith(R.measure.ELLIPSIS), "the name cut earlier or left out: " + name);
  assert.equal(svg.listeners.length, 6);
});

test("attach: --chart-text 32 at 80 × 24: no card, no error, the focus ring still drawn", () => {
  const frame = { w: 80, h: 24 };
  const theme = { base: "light", tokens: { "--chart-text": 32 } };
  const out = R.render(inputOf("line", { display_name: "T", config: { mark: { type: "line" }, encoding: ENC } }, { x: ["x0", "x1"], s: ["A", "A"], v: ["1", "2"] }, TYPES, frame, { theme }));
  const browser = fakeBrowser();
  const svg = browser.doc.createElementNS(R.write.NS, "svg");
  R.writeDom(out.primitives, svg, theme);
  layer.attach(svg, { output: out, theme, measure: null, frame }, { toggle() {} });
  svg.dispatch("keydown", { key: "ArrowRight" });
  assert.equal(cardTexts(svg).length, 0);
  assert.ok(descendants(svg).some((e) => /dp-focus/.test(e.getAttribute("class") || "")));
});

// ------------------------------------------------- a picture with a legend switch is a group (brief 1010)

const ROWS2 = [["a", "P", 1], ["a", "Q", 2], ["b", "P", 3], ["b", "Q", 4]];
const DOC = { display_name: "Interactions", config: { mark: { type: "bar" }, encoding: ENC } };
const kids = (svg) => svg.childNodes.filter((c) => c.nodeType === 1);
const tagOf = (e) => e.localName || e.tagName;
const isEntryEl = (e) => e.getAttribute("data-legend") !== null;
/** D2's set, computed here from the tree: every top-level child that is not title, desc or a legend entry. */
const shouldHide = (svg) => kids(svg).filter((e) => tagOf(e) !== "title" && tagOf(e) !== "desc" && !isEntryEl(e));
const hiddenEls = (svg) => descendants(svg).filter((e) => e.getAttribute("aria-hidden") !== null);
const attrsOf = (e) => ({ ...e.attrs });

/** A picture written as the adapter writes it (title and desc from describe), the layer attached over it. */
function described(out, frame, options = {}) {
  const browser = fakeBrowser();
  const svg = browser.doc.createElementNS(R.write.NS, "svg");
  browser.host.appendChild(svg);
  R.writeDom(out.primitives, svg, "light", R.write.describe(out, DOC));
  const toggles = [];
  const handle = layer.attach(svg, { output: out, theme: "light", measure: null, frame }, { toggle: (id) => toggles.push(id), ...options });
  return { browser, svg, handle, toggles };
}

function redraw(svg, handle, out, frame) {
  R.writeDom(out.primitives, svg, "light", R.write.describe(out, DOC));
  handle.update({ output: out, theme: "light", measure: null, frame });
}

/** D2's hidden set exactly: each child it names carries aria-hidden="true", nothing else carries aria-hidden. */
function assertGroup(svg, why) {
  assert.equal(svg.getAttribute("role"), "group", why + ": the picture is a group");
  const expected = shouldHide(svg);
  assert.ok(expected.length > 0, why + ": a non-vacuous set");
  for (const e of expected) assert.equal(e.getAttribute("aria-hidden"), "true", why + ": " + tagOf(e) + " " + (e.getAttribute("class") || ""));
  assert.equal(hiddenEls(svg).length, expected.length, why + ": aria-hidden on exactly D2's children");
  const entries = kids(svg).filter(isEntryEl);
  assert.ok(entries.length > 0, why + ": the switches are top-level children");
  assert.equal(descendants(svg).filter(isEntryEl).length, entries.length, why + ": no switch nested inside a hidden child");
  for (const t of ["title", "desc"]) assert.equal(kids(svg).find((e) => tagOf(e) === t).getAttribute("aria-hidden"), null, why + ": " + t + " stays read");
  return expected.length;
}

test("N1: a draw with a legend is a group; D2's children are hidden, the card and the ring too; a redraw holds it", () => {
  const frame = { w: 600, h: 400 };
  const out = chart("bar", ROWS2, frame);
  const { svg, handle } = described(out, frame);
  assertGroup(svg, "attach");
  svg.dispatch("keydown", { key: "ArrowRight" });
  const made = kids(svg).filter((e) => /dp-tooltip|dp-focus/.test(e.getAttribute("class") || ""));
  assert.ok(made.some((e) => /dp-tooltip/.test(e.getAttribute("class"))), "the card is drawn");
  assert.ok(made.some((e) => /dp-focus/.test(e.getAttribute("class"))), "the ring is drawn");
  for (const e of made) assert.equal(e.getAttribute("aria-hidden"), "true", "the layer's own " + e.getAttribute("class"));
  assertGroup(svg, "after a key");
  redraw(svg, handle, chart("bar", ROWS2, frame, { input: { hidden: ["string:Q"] } }), frame);
  assertGroup(svg, "after a fresh writeDom and update");
});

test("N1: the largest count of top-level children the aria-hidden pass meets over the bar, line and area fixtures", () => {
  const frame = { w: 600, h: 400 };
  const counts = {};
  for (const type of ["bar", "line", "area"]) {
    const { svg } = described(chart(type, ROWS2, frame), frame);
    counts[type] = { children: kids(svg).length, hidden: assertGroup(svg, type) };
  }
  console.log("1010 aria-hidden pass: " + JSON.stringify(counts) + " largest children=" + Math.max(...Object.values(counts).map((c) => c.children)));
});

test("N2: a draw without a legend entry stays an image and carries no aria-hidden", () => {
  const frame = { w: 600, h: 400 };
  const out = chart("bar", [["a", "Q", 2], ["b", "Q", 4]], frame);
  assert.equal(model(out).entries.length, 0, "one series draws no legend");
  const { svg } = described(out, frame);
  assert.equal(svg.getAttribute("role"), "img");
  svg.dispatch("keydown", { key: "ArrowRight" });
  assert.equal(svg.getAttribute("role"), "img");
  assert.equal(hiddenEls(svg).length, 0, "nothing hidden, the card and the ring included");
});

test("N3: through update, a picture that gains and loses its legend follows D1 both ways", () => {
  const frame = { w: 600, h: 400 };
  const one = chart("bar", [["a", "Q", 2], ["b", "Q", 4]], frame);
  const { svg, handle } = described(one, frame);
  assert.equal(svg.getAttribute("role"), "img");
  redraw(svg, handle, chart("bar", ROWS2, frame), frame);
  assertGroup(svg, "gained a legend");
  redraw(svg, handle, one, frame);
  assert.equal(svg.getAttribute("role"), "img", "lost its legend");
  assert.equal(hiddenEls(svg).length, 0);
});

test("N4: detach gives back the picture the writer wrote, attribute for attribute", () => {
  const frame = { w: 600, h: 400 };
  const out = chart("bar", ROWS2, frame);
  const { svg, handle } = described(out, frame);
  svg.dispatch("keydown", { key: "ArrowRight" });
  handle.detach();
  assert.equal(svg.getAttribute("role"), "img");
  assert.equal(hiddenEls(svg).length, 0);
  const fresh = fakeBrowser().doc.createElementNS(R.write.NS, "svg");
  R.writeDom(out.primitives, fresh, "light", R.write.describe(out, DOC));
  assert.deepEqual(attrsOf(svg), attrsOf(fresh), "the svg's attributes");
  assert.deepEqual(kids(svg).map(attrsOf), kids(fresh).map(attrsOf), "its top-level children's attributes");
});

test("N5: a press on an entry, or inside one, switches it once; a pointer click switches once either way", () => {
  const frame = { w: 600, h: 400 };
  const out = chart("bar", ROWS2, frame);
  const entries = model(out).entries;
  const centre = (e) => ({ clientX: e.box.x + e.box.w / 2, clientY: e.box.y + e.box.h / 2 });
  const byCoordinates = (e) => {
    const { svg, toggles } = described(out, frame);
    svg.dispatch("click", { target: svg, ...centre(e) });
    return toggles;
  };
  for (const e of entries) {
    const expected = byCoordinates(e);
    assert.equal(expected.length, 1, "a coordinate click with the svg as target: once");
    const el = (svg) => kids(svg).find((k) => k.getAttribute("data-legend") === e.id);
    let m = described(out, frame);
    m.svg.dispatch("click", { target: el(m.svg) });
    assert.deepEqual(m.toggles, expected, "a press on the entry, no coordinates: once, the same value");
    m = described(out, frame);
    const text = descendants(el(m.svg)).find((k) => tagOf(k) === "text");
    assert.ok(text, "the entry holds its text");
    m.svg.dispatch("click", { target: text });
    assert.deepEqual(m.toggles, expected, "a press on the entry's text, no coordinates: once, the entry's value");
    m = described(out, frame);
    const pointerText = descendants(el(m.svg)).find((k) => tagOf(k) === "text");
    assert.ok(pointerText, "the current entry holds its text");
    m.svg.dispatch("click", { target: pointerText, ...centre(e) });
    assert.deepEqual(m.toggles, expected, "a pointer click on the entry: once");
  }
  const m = described(out, frame);
  m.svg.dispatch("click", { target: m.svg });
  assert.deepEqual(m.toggles, [], "a press on nothing switches nothing");
});

test("the layer's file: 0 getComputedStyle, 0 colour literals, 0 markup sinks (D1, Security)", () => {
  const src = readFileSync(path.join(STATIC_JS, "datapipelines-chart-interactions.js"), "utf8");
  const counts = {
    getComputedStyle: (src.match(/getComputedStyle/g) || []).length,
    hex: (src.match(/#[0-9a-fA-F]{3,8}\b/g) || []).length,
    rgb: (src.match(/\b(?:rgba?|hsla?|oklch|color)\(/g) || []).length,
    sinks: (src.match(/innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\(|\bFunction\(/g) || []).length,
    attributes: (src.match(/setAttribute\(\s*"(?:style|href|on\w+)"/g) || []).length,
  };
  assert.deepEqual(counts, { getComputedStyle: 0, hex: 0, rgb: 0, sinks: 0, attributes: 0 });
});

// ============================================================ issue 1132: the crosshair dot's whole ink fits the frame

/**
 * The interaction layer loaded a second time from its own source, [edit] changing the text first (a falsification's
 * copy), so the base and the candidate are two independent loads of the one file. Each reads the engine from
 * globalThis.JsRendered (set at the top), the same engine every layer here uses.
 */
function layerWith(edit) {
  const src = readFileSync(path.join(STATIC_JS, "datapipelines-chart-interactions.js"), "utf8");
  const mod = { exports: {} };
  const run = new Function("module", "exports", "globalThis", edit ? edit(src) : src);
  run(mod, mod.exports, globalThis);
  return mod.exports;
}

/** The crosshair before issue 1132: the dot's radius and ring exactly as the theme gives them, never fitted. */
const UNFITTED = ['var fit = dotFit(R, frame, x, y, m.dot, t.n("--chart-gap"));', 'var fit = { r: m.dot, ring: t.n("--chart-gap") };'];

/** The base the candidate is compared with: the layer with the fit taken back out (the post-issue-1128 behaviour). */
const BASE = layerWith((s) => s.replace(UNFITTED[0], UNFITTED[1]))._internal;

/** Every chart fixture (wire, name, fixture), the engine module's own list, read the same way. */
function allFixtures() {
  const out = [];
  for (const wire of readdirSync(FIXTURES).sort()) {
    const dir = path.join(FIXTURES, wire);
    if (wire.startsWith("_") || !statSync(dir).isDirectory()) continue;
    for (const f of readdirSync(dir).sort()) if (f.endsWith(".json")) out.push({ wire, name: f.slice(0, -5), fixture: fixture(wire, f.slice(0, -5)) });
  }
  return out;
}

/** The engine's picture of [fx] at the frame under [theme]; the layer draws none of it, so the base and the candidate share it. */
function renderAt(fx, w, h, theme) {
  return R.render(inputOf(fx.document.config.mark.type, fx.document, fx.columns, fx.types, { w, h }, { theme }));
}

/** The model of [out] under [theme] (the layer's own): the measures the crosshair reads, the theme attached. */
function modelAt(layer, out, theme) {
  const m = layer.modelOf(R, out, WIDTH);
  const measures = layer.measures(R, theme);
  m.theme = measures.theme;
  return { m, measures };
}

/** The first, middle and last distinct datum of every visible series of [model]. */
function datumPicks(model) {
  const picks = [];
  for (const ser of model.series) {
    const n = ser.marks.length;
    if (n === 0) continue;
    for (const i of new Set([0, Math.floor((n - 1) / 2), n - 1])) picks.push({ id: ser.id, datum: ser.marks[i].datum });
  }
  return picks;
}

const guideDots = (scene) => scene.prims.filter((p) => p.k === "circle" && p.role === "guide");

/** A dot's four outer ink bounds: its center ± its radius and half its ring. */
function inkOf(dot) {
  const e = dot.r + dot.strokeWidth / 2;
  return { left: dot.cx - e, right: dot.cx + e, top: dot.cy - e, bottom: dot.cy + e };
}

/** A dot's whole ink inside the frame, within [tol] of arithmetic noise on each side. */
function dotInside(dot, w, h, tol) {
  const b = inkOf(dot);
  return b.left >= -tol && b.top >= -tol && b.right <= w + tol && b.bottom <= h + tol;
}

/**
 * The crosshair over every line and area fixture at the small frames under --chart-point 16 and each gap, both inputs,
 * the first, middle and last datum of every visible series: each guide dot's whole ink inside the frame and its center
 * the data point (unchanged from the base), the guide line, the focus ring and the card drawn. Returns the counts and
 * the problems; the main test asserts none, a falsification asserts some.
 */
function geometry(LAYER) {
  const frames = [[80, 24], [160, 24], [120, 40], [200, 140]];
  const gaps = [0, 2, 8];
  const seen = { pictures: 0, scenes: 0, dots: 0, shrunk: 0, whole: 0 };
  const problems = [];
  for (const f of allFixtures()) {
    if (f.wire !== "line" && f.wire !== "area") continue;
    for (const gap of gaps) for (const [w, h] of frames) {
      const theme = { base: "light", tokens: { "--chart-point": 16, "--chart-gap": gap } };
      const out = renderAt(f.fixture, w, h, theme);
      if (out.state !== "rendered") continue;
      const { m, measures } = modelAt(I, out, theme);
      if (m.series.length === 0) continue;
      seen.pictures++;
      for (const { id, datum } of datumPicks(m)) {
        const anchor = I.anchorOf(m, id, datum);
        for (const [mode, state] of [
          ["key", { last: "key", target: { kind: "mark", id, datum }, hit: null, pointer: null }],
          ["pointer", { last: "pointer", target: null, hit: { mark: { id, datum }, entry: null, cut: null }, pointer: anchor }],
        ]) {
          const cand = LAYER.scene(R, m, { frame: { w, h } }, measures, WIDTH, state);
          const base = BASE.scene(R, m, { frame: { w, h } }, measures, WIDTH, state);
          const at = `${f.wire}/${f.name} ${w}x${h} gap ${gap} ${mode} ${id}@${datum}`;
          const cdots = guideDots(cand);
          const bdots = guideDots(base);
          seen.scenes++;
          if (cdots.length === 0) {
            problems.push(`${at}: no guide dot`);
            continue;
          }
          const cen = (ds) => ds.map((d) => [d.cx, d.cy, d.series]);
          if (JSON.stringify(cen(cdots)) !== JSON.stringify(cen(bdots))) problems.push(`${at}: a center moved`);
          if (cand.prims.filter((p) => p.k === "line" && p.role === "guide").length !== 1) problems.push(`${at}: no guide line`);
          if (mode === "key" && cand.prims.filter((p) => p.k === "rect" && p.role === "focus").length !== 1) problems.push(`${at}: no focus ring`);
          if (cand.prims.filter((p) => p.role === "tooltip").length === 0) problems.push(`${at}: no card`);
          for (let i = 0; i < cdots.length; i++) {
            seen.dots++;
            const d = cdots[i];
            const b = bdots[i];
            if (d.r !== b.r || d.strokeWidth !== b.strokeWidth) seen.shrunk++;
            else seen.whole++;
            if (!dotInside(d, w, h, 1e-9)) problems.push(`${at}: a dot's ink ${JSON.stringify(inkOf(d))} leaves the frame`);
          }
        }
      }
    }
  }
  return { seen, problems };
}

test("1132: the fit at its witness and its borders", () => {
  assert.deepEqual(I.dotFit(R, { w: 80, h: 24 }, 6, 15.12, 16.5, 2), { r: 5.65, ring: 0.6857142857142857 }, "the 80x24 point 16 gap 2 witness");
  assert.deepEqual(I.dotFit(R, { w: 80, h: 24 }, 6, 15.12, 4.5, 2), { r: 4.5, ring: 2 }, "the default theme's dot fits and is kept exactly");
  assert.deepEqual(I.dotFit(R, { w: 80, h: 24 }, 0, 10, 16.5, 2), { r: 0, ring: 0 }, "zero room leaves both zero");
  assert.deepEqual(I.dotFit(R, { w: 80, h: 24 }, 6, 15.12, 16.5, 0), { r: 6, ring: 0 }, "without a ring the radius alone fits the 6 px of room");
  assert.deepEqual(I.dotFit(R, { w: 400, h: 200 }, 100, 50, 16.5, 2), { r: 16.5, ring: 2 }, "a dot the frame already holds is kept exactly");
});

test("1132: at --chart-point 16 the crosshair dot's whole ink stays inside the frame and its center is the data point", () => {
  const { seen, problems } = geometry(I);
  assert.deepEqual(problems.slice(0, 5), [], `${problems.length} problems`);
  console.log(`1132 point-16: ${JSON.stringify(seen)}`);
  assert.ok(seen.pictures >= 200 && seen.scenes >= 1000 && seen.dots >= 2000 && seen.shrunk >= 1000 && seen.whole >= 50, JSON.stringify(seen));
});

test("1132: at the default light and dark tokens the candidate is the base, byte for byte, in every picture and hover scene", () => {
  const frames = [[80, 24], [160, 24], [360, 240], [960, 540]];
  const themes = ["light", "dark"];
  const seen = { pictures: 0, scenes: 0 };
  const problems = [];
  for (const f of allFixtures()) {
    for (const [w, h] of frames) for (const theme of themes) {
      const out = renderAt(f.fixture, w, h, theme);
      if (out.state !== "rendered") continue;
      seen.pictures++;
      if (f.wire !== "line" && f.wire !== "area") continue;
      const { m, measures } = modelAt(I, out, theme);
      if (m.series.length === 0) continue;
      for (const { id, datum } of datumPicks(m)) {
        const anchor = I.anchorOf(m, id, datum);
        for (const state of [
          { last: "key", target: { kind: "mark", id, datum }, hit: null, pointer: null },
          { last: "pointer", target: null, hit: { mark: { id, datum }, entry: null, cut: null }, pointer: anchor },
        ]) {
          const cand = I.scene(R, m, { frame: { w, h } }, measures, WIDTH, state);
          const base = BASE.scene(R, m, { frame: { w, h } }, measures, WIDTH, state);
          seen.scenes++;
          if (JSON.stringify(cand) !== JSON.stringify(base)) problems.push(`${f.wire}/${f.name} ${w}x${h} ${theme} ${id}@${datum} differs`);
        }
      }
    }
  }
  assert.deepEqual(problems.slice(0, 5), [], `${problems.length} hover scenes differ from the base`);
  console.log(`1132 default: ${JSON.stringify(seen)}`);
  assert.ok(seen.pictures >= 1500 && seen.scenes >= 1000, JSON.stringify(seen));
});

test("1132 plant 1: without the downward rounding the witness dot's ink leaks past the frame", () => {
  const rounded = layerWith((s) => s.replace("Math.floor(100 *", "Math.round(100 *"))._internal;
  const fit = rounded.dotFit(R, { w: 80, h: 24 }, 6, 15.12, 16.5, 2);
  assert.equal(fit.r, 5.66, "the radius rounds up to 5.66 instead of down to 5.65");
  assert.ok(6 - (fit.r + fit.ring / 2) < 0, `the left ink is ${6 - (fit.r + fit.ring / 2)}`);
  const { problems } = geometry(rounded);
  assert.ok(problems.some((p) => /leaves the frame/.test(p)), problems[0] || "no bounds problem");
});

test("1132 plant 2: the theme-only radius lets the crosshair dot leave the frame at --chart-point 16", () => {
  const { seen, problems } = geometry(BASE);
  assert.ok(problems.some((p) => /leaves the frame/.test(p)), problems[0] || "no bounds problem");
  assert.equal(seen.shrunk, 0, "nothing is fitted, so nothing differs from the base");
});

test("1132 plant 3: without the half ring in the fit's reach a nonzero-ring dot near the edge is kept and leaks", () => {
  const noHalfRing = layerWith((s) => s.replace("rr + ring / 2 <= available", "rr <= available"))._internal;
  // A dot whose radius fits the 17 px of room but whose ring does not: the correct fit scales it, the plant keeps it.
  const kept = noHalfRing.dotFit(R, { w: 40, h: 40 }, 17, 17, 16.5, 8);
  assert.deepEqual(kept, { r: 16.5, ring: 8 }, "the reach without the half ring keeps the dot exactly");
  assert.ok(17 - (kept.r + kept.ring / 2) < 0, `its ink leaves the frame by ${17 - (kept.r + kept.ring / 2)}`);
  const fit = I.dotFit(R, { w: 40, h: 40 }, 17, 17, 16.5, 8);
  assert.ok(fit.r < 16.5 && 17 - (fit.r + fit.ring / 2) >= 0, "the shipped fit scales the same dot inside the frame");
});
