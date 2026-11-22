/*
 * The `chart` renderer for the Datapipelines dashboard runtime (design record 2026-11-14 §5.2): ONE adapter over the
 * js-rendered engine, registered on load as kind `chart`, major version `1`. The engine's files (`JsRendered`, served
 * from the engine module's resources in their load order) are loaded before this file; the server's judge runs the
 * same files (record A6, I6).
 *
 * ## Per draw (record §5.2)
 * 1. The theme: every token the engine holds a default of is read off the chart's own cascade and judged by the look
 *    contract's §3.7 (`resolveTheme`): an empty computed value is "not declared" (the default, nothing reported); a
 *    value valid for its kind and inside its range is taken; any other value is refused (its default is drawn and one
 *    `chart.token-refused` warning names it). `--chart-pad` and `--chart-tip-shadow` are the stylesheet's alone and
 *    never read here.
 * 2. `measure`: one canvas context's `measureText` over the resolved font list, cached per (text, size, weight).
 * 3. `JsRendered.render` over the kept columns at the host's frame; by `state` (record §4.3): `rendered` and `no-data`
 *    are drawn with `JsRendered.writeDom`, `refused` rejects with the code's fixed sentence (`error.refusal`), and
 *    `too-small` draws nothing.
 * Redraws are local, from the kept columns: a ResizeObserver on the host and the runtime's `resize` (one redraw in the
 * next animation frame), a theme change (an attribute of the document element, or the colour-scheme media query), and
 * once when the page's fonts are ready.
 *
 * ## The interaction layer (brief 990 D1, D4)
 * `datapipelines-chart-interactions.js` (`DatapipelinesChartInteractions`) is loaded directly before this file and is
 * required: a draw without it is refused with a sentence naming the file, the path a missing engine takes. Each draw
 * hands the layer the draw whole (`{output, theme, measure, frame}`: the output and the very inputs of that `render`
 * call). The legend switch's LIST of switched-off series ids lives here, with the mounted chart: the layer asks for a
 * series to be switched, this file changes the list and draws again at once from the kept columns. A data refresh
 * never changes the list; `dispose` drops it and detaches the layer. The picture carries its own description
 * (`role="img"`, `<title>`, `<desc>`, record §4.8), so the host element carries no role of its own.
 *
 * ## The rendering rule
 * Data reaches the page only through `JsRendered.writeDom` (createElementNS, setAttribute, textContent). This file
 * writes no markup string, no `style` attribute, no `href` and no event attribute; its one probe is a childless SVG
 * group whose presentation attributes (`color`, `stroke-width`, `font-family`) are set only to values the browser has
 * already accepted for that property (CSS.supports), so a refused value — a `url()` among them — is never applied.
 */
