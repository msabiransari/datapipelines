/** Normalize once for both request identity and admission. */
export function normalizeQuery(value) { return String(value || "").trim().replace(/\s+/g, " "); }

/**
 * Per-instance shallow tree state. The supplied source owns transport and authorization;
 * this state owns continuation, cancellation and admission even when transport ignores abort.
 */
export function createTreeState(options, changed = () => {}) {
  let source = options.source;
  let context = options.context && typeof options.context === "object" ? { ...options.context } : options.context;
  let root = options.root || "";
  let epoch = 0;
  let disposed = false;
  let query = "";
  const levels = new Map();
  const open = new Set();
  const folded = new Set();
  let search = newLevel();
  function newLevel() { return { nodes: new Map(), cursor: null, status: "unloaded", complete: false, generation: 0, controller: null }; }
  function level(parent) {
    if (!levels.has(parent)) levels.set(parent, newLevel());
    return levels.get(parent);
  }
  function cancel(target) {
    target.generation += 1;
    target.controller?.abort();
    target.controller = null;
    if (target.status === "loading") target.status = target.nodes.size ? "incomplete" : "unloaded";
  }
  function cancelAll() { cancel(search); levels.forEach(cancel); }
  function admit(page, parent, searching, expectedQuery) {
    if (!page || !Array.isArray(page.nodes) || !(page.nextCursor === null || typeof page.nextCursor === "string")) throw new Error("Invalid tree page");
    const keys = new Set();
    for (const node of page.nodes) {
      if (!node || typeof node.key !== "string" || typeof node.name !== "string" || typeof node.path !== "string" ||
          !["folder", "artifact"].includes(node.kind) || !(node.parentKey === null || typeof node.parentKey === "string") ||
          typeof node.hasChildren !== "boolean" || keys.has(node.key)) throw new Error("Invalid tree node");
      keys.add(node.key);
    }
    if (page.identity && (page.identity.root !== root || page.identity.parent !== parent || page.identity.query !== (searching ? expectedQuery : null) ||
        page.identity.mode !== (searching ? "search" : "browse"))) throw new Error("Foreign tree page");
  }
  async function fetchLevel(parent, searching = false) {
    const target = searching ? search : level(parent);
    if (disposed || target.status === "loading" || target.complete) return;
    const requestEpoch = epoch;
    const generation = ++target.generation;
    const expectedQuery = query;
    const controller = new AbortController();
    target.controller = controller;
    target.status = "loading";
    target.error = null;
    changed();
    const seen = new Set();
    if (target.cursor !== null) seen.add(target.cursor);
    const live = () => !disposed && requestEpoch === epoch && generation === target.generation && expectedQuery === query;
    try {
      while (live()) {
        const page = searching
          ? await source.search(expectedQuery, target.cursor, { context, root }, controller.signal)
          : await source.loadChildren(parent, target.cursor, { context, root }, controller.signal);
        if (!live()) return;
        admit(page, parent, searching, expectedQuery);
        if (page.context) {
          if (context?.viewToken && page.context.viewToken !== context.viewToken) {
            const error = new Error("Navigation view changed. Retry to reload."); error.resetTree = true;
            context = { ...page.context }; throw error;
          }
          context = { ...page.context };
        }
        if (page.nextCursor !== null && (page.nextCursor === "" || seen.has(page.nextCursor))) throw new Error("Non-progressing tree continuation");
        page.nodes.forEach(node => {
          target.nodes.set(node.key, node);
          if (searching && node.kind === "folder" && !folded.has(node.key)) open.add(node.key);
        });
        target.cursor = page.nextCursor;
        if (page.nextCursor === null) {
          target.complete = true;
          target.status = "complete";
          target.controller = null;
          changed();
          return;
        }
        seen.add(page.nextCursor);
        changed();
      }
    } catch (error) {
      if (!live()) return;
      if (error.resetTree) {
        cancelAll(); epoch += 1; query = ""; levels.clear(); open.clear(); folded.clear(); search = newLevel();
        const fresh = level(null); fresh.status = "error"; fresh.error = error; changed(); return;
      }
      target.status = target.nodes.size ? "incomplete" : "error";
      target.error = error;
      target.controller = null;
      changed();
    }
  }
  const state = {
    get query() { return query; }, get root() { return root; }, get open() { return open; },
    get search() { return search; }, get levels() { return levels; },
    initialize() { return fetchLevel(null); },
    nodes(parent = null) {
      const nodes = query ? Array.from(search.nodes.values()).filter(node => node.parentKey === parent) : Array.from(level(parent).nodes.values());
      return nodes.sort((a, b) => {
        const first = a.kind === "folder" ? 0 : 1;
        const second = b.kind === "folder" ? 0 : 1;
        return first - second || (a.path < b.path ? -1 : a.path > b.path ? 1 : a.key < b.key ? -1 : a.key > b.key ? 1 : 0);
      });
    },
    status(parent = null) { return query ? search : level(parent); },
    async expand(key) { open.add(key); folded.delete(key); changed(); if (!query) await fetchLevel(key); },
    collapse(key) { open.delete(key); if (query) folded.add(key); else cancel(level(key)); changed(); },
    async setQuery(value, defer = false) {
      const next = normalizeQuery(value);
      if (next === query) return;
      cancelAll(); epoch += 1; query = next; open.clear(); folded.clear(); search = newLevel(); changed();
      if (!defer) await (query ? fetchLevel(null, true) : fetchLevel(null));
    },
    async clear() {
      cancelAll(); epoch += 1; query = ""; open.clear(); folded.clear(); search = newLevel(); changed();
      await fetchLevel(null);
    },
    retry(parent = null) { return fetchLevel(parent, !!query); },
    async refresh(parent = null) {
      if (query) { cancel(search); search = newLevel(); await fetchLevel(null, true); return; }
      const target = level(parent); cancel(target); target.complete = false; target.cursor = null; target.nodes.clear();
      target.status = "unloaded";
      if (parent === null || open.has(parent)) await fetchLevel(parent); else changed();
    },
    async update(next) {
      cancelAll(); epoch += 1; source = next.source || source; context = next.context && typeof next.context === "object" ? { ...next.context } : next.context; root = next.root || "";
      query = ""; open.clear(); folded.clear(); levels.clear(); search = newLevel(); changed(); await fetchLevel(null);
    },
    dispose() { disposed = true; epoch += 1; cancelAll(); },
  };
  return state;
}
