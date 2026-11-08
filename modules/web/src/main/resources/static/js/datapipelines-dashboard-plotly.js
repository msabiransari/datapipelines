/*
 * The Plotly converter of the `chart` kind (design record 2026-11-06 §5.2): the bar family's pure
 * `(spec, bindings, tokens) → {option, warnings}` — ported from the 854 reference `convert-bar-plotly.mjs` — and
 * Plotly's host side (`react`, `rendered` on `plotly_afterplot`, resize, purge). It registers no renderer kind; it
 * registers itself with the chart adapter (`datapipelines-dashboard-chart.js`, loaded before it) as library `plotly`.
 *
 * ## Text is DATA (the rendering rule)
 * Plotly parses a subset of HTML in names, tick text, hover text, titles and bar text (`<a href>`, `<img>`, `<b>`), so
 * EVERY string the converter writes into the option is entity-escaped (`&`, `<`, `>`, quotes) — document text and
 * bound data alike. Categories are positional ids (`c0`, `c1`, …) labelled by `ticktext`, so no category value is ever
 * parsed by Plotly; value ticks are computed by the core and written as `tickvals`/`ticktext` (Plotly takes no format
 * function).
 *
 * ## Q-L4 (record O-2; 854 `matrix/quirks.md` Q-L4)
 * A horizontal bar's outside value label is drawn past the bar's end; `cliponaxis: false` lets it leave the plotting
 * rectangle, and the right margin is sized to the widest label so the label stays inside the CHART BOX (the container
 * element's box). The width is estimated from the label's characters at Plotly's default 12 px text size; the
 * browser suite's bounding-box assertion is the oracle.
 *
 * ## The host side
 * `instance` is the Plotly object the host declared at init (`charts: {library: "plotly", instance: window.Plotly}`);
 * this file never reads `window.Plotly`. The page loads exactly one vendored bundle (`data-dp-plotly-bundle`), and the
 * runtime places the CSP style marker before any draw.
 */
