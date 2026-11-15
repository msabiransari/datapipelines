// 07-scales.js : nice ticks on the 1-2-2.5-5 ladder and the linear scale (record §4.6, O-16). Powers of ten come from
// a table parsed from decimal text and a number's decade is found by comparison: no logarithm, no power function
// (R-BAN). Every coordinate leaves rounded to two decimals (r2), so two writers of the same numbers agree. Translated
// from lane 977's 05-scales.js (adapted: a range float64 cannot span falls back to its clamped endpoints, as the
// product's chart core does (#860 R1); the band and point scales live in the templates that use them).
(function (R) {
  "use strict";
  var MAX = Number.MAX_VALUE;

  function r2(v) {
    return Math.round(v * 100) / 100 + 0;
  }

  // Number("1e" + k) parses decimal text, which every runtime rounds correctly, so POW10[k + 323] = 10^k exactly as a
  // double in Node, a browser and GraalJS alike.
  var POW10 = [];
  for (var k = -323; k <= 308; k++) POW10.push(Number("1e" + k));

  /** 10^k for an integer k in [-323, 308]. */
  function pow10(k) {
    return POW10[k + 323];
  }

  /** The integer k with 10^k <= v < 10^(k+1), for a finite v > 0 (binary search over POW10). */
  function decade(v) {
    var lo = 0, hi = POW10.length - 1;
    if (!(v >= POW10[0])) return -323;
    if (v >= POW10[hi]) return 308;
    while (hi - lo > 1) {
      var mid = (lo + hi) >> 1;
      if (POW10[mid] <= v) lo = mid;
      else hi = mid;
    }
    return lo - 323;
  }

  function niceStep(raw) {
    var p = pow10(decade(raw));
    var f = raw / p;
    var n = f <= 1.0000001 ? 1 : f <= 2.0000001 ? 2 : f <= 2.5000001 ? 2.5 : f <= 5.0000001 ? 5 : 10;
    return n * p;
  }

  function clamp(v) {
    return v > MAX ? MAX : v < -MAX ? -MAX : v;
  }

  /** The bounded fallback: the clamped endpoints, with a midpoint when their span would overflow. */
  function bounded(lo, hi) {
    var a = clamp(lo), b = clamp(hi);
    if (!(b > a)) b = a < MAX ? Math.min(a + 1, MAX) : a;
    var span = b - a;
    if (span === Infinity) return { lo: a, hi: b, step: b / 2 - a / 2, ticks: [a, a / 2 + b / 2, b] };
    return { lo: a, hi: b, step: span, ticks: [a, b] };
  }

  /** The smallest nice range that contains [min, max] in at most `intervals` steps: {lo, hi, step, ticks}. */
  function nice(min, max, intervals) {
    if (!(max > min)) {
      if (min === 0 || min !== min) {
        min = 0;
        max = 1;
      } else if (min > 0) min = 0;
      else max = 0;
    }
    var n = intervals < 1 ? 1 : intervals;
    for (var tries = 0; tries < 4; tries++, n = n > 1 ? n - 1 : 1) {
      var span = max - min;
      if (!(span < Infinity)) return bounded(min, max);
      var step = niceStep(span / n);
      var first = Math.floor(min / step + 1e-9), last = Math.ceil(max / step - 1e-9);
      if (!(step < Infinity) || !(first > -Infinity) || !(last < Infinity)) return bounded(min, max);
      var count = last - first;
      if (count > intervals && n > 1) continue;
      var decimals = Math.max(0, 1 - decade(step));
      var scale = pow10(decimals > 308 ? 308 : decimals);
      var ticks = [];
      for (var i = 0; i <= count; i++) {
        var t = Math.round((first + i) * step * scale) / scale + 0;
        if (!(t > -Infinity && t < Infinity)) return bounded(min, max);
        ticks.push(t);
      }
      if (count > intervals) return bounded(min, max);
      return { lo: ticks[0], hi: ticks[ticks.length - 1], step: step, ticks: ticks };
    }
    return bounded(min, max);
  }

  /** The ticks of a fixed domain [lo, hi] (an author's scale.domain): the nice values inside it, the ends kept. */
  function within(lo, hi, intervals) {
    var n = nice(lo, hi, intervals), eps = (hi - lo) * 1e-9;
    var ticks = n.ticks.filter(function (t) {
      return t >= lo - eps && t <= hi + eps;
    });
    if (ticks.length === 0) ticks = [lo, hi];
    return { lo: lo, hi: hi, step: n.step, ticks: ticks };
  }

  /** v ↦ the range position; a domain of zero width maps everything to its start. */
  function linear(d0, d1, r0, r1) {
    var dd = d1 - d0;
    return function (v) {
      if (!(dd > 0) && !(dd < 0)) return r2(r0);
      var t = d1 / 2 - d0 / 2 === 0 ? 0 : (v / 2 - d0 / 2) / (d1 / 2 - d0 / 2);
      return r2(r0 + t * (r1 - r0));
    };
  }

  R.scales = { r2: r2, pow10: pow10, decade: decade, niceStep: niceStep, nice: nice, within: within, linear: linear };
})(JsRendered);
