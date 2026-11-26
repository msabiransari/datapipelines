// 16-lines.js : the line family's shared model and marks (record §4.5 rows line and area, §4.4 G1, G4, G6, G7, G8,
// §4.9 Thinning; look contract §2 line, weight, points, area; §4.5 labels: last; brief 989 D1, D5, D6, D8). The two
// templates (types/line.js, types/area.js) register their descriptors and call these with their options; they never
// write an axis, a legend or a title. Rows sharing x and series are aggregated (one pass, one entry per group: the
// work is bounded by the row cap; nothing is kept between calls); a null measure is a gap; x is a band (nominal, ordinal: evenly spaced, in the band's
// order) or a position (temporal: a continuous UTC axis; quantitative: a number axis, linear or log). The series come
// from the colour column, from `value.fields` (G1: the fields ARE the series, in the list's order), or are the one
// measure. No object is ever keyed by a data value: a raw value's slot, a series and an x entry live in Maps, and a
// series' groups in a list indexed by the x entry's ordinal.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, S = R.scales, look = R.tables.look, cap = R.tables.cap;

  function titleOf(enc, dflt) {
    return enc.title === null ? null : enc.title === undefined ? dflt : enc.title;
  }

  /** "3.7K" and its unit after one space (G6: a unit follows the number in tooltips and value labels, never a tick). */
  function withUnit(text, unit) {
    return typeof unit === "string" && unit !== "" && text !== "" ? text + " " + unit : text;
  }

  /** A tooltip line of a `detail` entry (G7): "title: value", the value written by its column's kind. */
  function detailLine(d, v, types) {
    var t = types[d.field], text;
    if (D.isNull(v)) text = "";
    else if (t === "DATE" || t === "TIMESTAMP" || t === "TIMESTAMP_TZ" || t === "TIME") text = T.temporalFull(v, d.format);
    else if (typeof v === "number" || (typeof v === "string" && D.EXACT_TYPES[t] === true)) text = T.value(v, d.format);
    else text = String(v);
    return (d.title === undefined || d.title === null ? d.field : d.title) + ": " + withUnit(text, d.unit);
  }

  /**
   * The model. opts = {area: boolean}. Returns what render reads (series with id, title, colour; markCount = the
   * non-null points of the data; warnings; legend; direct; legendKey; lasts; layout) and what marks reads (xs, cells).
   */
  function model(doc, columns, types, opts) {
    var cfg = doc.config, e = cfg.encoding, x = e.x, y = e.y, color = e.color || null, fields = Array.isArray(y.fields) ? y.fields : null;
    types = types && typeof types === "object" ? types : {};
    var warnings = [], warned = {};
    var warn = function (code) {
      if (warned[code] === true) return;
      warned[code] = true;
      warnings.push({ code: code });
    };
    var temporal = x.type === "temporal", position = temporal || x.type === "quantitative";
    var scaleY = y.scale || {}, scaleX = (position && x.scale) || {};
    var logY = scaleY.type === "log" && opts.area !== true, logX = !temporal && position && scaleX.type === "log";
    var X = D.column(columns, x.field), C = color ? D.column(columns, color.field) : null;
    var tips = Array.isArray(e.tooltip) ? e.tooltip : [];
    var tipCols = tips.map(function (d) {
      return D.column(columns, d.field);
    });
    // The value columns: one per entry of `fields`, or the one field (none for count).
    var measures = fields ? fields.map(function (f) {
      return { field: f.field, col: D.column(columns, f.field), title: f.title === undefined ? f.field : f.title, unit: f.unit };
    }) : [{ field: y.field || null, col: y.field ? D.column(columns, y.field) : null, title: null, unit: y.unit }];
    // One pass over the rows. Each series record holds its own groups (a list indexed by the x entry's ordinal), so a
    // row costs one x lookup, one series lookup and one group read; a group keeps its first value and makes a list only
    // when a second row joins it (most groups of a line are one row). Every memo below lives for this call only.
    var xsMap = new Map(), sers = new Map(), anyTime = false;
    var fieldKeys = fields ? measures.map(function (mm, k) {
      return "field:" + k;
    }) : null;
    var serOfRaw = new Map();
    var n = X.length;
    // A raw x (a string or a number) is read once per call: its slot holds what the row's x is (a warning code, or its
    // position and key) and, once a row of it is kept, its x entry. Rows repeating an x (one per series) then cost one
    // lookup by the raw value; a group is keyed by its x entry's ordinal, never by a position's number.
    var slots = new Map(), lastX = null, lastSlot = undefined;
    var slotOf = function (xv) {
      if (temporal) {
        var p = R.time.parse(xv);
        if (p === null) return { bad: "chart.rule.null-position", at: 0, xk: null, time: false, entry: null };
        return { bad: null, at: p.at, xk: p.at, time: p.time, entry: null };
      }
      if (position) {
        var num = Number(xv);
        if (!(num > -Infinity && num < Infinity)) return { bad: "chart.rule.null-position", at: 0, xk: null, time: false, entry: null };
        if (logX && !(num > 0)) return { bad: "chart.rule.log-nonpositive", at: 0, xk: null, time: false, entry: null };
        return { bad: null, at: num, xk: num, time: false, entry: null };
      }
      return { bad: null, at: null, xk: D.keyOf(xv), time: false, entry: null };
    };
    for (var i = 0; i < n; i++) {
      var xv = X[i];
      if (D.isNull(xv)) {
        warn(position ? "chart.rule.null-position" : "chart.rule.null-category");
        continue;
      }
      // The previous row's x first (rows ordered by x repeat it once per series), then the call's map of raw values.
      var memo = typeof xv === "string" || typeof xv === "number", slot = memo && xv === lastX ? lastSlot : memo ? slots.get(xv) : undefined;
      if (slot === undefined) {
        slot = slotOf(xv);
        if (memo) slots.set(xv, slot);
      }
      if (memo) {
        lastX = xv;
        lastSlot = slot;
      }
      if (slot.bad !== null) {
        warn(slot.bad);
        continue;
      }
      if (slot.time) anyTime = true;
      var cv = C ? C[i] : null;
      if (C && D.isNull(cv)) {
        warn("chart.rule.null-category");
        continue;
      }
      var xe = slot.entry;
      if (xe === null) {
        xe = xsMap.get(slot.xk);
        if (xe === undefined) {
          xe = { key: slot.xk, raw: xv, at: slot.at, first: xsMap.size, row: i };
          xsMap.set(slot.xk, xe);
        }
        slot.entry = xe;
      }
      // The row's series: with a colour, its record is found by the raw colour value (a string or a number: the same
      // value has the same key); the key text is made only the first time a value is seen.
      var rowSer = null;
      if (C) {
        var cmemo = typeof cv === "string" || typeof cv === "number";
        rowSer = cmemo ? serOfRaw.get(cv) : undefined;
        if (rowSer === undefined) {
          var ck = D.keyOf(cv);
          rowSer = sers.get(ck);
          if (rowSer === undefined) {
            rowSer = { key: ck, raw: cv, first: sers.size, measure: 0, full: String(cv), byX: [] };
            sers.set(ck, rowSer);
          }
          if (cmemo) serOfRaw.set(cv, rowSer);
        }
      }
      for (var mi = 0; mi < measures.length; mi++) {
        var ser = rowSer;
        if (ser === null) {
          var sk = fields ? fieldKeys[mi] : "";
          ser = sers.get(sk);
          if (ser === undefined) {
            ser = { key: sk, raw: null, first: sers.size, measure: mi, full: fields ? measures[mi].title : String(y.field || "count"), byX: [] };
            sers.set(sk, ser);
          }
        }
        var v = measures[mi].col ? measures[mi].col[i] : 1, g = ser.byX[xe.first];
        if (g === undefined) ser.byX[xe.first] = { v: v, vals: null, row: i };
        else if (g.vals === null) g.vals = [g.v, v];
        else g.vals.push(v);
      }
    }
    // The series cap before any work that grows with series × x (the cells): the refusal is the one render gives after
    // the model, so the result is the same and the work stays linear in the rows.
    if (sers.size > R.tables.cap("series")) return { rule: { code: "chart.too_many_series", details: { series: sers.size, limit: R.tables.cap("series") } } };
    // The x order: a band in its sort; a position ascending by its number (the line family sorts points, §4.9).
    var xs = Array.from(xsMap.values());
    if (position) {
      xs.sort(function (a, b) {
        return a.at - b.at || a.first - b.first;
      });
    } else xs = D.order(xs, x.sort, function () {
      return null;
    }, types[x.field], false);
    var xIndex = new Array(xs.length);
    for (var xi = 0; xi < xs.length; xi++) xIndex[xs[xi].first] = xi;
    var series = Array.from(sers.values());
    if (C) series = D.order(series, color.sort, function () {
      return null;
    }, types[color.field], false);
    var agg = y.aggregate, stack = opts.area === true && (C || fields) ? (y.stack === undefined ? null : y.stack) : null;
    var normalize = stack === "normalize", stacked = stack === "zero" || normalize;
    var format = normalize ? y.format || "percent" : y.format;
    var gran = temporal ? R.time.granularity(x.format, anyTime) : null;
    var undrawn = false, nonPositive = false;
    // cells[s][xi] = undefined (no row at that x) | null (a null measure: a gap) | {num, exact, text, row}.
    var cells = series.map(function (s) {
      var row = new Array(xs.length), byX = s.byX, field = measures[s.measure].field || "count";
      byX.forEach(function (g, key) {
        var a = aggregateGroup(g, agg, field), j = xIndex[key];
        if (a.num === null) {
          row[j] = null;
          return;
        }
        if (!isFinite(a.num)) {
          undrawn = true;
          row[j] = null;
          return;
        }
        if (logY && !(a.num > 0)) {
          nonPositive = true;
          row[j] = null;
          return;
        }
        row[j] = { num: a.num, exact: a.exact, text: "", row: g.row };
      });
      return row;
    });
    // A stack at zero whose sum at one position, up or down, is beyond float64 is not drawn there (#1011): every
    // series has a gap at that position, as a value that is not finite has; a series with no row there too, or its
    // line and its fill would run across the gap.
    if (stack === "zero") {
      for (var oj = 0; oj < xs.length; oj++) {
        var up = 0, down = 0;
        for (var os = 0; os < cells.length; os++) {
          var oc = cells[os][oj];
          if (oc === null || oc === undefined) continue;
          if (oc.num < 0) down += oc.num;
          else up += oc.num;
        }
        if (isFinite(up) && isFinite(down)) continue;
        for (var ok = 0; ok < cells.length; ok++) cells[ok][oj] = null;
        undrawn = true;
      }
    }
    if (undrawn) warn("chart.rule.non-finite");
    if (nonPositive) warn("chart.rule.log-nonpositive");
    if (normalize) {
      for (var nx = 0; nx < xs.length; nx++) {
        var total = 0;
        for (var a1 = 0; a1 < cells.length; a1++) if (cells[a1][nx]) total += Math.abs(cells[a1][nx].num);
        for (var a2 = 0; a2 < cells.length; a2++) {
          var c0 = cells[a2][nx];
          if (!c0) continue;
          var share = total ? c0.num / total : 0;
          cells[a2][nx] = { num: share === 0 ? 0 : share, exact: null, text: "", row: c0.row, value: c0.exact !== null ? c0.exact : c0.num };
        }
      }
    }
    var markCount = 0;
    for (var s1 = 0; s1 < cells.length; s1++) {
      var unit = measures[series[s1].measure].unit;
      for (var x1 = 0; x1 < xs.length; x1++) {
        var c1 = cells[s1][x1];
        if (!c1) continue;
        markCount++;
        c1.text = withUnit(T.value(c1.exact !== null ? c1.exact : c1.num, format), unit);
      }
    }
    for (var q = 0; q < series.length; q++) {
      series[q].id = C ? series[q].key : fields ? "field:" + fields[series[q].measure].field : "measure";
      series[q].title = series[q].full;
      series[q].colour = R.theme.SERIES[q] || null;
    }
    // `labels: last` (line only, contract §4.5): each series' last value, in its format or the short form, with its unit.
    var labelsOpt = opts.area === true ? "none" : cfg.mark.labels === undefined ? look("lineLabelsDefault") : cfg.mark.labels;
    var lasts = series.map(function (s, si) {
      var last = null;
      for (var k = xs.length - 1; k >= 0 && last === null; k--) if (cells[si][k]) last = cells[si][k];
      var text = labelsOpt === "last" && last !== null ? withUnit(T.value(last.exact !== null ? last.exact : last.num, format || look("lastLabelFormat")), measures[s.measure].unit) : null;
      return { id: s.id, value: text };
    });
    var xFull = function (xe) {
      return temporal ? T.temporalFull(xe.raw, gran) : position ? T.value(xe.at, x.format) : String(xe.raw);
    };
    var minAt = xs.length > 0 && position ? xs[0].at : 0, maxAt = xs.length > 0 && position ? xs[xs.length - 1].at : 0;
    var vt = fields ? null : titleOf(y, y.field ? y.aggregate.charAt(0).toUpperCase() + y.aggregate.slice(1) + " of " + y.field : "Count");
    if (fields && y.title !== undefined) vt = y.title;
    if (vt !== null && !fields && typeof y.unit === "string" && y.unit !== "") vt = vt + " (" + y.unit + ")";
    var curveOpt = cfg.mark.curve === undefined ? look("curveDefault") : cfg.mark.curve;
    var pointsOpt = opts.area === true ? look("pointsDefault") : cfg.mark.points === undefined ? look("pointsDefault") : cfg.mark.points;
    var zero = opts.area === true || scaleY.zero === true;
    var m = {
      area: opts.area === true,
      stack: stack,
      stacked: stacked,
      curve: curveOpt,
      points: pointsOpt,
      xs: xs,
      xFull: xFull,
      series: series,
      cells: cells,
      tips: tips,
      tipCols: tipCols,
      types: types,
      markCount: markCount,
      warnings: warnings,
      legend: color ? color.legend : undefined,
      direct: true,
      legendKey: opts.area === true ? "legendKeyArea" : "legendKeyLine",
      lasts: lasts,
      format: format,
      layout: {
        vertical: true,
        bands: position ? [] : xs.map(function (xe) {
          return { text: xFull(xe), under: null };
        }),
        x: position ? { kind: temporal ? "time" : logX ? "log" : "linear", lo: temporal ? R.time.domain(minAt, maxAt, gran)[0] : minAt, hi: temporal ? R.time.domain(minAt, maxAt, gran)[1] : maxAt, gran: gran, format: x.format, domain: scaleX.domain || null, zero: scaleX.zero === true } : null,
        log: logY,
        bandTitle: titleOf(x, x.field),
        valueTitle: vt,
        bandGrid: x.axis ? x.axis.grid : undefined,
        valueGrid: y.axis ? y.axis.grid : undefined,
        format: format,
        domain: scaleY.domain || null,
        endLabels: null,
        reach: function (h) {
          return pointsOpt === "none" ? h.theme.n("--chart-line") / 2 : h.theme.n("--chart-point") + h.theme.n("--chart-gap") / 2;
        },
        extent: function (visible) {
          return extent(m, visible, logY, zero);
        },
      },
    };
    return m;
  }

  /**
   * One group's aggregate, as D.aggregate gives it. A group of one number is answered here (count: 1; sum and mean:
   * 0 + v, as D.aggregate adds from 0; min and max: v); every other group, and a lone value that is not a number, goes
   * to D.aggregate unchanged.
   */
  function aggregateGroup(g, agg, field) {
    if (g.vals !== null) return D.aggregate(g.vals, agg, field);
    var v = g.v;
    if (agg === "count") return { num: 1, exact: null };
    if (typeof v === "number") return { num: agg === "sum" || agg === "mean" ? 0 + v : v, exact: null };
    return D.aggregate([v], agg, field);
  }

  /** The value extent of the visible series: [lo, hi] (with zero when asked), or [smallest positive, largest] for log. */
  function extent(m, visible, logY, zero) {
    var lo = null, hi = null;
    var take = function (v) {
      if (lo === null || v < lo) lo = v;
      if (hi === null || v > hi) hi = v;
    };
    for (var xi = 0; xi < m.xs.length; xi++) {
      var pos = 0, neg = 0, any = false;
      for (var s = 0; s < m.series.length; s++) {
        var c = m.cells[s][xi];
        if (!c || visible[m.series[s].id] !== true) continue;
        if (m.stacked) {
          any = true;
          if (c.num >= 0) pos += c.num;
          else neg += c.num;
        } else take(c.num);
      }
      if (m.stacked && any) {
        take(neg);
        take(pos);
      }
    }
    if (logY) return [lo, hi];
    if (lo === null) return [0, 0];
    return zero ? [Math.min(0, lo), Math.max(0, hi)] : [lo, hi];
  }

  function clampTo(v, lo, hi) {
    return v < lo ? lo : v > hi ? hi : v;
  }

  /**
   * The tooltip lines of one point (G7; carried on the point for the interaction layer, F6). xText = the call's texts
   * of the x positions, each written the first time a point at that x asks for it (every series shares them).
   */
  function tipOf(m, si, xi, c, xText) {
    var xt = xText[xi];
    if (xt === undefined) xt = xText[xi] = m.xFull(m.xs[xi]);
    var s = m.series[si], lines = [xt, (m.series.length > 1 || s.id !== "measure" ? s.title + ": " : "") + c.text];
    for (var d = 0; d < m.tips.length; d++) lines.push(detailLine(m.tips[d], m.tipCols[d][c.row], m.types));
    return lines;
  }

  /**
   * The marks of the visible series: per series one group (role mark) holding, for an area, its faded fill under its
   * line; the line (a stroke of --chart-line, round joins and ends); and its dots (the end dot by default: --chart-point
   * with a --chart-gap ring in the canvas colour, drawn smaller where the frame kept less than its reach). Over the drawing cap a series is thinned (§4.9) before its curve is
   * fitted; a stack keeps one set of x positions for all its series. Returns {primitives, points, ends}.
   */
  function marks(m, plot, hp) {
    var theme = hp.theme, out = [], index = [], count = 0, ends = [], vis = [], xText = new Array(m.xs.length), atText = null;
    for (var s = 0; s < m.series.length; s++) if (plot.visible[m.series[s].id] === true) vis.push(s);
    var val = plot.value, lo = val.lo, hi = val.hi, ys = val.scale;
    var xpx = m.xs.map(function (xe, j) {
      return plot.x ? plot.x.scale(clampTo(xe.at, plot.x.lo, plot.x.hi)) : plot.band.start + plot.band.step * (j + 0.5);
    });
    var base = ys(clampTo(0, lo, hi));
    var limit = cap((m.area ? "area" : "line") + ".points");
    // Stacked tops (and bottoms) per visible series over the shared x positions: a null or missing value adds nothing
    // to the stack and is a gap in its own series.
    var tops = [], bottoms = [];
    if (m.stacked) {
      var posAcc = m.xs.map(function () {
        return 0;
      }), negAcc = posAcc.slice();
      for (var a = 0; a < vis.length; a++) {
        var t = [], b = [];
        for (var j = 0; j < m.xs.length; j++) {
          var c = m.cells[vis[a]][j];
          if (!c) {
            t.push(null);
            b.push(null);
            continue;
          }
          var from = c.num >= 0 ? posAcc[j] : negAcc[j], to = from + c.num;
          if (c.num >= 0) posAcc[j] = to;
          else negAcc[j] = to;
          b.push(from);
          t.push(to);
        }
        tops.push(t);
        bottoms.push(b);
      }
    }
    // Which x indices each series draws: every present point, or the thinned ones.
    var keepAll = null;
    if (m.stacked) {
      var over = false;
      for (var o = 0; o < vis.length; o++) if (countOf(m.cells[vis[o]]) > limit) over = true;
      if (over) {
        var lists = tops.map(function (t2) {
          return t2.map(function (v) {
            return v === null ? null : -ys(clampTo(v, lo, hi));
          });
        });
        keepAll = R.thin.union(xpx, lists);
      }
    }
    for (var k = 0; k < vis.length; k++) {
      var si = vis[k], ser = m.series[si], row = m.cells[si], kids = [];
      var colour = { token: ser.colour, css: theme.css(ser.colour) };
      // The series' present points in x order: [x index], a null splitting its runs.
      var idx = [];
      for (var j2 = 0; j2 < m.xs.length; j2++) {
        if (row[j2] === undefined) continue;
        if (m.stacked && row[j2] === null) {
          idx.push(-1);
          continue;
        }
        idx.push(row[j2] === null ? -1 : j2);
      }
      var valueOf = function (j3) {
        return m.stacked ? tops[k][j3] : row[j3].num;
      };
      var drawn = idx;
      if (keepAll !== null) {
        var kept = {};
        for (var q = 0; q < keepAll.length; q++) kept[keepAll[q]] = true;
        drawn = idx.filter(function (j4) {
          return j4 < 0 || kept[j4] === true;
        });
      } else if (!m.stacked && countOf(row) > limit) {
        var px = [], vy = [];
        for (var u = 0; u < idx.length; u++) {
          px.push(idx[u] < 0 ? (u > 0 ? px[u - 1] : 0) : xpx[idx[u]]);
          vy.push(idx[u] < 0 ? null : -ys(clampTo(valueOf(idx[u]), lo, hi)));
        }
        var keep = R.thin.series(px, vy, []);
        drawn = idx.filter(function (j5, u2) {
          return j5 < 0 || keep[u2] === true;
        });
      }
      var runs = [], cur = [], lowerRuns = [], curLow = [];
      for (var r = 0; r < drawn.length; r++) {
        var jj = drawn[r];
        if (jj < 0) {
          if (cur.length > 0) {
            runs.push(cur);
            lowerRuns.push(curLow);
          }
          cur = [];
          curLow = [];
          continue;
        }
        cur.push({ x: xpx[jj], y: ys(clampTo(valueOf(jj), lo, hi)) });
        // The lower edge is an area's only (a line draws its upper edge alone).
        if (m.area) curLow.push({ x: xpx[jj], y: m.stacked ? ys(clampTo(bottoms[k][jj], lo, hi)) : base });
      }
      if (cur.length > 0) {
        runs.push(cur);
        lowerRuns.push(curLow);
      }
      var lineD = [];
      for (var g = 0; g < runs.length; g++) {
        var segs = R.curve.segments(runs[g], m.curve);
        if (m.area && runs[g].length > 1) {
          var areaD = R.curve.forward(runs[g], segs, []);
          var low = lowerRuns[g], lowSegs = m.stacked ? R.curve.segments(low, m.curve) : R.curve.segments(low, "linear");
          areaD.push("L", low[low.length - 1].x, low[low.length - 1].y);
          R.curve.append(R.curve.reversed(low, lowSegs), areaD);
          areaD.push("Z");
          var fade = look("areaFade") === true;
          kids.push(P.path({ role: "mark", d: areaD, fill: colour.token, color: colour.css, opacity: fade ? 1 : theme.n("--chart-area-top"), series: ser.id, fade: fade ? [theme.n("--chart-area-top"), theme.n("--chart-area-bottom"), plot.plot.y, base] : null }));
        }
        if (runs[g].length > 1) R.curve.forward(runs[g], segs, lineD);
      }
      if (lineD.length > 0) {
        kids.push(P.path({ role: "mark", d: lineD, stroke: colour.token, strokeColor: colour.css, strokeWidth: theme.n("--chart-line"), round: look("lineRound") === true, series: ser.id }));
      }
      var dots = [], lastPt = null;
      for (var r2 = 0; r2 < runs.length; r2++) {
        for (var w = 0; w < runs[r2].length; w++) {
          lastPt = runs[r2][w];
          if (m.points === "markers") dots.push(lastPt);
        }
      }
      if (m.points === "end" && lastPt !== null) dots.push(lastPt);
      // A dot the frame cannot hold is drawn smaller, with its ring, by the share the frame kept (L10, #1128); where
      // the frame holds the whole reach the two tokens are written as they are.
      var keep = plot.reach, share = keep && keep.asked > 0 ? keep.kept / keep.asked : 1;
      var dotR = theme.n("--chart-point"), ring = theme.n("--chart-gap");
      if (share < 1) {
        dotR = S.r2(dotR * share);
        ring = S.r2(ring * share);
      }
      for (var dd = 0; dd < dots.length; dd++) {
        kids.push(P.circle({ role: "mark", cx: dots[dd].x, cy: dots[dd].y, r: dotR, fill: colour.token, color: colour.css, stroke: "--chart-canvas", strokeColor: theme.css("--chart-canvas"), strokeWidth: ring, series: ser.id }));
      }
      if (lastPt !== null) ends.push({ id: ser.id, y: lastPt.y });
      P.group(out, { role: "mark", series: ser.id, label: ser.title }, kids);
      // The index holds every point, thinned or not (§4.9): per series its points' x (rounded once per x for every
      // series), datum and tip lines, in x order.
      if (atText === null) atText = xpx.map(R.scales.r2);
      var ix = { series: ser.id, at: [], datum: [], tip: [] };
      for (var z = 0; z < m.xs.length; z++) {
        var cz = row[z];
        if (!cz) continue;
        ix.at.push(atText[z]);
        ix.datum.push(z);
        ix.tip.push(tipOf(m, si, z, cz, xText));
      }
      count += ix.at.length;
      index.push(ix);
    }
    return { primitives: out, index: index, count: count, ends: ends };
  }

  function countOf(row) {
    var n = 0;
    for (var i = 0; i < row.length; i++) if (row[i]) n++;
    return n;
  }

  R.lines = { model: model, marks: marks, extent: extent, withUnit: withUnit, detailLine: detailLine };
})(JsRendered);
