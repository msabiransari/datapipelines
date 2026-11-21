// 18a-bare.js : the frame `bare` (record §4.3, §4.5 sparkline, §9 G3; look contract §4.7 sparkline, its fit rule of the
// last value; brief 1051 D7). It keeps the cartesian layout's scales and draws no title, no legend, no axis, no grid, no
// plot background and no frame line, at every size: the layout levels take nothing from it, and it never reads the
// level for what it draws. It reads the cartesian layout part's members a line needs (bands or x, extent, log, domain,
// reach) and one of its own, `last`: null, or the last value's text as the template wrote it (the short form is the
// template's). The plot is the frame inside max(edge, reach) on four sides, less the last value's room at the right
// where the fit rule writes it; the scales are those the minimal level computes for the same layout part (the cartesian
// file's `minimal`, called, not copied). The template receives the cartesian frame's four fields {plot, value, band, x},
// so a line-like template draws on either frame.
(function (R) {
  "use strict";
  var look = R.tables.look;

  /**
   * The fit rule of the last value (look contract §4.7, sparkline): ONE pure function of its numbers. The value is
   * written when the frame is at least one text line high (H ≥ line) and the plot that is left after the inset on both
   * sides, the gap and the value's width w is at least half of the frame's width; both comparisons include equality.
   */
  function fits(W, H, w, inset, gap, line) {
    return H >= line && W - 2 * inset - gap - w >= W / 2;
  }

  var BARE = {
    titles: false,
    legend: false,
    lay: function (h, W, H, lvl, top, m, series, visible, lg) {
      var L = m.layout, e = h.em("edge");
      if (L.last !== undefined && L.last !== null && typeof L.last !== "string") throw new Error("bare: last is null or a text");
      var last = typeof L.last === "string" ? L.last : null;
      var inset = Math.max(e, R.cartesian.reach(L, h)), gap = h.em("directGap"), line = look("lineHeight") * h.text;
      var w = last === null ? 0 : h.width(last, h.text, h.titleWeight);
      // Decided BEFORE the plot is fixed and the marks are drawn: the plot cannot widen after them.
      var room = last !== null && fits(W, H, w, inset, gap, line) ? gap + w : 0;
      var geo = R.cartesian.minimal(h, W, H, e, L, visible, room), plot = geo.plot;
      return {
        plot: plot,
        hand: R.cartesian.hand(geo),
        under: [],
        axes: [],
        over: [],
        after: function (drawn) {
          if (room === 0) return [];
          // The model's promise: `last` is handed a text only when the picture has a last point.
          if (Array.isArray(drawn) || !Array.isArray(drawn.ends) || drawn.ends.length === 0) throw new Error("bare: the template was handed last and returned no end to write it at");
          var s = h.text, half = h.boxH(s) / 2, cy = Math.min(Math.max(drawn.ends[0].y, half), H - half);
          return [h.label({ role: "guide", x: plot.x + plot.w + gap, y: h.middle(cy, s), text: last, anchor: "start", size: s, weight: h.titleWeight, ink: "--chart-ink" })];
        },
      };
    },
  };

  R.frames.add("bare", BARE);
  R.bare = { fits: fits };
})(JsRendered);
