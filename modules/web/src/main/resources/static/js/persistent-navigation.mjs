import { preparePage, mountCharts } from "./page-assets.mjs";

const FAMILIES = /^\/(?:pipelines|templates|dashboards|visualizations|parameter-sets)(?:\/|$)/;
// shell.js's busy pair (NAVIGATION_PREPARE_START/END): the bar counts a preparation like a request.
const PREPARE_START = "dp:navigation-prepare-start";
const PREPARE_END = "dp:navigation-prepare-end";
let generation = 0; let pending = null;
const tickets = new WeakMap();
const requests = new WeakMap();
function notice(message) {
  let region = document.getElementById("dp-navigation-notice");
  if (!region) { region = document.createElement("div"); region.id = "dp-navigation-notice"; region.className = "ds-alert"; region.setAttribute("role", "alert"); document.getElementById("app-main")?.prepend(region); }
  region.textContent = message;
}

// htmx's documented asynchronous confirmation seam keeps its swap, focus and history ownership.
// A preparation GET validates the destination; the final boosted GET remains freshly authorized.
document.body.addEventListener("htmx:confirm", async event => {
  const detail = event.detail; const anchor = detail?.elt;
  const destinationURL = detail?.path ? new URL(detail.path, location.href) : null;
  if (detail?.verb !== "get" || anchor?.tagName !== "A" || destinationURL?.origin !== location.origin || !FAMILIES.test(destinationURL.pathname) ||
      anchor.closest('[hx-boost="false"]')) return;
  event.preventDefault(); pending?.abort(); const controller = new AbortController(); pending = controller;
  const own = ++generation;
  document.body.dispatchEvent(new CustomEvent(PREPARE_START));
  try {
    const response = await fetch(detail.path, { signal: controller.signal, credentials: "same-origin", cache: "no-store", headers: { Accept: "text/html" } });
    if (!response.ok || !response.headers.get("content-type")?.includes("text/html")) throw new Error("Could not open destination");
    const destination = new DOMParser().parseFromString(await response.text(), "text/html");
    const main = destination.getElementById("app-main");
    if (!main || destination.documentElement.dataset.dpWorkspaceId !== document.documentElement.dataset.dpWorkspaceId) throw new Error("Session or workspace changed. Reload to continue.");
    await preparePage(main, controller.signal);
    if (own !== generation || controller.signal.aborted) return;
    document.getElementById("dp-navigation-notice")?.remove();
    tickets.set(anchor, own);
    detail.issueRequest(true);
  } catch (error) {
    if (own === generation && !controller.signal.aborted) notice(error.message);
  } finally {
    // After issueRequest: htmx's own request already holds the bar, so it never blinks off.
    document.body.dispatchEvent(new CustomEvent(PREPARE_END));
  }
});
document.body.addEventListener("htmx:beforeRequest", event => {
  const detail = event.detail;
  const ticket = tickets.get(detail?.elt);
  // Boosted navigation still targets body here; shell.js retargets it at beforeSwap.
  if (ticket === undefined && detail?.boosted) {
    pending?.abort(); generation += 1;
  }
  if (ticket !== undefined && detail.xhr) requests.set(detail.xhr, ticket);
});
document.body.addEventListener("htmx:beforeSwap", event => {
  const detail = event.detail;
  if (!detail?.boosted || !detail.shouldSwap || detail.isError) return;
  const ticket = requests.get(detail.xhr);
  if (ticket !== undefined && ticket !== generation) {
    detail.shouldSwap = false; event.preventDefault(); return;
  }
  const destination = new DOMParser().parseFromString(detail.serverResponse, "text/html");
  if (!destination.getElementById("app-main") ||
      destination.documentElement.dataset.dpWorkspaceId !== document.documentElement.dataset.dpWorkspaceId) {
    detail.shouldSwap = false; event.preventDefault(); notice("Session or workspace changed. Reload to continue.");
  }
}, true);
document.body.addEventListener("htmx:afterSettle", () => mountCharts());
document.body.addEventListener("htmx:historyRestore", () => mountCharts());
mountCharts();
