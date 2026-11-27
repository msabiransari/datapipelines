// types/radar.js : record §4.5 radar; brief #1077. One aggregate per (axis, series), one scale from zero.
// The radial frame owns rings, spokes and texts. Missing cells join their neighbours; negatives refuse the chart.
(function (R) {
  "use strict";
  var D = R.data, T = R.text, P = R.prim, look = R.tables.look, r2 = R.scales.r2;
  var MIN_AXES = 3, MAX_AXES = 12, MAX_SERIES = 6;
  var CHANNELS = [
    { key: "theta", required: true, node: { node: "shape", name: "band" } },
    { key: "r", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: false, node: { node: "shape", name: "series" } },
  ];

  function model(doc, columns, types) {
    var e = doc.config.encoding, theta = e.theta, value = e.r, color = e.color || null;
    var B = D.column(columns, theta.field), V = value.field ? D.column(columns, value.field) : null;
    var C = color ? D.column(columns, color.field) : null, temporal = theta.type === "temporal";
    var cats = new Map(), sers = new Map(), groups = new Map(), warnings = [], dropped = false;
    types = types || {};
    for (var i = 0; i < D.rowCount(columns); i++) {
      var b = B[i], s = C ? C[i] : null;
      if (D.isNull(b) || (C && D.isNull(s))) { dropped = true; continue; }
      var bk = D.keyOf(b), sk = C ? D.keyOf(s) : "";
      if (!cats.has(bk)) cats.set(bk, { key: bk, raw: b, first: cats.size, full: temporal ? T.temporalFull(b, theta.format) : String(b) });
      if (!sers.has(sk)) sers.set(sk, { key: sk, raw: s, first: sers.size, full: C ? String(s) : String(value.field || "count") });
      if (!groups.has(bk)) groups.set(bk, new Map());
      if (!groups.get(bk).has(sk)) groups.get(bk).set(sk, []);
      groups.get(bk).get(sk).push(V ? V[i] : 1);
    }
    if (dropped) warnings.push({ code: "chart.rule.null-category" });
    var raw = new Map(), negative = false;
    groups.forEach(function (bySeries, key) {
      var row = new Map();
      bySeries.forEach(function (vals, sk2) {
        var a = D.aggregate(vals, value.aggregate, value.field || "count");
        if (a.num !== null && isFinite(a.num) && a.num < 0) negative = true;
        row.set(sk2, a);
      });
      raw.set(key, row);
    });
    if (negative) return { rule: { code: "chart.rule.negative-part", details: {} } };
    if (sers.size > MAX_SERIES) return { rule: { code: "chart.too_many_series", details: { series: sers.size, limit: MAX_SERIES } } };
    if (cats.size > 0 && (cats.size < MIN_AXES || cats.size > MAX_AXES)) return { rule: { code: "chart.rule.radar-axes", details: { axes: cats.size, min: MIN_AXES, max: MAX_AXES } } };
    function cell(c, ser) {
      var row = raw.get(c.key);
      return (row && row.get(ser.key)) || { num: null, exact: null };
    }
    var categories = D.order(Array.from(cats.values()), theta.sort, function () { return null; }, types[theta.field], temporal);
    var series = D.order(Array.from(sers.values()), color ? color.sort : null, function () { return null; }, color ? types[color.field] : undefined, false);
    var nonFinite = false, markCount = 0;
    var cells = series.map(function (ser, si) {
      ser.id = color ? ser.key : "measure";
      ser.title = ser.full;
      ser.colour = R.theme.SERIES[si];
      return categories.map(function (cat) {
        var a = cell(cat, ser);
        if (a.num === null) return { num: null, text: "" };
        if (!isFinite(a.num)) { nonFinite = true; return { num: null, text: "" }; }
        markCount++;
        return { num: a.num, text: R.lines.withUnit(T.value(a.exact !== null ? a.exact : a.num, value.format), value.unit) };
      });
    });
    if (nonFinite) warnings.push({ code: "chart.rule.non-finite" });
    return {
      categories: categories, series: series, cells: cells, markCount: markCount, warnings: warnings,
      legend: color ? color.legend : undefined,
      fill: doc.config.mark.fill === undefined ? look("radarFillDefault") : doc.config.mark.fill,
      layout: {
        spokes: categories.map(function (c) { return { text: c.full }; }), domain: null, format: value.format,
        reach: function (h) { return h.theme.n("--chart-point") - look("radarDotLess") + look("radarRingShare") * h.theme.n("--chart-gap") / 2; },
        extent: function (visible) {
          var hi = 0;
          series.forEach(function (ser, si) {
            if (visible[ser.id] !== true) return;
            cells[si].forEach(function (c) { if (c.num !== null && c.num > hi) hi = c.num; });
          });
          return [0, hi];
        },
      },
    };
  }

  function marks(m, hand, h) {
    var out = [], lookup = [], theme = h.theme;
    var share = hand.reach.asked === 0 ? 1 : hand.reach.kept / hand.reach.asked;
    var dotR = r2((theme.n("--chart-point") - look("radarDotLess")) * share);
    var ringW = r2(look("radarRingShare") * theme.n("--chart-gap") * share);
    m.series.forEach(function (ser, si) {
      if (hand.visible[ser.id] !== true) return;
      var kids = [], points = [], token = ser.colour, css = theme.css(token);
      m.categories.forEach(function (cat, ci) {
        var cell = m.cells[si][ci];
        if (cell.num === null) return;
        points.push({ ci: ci, p: R.trig.point(hand.centre.x, hand.centre.y, hand.r.scale(cell.num), hand.angles[ci]) });
      });
      if (points.length >= 2) {
        var d = [];
        points.forEach(function (q, i) { d.push(i === 0 ? "M" : "L", q.p.x, q.p.y); });
        if (points.length >= 3) {
          d.push("Z");
          if (m.fill) kids.push(P.path({ role: "mark", d: d, fill: token, color: css, opacity: look("radarFillShare"), series: ser.id }));
        }
        kids.push(P.path({ role: "mark", d: d, stroke: token, strokeColor: css, strokeWidth: theme.n("--chart-line"), round: look("lineRound") === true, series: ser.id }));
      }
      points.forEach(function (q) {
        var cat = m.categories[q.ci], p = q.p, rows = [], keep = 0;
        kids.push(P.circle({ role: "mark", cx: p.x, cy: p.y, r: dotR, fill: token, color: css, stroke: "--chart-canvas", strokeColor: theme.css("--chart-canvas"), strokeWidth: ringW, series: ser.id, datum: cat.key }));
        m.series.forEach(function (other, oi) {
          if (hand.visible[other.id] !== true || m.cells[oi][q.ci].num === null) return;
          if (oi === si) keep = rows.length;
          rows.push({ name: other.title, value: m.cells[oi][q.ci].text, colour: other.colour, color: theme.css(other.colour), bold: oi === si });
        });
        lookup.push({ series: ser.id, datum: cat.key, row: si, shape: { kind: "point", x: p.x, y: p.y, r: dotR }, near: true, emphasis: "ring", card: { heading: cat.full, rows: rows, keep: keep } });
      });
      P.group(out, { role: "mark", series: ser.id, label: ser.title }, kids);
    });
    return { primitives: out, lookup: lookup, count: lookup.length };
  }

  R.type("radar", { channels: CHANNELS, model: model, marks: marks, axes: "radial" });
})(JsRendered);
