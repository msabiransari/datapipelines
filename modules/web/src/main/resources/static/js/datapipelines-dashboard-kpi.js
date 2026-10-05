/*
 * The KPI renderer for the Datapipelines dashboard runtime (#10, the implementation spec's §10.3).
 * Registered on load as renderer kind `kpi`, major version `1` — the value the E2E fixtures seed.
 *
 * ## The rendering rule
 * The value and the comparison value are DATA: `textContent`, never `innerHTML`.
 *
 * ## The configuration (Dashboards §2.1.2's kpi schema)
 * `label`, `value` (the path the binding fills), optional `format` (`number|integer|percent|currency`),
 * optional `unit` (≤ 16 chars, the schema's bound) and optional `comparison` (`{label, value}` — the
 * comparison value is bound through the SAME map). A zero KPI renders (a zero is a value, not
 * `no-data`).
 */
(function () {
  "use strict";

  var REGISTERED = false;

  function format(value, format) {
    if (value === null || value === undefined || value === "") return "";
    switch (format) {
      case "integer":
        return Math.round(Number(value)).toLocaleString();
      case "percent":
        return (Number(value) * 100).toLocaleString() + "%";
      case "currency":
        return Number(value).toLocaleString(undefined, { style: "currency", currency: "USD" });
      default:
        return Number(value).toLocaleString();
    }
  }

  function create(context) {
    var host = context.host;
    var occurrence = context.occurrence || {};
    var stored = occurrence.config || {};
    var root = null;

    function build() {
      root = document.createElement("div");
      root.className = "dp-dashboard-kpi";
      var label = document.createElement("div");
      label.className = "dp-dashboard-kpi-label";
      label.textContent = stored.label !== undefined && stored.label !== null ? String(stored.label) : "";
      root.appendChild(label);
      var value = document.createElement("div");
      value.className = "dp-dashboard-kpi-value";
      var number = document.createElement("span");
      number.className = "dp-dashboard-kpi-number";
      value.appendChild(number);
      root.appendChild(value);
      if (stored.unit) {
        var unit = document.createElement("span");
        unit.className = "dp-dashboard-kpi-unit";
        unit.textContent = " " + String(stored.unit);
        value.appendChild(unit);
      }
      if (stored.comparison) {
        var comparison = document.createElement("div");
        comparison.className = "dp-dashboard-kpi-comparison";
        var comparisonLabel = document.createElement("span");
        comparisonLabel.className = "dp-dashboard-kpi-comparison-label";
        comparisonLabel.textContent =
          stored.comparison.label !== undefined && stored.comparison.label !== null ? String(stored.comparison.label) : "";
        var comparisonValue = document.createElement("span");
        comparisonValue.className = "dp-dashboard-kpi-comparison-value";
        comparison.appendChild(comparisonLabel);
        comparison.appendChild(comparisonValue);
        root.appendChild(comparison);
      }
      host.appendChild(root);
    }

    return {
      renderData: function (occurrenceRef, rows, bindings) {
        var bound = bindings || {};
        if (!rows || rows === 0) return Promise.resolve("no-data");
        if (!root) build();
        var array = Object.prototype.hasOwnProperty.call(bound, "value") ? bound.value : bound[stored.value];
        var value = Array.isArray(array) ? array[0] : null;
        var numberElement = root.querySelector(".dp-dashboard-kpi-number");
        numberElement.textContent = format(value, stored.format);
        if (stored.comparison) {
          var comparisonArray = Object.prototype.hasOwnProperty.call(bound, "comparison.value")
            ? bound["comparison.value"]
            : bound[stored.comparison.value];
          var comparisonValue = Array.isArray(comparisonArray) ? comparisonArray[0] : null;
          var target = root.querySelector(".dp-dashboard-kpi-comparison-value");
          if (target) target.textContent = format(comparisonValue, stored.format);
        }
        return Promise.resolve("rendered");
      },
      resize: function () {},
      dispose: function () {
        if (root && root.parentNode) root.parentNode.removeChild(root);
        root = null;
      },
    };
  }

  var api = {
    register: function (dashboard) {
      if (REGISTERED || !dashboard) return;
      dashboard.registerRenderer({ kind: "kpi", version: "1", create: create });
      REGISTERED = true;
    },
    _internal: { format: format },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DatapipelinesDashboardKpi = api;
    api.register(window.DatapipelinesDashboard);
  }
})();
