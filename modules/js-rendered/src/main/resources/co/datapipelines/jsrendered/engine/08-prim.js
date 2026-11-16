// 08-prim.js : the primitives (record §4.3): a FLAT, ordered list of rect, line, path, circle, text and group records,
// never markup. Each constructor takes ONE object of named fields and THROWS on an unknown field or a missing required
// one, naming it (D4, R17: lane 977's positional constructors were the form's one weakness and are not copied). Every
// record of a kind has the same keys in the same order, optional ones present with their default, so the canonical
// JSON of a picture is JSON.stringify of the list. Coordinates are rounded to two decimals here. A colour is carried
// twice: `fill`/`stroke` name its token (the page writer's custom property), `color`/`strokeColor` hold its resolved
// value (the standalone writer's literal, and what makes a token's change visible in the primitives, LA2). A group
// carries n, the count of the records after it that it holds at any depth, so the list never nests.
(function (R) {
  "use strict";
  var r2 = R.scales.r2;
  // `tooltip` and `focus` are the interaction layer's (record §4.8, brief 990 D2, D5): its card and its focus ring,
  // drawn by the page over a picture; render never makes one.
  var ROLES = { mark: 1, axis: 1, grid: 1, tick: 1, legend: 1, title: 1, guide: 1, empty: 1, tooltip: 1, focus: 1 };
  var COORD = 1, NUM = 2, STR = 3, ANY = 4, BOOL = 5, OPT_STR = 6, LIST = 7, INT = 8;

  /** kind → [[field, type, required, default]], in output order. */
  var KINDS = {
    rect: [["role", STR, true], ["x", COORD, true], ["y", COORD, true], ["w", COORD, true], ["h", COORD, true],
      ["fill", OPT_STR, false, null], ["color", OPT_STR, false, null], ["stroke", OPT_STR, false, null], ["strokeColor", OPT_STR, false, null],
      ["strokeWidth", NUM, false, 0], ["rx", NUM, false, 0], ["opacity", NUM, false, 1], ["series", OPT_STR, false, null], ["datum", ANY, false, null], ["tip", LIST, false, null]],
    line: [["role", STR, true], ["x1", COORD, true], ["y1", COORD, true], ["x2", COORD, true], ["y2", COORD, true],
      ["stroke", STR, true], ["strokeColor", STR, true], ["strokeWidth", NUM, true], ["series", OPT_STR, false, null]],
    path: [["role", STR, true], ["d", LIST, true], ["fill", OPT_STR, false, null], ["color", OPT_STR, false, null], ["stroke", OPT_STR, false, null],
      ["strokeColor", OPT_STR, false, null], ["strokeWidth", NUM, false, 0], ["opacity", NUM, false, 1], ["series", OPT_STR, false, null], ["datum", ANY, false, null], ["tip", LIST, false, null],
      ["round", BOOL, false, false], ["fade", LIST, false, null]],
    circle: [["role", STR, true], ["cx", COORD, true], ["cy", COORD, true], ["r", COORD, true], ["fill", OPT_STR, false, null], ["color", OPT_STR, false, null],
      ["stroke", OPT_STR, false, null], ["strokeColor", OPT_STR, false, null], ["strokeWidth", NUM, false, 0], ["opacity", NUM, false, 1], ["series", OPT_STR, false, null], ["datum", ANY, false, null], ["tip", LIST, false, null]],
    text: [["role", STR, true], ["x", COORD, true], ["y", COORD, true], ["text", STR, true], ["shown", OPT_STR, false, null], ["anchor", STR, false, "start"],
      ["size", NUM, true], ["weight", NUM, false, 400], ["fill", STR, true], ["color", STR, true], ["rotate", NUM, false, 0], ["opacity", NUM, false, 1],
      ["struck", BOOL, false, false], ["series", OPT_STR, false, null]],
    group: [["role", STR, true], ["n", INT, true], ["series", OPT_STR, false, null], ["legend", OPT_STR, false, null], ["label", OPT_STR, false, null], ["on", BOOL, false, true]],
  };
  var INDEX = {};
  for (var kind in KINDS) {
    if (!Object.prototype.hasOwnProperty.call(KINDS, kind)) continue;
    INDEX[kind] = {};
    for (var i = 0; i < KINDS[kind].length; i++) INDEX[kind][KINDS[kind][i][0]] = KINDS[kind][i];
  }

  function finite(v) {
    return typeof v === "number" && v === v && v !== Infinity && v !== -Infinity;
  }

  function value(kind, spec, v) {
    var name = spec[0], type = spec[1];
    var ok = type === COORD || type === NUM ? finite(v)
      : type === INT ? finite(v) && Math.floor(v) === v && v >= 0
        : type === STR ? typeof v === "string"
          : type === OPT_STR ? v === null || typeof v === "string"
            : type === BOOL ? v === true || v === false
              : type === LIST ? v === null || Array.isArray(v)
                : true;
    if (!ok) throw new Error(kind + ": field " + name + " has a value of the wrong kind");
    if (name === "role" && ROLES[v] !== 1) throw new Error(kind + ": unknown role " + v);
    return type === COORD ? r2(v) : v;
  }

  function make(kind, fields) {
    if (fields === null || typeof fields !== "object" || Array.isArray(fields)) throw new Error(kind + ": takes one object of named fields");
    var idx = INDEX[kind];
    for (var k in fields) {
      if (Object.prototype.hasOwnProperty.call(fields, k) && !Object.prototype.hasOwnProperty.call(idx, k)) throw new Error(kind + ": unknown field " + k);
    }
    var out = { k: kind }, specs = KINDS[kind];
    for (var i = 0; i < specs.length; i++) {
      var s = specs[i], has = Object.prototype.hasOwnProperty.call(fields, s[0]) && fields[s[0]] !== undefined;
      if (!has && s[2]) throw new Error(kind + ": missing field " + s[0]);
      out[s[0]] = has ? value(kind, s, fields[s[0]]) : s[3];
    }
    if (out.fill !== undefined && (out.fill === null) !== (out.color === null)) throw new Error(kind + ": fill and color go together");
    if (out.stroke !== undefined && kind !== "line" && (out.stroke === null) !== (out.strokeColor === null)) throw new Error(kind + ": stroke and strokeColor go together");
    return out;
  }

  /** The coordinates each path command takes. */
  var ARITY = { M: 2, L: 2, A: 3, C: 6, Z: 0 };

  /**
   * Path data: commands M x y, L x y, A r x y (a circular arc of radius r, the shorter way, clockwise), C x1 y1 x2 y2 x y
   * (a cubic Bézier segment: two control points, then its end; the line family's smooth curve, brief 989 D6), Z.
   */
  function pathData(d) {
    var out = [], i = 0;
    while (i < d.length) {
      var c = d[i], n = Object.prototype.hasOwnProperty.call(ARITY, c) ? ARITY[c] : -1;
      if (n < 0 || i + n >= d.length + (n === 0 ? 1 : 0)) throw new Error("path: bad command at " + i);
      out.push(c);
      for (var j = 1; j <= n; j++) {
        if (!finite(d[i + j])) throw new Error("path: a coordinate is not a finite number");
        out.push(r2(d[i + j]));
      }
      i += n + 1;
    }
    return out;
  }

  /**
   * A path's fade (look contract §2 "area"): null, or [top, bottom, y0, y1]: its fill colour at opacity `top` at y0
   * falling to opacity `bottom` at y1, straight down the frame (the writers draw it as a vertical gradient from y0 to
   * y1). The two opacities are shares in [0, 1]; the two ys are coordinates, rounded as every coordinate is.
   */
  function fadeOf(p) {
    var f = p.fade;
    if (p.fill === null || f.length !== 4) throw new Error("path: a fade is [top, bottom, y0, y1] on a filled path");
    for (var i = 0; i < 4; i++) if (!finite(f[i])) throw new Error("path: a fade value is not a finite number");
    if (f[0] < 0 || f[0] > 1 || f[1] < 0 || f[1] > 1) throw new Error("path: a fade opacity is outside [0, 1]");
    return [f[0], f[1], r2(f[2]), r2(f[3])];
  }

  R.prim = {
    ARITY: ARITY,
    KINDS: KINDS,
    rect: function (f) {
      return make("rect", f);
    },
    line: function (f) {
      return make("line", f);
    },
    path: function (f) {
      var p = make("path", f);
      p.d = pathData(p.d);
      if (p.fade !== null) p.fade = fadeOf(p);
      return p;
    },
    circle: function (f) {
      return make("circle", f);
    },
    text: function (f) {
      var t = make("text", f);
      if (t.shown === t.text) t.shown = null;
      if (t.anchor !== "start" && t.anchor !== "middle" && t.anchor !== "end") throw new Error("text: unknown anchor " + t.anchor);
      return t;
    },
    /** A group record followed by its (already flat) children, appended to out. */
    group: function (out, f, children) {
      var g = {};
      for (var k in f) if (Object.prototype.hasOwnProperty.call(f, k)) g[k] = f[k];
      if (Object.prototype.hasOwnProperty.call(g, "n")) throw new Error("group: field n is counted, never given");
      g.n = children.length;
      out.push(make("group", g));
      for (var i = 0; i < children.length; i++) out.push(children[i]);
      return out;
    },
  };
})(JsRendered);
