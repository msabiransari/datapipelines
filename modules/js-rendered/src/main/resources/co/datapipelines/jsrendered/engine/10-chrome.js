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
   * Where the legend is (look contract §4.2, the owner's ruling L5; the record's §4.3, §4.5; brief 989 D7): ONE pure
   * function of the series of the data, the list of switched-off ids, the level, the `legend` the author wrote, and
   * whether the type writes names on its lines (line and area). The first line that applies decides:
   *   1. the level is minimal, or the author wrote `legend: null` → null (no legend, no names on the lines);
   *   2. the data has one series and its id is not on the list → null;
   *   3. the author wrote a place (top, bottom, left, right) → there;
   *   4. names on the lines, 2 to 4 series IN THE DATA (look fields directMin, directMax), none of their ids on the list,
   *      the level full → "direct";
   *   5. otherwise → "top".
   * At the compact level the legend of line 3 or 5 is one row at the bottom ("compact"). Every count is of the series
   * in the data, never of the visible ones. series = [{id, title}] of the FULL data; legend = absent, null or {orient}.
   * alone (brief 1054 D3, optional): true → line 2 does not apply, so one entry is a legend; only a legend of keys whose
   * model hands `keysAlone: true` passes it (the gauge's one tone). Five arguments answer as before.
   */
  function legendPlace(series, hidden, lvl, legend, direct, alone) {
    if (lvl === "minimal" || legend === null) return null;
    if (alone !== true && series.length === 1 && hidden.indexOf(series[0].id) < 0) return null;
    var orient = legend && typeof legend === "object" && typeof legend.orient === "string" ? legend.orient : null;
    var place = orient === "top" || orient === "bottom" || orient === "left" || orient === "right" ? orient : null;
    if (place === null && direct === true && lvl === "full" && series.length >= look("directMin") && series.length <= look("directMax")) {
      var listed = false;
      for (var i = 0; i < series.length; i++) if (hidden.indexOf(series[i].id) >= 0) listed = true;
      if (!listed) return "direct";
    }
    if (place === null) place = "top";
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
   * The legend's primitives at (ox, oy). A switch entry (keysOnly not true) is one group {role legend, series id,
   * legend id, label title, on = shown} holding the key in the entry's colour (its opacity --chart-dim when hidden) and
   * the name in ink-2, struck through when hidden. A key-only entry (keysOnly true, brief 1054 D4) is a group with no
   * series, no legend and no label, on, holding the key at opacity 1 and the name never struck, each with no series: no
   * writer makes it a button and the layer finds no entry in it. colourOf(id) → the entry's colour token. keyField: the
   * look field of every entry's key (absent: legendKeyBar), or a function (id) → that field for one entry. The key's
   * form is the field's value: "square" (the key's side), "line" (a stroke of --chart-line across the key's width) or
   * "dot" (a circle at the centre of the key's square, its radius --chart-point and at most half the key's side); every
   * form stands inside the same square, so the layout's arithmetic is one for all three.
   */
  function legendPrims(h, lay, ox, oy, hiddenOf, colourOf, out, keyField, keysOnly) {
    var row = look("lineHeight") * h.text, only = keysOnly === true;
    for (var i = 0; i < lay.items.length; i++) {
      var it = lay.items[i], e = it.e, x = ox + it.x, cy = oy + it.y + row / 2, off = !only && hiddenOf(it.s.id);
      var token = colourOf(it.s.id), kids = [], ser = only ? null : it.s.id;
      var field = typeof keyField === "function" ? keyField(it.s.id) : keyField;
      if (field === undefined) field = "legendKeyBar";
      var form = look(field), opacity = off ? h.theme.n("--chart-dim") : 1;
      if (form === "dot") {
        kids.push(P.circle({ role: "legend", cx: x + e.key / 2, cy: cy, r: Math.min(h.theme.n("--chart-point"), e.key / 2), fill: token, color: h.theme.css(token), opacity: opacity, series: ser }));
      } else {
        var kh = form === "square" ? e.key : form === "line" ? h.theme.n("--chart-line") : -1;
        if (kh < 0) throw new Error("unknown legend key " + form);
        kids.push(P.rect({ role: "legend", x: x, y: r2(cy - kh / 2), w: e.key, h: kh, fill: token, color: h.theme.css(token), opacity: opacity, series: ser }));
      }
      if (e.shown !== "") {
        kids.push(h.label({ role: "legend", x: x + e.key + e.gap, y: h.middle(cy, h.text), text: it.s.title, shown: e.shown, size: h.text, ink: "--chart-ink-2", struck: off, series: ser }));
      }
      if (only) P.group(out, { role: "legend", series: null, legend: null, label: null, on: true }, kids);
      else P.group(out, { role: "legend", series: it.s.id, legend: it.s.id, label: it.s.title, on: !off }, kids);
    }
    if (lay.more !== null) {
      out.push(h.label({ role: "legend", x: ox + lay.more.x, y: h.middle(oy + lay.more.y + row / 2, h.text), text: lay.more.text, size: h.text, ink: "--chart-ink-2" }));
    }
    return out;
  }

  /** The three legend key forms a look field may hold (look contract §4.2: a square, a short line, a dot). */
  var KEY_FORMS = { square: true, line: true, dot: true };
  var KEY_MEMBERS = { id: true, title: true, key: true, colour: true };

  /** A look field that holds a key form, or a throw naming where it was handed. */
  function keyFieldOf(where, field) {
    if (typeof field !== "string" || !Object.prototype.hasOwnProperty.call(R.tables.LOOK, field)) throw new Error("legend: " + where + " " + String(field) + " is no look-table field");
    var form = look(field);
    if (typeof form !== "string" || KEY_FORMS[form] !== true) throw new Error("legend: " + where + " " + field + " holds " + String(form) + ", not a key form (square, line, dot)");
    return field;
  }

  /**
   * What a type's model hands its legend, checked (brief 1054 D2): {keyOf(id) → the look field of series id's key,
   * keys: null | [{id, title, key, colour}], alone: null | true | false}. A series may carry `legendKey`, the name of a
   * look field whose value is a key form; one without takes the model's `legendKey` (absent: legendKeyBar, in
   * legendPrims). A model may carry `keys` (a legend of keys, no entry a switch) with `keysAlone` (true: one entry is a
   * legend). A throw is a defect of a type's template, never a state of a draw.
   */
  function legendModel(m) {
    var has = function (k) {
      return Object.prototype.hasOwnProperty.call(m, k) && m[k] !== undefined;
    };
    var own = new Map();
    for (var i = 0; i < m.series.length; i++) {
      var s = m.series[i];
      if (s !== null && typeof s === "object" && Object.prototype.hasOwnProperty.call(s, "legendKey") && s.legendKey !== undefined) own.set(s.id, keyFieldOf("series " + s.id + ": legendKey", s.legendKey));
    }
    var keyOf = function (id) {
      return own.has(id) ? own.get(id) : m.legendKey;
    };
    if (!has("keys")) {
      if (has("keysAlone")) throw new Error("legend: keysAlone is handed without keys");
      return { keyOf: keyOf, keys: null, alone: null };
    }
    if (!Array.isArray(m.keys)) throw new Error("legend: keys is not a list");
    if (m.shade) throw new Error("legend: a model hands keys or shade, not both");
    if (m.direct === true) throw new Error("legend: a model hands keys or direct: true, not both");
    if (m.keysAlone !== true && m.keysAlone !== false) throw new Error("legend: keys is handed without keysAlone true or false");
    var keys = [], seen = new Map(), tokens = R.theme.TOKENS;
    for (var k = 0; k < m.keys.length; k++) {
      var e = m.keys[k], at = "keys[" + k + "]";
      if (e === null || typeof e !== "object" || Array.isArray(e)) throw new Error("legend: " + at + " is not an object");
      for (var name in e) if (Object.prototype.hasOwnProperty.call(e, name) && KEY_MEMBERS[name] !== true) throw new Error("legend: " + at + ": " + name + " is not a member");
      for (var want in KEY_MEMBERS) if (!Object.prototype.hasOwnProperty.call(e, want) || e[want] === undefined) throw new Error("legend: " + at + ": " + want + " is missing");
      if (typeof e.id !== "string") throw new Error("legend: " + at + ": id is not a text");
      if (e.id === "") throw new Error("legend: " + at + ": id is empty");
      if (seen.has(e.id)) throw new Error("legend: " + at + ": id " + e.id + " stands twice");
      seen.set(e.id, true);
      if (typeof e.title !== "string") throw new Error("legend: " + at + ": title is not a text");
      var kind = typeof e.colour === "string" && Object.prototype.hasOwnProperty.call(tokens, e.colour) ? tokens[e.colour][0] : null;
      if (kind !== "colour" && kind !== "opaque") throw new Error("legend: " + at + ": colour " + String(e.colour) + " is no colour token");
      keys.push({ id: e.id, title: e.title, key: keyFieldOf(at + ": key", e.key), colour: e.colour });
    }
    return { keyOf: keyOf, keys: keys, alone: m.keysAlone };
  }

  /**
   * The output's names of a legend of keys (brief 1054 D6): summary.keys = [{id, title}] in the model's order, for a
   * model whose `keys` is not empty, whether the legend is drawn or not; nothing for any other model.
   */
  function legendKeys(m, summary) {
    var keys = legendModel(m).keys;
    if (keys === null || keys.length === 0) return;
    summary.keys = keys.map(function (e) {
      return { id: e.id, title: e.title };
    });
  }

  /**
   * The labels at the lines' ends (look contract §2 "legend", §4.5; brief 989 D6, D7): the names on the lines and the
   * values of `labels: last`. A label is {id, name: null | text, value: null | text}: name alone, value alone, or both
   * as ONE label, the name first ("Revenue 3.7K"). A name is written in secondary ink at the text size; a value alone in
   * the ink and size of a value label; a label holding a name is a legend entry without a key (role legend, its group
   * names the series). The longest a label may be is legendSideMaxEm (a longer one is cut, its full text kept, I4).
   */
  function endLabel(h, l) {
    var named = l.name !== null, text = named ? (l.value !== null ? l.name + " " + l.value : l.name) : l.value;
    var size = l.value !== null ? h.small : h.text;
    var shown = h.fit(text, size, 400, look("legendSideMaxEm") * h.text);
    return { id: l.id, named: named, title: l.name, text: text, shown: shown, size: size, w: shown === "" ? 0 : h.width(shown, size) };
  }

  /** The room the end labels take right of the plot: the widest label plus the gap after the last point (0 for none). */
  function endRoom(h, labels) {
    var w = 0;
    for (var i = 0; i < labels.length; i++) w = Math.max(w, endLabel(h, labels[i]).w);
    return w > 0 ? w + h.em("directGap") : 0;
  }

  /**
   * The end labels' primitives. ends = [{id, y}]: the y of each drawn series' last point; labels as endLabel takes
   * them; x = where a label starts; lo, hi = the band a label's box must lie in. Two labels closer than one text line are
   * pushed apart vertically (contract §2), first down in the order of their points, then up from the band's end, so no
   * two meet (I1) and every one stays inside the band (I2). Where the band cannot hold them all, the labels of the last
   * series in the data's order are left out until it can (a label that does not fit is not drawn, contract §4.3).
   * Returns {texts, drawn: {id: true} for the names written}.
   */
  function endLabels(h, ends, labels, x, lo, hi) {
    var byId = new Map(), rows = [];
    for (var i = 0; i < labels.length; i++) byId.set(labels[i].id, endLabel(h, labels[i]));
    for (var j = 0; j < ends.length; j++) {
      var l = byId.get(ends[j].id);
      if (l === undefined || l.shown === "") continue;
      rows.push({ l: l, want: ends[j].y, order: j, y: 0 });
    }
    var placed = null;
    while (rows.length > 0) {
      placed = pushApart(h, rows, lo, hi);
      if (placed) break;
      var last = 0;
      for (var q = 1; q < rows.length; q++) if (rows[q].order > rows[last].order) last = q;
      rows.splice(last, 1);
    }
    var out = { texts: [], drawn: {} };
    for (var k = 0; k < rows.length; k++) {
      var r = rows[k], t = h.label({ role: r.l.named ? "legend" : "guide", x: x, y: h.middle(r.y, r.l.size), text: r.l.text, shown: r.l.shown, size: r.l.size, ink: "--chart-ink-2", series: r.l.id });
      if (!r.l.named) {
        out.texts.push(t);
        continue;
      }
      out.drawn[r.l.id] = true;
      P.group(out.texts, { role: "legend", series: r.l.id, legend: r.l.id, label: r.l.title, on: true }, [t]);
    }
    return out;
  }

  /** Centres for rows inside [lo, hi] at least a text line apart, nearest their wants; false when they cannot fit. */
  function pushApart(h, rows, lo, hi) {
    rows.sort(function (a, b) {
      return a.want - b.want || a.order - b.order;
    });
    var half = function (r) {
      return h.boxH(r.l.size) / 2;
    };
    var apart = function (a, b) {
      return look("lineHeight") * Math.max(a.l.size, b.l.size);
    };
    for (var i = 0; i < rows.length; i++) {
      var floor = i === 0 ? lo + half(rows[0]) : rows[i - 1].y + apart(rows[i - 1], rows[i]);
      rows[i].y = Math.max(rows[i].want, floor);
    }
    var n = rows.length - 1;
    rows[n].y = Math.min(rows[n].y, hi - half(rows[n]));
    for (var j = n - 1; j >= 0; j--) rows[j].y = Math.min(rows[j].y, rows[j + 1].y - apart(rows[j], rows[j + 1]));
    return rows[0].y - half(rows[0]) >= lo - 1e-9;
  }

  R.chrome = { helpers: helpers, level: level, titles: titles, wrapTwo: wrapTwo, legendPlace: legendPlace, legendLayout: legendLayout, legendPrims: legendPrims, legendModel: legendModel, legendKeys: legendKeys, endRoom: endRoom, endLabels: endLabels };
})(JsRendered);
