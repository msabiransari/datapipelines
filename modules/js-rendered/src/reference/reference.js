/*
 * The look reference page (lane 991, design record 2026-11-14 §9 F7; look contract §10 LA9; brief 991 D1–D3, D2a, D2b).
 *
 * A static page assembled by the Gradle task `jsRenderedReference`: the engine's files (script tags generated from
 * LOAD-ORDER.txt), the interaction layer's one file, the product's stylesheets copied unedited, the tiles and the
 * study's chosen pictures. It draws with the engine directly — `JsRendered.render`, then `JsRendered.writeDom` — and
 * hands every draw to `DatapipelinesChartInteractions.attach(svg, draw, {toggle})`, as the product's chart adapter does.
 * The theme is the engine's `light` or `dark` defaults, following the page's ONE theme link (D1a); on the forest page
 * the seven theme-following tokens are read from the product's stylesheets through probe elements (D2a).
 *
 * The rendering rule: every text reaches the page through `textContent` (or the engine's writer); no markup string, no
 * `style` attribute, no inline script. The page's policy (its Content-Security-Policy meta) allows nothing from outside
 * its own directory.
 *
 * Read only, for the browser test: `window.DatapipelinesReference` (`ready`, `keys()`, `snapshot(key)`).
 */
(function () {
  "use strict";

  var NS = "http://www.w3.org/2000/svg";
  var WIRES = ["bar", "line", "area", "scatter", "pie", "heatmap", "box", "waterfall", "funnel", "gauge", "treemap"];
  var THEME_FILES = { light: "vendor/design-system/themes/light.css", dark: "vendor/design-system/themes/dark.css" };
  /** The product's sentence for the warning a frame below `minimal` carries (the chart adapter's WARNING_TEXT). */
  var TOO_SMALL = "the chart's frame is too small to draw it";
  /** The seven chart tokens that follow the theme and the design-system token each is a var() of (contract §5.1). */
  var FOLLOWING = [
    ["--chart-canvas", "--surface-default"],
    ["--chart-ink", "--text-primary"],
    ["--chart-ink-2", "--text-secondary"],
    ["--chart-muted", "--text-muted"],
    ["--chart-axis", "--border-subtle"],
    ["--chart-tip-surface", "--surface-raised"],
    ["--chart-tip-ink", "--text-primary"],
  ];

  var R = window.JsRendered;
  var L = window.DatapipelinesChartInteractions;
  var page = document.body.getAttribute("data-page");
  var mounts = {};
  var order = [];
  var themeName = "light";
  var forestTheme = null;

  // ------------------------------------------------------------------------------------------ helpers

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function fetchJson(path) {
    return fetch(path).then(function (r) {
      if (!r.ok) throw new Error(path + " answered " + r.status);
      return r.json();
    });
  }

  /** measure(text, size, weight) over one canvas context, cached per family (the product adapter's way, D1). */
  var measureCache = {};
  var measureCtx = null;
  function measureFor(family) {
    if (!measureCache[family]) measureCache[family] = new Map();
    var cache = measureCache[family];
    return function (text, size, weight) {
      var key = weight + "|" + size + "|" + text;
      var w = cache.get(key);
      if (w !== undefined) return w;
      if (measureCtx === null) measureCtx = document.createElement("canvas").getContext("2d");
      measureCtx.font = weight + " " + size + "px " + family;
      w = measureCtx.measureText(text).width;
      cache.set(key, w);
      return w;
    };
  }

  function themeInput() {
    return page === "forest" ? forestTheme : themeName;
  }

  function expectLine(e) {
    if (!e || !e.state) return "";
    if (e.state !== "rendered") return "expect: " + e.state + (e.code ? " (" + e.code + ")" : "");
    var parts = ["rendered", e.level, "legend " + e.legend];
    if (e.struck && e.struck.length) parts.push("struck " + e.struck.join(", "));
    parts.push(e.dots + (e.dots === 1 ? " end dot" : " end dots"));
    parts.push("labels " + (e.labels === undefined ? "not asserted" : e.labels));
    return "expect: " + parts.join(" · ");
  }

  // ------------------------------------------------------------------------------------------ a mounted tile

  /** The product's chart card (dashboards.css; lane 987's padding rule selects `> .dp-dashboard-viz > svg.dp-chart`). */
  function card(title) {
    var article = el("article", "dp-dashboard-card");
    var head = el("div", "dp-dashboard-card-head");
    head.appendChild(el("h3", "dp-dashboard-card-title", title));
    head.appendChild(el("p", "dp-dashboard-card-description", ""));
    var viz = el("div", "dp-dashboard-viz");
    var svg = document.createElementNS(NS, "svg");
    svg.setAttribute("class", "dp-chart");
    viz.appendChild(svg);
    var foot = el("div", "dp-dashboard-card-foot");
    var message = el("div", "dp-dashboard-card-message", "");
    foot.appendChild(message);
    article.appendChild(head);
    article.appendChild(viz);
    article.appendChild(foot);
    return { article: article, viz: viz, svg: svg, message: message };
  }

  function mount(key, wire, tile, host, opts) {
    var options = opts || {};
    var fluid = tile.frame.fluid === true;
    var figure = el("figure", "ref-tile " + (fluid ? "ref-fluid" : "ref-fixed"));
    figure.id = "tile-" + key.replace(/[^a-z0-9]+/gi, "-");
    var caption = el("figcaption", "ref-caption");
    caption.appendChild(el("span", "ref-tile-title", tile.title));
    caption.appendChild(el("span", "ref-tile-frame", fluid ? "fluid: the card's width × " + tile.frame.h : tile.frame.w + " × " + tile.frame.h));
    if (!tile.study && options.studyMark !== false) caption.appendChild(el("span", "ref-mark", "not in the study"));
    caption.appendChild(el("span", "ref-expect", fluid ? "expect: by the level of the width drawn (record §4.6)" : expectLine(tile.expect)));
    if (tile.note) caption.appendChild(el("span", "ref-note", tile.note));
    figure.appendChild(caption);
    var c = card(tile.document.display_name || tile.title);
    var m = { key: key, wire: wire, tile: tile, fluid: fluid, frame: fluid ? null : { w: tile.frame.w, h: tile.frame.h }, hidden: tile.hidden.slice(), card: c, out: null, layer: null, draws: 0, theme: null };
    if (!fluid) {
      var controls = el("label", "ref-drag");
      controls.appendChild(el("span", "ref-drag-label", "Frame width"));
      var range = document.createElement("input");
      range.type = "range";
      range.min = "40";
      range.max = String(Math.max(tile.frame.w * 2, 720));
      range.step = "1";
      range.value = String(tile.frame.w);
      range.setAttribute("data-tile", key);
      var readout = el("output", "ref-drag-value", String(tile.frame.w));
      range.addEventListener("input", function () {
        m.frame = { w: Number(range.value), h: tile.frame.h };
        readout.textContent = range.value;
        draw(m);
      });
      controls.appendChild(range);
      controls.appendChild(readout);
      figure.appendChild(controls);
      var scroll = el("div", "ref-scroll");
      scroll.appendChild(c.article);
      figure.appendChild(scroll);
    } else {
      figure.appendChild(c.article);
    }
    host.appendChild(figure);
    mounts[key] = m;
    order.push(key);
    if (fluid && typeof ResizeObserver === "function") {
      var pending = false;
      new ResizeObserver(function () {
        if (pending) return;
        pending = true;
        requestAnimationFrame(function () {
          pending = false;
          if (m.out !== null && m.frame !== null && Math.floor(c.viz.clientWidth) === m.frame.w) return;
          draw(m);
        });
      }).observe(c.viz);
    }
    return m;
  }

  function frameOf(m) {
    if (!m.fluid) return m.frame;
    return { w: Math.max(0, Math.floor(m.card.viz.clientWidth)), h: m.tile.frame.h };
  }

  function draw(m) {
    var theme = themeInput();
    var frame = frameOf(m);
    m.frame = frame;
    var measure = measureFor(R.theme.resolve(theme).font);
    var t = m.tile;
    var out = R.render({ type: m.wire, document: t.document, columns: t.columns, types: t.types, frame: frame, theme: theme, measure: measure, hidden: m.hidden.slice() });
    var drawn = out.state === "rendered" || out.state === "no-data";
    R.writeDom(drawn ? out.primitives : [], m.card.svg, theme, drawn ? R.write.describe(out, t.document) : undefined);
    var handed = { output: drawn ? out : { type: out.type, state: out.state, primitives: [], index: null, summary: out.summary }, theme: theme, measure: measure, frame: frame };
    if (m.layer === null) {
      m.layer = L.attach(m.card.svg, handed, {
        toggle: function (id) {
          var at = m.hidden.indexOf(id);
          if (at >= 0) m.hidden.splice(at, 1);
          else m.hidden.push(id);
          draw(m);
        },
      });
    } else m.layer.update(handed);
    var message = "";
    if (out.state === "refused") message = R.sentence(out.summary.refusal);
    else if (out.state === "too-small") message = TOO_SMALL + " (chart.frame-too-small)";
    m.card.message.textContent = message;
    m.out = out;
    m.theme = theme;
    m.draws++;
  }

  function drawAll() {
    order.forEach(function (k) {
      draw(mounts[k]);
    });
  }

  // ------------------------------------------------------------------------------------------ the theme switch (D1a)

  function setTheme(name) {
    var link = document.getElementById("theme-link");
    return new Promise(function (resolve, reject) {
      if (link.getAttribute("href") === THEME_FILES[name]) return resolve();
      link.addEventListener("load", function onload() {
        link.removeEventListener("load", onload);
        resolve();
      });
      link.addEventListener("error", function onerror() {
        link.removeEventListener("error", onerror);
        reject(new Error("the theme file " + THEME_FILES[name] + " did not load"));
      });
      link.setAttribute("href", THEME_FILES[name]);
    }).then(function () {
      themeName = name;
      document.documentElement.setAttribute("data-theme", name);
      drawAll();
      document.querySelectorAll("img[data-study]").forEach(function (img) {
        img.src = "study/" + img.getAttribute("data-study") + "-" + name + ".png";
      });
      var button = document.getElementById("theme-switch");
      if (button) {
        button.setAttribute("aria-pressed", name === "dark" ? "true" : "false");
        button.textContent = name === "dark" ? "Switch to light" : "Switch to dark";
      }
    });
  }

  // ------------------------------------------------------------------------------------------ the forest theme (D2a)

  /** "rgb(r, g, b)" → [r, g, b]; the probe's computed colour. */
  function channels(css) {
    var m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(css);
    if (!m) throw new Error("a probe's colour is not rgb(): " + css);
    return [Number(m[1]), Number(m[2]), Number(m[3])];
  }

  function hex(c) {
    return "#" + c.map(function (v) { return (v < 16 ? "0" : "") + v.toString(16); }).join("");
  }

  function readForest() {
    var tokens = {};
    var table = document.getElementById("forest-tokens");
    FOLLOWING.forEach(function (pair) {
      var probe = document.querySelector('[data-probe="' + pair[1] + '"]');
      if (!probe) throw new Error("no probe for " + pair[1]);
      var c = channels(getComputedStyle(probe).color);
      tokens[pair[0]] = c;
      var row = el("tr");
      row.appendChild(el("td", null, pair[0]));
      row.appendChild(el("td", null, pair[1]));
      var value = el("td", null, hex(c));
      value.setAttribute("data-token", pair[0]);
      row.appendChild(value);
      table.appendChild(row);
    });
    forestTheme = { base: "light", tokens: tokens };
  }

  // ------------------------------------------------------------------------------------------ the page

  function studyPair(host, wire, tile, provenance) {
    var pair = el("div", "ref-pair");
    var pic = provenance.pictures.filter(function (p) { return p.chart === tile.study.chart && p.theme === "light"; })[0];
    var figure = el("figure", "ref-study");
    var img = document.createElement("img");
    img.className = "ref-study-img";
    img.setAttribute("data-study", tile.study.chart);
    img.src = "study/" + tile.study.chart + "-" + themeName + ".png";
    img.width = Math.round(pic.box.w);
    img.height = Math.round(pic.box.h);
    img.alt = "The look study's " + tile.study.chart + " card at look v1 (" + pic.title + "), as the owner chose it";
    figure.appendChild(img);
    figure.appendChild(el("figcaption", "ref-caption", "The study: " + pic.title + " — card " + Math.round(pic.box.w) + " × " + Math.round(pic.box.h) + " CSS px"));
    pair.appendChild(figure);
    host.appendChild(pair);
    mount(wire + ":" + tile.id, wire, tile, pair, { studyMark: false });
  }

  function buildReference(files, provenance) {
    var studyHost = document.getElementById("study-pairs");
    var studyOrder = ["grouped", "line", "stacked", "area", "ranked"];
    studyOrder.forEach(function (chart) {
      WIRES.forEach(function (wire, i) {
        files[i].forEach(function (tile) {
          if (tile.study && tile.study.chart === chart) studyPair(studyHost, wire, tile, provenance);
        });
      });
    });
    WIRES.forEach(function (wire, i) {
      var host = document.getElementById("tiles-" + wire);
      files[i].forEach(function (tile) {
        if (!tile.study) mount(wire + ":" + tile.id, wire, tile, host);
      });
    });
    // The dashboard row's chart card (D2b): the bar's fluid tile, mounted again inside the row.
    var row = document.getElementById("row-chart");
    var fluidBar = files[0].filter(function (t) { return t.id === "fluid"; })[0];
    var rowCard = mount("row:bar", "bar", fluidBar, row, { studyMark: false });
    // The row holds the card itself, as a dashboard slot does: the figure's caption stays, the card is the slot's child.
    rowCard.card.article.setAttribute("data-row-card", "chart");
  }

  function buildForest(files) {
    readForest();
    var host = document.getElementById("forest-tiles");
    (document.body.getAttribute("data-forest-tiles") || "").split(" ").forEach(function (ref) {
      var parts = ref.split(":");
      var wire = parts[0];
      var tile = files[WIRES.indexOf(wire)].filter(function (t) { return t.id === parts[1]; })[0];
      if (!tile) throw new Error("the forest page names a tile that is not there: " + ref);
      mount("forest:" + ref, wire, tile, host, { studyMark: false });
    });
  }

  /**
   * The engine's font in use, not merely loaded. The product declares its faces `font-display: optional`: on a cold
   * visit, and on every visit under the browser test (request interception turns the HTTP cache off), the file
   * arrives after the short block period and the browser keeps the fallback for the whole document, although
   * `document.fonts` reports the face loaded. Measured: the fallback lays the text out about 16 % narrower, so the
   * page would be measured and judged in another font than the product's. The page therefore declares the product's
   * own faces of the engine's family once more, read from the copied stylesheets' rules (same files, weights, styles
   * and ranges), with `display: block`, and draws after they loaded. That is loading only; no rule of the look is
   * restated. Then it checks the font is in use and marks the body `data-font` (`real`, or `fallback` with a sentence
   * in words), which the browser test reads.
   */
  var FONT_PROBE = "Enterprise accounts 0123456789";

  function productFaces(family) {
    var faces = [];
    Array.prototype.forEach.call(document.styleSheets, function (sheet) {
      Array.prototype.forEach.call(sheet.cssRules, function (rule) {
        if (rule.type !== CSSRule.FONT_FACE_RULE) return;
        var st = rule.style;
        if (st.getPropertyValue("font-family").replace(/["']/g, "").trim() !== family) return;
        var src = st.getPropertyValue("src").replace(/url\((["']?)([^"')]+)\1\)/g, function (all, quote, address) {
          return 'url("' + new URL(address, sheet.href).href + '")';
        });
        var face = new FontFace(family, src, {
          style: st.getPropertyValue("font-style") || "normal",
          weight: st.getPropertyValue("font-weight") || "normal",
          unicodeRange: st.getPropertyValue("unicode-range") || "U+0-10FFFF",
          display: "block",
        });
        document.fonts.add(face);
        faces.push(face.load());
      });
    });
    if (faces.length === 0) throw new Error("the product's stylesheets declare no face of " + family);
    return Promise.all(faces);
  }

  function fontInUse(family) {
    var ctx = document.createElement("canvas").getContext("2d");
    ctx.font = '12px "' + family + '", serif';
    var withFont = ctx.measureText(FONT_PROBE).width;
    ctx.font = "12px serif";
    return withFont !== ctx.measureText(FONT_PROBE).width;
  }

  function fontsReady() {
    var family = R.theme.resolve("light").font.replace(/["']/g, "").trim();
    return productFaces(family)
      .then(function () {
        return document.fonts.ready;
      })
      .then(function () {
        if (fontInUse(family)) {
          document.body.setAttribute("data-font", "real");
          return;
        }
        document.body.setAttribute("data-font", "fallback");
        var note = el("p", "ref-line ref-font-note", "The product's font (" + family + ") is not in use: the charts are drawn in the browser's fallback font.");
        document.body.insertBefore(note, document.body.firstChild);
      });
  }

  var ready = Promise.all([fontsReady(), Promise.all(WIRES.map(function (w) { return fetchJson("tiles/" + w + ".json"); })), page === "reference" ? fetchJson("study/provenance.json") : null])
    .then(function (got) {
      var files = got[1];
      if (page === "forest") buildForest(files);
      else buildReference(files, got[2]);
      drawAll();
      var button = document.getElementById("theme-switch");
      if (button) {
        button.addEventListener("click", function () {
          setTheme(themeName === "dark" ? "light" : "dark");
        });
      }
      document.body.setAttribute("data-ready", "true");
      return order.length;
    });

  /** Read-only, for the browser test: the mounted tiles and each one's last draw (copies, never the live objects). */
  window.DatapipelinesReference = {
    ready: ready,
    keys: function () {
      return order.slice();
    },
    theme: function () {
      return JSON.parse(JSON.stringify(themeInput()));
    },
    setTheme: setTheme,
    snapshot: function (key) {
      var m = mounts[key];
      if (!m) return null;
      return JSON.parse(
        JSON.stringify({
          key: key,
          wire: m.wire,
          id: m.tile.id,
          fluid: m.fluid,
          frame: m.frame,
          hidden: m.hidden,
          theme: m.theme,
          draws: m.draws,
          state: m.out.state,
          level: m.out.level,
          primitives: m.out.primitives,
          index: m.out.index,
          summary: m.out.summary,
          sentence: m.out.state === "refused" ? R.sentence(m.out.summary.refusal) : null,
          message: m.card.message.textContent,
          expect: m.tile.expect,
          columns: m.tile.columns,
          document: m.tile.document,
          figure: "tile-" + key.replace(/[^a-z0-9]+/gi, "-"),
        }),
      );
    },
  };
})();
