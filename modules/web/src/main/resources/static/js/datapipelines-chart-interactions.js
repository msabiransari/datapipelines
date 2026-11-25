/*
 * The chart interaction layer (design record 2026-11-14 §4.8, §5.2; look contract §4.2, §4.4, L7, L8; brief 990): ONE
 * file beside the chart adapter, the same for every type. It gives a drawn picture its tooltip card, its hover highlight,
 * the line family's crosshair, the legend switch's clicks and keys, and the keyboard's target with its focus ring.
 *
 * ## The one entry
 * `DatapipelinesChartInteractions.attach(svg, draw, options) → {update(draw), detach()}`.
 * - `draw` = `{output, theme, measure, frame}`: `output` is what `JsRendered.render` returned; `theme`, `measure` and
 *   `frame` are the very values the host passed to THAT call. Every card is measured with that `measure` and coloured
 *   from that `theme`; `update` replaces all four together and nothing of an earlier draw is kept.
 * - `options.toggle(seriesId)`: the host's one callback. The layer asks for a series to be switched; the host owns the
 *   list of switched-off series, draws again and hands the new draw to `update`.
 * The layer resolves no token: the stylesheet's two (`--chart-dim`, `--chart-ease`, for the dimming) are the
 * stylesheet's, the rest arrive resolved in `theme`.
 *
 * ## What it reads
 * The engine's public surface only: the output of `render` (`primitives`, `index`, `summary`), `JsRendered.writeDom`'s
 * sibling `JsRendered.write.append` (the card, the guide and the focus ring are primitives written over the picture),
 * `JsRendered.nearest` (the lookup through the data index), `JsRendered.lookup.at` (a mark found by its own shape, the
 * index's second form, brief 1052), `JsRendered.scales.r2` (a number rounded as the engine rounds it),
 * `JsRendered.measure` (a card's widths by the draw's own `measure`) and the look table's `interaction` fields
 * (`JsRendered.tables.look`). It is not an engine file: no sandbox loads it.
 *
 * ## Two kinds of draw
 * A draw whose `output.index.marks` is a list is "by mark" (brief 1052 D5 to D8): its marks are found by their own
 * shape, its card is the one each mark carries, its highlight is the mark's own (`ring`, `move`) and its keys walk the
 * marks' rows. Every rule for such a draw stands in a branch of its own; a draw without the member runs the code below
 * as it ran before.
 *
 * ## The rendering rule
 * Every line of a card is data and reaches the page as a text node through the engine's writer. No markup string, no
 * `style` attribute, no `href`, no event attribute; events are listeners on the `svg` alone, every one removed by
 * `detach`. Nothing invisible is drawn to catch the pointer: the pointer is looked up through the index and the
 * primitives' own boxes.
 */
