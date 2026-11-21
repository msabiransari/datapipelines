// 11-axes.js : the shared layout's second half (record §4.6): the value axis (nice ticks, at most one per
// tickSpacing px), the x label row, and the cartesian frame a template draws its marks into. THE X LABEL ROW IS ONE
// FUNCTION (brief D7): it takes labels as a list of {text, under, x}, the frame, the plot and the box of the value
// axis's lowest tick label, thins, rotates or cuts by §4.6 and applies the corner rule (#981); nothing in it knows
// where the list came from (bar's bands, the line family's time and number ticks, lane 985's D9 harness).
(function (R) {
  "use strict";
  var S = R.scales, T = R.text, C = R.chrome, look = R.tables.look, r2 = S.r2;
  /** [cos, sin] of the angles a rotated label may take, exact from square roots (no sine function, O-16). */
  var ANGLES = { 30: [Math.sqrt(0.75), 0.5], 45: [Math.sqrt(0.5), Math.sqrt(0.5)], 60: [0.5, Math.sqrt(0.75)] };

  function angle() {
    var t = ANGLES[look("rotateAngle")];
    if (t === undefined) throw new Error("a rotated label's angle is 30, 45 or 60 degrees");
    return t;
  }

  function overlaps(a, b) {
    return a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1;
  }

  /**
   * The value axis over [lo, hi] (or the author's domain) for an axis `length` px long: {lo, hi, ticks, texts}. room:
   * px a value label needs beyond a data end; without an author's domain the range grows (at most three times) until
   * the largest and the smallest value have that room inside the plot.
   */
  function valueAxis(lvl, ext, domain, length, format, room) {
    var spacing = look(lvl === "compact" ? "tickSpacingCompact" : "tickSpacing");
    var intervals = Math.max(1, Math.floor(length / spacing));
    var n = domain ? S.within(domain[0], domain[1], intervals) : S.nice(ext[0], ext[1], intervals);
    for (var i = 0; !domain && room > 0 && i < 3; i++) {
      var span = n.hi - n.lo;
      if (!(span > 0 && span < Infinity) || !(length > room)) break;
      var u = (room * span) / length;
      var hi = ext[1] > 0 ? ext[1] + u : ext[1], lo = ext[0] < 0 ? ext[0] - u : ext[0];
      if (n.hi >= hi && n.lo <= lo) break;
      n = S.nice(Math.min(lo, n.lo), Math.max(hi, n.hi), intervals);
    }
    return { lo: n.lo, hi: n.hi, ticks: n.ticks, texts: T.ticks(n.ticks, format) };
  }

  /**
   * A log value axis (record §4.4 G4; brief 989 D4) over ext = [smallest positive value, largest value] (null, null when
   * no value is positive) or the author's domain, `length` px long: {lo, hi, ticks, texts, log: true}. Ticks are 1, 2, 5 ×
   * a power of ten, at most one per tickSpacing px (R.scales.logNice); each tick's text is its own value written
   * exactly, since a log axis's ticks share no step.
   */
  function logAxis(lvl, ext, domain, length, format) {
    var spacing = look(lvl === "compact" ? "tickSpacingCompact" : "tickSpacing");
    var n = S.logNice(ext[0], ext[1], Math.max(1, Math.floor(length / spacing)), domain);
    var texts = [];
    for (var i = 0; i < n.ticks.length; i++) texts.push(T.ticks([n.ticks[i]], format)[0]);
    return { lo: n.lo, hi: n.hi, ticks: n.ticks, texts: texts, log: true };
  }

  /** The scale of an axis {lo, hi, log?} onto [r0, r1]: the log scale for a log axis, else the linear one. */
  function scaleOf(ax, r0, r1) {
    return ax.log === true ? S.log(ax.lo, ax.hi, r0, r1) : S.linear(ax.lo, ax.hi, r0, r1);
  }

  /**
   * The x label row. o = {labels: [{text, under, x}] (centres, ascending), bounds: {x0, x1} (the room a label may
   * use left and right: the frame inside its edge, less a side legend), plot: {x, y, w, h},
   * corner: the lowest value tick label's box {x0, y0, x1, y1} or null, cornerIsFirst: true when the row's first
   * label IS that tick (a horizontal bar: never cut it), rotate: "never" | "allow" | "force", room: px free below the
   * row, maxLen: optional, the length a rotated label is cut to when the layout has less room than the look's
   * rotateMaxEm, whole: true on a position axis (brief 989 D5), where a tick label is drawn whole or not at all: a cut
   * tick would name a value it does not show}. Returns {texts, drawn: [label index], height, rotated, corner: "clear" | "moved" | "cut", need: the px the
   * corner asked to move down}.
   */
  function labelRow(h, o) {
    var s = h.text, a = h.ascent(s), d = h.descent(s), gap = h.em("labelGap"), tg = h.em("tickGap");
    var labels = o.labels, n = labels.length, x0 = o.bounds.x0, x1 = o.bounds.x1;
    var res = { texts: [], drawn: [], height: 0, rotated: false, corner: "clear", need: 0 };
    // The corner rule keeps the look's clearance around the lowest tick label's box (a browser draws a font's ascent
    // and descent rounded to whole pixels, so two boxes that only touch in the table's layout overlap when drawn).
    var cz = null;
    if (o.corner !== null) {
      var clear = h.em("cornerClear");
      cz = { x0: o.corner.x0 - clear, y0: o.corner.y0 - clear, x1: o.corner.x1 + clear, y1: o.corner.y1 + clear };
    }
    if (n === 0) return res;
    var widths = [], widest = 0, step = o.plot.w, anyUnder = false;
    for (var i = 0; i < n; i++) {
      widths.push(h.width(labels[i].text, s));
      if (widths[i] > widest) widest = widths[i];
      if (i > 0 && labels[i].x - labels[i - 1].x < step) step = labels[i].x - labels[i - 1].x;
      if (labels[i].under !== null && labels[i].under !== undefined) anyUnder = true;
    }
    if (!(step > 0)) step = 1;
    var k = 1, want = Math.min(widest, look("labelMinEm") * s) + gap;
    while (k * step < want && k < n) k++;
    var shownCount = Math.ceil(n / k);
    var rotated = o.rotate === "force" || (o.rotate === "allow" && 1 - shownCount / n > look("rotateHiddenShare"));
    var top = o.plot.y + o.plot.h + tg;
    if (rotated) {
      var cs = angle(), cos = cs[0], sin = cs[1], perp = h.boxH(s) + gap;
      k = 1;
      while (k * step * sin < perp && k < n) k++;
      var maxLen = rotatedMax(h, o.maxLen);
      if (cz !== null && cz.y1 > top) {
        res.need = cz.y1 - top;
        top = cz.y1;
        res.corner = "moved";
      }
      var longest = 0;
      for (var r = 0; r < n; r += k) {
        var ax = labels[r].x + 0.5 * sin * (a - d), ay = top + cos * a;
        var full = rotatedText(labels[r]);
        var room = Math.min(maxLen, (ax - x0 - a * sin) / cos);
        var shown = room > 0 ? h.fit(full, s, 400, room) : "";
        if (shown === "") continue;
        var sw = h.width(shown, s);
        if (sw > longest) longest = sw;
        res.drawn.push(r);
        res.texts.push(h.label({ role: "tick", x: ax, y: ay, text: full, shown: shown, anchor: "end", size: s, ink: "--chart-muted", rotate: -look("rotateAngle") }));
      }
      res.rotated = true;
      res.height = top - (o.plot.y + o.plot.h) + cos * (a + d) + sin * Math.min(maxLen, rotatedWidest(h, labels));
      return res;
    }
    var placed = [];
    var prevUnder = null;
    for (var j = 0; j < n; j += k) {
      var x = labels[j].x;
      var roomJ = Math.min(k * step - gap, 2 * (x - x0), 2 * (x1 - x));
      var sh = roomJ > 0 ? h.fit(labels[j].text, s, 400, roomJ) : "";
      if (sh === "" || (o.whole === true && sh !== labels[j].text)) continue;
      var under = labels[j].under !== null && labels[j].under !== undefined && (placed.length === 0 || labels[j].under !== prevUnder) ? labels[j].under : null;
      if (labels[j].under !== null && labels[j].under !== undefined) prevUnder = labels[j].under;
      placed.push({ i: j, x: x, shown: sh, w: h.width(sh, s), room: roomJ, under: under });
    }
    var dy = 0;
    if (cz !== null && placed.length > 0) {
      var f = placed[0], box = { x0: f.x - f.w / 2, y0: top, x1: f.x + f.w / 2, y1: top + h.boxH(s) };
      if (overlaps(box, cz)) {
        res.need = cz.y1 - top;
        if (res.need <= o.room || o.cornerIsFirst) {
          dy = res.need;
          res.corner = "moved";
        } else {
          var cutRoom = Math.min(f.room, 2 * (f.x - cz.x1));
          var cut = cutRoom > 0 ? h.fit(labels[f.i].text, s, 400, cutRoom) : "";
          res.corner = "cut";
          if (cut === "" || (o.whole === true && cut !== labels[f.i].text)) placed.shift();
          else {
            f.shown = cut;
            f.w = h.width(cut, s);
          }
        }
      }
    }
    var base = top + dy + a;
    for (var p = 0; p < placed.length; p++) {
      var q = placed[p];
      res.drawn.push(q.i);
      res.texts.push(h.label({ role: "tick", x: q.x, y: base, text: labels[q.i].text, shown: q.shown, anchor: "middle", size: s, ink: "--chart-muted" }));
      if (q.under !== null) {
        var us = h.fit(q.under, s, 400, q.room);
        if (us !== "" && (o.whole !== true || us === q.under)) res.texts.push(h.label({ role: "tick", x: q.x, y: base + look("lineHeight") * s, text: q.under, shown: us, anchor: "middle", size: s, ink: "--chart-muted" }));
      }
    }
    res.height = tg + dy + h.boxH(s) + (anyUnder ? look("lineHeight") * s : 0);
    return res;
  }

  /** A rotated label's text: the label and, when it has one, its second line after a space. */
  function rotatedText(l) {
    return l.under !== null && l.under !== undefined ? l.text + " " + l.under : l.text;
  }

  function rotatedWidest(h, labels) {
    var m = 0;
    for (var i = 0; i < labels.length; i++) m = Math.max(m, h.width(rotatedText(labels[i]), h.text));
    return m;
  }

  /** The length a rotated label is cut to: the look's rotateMaxEm, or less where the layout gives less room. */
  function rotatedMax(h, maxLen) {
    var m = look("rotateMaxEm") * h.text;
    return maxLen === undefined || maxLen === null ? m : Math.min(m, maxLen);
  }

  /** The height labelRow will take before the corner moves it: the reserve a layout makes for the row. */
  function rowReserve(h, labels, rotated, maxLen) {
    var s = h.text, tg = h.em("tickGap"), widest = 0, anyUnder = false;
    for (var i = 0; i < labels.length; i++) {
      if (labels[i].under !== null && labels[i].under !== undefined) anyUnder = true;
      var w = h.width(labels[i].text, s);
      if (w > widest) widest = w;
    }
    if (labels.length === 0) return 0;
    if (!rotated) return tg + h.boxH(s) + (anyUnder ? look("lineHeight") * s : 0);
    var cs = angle();
    return tg + cs[0] * h.boxH(s) + cs[1] * Math.min(rotatedMax(h, maxLen), rotatedWidest(h, labels));
  }

  // ---- the second value axis (brief 1051 D5; look contract §4.7, combo: one grid) ----

  /** How far apart, as a share of the axis, two positions may be and still be one (the left ticks' spacing test). */
  var SAME = 1e-9;
  /** The ladder steps (1, 2, 2.5, 5 × a power of ten) case (a) climbs before it gives way to case (c). */
  var LADDER_CLIMB = 48;

  /** Where a value lies on an axis {lo, hi, log}, as a share of it from lo (0) to hi (1); 0 on an axis of no width. */
  function shareOf(ax, v) {
    if (ax.log === true) {
      var l0 = S.log10(ax.lo), dl = S.log10(ax.hi) - l0;
      return dl > 0 ? (S.log10(v) - l0) / dl : 0;
    }
    var d = ax.hi - ax.lo;
    return d > 0 ? (v - ax.lo) / d : 0;
  }

  /** The extent of a linear right axis with no domain, widened as S.nice widens one value (or none) first. */
  function linearExtent(ext) {
    var lo = ext && typeof ext[0] === "number" ? ext[0] : NaN, hi = ext && typeof ext[1] === "number" ? ext[1] : NaN;
    if (!(lo > -Infinity && lo < Infinity && hi > -Infinity && hi < Infinity)) return [0, 1];
    if (!(hi > lo)) {
      if (lo === 0) return [0, 1];
      return lo > 0 ? [0, lo] : [lo, 0];
    }
    return [lo, hi];
  }

  /** The step after s on the tick rule's ladder (1 → 2 → 2.5 → 5 → 10 times its power of ten). */
  function nextStep(s) {
    var p = S.pow10(S.decade(s)), f = s / p;
    return (f < 1.5 ? 2 : f < 2.25 ? 2.5 : f < 3.75 ? 5 : 10) * p;
  }

  /**
   * Case (a): the right values r0 + j × s at equally spaced left positions f0 + j × df. s is the smallest step of the
   * ladder for which the largest multiple r0 of s whose bottom-edge value holds the extent's low end also gives a
   * top-edge value that holds its high end. null where no step of the climb does (two ticks at both edges with a span
   * across zero) or a number is not finite: case (c) then.
   */
  function caseA(ext, n, f0, df, format) {
    var e = linearExtent(ext), e0 = e[0], e1 = e[1];
    var s = S.niceStep((e1 - e0) * df);
    for (var c = 0; c < LADDER_CLIMB && s > 0 && s < Infinity; c++, s = nextStep(s)) {
      var span = s / df, k = Math.floor((e0 + f0 * span) / s + SAME);
      var lo = k * s - f0 * span, hi = lo + span;
      if (!(lo > -Infinity && hi < Infinity)) return null;
      if (lo > e0 + SAME * span || hi < e1 - SAME * span) continue;
      var decimals = Math.max(0, 1 - S.decade(s)), scale = S.pow10(decimals > 308 ? 308 : decimals), ticks = [];
      for (var j = 0; j < n; j++) {
        var t = Math.round((k + j) * s * scale) / scale + 0;
        if (!(t > -Infinity && t < Infinity)) return null;
        ticks.push(t);
      }
      return { lo: lo, hi: hi, ticks: ticks, texts: T.ticks(ticks, format), log: false, rule: "a" };
    }
    return null;
  }

  /**
   * Case (b): a log right axis whose left ticks are equally spaced from the plot's bottom edge to its top edge (ticks
   * 0…k): the values 10^(k0 + j × m), 10^k0 the record's lower bound (§4.4 G4) and m the smallest whole number from 1
   * that reaches the largest value. null past the power table: case (c) then.
   */
  function caseB(ext, n, format) {
    var min = ext ? ext[0] : null, max = ext ? ext[1] : null;
    if (min === null || !(min > 0)) {
      min = 1;
      max = 10;
    }
    var k0 = S.decade(min), k = n - 1, m = 1;
    while (k0 + k * m <= 308 && S.pow10(k0 + k * m) < max) m++;
    if (k0 + k * m > 308) return null;
    var ticks = [], texts = [];
    for (var j = 0; j < n; j++) {
      ticks.push(S.pow10(k0 + j * m));
      texts.push(T.ticks([ticks[j]], format)[0]);
    }
    return { lo: ticks[0], hi: ticks[n - 1], ticks: ticks, texts: texts, log: true, rule: "b" };
  }

  /** A value written with d significant digits, as the double its decimal text reads as (0 stays 0). */
  function significant(v, d) {
    if (!(v !== 0 && v > -Infinity && v < Infinity)) return v;
    var a = v < 0 ? -v : v, e = S.decade(a) - (d - 1), q = Math.round(a / S.pow10(e));
    return (v < 0 ? -1 : 1) * Number(q + "e" + e);
  }

  /**
   * Case (c): the right axis's own range (A.valueAxis's or A.logAxis's lo and hi over the same length, or its domain's
   * two ends) laid over the plot, and at each left position the value its scale gives there, held as it is; the texts
   * are those values at three significant digits, one more for all while two neighbours would share one.
   */
  function caseC(lvl, S2, ext, length, shares) {
    var own = S2.log === true ? logAxis(lvl, ext, S2.domain, length, S2.format) : valueAxis(lvl, ext, S2.domain, length, S2.format, 0);
    var lo = own.lo, hi = own.hi, l0 = S2.log === true ? S.log10(lo) : 0, l1 = S2.log === true ? S.log10(hi) : 0, ticks = [];
    for (var j = 0; j < shares.length; j++) ticks.push(S2.log === true ? S.exp10(l0 + shares[j] * (l1 - l0)) : lo + shares[j] * (hi - lo));
    var rounded = [];
    for (var d = 3; d <= 17; d++) {
      rounded = [];
      var same = false;
      for (var r = 0; r < ticks.length; r++) {
        rounded.push(significant(ticks[r], d));
        if (r > 0 && rounded[r] === rounded[r - 1]) same = true;
      }
      if (!same) break;
    }
    return { lo: lo, hi: hi, ticks: ticks, texts: T.ticks(rounded, S2.format), log: S2.log === true, rule: "c" };
  }

  /**
   * The second value axis at the right (brief 1051 D5; look contract §4.7, combo): exactly one tick per tick of the left
   * axis `left` = {lo, hi, ticks, log}, at that tick's position, so every right tick lies on a left grid line and there
   * is one grid. S2 = {domain: [lo, hi] | null, format, log}; ext = S2.extent(visible); length = the plot's height in px.
   * Which values are written there is case (a) (left positions equally spaced, a linear right axis, no domain), (b) (a
   * log right axis, no domain, left positions equally spaced from the bottom edge to the top edge) or (c) (every other
   * case, and an (a) or (b) that has no finite answer). Returns {lo, hi, ticks, texts, log, rule}: lo and hi are the
   * values at the plot's bottom and top edges; an author's domain is kept as it is, never widened.
   */
  function secondAxis(lvl, S2, ext, left, length) {
    var n = left.ticks.length, shares = [];
    for (var i = 0; i < n; i++) shares.push(shareOf(left, left.ticks[i]));
    var df = n >= 2 ? shares[1] - shares[0] : 0, even = n >= 2 && df > SAME;
    for (var j = 2; even && j < n; j++) if (Math.abs(shares[j] - shares[0] - j * df) > SAME) even = false;
    var noDomain = S2.domain === null || S2.domain === undefined, out = null;
    if (even && noDomain && S2.log !== true) out = caseA(ext, n, shares[0], df, S2.format);
    else if (even && noDomain && S2.log === true && Math.abs(shares[0]) <= SAME && Math.abs(shares[n - 1] - 1) <= SAME) out = caseB(ext, n, S2.format);
    return out !== null ? out : caseC(lvl, S2, ext, length, shares);
  }

  R.axes = { valueAxis: valueAxis, logAxis: logAxis, scaleOf: scaleOf, labelRow: labelRow, rowReserve: rowReserve, angle: angle, overlaps: overlaps, ANGLES: ANGLES, secondAxis: secondAxis };
})(JsRendered);
