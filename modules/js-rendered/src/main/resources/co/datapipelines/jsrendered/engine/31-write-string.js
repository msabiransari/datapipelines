// 31-write-string.js : the server's writer (record §4.3, §7, A8). THE ONE FILE OF THE ENGINE THAT HOLDS MARKUP
// STRINGS (the source guard names it). writeString(primitives, form, theme) serializes the tree of R.write.plan, the
// same tree writeDom builds, escaping EVERY text node and EVERY attribute value: & < > " ' and the three white-space
// controls. Attribute names come from the plan's constants only. form "page" writes classes and var(--token,
// resolved) colours for a page that holds the tokens; "standalone" writes literal colours for an image tag.
(function (R) {
  "use strict";
  var ENTITY = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;", "\t": "&#9;", "\n": "&#10;", "\r": "&#13;" };
  var SPECIAL = /[&<>"'\t\n\r]/g;

  function escape(s) {
    return String(s).replace(SPECIAL, function (c) {
      return ENTITY[c];
    });
  }

  function attrs(list) {
    var out = "";
    for (var i = 0; i < list.length; i++) out += " " + list[i][0] + '="' + escape(list[i][1]) + '"';
    return out;
  }

  function element(k) {
    var inner = k.text !== null ? escape(k.text) : "";
    for (var i = 0; i < k.kids.length; i++) inner += element(k.kids[i]);
    return "<" + k.tag + attrs(k.attrs) + (inner === "" && k.text === null ? "/>" : ">" + inner + "</" + k.tag + ">");
  }

  /**
   * The picture as one SVG document string. theme: the resolved or given theme; its font names the text's family. The
   * picture is one image (`role="img"`); with `about` (R.write.describe's {title, desc}) its `<title>` and `<desc>` come
   * first, escaped like every text (brief 990 D6).
   */
  function writeString(primitives, form, theme, about) {
    var font = R.theme.resolve(theme).font;
    var root = R.write.plan(primitives, form, font);
    var w = String(root.w), h = String(root.h);
    var head = "<svg" + attrs([["xmlns", R.write.NS], ["width", w], ["height", h], ["viewBox", "0 0 " + w + " " + h], ["class", "dp-chart"], ["role", "img"]]) + ">";
    var body = "";
    var described = R.write.aboutNodes(about);
    for (var d = 0; d < described.length; d++) body += element(described[d]);
    for (var i = 0; i < root.kids.length; i++) body += element(root.kids[i]);
    return head + body + "</svg>";
  }

  R.writeString = writeString;
  R.escape = escape;
})(JsRendered);
