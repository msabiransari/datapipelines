// types/test_bare.js : a type for TESTS ONLY (brief 1051 D7, D8), on the frame `bare`: a line as a path through its
// points, the first at the plot's left edge and the last at its right edge, a dot on the last point, and the layout
// part's `last`: the last value's text, read from its document's options. In no load order, no jar and no page. Its
// document: config.encoding.x = {field} (the points' order, one per row), y = {field}, color = {field} (absent: one
// series); config.options = {last: "value" | "dot" | "none", format?} (default "value", format "compact"). With
// `value` the model hands `last` the first series' last value written in the format, and only when that series has a
// value (the model's promise, D7); with `dot` and `none` it hands null. It returns {primitives, index, count, ends}: the
// index whole (the frame is not cartesian: render keeps a template's own index), count the mark primitives.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function model(doc, columns) {
    var e = doc.config.encoding, color = e.color || null, opts = doc.config.options || {};
    var Y = D.column(columns, e.y.field), C = color ? D.column(columns, color.field) : null, n = D.rowCount(columns);
    var lastOpt = opts.last === "dot" || opts.last === "none" ? opts.last : "value";
    var series = [], byKey = new Map(), points = [], count = 0, bands = [];
    for (var i = 0; i < n; i++) {
      var v = C ? C[i] : "rows";
      if (D.isNull(v)) continue;
      var key = typeof v + ":" + String(v);
      if (!byKey.has(key)) {
        byKey.set(key, series.length);
        series.push({ id: C ? key : "measure", title: String(v), colour: R.theme.SERIES[series.length] || null });
        points.push([]);
      }
      var s = byKey.get(key);
      points[s].push({ row: i, y: typeof Y[i] === "number" && Y[i] === Y[i] ? Y[i] : null });
      if (typeof Y[i] === "number" && Y[i] === Y[i]) count++;
    }
    var most = 0;
    for (var p = 0; p < points.length; p++) most = Math.max(most, points[p].length);
    for (var b = 0; b < most; b++) bands.push({ text: "", under: null });
    var lastValue = null;
    if (points.length > 0) for (var q = 0; q < points[0].length; q++) if (points[0][q].y !== null) lastValue = points[0][q].y;
    return {
      series: series,
      markCount: count,
      warnings: [],
      legend: undefined,
      points: points,
      lastOpt: lastOpt,
      layout: {
        vertical: true,
        bands: bands,
        log: false,
        domain: null,
        endLabels: null,
        last: lastOpt === "value" && lastValue !== null ? R.text.value(lastValue, opts.format || "compact") : null,
        reach: function (h) {
          return h.theme.n("--chart-point") - 0.5 + h.theme.n("--chart-gap") / 2;
        },
        extent: function (visible) {
          var lo = Infinity, hi = -Infinity;
          for (var k = 0; k < points.length; k++) {
            if (visible[series[k].id] !== true) continue;
            for (var j = 0; j < points[k].length; j++) {
              if (points[k][j].y === null) continue;
              lo = Math.min(lo, points[k][j].y);
              hi = Math.max(hi, points[k][j].y);
            }
          }
          return lo === Infinity ? [0, 0] : [lo, hi];
        },
      },
    };
  }

  function marks(m, hand, h) {
    var plot = hand.plot, out = [], ends = [], index = { axis: "x", plot: plot, series: [] }, count = 0;
    var span = Math.max(1, hand.band.n - 1);
    for (var s = 0; s < m.series.length; s++) {
      var ser = m.series[s], token = ser.colour, ix = { id: ser.id, on: hand.visible[ser.id] === true, at: [], datum: [], tip: [] };
      index.series.push(ix);
      if (!ix.on) continue;
      var pts = [];
      for (var j = 0; j < m.points[s].length; j++) {
        var pt = m.points[s][j];
        if (pt.y === null) continue;
        var x = hand.band.n === 1 ? plot.x + plot.w : plot.x + (plot.w * j) / span;
        pts.push({ x: x, y: Math.min(Math.max(hand.value.scale(pt.y), plot.y), plot.y + plot.h) });
        ix.at.push(R.scales.r2(x));
        ix.datum.push(pt.row);
        ix.tip.push([]);
      }
      if (pts.length === 0) continue;
      if (pts.length >= 2) {
        var d = [];
        for (var q = 0; q < pts.length; q++) d.push(q === 0 ? "M" : "L", pts[q].x, pts[q].y);
        out.push(P.path({ role: "mark", d: d, stroke: token, strokeColor: h.theme.css(token), strokeWidth: h.theme.n("--chart-line"), round: true, series: ser.id }));
        count++;
      }
      var last = pts[pts.length - 1];
      if (m.lastOpt !== "none") {
        // The frame's kept reach (#1128): the dot and its ring by kept / asked.
        var share = hand.reach.asked > 0 ? hand.reach.kept / hand.reach.asked : 1;
        var dotR = h.theme.n("--chart-point") - 0.5, ring = h.theme.n("--chart-gap");
        if (share < 1) {
          dotR = R.scales.r2(dotR * share);
          ring = R.scales.r2(ring * share);
        }
        out.push(P.circle({ role: "mark", cx: last.x, cy: last.y, r: dotR, fill: token, color: h.theme.css(token), stroke: "--chart-canvas", strokeColor: h.theme.css("--chart-canvas"), strokeWidth: ring, series: ser.id }));
        count++;
      }
      ends.push({ id: ser.id, y: last.y });
    }
    return { primitives: out, index: index, count: count, ends: ends };
  }

  R.type("test_bare", {
    channels: [
      { key: "x", required: true, node: { shape: "value", types: ["nominal", "quantitative", "temporal"] } },
      { key: "y", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "color", required: false, node: { shape: "series", types: ["nominal"] } },
    ],
    model: model,
    marks: marks,
    axes: "bare",
  });
})(JsRendered);
