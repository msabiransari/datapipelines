/*
 * The table renderer for the Datapipelines dashboard runtime (#10, the implementation spec's §10.3).
 * Registered on load as renderer kind `table`, major version `1` — the value the E2E fixtures seed.
 *
 * ## The rendering rule
 * Bound values and labels reach the DOM as TEXT: every cell is `textContent`, never `innerHTML` — a
 * data column that happens to contain markup renders as text, whole.
 *
 * ## The configuration (Dashboards §2.1.2's table schema)
 * `columns[]` each carry `label`, `values` (the path the binding fills), an optional `format`
 * (`text|number|integer|percent|date|datetime`) and `align`. The `visualization_data` frame's
 * `bindings` maps a column's `values` path to the column's array. A configuration with no columns
 * (the SQL-seeded fixtures' shape) renders one column per bound path. `page_size` caps the rows
 * rendered (round one: no pager; the cap is the page).
 */
(function () {
  "use strict";

  var REGISTERED = false;

  var FORMATS = ["text", "number", "integer", "percent", "date", "datetime"];

  function format(value, format) {
    if (value === null || value === undefined || value === "") return "";
    switch (format) {
      case "number":
        return Number(value).toLocaleString();
      case "integer":
        return Math.round(Number(value)).toLocaleString();
      case "percent":
        return (Number(value) * 100).toLocaleString() + "%";
      case "date":
        return new Date(value).toLocaleDateString();
      case "datetime":
        return new Date(value).toLocaleString();
      default:
        return String(value);
    }
  }

  function create(context) {
    var host = context.host;
    var occurrence = context.occurrence || {};
    var stored = occurrence.config || {};
    var table = null;

    function columnsFor(bindings) {
      if (Array.isArray(stored.columns) && stored.columns.length > 0) return stored.columns;
      var out = [];
      for (var path in bindings) {
        if (Object.prototype.hasOwnProperty.call(bindings, path)) out.push({ label: path, values: path });
      }
      return out;
    }

    return {
      renderData: function (occurrenceRef, rows, bindings) {
        var bound = bindings || {};
        var columns = columnsFor(bound);
        if (!rows || rows === 0) return Promise.resolve("no-data");
        var pageSize = typeof stored.page_size === "number" && stored.page_size > 0 ? stored.page_size : 100;
        if (table && table.parentNode) table.parentNode.removeChild(table);
        table = document.createElement("table");
        table.className = "dp-dashboard-table";
        var head = document.createElement("thead");
        var headRow = document.createElement("tr");
        for (var c = 0; c < columns.length; c++) {
          var th = document.createElement("th");
          th.scope = "col";
          th.textContent = columns[c].label !== undefined && columns[c].label !== null ? String(columns[c].label) : "";
          if (columns[c].align) th.style.textAlign = columns[c].align;
          headRow.appendChild(th);
        }
        head.appendChild(headRow);
        table.appendChild(head);
        var body = document.createElement("tbody");
        var width = 0;
        for (var c2 = 0; c2 < columns.length; c2++) {
          var values = bound[columns[c2].values];
          if (Array.isArray(values)) width = Math.max(width, values.length);
        }
        for (var r = 0; r < Math.min(width, pageSize); r++) {
          var tr = document.createElement("tr");
          for (var i = 0; i < columns.length; i++) {
            var td = document.createElement("td");
            var column = columns[i];
            var array = bound[column.values];
            var value = Array.isArray(array) ? array[r] : null;
            td.textContent = format(value, column.format);
            if (column.align) td.style.textAlign = column.align;
            tr.appendChild(td);
          }
          body.appendChild(tr);
        }
        table.appendChild(body);
        host.appendChild(table);
        return Promise.resolve("rendered");
      },
      resize: function () {},
      dispose: function () {
        if (table && table.parentNode) table.parentNode.removeChild(table);
        table = null;
      },
    };
  }

  var api = {
    register: function (dashboard) {
      if (REGISTERED || !dashboard) return;
      dashboard.registerRenderer({ kind: "table", version: "1", create: create });
      REGISTERED = true;
    },
    _internal: { format: format, FORMATS: FORMATS },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DatapipelinesDashboardTable = api;
    api.register(window.DatapipelinesDashboard);
  }
})();
