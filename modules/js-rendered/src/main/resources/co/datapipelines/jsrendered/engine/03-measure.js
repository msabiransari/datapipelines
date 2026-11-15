// 03-measure.js : text width (record §4.3, O-4). The engine never measures by itself: the caller injects
// measure(text, sizePx, weight) → px. The browser passes its own; Node and the server pass {table, margin}, which this
// file turns into a function over a width table (02-widths-*.js). A code point the table lacks takes the table's
// average width and is counted, and the render reports chart.text.width-missing (D8). Translated from lane 977's
// 03-measure.js (adapted: the average width in place of 0.6 em; the weight is passed through).
(function (R) {
  "use strict";
  var ELLIPSIS = "…";
  var averages = {};

  /** The code points of a string, iterated by code point (a lone surrogate is one code point). */
  function codePoints(s) {
    var out = [];
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        var d = s.charCodeAt(i + 1);
        if (d >= 0xdc00 && d <= 0xdfff) {
          out.push((c - 0xd800) * 0x400 + (d - 0xdc00) + 0x10000);
          i++;
          continue;
        }
      }
      out.push(c);
    }
    return out;
  }

  /** s without trailing white space (ASCII white space and U+3000). */
  function trimEnd(s) {
    var end = s.length;
    for (; end > 0; end--) {
      var c = s.charCodeAt(end - 1);
      if (c !== 32 && c !== 9 && c !== 10 && c !== 13 && c !== 12 && c !== 11 && c !== 0x3000) break;
    }
    return s.slice(0, end);
  }

  /** The mean advance of a table, in thousandths of an em (a sum of integers: exact, and the same in every runtime). */
  function average(table) {
    var sum = 0, n = 0;
    for (var k in table) {
      if (Object.prototype.hasOwnProperty.call(table, k)) {
        sum += table[k];
        n++;
      }
    }
    return n === 0 ? 0 : sum / n;
  }

  // Computed once, at load, for every table the files before this one registered: read only after.
  for (var name in R.widthTables) {
    if (Object.prototype.hasOwnProperty.call(R.widthTables, name)) averages[name] = average(R.widthTables[name]);
  }

  /** A measurer over a named width table: {fn(text, size), missing}. A new measurer per render call. */
  function tableMeasurer(spec) {
    if (!Object.prototype.hasOwnProperty.call(R.widthTables, spec.table)) throw new Error("unknown width table: " + spec.table);
    var table = R.widthTables[spec.table];
    var margin = spec.margin === undefined ? 1 : spec.margin;
    if (typeof margin !== "number" || !(margin >= 1 && margin <= 2)) throw new Error("a width margin outside 1 to 2: " + margin);
    var avg = averages[spec.table];
    var m = { missing: 0 };
    m.fn = function (text, size) {
      var cps = codePoints(text), em = 0;
      for (var i = 0; i < cps.length; i++) {
        var w = table[cps[i]];
        if (w === undefined) {
          em += avg;
          m.missing++;
        } else em += w;
      }
      return (em / 1000) * size * margin;
    };
    return m;
  }

  /** What the render call measures with: an injected function, or a width-table spec (the default: Noto Sans, 1.00). */
  function measurer(measure) {
    if (typeof measure === "function") {
      return {
        missing: 0,
        fn: function (text, size, weight) {
          var w = measure(text, size, weight);
          if (typeof w !== "number" || w !== w || w < 0 || w === Infinity) throw new Error("measure returned a width that is not a finite number");
          return w;
        },
      };
    }
    if (measure === null || measure === undefined) return tableMeasurer({ table: "noto-sans-regular", margin: 1 });
    if (typeof measure !== "object") throw new Error("measure is neither a function nor a width-table spec");
    return tableMeasurer(measure);
  }

  /** text, or its longest prefix (by code point) that fits max with an ellipsis; "" when not even that fits. */
  function fit(width, text, size, weight, max) {
    if (width(text, size, weight) <= max) return text;
    var cps = codePoints(text), lo = 0, hi = cps.length - 1;
    function prefix(n) {
      var s = "";
      for (var i = 0; i < n; i++) s += String.fromCodePoint(cps[i]);
      return trimEnd(s) + ELLIPSIS;
    }
    if (width(prefix(0), size, weight) > max) return "";
    while (lo < hi) {
      var mid = (lo + hi + 1) >>> 1;
      if (width(prefix(mid), size, weight) <= max) lo = mid;
      else hi = mid - 1;
    }
    return prefix(lo);
  }

  R.measure = { ELLIPSIS: ELLIPSIS, codePoints: codePoints, trimEnd: trimEnd, measurer: measurer, fit: fit };
})(JsRendered);
