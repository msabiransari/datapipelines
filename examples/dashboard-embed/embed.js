/*
 * embed.js — the boot glue a host page needs for one embedded dashboard
 * (the reference example at examples/dashboard-embed/).
 *
 * Plain ES2019, one IIFE, no module syntax: it loads in any browser and under
 * `node --test`'s fake DOM via `require`. It expects, in this order:
 *   1. the Plotly "2d" bundle script with data-dp-plotly-bundle="2d" (every chart
 *      family the chart kind ships is a 2D trace),
 *   2. /js/datapipelines-dashboard.js (publishes window.DatapipelinesDashboard),
 *   3. the chart kind's adapter and its Plotly converter, then the table and KPI
 *      renderers the dashboard's kinds need,
 *   4. this file — it mounts every [data-dp-embed] container automatically.
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
 * The one browser global: window.dpEmbed = { ready, instance } — the mounted
 * dashboard's readiness promise and instance, so a test (or the page) can
 * await and drive it. `require`ing this file exports { boot } as the test
 * seam. dispose() runs on pagehide.
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
      charts: { library: "plotly", instance: window.Plotly },
    });

    var handle = {
      ready: instance.ready,
      instance: instance,
      dispose: function () {
        instance.dispose();
      },
    };
    window.dpEmbed = { ready: handle.ready, instance: instance };

    // A rejected ready means a bootstrap step failed. The rejection is the runtime's
    // one error type (a DashboardError): `code` — the stable reason code, `message`,
    // `retryable` and `details` (possibly empty); it carries no step field. The same
    // failure is ALSO published as a bootstrap notification to the host's
    // onNotification callback — this example installs none, so the code is what the
    // page shows (text only, no markup).
    instance.ready.catch(function (error) {
      var note = document.createElement("p");
      note.setAttribute("role", "status");
      note.textContent = String(
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
})();
