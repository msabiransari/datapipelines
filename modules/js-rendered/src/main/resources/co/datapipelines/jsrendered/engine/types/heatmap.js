// Heatmap (§4.5, brief 1070): two category axes and one shade per occupied pair. The shared frame owns all chrome.
(function (R) {
  "use strict";
  var D = R.data, T = R.text, P = R.prim, r2 = R.scales.r2, look = R.tables.look;
  var CHANNELS = [
    { key: "x", required: true, node: { node: "shape", name: "band" } },
    { key: "y", required: true, node: { node: "shape", name: "band" } },
    { key: "color", required: true, node: { node: "shape", name: "shade" } },
  ];
  var STEP = 0.01;

  function title(enc) {
    return enc.title === undefined ? enc.field : enc.title;
  }

  function noTotal() {
    return null;
  }

  function category(raw, key, first, enc) {
    return { raw: raw, key: key, first: first, full: enc.type === "temporal" ? T.temporalFull(raw, enc.format) : String(raw) };
  }

  function axisTexts(categories, enc) {
    return categories.map(function (c) {
      return enc.type === "temporal" ? T.temporalAxis(c.raw, enc.format) : { text: c.full, under: null };
    });
  }

  /** Group only observed pairs: the work and storage follow rows, never the product of the category domains. */
  function model(doc, columns, types) {
    var e = doc.config.encoding, x = e.x, y = e.y, shade = e.color;
    var X = D.column(columns, x.field), Y = D.column(columns, y.field), V = D.column(columns, shade.field);
    var xs = new Map(), ys = new Map(), groups = new Map(), warnings = [], dropped = false;
    types = types || {};
    var n = D.rowCount(columns);
    for (var i = 0; i < n; i++) {
      if (D.isNull(X[i]) || D.isNull(Y[i])) {
        dropped = true;
        continue;
      }
      var xk = D.keyOf(X[i]), yk = D.keyOf(Y[i]);
      if (!xs.has(xk)) xs.set(xk, category(X[i], xk, xs.size, x));
      if (!ys.has(yk)) ys.set(yk, category(Y[i], yk, ys.size, y));
      var byY = groups.get(xk);
      if (byY === undefined) {
        byY = new Map();
        groups.set(xk, byY);
      }
      var vals = byY.get(yk);
      if (vals === undefined) {
        vals = [];
        byY.set(yk, vals);
      }
      if (!D.isNull(V[i])) vals.push(V[i]);
    }
    if (dropped) warnings.push({ code: "chart.rule.null-category" });
    var cols = D.order(Array.from(xs.values()), x.sort, noTotal, types[x.field], x.type === "temporal");
    var rows = D.order(Array.from(ys.values()), y.sort, noTotal, types[y.field], y.type === "temporal");
    var ci = new Map(), ri = new Map();
    cols.forEach(function (c, at) { ci.set(c.key, at); });
    rows.forEach(function (rw, at) { ri.set(rw.key, at); });
    var cells = [], nonFinite = false;
    groups.forEach(function (byY, xkey) {
      byY.forEach(function (values, ykey) {
        // An all-null group stays in both domains, but is empty even under count.
        if (values.length === 0) return;
        var agg = D.aggregate(values, shade.aggregate, shade.field);
        if (agg.num === null) return;
        if (!isFinite(agg.num)) {
          nonFinite = true;
          return;
        }
        var c = ci.get(xkey), r = ri.get(ykey);
        cells.push({ c: c, r: r, datum: r * cols.length + c, agg: agg });
      });
    });
    cells.sort(function (a, b) { return a.r - b.r || a.c - b.c; });
    if (nonFinite) warnings.push({ code: "chart.rule.non-finite" });
    var scale = R.colour.of(shade, cells.map(function (c) { return c.agg; }));
    return {
      series: [], legend: null, markCount: cells.length, warnings: warnings,
      shade: { scale: scale, legend: shade.legend, title: shade.field },
      cols: cols, rows: rows, cells: cells, field: shade.field,
      labelsOn: (doc.config.mark.labels === undefined ? look("heatmapLabelsDefault") : doc.config.mark.labels) === "values",
      layout: {
        vertical: true, bands: axisTexts(cols, x), rows: axisTexts(rows, y),
        bandTitle: title(x), valueTitle: title(y),
        extent: function () { throw new Error("heatmap: the rows frame must not ask for a value extent"); },
      },
    };
  }

  /** Round a side, flooring it to one rounding step; move it only if that floor takes it past the plot. */
  function side(at, length, edge) {
    var raw = r2(length), kept = Math.max(STEP, raw), start = r2(at);
    if (kept > raw && start + kept > edge) start = r2(edge - kept);
    return { at: start, size: kept };
  }

  function marks(m, hand, h) {
    var theme = h.theme, gap = theme.n("--chart-gap"), share = look("cellGapMaxShare");
    var radius = theme.n("--chart-cell-radius"), small = h.small, pad = look("valueLabelGap") * small;
    var prims = [], lookup = [], right = hand.plot.x + hand.plot.w, bottom = hand.plot.y + hand.plot.h;
    var gx = Math.min(gap, hand.band.step * share) / 2, gy = Math.min(gap, hand.rows.step * share) / 2;
    for (var i = 0; i < m.cells.length; i++) {
      var c = m.cells[i], sx = hand.band.start + c.c * hand.band.step, sy = hand.rows.start + c.r * hand.rows.step;
      var dx = side(sx + gx, hand.band.step - 2 * gx, right), dy = side(sy + gy, hand.rows.step - 2 * gy, bottom);
      var bx = side(sx, hand.band.step, right), by = side(sy, hand.rows.step, bottom);
      var fill = R.colour.token(m.shade.scale, c.agg.num), css = theme.css(fill);
      var text = T.value(c.agg.exact !== null ? c.agg.exact : c.agg.num);
      var cell = P.rect({ role: "mark", x: dx.at, y: dy.at, w: dx.size, h: dy.size,
        rx: r2(Math.min(radius, dx.size / 2, dy.size / 2)), fill: fill, color: css, series: null, datum: c.datum });
      lookup.push({ series: null, datum: c.datum, row: c.r, key: true, emphasis: "ring",
        shape: { kind: "box", x: bx.at, y: by.at, w: bx.size, h: by.size },
        card: { heading: m.rows[c.r].full + ", " + m.cols[c.c].full,
          rows: [{ name: m.field, value: text, colour: fill, color: css }], keep: 0 },
      });
      if (m.labelsOn && hand.level !== "minimal" && h.width(text, small) <= dx.size - 2 * pad + 1e-9 && h.boxH(small) <= dy.size + 1e-9) {
        var ink = R.theme.onFill(theme.colour(fill), theme.colour("--chart-on-fill-light"), theme.colour("--chart-on-fill-dark"));
        var label = h.label({ role: "guide", x: r2(dx.at + dx.size / 2), y: h.middle(dy.at + dy.size / 2, small),
          text: text, anchor: "middle", size: small, ink: ink });
        // One semantic mark: shared interaction fades the cell and its fitted value together (#1139).
        P.group(prims, { role: "mark" }, [cell, label]);
      } else prims.push(cell);
    }
    // The final occupied cell allocation (owner 2026-11-24, #1117): each axis independently, after the gap and before
    // the rounding floor, groups = 1 on each.
    if (m.cells.length > 0) hand.density = [
      { axis: "x", categories: hand.band.n, groups: 1, width: hand.band.step - 2 * gx },
      { axis: "y", categories: hand.rows.n, groups: 1, width: hand.rows.step - 2 * gy },
    ];
    return { primitives: prims, lookup: lookup, count: lookup.length };
  }

  R.type("heatmap", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
