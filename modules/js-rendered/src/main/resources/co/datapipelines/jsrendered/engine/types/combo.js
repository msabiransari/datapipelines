// types/combo.js: two to four independently aggregated layers over one category band (brief 1069 D1-D11).
// Axes, legend and chrome belong to the cartesian frame; layers retain their authored order in the index and card.
(function (R) {
  "use strict";
  var P = R.prim, L = R.lines, T = R.text, S = R.scales, look = R.tables.look, cap = R.tables.cap;
  var CHANNELS = [
    { key: "x", required: true, node: { node: "shape", name: "band" } },
    { key: "layers", required: true, node: { node: "list", min: 2, max: 4, of: { node: "object", members: { y: { node: "shape", name: "value" } }, layer: "mark" } } },
    { key: "tooltip", required: false, node: { node: "list", min: 0, max: 6, of: { node: "shape", name: "detail" } } },
  ];
  var KEYS = { bar: "legendKeyBar", area: "legendKeyArea", line: "legendKeyLine", point: "legendKeyPoint" };
  var ORDER = { bar: 0, area: 1, line: 2, point: 3 };

  function side(layer) { return layer.axis === "right" ? "right" : "left"; }

  /** Read the axis's scale from all its layers, including a hidden declaring layer. */
  function scaleOf(layers, axis) {
    for (var i = 0; i < layers.length; i++) if (side(layers[i]) === axis && layers[i].y.scale) return layers[i].y.scale;
    return {};
  }

  /** Empty layers keep their legend entry but do not extend an axis; an entirely hidden axis keeps its range. */
  function extent(group, log, visible) {
    var all = {}, shown = false, lo = null, hi = null;
    for (var i = 0; i < group.length; i++) {
      all[group[i].m.series[0].id] = true;
      if (visible[group[i].m.series[0].id] === true && group[i].cells > 0) shown = true;
    }
    var use = shown ? visible : all;
    for (var j = 0; j < group.length; j++) {
      var sub = group[j];
      if (use[sub.m.series[0].id] !== true || sub.cells === 0) continue;
      var range = sub.m.layout.extent(use);
      if (range[0] !== null && (lo === null || range[0] < lo)) lo = range[0];
      if (range[1] !== null && (hi === null || range[1] > hi)) hi = range[1];
    }
    return lo === null ? (log ? [null, null] : [0, 0]) : [lo, hi];
  }

  function axisTitle(group) {
    for (var i = 0; i < group.length; i++) if (group[i].y.title !== undefined) return group[i].m.layout.valueTitle;
    return group.length === 1 ? group[0].m.layout.valueTitle : null;
  }

  function axisFormat(group) {
    for (var i = 0; i < group.length; i++) if (group[i].y.format !== undefined) return group[i].y.format;
    return null;
  }

  function model(doc, columns, types) {
    var e = doc.config.encoding, layers = e.layers, limit = cap("combo.layers");
    if (layers.length > limit) return { rule: { code: "chart.too_many_series", details: { series: layers.length, limit: limit } } };
    var scales = { left: scaleOf(layers, "left"), right: scaleOf(layers, "right") };
    var temporal = e.x.type === "temporal", x = Object.assign({}, e.x), subs = [], warnings = [], warned = {}, count = 0;
    // Temporal categories remain evenly spaced bands, using the line family's categorical aggregation.
    if (temporal) { x.type = "nominal"; delete x.format; }
    for (var i = 0; i < layers.length; i++) {
      var layer = layers[i], axis = side(layer), area = layer.mark === "area", y = Object.assign({}, layer.y);
      var scale = Object.assign({}, scales[axis]);
      if (layer.mark === "bar" || area) scale.zero = true;
      if (Object.keys(scale).length > 0) y.scale = scale; else delete y.scale;
      var config = { mark: { type: area ? "area" : "line", points: layer.mark === "line" ? "markers" : "end" }, encoding: { x: x, y: y } };
      if (e.tooltip) config.encoding.tooltip = e.tooltip;
      var m = L.model({ config: config }, columns, types, { area: area });
      if (m.rule) return m;
      if (temporal) {
        m.xFull = function (xe) { return T.temporalFull(xe.raw, e.x.format); };
        m.layout.bands = m.xs.map(function (xe) { return T.temporalAxis(xe.raw, e.x.format); });
      }
      if (m.series.length === 0) { m.series = [{}]; m.cells = [[]]; }
      var series = m.series[0];
      series.id = "layer:" + i;
      series.title = typeof layer.title === "string" ? layer.title : String(layer.y.field || "count");
      series.colour = R.theme.SERIES[i];
      series.legendKey = KEYS[layer.mark];
      var cells = 0;
      for (var j = 0; j < m.cells[0].length; j++) if (m.cells[0][j]) cells++;
      var max = layer.mark === "bar" ? cap("bar.marks") : layer.mark === "point" ? cap("scatter.marks") : Infinity;
      if (cells > max) return { rule: { code: "chart.too_many_marks", details: { marks: cells, limit: max } } };
      count += cells;
      subs.push({ mark: layer.mark, axis: axis, m: m, cells: cells, y: layer.y });
      for (var w = 0; w < m.warnings.length; w++) if (warned[m.warnings[w].code] !== true) {
        warned[m.warnings[w].code] = true;
        warnings.push(m.warnings[w]);
      }
    }
    var left = subs.filter(function (s) { return s.axis === "left"; });
    var right = subs.filter(function (s) { return s.axis === "right"; });
    var layout = {
      vertical: true, bands: subs[0].m.layout.bands, bandTitle: subs[0].m.layout.bandTitle,
      bandGrid: subs[0].m.layout.bandGrid, valueGrid: left.length ? left[0].m.layout.valueGrid : undefined,
      valueTitle: axisTitle(left), format: axisFormat(left), domain: scales.left.domain || null, log: scales.left.type === "log", endLabels: null,
      reach: function (h) {
        return subs.some(function (s) { return s.mark !== "bar"; }) ? h.theme.n("--chart-point") + h.theme.n("--chart-gap") / 2 : 0;
      },
      extent: function (visible) { return extent(left, scales.left.type === "log", visible); },
    };
    if (right.length) layout.second = {
      extent: function (visible) { return extent(right, scales.right.type === "log", visible); },
      domain: scales.right.domain || null, format: axisFormat(right), log: scales.right.type === "log", title: axisTitle(right),
    };
    return { series: subs.map(function (s) { return s.m.series[0]; }), markCount: count, warnings: warnings,
      direct: false, legendKey: "legendKeyBar", subs: subs, layout: layout };
  }

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  /** The bar family's free-end corners, with no datum on the primitive (the index carries every interaction). */
  function bar(series, colour, x, end, foot, width, radius) {
    var top = Math.min(end, foot), height = Math.abs(end - foot), r = Math.min(radius, width * look("radiusMaxShare"), height), d;
    if (!(r > 0) || width <= 0 || height <= 0) return P.rect({ role: "mark", x: x, y: top, w: width, h: height, fill: series.colour, color: colour, series: series.id });
    if (end <= foot) d = ["M", x, foot, "L", x, end + r, "A", r, x + r, end, "L", x + width - r, end, "A", r, x + width, end + r, "L", x + width, foot, "Z"];
    else d = ["M", x + width, foot, "L", x + width, end - r, "A", r, x + width - r, end, "L", x + r, end, "A", r, x, end - r, "L", x, foot, "Z"];
    return P.path({ role: "mark", d: d, fill: series.colour, color: colour, series: series.id });
  }

  function tip(m, series, j, cell) {
    var lines = [m.xFull(m.xs[j]), series.title + ": " + cell.text];
    for (var i = 0; i < m.tips.length; i++) lines.push(L.detailLine(m.tips[i], m.tipCols[i][cell.row], m.types));
    return lines;
  }

  function marks(m, hand, h) {
    var out = [], index = [], count = 0, perLayer = [], band = hand.band, theme = h.theme, gap = theme.n("--chart-gap");
    var order = m.subs.map(function (s, i) { return i; });
    order.sort(function (a, b) { return ORDER[m.subs[a].mark] - ORDER[m.subs[b].mark] || a - b; });
    var bars = m.subs.filter(function (s) { return s.mark === "bar" && hand.visible[s.m.series[0].id] === true; });
    var share = hand.reach && hand.reach.asked > 0 ? hand.reach.kept / hand.reach.asked : 1;
    var dot = theme.n("--chart-point"), ring = gap;
    if (share < 1) { dot = S.r2(dot * share); ring = S.r2(ring * share); }
    for (var oi = 0; oi < order.length; oi++) {
      var i = order[oi], sub = m.subs[i], sm = sub.m, series = sm.series[0];
      if (hand.visible[series.id] !== true) continue;
      var value = sub.axis === "right" ? hand.value2 : hand.value;
      if (sub.mark === "line" || sub.mark === "area") {
        var sh = { plot: hand.plot, band: band, value: value, visible: {}, level: hand.level, reach: hand.reach };
        sh.visible[series.id] = true;
        var result = L.marks(sm, sh, h);
        for (var p = 0; p < result.primitives.length; p++) out.push(result.primitives[p]);
        perLayer[i] = result.index[0];
        count += result.count;
        continue;
      }
      var ix = { series: series.id, at: [], datum: [], tip: [] }, kids = [], colour = theme.css(series.colour);
      var group = band.step * look("barShare"), width = Math.max(0, (group - gap * (bars.length - 1)) / Math.max(1, bars.length));
      var slot = bars.indexOf(sub), foot = value.scale(clamp(0, value.lo, value.hi));
      if (sub.mark === "bar") ix.spans = [];
      for (var j = 0; j < sm.xs.length; j++) {
        var cell = sm.cells[0][j];
        if (!cell) continue;
        var at = band.start + band.step * (j + 0.5), end = value.scale(clamp(cell.num, value.lo, value.hi));
        if (sub.mark === "bar") {
          var x = band.start + band.step * j + (band.step - group) / 2 + slot * (width + gap);
          kids.push(bar(series, colour, x, end, foot, width, theme.n("--chart-bar-radius")));
          ix.spans.push({ x: S.r2(x), w: S.r2(width), foot: S.r2(foot), end: S.r2(end) });
        } else kids.push(P.circle({ role: "mark", cx: at, cy: end, r: dot, fill: series.colour, color: colour,
          stroke: "--chart-canvas", strokeColor: theme.css("--chart-canvas"), strokeWidth: ring, series: series.id }));
        ix.at.push(S.r2(at)); ix.datum.push(j); ix.tip.push(tip(sm, series, j, cell));
      }
      P.group(out, { role: "mark", series: series.id, label: series.title }, kids);
      perLayer[i] = ix;
      count += ix.at.length;
    }
    for (var k = 0; k < perLayer.length; k++) if (perLayer[k]) index.push(perLayer[k]);
    return { primitives: out, index: index, count: count };
  }

  R.type("combo", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
