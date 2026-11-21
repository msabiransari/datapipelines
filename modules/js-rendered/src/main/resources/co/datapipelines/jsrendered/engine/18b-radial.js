// 18b-radial.js : the frame `radial`, the polar frame with the shared radial axis (record §9 G3 "The radial axis", §4.5
// radar, §4.3, §4.6, O-16; look contract §2.1 radar "grid: straight", §4.7 radar, §3.1; brief 1055 D2 to D8). A radial
// type's template hands the layout its categories and its value range and receives the centre, the radius and each
// spoke's angle; it draws marks and no part of the axis. The frame asks the polar frame for its layout (the title
// block's room, the legend, the plot and the plot's background), takes the axis names' room from that plot BEFORE it
// fixes the radius, and writes one radial scale from zero: its ticks as rings, polygons through the spokes; one spoke
// per category, the first at 12 o'clock, clockwise; the ring numbers on plates of the card's colour; the axis names
// around the outside. Every angle goes through R.trig (no sine function of the runtime, O-16).
//
// The layout part the frame reads, m.layout = {spokes: [{text}], extent(visible) → [lo, hi], domain: [0, hi] | null,
// format}: 3 to 12 spokes (the record's row of radar), an extent that does not start below 0 and a domain that starts
// at 0 (one scale from zero: a type refuses a negative value before the layout is asked). A wrong part is a template's
// mistake, never a document's: it throws, naming the member.
//
// The template receives {plot, centre: {x, y}, radius, angles: [deg, …], r: {lo: 0, hi, ticks, scale}} plus visible and
// level: angles[i] = i × 360 ÷ n; r.scale(v) is the distance from the centre, 0 at 0 and radius at hi; a point is
// R.trig.point(centre.x, centre.y, r.scale(v), angles[i]).
(function (R) {
  "use strict";
  var P = R.prim, S = R.scales, A = R.axes, T = R.trig, look = R.tables.look, r2 = S.r2;
  var POLAR = R.frames.get("polar");

  function fail(what) {
    throw new Error("radial: " + what);
  }

  function finite(v) {
    return typeof v === "number" && v - v === 0;
  }

  /** The model's layout part, checked: {spokes, ext, domain, format}; a wrong member throws, naming it. */
  function part(m, visible) {
    var L = m.layout;
    if (L === null || typeof L !== "object") fail("the model's layout is {spokes, extent, domain, format}");
    var s = L.spokes;
    if (!Array.isArray(s) || s.length < 3 || s.length > 12) fail("layout.spokes holds 3 to 12 categories, not " + (Array.isArray(s) ? s.length : typeof s));
    for (var i = 0; i < s.length; i++) {
      if (s[i] === null || typeof s[i] !== "object" || typeof s[i].text !== "string") fail("layout.spokes[" + i + "].text is a string");
    }
    if (typeof L.extent !== "function") fail("layout.extent is a function of the visible series");
    var ext = L.extent(visible);
    if (!Array.isArray(ext) || ext.length !== 2 || !finite(ext[0]) || !finite(ext[1]) || ext[1] < ext[0]) {
      fail("layout.extent returns [lo, hi], two finite numbers with lo ≤ hi");
    }
    if (ext[0] < 0) fail("layout.extent starts at " + ext[0] + ", below 0: the radial axis is one scale from zero");
    var d = L.domain === undefined ? null : L.domain;
    if (d !== null) {
      if (!Array.isArray(d) || d.length !== 2 || !finite(d[0]) || !finite(d[1])) fail("layout.domain is null or [0, hi]");
      if (d[0] !== 0) fail("layout.domain starts at " + d[0] + ", not 0: the radial axis is one scale from zero");
      if (!(d[1] > 0)) fail("layout.domain ends at " + d[1] + ", not above 0");
    }
    return { spokes: s, ext: ext, domain: d, format: L.format === undefined ? null : L.format };
  }

  /** The ring's form (look field radialRings): "polygon", the pick, or "circle"; any other word throws. */
  function ringForm() {
    var f = look("radialRings");
    if (f !== "polygon" && f !== "circle") fail("the look field radialRings is polygon or circle, not " + f);
    return f;
  }

  /**
   * The names' room and the radius (D4): the names' column nw is the smallest of the widest name, bandLabelMaxEm em and
   * radialNameShare of the plot's width; nw + tickGap is taken at the left and the right, a text box's height plus
   * tickGap at the top and the bottom, and the centre and the radius are the box that is left's. null where the radius
   * left is below radialRadiusFloor of the polar frame's (no name is written then).
   */
  function room(h, plot, spokes, polarRadius) {
    var s = h.text, tg = h.em("tickGap"), bh = h.boxH(s), widest = 0;
    for (var i = 0; i < spokes.length; i++) widest = Math.max(widest, h.width(spokes[i].text, s));
    var nw = Math.min(widest, h.em("bandLabelMaxEm"), look("radialNameShare") * plot.w);
    var left = plot.x + nw + tg, right = plot.x + plot.w - nw - tg, top = plot.y + bh + tg, bottom = plot.y + plot.h - bh - tg;
    var radius = r2(Math.min(right - left, bottom - top) / 2);
    if (!(radius >= look("radialRadiusFloor") * polarRadius)) return null;
    return { centre: { x: r2((left + right) / 2), y: r2((top + bottom) / 2) }, radius: radius, nw: nw };
  }

  /**
   * The axis names (D4), placed from the first spoke clockwise, each at tickGap outside its outermost corner: the spoke
   * at 0° centred over it (its box's bottom there), one at exactly 180° centred under it (its box's top there), the
   * right half starting and the left half ending there with their middles on it. A name is cut to nw (it keeps its full
   * text) and left out where its box would meet a name already placed. Returns {prims, boxes}.
   */
  function names(h, spokes, angles, centre, radius, nw) {
    var s = h.text, tg = h.em("tickGap"), bh = h.boxH(s), prims = [], boxes = [];
    for (var i = 0; i < spokes.length; i++) {
      var text = spokes[i].text, shown = h.fit(text, s, 400, nw);
      if (shown === "") continue;
      var w = h.width(shown, s), a = angles[i], c = T.point(centre.x, centre.y, radius + tg, a);
      var anchor, x0, y0;
      if (a === 0) {
        anchor = "middle";
        x0 = c.x - w / 2;
        y0 = c.y - bh;
      } else if (a === 180) {
        anchor = "middle";
        x0 = c.x - w / 2;
        y0 = c.y;
      } else if (T.sin(a) > 0) {
        anchor = "start";
        x0 = c.x;
        y0 = c.y - bh / 2;
      } else {
        anchor = "end";
        x0 = c.x - w;
        y0 = c.y - bh / 2;
      }
      var box = { x0: x0, y0: y0, x1: x0 + w, y1: y0 + bh }, meets = false;
      for (var b = 0; b < boxes.length && !meets; b++) meets = A.overlaps(box, boxes[b]);
      if (meets) continue;
      boxes.push(box);
      prims.push(h.label({ role: "tick", x: c.x, y: y0 + h.ascent(s), text: text, shown: shown, anchor: anchor, size: s, ink: "--chart-muted" }));
    }
    return { prims: prims, boxes: boxes };
  }

  /**
   * The ring numbers (D5): on every ring but the outermost, its tick's text whole, at the middle of the ring's first
   * edge (halfway between the first two spokes), on a plate filled --chart-canvas: the text's box grown by --chart-gap on
   * four sides. Tried from the outermost numbered ring inward; a number is written only when its plate (a) lies wholly
   * inside the plot, (b) meets no drawn name, (c) meets no plate already placed and (d) does not cover the centre.
   */
  function numbers(h, rings, n, form, centre, plot, nameBoxes) {
    var s = h.small, bh = h.boxH(s), g = h.theme.n("--chart-gap"), a = 180 / n, k = form === "polygon" ? T.cos(a) : 1;
    var prims = [], plates = [];
    for (var i = rings.length - 2; i >= 0; i--) {
      var p = T.point(centre.x, centre.y, rings[i].d * k, a), w = h.width(rings[i].text, s);
      var plate = { x0: p.x - w / 2 - g, y0: p.y - bh / 2 - g, x1: p.x + w / 2 + g, y1: p.y + bh / 2 + g };
      if (plate.x0 < plot.x || plate.y0 < plot.y || plate.x1 > plot.x + plot.w || plate.y1 > plot.y + plot.h) continue;
      var meets = false;
      for (var b = 0; b < nameBoxes.length && !meets; b++) meets = A.overlaps(plate, nameBoxes[b]);
      for (var q = 0; q < plates.length && !meets; q++) meets = A.overlaps(plate, plates[q]);
      if (meets) continue;
      if (plate.x0 <= centre.x && centre.x <= plate.x1 && plate.y0 <= centre.y && centre.y <= plate.y1) continue;
      plates.push(plate);
      prims.push(P.rect({ role: "tick", x: plate.x0, y: plate.y0, w: plate.x1 - plate.x0, h: plate.y1 - plate.y0, fill: "--chart-canvas", color: h.theme.css("--chart-canvas") }));
      prims.push(h.label({ role: "tick", x: p.x, y: h.middle(p.y, s), text: rings[i].text, anchor: "middle", size: s, ink: "--chart-muted" }));
    }
    return prims;
  }

  /** The spokes, then the rings from the innermost out: the outermost in --chart-axis, the others in --chart-grid. */
  function grid(h, rings, angles, centre, form) {
    var out = [], sw = h.theme.n("--chart-stroke"), outer = rings[rings.length - 1].d;
    for (var i = 0; i < angles.length; i++) {
      var c = T.point(centre.x, centre.y, outer, angles[i]);
      out.push(h.rule("grid", "--chart-grid", centre.x, centre.y, c.x, c.y));
    }
    for (var r = 0; r < rings.length; r++) {
      var token = r === rings.length - 1 ? "--chart-axis" : "--chart-grid", stroke = { stroke: token, strokeColor: h.theme.css(token), strokeWidth: sw };
      if (form === "circle") {
        out.push(P.circle({ role: "grid", cx: centre.x, cy: centre.y, r: rings[r].d, stroke: stroke.stroke, strokeColor: stroke.strokeColor, strokeWidth: sw }));
        continue;
      }
      var d = [];
      for (var j = 0; j < angles.length; j++) {
        var q = T.point(centre.x, centre.y, rings[r].d, angles[j]);
        d.push(j === 0 ? "M" : "L", q.x, q.y);
      }
      d.push("Z");
      out.push(P.path({ role: "grid", d: d, stroke: stroke.stroke, strokeColor: stroke.strokeColor, strokeWidth: sw }));
    }
    return out;
  }

  /**
   * The frame's layout. At the minimal level no ring, spoke, number or name: the polar frame's centre and radius and the
   * scale computed (as the cartesian layout computes its scales there).
   */
  function lay(h, W, H, lvl, top, m, series, visible, lg) {
    var L = part(m, visible), form = ringForm();
    var pol = POLAR.lay(h, W, H, lvl, top, m, series, visible, lg), plot = pol.plot, n = L.spokes.length;
    var angles = [];
    for (var i = 0; i < n; i++) angles.push((i * 360) / n);
    var centre = pol.hand.centre, radius = pol.hand.radius, room0 = lvl === "minimal" ? null : room(h, plot, L.spokes, radius);
    if (room0 !== null) {
      centre = room0.centre;
      radius = room0.radius;
    }
    // The tick rule: the value axis's ticks over [0, the extent's hi] or the author's domain, for radialTickSpan × the radius.
    var ax = A.valueAxis(lvl, [0, L.ext[1]], L.domain, look("radialTickSpan") * radius, L.format, 0);
    var hi = ax.hi, scale = S.linear(0, hi, 0, radius);
    var hand = { plot: plot, centre: centre, radius: radius, angles: angles, r: { lo: 0, hi: hi, ticks: ax.ticks, scale: scale } };
    if (lvl === "minimal") return { plot: plot, hand: hand, under: pol.under, axes: pol.axes, over: [] };
    // One ring per tick above 0, and one at hi where hi is no tick (an author's domain); the last is the outermost.
    var rings = [];
    for (var t = 0; t < ax.ticks.length; t++) if (ax.ticks[t] > 0 && ax.ticks[t] < hi) rings.push({ d: scale(ax.ticks[t]), text: ax.texts[t] });
    rings.push({ d: scale(hi), text: null });
    var nm = room0 === null ? { prims: [], boxes: [] } : names(h, L.spokes, angles, centre, radius, room0.nw);
    var nums = numbers(h, rings, n, form, centre, plot, nm.boxes);
    return { plot: plot, hand: hand, under: pol.under.concat(grid(h, rings, angles, centre, form)), axes: pol.axes.concat(nm.prims, nums), over: [] };
  }

  R.frames.add("radial", { titles: true, legend: true, room: POLAR.room, lay: lay });
})(JsRendered);