(function () {
  "use strict";

  var NS = "http://www.w3.org/2000/svg";
  var REFUSED = "chart.token-refused";
  /** The record's §7 bound on a frame's side; below the engine's `minimal` level the result is `too-small`. */
  var MAX_SIDE = 4096;
  var NUMBER = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
  var TIME = /^([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)(ms|s)$/i;
  var HEX = /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i;
  var KEYWORD = /^-?[a-z][a-z-]*$/i;

  /** The runtime's warnings: a code and a fixed sentence per code, never document text (record §4.3). */
  var WARNING_TEXT = {
    "chart.rule.null-category": "rows with a null category or series were not drawn",
    "chart.rule.null-position": "rows with a null or unreadable position were not drawn",
    "chart.rule.log-nonpositive": "a value at or below zero was not drawn on a log axis",
    "chart.rule.non-finite": "a value is too large to draw and was not drawn",
    "chart.frame-too-small": "the chart's frame is too small to draw it",
    "chart.text.width-missing": "a text was measured with an average width",
  };

  /** The interaction layer's file: named by the refusal when its global is missing (D1). */
  var LAYER_FILE = "datapipelines-chart-interactions.js";

  /** The interaction layer the page loaded (`DatapipelinesChartInteractions`), read at call time (brief 990 D1). */
  function interactions() {
    var root = typeof globalThis !== "undefined" ? globalThis : typeof window !== "undefined" ? window : {};
    var L = root.DatapipelinesChartInteractions;
    if (!L || typeof L.attach !== "function") {
      throw new Error("the chart interaction layer is not loaded: the page loads " + LAYER_FILE + " before this renderer");
    }
    return L;
  }

  /** The engine namespace the page loaded (a classic script's `var JsRendered`), read at call time. */
  function engine() {
    var root = typeof globalThis !== "undefined" ? globalThis : typeof window !== "undefined" ? window : {};
    var R = root.JsRendered;
    if (!R || typeof R.render !== "function" || typeof R.writeDom !== "function") {
      throw new Error("the chart engine (JsRendered) is not loaded: the page loads its files before this renderer");
    }
    return R;
  }

  // ------------------------------------------------------------------------- the resolver (contract §3.7)

  /** Half up, then cut to [0, 255] (contract §3.7: "rounded to the nearest whole number, a half up"). */
  function channel(x) {
    var n = Math.floor(x + 0.5);
    return n < 0 ? 0 : n > 255 ? 255 : n;
  }

  function unitAlpha(a) {
    return a < 0 ? 0 : a > 1 ? 1 : a;
  }

  /** A hex literal's channels, or null. */
  function hexColour(text) {
    var m = HEX.exec(text);
    if (!m) return null;
    var h = m[1];
    if (h.length <= 4) h = h.replace(/./g, "$&$&");
    var out = [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
    if (h.length === 8) out.push(parseInt(h.slice(6, 8), 16) / 255);
    return out;
  }

  /**
   * A computed colour as the engine takes it: [r, g, b] or [r, g, b, a] (a < 1), whole channels; null for a form this
   * function does not read (a colour space the browser keeps unconverted, which the caller converts first).
   */
  function parseColour(computed) {
    var text = String(computed).trim();
    var hex = hexColour(text);
    var parts, rgb, alpha;
    if (hex) {
      rgb = hex.slice(0, 3);
      alpha = hex.length === 4 ? hex[3] : 1;
    } else {
      var legacy = /^rgba?\(([^)]*)\)$/i.exec(text);
      var srgb = /^color\(\s*srgb\s+([^)]*)\)$/i.exec(text);
      if (!legacy && !srgb) return null;
      parts = (legacy ? legacy[1] : srgb[1]).split(/[\s,/]+/).filter(function (p) {
        return p !== "";
      });
      if (parts.length !== 3 && parts.length !== 4) return null;
      var nums = parts.map(function (p, i) {
        var pct = /%$/.test(p);
        var v = parseFloat(p);
        if (!isFinite(v)) return NaN;
        if (i === 3) return pct ? v / 100 : v;
        if (pct) return (v / 100) * 255;
        return srgb ? v * 255 : v;
      });
      for (var i = 0; i < nums.length; i++) if (nums[i] !== nums[i]) return null;
      rgb = [nums[0], nums[1], nums[2]];
      alpha = nums.length === 4 ? nums[3] : 1;
    }
    var out = [channel(rgb[0]), channel(rgb[1]), channel(rgb[2])];
    alpha = unitAlpha(alpha);
    if (alpha !== 1) out.push(alpha);
    return out;
  }

  function computedStyle(el) {
    var win = el.ownerDocument && el.ownerDocument.defaultView;
    return win && typeof win.getComputedStyle === "function" ? win.getComputedStyle(el) : null;
  }

  function supports(property, value) {
    var root = typeof globalThis !== "undefined" ? globalThis : window;
    return !!(root.CSS && typeof root.CSS.supports === "function" && root.CSS.supports(property, value));
  }

  /** A presentation attribute's computed value on the probe: set, read, removed. */
  function probed(probe, attribute, cssName, value) {
    probe.setAttribute(attribute, value);
    try {
      var style = computedStyle(probe);
      return style ? String(style[cssName] || "") : "";
    } finally {
      probe.removeAttribute(attribute);
    }
  }

  /** One raw value of a kind, judged: the engine's value, or undefined when refused. */
  function judgeValue(kind, raw, spec, probe) {
    var n;
    if (kind === "colour" || kind === "opaque") {
      if (!supports("color", raw)) return undefined;
      var c = hexColour(raw) ? parseColour(raw) : parseColour(probed(probe, "color", "color", raw));
      if (c === null) {
        // A colour space the browser keeps as written (oklch, lab, display-p3): converted by the browser into sRGB.
        var mixed = "color-mix(in srgb, " + raw + ", " + raw + ")";
        c = supports("color", mixed) ? parseColour(probed(probe, "color", "color", mixed)) : null;
      }
      if (c === null) return undefined;
      if (kind === "opaque" && c.length === 4) return undefined;
      return c;
    }
    if (kind === "font") {
      if (!supports("font-family", raw)) return undefined;
      var family = probed(probe, "font-family", "fontFamily", raw).trim();
      return family === "" ? undefined : family;
    }
    if (kind === "length") {
      if (NUMBER.test(raw)) n = Number(raw);
      else if (raw.indexOf("%") !== -1 || KEYWORD.test(raw) || !supports("width", raw)) return undefined;
      else n = parseFloat(probed(probe, "stroke-width", "strokeWidth", raw));
    } else if (kind === "duration") {
      var t = TIME.exec(raw);
      if (NUMBER.test(raw)) n = Number(raw);
      else if (t) n = Number(t[1]) * (t[2].toLowerCase() === "s" ? 1000 : 1);
      else return undefined;
    } else {
      // share, weight: a bare number only.
      if (!NUMBER.test(raw)) return undefined;
      n = Number(raw);
    }
    if (!isFinite(n) || n < spec[3] || n > spec[4]) return undefined;
    return n;
  }

  /**
   * The theme at [svg] (contract §3.7): `{theme: {base: "light", tokens}, refused: [name]}`. Only declared, valid
   * tokens are in `tokens`; the engine supplies every default.
   */
  function resolveTheme(svg) {
    var R = engine();
    var TOKENS = R.theme.TOKENS;
    var tokens = {};
    var refused = [];
    var style = computedStyle(svg);
    if (!style) return { theme: { base: "light", tokens: tokens }, refused: refused };
    var probe = svg.ownerDocument.createElementNS(NS, "g");
    svg.appendChild(probe);
    try {
      for (var name in TOKENS) {
        if (!Object.prototype.hasOwnProperty.call(TOKENS, name)) continue;
        var raw = String(style.getPropertyValue(name) || "").trim();
        if (raw === "") continue;
        var value = judgeValue(TOKENS[name][0], raw, TOKENS[name], probe);
        if (value === undefined) refused.push(name);
        else tokens[name] = value;
      }
    } finally {
      svg.removeChild(probe);
    }
    return { theme: { base: "light", tokens: tokens }, refused: refused };
  }

  // ---------------------------------------------------------------------------------------- measure

  /** measure(text, size, weight) over one canvas context, cached; a new family starts a new cache. */
  function measurer(doc) {
    var ctx = null;
    var family = null;
    var cache = new Map();
    function get(fam) {
      if (fam !== family) {
        family = fam;
        cache = new Map();
      }
      return function (text, size, weight) {
        var key = weight + "|" + size + "|" + text;
        var w = cache.get(key);
        if (w !== undefined) return w;
        if (ctx === null) ctx = doc.createElement("canvas").getContext("2d");
        ctx.font = weight + " " + size + "px " + family;
        w = ctx.measureText(text).width;
        cache.set(key, w);
        return w;
      };
    }
    return {
      get: get,
      /** The page's fonts changed (document.fonts.ready): every width is measured again. */
      reset: function () {
        cache = new Map();
      },
    };
  }

  // ------------------------------------------------------------------------------------- the adapter

  function warningsOf(summary, refused) {
    var out = [];
    var seen = {};
    (summary.warnings || []).forEach(function (w) {
      if (!w || typeof w.code !== "string" || seen[w.code]) return;
      seen[w.code] = true;
      out.push({ code: w.code, message: WARNING_TEXT[w.code] || "the chart was drawn with a warning" });
    });
    refused.forEach(function (name) {
      out.push({ code: REFUSED, token: name, message: "the page's value of " + name + " is not one this token may hold; its default was drawn" });
    });
    return out;
  }

  function frameOf(host) {
    var w = Math.min(MAX_SIDE, Math.max(0, host.clientWidth || 0));
    var h = Math.min(MAX_SIDE, Math.max(0, host.clientHeight || 0));
    return { w: w, h: h };
  }

  function create(context) {
    var host = context.host;
    var occurrence = context.occurrence || {};
    var config = occurrence.config || {};
    var type = config.mark && typeof config.mark.type === "string" ? config.mark.type : null;
    var document_ = { display_name: occurrence.display_name, description: occurrence.description, config: config };
    var types = occurrence.columns && typeof occurrence.columns === "object" ? occurrence.columns : {};
    var doc = host.ownerDocument;
    var win = doc.defaultView;
    var svg = doc.createElementNS(NS, "svg");
    svg.setAttribute("class", "dp-chart");
    host.appendChild(svg);
    var measure = measurer(doc);
    var kept = null; // the columns while mounted (record §5.2)
    var hidden = []; // the legend switch's list (record §4.3, §4.8; brief 990 D4): only a click, Enter or Space changes it
    var index = null;
    var attached = null; // the interaction layer's attachment, made by the first draw
    var pending = false;
    var disposed = false;
    var observers = [];
    // #1065 D6 — the theme a host sets with `data-theme` on the element handed to the runtime (its scope root, class
    // `dp-scope`): the same observer watches that attribute there, beside the document element. The root is looked
    // for at creation and, when the host is not in a scope yet, once more at the first draw.
    var themeObserver = null;
    var scopeLookups = 0;

    function watchScope() {
      if (themeObserver === null || scopeLookups >= 2 || typeof host.closest !== "function") return;
      scopeLookups += 1;
      var scopeRoot = host.closest(".dp-scope");
      if (!scopeRoot) return;
      scopeLookups = 2;
      // The document element is observed for every attribute already; observing it again would narrow that.
      if (scopeRoot !== doc.documentElement) themeObserver.observe(scopeRoot, { attributes: true, attributeFilter: ["data-theme"] });
    }

    /** One draw from [columns]; returns the runtime's acknowledgement, or throws the refusal / the engine's error. */
    function draw(columns) {
      watchScope();
      var R = engine();
      var L = interactions();
      var resolved = resolveTheme(svg);
      var frame = frameOf(host);
      var width = measure.get(R.theme.resolve(resolved.theme).font);
      var out = R.render({
        type: type,
        document: document_,
        columns: columns,
        types: types,
        frame: frame,
        theme: resolved.theme,
        measure: width,
        hidden: hidden.slice(),
      });
      var drawn = out.state === "rendered" || out.state === "no-data";
      R.writeDom(drawn ? out.primitives : [], svg, resolved.theme, drawn ? R.write.describe(out, document_) : undefined);
      index = drawn ? out.index : null;
      decorate(L, { output: drawn ? out : { type: out.type, state: out.state, primitives: [], index: null, summary: out.summary }, theme: resolved.theme, measure: width, frame: frame });
      if (out.state === "refused") {
        var sentence = R.sentence(out.summary.refusal);
        var error = new Error(sentence);
        error.refusal = { code: out.summary.refusal.code, sentence: sentence };
        throw error;
      }
      return { outcome: out.state === "no-data" ? "no-data" : "rendered", warnings: warningsOf(out.summary, resolved.refused) };
    }

    /** The layer over the new picture: attached by the first draw, updated by every later one (D1). */
    function decorate(L, drawn) {
      if (attached === null) attached = L.attach(svg, drawn, { toggle: toggle });
      else attached.update(drawn);
    }

    /** The legend switch (D4): the series id in or out of the list, and the same picture drawn again at once. */
    function toggle(id) {
      if (disposed || typeof id !== "string") return;
      var at = hidden.indexOf(id);
      if (at >= 0) hidden.splice(at, 1);
      else hidden.push(id);
      if (kept === null) return;
      try {
        draw(kept);
      } catch (e) {
        /* the refresh's acknowledgement already reported this chart; a redraw changes nothing it published */
      }
    }

    function render(columns) {
      kept = columns;
      try {
        return Promise.resolve(draw(columns));
      } catch (error) {
        return Promise.reject(error);
      }
    }

    /** A local redraw from the kept columns in the next animation frame; its outcome was published by the first draw. */
    function schedule() {
      if (pending || disposed) return;
      pending = true;
      var run = function () {
        pending = false;
        if (disposed || kept === null) return;
        try {
          draw(kept);
        } catch (e) {
          /* the refresh's acknowledgement already reported this chart; a redraw changes nothing it published */
        }
      };
      if (win && typeof win.requestAnimationFrame === "function") win.requestAnimationFrame(run);
      else setTimeout(run, 0);
    }

    if (win && typeof win.ResizeObserver === "function") {
      var resizeObserver = new win.ResizeObserver(schedule);
      resizeObserver.observe(host);
      observers.push(function () {
        resizeObserver.disconnect();
      });
    }
    if (win && typeof win.MutationObserver === "function" && doc.documentElement) {
      themeObserver = new win.MutationObserver(schedule);
      themeObserver.observe(doc.documentElement, { attributes: true });
      observers.push(function () {
        themeObserver.disconnect();
      });
      watchScope();
    }
    if (win && typeof win.matchMedia === "function") {
      var scheme = win.matchMedia("(prefers-color-scheme: dark)");
      if (scheme && typeof scheme.addEventListener === "function") {
        scheme.addEventListener("change", schedule);
        observers.push(function () {
          scheme.removeEventListener("change", schedule);
        });
      }
    }
    if (doc.fonts && doc.fonts.ready && typeof doc.fonts.ready.then === "function") {
      doc.fonts.ready.then(function () {
        measure.reset();
        schedule();
      });
    }

    return {
      /** The delivered columns through the engine; resolves `{outcome: "rendered" | "no-data", warnings}`. */
      renderData: function (occurrenceRef, rows, bindings) {
        return render(bindings && typeof bindings === "object" ? bindings : {});
      },
      /** The server's `no-data` (no frame, no columns): the engine's empty state; the previous marks are gone. */
      renderEmpty: function () {
        return render({});
      },
      resize: schedule,
      dispose: function () {
        disposed = true;
        kept = null;
        index = null;
        hidden = [];
        if (attached !== null) attached.detach();
        attached = null;
        observers.splice(0).forEach(function (stop) {
          stop();
        });
        if (svg.parentNode) svg.parentNode.removeChild(svg);
      },
      /** The data index of the last draw (record §4.3). */
      index: function () {
        return index;
      },
      /** The legend switch's list as it stands (a copy), for the tests (D4). */
      hidden: function () {
        return hidden.slice();
      },
    };
  }

  var api = {
    /** Idempotent: a second call registers the same kind again (the runtime keys renderers by kind). */
    register: function (dashboard) {
      if (!dashboard) return;
      dashboard.registerRenderer({ kind: "chart", version: "1", create: create });
    },
    _internal: {
      resolveTheme: resolveTheme,
      parseColour: parseColour,
      judgeValue: judgeValue,
      measurer: measurer,
      warningsOf: warningsOf,
      WARNING_TEXT: WARNING_TEXT,
      MAX_SIDE: MAX_SIDE,
      LAYER_FILE: LAYER_FILE,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DatapipelinesDashboardChart = api;
    api.register(window.DatapipelinesDashboard);
  }
})();
