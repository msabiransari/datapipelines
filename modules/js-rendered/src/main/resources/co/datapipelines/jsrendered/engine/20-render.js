// 20-render.js : the ONE pure entry (record §4.3, R-PURE) and the type registry. render(input) → {version, type, state,
// level, primitives, index, summary}; input = {type, document, columns, types, frame: {w, h}, theme, measure, hidden}.
// No DOM, no clock, no random, no network, nothing kept between calls (the registry is filled once, at load, by the
// type files). The first row of §4.3's state table that applies decides: too-small, refused (the row cap BEFORE the
// model is entered, then a type's rule, then the semantic limits and the mark cap), no-data, rendered.
(function (R) {
  "use strict";
  var P = R.prim, C = R.chrome, D = R.data, look = R.tables.look, cap = R.tables.cap;
  var TYPES = new Map(), ORDER = [];
  var AXES = { cartesian: true, polar: true, none: true };
  var MEMBERS = { channels: true, model: true, marks: true, axes: true };
  var INPUT = { type: true, document: true, columns: true, types: true, frame: true, theme: true, measure: true, hidden: true };

  /** JsRendered.type(name, {channels, model, marks, axes}): a template registers itself (§4.3). */
  function type(name, def) {
    if (typeof name !== "string" || !/^[a-z][a-z0-9_]*$/.test(name)) throw new Error("type: a type name is lower-case letters, digits and _");
    if (TYPES.has(name)) throw new Error("type: " + name + " is already registered");
    if (def === null || typeof def !== "object") throw new Error("type: " + name + " takes {channels, model, marks, axes}");
    for (var k in def) if (Object.prototype.hasOwnProperty.call(def, k) && MEMBERS[k] !== true) throw new Error("type: " + name + ": unknown member " + k);
    for (var m in MEMBERS) if (!Object.prototype.hasOwnProperty.call(def, m)) throw new Error("type: " + name + ": missing member " + m);
    if (!Array.isArray(def.channels) || typeof def.model !== "function" || typeof def.marks !== "function" || AXES[def.axes] !== true) {
      throw new Error("type: " + name + ": channels is a list, model and marks are functions, axes is cartesian, polar or none");
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

  /** The data index (§4.3): per series, its marks' centres along the band axis, sorted, with datum and tooltip lines. */
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
      var n = d[i] === "M" || d[i] === "L" ? 2 : d[i] === "A" ? 3 : 0;
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

  function render(input) {
    var ok = checkInput(input), reg = TYPES.get(input.type), doc = input.document;
    var theme = R.theme.resolve(input.theme), W = input.frame.w, H = input.frame.h, lvl = C.level(W, H, theme);
    var out = result(input, "rendered", lvl);
    if (lvl === null) {
      out.state = "too-small";
      out.summary.warnings.push({ code: "chart.frame-too-small" });
      return out;
    }
    var rows = D.rowCount(ok.columns);
    if (rows > cap("rows")) return refuse(out, "chart.too_many_rows", { rows: rows, limit: cap("rows") });
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
    var t = C.titles(h, doc, lvl, W, h.em("edge"));
    var prims = [canvas(h, W, H)];
    if (rows === 0 || m.markCount === 0) {
      out.state = "no-data";
      var e = h.em("edge"), word = look("emptyText"), cy = t.bottom + (H - e - t.bottom) / 2;
      var shown = h.fit(word, h.text, 400, W - 2 * e);
      if (shown !== "") prims.push(h.label({ role: "empty", x: W / 2, y: h.middle(cy, h.text), text: word, shown: shown, anchor: "middle", size: h.text, ink: "--chart-muted" }));
      out.primitives = prims.concat(t.texts);
      return finish(out, meter);
    }
    if (reg.axes !== "cartesian") throw new Error("render: axes " + reg.axes + " are not drawn by this engine version");
    var place = C.legendPlace(series, ok.hidden, lvl, m.legend);
    var colourOf = new Map();
    for (var c = 0; c < m.series.length; c++) colourOf.set(m.series[c].id, m.series[c].colour);
    var lg = place === null ? null : {
      place: place,
      series: series,
      drawn: {},
      hiddenOf: function (id) {
        return ok.hidden.indexOf(id) >= 0;
      },
      colourOf: function (id) {
        return colourOf.get(id);
      },
    };
    // A series is hidden in this draw when its id is on the list AND its entry is drawn (look contract §4.2). The
    // legend's layout depends on neither the axes' drawing nor the visibility (only on the room the model's layout
    // part needs, R.cartesian.legendRoom), so it is laid out first and decides `visible`.
    var visible = {};
    if (lg !== null) {
      var probe = C.legendLayout(h, series, place, W - 2 * h.em("edge"), R.cartesian.legendRoom(h, lvl, m.layout, place, H - t.bottom - h.em("edge")));
      lg.drawn = probe.drawn;
    }
    for (var v = 0; v < series.length; v++) {
      series[v].hidden = lg !== null && lg.drawn[series[v].id] === true && ok.hidden.indexOf(series[v].id) >= 0;
      if (!series[v].hidden) visible[series[v].id] = true;
    }
    var geo = R.cartesian.cartesian(h, W, H, lvl, t.bottom, m.layout, visible, lg);
    for (var x = 0; x < series.length; x++) series[x].hidden = lg !== null && lg.drawn[series[x].id] === true && ok.hidden.indexOf(series[x].id) >= 0;
    var marks = reg.marks(m, { plot: geo.plot, value: geo.value, band: geo.band, visible: visible, level: lvl }, h);
    prims = prims.concat(geo.under, marks, geo.over, geo.axes, t.texts);
    var count = 0;
    for (var p = 0; p < marks.length; p++) if (marks[p].role === "mark" && marks[p].k !== "group" && marks[p].k !== "text") count++;
    out.primitives = prims;
    out.summary.marks = count;
    out.index = index(m.layout.vertical ? "x" : "y", geo.plot, series, visible, marks);
    return finish(out, meter);
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
