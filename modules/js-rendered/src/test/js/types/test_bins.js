// types/test_bins.js : a type for TESTS ONLY (brief 1051 D6, D8), on the frame `cartesian`: marks over an interval of a
// continuous axis, the layout part's `x.edges` member. One rectangle per bin that holds a value, from x.scale(edge i) to
// x.scale(edge i + 1), as high as its count. In no load order, no jar and no page. Its document: config.encoding.x =
// {field, edges?: [numbers], bins?: count, format?, title?}: the edges as written, or `bins` intervals of a nice step
// over the values (R.scales.niceStep: the template computes nice edges, the layout never does). A value belongs to the
// bin [edge i, edge i + 1), the last bin also holding its right edge; a value outside the edges is left out.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, S = R.scales;

  function edgesOf(ex, vals) {
    if (Array.isArray(ex.edges)) return ex.edges.slice();
    var lo = Infinity, hi = -Infinity;
    for (var i = 0; i < vals.length; i++) {
      lo = Math.min(lo, vals[i]);
      hi = Math.max(hi, vals[i]);
    }
    if (lo === Infinity) return [0, 1];
    if (!(hi > lo)) hi = lo + 1;
    var step = S.niceStep((hi - lo) / Math.max(1, ex.bins || 10)), first = Math.floor(lo / step), out = [];
    for (var k = first; out.length === 0 || out[out.length - 1] < hi || out.length < 2; k++) out.push(Math.round(k * step * 1e6) / 1e6);
    return out;
  }

  function model(doc, columns) {
    var ex = doc.config.encoding.x, X = D.column(columns, ex.field), n = D.rowCount(columns), vals = [], rowsOf = [];
    for (var i = 0; i < n; i++) {
      if (typeof X[i] === "number" && X[i] === X[i]) {
        vals.push(X[i]);
        rowsOf.push(i);
      }
    }
    var edges = edgesOf(ex, vals), counts = [];
    for (var b = 0; b + 1 < edges.length; b++) counts.push(0);
    for (var v = 0; v < vals.length; v++) {
      for (var j = 0; j < counts.length; j++) {
        if (vals[v] >= edges[j] && (vals[v] < edges[j + 1] || (j === counts.length - 1 && vals[v] === edges[j + 1]))) {
          counts[j]++;
          break;
        }
      }
    }
    var filled = 0, most = 0;
    for (var c = 0; c < counts.length; c++) {
      if (counts[c] > 0) filled++;
      most = Math.max(most, counts[c]);
    }
    return {
      series: [{ id: "measure", title: ex.title || "Count", colour: R.theme.SERIES[0] }],
      markCount: filled,
      warnings: [],
      legend: undefined,
      edges: edges,
      counts: counts,
      layout: {
        vertical: true,
        bands: [],
        x: { kind: "linear", lo: edges[0], hi: edges[edges.length - 1], edges: edges, format: ex.format || null, domain: null, zero: false },
        log: false,
        bandTitle: ex.title || null,
        valueTitle: "Count",
        bandGrid: undefined,
        valueGrid: undefined,
        format: "integer",
        domain: null,
        endLabels: null,
        extent: function () {
          return [0, most];
        },
      },
    };
  }

  function marks(m, hand, h) {
    var out = [], token = m.series[0].colour, base = hand.value.scale(0);
    if (hand.visible.measure !== true) return out;
    for (var i = 0; i < m.counts.length; i++) {
      if (m.counts[i] === 0) continue;
      var x0 = hand.x.scale(m.edges[i]), x1 = hand.x.scale(m.edges[i + 1]), y = hand.value.scale(m.counts[i]);
      out.push(P.rect({ role: "mark", x: x0, y: y, w: x1 - x0, h: base - y, fill: token, color: h.theme.css(token), series: "measure", datum: i }));
    }
    return out;
  }

  R.type("test_bins", {
    channels: [{ key: "x", required: true, node: { shape: "value", types: ["quantitative"] } }],
    model: model,
    marks: marks,
    axes: "cartesian",
  });
})(JsRendered);
