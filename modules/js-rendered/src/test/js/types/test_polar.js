// types/test_polar.js : a type for TESTS ONLY (brief 1049 D5, D6), on the frame `polar`: one ring segment per row
// around the frame's centre, row i on its own ring inside the radius, each drawn with R.trig.arc as a stroked path, so
// the arc helper's output crosses both runtimes inside a picture. Its document: config.encoding.color = {field,
// legend?} names the series column, encoding.from and encoding.to = {field} the columns of the segment's two angles
// (degrees, 0 at 12 o'clock, clockwise; from ≤ to ≤ from + 360). In no load order, no jar and no page.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function model(doc, columns) {
    var e = doc.config.encoding, color = e.color || null;
    var C = color ? D.column(columns, color.field) : null, F = D.column(columns, e.from.field), T = D.column(columns, e.to.field);
    var n = D.rowCount(columns), series = [], byKey = new Map(), rows = [];
    for (var i = 0; i < n; i++) {
      var v = C ? C[i] : "rows";
      if (D.isNull(v) || typeof F[i] !== "number" || typeof T[i] !== "number") continue;
      var key = typeof v + ":" + String(v);
      if (!byKey.has(key)) {
        byKey.set(key, series.length);
        series.push({ id: C ? key : "measure", title: String(v), colour: R.theme.SERIES[series.length] || null });
      }
      rows.push({ series: series[byKey.get(key)].id, row: i, from: F[i], to: T[i] });
    }
    return { series: series, markCount: rows.length, warnings: [], legend: color ? color.legend : undefined, rows: rows };
  }

  function marks(m, hand, h) {
    var on = [], out = [];
    for (var i = 0; i < m.rows.length; i++) if (hand.visible[m.rows[i].series] === true) on.push(m.rows[i]);
    if (on.length === 0) return out;
    var band = hand.radius / on.length, width = (band * 3) / 5;
    var colourOf = new Map();
    for (var s = 0; s < m.series.length; s++) colourOf.set(m.series[s].id, m.series[s].colour);
    for (var k = 0; k < on.length; k++) {
      var r = hand.radius - band * (k + 0.5), token = colourOf.get(on[k].series);
      var start = R.trig.point(hand.centre.x, hand.centre.y, r, on[k].from);
      var d = ["M", start.x, start.y].concat(R.trig.arc(hand.centre.x, hand.centre.y, r, on[k].from, on[k].to));
      out.push(P.path({ role: "mark", d: d, stroke: token, strokeColor: h.theme.css(token), strokeWidth: width, series: on[k].series, datum: on[k].row }));
    }
    return out;
  }

  R.type("test_polar", { channels: [{ key: "color", required: false, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "polar" });
})(JsRendered);
