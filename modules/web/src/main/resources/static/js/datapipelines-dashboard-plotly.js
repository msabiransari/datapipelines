/*
 * The Plotly renderer for the Datapipelines dashboard runtime (#10, the implementation spec's
 * §10.3/§10.4, D25). Registered on load as renderer kind `plotly`, major version `4` — the major the
 * wire's `renderer.version` is matched against at bootstrap.
 *
 * ## Data injection (§3.1)
 * The stored configuration is NEVER mutated: each `visualization_data` frame carries the resolved
 * bindings (`{path: [values]}`) and the adapter substitutes them into a CLONE — `data[0].x`,
 * `layout.title.text`, any path — and hands the result to `Plotly.react`. That is the whole
 * data-injection contract; nothing else changes between the stored configuration and the rendered one.
 *
 * ## Bound values are DATA, not markup (the rendering rule)
 * Plotly renders a subset of HTML (`<img>`, `<a href>`, `<b>`) inside text, hover and titles, so a
 * bound string is markup-ready XSS. Every bound value is neutralised (`&`, `<`, `>` escaped) before it
 * touches Plotly — a data cell that happens to contain `<img onerror>` renders as TEXT. The author's
 * own configuration strings are the author's; only data is escaped.
 *
 * ## The palette (D25, the THEME SYSTEM rule)
 * No literal colour lives here: at each render the adapter resolves the app's `--chart-*` tokens
 * (app.css, bridged off the theme) through a PROBE element — `getComputedStyle` answers `color-mix()`
 * token streams verbatim, so the browser must compute them — and maps them into Plotly's `layout`
 * (paper, plot, grid, ink, the categorical `colorway`). `presentation.tokens` is an open map; the
 * names the adapter knows (`series`) are honoured, names it does not know are ignored. A token that
 * resolves to nothing degrades to Plotly's own defaults rather than painting wrong.
 *
 * ## The size defaults (#386)
 * A compact margin and `automargin` on the axes, so a figure in a 2-row slot keeps a plot area. They
 * sit UNDER the author's stored `margin`/`automargin` (the author wins, key by key); the theme sits
 * OVER the author's colours. Both precedences: dashboards.md §6.3.
 *
 * ## The bundle
 * The page loads exactly one bundle (`plotly-2d.min.js` default; `plotly-3d.min.js` for a board with a
 * 3D trace), declared on its script tag as `data-dp-plotly-bundle`; the runtime refuses a board whose
 * declared bundle cannot cover it. This file touches `window.Plotly` only at render time.
 */
