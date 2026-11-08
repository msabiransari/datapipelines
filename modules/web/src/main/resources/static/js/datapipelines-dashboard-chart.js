/*
 * The `chart` renderer for the Datapipelines dashboard runtime (design record 2026-11-06 §5.2): ONE adapter for the
 * renderer-neutral chart document v1, registered on load as kind `chart`, major version `1`.
 *
 * ## The core (library-neutral, pure)
 * The bar family's model — sort, aggregation, normalize, nulls, ticks, the UTC time rules, the named number formats, the
 * 32-code-point label cut, the text escaping and the data warnings — ported from the 854 reference (`bar-core.mjs`,
 * `format.mjs`, `bar-fields.mjs`) with the record's two corrections (§4.1):
 *   1. ORDER BY THE COLUMN'S TYPE: `spec.columns` (`{field: LogicalType}`, served beside each chart occurrence) decides
 *      the `ascending`/`descending` comparison — BIGINTEGER/BIGDECIMAL by exact decimal value, `temporal` by the UTC
 *      digit key, two numbers by value, anything else by code point (the reference ordered by `typeof`, so an exact
 *      column's `"10"` sorted before `"2"`).
 *   2. THE EXACT MEAN ROUNDS HALF-EVEN at the inputs' scale + 6 (the reference truncated).
 * The server's `ChartModel` (modules/visualization) is the same model in Kotlin; the parity test runs both over one
 * fixture set on every web test run, so the mechanical check judges what this file draws.
 *
 * ## Converters (one per chart library)
 * A converter is a pure `(spec, bindings, tokens) → {option, warnings}` plus its library's host side
 * (`draw`/`resize`/`dispose`), registered here with `registerConverter({library, families, draw, resize, dispose})`.
 * `bindings` is the runtime's columnar delivery `{field: [values]}`. Installed in S1: `plotly`
 * (`datapipelines-dashboard-plotly.js`). The LIBRARY IDENTITIES of v1 are a separate, fixed set (record §5.2).
 *
 * ## The host contract at init (record §5.2, frozen)
 * `init({..., charts: {library, instance}})`: `library` one of the eight identities, `instance` the library's entry
 * value as the library documents it (an object, or a constructor function). The runtime validates it at `init`
 * (`init.invalid`) and judges every chart occurrence before any execution through this adapter's `judge`:
 * `chart.library_undeclared` (no `charts`) and `chart.converter_missing` (no installed converter for the library or the
 * occurrence's family). `instance` is only ever CALLED, by the converter's host side; never read as a global, never
 * serialized.
 *
 * ## Tokens
 * Resolved per render from the chart's own cascade: the `--chart-*` custom properties and `--font-sans` through a
 * probe element (computed colours, normalised to `rgb()`), each falling back to a built-in default when its property
 * is unset. `locale` is the document's `lang`, else `en-US`.
 *
 * ## The rendering rule
 * No `innerHTML`, no `eval`, no `new Function`: text reaches a library as escaped data (the converter's rule) and the
 * accessible description is set with `setAttribute`.
 */
