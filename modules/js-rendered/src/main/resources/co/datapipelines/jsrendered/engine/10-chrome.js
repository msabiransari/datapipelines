// 10-chrome.js : the shared layout's first half (record §4.6, look contract §4.1, §4.2, §4.5): the helpers a draw
// measures and writes text with, the level of a frame, the title block and the legend. Every size is a token of the
// theme or a field of the look table (06-tables.js); no number of its own (A14, LA5). A text's box is the font's
// box: from ascent × size above its baseline to descent × size below it, its measured width wide.
(function (R) {
  "use strict";
  var P = R.prim, M = R.measure, look = R.tables.look, r2 = R.scales.r2;

  /** The helpers of one draw: the resolved theme, the measurer, the text sizes and the ink of a token. */
  function helpers(theme, meter) {
    var text = theme.n("--chart-text");
    var h = {
      theme: theme,
      text: text,
      small: theme.n("--chart-text-small"),
      titleSize: theme.n("--chart-title"),
      titleWeight: theme.n("--chart-title-weight"),
      em: function (field) {
        return look(field) * text;
      },
      width: function (s, size, weight) {
        return meter.fn(s, size, weight === undefined ? 400 : weight);
      },
      fit: function (s, size, weight, max) {
        return M.fit(meter.fn, s, size, weight, max);
      },
      ascent: function (size) {
        return look("textAscent") * size;
      },
      descent: function (size) {
        return look("textDescent") * size;
      },
      /** The height of a text's box. */
      boxH: function (size) {
        return (look("textAscent") + look("textDescent")) * size;
      },
      /** The baseline that centres a text's box on y. */
      middle: function (y, size) {
        return y + ((look("textAscent") - look("textDescent")) / 2) * size;
      },
      /** {fill, color} of a fill token. */
      fill: function (token) {
        return { fill: token, color: theme.css(token) };
      },
      /** A text primitive: f = {role, x, y, text, shown?, anchor?, size, weight?, ink, rotate?, opacity?, struck?, series?}. */
      label: function (f) {
        var g = {};
        for (var k in f) if (Object.prototype.hasOwnProperty.call(f, k) && k !== "ink") g[k] = f[k];
        g.fill = f.ink;
        g.color = theme.css(f.ink);
        return P.text(g);
      },
      /** A 1-stroke line in a colour token (grid, frame, zero line). */
      rule: function (role, token, x1, y1, x2, y2) {
        return P.line({ role: role, x1: x1, y1: y1, x2: x2, y2: y2, stroke: token, strokeColor: theme.css(token), strokeWidth: theme.n("--chart-stroke") });
      },
    };
    return h;
  }

  /** The three text sizes the level numbers are written for (their defaults, contract §3.4). */
  var SIZES = ["--chart-text", "--chart-text-small", "--chart-title"];

  /**
   * The layout level of a frame (record §4.6): "full", "compact", "minimal", or null below the smallest. The full and
   * compact numbers hold at the default text sizes; a theme whose text is larger raises both in proportion to its
   * largest ratio over a default, so text still never collides nor leaves the frame at the top of each range (LA3).
   * Smaller text never lowers them. The minimal level draws no text and keeps its numbers.
   */
  function level(w, h, theme) {
    var k = 1, col = theme.name === "dark" ? 2 : 1;
    for (var i = 0; i < SIZES.length; i++) k = Math.max(k, theme.n(SIZES[i]) / R.theme.TOKENS[SIZES[i]][col]);
    var f = look("levelFull"), c = look("levelCompact"), m = look("levelMinimal");
    if (w >= f[0] * k && h >= f[1] * k) return "full";
    if (w >= c[0] * k && h >= c[1] * k) return "compact";
    if (w >= m[0] && h >= m[1]) return "minimal";
    return null;
  }

  /** The words of s split on spaces, each with the space before it kept off. */
  function wrapTwo(h, s, size, max) {
    if (h.width(s, size) <= max) return [s];
    var words = s.split(" "), first = "", i = 0;
    for (; i < words.length; i++) {
      var next = i === 0 ? words[0] : first + " " + words[i];
      if (h.width(next, size) > max) break;
      first = next;
    }
    if (i === 0) return [h.fit(s, size, 400, max)];
    var rest = words.slice(i).join(" ");
    var second = h.fit(rest, size, 400, max);
    return second === "" ? [first] : [first, second];
  }

  /**
   * The title block (record §4.4 G5, G10; §4.6): config.title.text in the title's size and weight, cut to the frame;
   * its subtitle at the full level only, in muted text, wrapped to two lines and then cut. A title whose text repeats
   * the card's heading (display_name, trimmed) and that has no subtitle is not drawn. Returns {texts, bottom}.
   */
  function titles(h, doc, lvl, W, top) {
    var out = [], t = doc.config && doc.config.title, e = h.em("edge"), max = W - 2 * e;
    if (lvl === "minimal" || !t || typeof t.text !== "string") return { texts: out, bottom: top };
    var sub = lvl === "full" && typeof t.subtitle === "string" ? t.subtitle : null;
    var heading = typeof doc.display_name === "string" ? doc.display_name.trim() : "";
    if (heading !== "" && t.text.trim() === heading && !t.subtitle) return { texts: out, bottom: top };
    var y = top, ts = h.titleSize;
    var shown = h.fit(t.text, ts, h.titleWeight, max);
    if (shown !== "") out.push(h.label({ role: "title", x: e, y: y + h.ascent(ts), text: t.text, shown: shown, size: ts, weight: h.titleWeight, ink: "--chart-ink" }));
    y += look("lineHeight") * ts;
    if (sub !== null) {
      var lines = wrapTwo(h, sub, h.text, max);
      for (var i = 0; i < lines.length; i++) {
        if (lines[i] === "") continue;
        out.push(h.label({ role: "title", x: e, y: y + h.ascent(h.text), text: sub, shown: lines[i], size: h.text, ink: "--chart-muted" }));
        y += look("lineHeight") * h.text;
      }
    }
    return { texts: out, bottom: y + h.em("titleGap") };
  }

  /**
   * Where the legend is (look contract §4.2, lines 1, 2, 3 and 5; line 4, the names on the lines, is lane 989's):
   * null, "top", "bottom", "left" or "right"; at the compact level every legend is one row at the bottom (§4.5).
   * series = [{id, title}] of the FULL data; hidden = the list of ids; legend = the author's `legend` (absent,
   * null, or {orient}).
   */
  function legendPlace(series, hidden, lvl, legend) {
    if (lvl === "minimal" || legend === null) return null;
    if (series.length === 1 && hidden.indexOf(series[0].id) < 0) return null;
    var place = legend && typeof legend === "object" && typeof legend.orient === "string" ? legend.orient : "top";
    return lvl === "compact" ? "compact" : place;
  }

  /** One entry's parts: {key, keyGap, name, shown, w}. */
  function entry(h, s, maxName) {
    var key = h.em("legendKey"), gap = h.em("legendKeyGap");
    var shown = h.fit(s.title, h.text, 400, Math.max(0, maxName - key - gap));
    return { s: s, key: key, gap: gap, shown: shown, w: key + gap + (shown === "" ? 0 : h.width(shown, h.text)) };
  }

  /**
   * The legend's layout: rows (top, bottom, compact) or a column (left, right) inside a box of width boxW and at most
   * boxH high. Entries that do not fit are folded into one "+n" text; a folded entry is not drawn, so its series is
   * drawn (§4.2). Rows wrap while they stay inside boxH (the compact level: one row); the last row that fits keeps
   * room for the "+n" as the compact row does (#985 R1: eight wrapped rows pushed the axes out of a full frame); a box
   * lower than one row holds no legend. Returns {place, w, h, items: [{s, x, y, e}], more: null | {text, x, y}, drawn: {id: true}} with x, y
   * relative to the box (y = the row's top).
   */
  function legendLayout(h, series, place, boxW, boxH) {
    var row = look("lineHeight") * h.text, sep = h.em("legendEntryGap");
    var items = [], drawn = {}, x = 0, y = 0, w = 0;
    var side = place === "left" || place === "right";
    var moreW = function (n) {
      return h.width(look("morePrefix") + String(n), h.text);
    };
    var maxName = side ? Math.min(boxW, look("legendSideMaxEm") * h.text) : boxW;
    var maxRows = place === "compact" ? 1 : Math.floor(boxH / row + 1e-9), rowIdx = 0;
    if (!side && maxRows < 1) return { place: place, w: 0, h: 0, items: [], more: null, drawn: drawn };
    var i;
    for (i = 0; i < series.length; i++) {
      var e = entry(h, series[i], maxName);
      var left = series.length - i - 1;
      if (side) {
        var need = (items.length + 1) * row + (left > 0 ? row : 0);
        if (need > boxH && items.length > 0) break;
        items.push({ s: series[i], x: 0, y: y, e: e });
        y += row;
        if (e.w > w) w = e.w;
        continue;
      }
      var placed = false;
      for (;;) {
        var lastRow = rowIdx >= maxRows - 1;
        var start = x === 0 ? 0 : x + sep;
        var reserve = lastRow && left > 0 ? sep + moreW(left) : 0;
        if (start + e.w + reserve <= boxW || (x === 0 && !lastRow)) {
          // An entry wider than the box keeps a row of its own on any row but the last.
          items.push({ s: series[i], x: start, y: y, e: e });
          x = start + e.w;
          if (x > w) w = x;
          placed = true;
          break;
        }
        if (x === 0 || lastRow) break;
        rowIdx++;
        y += row;
        x = 0;
      }
      if (!placed) break;
    }
    for (var d = 0; d < items.length; d++) drawn[items[d].s.id] = true;
    var more = null, rest = series.length - items.length;
    if (rest > 0) {
      var mt = look("morePrefix") + String(rest);
      if (side) {
        more = { text: mt, x: 0, y: y };
        y += row;
        w = Math.max(w, moreW(rest));
      } else {
        more = { text: mt, x: x === 0 ? 0 : x + sep, y: y };
        w = Math.max(w, more.x + moreW(rest));
      }
    }
    var height = side ? y : items.length === 0 && more === null ? 0 : y + row;
    return { place: place, w: w, h: height, items: items, more: more, drawn: drawn };
  }

  /**
   * The legend's primitives at (ox, oy): one group per entry (role legend, its series id, its name, on = shown) holding
   * the key, a square of the series colour (§4.2: a bar's key; its opacity --chart-dim when hidden) and the name in
   * ink-2, struck through when hidden. colourOf(id) → the series' colour token.
   */
  function legendPrims(h, lay, ox, oy, hiddenOf, colourOf, out) {
    var row = look("lineHeight") * h.text;
    for (var i = 0; i < lay.items.length; i++) {
      var it = lay.items[i], e = it.e, x = ox + it.x, cy = oy + it.y + row / 2, off = hiddenOf(it.s.id);
      var token = colourOf(it.s.id), kids = [];
      // The key's form: "square" (a bar's, contract §4.2) or "line" (a stroke of --chart-line across the key's width).
      var form = look("legendKeyBar"), kh = form === "square" ? e.key : form === "line" ? h.theme.n("--chart-line") : -1;
      if (kh < 0) throw new Error("unknown legend key " + form);
      kids.push(P.rect({ role: "legend", x: x, y: r2(cy - kh / 2), w: e.key, h: kh, fill: token, color: h.theme.css(token), opacity: off ? h.theme.n("--chart-dim") : 1, series: it.s.id }));
      if (e.shown !== "") {
        kids.push(h.label({ role: "legend", x: x + e.key + e.gap, y: h.middle(cy, h.text), text: it.s.title, shown: e.shown, size: h.text, ink: "--chart-ink-2", struck: off, series: it.s.id }));
      }
      P.group(out, { role: "legend", series: it.s.id, legend: it.s.id, label: it.s.title, on: !off }, kids);
    }
    if (lay.more !== null) {
      out.push(h.label({ role: "legend", x: ox + lay.more.x, y: h.middle(oy + lay.more.y + row / 2, h.text), text: lay.more.text, size: h.text, ink: "--chart-ink-2" }));
    }
    return out;
  }

  R.chrome = { helpers: helpers, level: level, titles: titles, wrapTwo: wrapTwo, legendPlace: legendPlace, legendLayout: legendLayout, legendPrims: legendPrims };
})(JsRendered);
