// types/test_points.js : a type for TESTS ONLY (brief 1051 D3, D8), on the frame `cartesian`: two continuous axes with no
// aggregation, the form a layout part with `x` and `vertical: true` asks for (no new member). One circle per row at
// x.scale(x) and value.scale(y), coloured by its series. In the template's own form so it runs where the engine runs; in
// no load order, no jar and no page (engine.mjs's engineWith and the JVM guard JsRenderedTestTypesTest load it after the
// engine). Its document: config.encoding.x = {field, kind: "linear" | "log" | "time", domain?, zero?, format?, title?},
// y = {field, log?, domain?, format?, title?}, color = {field, legend?} (absent: one series). A row is a mark when its
// series is not null, its x and y are numbers (time: a date text) and, on a log axis, positive.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function model(doc, columns) {
    var e = doc.config.encoding, ex = e.x, ey = e.y, color = e.color || null;
    var X = D.column(columns, ex.field), Y = D.column(columns, ey.field), C = color ? D.column(columns, color.field) : null;
    var n = D.rowCount(columns), kind = ex.kind || "linear", logY = ey.log === true;
    var series = [], byKey = new Map(), rows = [];
    for (var i = 0; i < n; i++) {
      var v = C ? C[i] : "rows", t = kind === "time" ? R.time.parse(X[i]) : null, xv = kind === "time" ? (t === null ? null : t.at) : X[i], yv = Y[i];
      if (D.isNull(v) || typeof xv !== "number" || typeof yv !== "number" || xv !== xv || yv !== yv) continue;
      if ((kind === "log" && !(xv > 0)) || (logY && !(yv > 0))) continue;
      var key = typeof v + ":" + String(v);
      if (!byKey.has(key)) {
        byKey.set(key, series.length);
        series.push({ id: C ? key : "measure", title: String(v), colour: R.theme.SERIES[series.length] || null });
      }
      rows.push({ series: series[byKey.get(key)].id, row: i, x: xv, y: yv });
    }
    var xlo = Infinity, xhi = -Infinity;
    for (var r = 0; r < rows.length; r++) {
      xlo = Math.min(xlo, rows[r].x);
      xhi = Math.max(xhi, rows[r].x);
    }
    if (rows.length === 0) {
      xlo = kind === "log" ? 1 : 0;
      xhi = kind === "log" ? 10 : 1;
    }
    var xd = kind === "time" ? R.time.domain(xlo, xhi, "date") : [xlo, xhi];
    var m = { series: series, markCount: rows.length, warnings: [], legend: color ? color.legend : undefined, rows: rows };
    m.layout = {
      vertical: true,
      bands: [],
      x: { kind: kind, lo: xd[0], hi: xd[1], gran: "date", format: ex.format || null, domain: ex.domain || null, zero: ex.zero === true },
      log: logY,
      bandTitle: ex.title || null,
      valueTitle: ey.title || null,
      bandGrid: undefined,
      valueGrid: undefined,
      format: ey.format || null,
      domain: ey.domain || null,
      endLabels: null,
      reach: function (h) {
        return h.theme.n("--chart-point");
      },
      extent: function (visible) {
        var lo = Infinity, hi = -Infinity;
        for (var k = 0; k < rows.length; k++) {
          if (visible[rows[k].series] !== true) continue;
          lo = Math.min(lo, rows[k].y);
          hi = Math.max(hi, rows[k].y);
        }
        return lo === Infinity ? (logY ? [null, null] : [0, 0]) : [lo, hi];
      },
    };
    return m;
  }

  function clamp(v, a, b) {
    return Math.min(Math.max(v, Math.min(a, b)), Math.max(a, b));
  }

  function marks(m, hand, h) {
    var plot = hand.plot, out = [], colourOf = new Map(), r = h.theme.n("--chart-point");
    for (var s = 0; s < m.series.length; s++) colourOf.set(m.series[s].id, m.series[s].colour);
    for (var i = 0; i < m.rows.length; i++) {
      var p = m.rows[i];
      if (hand.visible[p.series] !== true) continue;
      var token = colourOf.get(p.series);
      var cx = clamp(hand.x.scale(p.x), plot.x, plot.x + plot.w), cy = clamp(hand.value.scale(p.y), plot.y, plot.y + plot.h);
      out.push(P.circle({ role: "mark", cx: cx, cy: cy, r: r, fill: token, color: h.theme.css(token), series: p.series, datum: p.row }));
    }
    return out;
  }

  R.type("test_points", {
    channels: [
      { key: "x", required: true, node: { shape: "value", types: ["quantitative", "temporal"] } },
      { key: "y", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "color", required: false, node: { shape: "series", types: ["nominal"] } },
    ],
    model: model,
    marks: marks,
    axes: "cartesian",
  });
})(JsRendered);
