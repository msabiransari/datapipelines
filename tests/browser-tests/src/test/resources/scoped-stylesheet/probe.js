// #1065 A.6 — the test's own probe, evaluated into the page by the test (never served to it): it adds the stylesheets
// and the library's script, boots the dashboard, and reads computed styles. Every read is of the live document.
window.__dp1065 = (function () {
  "use strict";
  var CONTAINERS = ["dashboard", "parameters"];
  var INSIDE = "#parameters, #parameters *, #dashboard, #dashboard *";

  function containers() {
    return CONTAINERS.map(function (id) {
      return document.getElementById(id);
    });
  }

  function addSheet(href) {
    return new Promise(function (resolve) {
      var link = document.createElement("link");
      link.rel = "stylesheet";
      link.href = href;
      link.onload = function () {
        resolve("ok " + href);
      };
      link.onerror = function () {
        resolve("error " + href);
      };
      document.head.appendChild(link);
    });
  }

  function addScript(src) {
    return new Promise(function (resolve) {
      var script = document.createElement("script");
      script.src = src;
      script.onload = function () {
        resolve("ok " + src);
      };
      script.onerror = function () {
        resolve("error " + src);
      };
      document.head.appendChild(script);
    });
  }

  /** Every computed property that is not a custom property, by name. */
  function styles(element) {
    var computed = getComputedStyle(element);
    var out = {};
    for (var i = 0; i < computed.length; i++) {
      var name = computed[i];
      if (name.indexOf("--") !== 0) out[name] = computed.getPropertyValue(name);
    }
    return out;
  }

  function customCount(element) {
    var computed = getComputedStyle(element);
    var count = 0;
    for (var i = 0; i < computed.length; i++) if (computed[i].indexOf("--") === 0) count += 1;
    return count;
  }

  function label(element) {
    if (element.id === "dashboard" || element.id === "parameters") return "container#" + element.id;
    return element.tagName.toLowerCase();
  }

  /** The elements of [selector] in document order, each with its label and its computed properties. */
  function snapshot(selector) {
    return Array.prototype.map.call(document.querySelectorAll(selector), function (element) {
      return { tag: label(element), styles: styles(element) };
    });
  }

  /** A colour as the browser computes it, read through a probe inside [where] (kept attached until it is read). */
  function colour(where, value) {
    var probe = document.createElement("span");
    where.appendChild(probe);
    probe.style.color = value;
    var computed = getComputedStyle(probe).color;
    probe.remove();
    return computed;
  }

  function card(viz) {
    return document.querySelector('#dashboard article.dp-dashboard-card[data-dp-viz="' + viz + '"]');
  }

  return {
    hostSnapshot: function () {
      return snapshot("html, body, #host, #host *");
    },
    insideSnapshot: function () {
      return snapshot(INSIDE);
    },
    customCounts: function () {
      return {
        html: customCount(document.documentElement),
        body: customCount(document.body),
        dashboard: customCount(document.getElementById("dashboard")),
        parameters: customCount(document.getElementById("parameters")),
      };
    },
    addSheets: async function (hrefs) {
      var loaded = [];
      for (var i = 0; i < hrefs.length; i++) loaded.push(await addSheet(hrefs[i]));
      return loaded;
    },
    setWidth: function (width) {
      containers().forEach(function (element) {
        element.style.width = width;
      });
      return width;
    },
    setTheme: function (name) {
      containers().forEach(function (element) {
        if (name === null) element.removeAttribute("data-theme");
        else element.setAttribute("data-theme", name);
      });
      return name;
    },
    setRootFont: function (size) {
      document.documentElement.style.fontSize = size;
      return size;
    },
    /** Records the canvas fill of every chart picture at the moment its first canvas is written (B5). */
    watchFirstFills: function () {
      window.__dp1065FirstFills = [];
      var seen = new Set();
      new MutationObserver(function () {
        document.querySelectorAll("#dashboard svg.dp-chart").forEach(function (svg) {
          var canvas = svg.querySelector("rect.dp-guide");
          if (canvas && !seen.has(svg)) {
            seen.add(svg);
            window.__dp1065FirstFills.push(colour(svg.parentElement, canvas.getAttribute("fill")));
          }
        });
      }).observe(document.getElementById("dashboard"), { childList: true, subtree: true, attributes: true });
      return true;
    },
    firstFills: function () {
      return window.__dp1065FirstFills || [];
    },
    boot: async function (args) {
      // Compare the stylesheets with their declared fonts available for the first chart measurement.
      // With font-display: optional, cold visits can otherwise measure the same labels in different faces.
      await Promise.all(Array.from(document.fonts, function (face) { return face.load(); }));
      var script = await addScript(args.script);
      if (script.indexOf("ok ") !== 0) return script;
      var DP = window.DatapipelinesDashboard;
      var elements = containers();
      var adapter = DP.adapters(elements[0], { parametersContainer: elements[1] });
      var instance = DP.init({
        server: { credentials: { proxyBaseUrl: args.proxyBase } },
        dashboard: { id: args.board, version: "released" },
        container: elements[0],
        adapter: adapter,
      });
      window.__dp1065Instance = instance;
      try {
        await instance.ready;
        return "ready";
      } catch (e) {
        return "refused " + String((e && (e.code || e.message)) || e);
      }
    },
    /** Every tile drawn, no animation running, the fonts settled. */
    settled: function (args) {
      var charts = args.charts.every(function (viz) {
        var c = card(viz);
        return c !== null && c.querySelectorAll("svg.dp-chart .dp-mark").length > 0;
      });
      var cells = card("cells");
      var rows = cells !== null && cells.querySelectorAll(".dp-dashboard-table tbody tr").length === args.rows;
      var total = card("total");
      var kpi = total !== null && total.querySelector(".dp-dashboard-kpi-number") !== null &&
        total.querySelector(".dp-dashboard-kpi-number").textContent.trim() === args.kpi;
      var still = document.getAnimations().every(function (a) {
        return a.playState !== "running";
      });
      return charts && rows && kpi && still && document.fonts.status === "loaded" && this.chartsFit();
    },
    /** Each chart picture is its host's width, its viewBox the same width. */
    chartsFit: function () {
      var svgs = document.querySelectorAll("#dashboard svg.dp-chart");
      return svgs.length > 0 && Array.prototype.every.call(svgs, function (svg) {
        var width = parseFloat(svg.getAttribute("width"));
        var box = (svg.getAttribute("viewBox") || "").split(" ");
        return Math.abs(width - svg.parentElement.clientWidth) < 1 && Math.abs(parseFloat(box[2]) - width) < 0.01;
      });
    },
    /** B4/B5/B6: the card's background, the chart's canvas fill and text ink, each beside the token it should be. */
    theme: function (viz) {
      var c = card(viz);
      var svg = c.querySelector("svg.dp-chart");
      var host = svg.parentElement;
      var canvas = svg.querySelector("rect.dp-guide");
      var text = svg.querySelector("text");
      return {
        cardBackground: getComputedStyle(c).backgroundColor,
        surfaceToken: colour(c, "var(--surface-default)"),
        canvasFill: colour(host, canvas.getAttribute("fill")),
        canvasToken: colour(host, "var(--chart-canvas)"),
        textInk: text === null ? null : colour(host, text.getAttribute("fill")),
        inkTokens: ["--chart-ink", "--chart-ink-2", "--chart-muted"].map(function (token) {
          return colour(host, "var(" + token + ")");
        }),
        tipShadow: getComputedStyle(document.getElementById("dashboard")).getPropertyValue("--chart-tip-shadow").trim(),
        containerBackground: getComputedStyle(document.getElementById("dashboard")).backgroundColor,
      };
    },
    /** B10: the container's and a card's width, each picture beside its host, and three font sizes in CSS px. */
    zoom: function () {
      var round = function (n) {
        return Math.round(n * 100) / 100;
      };
      var firstText = document.querySelector("#dashboard svg.dp-chart text");
      var cell = document.querySelector("#dashboard td");
      return {
        innerWidth: window.innerWidth,
        devicePixelRatio: window.devicePixelRatio,
        container: round(document.getElementById("dashboard").getBoundingClientRect().width),
        card: round(card(this.firstChart()).getBoundingClientRect().width),
        cardFont: getComputedStyle(card(this.firstChart())).fontSize,
        cellFont: cell === null ? null : getComputedStyle(cell).fontSize,
        chartTextFont: firstText === null ? null : getComputedStyle(firstText).fontSize,
        pictures: Array.prototype.map.call(document.querySelectorAll("#dashboard svg.dp-chart"), function (svg) {
          return {
            width: parseFloat(svg.getAttribute("width")),
            viewBoxWidth: parseFloat((svg.getAttribute("viewBox") || "").split(" ")[2]),
            hostWidth: svg.parentElement.clientWidth,
            drawnWidth: round(svg.getBoundingClientRect().width),
          };
        }),
      };
    },
    firstChart: function () {
      var svg = document.querySelector("#dashboard svg.dp-chart");
      return svg.closest("article.dp-dashboard-card").getAttribute("data-dp-viz");
    },
    classes: function () {
      return containers().map(function (element) {
        return element.className;
      });
    },
  };
})();
