// types/bar.js : the bar type (record §4.5 row bar; look contract §2 corners, bars, gaps; §4.3 value labels). A plain
// function template (R17, O-6): `model` prepares the data, `marks` returns mark primitives built with the named-field
// constructors; neither writes an axis, a legend or a title. The model is the product chart core's (`model` of
// datapipelines-dashboard-chart.js, sha256 4b56c48b…), field for field, so the frozen corpus of A.6 compares it:
// categories and series {key, raw, first, full}, cells {num, exact, text, aggregate}, markCount. Adapted: the number
// text follows §4.7 (01-text.js) where the core used Intl, and the model adds what the shared layout reads (`layout`,
// series ids and titles). Lane 977's 10-template-bar-t1.js was the reference for what the marks compute.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, S = R.scales, look = R.tables.look, r2 = S.r2;

  /** Bar's channel descriptor (record §4.2), the same data as ChartType.bar's in Kotlin (R-REG compares them). */
  var BAND_OR_VALUE = { node: "oneOf", of: [{ shape: "band", types: ["nominal", "ordinal", "temporal"] }, { shape: "value", types: ["quantitative"] }] };
  var CHANNELS = [
    { key: "x", required: true, node: BAND_OR_VALUE },
    { key: "y", required: true, node: BAND_OR_VALUE },
    { key: "color", required: false, node: { node: "oneOf", of: [{ shape: "series", types: ["nominal", "ordinal"] }, { shape: "shade", types: ["quantitative"] }] } },
    { key: "tooltip", required: false, node: { node: "list", min: 0, max: 6, of: { node: "shape", name: "detail" } } },
  ];

  function titleOf(enc, dflt) {
    return enc.title === null ? null : enc.title === undefined ? dflt : enc.title;
  }

  function model(doc, columns, types) {
    var e = doc.config.encoding, vertical = e.y.type === "quantitative";
    var band = vertical ? e.x : e.y, value = vertical ? e.y : e.x, color = e.color || null;
    if (color !== null && color.type === "quantitative") throw new Error("bar: colour by value (shade) is not drawn by this engine version");
    types = types && typeof types === "object" ? types : {};
    var warnings = [];
    var B = D.column(columns, band.field), V = value.field ? D.column(columns, value.field) : null, C = color ? D.column(columns, color.field) : null;
    var temporal = band.type === "temporal";
    var cats = new Map(), sers = new Map(), groups = new Map(), droppedCat = 0, droppedSer = 0;
    for (var i = 0; i < B.length; i++) {
      var b = B[i], s = C ? C[i] : null;
      if (D.isNull(b)) {
        droppedCat++;
        continue;
      }
      if (C && D.isNull(s)) {
        droppedSer++;
        continue;
      }
      var bk = D.keyOf(b), sk = C ? D.keyOf(s) : "";
      if (!cats.has(bk)) cats.set(bk, { key: bk, raw: b, first: cats.size, full: temporal ? T.temporalFull(b, band.format) : String(b) });
      if (!sers.has(sk)) sers.set(sk, { key: sk, raw: s, first: sers.size, full: C ? String(s) : String(value.field || "count") });
      if (!groups.has(bk)) groups.set(bk, new Map());
      var bySeries = groups.get(bk);
      if (!bySeries.has(sk)) bySeries.set(sk, []);
      bySeries.get(sk).push(V ? V[i] : 1);
    }
    if (droppedCat || droppedSer) warnings.push({ code: "chart.rule.null-category" });
    var agg = value.aggregate;
    var stack = color ? (value.stack === undefined ? null : value.stack) : undefined;
    var raw = new Map();
    groups.forEach(function (bySeries, key) {
      var m = new Map();
      bySeries.forEach(function (vals, sk2) {
        m.set(sk2, D.aggregate(vals, agg, value.field || "count"));
      });
      raw.set(key, m);
    });
    var cell = function (c, s2) {
      var g = raw.get(c.key);
      return (g && g.get(s2.key)) || { num: null, exact: null };
    };
    var catTotal = function (c) {
      var t = null;
      sers.forEach(function (s2) {
        var v = cell(c, s2).num;
        if (v !== null) t = (t === null ? 0 : t) + v;
      });
      return t;
    };
    var categories = D.order(Array.from(cats.values()), band.sort, catTotal, types[band.field], temporal);
    var series = D.order(Array.from(sers.values()), color ? color.sort : null, function () {
      return null;
    }, color ? types[color.field] : undefined, false);
    var normalize = stack === "normalize";
    var format = normalize ? value.format || "percent" : value.format;
    var normalizers = normalize ? categories.map(function (c) {
      var maximum = 0, total = 0;
      for (var a = 0; a < series.length; a++) {
        var v = cell(c, series[a]).num;
        if (v !== null && isFinite(v)) maximum = Math.max(maximum, Math.abs(v));
      }
      if (maximum) {
        for (var j = 0; j < series.length; j++) {
          var n = cell(c, series[j]).num;
          if (n !== null && isFinite(n)) total += Math.abs(n) / maximum;
        }
      }
      return { maximum: maximum, total: total };
    }) : [];
    var undrawn = 0;
    var cells = series.map(function (s2) {
      return categories.map(function (c, index) {
        var r = cell(c, s2);
        if (r.num === null || !isFinite(r.num)) {
          if (r.num !== null) undrawn++;
          return { num: null, exact: null, text: "", aggregate: null };
        }
        var text = r.exact !== null ? r.exact : String(r.num);
        if (normalize) {
          var norm = normalizers[index];
          var share = norm.total ? r.num / norm.maximum / norm.total : 0;
          if (share === 0) share = 0;
          return { num: share, exact: null, text: T.value(share, format), aggregate: text };
        }
        return { num: r.num, exact: r.exact, text: T.value(r.exact !== null ? r.exact : r.num, format), aggregate: text };
      });
    });
    if (undrawn) warnings.push({ code: "chart.rule.non-finite" });
    var markCount = 0;
    for (var si = 0; si < cells.length; si++) for (var ci = 0; ci < cells[si].length; ci++) if (cells[si][ci].num !== null) markCount++;
    for (var q = 0; q < series.length; q++) {
      series[q].id = color ? series[q].key : "measure";
      series[q].title = series[q].full;
      series[q].colour = R.theme.SERIES[q] || null;
    }
    var stacked = stack === "zero" || normalize;
    // The option the author wrote wins over the look's default (contract §4.5); a normalized stack's total is always
    // 100 % and carries no label.
    var option = doc.config.mark.labels === undefined ? look("labelsDefault") : doc.config.mark.labels;
    var labelsOn = option === "values" && !normalize;
    var endLabels = null;
    if (labelsOn) {
      endLabels = [];
      for (var c3 = 0; c3 < categories.length; c3++) {
        var tot = 0, any = false;
        for (var s4 = 0; s4 < series.length; s4++) {
          var cv = cells[s4][c3];
          if (cv.num === null) continue;
          if (stacked) {
            tot += cv.num;
            any = true;
          } else endLabels.push(cv.text);
        }
        if (stacked && any) endLabels.push(T.value(tot, look("stackTotalFormat")));
      }
    }
    var a0 = value.aggregate.charAt(0).toUpperCase() + value.aggregate.slice(1);
    return {
      vertical: vertical,
      stack: stack,
      categories: categories,
      series: series,
      cells: cells,
      markCount: markCount,
      warnings: warnings,
      legend: color ? color.legend : null,
      labelsOn: labelsOn,
      format: format,
      layout: {
        vertical: vertical,
        bands: categories.map(function (c) {
          return temporal ? T.temporalAxis(c.raw, band.format) : { text: c.full, under: null };
        }),
        bandTitle: titleOf(band, band.field),
        valueTitle: titleOf(value, value.field ? a0 + " of " + value.field : "Count"),
        bandGrid: band.axis ? band.axis.grid : undefined,
        valueGrid: value.axis ? value.axis.grid : undefined,
        format: format,
        domain: value.scale && value.scale.domain ? value.scale.domain : null,
        endLabels: endLabels,
        extent: function (visible) {
          var lo = 0, hi = 0;
          for (var c2 = 0; c2 < categories.length; c2++) {
            var pos = 0, neg = 0;
            for (var s3 = 0; s3 < series.length; s3++) {
              var v = cells[s3][c2].num;
              if (v === null || visible[series[s3].id] !== true) continue;
              if (stacked) {
                if (v >= 0) pos += v;
                else neg += v;
              } else {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
              }
            }
            if (stacked) {
              lo = Math.min(lo, neg);
              hi = Math.max(hi, pos);
            }
          }
          return [lo, hi];
        },
      },
    };
  }

  /** A bar with its data end rounded (look contract §2): a rect when r is 0, else a path. dir = the data end's side. */
  function shape(base, x, y, w, h, dir, r) {
    if (!(r > 0) || w <= 0 || h <= 0) return P.rect({ role: "mark", x: x, y: y, w: w, h: h, fill: base.fill, color: base.color, series: base.series, datum: base.datum, tip: base.tip });
    var d;
    if (dir === "up") d = ["M", x, y + h, "L", x, y + r, "A", r, x + r, y, "L", x + w - r, y, "A", r, x + w, y + r, "L", x + w, y + h, "Z"];
    else if (dir === "down") d = ["M", x + w, y, "L", x + w, y + h - r, "A", r, x + w - r, y + h, "L", x + r, y + h, "A", r, x, y + h - r, "L", x, y, "Z"];
    else if (dir === "right") d = ["M", x, y, "L", x + w - r, y, "A", r, x + w, y + r, "L", x + w, y + h - r, "A", r, x + w - r, y + h, "L", x, y + h, "Z"];
    else d = ["M", x + w, y + h, "L", x + r, y + h, "A", r, x, y + h - r, "L", x, y + r, "A", r, x + r, y, "L", x + w, y, "Z"];
    return P.path({ role: "mark", d: d, fill: base.fill, color: base.color, series: base.series, datum: base.datum, tip: base.tip });
  }

  function clampTo(v, lo, hi) {
    return v < lo ? lo : v > hi ? hi : v;
  }

  /**
   * The marks: one group per visible series (role mark), then the value labels (role guide). plot = {plot, value:
   * {lo, hi, scale}, band: {start, step, n}, visible: {id: true}, level}; hp = the draw's helpers.
   */
  function marks(m, plot, hp) {
    var out = [], labels = [], vis = [], theme = hp.theme;
    for (var s = 0; s < m.series.length; s++) if (plot.visible[m.series[s].id] === true) vis.push(s);
    var k = Math.max(1, vis.length), stacked = m.stack === "zero" || m.stack === "normalize";
    var step = plot.band.step, groupW = step * look(stacked ? "stackShare" : "barShare");
    var gap = theme.n("--chart-gap"), radius = theme.n("--chart-bar-radius"), maxShare = look("radiusMaxShare");
    var barW = stacked ? groupW : Math.max(0, (groupW - gap * (k - 1)) / k);
    var lo = plot.value.lo, hi = plot.value.hi, sc = plot.value.scale, base = sc(clampTo(0, lo, hi));
    var vertical = m.vertical, rects = [];
    var pos = [], neg = [];
    for (var c = 0; c < m.categories.length; c++) {
      pos.push(0);
      neg.push(0);
    }
    var outer = [];
    if (stacked) {
      for (var c1 = 0; c1 < m.categories.length; c1++) {
        var op = -1, on = -1;
        for (var a = 0; a < vis.length; a++) {
          var v0 = m.cells[vis[a]][c1].num;
          if (v0 === null) continue;
          if (v0 >= 0) op = vis[a];
          else on = vis[a];
        }
        outer.push({ pos: op, neg: on });
      }
    }
    for (var j = 0; j < vis.length; j++) {
      var si = vis[j], ser = m.series[si], kids = [];
      for (var ci = 0; ci < m.categories.length; ci++) {
        var cellv = m.cells[si][ci];
        if (cellv.num === null) continue;
        var v = cellv.num, from, to, first;
        if (stacked) {
          first = v >= 0 ? pos[ci] === 0 : neg[ci] === 0;
          from = v >= 0 ? pos[ci] : neg[ci];
          to = from + v;
          if (v >= 0) pos[ci] = to;
          else neg[ci] = to;
        } else {
          from = 0;
          to = v;
          first = true;
        }
        var p0 = sc(clampTo(from, lo, hi)), p1 = sc(clampTo(to, lo, hi));
        var off = plot.band.start + step * ci + (step - groupW) / 2 + (stacked ? 0 : j * (barW + gap));
        var len = Math.abs(p1 - p0), shift = !first && len > 0 ? Math.min(gap, len) : 0;
        len -= shift;
        var isOuter = !stacked || look("roundOuterOnly") !== true || (v >= 0 ? outer[ci].pos === si : outer[ci].neg === si);
        var r = isOuter ? Math.min(radius, barW * maxShare, len) : 0;
        var tip = [m.categories[ci].full, (m.series.length > 1 || ser.id !== "measure" ? ser.title + ": " : "") + cellv.text];
        var baseF = { fill: ser.colour, color: theme.css(ser.colour), series: ser.id, datum: ci, tip: tip };
        var mk, box;
        if (vertical) {
          var up = p1 <= p0, y = up ? p1 : p0 + shift;
          mk = shape(baseF, off, up ? y : y, barW, len, up ? "up" : "down", r);
          box = { x: off, w: barW, end: p1, up: up };
        } else {
          var right = p1 >= p0, x = right ? p0 + shift : p1;
          mk = shape(baseF, x, off, len, barW, right ? "right" : "left", r);
          box = { y: off, w: barW, end: p1, up: right };
        }
        kids.push(mk);
        rects.push({ s: si, c: ci, v: v, text: cellv.text, box: box });
      }
      P.group(out, { role: "mark", series: ser.id, label: ser.title }, kids);
    }
    if (m.labelsOn && plot.level !== "minimal") {
      if (stacked) stackTotals(m, plot, hp, rects, groupW, labels);
      else barLabels(m, plot, hp, rects, barW, groupW, labels);
    }
    return out.concat(labels);
  }

  function inside(plot, x0, y0, x1, y1) {
    var p = plot.plot;
    return x0 >= p.x && y0 >= p.y && x1 <= p.x + p.w && y1 <= p.y + p.h;
  }

  /** True when a box [x0, y0, x1, y1] fits the plot and meets no label already placed; then it is placed. */
  function free(plot, placed, x0, y0, x1, y1) {
    if (!inside(plot, x0, y0, x1, y1)) return false;
    for (var i = 0; i < placed.length; i++) {
      var q = placed[i];
      if (x0 < q[2] && q[0] < x1 && y0 < q[3] && q[1] < y1) return false;
    }
    placed.push([x0, y0, x1, y1]);
    return true;
  }

  /**
   * A value label at the data end of a box, or null when it does not fit (contract §4.3: not drawn): wider than maxW,
   * outside the plot, or meeting a label already placed.
   */
  function labelAt(m, plot, hp, box, text, maxW, thick, placed) {
    var s = hp.small, g = look("valueLabelGap") * s, w = hp.width(text, s), bh = hp.boxH(s);
    if (w > maxW + 1e-9) return null;
    if (m.vertical) {
      var cx = box.x + box.w / 2, y0 = box.up ? box.end - g - bh : box.end + g;
      if (!free(plot, placed, cx - w / 2, y0, cx + w / 2, y0 + bh)) return null;
      return hp.label({ role: "guide", x: cx, y: y0 + hp.ascent(s), text: text, anchor: "middle", size: s, ink: "--chart-ink-2" });
    }
    if (bh > thick + 1e-9) return null;
    var cy = box.y + box.w / 2, x0 = box.up ? box.end + g : box.end - g - w;
    if (!free(plot, placed, x0, cy - bh / 2, x0 + w, cy + bh / 2)) return null;
    return hp.label({ role: "guide", x: box.up ? x0 : x0 + w, y: hp.middle(cy, s), text: text, anchor: box.up ? "start" : "end", size: s, ink: "--chart-ink-2" });
  }

  /** Grouped and single bars: every bar when every label fits its own bar's width, else the tallest of each group. */
  function barLabels(m, plot, hp, rects, barW, groupW, out) {
    var every = true, placed = [];
    if (m.vertical) for (var i = 0; i < rects.length && every; i++) every = hp.width(rects[i].text, hp.small) <= barW + 1e-9;
    if (!m.vertical || every) {
      for (var j = 0; j < rects.length; j++) {
        var l = labelAt(m, plot, hp, rects[j].box, rects[j].text, m.vertical ? barW : Infinity, barW, placed);
        if (l !== null) out.push(l);
      }
      return;
    }
    var best = new Map();
    for (var k = 0; k < rects.length; k++) {
      var cur = best.get(rects[k].c);
      if (cur === undefined || Math.abs(rects[k].v) > Math.abs(cur.v)) best.set(rects[k].c, rects[k]);
    }
    for (var c = 0; c < m.categories.length; c++) {
      var r = best.get(c);
      if (r === undefined) continue;
      var lb = labelAt(m, plot, hp, r.box, r.text, groupW, barW, placed);
      if (lb !== null) out.push(lb);
    }
  }

  /** A stack's total beyond its end, in the short form (contract §4.3, look field stackTotalFormat). */
  function stackTotals(m, plot, hp, rects, groupW, out) {
    var sums = new Map(), ends = new Map(), placed = [];
    for (var i = 0; i < rects.length; i++) {
      var r = rects[i];
      sums.set(r.c, (sums.has(r.c) ? sums.get(r.c) : 0) + r.v);
      var e = ends.get(r.c) || { pos: null, neg: null };
      if (r.v >= 0) e.pos = r.box;
      else e.neg = r.box;
      ends.set(r.c, e);
    }
    for (var c = 0; c < m.categories.length; c++) {
      if (!sums.has(c)) continue;
      var total = sums.get(c), en = ends.get(c), box = total >= 0 ? en.pos || en.neg : en.neg || en.pos;
      var outerBox = { x: box.x, y: box.y, w: box.w, end: box.end, up: total >= 0 ? true : false };
      if (total >= 0 && en.pos === null) outerBox.end = plot.value.scale(Math.max(plot.value.lo, Math.min(plot.value.hi, 0)));
      var lb = labelAt(m, plot, hp, outerBox, T.value(total, look("stackTotalFormat")), groupW, groupW, placed);
      if (lb !== null) out.push(lb);
    }
  }

  R.type("bar", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
