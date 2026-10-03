(function () {
  "use strict";

  /*
   * #402 — the workspaces' OWN history entries, beside the shell's htmx-owned ones: an
   * in-page version or tab switch pushes an entry, and Back/Forward re-apply it IN PAGE (no
   * document reload, no re-activation, an active run's stream untouched). Clients: the
   * pipeline editor (init.js — version + tab) and the dashboard workspace
   * (dashboards/workspace.js — tab only; its version switch stays a full navigation).
   *
   * How the entries coexist with htmx 2.0.10's history (read in the vendored build):
   *
   *  - htmx's `window.onpopstate` restores ONLY an entry whose state carries `htmx: true`
   *    and hands every other entry to the previous handler. Ours carry `{dpWorkspace:
   *    {family, version, tab}}` and nothing else — no parameter value, ever (pipeline
   *    workspace spec: "canonical version/tab state may enter navigation URLs; parameter
   *    values may not") — so htmx ignores them and ONE window listener (this file's)
   *    replays them.
   *  - The first push CONVERTS the current entry when it is not ours (htmx's `{htmx:true}`
   *    or the document's null): Back must land on an entry we replay, not on an htmx entry
   *    whose snapshot was never written (htmx would re-render it).
   *  - htmx keys its next snapshot by its OWN record of the current path —
   *    `sessionStorage["htmx-current-path-for-history"]`, written only by its push/replace/
   *    restore. After every push, replace and replay this file writes it to THIS document's
   *    `location.pathname + location.search`, so a boost-away snapshot is stored under the
   *    URL the user returns to (Back is a cache HIT, not a server restore). An INTERNAL key of
   *    htmx 2.0.10: workspace-history.test.mjs counts the literal in the vendored build and
   *    goes red when an upgrade renames it.
   *  - An entry of ours that pops while the page shows ANOTHER screen (Forward from the list
   *    onto a workspace entry; another pipeline's workspace) cannot be replayed in place: the
   *    client's `apply` answers false and the entry is handed to htmx's own restore — exactly
   *    what htmx would have done with the entry before it was ours.
   *
   * The registry lives on `window` (one listener per document, never per root): a boosted
   * arrival or a cached restore re-runs a family's glue, and a per-root listener would stay
   * bound to a detached root. `apply` is resolved AT EVENT TIME from the registry, so the
   * live instance answers, never the one that registered first.
   *
   * The core (entryState, urlFor, entryFor) is pure for `node --test`; push/listen take an
   * optional `win` so the harness drives them over a fake window.
   */

  var STATE_KEY = "dpWorkspace";
  /** htmx 2.0.10's own current-path record (see above) — a drift-pinned internal. */
  var HTMX_CURRENT_PATH_KEY = "htmx-current-path-for-history";
  var REGISTRY = "__dpWorkspaceHistory";

  /** The ONE state shape an entry of ours carries: family, version, tab — nothing else. */
  function entryState(family, version, tab) {
    return {
      dpWorkspace: {
        family: String(family),
        version: typeof version === "number" && isFinite(version) ? version : null,
        tab: typeof tab === "string" ? tab : null,
      },
    };
  }

  /** The entry's record when [state] is OUR entry for [family]; null otherwise. */
  function entryFor(state, family) {
    var entry = state && typeof state === "object" ? state[STATE_KEY] : null;
    if (!entry || typeof entry !== "object" || entry.family !== family) return null;
    return entry;
  }

  /**
   * The canonical URL of a view: the same path, every other query parameter kept, `version`
   * set when given, `tab` set — or REMOVED when it is [defaultTab] (the pipeline page's
   * canonical form; a family without a default always states its tab). Relative, no
   * fragment — exactly what the pipeline editor's replaceState wrote before #402.
   */
  function urlFor(href, version, tab, defaultTab) {
    var url = new URL(href || "/", "http://workspace.invalid");
    var params = new URLSearchParams(url.search);
    if (typeof version === "number" && isFinite(version)) params.set("version", String(version));
    if (typeof tab === "string" && tab !== "") {
      if (defaultTab !== undefined && tab === defaultTab) params.delete("tab");
      else params.set("tab", tab);
    }
    var qs = params.toString();
    return url.pathname + (qs ? "?" + qs : "");
  }

  function windowOf(win) {
    if (win) return win;
    return typeof window !== "undefined" ? window : null;
  }

  /** htmx's current-path record follows THIS document's location (never a state value). */
  function syncHtmxPath(win) {
    var w = windowOf(win);
    if (!w || !w.location) return;
    try {
      if (w.sessionStorage) w.sessionStorage.setItem(HTMX_CURRENT_PATH_KEY, w.location.pathname + w.location.search);
    } catch (e) {
      /* storage refused: htmx falls back to its in-memory record, nothing to keep in step */
    }
  }

  function sameView(a, b) {
    return !!a && !!b && a.version === b.version && a.tab === b.tab;
  }

  /**
   * A USER's view change, [from] → [to] (each `{version, tab}`): pushes an entry for [to] —
   * converting the current entry to [from] first when it is not ours — or, when the view did
   * not change (a re-selected tab, the viewed version re-opened), only re-states the current
   * entry. Never called for a replay: a popstate re-applies, it does not mint.
   *
   * @param {Object} [options]  `defaultTab` (the canonical URL's omitted tab), `win`
   * @return {string} "push" | "replace" | "none"
   */
  function push(family, from, to, options) {
    var opts = options || {};
    var w = windowOf(opts.win);
    var h = w && w.history;
    if (!h || typeof h.pushState !== "function" || !to) return "none";
    var next = entryState(family, to.version, to.tab);
    var url = urlFor(w.location.href, next.dpWorkspace.version, next.dpWorkspace.tab, opts.defaultTab);
    var current = from ? entryState(family, from.version, from.tab).dpWorkspace : null;
    var verb;
    if (sameView(current, next.dpWorkspace)) {
      h.replaceState(next, "", url);
      verb = "replace";
    } else {
      if (!entryFor(h.state, family) && current) {
        h.replaceState(entryState(family, current.version, current.tab), "", w.location.href);
      }
      h.pushState(next, "", url);
      verb = "push";
    }
    syncHtmxPath(w);
    return verb;
  }

  function registry(w) {
    if (!w[REGISTRY]) w[REGISTRY] = { handlers: {}, listeners: 0, replayed: {}, handedToHtmx: 0 };
    return w[REGISTRY];
  }

  /** Hands an entry we cannot replay in place to htmx's own restore (cache, else server). */
  function handToHtmx(w) {
    var reg = registry(w);
    reg.handedToHtmx = reg.handedToHtmx + 1;
    if (typeof w.onpopstate === "function" && w.htmx) {
      w.onpopstate({ state: { htmx: true } });
    } else if (w.location && typeof w.location.reload === "function") {
      w.location.reload();
    }
  }

  /** One popstate: OUR entry for a registered family replays; everything else is htmx's. */
  function dispatch(w, state) {
    var entry = state && typeof state === "object" ? state[STATE_KEY] : null;
    if (!entry || typeof entry !== "object" || typeof entry.family !== "string") return;
    var reg = registry(w);
    var apply = Object.prototype.hasOwnProperty.call(reg.handlers, entry.family) ? reg.handlers[entry.family] : null;
    var replayed = false;
    if (typeof apply === "function") {
      try {
        replayed = apply(entry.version, entry.tab) === true;
      } catch (e) {
        replayed = false;
      }
    }
    if (replayed) {
      reg.replayed[entry.family] = (reg.replayed[entry.family] || 0) + 1;
      syncHtmxPath(w);
    } else {
      handToHtmx(w);
    }
  }

  /**
   * Registers [family]'s replay: `apply(version, tab)` re-applies the view IN PAGE and
   * answers true, or answers false when the live page is not that family's view of this URL.
   * Re-registering replaces the family's handler (the live root's); the window listener is
   * installed once per document.
   */
  function listen(family, apply, options) {
    var w = windowOf(options && options.win);
    if (!w || typeof w.addEventListener !== "function") return;
    var reg = registry(w);
    reg.handlers[family] = apply;
    if (reg.listeners === 0) {
      reg.listeners = 1;
      w.addEventListener("popstate", function (evt) {
        dispatch(w, evt ? evt.state : null);
      });
    }
  }

  /** Counters for the suites: listeners installed, replays per family, hand-offs to htmx. */
  function stats(win) {
    var w = windowOf(win);
    var reg = w && w[REGISTRY];
    if (!reg) return { listeners: 0, replayed: {}, handedToHtmx: 0 };
    var replayed = {};
    Object.keys(reg.replayed).forEach(function (k) {
      replayed[k] = reg.replayed[k];
    });
    return { listeners: reg.listeners, replayed: replayed, handedToHtmx: reg.handedToHtmx };
  }

  var api = {
    HTMX_CURRENT_PATH_KEY: HTMX_CURRENT_PATH_KEY,
    entryState: entryState,
    entryFor: entryFor,
    urlFor: urlFor,
    push: push,
    listen: listen,
    syncHtmxPath: syncHtmxPath,
    stats: stats,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.WorkspaceHistory = api;
})();
