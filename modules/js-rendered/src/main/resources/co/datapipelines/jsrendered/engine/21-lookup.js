// 21-lookup.js : the data index's second form and its lookup (record §4.3, §4.8; brief 1052 D2 to D4). A type with no
// band axis (scatter, pie, heatmap, box, radar, treemap, funnel, gauge) finds a mark by its own shape in two
// dimensions: its template returns {primitives, lookup, count}, `lookup` one entry per mark the pointer or the keys can
// reach, and render's indexOf hands that list here. R.lookup.index checks the whole list before it builds anything and
// returns {axis: null, plot, series, marks}; R.lookup.at(ix, x, y, reach) answers the position of the mark at a place,
// or −1. The arithmetic is O-16's: the four operations, comparisons, Math.sqrt and R.trig; a sector's angle is tested
// by two side tests against the directions of its edges, never by an inverse tangent.
(function (R) {
  "use strict";
  var r2 = R.scales.r2, T = R.trig;
  var own = Object.prototype.hasOwnProperty;
  var ENTRY = ["series", "datum", "shape", "row", "key", "near", "emphasis", "card"];
  var CARD = ["heading", "rows", "keep", "extra"];
  var ROW = ["name", "value", "colour", "color", "bold"];
  /** Each shape's members after `kind`, in output order. */
  var SHAPES = { box: ["x", "y", "w", "h"], point: ["x", "y", "r"], sector: ["cx", "cy", "r0", "r1", "from", "to"] };
  var EMPHASIS = { ring: true, move: true };

  function plain(v) {
    return v !== null && typeof v === "object" && !Array.isArray(v);
  }

  function finite(v) {
    return typeof v === "number" && v - v === 0;
  }

  function whole(v) {
    return finite(v) && Math.floor(v) === v && v >= 0;
  }

  /** Supplied: an own member whose value is not undefined (a member with a default may be left out or be undefined). */
  function has(o, k) {
    return own.call(o, k) && o[k] !== undefined;
  }

  function fail(where, member, why) {
    throw new Error("lookup: " + where + (member === null ? "" : ": " + member) + " " + why);
  }

  /** A structure is closed: a member this brief does not name throws. */
  function closed(o, names, where, prefix) {
    for (var k in o) if (own.call(o, k) && names.indexOf(k) < 0) fail(where, prefix + k, "is not a member");
  }

  function checkRow(row, where, at) {
    var p = "card.rows[" + at + "].";
    if (!plain(row)) fail(where, "card.rows[" + at + "]", "is not an object");
    closed(row, ROW, where, p);
    if (!has(row, "name") || typeof row.name !== "string") fail(where, p + "name", "is not a text");
    if (!has(row, "value") || typeof row.value !== "string") fail(where, p + "value", "is not a text");
    var colour = has(row, "colour") ? row.colour : null, color = has(row, "color") ? row.color : null;
    if (colour !== null && typeof colour !== "string") fail(where, p + "colour", "is not a text or null");
    if (color !== null && typeof color !== "string") fail(where, p + "color", "is not a text or null");
    if ((colour === null) !== (color === null)) fail(where, p + "colour", "and color go together: both a text or both null");
    if (has(row, "bold") && typeof row.bold !== "boolean") fail(where, p + "bold", "is not true or false");
  }

  function checkCard(card, where) {
    if (!plain(card)) fail(where, "card", "is not an object");
    closed(card, CARD, where, "card.");
    if (has(card, "heading") && card.heading !== null && typeof card.heading !== "string") fail(where, "card.heading", "is not a text or null");
    if (!has(card, "rows")) fail(where, "card.rows", "is missing");
    if (!Array.isArray(card.rows)) fail(where, "card.rows", "is not a list");
    if (card.rows.length === 0) fail(where, "card.rows", "is empty (a card has one row or more)");
    for (var i = 0; i < card.rows.length; i++) checkRow(card.rows[i], where, i);
    if (!has(card, "keep")) fail(where, "card.keep", "is missing");
    if (!whole(card.keep) || card.keep >= card.rows.length) fail(where, "card.keep", "is not a position of card.rows");
    if (has(card, "extra")) {
      if (!Array.isArray(card.extra)) fail(where, "card.extra", "is not a list");
      for (var x = 0; x < card.extra.length; x++) if (typeof card.extra[x] !== "string") fail(where, "card.extra[" + x + "]", "is not a text");
    }
  }

  function checkShape(s, where) {
    if (!plain(s)) fail(where, "shape", "is not an object");
    if (!has(s, "kind") || typeof s.kind !== "string" || !own.call(SHAPES, s.kind)) fail(where, "shape.kind", "is not box, point or sector");
    var names = SHAPES[s.kind];
    closed(s, ["kind"].concat(names), where, "shape.");
    for (var i = 0; i < names.length; i++) if (!has(s, names[i]) || !finite(s[names[i]])) fail(where, "shape." + names[i], "is not a finite number");
    if (s.kind === "box" && (s.w < 0 || s.h < 0)) fail(where, "shape." + (s.w < 0 ? "w" : "h"), "is negative");
    if (s.kind === "point" && s.r < 0) fail(where, "shape.r", "is negative");
    if (s.kind === "sector") {
      if (s.r0 < 0 || s.r0 > s.r1) fail(where, "shape.r0", "is not between 0 and r1");
      if (s.to < s.from || s.to > s.from + 360) fail(where, "shape.to", "is not between from and from + 360");
    }
  }

  /** Every rule of D3's table and its "across members", for entry i; nothing is converted. */
  function checkEntry(e, i, ids) {
    var where = "entry " + i;
    if (!plain(e)) fail(where, null, "is not an object");
    closed(e, ENTRY, where, "");
    if (!has(e, "series")) fail(where, "series", "is missing (a mark without a series says null)");
    if (e.series !== null && (typeof e.series !== "string" || !ids.has(e.series))) fail(where, "series", "is not a series of this draw or null");
    if (!has(e, "datum") || !(typeof e.datum === "string" || finite(e.datum))) fail(where, "datum", "is not a text or a finite number");
    if (!has(e, "shape")) fail(where, "shape", "is missing");
    checkShape(e.shape, where);
    if (has(e, "row") && !whole(e.row)) fail(where, "row", "is not a whole number of 0 or more");
    if (has(e, "key") && typeof e.key !== "boolean") fail(where, "key", "is not true or false");
    if (has(e, "near") && typeof e.near !== "boolean") fail(where, "near", "is not true or false");
    if (has(e, "emphasis") && e.emphasis !== null && !(typeof e.emphasis === "string" && own.call(EMPHASIS, e.emphasis))) fail(where, "emphasis", "is not null, ring or move");
    if (!has(e, "card")) fail(where, "card", "is missing");
    checkCard(e.card, where);
    var kind = e.shape.kind;
    if (e.near === true && kind !== "point") fail(where, "near", "is true on a " + kind + " (only a point is found near)");
    if (e.emphasis === "ring" && kind === "sector") fail(where, "emphasis", "ring is not for a sector");
    if (e.emphasis === "move" && kind !== "sector") fail(where, "emphasis", "move is for a sector only");
  }

  function idOf(series, datum) {
    return JSON.stringify([series, datum]);
  }

  /** The bounds {x, y, w, h} of a sector: its four corners and the axis directions inside its angles, on r1. */
  function sectorBox(s) {
    var pts = [T.point(s.cx, s.cy, s.r0, s.from), T.point(s.cx, s.cy, s.r0, s.to), T.point(s.cx, s.cy, s.r1, s.from), T.point(s.cx, s.cy, s.r1, s.to)];
    for (var d = 0; d < 360; d += 90) {
      var a = s.from + (((d - s.from) % 360) + 360) % 360;
      if (a <= s.to) pts.push(T.point(s.cx, s.cy, s.r1, a));
    }
    var x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (var i = 0; i < pts.length; i++) {
      if (pts[i].x < x0) x0 = pts[i].x;
      if (pts[i].x > x1) x1 = pts[i].x;
      if (pts[i].y < y0) y0 = pts[i].y;
      if (pts[i].y > y1) y1 = pts[i].y;
    }
    return { x: x0, y: y0, w: r2(x1 - x0), h: r2(y1 - y0) };
  }

  function built(e) {
    var s = e.shape, shape = { kind: s.kind }, names = SHAPES[s.kind];
    for (var i = 0; i < names.length; i++) shape[names[i]] = s[names[i]];
    var c = e.card, rows = [];
    for (var r = 0; r < c.rows.length; r++) {
      var w = c.rows[r];
      rows.push({ name: w.name, value: w.value, colour: has(w, "colour") ? w.colour : null, color: has(w, "color") ? w.color : null, bold: has(w, "bold") ? w.bold : false });
    }
    var out = {
      series: e.series,
      datum: e.datum,
      shape: shape,
      row: has(e, "row") ? e.row : 0,
      key: has(e, "key") ? e.key : true,
      near: has(e, "near") ? e.near : false,
      emphasis: has(e, "emphasis") ? e.emphasis : null,
      card: { heading: has(c, "heading") ? c.heading : null, rows: rows, keep: c.keep, extra: has(c, "extra") ? c.extra.slice() : [] },
    };
    if (s.kind === "box") {
      out.anchor = { x: r2(s.x + s.w / 2), y: r2(s.y + s.h / 2) };
      out.box = { x: s.x, y: s.y, w: s.w, h: s.h };
    } else if (s.kind === "point") {
      out.anchor = { x: s.x, y: s.y };
      out.box = { x: r2(s.x - s.r), y: r2(s.y - s.r), w: r2(2 * s.r), h: r2(2 * s.r) };
    } else {
      var mid = s.from + (s.to - s.from) / 2;
      out.anchor = T.point(s.cx, s.cy, s.r0 + (s.r1 - s.r0) / 2, mid);
      out.box = sectorBox(s);
      out.out = { x: T.sin(mid), y: 0 - T.cos(mid) };
    }
    return out;
  }

  /**
   * The index's second form (D3): {axis: null, plot, series, marks}. series = every series of the draw with no position
   * (R.nearest answers null on it); marks = the template's entries in its order, every default filled, each with its
   * anchor, its box and, for a sector, the unit direction `out` of its middle angle. prims = the template's primitives:
   * each entry's (series, datum) is carried by at least one mark primitive there.
   */
  function index(plot, series, visible, list, prims) {
    if (!Array.isArray(list)) fail("the template's result", "lookup", "is not a list");
    var ids = new Set(), held = new Set(), seen = new Set();
    for (var s = 0; s < series.length; s++) ids.add(series[s].id);
    for (var p = 0; p < prims.length; p++) {
      var q = prims[p];
      if (q.role === "mark" && q.k !== "group" && q.k !== "text") held.add(idOf(q.series, q.datum));
    }
    for (var i = 0; i < list.length; i++) {
      checkEntry(list[i], i, ids);
      var id = idOf(list[i].series, list[i].datum);
      if (seen.has(id)) fail("entry " + i, "datum", "repeats the mark (series, datum) of an earlier entry");
      if (!held.has(id)) fail("entry " + i, "datum", "is carried by no mark primitive of the same series");
      seen.add(id);
    }
    var out = { axis: null, plot: plot, series: [], marks: [] };
    for (var t = 0; t < series.length; t++) out.series.push({ id: series[t].id, on: visible[series[t].id] === true, at: [], datum: [], tip: [] });
    for (var m = 0; m < list.length; m++) out.marks.push(built(list[m]));
    return out;
  }

  /** u(a) × v: positive when v lies clockwise of the direction of angle a, less than a half turn away (y down). */
  function side(a, vx, vy) {
    return T.sin(a) * vy + T.cos(a) * vx;
  }

  /** Whether direction (vx, vy) lies between `from` and `to`, both edges included (the centre itself does). */
  function within(s, vx, vy) {
    var span = s.to - s.from;
    if (span >= 360 || (vx === 0 && vy === 0)) return true;
    // Equal edges describe one ray; their side tests alone would also admit the opposite direction.
    if (span === 0) return side(s.from, vx, vy) === 0 && T.sin(s.from) * vx - T.cos(s.from) * vy >= 0;
    if (span <= 180) return side(s.from, vx, vy) >= 0 && side(s.to, vx, vy) <= 0;
    // Above a half turn: the place is outside only strictly inside the part that is left out, from `to` to `from`.
    return !(side(s.to, vx, vy) > 0 && side(s.from, vx, vy) < 0);
  }

  function distance(dx, dy) {
    return Math.sqrt(dx * dx + dy * dy);
  }

  function holds(m, x, y) {
    var s = m.shape;
    if (s.kind === "box") return x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h;
    if (s.kind === "point") return distance(x - s.x, y - s.y) <= s.r;
    var d = distance(x - s.cx, y - s.cy);
    return d >= s.r0 && d <= s.r1 && within(s, x - s.cx, y - s.cy);
  }

  /**
   * The mark at (x, y): its position in ix.marks, or −1 (D4). 1. No marks → −1 (a reach that is not a finite number of
   * 0 or more throws first). 2. Outside the plot → −1, its edges inside. 3. On a shape (a box, a sector, a point with
   * near false): the LAST such mark that holds the place. 4. Only when none: the near points by the distance to their
   * centre, within reach (equal counts); the nearest, the first of two equally near.
   */
  function at(ix, x, y, reach) {
    if (!finite(reach) || reach < 0) throw new Error("lookup: reach is not a finite number of 0 or more");
    if (!ix || !Array.isArray(ix.marks) || ix.marks.length === 0) return -1;
    var plot = ix.plot;
    if (!plot || x < plot.x || x > plot.x + plot.w || y < plot.y || y > plot.y + plot.h) return -1;
    var marks = ix.marks, found = -1;
    for (var i = 0; i < marks.length; i++) if (marks[i].near !== true && holds(marks[i], x, y)) found = i;
    if (found >= 0) return found;
    var best = Infinity;
    for (var j = 0; j < marks.length; j++) {
      if (marks[j].near !== true) continue;
      var d = distance(x - marks[j].shape.x, y - marks[j].shape.y);
      if (d <= reach && d < best) {
        best = d;
        found = j;
      }
    }
    return found;
  }

  R.lookup = { index: index, at: at };
})(JsRendered);
