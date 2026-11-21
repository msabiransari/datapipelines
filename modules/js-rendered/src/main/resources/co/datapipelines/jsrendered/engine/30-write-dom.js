// 30-write-dom.js : from primitives to SVG elements (record §4.3, §7). plan(primitives, form) turns the flat list into
// a tree of {tag, attrs: [[name, value]], text, kids}: the ONE mapping both writers share, so the browser's DOM and the
// server's string are the same picture. Every attribute NAME is a constant of this file, never a value from a
// document; no href, no style attribute and no event attribute is ever written. writeDom builds the tree with
// createElementNS, setAttribute and textContent only, and sets what it is handed (look contract §9): the colours the
// primitives carry and the font of the theme it is given, never a var() (the engine never reads a stylesheet, record
// §5.2; the page's adapter resolves the tokens and redraws on a theme change, §4.8). Translated from lane 977's
// 30-writers.js (adapted: the named fields, the two forms, the frame read from the canvas rectangle, no markup here).
(function (R) {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";

  function num(v) {
    return String(v);
  }

  /** A colour attribute: page form → var(--token, resolved) (the host's custom property wins); standalone → resolved. */
  function paint(form, token, resolved) {
    if (token === null) return "none";
    return form === "page" ? "var(" + token + ", " + resolved + ")" : resolved;
  }

  function pathText(d) {
    var out = [];
    for (var i = 0; i < d.length; i++) {
      var c = d[i];
      if (c === "M" || c === "L") {
        out.push(c + num(d[i + 1]) + " " + num(d[i + 2]));
        i += 2;
      } else if (c === "A") {
        out.push("A" + num(d[i + 1]) + " " + num(d[i + 1]) + " 0 0 1 " + num(d[i + 2]) + " " + num(d[i + 3]));
        i += 3;
      } else if (c === "C") {
        out.push("C" + num(d[i + 1]) + " " + num(d[i + 2]) + " " + num(d[i + 3]) + " " + num(d[i + 4]) + " " + num(d[i + 5]) + " " + num(d[i + 6]));
        i += 6;
      } else out.push("Z");
    }
    return out.join(" ");
  }

  function common(p, form, attrs) {
    attrs.push(["class", "dp-" + p.role]);
    if (p.fill !== undefined) attrs.push(["fill", paint(form, p.fill, p.color)]);
    if (p.stroke !== undefined && p.stroke !== null) {
      attrs.push(["stroke", paint(form, p.stroke, p.strokeColor)]);
      attrs.push(["stroke-width", num(p.strokeWidth)]);
    }
    if (p.opacity !== undefined && p.opacity !== 1) attrs.push(["opacity", num(p.opacity)]);
    return attrs;
  }

  function node(p, form, font) {
    var a = [];
    if (p.k === "rect") {
      a.push(["x", num(p.x)], ["y", num(p.y)], ["width", num(p.w)], ["height", num(p.h)]);
      if (p.rx > 0) a.push(["rx", num(p.rx)]);
      return { tag: "rect", attrs: common(p, form, a), text: null, kids: [] };
    }
    if (p.k === "line") {
      a.push(["x1", num(p.x1)], ["y1", num(p.y1)], ["x2", num(p.x2)], ["y2", num(p.y2)]);
      return { tag: "line", attrs: common(p, form, a), text: null, kids: [] };
    }
    if (p.k === "path") {
      a.push(["d", pathText(p.d)]);
      var attrs = common(p, form, a);
      if (p.round) attrs.push(["stroke-linejoin", "round"], ["stroke-linecap", "round"]);
      if (p.fade === null) return { tag: "path", attrs: attrs, text: null, kids: [] };
      // A faded fill (look contract §2 "area"): the path's fill names a vertical gradient written just before it. The
      // gradient's id is made from everything it draws (colour, opacities, the two ys), so two charts on one page that
      // write the same id write the same gradient, and no counter or state is kept between calls.
      var id = fadeId(p, form);
      for (var f = 0; f < attrs.length; f++) if (attrs[f][0] === "fill") attrs[f][1] = "url(#" + id + ")";
      return { tag: "path", attrs: attrs, text: null, kids: [], before: fadeNode(p, form, id) };
    }
    if (p.k === "circle") {
      a.push(["cx", num(p.cx)], ["cy", num(p.cy)], ["r", num(p.r)]);
      return { tag: "circle", attrs: common(p, form, a), text: null, kids: [] };
    }
    if (p.k === "text") {
      a.push(["x", num(p.x)], ["y", num(p.y)]);
      if (p.anchor !== "start") a.push(["text-anchor", p.anchor]);
      a.push(["font-size", num(p.size)], ["font-family", form === "page" ? "var(--font-sans, " + font + ")" : font]);
      if (p.weight !== 400) a.push(["font-weight", num(p.weight)]);
      if (p.rotate !== 0) a.push(["transform", "rotate(" + num(p.rotate) + " " + num(p.x) + " " + num(p.y) + ")"]);
      if (p.struck) a.push(["text-decoration", "line-through"]);
      common(p, form, a);
      return { tag: "text", attrs: a, text: p.shown !== null ? p.shown : p.text, kids: [] };
    }
    if (p.k === "group") {
      a.push(["class", "dp-" + p.role + (p.on ? "" : " dp-off")]);
      if (p.series !== null) a.push(["data-series", p.series]);
      if (p.legend !== null) a.push(["data-legend", p.legend]);
      if (p.label !== null) a.push(["aria-label", p.label]);
      // A legend entry is a button that switches its series (record §4.8, brief 990 D4): pressed while the series is
      // shown. The interaction layer answers its click and its keys; a picture without the layer only names it.
      if (p.legend !== null) a.push(["role", "button"], ["aria-pressed", p.on ? "true" : "false"]);
      return { tag: "g", attrs: a, text: null, kids: [] };
    }
    throw new Error("write: unknown primitive kind " + p.k);
  }

  /** An id's characters from a free text: letters and digits kept, every other character as _<code>_ (one to one). */
  function idText(s) {
    var out = "";
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      out += /[A-Za-z0-9]/.test(c) ? c : "_" + s.charCodeAt(i) + "_";
    }
    return out;
  }

  function fadeId(p, form) {
    var f = p.fade;
    return "dp-fade-" + idText([form, p.fill, p.color, f[0], f[1], f[2], f[3]].join(" "));
  }

  /** The gradient element of a faded path: userSpaceOnUse from (0, y0) to (0, y1), two stops of the fill colour. */
  function fadeNode(p, form, id) {
    var f = p.fade, colour = paint(form, p.fill, p.color);
    var stop = function (offset, opacity) {
      return { tag: "stop", attrs: [["offset", offset], ["stop-color", colour], ["stop-opacity", num(opacity)]], text: null, kids: [] };
    };
    return {
      tag: "linearGradient",
      attrs: [["id", id], ["gradientUnits", "userSpaceOnUse"], ["x1", "0"], ["y1", num(f[2])], ["x2", "0"], ["y2", num(f[3])]],
      text: null,
      kids: [stop("0", f[0]), stop("1", f[1])],
    };
  }

  /**
   * The element tree of a picture: {w, h, kids}. The frame is the first primitive, the canvas rectangle at 0, 0 every
   * drawn state begins with; an empty list (too-small, refused) is a 0 × 0 picture.
   */
  function plan(primitives, form, font) {
    if (form !== "page" && form !== "standalone") throw new Error("write: form is page or standalone");
    var f = primitives.length > 0 && primitives[0].k === "rect" && primitives[0].x === 0 && primitives[0].y === 0 ? primitives[0] : null;
    var root = { w: f ? f.w : 0, h: f ? f.h : 0, kids: [] };
    var stack = [{ kids: root.kids, left: Infinity }];
    for (var i = 0; i < primitives.length; i++) {
      var p = primitives[i], el = node(p, form, font);
      if (el.before !== undefined) {
        stack[stack.length - 1].kids.push(el.before);
        delete el.before;
      }
      stack[stack.length - 1].kids.push(el);
      for (var s = 1; s < stack.length; s++) stack[s].left--;
      if (p.k === "group" && p.n > 0) stack.push({ kids: el.kids, left: p.n });
      while (stack.length > 1 && stack[stack.length - 1].left === 0) stack.pop();
    }
    if (stack.length > 1) throw new Error("write: a group counts more records than follow it");
    return root;
  }

  function build(doc, parent, kids) {
    var made = [];
    for (var i = 0; i < kids.length; i++) {
      var k = kids[i], el = doc.createElementNS(NS, k.tag);
      for (var a = 0; a < k.attrs.length; a++) el.setAttribute(k.attrs[a][0], k.attrs[a][1]);
      if (k.text !== null) el.textContent = k.text;
      build(doc, el, k.kids);
      parent.appendChild(el);
      made.push(el);
    }
    return made;
  }

  /** "Bar", "Line": a type name with its first letter in capitals. */
  function typeName(type) {
    var t = typeof type === "string" ? type : "";
    return t.charAt(0).toUpperCase() + t.slice(1);
  }

  /**
   * The accessible description of a draw (record §4.8, brief 990 D6): {title, desc}. title = the document's display
   * name; desc = the type, the series names of the data, a legend of keys' names (summary.keys), the mark count, then the document's description when it has
   * one. Both are data and reach a picture as text nodes only. output = render's result; document = its input's.
   */
  function describe(output, document) {
    var doc = document && typeof document === "object" ? document : {};
    var summary = output && output.summary ? output.summary : { series: [], marks: 0 };
    var parts = [typeName(output && output.type) + " chart"];
    if (output && output.state === "no-data") parts.push("no data");
    else {
      var names = [];
      for (var i = 0; i < summary.series.length; i++) names.push(summary.series[i].title);
      if (names.length > 0) parts.push("series: " + names.join(", "));
      // A legend of keys (brief 1054 D6): its entries' names, as the series are named.
      if (Array.isArray(summary.keys) && summary.keys.length > 0) {
        var titles = [];
        for (var k = 0; k < summary.keys.length; k++) titles.push(summary.keys[k].title);
        parts.push("legend: " + titles.join(", "));
      }
      parts.push(R.text.value(summary.marks, "integer") + (summary.marks === 1 ? " mark" : " marks"));
    }
    var desc = parts.join("; ") + ".";
    if (typeof doc.description === "string" && doc.description !== "") desc += " " + doc.description;
    return { title: typeof doc.display_name === "string" ? doc.display_name : "", desc: desc };
  }

  /** The two description elements of a picture (an `about` of describe's shape), as plan nodes; none without one. */
  function aboutNodes(about) {
    if (about === undefined || about === null) return [];
    if (typeof about !== "object" || typeof about.title !== "string" || typeof about.desc !== "string") throw new Error("write: about is {title, desc}");
    return [{ tag: "title", attrs: [], text: about.title, kids: [] }, { tag: "desc", attrs: [], text: about.desc, kids: [] }];
  }

  /**
   * writeDom(primitives, svg, theme, about): the picture into an svg element of the page, its old children removed. Every
   * value is a literal: each colour the one its primitive carries, the font the given theme's (a name or {base, tokens},
   * as render takes it; absent → the engine's default font). The plan's literal form, the one the standalone string
   * writes; no attribute value names a custom property. The picture is one image (`role="img"`); with `about`
   * (describe's {title, desc}) its `<title>` and `<desc>` are its first children (D6).
   */
  function writeDom(primitives, svg, theme, about) {
    var root = plan(primitives, "standalone", R.theme.resolve(theme).font);
    var described = aboutNodes(about);
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    svg.setAttribute("viewBox", "0 0 " + num(root.w) + " " + num(root.h));
    svg.setAttribute("width", num(root.w));
    svg.setAttribute("height", num(root.h));
    svg.setAttribute("role", "img");
    build(svg.ownerDocument, svg, described);
    build(svg.ownerDocument, svg, root.kids);
    return svg;
  }

  /**
   * append(primitives, parent, theme): primitives written after the children `parent` already has, nothing removed
   * (the interaction layer's card, guide and focus ring over a drawn picture, brief 990 D2). Returns the elements made
   * at the top level, so their writer can remove exactly those.
   */
  function append(primitives, parent, theme) {
    var kids = plan(primitives, "standalone", R.theme.resolve(theme).font).kids;
    return build(parent.ownerDocument, parent, kids);
  }

  R.write = { plan: plan, pathText: pathText, NS: NS, append: append, describe: describe, aboutNodes: aboutNodes };
  R.writeDom = writeDom;
})(JsRendered);
