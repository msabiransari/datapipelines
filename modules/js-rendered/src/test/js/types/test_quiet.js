// types/test_quiet.js : a type for TESTS ONLY (brief 1049 D5, D6) that proves a frame's two booleans: it adds its own
// frame, `test_quiet`, with titles and legend both false (and so no `room`), and registers a type on it. The plot is the
// frame inside its edge; one bar per row across it, coloured by its series. Whatever its document and the hidden list
// say, a draw of it has no title text, no legend group and no hidden series. In no load order, no jar and no page.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, r2 = R.scales.r2;

  R.frames.add("test_quiet", {
    titles: false,
    legend: false,
    lay: function (h, W, H) {
      var e = h.em("edge"), plot = { x: r2(e), y: r2(e), w: r2(Math.max(1, W - 2 * e)), h: r2(Math.max(1, H - 2 * e)) };
      return { plot: plot, hand: { plot: plot }, under: [], axes: [], over: [] };
    },
  });

  function model(doc, columns) {
    var color = doc.config.encoding && doc.config.encoding.color ? doc.config.encoding.color : null;
    var C = color ? D.column(columns, color.field) : null, n = D.rowCount(columns);
    var series = [], byKey = new Map(), rows = [];
    for (var i = 0; i < n; i++) {
      var v = C ? C[i] : "rows";
      if (D.isNull(v)) continue;
      var key = typeof v + ":" + String(v);
      if (!byKey.has(key)) {
        byKey.set(key, series.length);
        series.push({ id: C ? key : "measure", title: String(v), colour: R.theme.SERIES[series.length] || null });
      }
      rows.push({ series: series[byKey.get(key)].id, row: i });
    }
    return { series: series, markCount: rows.length, warnings: [], legend: color ? color.legend : undefined, rows: rows };
  }

  function marks(m, hand, h) {
    var plot = hand.plot, on = [], out = [];
    for (var i = 0; i < m.rows.length; i++) if (hand.visible[m.rows[i].series] === true) on.push(m.rows[i]);
    var step = plot.w / Math.max(1, on.length);
    var colourOf = new Map();
    for (var s = 0; s < m.series.length; s++) colourOf.set(m.series[s].id, m.series[s].colour);
    for (var k = 0; k < on.length; k++) {
      var token = colourOf.get(on[k].series);
      out.push(P.rect({ role: "mark", x: plot.x + k * step + step / 10, y: plot.y, w: (step * 4) / 5, h: plot.h, fill: token, color: h.theme.css(token), series: on[k].series, datum: on[k].row }));
    }
    return out;
  }

  R.type("test_quiet", { channels: [{ key: "color", required: false, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "test_quiet" });
})(JsRendered);
