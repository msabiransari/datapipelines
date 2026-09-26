/*
 * #9 slice 2 — the selected schedule: the RIGHT pane (ui-screens.md §4.20).
 *
 * Three §20 reads fill it — the schedule (§20.4, whose ETag is the revision every edit and the
 * delete must carry), its next five (§20.9) and its latest runs (§20.10) — and the verbs are
 * §20's own routes: pause/resume (§20.7), unblock (§20.8), Run now (§20.12, with a fresh
 * idempotency key per attempt), delete (§20.6, If-Match). A verb's success re-renders from the
 * schedule the server returned, never from a client guess; a refusal is a toast carrying the
 * catalog's message (§5.1).
 *
 * While any listed run is queued, starting or running, the runs are re-read every few seconds
 * so a Run now is seen to start and finish; the poll stops when nothing is active, when another
 * schedule is selected, or when the page is left.
 */
(function () {
  "use strict";

  var S = (window.DpSchedules = window.DpSchedules || {});
  var M = function () { return window.DpSchedulesModel; };
  var API = function () { return window.DpSchedulesApi; };

  var RUNS_PAGE = 20;
  var POLL_MS = 4000;

  function pane() {
    return document.getElementById("schedule-detail");
  }

  function current() {
    return S.state.current || null;
  }

  function setBusy(on) {
    var view = pane();
    S.show(S.slot(view, "busy"), on);
    Array.prototype.forEach.call(view.querySelectorAll("[data-verb]"), function (b) {
      if (on) b.setAttribute("aria-disabled", "true");
      else b.removeAttribute("aria-disabled");
    });
  }

  // ------------------------------------------------------------------ load

  function paneMessage(title, description) {
    var view = pane();
    S.clear(view);
    var t = document.createElement("p");
    t.className = "ds-empty-title";
    t.textContent = title;
    view.appendChild(t);
    if (description) {
      var d = document.createElement("p");
      d.className = "ds-empty-description";
      d.textContent = description;
      view.appendChild(d);
    }
    return view;
  }

  /**
   * Select and read a schedule. `after` runs once it is on screen (a deep-linked run opens
   * then). `quiet` re-reads the one already shown without blanking it first — a verb's
   * aftermath and a poll must not flash the pane.
   */
  function show(scheduleId, after, quiet) {
    var seq = (S.state.detailSeq = (S.state.detailSeq || 0) + 1);
    stopPoll();
    if (!quiet) {
      S.state.current = null;
      S.explorer.markSelected(scheduleId);
      S.remember(scheduleId);
    }
    var view = quiet ? pane() : paneMessage("Loading…", "");
    view.setAttribute("aria-busy", "true");
    return Promise.all([API().get(scheduleId), API().upcoming(scheduleId, 5), API().runs(scheduleId, 0, RUNS_PAGE)])
      .then(function (answers) {
        if (!S.live() || seq !== S.state.detailSeq) return;
        view.removeAttribute("aria-busy");
        var runs = answers[2].data || {};
        S.state.current = {
          schedule: answers[0].data,
          etag: answers[0].etag,
          occurrences: (answers[1].data || {}).occurrences || [],
          runs: runs.items || [],
          runsHasMore: !!(runs.pagination && runs.pagination.has_more),
        };
        render();
        if (window.explorerDetail && window.explorerDetail.setDrawer) window.explorerDetail.setDrawer(false);
        if (after) after();
      })
      .catch(function (err) {
        if (!S.live() || seq !== S.state.detailSeq) return;
        view.removeAttribute("aria-busy");
        if (err.status === 404) {
          paneMessage("Schedule not found", "No schedule with that id in this workspace — it may have been deleted.");
        } else {
          paneMessage("This schedule could not be loaded", (err.userMessage || err.message) + (err.correlationId ? " (ref " + err.correlationId + ")" : ""));
        }
      });
  }

  /** Re-read the selected schedule (after a verb, or when a poll saw its runs change). */
  function reload() {
    var c = current();
    if (!c) return Promise.resolve();
    return show(c.schedule.id, null, true);
  }

  // ------------------------------------------------------------------ render

  function render() {
    var c = current();
    var s = c.schedule;
    var view = pane();
    var content = S.clone("sch-tpl-detail");
    var names = M().splitName(s.name);
    S.text(content, "folder", names.folder).setAttribute("title", s.name);
    S.text(content, "leaf", names.leaf).setAttribute("title", s.name);
    content.setAttribute("data-schedule-id", s.id);

    renderVerbs(content, s);
    renderBlocked(content, s);
    renderOverview(content, s, c);
    renderOccurrences(content, s, c.occurrences);
    renderParameters(content, s);
    renderRuns(content, c);

    S.clear(view);
    view.appendChild(content);
    resolvePipelineLink(content, s);
    schedulePoll();
  }

  function renderVerbs(root, s) {
    // State decides which of the ROLE-rendered verbs apply (the skeleton holds none for a reader).
    S.show(root.querySelector('[data-verb="schedule-pause"]'), s.enabled && s.condition !== "blocked");
    S.show(root.querySelector('[data-verb="schedule-resume"]'), !s.enabled);
    // Run now is refused on a blocked schedule (§20.12) — the recovery verb is Unblock.
    S.show(root.querySelector('[data-verb="schedule-run"]'), s.condition !== "blocked");
  }

  function renderBlocked(root, s) {
    var box = S.slot(root, "blocked");
    var b = s.blocked;
    S.show(box, !!b);
    if (!b) return;
    S.text(root, "blocked-reason", M().blockedText(b.reason));
    S.text(root, "blocked-code", b.reason);
    S.time(root, "blocked-at", b.at, M().localText(b.at, s.timezone) + " (" + M().relativeText(b.at) + ")");
    var wrap = S.slot(root, "blocked-run-wrap");
    S.show(wrap, !!b.run_id);
    if (b.run_id) root.querySelector('[data-sch-action="open-blocking-run"]').setAttribute("data-run-id", b.run_id);
  }

  var POLICY_TEXT = {
    skip: "Skipped — an outage's missed occurrences are recorded, not run",
    latest: "The latest missed occurrence runs once, if it is less than a day old",
  };

  function renderOverview(root, s, c) {
    var cond = S.text(root, "condition", s.condition);
    cond.className = "ds-badge " + M().conditionBadge(s.condition);
    S.text(root, "executor", s.executor);
    S.text(root, "policy-chip", s.missed_run_policy === "latest" ? "catches up the latest" : "skips missed");
    S.text(root, "revision", "revision " + s.revision);
    S.text(root, "pipeline-text", M().pipelineOf(s));
    var lastPrepared = (c.runs || []).filter(function (r) { return r.prepared && r.prepared.version !== undefined; })[0];
    S.text(
      root,
      "version",
      "current version" + (lastPrepared ? " — the latest run used v" + lastPrepared.prepared.version +
        (lastPrepared.prepared.version_status ? " (" + String(lastPrepared.prepared.version_status).toLowerCase() + ")" : "") : ""),
    );
    var preset = M().describeCron(s.cron);
    S.text(root, "when", preset.preset === "custom" ? "Custom pattern" : preset.label);
    S.text(root, "cron", s.cron);
    S.text(root, "timezone", s.timezone);
    S.text(root, "policy", POLICY_TEXT[s.missed_run_policy] || s.missed_run_policy);
    if (s.condition !== "enabled") {
      S.text(root, "next-due", s.condition === "paused" ? "— paused: nothing is recorded until it resumes" : "— blocked: nothing fires until it is unblocked");
    } else if (s.next_due_at) {
      var due = S.text(root, "next-due", M().localText(s.next_due_at, s.timezone) + " (" + M().relativeText(s.next_due_at) + ")");
      due.setAttribute("title", s.next_due_at);
    } else {
      S.text(root, "next-due", "—");
    }
    var updated = S.text(root, "updated", M().relativeText(s.updated_at) + " · created " + M().relativeText(s.created_at));
    updated.setAttribute("title", "updated " + s.updated_at + " · created " + s.created_at);
  }

  /** The pipeline's link: its editor for a reader who may open it, else the explorer's search. */
  function resolvePipelineLink(root, s) {
    var name = M().pipelineOf(s);
    if (!name) return;
    var link = S.slot(root, "pipeline-link");
    var plain = S.slot(root, "pipeline-text");
    var canEdit = S.state.root.getAttribute("data-can-execute") === "true";
    var explorerUrl = "/pipelines?q=" + encodeURIComponent(name);
    function use(href) {
      if (!link.isConnected) return;
      link.textContent = name;
      link.setAttribute("href", href);
      link.hidden = false;
      plain.hidden = true;
    }
    if (!canEdit) {
      use(explorerUrl);
      return;
    }
    API()
      .findPipelines(name)
      .then(function (r) {
        var hit = ((r.data || {}).items || []).filter(function (p) { return p.name === name; })[0];
        // A link built here is not boosted (htmx processed the page before it existed), so the
        // editor is the full document load it must be (pipeline-detail.html's note).
        if (hit) {
          use("/pipelines/" + encodeURIComponent(hit.id) + "/editor");
        } else {
          use(explorerUrl);
        }
      })
      .catch(function () { use(explorerUrl); });
  }

  function renderOccurrences(root, s, occurrences) {
    var body = S.slot(root, "occurrences");
    S.text(root, "occurrences-note", s.condition === "enabled" ? "in " + s.timezone : "if it were running — " + s.condition);
    occurrences.forEach(function (o) {
      var row = S.clone("sch-tpl-occurrence");
      S.text(row, "local", M().occurrenceLocalText(o.local));
      S.text(row, "offset", M().offsetText(o.offset));
      S.text(row, "utc", M().shortText(o.at, "UTC")).setAttribute("title", o.at);
      row.setAttribute("data-at", o.at);
      body.appendChild(row);
    });
  }

  function renderParameters(root, s) {
    var params = s.parameters || {};
    var keys = Object.keys(params).sort();
    S.text(root, "parameters-note", keys.length === 1 ? "1 value" : keys.length + " values");
    S.show(S.slot(root, "parameters-empty"), !keys.length);
    S.show(S.slot(root, "parameters-wrap"), keys.length > 0);
    var body = S.slot(root, "parameters");
    keys.forEach(function (k) {
      var row = S.clone("sch-tpl-parameter");
      S.text(row, "name", k);
      S.text(row, "value", M().parameterText(params[k]));
      body.appendChild(row);
    });
  }

  // ------------------------------------------------------------------ runs

  function runRow(run, timezone) {
    var row = S.clone("sch-tpl-run-row");
    row.setAttribute("data-run-id", run.id);
    row.setAttribute("data-state", run.state);
    var chip = S.slot(row, "state");
    chip.className = "app-chip " + M().runChip(run.state);
    S.text(row, "state-text", M().runStateText(run.state));
    S.text(row, "origin", M().originText(run.origin));
    S.text(row, "reason", run.reason || "");
    // DUE is the occurrence; a Run now has none, so its row says when it was asked for.
    var dueIso = run.scheduled_at || run.reference_at;
    S.text(row, "due-label", run.scheduled_at ? "due" : "asked");
    S.time(row, "due", dueIso, M().shortText(dueIso, timezone));
    S.time(row, "started", run.started_at, M().shortText(run.started_at, timezone));
    S.text(row, "duration", durationFor(run));
    row.querySelector('[data-sch-action="open-run"]').setAttribute("data-run-id", run.id);
    if (run.execution_id) row.setAttribute("data-execution-id", run.execution_id);
    var exec = S.slot(row, "execution");
    if (exec && run.execution_id) {
      exec.setAttribute("href", "/executions/" + encodeURIComponent(run.execution_id));
      exec.hidden = false;
    }
    return row;
  }

  /**
   * How long the EXECUTION took — its own `duration_ms` (§10.2), read once per finished
   * execution and kept: a finished execution's duration never changes. The run's own stamps are
   * not used for this: §20's `finished_at` is when the reconciler RECORDED the end, up to a tick
   * after it (a 17 ms pipeline read "took 20 s" off them).
   */
  function durationFor(run) {
    if (M().isActive(run)) return "running…";
    if (!run.execution_id) return "—";
    var ms = (S.state.durations || {})[run.execution_id];
    return typeof ms === "number" ? M().msText(ms) : "…";
  }

  function fillDurations(root, c) {
    if (S.state.root.getAttribute("data-can-read-executions") !== "true") {
      Array.prototype.forEach.call(root.querySelectorAll(".sch-runrow [data-slot=duration]"), function (d) {
        if (d.textContent === "…") d.textContent = "—";
      });
      return;
    }
    S.state.durations = S.state.durations || {};
    c.runs.forEach(function (run) {
      var id = run.execution_id;
      if (!id || M().isActive(run) || typeof S.state.durations[id] === "number") return;
      S.state.durations[id] = null; // asked once
      API()
        .execution(id)
        .then(function (r) {
          var ms = r.data && r.data.duration_ms;
          if (typeof ms !== "number") return;
          S.state.durations[id] = ms;
          Array.prototype.forEach.call(document.querySelectorAll('#schedule-detail .sch-runrow[data-execution-id="' + id + '"] [data-slot=duration]'), function (d) {
            d.textContent = durationFor(run);
          });
        })
        .catch(function () { /* the row keeps "…" → the reader can open the execution */ });
    });
  }

  function renderRuns(root, c) {
    var list = S.slot(root, "runs");
    S.clear(list);
    c.runs.forEach(function (r) { list.appendChild(runRow(r, c.schedule.timezone)); });
    S.show(S.slot(root, "runs-empty"), !c.runs.length);
    S.show(S.slot(root, "runs-more-wrap"), c.runsHasMore);
    fillDurations(root, c);
  }

  function rerenderRuns() {
    var root = pane().querySelector("[data-schedule-detail]");
    if (root && current()) renderRuns(root, current());
  }

  /** Re-read the first page (a poll, a refresh, after Run now); older pages already shown are kept out. */
  function refreshRuns() {
    var c = current();
    if (!c) return Promise.resolve();
    var id = c.schedule.id;
    return API()
      .runs(id, 0, RUNS_PAGE)
      .then(function (r) {
        var now = current();
        if (!S.live() || !now || now.schedule.id !== id) return;
        var data = r.data || {};
        var before = now.runs.map(function (x) { return x.id + ":" + x.state; }).join();
        now.runs = data.items || [];
        now.runsHasMore = !!(data.pagination && data.pagination.has_more);
        rerenderRuns();
        var after = now.runs.map(function (x) { return x.id + ":" + x.state; }).join();
        // A run that just ended can have BLOCKED the schedule (unknown, or a refusal that
        // blocks): the schedule itself is re-read when any run changed state.
        if (before !== after && !now.runs.some(M().isActive)) return reload();
        schedulePoll();
      })
      .catch(function () { schedulePoll(); });
  }

  function moreRuns() {
    var c = current();
    if (!c) return;
    var id = c.schedule.id;
    API()
      .runs(id, c.runs.length, RUNS_PAGE)
      .then(function (r) {
        var now = current();
        if (!now || now.schedule.id !== id) return;
        var data = r.data || {};
        now.runs = now.runs.concat(data.items || []);
        now.runsHasMore = !!(data.pagination && data.pagination.has_more);
        rerenderRuns();
      })
      .catch(function (err) { S.toastError(err, "Older runs could not be loaded"); });
  }

  function stopPoll() {
    if (S.state.pollTimer) clearTimeout(S.state.pollTimer);
    S.state.pollTimer = null;
  }

  function schedulePoll() {
    stopPoll();
    var c = current();
    if (!c || !c.runs.some(M().isActive)) return;
    var id = c.schedule.id;
    S.state.pollTimer = setTimeout(function () {
      S.state.pollTimer = null;
      if (!S.live() || !current() || current().schedule.id !== id) return;
      if (document.hidden) return schedulePoll();
      refreshRuns();
    }, POLL_MS);
  }

  // ------------------------------------------------------------------ verbs

  /** A verb's success: the server's schedule is the new truth for the pane AND the tree row. */
  function adopt(result, toastTitle, toastBody) {
    var c = current();
    if (!c) return;
    c.schedule = result.data;
    c.etag = result.etag || '"' + result.data.revision + '"';
    S.explorer.updateRow(result.data);
    if (toastTitle) S.toast("success", toastTitle, toastBody);
    return reload();
  }

  function simpleVerb(call, title, bodyFor) {
    var c = current();
    if (!c) return;
    setBusy(true);
    call(c.schedule.id)
      .then(function (r) { return adopt(r, title, bodyFor ? bodyFor(r.data) : ""); })
      .catch(function (err) {
        setBusy(false);
        S.toastError(err, title + " failed");
      });
  }

  function pause() {
    simpleVerb(API().pause, "Paused", function (s) { return s.name + " records nothing until it resumes."; });
  }

  function resume() {
    simpleVerb(API().resume, "Resumed", function (s) { return s.name + " runs again from its next occurrence."; });
  }

  function unblock() {
    simpleVerb(API().unblock, "Unblocked", function (s) { return s.name + " resumes from now."; });
  }

  /**
   * Run now (§20.12). One idempotency key per attempt; a retry after a network failure — the
   * server may have recorded the run — reuses it, so the retry answers the original run.
   */
  function runNow() {
    var c = current();
    if (!c) return;
    var id = c.schedule.id;
    var attempt = (S.state.runAttempt = M().nextAttempt(S.state.runAttempt && S.state.runAttempt.scheduleId === id ? S.state.runAttempt : null, id, S.newKey));
    attempt.scheduleId = id;
    setBusy(true);
    API()
      .runNow(id, attempt.key)
      .then(function (r) {
        setBusy(false);
        S.state.runAttempt = null;
        S.toast("success", "Run requested", "It runs as the system identity; its row below follows it.");
        var now = current();
        if (now && now.schedule.id === id && r.data) {
          now.runs = [r.data].concat(now.runs.filter(function (x) { return x.id !== r.data.id; }));
          rerenderRuns();
          schedulePoll();
        }
      })
      .catch(function (err) {
        setBusy(false);
        if (err.network) attempt.unanswered = true;
        else S.state.runAttempt = null;
        S.toastError(err, "Run now failed");
      });
  }

  // ------------------------------------------------------------------ delete (§20.6)

  function confirmDelete() {
    var c = current();
    if (!c) return;
    var dlg = S.clone("sch-tpl-delete");
    if (!dlg) return;
    S.text(dlg, "name", c.schedule.name);
    S.openDialog(dlg);
  }

  /** The soft delete, claiming the revision the pane shows (If-Match). */
  function remove() {
    var c = current();
    var dlg = S.dialog();
    if (!c || !dlg) return;
    var button = dlg.querySelector('[data-sch-action="confirm-delete"]');
    if (button) button.disabled = true;
    var name = c.schedule.name;
    API()
      .remove(c.schedule.id, c.etag)
      .then(function () {
        S.closeDialog(true);
        stopPoll();
        S.state.current = null;
        S.state.selectedId = null;
        S.remember(null);
        paneMessage("Select a schedule", "Choose a schedule in the tree to see when it runs next and what its runs did.");
        S.toast("success", "Schedule deleted", name + " records no more occurrences. Its executions stay readable on the Executions screen.");
        return S.explorer.load();
      })
      .catch(function (err) {
        if (button) button.disabled = false;
        var why =
          err.code === "schedule.revision_conflict"
            ? "Someone changed this schedule after you opened it. Close this, look at it again, and delete it if you still mean to."
            : (err.userMessage || err.message) + (err.correlationId ? " (ref " + err.correlationId + ")" : "");
        var p = S.slot(dlg, "error");
        if (p) {
          p.textContent = why;
          p.hidden = false;
        }
        if (err.code === "schedule.revision_conflict") reload();
      });
  }

  S.detail = {
    confirmDelete: confirmDelete,
    remove: remove,
    show: show,
    reload: reload,
    refreshRuns: refreshRuns,
    moreRuns: moreRuns,
    stopPoll: stopPoll,
    pause: pause,
    resume: resume,
    unblock: unblock,
    runNow: runNow,
    current: current,
  };
})();
