// geometry.js : the invariant checker (record §8 "Invariants": I1, I2, I4, I5; brief 985 A.3, A.5) as ONE classic
// script, so Node (geometry.mjs) and the sandbox (JsRenderedIdentityTest, after the engine's files) run the same code
// over the same primitives. Test code only: never in the engine's resources, so R-BAN does not apply (it rotates a box
// with Math.cos and Math.sin). It reads primitives only. A text's box is its measured width by the font's vertical box
// (look fields textAscent and textDescent, read from the vendored Noto Sans: evidence d8), rotated about its anchor as
// the writer's transform does. Two boxes meet when they overlap by more than TOL px on every separating axis.
// JsRenderedInvariants(E) takes the engine whose width table and look fields it measures with: Node passes a PRISTINE
// engine (a planted width change must not move the checker with it, G2).
function JsRenderedInvariants(E) {
  "use strict";
  /** Overlap below this many px is not a meeting (rounding to two decimals moves an edge by at most 0.005). */
  const TOL = 0.02;

  var ASC = E.tables.look("textAscent");
  var DESC = E.tables.look("textDescent");

  // The width of a text at a size: the checker's OWN sum over the raw advance table (Noto Sans, margin 1), never the
  // engine's measuring code, so a planted change to the engine's estimate (03-measure.js) cannot move the checker with it
  // (G2's first plant). The table's numbers themselves are pinned to the font by text.test.mjs (D8). A code point the
  // table lacks takes the table's mean advance, as the engine's does.
  var TABLE = E.widthTables["noto-sans-regular"];
  var MEAN = (function () {
    var sum = 0, n = 0;
    for (var k in TABLE) if (Object.prototype.hasOwnProperty.call(TABLE, k)) { sum += TABLE[k]; n++; }
    return sum / n;
  })();
  function width(text, size) {
    var em = 0;
    for (const ch of text) {
      var w = TABLE[ch.codePointAt(0)];
      em += w === undefined ? MEAN : w;
    }
    return (em / 1000) * size;
  }

  /** What a text primitive draws: its shown text when cut, else its text. */
  function drawn(t) {
    return t.shown !== null ? t.shown : t.text;
  }

  /** The four corners of a text's box, in frame coordinates. */
  function textBox(t) {
    const s = drawn(t), w = width(s, t.size);
    const x0 = t.anchor === "start" ? 0 : t.anchor === "middle" ? -w / 2 : -w;
    const local = [[x0, -ASC * t.size], [x0 + w, -ASC * t.size], [x0 + w, DESC * t.size], [x0, DESC * t.size]];
    const a = (t.rotate * Math.PI) / 180, c = Math.cos(a), n = Math.sin(a);
    return local.map(([x, y]) => [t.x + x * c - y * n, t.y + x * n + y * c]);
  }

  function axes(poly) {
    const out = [];
    for (let i = 0; i < poly.length; i++) {
      const [x1, y1] = poly[i], [x2, y2] = poly[(i + 1) % poly.length];
      const len = Math.hypot(x2 - x1, y2 - y1);
      if (len > 0) out.push([-(y2 - y1) / len, (x2 - x1) / len]);
    }
    return out;
  }

  function project(poly, [ax, ay]) {
    let lo = Infinity, hi = -Infinity;
    for (const [x, y] of poly) {
      const p = x * ax + y * ay;
      if (p < lo) lo = p;
      if (p > hi) hi = p;
    }
    return [lo, hi];
  }

  /** True when two convex polygons overlap by more than TOL on every separating axis. */
  function meet(a, b) {
    for (const ax of axes(a).concat(axes(b))) {
      const [l1, h1] = project(a, ax), [l2, h2] = project(b, ax);
      if (h1 - TOL <= l2 || h2 - TOL <= l1) return false;
    }
    return true;
  }

  /**
   * The axis points of the circle an arc segment lies on that the arc passes through: `A r` from (x0, y0) to (x1, y1)
   * is the path primitive's "circular arc of radius r, the shorter way, clockwise" (the writers' sweep flag 1, large-arc
   * 0). Its centre is on the right of the chord from start to end (y grows down the screen), h = √(r² − (chord/2)²)
   * from the chord's middle; a radius shorter than half the chord grows to it, as a writer draws it (a half circle).
   * The arc is the part of the circle LEFT of the chord (the shorter part; a half circle's clockwise half), so of the
   * circle's four axis points those on that side, by the sign of one cross product, lie on it. No trigonometry.
   */
  function arcExtremes(x0, y0, r, x1, y1) {
    const dx = x1 - x0, dy = y1 - y0, chord = Math.sqrt(dx * dx + dy * dy);
    if (chord === 0) return [];
    const half = chord / 2, rr = r < half ? half : r, h = Math.sqrt(rr * rr - half * half);
    // The unit normal on the right of the travel (dx, dy) on a y-down screen is (-dy, dx) / chord.
    const cx = x0 + dx / 2 - (dy / chord) * h, cy = y0 + dy / 2 + (dx / chord) * h;
    const out = [];
    for (const [px, py] of [[cx + rr, cy], [cx, cy + rr], [cx - rr, cy], [cx, cy - rr]]) {
      // Left of the travel: (p − start) × (end − start) > 0 on a y-down screen.
      if ((px - x0) * dy - (py - y0) * dx > 1e-9) out.push([px, py]);
    }
    return out;
  }

  /**
   * The bounding box [x0, y0, x1, y1] of a mark primitive. A path's box holds its end points, a cubic's two control
   * points (the segment lies inside their hull) and an arc's own extremes (arcExtremes), so an arc that bulges out of
   * the frame is outside it here too.
   */
  function markBox(p) {
    if (p.k === "rect") return [p.x, p.y, p.x + p.w, p.y + p.h];
    if (p.k === "circle") return [p.cx - p.r, p.cy - p.r, p.cx + p.r, p.cy + p.r];
    if (p.k === "line") return [Math.min(p.x1, p.x2), Math.min(p.y1, p.y2), Math.max(p.x1, p.x2), Math.max(p.y1, p.y2)];
    const xs = [], ys = [];
    let atX = null, atY = null;
    for (let i = 0; i < p.d.length; i++) {
      const c = p.d[i], n = c === "M" || c === "L" ? 2 : c === "A" ? 3 : c === "C" ? 6 : 0;
      if (n === 0) continue;
      // A cubic segment lies inside the hull of its end points and its two control points, so all three bound it.
      if (c === "C") for (let q = 1; q < 5; q += 2) {
        xs.push(p.d[i + q]);
        ys.push(p.d[i + q + 1]);
      }
      if (c === "A" && atX !== null) for (const [ex, ey] of arcExtremes(atX, atY, p.d[i + 1], p.d[i + 2], p.d[i + 3])) {
        xs.push(ex);
        ys.push(ey);
      }
      atX = p.d[i + n - 1];
      atY = p.d[i + n];
      xs.push(atX);
      ys.push(atY);
      i += n;
    }
    return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
  }

  /** The texts a picture draws (a text cut to nothing is not drawn). */
  function texts(prims) {
    return prims.filter((p) => p.k === "text" && drawn(p) !== "");
  }

  /** Every number of every primitive, for I5. */
  function numbers(p, out) {
    for (const k of Object.keys(p)) {
      const v = p[k];
      if (typeof v === "number") out.push([k, v]);
      else if (Array.isArray(v)) for (const e of v) if (typeof e === "number") out.push([k, e]);
    }
    return out;
  }

  /**
   * The cut texts of a picture in groups: a title-role text drawn on more than one line (a wrapped subtitle, record §4.4
   * G5) is one group of its lines in order; every other cut text is a group of one.
   */
  function cutGroups(ts) {
    const groups = new Map();
    for (const t of ts) {
      if (t.shown === null) continue;
      const key = t.role === "title" ? JSON.stringify(["title", t.text, t.size, t.weight]) : Symbol();
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(t);
    }
    return [...groups.values()];
  }

  /**
   * I4 over one group, or null when it holds: the lines, each without its ellipsis, joined by one space are a prefix of
   * the full text; only the last line may end with the ellipsis; a group ending without one shows the whole text and is
   * more than one line (one line without an ellipsis is a silent cut); a cut text is never its full text.
   */
  function i4(lines) {
    const text = lines[0].text, heads = [];
    for (let i = 0; i < lines.length; i++) {
      const shown = lines[i].shown, cut = shown.endsWith("…");
      if (cut && i < lines.length - 1) return "an ellipsis before the last line";
      heads.push(cut ? shown.slice(0, -1).trimEnd() : shown);
    }
    const joined = heads.join(" "), last = lines[lines.length - 1].shown;
    if (lines.length === 1 && last === text) return "a cut text equal to its full text";
    if (!text.startsWith(joined)) return "not a prefix of the full text";
    if (!last.endsWith("…") && (lines.length === 1 || joined !== text)) return "cut without an ellipsis";
    return null;
  }

  /**
   * The violations of I1, I2, I4 and I5 in one picture of frame W × H: [{inv, what}]. Also returns the counts it checked,
   * so a caller can floor them (a checker that saw nothing is not a pass).
   */
  function violations(prims, W, H) {
    const out = [], ts = texts(prims), boxes = ts.map(textBox);
    for (let i = 0; i < ts.length; i++) {
      for (let j = i + 1; j < ts.length; j++) {
        if (meet(boxes[i], boxes[j])) out.push({ inv: "I1", what: JSON.stringify(drawn(ts[i])) + " meets " + JSON.stringify(drawn(ts[j])) });
      }
      for (const [x, y] of boxes[i]) {
        if (x < -TOL || y < -TOL || x > W + TOL || y > H + TOL) {
          out.push({ inv: "I2", what: "text " + JSON.stringify(drawn(ts[i])) + " leaves the frame at " + x.toFixed(2) + "," + y.toFixed(2) });
          break;
        }
      }
    }
    for (const lines of cutGroups(ts)) {
      const t = lines[0], why = i4(lines);
      if (why !== null) out.push({ inv: "I4", what: why + ": " + JSON.stringify(lines.map((l) => l.shown)) + " of " + JSON.stringify(t.text) });
    }
    let marks = 0;
    for (const p of prims) {
      if (p.role === "mark" && p.k !== "group" && p.k !== "text") {
        marks++;
        const [x0, y0, x1, y1] = markBox(p);
        if (x0 < -TOL || y0 < -TOL || x1 > W + TOL || y1 > H + TOL) out.push({ inv: "I2", what: "mark of " + p.series + " leaves the frame: " + [x0, y0, x1, y1].join(",") });
      }
      for (const [k, v] of numbers(p, [])) if (!Number.isFinite(v)) out.push({ inv: "I5", what: p.k + "." + k + " = " + v });
    }
    return { violations: out, texts: ts.length, marks };
  }

  return { TOL: TOL, width: width, drawn: drawn, textBox: textBox, meet: meet, markBox: markBox, arcExtremes: arcExtremes, texts: texts, violations: violations };
}
