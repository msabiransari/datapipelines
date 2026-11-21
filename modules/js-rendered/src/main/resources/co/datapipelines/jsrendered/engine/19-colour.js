// 19-colour.js : colour by value (record §4.4 `shade`, G3; look contract §3.3, §4.2; brief 1050 D4, D5). A colour
// scale over the aggregates of the marks a draw draws, the step and the token of a value on it, and the scale's legend.
// A scale is an object with a `kind`; `step` and `token` branch on it in ONE place each (the table KINDS), so the
// two-sided scale (lane G5, the look contract's §4.7) adds a kind here and changes no caller.
//
// A scale: {kind: "sequential", lo, hi, loText, hiText}. lo and hi (numbers) place the steps; loText and hiText are the
// texts written for the two ends, each from the end aggregate's EXACT text where it has one, else from its float, by
// the record's one number rule (§4.7; R.text.value without a format). A float is never the source of a label where an
// exact text exists.
(function (R) {
  "use strict";
  var P = R.prim, T = R.text, look = R.tables.look, r2 = R.scales.r2;
  /** The look contract's ramps hold seven steps (§3.3); with no spread every mark takes the middle one. */
  var STEPS = 7, MIDDLE = (STEPS + 1) / 2;
  /** The fill of a mark whose value has no step (null, or not finite): the faintest line colour (contract §3.1). */
  var NONE = "--chart-grid";
  var DECIMAL = /^(-?)([0-9]+)(?:\.([0-9]+))?$/;

  function finite(n) {
    return typeof n === "number" && n === n && n !== Infinity && n !== -Infinity;
  }

  /** −1, 0 or 1: two exact decimal texts compared by value (an exact column's aggregate, R.data's decimal form). */
  function cmpText(a, b) {
    var x = DECIMAL.exec(a), y = DECIMAL.exec(b);
    if (x === null || y === null) throw new Error("colour: an exact value is not a decimal text");
    var fx = x[3] || "", fy = y[3] || "";
    while (fx.length < fy.length) fx += "0";
    while (fy.length < fx.length) fy += "0";
    var bx = BigInt(x[1] + x[2] + fx), by = BigInt(y[1] + y[2] + fy);
    return bx < by ? -1 : bx > by ? 1 : 0;
  }

  function hasExact(a) {
    return a.exact !== null && a.exact !== undefined;
  }

  /** The value an end's text is written from: the exact text where there is one, else the float. */
  function source(a) {
    return hasExact(a) ? a.exact : a.num;
  }

  /** Where two aggregates share one float, the one whose exact text lies further out (sign −1: lower, 1: higher). */
  function further(a, b, sign) {
    if (!hasExact(a)) return false;
    if (!hasExact(b)) return true;
    return cmpText(a.exact, b.exact) * sign > 0;
  }

  /**
   * The one-sided scale over `values`, each an aggregate {num, exact} as R.data.aggregate gives it (or null): lo and hi
   * are the smallest and the largest finite num; null when no value is finite.
   */
  function sequential(values) {
    var lo = null, hi = null;
    for (var i = 0; i < values.length; i++) {
      var v = values[i];
      if (v === null || v === undefined || !finite(v.num)) continue;
      if (lo === null || v.num < lo.num || (v.num === lo.num && further(v, lo, -1))) lo = v;
      if (hi === null || v.num > hi.num || (v.num === hi.num && further(v, hi, 1))) hi = v;
    }
    if (lo === null) return null;
    return { kind: "sequential", lo: lo.num, hi: hi.num, loText: T.value(source(lo)), hiText: T.value(source(hi)) };
  }

  /**
   * The step of v on a one-sided scale: seven equal parts between lo and hi, the ratio first, then × 7 (the first look
   * study's rule), a value on a border taking the higher step. No intermediate overflows: where hi − lo or v − lo is not
   * finite the same ratio is taken from halved operands. With hi = lo no division is made: every value takes the middle.
   */
  function sequentialStep(scale, v) {
    var lo = scale.lo, hi = scale.hi;
    if (hi === lo) return MIDDLE;
    var span = hi - lo, off = v - lo;
    var r = finite(span) && finite(off) ? off / span : (v / 2 - lo / 2) / (hi / 2 - lo / 2);
    return 1 + Math.max(0, Math.min(STEPS - 1, Math.floor(r * STEPS)));
  }

  /** Each kind's step rule and the prefix of its ramp's tokens. */
  var KINDS = {
    sequential: { step: sequentialStep, prefix: "--chart-seq-" },
  };

  function kindOf(scale) {
    var k = Object.prototype.hasOwnProperty.call(KINDS, scale.kind) ? KINDS[scale.kind] : null;
    if (k === null) throw new Error("colour: no scale of kind " + scale.kind);
    return k;
  }

  /** The step (1 to 7) of v on the scale, or null when v is null or not finite, or there is no scale. */
  function step(scale, v) {
    if (scale === null || v === null || v === undefined || !finite(v)) return null;
    return kindOf(scale).step(scale, v);
  }

  /** The fill token of v: its step's token of the scale's ramp, or NONE when it has no step. */
  function token(scale, v) {
    var s = step(scale, v);
    return s === null ? NONE : kindOf(scale).prefix + s;
  }

  /**
   * Where a scale's legend is (brief 1050 D5): none at the minimal level or under `legend: null`; at the place the author
   * wrote; otherwise at the top; at the compact level one row at the bottom ("compact"). The one-series rule of the
   * contract's §4.2 does not apply (a scale's legend explains colours, not series) and the switched-off list is not read.
   */
  function place(lvl, legend) {
    if (lvl === "minimal" || legend === null) return null;
    var orient = legend && typeof legend === "object" && typeof legend.orient === "string" ? legend.orient : null;
    var at = orient === "top" || orient === "bottom" || orient === "left" || orient === "right" ? orient : "top";
    return lvl === "compact" ? "compact" : at;
  }

  /**
   * The scale's legend as G1's placement reads a legend (R.frames.legend): {place, drawn, layout(h, boxW, boxH),
   * prims(h, lay, ox, oy, out)}, or null without a scale or a place. In a row: lo's text, the seven steps without gaps
   * (step 1 first), hi's text. In a column (left, right): hi's text, the steps from 7 down to 1, lo's text. A step is
   * legendKey high and legendStepRatio × that wide in a row, the other way round in a column; legendKeyGap between a
   * number and the steps. A legend wider or higher than its box is not drawn (`empty`); the marks keep their colours.
   * Its group is role legend with legend null: no button, no switch. `drawn` stays empty: it hides no series.
   * opts = {place, label}: the place (place() above) and the group's name (the shade's field).
   */
  function legend(scale, opts) {
    if (scale === null || opts.place === null) return null;
    var at = opts.place, side = at === "left" || at === "right";
    var lg = {
      place: at,
      drawn: {},
      layout: function (h, boxW, boxH) {
        var key = h.em("legendKey"), long = key * look("legendStepRatio"), gap = h.em("legendKeyGap"), row = look("lineHeight") * h.text;
        var lw = h.width(scale.loText, h.text), hw = h.width(scale.hiText, h.text);
        var w = side ? Math.max(key, lw, hw) : lw + gap + STEPS * long + gap + hw;
        var ht = side ? row + gap + STEPS * long + gap + row : row;
        var fits = w <= boxW + 1e-9 && ht <= boxH + 1e-9;
        return { place: at, w: fits ? w : 0, h: fits ? ht : 0, drawn: {}, empty: !fits, key: key, long: long, gap: gap, row: row, lw: lw };
      },
      prims: function (h, lay, ox, oy, out) {
        var kids = [], size = h.text, ink = "--chart-ink-2";
        var text = function (x, cy, s) {
          kids.push(h.label({ role: "legend", x: x, y: h.middle(cy, size), text: s, size: size, ink: ink }));
        };
        // Each step's edges from the first one's start, so neighbours share an edge after rounding (no gap, no overlap).
        var cell = function (n, start, i) {
          var tok = KINDS[scale.kind].prefix + n, a = r2(start + i * lay.long), b = r2(start + (i + 1) * lay.long);
          return { tok: tok, a: a, len: b - a };
        };
        if (!side) {
          var cy = oy + lay.row / 2, x0 = ox + lay.lw + lay.gap;
          text(ox, cy, scale.loText);
          for (var i = 0; i < STEPS; i++) {
            var c = cell(i + 1, x0, i);
            kids.push(P.rect({ role: "legend", x: c.a, y: r2(cy - lay.key / 2), w: c.len, h: lay.key, fill: c.tok, color: h.theme.css(c.tok) }));
          }
          text(x0 + STEPS * lay.long + lay.gap, cy, scale.hiText);
        } else {
          var y0 = oy + lay.row + lay.gap;
          text(ox, oy + lay.row / 2, scale.hiText);
          for (var j = 0; j < STEPS; j++) {
            var d = cell(STEPS - j, y0, j);
            kids.push(P.rect({ role: "legend", x: ox, y: d.a, w: lay.key, h: d.len, fill: d.tok, color: h.theme.css(d.tok) }));
          }
          text(ox, y0 + STEPS * lay.long + lay.gap + lay.row / 2, scale.loText);
        }
        P.group(out, { role: "legend", legend: null, label: opts.label === undefined ? null : opts.label }, kids);
        return out;
      },
    };
    return lg;
  }

  R.colour = { STEPS: STEPS, NONE: NONE, sequential: sequential, step: step, token: token, place: place, legend: legend };
})(JsRendered);
