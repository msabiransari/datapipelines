// 18-frames.js : the frame registry, the shared legend placement and the two frames without axes (record §4.3, §4.6,
// §9 G1; look contract §4.2, §4.5, §3.1; brief 1049 D2, D3). A type names its frame in `axes`; `render` asks the frame
// whether it takes the title block and the legend, and for the room a legend may take and the frame's layout. A frame
// added by a later file needs no line of 20-render.js.
//
// A frame: {titles, legend, room(h, lvl, m, place, boxH), lay(h, W, H, lvl, top, m, series, visible, lg)}.
//   titles, legend: booleans; false takes the title block, or the legend, away (no title text, no legend group, no
//     hidden series in any draw of the frame, whatever the document and the list say).
//   room: the height a legend at `place` may take in a box boxH high; required when `legend` is true, never called
//     when it is false.
//   lay: lays the frame out below `top` (the title block's bottom, 0 without titles), places the legend through
//     R.frames.legend, and returns geo = {plot: {x, y, w, h}, hand, under, axes, over, after}: `hand` is what the
//     template's marks receive (render adds `visible` and `level`); under, axes and over are primitive lists drawn
//     before the marks, after them, and after `over`; after(drawn), optional, returns what the frame draws once the
//     template's result is known.
(function (R) {
  "use strict";
  var P = R.prim, look = R.tables.look, r2 = R.scales.r2;
  var FRAMES = new Map(), ORDER = [];
  var MEMBERS = { titles: true, legend: true, room: true, lay: true };

  /** Adds a frame by name; a second frame of one name, an unknown member or a wrong one throws, naming it. */
  function add(name, def) {
    if (typeof name !== "string" || !/^[a-z][a-z0-9_]*$/.test(name)) throw new Error("frames: a frame name is lower-case letters, digits and _");
    if (FRAMES.has(name)) throw new Error("frames: " + name + " is already added");
    if (def === null || typeof def !== "object") throw new Error("frames: " + name + " takes {titles, legend, room, lay}");
    for (var k in def) if (Object.prototype.hasOwnProperty.call(def, k) && MEMBERS[k] !== true) throw new Error("frames: " + name + ": unknown member " + k);
    if (typeof def.titles !== "boolean") throw new Error("frames: " + name + ": titles is true or false");
    if (typeof def.legend !== "boolean") throw new Error("frames: " + name + ": legend is true or false");
    if (typeof def.lay !== "function") throw new Error("frames: " + name + ": lay is a function");
    var hasRoom = Object.prototype.hasOwnProperty.call(def, "room") && def.room !== undefined;
    if ((def.legend || hasRoom) && typeof def.room !== "function") throw new Error("frames: " + name + ": room is a function" + (def.legend ? " (the frame has a legend)" : ""));
    FRAMES.set(name, { titles: def.titles, legend: def.legend, room: hasRoom ? def.room : null, lay: def.lay });
    ORDER.push(name);
  }

  /** The frame of that name; an unknown name throws. */
  function get(name) {
    var f = FRAMES.get(name);
    if (f === undefined) throw new Error("frames: no frame " + name);
    return f;
  }

  /** The names of every frame, in the order they were added. */
  function names() {
    return ORDER.slice();
  }

  /**
   * The shared legend placement: lays the legend out in `box` ({left, right, top, bottom}), pushes its primitives onto
   * `out` at the top, bottom, left or right of the box, sets lg.drawn and returns the box that is left. It reads the
   * legend through lg.place, lg.layout(h, boxW, boxH) → {w, h, drawn, empty, …} and lg.prims(h, lay, ox, oy, out) only,
   * so a legend drawn by another function is placed by the same code. room(place, boxH) → the height the legend may
   * take (the frame's own `room` with its arguments bound). A null legend, a null place and `direct` place nothing.
   */
  function legend(h, box, lg, room, out) {
    if (lg === null || lg.place === null || lg.place === "direct") return box;
    var gap = h.em("legendGap"), place = lg.place;
    var lay = lg.layout(h, box.right - box.left, room(place, box.bottom - box.top));
    lg.drawn = lay.drawn;
    if (lay.empty) return box;
    var b = { left: box.left, right: box.right, top: box.top, bottom: box.bottom };
    if (place === "top") {
      lg.prims(h, lay, box.left, box.top, out);
      b.top += lay.h + gap;
    } else if (place === "bottom" || place === "compact") {
      lg.prims(h, lay, box.left, box.bottom - lay.h, out);
      b.bottom -= lay.h + gap;
    } else if (place === "left") {
      lg.prims(h, lay, box.left, box.top, out);
      b.left += lay.w + gap;
    } else {
      lg.prims(h, lay, box.right - lay.w, box.top, out);
      b.right -= lay.w + gap;
    }
    return b;
  }

  /**
   * A frame without axes' legend room (the form of the cartesian frame's): a column may take all of the box's height;
   * rows what is left of it after the legend's gap and the smallest plot a chart has (the minimal level's height).
   */
  function roomWithoutAxes(h, lvl, m, place, boxH) {
    if (place === "left" || place === "right") return boxH;
    return boxH - h.em("legendGap") - look("levelMinimal")[1];
  }

  /**
   * The plot of a frame without axes: the frame less the edge on four sides, below the title block, less the legend.
   * At the full and compact levels `under` holds the plot's own background, one rectangle filled --chart-plot written as
   * the cartesian frame writes it; at the minimal level nothing (no title, no legend, no background, as there).
   */
  function plain(h, W, H, lvl, top, m, lg) {
    var e = h.em("edge"), legendOut = [];
    var box = legend(h, { left: e, right: W - e, top: top, bottom: H - e }, lg, function (place, boxH) {
      return roomWithoutAxes(h, lvl, m, place, boxH);
    }, legendOut);
    var plot = { x: r2(box.left), y: r2(box.top), w: r2(Math.max(1, box.right - box.left)), h: r2(Math.max(1, box.bottom - box.top)) };
    var under = [];
    if (lvl !== "minimal") under.push(P.rect({ role: "grid", x: plot.x, y: plot.y, w: plot.w, h: plot.h, fill: "--chart-plot", color: h.theme.css("--chart-plot") }));
    return { plot: plot, under: under, axes: legendOut };
  }

  /** `none`: the template receives the plot only. */
  var NONE = {
    titles: true,
    legend: true,
    room: roomWithoutAxes,
    lay: function (h, W, H, lvl, top, m, series, visible, lg) {
      var f = plain(h, W, H, lvl, top, m, lg);
      return { plot: f.plot, hand: { plot: f.plot }, under: f.under, axes: f.axes, over: [] };
    },
  };

  /**
   * `polar`: the plot, its centre and half its smaller side as the radius, each rounded as a coordinate is. A type that
   * wants another circle (a gauge's open arc) computes it from the plot.
   */
  var POLAR = {
    titles: true,
    legend: true,
    room: roomWithoutAxes,
    lay: function (h, W, H, lvl, top, m, series, visible, lg) {
      var f = plain(h, W, H, lvl, top, m, lg), p = f.plot;
      var centre = { x: r2(p.x + p.w / 2), y: r2(p.y + p.h / 2) };
      return { plot: p, hand: { plot: p, centre: centre, radius: r2(Math.min(p.w, p.h) / 2) }, under: f.under, axes: f.axes, over: [] };
    },
  };

  R.frames = { add: add, get: get, names: names, legend: legend };
  add("cartesian", R.cartesian.frame);
  add("polar", POLAR);
  add("none", NONE);
})(JsRendered);