(function () {
  "use strict";


  /** The v1 chart library identities (record §5.2): fixed at S1; a ninth is a compatible addition, never a rename. */
  var LIBRARIES = ["plotly", "echarts", "chartjs", "apexcharts", "vegalite", "highcharts", "amcharts5", "recharts"];

  /** `mark.type` → the family a converter registers under (v1: the bar family only). */
  var FAMILIES = { bar: "bar" };

  var EXACT_TYPES = { BIGINTEGER: true, BIGDECIMAL: true };

  var LABEL_CODE_POINTS = 32;

  /** The mean's extra fraction digits beyond the inputs' scale (record §4.1). */
  var MEAN_EXTRA_SCALE = 6;

  // ------------------------------------------------------------------------------------------------ formats

  var NUMBER_OPTIONS = {
    integer: { maximumFractionDigits: 0 },
    percent: { style: "percent", maximumFractionDigits: 1 },
    compact: { notation: "compact", maximumFractionDigits: 1 },
  };

  /** The named number formats through `Intl.NumberFormat`; an exact decimal STRING is formatted as the string. */
  function numberFormatter(name, locale) {
    var opts;
    if (name === undefined || name === null || name === "default") opts = { maximumFractionDigits: 2 };
    else if (Object.prototype.hasOwnProperty.call(NUMBER_OPTIONS, name)) opts = NUMBER_OPTIONS[name];
    else {
      var dec = /^decimal\(([0-6])\)$/.exec(name);
      var cur = /^currency\(([A-Z]{3})\)$/.exec(name);
      if (dec) opts = { minimumFractionDigits: Number(dec[1]), maximumFractionDigits: Number(dec[1]) };
      else if (cur) opts = { style: "currency", currency: cur[1] };
      else throw new Error("unknown number format"); // the schema refuses it at save
    }
    var nf = new Intl.NumberFormat(locale, opts);
    return function (v) {
      return v === null || v === undefined ? "" : nf.format(v);
    };
  }

  var DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
  var STAMP = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?Z$/;
  var STAMP_FULL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?Z$/;

  function temporalParts(raw) {
    var s = String(raw);
    var d = DATE.exec(s);
    var t = d ? null : STAMP.exec(s);
    if (d) return { y: d[1], m: d[2], d: d[3], time: null };
    if (t) return { y: t[1], m: t[2], d: t[3], time: t[4] + ":" + t[5] };
    return null;
  }

  /** The fixed-width UTC sort key `YYYY-MM-DDTHH:MM:SS.fffffffff`, from the string's own digits (never `Date`). */
  function temporalKey(raw) {
    var s = String(raw);
    var d = DATE.exec(s);
    var t = d ? null : STAMP_FULL.exec(s);
    if (d) return d[1] + "-" + d[2] + "-" + d[3] + "T00:00:00.000000000";
    if (t) return t[1] + "-" + t[2] + "-" + t[3] + "T" + t[4] + ":" + t[5] + ":" + (t[6] || "00") + "." + (t[7] || "").padEnd(9, "0");
    return null;
  }

  function temporalLabel(raw, granularity) {
    var p = temporalParts(raw);
    if (!p) return String(raw);
    var g = granularity || (p.time ? "datetime" : "date");
    if (g === "year") return p.y;
    if (g === "month") return p.y + "-" + p.m;
    if (g === "date") return p.y + "-" + p.m + "-" + p.d;
    return p.y + "-" + p.m + "-" + p.d + " " + (p.time || "00:00") + " UTC";
  }

  var ENTITY = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

  /** For the sinks that parse markup (Plotly's pseudo-HTML): every string is entity-escaped data. */
  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return ENTITY[c];
    });
  }

  /** At most [n] code points, the last an ellipsis. */
  function cut(s, n) {
    var limit = n === undefined ? LABEL_CODE_POINTS : n;
    var cps = Array.from(String(s));
    return cps.length > limit ? cps.slice(0, limit - 1).join("") + "…" : String(s);
  }

  /** 1-2-2.5-5 ticks covering lo..hi. */
  function niceTicks(lo, hi, count) {
    var n = count || 5;
    if (!(hi > lo)) hi = lo + 1;
    var raw = (hi - lo) / n;
    var mag = Math.pow(10, Math.floor(Math.log10(raw)));
    var step = [1, 2, 2.5, 5, 10]
      .map(function (k) {
        return k * mag;
      })
      .find(function (s) {
        return s >= raw;
      });
    var a = Math.floor(lo / step + 1e-9) * step;
    var b = Math.ceil(hi / step - 1e-9) * step;
    var ticks = [];
    for (var v = a; v <= b + step / 2; v += step) ticks.push(Math.round(v / step) * step);
    return { lo: a, hi: b, step: step, ticks: ticks };
  }

  // ------------------------------------------------------------------------------------- exact decimals

  var DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

  function dec(s) {
    var m = DECIMAL.exec(String(s).trim());
    if (!m) return null;
    var frac = m[3] || "";
    return { big: BigInt(m[1] + m[2] + frac), scale: frac.length };
  }

  function rescale(x, scale) {
    return x.big * BigInt(10) ** BigInt(scale - x.scale);
  }

  function decStr(big, scale) {
    var neg = big < BigInt(0);
    var digits = (neg ? -big : big).toString().padStart(scale + 1, "0");
    var whole = digits.slice(0, digits.length - scale);
    var frac = scale ? "." + digits.slice(digits.length - scale) : "";
    return (neg ? "-" : "") + whole + frac;
  }

  function cmpDec(x, y) {
    var scale = Math.max(x.scale, y.scale);
    var a = rescale(x, scale);
    var b = rescale(y, scale);
    return a < b ? -1 : a > b ? 1 : 0;
  }

  /** `numerator / denominator` rounded half-even (BigInt division truncates toward zero). */
  function divideHalfEven(numerator, denominator) {
    var zero = BigInt(0);
    var q = numerator / denominator;
    var r = numerator % denominator;
    if (r === zero) return q;
    var sign = numerator < zero !== denominator < zero ? BigInt(-1) : BigInt(1);
    var twice = (r < zero ? -r : r) * BigInt(2);
    var d = denominator < zero ? -denominator : denominator;
    if (twice > d || (twice === d && q % BigInt(2) !== zero)) q += sign;
    return q;
  }

  function notAMeasure(field) {
    return new Error("A value of '" + field + "' is not a number in its column's wire form.");
  }

  /** `sum | min | max` exact at the inputs' largest scale; `mean` exact ÷ count, HALF-EVEN at scale + 6 (record §4.1). */
  function aggregateExact(texts, agg, field) {
    var xs = texts.map(function (t) {
      var x = dec(t);
      if (!x) throw notAMeasure(field);
      return x;
    });
    var scale = Math.max.apply(
      null,
      xs.map(function (x) {
        return x.scale;
      }),
    );
    var bs = xs.map(function (x) {
      return rescale(x, scale);
    });
    var sum = function () {
      return bs.reduce(function (a, b) {
        return a + b;
      }, BigInt(0));
    };
    if (agg === "sum") return decStr(sum(), scale);
    if (agg === "min") return decStr(bs.reduce(function (a, b) { return b < a ? b : a; }), scale);
    if (agg === "max") return decStr(bs.reduce(function (a, b) { return b > a ? b : a; }), scale);
    var q = divideHalfEven(sum() * BigInt(10) ** BigInt(MEAN_EXTRA_SCALE), BigInt(bs.length));
    return decStr(q, scale + MEAN_EXTRA_SCALE).replace(/\.?0+$/, "");
  }

  function isNull(v) {
    return v === null || v === undefined;
  }

  /** One group's aggregate: `{num, exact}` — exact decimal arithmetic when any value is a string (an exact column). */
  function aggregate(vals, agg, field) {
    if (agg === "count") return { num: vals.length, exact: null };
    var xs = vals.filter(function (v) {
      return !isNull(v);
    });
    if (!xs.length) return { num: null, exact: null };
    if (
      xs.some(function (v) {
        return typeof v === "string";
      })
    ) {
      var e = aggregateExact(xs.map(String), agg, field);
      return { num: Number(e), exact: e };
    }
    if (
      xs.some(function (v) {
        return typeof v !== "number";
      })
    ) {
      throw notAMeasure(field);
    }
    var n;
    if (agg === "sum") n = xs.reduce(function (a, b) { return a + b; }, 0);
    else if (agg === "min") n = xs.reduce(function (a, b) { return b < a ? b : a; });
    else if (agg === "max") n = xs.reduce(function (a, b) { return b > a ? b : a; });
    else n = xs.reduce(function (a, b) { return a + b; }, 0) / xs.length;
    return { num: n, exact: null };
  }

  // ---------------------------------------------------------------------------------------------- order

  /** Code point order (a string's `<` compares UTF-16 units; the two differ above U+FFFF). */
  function cmpCodePoint(a, b) {
    var x = Array.from(a);
    var y = Array.from(b);
    for (var i = 0; i < Math.min(x.length, y.length); i++) {
      var d = x[i].codePointAt(0) - y[i].codePointAt(0);
      if (d) return d;
    }
    return x.length - y.length;
  }

  /** `ascending` by the column's type (correction 1): exact by decimal value, temporal by key, numbers by value. */
  function byValue(type, temporal) {
    if (temporal) {
      return function (a, b) {
        var x = temporalKey(a.raw);
        var y = temporalKey(b.raw);
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

  function order(items, sort, totalOf, type, temporal) {
    var byFirst = items.slice().sort(function (a, b) {
      return a.first - b.first;
    });
    if (sort === null || sort === undefined) return byFirst;
    if (Array.isArray(sort)) {
      var pos = new Map(
        sort.map(function (v, i) {
          return [String(v), i];
        }),
      );
      var listed = byFirst
        .filter(function (i) {
          return pos.has(String(i.raw));
        })
        .sort(function (a, b) {
          return pos.get(String(a.raw)) - pos.get(String(b.raw));
        });
      return listed.concat(
        byFirst.filter(function (i) {
          return !pos.has(String(i.raw));
        }),
      );
    }
    if (sort === "ascending" || sort === "descending") {
      var sorted = byFirst.sort(byValue(type, temporal));
      return sort === "descending" ? sorted.reverse() : sorted;
    }
    var desc = String(sort).charAt(0) === "-";
    return byFirst.sort(function (a, b) {
      var x = totalOf(a);
      var y = totalOf(b);
      if (x === null && y === null) return a.first - b.first;
      if (x === null) return 1;
      if (y === null) return -1;
      var c = desc ? (y < x ? -1 : y > x ? 1 : 0) : x < y ? -1 : x > y ? 1 : 0;
      return c || a.first - b.first;
    });
  }

  // ---------------------------------------------------------------------------------------------- model

  /** Which channel plays which role (orientation is derived: `y` quantitative = vertical). */
  function channels(config) {
    var e = config.encoding;
    var vertical = e.y.type === "quantitative";
    return {
      vertical: vertical,
      band: vertical ? { ch: "x", enc: e.x } : { ch: "y", enc: e.y },
      value: vertical ? { ch: "y", enc: e.y } : { ch: "x", enc: e.x },
      color: e.color ? { ch: "color", enc: e.color } : null,
    };
  }

  function keyOf(v) {
    return typeof v + ":" + String(v);
  }

  /**
   * The bar model: `{vertical, stack, band, value, color, categories, series, cells, totals, axis, fmt, formatName,
   * tooltip, warnings, locale}`. `cells[s][c] = {num, exact, text, aggregate}` — `num` the drawn value (null = a gap,
   * never a zero; a normalized stack's share), `exact` the exact decimal string, `aggregate` the aggregate's text
   * before normalization (the server's `text_visible` proof reads the same).
   */
  function model(spec, bindings, tokens) {
    var ch = channels(spec.config);
    var band = ch.band;
    var value = ch.value;
    var color = ch.color;
    var types = spec.columns && typeof spec.columns === "object" ? spec.columns : {};
    var warnings = [];
    var B = bindings[band.enc.field] || [];
    var V = value.enc.field ? bindings[value.enc.field] || [] : null;
    var C = color ? bindings[color.enc.field] || [] : null;
    var temporal = band.enc.type === "temporal";
    var cats = new Map();
    var sers = new Map();
    var groups = new Map();
    var droppedCat = 0;
    var droppedSer = 0;
    for (var i = 0; i < B.length; i++) {
      var b = B[i];
      var s = C ? C[i] : null;
      if (isNull(b)) {
        droppedCat++;
        continue;
      }
      if (C && isNull(s)) {
        droppedSer++;
        continue;
      }
      var bk = keyOf(b);
      var sk = C ? keyOf(s) : "";
      if (!cats.has(bk)) cats.set(bk, { key: bk, raw: b, first: cats.size, full: temporal ? temporalLabel(b, band.enc.format) : String(b) });
      if (!sers.has(sk)) sers.set(sk, { key: sk, raw: s, first: sers.size, full: C ? String(s) : String(value.enc.field || "count") });
      var g = bk + "\u0000" + sk;
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(V ? V[i] : 1);
    }
    if (droppedCat) warnings.push({ field: "rule.null-category", class: "data", message: droppedCat + " row(s) with a null category were not drawn." });
    if (droppedSer) warnings.push({ field: "rule.null-category", class: "data", message: droppedSer + " row(s) with a null series were not drawn." });

    var agg = value.enc.aggregate;
    var stack = color ? (value.enc.stack === undefined ? null : value.enc.stack) : undefined;
    var raw = new Map();
    groups.forEach(function (vals, key) {
      raw.set(key, aggregate(vals, agg, value.enc.field || "count"));
    });
    var cell = function (c, s2) {
      return raw.get(c.key + "\u0000" + s2.key) || { num: null, exact: null };
    };
    var catTotal = function (c) {
      var t = null;
      sers.forEach(function (s2) {
        var v = cell(c, s2).num;
        if (v !== null) t = (t === null ? 0 : t) + v;
      });
      return t;
    };
    var categories = order(Array.from(cats.values()), band.enc.sort, catTotal, types[band.enc.field], temporal).map(function (c) {
      return Object.assign({}, c, { label: cut(c.full) });
    });
    var series = order(
      Array.from(sers.values()),
      color ? color.enc.sort : null,
      function () {
        return null;
      },
      color ? types[color.enc.field] : undefined,
      false,
    );

    var locale = tokens.locale;
    var normalize = stack === "normalize";
    var fmt = numberFormatter(normalize ? value.enc.format || "percent" : value.enc.format, locale);
    var cells = series.map(function (s2) {
      return categories.map(function (c) {
        var r = cell(c, s2);
        if (r.num === null) return { num: null, exact: null, text: "", aggregate: null };
        var text = r.exact !== null ? r.exact : String(r.num);
        if (normalize) {
          var abs = 0;
          for (var t = 0; t < series.length; t++) {
            var v = cell(c, series[t]).num;
            if (v !== null) abs += Math.abs(v);
          }
          var share = abs ? r.num / abs : 0;
          return { num: share, exact: null, text: fmt(share), aggregate: text };
        }
        return { num: r.num, exact: r.exact, text: fmt(r.exact !== null ? r.exact : r.num), aggregate: text };
      });
    });
    var markCount = 0;
    cells.forEach(function (row) {
      row.forEach(function (c) {
        if (c.num !== null) markCount++;
      });
    });
    // No marks — no rows, every category null, or every aggregate null — is the server's `no_data` (ChartModel.noData);
    // the reference warned only for the first two, so a chart of all-null measures drew empty with no empty state.
    if (markCount === 0) warnings.push({ field: "rule.no-data", class: "data", message: "No rows to draw." });
    var totals = categories.map(function (c, ci) {
      var pos = 0;
      var neg = 0;
      var sum = null;
      for (var si = 0; si < series.length; si++) {
        var v = cells[si][ci].num;
        if (v === null) continue;
        if (v >= 0) pos += v;
        else neg += v;
        sum = (sum === null ? 0 : sum) + v;
      }
      return { pos: pos, neg: neg, sum: sum };
    });

    var lo = 0;
    var hi = 0;
    var stacked = stack === "zero" || normalize;
    for (var ci = 0; ci < categories.length; ci++) {
      if (stacked) {
        lo = Math.min(lo, totals[ci].neg);
        hi = Math.max(hi, totals[ci].pos);
      } else {
        for (var si2 = 0; si2 < series.length; si2++) {
          var v2 = cells[si2][ci].num;
          if (v2 !== null) {
            lo = Math.min(lo, v2);
            hi = Math.max(hi, v2);
          }
        }
      }
    }
    var domain = value.enc.scale && value.enc.scale.domain;
    var nice = domain ? Object.assign({}, niceTicks(domain[0], domain[1]), { lo: domain[0], hi: domain[1] }) : niceTicks(lo, hi);
    var ticks = nice.ticks.filter(function (t2) {
      return t2 >= nice.lo - 1e-12 && t2 <= nice.hi + 1e-12;
    });
    var axis = { lo: nice.lo, hi: nice.hi, step: nice.step, ticks: ticks, tickText: ticks.map(fmt) };

    var tooltip = function (si3, ci3) {
      return { category: categories[ci3].full, series: color ? series[si3].full : null, value: cells[si3][ci3].text };
    };
    var formatName = value.enc.format || (normalize ? "percent" : "default");
    return {
      vertical: ch.vertical,
      stack: stack,
      band: band,
      value: value,
      color: color,
      categories: categories,
      series: series,
      cells: cells,
      markCount: markCount,
      totals: totals,
      axis: axis,
      fmt: fmt,
      formatName: formatName,
      tooltip: tooltip,
      warnings: warnings,
      locale: locale,
    };
  }

  /** Axis and legend titles: absent = the default (field name; "Sum of amount"; "Count"), null = no title. */
  function titles(m) {
    var t = function (enc, dflt) {
      return enc.title === null ? null : enc.title === undefined ? dflt : enc.title;
    };
    var a = m.value.enc.aggregate;
    var agg = a.charAt(0).toUpperCase() + a.slice(1);
    return {
      band: t(m.band.enc, m.band.enc.field),
      value: t(m.value.enc, m.value.enc.field ? agg + " of " + m.value.enc.field : "Count"),
      series: m.color ? t(m.color.enc, m.color.enc.field) : null,
    };
  }

  /** A tooltip as one plain-text line; an HTML sink escapes it. */
  function tooltipText(t) {
    return t.category + (t.series !== null ? " · " + t.series : "") + ": " + t.value;
  }

  /**
   * The D-A field ids a document SETS (`chart-support/<library>.json` on the server; the same ids): a converter owes
   * one `degraded` warning per set field its library cannot draw directly. [columns] is `{field: LogicalType}`.
   */
  function setFields(doc, columns) {
    var c = doc.config;
    var ch = channels(c);
    var s = {};
    var add = function (f) {
      s[f] = true;
    };
    [
      "mark.type", "orientation", "category.field", "category.type", "category.sort", "value.aggregate",
      "rule.null-measure", "rule.null-category", "rule.no-data", "rule.text-as-text", "rule.tooltip-content", "rule.long-labels",
    ].forEach(add);
    var text = function (v) {
      return typeof v === "string";
    };
    if (ch.value.enc.field) add("value.field");
    if (ch.color) {
      add("color.field");
      add("color.sort");
      var stack = ch.value.enc.stack === undefined ? null : ch.value.enc.stack;
      add("value.stack=" + stack);
      if (stack !== null) add("rule.stack-sign");
      if (text(ch.color.enc.title)) add("color.title");
      if (ch.color.enc.legend === null) add("color.legend=null");
      else if (ch.color.enc.legend && ch.color.enc.legend.orient) add("color.legend.orient");
    }
    if (ch.band.enc.type === "temporal") add("rule.utc");
    if (ch.value.enc.field && columns && EXACT_TYPES[columns[ch.value.enc.field]] === true) add("rule.exact-big-numbers");
    if (text(doc.description)) add("description");
    if (c.title) {
      add("title.text");
      if (text(c.title.subtitle)) add("title.subtitle");
    }
    if (text(ch.band.enc.title)) add("category.title");
    if (text(ch.value.enc.title)) add("value.title");
    if (ch.band.enc.axis && "grid" in ch.band.enc.axis) add("category.axis.grid");
    if (ch.value.enc.axis && "grid" in ch.value.enc.axis) add("value.axis.grid");
    if (ch.band.enc.format) add("category.format");
    if (ch.value.enc.format) add("value.format");
    if (ch.value.enc.scale) add("value.scale.domain");
    if (c.mark.labels === "values") add("mark.labels");
    return Object.keys(s).sort();
  }

  /** The warnings a converter owes for the fields a document sets that its library classes `degraded`. */
  function degradedWarnings(set, table) {
    return Object.keys(table)
      .filter(function (f) {
        return set.indexOf(f) !== -1;
      })
      .map(function (field) {
        return { field: field, class: table[field][0], message: table[field][1] };
      });
  }

  // --------------------------------------------------------------------------------------------- tokens

  /**
   * The built-in tokens (record §5.2: "each token falling back to a built-in default when its property is unset"): the
   * product's light theme as the 854 probe resolved it. A page with the app stylesheet never reaches them.
   */
  var DEFAULT_TOKENS = {
    canvas: "rgb(255, 255, 255)",
    plot: "rgb(248, 249, 251)",
    grid: "rgb(203, 207, 212)",
    ink: "rgb(15, 23, 42)",
    muted: "rgb(100, 116, 139)",
    series: ["rgb(3, 105, 161)", "rgb(21, 128, 61)", "rgb(161, 98, 7)", "rgb(220, 38, 38)", "rgb(11, 115, 116)", "rgb(188, 71, 21)"],
    font: "ui-sans-serif, system-ui, sans-serif",
    locale: "en-US",
  };

  var TOKEN_PROPERTIES = { canvas: "--chart-canvas", plot: "--chart-plot", grid: "--chart-grid", ink: "--chart-ink", muted: "--chart-muted" };

  /** CSS Color 4's `color(srgb …)` to the legacy `rgb()`/`rgba()` every chart library's colour parser accepts. */
  function normaliseColor(computed) {
    if (!computed) return null;
    var value = String(computed).trim();
    if (value.indexOf("rgb") === 0 || value.charAt(0) === "#") return value;
    var srgb = /^color\(\s*srgb\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s*(?:\/\s*([0-9.]+)\s*)?\)$/.exec(value);
    if (!srgb) return null;
    var byte = function (x) {
      return Math.max(0, Math.min(255, Math.round(parseFloat(x) * 255)));
    };
    var rgb = byte(srgb[1]) + ", " + byte(srgb[2]) + ", " + byte(srgb[3]);
    var alpha = srgb[4] === undefined ? 1 : parseFloat(srgb[4]);
    return alpha >= 1 ? "rgb(" + rgb + ")" : "rgba(" + rgb + ", " + alpha + ")";
  }

  function computedStyle(win, element) {
    try {
      return win && typeof win.getComputedStyle === "function" ? win.getComputedStyle(element) : null;
    } catch (e) {
      return null;
    }
  }

  /**
   * One custom property through a FRESH probe in [parent]'s cascade (a reused probe answers its first value). An
   * UNSET property is detected on the parent first (`getPropertyValue` answers ""), so the inherited text colour is
   * never mistaken for a token; null → the built-in default.
   */
  function probe(doc, win, parent, property, cssProperty, read) {
    var parentStyle = computedStyle(win, parent);
    if (parentStyle && typeof parentStyle.getPropertyValue === "function" && String(parentStyle.getPropertyValue(property)).trim() === "") {
      return null;
    }
    var el = doc.createElement("span");
    el.setAttribute("aria-hidden", "true");
    el.style.position = "absolute";
    el.style.width = "0";
    el.style.height = "0";
    el.style.visibility = "hidden";
    el.style.pointerEvents = "none";
    el.style.transition = "none";
    el.style[cssProperty] = "var(" + property + ")";
    parent.appendChild(el);
    var out = null;
    try {
      var style = computedStyle(win, el);
      out = style ? read(style[cssProperty]) : null;
    } finally {
      parent.removeChild(el);
    }
    return out;
  }

  /** `{canvas, plot, grid, ink, muted, series[6], font, locale}` from [host]'s cascade, defaults where unset. */
  function resolveTokens(host) {
    var tokens = {
      canvas: DEFAULT_TOKENS.canvas,
      plot: DEFAULT_TOKENS.plot,
      grid: DEFAULT_TOKENS.grid,
      ink: DEFAULT_TOKENS.ink,
      muted: DEFAULT_TOKENS.muted,
      series: DEFAULT_TOKENS.series.slice(),
      font: DEFAULT_TOKENS.font,
      locale: DEFAULT_TOKENS.locale,
    };
    var doc = host && host.ownerDocument ? host.ownerDocument : typeof document !== "undefined" ? document : null;
    if (!doc || typeof doc.createElement !== "function") return tokens;
    var win = doc.defaultView || (typeof window !== "undefined" ? window : null);
    var parent = host && typeof host.appendChild === "function" ? host : doc.body;
    if (!parent) return tokens;
    var keys = Object.keys(TOKEN_PROPERTIES);
    for (var i = 0; i < keys.length; i++) {
      tokens[keys[i]] = probe(doc, win, parent, TOKEN_PROPERTIES[keys[i]], "color", normaliseColor) || tokens[keys[i]];
    }
    for (var s = 0; s < 6; s++) {
      tokens.series[s] = probe(doc, win, parent, "--chart-series-" + (s + 1), "color", normaliseColor) || tokens.series[s];
    }
    tokens.font =
      probe(doc, win, parent, "--font-sans", "fontFamily", function (v) {
        return v && String(v).trim() ? String(v).trim() : null;
      }) || tokens.font;
    var lang = doc.documentElement && typeof doc.documentElement.getAttribute === "function" ? doc.documentElement.getAttribute("lang") : null;
    if (lang && /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(lang)) tokens.locale = lang;
    return tokens;
  }

  // ------------------------------------------------------------------------------------ the registry

  var CONVERTERS = {};

  /**
   * `registerConverter({library, families: {bar: convert}, draw, resize?, dispose?})`: [library] one of the
   * identities; each family's `convert(spec, bindings, tokens) → {option, warnings}` is pure; `draw(instance, host,
   * option) → Promise` (resolving once drawn), `resize(instance, host)` and `dispose(instance, host)` are the
   * library's host side and the ONLY code that calls the host's `instance`.
   */
  function registerConverter(spec) {
    if (!spec || typeof spec !== "object" || LIBRARIES.indexOf(spec.library) === -1) {
      throw new Error("registerConverter requires a library that is one of the chart library identities");
    }
    if (!spec.families || typeof spec.families !== "object" || typeof spec.draw !== "function") {
      throw new Error("registerConverter requires { library, families, draw }");
    }
    var families = {};
    Object.keys(spec.families).forEach(function (family) {
      if (typeof spec.families[family] === "function") families[family] = spec.families[family];
    });
    CONVERTERS[spec.library] = {
      library: spec.library,
      families: families,
      draw: spec.draw,
      resize: typeof spec.resize === "function" ? spec.resize : function () {},
      dispose: typeof spec.dispose === "function" ? spec.dispose : function () {},
    };
    return api;
  }

  function familyOf(occurrence) {
    var config = occurrence && occurrence.config;
    var type = config && config.mark && typeof config.mark.type === "string" ? config.mark.type : null;
    return type !== null && Object.prototype.hasOwnProperty.call(FAMILIES, type) ? FAMILIES[type] : String(type);
  }

  /**
   * The mount judgement the runtime runs for every chart occurrence before any execution (record §5.2): null when the
   * occurrence can be drawn, else the refusal `{code, message, details}` the dashboard fails with as a whole.
   */
  function judge(occurrence, mount) {
    var charts = mount && mount.charts;
    var name = occurrence && occurrence.name;
    if (!charts) {
      return {
        code: "chart.library_undeclared",
        message: "the page declared no chart library: init needs charts { library, instance } to draw a chart",
        details: { name: name },
      };
    }
    var family = familyOf(occurrence);
    var converter = CONVERTERS[charts.library];
    if (!converter || !converter.families[family]) {
      return {
        code: "chart.converter_missing",
        message: "no converter is installed for this chart library and family",
        details: { library: charts.library, family: family, name: name },
      };
    }
    return null;
  }

  // ------------------------------------------------------------------------------------- the adapter

  /** The runtime's warnings: a code and a fixed sentence per code — never document text (record §5.2). */
  var WARNING_TEXT = {
    "chart.rule.no-data": "the chart has no rows to draw",
    "chart.rule.null-category": "rows with a null category or series were not drawn",
  };

  function renderWarnings(warnings) {
    var out = [];
    var seen = {};
    (warnings || []).forEach(function (w) {
      if (!w || typeof w.field !== "string") return;
      var code = (w.class === "data" ? "chart." : "chart.degraded.") + w.field;
      if (seen[code]) return;
      seen[code] = true;
      out.push({ code: code, message: WARNING_TEXT[code] || "the chart library draws a field of this chart approximately" });
    });
    return out;
  }

  /**
   * #473 — the card heading names the chart (a chart has no `presentation`, so the heading is its `display_name`): a
   * chart title whose text repeats it (trimmed) and that carries no subtitle says nothing the heading does not, and
   * is not drawn, so a card never prints the same words twice. A title with a subtitle stays whole — the subtitle
   * says something more and is drawn under its title. A new config; the occurrence's is unchanged.
   */
  function withoutDuplicateTitle(config, displayName) {
    if (!config || typeof config !== "object" || !config.title || typeof config.title !== "object") return config;
    if (typeof config.title.text !== "string" || typeof displayName !== "string") return config;
    var heading = displayName.trim();
    if (heading === "" || config.title.text.trim() !== heading || config.title.subtitle) return config;
    var drawn = Object.assign({}, config);
    delete drawn.title;
    return drawn;
  }

  function create(context) {
    var host = context.host;
    var occurrence = context.occurrence || {};
    var mount = { charts: context.charts || null };
    var refusal = judge(occurrence, mount);
    if (refusal) {
      var error = new Error(refusal.message);
      error.code = refusal.code;
      error.details = refusal.details;
      throw error;
    }
    var instance = mount.charts.instance;
    var converter = CONVERTERS[mount.charts.library];
    var convert = converter.families[familyOf(occurrence)];
    var spec = {
      display_name: occurrence.display_name,
      description: occurrence.description,
      config: withoutDuplicateTitle(occurrence.config, occurrence.display_name),
      columns: occurrence.columns && typeof occurrence.columns === "object" ? occurrence.columns : {},
    };
    // The accessible description (record §4.1 host contract): Plotly has no description of its own.
    if (typeof occurrence.description === "string" && occurrence.description !== "" && host && typeof host.setAttribute === "function") {
      host.setAttribute("role", "img");
      host.setAttribute("aria-label", occurrence.description);
    }

    /** Never throws: a model refusal (a measure outside its wire form) is the rejection the runtime reports as render.failed. */
    function draw(bindings) {
      var out;
      try {
        out = convert(spec, bindings, resolveTokens(host));
      } catch (error) {
        return Promise.reject(error);
      }
      var noData = out.warnings.some(function (w) {
        return w && w.field === "rule.no-data";
      });
      return Promise.resolve(converter.draw(instance, host, out.option)).then(function () {
        return { outcome: noData ? "no-data" : "rendered", warnings: renderWarnings(out.warnings) };
      });
    }

    return {
      /** The delivered columns through the converter; resolves `{outcome: "rendered" | "no-data", warnings}`. */
      renderData: function (occurrenceRef, rows, bindings) {
        return draw(bindings && typeof bindings === "object" ? bindings : {});
      },
      /** The server's `no-data` (no frame, no columns): the no-marks option — the previous marks are gone. */
      renderEmpty: function () {
        return draw({});
      },
      resize: function () {
        try {
          converter.resize(instance, host);
        } catch (e) {
          /* an undrawn host has nothing to resize */
        }
      },
      dispose: function () {
        try {
          converter.dispose(instance, host);
        } catch (e) {
          /* already gone */
        }
      },
    };
  }

  var api = {
    LIBRARIES: LIBRARIES.slice(),
    registerConverter: registerConverter,
    /** Idempotent: a second call registers the same kind again (the runtime keys renderers by kind). */
    register: function (dashboard) {
      if (!dashboard) return;
      dashboard.registerRenderer({
        kind: "chart",
        version: "1",
        create: create,
        judge: judge,
        // Every v1 family is a 2D trace: the 3D bundle is never needed by a chart (record §5.2).
        needsBundle: function () {
          return "2d";
        },
        libraries: LIBRARIES.slice(),
      });
    },
    /** The library-neutral core, for the converters, the node tests and the parity emitter. */
    core: {
      model: model,
      titles: titles,
      tooltipText: tooltipText,
      setFields: setFields,
      degradedWarnings: degradedWarnings,
      channels: channels,
      numberFormatter: numberFormatter,
      temporalKey: temporalKey,
      temporalLabel: temporalLabel,
      escapeHtml: escapeHtml,
      cut: cut,
      niceTicks: niceTicks,
      aggregate: aggregate,
    },
    _internal: {
      resolveTokens: resolveTokens,
      withoutDuplicateTitle: withoutDuplicateTitle,
      normaliseColor: normaliseColor,
      renderWarnings: renderWarnings,
      judge: judge,
      converters: function () {
        return CONVERTERS;
      },
      resetConverters: function () {
        Object.keys(CONVERTERS).forEach(function (k) {
          delete CONVERTERS[k];
        });
      },
      DEFAULT_TOKENS: DEFAULT_TOKENS,
      LABEL_CODE_POINTS: LABEL_CODE_POINTS,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DatapipelinesDashboardChart = api;
    api.register(window.DatapipelinesDashboard);
  }
})();
