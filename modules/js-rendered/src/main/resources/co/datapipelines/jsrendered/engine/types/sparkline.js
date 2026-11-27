// types/sparkline.js : one line on the bare frame (§4.5 sparkline; look contract §4.7; #1076).
// The shared line model owns aggregation, gaps and warnings. This type owns its data-range scale, edge-to-edge
// span, fill and end dot. The frame owns the last value's fit and the kept reach; the layer owns the crosshair.
(function (R) {
  "use strict";
  var P = R.prim, L = R.lines, T = R.text, look = R.tables.look;
  var CHANNELS = [
    { key: "x", required: true, node: { node: "oneOf", of: [{ shape: "band", types: ["nominal", "ordinal"] }, { shape: "position", types: ["temporal", "quantitative"] }] } },
    { key: "y", required: true, node: { node: "shape", name: "value" } },
  ];

  // Exact aggregates may share one float. Reuse the data helper for their signed difference; a true tie keeps
  // the first point. Geometry still uses num, while Highest and Lowest retain the exact value they name.
  function compareCells(a, b) {
    if (a.num !== b.num) return a.num < b.num ? -1 : 1;
    if (a.exact === null || b.exact === null) return 0;
    var opposite = b.exact.charAt(0) === "-" ? b.exact.slice(1) : "-" + b.exact;
    var difference = R.data.aggregate([a.exact, opposite], "sum", null).exact;
    return /^-?0+(?:\.0+)?$/.test(difference) ? 0 : difference.charAt(0) === "-" ? -1 : 1;
  }

  function model(doc, columns, types) {
    var m = L.model(doc, columns, types, { area: false });
    if (m.rule) return m;
    var mark = doc.config.mark, y = doc.config.encoding.y;
    m.sparkLast = mark.last === undefined ? look("sparkLastDefault") : mark.last;
    m.sparkFill = mark.fill === undefined ? look("sparkFillDefault") : mark.fill;
    var cells = m.series.length ? m.cells[0] : [], last = null, hi = null, lo = null;
    for (var j = 0; j < cells.length; j++) {
      var c = cells[j];
      if (!c) continue;
      last = c;
      if (hi === null || compareCells(c, hi) > 0) hi = c;
      if (lo === null || compareCells(c, lo) < 0) lo = c;
    }
    m.sparkCells = { last: last, hi: hi, lo: lo };
    m.sparkFlat = false;
    if (lo !== null && m.layout.domain === null && !m.layout.log) {
      var zero = y.scale && y.scale.zero === true;
      var a = zero ? Math.min(0, lo.num) : lo.num, b = zero ? Math.max(0, hi.num) : hi.num;
      if (a < b) m.layout.domain = [a, b];
      // No arithmetic on a flat value: even subnormals and the largest finite values sit at the middle.
      else m.sparkFlat = true;
    }
    m.layout.last = m.sparkLast === "value" && last !== null ? L.withUnit(T.value(last.exact !== null ? last.exact : last.num, m.format || look("lastLabelFormat")), y.unit) : null;
    m.layout.reach = function (h) {
      return m.sparkLast === "none" ? h.theme.n("--chart-line") / 2 : h.theme.n("--chart-point") - look("sparkDotShrink") + h.theme.n("--chart-gap") / 2;
    };
    return m;
  }

  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

  function marks(m, hand, h) {
    var theme = h.theme, plot = hand.plot, ser = m.series[0], row = m.cells[0], n = m.xs.length;
    var value = hand.value, ys = m.sparkFlat ? function () { return plot.y + plot.h / 2; } : value.scale;
    var first = -1, last = -1, count = 0, idx = [];
    for (var j = 0; j < n; j++) {
      if (row[j] !== undefined) idx.push(row[j] === null ? -1 : j);
      if (row[j]) { if (first < 0) first = j; last = j; count++; }
    }
    var xpx = m.xs.map(function (x, ordinal) {
      if (first === last) return plot.x + plot.w;
      var fraction;
      if (m.layout.x) {
        var a = m.xs[first].at, b = m.xs[last].at, span = b - a;
        fraction = isFinite(span) ? (x.at - a) / span : (x.at / 2 - a / 2) / (b / 2 - a / 2);
      } else fraction = (ordinal - first) / (last - first);
      return plot.x + plot.w * fraction;
    });
    var drawn = idx;
    if (count > R.tables.cap("sparkline.points")) {
      var px = [], vy = [];
      for (var u = 0; u < idx.length; u++) {
        px.push(idx[u] < 0 ? (u > 0 ? px[u - 1] : 0) : xpx[idx[u]]);
        vy.push(idx[u] < 0 ? null : -ys(clamp(row[idx[u]].num, value.lo, value.hi)));
      }
      var keep = R.thin.series(px, vy, []);
      drawn = idx.filter(function (k, ordinal) { return k < 0 || keep[ordinal] === true; });
    }
    var runs = [], run = [];
    for (var r = 0; r < drawn.length; r++) {
      if (drawn[r] < 0) { if (run.length) runs.push(run); run = []; }
      else run.push({ x: xpx[drawn[r]], y: ys(clamp(row[drawn[r]].num, value.lo, value.hi)) });
    }
    if (run.length) runs.push(run);
    var out = [], kids = [], line = [], bottom = plot.y + plot.h, css = theme.css(ser.colour), end = null;
    for (var g = 0; g < runs.length; g++) {
      var points = runs[g], segments = R.curve.segments(points, m.curve);
      end = points[points.length - 1];
      if (points.length < 2) continue;
      if (m.sparkFill) {
        var area = R.curve.forward(points, segments, []), fade = look("areaFade") === true;
        area.push("L", end.x, bottom, "L", points[0].x, bottom, "Z");
        kids.push(P.path({ role: "mark", d: area, fill: ser.colour, color: css, opacity: fade ? 1 : theme.n("--chart-area-top"), series: ser.id, fade: fade ? [theme.n("--chart-area-top"), theme.n("--chart-area-bottom"), plot.y, bottom] : null }));
      }
      R.curve.forward(points, segments, line);
    }
    if (line.length) kids.push(P.path({ role: "mark", d: line, stroke: ser.colour, strokeColor: css, strokeWidth: theme.n("--chart-line"), round: look("lineRound") === true, series: ser.id }));
    var share = hand.reach && hand.reach.asked > 0 ? hand.reach.kept / hand.reach.asked : 1;
    if (m.sparkLast !== "none" && end !== null) kids.push(P.circle({ role: "mark", cx: end.x, cy: end.y, r: R.scales.r2((theme.n("--chart-point") - look("sparkDotShrink")) * share), fill: ser.colour, color: css, stroke: "--chart-canvas", strokeColor: theme.css("--chart-canvas"), strokeWidth: R.scales.r2(theme.n("--chart-gap") * share), series: ser.id }));
    P.group(out, { role: "mark", series: ser.id, label: ser.title }, kids);
    var cells = m.sparkCells;
    var fields = [look("sparkLastText") + ": " + cells.last.text, look("sparkHighText") + ": " + cells.hi.text, look("sparkLowText") + ": " + cells.lo.text];
    var index = { id: ser.id, on: true, at: [], datum: [], tip: [] };
    for (var z = 0; z < n; z++) {
      if (!row[z]) continue;
      index.at.push(R.scales.r2(xpx[z]));
      index.datum.push(z);
      index.tip.push([m.xFull(m.xs[z]), row[z].text].concat(fields));
    }
    return { primitives: out, index: { axis: "x", plot: plot, series: [index] }, count: index.at.length, ends: end === null ? [] : [{ id: ser.id, y: end.y }] };
  }

  R.type("sparkline", { channels: CHANNELS, model: model, marks: marks, axes: "bare" });
})(JsRendered);
