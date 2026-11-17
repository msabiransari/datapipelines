// 09-data.js : reading the delivered columns, shared by every template (record §4.3: rows by index; no object keyed
// by a data value is ever built: a group's identity lives in a Map). Translated from the product's chart core
// (datapipelines-dashboard-chart.js, sha256 4b56c48b…, the oracle of A.6): the key of a value, exact decimal
// aggregation (sum, min, max at the inputs' largest scale; the mean ÷ count, half to even at scale + 6), float
// aggregation, and the orders (first appearance; an explicit list; ascending/descending by the column's type; by
// total). Adapted: a power of ten by multiplication (R-BAN bars the power operator).
(function (R) {
  "use strict";
  var T = R.text;
  var EXACT_TYPES = { BIGINTEGER: true, BIGDECIMAL: true };
  var MEAN_EXTRA_SCALE = 6;
  var DECIMAL = /^(-?)([0-9]+)(?:\.([0-9]+))?$/;
  var ZERO = BigInt(0), ONE = BigInt(1), TWO = BigInt(2), TEN = BigInt(10);

  function isNull(v) {
    return v === null || v === undefined;
  }

  /** A value's identity: its JS type and its text, so 1 and "1" are two groups (the core's keyOf). */
  function keyOf(v) {
    return typeof v + ":" + String(v);
  }

  function tenTo(n) {
    var p = ONE;
    for (var i = 0; i < n; i++) p = p * TEN;
    return p;
  }

  function dec(s) {
    var m = DECIMAL.exec(String(s).trim());
    if (!m) return null;
    var frac = m[3] || "";
    return { big: BigInt(m[1] + m[2] + frac), scale: frac.length };
  }

  function rescale(x, scale) {
    return x.big * tenTo(scale - x.scale);
  }

  function decStr(big, scale) {
    var neg = big < ZERO;
    var digits = (neg ? -big : big).toString();
    while (digits.length < scale + 1) digits = "0" + digits;
    var whole = digits.slice(0, digits.length - scale);
    var frac = scale ? "." + digits.slice(digits.length - scale) : "";
    return (neg ? "-" : "") + whole + frac;
  }

  function cmpDec(x, y) {
    var scale = Math.max(x.scale, y.scale);
    var a = rescale(x, scale), b = rescale(y, scale);
    return a < b ? -1 : a > b ? 1 : 0;
  }

  /** numerator / denominator rounded half to even (BigInt division truncates toward zero). */
  function divideHalfEven(numerator, denominator) {
    var q = numerator / denominator, r = numerator % denominator;
    if (r === ZERO) return q;
    var sign = (numerator < ZERO) !== (denominator < ZERO) ? -ONE : ONE;
    var twice = (r < ZERO ? -r : r) * TWO;
    var d = denominator < ZERO ? -denominator : denominator;
    if (twice > d || (twice === d && q % TWO !== ZERO)) q += sign;
    return q;
  }

  function notAMeasure(field) {
    return new Error("A value of '" + field + "' is not a number in its column's wire form.");
  }

  function aggregateExact(texts, agg, field) {
    var xs = [], scale = 0;
    for (var i = 0; i < texts.length; i++) {
      var x = dec(texts[i]);
      if (!x) throw notAMeasure(field);
      xs.push(x);
      if (x.scale > scale) scale = x.scale;
    }
    var bs = [];
    for (var j = 0; j < xs.length; j++) bs.push(rescale(xs[j], scale));
    var acc = bs[0];
    if (agg === "min" || agg === "max") {
      for (var k = 1; k < bs.length; k++) if (agg === "min" ? bs[k] < acc : bs[k] > acc) acc = bs[k];
      return decStr(acc, scale);
    }
    var sum = ZERO;
    for (var s = 0; s < bs.length; s++) sum += bs[s];
    if (agg === "sum") return decStr(sum, scale);
    var q = divideHalfEven(sum * tenTo(MEAN_EXTRA_SCALE), BigInt(bs.length));
    return decStr(q, scale + MEAN_EXTRA_SCALE).replace(/\.?0+$/, "");
  }

  /** One group's aggregate: {num, exact}; exact decimal arithmetic when any value is a string (an exact column). */
  function aggregate(vals, agg, field) {
    if (agg === "count") return { num: vals.length, exact: null };
    var xs = [], anyString = false, anyOther = false;
    for (var i = 0; i < vals.length; i++) {
      var v = vals[i];
      if (isNull(v)) continue;
      xs.push(v);
      if (typeof v === "string") anyString = true;
      else if (typeof v !== "number") anyOther = true;
    }
    if (xs.length === 0) return { num: null, exact: null };
    if (anyString) {
      var texts = [];
      for (var j = 0; j < xs.length; j++) texts.push(String(xs[j]));
      var e = aggregateExact(texts, agg, field);
      return { num: Number(e), exact: e };
    }
    if (anyOther) throw notAMeasure(field);
    var n = agg === "sum" || agg === "mean" ? 0 : xs[0];
    for (var k = 0; k < xs.length; k++) {
      if (agg === "sum" || agg === "mean") n += xs[k];
      else if (agg === "min" ? xs[k] < n : xs[k] > n) n = xs[k];
    }
    if (agg === "mean") n = n / xs.length;
    return { num: n, exact: null };
  }

  /** Code point order (a string's < compares UTF-16 units; the two differ above U+FFFF). */
  function cmpCodePoint(a, b) {
    var x = R.measure.codePoints(a), y = R.measure.codePoints(b);
    var n = Math.min(x.length, y.length);
    for (var i = 0; i < n; i++) {
      var d = x[i] - y[i];
      if (d) return d;
    }
    return x.length - y.length;
  }

  function byValue(type, temporal) {
    if (temporal) {
      return function (a, b) {
        var x = T.temporalKey(a.raw), y = T.temporalKey(b.raw);
        return cmpCodePoint(x === null ? String(a.raw) : x, y === null ? String(b.raw) : y);
      };
    }
    if (EXACT_TYPES[type] === true) {
      return function (a, b) {
        var x = typeof a.raw === "string" ? dec(a.raw) : null;
        var y = typeof b.raw === "string" ? dec(b.raw) : null;
        return x && y ? cmpDec(x, y) : cmpCodePoint(String(a.raw), String(b.raw));
      };
    }
    return function (a, b) {
      if (typeof a.raw === "number" && typeof b.raw === "number") return a.raw < b.raw ? -1 : a.raw > b.raw ? 1 : 0;
      return cmpCodePoint(String(a.raw), String(b.raw));
    };
  }

  /**
   * Items {key, raw, first, …} in the document's order: absent → first appearance; a list → the listed values first in
   * the list's order, then the rest by first appearance; ascending/descending → by the column's type; "y"/"-y"
   * (or "x"/"-x") → by totalOf, nulls last, ties by first appearance.
   */
  function order(items, sort, totalOf, type, temporal) {
    var byFirst = items.slice().sort(function (a, b) {
      return a.first - b.first;
    });
    if (sort === null || sort === undefined) return byFirst;
    if (Array.isArray(sort)) {
      var pos = new Map();
      for (var i = 0; i < sort.length; i++) pos.set(String(sort[i]), i);
      var listed = byFirst.filter(function (it) {
        return pos.has(String(it.raw));
      }).sort(function (a, b) {
        return pos.get(String(a.raw)) - pos.get(String(b.raw));
      });
      return listed.concat(byFirst.filter(function (it) {
        return !pos.has(String(it.raw));
      }));
    }
    if (sort === "ascending" || sort === "descending") {
      var sorted = byFirst.sort(byValue(type, temporal));
      return sort === "descending" ? sorted.reverse() : sorted;
    }
    var desc = String(sort).charAt(0) === "-";
    return byFirst.sort(function (a, b) {
      var x = totalOf(a), y = totalOf(b);
      if (x === null && y === null) return a.first - b.first;
      if (x === null) return 1;
      if (y === null) return -1;
      var c = desc ? (y < x ? -1 : y > x ? 1 : 0) : x < y ? -1 : x > y ? 1 : 0;
      return c || a.first - b.first;
    });
  }

  /** The column of a field, or an empty list when the delivery lacks it. */
  function column(columns, field) {
    if (field === null || field === undefined) return null;
    var c = Object.prototype.hasOwnProperty.call(columns, field) ? columns[field] : null;
    return Array.isArray(c) ? c : [];
  }

  /** The number of rows of a delivery: its longest column (record §4.9: the row cap reads the columns' length). */
  function rowCount(columns) {
    var n = 0;
    for (var f in columns) {
      if (Object.prototype.hasOwnProperty.call(columns, f) && Array.isArray(columns[f]) && columns[f].length > n) n = columns[f].length;
    }
    return n;
  }

  R.data = { isNull: isNull, keyOf: keyOf, aggregate: aggregate, order: order, column: column, rowCount: rowCount, cmpCodePoint: cmpCodePoint, EXACT_TYPES: EXACT_TYPES };
})(JsRendered);
