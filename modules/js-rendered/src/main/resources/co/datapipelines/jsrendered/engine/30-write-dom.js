// 30-write-dom.js : from primitives to SVG elements (record §4.3, §7). plan(primitives, form) turns the flat list into
// a tree of {tag, attrs: [[name, value]], text, kids}: the ONE mapping both writers share, so the browser's DOM and the
// server's string are the same picture. Every attribute NAME is a constant of this file, never a value from a
// document; no href, no style attribute and no event attribute is ever written. writeDom builds the tree with
// createElementNS, setAttribute and textContent only. Translated from lane 977's 30-writers.js (adapted: the named
// fields, the two forms, the frame read from the canvas rectangle, no markup in this file).
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
      return { tag: "path", attrs: common(p, form, a), text: null, kids: [] };
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
      return { tag: "g", attrs: a, text: null, kids: [] };
    }
    throw new Error("write: unknown primitive kind " + p.k);
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
      stack[stack.length - 1].kids.push(el);
      for (var s = 1; s < stack.length; s++) stack[s].left--;
      if (p.k === "group" && p.n > 0) stack.push({ kids: el.kids, left: p.n });
      while (stack.length > 1 && stack[stack.length - 1].left === 0) stack.pop();
    }
    if (stack.length > 1) throw new Error("write: a group counts more records than follow it");
    return root;
  }

  function build(doc, parent, kids) {
    for (var i = 0; i < kids.length; i++) {
      var k = kids[i], el = doc.createElementNS(NS, k.tag);
      for (var a = 0; a < k.attrs.length; a++) el.setAttribute(k.attrs[a][0], k.attrs[a][1]);
      if (k.text !== null) el.textContent = k.text;
      build(doc, el, k.kids);
      parent.appendChild(el);
    }
  }

  /** The page form's fallback font: the built-in default of --font-sans (the host's custom property wins). */
  var FONT = R.theme.TOKENS["--font-sans"][1];

  /** writeDom(primitives, svg): the picture into an svg element of the page, its old children removed (page form). */
  function writeDom(primitives, svg) {
    var root = plan(primitives, "page", FONT);
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    svg.setAttribute("viewBox", "0 0 " + num(root.w) + " " + num(root.h));
    svg.setAttribute("width", num(root.w));
    svg.setAttribute("height", num(root.h));
    build(svg.ownerDocument, svg, root.kids);
    return svg;
  }

  R.write = { plan: plan, pathText: pathText, NS: NS };
  R.writeDom = writeDom;
})(JsRendered);
