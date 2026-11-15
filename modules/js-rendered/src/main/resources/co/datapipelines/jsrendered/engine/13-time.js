// 13-time.js : the continuous UTC time axis (record §4.7, §4.5 row line; brief 989 D5). A temporal value is read from
// its wire string's own digits into seconds since 1970-01-01T00:00:00Z with the engine's day-number arithmetic (the
// proleptic Gregorian calendar, whole-number operations and Math.floor only): no Date object, no clock, no zone.
// Ticks fall on whole units of the axis's granularity (minutes and hours for datetime, days for date, months, years),
// at most one per tickSpacing px; each tick is handed on as its wire string, so its text is G9's (R.text.temporalAxis)
// and the x label row (R.axes.labelRow) places it. Nothing here places a label.
(function (R) {
  "use strict";
  var T = R.text, look = R.tables.look;
  var WIRE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?Z)?$/;
  var DAY = 86400;
  var MONTH_DAYS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

  function leap(y) {
    return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  }

  /** Days from 1970-01-01 to y-m-d (m 1 to 12), proleptic Gregorian, by the era arithmetic of a 400-year cycle. */
  function days(y, m, d) {
    var yy = m <= 2 ? y - 1 : y;
    var era = Math.floor(yy / 400), yoe = yy - era * 400;
    var mp = (m + 9) % 12;
    var doy = Math.floor((153 * mp + 2) / 5) + d - 1;
    var doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
    return era * 146097 + doe - 719468;
  }

  /** The civil date {y, m, d} of a day number (the inverse of days). */
  function civil(z) {
    var n = z + 719468, era = Math.floor(n / 146097), doe = n - era * 146097;
    var yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
    var doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
    var mp = Math.floor((5 * doy + 2) / 153), d = doy - Math.floor((153 * mp + 2) / 5) + 1, m = mp < 10 ? mp + 3 : mp - 9;
    return { y: yoe + era * 400 + (m <= 2 ? 1 : 0), m: m, d: d };
  }

  /** The whole number of the ASCII digits s[from, to) (the caller has checked they are digits). */
  function intAt(s, from, to) {
    var v = 0;
    for (var i = from; i < to; i++) v = v * 10 + (s.charCodeAt(i) - 48);
    return v;
  }

  function int(s) {
    var v = 0;
    for (var i = 0; i < s.length; i++) v = v * 10 + (s.charCodeAt(i) - 48);
    return v;
  }

  /**
   * A temporal wire value as {at: seconds since the epoch, time: whether it carried a time of day}, or null when it is
   * not a valid date or UTC timestamp (a month outside 1 to 12, a day its month lacks, an hour from 24, a minute or a
   * second from 60).
   */
  function parse(raw) {
    if (typeof raw !== "string") return null;
    if (R.text.isDateText(raw)) {
      // A plain date (what WIRE matches with no time part), read by its character codes.
      var y0 = intAt(raw, 0, 4), m0 = intAt(raw, 5, 7), d0 = intAt(raw, 8, 10);
      if (m0 < 1 || m0 > 12 || d0 < 1 || d0 > (m0 === 2 && leap(y0) ? 29 : MONTH_DAYS[m0 - 1])) return null;
      return { at: days(y0, m0, d0) * DAY, time: false };
    }
    var w = WIRE.exec(raw);
    if (!w) return null;
    var y = int(w[1]), m = int(w[2]), d = int(w[3]);
    if (m < 1 || m > 12 || d < 1 || d > (m === 2 && leap(y) ? 29 : MONTH_DAYS[m - 1])) return null;
    var at = days(y, m, d) * DAY;
    if (w[4] === undefined) return { at: at, time: false };
    var hh = int(w[4]), mm = int(w[5]), ss = w[6] === undefined ? 0 : int(w[6]);
    if (hh > 23 || mm > 59 || ss > 59) return null;
    var frac = w[7] === undefined ? 0 : Number("0." + w[7]);
    return { at: at + hh * 3600 + mm * 60 + ss + frac, time: true };
  }

  function pad(v, n) {
    var s = String(v);
    while (s.length < n) s = "0" + s;
    return s;
  }

  /** The wire string of a whole second (a date when the axis is not datetime). */
  function wire(at, datetime) {
    var z = Math.floor(at / DAY), c = civil(z), date = pad(c.y, 4) + "-" + pad(c.m, 2) + "-" + pad(c.d, 2);
    if (!datetime) return date;
    var s = at - z * DAY, hh = Math.floor(s / 3600), mm = Math.floor((s - hh * 3600) / 60);
    return date + "T" + pad(hh, 2) + ":" + pad(mm, 2) + ":00Z";
  }

  // The steps a granularity may take, finest first: [unit, count]. A unit of "s" counts seconds; "mo" months; "y"
  // years. A granularity never takes a step finer than itself, so no two ticks share a label (G9 writes HH:MM, a day,
  // a month or a year).
  var LADDER = {
    datetime: [["s", 60], ["s", 120], ["s", 300], ["s", 600], ["s", 900], ["s", 1800], ["s", 3600], ["s", 7200], ["s", 10800], ["s", 21600], ["s", 43200]],
    date: [["s", DAY], ["s", 2 * DAY], ["s", 7 * DAY], ["s", 14 * DAY]],
    month: [["mo", 1], ["mo", 2], ["mo", 3], ["mo", 6]],
  };
  var REST = { datetime: "date", date: "month", month: "year" };

  function steps(gran) {
    var out = [], g = gran;
    while (g !== "year") {
      out = out.concat(LADDER[g]);
      g = REST[g];
    }
    for (var e = 0; e < 12; e++) out.push(["y", [1, 2, 5][e % 3] * R.scales.pow10((e / 3) | 0)]);
    return out;
  }

  /** The ticks of one step inside [lo, hi], at most limit + 1 of them (more → null). */
  function ticksOf(step, lo, hi, limit) {
    var out = [], unit = step[0], n = step[1];
    if (unit === "s") {
      for (var t = Math.ceil(lo / n) * n; t <= hi; t += n) {
        if (out.length > limit) return null;
        out.push(t);
      }
      return out;
    }
    var c = civil(Math.floor(lo / DAY)), months = unit === "mo" ? n : 12 * n;
    var index = c.y * 12 + (c.m - 1);
    index = Math.ceil(index / months) * months;
    for (;; index += months) {
      var y = Math.floor(index / 12), at = days(y, index - y * 12 + 1, 1) * DAY;
      if (at < lo) continue;
      if (at > hi) return out;
      if (out.length > limit) return null;
      out.push(at);
    }
  }

  /**
   * The axis's granularity: the author's format (year, month, date, datetime) when written; otherwise datetime when
   * any value carried a time of day, else date (as G9's text decides it per value).
   */
  function granularity(format, anyTime) {
    return format === "year" || format === "month" || format === "date" || format === "datetime" ? format : anyTime ? "datetime" : "date";
  }

  /** One unit of a granularity in seconds: how far a one-value axis reaches each way. */
  function unitOf(gran) {
    return gran === "datetime" ? 60 : gran === "date" ? DAY : gran === "month" ? 15 * DAY : 183 * DAY;
  }

  /**
   * The ticks of a time axis over [lo, hi] seconds `length` px long: the finest step of the granularity's ladder whose
   * ticks are at most one per tickSpacing px (tickSpacingCompact at the compact level). Returns [{at, text, under}],
   * text and under being G9's for the tick's wire string. A one-value axis is widened by one unit each way first.
   */
  function ticks(lo, hi, length, gran, lvl) {
    var spacing = look(lvl === "compact" ? "tickSpacingCompact" : "tickSpacing");
    var limit = Math.max(1, Math.floor(length / spacing));
    var all = steps(gran), chosen = null;
    for (var i = 0; i < all.length && chosen === null; i++) chosen = ticksOf(all[i], lo, hi, limit);
    if (chosen === null) chosen = [];
    var out = [], dt = gran === "datetime";
    for (var k = 0; k < chosen.length; k++) {
      var label = T.temporalAxis(wire(chosen[k], dt), gran);
      out.push({ at: chosen[k], text: label.text, under: label.under });
    }
    return out;
  }

  /** The axis's domain [lo, hi] in seconds from the values' extent: a one-value extent widened by a unit each way. */
  function domain(min, max, gran) {
    if (max > min) return [min, max];
    var u = unitOf(gran);
    return [min - u, min + u];
  }

  R.time = { parse: parse, days: days, civil: civil, wire: wire, granularity: granularity, ticks: ticks, domain: domain };
})(JsRendered);
