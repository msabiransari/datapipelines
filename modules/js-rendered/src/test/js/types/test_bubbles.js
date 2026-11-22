// types/test_bubbles.js : a type for TESTS ONLY (brief 1066 D5), on the frame `cartesian`: two continuous axes, one circle
// per row at x.scale(x) and value.scale(y), its radius read per row from the document (encoding.size.field, px). The
// layout part hands `pad` = the document's largest radius (encoding.pad overrides it: the fixture "pad-zero"), and marks
// multiplies every radius by min(1, hand.pad.x / pad, hand.pad.y / pad), so a circle never crosses the plot's edge.
// Document: encoding.x = {field, kind: "linear" | "log" | "time", ...}, y = {field, log?}, size = {field}, color?, pad?.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function model(doc, columns) {
    var e = doc.config.encoding, ex = e.x, ey = e.y, color = e.color || null;
    var X = D.column(columns, ex.field), Y = D.column(columns, ey.field), Z = D.column(columns, e.size.field), C = color ? D.column(columns, color.field) : null;
    var n = D.rowCount(columns), kind = ex.kind || "linear", logY = ey.log === true;
    var series = [], byKey = new Map(), rows = [], largest = 0;
    for (var i = 0; i < n; i++) {
      var v = C ? C[i] : "rows", t = kind === "time" ? R.time.parse(X[i]) : null, xv = kind === "time" ? (t === null ? null : t.at) : X[i], yv = Y[i], rv = Z[i];
      if (D.isNull(v) || typeof xv !== "number" || typeof yv !== "number" || typeof rv !== "number" || xv !== xv || yv !== yv || !(rv >= 0)) continue;
      if ((kind === "log" && !(xv > 0)) || (logY && !(yv > 0))) continue;
      var key = typeof v + ":" + String(v);
      if (!byKey.has(key)) {
        byKey.set(key, series.length);
        series.push({ id: C ? key : "measure", title: String(v), colour: R.theme.SERIES[series.length] || null });
      }
      rows.push({ series: series[byKey.get(key)].id, row: i, x: xv, y: yv, r: rv });
      largest = Math.max(largest, rv);
    }
    var xlo = Infinity, xhi = -Infinity;
    for (var q = 0; q < rows.length; q++) {
      xlo = Math.min(xlo, rows[q].x);
      xhi = Math.max(xhi, rows[q].x);
    }
    if (rows.length === 0) {
      xlo = kind === "log" ? 1 : 0;
      xhi = kind === "log" ? 10 : 1;
    }
    var xd = kind === "time" ? R.time.domain(xlo, xhi, "date") : [xlo, xhi];
    var pad = typeof e.pad === "number" ? e.pad : largest;
    var m = { series: series, markCount: rows.length, warnings: [], legend: color ? color.legend : undefined, rows: rows, pad: pad };
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
      pad: function () {
        return pad;
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

  function marks(m, hand, h) {
    var out = [], colourOf = new Map();
    for (var s = 0; s < m.series.length; s++) colourOf.set(m.series[s].id, m.series[s].colour);
    // The kept distance scales every radius, so a circle stays inside the plot on any frame; a part with no pad, or a pad
    // of 0, scales nothing.
    var k = hand.pad && m.pad > 0 ? Math.min(1, hand.pad.x / m.pad, hand.pad.y / m.pad) : 1;
    for (var i = 0; i < m.rows.length; i++) {
      var p = m.rows[i];
      if (hand.visible[p.series] !== true) continue;
      var token = colourOf.get(p.series);
      out.push(P.circle({ role: "mark", cx: hand.x.scale(p.x), cy: hand.value.scale(p.y), r: p.r * k, fill: token, color: h.theme.css(token), series: p.series, datum: p.row }));
    }
    return out;
  }

  R.type("test_bubbles", {
    channels: [
      { key: "x", required: true, node: { shape: "value", types: ["quantitative", "temporal"] } },
      { key: "y", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "size", required: true, node: { shape: "value", types: ["quantitative"] } },
      { key: "color", required: false, node: { shape: "series", types: ["nominal"] } },
    ],
    model: model,
    marks: marks,
    axes: "cartesian",
  });
})(JsRendered);
