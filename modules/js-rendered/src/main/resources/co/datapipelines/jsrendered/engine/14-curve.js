// 14-curve.js : the line family's curves (record §4.5 "Defaults and the look"; look contract §2 line; brief 989 D6).
// A run is a list of points [{x, y}] between two nulls (a null is a gap, never joined). `smooth` is a monotone cubic
// through the points (Fritsch and Carlson's tangents in the form of d3's curveMonotoneX, translated): each tangent is
// limited so the segment between two points never rises above or falls below them, and its two control points lie
// inside the segment's range too. `step` holds a value until the next point's x, then moves (the kept points of a
// thinned series still step to the next kept point, §4.9). `linear` joins the points with straight segments. Every
// command is path data (08-prim.js: M, L, C) in frame coordinates; four operations and comparisons only.
(function (R) {
  "use strict";

  function sign(v) {
    return v < 0 ? -1 : v > 0 ? 1 : 0;
  }

  /** The tangent at the middle of three points: 0 at a turn, else bounded by both neighbouring slopes. */
  function slope3(a, b, c) {
    var h0 = b.x - a.x, h1 = c.x - b.x;
    if (!(h0 > 0) || !(h1 > 0)) return 0;
    var s0 = (b.y - a.y) / h0, s1 = (c.y - b.y) / h1, p = (s0 * h1 + s1 * h0) / (h0 + h1);
    var t = (sign(s0) + sign(s1)) * Math.min(Math.abs(s0), Math.abs(s1), 0.5 * Math.abs(p));
    return t === t ? t : 0;
  }

  /** The tangent at a run's end, from the segment's slope and its other end's tangent. */
  function slope2(a, b, t) {
    var h = b.x - a.x;
    return h > 0 ? (3 * (b.y - a.y) / h - t) / 2 : t;
  }

  /**
   * The commands after a run's first point, as segments: {c: "L", x, y} or {c: "C", x1, y1, x2, y2, x, y}. A run of one
   * point has none (nothing is drawn between nulls for it); two smooth points are one straight segment.
   * A step run always holds to the next point's x before moving to its y, including a run of only two points.
   */
  function segments(run, kind) {
    var out = [], n = run.length, i;
    if (n < 2) return out;
    if (kind === "linear" || (kind === "smooth" && n === 2)) {
      for (i = 1; i < n; i++) out.push({ c: "L", x: run[i].x, y: run[i].y });
      return out;
    }
    if (kind === "step") {
      for (i = 1; i < n; i++) {
        out.push({ c: "L", x: run[i].x, y: run[i - 1].y });
        out.push({ c: "L", x: run[i].x, y: run[i].y });
      }
      return out;
    }
    if (kind !== "smooth") throw new Error("curve: unknown curve " + kind);
    var t = [];
    for (i = 1; i < n - 1; i++) t[i] = slope3(run[i - 1], run[i], run[i + 1]);
    t[0] = slope2(run[0], run[1], t[1]);
    t[n - 1] = slope2(run[n - 2], run[n - 1], t[n - 2]);
    for (i = 1; i < n; i++) {
      var a = run[i - 1], b = run[i], dx = (b.x - a.x) / 3;
      out.push({ c: "C", x1: a.x + dx, y1: a.y + dx * t[i - 1], x2: b.x - dx, y2: b.y - dx * t[i], x: b.x, y: b.y });
    }
    return out;
  }

  /** Path data for a run: M at its first point, then its segments. */
  function forward(run, segs, d) {
    d.push("M", run[0].x, run[0].y);
    return append(segs, d);
  }

  function append(segs, d) {
    for (var i = 0; i < segs.length; i++) {
      var s = segs[i];
      if (s.c === "L") d.push("L", s.x, s.y);
      else d.push("C", s.x1, s.y1, s.x2, s.y2, s.x, s.y);
    }
    return d;
  }

  /**
   * The same curve walked from its last point back to its first (an area's lower edge, which is the series below's
   * upper edge reversed): a straight segment goes to the vertex before it; a cubic swaps its two control points.
   * Returns the segments of the reversed walk, which starts at the run's last vertex.
   */
  function reversed(run, segs) {
    var verts = [run[0]];
    for (var i = 0; i < segs.length; i++) verts.push({ x: segs[i].x, y: segs[i].y });
    var out = [];
    for (var j = segs.length - 1; j >= 0; j--) {
      var s = segs[j], to = verts[j];
      if (s.c === "L") out.push({ c: "L", x: to.x, y: to.y });
      else out.push({ c: "C", x1: s.x2, y1: s.y2, x2: s.x1, y2: s.y1, x: to.x, y: to.y });
    }
    return out;
  }

  /** The vertices a run's curve passes through after its first point (for D9a's count: a path's drawn points). */
  function vertices(segs, kind) {
    return kind === "step" ? segs.length / 2 : segs.length;
  }

  R.curve = { segments: segments, forward: forward, append: append, reversed: reversed, vertices: vertices };
})(JsRendered);