(function () {
  "use strict";

  var REGISTERED = false;

  function isObject(value) {
    return !!value && typeof value === "object" && !Array.isArray(value);
  }

  /** `&` first, then the brackets — a bound string arrives as TEXT or not at all. */
  function escapeMarkup(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  /**
   * `data[0].x` → ["data", 0, "x"]. A path segment is either a name or `[n]`; a malformed path is
   * skipped (the binding simply does not land) — the server validated it at save.
   */
  function parsePath(path) {
    var segments = [];
    var parts = String(path).split(".");
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      var match = part.match(/^([^\[\]]+)\[(\d+)\]$/);
      if (match) {
        segments.push(match[1]);
        segments.push(Number(match[2]));
      } else if (/^\d+$/.test(part)) {
        segments.push(Number(part));
      } else {
        segments.push(part);
      }
    }
    return segments;
  }

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function substitute(config, bindings, escape) {
    var out = clone(config);
    if (!isObject(bindings)) return out;
    for (var path in bindings) {
      if (!Object.prototype.hasOwnProperty.call(bindings, path)) continue;
      var values = bindings[path];
      if (!Array.isArray(values)) continue;
      var segments = parsePath(path);
      var target = out;
      var ok = true;
      for (var i = 0; i < segments.length - 1; i++) {
        var key = segments[i];
        if (target == null || typeof target !== "object") {
          ok = false;
          break;
        }
        if (target[key] == null || typeof target[key] !== "object") {
          // Bindings resolve inside the configuration at SAVE (§3.1); a path that resolves nowhere
          // now is skipped whole — the adapter never invents structure.
          ok = false;
          break;
        }
        target = target[key];
      }
      if (!ok || target == null || typeof target !== "object") continue;
      var leaf = segments[segments.length - 1];
      // Strings are escaped (they are DATA, never markup); numbers, booleans and nulls pass through.
      target[leaf] = values.map(function (value) {
        return typeof value === "string" ? escape(value) : value;
      });
    }
    return out;
  }

  /**
   * `getComputedStyle(...).getPropertyValue('--x')` returns a custom property's TOKEN STREAM verbatim
   * (a `color-mix()` bridge arrives uncomputed), so colours resolve through a probe element the way
   * the pipeline editor's canvas reads its tokens (graph.js readDesignTokens, the same mould): assign
   * `color: var(--x)` to a real element and read `getComputedStyle(...).color` back, normalised from
   * CSS Color 4's `color(srgb …)` form to the legacy `rgb()/rgba()` Plotly's own colour validation
   * accepts. One FRESH probe per token (093: a reused probe answers its first colour); null when the
   * token resolves to nothing, which the caller reads as "leave Plotly's default".
   */
  function resolveToken(name) {
    if (typeof document === "undefined" || !document.body) return null;
    var probe = document.createElement("span");
    probe.setAttribute("aria-hidden", "true");
    probe.style.position = "absolute";
    probe.style.width = "0";
    probe.style.height = "0";
    probe.style.visibility = "hidden";
    probe.style.pointerEvents = "none";
    probe.style.transition = "none";
    probe.style.color = "var(" + name + ")";
    document.body.appendChild(probe);
    var computed = null;
    try {
      computed = window.getComputedStyle(probe).color;
    } catch (e) {
      computed = null;
    }
    document.body.removeChild(probe);
    return normaliseColor(computed);
  }

  function normaliseColor(computed) {
    if (!computed) return null;
    var value = String(computed).trim();
    if (value.indexOf("rgb") === 0 || value.charAt(0) === "#") return value;
    var srgb = /^color\(\s*srgb\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s*(?:\/\s*([0-9.]+)\s*)?\)$/.exec(value);
    if (!srgb) return null;
    var byte = function (x) {
      return Math.max(0, Math.min(255, Math.round(parseFloat(x) * 255)));
    };
    var rgb = byte(srgb[1]) + ", " + byte(srgb[2]) + ", " + byte(srgb[3]);
    var alpha = srgb[4] === undefined ? 1 : parseFloat(srgb[4]);
    return alpha >= 1 ? "rgb(" + rgb + ")" : "rgba(" + rgb + ", " + alpha + ")";
  }

  /** The theme map (D25): the token names and the `layout` paths they feed. */
  var THEME_TOKENS = {
    canvas: "--chart-canvas",
    plot: "--chart-plot",
    grid: "--chart-grid",
    ink: "--chart-ink",
    muted: "--chart-muted",
  };

  function themeLayout(presentation) {
    var resolved = {};
    for (var key in THEME_TOKENS) {
      if (Object.prototype.hasOwnProperty.call(THEME_TOKENS, key)) resolved[key] = resolveToken(THEME_TOKENS[key]);
    }
    var layout = {};
    if (resolved.canvas) layout.paper_bgcolor = resolved.canvas;
    if (resolved.plot) layout.plot_bgcolor = resolved.plot;
    if (resolved.ink) {
      layout.font = { color: resolved.ink };
      layout.xaxis = { gridcolor: resolved.grid || null, zerolinecolor: resolved.grid || null };
      layout.yaxis = { gridcolor: resolved.grid || null, zerolinecolor: resolved.grid || null };
    }
    if (resolved.muted) {
      layout.xaxis = layout.xaxis || {};
      layout.yaxis = layout.yaxis || {};
      layout.xaxis.zerolinecolor = resolved.muted;
      layout.yaxis.zerolinecolor = resolved.muted;
    }
    // `presentation.tokens` is an open map (documented): `series` is the one name this adapter knows.
    var tokens = presentation && isObject(presentation.tokens) ? presentation.tokens : {};
    if ((!tokens.series || tokens.series === "categorical") && resolved.canvas) {
      var colorway = [];
      for (var i = 1; i <= 6; i++) {
        var colour = resolveToken("--chart-series-" + i);
        if (colour) colorway.push(colour);
      }
      if (colorway.length) layout.colorway = colorway;
    }
    return layout;
  }

  /**
   * #386 — the SIZE defaults (dashboards.md §6.3). Plotly's own margins (about 100 px top, 80 px on
   * the other sides) consume a 2-row slot (176 px) whole; a figure starts from a compact frame and
   * each axis's `automargin` grows it by what its tick labels and axis title need, and no more. The
   * top margin opens for a title only. These are DEFAULTS under the author: a stored `layout.margin`
   * key and a stored `automargin` win over them key by key — the opposite precedence to the theme's
   * colours, which win over the author's ([mergeLayout]).
   */
  var COMPACT_MARGIN = { l: 8, r: 8, t: 8, b: 8, pad: 0 };

  /** The top margin of a titled figure: one line of Plotly's default title font (17 px) with its leading. */
  var COMPACT_TITLED_TOP = 36;

  /** `xaxis`, `yaxis` and the numbered axes an author declared (`xaxis2`, `yaxis3`, …). */
  var AXIS_KEY = /^[xy]axis\d*$/;

  function hasTitle(layout) {
    var title = layout.title;
    if (typeof title === "string") return title.length > 0;
    return isObject(title) && title.text !== undefined && title.text !== null && String(title.text).length > 0;
  }

  function withSizeDefaults(stored) {
    var out = clone(stored && isObject(stored) ? stored : {});
    var margin = Object.assign({}, COMPACT_MARGIN);
    if (hasTitle(out)) margin.t = COMPACT_TITLED_TOP;
    out.margin = Object.assign(margin, isObject(out.margin) ? out.margin : {});
    var axes = ["xaxis", "yaxis"];
    for (var key in out) {
      if (Object.prototype.hasOwnProperty.call(out, key) && AXIS_KEY.test(key) && axes.indexOf(key) === -1) axes.push(key);
    }
    for (var i = 0; i < axes.length; i++) {
      var axis = isObject(out[axes[i]]) ? out[axes[i]] : {};
      if (axis.automargin === undefined) axis.automargin = true;
      out[axes[i]] = axis;
    }
    return out;
  }

  function mergeLayout(stored, theme) {
    var out = clone(stored && isObject(stored) ? stored : {});
    for (var key in theme) {
      if (!Object.prototype.hasOwnProperty.call(theme, key)) continue;
      if (key === "xaxis" || key === "yaxis") {
        out[key] = Object.assign({}, out[key], theme[key]);
        for (var sub in theme[key]) {
          if (theme[key][sub] === null) delete out[key][sub];
        }
      } else if (key === "font") {
        out[key] = Object.assign({}, out[key], theme[key]);
      } else {
        out[key] = theme[key];
      }
    }
    return out;
  }

  function create(context) {
    var host = context.host;
    var occurrence = context.occurrence || {};
    var stored = occurrence.config || {};

    function plot() {
      return typeof window !== "undefined" && window.Plotly && typeof window.Plotly.react === "function";
    }

    return {
      /**
       * §10.3: substitute the bound arrays into the stored configuration, `Plotly.react`, and resolve
       * `'rendered'` on `plotly_afterplot`. `rows === 0` is the renderable-empty case the adapter owns:
       * `'no-data'` back to the runtime. The theme is re-resolved at EACH render — a theme swap shows
       * on the next frame without a reload (D25).
       */
      renderData: function (occurrenceRef, rows, bindings) {
        if (!rows || rows === 0) return Promise.resolve("no-data");
        if (!plot()) {
          throw new Error("Plotly is not loaded; the page must load one vendored bundle");
        }
        var config = substitute(stored, bindings || {}, escapeMarkup);
        config.layout = mergeLayout(withSizeDefaults(config.layout), themeLayout(occurrence.presentation));
        var plotConfig = Object.assign({ responsive: true, displaylogo: false }, config.config || {});
        return new Promise(function (resolve, reject) {
          var settled = false;
          function done(outcome) {
            if (settled) return;
            settled = true;
            resolve(outcome);
          }
          host.addEventListener(
            "plotly_afterplot",
            function () {
              done("rendered");
            },
            { once: true },
          );
          window.Plotly.react(host, config.data || [], config.layout || {}, plotConfig).then(
            function () {
              // `react` resolving IS a completed draw (the event may have raced ahead of the promise);
              // whichever arrives first settles.
              done("rendered");
            },
            function (error) {
              if (!settled) {
                settled = true;
                reject(error);
              }
            },
          );
        });
      },
      resize: function () {
        if (plot() && host) {
          try {
            window.Plotly.Plots.resize(host);
          } catch (e) {
            /* an unrendered host has nothing to resize */
          }
        }
      },
      dispose: function () {
        if (plot() && host) {
          try {
            window.Plotly.purge(host);
          } catch (e) {
            /* already gone */
          }
        }
      },
    };
  }

  var api = {
    register: function (dashboard) {
      if (REGISTERED || !dashboard) return;
      dashboard.registerRenderer({ kind: "plotly", version: "4", create: create });
      REGISTERED = true;
    },
    /** Exported for the node tests: the pure substitution and escaping, the theme and the size defaults. */
    _internal: {
      substitute: substitute,
      escapeMarkup: escapeMarkup,
      parsePath: parsePath,
      themeLayout: themeLayout,
      mergeLayout: mergeLayout,
      withSizeDefaults: withSizeDefaults,
      COMPACT_MARGIN: COMPACT_MARGIN,
      COMPACT_TITLED_TOP: COMPACT_TITLED_TOP,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DatapipelinesDashboardPlotly = api;
    api.register(window.DatapipelinesDashboard);
  }
})();
