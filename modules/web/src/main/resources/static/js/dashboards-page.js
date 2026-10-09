/*
 * dashboards-page.js (#10 L3b) — the board page's glue. The CSP allows no inline script, so
 * this FILE is the whole host: it reads the two data attributes the server wrote (the
 * dashboard id on the container; nothing else crosses into script data), boots the vendored
 * runtime with the first-party composite adapter and the session credential, and renders the
 * page's notification/refusal regions.
 *
 * Deliberately small: it owns NO protocol (the runtime does), NO chart rendering (the
 * adapters do), NO polling (the dock's History partial re-fetches itself over htmx). What it
 * owns is the page contract:
 *
 *   - init with `credentials: "session"` and `version: "released"` — the released view's only
 *     value — or, since #369, the integer the preview page wrote into
 *     `data-dp-dashboard-version` (a DRAFT or named RELEASED version; dashboards.md §5.2/§7).
 *     The version crosses the SAME data-attribute channel the id rides; nothing else crosses
 *     into script data.
 *   - options.onNotification is the page's sink: notifications land in window.__dpPage AND,
 *     for a boot failure, in the refusal region the server pre-rendered.
 *   - options.onStreamEvent is the #473 dock's feed: the runtime's `scope: "dashboard"`
 *     summaries land in the activity dock (events-dock.js's log); everything else is ignored.
 *   - the #473 filters DRAWER at narrow widths (open/close/Escape, aria-expanded, focus in
 *     and back; closed when the Board tab is left) and the #473 activity DOCK (tabs, filters,
 *     clear, collapse, splitter).
 *   - window.__dpPage is the page's test seam (window.__dp is the conformance host's): ready,
 *     error, code, notifications, instance, dock — the same shape, so a reader knows one.
 *
 * Disposal runs at history snapshot creation after navigation admission. The snapshot holds
 * inert shell markup; the persistent host replays this page mount against singleton libraries.
 * ONE history listener disposes the live instance and retains the runtime's refresh cancellation.
 * #473: the persistent shell never unloads the document, so every listener a mount puts on
 * `document` or `window` (the drawer's Escape, the splitter's drag, the tab-leave observer) is
 * registered through that mount's `listen` and removed by its `teardown`, which the same
 * history listener runs — a board revisited in-shell holds exactly one set, never one per visit.
 * Listeners on the board's own elements leave with the swapped-out markup.
 */
