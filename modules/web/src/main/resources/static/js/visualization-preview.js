/*
 * The visualization test preview's glue (#353; ui-screens.md §4.x, the implementation spec's §11.2). A FILE, because
 * the CSP has no unsafe-inline: it reads the page's one JSON block and mounts ONE runtime instance per case, in the
 * runtime's fixture mode — `server: { fixtures }`, no base URL, no credentials, so no request from this page can
 * reach `/api/v1` — with the first-party composite adapter, exactly as a board mounts a visualization.
 *
 * Each case section carries what an agent's Playwright checks read: `data-dp-ready="true"` once the instance's
 * bootstrap barrier held, `data-dp-error="<code>"` when it did not, and the composite's own status chip
 * (`.dp-dashboard-status[data-dp-state]`) per occurrence. `window.DatapipelinesPreview.instances` exposes the
 * instances for an agent that wants to re-render (`instance.refresh()` replays the same fixtures).
 *
 * #399 — the ONE case mount is exposed as `window.DatapipelinesPreviewMount(...)`: the signed-in workspace's
 * Preview tab mounts its cases through the SAME function over the SAME `{visualization, cases}` block (built by the
 * same PreviewViews.page), so the two surfaces cannot render a case differently. On the capability page nothing
 * changed: the block id, the per-case loop and the exposed instances are as before.
 */
(function () {
  "use strict";

  /**
   * Mounts one case in fixture mode into [board] and marks [section] ready or failed. Returns the instance, or
   * null when init threw (the section then carries `data-dp-error`).
   */
  function mountCase(runtime, data, testCase, section, board, notifications) {
    try {
      var instance = runtime.init({
        server: { fixtures: { config: testCase.config, results: testCase.results } },
        dashboard: { id: data.visualization.id, version: data.visualization.version },
        container: board,
        adapter: runtime.adapters(board),
        options: {
          onNotification: function (notification) {
            notifications.push({ case: testCase.name, code: notification.code });
          },
        },
      });
      instance.ready.then(
        function () {
          section.setAttribute("data-dp-ready", "true");
        },
        function (error) {
          section.setAttribute("data-dp-error", error && error.code ? error.code : "bootstrap.failed");
        },
      );
      return instance;
    } catch (error) {
      section.setAttribute("data-dp-error", error && error.code ? error.code : "init.failed");
      return null;
    }
  }

  window.DatapipelinesPreviewMount = mountCase;

  var block = document.getElementById("dp-preview-data");
  var runtime = window.DatapipelinesDashboard;
  if (!block || !runtime) return;
  var data = JSON.parse(block.textContent);
  var instances = [];
  window.DatapipelinesPreview = { instances: instances, notifications: [] };

  (data.cases || []).forEach(function (testCase, index) {
    var section = document.querySelector('[data-dp-case-index="' + index + '"]');
    var board = document.querySelector('[data-dp-case-board="' + index + '"]');
    if (!section || !board) return;
    var instance = mountCase(runtime, data, testCase, section, board, window.DatapipelinesPreview.notifications);
    if (instance) instances.push(instance);
  });
})();
