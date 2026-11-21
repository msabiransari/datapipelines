// types/test_web.js : a type for TESTS ONLY (brief 1055 D7), on the frame `radial`: per series one closed path through
// its points and one circle on every point, each point at R.trig.point(centre, r.scale(value), angles[spoke]), so the
// radial axis's geometry crosses both runtimes inside a picture. Its document: config.encoding.category = {field} names
// the spokes' column (the categories in their first order), encoding.value = {field, scale?: {domain}, format?} the
// value column, encoding.color = {field, legend?} the series column (optional). Rows sharing a series and a category
// add up. It refuses nothing: a negative value or a wrong count of categories reaches the frame, whose refusal is what
// the tests read. In no load order, no jar and no page.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function model(doc, columns) {
    var e = doc.config.encoding, color = e.color || null;
    var K = D.column(columns, e.category.field), V = D.column(columns, e.value.field), C = color ? D.column(columns, color.field) : null;
    var n = D.rowCount(columns), spokes = [], spokeOf = new Map(), series = [], byKey = new Map(), cells = new Map(), points = [];
    for (var i = 0; i < n; i++) {
      var k = K[i], v = V[i], s = C ? C[i] : "rows";
      if (D.isNull(k) || D.isNull(s) || typeof v !== "number") continue;
      var kk = typeof k + ":" + String(k), sk = typeof s + ":" + String(s);
      if (!spokeOf.has(kk)) {
        spokeOf.set(kk, spokes.length);
        spokes.push({ text: String(k) });
      }
      if (!byKey.has(sk)) {
        byKey.set(sk, series.length);
        series.push({ id: C ? sk : "measure", title: String(s), colour: R.theme.SERIES[series.length] || null });
      }
      var id = series[byKey.get(sk)].id, cell = id + "\u0000" + spokeOf.get(kk);
      if (cells.has(cell)) cells.get(cell).value += v;
      else {
        var p = { series: id, spoke: spokeOf.get(kk), value: v, row: i };
        cells.set(cell, p);
        points.push(p);
      }
    }
    var scale = e.value.scale || null;
    var layout = {
      spokes: spokes,
      extent: function (visible) {
        var lo = Infinity, hi = -Infinity;
        for (var q = 0; q < points.length; q++) {
          if (visible[points[q].series] !== true) continue;
          lo = Math.min(lo, points[q].value);
          hi = Math.max(hi, points[q].value);
        }
        return lo > hi ? [0, 0] : [lo, hi];
      },
      domain: scale && scale.domain ? scale.domain : null,
      format: e.value.format || null,
    };
    return { series: series, markCount: points.length, warnings: [], legend: color ? color.legend : undefined, layout: layout, points: points };
  }

  function marks(m, hand, h) {
    var out = [], c = hand.centre;
    for (var s = 0; s < m.series.length; s++) {
      var id = m.series[s].id, token = m.series[s].colour;
      if (hand.visible[id] !== true) continue;
      var mine = m.points.filter(function (p) {
        return p.series === id;
      }).sort(function (a, b) {
        return a.spoke - b.spoke;
      });
      if (mine.length === 0) continue;
      var d = [], at = [];
      for (var i = 0; i < mine.length; i++) {
        var q = R.trig.point(c.x, c.y, hand.r.scale(mine[i].value), hand.angles[mine[i].spoke]);
        at.push(q);
        d.push(i === 0 ? "M" : "L", q.x, q.y);
      }
      d.push("Z");
      out.push(P.path({ role: "mark", d: d, stroke: token, strokeColor: h.theme.css(token), strokeWidth: h.theme.n("--chart-line"), series: id }));
      for (var j = 0; j < at.length; j++) {
        out.push(P.circle({ role: "mark", cx: at[j].x, cy: at[j].y, r: h.theme.n("--chart-point"), fill: token, color: h.theme.css(token), series: id, datum: mine[j].row }));
      }
    }
    return out;
  }

  R.type("test_web", { channels: [{ key: "color", required: false, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "radial" });
})(JsRendered);
