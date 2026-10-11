import { mountSearchTree } from "./component.mjs";
import { restTreeSource } from "./rest-source.mjs";
import { nextOpen, restoreOpen } from "./sidebar-state.mjs";

const instances = new Map();
const reveal = new Map();
const workspace = document.documentElement.dataset.dpWorkspaceId;
const workspaceName = document.documentElement.dataset.dpWorkspace;
const storageKey = `dp-tree-open:${workspaceName}`;
const legacyKey = family => `dp-tree-panel:${workspaceName}:${family}`;
const branches = new Map();
let open = null;
function remember() {
  try {
    if (open === null) localStorage.removeItem(storageKey);
    else localStorage.setItem(storageKey, open);
  } catch (_) { /* preference only; the accordion works without storage */ }
}
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
function apply(family) {
  const focused = document.activeElement;
  const closing = branches.get(open);
  open = family;
  branches.forEach(({ branch, panel, toggle, label }, source) => {
    const visible = source === open;
    panel.hidden = !visible;
    toggle.setAttribute("aria-expanded", String(visible));
    branch.classList.toggle("is-tree-open", visible);
    if (visible && !instances.has(source)) {
      const instance = mountSearchTree(panel, {
        source: restTreeSource(source), sourceKey: source, context: { workspace }, label,
        searchPlaceholder: `Search ${label.toLowerCase()}…`,
        getHref: node => node.href,
        onRendered(region) { window.htmx?.process(region || panel); markCurrent(); },
      });
      instances.set(source, instance); markCurrent();
    }
  });
  if (closing?.panel.hidden && closing.panel.contains(focused)) {
    (branches.get(open) || closing).toggle.focus();
  }
  remember();
}
document.querySelectorAll("[data-nav-branch]").forEach(branch => {
  const family = branch.dataset.navBranch;
  const panel = branch.querySelector("[data-nav-tree]"); const toggle = branch.querySelector("[data-nav-tree-toggle]");
  if (!panel || !toggle) return;
  const label = branch.querySelector(".app-nav-link .app-rail-label")?.textContent?.trim() || family.replaceAll("-", " ");
  branches.set(family, { branch, panel, toggle, label });
  toggle.addEventListener("click", () => apply(nextOpen(open, family)));
  reveal.set(family, () => {
    document.documentElement.classList.remove("rail-collapsed");
    document.documentElement.classList.add("rail-expanded");
    if (window.innerWidth < 768) document.documentElement.classList.add("rail-open");
    apply(family); instances.get(family)?.input.focus();
  });
});
const order = Array.from(branches.keys());
const current = document.querySelector("#app-main [data-nav-current]")?.getAttribute("data-nav-current") ||
  document.querySelector("[data-nav-branch] .app-nav-link.active")?.closest("[data-nav-branch]")?.dataset.navBranch;
const restored = restoreOpen({
  get stored() { return localStorage.getItem(storageKey); },
  get legacyFlags() { return Object.fromEntries(order.map(family => [family, localStorage.getItem(legacyKey(family))])); },
  current, order,
});
apply(restored);
try { order.forEach(family => localStorage.removeItem(legacyKey(family))); } catch (_) { /* preference only */ }
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
  markCurrent();
});
document.body.addEventListener("htmx:historyRestore", markCurrent);
document.body.addEventListener("click", event => {
  const control = event.target.closest("[data-nav-tree-reveal]");
  if (control) reveal.get(control.dataset.navTreeReveal)?.();
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) instances.forEach(instance => instance.refresh());
});
// Observable host seam also supports acceptance fixtures without exposing the widget's routing.
window.DatapipelinesSidebarTrees = instances;
