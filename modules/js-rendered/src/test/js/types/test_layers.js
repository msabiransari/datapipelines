// types/test_layers.js : a type for TESTS ONLY (brief 1101), on the frame `cartesian`: bars and a line over ONE crosshair,
// the shape the combo type (#1069) draws, with the smallest model that reproduces it. Series "bars" (bars, the left axis),
// "bars2" (a second bar layer, the left axis, drawn beside the first; only when y3 is given) and "line" (a dot per category
// and a line through them, the right axis). Every layer's index entry puts a mark at the category's CENTRE, so the layers
// share one crosshair; each bar layer's entry also carries `spans`, the bars' own boxes (the contract of #1101). In no
// load order, no jar and no page. Its document: config.encoding.x = {field, title?} (the categories, in order of first
// appearance), y = {field, title?, format?} (bars), y2 = {field, title?, format?} (the line), y3 = {field, title?} (the
// second bar layer, optional); config.options = {lineFirst?: true (the line's series before the bars'), spans?: false (the
// bar layers carry no `spans`: today's template, the control of the defect)}. A layer draws a mark for a number only.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function usable(v) {
    return typeof v === "number" && v === v;
  }

  function extentOf(defs, visible, zero) {
    var lo = Infinity, hi = -Infinity;
    for (var d = 0; d < defs.length; d++) {
      if (visible[defs[d].id] !== true) continue;
      for (var i = 0; i < defs[d].vals.length; i++) {
        if (defs[d].vals[i] === null) continue;
        lo = Math.min(lo, defs[d].vals[i]);
        hi = Math.max(hi, defs[d].vals[i]);
      }
    }
    if (lo === Infinity) return [0, 0];
    return zero ? [Math.min(0, lo), Math.max(0, hi)] : [lo, hi];
  }

  function model(doc, columns) {
    var e = doc.config.encoding, o = doc.config.options || {};
    var X = D.column(columns, e.x.field), n = D.rowCount(columns);
    var defs = [{ id: "bars", kind: "bar", e: e.y, name: "Bars" }];
    if (e.y3) defs.push({ id: "bars2", kind: "bar", e: e.y3, name: "Bars 2" });
    var line = { id: "line", kind: "line", e: e.y2, name: "Line" };
    if (o.lineFirst === true) defs.unshift(line); else defs.push(line);
    var bands = [], rowOf = [], i, d;
    for (d = 0; d < defs.length; d++) defs[d].vals = [];
    for (i = 0; i < n; i++) {
      if (D.isNull(X[i])) continue;
      bands.push({ text: String(X[i]), under: null });
      rowOf.push(i);
      for (d = 0; d < defs.length; d++) {
        var v = D.column(columns, defs[d].e.field)[i];
        defs[d].vals.push(usable(v) ? v : null);
      }
    }
    var marksN = 0;
    for (d = 0; d < defs.length; d++) for (i = 0; i < defs[d].vals.length; i++) if (defs[d].vals[i] !== null) marksN++;
    var series = defs.map(function (s, k) {
      return { id: s.id, title: s.e.title || s.name, colour: R.theme.SERIES[k], legendKey: s.kind === "bar" ? "legendKeyBar" : "legendKeyLine" };
    });
    var left = defs.filter(function (s) { return s.kind === "bar"; }), right = [line];
    return {
      series: series,
      markCount: marksN,
      warnings: [],
      legend: e.legend,
      defs: defs,
      rowOf: rowOf,
      spans: o.spans !== false,
      tips: bands.map(function (b) { return b.text; }),
      layout: {
        vertical: true,
        bands: bands,
        bandTitle: e.x.title || null,
        valueTitle: e.y.title || null,
        bandGrid: undefined,
        valueGrid: undefined,
        format: e.y.format || null,
        domain: null,
        log: false,
        endLabels: null,
        reach: function (h) {
          return h.theme.n("--chart-point");
        },
        extent: function (visible) {
          return extentOf(left, visible, true);
        },
        second: {
          extent: function (visible) {
            return extentOf(right, visible, false);
          },
          domain: null,
          format: e.y2.format || null,
          log: false,
          title: e.y2.title || null,
        },
      },
    };
  }

  function clampY(v, plot) {
    return Math.min(Math.max(v, plot.y), plot.y + plot.h);
  }

  function marks(m, hand, h) {
    var plot = hand.plot, band = hand.band, out = [], index = [], count = 0, gap = h.theme.n("--chart-gap");
    var r2 = R.scales.r2, shownBars = m.defs.filter(function (s) { return s.kind === "bar" && hand.visible[s.id] === true; });
    var perId = {};
    for (var d = 0; d < m.defs.length; d++) {
      var s = m.defs[d], ser = m.series[d], token = ser.colour, css = h.theme.css(token);
      if (hand.visible[s.id] !== true) continue;
      var ix = { series: s.id, at: [], datum: [], tip: [] }, pts = [];
      if (s.kind === "bar") {
        var k = shownBars.length, groupW = band.step / 2, barW = (groupW - gap * (k - 1)) / k, slot = shownBars.indexOf(s);
        var base = clampY(hand.value.scale(Math.max(hand.value.lo, Math.min(0, hand.value.hi))), plot);
        if (m.spans) ix.spans = [];
        for (var i = 0; i < s.vals.length; i++) {
          if (s.vals[i] === null) continue;
          var y = clampY(hand.value.scale(s.vals[i]), plot), x = band.start + band.step * i + (band.step - groupW) / 2 + slot * (barW + gap);
          out.push(P.rect({ role: "mark", x: x, y: Math.min(y, base), w: barW, h: Math.abs(base - y), fill: token, color: css, series: s.id }));
          ix.at.push(r2(band.start + band.step * (i + 0.5)));
          ix.datum.push(m.rowOf[i]);
          ix.tip.push([m.tips[i], ser.title + ": " + String(s.vals[i])]);
          if (m.spans) ix.spans.push({ x: r2(x), w: r2(barW), foot: r2(base), end: r2(y) });
          count++;
        }
      } else {
        for (var j = 0; j < s.vals.length; j++) {
          if (s.vals[j] !== null) pts.push({ x: band.start + band.step * (j + 0.5), y: clampY(hand.value2.scale(s.vals[j]), plot), j: j });
        }
        if (pts.length >= 2) {
          var path = [];
          for (var q = 0; q < pts.length; q++) path.push(q === 0 ? "M" : "L", pts[q].x, pts[q].y);
          out.push(P.path({ role: "mark", d: path, stroke: token, strokeColor: css, strokeWidth: h.theme.n("--chart-line"), series: s.id }));
          count++;
        }
        for (var c = 0; c < pts.length; c++) {
          out.push(P.circle({ role: "mark", cx: pts[c].x, cy: pts[c].y, r: h.theme.n("--chart-point") - gap / 2, fill: token, color: css, series: s.id }));
          ix.at.push(r2(pts[c].x));
          ix.datum.push(m.rowOf[pts[c].j]);
          ix.tip.push([m.tips[pts[c].j], ser.title + ": " + String(s.vals[pts[c].j])]);
          count++;
        }
      }
      perId[s.id] = ix;
    }
    for (var z = 0; z < m.defs.length; z++) if (perId[m.defs[z].id]) index.push(perId[m.defs[z].id]);
    return { primitives: out, index: index, count: count };
  }

  R.type("test_layers", {
    channels: [
      { key: "x", required: true, node: { shape: "value", types: ["nominal"] } },
      { key: "y", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "y2", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "y3", required: false, node: { shape: "value", types: ["quantitative"] } },
    ],
    model: model,
    marks: marks,
    axes: "cartesian",
  });
})(JsRendered);
