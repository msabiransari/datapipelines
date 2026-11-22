// 01-text.js : numbers and dates as text, by the engine's own code (record §4.7, R14): never Intl, toLocaleString or
// localeCompare, so Node, a browser and GraalJS write the same characters. One fixed convention: "," groups thousands,
// "." is the decimal point, a negative number carries the true minus sign U+2212. A number's digits come from
// String(v), the shortest digits that read back as v, or from an exact decimal string as delivered; rounding is half
// to even on those decimal digits, never on a float's binary value. Translated from lane 977's 01-text.js (adapted:
// U+2212, the ISO code before a currency amount, compact at one fraction digit, exact strings, the label rules of G9).
(function (R) {
  "use strict";
  var MINUS = "−";
  var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  /** Fraction digits of a currency amount; any other ISO code takes 2. */
  var CURRENCY_DIGITS = { JPY: 0, KRW: 0, CLP: 0, ISK: 0, VND: 0, BHD: 3, KWD: 3, OMR: 3, JOD: 3, TND: 3 };
  var UNITS = ["", "K", "M", "B", "T"];
  var EXACT = /^([+-]?)([0-9]+)(?:\.([0-9]+))?$/;

  function intText(s, from, to) {
    var v = 0;
    for (var i = from; i < to; i++) {
      var c = s.charCodeAt(i) - 48;
      if (c < 0 || c > 9) return NaN;
      v = v * 10 + c;
    }
    return v;
  }

  function trimmed(neg, d, p) {
    var lead = 0;
    while (lead < d.length - 1 && d.charAt(lead) === "0") lead++;
    d = d.slice(lead);
    p -= lead;
    var end = d.length;
    while (end > 1 && d.charAt(end - 1) === "0") end--;
    d = d.slice(0, end);
    if (d === "0") return { neg: false, d: "0", p: 1 };
    return { neg: neg, d: d, p: p };
  }

  /**
   * {neg, d, p}: the value is 0.d × 10^p, d without leading or trailing zeros ("0" for zero, p = 1). A number is read
   * from String(v); an exact decimal string (a BIG* column's wire form) from its own digits.
   */
  function decimal(v) {
    if (typeof v === "string") {
      var m = EXACT.exec(v);
      if (m) {
        var frac = m[3] || "";
        return trimmed(m[1] === "-", m[2] + frac, m[2].length);
      }
      v = Number(v);
    }
    var s = String(v), neg = false, exp = 0;
    if (s.charAt(0) === "-") {
      neg = true;
      s = s.slice(1);
    }
    var e = s.indexOf("e");
    if (e >= 0) {
      var sign = s.charAt(e + 1) === "-" ? -1 : 1;
      exp = sign * intText(s, e + 2, s.length);
      s = s.slice(0, e);
    }
    var dot = s.indexOf(".");
    var digits = dot < 0 ? s : s.slice(0, dot) + s.slice(dot + 1);
    return trimmed(neg, digits, (dot < 0 ? s.length : dot) + exp);
  }

  /** The decimal rounded to f fraction digits, half to even. */
  function round(x, f) {
    if (x.d === "0") return x;
    var keep = x.p + f;
    if (keep >= x.d.length) return x;
    if (keep < 0) return { neg: false, d: "0", p: 1 };
    var next = x.d.charCodeAt(keep) - 48, rest = keep + 1 < x.d.length;
    var last = keep > 0 ? x.d.charCodeAt(keep - 1) - 48 : 0;
    var up = next > 5 || (next === 5 && (rest || last % 2 === 1));
    var d = x.d.slice(0, keep), p = x.p;
    if (up) {
      var i = d.length - 1, out = "";
      while (i >= 0 && d.charAt(i) === "9") {
        out = "0" + out;
        i--;
      }
      if (i < 0) {
        d = "1" + out;
        p++;
      } else d = d.slice(0, i) + String.fromCharCode(d.charCodeAt(i) + 1) + out;
    }
    if (d === "") return { neg: false, d: "0", p: 1 };
    return trimmed(x.neg, d, p);
  }

  function zeros(n) {
    var s = "";
    for (var i = 0; i < n; i++) s += "0";
    return s;
  }

  /** Plain digits, grouped, with at least minFrac fraction digits (the decimal is already rounded to the maximum). */
  function plain(x, minFrac) {
    var intPart, frac;
    if (x.d === "0") {
      intPart = "0";
      frac = "";
    } else if (x.p <= 0) {
      intPart = "0";
      frac = zeros(-x.p) + x.d;
    } else if (x.p >= x.d.length) {
      intPart = x.d + zeros(x.p - x.d.length);
      frac = "";
    } else {
      intPart = x.d.slice(0, x.p);
      frac = x.d.slice(x.p);
    }
    if (frac.length < minFrac) frac += zeros(minFrac - frac.length);
    if (intPart.length > 3) {
      var g = "", n = intPart.length;
      for (var i = 0; i < n; i++) {
        if (i > 0 && (n - i) % 3 === 0) g += ",";
        g += intPart.charAt(i);
      }
      intPart = g;
    }
    return (x.neg && x.d !== "0" ? MINUS : "") + intPart + (frac ? "." + frac : "");
  }

  function fixed(v, minFrac, maxFrac) {
    return plain(round(decimal(v), maxFrac), minFrac);
  }

  /** Compact: below 1,000 plain; then K, M, B, T, with at most maxFrac fraction digits. */
  function compact(v, maxFrac) {
    var x = decimal(v);
    var r = round(x, maxFrac);
    if (r.d === "0" || r.p <= 3) return plain(r, 0);
    var u = Math.min(4, Math.floor((x.p - 1) / 3));
    for (;;) {
      var r2 = round({ neg: x.neg, d: x.d, p: x.p - 3 * u }, maxFrac);
      if (r2.p > 3 && u < 4) {
        u++;
        continue;
      }
      return plain(r2, 0) + UNITS[u];
    }
  }

  function percent(v, maxFrac) {
    var x = decimal(v);
    return plain(round({ neg: x.neg, d: x.d, p: x.d === "0" ? 1 : x.p + 2 }, maxFrac), 0) + "%";
  }

  /** A value at the document's named format (record §4.4): integer | decimal(n) | percent | compact | currency(XXX). */
  function value(v, format) {
    if (v === null || v === undefined) return "";
    if (typeof v === "number" && v !== v) return "";
    if (format === null || format === undefined || format === "" || format === "default") return fixed(v, 0, 2);
    if (format === "integer") return fixed(v, 0, 0);
    if (format === "compact") return compact(v, 1);
    if (format === "percent") return percent(v, 1);
    if (format.length === 10 && format.slice(0, 8) === "decimal(" && format.charAt(9) === ")") {
      var n = format.charCodeAt(8) - 48;
      if (n >= 0 && n <= 6) return fixed(v, n, n);
    }
    if (format.length === 13 && format.slice(0, 9) === "currency(" && format.charAt(12) === ")") {
      var code = format.slice(9, 12);
      var digits = Object.prototype.hasOwnProperty.call(CURRENCY_DIGITS, code) ? CURRENCY_DIGITS[code] : 2;
      return code + " " + fixed(v, digits, digits);
    }
    throw new Error("not a named number format: " + format);
  }

  /** The number a label states, read back from its own characters (NaN when it is not one of this file's labels). */
  function readLabel(s) {
    var t = "", unit = 0, pct = false;
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === ",") continue;
      t += c === MINUS ? "-" : c;
    }
    var last = t.charAt(t.length - 1);
    if (last === "%") {
      pct = true;
      t = t.slice(0, -1);
    } else {
      for (var u = 1; u < UNITS.length; u++) {
        if (last === UNITS[u]) {
          unit = u;
          t = t.slice(0, -1);
        }
      }
    }
    var m = /^(-?[0-9]+(?:\.[0-9]+)?)(?:e(-?[0-9]+))?$/.exec(t);
    if (!m || (m[2] !== undefined && unit > 0)) return NaN;
    // The scale goes into the decimal text's own exponent, which every runtime parses correctly rounded: a label that
    // carries all of a value's digits reads back as that very value (n / 100 could miss it by one unit in the last place).
    var ex = (m[2] === undefined ? 0 : Number(m[2])) + (pct ? -2 : 3 * unit);
    return Number(m[1] + (ex !== 0 ? "e" + ex : ""));
  }

  /** An axis writes its ticks in the e form when one of them is 10^15 or more, or below 10^-5 and not zero (owner 2026-11-21, #1059). */
  var SCI_FROM = 15, SCI_BELOW = -5;

  /**
   * The e form of a tick: one digit, at most `digits` fraction digits (half to even, trailing zeros dropped), "e", the
   * power of ten with the true minus sign: 2.5e18, 7.5e−10; zero is 0. A percent axis writes the percentage, then %.
   */
  function scientific(v, digits, pct) {
    var x = decimal(v);
    if (x.d === "0") return pct ? "0%" : "0";
    var m = round({ neg: x.neg, d: x.d, p: 1 }, digits), power = x.p - 1 + (pct ? 2 : 0);
    if (m.p === 2) {
      m = { neg: m.neg, d: m.d, p: 1 };
      power++;
    }
    return plain(m, 0) + "e" + (power < 0 ? MINUS + -power : String(power)) + (pct ? "%" : "");
  }

  /** The refusal of `ticks` when no count of digits writes every tick as its own value (one message, read by tests). */
  function noDigits(values) {
    return new Error("ticks: no fraction digits write every tick as its own value: " + values.join(", "));
  }

  /** The fraction digits a value needs to be written exactly: those of String(v) (shifted two places for a percent). */
  function exactDigits(v, shift) {
    var x = decimal(v);
    return x.d === "0" ? 0 : Math.max(0, x.d.length - (x.p + shift));
  }

  /** The most fraction digits the short form (K, M, B, T) of a tick carries: 1.25B (owner 2026-11-22, #1089). */
  var SHORT_DIGITS = 2;

  /**
   * Axis tick labels with the fewest fraction digits at which every label, read back, IS its tick (lane 972's rule,
   * within a millionth of the smallest tick gap, so each label keeps its tick's value and no two ticks share a text).
   * The search starts at 0 digits and ends at the digits that write every tick exactly, where the read-back is exact
   * by construction; it never returns labels that lost a value (#985 R2: a fixed limit of 3 digits wrote 0, 0.0001,
   * 0.0002 as "0", "0", "0"). A percent axis writes percentages; any other axis writes plain digits below 1,000 and the
   * compact form at or above it while at most SHORT_DIGITS fraction digits suffice (§4.7; 1.25B, #1089), plain grouped
   * digits otherwise.
   */
  function ticks(values, format) {
    var pct = format === "percent";
    var step = 0, most = 0, least = 0, sci = false, mostSci = 0;
    for (var i = 0; i < values.length; i++) {
      most = Math.max(most, exactDigits(values[i], pct ? 2 : 0));
      var x = decimal(values[i]), a0 = values[i] < 0 ? -values[i] : values[i];
      if (x.d !== "0") {
        // The power of ten of the number as written (a percent is written a hundred times its value).
        var power = x.p - 1 + (pct ? 2 : 0);
        if (power >= SCI_FROM || power < SCI_BELOW) sci = true;
        mostSci = Math.max(mostSci, x.d.length - 1);
        if (least === 0 || a0 < least) least = a0;
      }
      if (i === 0) continue;
      var gap = values[i] - values[i - 1];
      if (gap < 0) gap = -gap;
      // Two finite ticks can stand more than the largest double apart: their distance is then the largest double.
      if (gap === Infinity) gap = Number.MAX_VALUE;
      if (gap > 0 && (step === 0 || gap < step)) step = gap;
    }
    // Values with no gap between them (one tick: a log axis writes each tick alone) are held to their own size, so a
    // tick below a millionth is not written as 0; a value of 1 or more keeps the tolerance it had.
    if (step === 0) step = least > 0 && least < 1 ? least : 1;
    if (sci) {
      for (var sd = 0; sd <= mostSci; sd++) {
        var so = [], sx = true;
        for (var j = 0; j < values.length; j++) {
          var sl = scientific(values[j], sd, pct);
          so.push(sl);
          var sr = readLabel(sl), sb = sr - values[j];
          if (!(sr - sr === 0) || !(sb <= step * 1e-6 && sb >= -step * 1e-6)) sx = false;
        }
        if (sx) return so;
      }
      throw noDigits(values);
    }
    // Each count of digits up to `most` (at least SHORT_DIGITS, the short form's digits: 1.25K), then plain digits at
    // `most` (a short attempt above 1,000 can miss where plain grouped digits are exact: 1,125 is not 1.13K).
    var last = Math.max(most, SHORT_DIGITS);
    for (var digits = 0; digits <= last + 1; digits++) {
      var plainOnly = digits > last, d = plainOnly ? most : digits;
      // At each count of digits: the short form first, while it has at most SHORT_DIGITS digits (1.25B, #1089), then
      // plain digits from two digits on, as before (2,311.28 stays where 2.31K is not its tick). A percent has one form.
      for (var form = 0; form < 2; form++) {
        var shortForm = form === 0;
        if (shortForm && !pct && (plainOnly || d > SHORT_DIGITS)) continue;
        if (!shortForm && (pct || (!plainOnly && d < 2))) continue;
        var out = [];
        var exact = true;
        for (var k = 0; k < values.length; k++) {
          var v = values[k], a = v < 0 ? -v : v, label;
          if (pct) label = percent(v, d);
          else label = shortForm && a >= 1000 ? compact(v, d) : fixed(v, 0, d);
          out.push(label);
          var back = readLabel(label) - v;
          if (!(back <= step * 1e-6 && back >= -step * 1e-6)) exact = false;
        }
        if (exact) return out;
      }
    }
    throw noDigits(values);
  }

  // ---- dates: UTC, from the wire string's own digits (no Date object, no clock, no zone) ----

  var DATE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/;
  var STAMP = /^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.[0-9]+)?)?Z$/;
  var STAMP_FULL = /^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?Z$/;

  function temporalParts(raw) {
    var s = String(raw);
    var d = DATE.exec(s);
    if (d) return { y: d[1], m: d[2], d: d[3], time: null };
    var t = STAMP.exec(s);
    if (t) return { y: t[1], m: t[2], d: t[3], time: t[4] + ":" + t[5] };
    return null;
  }

  /** The fixed-width UTC sort key YYYY-MM-DDTHH:MM:SS.fffffffff (null when the text is not a temporal wire value). */
  function temporalKey(raw) {
    var s = String(raw);
    var d = DATE.exec(s);
    if (d) return d[1] + "-" + d[2] + "-" + d[3] + "T00:00:00.000000000";
    var t = STAMP_FULL.exec(s);
    if (!t) return null;
    var f = t[7] || "";
    return t[1] + "-" + t[2] + "-" + t[3] + "T" + t[4] + ":" + t[5] + ":" + (t[6] || "00") + "." + f + zeros(9 - f.length);
  }

  function granularity(p, format) {
    return format || (p.time ? "datetime" : "date");
  }

  /** The full text of a temporal value (a category's full text and a tooltip's heading): 2024-01-05 14:00 UTC. */
  function temporalFull(raw, format) {
    var p = temporalParts(raw);
    if (!p) return String(raw);
    var g = granularity(p, format);
    if (g === "year") return p.y;
    if (g === "month") return p.y + "-" + p.m;
    if (g === "date") return p.y + "-" + p.m + "-" + p.d;
    return p.y + "-" + p.m + "-" + p.d + " " + (p.time || "00:00") + " UTC";
  }

  /**
   * An axis label of a temporal value (G9): {text, under}. year: 2024; month: Jan, the year under; date: Jan 5, the
   * year under; datetime: 14:00, the date under. The label row writes `under` beneath the first label it draws and
   * wherever it changes (layout, never here).
   */
  function temporalAxis(raw, format) {
    var p = temporalParts(raw);
    if (!p) return { text: String(raw), under: null };
    var g = granularity(p, format), mon = MONTHS[intText(p.m, 0, 2) - 1], day = String(intText(p.d, 0, 2));
    if (mon === undefined) return { text: String(raw), under: null };
    if (g === "year") return { text: p.y, under: null };
    if (g === "month") return { text: mon, under: p.y };
    if (g === "date") return { text: mon + " " + day, under: p.y };
    return { text: p.time || "00:00", under: mon + " " + day + ", " + p.y };
  }

  R.text = {
    MINUS: MINUS,
    decimal: decimal,
    round: round,
    fixed: fixed,
    compact: compact,
    percent: percent,
    value: value,
    ticks: ticks,
    readLabel: readLabel,
    temporalKey: temporalKey,
    temporalFull: temporalFull,
    temporalAxis: temporalAxis,
  };
})(JsRendered);
