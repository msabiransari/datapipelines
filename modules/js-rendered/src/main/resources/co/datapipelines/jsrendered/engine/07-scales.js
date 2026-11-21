// 07-scales.js : nice ticks on the 1-2-2.5-5 ladder, the linear scale and the log scale (record §4.6, §4.4 G4, O-16). Powers of ten come from
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

  // ---- the logarithm (record §4.4 G4, O-16; brief 989 D4): four operations and comparisons on the POW10 table ----

  // ln 2 and ln 10 as decimal text, parsed alike by every runtime.
  var LN2 = Number("0.6931471805599453"), LN10 = Number("2.302585092994046");
  /** Terms of the series below: z² ≤ 1/9, so 40 terms leave (1/9)^40, far below a double's last bit. */
  var LN_TERMS = 40;

  /** ln m for m in [1, 2): 2 atanh z = 2 (z + z³/3 + z⁵/5 + …), z = (m − 1) / (m + 1) in [0, 1/3). */
  function lnNear1(m) {
    var z = (m - 1) / (m + 1), z2 = z * z, term = z, sum = 0;
    for (var n = 0; n < LN_TERMS; n++) {
      sum += term / (2 * n + 1);
      term *= z2;
    }
    return 2 * sum;
  }

  /**
   * log10 v for a finite v > 0, without a logarithm function: v = m × 10^k by the decade search, m halved (exactly)
   * into [1, 2) j times, so log10 v = k + (j ln 2 + ln m) / ln 10. A power of ten gives its exponent exactly. Every
   * step is an IEEE operation with one correctly rounded result, so Node, a browser and GraalJS agree to the bit (I6).
   * Total, so its loops always end: 0, a negative or NaN gives -Infinity, Infinity gives Infinity (never on a log axis:
   * the model leaves such values out and logNice refuses such a domain).
   */
  function log10(v) {
    if (!(v > 0)) return -Infinity;
    if (v === Infinity) return Infinity;
    var k = decade(v), m = v / pow10(k), j = 0;
    while (m >= 2) {
      m /= 2;
      j++;
    }
    while (m < 1) {
      m *= 2;
      j--;
    }
    return m === 1 && j === 0 ? k : k + (j * LN2 + lnNear1(m)) / LN10;
  }

  /** f × 10^k as the double its decimal text reads as ("2e-3" is 0.002 exactly as parsed). */
  function tenths(f, k) {
    return Number(String(f) + "e" + k);
  }

  /**
   * The ticks of a log axis (G4, D4): a decade's ticks are 1, 2 and 5 × its power of ten; where they are more than
   * `intervals` + 1, the powers of ten alone; then every s-th power, s = 2, 3, 4 … decades (the fewest that fit, so
   * the axis never reaches more than s - 1 decades past the largest value). The lower bound is the
   * author's domain[0], else the largest power of ten at or below the smallest positive value; the upper bound the
   * author's domain[1], else the smallest tick of the chosen set at or above the largest value, so the axis range is
   * the ticks' range (record §4.6) with G4's lower bound. min/max: the smallest and largest positive values (null when
   * none: the axis is 1 to 10). A domain that is not 0 < lo < hi < Infinity (the save refuses it,
   * `chart.scale.domain_log`; a number past a double's range reads as Infinity) is not used: the data's range is.
   * Returns {lo, hi, ticks, log: true}.
   */
  function logNice(min, max, intervals, domain) {
    var limit = (intervals < 1 ? 1 : intervals) + 1;
    if (domain && domain[0] > 0 && domain[0] < domain[1] && domain[1] < Infinity) return logWithin(domain[0], domain[1], limit);
    if (min === null || !(min > 0)) {
      min = 1;
      max = 10;
    }
    var k0 = decade(min), lo = pow10(k0);
    var sets = [[1, 2, 5], [1]];
    for (var a = 0; a < sets.length; a++) {
      var t = ladder(lo, k0, max, sets[a], 1);
      if (t.length <= limit) return { lo: lo, hi: t[t.length - 1], ticks: t, log: true };
    }
    for (var stride = 2; stride <= 700; stride++) {
      var u = ladder(lo, k0, max, [1], stride);
      if (u.length <= limit) return { lo: lo, hi: u[u.length - 1], ticks: u, log: true };
    }
    return { lo: lo, hi: pow10(308), ticks: [lo, pow10(308)], log: true };
  }

  /** The ticks from lo = 10^k0 up to the first one at or above max (and above lo), every stride-th decade. */
  function ladder(lo, k0, max, set, stride) {
    var out = [lo];
    for (var k = k0; k <= 308; k += stride) {
      for (var i = 0; i < set.length; i++) {
        var v = tenths(set[i], k);
        if (!(v > out[out.length - 1])) continue;
        out.push(v);
        if (v >= max) return out;
      }
    }
    return out;
  }

  /** The ticks inside a fixed log domain [lo, hi]: 1-2-5 ticks, then powers of ten, then every s-th power (s = 2, 3 …); the ends kept when none. */
  function logWithin(lo, hi, limit) {
    var k0 = decade(lo), k1 = decade(hi);
    var sets = [[1, 2, 5], [1]];
    for (var stride = 1; stride <= 700; stride++) {
      for (var a = 0; a < sets.length; a++) {
        if (stride > 1 && a === 0) continue;
        var out = [];
        for (var k = k0; k <= k1; k++) {
          if ((k - k0) % stride !== 0) continue;
          for (var i = 0; i < sets[a].length; i++) {
            var v = tenths(sets[a][i], k);
            if (v >= lo && v <= hi) out.push(v);
          }
        }
        if (out.length <= limit) return { lo: lo, hi: hi, ticks: out.length === 0 ? [lo, hi] : out, log: true };
      }
    }
    return { lo: lo, hi: hi, ticks: [lo, hi], log: true };
  }

  /** v ↦ the range position on a log axis [d0, d1] (both > 0); a value at or below 0 is never asked for (G4). */
  function log(d0, d1, r0, r1) {
    var l0 = log10(d0), l1 = log10(d1), dl = l1 - l0;
    return function (v) {
      if (!(dl > 0)) return r2(r0);
      return r2(r0 + ((log10(v) - l0) / dl) * (r1 - r0));
    };
  }

  /** Terms of the series below: |y| ≤ ln 10 / 16 < 0.144, so 24 terms leave 0.144^24 / 24!, far below a double's last bit. */
  var EXP_TERMS = 24;

  /**
   * 10^x for a number x, without a power or exponential function (brief 1051 D5: the value a log axis has at a position
   * between its ticks; O-16): 10^x = 10^k × e^(f ln 10) for the integer k = floor(x) and f = x − k in [0, 1); e^y by its
   * series on y / 16, then squared four times. Four operations and comparisons only, so the runtimes agree to the bit
   * (I6). A whole x gives the table's power exactly; NaN gives NaN. It is log10's inverse on the same table: log10 reads
   * a value's power from POW10, so exp10 multiplies by that same entry (a subnormal entry is the double nearest 10^k,
   * and the two agree on it). Below the table's smallest power, x in [-324, -323), k stays -323 and f is negative, as
   * log10 writes a value under 10^-323 (down to the smallest subnormal, -323.3): such a value is no longer cut to 0
   * (brief 1051 r4, R4). Below -324, 0; at or past 309, Infinity. The series is accurate to a few units in the last
   * place, so a value next to the largest double can still read Infinity: a caller that needs a value inside a range
   * bounds it to the range (the second axis does).
   */
  function exp10(x) {
    if (x !== x) return x;
    if (!(x < 309)) return Infinity;
    if (!(x >= -324)) return 0;
    var k = Math.floor(x);
    if (k < -323) k = -323;
    var f = x - k;
    if (f === 0) return pow10(k);
    var y = (f * LN10) / 16, term = 1, sum = 1;
    for (var n = 1; n < EXP_TERMS; n++) {
      term = (term * y) / n;
      sum += term;
    }
    for (var i = 0; i < 4; i++) sum *= sum;
    return pow10(k) * sum;
  }

  R.scales = { r2: r2, pow10: pow10, decade: decade, niceStep: niceStep, nice: nice, within: within, linear: linear, log10: log10, logNice: logNice, log: log, tenths: tenths, exp10: exp10 };
})(JsRendered);
