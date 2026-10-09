/** Pages one load fetches before a level stops and offers "Load more" (#465): 10 x 200 rows. */
export const PAGE_CEILING = 10;

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
  const pageCeiling = Number.isInteger(options.pageCeiling) && options.pageCeiling > 0 ? options.pageCeiling : PAGE_CEILING;
  const levels = new Map();
  const open = new Set();
  const folded = new Set();
  let search = newLevel();
  function newLevel() { return { nodes: new Map(), cursor: null, status: "unloaded", complete: false, generation: 0, controller: null, staging: null, refreshError: null }; }
  function level(parent) {
    if (!levels.has(parent)) levels.set(parent, newLevel());
    return levels.get(parent);
  }
  function halt(target) {
    target.generation += 1;
    target.controller?.abort();
    target.controller = null;
  }
  function cancel(target) {
    if (target.staging) { halt(target.staging); target.staging = null; }
    halt(target);
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
  /**
   * Load pages into a level until it completes or the page ceiling stops it ("more").
   * With `staged`, pages go into a hidden staging level that replaces the visible one's
   * rows only on success, so a refresh never blanks what the reader is looking at.
   */
  async function fetchLevel(parent, searching = false, { staged = null, resume = false } = {}) {
    const visible = searching ? search : level(parent);
    const target = staged || visible;
    if (disposed || target.status === "loading" || target.complete || (target.status === "more" && !resume)) return;
    const requestEpoch = epoch;
    const generation = ++target.generation;
    const expectedQuery = query;
    const controller = new AbortController();
    target.controller = controller;
    target.status = "loading";
    target.error = null;
    if (!staged) changed();
    const seen = new Set();
    if (target.cursor !== null) seen.add(target.cursor);
    const live = () => !disposed && requestEpoch === epoch && generation === target.generation && expectedQuery === query &&
      (!staged || visible.staging === staged);
    let pages = 0;
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
        pages += 1;
        if (page.nextCursor === null || pages >= pageCeiling) {
          target.complete = page.nextCursor === null;
          target.status = target.complete ? "complete" : "more";
          target.controller = null;
          if (staged) commit(parent, searching, visible, staged); else changed({ parent, searching });
          return;
        }
        seen.add(page.nextCursor);
        if (!staged) changed({ parent, searching });
      }
    } catch (error) {
      if (!live()) return;
      if (error.resetTree) {
        cancelAll(); epoch += 1; query = ""; levels.clear(); open.clear(); folded.clear(); search = newLevel();
        const fresh = level(null); fresh.status = "error"; fresh.error = error; changed(); return;
      }
      if (staged) {
        // The visible rows stay; the failure is reported beside them and Refresh retries.
        visible.staging = null; visible.refreshError = error; changed(); return;
      }
      target.status = target.nodes.size ? "incomplete" : "error";
      target.error = error;
      target.controller = null;
      changed();
    }
  }
  /** Swap a completed staging level's rows into the visible level in one step. */
  function commit(parent, searching, visible, staged) {
    const removed = Array.from(visible.nodes.keys()).filter(key => !staged.nodes.has(key));
    halt(visible);
    Object.assign(visible, { nodes: staged.nodes, cursor: staged.cursor, status: staged.status, complete: staged.complete,
      error: null, refreshError: null, staging: null, controller: null });
    if (!searching) removed.forEach(drop);
    changed();
  }
  /** Forget a folder that is gone: its open state and every cached level beneath it. */
  function drop(key) {
    open.delete(key); folded.delete(key);
    const gone = levels.get(key); if (!gone) return;
    cancel(gone); levels.delete(key);
    gone.nodes.forEach((node, child) => { if (node.kind === "folder") drop(child); });
  }
  /** An invisible level is not fetched: it empties, so its next expand loads it fresh. */
  function invalidate(key) {
    const target = level(key); cancel(target);
    target.nodes.forEach((node, child) => { if (node.kind === "folder") drop(child); });
    Object.assign(target, { nodes: new Map(), cursor: null, status: "unloaded", complete: false, error: null, refreshError: null });
  }
  /** Refetch one browse level, then every visible open level below it; closed cached levels are invalidated. */
  async function reload(parent) {
    const target = level(parent);
    if (parent !== null && !open.has(parent)) { invalidate(parent); changed(); return; }
    let staged = null;
    if (!target.nodes.size) {
      cancel(target); Object.assign(target, { cursor: null, status: "unloaded", complete: false, refreshError: null });
      await fetchLevel(parent);
    } else {
      if (target.staging) halt(target.staging);
      staged = newLevel(); target.staging = staged;
      await fetchLevel(parent, false, { staged });
    }
    // Descend only from the level THIS reload committed: a superseded, cancelled or failed one stops here.
    if (levels.get(parent) !== target || (staged ? target.nodes !== staged.nodes : !["complete", "more"].includes(target.status))) return;
    const children = Array.from(target.nodes.values()).filter(node => node.kind === "folder" && levels.has(node.key));
    await Promise.all(children.map(node => reload(node.key)));
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
    /** Continue a level the page ceiling stopped, with a fresh page budget. */
    more(parent = null) { return fetchLevel(parent, !!query, { resume: true }); },
    async refresh(parent = null) {
      if (!query) { await reload(parent); return; }
      if (!search.nodes.size) { cancel(search); search = newLevel(); await fetchLevel(null, true); return; }
      if (search.staging) halt(search.staging);
      const staged = newLevel(); search.staging = staged;
      await fetchLevel(null, true, { staged });
    },
    async update(next) {
      cancelAll(); epoch += 1; source = next.source || source; context = next.context && typeof next.context === "object" ? { ...next.context } : next.context; root = next.root || "";
      query = ""; open.clear(); folded.clear(); levels.clear(); search = newLevel(); changed(); await fetchLevel(null);
    },
    dispose() { disposed = true; epoch += 1; cancelAll(); },
  };
  return state;
}
