/*
 * embed.js — the boot glue a host page needs for one embedded dashboard
 * (the reference example at examples/dashboard-embed/).
 *
 * Plain ES2019, one IIFE, no module syntax: it loads in any browser and under
 * `node --test`'s fake DOM via `require`. It expects, in this order:
 *   1. the Plotly bundle script with data-dp-plotly-bundle="2d" (or "3d"),
 *   2. /js/datapipelines-dashboard.js (publishes window.DatapipelinesDashboard),
 *   3. the renderer adapter files the dashboard's kinds need,
 *   4. this file.
 *
 * The host page declares ONE container (and optionally a parameters pane):
 *   <div data-dp-embed
 *        data-dp-proxy-base="https://your-proxy.example"
 *        data-dp-dashboard-id="00000000-0000-0000-0000-000000000000"
 *        data-dp-parameters="parameters"></div>
 *   <div id="parameters"></div>
 *
 * The key NEVER appears here: every request goes to the proxy base, which
 * attaches the dashboard key server-side (examples/dashboard-proxy/).
 *
 * The one global: window.dpEmbed = { ready, instance } — the mounted
 * dashboard's readiness promise and instance, so a test (or the page) can
 * await and drive it. dispose() runs on pagehide.
 */
(function () {
  "use strict";

  function boot(container) {
    var DP = window.DatapipelinesDashboard;
    if (!DP) throw new Error("datapipelines-dashboard.js must load before embed.js");

    var proxyBase = container.getAttribute("data-dp-proxy-base");
    var dashboardId = container.getAttribute("data-dp-dashboard-id");
    if (!proxyBase) throw new Error("the container is missing data-dp-proxy-base");
    if (!dashboardId) throw new Error("the container is missing data-dp-dashboard-id");

    var parametersEl = null;
    var parametersId = container.getAttribute("data-dp-parameters");
    if (parametersId && typeof document.getElementById === "function") {
      parametersEl = document.getElementById(parametersId);
    }

    var adapter = DP.adapters(container, parametersEl ? { parametersContainer: parametersEl } : undefined);
    var instance = DP.init({
      server: { credentials: { proxyBaseUrl: proxyBase } },
      dashboard: { id: dashboardId, version: "released" },
      container: container,
      adapter: adapter,
    });

    var handle = {
      ready: instance.ready,
      instance: instance,
      dispose: function () {
        instance.dispose();
      },
    };
    window.dpEmbed = { ready: handle.ready, instance: instance };

    // A rejected ready means a bootstrap step failed; the notification's step
    // name is the one thing a host page should show (text only, no markup).
    instance.ready.catch(function (error) {
      var note = document.createElement("p");
      note.setAttribute("role", "status");
      note.textContent = String(
        (error && error.details && error.details.step) ||
          (error && error.code) ||
          (error && error.message) ||
          error,
      );
      container.appendChild(note);
    });

    if (typeof window.addEventListener === "function") {
      window.addEventListener("pagehide", handle.dispose);
    }
    return handle;
  }

  function mountAll() {
    var hosts = document.querySelectorAll("[data-dp-embed]");
    for (var i = 0; i < hosts.length; i++) boot(hosts[i]);
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mountAll);
  } else {
    mountAll();
  }

  if (typeof module !== "undefined" && module.exports) module.exports = { boot: boot };
  if (typeof window !== "undefined") window.dpEmbedBoot = boot;
})();
