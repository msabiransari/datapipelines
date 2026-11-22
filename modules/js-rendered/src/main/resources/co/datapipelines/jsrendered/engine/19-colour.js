// 19-colour.js : colour by value (record §4.4 `shade`, G3; look contract §3.3, §4.2; brief 1050 D4, D5). A colour
// scale over the aggregates of the marks a draw draws, the step and the token of a value on it, and the scale's legend.
// A scale is an object with a `kind`; `step` and `token` branch on it in ONE place each (the table KINDS), so the
// two-sided scale (lane G5, the look contract's §4.7) adds a kind here and changes no caller.
//
// A scale: {kind: "sequential", lo, hi, loText, hiText}. lo and hi (numbers) place the steps; loText and hiText are the
// texts written for the two ends, each from the end aggregate's EXACT text where it has one, else from its float, by
// the record's one number rule (§4.7; R.text.value without a format). A float is never the source of a label where an
// exact text exists.
// Lane G5 (brief 1053 D6 to D8; the look contract's §3.7 "The two-sided scale", LA18) adds the kind "diverging":
// {kind: "diverging", c, m, h, loText, hiText}. c is the middle (the shade's `mid`, zero where none is given); m is the
// largest distance |num − c| of a drawn value (a number, possibly not finite where the subtraction overflows); h is the
// largest |num ÷ 2 − c ÷ 2| (always finite), the divisor of the halved form. loText and hiText are the texts of c − m
// (step 1) and c + m (step 7); either is null where that end is not a finite number and no exact text gives it, and
// then the legend is not drawn. R.colour.of(shade, values) is the one place that chooses the kind from the document.
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

  /** The decimal value of an exact text as a BigInt at `scale` fraction digits (the text's own digits, never fewer). */
  function scaled(m, scale) {
    var f = m[3] || "";
    while (f.length < scale) f += "0";
    return BigInt(m[1] + m[2] + f);
  }

  /** A BigInt at `scale` fraction digits written as a plain decimal text (R.data's form: a minus or none, digits). */
  function decimalText(n, scale) {
    var neg = n < BigInt(0), d = (neg ? -n : n).toString();
    while (d.length <= scale) d = "0" + d;
    var t = scale === 0 ? d : d.slice(0, d.length - scale) + "." + d.slice(d.length - scale);
    return (neg ? "-" : "") + t;
  }

  /**
   * The mirror 2 × c − f of the far value's exact text, computed exactly in decimal (brief 1053 D7), or null where c's
   * own text is not a plain decimal (an exponent: then the caller takes the end from numbers).
   */
  function mirrorText(c, fText) {
    var x = DECIMAL.exec(String(c)), y = DECIMAL.exec(fText);
    if (x === null || y === null) return null;
    var scale = Math.max((x[3] || "").length, (y[3] || "").length);
    return decimalText(BigInt(2) * scaled(x, scale) - scaled(y, scale), scale);
  }

  /**
   * The two-sided scale over `values` (aggregates {num, exact}, or null) around the middle `mid` (a finite number; zero
   * where absent), or null when no value is finite. m is the largest distance from c of a drawn value, so the scale runs
   * from c − m to c + m and step 4 always holds the middle. The two end texts (D7): an end where a drawn value stands
   * takes that value as its source as the sequential ends do (its exact text where it has one; of several with one num
   * the one further out); the other end is the mirror of the far value f, 2 × c − f, exactly in decimal where f has an
   * exact text and c is a plain decimal, else the number c − m or c + m; with m of zero both are c's.
   */
  function diverging(values, mid) {
    var c = mid === undefined || mid === null ? 0 : mid;
    if (!finite(c)) throw new Error("colour: the two-sided scale's middle is not a finite number");
    var lo = null, hi = null, any = false, m = 0, h = 0;
    for (var i = 0; i < values.length; i++) {
      var v = values[i];
      if (v === null || v === undefined || !finite(v.num)) continue;
      any = true;
      var d = v.num < c ? c - v.num : v.num - c, d2 = v.num / 2 - c / 2;
      if (d2 < 0) d2 = -d2;
      if (d > m) m = d;
      if (d2 > h) h = d2;
    }
    if (!any) return null;
    var cText = T.value(c);
    if (m === 0) return { kind: "diverging", c: c, m: 0, h: 0, loText: cText, hiText: cText };
    // The values standing at either end: their halved distance is h (equal halved distances are equal distances).
    for (var j = 0; j < values.length; j++) {
      var w = values[j];
      if (w === null || w === undefined || !finite(w.num)) continue;
      var e2 = w.num / 2 - c / 2;
      if (e2 < 0) e2 = -e2;
      if (e2 !== h) continue;
      if (w.num < c && (lo === null || further(w, lo, -1))) lo = w;
      if (w.num > c && (hi === null || further(w, hi, 1))) hi = w;
    }
    var mirror = function (f, sign) {
      if (hasExact(f)) {
        var t = mirrorText(c, f.exact);
        if (t !== null) return T.value(t);
      }
      var n = c + sign * m;
      return finite(n) ? T.value(n) : null;
    };
    var loText = lo !== null ? T.value(source(lo)) : mirror(hi, -1);
    var hiText = hi !== null ? T.value(source(hi)) : mirror(lo, 1);
    return { kind: "diverging", c: c, m: m, h: h, loText: loText, hiText: hiText };
  }

  /**
   * The step of v on a two-sided scale, by the contract's rule in its order: q = 3 × (v − c) ÷ m; a = q without its
   * sign; k = how many of the borders 0.5, 1.5 and 2.5 a has reached (three comparisons: a half goes away from the
   * middle, and nothing is clamped); the step is 4 + k above the middle and 4 − k below it. Never Math.round (−0.5 → 0)
   * nor Math.floor(a + 0.5) (0.49999999999999994 + 0.5 is 1). With m of zero no division is made: step 4. Where v − c,
   * 3 × (v − c) or m is not finite, q = 3 × ((v ÷ 2 − c ÷ 2) ÷ h), which never overflows. A q that is not a finite
   * number is a defect of this file and throws, naming the ratio (0 ÷ 0 would fail all three comparisons and read 4).
   */
  function divergingStep(scale, v) {
    var c = scale.c, m = scale.m;
    if (m === 0) return MIDDLE;
    var d = v - c, t = 3 * d;
    var q = finite(d) && finite(t) && finite(m) ? t / m : 3 * ((v / 2 - c / 2) / scale.h);
    if (!finite(q)) throw new Error("colour: the two-sided scale's ratio 3 × (v − c) ÷ m is not a finite number (v " + v + ", c " + c + ", m " + m + ")");
    var a = q < 0 ? -q : q;
    var k = (a >= 0.5 ? 1 : 0) + (a >= 1.5 ? 1 : 0) + (a >= 2.5 ? 1 : 0);
    return q < 0 ? MIDDLE - k : MIDDLE + k;
  }

  /** Each kind's step rule and the prefix of its ramp's tokens. */
  var KINDS = {
    sequential: { step: sequentialStep, prefix: "--chart-seq-" },
    diverging: { step: divergingStep, prefix: "--chart-div-" },
  };

  /**
   * The scale a shade asks for (brief 1053 D6), the one place that chooses: `scale` "sequential" → sequential(values);
   * "diverging" → diverging(values, shade.mid). A type calls this and never chooses itself.
   */
  function of(shade, values) {
    if (shade.scale === "sequential") return sequential(values);
    if (shade.scale === "diverging") return diverging(values, shade.mid);
    throw new Error("colour: no scale " + shade.scale);
  }

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
   * opts = {place, label}: the place (place() above) and the group's name (the shade's field). A two-sided scale
   * (brief 1053 D8) takes the same form with its own ramp's tokens (step 1 at the low end's text); no middle number.
   */
  function legend(scale, opts) {
    // A two-sided scale whose end is not a finite number and has no exact text has no end text: no legend (D7).
    if (scale === null || opts.place === null || scale.loText === null || scale.hiText === null) return null;
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

  R.colour = { STEPS: STEPS, NONE: NONE, sequential: sequential, diverging: diverging, of: of, step: step, token: token, place: place, legend: legend };
})(JsRendered);