(function () {
  "use strict";

  /** The layer's only fixed texts (contract §4.4, step 4): the count line of a reduced card. */
  var MORE = " more";
  var PLUS = "+";
  /** The keys the keyboard's target answers (D5). */
  var MOVES = { ArrowLeft: 1, ArrowRight: 1, ArrowUp: 1, ArrowDown: 1, Home: 1, End: 1 };
  var EPS = 1e-9;
  /** Coordinates are compared at the primitives' precision (two decimals). */
  var SAME = 0.005;
  /**
   * Two places along the band axis are one when they differ by no more than this: each is rounded to two decimals on
   * its own, so the segments of one stack can stand 0.01 apart (measured on parity-bar-stacked).
   */
  var ONE_PLACE = 0.05;

  function engine() {
    var root = typeof globalThis !== "undefined" ? globalThis : typeof window !== "undefined" ? window : {};
    var R = root.JsRendered;
    if (!R || typeof R.render !== "function" || !R.write || typeof R.write.append !== "function") {
      throw new Error("the chart engine (JsRendered) is not loaded: the page loads its files before the interaction layer");
    }
    return R;
  }

  // ===================================================================================== the pure half

  /**
   * The card's measures for one draw (contract §4.4, L8; the look table's `interaction` fields, D4a): the text size s
   * (`--chart-text`), and as multiples of it the line height h, the padding p, the key's width, the gap and the
   * greatest width G; the bold weight, the distance from the anchor, the crosshair dot's radius and the focus ring's gap.
   */
  function measures(R, theme) {
    var t = R.theme.resolve(theme);
    var look = R.tables.look;
    var s = t.n("--chart-text");
    return {
      theme: t,
      s: s,
      h: look("tipLine") * s,
      p: look("tipPad") * s,
      key: look("tipKey") * s,
      gap: look("tipGap") * s,
      G: look("tipMax") * s,
      bold: look("tipValueWeight"),
      join: look("tipTotalJoin"),
      dist: look("tipDistance"),
      dot: t.n("--chart-point") + look("crosshairDot"),
      focus: look("focusGap") * s,
      ascent: look("textAscent"),
      descent: look("textDescent"),
    };
  }

  /** W, w and k of a frame (contract §4.4): the box's greatest width, the content width and the lines it holds. */
  function bounds(frame, m) {
    var W = Math.min(frame.w, m.G);
    var w = W - 2 * m.p;
    var room = frame.h - 2 * m.p;
    var k = room < m.h ? 0 : Math.floor(room / m.h + EPS);
    return { W: W, w: w, k: k };
  }

  /** text cut to its longest beginning that fits max together with "…" (always with it); "" when one character does not. */
  function withEllipsis(R, width, text, size, weight, max) {
    var cps = R.measure.codePoints(text);
    var prefix = function (n) {
      var out = "";
      for (var i = 0; i < n; i++) out += String.fromCodePoint(cps[i]);
      return R.measure.trimEnd(out) + R.measure.ELLIPSIS;
    };
    if (cps.length === 0 || width(prefix(1), size, weight) > max) return "";
    var lo = 1;
    var hi = cps.length;
    while (lo < hi) {
      var mid = (lo + hi + 1) >>> 1;
      if (width(prefix(mid), size, weight) <= max) lo = mid;
      else hi = mid - 1;
    }
    return prefix(lo);
  }

  /** A `tooltip` field's line (G7, "title: value") as its two parts: split at the first ": ". */
  function fieldParts(line) {
    var at = line.indexOf(": ");
    return at < 0 ? { title: "", value: line } : { title: line.slice(0, at), value: line.slice(at + 2) };
  }

  /**
   * One line of a mark's card fitted to the content width w (contract §4.4, step 1): {line, ok, w, ...the parts drawn}.
   * A row needs its key, one gap and its WHOLE value; its name takes what is left after a second gap. A field is a row
   * without a key. A heading is cut, or left out when not even "…" fits.
   */
  function fitLine(R, width, line, w, m) {
    var s = m.s;
    if (line.kind === "heading") {
      // A stacked bar's heading is the category, the join and the stack's total (brief 990 r2 D2), shown whole or not
      // at all: a total is a value, and a value is never cut (L8); the category alone is then cut as any heading is.
      if (typeof line.total === "string" && line.total !== "") {
        var joined = line.text + m.join + line.total;
        var jw = width(joined, s, 400);
        if (jw <= w + EPS) return { line: line, ok: true, text: joined, w: jw };
      }
      var head = R.measure.fit(width, line.text, s, 400, w);
      return { line: line, ok: head !== "", text: head, w: head === "" ? 0 : width(head, s, 400) };
    }
    var weight = line.kind === "row" ? m.bold : 400;
    var vw = width(line.value, s, weight);
    // A row that stands for no colour (brief 1052 D6, contract §4.7) keeps no room for a key and is fitted as a field
    // is: its name cut, its value never, and a name that cannot show one character does not fit. Its value keeps the
    // row's weight.
    var plain = line.kind === "row" && line.plain === true;
    var lead = line.kind === "row" && !plain ? m.key + m.gap : 0;
    if (lead + vw > w + EPS) return { line: line, ok: false };
    var nameWeight = line.kind === "row" && line.bold ? m.bold : 400;
    var label = line.kind === "row" ? line.name : line.title;
    var room = w - lead - vw - m.gap;
    var shown = label === "" || room <= 0 ? "" : R.measure.fit(width, label, s, nameWeight, room);
    if ((line.kind === "field" || plain) && label !== "" && shown === "") return { line: line, ok: false };
    var nw = shown === "" ? 0 : width(shown, s, nameWeight) + m.gap;
    return { line: line, ok: true, label: shown, labelWeight: nameWeight, labelW: nw, valueW: vw, w: lead + nw + vw };
  }

  /**
   * A mark's card (contract §4.4, L8; brief 990 D2a): what it holds, decided before anything is drawn. lines = the full
   * card in its own order: [{kind: "heading", text}], then rows {kind: "row", id, name, value, colour, color, bold},
   * then fields {kind: "field", title, value}. hovered = the hovered series' id. A card of one mark (brief 1052 D6)
   * marks its kept row itself (`kept: true` on one row), and then `hovered` plays no part. Returns null (no card) or
   * {W, w, k, lines: [fitted], n, box: {w, h}}; a count line is {line: {kind: "more"}, text, w}.
   */
  function markCard(R, width, lines, hovered, frame, m) {
    var b = bounds(frame, m);
    if (b.k === 0 || b.w <= 0) return null;
    var fitted = lines.map(function (l) {
      return fitLine(R, width, l, b.w, m);
    });
    var hov = -1;
    for (var i = 0; i < fitted.length; i++) if (fitted[i].line.kind === "row" && fitted[i].line.id === hovered) hov = i;
    for (var q = 0; q < fitted.length; q++) {
      if (fitted[q].line.kind === "row" && fitted[q].line.kept === true) {
        hov = q;
        break;
      }
    }
    if (hov < 0 || !fitted[hov].ok) return null;
    var all = fitted.every(function (f) {
      return f.ok;
    });
    var shown;
    if (all && fitted.length <= b.k) shown = fitted;
    else {
      shown = null;
      if (b.k >= 3) shown = reduced(fitted, hov, b, m, width);
      if (shown === null) shown = twoOrOne(fitted, hov, b.k);
    }
    var widest = 0;
    for (var j = 0; j < shown.length; j++) widest = Math.max(widest, shown[j].w);
    return { W: b.W, w: b.w, k: b.k, lines: shown, n: fitted.length - shown.filter(notMore).length, box: { w: widest + 2 * m.p, h: 2 * m.p + shown.length * m.h } };
  }

  function notMore(f) {
    return f.line.kind !== "more";
  }

  /** Step 4: the heading, the hovered row first, the others in order while one line stays free, then "+n more". */
  function reduced(fitted, hov, b, m, width) {
    var out = [];
    if (fitted[0].line.kind === "heading" && fitted[0].ok) out.push(fitted[0]);
    out.push(fitted[hov]);
    for (var i = 0; i < fitted.length; i++) {
      if (out.length >= b.k - 1) break;
      if (i === hov || !fitted[i].ok || fitted[i].line.kind === "heading") continue;
      out.push(fitted[i]);
    }
    var n = fitted.length - out.length;
    var text = PLUS + String(n) + MORE;
    if (width(text, m.s, 400) > b.w + EPS) text = PLUS + String(n);
    if (width(text, m.s, 400) > b.w + EPS) return null;
    out.push({ line: { kind: "more" }, ok: true, text: text, w: width(text, m.s, 400) });
    return out;
  }

  /** Step 5: with k of 2 the heading and the hovered row; with k of 1 the row alone. */
  function twoOrOne(fitted, hov, k) {
    if (k >= 2 && fitted[0].line.kind === "heading" && fitted[0].ok) return [fitted[0], fitted[hov]];
    return [fitted[hov]];
  }

  /**
   * A cut text's card (contract §4.4; brief 990 D3a): the text alone, wrapped to w. Returns null or {lines, box}. The
   * guards first: no k, no w, or a w narrower than the text's widest character: no card, and no wrapping is tried.
   */
  function textCard(R, width, text, frame, m) {
    var b = bounds(frame, m);
    if (b.k === 0 || b.w <= 0) return null;
    var cps = R.measure.codePoints(text);
    var widest = 0;
    for (var i = 0; i < cps.length; i++) widest = Math.max(widest, width(String.fromCodePoint(cps[i]), m.s, 400));
    if (cps.length === 0 || b.w + EPS < widest) return null;
    var lines = wrap(R, width, text, b.w, m.s);
    if (lines.length > b.k) {
      var last = withEllipsis(R, width, lines[b.k - 1], m.s, 400, b.w);
      if (last === "") return null;
      lines = lines.slice(0, b.k - 1).concat([last]);
    }
    var wide = 0;
    for (var j = 0; j < lines.length; j++) wide = Math.max(wide, width(lines[j], m.s, 400));
    return { W: b.W, w: b.w, k: b.k, lines: lines, box: { w: wide + 2 * m.p, h: 2 * m.p + lines.length * m.h } };
  }

  /** text wrapped to w at its spaces; a word wider than w broken where its line is full (every line holds one character). */
  function wrap(R, width, text, w, s) {
    var words = text.split(" ");
    var lines = [];
    var cur = "";
    for (var i = 0; i < words.length; i++) {
      var word = words[i];
      var joined = cur === "" ? word : cur + " " + word;
      if (width(joined, s, 400) <= w + EPS) {
        cur = joined;
        continue;
      }
      if (cur !== "") lines.push(cur);
      cur = "";
      if (width(word, s, 400) <= w + EPS) {
        cur = word;
        continue;
      }
      var cps = R.measure.codePoints(word);
      for (var c = 0; c < cps.length; c++) {
        var ch = String.fromCodePoint(cps[c]);
        if (cur !== "" && width(cur + ch, s, 400) > w + EPS) {
          lines.push(cur);
          cur = "";
        }
        cur += ch;
      }
    }
    if (cur !== "" || lines.length === 0) lines.push(cur);
    return lines;
  }

  /**
   * Where a box lies (contract §4.4 "Place"): dist from the anchor, right of it and above it, flipped at the frame's
   * edges; with room on neither side at that distance it is moved along that axis until it lies inside the frame.
   */
  function place(box, anchor, frame, dist) {
    var axis = function (at, size, limit, after) {
      var first = after ? at + dist : at - dist - size;
      var second = after ? at - dist - size : at + dist;
      if (first >= -EPS && first + size <= limit + EPS) return first;
      if (second >= -EPS && second + size <= limit + EPS) return second;
      return Math.max(0, Math.min(limit - size, first));
    };
    return { x: axis(anchor.x, box.w, frame.w, true), y: axis(anchor.y, box.h, frame.h, false) };
  }

  // -------------------------------------------------------------------------- the model of one draw

  /** The box of a path's coordinates. */
  function pathBox(d, ARITY) {
    var x0 = Infinity;
    var y0 = Infinity;
    var x1 = -Infinity;
    var y1 = -Infinity;
    for (var i = 0; i < d.length; i++) {
      var n = ARITY[d[i]];
      if (n === 0) continue;
      var x = d[i + n - 1];
      var y = d[i + n];
      if (d[i] === "A") {
        x = d[i + 2];
        y = d[i + 3];
      }
      x0 = Math.min(x0, x);
      x1 = Math.max(x1, x);
      y0 = Math.min(y0, y);
      y1 = Math.max(y1, y);
      i += n;
    }
    return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
  }

  /** The y of a stroked path at x: a vertex at that x, else the segment over it (a cubic solved for x); null when none. */
  function pathY(d, x) {
    var cx = 0;
    var cy = 0;
    for (var v = 0; v < d.length; v++) {
      if (d[v] === "M" || d[v] === "L") {
        if (Math.abs(d[v + 1] - x) < SAME) return d[v + 2];
        v += 2;
      } else if (d[v] === "C") {
        if (Math.abs(d[v + 5] - x) < SAME) return d[v + 6];
        v += 6;
      }
    }
    for (var i = 0; i < d.length; i++) {
      var c = d[i];
      if (c === "M") {
        cx = d[i + 1];
        cy = d[i + 2];
        i += 2;
      } else if (c === "L") {
        var lx = d[i + 1];
        var ly = d[i + 2];
        if ((x - cx) * (x - lx) <= 0 && lx !== cx) return cy + ((x - cx) / (lx - cx)) * (ly - cy);
        cx = lx;
        cy = ly;
        i += 2;
      } else if (c === "C") {
        var p = [cx, cy, d[i + 1], d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i + 6]];
        if ((x - p[0]) * (x - p[6]) <= 0 && p[6] !== p[0]) return cubicY(p, x);
        cx = p[6];
        cy = p[7];
        i += 6;
      } else if (c === "A") i += 3;
    }
    return null;
  }

  function cubicY(p, x) {
    var at = function (t, a, b, c, e) {
      var u = 1 - t;
      return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * e;
    };
    var lo = 0;
    var hi = 1;
    var rising = p[6] > p[0];
    for (var n = 0; n < 50; n++) {
      var mid = (lo + hi) / 2;
      var mx = at(mid, p[0], p[2], p[4], p[6]);
      if (mx < x === rising) lo = mid;
      else hi = mid;
    }
    return at((lo + hi) / 2, p[1], p[3], p[5], p[7]);
  }

  /**
   * What a draw offers the pointer and the keys: the series with drawn marks (data order; each its marks in the
   * index's order), the legend entries this draw draws, the cut texts, and the geometry to find and anchor each.
   * Nothing here is a rule of the layer's own about which series is shown: the index and the primitives say it.
   */
  function modelOf(R, output, width) {
    var model = { series: [], entries: [], cuts: [], titles: {}, colours: {}, markColours: {}, bars: {}, lines: {}, totals: null, crosshair: true, plot: null, axis: "x", order: {}, output: output };
    if (!output || !output.index || !Array.isArray(output.primitives)) return model;
    var prims = output.primitives;
    var summary = output.summary || { series: [] };
    for (var t = 0; t < summary.series.length; t++) {
      model.titles[summary.series[t].id] = summary.series[t].title;
      model.order[summary.series[t].id] = t;
    }
    model.plot = output.index.plot;
    model.axis = output.index.axis || "x";
    // A stacked bar's totals by datum (the engine's text, brief 990 r2 D1); absent on every other draw.
    if (Array.isArray(output.index.totals)) model.totals = output.index.totals;
    for (var i = 0; i < prims.length; i++) {
      var p = prims[i];
      if (p.role === "mark" && p.series !== null && p.k !== "group" && p.k !== "text") {
        var own = p.fill !== null && p.fill !== undefined ? { token: p.fill, css: p.color } : { token: p.stroke, css: p.strokeColor };
        if (!model.colours[p.series]) model.colours[p.series] = own;
        if (p.datum !== null && p.datum !== undefined) {
          model.crosshair = false;
          // A mark's own colour (brief 1057 D1): a bar coloured by value fills the marks of ONE series differently.
          var mkey = p.series + "\u0000" + p.datum;
          if (!model.markColours[mkey]) model.markColours[mkey] = own;
          model.bars[p.series + "\u0000" + p.datum] = p.k === "rect" ? { x: p.x, y: p.y, w: p.w, h: p.h } : p.k === "circle" ? { x: p.cx - p.r, y: p.cy - p.r, w: 2 * p.r, h: 2 * p.r } : pathBox(p.d, R.prim.ARITY);
        } else if (p.k === "path" && p.stroke !== null && p.fill === null) {
          (model.lines[p.series] = model.lines[p.series] || []).push(p.d);
        }
      }
      if (p.k === "group" && p.role === "legend" && p.legend !== null) {
        model.entries.push({ id: p.legend, on: p.on, box: groupBox(R, prims, i, width) });
      }
      if (p.k === "text" && p.shown !== null) model.cuts.push({ i: i, text: p.text, box: textBox(R, p, width) });
    }
    if (Array.isArray(output.index.marks)) byMark(model, output.index.marks, prims);
    var ix = output.index.series || [];
    for (var s = 0; s < ix.length; s++) {
      var e = ix[s];
      if (!e.on || e.at.length === 0) continue;
      var marks = [];
      for (var k = 0; k < e.at.length; k++) marks.push({ datum: e.datum[k], at: e.at[k], tip: e.tip[k] });
      model.series.push({ id: e.id, marks: marks });
    }
    return model;
  }

  /**
   * A draw by mark (brief 1052 D5): model.marks = the index's marks, and model.held[n] = the positions in the
   * primitives of mark n's own primitives (role `mark`, not a group, not a text, its series and datum). The index has
   * no position, so model.series stays empty and neither the crosshair nor a bar's rule runs.
   */
  function byMark(model, marks, prims) {
    model.marks = marks;
    model.held = marks.map(function (mk) {
      var at = [];
      for (var i = 0; i < prims.length; i++) {
        var p = prims[i];
        if (p.role === "mark" && p.k !== "group" && p.k !== "text" && p.series === mk.series && p.datum === mk.datum) at.push(i);
      }
      return at;
    });
  }

  /** The position of the mark (id, datum) in model.marks, or -1. */
  function markAt(model, id, datum) {
    if (!model.marks) return -1;
    for (var i = 0; i < model.marks.length; i++) if (model.marks[i].series === id && model.marks[i].datum === datum) return i;
    return -1;
  }

  /** A text primitive's box: its shown text measured as the draw measured it; a rotated text by its four corners. */
  function textBox(R, p, width) {
    var shown = p.shown !== null ? p.shown : p.text;
    var w = width(shown, p.size, p.weight);
    var look = R.tables.look;
    var x0 = p.anchor === "middle" ? p.x - w / 2 : p.anchor === "end" ? p.x - w : p.x;
    return { x: x0, y: p.y - look("textAscent") * p.size, w: w, h: (look("textAscent") + look("textDescent")) * p.size, rotate: p.rotate, ox: p.x, oy: p.y };
  }

  /** The union of the boxes of a group's children. */
  function groupBox(R, prims, at, width) {
    var n = prims[at].n;
    var box = null;
    for (var i = at + 1; i <= at + n && i < prims.length; i++) {
      var p = prims[i];
      // A dot key (brief 1054 D7) counts with the square around its circle, so an entry is found by its key too.
      var b = p.k === "text" ? textBox(R, p, width) : p.k === "rect" ? { x: p.x, y: p.y, w: p.w, h: p.h } : p.k === "circle" ? { x: p.cx - p.r, y: p.cy - p.r, w: 2 * p.r, h: 2 * p.r } : null;
      if (b === null) continue;
      box = box === null ? { x: b.x, y: b.y, w: b.w, h: b.h } : union(box, b);
    }
    return box;
  }

  function union(a, b) {
    var x0 = Math.min(a.x, b.x);
    var y0 = Math.min(a.y, b.y);
    return { x: x0, y: y0, w: Math.max(a.x + a.w, b.x + b.w) - x0, h: Math.max(a.y + a.h, b.y + b.h) - y0 };
  }

  /** Whether pos lies in box (a rotated text's box is tested in its own turned frame). */
  function inside(box, pos) {
    if (!box) return false;
    var x = pos.x;
    var y = pos.y;
    if (box.rotate) {
      var a = (-box.rotate * Math.PI) / 180;
      var dx = x - box.ox;
      var dy = y - box.oy;
      x = box.ox + dx * Math.cos(a) - dy * Math.sin(a);
      y = box.oy + dx * Math.sin(a) + dy * Math.cos(a);
    }
    return x >= box.x - SAME && x <= box.x + box.w + SAME && y >= box.y - SAME && y <= box.y + box.h + SAME;
  }

  function seriesOf(model, id) {
    for (var i = 0; i < model.series.length; i++) if (model.series[i].id === id) return i;
    return -1;
  }

  function markOf(series, datum) {
    for (var i = 0; i < series.marks.length; i++) if (series.marks[i].datum === datum) return i;
    return -1;
  }

  /** A line point's y: on its series' drawn line at the point's x. */
  function pointY(model, id, at) {
    var ds = model.lines[id] || [];
    for (var i = 0; i < ds.length; i++) {
      var y = pathY(ds[i], at);
      if (y !== null) return y;
    }
    for (var c = 0; c < model.output.primitives.length; c++) {
      var p = model.output.primitives[c];
      if (p.k === "circle" && p.role === "mark" && p.series === id && Math.abs(p.cx - at) < SAME) return p.cy;
    }
    return null;
  }

  /** The point a mark is anchored at: a bar's centre; a line point on its line. */
  function anchorOf(model, id, datum) {
    if (model.marks) {
      var n = markAt(model, id, datum);
      return n < 0 ? null : model.marks[n].anchor;
    }
    var s = model.series[seriesOf(model, id)];
    var m = s ? s.marks[markOf(s, datum)] : null;
    if (!m) return null;
    if (!model.crosshair) {
      var b = model.bars[id + "\u0000" + datum];
      return b ? { x: b.x + b.w / 2, y: b.y + b.h / 2 } : null;
    }
    var y = pointY(model, id, m.at);
    return { x: m.at, y: y === null ? model.plot.y + model.plot.h / 2 : y };
  }

  /**
   * What the pointer is on (D2a "Which input decides", D3): {mark: {id, datum} | null, entry: id | null, cut: n | null}.
   * A mark is looked up through the index (JsRendered.nearest) inside the plot: a bar by its centre along the band axis
   * (a stack's segments, which share it, by the one under the pointer); a line point by its x, then the series whose
   * point at that x is nearest the pointer, the first in the data's order when two are equally near.
   */
  function lookup(R, model, pos) {
    var hit = { mark: null, entry: null, cut: null };
    for (var e = 0; e < model.entries.length; e++) if (inside(model.entries[e].box, pos)) hit.entry = model.entries[e].id;
    for (var c = 0; c < model.cuts.length; c++) if (inside(model.cuts[c].box, pos)) hit.cut = c;
    var plot = model.plot;
    if (model.marks) {
      // A draw by mark (brief 1052 D5): the index's own lookup, at the look table's reach; always the index, so a moved
      // slice is found where it was drawn.
      if (hit.entry === null && hit.cut === null) {
        var n = R.lookup.at(model.output.index, pos.x, pos.y, R.tables.look("markReach"));
        if (n >= 0) hit.mark = { id: model.marks[n].series, datum: model.marks[n].datum };
      }
      return hit;
    }
    if (hit.entry !== null || hit.cut !== null || !plot || model.series.length === 0) return hit;
    if (pos.x < plot.x - SAME || pos.x > plot.x + plot.w + SAME || pos.y < plot.y - SAME || pos.y > plot.y + plot.h + SAME) return hit;
    var along = model.axis === "y" ? pos.y : pos.x;
    var best = [];
    var bestD = Infinity;
    for (var s = 0; s < model.series.length; s++) {
      var r = R.nearest(model.output.index, model.series[s].id, along);
      if (r === null) continue;
      var d = Math.abs(r.at - along);
      if (d < bestD - ONE_PLACE) {
        best = [];
        bestD = d;
      }
      if (d <= bestD + ONE_PLACE) best.push({ id: model.series[s].id, datum: r.datum, at: r.at });
    }
    if (best.length === 0) return hit;
    hit.mark = model.crosshair ? pointUnder(model, best, pos) : barUnder(model, best, pos);
    return hit;
  }

  function barUnder(model, best, pos) {
    if (best.length === 1) return { id: best[0].id, datum: best[0].datum };
    var cross = model.axis === "y" ? pos.x : pos.y;
    var pick = null;
    var pickD = Infinity;
    for (var i = 0; i < best.length; i++) {
      var b = model.bars[best[i].id + "\u0000" + best[i].datum];
      if (!b) continue;
      var lo = model.axis === "y" ? b.x : b.y;
      var hi = lo + (model.axis === "y" ? b.w : b.h);
      var d = cross < lo ? lo - cross : cross > hi ? cross - hi : 0;
      if (d < pickD - SAME) {
        pick = best[i];
        pickD = d;
      }
    }
    pick = pick || best[0];
    return { id: pick.id, datum: pick.datum };
  }

  function pointUnder(model, best, pos) {
    var datum = best[0].datum;
    var pick = null;
    var pickD = Infinity;
    for (var s = 0; s < model.series.length; s++) {
      var ser = model.series[s];
      var k = markOf(ser, datum);
      if (k < 0) continue;
      var y = pointY(model, ser.id, ser.marks[k].at);
      var d = y === null ? Infinity : Math.abs(y - pos.y);
      if (pick === null || d < pickD - SAME) {
        pick = ser.id;
        pickD = d;
      }
    }
    return { id: pick, datum: datum };
  }

  /**
   * The full card of a mark (contract §4.4; record §4.8): the heading (the category or the date: the mark's first tip
   * line; on a stacked bar with the stack's total from the index, joined when the card is drawn), one row per series drawn at that x (a bar's whole group; a stack top segment first), then the hovered mark's
   * `tooltip` fields. A row's value is its tip line after "name: ".
   */
  function cardLines(model, id, datum) {
    var rows = [];
    var hovered = null;
    for (var s = 0; s < model.series.length; s++) {
      var ser = model.series[s];
      var k = markOf(ser, datum);
      if (k < 0) continue;
      var tip = ser.marks[k].tip || [];
      var name = model.titles[ser.id] === undefined ? ser.id : model.titles[ser.id];
      var line = tip.length > 1 ? tip[1] : "";
      var value = line.indexOf(name + ": ") === 0 ? line.slice(name.length + 2) : line;
      // The row's key is its own mark's colour, else its series' (brief 1057 D2).
      var colour = model.markColours[ser.id + "\u0000" + datum] || model.colours[ser.id] || { token: "--chart-ink", css: model.theme ? model.theme.css("--chart-ink") : "" };
      var row = { kind: "row", id: ser.id, name: name, value: value, colour: colour.token, color: colour.css, bold: false, at: ser.marks[k].at };
      rows.push(row);
      if (ser.id === id) hovered = { row: row, tip: tip };
    }
    if (hovered === null) return null;
    if (!model.crosshair && rows.length > 1 && stacked(rows)) {
      rows.sort(function (a, b) {
        var ba = model.bars[a.id + "\u0000" + datum];
        var bb = model.bars[b.id + "\u0000" + datum];
        return model.axis === "y" ? bb.x + bb.w - (ba.x + ba.w) : ba.y - bb.y;
      });
    }
    if (!model.crosshair && rows.length > 1) hovered.row.bold = true;
    var total = model.totals !== null && typeof model.totals[datum] === "string" ? model.totals[datum] : null;
    var lines = [{ kind: "heading", text: hovered.tip.length > 0 ? hovered.tip[0] : "", total: total }].concat(rows);
    for (var f = 2; f < hovered.tip.length; f++) {
      var parts = fieldParts(hovered.tip[f]);
      lines.push({ kind: "field", title: parts.title, value: parts.value });
    }
    return lines;
  }

  /** A bar group is a stack when its segments share one place along the band axis. */
  function stacked(rows) {
    for (var i = 1; i < rows.length; i++) if (Math.abs(rows[i].at - rows[0].at) > ONE_PLACE) return false;
    return true;
  }

  /**
   * The full card of mark n of a draw by mark (brief 1052 D6): its heading when it is a text that is not empty, its rows
   * in the template's order (`kept` on rows[keep]; `plain` on a row that stands for no colour), then its extra lines as
   * fields. Nothing is read from the tips.
   */
  function markLines(model, n) {
    var mk = model.marks[n];
    var c = mk.card;
    var lines = [];
    if (typeof c.heading === "string" && c.heading !== "") lines.push({ kind: "heading", text: c.heading, total: null });
    for (var r = 0; r < c.rows.length; r++) {
      var row = c.rows[r];
      lines.push({ kind: "row", id: mk.series, name: row.name, value: row.value, colour: row.colour, color: row.color, bold: row.bold, kept: r === c.keep, plain: row.colour === null });
    }
    for (var f = 0; f < c.extra.length; f++) {
      var parts = fieldParts(c.extra[f]);
      lines.push({ kind: "field", title: parts.title, value: parts.value });
    }
    return lines;
  }

  // ------------------------------------------------------------------------------------ the keyboard

  function mark(id, datum) {
    return { kind: "mark", id: id, datum: datum };
  }

  /** Entering (D5): the first mark; with none the first legend entry; with neither the first cut text; else none. */
  function enter(model) {
    if (model.marks) {
      var first = keysOf(model);
      if (first.length > 0) return markTarget(model, first[0]);
    }
    if (model.series.length > 0) return mark(model.series[0].id, model.series[0].marks[0].datum);
    if (model.entries.length > 0) return { kind: "entry", id: model.entries[0].id };
    if (model.cuts.length > 0) return { kind: "cut", n: 0 };
    return null;
  }

  function lastMarkOfFirst(model) {
    if (model.marks) {
      var rows = rowsOf(model);
      if (rows.length === 0) return null;
      var row = rows[0].at;
      return markTarget(model, row[row.length - 1]);
    }
    if (model.series.length === 0) return null;
    var first = model.series[0];
    return mark(first.id, first.marks[first.marks.length - 1].datum);
  }

  function entryIndex(model, id) {
    for (var i = 0; i < model.entries.length; i++) if (model.entries[i].id === id) return i;
    return -1;
  }

  /** A series' mark at datum; else its mark nearest to it in the draw's x order, the lower x when two are equally near. */
  function nearestMark(series, datum) {
    var k = markOf(series, datum);
    if (k >= 0) return series.marks[k].datum;
    var pick = series.marks[0].datum;
    for (var i = 1; i < series.marks.length; i++) {
      var d = series.marks[i].datum;
      var nd = Math.abs(d - datum);
      var pd = Math.abs(pick - datum);
      if (nd < pd || (nd === pd && d < pick)) pick = d;
    }
    return pick;
  }

  /**
   * The keyboard's next target (D5), pure: (model, target, key) → {target, toggle: id | null, handled}. Every rule of
   * the brief's D5 is one branch here; a key a state does not answer leaves the target as it was.
   */
  function keyTarget(model, target, key) {
    var out = { target: target, toggle: null, handled: false };
    if (key === "Escape") return { target: null, toggle: null, handled: true };
    if (target === null) {
      if (MOVES[key] !== 1) return out;
      return { target: enter(model), toggle: null, handled: true };
    }
    out.handled = MOVES[key] === 1 || key === "Enter" || key === " ";
    if (target.kind === "mark" && model.marks) return keyByMark(model, target, key, out);
    if (target.kind === "mark") {
      var s = seriesOf(model, target.id);
      var ser = model.series[s];
      var i = markOf(ser, target.datum);
      if (key === "ArrowLeft" && i > 0) out.target = mark(ser.id, ser.marks[i - 1].datum);
      else if (key === "ArrowRight") {
        if (i < ser.marks.length - 1) out.target = mark(ser.id, ser.marks[i + 1].datum);
        else if (entryIndex(model, ser.id) >= 0) out.target = { kind: "entry", id: ser.id };
        else if (model.entries.length > 0) out.target = { kind: "entry", id: model.entries[0].id };
        else if (model.cuts.length > 0) out.target = { kind: "cut", n: 0 };
      } else if (key === "ArrowUp" && s > 0) out.target = mark(model.series[s - 1].id, nearestMark(model.series[s - 1], target.datum));
      else if (key === "ArrowDown" && s < model.series.length - 1) out.target = mark(model.series[s + 1].id, nearestMark(model.series[s + 1], target.datum));
      else if (key === "Home") out.target = mark(ser.id, ser.marks[0].datum);
      else if (key === "End") out.target = mark(ser.id, ser.marks[ser.marks.length - 1].datum);
      return out;
    }
    if (target.kind === "entry") {
      var j = entryIndex(model, target.id);
      if (key === "ArrowLeft") out.target = j > 0 ? { kind: "entry", id: model.entries[j - 1].id } : lastMarkOfFirst(model) || target;
      else if (key === "ArrowRight") out.target = j < model.entries.length - 1 ? { kind: "entry", id: model.entries[j + 1].id } : model.cuts.length > 0 ? { kind: "cut", n: 0 } : target;
      else if (key === "Enter" || key === " ") out.toggle = target.id;
      return out;
    }
    var n = target.n;
    if (key === "ArrowLeft") {
      if (n > 0) out.target = { kind: "cut", n: n - 1 };
      else if (model.entries.length > 0) out.target = { kind: "entry", id: model.entries[model.entries.length - 1].id };
      else out.target = lastMarkOfFirst(model) || target;
    } else if (key === "ArrowRight" && n < model.cuts.length - 1) out.target = { kind: "cut", n: n + 1 };
    return out;
  }

  /**
   * The target found again in a new draw, by identity (D5 "After every redraw"): a mark still drawn stays, else its
   * series' first mark, else its series' entry, else as on entering; an entry whose series still has one stays, else
   * that series' first mark, else entering; a cut text at the same place in the new order, else entering.
   */
  function findAgain(model, target) {
    if (target === null) return null;
    if (model.marks && (target.kind === "mark" || target.kind === "entry")) {
      // A draw by mark (brief 1052 D8): a key target still drawn stays; else the first key target of its series; else
      // that series' entry; else as on entering. An entry stays while drawn, else its series' first key target.
      var keys = keysOf(model);
      var n = target.kind === "mark" ? markAt(model, target.id, target.datum) : -1;
      if (target.kind === "entry" && entryIndex(model, target.id) >= 0) return target;
      if (n >= 0 && model.marks[n].key === true) return target;
      for (var k = 0; k < keys.length; k++) if (model.marks[keys[k]].series === target.id) return markTarget(model, keys[k]);
      if (target.kind === "mark" && entryIndex(model, target.id) >= 0) return { kind: "entry", id: target.id };
      return enter(model);
    }
    if (target.kind === "mark") {
      var s = seriesOf(model, target.id);
      if (s >= 0 && markOf(model.series[s], target.datum) >= 0) return target;
      if (s >= 0) return mark(target.id, model.series[s].marks[0].datum);
      if (entryIndex(model, target.id) >= 0) return { kind: "entry", id: target.id };
      return enter(model);
    }
    if (target.kind === "entry") {
      if (entryIndex(model, target.id) >= 0) return target;
      var e = seriesOf(model, target.id);
      return e >= 0 ? mark(target.id, model.series[e].marks[0].datum) : enter(model);
    }
    return target.n < model.cuts.length ? target : enter(model);
  }

  /** The key targets of a draw by mark (D8): the positions of the marks with key true, in the list's order. */
  function keysOf(model) {
    var out = [];
    for (var i = 0; i < model.marks.length; i++) if (model.marks[i].key === true) out.push(i);
    return out;
  }

  /** The rows of the key targets (D8): [{row, at: [positions in the list's order]}], ordered by the row's number. */
  function rowsOf(model) {
    var rows = [];
    var keys = keysOf(model);
    for (var i = 0; i < keys.length; i++) {
      var r = model.marks[keys[i]].row;
      var found = null;
      for (var j = 0; j < rows.length; j++) if (rows[j].row === r) found = rows[j];
      if (found === null) rows.push((found = { row: r, at: [] }));
      found.at.push(keys[i]);
    }
    rows.sort(function (a, b) {
      return a.row - b.row;
    });
    return rows;
  }

  function markTarget(model, n) {
    return mark(model.marks[n].series, model.marks[n].datum);
  }

  /**
   * A key on a mark of a draw by mark (D8): Left and Right along the mark's row (past the last: the mark's series'
   * entry, else the first entry, else the first cut text, else it stays); Home and End the row's first and last; Up and
   * Down the row before and after, at its target whose anchor is nearest (the first of two equally near).
   */
  function keyByMark(model, target, key, out) {
    var n = markAt(model, target.id, target.datum);
    if (n < 0) return out;
    var rows = rowsOf(model);
    var r = -1;
    for (var j = 0; j < rows.length; j++) if (rows[j].row === model.marks[n].row) r = j;
    if (r < 0) return out;
    var row = rows[r].at;
    var i = row.indexOf(n);
    if (key === "ArrowLeft" && i > 0) out.target = markTarget(model, row[i - 1]);
    else if (key === "ArrowRight") {
      if (i < row.length - 1) out.target = markTarget(model, row[i + 1]);
      else if (target.id !== null && entryIndex(model, target.id) >= 0) out.target = { kind: "entry", id: target.id };
      else if (model.entries.length > 0) out.target = { kind: "entry", id: model.entries[0].id };
      else if (model.cuts.length > 0) out.target = { kind: "cut", n: 0 };
    } else if (key === "Home") out.target = markTarget(model, row[0]);
    else if (key === "End") out.target = markTarget(model, row[row.length - 1]);
    else if ((key === "ArrowUp" && r > 0) || (key === "ArrowDown" && r < rows.length - 1)) {
      var next = rows[key === "ArrowUp" ? r - 1 : r + 1].at;
      var a = model.marks[n].anchor;
      var pick = next[0];
      var best = Infinity;
      for (var k = 0; k < next.length; k++) {
        var b = model.marks[next[k]].anchor;
        var dx = b.x - a.x;
        var dy = b.y - a.y;
        var d = Math.sqrt(dx * dx + dy * dy);
        if (d < best) {
          best = d;
          pick = next[k];
        }
      }
      out.target = markTarget(model, pick);
    }
    return out;
  }

  // ===================================================================================== primitives

  /** The card's primitives at (x, y): its surface, then each line; tokens from the draw's theme only. */
  function cardPrims(R, card, at, m, textual) {
    var P = R.prim;
    var t = m.theme;
    var sw = t.n("--chart-stroke");
    var kids = [];
    var bw = card.box.w;
    var bh = card.box.h;
    var surface = { role: "tooltip", x: at.x + sw / 2, y: at.y + sw / 2, w: Math.max(0, bw - sw), h: Math.max(0, bh - sw), rx: t.n("--chart-tip-radius") };
    surface.fill = "--chart-tip-surface";
    surface.color = t.css("--chart-tip-surface");
    surface.stroke = "--chart-tip-border";
    surface.strokeColor = t.css("--chart-tip-border");
    surface.strokeWidth = sw;
    kids.push(P.rect(surface));
    var left = at.x + m.p;
    var right = at.x + bw - m.p;
    var text = function (x, line, s, anchor, ink, weight) {
      return P.text({ role: "tooltip", x: x, y: baseline(m, at, line), text: s, anchor: anchor, size: m.s, weight: weight, fill: ink, color: t.css(ink) });
    };
    for (var i = 0; i < card.lines.length; i++) {
      var l = card.lines[i];
      if (textual) {
        kids.push(text(left, i, l, "start", "--chart-tip-ink", 400));
        continue;
      }
      var kind = l.line.kind;
      if (kind === "heading") kids.push(text(left, i, l.text, "start", "--chart-muted", 400));
      else if (kind === "more") kids.push(text(left, i, l.text, "start", "--chart-muted", 400));
      else if (kind === "row" && l.line.plain === true) {
        // A row without a colour (brief 1052 D6): no key; its name where a key would stand, its value as a row's.
        if (l.label !== "") kids.push(text(left, i, l.label, "start", "--chart-tip-ink", l.labelWeight));
        kids.push(text(right, i, l.line.value, "end", "--chart-tip-ink", m.bold));
      } else if (kind === "row") {
        var cy = at.y + m.p + (i + 0.5) * m.h;
        kids.push(P.rect({ role: "tooltip", x: left, y: cy - m.key / 2, w: m.key, h: m.key, fill: l.line.colour, color: l.line.color, series: l.line.id }));
        if (l.label !== "") kids.push(text(left + m.key + m.gap, i, l.label, "start", "--chart-tip-ink", l.labelWeight));
        kids.push(text(right, i, l.line.value, "end", "--chart-tip-ink", m.bold));
      } else {
        if (l.label !== "") kids.push(text(left, i, l.label, "start", "--chart-muted", 400));
        kids.push(text(right, i, l.line.value, "end", "--chart-tip-ink", 400));
      }
    }
    var out = [];
    P.group(out, { role: "tooltip" }, kids);
    return out;
  }

  /** The baseline of line i of a card at `at`: the line's box centred in its h. */
  function baseline(m, at, i) {
    return at.y + m.p + (i + 0.5) * m.h + ((m.ascent - m.descent) / 2) * m.s;
  }

  /** The crosshair (D3): one vertical guide at x across the plot, and a dot on every visible series' point there. */
  function crosshairPrims(R, model, datum, m) {
    var P = R.prim;
    var t = m.theme;
    var out = [];
    var x = null;
    var dots = [];
    for (var s = 0; s < model.series.length; s++) {
      var ser = model.series[s];
      var k = markOf(ser, datum);
      if (k < 0) continue;
      x = ser.marks[k].at;
      var y = pointY(model, ser.id, x);
      var c = model.colours[ser.id];
      if (y === null || !c) continue;
      dots.push(P.circle({ role: "guide", cx: x, cy: y, r: m.dot, fill: c.token, color: c.css, stroke: "--chart-canvas", strokeColor: t.css("--chart-canvas"), strokeWidth: t.n("--chart-gap"), series: ser.id }));
    }
    if (x === null) return out;
    out.push(P.line({ role: "guide", x1: x, y1: model.plot.y, x2: x, y2: model.plot.y + model.plot.h, stroke: "--chart-axis", strokeColor: t.css("--chart-axis"), strokeWidth: t.n("--chart-stroke") }));
    return out.concat(dots);
  }

  /** The focus ring (D5): a rectangle focusGap outside the target's box, kept inside the frame. */
  function focusPrims(R, model, target, m, frame) {
    var box = targetBox(model, target, m);
    if (box === null) return [];
    var t = m.theme;
    var g = m.focus;
    var lw = t.n("--chart-line");
    var x0 = Math.max(lw / 2, box.x - g);
    var y0 = Math.max(lw / 2, box.y - g);
    var x1 = Math.min(frame.w - lw / 2, box.x + box.w + g);
    var y1 = Math.min(frame.h - lw / 2, box.y + box.h + g);
    if (x1 < x0 || y1 < y0) return [];
    var ring = { role: "focus", x: x0, y: y0, w: x1 - x0, h: y1 - y0, rx: t.n("--chart-bar-radius"), stroke: "--chart-ink", strokeColor: t.css("--chart-ink"), strokeWidth: lw };
    return [R.prim.rect(ring)];
  }

  function targetBox(model, target, m) {
    if (target === null) return null;
    if (target.kind === "entry") {
      var e = entryIndex(model, target.id);
      return e >= 0 ? model.entries[e].box : null;
    }
    if (target.kind === "cut") {
      var c = model.cuts[target.n];
      return c ? flatBox(c.box) : null;
    }
    if (model.marks) {
      var n = markAt(model, target.id, target.datum);
      return n < 0 ? null : model.marks[n].box;
    }
    if (!model.crosshair) return model.bars[target.id + "\u0000" + target.datum] || null;
    var a = anchorOf(model, target.id, target.datum);
    return a === null ? null : { x: a.x - m.dot, y: a.y - m.dot, w: 2 * m.dot, h: 2 * m.dot };
  }

  /** A box as drawn: a rotated text's box by the bounds of its turned corners. */
  function flatBox(box) {
    if (!box.rotate) return box;
    var a = (box.rotate * Math.PI) / 180;
    var xs = [];
    var ys = [];
    [[box.x, box.y], [box.x + box.w, box.y], [box.x, box.y + box.h], [box.x + box.w, box.y + box.h]].forEach(function (c) {
      var dx = c[0] - box.ox;
      var dy = c[1] - box.oy;
      xs.push(box.ox + dx * Math.cos(a) - dy * Math.sin(a));
      ys.push(box.oy + dx * Math.sin(a) + dy * Math.cos(a));
    });
    var x0 = Math.min.apply(null, xs);
    var y0 = Math.min.apply(null, ys);
    return { x: x0, y: y0, w: Math.max.apply(null, xs) - x0, h: Math.max.apply(null, ys) - y0 };
  }

  /**
   * The hover ring of mark n (brief 1052 D7, rule 2): one primitive of role `guide`, no fill, its stroke the colour of
   * the mark's first primitive (its fill, else its stroke), `--chart-line` wide; around a point a circle markRing × s
   * beyond its radius, around a box a rectangle markRing × s outside it with the focus ring's corner. [] for any other.
   */
  function ringPrims(R, model, n, m) {
    var mk = model.marks[n];
    var held = model.held[n];
    if (mk.emphasis !== "ring" || held.length === 0) return [];
    var first = model.output.primitives[held[0]];
    var token = first.fill !== null && first.fill !== undefined ? first.fill : first.stroke;
    var css = first.fill !== null && first.fill !== undefined ? first.color : first.strokeColor;
    var t = m.theme;
    var g = R.tables.look("markRing") * m.s;
    var base = { role: "guide", fill: null, stroke: token, strokeColor: css, strokeWidth: t.n("--chart-line") };
    var sh = mk.shape;
    if (sh.kind === "point") return [R.prim.circle(Object.assign({ cx: sh.x, cy: sh.y, r: sh.r + g }, base))];
    if (sh.kind === "box") return [R.prim.rect(Object.assign({ x: sh.x - g, y: sh.y - g, w: sh.w + 2 * g, h: sh.h + 2 * g, rx: t.n("--chart-bar-radius") }, base))];
    return [];
  }

  /**
   * The move of mark n (brief 1052 D7, rule 3): {at: [positions of its primitives], value} with the sector's `out`
   * times markMove, rounded as the engine rounds and written as the writer writes a number; null for any other mark.
   */
  function moveOf(R, model, n) {
    var mk = model.marks[n];
    if (mk.emphasis !== "move" || !mk.out) return null;
    var by = R.tables.look("markMove");
    var r2 = R.scales.r2;
    return { at: model.held[n], value: "translate(" + String(r2(mk.out.x * by)) + " " + String(r2(mk.out.y * by)) + ")" };
  }

  /**
   * What is shown for one state, pure: {prims, active, activeMark, move}. activeMark holds the primitive positions of a
   * selected mark without a series; active holds a selected series. focus = the keyboard's target; last = "key" | "pointer" | null;
   * hit = the pointer's lookup; pointer = its place. The card follows the input that acted last (D2a).
   */
  function scene(R, model, draw, m, width, state) {
    var frame = draw.frame;
    var under = [];
    var card = [];
    var active = null;
    var activeMark = null;
    var move = null;
    var show = null;
    if (state.last === "key" && state.target !== null) show = { what: state.target, anchor: null };
    else if (state.last === "pointer" && state.hit !== null) show = { what: state.hit, anchor: state.pointer };
    if (show !== null) {
      var what = show.what;
      var markT = what.kind === "mark" ? what : what.mark || null;
      var entryT = what.kind === "entry" ? what.id : what.entry !== undefined ? what.entry : null;
      var cutT = what.kind === "cut" ? what.n : what.cut !== undefined ? what.cut : null;
      if (markT && model.marks) {
        // A draw by mark: its own card and ring or move; highlight its series, or its own primitives when it has none.
        var n = markAt(model, markT.id, markT.datum);
        if (n >= 0) {
          var mk = model.marks[n];
          active = mk.series;
          if (mk.series === null) activeMark = model.held[n];
          var mc = markCard(R, width, markLines(model, n), null, frame, m);
          var ma = show.anchor || mk.anchor;
          if (mc !== null) card = cardPrims(R, mc, place(mc.box, ma, frame, m.dist), m, false);
          under = ringPrims(R, model, n, m);
          move = moveOf(R, model, n);
        }
      } else if (markT) {
        active = markT.id;
        var lines = cardLines(model, markT.id, markT.datum);
        if (model.crosshair) under = crosshairPrims(R, model, markT.datum, m);
        var c = lines === null ? null : markCard(R, width, lines, markT.id, frame, m);
        var anchor = show.anchor || anchorOf(model, markT.id, markT.datum);
        if (c !== null && anchor !== null) card = cardPrims(R, c, place(c.box, anchor, frame, m.dist), m, false);
      } else {
        if (entryT !== null && entryT !== undefined) active = entryT;
        if (cutT !== null && cutT !== undefined && model.cuts[cutT]) {
          var cut = model.cuts[cutT];
          var tc = textCard(R, width, cut.text, frame, m);
          var box = flatBox(cut.box);
          var at = show.anchor || { x: box.x + box.w / 2, y: box.y + box.h / 2 };
          if (tc !== null) card = cardPrims(R, tc, place(tc.box, at, frame, m.dist), m, true);
        }
      }
    }
    var ring = focusPrims(R, model, state.target, m, frame);
    return { prims: under.concat(ring, card), active: active, activeMark: activeMark, move: move };
  }

  // ===================================================================================== the DOM half

  function classes(el) {
    var c = el.getAttribute("class");
    return c ? c.split(" ").filter(function (x) {
      return x !== "";
    }) : [];
  }

  function setClass(el, name, on) {
    var list = classes(el).filter(function (x) {
      return x !== name;
    });
    if (on) list.push(name);
    // An emptied list removes the attribute, so detach leaves a picture without a class as the writer wrote it.
    if (list.length === 0) el.removeAttribute("class");
    else el.setAttribute("class", list.join(" "));
  }

  /** A legend entry's element: the writer's switch, the group that carries `data-legend` (record §4.8, brief 990 D4). */
  function isEntry(el) {
    return el.nodeType === 1 && el.getAttribute("data-legend") !== null;
  }

  /**
   * In a picture that is a group (brief 1010 D2), what assistive technology skips: every top-level child that is not
   * the picture's title, its description or a legend switch.
   */
  function presentational(el) {
    var tag = el.localName || el.tagName;
    return el.nodeType === 1 && tag !== "title" && tag !== "desc" && !isEntry(el);
  }

  /** The elements the writer made for the primitives, in their order (A4): description and gradient nodes skipped. */
  function elementsOf(svg, count) {
    var out = [];
    var walk = function (el) {
      for (var c = el.firstChild; c !== null && out.length < count; c = c.nextSibling) {
        if (c.nodeType !== 1) continue;
        var tag = c.localName || c.tagName;
        if (tag === "title" || tag === "desc" || tag === "linearGradient") continue;
        out.push(c);
        walk(c);
      }
    };
    walk(svg);
    return out;
  }

  /**
   * attach(svg, draw, options) → {update(draw), detach()} (D1). The picture becomes the chart's one tab stop; every
   * listener is on the svg and removed by detach.
   */
  function attach(svg, draw, options) {
    var R = engine();
    var toggle = options && typeof options.toggle === "function" ? options.toggle : function () {};
    var st = { draw: null, model: null, m: null, width: null, target: null, last: null, hit: null, pointer: null };
    var made = [];
    var marked = [];
    var moved = []; // the elements this layer moved (brief 1052 D7), for clear to take the move away
    var hidden = []; // the writer's elements this layer gave aria-hidden (brief 1010 D2), for detach to give back
    var listeners = [];
    var detached = false;

    function load(next) {
      st.draw = next;
      st.width = R.measure.measurer(next.measure).fn;
      st.m = measures(R, next.theme);
      st.model = modelOf(R, next.output, st.width);
      st.model.theme = st.m.theme;
    }

    /** aria-hidden="true" on each of [els] that D2 hides, when the picture is a group; [keep] records it for detach. */
    function hide(els, keep) {
      if (svg.getAttribute("role") !== "group") return;
      els.forEach(function (el) {
        if (el.parentNode !== svg || !presentational(el) || el.getAttribute("aria-hidden") !== null) return;
        el.setAttribute("aria-hidden", "true");
        if (keep) hidden.push(el);
      });
    }

    function unhide() {
      hidden.splice(0).forEach(function (el) {
        el.removeAttribute("aria-hidden");
      });
    }

    /**
     * After every draw the layer is given (brief 1010 D1, owner ruling 2026-11-16): a picture with a legend switch is a
     * group, so its switches are buttons for a screen reader; without one the writer's image stays. Name and
     * description stay the picture's title and desc (D2).
     */
    function dress() {
      unhide();
      if (st.model.entries.length > 0) svg.setAttribute("role", "group");
      else if (svg.getAttribute("role") === "group") svg.setAttribute("role", "img");
      var children = [];
      for (var c = svg.firstChild; c !== null; c = c.nextSibling) children.push(c);
      hide(children, true);
    }

    /** The legend entry a press landed on or inside (brief 1010 D3): its series id as a pointer click finds it, or null. */
    function pressed(node) {
      for (var el = node; el && el !== svg; el = el.parentNode) {
        if (el.nodeType !== 1 || !isEntry(el)) continue;
        var id = el.getAttribute("data-legend");
        for (var e = 0; e < st.model.entries.length; e++) if (String(st.model.entries[e].id) === id) return st.model.entries[e].id;
        return null;
      }
      return null;
    }

    function clear() {
      made.forEach(function (el) {
        if (el.parentNode === svg) svg.removeChild(el);
      });
      made = [];
      marked.forEach(function (el) {
        setClass(el, "dp-active", false);
        setClass(el, "dp-inactive", false);
      });
      marked = [];
      moved.forEach(function (el) {
        el.removeAttribute("transform");
      });
      moved = [];
      setClass(svg, "dp-dimmed", false);
    }

    function paint() {
      clear();
      if (detached || st.model === null) return;
      if (st.last === "pointer" && st.pointer !== null) st.hit = lookup(R, st.model, st.pointer);
      var sc = scene(R, st.model, st.draw, st.m, st.width, st);
      if (sc.active !== null || sc.activeMark !== null) {
        var prims = st.draw.output.primitives;
        var els = elementsOf(svg, prims.length);
        for (var i = 0; i < prims.length && i < els.length; i++) {
          var p = prims[i];
          if (sc.active !== null && p.k === "group" && p.series === sc.active && (p.role === "mark" || p.role === "legend")) {
            setClass(els[i], "dp-active", true);
            marked.push(els[i]);
          } else if (sc.activeMark !== null && p.series === null && p.role === "mark" && p.k !== "group" && p.k !== "text") {
            var selected = sc.activeMark.indexOf(i) >= 0;
            setClass(els[i], selected ? "dp-active" : "dp-inactive", true);
            marked.push(els[i]);
          }
        }
        setClass(svg, "dp-dimmed", true);
      }
      if (sc.move !== null) {
        var all = elementsOf(svg, st.draw.output.primitives.length);
        sc.move.at.forEach(function (at) {
          if (!all[at]) return;
          all[at].setAttribute("transform", sc.move.value);
          moved.push(all[at]);
        });
      }
      if (sc.prims.length > 0) made = R.write.append(sc.prims, svg, st.draw.theme);
      hide(made, false); // the card, guide and ring leave with clear; nothing to give back
    }

    function position(event) {
      var rect = svg.getBoundingClientRect();
      var f = st.draw.frame;
      if (!rect || !(rect.width > 0) || !(rect.height > 0)) return null;
      return { x: ((event.clientX - rect.left) * f.w) / rect.width, y: ((event.clientY - rect.top) * f.h) / rect.height };
    }

    function on(type, fn) {
      svg.addEventListener(type, fn);
      listeners.push([type, fn]);
    }

    var onPointer = function (event) {
      var pos = position(event);
      if (pos === null) return;
      st.pointer = pos;
      st.last = "pointer";
      paint();
    };
    on("pointermove", onPointer);
    on("pointerdown", onPointer);
    on("pointerleave", function (event) {
      // A finger lifts with a leave: the tapped card stays until a tap elsewhere in the picture, or the picture's blur.
      if (event.pointerType === "touch") return;
      st.pointer = null;
      st.hit = null;
      if (st.last === "pointer") st.last = null;
      paint();
    });
    on("click", function (event) {
      // A press on a switch (a screen reader's, or a click whose target is the entry or its text) answers by its
      // target, once; any other click by the pointer's position, as before (brief 1010 D3).
      var entry = st.model === null ? null : pressed(event.target);
      if (entry !== null) {
        toggle(entry);
        return;
      }
      var pos = position(event);
      if (pos === null) return;
      var hit = lookup(R, st.model, pos);
      if (hit.entry !== null) toggle(hit.entry);
    });
    on("keydown", function (event) {
      if (st.model === null) return;
      var r = keyTarget(st.model, st.target, event.key);
      if (!r.handled) return;
      if (typeof event.preventDefault === "function") event.preventDefault();
      st.target = r.target;
      st.last = r.target === null ? null : "key";
      if (event.key === "Escape") {
        st.hit = null;
        st.pointer = null;
      }
      if (r.toggle !== null) {
        toggle(r.toggle);
        return; // the host's redraw came back through update, which painted
      }
      paint();
    });
    on("blur", function () {
      // Blur does what Escape does (D5 "Leaving"): the target cleared, what is shown closed.
      st.target = null;
      st.last = null;
      st.hit = null;
      st.pointer = null;
      paint();
    });

    svg.setAttribute("tabindex", "0");
    load(draw);
    dress();
    paint();

    return {
      /** A new draw of the same picture: the target found again by identity, the card drawn from the new draw alone. */
      update: function (next) {
        if (detached) return;
        made = []; // the writer emptied the svg: the old card went with the old picture
        marked = [];
        moved = [];
        load(next);
        dress();
        st.target = findAgain(st.model, st.target);
        if (st.last === "key" && st.target === null) st.last = null;
        paint();
      },
      /** Every listener removed, the card, the guide and the ring gone, the picture as the writer wrote it (an image). */
      detach: function () {
        if (detached) return;
        clear();
        unhide();
        if (svg.getAttribute("role") === "group") svg.setAttribute("role", "img");
        detached = true;
        listeners.splice(0).forEach(function (l) {
          svg.removeEventListener(l[0], l[1]);
        });
        svg.removeAttribute("tabindex");
      },
      /** The keyboard's target, for the tests (read only). */
      target: function () {
        return st.target === null ? null : JSON.parse(JSON.stringify(st.target));
      },
      /** The count of listeners attached (D7: detach leaves none). */
      listeners: function () {
        return listeners.length;
      },
    };
  }

  var api = {
    attach: attach,
    _internal: {
      measures: measures,
      bounds: bounds,
      markCard: markCard,
      textCard: textCard,
      wrap: wrap,
      place: place,
      modelOf: modelOf,
      lookup: lookup,
      cardLines: cardLines,
      keyTarget: keyTarget,
      findAgain: findAgain,
      anchorOf: anchorOf,
      pathY: pathY,
      scene: scene,
      cardPrims: cardPrims,
      markLines: markLines,
      ringPrims: ringPrims,
      moveOf: moveOf,
      targetBox: targetBox,
      MORE: MORE,
      PLUS: PLUS,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DatapipelinesChartInteractions = api;
})();
