// types/test_plain.js : a type for TESTS ONLY (brief 1049 D5, D6), on the frame `none`: one rounded rectangle per row,
// laid in a grid inside the plot, coloured by its series, with a legend of its series. In the template's own form so it
// runs where the engine runs; it is in no load order, no jar and no page (engine.mjs's engineWith and the JVM guard
// JsRenderedTestTypesTest load it after the engine). Its document: config.encoding.color = {field, legend?} names the
// series column (absent: one series); every row whose series is not null is a mark.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, r2 = R.scales.r2;

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
    if (on.length === 0) return out;
    var cols = Math.max(1, Math.ceil(Math.sqrt((on.length * plot.w) / plot.h))), lines = Math.ceil(on.length / cols);
    var cw = plot.w / cols, ch = plot.h / lines, gap = Math.min(cw, ch) / 10;
    var colourOf = new Map();
    for (var s = 0; s < m.series.length; s++) colourOf.set(m.series[s].id, m.series[s].colour);
    for (var k = 0; k < on.length; k++) {
      var c = k % cols, l = (k - c) / cols, token = colourOf.get(on[k].series);
      var w = cw - 2 * gap, hh = ch - 2 * gap;
      out.push(P.rect({ role: "mark", x: plot.x + c * cw + gap, y: plot.y + l * ch + gap, w: w, h: hh, fill: token, color: h.theme.css(token), rx: r2(Math.min(w, hh) / 5), series: on[k].series, datum: on[k].row }));
    }
    return out;
  }

  R.type("test_plain", { channels: [{ key: "color", required: false, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "none" });
})(JsRendered);
