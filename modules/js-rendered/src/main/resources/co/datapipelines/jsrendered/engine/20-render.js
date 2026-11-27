// 20-render.js : the ONE pure entry (record §4.3, R-PURE) and the type registry. render(input) → {version, type, state,
// level, primitives, index, summary}; input = {type, document, columns, types, frame: {w, h}, theme, measure, hidden}.
// No DOM, no clock, no random, no network, nothing kept between calls (the registry is filled once, at load, by the
// type files). The first row of §4.3's state table that applies decides: too-small, refused (the row cap BEFORE the
// model is entered, then a type's rule, then the semantic limits and the mark cap), no-data, rendered.
(function (R) {
  "use strict";
  var P = R.prim, C = R.chrome, D = R.data, look = R.tables.look, cap = R.tables.cap;
  var TYPES = new Map(), ORDER = [];
  var MEMBERS = { channels: true, model: true, marks: true, axes: true };
  var INPUT = { type: true, document: true, columns: true, types: true, frame: true, theme: true, measure: true, hidden: true };

  /** JsRendered.type(name, {channels, model, marks, axes}): a template registers itself (§4.3). */
  function type(name, def) {
    if (typeof name !== "string" || !/^[a-z][a-z0-9_]*$/.test(name)) throw new Error("type: a type name is lower-case letters, digits and _");
    if (TYPES.has(name)) throw new Error("type: " + name + " is already registered");
    if (def === null || typeof def !== "object") throw new Error("type: " + name + " takes {channels, model, marks, axes}");
    for (var k in def) if (Object.prototype.hasOwnProperty.call(def, k) && MEMBERS[k] !== true) throw new Error("type: " + name + ": unknown member " + k);
    for (var m in MEMBERS) if (!Object.prototype.hasOwnProperty.call(def, m)) throw new Error("type: " + name + ": missing member " + m);
    if (!Array.isArray(def.channels) || typeof def.model !== "function" || typeof def.marks !== "function") {
      throw new Error("type: " + name + ": channels is a list, model and marks are functions");
    }
    // axes names the type's frame: one the frame registry holds (18-frames.js: cartesian, polar, none, and a frame a
    // later file adds before its type).
    if (typeof def.axes !== "string" || R.frames.names().indexOf(def.axes) < 0) {
      throw new Error("type: " + name + ": axes " + def.axes + " is not a frame (" + R.frames.names().join(", ") + ")");
    }
    TYPES.set(name, { channels: JSON.stringify(def.channels), model: def.model, marks: def.marks, axes: def.axes });
    ORDER.push(name);
  }

  /** [{name, channels}] of every registered type, in load order (R-REG reads it in GraalJS). */
  function types() {
    return ORDER.map(function (n) {
      return { name: n, channels: JSON.parse(TYPES.get(n).channels) };
    });
  }

  var SENTENCES = {
    "chart.too_many_marks": function (d) {
      return "Too many marks to draw (" + d.marks + ", the limit is " + d.limit + "). Aggregate in the query.";
    },
    "chart.too_many_rows": function (d) {
      return "Too many rows to draw (" + d.rows + ", the limit is " + d.limit + "). Aggregate in the query.";
    },
    "chart.too_many_series": function (d) {
      return "Too many series to draw (" + d.series + ", the limit is " + d.limit + "). Group the smaller ones in the query.";
    },
    "chart.rule.negative-part": function () {
      return "This chart cannot show negative values.";
    },
  };

  /** The fixed sentence of a refusal {code, details} (§4.3), its numbers grouped as the engine writes numbers. */
  function sentence(refusal) {
    var f = SENTENCES[refusal.code];
    if (f === undefined) throw new Error("no sentence for " + refusal.code);
    var d = {};
    for (var k in refusal.details) if (Object.prototype.hasOwnProperty.call(refusal.details, k)) d[k] = R.text.value(refusal.details[k], "integer");
    return f(d);
  }

  function checkInput(input) {
    if (input === null || typeof input !== "object" || Array.isArray(input)) throw new Error("render: input is not an object");
    for (var k in input) if (Object.prototype.hasOwnProperty.call(input, k) && INPUT[k] !== true) throw new Error("render: unknown input member " + k);
    if (!TYPES.has(input.type)) throw new Error("render: no type " + input.type);
    var doc = input.document;
    if (!doc || typeof doc !== "object" || !doc.config || !doc.config.mark || doc.config.mark.type !== input.type) throw new Error("render: the document's mark.type is not " + input.type);
    var f = input.frame;
    if (!f || typeof f.w !== "number" || typeof f.h !== "number" || !(f.w >= 0 && f.h >= 0 && f.w < Infinity && f.h < Infinity)) throw new Error("render: frame is not {w, h} of finite numbers");
    var hidden = input.hidden === undefined || input.hidden === null ? [] : input.hidden;
    if (!Array.isArray(hidden)) throw new Error("render: hidden is not a list");
    for (var i = 0; i < hidden.length; i++) if (typeof hidden[i] !== "string") throw new Error("render: a hidden id is not text");
    var columns = input.columns === undefined || input.columns === null ? {} : input.columns;
    if (typeof columns !== "object" || Array.isArray(columns)) throw new Error("render: columns is not {field: [values]}");
    return { hidden: hidden, columns: columns };
  }

  function result(input, state, lvl) {
    return { version: R.version, type: input.type, state: state, level: lvl, primitives: [], index: { axis: null, plot: null, series: [] }, summary: { marks: 0, series: [], warnings: [], refusal: null } };
  }

  function refuse(out, code, details) {
    out.state = "refused";
    out.summary.refusal = { code: code, details: details };
    return out;
  }

  /**
   * The data index (§4.3): per series, its marks' centres along the band axis, sorted, with datum and tooltip lines. A
   * stacked bar's index adds `totals` after it is built (indexOf).
   */
  function index(axis, plot, series, visible, prims) {
    var by = new Map(), out = { axis: axis, plot: plot, series: [] };
    for (var s = 0; s < series.length; s++) {
      var e = { id: series[s].id, on: visible[series[s].id] === true, at: [], datum: [], tip: [] };
      by.set(series[s].id, []);
      out.series.push(e);
    }
    for (var i = 0; i < prims.length; i++) {
      var p = prims[i];
      if (p.role !== "mark" || p.k === "group" || p.k === "text" || p.series === null || !by.has(p.series)) continue;
      by.get(p.series).push({ at: centre(p, axis), datum: p.datum, tip: p.tip });
    }
    for (var j = 0; j < out.series.length; j++) {
      var list = by.get(out.series[j].id).sort(function (a, b) {
        return a.at - b.at;
      });
      for (var q = 0; q < list.length; q++) {
        out.series[j].at.push(list[q].at);
        out.series[j].datum.push(list[q].datum);
        out.series[j].tip.push(list[q].tip);
      }
    }
    return out;
  }

  function centre(p, axis) {
    if (p.k === "rect") return R.scales.r2(axis === "x" ? p.x + p.w / 2 : p.y + p.h / 2);
    if (p.k === "circle") return axis === "x" ? p.cx : p.cy;
    var lo = Infinity, hi = -Infinity, d = p.d, off = axis === "x" ? 0 : 1;
    for (var i = 0; i < d.length; i++) {
      var n = P.ARITY[d[i]];
      if (n === 0) continue;
      var v = d[i + n - 1 + off];
      if (v < lo) lo = v;
      if (v > hi) hi = v;
      i += n;
    }
    return R.scales.r2(lo / 2 + hi / 2);
  }

  /** The mark of one series nearest to pos along the index's axis, by binary search: {i, at, datum, tip} or null. */
  function nearest(ix, id, pos) {
    var e = null;
    for (var s = 0; s < ix.series.length; s++) if (ix.series[s].id === id) e = ix.series[s];
    if (e === null || e.at.length === 0) return null;
    var lo = 0, hi = e.at.length - 1;
    while (lo < hi) {
      var mid = (lo + hi) >>> 1;
      if (e.at[mid] < pos) lo = mid + 1;
      else hi = mid;
    }
    var i = lo > 0 && pos - e.at[lo - 1] <= e.at[lo] - pos ? lo - 1 : lo;
    return { i: i, at: e.at[i], datum: e.datum[i], tip: e.tip[i] };
  }

  function canvas(h, W, H) {
    return P.rect({ role: "guide", x: 0, y: 0, w: W, h: H, fill: "--chart-canvas", color: h.theme.css("--chart-canvas") });
  }

  /**
   * The one entry. Between the model and the result it holds no rule of its own about a frame, a legend or an index
   * (brief 1049 D2): the type's frame (R.frames.get(reg.axes)) says whether there is a title block and a legend, gives
   * the legend's room and lays itself out; legendOf decides the legend; indexOf builds the index.
   */
  function render(input) {
    var ok = checkInput(input), reg = TYPES.get(input.type), doc = input.document;
    var theme = R.theme.resolve(input.theme), W = input.frame.w, H = input.frame.h, lvl = C.level(W, H, theme);
    var out = result(input, "rendered", lvl);
    if (lvl === null) {
      out.state = "too-small";
      out.summary.warnings.push({ code: "chart.frame-too-small" });
      return out;
    }
    // The row cap before the model is entered (§4.9 "What bounds the work", 1): the type's own (`<type>.rows`, the line
    // family's measured cap, brief 989 D9) where the table holds one, else the one of every type.
    var rows = D.rowCount(ok.columns), rowCap = cap(Object.prototype.hasOwnProperty.call(R.tables.CAPS, input.type + ".rows") ? input.type + ".rows" : "rows");
    if (rows > rowCap) return refuse(out, "chart.too_many_rows", { rows: rows, limit: rowCap });
    var meter = R.measure.measurer(input.measure), h = C.helpers(theme, meter);
    var m = reg.model(doc, ok.columns, input.types);
    if (m.rule) return refuse(out, m.rule.code, m.rule.details || {});
    if (m.series.length > cap("series")) return refuse(out, "chart.too_many_series", { series: m.series.length, limit: cap("series") });
    var markCap = input.type + ".marks";
    if (Object.prototype.hasOwnProperty.call(R.tables.CAPS, markCap) && m.markCount > cap(markCap)) return refuse(out, "chart.too_many_marks", { marks: m.markCount, limit: cap(markCap) });
    for (var w = 0; w < m.warnings.length; w++) out.summary.warnings.push(m.warnings[w]);
    var series = m.series.map(function (s) {
      return { id: s.id, title: s.title, hidden: false };
    });
    out.summary.series = series;
    C.legendKeys(m, out.summary);
    var frame = R.frames.get(reg.axes), e = h.em("edge");
    // 1. The title block, when the frame takes one; a frame without it lays itself out from the frame's top.
    var t = frame.titles ? C.titles(h, doc, lvl, W, e) : { texts: [], bottom: 0 };
    var prims = [canvas(h, W, H)];
    // 2. The no-data state, under the same title block.
    if (rows === 0 || m.markCount === 0) {
      out.state = "no-data";
      var word = look("emptyText"), cy = t.bottom + (H - e - t.bottom) / 2;
      var shown = h.fit(word, h.text, 400, W - 2 * e);
      if (shown !== "") prims.push(h.label({ role: "empty", x: W / 2, y: h.middle(cy, h.text), text: word, shown: shown, anchor: "middle", size: h.text, ink: "--chart-muted" }));
      out.primitives = prims.concat(t.texts);
      return finish(out, meter);
    }
    // 3. The legend, when the frame takes one.
    var lg = frame.legend ? legendOf(reg, m, series, ok.hidden, lvl) : null;
    // 4. Which entries are drawn: a series is hidden in this draw when its id is on the list AND its entry is drawn
    // (look contract §4.2). The legend's layout depends on neither the frame's drawing nor the visibility (only on the
    // room the frame gives it), so it is laid out first and decides `visible`.
    if (lg !== null && lg.place !== "direct") lg.drawn = lg.layout(h, W - 2 * e, frame.room(h, lvl, m, lg.place, H - t.bottom - e)).drawn;
    // 5. Which series are visible.
    var visible = {};
    for (var v = 0; v < series.length; v++) {
      series[v].hidden = lg !== null && lg.drawn[series[v].id] === true && ok.hidden.indexOf(series[v].id) >= 0;
      if (!series[v].hidden) visible[series[v].id] = true;
    }
    // 6. The frame, the template's marks on what the frame hands them, then what the frame draws after them.
    var geo = frame.lay(h, W, H, lvl, t.bottom, m, series, visible, lg);
    for (var x = 0; x < series.length; x++) series[x].hidden = lg !== null && lg.drawn[series[x].id] === true && ok.hidden.indexOf(series[x].id) >= 0;
    var hand = geo.hand;
    hand.visible = visible;
    hand.level = lvl;
    // A template returns its mark primitives (bar), or {primitives, index, count, ends} (the line family): index = per
    // visible series every data point's {at, datum, tip} as lists, which the index holds, and count = their number,
    // which summary.marks is (§4.9: thinning never changes either); ends = [{id, y}] of each drawn series' last point.
    var drawn = reg.marks(m, hand, h);
    var marks = Array.isArray(drawn) ? drawn : drawn.primitives;
    var after = typeof geo.after === "function" ? geo.after(drawn) : [];
    // 7. The primitives in their order, then the index.
    out.primitives = prims.concat(geo.under, marks, geo.over, geo.axes, after, t.texts);
    var ix = indexOf(reg, m, geo, series, visible, drawn, marks);
    out.summary.marks = ix.marks;
    out.index = ix.index;
    return finish(out, meter);
  }

  /**
   * The legend of a draw: null, or {place, series, drawn, hiddenOf(id), colourOf(id), key, layout(h, boxW, boxH),
   * prims(h, lay, ox, oy, out)}. The ONE place a draw decides which legend it has (where: C.legendPlace, look contract
   * §4.2), in this order (brief 1054 D3):
   *   1. a model that hands a colour scale (m.shade = {scale, legend, title}, brief 1050 D5) gets the scale's legend
   *      (R.colour.legend, placed by R.colour.place): no type has both, `color` is a series or a shade;
   *   2. a model that hands `keys` (C.legendModel, brief 1054 D2) gets a legend of keys: no entry a switch, none for an
   *      empty list, placed by C.legendPlace with no hidden id and the model's keysAlone; its `drawn` is always empty,
   *      so no id on the hidden list hides a series;
   *   3. else the legend of its series, each entry's key the look field the series names (`legendKey`) or the model's.
   * A legend of series is laid out by C.legendLayout ({place, w, h, items, more, drawn} plus `empty`, nothing to draw)
   * and written by C.legendPrims. With the names on the lines (`direct`) every entry is drawn (line 4: no id of the
   * data is on the list, so every series is drawn). The scale's `drawn` is empty too, so it hides no series.
   */
  function legendOf(reg, m, series, hidden, lvl) {
    var lm = C.legendModel(m);
    if (m.shade) return R.colour.legend(m.shade.scale, { place: R.colour.place(lvl, m.shade.legend), label: m.shade.title });
    if (lm.keys !== null) return keysLegend(m, lm, lvl);
    var place = C.legendPlace(series, hidden, lvl, m.legend, m.direct === true);
    if (place === null) return null;
    var colourOf = new Map();
    for (var c = 0; c < m.series.length; c++) colourOf.set(m.series[c].id, m.series[c].colour);
    var lg = {
      place: place,
      series: series,
      drawn: {},
      hiddenOf: function (id) {
        return hidden.indexOf(id) >= 0;
      },
      colourOf: function (id) {
        return colourOf.get(id);
      },
      key: lm.keyOf,
      layout: function (h, boxW, boxH) {
        var lay = C.legendLayout(h, series, place, boxW, boxH);
        lay.empty = lay.items.length === 0 && lay.more === null;
        return lay;
      },
      prims: function (h, lay, ox, oy, out) {
        return C.legendPrims(h, lay, ox, oy, lg.hiddenOf, lg.colourOf, out, lg.key);
      },
    };
    if (place === "direct") for (var a = 0; a < series.length; a++) lg.drawn[series[a].id] = true;
    return lg;
  }

  /** The legend of keys (brief 1054 D3, D4): null for an empty list or where C.legendPlace gives none. */
  function keysLegend(m, lm, lvl) {
    var entries = lm.keys.map(function (e) {
      return { id: e.id, title: e.title };
    });
    if (entries.length === 0) return null;
    var place = C.legendPlace(entries, [], lvl, m.legend, false, lm.alone);
    if (place === null) return null;
    var colourOf = new Map(), keyOf = new Map();
    for (var k = 0; k < lm.keys.length; k++) {
      colourOf.set(lm.keys[k].id, lm.keys[k].colour);
      keyOf.set(lm.keys[k].id, lm.keys[k].key);
    }
    var lg = {
      place: place,
      series: entries,
      drawn: {},
      hiddenOf: function () {
        return false;
      },
      colourOf: function (id) {
        return colourOf.get(id);
      },
      key: function (id) {
        return keyOf.get(id);
      },
      layout: function (h, boxW, boxH) {
        var lay = C.legendLayout(h, entries, place, boxW, boxH);
        lay.empty = lay.items.length === 0 && lay.more === null;
        // No entry is a switch: the draw reads no hidden list (render hides a series only where lg.drawn holds it).
        lay.drawn = {};
        return lay;
      },
      prims: function (h, lay, ox, oy, out) {
        return C.legendPrims(h, lay, ox, oy, lg.hiddenOf, lg.colourOf, out, lg.key, true);
      },
    };
    return lg;
  }

  /**
   * The index of a draw and summary.marks: {index, marks}. The cartesian frame: a template that returned a list gets
   * `index` along the band axis (a stacked bar's adds `totals`), the line family pointIndex. Another frame has no band
   * axis: a template that returns {primitives, index, count} gets its own index; one that returns a list gets an index
   * that holds the plot, every series and no position.
   */
  function indexOf(reg, m, geo, series, visible, drawn, marks) {
    // A template of any frame that finds its marks by their own shape returns {primitives, lookup, count} (brief 1052
    // D2): the index's second form, 21-lookup.js. Every other result takes the path below, unchanged.
    if (!Array.isArray(drawn) && Object.prototype.hasOwnProperty.call(drawn, "lookup")) {
      if (Object.prototype.hasOwnProperty.call(drawn, "index")) throw new Error("render: a template returns lookup or index, not both");
      return { marks: drawn.count, index: R.lookup.index(geo.plot, series, visible, drawn.lookup, marks) };
    }
    if (Array.isArray(drawn)) {
      var count = 0;
      for (var p = 0; p < marks.length; p++) if (marks[p].role === "mark" && marks[p].k !== "group" && marks[p].k !== "text") count++;
      if (reg.axes !== "cartesian") return { marks: count, index: index(null, geo.plot, series, visible, []) };
      var ix = index(m.layout.vertical ? "x" : "y", geo.plot, series, visible, marks);
      // A stacked bar's index also carries `totals` (brief 990 r2 D1): by datum, the stack's total as the card's text,
      // or null. A model without the member (every other type, an unstacked or normalized bar) adds nothing.
      if (typeof m.totals === "function") ix.totals = m.totals(visible);
      return { marks: count, index: ix };
    }
    if (reg.axes !== "cartesian") return { marks: drawn.count, index: drawn.index };
    return { marks: drawn.count, index: pointIndex(geo.plot, series, visible, drawn.index) };
  }

  /**
   * The data index of the line family: per series every point's x (sorted by the template), datum and tip lines. drawn
   * = the template's [{series, at, datum, tip}] of the series it drew; a series it did not draw has empty lists. A
   * series whose marks are bars may add `spans` (spansOf): the bars' own boxes, which the interaction layer reads.
   */
  function pointIndex(plot, series, visible, drawn) {
    var by = new Map(), out = { axis: "x", plot: plot, series: [] };
    for (var d = 0; d < drawn.length; d++) by.set(drawn[d].series, drawn[d]);
    for (var s = 0; s < series.length; s++) {
      var q = by.get(series[s].id);
      var e = { id: series[s].id, on: visible[series[s].id] === true, at: q ? q.at : [], datum: q ? q.datum : [], tip: q ? q.tip : [] };
      if (q && q.spans !== undefined) e.spans = spansOf(q.spans, e.at.length, series[s].id);
      out.series.push(e);
    }
    return out;
  }

  /**
   * A bar layer's `spans` (#1101): one entry per mark, in the order of `at`, either null (a mark that is no bar) or the
   * bar's box as drawn, {x, w, foot, end}: x and w its left edge and width, foot the y where it starts (the zero line,
   * or the plot's edge when the scale does not hold zero), end the y of its value (the top of a bar that grows up, the
   * bottom of one that grows down). Checked whole before anything is kept; every number finite, w not below 0.
   */
  function spansOf(list, count, id) {
    if (!Array.isArray(list) || list.length !== count) throw new Error("render: series " + id + ": spans is not a list of one entry per mark");
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      if (b === null) {
        out.push(null);
        continue;
      }
      if (b === undefined || typeof b !== "object" || !(b.w >= 0)) throw new Error("render: series " + id + ": spans[" + i + "] is not null or a box with a width of 0 or more");
      var names = ["x", "w", "foot", "end"];
      for (var n = 0; n < 4; n++) if (typeof b[names[n]] !== "number" || b[names[n]] - b[names[n]] !== 0) throw new Error("render: series " + id + ": spans[" + i + "]." + names[n] + " is not a finite number");
      out.push({ x: b.x, w: b.w, foot: b.foot, end: b.end });
    }
    return out;
  }

  function finish(out, meter) {
    if (meter.missing > 0) out.summary.warnings.push({ code: "chart.text.width-missing" });
    return out;
  }

  R.type = type;
  R.types = types;
  R.render = render;
  R.sentence = sentence;
  R.nearest = nearest;
})(JsRendered);
