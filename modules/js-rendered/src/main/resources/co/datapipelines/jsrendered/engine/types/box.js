// types/box.js : one distribution per category (record §4.5 box; look contract §2.1 and §4.7; brief 1072).
// Quartiles use type-7 interpolation. Exact text stays in BigInt through the statistics and outside-point ordering;
// only coordinates cross to float64. The cartesian frame owns axes; the interaction layer owns cards and rings.
(function (R) {
  "use strict";
  var D = R.data, P = R.prim, T = R.text, look = R.tables.look, cap = R.tables.cap, r2 = R.scales.r2;
  var ZERO = BigInt(0), FOUR = BigInt(4), EIGHT = BigInt(8);
  var CHANNELS = [
    { key: "x", required: false, node: { node: "shape", name: "band" } },
    { key: "y", required: true, node: { node: "shape", name: "position" } },
  ];
  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }
  function title(e) { return e.title === null ? null : e.title === undefined ? e.field : e.title; }
  function compare(a, b) { return a < b ? -1 : a > b ? 1 : 0; }
  function ten(n) { var v = BigInt(1); for (var i = 0; i < n; i++) v *= BigInt(10); return v; }
  function decimal(v, scale) {
    var sign = v < ZERO ? "-" : "", digits = (v < ZERO ? -v : v).toString();
    while (digits.length <= scale) digits = "0" + digits;
    if (!scale) return sign + digits;
    return (sign + digits.slice(0, -scale) + "." + digits.slice(-scale)).replace(/0+$/, "").replace(/\.$/, "");
  }

  /** Exact values at the largest input scale; quartiles in quarters and fences in eighths of that scale. */
  function exactStatistics(values) {
    var scale = 0, parts = values.map(function (v) {
      var match = /^(-?)([0-9]+)(?:\.([0-9]+))?$/.exec(v.exact), frac = match[3] || "";
      scale = Math.max(scale, frac.length);
      return { value: v, integer: BigInt(match[1] + match[2] + frac), scale: frac.length };
    });
    parts.forEach(function (p) { p.integer *= ten(scale - p.scale); });
    parts.sort(function (a, b) { return compare(a.integer, b.integer) || a.value.row - b.value.row; });
    function quartile(k) {
      var h = (parts.length - 1) * k, index = Math.floor(h / 4), rest = BigInt(h % 4);
      return parts[index].integer * (FOUR - rest) + parts[Math.min(index + 1, parts.length - 1)].integer * rest;
    }
    var q1 = quartile(1), median = quartile(2), q3 = quartile(3);
    var span = (q3 - q1) * BigInt(3), low = q1 * BigInt(2) - span, high = q3 * BigInt(2) + span;
    function stat(q) {
      // When interpolation lands on an input, retain its text (including its authored decimal scale).
      for (var j = 0; j < parts.length; j++) if (parts[j].integer * FOUR === q) return parts[j].value;
      var text = decimal(q * BigInt(25), scale + 2);
      return { num: Number(text), exact: text };
    }
    var inside = [], outside = [];
    parts.forEach(function (p) {
      var v = p.integer * EIGHT, side = v < low ? -1 : v > high ? 1 : 0;
      if (!side) inside.push(p.value);
      else outside.push({ num: p.value.num, exact: p.value.exact, row: p.value.row, side: side, order: p.integer,
        distance: side < 0 ? q1 * BigInt(2) - v : v - q3 * BigInt(2) });
    });
    return { q1: stat(q1), median: stat(median), q3: stat(q3), low: inside[0], high: inside[inside.length - 1], outside: outside };
  }

  /** Finite interpolation avoids forming b-a. A non-finite fence is tested with every term divided by eight. */
  function statistics(values) {
    if (values.every(function (v) { return v.exact !== null; })) return exactStatistics(values);
    var sorted = values.slice().sort(function (a, b) { return compare(a.num, b.num) || a.row - b.row; });
    function quartile(p) {
      var h = (sorted.length - 1) * p, i = Math.floor(h), f = h - i;
      return f === 0 ? sorted[i] : { num: (1 - f) * sorted[i].num + f * sorted[i + 1].num, exact: null };
    }
    var q1 = quartile(0.25), median = quartile(0.5), q3 = quartile(0.75), span = 1.5 * (q3.num - q1.num);
    var low = q1.num - span, high = q3.num + span;
    var low8 = q1.num / 8 - 1.5 * (q3.num / 8 - q1.num / 8), high8 = q3.num / 8 + 1.5 * (q3.num / 8 - q1.num / 8);
    var inside = [], outside = [];
    sorted.forEach(function (v) {
      var below = isFinite(low) ? v.num < low : v.num / 8 < low8;
      var above = isFinite(high) ? v.num > high : v.num / 8 > high8;
      if (!below && !above) inside.push(v);
      else outside.push({ num: v.num, exact: v.exact, row: v.row, side: below ? -1 : 1, order: v.num,
        distance: below ? q1.num - v.num : v.num - q3.num });
    });
    return { q1: q1, median: median, q3: q3, low: inside[0], high: inside[inside.length - 1], outside: outside };
  }

  /** Within a side compare values, across sides compare distances; ties favour the low side, then input order. */
  function farthest(a, b) {
    if (a.side === b.side) return (a.side < 0 ? compare(a.order, b.order) : compare(b.order, a.order)) || a.row - b.row;
    return compare(b.distance, a.distance) || a.side - b.side || a.row - b.row;
  }

  function model(doc, columns, types) {
    var enc = doc.config.encoding, x = enc.x || null, y = enc.y, scale = y.scale || {};
    var X = x ? D.column(columns, x.field) : null, Y = D.column(columns, y.field), groups = new Map(), warned = new Map();
    var log = scale.type === "log", temporal = x && x.type === "temporal";
    var option = doc.config.mark.outliers === undefined ? look("boxOutliersDefault") : doc.config.mark.outliers;
    for (var i = 0; i < D.rowCount(columns); i++) {
      if (X && D.isNull(X[i])) { warned.set("chart.rule.null-category", true); continue; }
      if (D.isNull(Y[i])) continue;
      var value = D.aggregate([Y[i]], "min", y.field);
      if (!isFinite(value.num)) { warned.set("chart.rule.non-finite", true); continue; }
      if (log && value.num <= 0) { warned.set("chart.rule.log-nonpositive", true); continue; }
      var raw = X ? X[i] : "", key = D.keyOf(raw);
      if (!groups.has(key)) groups.set(key, { key: key, raw: raw, first: groups.size, full: x ? (temporal ? T.temporalFull(raw, x.format) : String(raw)) : y.field, values: [] });
      groups.get(key).values.push({ num: value.num, exact: value.exact, row: i });
    }
    var categories = Array.from(groups.values());
    if (x) categories = D.order(categories, x.sort, function () { return null; }, (types || {})[x.field], temporal);
    var drawn = 0;
    categories.forEach(function (c) {
      c.stats = statistics(c.values);
      c.points = option === "points" ? c.stats.outside.slice().sort(farthest).slice(0, cap("box.outliers_each")) : [];
      drawn += c.points.length;
    });
    if (categories.length <= cap("box.marks") && drawn > cap("box.outliers")) return { rule: { code: "chart.too_many_marks", details: { marks: drawn, limit: cap("box.outliers") } } };
    var valueTitle = title(y);
    if (valueTitle !== null && y.unit) valueTitle += " (" + y.unit + ")";
    return {
      series: [{ id: "measure", title: y.field, colour: R.theme.SERIES[0] }], categories: categories, markCount: categories.length,
      warnings: Array.from(warned.keys()).map(function (code) { return { code: code }; }),
      text: function (v) { return R.lines.withUnit(T.value(v.exact === null ? v.num : v.exact, y.format), y.unit); },
      layout: {
        vertical: true, log: log, bands: categories.map(function (c) { return temporal ? T.temporalAxis(c.raw, x.format) : { text: c.full, under: null }; }),
        bandTitle: x ? title(x) : null, valueTitle: valueTitle, bandGrid: x && x.axis ? x.axis.grid : undefined,
        valueGrid: y.axis ? y.axis.grid : undefined, format: y.format, domain: scale.domain || null, endLabels: null,
        extent: function (visible) {
          var lo = null, hi = null;
          if (visible.measure === true) categories.forEach(function (c) {
            [c.stats.low, c.stats.high].concat(c.points).forEach(function (v) {
              if (lo === null || v.num < lo) lo = v.num;
              if (hi === null || v.num > hi) hi = v.num;
            });
          });
          if (lo === null) return log ? [null, null] : [0, 0];
          return scale.zero === true && !log ? [Math.min(0, lo), Math.max(0, hi)] : [lo, hi];
        },
      },
    };
  }

  function rows(m, stats) {
    var result = [
      { name: "Highest inside", value: m.text(stats.high) }, { name: "Upper quarter", value: m.text(stats.q3) },
      { name: "Middle", value: m.text(stats.median) }, { name: "Lower quarter", value: m.text(stats.q1) },
      { name: "Lowest inside", value: m.text(stats.low) },
    ];
    if (stats.outside.length) result.push({ name: "Outside points", value: T.value(stats.outside.length, "integer") });
    return result;
  }

  function marks(m, hand, h) {
    var theme = h.theme, series = m.series[0], children = [], points = [], boxes = [], pointLookup = [], out = [];
    var plot = hand.plot, step = hand.band.step, width = Math.min(look("boxMaxWidth"), look("boxShare") * step), capWidth = look("boxCapShare") * width;
    var line = theme.n("--chart-line"), minHeight = Math.min(look("boxMinLines") * line, plot.h), radius = theme.n("--chart-bar-radius");
    var pointRadius = Math.max(0, theme.n("--chart-point") - look("boxOutsideLess")), ring = look("boxOutsideRing") * theme.n("--chart-gap");
    var reach = pointRadius + ring / 2, k = reach > 0 ? Math.min(1, width / 2 / reach, plot.h / 2 / reach) : 1;
    var rPoint = r2(pointRadius * k), rRing = ring * k, outer = reach * k;
    var fill = series.colour, css = theme.css(fill), canvas = "--chart-canvas", canvasCss = theme.css(canvas);
    function py(v) { return hand.value.scale(clamp(v.num, hand.value.lo, hand.value.hi)); }
    if (hand.visible.measure === true) m.categories.forEach(function (c, i) {
      var s = c.stats, cx = hand.band.start + step * (i + 0.5), left = cx - width / 2;
      var yHigh = py(s.high), yLow = py(s.low), q3 = py(s.q3), q1 = py(s.q1), median = py(s.median);
      var height = Math.max(q1 - q3, minHeight), top = clamp((q1 + q3) / 2 - height / 2, plot.y, plot.y + plot.h - height);
      var r = Math.min(radius, width / 2, height / 2), base = { role: "mark", series: series.id, datum: i };
      var stroke = { fill: null, stroke: fill, strokeColor: css, strokeWidth: line, round: true };
      children.push(P.path(Object.assign({ d: ["M", cx, yHigh, "L", cx, yLow] }, base, stroke)));
      children.push(P.path(Object.assign({ d: ["M", cx - capWidth / 2, yHigh, "L", cx + capWidth / 2, yHigh] }, base, stroke)));
      children.push(P.path(Object.assign({ d: ["M", cx - capWidth / 2, yLow, "L", cx + capWidth / 2, yLow] }, base, stroke)));
      var paint = { fill: fill, color: css };
      if (r > 0) children.push(P.path(Object.assign({ d: ["M", left + r, top, "L", left + width - r, top, "A", r, left + width, top + r,
        "L", left + width, top + height - r, "A", r, left + width - r, top + height, "L", left + r, top + height,
        "A", r, left, top + height - r, "L", left, top + r, "A", r, left + r, top, "Z"] }, base, paint)));
      else children.push(P.rect(Object.assign({ x: left, y: top, w: width, h: height }, base, paint)));
      children.push(P.path(Object.assign({ d: ["M", left, median, "L", left + width, median] }, base,
        { fill: null, stroke: canvas, strokeColor: canvasCss, strokeWidth: line })));
      var high = Math.min(yHigh, top), low = Math.max(yLow, top + height);
      boxes.push({ series: series.id, datum: i, row: 0, key: true, near: false, emphasis: "ring",
        shape: { kind: "box", x: r2(left), y: r2(high), w: r2(width), h: r2(low - high) }, card: { heading: c.full, rows: rows(m, s), keep: 2 } });
      c.points.forEach(function (v, j) {
        var cy = r2(clamp(py(v), plot.y + outer, plot.y + plot.h - outer)), x = r2(cx), datum = i + "/" + j;
        points.push(P.circle({ role: "mark", series: series.id, datum: datum, cx: x, cy: cy, r: rPoint,
          fill: fill, color: css, stroke: canvas, strokeColor: canvasCss, strokeWidth: rRing, opacity: look("boxOutsideOpacity") }));
        pointLookup.push({ series: series.id, datum: datum, row: 0, key: false, near: false, emphasis: "ring",
          shape: { kind: "point", x: x, y: cy, r: rPoint }, card: { heading: c.full, rows: [{ name: "This point", value: m.text(v) }].concat(rows(m, s)), keep: 0 } });
      });
    });
    P.group(out, { role: "mark", series: series.id, label: series.title }, children.concat(points));
    return { primitives: out, lookup: boxes.concat(pointLookup), count: boxes.length };
  }
  R.type("box", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
