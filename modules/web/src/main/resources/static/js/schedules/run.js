/*
 * #9 slice 2 — one run, opened from the runs list or deep-linked (`/schedules?id=…&run=…`):
 * rest-api §20.11 (the run, its frozen job and parameters, its trail) and, when it launched an
 * execution and the reader holds `execution.read`, §10.3A (that execution's DURABLE events,
 * `?format=json`, oldest first, paged by event id).
 *
 * The Messages pane merges the two logs on read (the record's R10, §5.4), each line labelled
 * `scheduler` or `pipeline`. The execution half can legitimately be absent, and the pane says
 * which case it is rather than showing a shorter list silently: no execution (the run never
 * started), a reader whose role reads no executions (the promoter), a record past its retention
 * (410 `event_record_expired`), or a read that failed.
 */
(function () {
  "use strict";

  var S = (window.DpSchedules = window.DpSchedules || {});
  var M = function () { return window.DpSchedulesModel; };
  var API = function () { return window.DpSchedulesApi; };

  /** At most this many durable events are read (4 pages of 500); the note says when it stopped. */
  var MAX_EVENT_PAGES = 4;

  function canReadExecutions() {
    return S.state.root.getAttribute("data-can-read-executions") === "true";
  }

  function readEvents(executionId) {
    var events = [];
    function page(after, n) {
      return API()
        .executionEvents(executionId, after)
        .then(function (r) {
          var data = r.data || {};
          events = events.concat(data.events || []);
          if (data.has_more && data.next_after !== null && data.next_after !== undefined && n + 1 < MAX_EVENT_PAGES) {
            return page(data.next_after, n + 1);
          }
          return { events: events, truncated: !!data.has_more };
        });
    }
    return page(0, 0);
  }

  function open(scheduleId, runId) {
    var dlg = S.clone("sch-tpl-run-dialog");
    if (!dlg) return;
    dlg.setAttribute("data-run-id", runId);
    var c = S.detail.current();
    S.text(dlg, "schedule", c && c.schedule.id === scheduleId ? c.schedule.name : "");
    S.text(dlg, "messages-note", "Loading…");
    S.openDialog(dlg, function () { S.remember(scheduleId); });
    S.remember(scheduleId, runId);
    API()
      .run(scheduleId, runId)
      .then(function (r) {
        if (!dlg.isConnected) return;
        var run = r.data;
        var timezone = run.reference_timezone || (c && c.schedule.timezone);
        fill(dlg, run, timezone, c);
        return messages(dlg, run, timezone);
      })
      .catch(function (err) {
        if (!dlg.isConnected) return;
        S.text(dlg, "messages-note", "This run could not be read: " + (err.userMessage || err.message) + (err.correlationId ? " (ref " + err.correlationId + ")" : ""));
      });
  }

  function fill(dlg, run, timezone, c) {
    var chip = S.slot(dlg, "state");
    chip.className = "app-chip " + M().runChip(run.state);
    S.text(dlg, "state-text", M().runStateText(run.state));
    S.text(dlg, "origin", M().originText(run.origin));
    var reason = S.text(dlg, "reason", run.reason || "");
    S.show(reason, !!run.reason);

    var manual = run.origin === "manual";
    S.text(dlg, "due-label", manual ? "Requested" : "Due");
    var dueIso = run.scheduled_at || run.reference_at;
    S.time(dlg, "due", dueIso, M().localText(dueIso, timezone));
    S.time(dlg, "started", run.started_at, M().localText(run.started_at, timezone));
    S.time(dlg, "finished", run.finished_at, M().localText(run.finished_at, timezone));
    // The EXECUTION's own time (§10.2) — see detail.js's durationFor for why not the run's stamps.
    S.text(dlg, "duration", M().isActive(run) ? "running…" : "—");
    if (run.execution_id && canReadExecutions()) {
      API()
        .execution(run.execution_id)
        .then(function (r) {
          var ms = r.data && r.data.duration_ms;
          if (typeof ms === "number" && dlg.isConnected) {
            S.text(dlg, "duration", M().msText(ms));
          }
        })
        .catch(function () { /* the Messages say what the execution did */ });
    }
    S.time(dlg, "admit-by", run.admit_by, M().localText(run.admit_by, timezone));
    S.text(dlg, "attempts", run.attempts > 0 ? " · " + run.attempts + " capacity " + (run.attempts === 1 ? "retry" : "retries") : "");
    var p = run.prepared;
    S.text(dlg, "prepared", p ? (p.pipeline || "") + " v" + p.version + (p.version_status ? " (" + String(p.version_status).toLowerCase() + ")" : "") +
      (p.body_sha256 ? " · body " + String(p.body_sha256).substring(0, 12) : "") : "— not prepared (nothing was resolved)");
    S.text(dlg, "revision", run.schedule_revision === null || run.schedule_revision === undefined ? "—" : "revision " + run.schedule_revision);
    S.text(dlg, "payload", JSON.stringify(run.payload || {}, null, 2));
    S.text(dlg, "parameters", JSON.stringify(run.parameters || {}, null, 2));

    var link = S.slot(dlg, "execution");
    if (run.execution_id && link) {
      link.textContent = run.execution_id;
      link.setAttribute("href", "/executions/" + encodeURIComponent(run.execution_id));
      link.hidden = false;
      S.text(dlg, "execution-none", "");
    } else {
      S.text(dlg, "execution-none", run.execution_id ? run.execution_id : "none — nothing was launched");
    }

    var unknown = run.state === "unknown";
    S.show(S.slot(dlg, "unknown"), unknown);
    if (unknown) {
      S.text(dlg, "unknown-reason", M().unknownText(run.reason));
      var openExec = S.slot(dlg, "unknown-execution");
      if (openExec && run.execution_id) {
        openExec.setAttribute("href", "/executions/" + encodeURIComponent(run.execution_id));
        openExec.hidden = false;
      }
      S.show(S.slot(dlg, "unknown-step-execution"), !!run.execution_id);
      // Unblock is offered here only while THIS run is what blocks the schedule.
      var blocking = c && c.schedule.blocked && c.schedule.blocked.run_id === run.id;
      S.show(dlg.querySelector('[data-verb="schedule-unblock"]'), !!blocking);
    }
  }

  function messages(dlg, run, timezone) {
    var trail = run.trail || [];
    if (!run.execution_id) {
      render(dlg, trail, [], timezone, "The scheduler's trail only — this run launched no execution.");
      return;
    }
    if (!canReadExecutions()) {
      render(dlg, trail, [], timezone, "The scheduler's trail only — your role does not read executions, so the pipeline's messages are not shown.");
      return;
    }
    return readEvents(run.execution_id)
      .then(function (read) {
        if (!dlg.isConnected) return;
        var note = "The scheduler's trail and the pipeline's messages, merged in time order.";
        if (read.truncated) note += " The first " + read.events.length + " pipeline messages are shown; the execution's page has the rest.";
        render(dlg, trail, read.events, timezone, note);
      })
      .catch(function (err) {
        if (!dlg.isConnected) return;
        var why =
          err.status === 410
            ? "The pipeline's messages are past their retention (kept a week after the execution finished); the execution's summary stays on its page."
            : "The pipeline's messages could not be read: " + (err.userMessage || err.message) + (err.correlationId ? " (ref " + err.correlationId + ")" : "");
        render(dlg, trail, [], timezone, "The scheduler's trail only. " + why);
      });
  }

  function render(dlg, trail, events, timezone, note) {
    S.text(dlg, "messages-note", note);
    var body = S.slot(dlg, "messages");
    S.clear(body);
    M()
      .mergeMessages(trail, events)
      .forEach(function (line) {
        var row = S.clone("sch-tpl-message");
        row.setAttribute("data-source", line.source);
        S.time(row, "at", line.at, clockText(line.at, timezone));
        var src = S.text(row, "source", line.source);
        src.className = "ds-badge sch-source " + (line.source === "scheduler" ? "ds-badge-primary" : "ds-badge-default");
        S.text(row, "what", line.what);
        S.text(row, "detail", line.detail);
        body.appendChild(row);
      });
  }

  /** A message's time: the wall clock in the schedule's zone, to the second. */
  function clockText(iso, timezone) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso || "");
    try {
      return new Intl.DateTimeFormat(undefined, {
        timeZone: timezone || undefined, month: "short", day: "numeric",
        hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
      }).format(d);
    } catch (e) {
      return d.toISOString();
    }
  }

  S.run = { open: open };
})();
