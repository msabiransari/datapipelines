// types/scatter.js : the scatter type (record §4.5 row scatter; §4.4 G4, G6, G8; look contract §2 gaps, §3.8
// --chart-point, §4.2 "a dot for points", §4.4 "Scatter: the nearest point within 24 px gets a ring"; brief 1062). A
// plain function template (R17, O-6): `model` prepares the rows, `marks` returns the circles and the lookup (the
// index's second form, 21-lookup.js); neither writes an axis, a legend or a title. One mark per row, no aggregation, on
// two continuous axes: the layout part #1051 proved (`x` and `vertical: true`), with `pad` (#1066) so a mark of the
// largest size stays inside the plot. A scatter with `size` is the bubble chart: diameters from the document's range,
// the AREA following the value. `color` is a series (nominal, ordinal: one colour per series, a switch legend of dots)
// or a shade (quantitative: one series, each point's colour its value's step on the colour scale, R.colour).
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, L = R.lines, r2 = R.scales.r2;

  /** Scatter's channel descriptor (record §4.2), the same data as ChartType.SCATTER's in Kotlin (R-REG compares them). */
  var CHANNELS = [
    { key: "x", required: true, node: { node: "shape", name: "position" } },
    { key: "y", required: true, node: { node: "shape", name: "position" } },
    { key: "color", required: false, node: { node: "oneOf", of: [{ shape: "series", types: ["nominal", "ordinal"] }, { shape: "shade", types: ["quantitative"] }] } },
    { key: "size", required: false, node: { node: "shape", name: "size" } },
    { key: "tooltip", required: false, node: { node: "list", min: 0, max: 6, of: { node: "shape", name: "detail" } } },
  ];

  /** The record's default diameters of a size channel, px (§4.4 size). */
  var RANGE = [6, 40];
  /** The ring around every point, × --chart-gap (look contract §2 gaps: 1.5 px at the default gap of 2). */
  var RING = 0.75;

  function titleOf(enc) {
    return enc.title === null ? null : enc.title === undefined ? enc.field : enc.title;
  }

  /** A channel's axis title with its unit after it (G6; the line family's value title form); null writes neither. */
  function axisTitle(enc) {
    var t = titleOf(enc);
    return t !== null && typeof enc.unit === "string" && enc.unit !== "" ? t + " (" + enc.unit + ")" : t;
  }

  /** The name of a card row: the channel's title, else its field name (a title of null still names the row). */
  function rowName(enc) {
    return typeof enc.title === "string" ? enc.title : enc.field;
  }

  /** A number cell's place: a number as it is; an exact column's text by Number (null was asked first). */
  function numberOf(v) {
    return typeof v === "number" ? v : Number(v);
  }

  function finite(v) {
    return typeof v === "number" && v - v === 0;
  }

  function clamp(v, lo, hi) {
    return v < lo ? lo : v > hi ? hi : v;
  }

  /**
   * The diameter of size value v over [lo, hi] of the rows drawn (brief D5): the area follows the value. t is the plain
   * quotient where hi − lo is a finite number, else the quotient of the halves (the span is past the number range);
   * hi = lo gives every mark the diameter of the middle area.
   */
  function diameter(v, lo, hi, dmin, dmax) {
    var t;
    if (hi === lo) t = 0.5;
    else if (finite(hi - lo)) t = (v - lo) / (hi - lo);
    else t = (v / 2 - lo / 2) / (hi / 2 - lo / 2);
    return Math.sqrt(dmin * dmin + t * (dmax * dmax - dmin * dmin));
  }

  function model(doc, columns, types) {
    var e = doc.config.encoding, x = e.x, y = e.y, given = e.color || null, size = e.size || null;
    var shade = given !== null && given.type === "quantitative" ? given : null, color = shade === null ? given : null;
    types = types && typeof types === "object" ? types : {};
    var warnings = [], warned = {};
    var warn = function (code) {
      if (warned[code] === true) return;
      warned[code] = true;
      warnings.push({ code: code });
    };
    var temporal = x.type === "temporal", scaleX = (!temporal && x.scale) || {}, scaleY = y.scale || {};
    var logX = scaleX.type === "log", logY = scaleY.type === "log";
    var X = D.column(columns, x.field), Y = D.column(columns, y.field), C = color ? D.column(columns, color.field) : null;
    var Z = size ? D.column(columns, size.field) : null, SH = shade ? D.column(columns, shade.field) : null;
    var tips = Array.isArray(e.tooltip) ? e.tooltip : [];
    var tipCols = tips.map(function (d) {
      return D.column(columns, d.field);
    });
    var n = D.rowCount(columns), sers = new Map(), rows = [], anyTime = false;
    for (var i = 0; i < n; i++) {
      var cv = C ? C[i] : null;
      if (C && D.isNull(cv)) {
        warn("chart.rule.null-category");
        continue;
      }
      var xv = X[i], yv = Y[i], zv = Z ? Z[i] : null, tx = null;
      if (temporal && !D.isNull(xv)) tx = R.time.parse(xv);
      // A null x or y (a time x that is not a time), or a null size where the chart has one: not drawn (D3).
      if (D.isNull(xv) || D.isNull(yv) || (temporal && tx === null) || (Z && D.isNull(zv))) {
        warn("chart.rule.null-position");
        continue;
      }
      var xn = temporal ? tx.at : numberOf(xv), yn = numberOf(yv), zn = Z ? numberOf(zv) : null;
      if (!finite(xn) || !finite(yn) || (Z && !finite(zn))) {
        warn("chart.rule.non-finite");
        continue;
      }
      if ((logX && !(xn > 0)) || (logY && !(yn > 0))) {
        warn("chart.rule.log-nonpositive");
        continue;
      }
      if (tx !== null && tx.time) anyTime = true;
      var sk = C ? D.keyOf(cv) : "";
      var ser = sers.get(sk);
      if (ser === undefined) {
        ser = { key: sk, raw: C ? cv : null, first: sers.size, full: C ? String(cv) : String(y.field) };
        sers.set(sk, ser);
      }
      rows.push({ key: sk, row: i, x: xn, y: yn, z: zn, xRaw: xv, yRaw: yv, zRaw: zv });
    }
    if (sers.size > R.tables.cap("series")) return { rule: { code: "chart.too_many_series", details: { series: sers.size, limit: R.tables.cap("series") } } };
    var series = Array.from(sers.values());
    if (C) series = D.order(series, color.sort, function () {
      return null;
    }, types[color.field], false);
    var byKey = new Map();
    for (var q = 0; q < series.length; q++) {
      series[q].id = C ? series[q].key : "measure";
      series[q].title = series[q].full;
      series[q].colour = R.theme.SERIES[q] || null;
      byKey.set(series[q].key, series[q]);
    }
    // The model's x ends and the size range are over the rows drawn of EVERY series (D4, D5): the model never sees the
    // hidden list, so hiding a series moves neither.
    var xlo = null, xhi = null, zlo = null, zhi = null;
    for (var r = 0; r < rows.length; r++) {
      var w = rows[r];
      w.series = byKey.get(w.key).id;
      if (xlo === null || w.x < xlo) xlo = w.x;
      if (xhi === null || w.x > xhi) xhi = w.x;
      if (Z) {
        if (zlo === null || w.z < zlo) zlo = w.z;
        if (zhi === null || w.z > zhi) zhi = w.z;
      }
    }
    if (xlo === null) {
      xlo = logX ? 1 : 0;
      xhi = logX ? 10 : 1;
    }
    var gran = temporal ? R.time.granularity(x.format, anyTime) : null;
    var xd = temporal ? R.time.domain(xlo, xhi, gran) : [xlo, xhi];
    var range = size && Array.isArray(size.range) ? size.range : RANGE, largest = 0;
    for (var s = 0; s < rows.length; s++) {
      rows[s].d = Z ? diameter(rows[s].z, zlo, zhi, range[0], range[1]) : null;
      if (Z && rows[s].d > largest) largest = rows[s].d;
    }
    // Colour by value (D7): a point's shade value is the document's aggregate over its ONE row, its null left out first
    // (the bar's rule), and the scale is computed over the rows drawn.
    var shades = shade ? rows.map(function (w2) {
      return D.aggregate(D.isNull(SH[w2.row]) ? [] : [SH[w2.row]], shade.aggregate, shade.field);
    }) : null;
    var scale = shade ? R.colour.of(shade, shades) : null;
    var zero = scaleY.zero === true;
    var m = {
      series: series,
      rows: rows,
      markCount: rows.length,
      warnings: warnings,
      legend: color ? color.legend : undefined,
      legendKey: "legendKeyPoint",
      shade: shade ? { scale: scale, legend: shade.legend, title: shade.field } : null,
      enc: { x: x, y: y, size: size, shade: shade, color: color },
      gran: gran,
      shades: shades,
      sized: Z !== null,
      largest: largest,
      tips: tips,
      tipCols: tipCols,
      types: types,
      layout: {
        vertical: true,
        bands: [],
        x: { kind: temporal ? "time" : logX ? "log" : "linear", lo: xd[0], hi: xd[1], gran: gran, format: x.format, domain: scaleX.domain || null, zero: scaleX.zero === true },
        log: logY,
        bandTitle: axisTitle(x),
        valueTitle: axisTitle(y),
        bandGrid: x.axis ? x.axis.grid : undefined,
        valueGrid: y.axis ? y.axis.grid : undefined,
        format: y.format,
        domain: scaleY.domain || null,
        endLabels: null,
        // The room a mark needs inside the plot's edge (#1066): the largest radius of the draw plus its ring.
        pad: function (h) {
          return (Z ? largest / 2 : h.theme.n("--chart-point")) + RING * h.theme.n("--chart-gap");
        },
        // The value axis is over the rows drawn of the SHOWN series (record §4.8: the value axis refits on a switch).
        extent: function (visible) {
          var lo = null, hi = null;
          for (var k = 0; k < rows.length; k++) {
            if (visible[rows[k].series] !== true) continue;
            if (lo === null || rows[k].y < lo) lo = rows[k].y;
            if (hi === null || rows[k].y > hi) hi = rows[k].y;
          }
          if (lo === null) return logY ? [null, null] : [0, 0];
          if (logY) return [lo, hi];
          return zero ? [Math.min(0, lo), Math.max(0, hi)] : [lo, hi];
        },
      },
    };
    return m;
  }

  /** A position's value on the card: a time by G9's full text, a number in the axis's format with its unit (G6). */
  function positionText(m, enc, raw, isX) {
    if (isX && m.gran !== null) return T.temporalFull(raw, m.gran);
    return L.withUnit(T.value(raw, enc.format), enc.unit);
  }

  /** An aggregate {num, exact} by the one number rule, from its exact text where there is one; else the empty text. */
  function aggregateText(a) {
    return a.exact !== null ? T.value(a.exact) : a.num !== null && finite(a.num) ? T.value(a.num) : "";
  }

  /** The card of row w (D9): heading, then x, y (kept, the point's colour), size, shade; the tooltip lines as extra. */
  function card(m, w, index, fill, css, title) {
    var enc = m.enc, rows = [
      { name: rowName(enc.x), value: positionText(m, enc.x, w.xRaw, true) },
      { name: rowName(enc.y), value: positionText(m, enc.y, w.yRaw, false), colour: fill, color: css },
    ];
    if (enc.size) rows.push({ name: enc.size.field, value: T.value(w.zRaw) });
    if (enc.shade) rows.push({ name: enc.shade.field, value: aggregateText(m.shades[index]) });
    var extra = [];
    for (var d = 0; d < m.tips.length; d++) extra.push(L.detailLine(m.tips[d], m.tipCols[d][w.row], m.types));
    return { heading: enc.color ? title : null, rows: rows, keep: 1, extra: extra };
  }

  /**
   * The marks (D5, D9): one circle per row drawn of a shown series, its centre at the two scales' places of its values
   * clamped to the axes' ends (D4), filled with its series' colour or its shade's step, ringed in the canvas colour.
   * Every radius and the ring are scaled by the distance the frame kept (hand.pad), so no circle crosses the plot's
   * edge. With size the larger marks are drawn first. The lookup holds one entry per mark, in the rows' order; a mark's
   * key row (brief 1052 D8) is its series' position, as the line's keys walk one series per row: Right and Left walk a
   * series' points in the data's order, Down and Up reach the next or previous series.
   */
  function marks(m, hand, h) {
    var theme = h.theme, titles = new Map(), colours = new Map(), keyRows = new Map();
    for (var s = 0; s < m.series.length; s++) {
      titles.set(m.series[s].id, m.series[s].title);
      colours.set(m.series[s].id, m.series[s].colour);
      keyRows.set(m.series[s].id, s);
    }
    var asked = m.layout.pad(h), kept = hand.pad;
    var k = kept && asked > 0 ? Math.min(1, kept.x / asked, kept.y / asked) : 1;
    var ring = RING * theme.n("--chart-gap") * k, point = theme.n("--chart-point");
    var xs = hand.x, vs = hand.value, drawn = [], lookup = [];
    for (var i = 0; i < m.rows.length; i++) {
      var w = m.rows[i];
      if (hand.visible[w.series] !== true) continue;
      var fill = m.shade ? R.colour.token(m.shade.scale, m.shades[i].num) : colours.get(w.series), css = theme.css(fill);
      var cx = r2(xs.scale(clamp(w.x, xs.lo, xs.hi))), cy = r2(vs.scale(clamp(w.y, vs.lo, vs.hi)));
      var r = r2((m.sized ? w.d / 2 : point) * k);
      drawn.push({ at: drawn.length, d: m.sized ? w.d : 0, prim: P.circle({ role: "mark", cx: cx, cy: cy, r: r, fill: fill, color: css, stroke: "--chart-canvas", strokeColor: theme.css("--chart-canvas"), strokeWidth: ring, series: w.series, datum: w.row }) });
      lookup.push({ series: w.series, datum: w.row, shape: { kind: "point", x: cx, y: cy, r: r }, row: keyRows.get(w.series), key: true, near: true, emphasis: "ring", card: card(m, w, i, fill, css, titles.get(w.series)) });
    }
    if (m.sized) {
      drawn.sort(function (a, b) {
        return b.d - a.d || a.at - b.at;
      });
    }
    return {
      primitives: drawn.map(function (q) {
        return q.prim;
      }),
      lookup: lookup,
      count: lookup.length,
    };
  }

  R.type("scatter", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