(function () {
  "use strict";

  var chart =
    typeof module !== "undefined" && module.exports
      ? require("./datapipelines-dashboard-chart.js") // node --test and the parity/equivalence scripts
      : typeof window !== "undefined"
        ? window.DatapipelinesDashboardChart
        : null;

  /** Every bar field is `direct` in Plotly (`chart-support/plotly.json`: 35 rows, all direct). */
  var DEGRADED = {};

  var LEGEND = {
    top: { orientation: "h", x: 0, xanchor: "left", y: 1.02, yanchor: "bottom" },
    bottom: { orientation: "h", x: 0, xanchor: "left", y: -0.18, yanchor: "top" },
    left: { orientation: "v", x: -0.12, xanchor: "right", y: 1, yanchor: "top" },
    right: { orientation: "v", x: 1.02, xanchor: "left", y: 1, yanchor: "top" },
  };

  /** The reference's right margin; Q-L4 widens it for horizontal outside labels only. */
  var MARGIN_RIGHT = 20;

  /** Plotly's default text size (`layout.font.size`), which the outside bar text inherits. */
  var TEXT_PX = 12;

  /** Plotly's outside-text pad plus a safety allowance for the font the browser actually picks. */
  var LABEL_PAD_PX = 12;

  /**
   * Upper-bound advance widths, in em, of a sans-serif face by character class: what a formatted value label is made
   * of (digits, separators, signs, `%`, compact suffixes, currency symbols).
   */
  function charEm(c) {
    if (/[0-9]/.test(c)) return 0.6;
    if (/[.,:;'!|  ]/.test(c)) return 0.34;
    if (/[-−+]/.test(c)) return 0.62;
    if (/[%MWmw@]/.test(c)) return 0.95;
    if (/[A-Z]/.test(c)) return 0.75;
    if (/[a-z]/.test(c)) return 0.6;
    return 1.0;
  }

  function labelWidth(text) {
    var w = 0;
    var cps = Array.from(String(text));
    for (var i = 0; i < cps.length; i++) w += charEm(cps[i]);
    return Math.ceil(w * TEXT_PX);
  }

  /** The bar spec → Plotly `{data, layout, config}` (the product's 2D bundle). Pure. */
  function convert(spec, bindings, tokens) {
    var core = chart.core;
    var e = core.escapeHtml;
    var m = core.model(spec, bindings, tokens);
    var c = spec.config;
    var t = core.titles(m);
    var ids = m.categories.map(function (_, i) {
      return "c" + i;
    });
    var B = m.vertical ? "x" : "y";
    var V = m.vertical ? "y" : "x";
    var labels = c.mark.labels === "values";
    var legendOn = !!m.color && m.color.enc.legend !== null;
    var data = m.series.map(function (s, si) {
      var trace = {
        type: "bar",
        orientation: m.vertical ? "v" : "h",
        name: e(s.full),
      };
      trace[B] = ids;
      trace[V] = m.cells[si].map(function (v) {
        return v.num;
      });
      trace.hovertext = m.categories.map(function (_, ci) {
        return e(core.tooltipText(m.tooltip(si, ci)));
      });
      trace.hoverinfo = "text";
      trace.marker = { color: tokens.series[si % tokens.series.length] };
      if (labels) {
        trace.text = m.cells[si].map(function (v) {
          return e(v.text);
        });
        trace.textposition = m.stack ? "inside" : "outside";
        trace.cliponaxis = false;
      }
      trace.showlegend = legendOn;
      return trace;
    });
    var axisTitle = function (x) {
      return x === null ? { text: "" } : { text: e(x) };
    };
    var band = {
      type: "category",
      categoryorder: "array",
      categoryarray: ids,
      tickmode: "array",
      tickvals: ids,
      ticktext: m.categories.map(function (k) {
        return e(k.label);
      }),
      showgrid: !!(m.band.enc.axis && m.band.enc.axis.grid),
      gridcolor: tokens.grid,
      title: axisTitle(t.band),
      automargin: true,
      fixedrange: true,
      color: tokens.muted,
    };
    if (!m.vertical) band.autorange = "reversed";
    var value = {
      type: "linear",
      range: [m.axis.lo, m.axis.hi],
      autorange: false,
      tickmode: "array",
      tickvals: m.axis.ticks,
      ticktext: m.axis.tickText.map(e),
      showgrid: m.value.enc.axis && "grid" in m.value.enc.axis ? m.value.enc.axis.grid : true,
      gridcolor: tokens.grid,
      zeroline: true,
      zerolinecolor: tokens.muted,
      title: axisTitle(t.value),
      automargin: true,
      fixedrange: true,
      color: tokens.muted,
    };
    var right = MARGIN_RIGHT;
    if (labels && !m.vertical && !m.stack) {
      // Q-L4: the widest outside label must fit between the plotting rectangle's right edge and the chart box's.
      m.cells.forEach(function (row) {
        row.forEach(function (v) {
          if (v.num !== null) right = Math.max(right, labelWidth(v.text) + LABEL_PAD_PX);
        });
      });
    }
    var layout = {
      barmode: m.stack === "zero" || m.stack === "normalize" ? "relative" : "group",
      paper_bgcolor: tokens.canvas,
      plot_bgcolor: tokens.plot,
      font: { family: tokens.font, color: tokens.ink },
      showlegend: legendOn,
      hovermode: "closest",
      margin: { t: c.title ? 80 : 30, r: right, b: 60, l: 60 },
    };
    layout[B + "axis"] = band;
    layout[V + "axis"] = value;
    if (c.title) {
      layout.title = { text: e(c.title.text) };
      if (c.title.subtitle) layout.title.subtitle = { text: e(c.title.subtitle) };
    }
    if (legendOn) {
      var orient = (m.color.enc.legend && m.color.enc.legend.orient) || "right";
      layout.legend = Object.assign({}, LEGEND[orient], { traceorder: "normal" });
      if (t.series !== null) layout.legend.title = { text: e(t.series) };
    }
    var warnings = m.warnings.concat(core.degradedWarnings(core.setFields(spec, spec.columns), DEGRADED));
    return { option: { data: data, layout: layout, config: { displayModeBar: false, responsive: false } }, warnings: warnings };
  }

  /** `Plotly.react` with the converted option; resolves on `plotly_afterplot` or the call's own promise, whichever first. */
  function draw(Plotly, host, option) {
    if (!Plotly || typeof Plotly.react !== "function") {
      return Promise.reject(new Error("the declared Plotly instance cannot draw: it has no react function"));
    }
    return new Promise(function (resolve, reject) {
      var settled = false;
      function done() {
        if (settled) return;
        settled = true;
        resolve("rendered");
      }
      host.addEventListener("plotly_afterplot", done, { once: true });
      Promise.resolve(Plotly.react(host, option.data, option.layout, option.config)).then(done, function (error) {
        if (settled) return;
        settled = true;
        reject(error);
      });
    });
  }

  function resize(Plotly, host) {
    if (Plotly && Plotly.Plots && typeof Plotly.Plots.resize === "function") Plotly.Plots.resize(host);
  }

  function dispose(Plotly, host) {
    if (Plotly && typeof Plotly.purge === "function") Plotly.purge(host);
  }

  var api = {
    /** Idempotent: a second call replaces the library's converter with the same one. */
    register: function (chartAdapter) {
      if (!chartAdapter) return;
      chartAdapter.registerConverter({ library: "plotly", families: { bar: convert }, draw: draw, resize: resize, dispose: dispose });
    },
    /** For the node tests and the equivalence oracle: the pure converter and the Q-L4 width estimate. */
    _internal: {
      convert: convert,
      labelWidth: labelWidth,
      MARGIN_RIGHT: MARGIN_RIGHT,
      LABEL_PAD_PX: LABEL_PAD_PX,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DatapipelinesDashboardPlotly = api;
    api.register(window.DatapipelinesDashboardChart);
  }
})();
