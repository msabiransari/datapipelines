// types/pie.js : parts of a whole, clockwise from noon (record §4.5; brief 1068 D1-D13).
// Geometry uses doubles; every aggregate's exact decimal text survives into labels, the total and the card.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, TR = R.trig, r2 = R.scales.r2, look = R.tables.look;
  // Numeric limits of the path: below this angle a rounded arc can degenerate; the inner cubic spans at most 60°.
  var MIN_ANGLE = 0.02, INNER_PIECE = 60;
  var CHANNELS = [
    { key: "theta", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: true, node: { node: "shape", name: "series" } },
  ];

  function partOf(s) { return s.exact !== null ? s.exact : s.num; }

  /** Aggregate each category, refuse negative parts, fold the smallest first, and decide overflow before visibility. */
  function model(doc, columns, types) {
    var enc = doc.config.encoding, theta = enc.theta, mark = doc.config.mark;
    var names = D.column(columns, enc.color.field), values = theta.aggregate === "count" ? null : D.column(columns, theta.field);
    var groups = new Map(), warnings = [], nullCategory = false, nonFinite = false, rows = D.rowCount(columns);
    for (var i = 0; i < rows; i++) {
      if (D.isNull(names[i])) { nullCategory = true; continue; }
      var id = D.keyOf(names[i]);
      if (!groups.has(id)) groups.set(id, { id: id, key: id, raw: names[i], first: groups.size, vals: [] });
      groups.get(id).vals.push(values ? values[i] : 1);
    }
    if (nullCategory) warnings.push({ code: "chart.rule.null-category" });
    var ordered = D.order(Array.from(groups.values()), enc.color.sort, function () { return null; }, types[enc.color.field], false);
    var slices = [];
    for (var g = 0; g < ordered.length; g++) {
      var group = ordered[g], a = D.aggregate(group.vals, theta.aggregate, theta.field);
      if (a.num === null) continue;
      if (!isFinite(a.num)) { nonFinite = true; continue; }
      if (a.num < 0) return { rule: { code: "chart.rule.negative-part", details: {} } };
      slices.push({ id: group.id, title: String(group.raw), num: a.num, exact: a.exact });
    }
    var limit = mark.max_slices === undefined ? 8 : mark.max_slices;
    if (slices.length > limit) {
      var ranked = slices.map(function (s, at) { return { num: s.num, at: at }; });
      ranked.sort(function (a, b) { return b.num - a.num || a.at - b.at; });
      var keep = new Set(ranked.slice(0, limit - 1).map(function (s) { return s.at; }));
      var parts = [], kept = [];
      for (var k = 0; k < slices.length; k++) {
        if (keep.has(k)) kept.push(slices[k]);
        else parts.push(partOf(slices[k]));
      }
      var other = D.aggregate(parts, "sum", theta.field);
      if (other.num !== null && isFinite(other.num)) kept.push({ id: "other", title: look("pieOtherText"), num: other.num, exact: other.exact });
      else nonFinite = true;
      slices = kept;
    }
    var total = 0;
    for (var j = 0; j < slices.length; j++) total += slices[j].num;
    if (!isFinite(total)) { slices = []; nonFinite = true; }
    if (nonFinite) warnings.push({ code: "chart.rule.non-finite" });
    return {
      slices: slices,
      series: slices.map(function (s, at) { return { id: s.id, title: s.title, colour: R.theme.SERIES[at] }; }),
      markCount: slices.filter(function (s) { return s.num > 0; }).length,
      warnings: warnings, legend: enc.color.legend, theta: theta, mark: mark,
      title: doc.config.title && doc.config.title.text ? doc.config.title.text : null,
    };
  }

  /** The inner boundary runs backwards with cubic pieces, using only the shared deterministic trig. */
  function innerBack(cx, cy, r, from, to) {
    var pieces = Math.max(1, Math.ceil((to - from) / INNER_PIECE - 1e-9)), step = (to - from) / pieces;
    var half = step / 2, control = (4 / 3) * ((1 - TR.cos(half)) / TR.sin(half)) * r, out = [];
    for (var j = 0; j < pieces; j++) {
      var a = to - step * j, b = a - step, pa = TR.point(cx, cy, r, a), pb = TR.point(cx, cy, r, b);
      out.push("C", r2(pa.x - control * TR.cos(a)), r2(pa.y - control * TR.sin(a)),
        r2(pb.x + control * TR.cos(b)), r2(pb.y + control * TR.sin(b)), pb.x, pb.y);
    }
    return out;
  }

  function slicePath(cx, cy, r0, r1, from, to) {
    var p = TR.point(cx, cy, r1, from), out = ["M", p.x, p.y].concat(TR.arc(cx, cy, r1, from, to));
    if (r0 === 0) return out.concat(["L", cx, cy, "Z"]);
    var q = TR.point(cx, cy, r0, to);
    return out.concat(["L", q.x, q.y], innerBack(cx, cy, r0, from, to), ["Z"]);
  }

  /** A corner clears both circular edges and the wedge's straight edges by pad, without an inverse trig call. */
  function inSlice(px, py, shape, pad) {
    var dx = px - shape.cx, dy = py - shape.cy, radius = Math.sqrt(dx * dx + dy * dy);
    if (radius < shape.r0 + pad || radius > shape.r1 - pad) return false;
    if (shape.to - shape.from >= 360) return true;
    var first = TR.sin(shape.from) * dy + TR.cos(shape.from) * dx;
    var last = -dx * TR.cos(shape.to) - dy * TR.sin(shape.to);
    return shape.to - shape.from <= 180 ? first >= pad && last >= pad : first >= pad || last >= pad;
  }

  function valueText(s, theta) { return R.lines.withUnit(T.value(partOf(s), theta.format), theta.unit); }

  /** Fit the label against its sector at rest AND while the sector is moved out under the fixed label. */
  function sliceLabel(s, shape, token, share, labels, h, move) {
    var text = labels === "percent" ? T.percent(share, 0) : valueText(s, h.theta);
    if (labels === "percent" && text === "0%") return null;
    var middle = (shape.from + shape.to) / 2;
    var radius = shape.r0 > 0 ? (shape.r0 + shape.r1) / 2 : shape.r1 * look("pieLabelRadius");
    var at = TR.point(shape.cx, shape.cy, radius, middle), width = h.width(text, h.small, h.titleWeight), height = h.boxH(h.small);
    var mx = TR.sin(middle) * move, my = -TR.cos(middle) * move, pad = look("sliceLabelPad");
    for (var x = -1; x <= 1; x += 2) for (var y = -1; y <= 1; y += 2) {
      var px = at.x + x * width / 2, py = at.y + y * height / 2;
      if (!inSlice(px, py, shape, pad) || !inSlice(px - mx, py - my, shape, pad)) return null;
    }
    var theme = h.theme, ink = R.theme.onFill(theme.colour(token), theme.colour("--chart-on-fill-light"), theme.colour("--chart-on-fill-dark"));
    return h.label({ role: "mark", x: at.x, y: h.middle(at.y, h.small), text: text, anchor: "middle", size: h.small, weight: h.titleWeight, ink: ink, series: s.id });
  }

  /** Fit the complete text block in the hole; drop the unit first, then the total. */
  function hole(m, shown, cx, cy, r0, h, gap) {
    var sum = D.aggregate(shown.map(partOf), "sum", m.theta.field);
    var text = T.value(sum.exact !== null ? sum.exact : sum.num, m.theta.format), unit = m.theta.unit || null;
    var width = h.width(text, h.titleSize, h.titleWeight), totalH = h.boxH(h.titleSize), unitH = h.boxH(h.text), unitGap = look("holeUnitGap");
    function fits(w, height) { return Math.sqrt(w * w / 4 + height * height / 4) <= r0 - gap; }
    var bothH = totalH + unitGap + unitH;
    var showUnit = unit !== null && fits(Math.max(width, h.width(unit, h.text, 400)), bothH);
    var height = showUnit ? bothH : totalH;
    if (!fits(width, height)) return [];
    var top = cy - height / 2, out = [h.label({ role: "mark", x: cx, y: top + h.ascent(h.titleSize), text: text, anchor: "middle", size: h.titleSize, weight: h.titleWeight, ink: "--chart-ink" })];
    if (showUnit) out.push(h.label({ role: "mark", x: cx, y: top + totalH + unitGap + h.ascent(h.text), text: unit, anchor: "middle", size: h.text, ink: "--chart-ink-2" }));
    return out;
  }

  /** Shown values decide text; drawn values decide angles. Hidden slices retain their original colour slot. */
  function marks(m, hand, h) {
    var theme = h.theme, gap = theme.n("--chart-gap"), move = look("pieMoveAllowance");
    var cx = hand.centre.x, cy = hand.centre.y, r1 = r2(hand.radius - Math.max(0, move + gap / 2 - h.em("edge")));
    var inner = m.mark.inner === undefined ? look("pieInnerDefault") : m.mark.inner, r0 = r2(r1 * inner);
    var labels = m.mark.labels === undefined ? look("pieLabelsDefault") : m.mark.labels;
    var shown = m.slices.filter(function (s) { return hand.visible[s.id] === true && s.num > 0; }), total = 0;
    for (var j = 0; j < shown.length; j++) total += shown[j].num;
    var drawn = shown.filter(function (s) { return s.num / total * 360 >= MIN_ANGLE; }), drawnTotal = 0;
    for (var d = 0; d < drawn.length; d++) drawnTotal += drawn[d].num;
    if (drawn.length === 0 || r1 <= 0) return { primitives: [], lookup: [], count: 0 };
    var colours = new Map();
    m.series.forEach(function (s) { colours.set(s.id, s.colour); });
    var primitives = [], lookup = [], from = 0;
    for (var i = 0; i < drawn.length; i++) {
      var s = drawn[i], to = i === drawn.length - 1 ? 360 : from + s.num / drawnTotal * 360;
      var shape = { kind: "sector", cx: cx, cy: cy, r0: r0, r1: r1, from: r2(from), to: r2(to) };
      var token = colours.get(s.id), css = theme.css(token), share = s.num / total;
      var children = [P.path({ role: "mark", d: slicePath(cx, cy, r0, r1, shape.from, shape.to), fill: token, color: css, stroke: "--chart-canvas", strokeColor: theme.css("--chart-canvas"), strokeWidth: gap, round: true, series: s.id, datum: s.id })];
      if (labels !== "none" && hand.level !== "minimal") {
        var label = sliceLabel(s, shape, token, share, labels, Object.assign({}, h, { theta: m.theta }), move);
        if (label) children.push(label);
      }
      P.group(primitives, { role: "mark", series: s.id, label: s.title }, children);
      lookup.push({ series: s.id, datum: s.id, row: 0, shape: shape, emphasis: "move", card: {
        heading: m.title, rows: [{ name: s.title, value: valueText(s, m.theta) + " (" + T.value(share, "percent") + ")", colour: token, color: css }], keep: 0,
      } });
      from = to;
    }
    if (r0 > look("holeTotalMin") && hand.level !== "minimal") primitives = primitives.concat(hole(m, shown, cx, cy, r0, h, gap));
    return { primitives: primitives, lookup: lookup, count: lookup.length };
  }

  R.type("pie", { channels: CHANNELS, model: model, marks: marks, axes: "polar" });
})(JsRendered);
