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
   * the largest and the smallest value have that room inside the plot. whole === true (a count, #1102): every tick is a
   * whole number (wholeTicks); anything else leaves the ticks as they were.
   */
  function valueAxis(lvl, ext, domain, length, format, room, whole) {
    var spacing = look(lvl === "compact" ? "tickSpacingCompact" : "tickSpacing");
    var intervals = Math.max(1, Math.floor(length / spacing));
    var n = wholeTicks(domain, ext[0], ext[1], intervals, whole);
    for (var i = 0; !domain && room > 0 && i < 3; i++) {
      var span = n.hi - n.lo;
      if (!(span > 0 && span < Infinity) || !(length > room)) break;
      var u = (room * span) / length;
      var hi = ext[1] > 0 ? ext[1] + u : ext[1], lo = ext[0] < 0 ? ext[0] - u : ext[0];
      if (n.hi >= hi && n.lo <= lo) break;
      n = wholeTicks(null, Math.min(lo, n.lo), Math.max(hi, n.hi), intervals, whole);
    }
    return { lo: n.lo, hi: n.hi, ticks: n.ticks, texts: T.ticks(n.ticks, format) };
  }

  /** Every number of the list is a whole number. */
  function allWhole(t) {
    for (var i = 0; i < t.length; i++) if (Math.floor(t[i]) !== t[i]) return false;
    return true;
  }

  /**
   * The nice ticks over [lo, hi] in at most `intervals` steps (the author's domain's ticks when domain is given). With
   * whole === true the first answer whose ticks are all whole numbers, tried from `intervals` steps down to one: the
   * finest whole step the room allows (a count of 3 reads 0 1 2 3, never 0 0.5 ... 3). The one-step answer of a range
   * that is not whole itself (2.3: step 2.5) is the range widened to whole ends. An author's domain whose ends are not
   * both whole numbers is the author's own range: it is drawn as written, as without whole. This is a linear value
   * axis rule; the log axis never asks. A position axis's o.whole means uncut labels, a separate rule.
   */
  function wholeTicks(domain, lo, hi, intervals, whole) {
    var w = whole === true && (!domain || allWhole(domain));
    var n = domain ? S.within(domain[0], domain[1], intervals) : S.nice(lo, hi, intervals);
    for (var k = intervals - 1; w && k >= 1 && !allWhole(n.ticks); k--) n = domain ? S.within(domain[0], domain[1], k) : S.nice(lo, hi, k);
    if (w && !domain && !allWhole(n.ticks)) n = S.nice(Math.floor(lo), Math.ceil(hi), 1);
    return n;
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
   * The step rule keeps every k-th label, k the least number for which k × the least distance between two labels is at
   * least the room a label asks plus the label gap: on a row of whole labels the widest label's whole width, so a kept
   * label never lacks room beside its neighbours (lane 1087); on a row whose labels may be cut min(the widest label,
   * labelMinEm). When the rule ends at k = n on a row of whole labels the first label is kept alone and has no neighbour:
   * its room is the frame's edges alone. A kept label is then left out only where the frame's edge leaves it less than
   * its width, or by the corner rule; the rule counts from the first label and has no second choice, so a row whose
   * kept labels all stand at the frame's edges is empty even when a label it did not keep would fit (issue #1092).
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
    var k = 1, want = (o.whole === true ? widest : Math.min(widest, look("labelMinEm") * s)) + gap;
    while (k * step < want && k < n) k++;
    var shownCount = Math.ceil(n / k);
    var alone = o.whole === true && k >= n;
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
      var roomJ = Math.min(alone ? Infinity : k * step - gap, 2 * (x - x0), 2 * (x1 - x));
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
  /** The smallest k whose 10^k is a normal double (10^-308 is subnormal); below it significant() raises first. */
  var MIN_NORMAL_POWER = -307;
  /** The power significant() raises a value of a subnormal decade by: a value below 10^-290 times 10^300 stays below 10^10. */
  var RAISE = 300;

  /**
   * Where a value lies on an axis {lo, hi, log}, as a share of it from lo (0) to hi (1); 0 on an axis of no width. Two
   * finite ends whose difference a double cannot hold (-1e308 to 1e308) are halved first, as S.linear does: halving a
   * number that large is exact, so the share is the one the two ends define and never NaN (brief 1051 r3, R2). An end
   * that is not finite keeps the plain difference, so such an input fails as it did before. A finite tick whose own
   * distance from lo overflows on a finite span (a tick past hi beside lo = -MAX) is halved too (brief 1051 r5, D3: the
   * left scale admits ticks a hair past its ends; valueAxis cannot hand this one today, and the second axis does not
   * rely on that).
   */
  function shareOf(ax, v) {
    if (ax.log === true) {
      var l0 = S.log10(ax.lo), dl = S.log10(ax.hi) - l0;
      return dl > 0 ? (S.log10(v) - l0) / dl : 0;
    }
    var d = ax.hi - ax.lo;
    if ((d < Infinity && !(v - ax.lo === Infinity || v - ax.lo === -Infinity)) || !finite(ax.lo, ax.hi) || !finite(v, v)) return d > 0 ? (v - ax.lo) / d : 0;
    var d2 = ax.hi / 2 - ax.lo / 2;
    return d2 > 0 ? (v / 2 - ax.lo / 2) / d2 : 0;
  }

  /**
   * The value at share t of a linear range [lo, hi]. Where hi - lo overflows (two finite ends of opposite signs past
   * half a double's range), it is lo × (1 - t) + hi × t: each term is at most the larger end, the two have opposite
   * signs, so the sum cannot overflow, and t = 0 and t = 1 give lo and hi exactly (brief 1051 r3, R2). Otherwise lo + t ×
   * (hi - lo), and at t = 1 hi itself: lo + (hi - lo) need not read hi (-MAX + (MAX - 1) is 0, not -1), and an
   * author's end is returned as written (brief 1051 r4, the boundary bank). An end that is not finite keeps lo + t ×
   * (hi - lo): such a domain is refused downstream with the values, and so the message, it gave before (the save
   * refuses it first, chart.scale.domain_invalid).
   */
  function lerp(lo, hi, t) {
    var d = hi - lo;
    if (!finite(lo, hi)) return lo + t * d;
    if (t === 1) return hi;
    if (t < 0 || t > 1) return pastEnd(lo, hi, d, t);
    return d < Infinity ? lo + t * d : lo * (1 - t) + hi * t;
  }

  /** The largest and the smallest positive double: where the second axis's value past an end of its range stops. */
  var MAX = Number.MAX_VALUE, MIN = Number.MIN_VALUE;

  /**
   * The value at a share t below 0 or above 1 of a finite linear range [lo, hi] (d = hi - lo, Infinity where it
   * overflows): the scale extended past the end t passes, end + u × span, u = t (below) or t - 1 (above), the span
   * halved where it overflows; a value past the doubles reads as the nearest one, ±MAX. The left scale admits a tick
   * within its own tolerance past an end of its domain (S.within keeps ticks up to 1e-9 of the span outside it), so a
   * share can lie a little below 0 or above 1; both lerp formulas then overflow where the range reaches ±MAX (brief
   * 1051 r5, R6: left [0, 0.9999999999] beside right [-MAX, MAX] or [1e308, MAX] threw), and lo + t × d reads Infinity
   * on [-MAX, -1], whose value there is 1.8e298. Measured from the passed end, u × span is a hair of the span, so the
   * sum overflows only where the exact value lies past the doubles. Inside [0, 1] neither lerp formula can overflow,
   * and a share there keeps the value it had.
   */
  function pastEnd(lo, hi, d, t) {
    var u = t < 0 ? t : t - 1, v = (t < 0 ? lo : hi) + (d < Infinity ? u * d : 2 * u * (hi / 2 - lo / 2));
    return v === Infinity ? MAX : v === -Infinity ? -MAX : v;
  }

  /**
   * The value at share t of a log range [lo, hi] (0 < lo < hi, both finite): lo and hi themselves at t = 0 and t = 1,
   * else 10^(log lo + t × (log hi − log lo)) bounded to [lo, hi]. The bound matters only within a few units in the last
   * place of an end, where the approximate inverse can step past it: past the largest double that read Infinity, and
   * below the smallest subnormal 0 (brief 1051 r4, R4: [1e308, MAX] and [MIN_VALUE, 1e-322], ranges the save admits).
   * So every value is positive and finite, the authored ends come back exactly, and each lies within one double of
   * the exact value at its share (a subnormal has few doubles near it: there that one step is the format's). A share
   * past an end (the left scale's tolerance, see pastEnd) is read on the scale extended past that end, bounded to
   * the positive finite doubles, MIN_VALUE to MAX, so a tick a hair outside the left domain stands on its own position
   * and not on the edge's (brief 1051 r5, R6).
   */
  function logLerp(lo, hi, l0, l1, t) {
    if (t === 0) return lo;
    if (t === 1) return hi;
    var v = S.exp10(l0 + t * (l1 - l0)), a = t < 0 ? MIN : lo, b = t > 1 ? MAX : hi;
    return v < a ? a : v > b ? b : v;
  }

  /** Both ends are finite numbers. */
  function finite(lo, hi) {
    return lo > -Infinity && lo < Infinity && hi > -Infinity && hi < Infinity;
  }

  /** The extent of a linear right axis with no domain, widened as S.nice widens one value (or none) first. */
  function linearExtent(ext) {
    var lo = ext && typeof ext[0] === "number" ? ext[0] : NaN, hi = ext && typeof ext[1] === "number" ? ext[1] : NaN;
    if (!(lo > -Infinity && lo < Infinity && hi > -Infinity && hi < Infinity)) return [0, 1];
    if (!(hi > lo)) {
      if (lo === 0) return [0, 1];
      return lo > 0 ? [0, lo] : [lo, 0];
    }
    // Two ends the ticks cannot tell apart are one value, as on the single axis (owner 2026-11-21, #1063).
    if (S.oneValue(lo, hi)) return lo > 0 ? [0, hi] : hi < 0 ? [lo, 0] : [lo, hi];
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
   * top-edge value that holds its high end. null where no step of the climb does or a number is not finite: case (c)
   * then, whose range is the right axis's own nice range and so holds the extent, both signs of it included. A
   * candidate holds the extent only within `tol`, a share SAME of the EXTENT's own span (rounding noise of the data's
   * scale), never of the candidate's span: a share of a span that grows with the climb accepted, at 0..1e9, a range
   * that leaves out the whole negative half of [-1, 1] (brief 1051 r3, R1). Two left ticks on the plot's two edges
   * beside an extent across zero are such a case: the multiples k × s and (k + 1) × s never straddle zero, so no step
   * of the ladder holds it and the climb ends in case (c).
   */
  function caseA(ext, n, f0, df, format) {
    var e = linearExtent(ext), e0 = e[0], e1 = e[1], tol = SAME * (e1 - e0);
    var s = S.niceStep((e1 - e0) * df);
    for (var c = 0; c < LADDER_CLIMB && s > 0 && s < Infinity; c++, s = nextStep(s)) {
      // k: the largest multiple whose bottom-edge value holds e0; the next one is taken where only rounding of the
      // division put it out (0.3 / 0.1 is 2.9999999999999996, and 3 × 0.1 holds 0.3 within tol).
      var span = s / df, k = Math.floor((e0 + f0 * span) / s);
      if ((k + 1) * s - f0 * span <= e0 + tol) k++;
      var lo = k * s - f0 * span, hi = lo + span;
      if (!(lo > -Infinity && hi < Infinity)) return null;
      if (lo > e0 + tol || hi < e1 - tol) continue;
      // The tick rule's rounding to the step's decimals takes off a product's last-place noise (3 × 0.1); a step below
      // 10^-307 has more decimals than the power table can scale by, and rounding at 10^308 instead would cut every
      // value to a multiple of 10^-308 (2.2e-308 read 3e-308, a subnormal 0): there the multiple is kept as it is
      // (brief 1051 r4, the boundary bank).
      var decimals = Math.max(0, 1 - S.decade(s)), scale = decimals > 308 ? 0 : S.pow10(decimals), ticks = [];
      for (var j = 0; j < n; j++) {
        var t = scale > 0 ? Math.round((k + j) * s * scale) / scale + 0 : (k + j) * s + 0;
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
    // Below the table too: the smallest double lies under 10^-323, and no power of ten holds it (#1060).
    if (S.pow10(k0) > min) return null;
    var ticks = [], texts = [];
    for (var j = 0; j < n; j++) {
      ticks.push(S.pow10(k0 + j * m));
      texts.push(T.ticks([ticks[j]], format)[0]);
    }
    return { lo: ticks[0], hi: ticks[n - 1], ticks: ticks, texts: texts, log: true, rule: "b" };
  }

  /**
   * A value written with d significant digits, as the double its decimal text reads as (0 stays 0). Total over finite
   * values: where 10^e is below a normal double (a value near the smallest subnormals asks for e down to -340, past the
   * power table's -323), the value is first raised by 10^300 and then divided by 10^(e + 300), both normal powers of the
   * table, so q is a whole number of d digits. A value next to the largest double can round up past it (1.797…e308 at
   * three digits reads 1.80e308, Infinity): there the d digits are taken one unit lower, the largest such value below
   * it. So every finite value gives a finite one (brief 1051 r3, R3). Four operations only (O-16).
   */
  function significant(v, d) {
    if (!(v !== 0 && v > -Infinity && v < Infinity)) return v;
    var a = v < 0 ? -v : v, e = S.decade(a) - (d - 1);
    var q = e >= MIN_NORMAL_POWER ? Math.round(a / S.pow10(e)) : Math.round((a * S.pow10(RAISE)) / S.pow10(e + RAISE));
    var r = Number(q + "e" + e);
    if (!(r < Infinity)) r = Number(q - 1 + "e" + e);
    return (v < 0 ? -1 : 1) * r;
  }

  /**
   * Case (c): the right axis's own range (A.valueAxis's or A.logAxis's lo and hi over the same length, or its domain's
   * two ends) laid over the plot, and at each left position the value its scale gives there, held as it is; the texts
   * are those values at three significant digits, one more for all while two neighbours would share one.
   */
  function caseC(lvl, S2, ext, length, shares) {
    var own = S2.log === true ? logAxis(lvl, ext, S2.domain, length, S2.format) : valueAxis(lvl, ext, S2.domain, length, S2.format, 0);
    var lo = own.lo, hi = own.hi, l0 = S2.log === true ? S.log10(lo) : 0, l1 = S2.log === true ? S.log10(hi) : 0, ticks = [];
    for (var j = 0; j < shares.length; j++) ticks.push(S2.log === true ? logLerp(lo, hi, l0, l1, shares[j]) : lerp(lo, hi, shares[j]));
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
