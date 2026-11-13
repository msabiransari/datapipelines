import { mountSearchTree } from "./component.mjs";
import { restTreeSource } from "./rest-source.mjs";
import { browseLabel, familyForPath, retiredTreeKeys } from "./sidebar-state.mjs";

/*
 * #554 — the resource browser's lifecycle (layouts/default.html `#app-browser`). The five
 * `[data-nav-tree]` panels are the REST tree engine's hosts; ONE is visible — the family of
 * the current page, derived from the path — and the whole column is hidden elsewhere.
 * Each family mounts lazily the first time it is actually shown and is kept for the life
 * of the document, so switching families and coming back finds its search, open folders
 * and scroll as they were (record §7). Showing a panel never refreshes it.
 */
const root = document.documentElement;
const workspace = root.dataset.dpWorkspaceId;
const workspaceName = root.dataset.dpWorkspace;
const browser = document.getElementById("app-browser");
const opener = document.getElementById("browser-open");
const panels = new Map(Array.from(document.querySelectorAll("#app-browser [data-nav-tree]"),
  panel => [panel.dataset.navTree, panel]));
const families = Array.from(panels.keys());
const instances = new Map();
const scrolled = new Map();
let active = null;

try { retiredTreeKeys(workspaceName, families).forEach(key => localStorage.removeItem(key)); } catch (_) { /* preference only */ }

function markCurrent() {
  const current = document.querySelector("#app-main [data-nav-current]");
  const family = current?.getAttribute("data-nav-current");
  const id = current?.getAttribute("data-nav-current-id");
  instances.forEach((instance, source) => {
    let key = source === family && id ? `artifact:${id}` : null;
    if (!key || source === "templates") {
      const matching = [...Array.from(instance.state.levels.values()).flatMap(level => Array.from(level.nodes.values())),
        ...instance.state.search.nodes.values()]
        .find(node => node.href === location.pathname);
      key = matching?.key || null;
    }
    instance.select(key);
  });
}

/* Is the browser on screen? A desktop column the user has not hidden, or the open phone
   drawer. A closed drawer and a hidden column mount nothing. */
function shown() {
  if (!active || browser?.hidden) return false;
  if (window.matchMedia(window.DpShell?.PHONE_QUERY || "(max-width: 760.98px)").matches) {
    return root.classList.contains("browser-open");
  }
  return !root.classList.contains("browser-hidden");
}

function mount(family) {
  const panel = panels.get(family);
  const label = panel.dataset.browserLabel || family.replaceAll("-", " ");
  const instance = mountSearchTree(panel, {
    source: restTreeSource(family), context: { workspace }, label, leafGlyph: panel.dataset.browserGlyph,
    searchPlaceholder: `Search ${label.toLowerCase()}…`, maxLength: Number(root.dataset.dpSearchMaxLength),
    getHref: node => node.href,
    onRendered(region) { window.htmx?.process(region || panel); markCurrent(); },
  });
  // The last offset the reader scrolled to, read while it is on screen: a scroller taken
  // out of layout reports 0, so the value is kept as it happens, not read at hide time.
  instance.scroll.addEventListener("scroll", () => scrolled.set(family, instance.scroll.scrollTop), { passive: true });
  instances.set(family, instance);
  markCurrent();
}

/* Brings the column in line with the current path: the active family's parts visible,
   every other family's hidden, the folder button named for it, and the family mounted
   if the browser is on screen. Called on load, after every settled swap and history
   restore, and when the browser is shown or hidden. */
function sync() {
  const family = familyForPath(location.pathname, families);
  const changed = family !== active;
  active = family;
  if (browser) browser.hidden = !family;
  if (changed) {
    document.querySelectorAll("#app-browser [data-browser-family]").forEach(part => {
      part.hidden = part.dataset.browserFamily !== family;
    });
  }
  if (opener) {
    opener.hidden = !family;
    const name = browseLabel(family ? panels.get(family)?.dataset.browserLabel : null);
    opener.setAttribute("aria-label", name); opener.setAttribute("title", name);
  }
  if (!shown()) return;
  if (!instances.has(family)) mount(family);
  else if (scrolled.has(family)) instances.get(family).scroll.scrollTop = scrolled.get(family);
}

sync();
document.addEventListener(window.DpShell?.BROWSER_VISIBILITY_EVENT || "dp:browser-visibility", sync);
document.body.addEventListener("htmx:afterSettle", () => {
  document.querySelectorAll("[data-artifact-change-family]").forEach(marker => {
    const instance = instances.get(marker.dataset.artifactChangeFamily);
    const path = marker.dataset.artifactChangePath;
    marker.removeAttribute("data-artifact-change-family"); marker.removeAttribute("data-artifact-change-path");
    if (!instance || !path) return;
    let parent = null; let prefix = "";
    for (const segment of path.split("/").slice(0, -1)) {
      prefix += (prefix ? "/" : "") + segment;
      const key = `folder:${prefix}`;
      if (!instance.state.levels.get(parent)?.nodes.has(key)) break;
      parent = key;
    }
    instance.refresh(parent);
  });
  sync();
  markCurrent();
});
document.body.addEventListener("htmx:historyRestore", () => { sync(); markCurrent(); });
// A catalog's Browse folders action (record §3 rule 4): show the browser — the drawer on a
// phone, the column on a desktop — and focus its search. The menu's collapse is untouched.
document.body.addEventListener("click", event => {
  if (event.target.closest("[data-nav-tree-reveal]")) window.DpShell?.revealBrowser(document, window, window.localStorage);
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) instances.forEach(instance => instance.refresh());
});
// Observable host seam also supports acceptance fixtures without exposing the widget's routing.
window.DatapipelinesSidebarTrees = instances;
