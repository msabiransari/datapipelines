import { mountSearchTree } from "./component.mjs";
import { restTreeSource } from "./rest-source.mjs";

const instances = new Map();
const reveal = new Map();
const workspace = document.documentElement.dataset.dpWorkspaceId;
const workspaceName = document.documentElement.dataset.dpWorkspace;
const storageKey = family => `dp-tree-panel:${workspaceName}:${family}`;
function remember(family, visible) { try { localStorage.setItem(storageKey(family), visible ? "1" : "0"); } catch (_) { /* preference only */ } }
function markCurrent() {
  const current = document.querySelector("#app-main [data-nav-current]");
  const family = current?.getAttribute("data-nav-current");
  const id = current?.getAttribute("data-nav-current-id");
  instances.forEach((instance, source) => {
    let key = source === family && id ? `artifact:${id}` : null;
    if (!key || source === "templates") {
      const matching = Array.from(instance.state.levels.values()).flatMap(level => Array.from(level.nodes.values()))
        .find(node => node.href === location.pathname);
      key = matching?.key || null;
    }
    instance.select(key);
  });
}
document.querySelectorAll("[data-nav-branch]").forEach(branch => {
  const family = branch.dataset.navBranch;
  const panel = branch.querySelector("[data-nav-tree]"); const toggle = branch.querySelector("[data-nav-tree-toggle]");
  if (!panel || !toggle) return;
  function visibility(visible) {
    panel.hidden = !visible; toggle.setAttribute("aria-expanded", String(visible)); remember(family, visible);
    if (visible && !instances.has(family)) {
      const instance = mountSearchTree(panel, {
        source: restTreeSource(family), sourceKey: family, context: { workspace }, label: branch.querySelector(".app-nav-label")?.textContent?.trim() || family.replaceAll("-", " "),
        getHref: node => node.href,
        onRendered() { window.htmx?.process(panel); markCurrent(); },
      });
      instances.set(family, instance); markCurrent();
    }
  }
  toggle.addEventListener("click", () => visibility(panel.hidden));
  reveal.set(family, () => {
    document.documentElement.classList.remove("rail-collapsed");
    document.documentElement.classList.add("rail-expanded");
    if (window.innerWidth < 768) document.documentElement.classList.add("rail-open");
    visibility(true); instances.get(family)?.input.focus();
  });
  try { if (localStorage.getItem(storageKey(family)) === "1") visibility(true); } catch (_) { /* closed by default */ }
});
document.body.addEventListener("htmx:afterSettle", markCurrent);
document.body.addEventListener("htmx:historyRestore", markCurrent);
document.body.addEventListener("dp:artifacts-changed", event => {
  const instance = instances.get(event.detail?.family);
  if (instance) instance.refresh(event.detail.parentKey || null);
});
document.body.addEventListener("click", event => {
  const control = event.target.closest("[data-nav-tree-reveal]");
  if (control) reveal.get(control.dataset.navTreeReveal)?.();
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) instances.forEach(instance => instance.refresh());
});
// Observable host seam also supports acceptance fixtures without exposing the widget's routing.
window.DatapipelinesSidebarTrees = instances;
