// #494 — the ONE page-script loader. Every family page declares the scripts it needs in inert
// <template data-page-assets> catalogs inside #app-main (template content neither parses nor
// restores from htmx's history cache as executable script). This module executes each catalog
// entry in order, once per document whoever asks for it, through one gate (assetPath), and calls
// each `data-page-mount` entry's mount once per catalog node. A catalog's attribute value says
// WHEN its scripts may run: empty (reusable libraries, mount glue, document-level wiring) runs
// during the preparation, before the swap; "on-mount" (the pipeline editor's and the parameter
// workspace's runtimes, whose modules and activations need their page in place) is only fetched
// then, and executes once the page is live — exactly when their own loaders used to run it.
const CATALOG = "template[data-page-assets]";
const loaded = new Map();
const mounted = new WeakMap(); // catalog node -> its one mount attempt
/** The global each singleton provides: a source whose global already exists never executes again. */
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
  // #556: the overviews' document-level Copy prompt listener, bound once per document.
  "/js/overview.mjs": "DatapipelinesOverview",
  // #358: a second Alpine boots a second observer and stacks the editor's bindings.
  "/vendor/alpinejs/alpine.min.js": "Alpine",
  "/vendor/dagre/dagre.min.js": "dagre",
  "/vendor/cytoscape/cytoscape.min.js": "cytoscape",
};
const MOUNTS = {
  "/js/dashboards-page.js": "DashboardPageMount",
  "/js/dashboards/workspace.js": "DashboardWorkspaceMount",
  "/js/visualizations/workspace.js": "VisualizationWorkspaceMount",
  "/js/template-editor/workspace.js": "TemplateWorkspaceMount",
  "/js/pipeline-editor/runtime.js": "PipelineWorkspaceMount",
  "/js/parameter-workspace/runtime.js": "ParameterWorkspaceMount",
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
function catalogsIn(main) {
  return Array.from(main.querySelectorAll(CATALOG));
}
function entriesOf(catalog) {
  return Array.from(catalog.content.querySelectorAll("script[src]"));
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
    // The head is outside the history element: a cached restore never replays these.
    document.head.append(script);
  });
  loaded.set(source, promise);
  return promise;
}

/** Fetch every catalog's dependencies before commit; execute only the catalogs that may run before the swap. */
export async function preparePage(main, signal) {
  const early = new Set();
  for (const catalog of catalogsIn(main)) {
    if (catalog.getAttribute("data-page-assets") === "on-mount") continue;
    for (const script of entriesOf(catalog)) early.add(script.getAttribute("src"));
  }
  for (const entry of sourcesIn(main)) {
    if (signal?.aborted) throw new DOMException("Navigation superseded", "AbortError");
    if (loaded.has(entry.source)) { await loaded.get(entry.source); continue; }
    if (early.has(entry.source)) { await loadScript(entry.source); continue; }
    const response = await fetch(entry.source, { signal, credentials: "same-origin", cache: "default" });
    if (!response.ok) throw new Error("Could not load page dependencies");
    await response.arrayBuffer();
  }
}

// The page's own bare script tags run BEFORE its mounts, as on a full load (parser-inserted tags execute before
// this deferred module). A swap or cached restore re-creates them (htmx: async=false, in order) to execute AFTER
// the swap event, so a mount waits for each one still pending in main: an activation that ran first would let a
// late script act on the live page (#494: a replayed second Alpine's start walk bound the activated root twice).
const settledScripts = new WeakSet();
const markSettled = event => { if (event.target?.tagName === "SCRIPT") settledScripts.add(event.target); };
if (globalThis.document?.addEventListener) { // absent only for non-browser importers of assetPath (node tests)
  document.addEventListener("load", markSettled, true);
  document.addEventListener("error", markSettled, true);
  document.querySelectorAll("script").forEach(script => settledScripts.add(script));
}
function pendingScriptsIn(main) {
  return Array.from(main.querySelectorAll("script[src]"))
    .filter(script => !settledScripts.has(script) && !script.noModule && ["", "text/javascript", "module"].includes(script.type))
    .map(script => new Promise(resolve => {
      script.addEventListener("load", resolve, { once: true });
      script.addEventListener("error", resolve, { once: true });
    }));
}

/** Each catalog node owns one mount, including repeated innerHTML history restores into the same main. */
export async function mountPage(main = document.getElementById("app-main")) {
  if (!main) return;
  await Promise.all(pendingScriptsIn(main));
  await Promise.all(catalogsIn(main).map(catalog => mountCatalog(main, catalog)));
}

function mountCatalog(main, catalog) {
  if (mounted.has(catalog)) return mounted.get(catalog);
  const live = () => main.isConnected && catalogsIn(main).includes(catalog);
  const promise = (async () => {
    for (const script of entriesOf(catalog)) {
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
    if (!live()) return;
    // Remembered per catalog (#465): later settles of this page (its partial polls) neither retry
    // nor stack notices; a navigation or history restore brings a new catalog and a new attempt.
    const notice = document.createElement("p"); notice.className = "ds-error"; notice.setAttribute("role", "alert");
    notice.textContent = error.message; main.prepend(notice);
  });
  mounted.set(catalog, promise);
  return promise;
}
