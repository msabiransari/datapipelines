// types/test_grid.js : a type for TESTS ONLY (brief 1051 D4, D8), on the frame `cartesian`: two category axes, the layout
// part's `rows` member. One rectangle per filled cell of band × rows, coloured by its series. Its `extent` throws: the
// form never asks for it. In no load order, no jar and no page. Its document: config.encoding.x = {field, title?} (the
// columns, in order of first appearance), y = {field, title?} (the rows, the first seen at the top), color = {field,
// legend?} (absent: one series). A row is a cell when its x, y and series are not null; a cell filled twice is one mark,
// of the series that filled it first.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data;

  function model(doc, columns) {
    var e = doc.config.encoding, color = e.color || null;
    var X = D.column(columns, e.x.field), Y = D.column(columns, e.y.field), C = color ? D.column(columns, color.field) : null;
    var n = D.rowCount(columns), series = [], byKey = new Map(), bands = [], rowsList = [], bi = new Map(), ri = new Map(), cells = [], seen = new Set();
    for (var i = 0; i < n; i++) {
      var v = C ? C[i] : "rows";
      if (D.isNull(v) || D.isNull(X[i]) || D.isNull(Y[i])) continue;
      var xs = String(X[i]), ys = String(Y[i]);
      if (!bi.has(xs)) {
        bi.set(xs, bands.length);
        bands.push({ text: xs, under: null });
      }
      if (!ri.has(ys)) {
        ri.set(ys, rowsList.length);
        rowsList.push({ text: ys, under: null });
      }
      var key = typeof v + ":" + String(v);
      if (!byKey.has(key)) {
        byKey.set(key, series.length);
        series.push({ id: C ? key : "measure", title: String(v), colour: R.theme.SERIES[series.length] || null });
      }
      var cell = bi.get(xs) + "/" + ri.get(ys);
      if (seen.has(cell)) continue;
      seen.add(cell);
      cells.push({ series: series[byKey.get(key)].id, row: i, c: bi.get(xs), r: ri.get(ys) });
    }
    return {
      series: series,
      markCount: cells.length,
      warnings: [],
      legend: color ? color.legend : undefined,
      cells: cells,
      layout: {
        vertical: true,
        bands: bands,
        rows: rowsList,
        bandTitle: e.x.title || null,
        valueTitle: e.y.title || null,
        bandGrid: undefined,
        valueGrid: undefined,
        format: null,
        domain: null,
        endLabels: null,
        extent: function () {
          throw new Error("test_grid: the rows form called extent");
        },
      },
    };
  }

  function marks(m, hand, h) {
    var out = [], colourOf = new Map(), gap = h.theme.n("--chart-gap") / 2;
    for (var s = 0; s < m.series.length; s++) colourOf.set(m.series[s].id, m.series[s].colour);
    for (var i = 0; i < m.cells.length; i++) {
      var c = m.cells[i];
      if (hand.visible[c.series] !== true) continue;
      var token = colourOf.get(c.series), w = Math.max(0, hand.band.step - 2 * gap), hh = Math.max(0, hand.rows.step - 2 * gap);
      out.push(P.rect({ role: "mark", x: hand.band.start + c.c * hand.band.step + gap, y: hand.rows.start + c.r * hand.rows.step + gap, w: w, h: hh, fill: token, color: h.theme.css(token), series: c.series, datum: c.row }));
    }
    return out;
  }

  R.type("test_grid", {
    channels: [
      { key: "x", required: true, node: { shape: "value", types: ["nominal"] } },
      { key: "y", required: true, node: { shape: "value", types: ["nominal"] } },
      { key: "color", required: false, node: { shape: "series", types: ["nominal"] } },
    ],
    model: model,
    marks: marks,
    axes: "cartesian",
  });
})(JsRendered);
