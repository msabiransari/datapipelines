// types/waterfall.js — running changes and a closing total (#1073; record §4.5, look contract §4.3 and §4.7).
// The cartesian frame owns axes and chrome; this type supplies bars, connectors, value labels and shape lookup.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, L = R.lines, look = R.tables.look, r2 = R.scales.r2;
  var CHANNELS = [
    { key: "x", required: true, node: { node: "shape", name: "band" } },
    { key: "y", required: true, node: { node: "shape", name: "value" } },
  ];
  var TOKENS = { opening: "--chart-total", total: "--chart-total", rise: "--chart-positive", fall: "--chart-negative" };

  function finite(n) { return typeof n === "number" && n - n === 0; }
  function title(enc, fallback) { return enc.title === null ? null : enc.title === undefined ? fallback : enc.title; }
  function clamp(n, lo, hi) { return n < lo ? lo : n > hi ? hi : n; }
  function text(n, enc) { return L.withUnit(T.value(n, enc.format), enc.unit); }

  /** One category is one aggregate; null categories warn once, null measures leave a gap, zero remains a step. */
  function stepsOf(columns, band, value, types, warn) {
    var X = D.column(columns, band.field), Y = value.field ? D.column(columns, value.field) : null;
    var categories = new Map();
    for (var i = 0; i < X.length; i++) {
      if (D.isNull(X[i])) { warn("chart.rule.null-category"); continue; }
      var key = D.keyOf(X[i]), c = categories.get(key);
      if (c === undefined) {
        c = { key: key, raw: X[i], first: categories.size, values: [] };
        c.full = band.type === "temporal" ? T.temporalFull(X[i], band.format) : String(X[i]);
        categories.set(key, c);
      }
      c.values.push(Y ? Y[i] : 1);
    }
    var ordered = D.order(Array.from(categories.values()), band.sort, function () { return null; }, types[band.field], band.type === "temporal");
    var steps = [];
    for (var j = 0; j < ordered.length; j++) {
      var a = D.aggregate(ordered[j].values, value.aggregate, value.field || "count");
      if (a.num === null) continue;
      if (!finite(a.num)) { warn("chart.rule.non-finite"); continue; }
      steps.push({ category: ordered[j], num: a.num, exact: a.exact });
    }
    return steps;
  }

  /** Levels follow document order; overflow ends the walk and suppresses the total. Exact sums keep their text. */
  function barsOf(steps, total, value, warn) {
    var bars = [], level = 0, parts = [], allExact = true, cut = false, running = null;
    for (var i = 0; i < steps.length; i++) {
      var s = steps[i], to = level + s.num;
      if (!finite(to)) { warn("chart.rule.non-finite"); cut = true; break; }
      parts.push(s.exact);
      if (s.exact === null) allExact = false;
      running = allExact ? D.aggregate(parts, "sum", value.field || "count").exact : null;
      bars.push({ kind: i === 0 ? "opening" : s.num >= 0 ? "rise" : "fall", cat: s.category,
        name: s.category.full, from: level, to: to, num: s.num, exact: s.exact,
        text: text(s.exact !== null ? s.exact : s.num, value), run: text(running !== null ? running : to, value) });
      level = to;
    }
    if (total === "end" && bars.length > 0 && !cut) {
      bars.push({ kind: "total", cat: null, name: look("waterfallTotalText"), from: 0, to: level, num: level,
        exact: running, text: text(running !== null ? running : level, value), run: null });
    }
    // ONE formatted label feeds both the frame's end room and the drawing, including exact half-even rounding.
    for (var j = 0; j < bars.length; j++) {
      var b = bars[j];
      b.label = L.withUnit(T.value(b.exact !== null ? b.exact : b.num, look("stackTotalFormat")), value.unit);
    }
    return bars;
  }

  function model(doc, columns, types) {
    var enc = doc.config.encoding, mark = doc.config.mark, warnings = [], seen = {};
    function warn(code) { if (seen[code] !== true) { seen[code] = true; warnings.push({ code: code }); } }
    var steps = stepsOf(columns, enc.x, enc.y, types || {}, warn);
    var total = mark.total === undefined ? look("waterfallTotalDefault") : mark.total;
    var count = steps.length + (total === "end" && steps.length > 0 ? 1 : 0);
    // The semantic cap counts steps BEFORE the overflow cut and includes the requested total.
    var bars = count > R.tables.cap("waterfall.marks") ? [] : barsOf(steps, total, enc.y, warn);
    var labelsOn = (mark.labels === undefined ? look("labelsDefault") : mark.labels) === "values";
    var agg = enc.y.aggregate, defaultTitle = enc.y.field ? agg.charAt(0).toUpperCase() + agg.slice(1) + " of " + enc.y.field : "Count";
    var valueTitle = title(enc.y, defaultTitle);
    if (valueTitle !== null && typeof enc.y.unit === "string" && enc.y.unit !== "") valueTitle += " (" + enc.y.unit + ")";
    return {
      series: [], legend: undefined, keysAlone: false, markCount: count, warnings: warnings, bars: bars, labelsOn: labelsOn,
      keys: [
        { id: "rise", title: look("waterfallRiseText"), key: "legendKeyBar", colour: "--chart-positive" },
        { id: "fall", title: look("waterfallFallText"), key: "legendKeyBar", colour: "--chart-negative" },
        { id: "total", title: look("waterfallKeyTotalText"), key: "legendKeyBar", colour: "--chart-total" },
      ],
      layout: {
        vertical: true, log: false,
        bands: bars.map(function (b) { return b.cat !== null && enc.x.type === "temporal" ? T.temporalAxis(b.cat.raw, enc.x.format) : { text: b.name, under: null }; }),
        bandTitle: title(enc.x, enc.x.field), valueTitle: valueTitle,
        bandGrid: enc.x.axis ? enc.x.axis.grid : undefined, valueGrid: enc.y.axis ? enc.y.axis.grid : undefined,
        format: enc.y.format, domain: enc.y.scale && enc.y.scale.domain ? enc.y.scale.domain : null,
        endLabels: labelsOn ? bars.map(function (b) { return b.label; }) : null,
        extent: function () {
          var lo = 0, hi = 0;
          for (var i = 0; i < bars.length; i++) { lo = Math.min(lo, bars[i].from, bars[i].to); hi = Math.max(hi, bars[i].from, bars[i].to); }
          return [lo, hi];
        },
      },
    };
  }

  /** Same end rounding as bar: the radius never exceeds the length of this bar, including a thin change. */
  function bar(x, y, w, h, up, radius, token, css, datum) {
    if (!(radius > 0) || w <= 0 || h <= 0) return P.rect({ role: "mark", x: x, y: y, w: w, h: h, fill: token, color: css, series: null, datum: datum });
    var d = up ? ["M", x, y + h, "L", x, y + radius, "A", radius, x + radius, y, "L", x + w - radius, y, "A", radius, x + w, y + radius, "L", x + w, y + h, "Z"]
      : ["M", x + w, y, "L", x + w, y + h - radius, "A", radius, x + w - radius, y + h, "L", x + radius, y + h, "A", radius, x, y + h - radius, "L", x, y, "Z"];
    return P.path({ role: "mark", d: d, fill: token, color: css, series: null, datum: datum });
  }

  function card(b, token, css) {
    var rows;
    if (b.kind === "opening" || b.kind === "total") {
      rows = [{ name: b.kind === "opening" ? look("waterfallOpeningText") : look("waterfallTotalText"), value: b.text, colour: token, color: css }];
    } else {
      rows = [{ name: b.kind === "rise" ? look("waterfallRiseText") : look("waterfallFallText"), value: b.text, colour: token, color: css },
        { name: look("waterfallRunningText"), value: b.run, colour: null, color: null }];
    }
    return { heading: b.name, rows: rows, keep: 0 };
  }

  /** A label must fit its bar plus the slack, the plot, and the space left by every earlier label. */
  function label(b, x, y, bw, up, plot, placed, h) {
    var size = h.small, width = h.width(b.label, size), height = h.boxH(size);
    if (width > bw + look("waterfallLabelSlack") + 1e-9) return null;
    var cx = x + bw / 2, top = up ? y - look("valueLabelGap") * size - height : y + look("valueLabelGap") * size;
    var box = [cx - width / 2, top, cx + width / 2, top + height];
    if (box[0] < plot.x || box[1] < plot.y || box[2] > plot.x + plot.w || box[3] > plot.y + plot.h) return null;
    for (var i = 0; i < placed.length; i++) {
      var p = placed[i];
      if (box[0] < p[2] && p[0] < box[2] && box[1] < p[3] && p[1] < box[3]) return null;
    }
    placed.push(box);
    var strong = b.kind === "opening" || b.kind === "total";
    return h.label({ role: "guide", x: cx, y: top + h.ascent(size), text: b.label, anchor: "middle", size: size,
      weight: strong ? h.titleWeight : undefined, ink: strong ? "--chart-ink" : "--chart-ink-2" });
  }

  function marks(m, hand, h) {
    var out = [], connectors = [], labels = [], lookup = [], placed = [];
    var step = hand.band.step, bw = step * look("barShare"), scale = hand.value.scale;
    for (var i = 0; i < m.bars.length; i++) {
      var b = m.bars[i], x = hand.band.start + step * i + (step - bw) / 2;
      var p0 = scale(clamp(b.from, hand.value.lo, hand.value.hi)), p1 = scale(clamp(b.to, hand.value.lo, hand.value.hi));
      var y = Math.min(p0, p1), length = Math.abs(p1 - p0), up = b.to >= b.from;
      var token = TOKENS[b.kind], css = h.theme.css(token);
      var radius = Math.min(h.theme.n("--chart-bar-radius"), bw * look("radiusMaxShare"), length);
      out.push(bar(x, y, bw, length, up, radius, token, css, i));
      var hit = Math.max(length, look("waterfallHitMin"));
      lookup.push({ series: null, datum: i, row: 0, key: true, near: false, emphasis: "ring",
        shape: { kind: "box", x: r2(x), y: r2(y - (hit - length) / 2), w: r2(bw), h: r2(hit) }, card: card(b, token, css) });
      if (i + 1 < m.bars.length) connectors.push(h.rule("guide", "--chart-axis", x + bw, p1, x + step, p1));
      if (m.labelsOn && hand.level !== "minimal") {
        var l = label(b, x, up ? y : y + length, bw, up, hand.plot, placed, h);
        if (l !== null) labels.push(l);
      }
    }
    // The final occupied bar allocation (owner 2026-11-24, #1117): each step and shown total's width, groups = 1.
    if (m.bars.length > 0) hand.density = [{ axis: "x", categories: m.bars.length, groups: 1, width: bw }];
    return { primitives: out.concat(connectors, labels), lookup: lookup, count: lookup.length };
  }
  R.type("waterfall", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
