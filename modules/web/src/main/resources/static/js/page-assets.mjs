const loaded = new Map();
const PROVIDED = {
  "/js/datapipelines-dashboard.js": "DatapipelinesDashboard",
  "/js/datapipelines-dashboard-plotly.js": "DatapipelinesDashboardPlotly",
  "/js/datapipelines-dashboard-table.js": "DatapipelinesDashboardTable",
  "/js/datapipelines-dashboard-kpi.js": "DatapipelinesDashboardKpi",
  "/js/visualization-preview.js": "DatapipelinesPreviewMount",
  "/js/workspace/tabs.js": "WorkspaceTabs",
  "/js/workspace/history.js": "WorkspaceHistory",
  "/js/workspace/panes.js": "WorkspacePanes",
  // #465: document-level dialog wiring that must run once per document whichever page loads it first.
  "/js/lifecycle-dialog.js": "lifecycleDialog",
};
const MOUNTS = {
  "/js/dashboards-page.js": "DashboardPageMount",
  "/js/dashboards/workspace.js": "DashboardWorkspaceMount",
  "/js/visualizations/workspace.js": "VisualizationWorkspaceMount",
};

/** Validate first-party asset paths; user data never selects a script URL. */
export function assetPath(value) {
  if (typeof value !== "string" || !/^\/(?:js|vendor)\/[a-zA-Z0-9_./-]+\.(?:js|mjs)$/.test(value) ||
      value.split("/").some(part => part === ".." || part === ".")) throw new Error("Invalid page dependency");
  return value;
}
export function sourcesIn(root) {
  const scripts = Array.from(root.querySelectorAll("script[src]"));
  root.querySelectorAll("template").forEach(template => scripts.push(...template.content.querySelectorAll("script[src]")));
  return scripts.map(script => ({ source: assetPath(script.getAttribute("src")), replay: script.hasAttribute("data-page-mount") }));
}

/** A compatible singleton bundle, with CSP setup BEFORE the vendor executes. */
export function loadScript(source) {
  assetPath(source);
  if (loaded.has(source)) return loaded.get(source);
  if (PROVIDED[source] && window[PROVIDED[source]]) return Promise.resolve();
  if (MOUNTS[source]) window.DatapipelinesPageMountManaged = true;
  const promise = new Promise((resolve, reject) => {
    const script = document.createElement("script"); script.src = source; script.async = false;
    if (source.endsWith(".mjs")) script.type = "module";
    if (source === "/vendor/plotly/plotly-3d.min.js") {
      let style = document.getElementById("plotly.js-style-global");
      if (!style) { style = document.createElement("style"); style.id = "plotly.js-style-global"; document.head.append(style); }
      style.classList.add("no-inline-styles"); script.setAttribute("data-dp-plotly-bundle", "3d");
    }
    script.onload = () => resolve();
    script.onerror = () => { script.remove(); loaded.delete(source); reject(new Error("Could not load page dependencies")); };
    document.head.append(script);
  });
  loaded.set(source, promise);
  return promise;
}

/** Fetch all destination dependencies before commit; execute only inert reusable chart libraries. */
export async function preparePage(main, signal) {
  const chart = main.querySelector("template[data-chart-assets]");
  const chartSources = new Set(chart ? Array.from(chart.content.querySelectorAll("script[src]")).map(script => script.getAttribute("src")) : []);
  for (const entry of sourcesIn(main)) {
    if (signal?.aborted) throw new DOMException("Navigation superseded", "AbortError");
    if (loaded.has(entry.source)) { await loaded.get(entry.source); continue; }
    if (chartSources.has(entry.source)) { await loadScript(entry.source); continue; }
    const response = await fetch(entry.source, { signal, credentials: "same-origin", cache: "default" });
    if (!response.ok) throw new Error("Could not load page dependencies");
    await response.arrayBuffer();
  }
}

/** The catalog node owns one mount, including repeated innerHTML history restores into the same main. */
export async function mountCharts(main = document.getElementById("app-main")) {
  const catalog = main?.querySelector("template[data-chart-assets]");
  if (!catalog) return;
  if (main.__chartMount?.catalog === catalog) return main.__chartMount.promise;
  const own = { catalog, promise: null }; main.__chartMount = own;
  const live = () => main.isConnected && main.querySelector("template[data-chart-assets]") === catalog;
  own.promise = (async () => {
    for (const script of catalog.content.querySelectorAll("script[src]")) {
      if (!live()) return;
      const source = script.getAttribute("src");
      await loadScript(source);
      if (!live()) return;
      if (script.hasAttribute("data-page-mount")) {
        const mount = window[MOUNTS[source]];
        if (typeof mount !== "function") throw new Error("Page mount is unavailable");
        mount(main);
      }
    }
  })().catch(error => {
    if (!live() || main.__chartMount !== own) return;
    // Remembered per catalog (#465): later settles of this page (its partial polls) neither retry
    // nor stack notices (main.__chartMount stays this catalog's); a navigation or history restore
    // brings a new catalog and a new attempt.
    const notice = document.createElement("p"); notice.className = "ds-error"; notice.setAttribute("role", "alert");
    notice.textContent = error.message; main.prepend(notice);
  });
  return own.promise;
}
