// 12-cartesian.js : the cartesian frame (record §4.6; look contract §2 grid, axis; §4.2 legend): from a model's
// layout part it places the legend (through the shared placement of 18-frames.js), the axis titles, the value axis and the band axis, and returns the plot and its
// scales for the template's marks with the primitives drawn under and over them. Vertical: bands along x, values up.
// Horizontal: bands down y (the first category at the top, record §4.5), values along x.
//
// A model's layout part: {vertical, bands: [{text, under}], bandTitle, valueTitle, bandGrid, valueGrid, format,
// domain, extent(visible) → [lo, hi], endLabels: null | [texts of the value labels]}; bandGrid/valueGrid are the
// author's axis.grid (undefined = the look's default); endLabels makes the value axis keep a label's room at a data end.
// The line family adds (brief 989 D4, D5, D6; all optional, absent on bar but `log`): `log` (a log value axis: then
// extent gives [smallest positive, largest] or [null, null]); `x` (a continuous x axis in place of the bands: {kind:
// "time" | "linear" | "log", lo, hi (seconds for time; for log the smallest positive and the largest), gran (time),
// format, domain (linear, log), zero (linear)}); `right` (px kept right of the plot for the end labels); `reach` (px a
// mark reaches beyond its point: the end dot and its ring), kept inside the frame; without axes, at most a quarter
// of the frame's smaller side is kept and the template draws the dot and ring smaller by kept / asked (L10, #1128).
// The other axis forms (brief 1051 D2 to D6; record §9 G3), each asked for by an OPTIONAL member; a layout part without
// it takes the code path it took before: `rows` ([{text, under}], two category axes: a second list of categories down
// y in place of the value axis; vertical only, never beside x, log, second, endLabels or a right room); `second`
// ({extent(visible) → [lo, hi], domain, format, log, title}, a second value axis at the right on the left axis's grid;
// vertical only, never beside rows or a right room); `x.edges` (a list of 2 or more increasing numbers on a linear x:
// the axis runs from the first edge to the last and its ticks are the edges; never beside x.domain or x.zero). A
// combination a form does not allow throws, naming the member: a template's mistake, never a document's.
(function (R) {
  "use strict";
  var P = R.prim, S = R.scales, C = R.chrome, A = R.axes, T = R.text, look = R.tables.look, r2 = S.r2;

  function widestOf(h, texts, size) {
    var m = 0;
    for (var i = 0; i < texts.length; i++) m = Math.max(m, h.width(texts[i], size));
    return m;
  }

  /** A member a layout part has: present and not null. */
  function has(v) {
    return v !== undefined && v !== null;
  }

  /** Throws, naming the member, for a combination of the forms a layout part may not ask for (brief 1051 D2). */
  function checkForms(L) {
    var right = typeof L.right === "number" && L.right > 0;
    if (has(L.rows)) {
      if (!Array.isArray(L.rows)) throw new Error("cartesian: rows is a list of {text, under}");
      if (L.vertical !== true) throw new Error("cartesian: rows asks for vertical: true");
      if (has(L.x)) throw new Error("cartesian: rows cannot stand beside x");
      if (L.log === true) throw new Error("cartesian: rows cannot stand beside log");
      if (has(L.second)) throw new Error("cartesian: rows cannot stand beside second");
      if (has(L.endLabels)) throw new Error("cartesian: rows cannot stand beside endLabels");
      if (right) throw new Error("cartesian: rows cannot stand beside right");
    }
    if (has(L.second)) {
      if (L.vertical !== true) throw new Error("cartesian: second asks for vertical: true");
      if (right) throw new Error("cartesian: second cannot stand beside right");
      if (typeof L.second.extent !== "function") throw new Error("cartesian: second.extent is a function");
    }
    if (has(L.pad)) {
      if (typeof L.pad !== "function") throw new Error("cartesian: pad is a function of the helper");
      if (!has(L.x) || L.vertical !== true) throw new Error("cartesian: pad is for a layout part with x and vertical: true");
      if (has(L.second)) throw new Error("cartesian: pad cannot stand beside second");
    }
    if (has(L.x) && has(L.x.edges)) {
      var ed = L.x.edges, ok = Array.isArray(ed) && ed.length >= 2;
      for (var i = 0; ok && i < ed.length; i++) ok = typeof ed[i] === "number" && ed[i] > -Infinity && ed[i] < Infinity && (i === 0 || ed[i] > ed[i - 1]);
      if (!ok) throw new Error("cartesian: x.edges is a list of 2 or more finite numbers, strictly increasing");
      if (L.x.kind !== "linear") throw new Error("cartesian: x.edges asks for x.kind linear");
      if (has(L.x.domain)) throw new Error("cartesian: x.edges cannot stand beside x.domain");
      if (L.x.zero === true) throw new Error("cartesian: x.edges cannot stand beside x.zero");
    }
  }

  function on(author, field) {
    return author === undefined ? look(field) === true : author === true;
  }

  /** The least height the value axis needs: two tick labels with the least room between neighbouring labels. */
  function valueNeed(h) {
    return h.boxH(h.text) + h.em("labelGap");
  }

  /** The px the corner rule may move a label row down: the lowest tick label's lower half and clearance below tickGap. */
  function cornerMove(h) {
    return Math.max(0, h.boxH(h.text) / 2 + h.em("cornerClear") - h.em("tickGap"));
  }

  function bandLabels(L) {
    var labels = [];
    for (var i = 0; i < L.bands.length; i++) labels.push({ text: L.bands[i].text, under: L.bands[i].under, x: 0 });
    return labels;
  }

  /** The value axis of a layout part over `length` px: a log axis (G4) or the linear one. */
  function valueAxisOf(lvl, L, ext, length, room) {
    return L.log === true ? A.logAxis(lvl, ext, L.domain, length, L.format) : A.valueAxis(lvl, ext, L.domain, length, L.format, room, L.whole);
  }

  /**
   * A continuous x axis (brief 989 D5) over `length` px: {lo, hi, ticks: [{at, text, under}], log}. Time: the data's
   * extent and the time ticks (13-time.js), labelled by G9. Linear: the nice range of the extent (with zero when the
   * author asked for it) or the author's domain, written as the value axis writes ticks. Log: as a log value axis.
   * Linear with `edges` (brief 1051 D6): exactly the first edge to the last, the edges the only ticks, their texts
   * written together as the value axis writes ticks.
   */
  function positionAxis(lvl, X, length) {
    var out = [], i;
    if (X.kind === "linear" && has(X.edges)) {
      var et = T.ticks(X.edges, X.format);
      for (i = 0; i < X.edges.length; i++) out.push({ at: X.edges[i], text: et[i], under: null });
      return { lo: X.edges[0], hi: X.edges[X.edges.length - 1], ticks: out, log: false };
    }
    if (X.kind === "time") {
      var t = R.time.ticks(X.lo, X.hi, length, X.gran, lvl);
      for (i = 0; i < t.length; i++) out.push({ at: t[i].at, text: t[i].text, under: t[i].under });
      return { lo: X.lo, hi: X.hi, ticks: out, log: false };
    }
    if (X.kind === "log") {
      var g = A.logAxis(lvl, [X.lo, X.hi], X.domain, length, X.format);
      for (i = 0; i < g.ticks.length; i++) out.push({ at: g.ticks[i], text: g.texts[i], under: null });
      return { lo: g.lo, hi: g.hi, ticks: out, log: true };
    }
    var lo = X.zero === true ? Math.min(0, X.lo) : X.lo, hi = X.zero === true ? Math.max(0, X.hi) : X.hi;
    var v = A.valueAxis(lvl, [lo, hi], X.domain, length, X.format, 0);
    for (i = 0; i < v.ticks.length; i++) out.push({ at: v.ticks[i], text: v.texts[i], under: null });
    return { lo: v.lo, hi: v.hi, ticks: out, log: false };
  }

  /** The x labels of a layout part at a plot: the bands' centres, or a position axis's ticks at their places. */
  function xLabels(lvl, L, plot, px) {
    if (!L.x) {
      var labels = bandLabels(L), step = plot.w / Math.max(1, L.bands.length);
      for (var j = 0; j < labels.length; j++) labels[j].x = plot.x + step * (j + 0.5);
      return { labels: labels, axis: null, step: step };
    }
    var ax = positionAxis(lvl, L.x, plot.w), sc = A.scaleOf(ax, plot.x + (px || 0), plot.x + plot.w - (px || 0)), out = [];
    for (var i = 0; i < ax.ticks.length; i++) out.push({ text: ax.ticks[i].text, under: ax.ticks[i].under, x: sc(ax.ticks[i].at) });
    return { labels: out, axis: { lo: ax.lo, hi: ax.hi, log: ax.log, scale: sc }, step: plot.w / Math.max(1, L.bands.length) };
  }

  /**
   * How far the marks reach past their points (a line's dot and its ring, half a line's width): the model's
   * `reach(h)`, a function of the theme's lengths, or 0 (a bar stays inside its plot).
   */
  function reachOf(L, h) {
    var r = typeof L.reach === "function" ? L.reach(h) : 0;
    return typeof r === "number" && r > 0 ? r : 0;
  }

  /**
   * What a frame with no axes keeps of a reach (look contract §4.5, L10; #1128): {asked, kept}, kept never more than a
   * quarter of the frame's smaller side. The frame insets its plot by kept and hands both numbers to the template, which
   * draws its dot and the dot's ring smaller by kept / asked: the dot is never left out and never leaves the frame.
   */
  function keepOf(asked, W, H) {
    return { asked: asked, kept: Math.min(asked, Math.min(W, H) / 4) };
  }

  /**
   * The minimal level's per-side inset (the owner's ruling of 2026-11-24, #1131): the frame's edge or the kept reach,
   * whichever is larger, capped on each side at a quarter of that side — min(max(edge, kept), W/4) horizontally and
   * min(max(edge, kept), H/4) vertically — before a separately reserved right-side room for a last value. Each cap is
   * the side it insets, so a frame wider than it is tall keeps more room left and right than top and bottom, and the
   * plot never loses more than half of either dimension.
   */
  function insetsOf(e, kept, W, H) {
    var asked = Math.max(e, kept);
    return { x: Math.min(asked, W / 4), y: Math.min(asked, H / 4) };
  }

  /** The room a layout part asks for inside the plot's edges (pad(h), px), or null when it has no `pad`; a bad value throws. */
  function padOf(L, h) {
    if (!has(L.pad)) return null;
    var p = L.pad(h);
    if (typeof p !== "number" || !(p >= 0 && p < Infinity)) throw new Error("cartesian: pad is a finite number of px, 0 or more");
    return p;
  }

  /** What is kept of it at a plot: {x, y}, never more than a quarter of a side; null when the part has no `pad`. */
  function keptOf(L, h, plot) {
    var p = padOf(L, h);
    return p === null ? null : { x: Math.min(p, plot.w / 4), y: Math.min(p, plot.h / 4) };
  }

  /**
   * The least height the axes need under a legend at the top or bottom (#985 R1), from the model's layout part alone
   * (never the visibility, so the legend's layout can decide it): vertical, the top tick label's upper half, the value
   * axis's two labels, the label row unrotated, the corner's move and the band title; horizontal, one band name, the
   * value label row, the corner's move and the value title. With rows (brief 1051 D4): one name's height where the
   * value axis's two labels are.
   */
  function axesNeed(h, lvl, L) {
    var s = h.text, full = lvl === "full", titleH = h.em("axisTitleGap") + h.boxH(s);
    if (L.vertical && has(L.rows)) return h.boxH(s) + A.rowReserve(h, bandLabels(L), false) + cornerMove(h) + (full && L.bandTitle !== null ? titleH : 0);
    if (L.vertical) return h.boxH(s) / 2 + valueNeed(h) + A.rowReserve(h, L.x ? positionGuess(lvl, L) : bandLabels(L), false) + cornerMove(h) + (full && L.bandTitle !== null ? titleH : 0);
    return h.boxH(s) + h.em("tickGap") + h.boxH(s) + cornerMove(h) + (full && L.valueTitle !== null ? titleH : 0);
  }

  /** A position axis's labels for a reserve made before the plot is known: its ticks over a typical length. */
  function positionGuess(lvl, L) {
    return xLabels(lvl, L, { x: 0, y: 0, w: look("levelFull")[0], h: 1 }).labels;
  }

  /** The height a legend at `place` may take in a box `room` px high: a column all of it, rows what the axes leave. */
  function legendRoom(h, lvl, L, place, room) {
    if (place === "left" || place === "right") return room;
    return room - axesNeed(h, lvl, L) - h.em("legendGap");
  }

  function frameAndZero(h, plot, value, vertical, over) {
    if (look("frame") === true) {
      over.push(P.rect({ role: "axis", x: plot.x, y: plot.y, w: plot.w, h: plot.h, stroke: "--chart-axis", strokeColor: h.theme.css("--chart-axis"), strokeWidth: h.theme.n("--chart-stroke") }));
    }
    if (look("zeroLine") === true && value !== null && value.log !== true && value.lo < 0 && value.hi > 0) {
      var z = value.scale(0);
      over.push(vertical ? h.rule("axis", "--chart-axis", plot.x, z, plot.x + plot.w, z) : h.rule("axis", "--chart-axis", z, plot.y, z, plot.y + plot.h));
    }
  }

  /** The vertical layout. rightCol (brief 1051 D5, the second axis's column): px kept right of the plot in L.right's place. */
  function vertical(h, W, H, lvl, b, L, visible, g, rightCol) {
    var s = h.text, tg = h.em("tickGap"), hh = h.boxH(s) / 2, full = lvl === "full";
    var titleCol = full && L.valueTitle !== null ? h.boxH(s) + h.em("axisTitleGap") : 0;
    var bandTitleH = full && L.bandTitle !== null ? h.em("axisTitleGap") + h.boxH(s) : 0;
    var reach = reachOf(L, h), right = Math.max(rightCol !== undefined ? rightCol : typeof L.right === "number" ? L.right : 0, reach);
    var n = L.bands.length, plotTop = b.top + Math.max(hh, reach), labels = L.x ? positionGuess(lvl, L) : bandLabels(L);
    var reserve = A.rowReserve(h, labels, false), extra = 0, rotate = L.x ? "never" : "allow", ext = L.extent(visible), xs = null;
    // The plot keeps the value axis's need (#985 R1): a rotated label is cut shorter, or the corner cuts the first
    // label, before the plot is made lower than two tick labels.
    var avail = b.bottom - bandTitleH - plotTop, need = valueNeed(h), cs = A.angle(), maxLen = null;
    var ax, plot, scale, row, step, kept;
    for (var pass = 0; pass < 2; pass++) {
      var plotBottom = b.bottom - bandTitleH - reserve - extra;
      var plotH = Math.max(1, plotBottom - plotTop);
      ax = valueAxisOf(lvl, L, ext, plotH, L.endLabels === null ? 0 : h.boxH(h.small) + look("valueLabelGap") * h.small);
      var plotX = Math.max(b.left + titleCol + widestOf(h, ax.texts, s) + tg, reach);
      plot = { x: r2(plotX), y: r2(plotTop), w: r2(Math.max(1, b.right - right - plotX)), h: r2(plotH) };
      kept = keptOf(L, h, plot);
      scale = kept === null ? A.scaleOf(ax, plot.y + plot.h, plot.y) : A.scaleOf(ax, plot.y + plot.h - kept.y, plot.y + kept.y);
      xs = xLabels(lvl, L, plot, kept === null ? 0 : kept.x);
      labels = xs.labels;
      step = xs.step;
      var ly = scale(ax.ticks[0]), lw = h.width(ax.texts[0], s);
      var corner = { x0: plot.x - tg - lw, y0: ly - hh, x1: plot.x - tg, y1: ly + hh };
      row = A.labelRow(h, { labels: labels, bounds: { x0: b.left, x1: b.right }, plot: plot, corner: corner, cornerIsFirst: false, rotate: rotate, room: extra, maxLen: maxLen, whole: !!L.x });
      if (pass > 0) break;
      if (row.rotated) {
        var len = (avail - need - row.need - tg - cs[0] * h.boxH(s)) / cs[1];
        if (len > 0) {
          maxLen = len;
          reserve = A.rowReserve(h, labels, true, maxLen) + row.need;
          rotate = "force";
        } else rotate = "never";
      } else if (row.corner !== "clear") {
        extra = Math.min(row.need, Math.max(0, avail - reserve - need));
        rotate = "never";
      } else break;
    }
    var value = { lo: ax.lo, hi: ax.hi, ticks: ax.ticks, scale: scale, log: ax.log === true };
    if (on(L.valueGrid, "gridValue")) for (var t = 0; t < ax.ticks.length; t++) g.grid.push(h.rule("grid", "--chart-grid", plot.x, scale(ax.ticks[t]), plot.x + plot.w, scale(ax.ticks[t])));
    if (on(L.bandGrid, "gridCategory")) for (var d = 0; d < row.drawn.length; d++) g.grid.push(h.rule("grid", "--chart-grid", labels[row.drawn[d]].x, plot.y, labels[row.drawn[d]].x, plot.y + plot.h));
    // Value tick labels are thinned (record §4.6) from the lowest, which is always drawn (the corner rule): a label
    // whose box would come closer to the last drawn one than half the least room between labels is left out, its grid
    // line kept. The allocation above gives two labels the whole room, so coordinates rounded to 0.01 px never thin one.
    var lastY = Infinity, thin = h.boxH(s) + h.em("labelGap") / 2;
    for (var k = 0; k < ax.ticks.length; k++) {
      var ty = scale(ax.ticks[k]);
      if (k > 0 && lastY - ty < thin) continue;
      lastY = ty;
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
    return { plot: plot, value: value, band: { start: plot.x, step: step, n: n }, x: xs.axis, pad: kept, corner: row.corner, rotated: row.rotated, labelTop: plot.y + plot.h + tg, boxTop: b.top, rowTexts: row.texts };
  }

  /** The names of a list of categories [{text, under}] as a column writes them: the text, then its second line after a space. */
  function namesOf(list) {
    var names = [];
    for (var i = 0; i < list.length; i++) names.push(list[i].under !== null && list[i].under !== undefined ? list[i].text + " " + list[i].under : list[i].text);
    return names;
  }

  /** A column of names' width: the widest name, at most bandLabelMaxEm em and half the box. */
  function nameWidth(h, names, b) {
    return Math.min(widestOf(h, names, h.text), look("bandLabelMaxEm") * h.text, (b.right - b.left) / 2);
  }

  /**
   * The column of category names left of a plot (a horizontal bar's bands; brief 1051 D4's rows): name c centred on
   * plot.y + step × (c + 0.5), the first at the top; every k-th from the first so two drawn names are a text line apart;
   * each cut to nameW, anchored at its end at plot.x − tickGap, in --chart-ink-2. Returns {drawn: [index], texts, last:
   * the lowest drawn name's box or null}.
   */
  function nameColumn(h, names, nameW, plot, step) {
    var s = h.text, tg = h.em("tickGap"), hh = h.boxH(s) / 2, n = names.length;
    var k = 1;
    while (k * step < look("lineHeight") * s && k < n) k++;
    var drawn = [], texts = [], last = null;
    for (var c = 0; c < n; c += k) {
      var shown = h.fit(names[c], s, 400, nameW);
      if (shown === "") continue;
      var cy = plot.y + step * (c + 0.5);
      drawn.push(c);
      texts.push(h.label({ role: "tick", x: plot.x - tg, y: h.middle(cy, s), text: names[c], shown: shown, anchor: "end", size: s, ink: "--chart-ink-2" }));
      last = { x0: plot.x - tg - h.width(shown, s), y0: cy - hh, x1: plot.x - tg, y1: cy + hh };
    }
    return { drawn: drawn, texts: texts, last: last };
  }

  function horizontal(h, W, H, lvl, b, L, visible, g) {
    var s = h.text, tg = h.em("tickGap"), hh = h.boxH(s) / 2, full = lvl === "full";
    var titleCol = full && L.bandTitle !== null ? h.boxH(s) + h.em("axisTitleGap") : 0;
    var valueTitleH = full && L.valueTitle !== null ? h.em("axisTitleGap") + h.boxH(s) : 0;
    var n = L.bands.length, names = namesOf(L.bands);
    var nameW = nameWidth(h, names, b);
    var plotX = b.left + titleCol + nameW + tg, ext = L.extent(visible), extra = 0, rightPad = 0;
    var ax, plot, scale, row, step, drawn, nameTexts;
    for (var pass = 0; pass < 2; pass++) {
      var plotBottom = b.bottom - valueTitleH - (tg + h.boxH(s)) - extra;
      var room = plotBottom - b.top, twoN = 2 * Math.max(1, n);
      var topPad = Math.max(0, (twoN * hh - room) / (twoN - 1));
      var plotH = Math.max(1, room - topPad);
      for (var it = 0; it < 3; it++) {
        var plotW0 = Math.max(1, b.right - rightPad - plotX);
        ax = valueAxisOf(lvl, L, ext, plotW0, L.endLabels === null ? 0 : widestOf(h, L.endLabels, h.small) + look("valueLabelGap") * h.small);
        var pad = h.width(ax.texts[ax.texts.length - 1], s) / 2;
        if (pad <= rightPad) break;
        rightPad = pad;
      }
      plot = { x: r2(plotX), y: r2(b.top + topPad), w: r2(Math.max(1, b.right - rightPad - plotX)), h: r2(plotH) };
      scale = A.scaleOf(ax, plot.x, plot.x + plot.w);
      step = plot.h / Math.max(1, n);
      var column = nameColumn(h, names, nameW, plot, step), last = column.last;
      drawn = column.drawn;
      nameTexts = column.texts;
      var ticks = [];
      for (var t = 0; t < ax.ticks.length; t++) ticks.push({ text: ax.texts[t], under: null, x: scale(ax.ticks[t]) });
      // The loop above reserved half the last tick label's width at the right of the plot. The plot's left edge and its
      // width are each rounded to a hundredth (r2), so the last tick can stand up to 0.01 px short of the reserved edge
      // and the label's room (twice its distance to the box's edge) falls up to 0.02 px short: labelRow then cuts the
      // label ("15" drawn as "…") or leaves it out (#1105). The box's edge gets 0.015 px back: 0.01 and half a hundredth.
      row = A.labelRow(h, { labels: ticks, bounds: { x0: b.left, x1: b.right + 0.015 }, plot: plot, corner: last, cornerIsFirst: true, rotate: "never", room: extra });
      if (pass > 0 || row.corner === "clear") break;
      extra = row.need;
    }
    var value = { lo: ax.lo, hi: ax.hi, ticks: ax.ticks, scale: scale, log: ax.log === true };
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

  /**
   * Two category axes (brief 1051 D4): the bands along x as a vertical layout's, the rows down y in a column of names left
   * of the plot, placed, cut and thinned as a horizontal bar's names (nameColumn), the first at the top. The x label row
   * keeps clear of the lowest drawn name's box (the corner). No value axis, no grid line, no zero line; `extent` is
   * never called. bandTitle names x, valueTitle the rows, each where the vertical layout writes it.
   */
  function rowsForm(h, W, H, lvl, b, L, visible, g) {
    var s = h.text, tg = h.em("tickGap"), hh = h.boxH(s) / 2, full = lvl === "full";
    var titleCol = full && L.valueTitle !== null ? h.boxH(s) + h.em("axisTitleGap") : 0;
    var bandTitleH = full && L.bandTitle !== null ? h.em("axisTitleGap") + h.boxH(s) : 0;
    var reach = reachOf(L, h), n = L.bands.length, nr = L.rows.length, names = namesOf(L.rows);
    var nameW = nameWidth(h, names, b), plotX = b.left + titleCol + nameW + tg;
    var labels = bandLabels(L), reserve = A.rowReserve(h, labels, false), extra = 0, rotate = "allow";
    // As the vertical layout: a rotated label is cut shorter, or the corner cuts the first label, before the plot is
    // made lower than one row name.
    var avail = b.bottom - bandTitleH - b.top, need = h.boxH(s), cs = A.angle(), maxLen = null;
    var plot, column, row, xs, rowStep;
    for (var pass = 0; pass < 2; pass++) {
      var room = b.bottom - bandTitleH - reserve - extra - b.top, twoN = 2 * Math.max(1, nr);
      // The first name's box stays below the box's top (the horizontal layout's top pad), and a mark's reach inside.
      var topPad = Math.max(reach, (twoN * hh - room) / (twoN - 1));
      plot = { x: r2(plotX), y: r2(b.top + topPad), w: r2(Math.max(1, b.right - reach - plotX)), h: r2(Math.max(1, room - topPad)) };
      rowStep = plot.h / Math.max(1, nr);
      column = nameColumn(h, names, nameW, plot, rowStep);
      xs = xLabels(lvl, L, plot);
      labels = xs.labels;
      row = A.labelRow(h, { labels: labels, bounds: { x0: b.left, x1: b.right }, plot: plot, corner: column.last, cornerIsFirst: false, rotate: rotate, room: extra, maxLen: maxLen, whole: false });
      if (pass > 0) break;
      if (row.rotated) {
        var len = (avail - need - row.need - tg - cs[0] * h.boxH(s)) / cs[1];
        if (len > 0) {
          maxLen = len;
          reserve = A.rowReserve(h, labels, true, maxLen) + row.need;
          rotate = "force";
        } else rotate = "never";
      } else if (row.corner !== "clear") {
        extra = Math.min(row.need, Math.max(0, avail - reserve - need));
        rotate = "never";
      } else break;
    }
    for (var u = 0; u < column.texts.length; u++) g.ticks.push(column.texts[u]);
    for (var r = 0; r < row.texts.length; r++) g.ticks.push(row.texts[r]);
    if (titleCol > 0) {
      var vt = h.fit(L.valueTitle, s, 400, plot.h);
      if (vt !== "") g.titles.push(h.label({ role: "axis", x: b.left + h.ascent(s), y: plot.y + plot.h / 2, text: L.valueTitle, shown: vt, anchor: "middle", size: s, ink: "--chart-muted", rotate: -90 }));
    }
    if (bandTitleH > 0) {
      var bt = h.fit(L.bandTitle, s, 400, plot.w);
      if (bt !== "") g.titles.push(h.label({ role: "axis", x: plot.x + plot.w / 2, y: plot.y + plot.h + row.height + h.em("axisTitleGap") + h.ascent(s), text: L.bandTitle, shown: bt, anchor: "middle", size: s, ink: "--chart-muted" }));
    }
    return { plot: plot, value: null, band: { start: plot.x, step: xs.step, n: n }, rows: { start: plot.y, step: rowStep, n: nr }, x: null, corner: row.corner, rotated: row.rotated, labelTop: plot.y + plot.h + tg, boxTop: b.top, rowTexts: row.texts };
  }

  /** The second axis's {lo, hi, ticks, texts, scale, log} on a plot whose left axis is `value` (brief 1051 D5). */
  function secondOf(lvl, L, visible, value, plot) {
    var ax2 = A.secondAxis(lvl, L.second, L.second.extent(visible), { lo: value.lo, hi: value.hi, ticks: value.ticks, log: value.log }, plot.h);
    return { lo: ax2.lo, hi: ax2.hi, ticks: ax2.ticks, texts: ax2.texts, scale: A.scaleOf(ax2, plot.y + plot.h, plot.y), log: ax2.log === true, rule: ax2.rule };
  }

  /** [x0, y0, x1, y1] of a text primitive's box, turned as the text is (an x label may be rotated). */
  function boxOf(h, t) {
    var w = h.width(t.shown !== null ? t.shown : t.text, t.size), a = h.ascent(t.size), d = h.descent(t.size);
    var x0 = t.anchor === "end" ? -w : t.anchor === "middle" ? -w / 2 : 0, pts = [[x0, -a], [x0 + w, -a], [x0, d], [x0 + w, d]];
    var c = 1, sn = 0;
    if (t.rotate !== 0) {
      var cs = t.rotate === -90 ? [0, 1] : A.angle();
      c = cs[0];
      sn = cs[1];
    }
    var b = [Infinity, Infinity, -Infinity, -Infinity];
    for (var i = 0; i < pts.length; i++) {
      // A turn of −θ (counter-clockwise on the screen): (dx, dy) → (dx cos θ + dy sin θ, −dx sin θ + dy cos θ).
      var px = t.x + pts[i][0] * c + pts[i][1] * sn, py = t.y - pts[i][0] * sn + pts[i][1] * c;
      b = [Math.min(b[0], px), Math.min(b[1], py), Math.max(b[2], px), Math.max(b[3], py)];
    }
    return b;
  }

  /**
   * The second value axis at the right (brief 1051 D5; look contract §4.7, combo). The left axis does not know it, only
   * its column: the plot, the left axis and the x labels are the vertical layout's with that column in L.right's place.
   * The column is found in two passes: the first reserves the widest text of the right axis's own rule, the second what
   * the final texts need (their widest plus tickGap, plus at full the title's column); a final text still wider than
   * the column is left out. One right tick per left tick, at its position; labels right of the plot at tickGap,
   * anchored at their start, muted, thinned by the left axis's rule and left out where one would meet an x label; no
   * grid line and no zero line of its own. The title, at full, outside them, turned as the left axis's title is.
   */
  function withSecond(h, W, H, lvl, b, L, visible, g) {
    var s = h.text, tg = h.em("tickGap"), S2 = L.second;
    var title = lvl === "full" && has(S2.title) ? S2.title : null, titleCol = title !== null ? h.boxH(s) + h.em("axisTitleGap") : 0;
    var own = S2.log === true ? A.logAxis(lvl, S2.extent(visible), S2.domain, Math.max(1, b.bottom - b.top), S2.format) : A.valueAxis(lvl, S2.extent(visible), S2.domain, Math.max(1, b.bottom - b.top), S2.format, 0);
    var first = vertical(h, W, H, lvl, b, L, visible, { grid: [], ticks: [], titles: [] }, widestOf(h, own.texts, s) + tg + titleCol);
    var column = widestOf(h, secondOf(lvl, L, visible, first.value, first.plot).texts, s) + tg + titleCol;
    var geo = vertical(h, W, H, lvl, b, L, visible, g, column), plot = geo.plot, value = geo.value;
    var v2 = secondOf(lvl, L, visible, value, plot), room = column - tg - titleCol, x = plot.x + plot.w + tg;
    var xBoxes = [];
    for (var q = 0; q < geo.rowTexts.length; q++) xBoxes.push(boxOf(h, geo.rowTexts[q]));
    var lastY = Infinity, thin = h.boxH(s) + h.em("labelGap") / 2, hh = h.boxH(s) / 2;
    for (var k = 0; k < value.ticks.length; k++) {
      var ty = value.scale(value.ticks[k]);
      if (k > 0 && lastY - ty < thin) continue;
      lastY = ty;
      var w = h.width(v2.texts[k], s), box = [x, ty - hh, x + w, ty + hh], meets = false;
      for (var m = 0; m < xBoxes.length && !meets; m++) meets = box[0] < xBoxes[m][2] && xBoxes[m][0] < box[2] && box[1] < xBoxes[m][3] && xBoxes[m][1] < box[3];
      if (w > room || meets) continue;
      g.ticks.push(h.label({ role: "tick", x: x, y: h.middle(ty, s), text: v2.texts[k], anchor: "start", size: s, ink: "--chart-muted" }));
    }
    if (title !== null) {
      var tt = h.fit(title, s, 400, plot.h);
      if (tt !== "") g.titles.push(h.label({ role: "axis", x: b.right - h.descent(s), y: plot.y + plot.h / 2, text: title, shown: tt, anchor: "middle", size: s, ink: "--chart-muted", rotate: -90 }));
    }
    geo.value2 = v2;
    geo.column = column;
    return geo;
  }

  /**
   * The minimal level (record §4.6, contract §4.5): marks only, the plot the whole frame inside the per-side inset (the
   * edge or the kept reach, whichever is larger, capped at a quarter of each side — #1131), less `right` px at the right
   * when given (the bare frame's last value, brief 1051 D7; absent for every other caller). The scales are computed: with
   * rows no value axis (extent is not called), with second the right axis on the left's ticks.
   */
  function minimal(h, W, H, e, L, visible, right) {
    checkForms(L);
    var keep = keepOf(reachOf(L, h), W, H);
    var ins = insetsOf(e, keep.kept, W, H), room = typeof right === "number" && right > 0 ? right : 0;
    var plot = { x: r2(ins.x), y: r2(ins.y), w: r2(Math.max(1, W - 2 * ins.x - room)), h: r2(Math.max(1, H - 2 * ins.y)) };
    var n = L.bands.length, len = L.vertical ? plot.h : plot.w;
    var step = (L.vertical ? plot.w : plot.h) / Math.max(1, n);
    var out = { plot: plot, value: null, band: { start: L.vertical ? plot.x : plot.y, step: step, n: n }, x: null, under: [], over: [], axes: [], corner: "clear", rotated: false, labelTop: plot.y + plot.h, boxTop: plot.y };
    out.reach = keep;
    if (has(L.rows)) {
      out.rows = { start: plot.y, step: plot.h / Math.max(1, L.rows.length), n: L.rows.length };
      return out;
    }
    var ax = valueAxisOf("minimal", L, L.extent(visible), len, 0), kept = keptOf(L, h, plot);
    var scale = kept !== null ? A.scaleOf(ax, plot.y + plot.h - kept.y, plot.y + kept.y) : L.vertical ? A.scaleOf(ax, plot.y + plot.h, plot.y) : A.scaleOf(ax, plot.x, plot.x + plot.w);
    if (kept !== null) out.pad = kept;
    out.value = { lo: ax.lo, hi: ax.hi, ticks: ax.ticks, scale: scale, log: ax.log === true };
    out.x = L.x ? xLabels("minimal", L, plot, kept === null ? 0 : kept.x).axis : null;
    if (has(L.second)) out.value2 = secondOf("minimal", L, visible, out.value, plot);
    return out;
  }

  /**
   * The cartesian frame's layout. lg = null or the legend object of render's legendOf (placed by the shared placement,
   * R.frames.legend); after the call lg.drawn holds the ids whose entries were drawn. Returns {plot, value: {lo, hi,
   * ticks, scale, log}, band: {start, step, n}, x (a continuous x axis or absent), under, axes, over, corner, rotated,
   * labelTop, boxTop (vertical and minimal)}: under (plot background, grid) goes before the marks; axes (tick labels, axis titles, legend) and
   * over (frame, zero line) after them; labelTop and boxTop bound the end labels (the frame's after).
   */
  function cartesian(h, W, H, lvl, top, L, visible, lg) {
    var e = h.em("edge"), legendOut = [];
    if (lvl === "minimal") return minimal(h, W, H, e, L, visible);
    checkForms(L);
    var box = R.frames.legend(h, { left: e, right: W - e, top: top, bottom: H - e }, lg, function (place, boxH) {
      return legendRoom(h, lvl, L, place, boxH);
    }, legendOut);
    var g = { grid: [], ticks: [], titles: [] };
    var geo = !L.vertical ? horizontal(h, W, H, lvl, box, L, visible, g) : has(L.rows) ? rowsForm(h, W, H, lvl, box, L, visible, g) : has(L.second) ? withSecond(h, W, H, lvl, box, L, visible, g) : vertical(h, W, H, lvl, box, L, visible, g);
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

  /**
   * The end labels of a draw: for each visible series, its name when the names are on the lines and its last value
   * when the type writes it (`labels: last`, m.lasts = [{id, value: text | null}]); none at the minimal level.
   */
  function endLabelsOf(m, series, visible, place, lvl) {
    var out = [];
    if (lvl === "minimal" || !Array.isArray(m.lasts)) return out;
    for (var i = 0; i < m.lasts.length; i++) {
      var l = m.lasts[i];
      if (visible[l.id] !== true) continue;
      var title = null;
      for (var s = 0; s < series.length; s++) if (series[s].id === l.id) title = series[s].title;
      var name = place === "direct" ? title : null;
      if (name === null && l.value === null) continue;
      out.push({ id: l.id, name: name, value: l.value });
    }
    return out;
  }

  /**
   * What a template receives from a cartesian layout: {plot, value, band, x}, with `rows` (brief 1051 D4) and `value2`
   * ({lo, hi, ticks, scale, log}, D5) when the layout part asked for them.
   */
  function handOf(geo) {
    var hand = { plot: geo.plot, value: geo.value, band: geo.band, x: geo.x };
    if (has(geo.rows)) hand.rows = geo.rows;
    if (has(geo.pad)) hand.pad = geo.pad;
    if (has(geo.reach)) hand.reach = geo.reach;
    if (has(geo.value2)) hand.value2 = { lo: geo.value2.lo, hi: geo.value2.hi, ticks: geo.value2.ticks, scale: geo.value2.scale, log: geo.value2.log };
    return hand;
  }

  /**
   * The cartesian frame as the frame registry takes it (18-frames.js adds it as `cartesian`; brief 1049 D2). Its room is
   * legendRoom over the model's layout part. Its lay keeps the end labels' room right of the plot (brief 989 D6, D7:
   * the names on the lines and the values of `labels: last`) before the frame is laid out, and hands the template
   * {plot, value, band, x}; after(drawn) writes those end labels once the template returned its lines' ends.
   */
  var frame = {
    titles: true,
    legend: true,
    room: function (h, lvl, m, place, boxH) {
      return legendRoom(h, lvl, m.layout, place, boxH);
    },
    lay: function (h, W, H, lvl, top, m, series, visible, lg) {
      var ends = endLabelsOf(m, series, visible, lg === null ? null : lg.place, lvl);
      if (ends.length > 0) m.layout.right = C.endRoom(h, ends);
      var geo = cartesian(h, W, H, lvl, top, m.layout, visible, lg);
      return {
        plot: geo.plot,
        hand: handOf(geo),
        under: geo.under,
        axes: geo.axes,
        over: geo.over,
        after: function (drawn) {
          if (ends.length === 0 || Array.isArray(drawn)) return [];
          return C.endLabels(h, drawn.ends, ends, geo.plot.x + geo.plot.w + h.em("directGap"), geo.boxTop, geo.labelTop).texts;
        },
      };
    },
  };

  R.cartesian = { cartesian: cartesian, positionAxis: positionAxis, frame: frame, minimal: minimal, reach: reachOf, keep: keepOf, insets: insetsOf, hand: handOf };
})(JsRendered);
