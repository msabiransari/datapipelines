(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's INSPECTOR (#374): renders PSModel.inspect()'s rows into a region. TEXT ONLY — every
   * value reaches the DOM through textContent / setAttribute on an element this file created; nothing here builds an
   * HTML string, and a link's href is composed from encodeURIComponent'ed parts by the model.
   */

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function renderRow(row) {
    var wrap = el("div", "ps-insp-row");
    wrap.appendChild(el("dt", "ps-insp-label", row.label));
    var dd = el("dd", "ps-insp-value");
    if (row.kind === "code") {
      dd.appendChild(el("pre", "ps-insp-code", row.value));
    } else if (row.kind === "table") {
      var table = el("table", "ps-insp-table");
      var head = el("tr");
      row.headers.forEach(function (h) {
        head.appendChild(el("th", null, h));
      });
      table.appendChild(head);
      row.body.forEach(function (cells) {
        var tr = el("tr");
        cells.forEach(function (c) {
          tr.appendChild(el("td", null, c));
        });
        table.appendChild(tr);
      });
      dd.appendChild(table);
    } else if (row.kind === "links") {
      row.links.forEach(function (l) {
        var a = el("a", "ps-insp-link", l.text);
        a.setAttribute("href", l.href);
        a.setAttribute("hx-boost", "false");
        dd.appendChild(a);
      });
    } else {
      dd.textContent = row.value;
    }
    wrap.appendChild(dd);
    return wrap;
  }

  /** Mounts into [region]: returns {show(parameter, set, last, stream), clear()}. [stream] is a live frame failure. */
  function mount(region) {
    var empty = "Select a parameter in the graph to inspect it.";
    function clear() {
      region.replaceChildren(el("p", "ps-insp-empty", empty));
    }
    function show(parameter, set, last, stream) {
      var list = el("dl", "ps-insp-list");
      window.PSModel.inspect(parameter, set, last, stream).forEach(function (row) {
        list.appendChild(renderRow(row));
      });
      var title = el("h3", "ps-insp-title", parameter.label || parameter.name);
      region.replaceChildren(title, list);
    }
    clear();
    return { show: show, clear: clear };
  }

  window.PSInspector = { mount: mount };
})();
