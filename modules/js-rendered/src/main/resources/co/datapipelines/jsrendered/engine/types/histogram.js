// types/histogram.js — one numeric distribution, optionally stacked by series (§4.5; #1071).
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, S = R.scales, r2 = S.r2, look = R.tables.look;
  var CHANNELS = [
    { key: "x", required: true, node: { node: "shape", name: "position" } },
    { key: "y", required: true, node: { node: "shape", name: "count" } },
    { key: "color", required: false, node: { node: "shape", name: "series" } },
  ];
  function finite(v) { return typeof v === "number" && v - v === 0; }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  /** Sturges by doubling: ceil(log2(n)) + 1, without a runtime-dependent logarithm. */
  function sturges(n) {
    var k = 1, p = 1;
    while (p < n) { p *= 2; k++; }
    return k;
  }
  /** Largest lower edge by binary search; the last interval includes its upper edge. */
  function binOf(edges, v) {
    var lo = 0, hi = edges.length - 1;
    if (v >= edges[hi]) return hi - 1;
    while (hi - lo > 1) {
      var mid = (lo + hi) >> 1;
      if (edges[mid] <= v) lo = mid;
      else hi = mid;
    }
    return lo;
  }
  function totalOf(m, visible) {
    var total = 0;
    for (var s = 0; s < m.series.length; s++) if (visible[m.series[s].id] === true)
      for (var b = 0; b < m.cells[s].length; b++) total += m.cells[s][b];
    return total;
  }
  function model(doc, columns, types) {
    var enc = doc.config.encoding, x = enc.x, color = enc.color || null, share = enc.y.aggregate === "share";
    var X = D.column(columns, x.field), C = color ? D.column(columns, color.field) : null;
    var warnings = [], warned = {}, groups = new Map(), rows = [], lo = null, hi = null;
    function warn(code) {
      if (warned[code] === true) return;
      warned[code] = true;
      warnings.push({ code: code });
    }
    for (var i = 0; i < D.rowCount(columns); i++) {
      var cv = C ? C[i] : null;
      if (C && D.isNull(cv)) { warn("chart.rule.null-category"); continue; }
      if (D.isNull(X[i])) { warn("chart.rule.null-position"); continue; }
      var v = typeof X[i] === "number" ? X[i] : Number(X[i]);
      if (!finite(v)) { warn("chart.rule.non-finite"); continue; }
      var key = C ? D.keyOf(cv) : "measure";
      if (!groups.has(key)) groups.set(key, { key: key, raw: cv, first: groups.size, full: C ? String(cv) : share ? "Share" : "Count" });
      rows.push({ key: key, x: v });
      lo = lo === null ? v : Math.min(lo, v);
      hi = hi === null ? v : Math.max(hi, v);
    }
    var seriesCap = R.tables.cap("series");
    if (groups.size > seriesCap) return { rule: { code: "chart.too_many_series", details: { series: groups.size, limit: seriesCap } } };
    var series = Array.from(groups.values());
    if (C) series = D.order(series, color.sort, function () { return null; }, types && types[color.field], false);
    var byKey = new Map();
    for (var s = 0; s < series.length; s++) {
      series[s].id = series[s].key;
      series[s].title = series[s].full;
      series[s].colour = R.theme.SERIES[s];
      byKey.set(series[s].key, s);
    }
    var cap = R.tables.cap("histogram.marks"), bins = doc.config.mark.bins;
    var asked = finite(bins) && Math.floor(bins) === bins && bins >= 1 ? bins : Math.min(cap, Math.max(5, sturges(rows.length)));
    if (asked > cap) return { rule: { code: "chart.too_many_marks", details: { marks: asked, limit: cap } } };
    var edges = lo === null ? [0, 1] : S.nice(lo, hi, asked).ticks, nb = edges.length - 1, cells = [];
    for (var j = 0; j < series.length; j++) {
      var counts = [];
      for (var b = 0; b < nb; b++) counts.push(0);
      cells.push(counts);
    }
    for (var r = 0; r < rows.length; r++) cells[byKey.get(rows[r].key)][binOf(edges, rows[r].x)]++;
    var occupied = 0;
    for (var c = 0; c < nb; c++) {
      var any = false;
      for (var q = 0; q < series.length; q++) if (cells[q][c] > 0) any = true;
      if (any) occupied++;
    }
    var title = x.title === null ? null : x.title === undefined ? x.field : x.title;
    if (title !== null && typeof x.unit === "string" && x.unit !== "") title += " (" + x.unit + ")";
    var m = {
      series: series, edges: edges, edgeTexts: T.ticks(edges, x.format), cells: cells, markCount: occupied,
      warnings: warnings, legend: color ? color.legend : undefined, enc: enc, share: share,
      layout: {
        vertical: true, bands: [], log: false, whole: !share,
        x: { kind: "linear", lo: edges[0], hi: edges[nb], edges: edges, format: x.format, domain: null, zero: false },
        bandTitle: title, valueTitle: share ? "Share" : "Count", bandGrid: x.axis ? x.axis.grid : undefined,
        valueGrid: undefined, format: share ? "percent" : undefined, domain: null, endLabels: null,
        extent: function (visible) {
          var tallest = 0, total = totalOf(m, visible);
          for (var b = 0; b < nb; b++) {
            var count = 0;
            for (var s = 0; s < series.length; s++) if (visible[series[s].id] === true) count += cells[s][b];
            tallest = Math.max(tallest, share ? total > 0 ? count / total : 0 : count);
          }
          return [0, tallest];
        },
      },
    };
    return m;
  }
  function edgeText(m, i) { return R.lines.withUnit(m.edgeTexts[i], m.enc.x.unit); }

  /** Square baseline, with only the outermost segment's two top corners rounded. */
  function shape(base, x, y, w, h, radius) {
    if (!(radius > 0) || w <= 0 || h <= 0)
      return P.rect({ role: "mark", x: x, y: y, w: w, h: h, fill: base.fill, color: base.color, series: base.series, datum: base.datum });
    var d = ["M", x, y + h, "L", x, y + radius, "A", radius, x + radius, y, "L", x + w - radius, y, "A", radius, x + w, y + radius, "L", x + w, y + h, "Z"];
    return P.path({ role: "mark", d: d, fill: base.fill, color: base.color, series: base.series, datum: base.datum });
  }
  function marks(m, hand, h) {
    var theme = h.theme, xs = hand.x, vs = hand.value, visible = [], kids = [], lookup = [];
    for (var s = 0; s < m.series.length; s++) {
      kids.push([]);
      if (hand.visible[m.series[s].id] === true) visible.push(s);
    }
    var gap = theme.n("--chart-gap"), radius = theme.n("--chart-bar-radius"), maxShare = look("radiusMaxShare");
    var total = totalOf(m, hand.visible);
    for (var b = 0; b < m.edges.length - 1; b++) {
      var x0 = xs.scale(m.edges[b]), wide = xs.scale(m.edges[b + 1]) - x0;
      var g = Math.min(gap, wide / 2), bx = r2(x0 + g / 2), bw = r2(Math.max(0, wide - g));
      var outer = -1, acc = 0, segments = [];
      for (var o = 0; o < visible.length; o++) if (m.cells[visible[o]][b] > 0) outer = visible[o];
      for (var j = 0; j < visible.length; j++) {
        var si = visible[j], count = m.cells[si][b];
        if (count === 0) continue;
        var value = m.share ? total > 0 ? count / total : 0 : count, from = acc;
        acc += value;
        var bottom = vs.scale(clamp(from, vs.lo, vs.hi)), top = vs.scale(clamp(acc, vs.lo, vs.hi));
        var length = Math.abs(bottom - top), shift = from > 0 ? Math.min(gap, length) : 0;
        var paint = length - shift, round = si === outer ? Math.min(radius, bw * maxShare, paint) : 0;
        segments.push({ si: si, count: count, value: value, y: r2(top), h: r2(paint), hit: r2(length), radius: r2(round) });
      }
      for (var z = 0; z < segments.length; z++) {
        var sg = segments[z], ser = m.series[sg.si], token = ser.colour, css = theme.css(token), rows = [];
        kids[sg.si].push(shape({ fill: token, color: css, series: ser.id, datum: b }, bx, sg.y, bw, sg.h, sg.radius));
        for (var k = segments.length - 1; k >= 0; k--) {
          var cell = segments[k], sr = m.series[cell.si];
          rows.push({ name: sr.title, value: m.share ? T.value(cell.value, "percent") : T.value(cell.count, "integer"), colour: sr.colour, color: theme.css(sr.colour), bold: cell.si === sg.si });
        }
        lookup.push({ series: ser.id, datum: b, shape: { kind: "box", x: r2(x0), y: sg.y, w: r2(wide), h: sg.hit },
          row: sg.si, key: true, near: false, emphasis: null,
          card: { heading: "from " + edgeText(m, b) + " to " + edgeText(m, b + 1), rows: rows, keep: segments.length - 1 - z } });
      }
    }
    var rank = {};
    for (var q = 0; q < m.series.length; q++) rank[m.series[q].id] = q;
    lookup.sort(function (a, b) { return rank[a.series] - rank[b.series] || a.datum - b.datum; });
    var out = [];
    for (var v = 0; v < visible.length; v++) {
      var one = m.series[visible[v]];
      P.group(out, { role: "mark", series: one.id, label: one.title }, kids[visible[v]]);
    }
    return { primitives: out, lookup: lookup, count: lookup.length };
  }
  R.type("histogram", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
