// types/test_tones.js : a type for TESTS ONLY (brief 1053 D10), on the frame `none`, modelled on test_plain: one
// rounded rectangle per row, laid in a grid inside the plot, each filled with the colour with a meaning its row names
// (the look contract's §3.6: positive, negative, total, good, warn, bad → --chart-<tone>), one series, no legend. It
// proves the path from a theme to a primitive for each of the six tokens before any product type draws them (the
// waterfall's and the gauge's lanes carry LA2 for their own pictures). In no load order, no jar and no page (engine.mjs's
// engineWith and the JVM guard JsRenderedTestTypesTest load it after the engine). Its document:
// config.encoding.tone = {field} names the column of tone names; a null row is no mark; any other name throws.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, r2 = R.scales.r2;
  var TONES = { positive: "--chart-positive", negative: "--chart-negative", total: "--chart-total", good: "--chart-good", warn: "--chart-warn", bad: "--chart-bad" };

  function model(doc, columns) {
    var tone = doc.config.encoding.tone, T = D.column(columns, tone.field), n = D.rowCount(columns), rows = [];
    for (var i = 0; i < n; i++) {
      if (D.isNull(T[i])) continue;
      if (!Object.prototype.hasOwnProperty.call(TONES, T[i])) throw new Error("test_tones: no tone " + T[i]);
      rows.push({ token: TONES[T[i]], row: i });
    }
    return { series: [{ id: "measure", title: tone.field, colour: R.theme.SERIES[0] }], markCount: rows.length, warnings: [], legend: null, rows: rows };
  }

  function marks(m, hand, h) {
    var plot = hand.plot, out = [];
    if (hand.visible.measure !== true || m.rows.length === 0) return out;
    var n = m.rows.length, cols = Math.max(1, Math.ceil(Math.sqrt((n * plot.w) / plot.h))), lines = Math.ceil(n / cols);
    var cw = plot.w / cols, ch = plot.h / lines, gap = Math.min(cw, ch) / 10;
    for (var k = 0; k < n; k++) {
      var c = k % cols, l = (k - c) / cols, token = m.rows[k].token, w = cw - 2 * gap, hh = ch - 2 * gap;
      out.push(P.rect({ role: "mark", x: plot.x + c * cw + gap, y: plot.y + l * ch + gap, w: w, h: hh, fill: token, color: h.theme.css(token), rx: r2(Math.min(w, hh) / 5), series: "measure", datum: m.rows[k].row }));
    }
    return out;
  }

  R.type("test_tones", { channels: [{ key: "tone", required: true, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "none" });
})(JsRendered);
