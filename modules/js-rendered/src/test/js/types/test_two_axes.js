// types/test_two_axes.js : a type for TESTS ONLY (brief 1051 D5, D8), on the frame `cartesian`: a second value axis at the
// right, the layout part's `second` member. Bars on the left axis (`value`), and a line with a dot per category on the
// right axis (`value2`); two series, "bars" and "line". In no load order, no jar and no page. Its document:
// config.encoding.x = {field, title?} (the categories, in order of first appearance), y = {field, log?, domain?,
// format?, title?} (the bars), y2 = {field, log?, domain?, format?, title?} (the line). A bar is drawn for a number
// (on a log axis a positive one), a dot for a number of y2 (likewise); a category is one row.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function usable(v, log) {
    return typeof v === "number" && v === v && (!log || v > 0);
  }

  function extentOf(vals, log, zero) {
    var lo = Infinity, hi = -Infinity;
    for (var i = 0; i < vals.length; i++) {
      if (vals[i] === null) continue;
      lo = Math.min(lo, vals[i]);
      hi = Math.max(hi, vals[i]);
    }
    if (lo === Infinity) return log ? [null, null] : [0, 0];
    return log || !zero ? [lo, hi] : [Math.min(0, lo), Math.max(0, hi)];
  }

  function model(doc, columns) {
    var e = doc.config.encoding, ey = e.y, e2 = e.y2, logY = ey.log === true, log2 = e2.log === true;
    var X = D.column(columns, e.x.field), Y = D.column(columns, ey.field), Y2 = D.column(columns, e2.field), n = D.rowCount(columns);
    var bands = [], bars = [], line = [], rowOf = [];
    for (var i = 0; i < n; i++) {
      if (D.isNull(X[i])) continue;
      bands.push({ text: String(X[i]), under: null });
      bars.push(usable(Y[i], logY) ? Y[i] : null);
      line.push(usable(Y2[i], log2) ? Y2[i] : null);
      rowOf.push(i);
    }
    var nb = 0, nl = 0;
    for (var k = 0; k < bars.length; k++) {
      if (bars[k] !== null) nb++;
      if (line[k] !== null) nl++;
    }
    var series = [{ id: "bars", title: ey.title || "Bars", colour: R.theme.SERIES[0] }, { id: "line", title: e2.title || "Line", colour: R.theme.SERIES[1] }];
    return {
      series: series,
      // A mark is a bar, the line (one path where it has two dots or more) and a dot.
      markCount: nb + nl + (nl >= 2 ? 1 : 0),
      warnings: [],
      legend: e.legend,
      bars: bars,
      line: line,
      rowOf: rowOf,
      layout: {
        vertical: true,
        bands: bands,
        bandTitle: e.x.title || null,
        valueTitle: ey.title || null,
        bandGrid: undefined,
        valueGrid: undefined,
        format: ey.format || null,
        domain: ey.domain || null,
        log: logY,
        endLabels: null,
        reach: function (h) {
          return h.theme.n("--chart-point");
        },
        extent: function (visible) {
          return visible.bars === true ? extentOf(bars, logY, true) : logY ? [null, null] : [0, 0];
        },
        second: {
          extent: function (visible) {
            return visible.line === true ? extentOf(line, log2, false) : log2 ? [null, null] : [0, 0];
          },
          domain: e2.domain || null,
          format: e2.format || null,
          log: log2,
          title: e2.title || null,
        },
      },
    };
  }

  function clampY(v, plot) {
    return Math.min(Math.max(v, plot.y), plot.y + plot.h);
  }

  function marks(m, hand, h) {
    var plot = hand.plot, out = [], band = hand.band, gap = h.theme.n("--chart-gap");
    var bt = m.series[0].colour, lt = m.series[1].colour, r = h.theme.n("--chart-point");
    if (hand.visible.bars === true) {
      var base = hand.value.log ? plot.y + plot.h : clampY(hand.value.scale(Math.max(hand.value.lo, Math.min(0, hand.value.hi))), plot);
      for (var i = 0; i < m.bars.length; i++) {
        if (m.bars[i] === null) continue;
        var y = clampY(hand.value.scale(m.bars[i]), plot), x = band.start + band.step * i + band.step / 4;
        out.push(P.rect({ role: "mark", x: x, y: Math.min(y, base), w: band.step / 2, h: Math.abs(base - y), fill: bt, color: h.theme.css(bt), series: "bars", datum: m.rowOf[i] }));
      }
    }
    if (hand.visible.line === true) {
      var pts = [];
      for (var j = 0; j < m.line.length; j++) {
        if (m.line[j] !== null) pts.push({ x: band.start + band.step * (j + 0.5), y: clampY(hand.value2.scale(m.line[j]), plot), j: j });
      }
      if (pts.length >= 2) {
        var d = [];
        for (var q = 0; q < pts.length; q++) d.push(q === 0 ? "M" : "L", pts[q].x, pts[q].y);
        out.push(P.path({ role: "mark", d: d, stroke: lt, strokeColor: h.theme.css(lt), strokeWidth: h.theme.n("--chart-line"), series: "line" }));
      }
      for (var c = 0; c < pts.length; c++) {
        out.push(P.circle({ role: "mark", cx: pts[c].x, cy: pts[c].y, r: r - gap / 2, fill: lt, color: h.theme.css(lt), series: "line", datum: m.rowOf[pts[c].j] }));
      }
    }
    return out;
  }

  R.type("test_two_axes", {
    channels: [
      { key: "x", required: true, node: { shape: "value", types: ["nominal"] } },
      { key: "y", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "y2", required: true, node: { shape: "value", types: ["quantitative"] } },
    ],
    model: model,
    marks: marks,
    axes: "cartesian",
  });
})(JsRendered);
