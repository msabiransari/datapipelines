import { createTreeState, normalizeQuery } from "./state.mjs";
import { createTreeRenderer, icon } from "./render.mjs";

/** Mount a source-driven, independent search-and-tree, with optional host navigation or selection. */
export function mountSearchTree(host, options) {
  const document = host.ownerDocument;
  const controls = document.createElement("div"); controls.className = "dp-tree-search";
  const input = document.createElement("input"); input.type = "search"; input.className = "ds-input"; input.autocomplete = "off";
  input.placeholder = options.searchPlaceholder || "Search names…"; input.setAttribute("aria-label", `Search ${options.label || "items"}`);
  const clear = document.createElement("button"); clear.type = "button"; clear.className = "ds-button ds-button-ghost ds-button-sm";
  clear.setAttribute("aria-label", "Clear search"); clear.append(icon(document, "x")); clear.hidden = true;
  const refresh = document.createElement("button"); refresh.type = "button"; refresh.className = "ds-button ds-button-ghost ds-button-sm";
  refresh.setAttribute("aria-label", "Refresh tree"); refresh.append(icon(document, "rotate-ccw"));
  controls.append(input, clear, refresh);
  const scroll = document.createElement("div"); scroll.className = "dp-tree-scroll";
  host.replaceChildren(controls, scroll);
  let renderer; let timer = null;
  const state = createTreeState(options, () => { if (state.status().error?.resetTree) input.value = ""; renderer?.render(); clear.hidden = !normalizeQuery(input.value); options.onRendered?.(); });
  renderer = createTreeRenderer(scroll, state, options);
  function clearTree() { clearTimeout(timer); timer = null; input.value = ""; scroll.scrollTop = 0; scroll.scrollLeft = 0; state.clear(); input.focus(); }
  function queryChanged() {
    const value = normalizeQuery(input.value);
    clear.hidden = !value;
    if (value === state.query) return;
    clearTimeout(timer); timer = null;
    if (!value) { scroll.scrollTop = 0; state.setQuery(""); }
    else {
      state.setQuery(value, true);
      timer = setTimeout(() => state.retry(), options.debounceMs ?? 250);
    }
  }
  input.addEventListener("input", queryChanged); input.addEventListener("search", queryChanged);
  clear.addEventListener("click", clearTree); refresh.addEventListener("click", () => state.refresh());
  input.addEventListener("keydown", event => {
    if (event.key === "ArrowDown") { event.preventDefault(); renderer.focusFirst(); }
  });
  state.initialize();
  return {
    state, input, scroll, clear: clearTree,
    select(key) { renderer.select(key); },
    async update(next) { clearTimeout(timer); input.value = ""; await state.update(next); },
    refresh(parent) { return state.refresh(parent); },
    dispose() { clearTimeout(timer); state.dispose(); renderer.dispose(); controls.remove(); scroll.remove(); },
  };
}
