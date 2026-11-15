// 12-cartesian.js : the cartesian frame (record §4.6; look contract §2 grid, axis; §4.2 legend): from a model's
// layout part it places the legend, the axis titles, the value axis and the band axis, and returns the plot and its
// scales for the template's marks with the primitives drawn under and over them. Vertical: bands along x, values up.
// Horizontal: bands down y (the first category at the top, record §4.5), values along x.
//
// A model's layout part: {vertical, bands: [{text, under}], bandTitle, valueTitle, bandGrid, valueGrid, format,
// domain, extent(visible) → [lo, hi], endLabels: null | [texts of the value labels]}; bandGrid/valueGrid are the
// author's axis.grid (undefined = the look's default); endLabels makes the value axis keep a label's room at a data end.
(function (R) {
  "use strict";
  var P = R.prim, S = R.scales, C = R.chrome, A = R.axes, look = R.tables.look, r2 = S.r2;

  function widestOf(h, texts, size) {
    var m = 0;
    for (var i = 0; i < texts.length; i++) m = Math.max(m, h.width(texts[i], size));
    return m;
  }

  function on(author, field) {
    return author === undefined ? look(field) === true : author === true;
  }

  /** Places the legend; returns the box left for the axes {left, right, top, bottom} and pushes its primitives. */
  function placeLegend(h, W, H, box, lg, out) {
    if (lg === null || lg.place === null) return box;
    var gap = h.em("legendGap"), place = lg.place, side = place === "left" || place === "right";
    var lay = C.legendLayout(h, lg.series, place, side ? box.right - box.left : box.right - box.left, box.bottom - box.top);
    lg.drawn = lay.drawn;
    if (lay.items.length === 0 && lay.more === null) return box;
    var b = { left: box.left, right: box.right, top: box.top, bottom: box.bottom };
    if (place === "top") {
      C.legendPrims(h, lay, box.left, box.top, lg.hiddenOf, lg.colourOf, out);
      b.top += lay.h + gap;
    } else if (place === "bottom" || place === "compact") {
      C.legendPrims(h, lay, box.left, box.bottom - lay.h, lg.hiddenOf, lg.colourOf, out);
      b.bottom -= lay.h + gap;
    } else if (place === "left") {
      C.legendPrims(h, lay, box.left, box.top, lg.hiddenOf, lg.colourOf, out);
      b.left += lay.w + gap;
    } else {
      C.legendPrims(h, lay, box.right - lay.w, box.top, lg.hiddenOf, lg.colourOf, out);
      b.right -= lay.w + gap;
    }
    return b;
  }

  function frameAndZero(h, plot, value, vertical, over) {
    if (look("frame") === true) {
      over.push(P.rect({ role: "axis", x: plot.x, y: plot.y, w: plot.w, h: plot.h, stroke: "--chart-axis", strokeColor: h.theme.css("--chart-axis"), strokeWidth: h.theme.n("--chart-stroke") }));
    }
    if (look("zeroLine") === true && value.lo < 0 && value.hi > 0) {
      var z = value.scale(0);
      over.push(vertical ? h.rule("axis", "--chart-axis", plot.x, z, plot.x + plot.w, z) : h.rule("axis", "--chart-axis", z, plot.y, z, plot.y + plot.h));
    }
  }

  function vertical(h, W, H, lvl, b, L, visible, g) {
    var s = h.text, tg = h.em("tickGap"), hh = h.boxH(s) / 2, full = lvl === "full";
    var titleCol = full && L.valueTitle !== null ? h.boxH(s) + h.em("axisTitleGap") : 0;
    var bandTitleH = full && L.bandTitle !== null ? h.em("axisTitleGap") + h.boxH(s) : 0;
    var n = L.bands.length, plotTop = b.top + hh, labels = [];
    for (var i = 0; i < n; i++) labels.push({ text: L.bands[i].text, under: L.bands[i].under, x: 0 });
    var reserve = A.rowReserve(h, labels, false), extra = 0, rotate = "allow", ext = L.extent(visible);
    var ax, plot, scale, row, step;
    for (var pass = 0; pass < 2; pass++) {
      var plotBottom = b.bottom - bandTitleH - reserve - extra;
      var plotH = Math.max(1, plotBottom - plotTop);
      ax = A.valueAxis(lvl, ext, L.domain, plotH, L.format, L.endLabels === null ? 0 : h.boxH(h.small) + look("valueLabelGap") * h.small);
      var plotX = b.left + titleCol + widestOf(h, ax.texts, s) + tg;
      plot = { x: r2(plotX), y: r2(plotTop), w: r2(Math.max(1, b.right - plotX)), h: r2(plotH) };
      scale = S.linear(ax.lo, ax.hi, plot.y + plot.h, plot.y);
      step = plot.w / Math.max(1, n);
      for (var j = 0; j < n; j++) labels[j].x = plot.x + step * (j + 0.5);
      var ly = scale(ax.ticks[0]), lw = h.width(ax.texts[0], s);
      var corner = { x0: plot.x - tg - lw, y0: ly - hh, x1: plot.x - tg, y1: ly + hh };
      row = A.labelRow(h, { labels: labels, bounds: { x0: b.left, x1: b.right }, plot: plot, corner: corner, cornerIsFirst: false, rotate: rotate, room: extra });
      if (pass > 0) break;
      if (row.rotated) {
        reserve = A.rowReserve(h, labels, true) + row.need;
        rotate = "force";
      } else if (row.corner !== "clear") {
        extra = row.need;
        rotate = "never";
      } else break;
    }
    var value = { lo: ax.lo, hi: ax.hi, ticks: ax.ticks, scale: scale };
    if (on(L.valueGrid, "gridValue")) for (var t = 0; t < ax.ticks.length; t++) g.grid.push(h.rule("grid", "--chart-grid", plot.x, scale(ax.ticks[t]), plot.x + plot.w, scale(ax.ticks[t])));
    if (on(L.bandGrid, "gridCategory")) for (var d = 0; d < row.drawn.length; d++) g.grid.push(h.rule("grid", "--chart-grid", labels[row.drawn[d]].x, plot.y, labels[row.drawn[d]].x, plot.y + plot.h));
    for (var k = 0; k < ax.ticks.length; k++) {
      g.ticks.push(h.label({ role: "tick", x: plot.x - tg, y: h.middle(scale(ax.ticks[k]), s), text: ax.texts[k], anchor: "end", size: s, ink: "--chart-muted" }));
    }
    for (var r = 0; r < row.texts.length; r++) g.ticks.push(row.texts[r]);
    if (titleCol > 0) {
      var vt = h.fit(L.valueTitle, s, 400, plot.h);
      if (vt !== "") g.titles.push(h.label({ role: "axis", x: b.left + h.ascent(s), y: plot.y + plot.h / 2, text: L.valueTitle, shown: vt, anchor: "middle", size: s, ink: "--chart-muted", rotate: -90 }));
    }
    if (bandTitleH > 0) {
      var bt = h.fit(L.bandTitle, s, 400, plot.w);
      if (bt !== "") g.titles.push(h.label({ role: "axis", x: plot.x + plot.w / 2, y: plot.y + plot.h + row.height + h.em("axisTitleGap") + h.ascent(s), text: L.bandTitle, shown: bt, anchor: "middle", size: s, ink: "--chart-muted" }));
    }
    return { plot: plot, value: value, band: { start: plot.x, step: step, n: n }, corner: row.corner, rotated: row.rotated };
  }

  function horizontal(h, W, H, lvl, b, L, visible, g) {
    var s = h.text, tg = h.em("tickGap"), hh = h.boxH(s) / 2, full = lvl === "full";
    var titleCol = full && L.bandTitle !== null ? h.boxH(s) + h.em("axisTitleGap") : 0;
    var valueTitleH = full && L.valueTitle !== null ? h.em("axisTitleGap") + h.boxH(s) : 0;
    var n = L.bands.length, names = [];
    for (var i = 0; i < n; i++) names.push(L.bands[i].under !== null && L.bands[i].under !== undefined ? L.bands[i].text + " " + L.bands[i].under : L.bands[i].text);
    var nameW = Math.min(widestOf(h, names, s), look("bandLabelMaxEm") * s, (b.right - b.left) / 2);
    var plotX = b.left + titleCol + nameW + tg, ext = L.extent(visible), extra = 0, rightPad = 0;
    var ax, plot, scale, row, step, drawn, nameTexts;
    for (var pass = 0; pass < 2; pass++) {
      var plotBottom = b.bottom - valueTitleH - (tg + h.boxH(s)) - extra;
      var room = plotBottom - b.top, twoN = 2 * Math.max(1, n);
      var topPad = Math.max(0, (twoN * hh - room) / (twoN - 1));
      var plotH = Math.max(1, room - topPad);
      for (var it = 0; it < 3; it++) {
        var plotW0 = Math.max(1, b.right - rightPad - plotX);
        ax = A.valueAxis(lvl, ext, L.domain, plotW0, L.format, L.endLabels === null ? 0 : widestOf(h, L.endLabels, h.small) + look("valueLabelGap") * h.small);
        var pad = h.width(ax.texts[ax.texts.length - 1], s) / 2;
        if (pad <= rightPad) break;
        rightPad = pad;
      }
      plot = { x: r2(plotX), y: r2(b.top + topPad), w: r2(Math.max(1, b.right - rightPad - plotX)), h: r2(plotH) };
      scale = S.linear(ax.lo, ax.hi, plot.x, plot.x + plot.w);
      step = plot.h / Math.max(1, n);
      var k = 1;
      while (k * step < look("lineHeight") * s && k < n) k++;
      drawn = [];
      nameTexts = [];
      var last = null;
      for (var c = 0; c < n; c += k) {
        var shown = h.fit(names[c], s, 400, nameW);
        if (shown === "") continue;
        var cy = plot.y + step * (c + 0.5);
        drawn.push(c);
        nameTexts.push(h.label({ role: "tick", x: plot.x - tg, y: h.middle(cy, s), text: names[c], shown: shown, anchor: "end", size: s, ink: "--chart-ink-2" }));
        last = { x0: plot.x - tg - h.width(shown, s), y0: cy - hh, x1: plot.x - tg, y1: cy + hh };
      }
      var ticks = [];
      for (var t = 0; t < ax.ticks.length; t++) ticks.push({ text: ax.texts[t], under: null, x: scale(ax.ticks[t]) });
      row = A.labelRow(h, { labels: ticks, bounds: { x0: b.left, x1: b.right }, plot: plot, corner: last, cornerIsFirst: true, rotate: "never", room: extra });
      if (pass > 0 || row.corner === "clear") break;
      extra = row.need;
    }
    var value = { lo: ax.lo, hi: ax.hi, ticks: ax.ticks, scale: scale };
    if (on(L.valueGrid, "gridValue")) for (var q = 0; q < row.drawn.length; q++) {
      var gx = scale(ax.ticks[row.drawn[q]]);
      g.grid.push(h.rule("grid", "--chart-grid", gx, plot.y, gx, plot.y + plot.h));
    }
    if (on(L.bandGrid, "gridCategory")) for (var d = 0; d < drawn.length; d++) {
      var gy = plot.y + step * (drawn[d] + 0.5);
      g.grid.push(h.rule("grid", "--chart-grid", plot.x, gy, plot.x + plot.w, gy));
    }
    for (var u = 0; u < nameTexts.length; u++) g.ticks.push(nameTexts[u]);
    for (var r = 0; r < row.texts.length; r++) g.ticks.push(row.texts[r]);
    if (titleCol > 0) {
      var bt = h.fit(L.bandTitle, s, 400, plot.h);
      if (bt !== "") g.titles.push(h.label({ role: "axis", x: b.left + h.ascent(s), y: plot.y + plot.h / 2, text: L.bandTitle, shown: bt, anchor: "middle", size: s, ink: "--chart-muted", rotate: -90 }));
    }
    if (valueTitleH > 0) {
      var vt = h.fit(L.valueTitle, s, 400, plot.w);
      if (vt !== "") g.titles.push(h.label({ role: "axis", x: plot.x + plot.w / 2, y: plot.y + plot.h + row.height + h.em("axisTitleGap") + h.ascent(s), text: L.valueTitle, shown: vt, anchor: "middle", size: s, ink: "--chart-muted" }));
    }
    return { plot: plot, value: value, band: { start: plot.y, step: step, n: n }, corner: row.corner, rotated: false };
  }

  /** The minimal level (record §4.6, contract §4.5): marks only, the plot the whole frame inside its edge. */
  function minimal(h, W, H, e, L, visible) {
    var plot = { x: r2(e), y: r2(e), w: r2(Math.max(1, W - 2 * e)), h: r2(Math.max(1, H - 2 * e)) };
    var n = L.bands.length, len = L.vertical ? plot.h : plot.w;
    var ax = A.valueAxis("minimal", L.extent(visible), L.domain, len, L.format);
    var scale = L.vertical ? S.linear(ax.lo, ax.hi, plot.y + plot.h, plot.y) : S.linear(ax.lo, ax.hi, plot.x, plot.x + plot.w);
    var step = (L.vertical ? plot.w : plot.h) / Math.max(1, n);
    return { plot: plot, value: { lo: ax.lo, hi: ax.hi, ticks: ax.ticks, scale: scale }, band: { start: L.vertical ? plot.x : plot.y, step: step, n: n }, under: [], over: [], axes: [], corner: "clear", rotated: false };
  }

  /**
   * The cartesian frame. lg = null or {place, series, hiddenOf(id), colourOf(id)}; after the call lg.drawn holds the
   * ids whose entries were drawn. Returns {plot, value: {lo, hi, ticks, scale}, band: {start, step, n}, under, axes,
   * over, corner, rotated}: under (plot background, grid) goes before the marks; axes (tick labels, axis titles, legend)
   * and over (frame, zero line) after them.
   */
  function cartesian(h, W, H, lvl, top, L, visible, lg) {
    var e = h.em("edge"), legendOut = [];
    if (lvl === "minimal") return minimal(h, W, H, e, L, visible);
    var box = placeLegend(h, W, H, { left: e, right: W - e, top: top, bottom: H - e }, lg, legendOut);
    var g = { grid: [], ticks: [], titles: [] };
    var geo = L.vertical ? vertical(h, W, H, lvl, box, L, visible, g) : horizontal(h, W, H, lvl, box, L, visible, g);
    var plot = geo.plot, under = [];
    under.push(P.rect({ role: "grid", x: plot.x, y: plot.y, w: plot.w, h: plot.h, fill: "--chart-plot", color: h.theme.css("--chart-plot") }));
    for (var i = 0; i < g.grid.length; i++) under.push(g.grid[i]);
    var over = [];
    frameAndZero(h, plot, geo.value, L.vertical, over);
    geo.under = under;
    geo.over = over;
    geo.axes = g.ticks.concat(g.titles, legendOut);
    return geo;
  }

  R.cartesian = { cartesian: cartesian };
})(JsRendered);
