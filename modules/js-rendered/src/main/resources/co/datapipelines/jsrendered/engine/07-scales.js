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

  /** A nice step as decimal parts {m, e}: m × 10^e, m one of 1, 2, 25, 5 (25 × 10^(k-1) is 2.5 × 10^k). */
  function niceParts(raw) {
    var k = decade(raw), f = raw / pow10(k);
    if (f <= 1.0000001) return { m: 1, e: k };
    if (f <= 2.0000001) return { m: 2, e: k };
    if (f <= 2.5000001) return { m: 25, e: k - 1 };
    if (f <= 5.0000001) return { m: 5, e: k };
    return { m: 1, e: k + 1 };
  }

  /**
   * The step that judges whether two ends are one value: the smallest of 1, 2 or 5 × a power of ten at or above their
   * span. Never 2.5: its ticks need one digit more than a step of 5, and every finer nice step needs at least the
   * judge's digits, so what the judge cannot write no step can.
   */
  function judgeParts(raw) {
    var k = decade(raw), f = raw / pow10(k);
    if (f <= 1.0000001) return { m: 1, e: k };
    if (f <= 2.0000001) return { m: 2, e: k };
    if (f <= 5.0000001) return { m: 5, e: k };
    return { m: 1, e: k + 1 };
  }

  /** A tick is a decimal of at most 15 significant digits, what a double holds; past them the digits are rounding. */
  var TICK_DIGITS = 1e15;
  var SAME = "same";

  /**
   * Whether the tick k × 10^e (k a whole number) needs a 16th significant digit: its zeros at the end are not digits
   * it needs, so 10^15 itself is one digit and 999,999,999,999,999 beside it is fifteen.
   */
  function wide(k) {
    if (k < 0) k = -k;
    while (!(k < TICK_DIGITS) && k % 10 === 0) k = k / 10;
    return !(k < TICK_DIGITS);
  }

  /**
   * Whether a tick of first × m … last × m (whole numbers of the step's last digit) needs a 16th significant digit.
   * Two neighbouring ticks of 16 digits or more cannot both end in a zero (m is 1, 2, 5 or 25), so past 2 × 10^15 one
   * always does, and below it the products are exact and the two ticks at each end decide for all between them.
   */
  function wideRange(first, last, m) {
    var a = first < 0 ? -first : first, b = last < 0 ? -last : last;
    if (!((a > b ? a : b) * m < 2 * TICK_DIGITS)) return true;
    if (wide(first * m) || wide(last * m)) return true;
    return last > first && (wide((first + 1) * m) || wide((last - 1) * m));
  }

  /**
   * Where the ticks of [min, max] lie at a step m × 10^e: {step, first, last, wide}; first and last are the whole
   * multiples of the step at the two ends (an end within a billionth of a step of a multiple is on it), wide whether
   * one of the ticks needs a 16th significant digit (wideRange). null where a number is not finite.
   */
  function indices(min, max, parts) {
    var step = Number(parts.m + "e" + parts.e);
    if (!(step > 0 && step < Infinity)) return null;
    var first = Math.floor(min / step + 1e-9), last = Math.ceil(max / step - 1e-9);
    if (!(first > -Infinity) || !(last < Infinity)) return null;
    // At a large index the division's own rounding can put an end one multiple off, either way: the tick itself decides.
    var tol = step * 1e-9;
    if (Number(first * parts.m + "e" + parts.e) > min + tol) first--;
    else if (Number((first + 1) * parts.m + "e" + parts.e) <= min + tol) first++;
    if (Number(last * parts.m + "e" + parts.e) < max - tol) last++;
    else if (Number((last - 1) * parts.m + "e" + parts.e) >= max - tol) last--;
    return { step: step, first: first, last: last, wide: wideRange(first, last, parts.m) };
  }

  /**
   * The nice ticks over [min, max] (min < max), each the double its decimal text reads as (index × m, "e", e), so a
   * tick is its decimal value correctly rounded and never a product's rounding. SAME where the two ends cannot be told
   * apart: at the judge's step (judgeParts) a tick would still need a 16th significant digit (judged on that one
   * step, so the answer does not depend on the axis's length). A finer step whose ticks would need a 16th digit is
   * coarsened, one interval fewer at a time, and at one interval gives way to the judge's step. null where no nice
   * ticks hold the range (an overflow, more steps than `intervals`): the caller's bounded fallback.
   */
  function niceTicks(min, max, intervals) {
    var span = max - min;
    if (!(span < Infinity)) return null;
    var judge = judgeParts(span), coarse = indices(min, max, judge);
    // A judge's step past the largest double (a span over 10^308) judges nothing: two ends that far apart are never
    // one value, and the finer steps below decide the range as before (0 … 1.5e308 keeps 0, 5e307, 1e308, 1.5e308).
    if (coarse !== null && coarse.wide) return SAME;
    var n = intervals < 1 ? 1 : intervals, tries = 0;
    for (;;) {
      var parts = niceParts(span / n), x = indices(min, max, parts);
      if (x === null) return null;
      if (x.wide) {
        if (n > 1) {
          n--;
          continue;
        }
        if (coarse === null) return null;
        parts = judge;
        x = coarse;
      }
      var count = x.last - x.first;
      if (count > intervals && n > 1 && tries < 3) {
        tries++;
        n--;
        continue;
      }
      if (count > intervals || count < 1) return null;
      var ticks = [];
      for (var i = 0; i <= count; i++) {
        var t = Number((x.first + i) * parts.m + "e" + parts.e) + 0;
        if (!(t > -Infinity && t < Infinity)) return null;
        if (i > 0 && !(t > ticks[i - 1])) return SAME;
        ticks.push(t);
      }
      // The range holds both ends, or it is no nice range (below the normal doubles a decimal step is not a double,
      // and its multiples can miss an end): the bounded fallback then.
      if (ticks[0] > min + x.step * 1e-9 || ticks[count] < max - x.step * 1e-9) return null;
      return { lo: ticks[0], hi: ticks[count], step: x.step, ticks: ticks };
    }
  }

  /**
   * The smallest nice range that contains [min, max] in at most `intervals` steps: {lo, hi, step, ticks}. Two ends
   * the ticks cannot tell apart (niceTicks) are one value (owner 2026-11-21, #1063): the range is the one a single
   * value takes, 0 to the value. A range no nice ticks hold takes the bounded fallback.
   */
  function nice(min, max, intervals) {
    if (!(max > min)) {
      if (min === 0 || min !== min) {
        min = 0;
        max = 1;
      } else if (min > 0) min = 0;
      else max = 0;
    }
    var r = niceTicks(min, max, intervals);
    if (r === SAME) {
      // One value: from 0, as a single value is, its fallback included (1.7e308 and the next double: 0 … 1.7e308).
      if (min > 0) min = 0;
      else if (max < 0) max = 0;
      r = niceTicks(min, max, intervals);
    }
    return r !== null && r !== SAME ? r : bounded(min, max);
  }

  /**
   * Whether two ends are one value: the ticks cannot tell them apart (niceTicks' verdict, which is the same at every
   * axis length). For a caller that takes an extent without asking for its ticks: the second axis.
   */
  function oneValue(min, max) {
    return max > min && niceTicks(min, max, 1) === SAME;
  }

  /**
   * The ticks of a fixed domain [lo, hi] (an author's scale.domain): the nice values inside it, the ends kept. Fewer than
   * two nice values inside (a plot of one tick spacing or less: the one step is wider than the domain) gives the two ends:
   * an axis of one tick says nothing about the scale (#1104). A domain that is no range (hi not above lo) keeps its one tick.
   */
  function within(lo, hi, intervals) {
    var n = nice(lo, hi, intervals), eps = (hi - lo) * 1e-9;
    var ticks = n.ticks.filter(function (t) {
      return t >= lo - eps && t <= hi + eps;
    });
    if (ticks.length === 0 || (ticks.length === 1 && hi > lo)) ticks = [lo, hi];
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
    // The smallest double lies below the table's smallest power (#1060): the range starts at the value itself.
    if (lo > min) lo = min;
    var sets = [[1, 2, 5], [1]];
    for (var a = 0; a < sets.length; a++) {
      var t = ladder(lo, k0, max, sets[a], 1);
      if (t !== null && t.length <= limit) return { lo: lo, hi: t[t.length - 1], ticks: t, log: true };
    }
    for (var stride = 2; stride <= 700; stride++) {
      var u = ladder(lo, k0, max, [1], stride);
      if (u !== null && u.length <= limit) return { lo: lo, hi: u[u.length - 1], ticks: u, log: true };
    }
    // No stride from lo lands on a power at or above max inside the table: the two ends, the top the smallest power
    // of ten at or above max. Past the table's largest power, and for one value at it, no power lies at or above the
    // value and above lo (#1061): the largest double does.
    var kt = decade(max), top = pow10(kt);
    if (top < max) top = kt < 308 ? pow10(kt + 1) : MAX;
    if (!(top > lo)) top = MAX;
    return { lo: lo, hi: top, ticks: [lo, top], log: true };
  }

  /**
   * The ticks from lo up to the first one at or above max (and above lo), every stride-th decade from 10^k0; null
   * where the table ends before a tick reaches max (#1061: a ladder that stops below the largest value is no range).
   */
  function ladder(lo, k0, max, set, stride) {
    var out = [lo];
    for (var k = k0; k <= 308; k += stride) {
      for (var i = 0; i < set.length; i++) {
        var v = tenths(set[i], k);
        if (!(v > out[out.length - 1]) || !(v < Infinity)) continue;
        out.push(v);
        if (v >= max) return out;
      }
    }
    return null;
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
    // Two ends a few doubles apart can share one log10 (#1063): over a span that narrow the log scale is a straight
    // line, so the linear scale places the values.
    if (!(dl > 0) && d1 > d0) return linear(d0, d1, r0, r1);
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

  R.scales = { r2: r2, pow10: pow10, decade: decade, niceStep: niceStep, nice: nice, oneValue: oneValue, within: within, linear: linear, log10: log10, logNice: logNice, log: log, tenths: tenths, exp10: exp10 };
})(JsRendered);
