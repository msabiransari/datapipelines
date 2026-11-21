// types/test_marks.js : a type for TESTS ONLY (brief 1052 D10), on the frame `polar`: one mark per row, found by its own
// shape (the index's second form, 21-lookup.js). It returns {primitives, lookup, count} and writes each lookup entry
// from the same numbers it draws with. In no load order, no jar and no page of the product.
//
// Its document: config.encoding.color = {field, legend?} names the series column (a null cell is a mark without a
// series), encoding.id = {field} the mark's datum. The other columns, by fixed names: `kind` (box, point or sector),
// `a`, `b`, `c`, `d`, and for a point `e`, `f`; `row`, `key`, `near`, `emph` (a null cell leaves the member out).
// - box: a, b = its corner as shares of the plot; c, d = its width and height as shares of the plot;
// - point: a, b = its centre as shares of the plot, e, f = px added to it; c = its radius in px, at most a third of the
//   plot's shorter side;
// - sector: a, b = its angles (degrees, 0 at 12 o'clock, clockwise) around the frame's centre; c, d = its radii as
//   shares of the frame's radius. With c above 0 it is drawn as a stroked arc on the middle radius, as wide as the ring.
// config.cards = {datum as text: {heading, rows: [{name, value, colour, bold}], keep, extra}}; a row's colour "series" is
// the mark's own colour, a token name is that token, null no colour. A mark without a card shows its series and datum.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, r2 = R.scales.r2;
  var CELL = "--chart-seq-5";

  function val(col, i) {
    return col && !D.isNull(col[i]) ? col[i] : null;
  }

  function model(doc, columns) {
    var e = doc.config.encoding, color = e.color || null;
    var C = color ? D.column(columns, color.field) : null, I = D.column(columns, e.id.field), K = D.column(columns, "kind");
    var n = D.rowCount(columns), series = [], byKey = new Map(), rows = [];
    for (var i = 0; i < n; i++) {
      var kind = val(K, i), datum = val(I, i);
      if (kind === null || datum === null) continue;
      var v = val(C, i), sid = null;
      if (v !== null) {
        var key = typeof v + ":" + String(v);
        if (!byKey.has(key)) {
          byKey.set(key, series.length);
          series.push({ id: key, title: String(v), colour: R.theme.SERIES[series.length] || null });
        }
        sid = key;
      }
      var r = { series: sid, datum: datum, kind: kind };
      ["a", "b", "c", "d", "e", "f", "row", "key", "near", "emph"].forEach(function (f) {
        r[f] = val(D.column(columns, f), i);
      });
      rows.push(r);
    }
    return { series: series, markCount: rows.length, warnings: [], legend: color ? color.legend : undefined, rows: rows, cards: doc.config.cards || {} };
  }

  function card(m, r, title, token, h) {
    var c = m.cards[String(r.datum)];
    if (!c) return { heading: null, rows: [{ name: title, value: String(r.datum), colour: token, color: h.theme.css(token) }], keep: 0 };
    var rows = c.rows.map(function (w) {
      var t = w.colour === "series" ? token : w.colour;
      var row = { name: w.name, value: w.value };
      if (t !== null) {
        row.colour = t;
        row.color = h.theme.css(t);
      }
      if (w.bold === true) row.bold = true;
      return row;
    });
    var out = { heading: c.heading === undefined ? null : c.heading, rows: rows, keep: c.keep };
    if (c.extra) out.extra = c.extra;
    return out;
  }

  function marks(m, hand, h) {
    var plot = hand.plot, out = [], lookup = [], bySeries = new Map(), loose = [];
    var titles = new Map(), tokens = new Map();
    for (var s = 0; s < m.series.length; s++) {
      titles.set(m.series[s].id, m.series[s].title);
      tokens.set(m.series[s].id, m.series[s].colour);
      bySeries.set(m.series[s].id, []);
    }
    var big = Math.min(plot.w, plot.h) / 3;
    for (var i = 0; i < m.rows.length; i++) {
      var r = m.rows[i];
      if (r.series !== null && hand.visible[r.series] !== true) continue;
      var token = r.series === null ? CELL : tokens.get(r.series), css = h.theme.css(token), prim, shape;
      var base = { role: "mark", series: r.series, datum: r.datum };
      if (r.kind === "box") {
        shape = { kind: "box", x: r2(plot.x + r.a * plot.w), y: r2(plot.y + r.b * plot.h), w: r2(r.c * plot.w), h: r2(r.d * plot.h) };
        prim = P.rect(Object.assign({ x: shape.x, y: shape.y, w: shape.w, h: shape.h, fill: token, color: css }, base));
      } else if (r.kind === "point") {
        shape = { kind: "point", x: r2(plot.x + r.a * plot.w + (r.e || 0)), y: r2(plot.y + r.b * plot.h + (r.f || 0)), r: r2(Math.min(r.c, big)) };
        prim = P.circle(Object.assign({ cx: shape.x, cy: shape.y, r: shape.r, fill: token, color: css }, base));
      } else {
        var cx = hand.centre.x, cy = hand.centre.y, r0 = r2(r.c * hand.radius), r1 = r2(r.d * hand.radius);
        shape = { kind: "sector", cx: cx, cy: cy, r0: r0, r1: r1, from: r.a, to: r.b };
        if (r0 === 0) {
          var start = R.trig.point(cx, cy, r1, r.a);
          var d = ["M", cx, cy, "L", start.x, start.y].concat(R.trig.arc(cx, cy, r1, r.a, r.b), ["Z"]);
          prim = P.path(Object.assign({ d: d, fill: token, color: css }, base));
        } else {
          var mid = r2(r0 + (r1 - r0) / 2), from = R.trig.point(cx, cy, mid, r.a);
          var arc = ["M", from.x, from.y].concat(R.trig.arc(cx, cy, mid, r.a, r.b));
          prim = P.path(Object.assign({ d: arc, stroke: token, strokeColor: css, strokeWidth: r2(r1 - r0) }, base));
        }
      }
      if (r.series === null) loose.push(prim);
      else bySeries.get(r.series).push(prim);
      var entry = { series: r.series, datum: r.datum, shape: shape, card: card(m, r, r.series === null ? "Mark" : titles.get(r.series), token, h) };
      if (r.row !== null) entry.row = r.row;
      if (r.key !== null) entry.key = r.key;
      if (r.near !== null) entry.near = r.near;
      if (r.emph !== null) entry.emphasis = r.emph;
      lookup.push(entry);
    }
    for (var q = 0; q < m.series.length; q++) {
      var kids = bySeries.get(m.series[q].id);
      if (kids.length > 0) P.group(out, { role: "mark", series: m.series[q].id, label: m.series[q].title }, kids);
    }
    return { primitives: loose.concat(out), lookup: lookup, count: lookup.length };
  }

  R.type("test_marks", { channels: [{ key: "color", required: false, node: { shape: "series", types: ["nominal"] } }], model: model, marks: marks, axes: "polar" });
})(JsRendered);