(function () {
  "use strict";

  function mount(main) {
  var container = main && main.querySelector ? main.querySelector("#dp-board") : document.getElementById("dp-board");
  if (!container || container.__dpPageMounted) return;
  container.__dpPageMounted = true;
  // A previous board whose leave did not pass the history save (no snapshot was taken) still
  // owns document-level listeners: retire them before this mount registers its own.
  var previous = window.__dpPage;
  if (previous && typeof previous.teardown === "function") previous.teardown();
  var cleanups = [];
  var pageState = {
    ready: false,
    error: null,
    code: null,
    notifications: [],
    instance: null,
    dock: null,
    teardown: function () {
      while (cleanups.length > 0) {
        try {
          cleanups.pop()();
        } catch (e) {
          /* one failing cleanup must not keep the others */
        }
      }
    },
  };
  window.__dpPage = pageState;

  // Registers a listener this mount owns; `teardown` removes it. Returns its own remover for a
  // listener that ends earlier (a drag ends on pointerup).
  function listen(target, type, handler) {
    target.addEventListener(type, handler);
    var removed = false;
    function remove() {
      if (removed) return;
      removed = true;
      target.removeEventListener(type, handler);
    }
    cleanups.push(remove);
    return remove;
  }

  var id = container.getAttribute("data-dp-dashboard-id");
  if (!id) return;
  // #369 — the draft preview names its version in the same data attribute channel the id rides
  // (an integer attribute, never a script body); the released view writes none and stays on
  // "released", the only value the first-party page may pass beside a number.
  var versionAttribute = container.getAttribute("data-dp-dashboard-version");
  var version = "released";
  if (versionAttribute !== null && versionAttribute !== "") {
    version = parseInt(versionAttribute, 10);
    // A version attribute that is not an integer is the server's defect, never a bootable page.
    if (!isFinite(version)) return;
  }

  var runtime = window.DatapipelinesDashboard;
  if (!runtime) return;

  // The board's own region: every element lookup below is scoped to the main this mount owns.
  var scope = main && main.querySelector ? main : document;

  // The snapshot must never carry a mounted container OR Plotly's scratch: dispose BEFORE
  // htmx saves history, and strip the tester svg. The tester (#js-plotly-tester) is a BODY
  // child OUTSIDE the boosted swap region, so it survives every swap and would ride the
  // snapshot; htmx's restore settle then re-applies its style ATTRIBUTE — a style-src-attr
  // refusal (the pages' suite measured it; the conformance host has no htmx and could not).
  // Plotly recreates the tester on its next render; a runtime that ever cleans up after
  // itself retires this line. One listener per document; a failing dispose must not break
  // the save. #473: it also runs the live mount's teardown (its document/window listeners).
  if (!window.__dpBoardHistoryCleanup) {
  window.__dpBoardHistoryCleanup = true;
  document.body.addEventListener("htmx:beforeHistorySave", function () {
    if (window.__dpPage.instance) {
      try {
        window.__dpPage.instance.dispose();
      } catch (e) {
        /* the save proceeds either way */
      }
      window.__dpPage.instance = null;
    }
    if (typeof window.__dpPage.teardown === "function") window.__dpPage.teardown();
    var tester = document.getElementById("js-plotly-tester");
    if (tester && tester.parentElement) tester.parentElement.removeChild(tester);
  });

  }

  /* ---- #473 — the filters drawer ----------------------------------------------------- */

  var filtersPanel = scope.querySelector("#dp-board-filters");
  var filtersTrigger = scope.querySelector("#dp-board-filters-trigger");
  var filtersClose = scope.querySelector("#dp-board-filters-close");
  var drawerOpen = false;

  function drawerSet(open, moveFocus) {
    if (!filtersPanel || !filtersTrigger || open === drawerOpen) return;
    drawerOpen = open;
    if (open) filtersPanel.classList.add("is-open");
    else filtersPanel.classList.remove("is-open");
    filtersTrigger.setAttribute("aria-expanded", String(open));
    if (moveFocus === false) return;
    // The focus moves WITH the surface: in to its first control, back to the trigger that
    // opened it. Focus must exist for this to mean anything — the try keeps a hostile DOM
    // from taking the page down.
    try {
      if (open) {
        (filtersClose || filtersPanel).focus();
      } else {
        filtersTrigger.focus();
      }
    } catch (e) {
      /* focus is best-effort */
    }
  }

  if (filtersTrigger && filtersClose && filtersPanel) {
    filtersTrigger.addEventListener("click", function () {
      drawerSet(!drawerOpen);
    });
    filtersClose.addEventListener("click", function () {
      drawerSet(false);
    });
    listen(document, "keydown", function (e) {
      if (e && e.key === "Escape") drawerSet(false);
    });
    // A history snapshot never carries an open drawer: the restored copy mounts closed.
    cleanups.push(function () {
      drawerSet(false, false);
    });
    // Leaving the Board tab closes the drawer without pulling focus into a hidden pane: the
    // workspace hides the pane through its `hidden` attribute.
    var boardPane = scope.querySelector("#dp-pane-board");
    if (boardPane && typeof window.MutationObserver === "function") {
      var paneObserver = new window.MutationObserver(function () {
        if (boardPane.hidden) drawerSet(false, false);
      });
      paneObserver.observe(boardPane, { attributes: true, attributeFilter: ["hidden"] });
      cleanups.push(function () {
        paneObserver.disconnect();
      });
    }
  }

  // No visible parameter (none declared, or every one hidden by the server's state) means no
  // panel and no Filters trigger: the page carries `data-dp-filters="none"` (the server renders
  // it so), the CSS reserves no column, and the board takes the width. The glue re-reads the
  // pane after every render the runtime makes (rows hide through their own display), and a
  // change re-fits the mounted charts once laid out — the board's width moved, not the window's.
  var boardPage = scope.querySelector(".dp-board-page");
  function syncFiltersPresence() {
    if (!boardPage || !filtersPanel) return;
    var rows = filtersPanel.querySelectorAll(".dp-dashboard-parameter");
    var visible = false;
    for (var r = 0; r < rows.length && !visible; r++) {
      visible = !rows[r].hidden && rows[r].style.display !== "none";
    }
    var next = visible ? "some" : "none";
    if (boardPage.getAttribute("data-dp-filters") === next) return;
    boardPage.setAttribute("data-dp-filters", next);
    if (!visible) drawerSet(false, false);
    var refit = function () {
      if (!pageState.instance || typeof pageState.instance.resize !== "function") return;
      try {
        pageState.instance.resize();
      } catch (e) {
        /* a renderer's failed re-fit leaves the others laid out */
      }
    };
    if (typeof window.requestAnimationFrame === "function") window.requestAnimationFrame(refit);
    else setTimeout(refit, 0);
  }
  if (boardPage && filtersPanel && typeof window.MutationObserver === "function") {
    var filtersObserver = new window.MutationObserver(syncFiltersPresence);
    filtersObserver.observe(filtersPanel, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["style", "hidden"],
    });
    cleanups.push(function () {
      filtersObserver.disconnect();
    });
  }
  syncFiltersPresence();

  /* ---- #473 — the activity dock ------------------------------------------------------- */

  // The dock's wiring: the log (events-dock.js) holds the events; `render` rebuilds the two
  // live lists from it (Events obeys the severity select, Errors is the severity=error view —
  // the kind select narrows both); the count badges and the omission foot are derived, never
  // incremented. Rows are built with textContent only. The tail follows while the reader sits
  // at the bottom and yields the moment they scroll up (the pipeline editor's contract).
  function wireDock() {
    var dock = scope.querySelector("#dp-board-dock");
    if (!dock || !window.DPDock) return null;
    var dockLog = window.DPDock.createDashboardEventLog({ limit: window.DPDock.DEFAULT_LIMIT });
    var body = dock.querySelector("[data-dp-dock-body]");
    var lists = {
      events: dock.querySelector('[data-dp-dock-list="events"]'),
      errors: dock.querySelector('[data-dp-dock-list="errors"]'),
    };
    var empties = {
      events: dock.querySelector('[data-dp-dock-empty="events"]'),
      errors: dock.querySelector('[data-dp-dock-empty="errors"]'),
    };
    var counts = {
      events: dock.querySelector('[data-dp-dock-count="events"]'),
      errors: dock.querySelector('[data-dp-dock-count="errors"]'),
    };
    var foot = dock.querySelector("[data-dp-dock-foot]");
    var severitySelect = dock.querySelector('[data-dp-dock-filter="severity"]');
    var kindSelect = dock.querySelector('[data-dp-dock-filter="kind"]');
    var stick = true;

    function timeText(at) {
      var d = new Date(Number(at) || 0);
      if (isNaN(d.getTime())) return "";
      var pad = function (n) {
        return (n < 10 ? "0" : "") + n;
      };
      return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    function buildRow(event) {
      var li = document.createElement("li");
      li.className = "dp-board-event";
      var severity = window.DPDock.severityOf(event);
      var time = document.createElement("span");
      time.className = "dp-board-event-time";
      time.textContent = timeText(event.at);
      var marker = document.createElement("span");
      marker.className = "dp-board-event-severity";
      marker.setAttribute("data-dp-severity", severity);
      marker.textContent = severity;
      var kind = document.createElement("span");
      kind.className = "dp-board-event-kind";
      kind.textContent = window.DPDock.kindOf(event);
      var text = document.createElement("span");
      text.className = "dp-board-event-text";
      text.textContent = window.DPDock.formatEvent(event);
      li.appendChild(time);
      li.appendChild(marker);
      li.appendChild(kind);
      li.appendChild(text);
      if (event.refresh_id) {
        var rid = document.createElement("span");
        rid.className = "dp-board-event-id";
        rid.textContent = window.DPDock.shortId(event.refresh_id);
        li.appendChild(rid);
      }
      return li;
    }

    function fillList(listElement, emptyElement, filters) {
      if (!listElement) return 0;
      while (listElement.firstChild) listElement.removeChild(listElement.firstChild);
      var shown = 0;
      var all = dockLog.all();
      for (var i = 0; i < all.length; i++) {
        if (window.DPDock.matchesFilters(all[i], filters)) {
          listElement.appendChild(buildRow(all[i]));
          shown += 1;
        }
      }
      if (emptyElement) emptyElement.hidden = shown > 0;
      return shown;
    }

    function render() {
      var kind = kindSelect ? kindSelect.value : "";
      var eventsShown = fillList(lists.events, empties.events, {
        severity: severitySelect ? severitySelect.value : "",
        kind: kind,
      });
      var errorsShown = fillList(lists.errors, empties.errors, { severity: "error", kind: kind });
      if (counts.events) {
        counts.events.textContent = String(eventsShown);
        counts.events.setAttribute("data-dp-count-zero", eventsShown === 0 ? "true" : "false");
      }
      if (counts.errors) {
        counts.errors.textContent = String(errorsShown);
        counts.errors.setAttribute("data-dp-count-zero", errorsShown === 0 ? "true" : "false");
      }
      if (foot) {
        var dropped = dockLog.droppedTotal();
        foot.hidden = dropped === 0;
        if (dropped > 0) foot.textContent = "Older live events omitted: " + dropped + ".";
      }
    }

    // The tabs: one active pane, aria-selected on the tab — the workspace tab strip's shape.
    var tabs = dock.querySelectorAll("[data-dp-dock-tab]");
    var panes = dock.querySelectorAll("[data-dp-dock-pane]");
    function switchTab(name) {
      dock.setAttribute("data-dp-dock-active", name);
      for (var t = 0; t < tabs.length; t++) {
        tabs[t].setAttribute("aria-selected", tabs[t].getAttribute("data-dp-dock-tab") === name ? "true" : "false");
      }
      for (var p = 0; p < panes.length; p++) {
        panes[p].hidden = panes[p].getAttribute("data-dp-dock-pane") !== name;
      }
    }
    for (var t = 0; t < tabs.length; t++) {
      (function (tab) {
        tab.addEventListener("click", function () {
          switchTab(tab.getAttribute("data-dp-dock-tab"));
        });
      })(tabs[t]);
    }

    if (severitySelect) severitySelect.addEventListener("change", render);
    if (kindSelect) kindSelect.addEventListener("change", render);

    var clearButton = dock.querySelector("[data-dp-dock-clear]");
    if (clearButton) {
      clearButton.addEventListener("click", function () {
        // A VIEW action: the runtime keeps streaming; only this dock forgets.
        dockLog.clear();
        stick = true;
        render();
      });
    }

    var collapseButton = dock.querySelector("[data-dp-dock-collapse]");
    if (collapseButton) {
      collapseButton.addEventListener("click", function () {
        var collapsed = dock.hasAttribute("data-dp-dock-collapsed");
        if (collapsed) dock.removeAttribute("data-dp-dock-collapsed");
        else dock.setAttribute("data-dp-dock-collapsed", "");
        collapseButton.setAttribute("aria-expanded", String(!collapsed));
      });
    }

    var splitter = dock.querySelector("[data-dp-dock-resize]");
    if (splitter && body && typeof window.addEventListener === "function") {
      // One drag at a time: its two window listeners belong to this mount (`listen`), so a
      // navigation in the middle of a drag cannot leave them behind.
      var endDrag = null;
      splitter.addEventListener("pointerdown", function (e) {
        if (endDrag) endDrag();
        var startY = e.clientY;
        var startHeight = body.getBoundingClientRect().height;
        dock.removeAttribute("data-dp-dock-collapsed");
        collapseButton && collapseButton.setAttribute("aria-expanded", "true");
        var removeMove = listen(window, "pointermove", function (ev) {
          // Drag UP grows the pane; the bounds keep the dock a dock.
          var height = Math.min(480, Math.max(96, startHeight + (startY - ev.clientY)));
          dock.style.setProperty("--dp-dock-pane-h", Math.round(height) + "px");
        });
        var removeUp = listen(window, "pointerup", function () {
          if (endDrag) endDrag();
        });
        endDrag = function () {
          removeMove();
          removeUp();
          endDrag = null;
        };
        e.preventDefault();
      });
    }

    // The drag writes the pane height through the CSSOM; a history snapshot would serialise it
    // as a `style` attribute the CSP refuses on Back (#287), so the teardown strips it first.
    cleanups.push(function () {
      dock.style.removeProperty("--dp-dock-pane-h");
      if (dock.getAttribute("style") === "") dock.removeAttribute("style");
    });

    if (body && typeof body.addEventListener === "function") {
      body.addEventListener("scroll", function () {
        stick = body.scrollTop + body.clientHeight >= body.scrollHeight - 8;
      });
    }

    render();
    return {
      append: function (event) {
        // `at` is ARRIVAL time — the witness payload carries no clock of its own.
        var record = {};
        for (var key in event) {
          if (Object.prototype.hasOwnProperty.call(event, key)) record[key] = event[key];
        }
        record.at = Date.now();
        dockLog.add(record);
        render();
        if (stick && body) body.scrollTop = body.scrollHeight;
      },
      clear: function () {
        dockLog.clear();
        render();
      },
      render: render,
      size: function () {
        return dockLog.size();
      },
      dropped: function () {
        return dockLog.droppedTotal();
      },
    };
  }

  var dockApi = wireDock();
  pageState.dock = dockApi;

  function refusalRegion() {
    return document.getElementById("dp-board-refusal");
  }

  // The refusal state is the in-page answer to a board that cannot run: the server fills it
  // when the configuration read refused; the sink fills it when a later bootstrap step fails
  // (the runtime's `ready` rejection names the failing step and the family's code). Both put
  // a code and a sentence where the person is looking, never a blank pane.
  function showRefusal(code, message) {
    if (main && (!main.isConnected || main.querySelector("#dp-board") !== container)) return;
    var region = refusalRegion();
    if (!region) return;
    var text = document.getElementById("dp-board-refusal-message");
    var codeEl = document.getElementById("dp-board-refusal-code");
    if (text && message) text.textContent = message;
    if (codeEl && code) codeEl.textContent = code;
    region.hidden = false;
  }

  try {
    var instance = runtime.init({
      server: { baseUrl: "", credentials: "session" },
      dashboard: { id: id, version: version },
      container: container,
      adapter: runtime.adapters(container, {
        // #473 — the parameters pane mounts in the LEFT panel (outside the grid container),
        // the drawer's content at narrow widths.
        parametersContainer: filtersPanel,
      }),
      options: {
        onNotification: function (n) {
          pageState.notifications.push(n);
        },
        onStreamEvent: function (event) {
          if (!event || event.scope !== "dashboard") return;
          if (dockApi) dockApi.append(event);
        },
      },
    });
    pageState.instance = instance;
    instance.ready
      .then(function () {
        pageState.ready = true;
      })
      .catch(function (error) {
        pageState.error = error;
        pageState.code = error && error.code ? error.code : String(error);
        showRefusal(pageState.code, error && error.message ? error.message : String(error));
      });
  } catch (e) {
    pageState.code = e && e.code ? e.code : e.name;
    pageState.error = String(e);
    showRefusal(pageState.code, e && e.message ? e.message : String(e));
  }
  }
  window.DashboardPageMount = mount;
  var main = document.getElementById("app-main");
  if (!window.DatapipelinesPageMountManaged && (!main || !main.querySelector("template[data-chart-assets]"))) mount(main);
})();
