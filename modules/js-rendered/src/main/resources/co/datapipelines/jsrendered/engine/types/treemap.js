// types/treemap.js : nested squarified leaf cells (record §4.5, look contract §4.7; brief 1078 D1–D14).
// The none frame owns chrome; this template owns cells, whole labels and box lookup entries. Positive leaves
// survive even when their one-pixel minimum consumes the gap. Hidden ids cannot change this key-only legend.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, r2 = R.scales.r2, look = R.tables.look;
  var CHANNELS = [
    { key: "path", required: true, node: { node: "list", min: 1, max: 3, of: { node: "shape", name: "level" } } },
    { key: "size", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: false, node: { node: "oneOf", of: [{ shape: "series", types: ["nominal", "ordinal"] }, { shape: "shade", types: ["quantitative"] }] } },
  ];

  function finite(n) { return typeof n === "number" && n - n === 0; }
  function nameOf(leaf) { return String(leaf.values[leaf.values.length - 1]); }
  function inKeyOrder(leaves) {
    return leaves.slice().sort(function (a, b) { return D.cmpCodePoint(a.key, b.key); });
  }
  // D4: floating sums/means have one order independent of row arrival. Exact texts keep the helper's exact path.
  function aggregate(values, spec) {
    var xs = values.filter(function (v) { return !D.isNull(v); });
    if ((spec.aggregate === "sum" || spec.aggregate === "mean") && xs.every(function (v) { return typeof v === "number"; })) {
      xs.sort(function (a, b) { return Math.abs(b) - Math.abs(a) || a - b; });
    }
    return D.aggregate(spec.aggregate === "count" ? values : xs, spec.aggregate, spec.field);
  }

  function model(doc, columns, types) {
    var e = doc.config.encoding, size = e.size, given = e.color || null;
    var shade = given && given.type === "quantitative" ? given : null, color = shade ? null : given;
    var paths = e.path.map(function (p) { return D.column(columns, p.field); });
    var V = size.aggregate === "count" ? null : D.column(columns, size.field);
    var C = color ? D.column(columns, color.field) : null, S = shade ? D.column(columns, shade.field) : null;
    var groups = new Map(), leaves = new Map(), warnings = [], nulls = false;
    for (var i = 0; i < D.rowCount(columns); i++) {
      var values = paths.map(function (p) { return p[i]; });
      if (values.some(D.isNull) || (C && D.isNull(C[i]))) { nulls = true; continue; }
      var keys = values.map(D.keyOf), raw = C ? C[i] : values[0], group = D.keyOf(raw);
      var key = JSON.stringify(keys.concat(C ? [group] : []));
      if (!groups.has(group)) groups.set(group, { key: group, raw: raw, first: groups.size, title: String(raw) });
      if (!leaves.has(key)) leaves.set(key, { key: key, keys: keys, values: values, group: group, sizes: [], shades: [] });
      var leaf = leaves.get(key);
      leaf.sizes.push(V ? V[i] : 1);
      if (S) leaf.shades.push(S[i]);
    }
    // O-15 precedes aggregation: even groups whose measures are all null count towards the series cap.
    if (!shade && groups.size > R.tables.cap("series")) return { rule: { code: "chart.too_many_series", details: { series: groups.size, limit: R.tables.cap("series") } } };
    if (nulls) warnings.push({ code: "chart.rule.null-category" });
    var list = [], negative = false, nonfinite = false;
    leaves.forEach(function (leaf) {
      leaf.agg = aggregate(leaf.sizes, size);
      if (leaf.agg.num === null) return;
      if (!finite(leaf.agg.num)) { nonfinite = true; return; }
      if (leaf.agg.num < 0) negative = true;
      if (shade) leaf.shade = aggregate(leaf.shades, shade);
      list.push(leaf);
    });
    if (negative) return { rule: { code: "chart.rule.negative-part", details: {} } };
    if (nonfinite) warnings.push({ code: "chart.rule.non-finite" });
    var positive = list.filter(function (leaf) { return leaf.agg.num > 0; }), scale = null, series;
    if (shade) {
      series = [{ id: "cell", title: size.field || "count", colour: null }];
      scale = R.colour.of(shade, positive.map(function (leaf) { return leaf.shade; }));
    } else {
      series = D.order(Array.from(groups.values()), color ? color.sort : null, function () { return null; }, color && types ? types[color.field] : undefined, false)
        .map(function (g, ix) { return { id: g.key, title: g.title, colour: R.theme.SERIES[ix] }; });
    }
    var m = { series: series, leaves: list, markCount: positive.length, warnings: warnings, size: size, path: e.path,
      mark: doc.config.mark, legend: color ? color.legend : undefined,
      shade: shade ? { scale: scale, legend: shade.legend, title: shade.field } : null };
    if (!shade) {
      m.keys = series.map(function (s) { return { id: s.id, title: s.title, key: "legendKeyBar", colour: s.colour }; });
      m.keysAlone = false;
    }
    return m;
  }

  function byShare(a, b) {
    return (a.share > b.share ? -1 : a.share < b.share ? 1 : 0) || D.cmpCodePoint(a.title, b.title) || D.cmpCodePoint(a.key, b.key);
  }
  /** Squarified rows along the free rectangle's shorter side; largest first, ties by name then typed key. */
  function squarify(items, box) {
    var out = [], total = 0, x = box.x, y = box.y, w = box.w, hh = box.h;
    items = items.slice().sort(byShare);
    items.forEach(function (it) { total += it.share; });
    if (!(w > 0 && hh > 0 && total > 0)) return items.map(function (it) { return { item: it, x: x, y: y, w: 0, h: 0 }; });
    var scale = w * hh / total, at = 0;
    while (at < items.length) {
      var short = Math.min(w, hh), row = [], sum = 0, best = Infinity, mx = 0, mn = Infinity;
      while (at < items.length) {
        var area = items[at].share * scale, next = sum + area, high = Math.max(mx, area), low = Math.min(mn, area);
        var worst = Math.max(short * short * high / (next * next), next * next / (short * short * low));
        if (row.length && worst > best) break;
        row.push({ item: items[at++], area: area }); sum = next; mx = high; mn = low; best = worst;
      }
      var thick = short > 0 ? sum / short : 0, offset = 0;
      row.forEach(function (cell) {
        var length = thick > 0 ? cell.area / thick : 0;
        out.push(w >= hh ? { item: cell.item, x: x, y: y + offset, w: thick, h: length } : { item: cell.item, x: x + offset, y: y, w: length, h: thick });
        offset += length;
      });
      if (w >= hh) { x += thick; w = Math.max(0, w - thick); } else { y += thick; hh = Math.max(0, hh - thick); }
    }
    return out;
  }

  function layout(leaves, plot) {
    var root = { kids: new Map() }, flat = [];
    inKeyOrder(leaves).forEach(function (leaf) {
      var node = root;
      leaf.keys.forEach(function (key, depth) {
        if (!node.kids.has(key)) node.kids.set(key, { key: key, title: String(leaf.values[depth]), share: 0, kids: new Map(), leaves: [] });
        node = node.kids.get(key);
        node.share += leaf.share;
      });
      node.leaves.push(leaf);
    });
    function walk(node, box) {
      if (node.kids.size) {
        squarify(Array.from(node.kids.values()), box).forEach(function (cell) { walk(cell.item, cell); });
      } else if (node.leaves.length === 1) {
        flat.push({ leaf: node.leaves[0], box: box });
      } else {
        squarify(node.leaves.map(function (leaf) { return { key: leaf.key, title: nameOf(leaf), share: leaf.share, leaf: leaf }; }), box)
          .forEach(function (cell) { flat.push({ leaf: cell.item.leaf, box: cell }); });
      }
    }
    walk(root, plot);
    return flat;
  }

  /** Whole labels only, with the contract's room around both lines; no labels at minimal. */
  function labels(out, m, leaf, box, fill, hand, h, series) {
    var option = m.mark.labels === undefined ? look("treemapLabelsDefault") : m.mark.labels;
    if (option === "none" || hand.level === "minimal") return;
    var name = nameOf(leaf), roomX = look("treemapLabelRoomX"), roomY = look("treemapLabelRoomY");
    var line = look("lineHeight") * h.text, second = look("lineHeight") * h.small;
    if (name === "" || box.w < h.width(name, h.text, h.titleWeight) + roomX || box.h < line + roomY) return;
    var ink = fill === R.colour.NONE ? "--chart-ink" : R.theme.onFill(h.theme.colour(fill), h.theme.colour("--chart-on-fill-light"), h.theme.colour("--chart-on-fill-dark"));
    var x = box.x + roomX / 2, top = box.y + roomY / 2;
    out.push(h.label({ role: "mark", x: x, y: top + h.ascent(h.text), text: name, anchor: "start", size: h.text, weight: h.titleWeight, ink: ink, series: series }));
    var text = valueText(leaf, m);
    if (option === "names_values" && box.h >= line + second + look("treemapValueRoomY") && box.w >= h.width(text, h.small) + roomX) {
      out.push(h.label({ role: "mark", x: x, y: top + line + h.ascent(h.small), text: text, anchor: "start", size: h.small, weight: 400, ink: ink, series: series }));
    }
  }
  function valueText(leaf, m) {
    return R.lines.withUnit(T.value(leaf.agg.exact !== null ? leaf.agg.exact : leaf.agg.num, m.size.format), m.size.unit);
  }

  function marks(m, hand, h) {
    var leaves = m.leaves.filter(function (leaf) { return leaf.agg.num > 0; });
    var largest = 0, sum = 0, colours = new Map();
    leaves.forEach(function (leaf) { largest = Math.max(largest, leaf.agg.num); });
    leaves.forEach(function (leaf) { leaf.ratio = leaf.agg.num / largest; leaf.share = Math.max(leaf.ratio, 1e-12); });
    inKeyOrder(leaves).forEach(function (leaf) { sum += leaf.ratio; });
    m.series.forEach(function (s) { colours.set(s.id, s.colour); });
    var inset = h.theme.n("--chart-gap") / 2, least = look("treemapMinCell"), out = [], lookup = [];
    layout(leaves, hand.plot).forEach(function (cell, ix) {
      var leaf = cell.leaf, slot = cell.box, w0 = Math.max(0, slot.w - 2 * inset), h0 = Math.max(0, slot.h - 2 * inset);
      var w = Math.max(least, w0), hh = Math.max(least, h0);
      var box = { x: r2(w0 >= least ? slot.x + inset : slot.x + slot.w / 2 - w / 2), y: r2(h0 >= least ? slot.y + inset : slot.y + slot.h / 2 - hh / 2), w: r2(w), h: r2(hh) };
      var series = m.shade ? "cell" : leaf.group, fill = m.shade ? R.colour.token(m.shade.scale, leaf.shade.num) : colours.get(series), css = h.theme.css(fill);
      var kids = [P.rect({ role: "mark", x: box.x, y: box.y, w: box.w, h: box.h, fill: fill, color: css, rx: h.theme.n("--chart-cell-radius"), series: series, datum: ix })];
      labels(kids, m, leaf, box, fill, hand, h, series);
      P.group(out, { role: "mark", series: series, label: nameOf(leaf) }, kids);
      lookup.push({ series: series, datum: ix, row: 0, shape: { kind: "box", x: box.x, y: box.y, w: box.w, h: box.h }, emphasis: "ring",
        card: { heading: leaf.values.length > 1 ? leaf.values.slice(0, -1).map(String).join(look("treemapPathJoin")) : null,
          rows: [{ name: nameOf(leaf), value: valueText(leaf, m), colour: fill, color: css }, { name: look("treemapShareText"), value: leaf.ratio === 0 ? look("treemapTinyText") : T.value(leaf.ratio / sum, "percent") }], keep: 0 } });
    });
    return { primitives: out, lookup: lookup, count: lookup.length };
  }
  R.type("treemap", { channels: CHANNELS, model: model, marks: marks, axes: "none" });
})(JsRendered);
