// types/test_keys.js : a type for TESTS ONLY (brief 1054 D9, D10), on the frame `none`: test_plain's rectangles, with a
// legend of keys (no entry a switch) as the waterfall, the gauge and the treemap will hand one. In the template's own
// form so it runs where the engine runs; it is in no load order, no jar and no page (engine.mjs's engineWith and the JVM
// guard JsRenderedTestTypesTest load it after the engine). Its document: config.encoding.color = {field, legend?} names
// the series column (absent: one series); config.test = {keys: [{id, title, key, colour}], keysAlone}: handed to the
// legend as they are written. Every row whose series is not null is a mark; a series's marks are one group, as bar's.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, r2 = R.scales.r2;

  function model(doc, columns) {
    var color = doc.config.encoding && doc.config.encoding.color ? doc.config.encoding.color : null;
    var given = doc.config.test && typeof doc.config.test === "object" ? doc.config.test : {};
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
    return { series: series, markCount: rows.length, warnings: [], legend: color ? color.legend : undefined, rows: rows, keys: given.keys, keysAlone: given.keysAlone };
  }

  function marks(m, hand, h) {
    var plot = hand.plot, on = [], out = [];
    for (var i = 0; i < m.rows.length; i++) if (hand.visible[m.rows[i].series] === true) on.push(m.rows[i]);
    if (on.length === 0) return out;
    var cols = Math.max(1, Math.ceil(Math.sqrt((on.length * plot.w) / plot.h))), lines = Math.ceil(on.length / cols);
    var cw = plot.w / cols, ch = plot.h / lines, gap = Math.min(cw, ch) / 10;
    var colourOf = new Map(), kidsOf = new Map();
    for (var s = 0; s < m.series.length; s++) {
      colourOf.set(m.series[s].id, m.series[s].colour);
      kidsOf.set(m.series[s].id, []);
    }
    for (var k = 0; k < on.length; k++) {
      var c = k % cols, l = (k - c) / cols, token = colourOf.get(on[k].series);
      var w = cw - 2 * gap, hh = ch - 2 * gap;
      kidsOf.get(on[k].series).push(P.rect({ role: "mark", x: plot.x + c * cw + gap, y: plot.y + l * ch + gap, w: w, h: hh, fill: token, color: h.theme.css(token), rx: r2(Math.min(w, hh) / 5), series: on[k].series, datum: on[k].row }));
    }
    // One group per series that draws, as bar groups its marks: the page dims a series by its group (app.css).
    for (var g = 0; g < m.series.length; g++) {
      var kids = kidsOf.get(m.series[g].id);
      if (kids.length > 0) P.group(out, { role: "mark", series: m.series[g].id, label: m.series[g].title }, kids);
    }
    return out;
  }

  R.type("test_keys", { channels: [{ key: "color", required: false, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "none" });
})(JsRendered);
