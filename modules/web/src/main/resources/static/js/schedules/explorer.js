/*
 * #9 slice 2 — the Schedules explorer: the LEFT pane (ui-screens.md §4.20).
 *
 * The same tree the pipelines and templates explorers draw (§4.3), with one difference that
 * rest-api §20 imposes: §20.1 answers a folder's whole SUBTREE by `prefix`, not one level, so
 * each level's direct sub-folders are derived here from the names (model.buildLevel). Each
 * level is still ONE request, made when its folder first opens — the root without a prefix,
 * `finance` with `prefix=finance` — so an opened folder shows the server's current answer.
 *
 * Search is the explorers' rule: text in the box replaces the tree with a flat list of full
 * paths matching every column the list renders (§5.1); clearing it returns the tree.
 *
 * Selection, focus and the keyboard are template-explorer.js's (the pane carries
 * `data-explorer-pane`); this file re-runs its init after each render so new rows join the
 * roving tabindex. A leaf's click is what loads the detail — the keyboard layer's debounced
 * load is that same click.
 */
(function () {
  "use strict";

  var S = (window.DpSchedules = window.DpSchedules || {});
  var M = function () { return window.DpSchedulesModel; };
  var API = function () { return window.DpSchedulesApi; };

  function listRoot() {
    return document.getElementById("schedule-list");
  }

  function reinitKeyboard() {
    if (window.templateExplorer && window.templateExplorer.init) window.templateExplorer.init();
  }

  function conditionBadge(el, schedule) {
    var badge = S.slot(el, "condition");
    if (!badge) return;
    var c = schedule.condition;
    badge.className = "ds-badge " + M().conditionBadge(c);
    badge.textContent = c === "enabled" ? "" : c;
    badge.hidden = c === "enabled";
  }

  function leafFor(schedule, level) {
    var li = S.clone("sch-tpl-leaf");
    var button = li.querySelector(".tpl-leaf");
    var prefix = level ? level + "/" : "";
    S.text(li, "label", schedule.name.substring(prefix.length)).setAttribute("title", schedule.name);
    button.setAttribute("data-leaf-id", schedule.id);
    button.setAttribute("data-leaf-name", schedule.name);
    button.setAttribute("data-editor-url", S.url(schedule.id));
    if (S.state.selectedId === schedule.id) button.setAttribute("aria-selected", "true");
    conditionBadge(li, schedule);
    return li;
  }

  function folderFor(folder) {
    var li = S.clone("sch-tpl-folder");
    S.text(li, "label", folder.segment).setAttribute("title", folder.path);
    S.text(li, "count", folder.count);
    li.querySelector("details").setAttribute("data-folder", folder.path);
    return li;
  }

  /** One level's list: folders, then leaves (the trees' order). */
  function levelList(level, prefix) {
    var ul = document.createElement("ul");
    ul.className = "tpl-tree";
    if (!prefix) {
      ul.setAttribute("role", "tree");
      ul.setAttribute("aria-label", "Schedules");
    }
    level.folders.forEach(function (f) { ul.appendChild(folderFor(f)); });
    level.leaves.forEach(function (s) { ul.appendChild(leafFor(s, prefix)); });
    return ul;
  }

  function busy(el, on) {
    if (!el) return;
    if (on) el.setAttribute("aria-busy", "true");
    else el.removeAttribute("aria-busy");
  }

  function errorBlock(err, title) {
    var block = S.clone("sch-tpl-error");
    if (title) S.text(block, "title", title);
    S.text(block, "message", err.userMessage || err.message);
    S.text(block, "reference", [err.code, err.correlationId ? "ref " + err.correlationId : ""].filter(Boolean).join(" · "));
    return block;
  }

  // ------------------------------------------------------------------ the root and search

  function render() {
    var root = listRoot();
    if (!root || !S.state.all) return;
    S.clear(root);
    busy(root, false);
    var query = S.state.query || "";
    if (query) {
      renderSearch(root, query);
    } else if (!S.state.all.length) {
      root.appendChild(S.clone("sch-tpl-empty"));
    } else {
      var reopen = S.state.openFolders || {};
      root.appendChild(levelList(M().buildLevel(S.state.all, ""), ""));
      // A re-render (after a create, a rename, a delete) re-opens what the reader had open —
      // each opening re-reads that folder, so the tree never shows a stale level.
      Array.prototype.forEach.call(root.querySelectorAll("details.tpl-folder"), function (d) {
        if (reopen[d.getAttribute("data-folder")]) d.open = true;
      });
    }
    reinitKeyboard();
  }

  function renderSearch(root, query) {
    var hits = M().search(S.state.all, query);
    if (!hits.length) {
      var none = S.clone("sch-tpl-no-match");
      S.text(none, "title", "No schedules match “" + query + "”");
      root.appendChild(none);
      return;
    }
    var ul = document.createElement("ul");
    ul.className = "tpl-results";
    ul.setAttribute("role", "listbox");
    ul.setAttribute("aria-label", "Search results");
    hits.forEach(function (s) {
      var li = S.clone("sch-tpl-result");
      var button = li.querySelector(".tpl-result");
      S.text(li, "path", s.name).setAttribute("title", s.name);
      button.setAttribute("data-leaf-id", s.id);
      button.setAttribute("data-leaf-name", s.name);
      button.setAttribute("data-editor-url", S.url(s.id));
      if (S.state.selectedId === s.id) button.setAttribute("aria-selected", "true");
      conditionBadge(li, s);
      ul.appendChild(li);
    });
    root.appendChild(ul);
  }

  /** §20.1 without a prefix: every schedule the reader may see (the lens applies server-side). */
  function load() {
    var root = listRoot();
    busy(root, true);
    return API()
      .listAll("")
      .then(function (items) {
        if (!S.live()) return;
        S.state.all = items;
        render();
        if (S.form && S.form.refreshFolders) S.form.refreshFolders();
      })
      .catch(function (err) {
        if (!S.live()) return;
        S.clear(root);
        busy(root, false);
        root.appendChild(errorBlock(err));
      });
  }

  // ------------------------------------------------------------------ folders

  /** The `toggle` listener (capture — it does not bubble): an opening folder reads its subtree. */
  function onToggle(event) {
    var details = event.target;
    if (!details || details.tagName !== "DETAILS" || !details.classList.contains("tpl-folder")) return;
    var path = details.getAttribute("data-folder");
    if (!path) return;
    S.state.openFolders = S.state.openFolders || {};
    if (!details.open) {
      delete S.state.openFolders[path];
      return;
    }
    S.state.openFolders[path] = true;
    if (details.getAttribute("data-loaded") === "true") return;
    details.setAttribute("data-loaded", "true");
    var pending = details.querySelector(":scope > .tpl-level");
    API()
      .listAll(path)
      .then(function (items) {
        if (!S.live() || !details.isConnected) return;
        var level = document.createElement("div");
        level.className = "tpl-level";
        level.setAttribute("role", "group");
        level.appendChild(levelList(M().buildLevel(items, path), path));
        details.replaceChild(level, pending);
        // A folder the reader had open (or that holds the selection) opens again as its parent
        // lands — each opening is its own read.
        Array.prototype.forEach.call(level.querySelectorAll("details.tpl-folder"), function (d) {
          if (S.state.openFolders[d.getAttribute("data-folder")]) d.open = true;
        });
        reinitKeyboard();
      })
      .catch(function (err) {
        if (!details.isConnected) return;
        details.setAttribute("data-loaded", "false");
        busy(pending, false);
        S.clear(pending);
        pending.appendChild(errorBlock(err, "This folder could not be loaded"));
      });
  }

  // ------------------------------------------------------------------ rows the detail changes

  /** A schedule's condition changed (pause, resume, unblock, a block): its rows follow. */
  function updateRow(schedule) {
    (S.state.all || []).forEach(function (s, i) {
      if (s.id === schedule.id) S.state.all[i] = schedule;
    });
    Array.prototype.forEach.call(
      document.querySelectorAll('#schedule-tree-pane [data-leaf-id="' + schedule.id + '"]'),
      function (button) { conditionBadge(button.parentNode, schedule); },
    );
  }

  /** Opens the folders down to a schedule's leaf, so a deep-linked selection is visible in the tree. */
  function reveal(name) {
    var parts = String(name || "").split("/");
    S.state.openFolders = S.state.openFolders || {};
    for (var i = 1; i < parts.length; i++) S.state.openFolders[parts.slice(0, i).join("/")] = true;
    if (!S.state.query) render();
  }

  function markSelected(scheduleId) {
    S.state.selectedId = scheduleId;
    Array.prototype.forEach.call(document.querySelectorAll("#schedule-tree-pane [data-leaf-id]"), function (b) {
      b.setAttribute("aria-selected", b.getAttribute("data-leaf-id") === scheduleId ? "true" : "false");
    });
    reinitKeyboard();
  }

  function setQuery(text) {
    S.state.query = String(text || "").trim();
    render();
  }

  S.explorer = {
    load: load,
    render: render,
    onToggle: onToggle,
    updateRow: updateRow,
    markSelected: markSelected,
    reveal: reveal,
    setQuery: setQuery,
  };
})();
