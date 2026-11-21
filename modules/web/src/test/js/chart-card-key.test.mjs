// The card's key follows its own mark (brief 1057; look contract §4.4 "A mark's card": a row is key, name, value).
// On a bar coloured by value (#1050) the marks of ONE series carry different fills, so a row's key takes the token and
// the resolved colour of the primitive of the mark the row is about, never of the series' first mark. On every draw
// whose marks of one series share one fill (a grouped bar, a stacked bar) the key stays the series' colour. The engine
// is loaded from its module's resources as the page loads it; the expected colours are read HERE off the engine's
// primitives, never from the layer's model.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import path from "node:path";
import { loadEngine, STATIC_JS, ENGINE_DIR } from "./support/js-rendered.mjs";

const require = createRequire(import.meta.url);
const R = loadEngine();
globalThis.JsRendered = R;
const layer = require(path.join(STATIC_JS, "datapipelines-chart-interactions.js"));
const I = layer._internal;
const FIXTURES = path.resolve(ENGINE_DIR, "../../../../../../test/fixtures");
const WIDTH = R.measure.measurer(null).fn;
const FRAME = { w: 600, h: 400 };

function fixture(name) {
  return JSON.parse(readFileSync(path.join(FIXTURES, "bar", name + ".json"), "utf8"));
}

function draw(fx) {
  return R.render({ type: fx.document.config.mark.type, document: fx.document, columns: fx.columns, types: fx.types, frame: FRAME, theme: "light", measure: null, hidden: [] });
}

function modelOf(out) {
  const m = I.modelOf(R, out, WIDTH);
  m.theme = I.measures(R, "light").theme;
  return m;
}

/** The engine's own mark primitive of (series, datum): role mark, not a group or a text; the first one, as drawn. */
function primOf(out, series, datum) {
  return out.primitives.find((p) => p.role === "mark" && p.k !== "group" && p.k !== "text" && p.series === series && p.datum === datum);
}

/** {token, css} of a primitive: its fill and resolved colour, else its stroke and resolved stroke colour. */
function colourOf(p) {
  return p.fill !== null && p.fill !== undefined ? { token: p.fill, css: p.color } : { token: p.stroke, css: p.strokeColor };
}

/** Every drawn mark of the draw, with the rows of the card shown for it. */
function cards(out) {
  const m = modelOf(out);
  const shown = [];
  for (const ser of m.series) {
    for (const mk of ser.marks) {
      const lines = I.cardLines(m, ser.id, mk.datum);
      assert.notEqual(lines, null, `a card for ${ser.id} at ${mk.datum}`);
      shown.push({ series: ser.id, datum: mk.datum, rows: lines.filter((l) => l.kind === "row") });
    }
  }
  return { model: m, shown };
}

// ------------------------------------------------------------------------------- D5.1 a bar coloured by value

for (const name of ["shade-vertical", "shade-horizontal"]) {
  test(`${name}: each bar's card key carries THAT bar's token and resolved colour`, () => {
    const out = draw(fixture(name));
    const { shown } = cards(out);
    // Floor (G2): at least three bars on at least three different steps, so a series-wide colour cannot pass.
    const fills = new Set(shown.map((c) => colourOf(primOf(out, c.series, c.datum)).token));
    assert.ok(shown.length >= 3, `${name}: at least three bars (got ${shown.length})`);
    assert.ok(fills.size >= 3, `${name}: the floor: at least three different fills among the bars (got ${[...fills].join(", ")})`);
    const wrong = [];
    for (const c of shown) {
      const own = colourOf(primOf(out, c.series, c.datum));
      assert.equal(c.rows.length, 1, `${name}: one row per card on a one-series bar (${c.datum})`);
      const row = c.rows[0];
      if (row.colour !== own.token || row.color !== own.css) wrong.push(`${c.datum}: key ${row.colour} ${row.color}, bar ${own.token} ${own.css}`);
    }
    assert.deepEqual(wrong, [], `${name}: the bars whose card key is not their own colour`);
  });
}

// ---------------------------------------------------------- D5.2 several series, one fill each: as before

for (const name of ["parity-bar-grouped", "parity-bar-stacked", "look-horizontal-stacked"]) {
  test(`${name}: every row's key is its series' colour, on every card`, () => {
    const out = draw(fixture(name));
    const { model, shown } = cards(out);
    const series = new Set(shown.map((c) => c.series));
    assert.ok(series.size >= 2, `${name}: several series (got ${series.size})`);
    let rows = 0;
    for (const c of shown) {
      assert.ok(c.rows.length >= 2, `${name}: a card of several rows (${c.series} at ${c.datum})`);
      for (const row of c.rows) {
        const own = model.colours[row.id];
        assert.ok(own, `${name}: the series ${row.id} has a colour`);
        assert.equal(row.colour, own.token, `${name}: ${c.series} at ${c.datum}, row ${row.id}: token`);
        assert.equal(row.color, own.css, `${name}: ${c.series} at ${c.datum}, row ${row.id}: resolved colour`);
        // The series' colour is the colour of the row's own mark too: one fill per series.
        const prim = primOf(out, row.id, c.datum);
        assert.equal(colourOf(prim).token, own.token, `${name}: ${row.id} at ${c.datum} is drawn in its series' fill`);
        rows++;
      }
    }
    assert.ok(rows >= 4, `${name}: rows compared (got ${rows})`);
  });
}
