/*
 * #9 slice 2 — the Schedules page's DECISIONS, DOM-free (ui-screens.md §4.20).
 *
 * Everything here is a pure function of REST data, so `node --test`
 * (src/test/js/schedules-model.test.mjs) pins it without a browser: which folders and leaves
 * a level shows, what a search matches, which preset a cron pattern is, how the two logs of a
 * run merge (the record's R10), which form field a §20 refusal belongs to, how a parameter's
 * typed text becomes its wire value, and when an idempotency key may be reused.
 *
 * The DOM halves (explorer.js, detail.js, run.js, form.js, page.js) delegate here.
 */
(function () {
  "use strict";

  // ------------------------------------------------------------------ names and levels

  function segmentsOf(name) {
    return String(name || "").split("/");
  }

  /**
   * One tree level under `prefix` ("" = the root) from a §20.1 read of that subtree: the
   * direct sub-folders (with how many schedules live beneath each) and the direct leaves.
   * Folders are derived from the names — they have no identity (template-hierarchy §3.1).
   */
  function buildLevel(schedules, prefix) {
    var base = prefix ? prefix + "/" : "";
    var folders = {};
    var leaves = [];
    (schedules || []).forEach(function (s) {
      var name = s.name || "";
      if (base && name.indexOf(base) !== 0) return;
      var rest = name.substring(base.length);
      if (!rest) return;
      var slash = rest.indexOf("/");
      if (slash === -1) {
        leaves.push(s);
      } else {
        var segment = rest.substring(0, slash);
        var path = base + segment;
        if (!folders[path]) folders[path] = { path: path, segment: segment, count: 0 };
        folders[path].count += 1;
      }
    });
    var folderList = Object.keys(folders).sort().map(function (k) { return folders[k]; });
    leaves.sort(function (a, b) { return a.name < b.name ? -1 : a.name > b.name ? 1 : 0; });
    return { folders: folderList, leaves: leaves };
  }

  /** The folder paths a name field may suggest: every proper prefix of every name, sorted. */
  function folderSuggestions(schedules) {
    var seen = {};
    (schedules || []).forEach(function (s) {
      var parts = segmentsOf(s.name);
      for (var i = 1; i < parts.length; i++) seen[parts.slice(0, i).join("/") + "/"] = true;
    });
    return Object.keys(seen).sort();
  }

  /** The leaf segment and the folder eyebrow ("finance/daily/") of a name. */
  function splitName(name) {
    var at = String(name || "").lastIndexOf("/");
    return at === -1 ? { folder: "", leaf: name || "" } : { folder: name.substring(0, at + 1), leaf: name.substring(at + 1) };
  }

  // ------------------------------------------------------------------ conditions and search

  var CONDITION_BADGE = { enabled: "ds-badge-success", paused: "ds-badge-warning", blocked: "ds-badge-danger" };

  function conditionBadge(condition) {
    return CONDITION_BADGE[condition] || "ds-badge-default";
  }

  function pipelineOf(schedule) {
    return (schedule && schedule.payload && typeof schedule.payload.pipeline === "string") ? schedule.payload.pipeline : "";
  }

  /**
   * The search (§5.1: it matches every column the list renders — the path, the pipeline, the
   * pattern, its preset wording, the zone and the condition). Case-insensitive substring; the
   * result is sorted by name.
   */
  function search(schedules, query) {
    var q = String(query || "").trim().toLowerCase();
    if (!q) return [];
    return (schedules || [])
      .filter(function (s) {
        var hay = [s.name, pipelineOf(s), s.cron, describeCron(s.cron).label, s.timezone, s.condition].join("\n").toLowerCase();
        return hay.indexOf(q) !== -1;
      })
      .sort(function (a, b) { return a.name < b.name ? -1 : a.name > b.name ? 1 : 0; });
  }

  // ------------------------------------------------------------------ cron presets

  var DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

  function plainInt(text, min, max) {
    if (!/^\d{1,2}$/.test(text)) return null;
    var n = parseInt(text, 10);
    return n >= min && n <= max ? n : null;
  }

  function pad2(n) {
    return (n < 10 ? "0" : "") + n;
  }

  /**
   * Which preset a five-field pattern is, with its parts — or `custom`. Only the exact shapes
   * the presets BUILD are recognised, so describe(build(x)) is x and a pattern the form did not
   * write (a list, a range, a step) stays custom rather than being mis-summarised.
   */
  function describeCron(cron) {
    var fields = String(cron || "").trim().split(/\s+/);
    var custom = { preset: "custom", label: "Custom pattern" };
    if (fields.length !== 5) return custom;
    var minute = plainInt(fields[0], 0, 59);
    if (minute === null) return custom;
    if (fields[1] === "*" && fields[2] === "*" && fields[3] === "*" && fields[4] === "*") {
      return { preset: "hourly", minute: minute, label: "Every hour at :" + pad2(minute) };
    }
    var hour = plainInt(fields[1], 0, 23);
    if (hour === null || fields[3] !== "*") return custom;
    var time = pad2(hour) + ":" + pad2(minute);
    if (fields[2] === "*" && fields[4] === "*") return { preset: "daily", minute: minute, hour: hour, label: "Every day at " + time };
    if (fields[2] === "*") {
      var dow = plainInt(fields[4], 0, 6);
      if (dow === null) return custom;
      return { preset: "weekly", minute: minute, hour: hour, dow: dow, label: "Every " + DAYS[dow] + " at " + time };
    }
    if (fields[4] === "*") {
      var dom = plainInt(fields[2], 1, 31);
      if (dom === null) return custom;
      return { preset: "monthly", minute: minute, hour: hour, dom: dom, label: "Every month on day " + dom + " at " + time };
    }
    return custom;
  }

  /** The pattern a preset's parts spell. `parts.time` is "HH:MM" from a time input. */
  function buildCron(preset, parts) {
    var p = parts || {};
    var hm = String(p.time || "00:00").split(":");
    var hour = parseInt(hm[0], 10) || 0;
    var minute = parseInt(hm[1], 10) || 0;
    switch (preset) {
      case "hourly":
        return clampInt(p.minute, 0, 59) + " * * * *";
      case "daily":
        return minute + " " + hour + " * * *";
      case "weekly":
        return minute + " " + hour + " * * " + clampInt(p.dow, 0, 6);
      case "monthly":
        return minute + " " + hour + " " + clampInt(p.dom, 1, 31) + " * *";
      default:
        return String(p.cron || "").trim();
    }
  }

  function clampInt(value, min, max) {
    var n = parseInt(value, 10);
    if (isNaN(n)) n = min;
    return Math.min(max, Math.max(min, n));
  }

  // ------------------------------------------------------------------ times

  /** A wall-clock string for an instant, in a zone (the schedule's), in the reader's locale. */
  function localText(iso, timeZone, locale) {
    if (!iso) return "";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    try {
      return new Intl.DateTimeFormat(locale || undefined, {
        timeZone: timeZone || undefined,
        weekday: "short", year: "numeric", month: "short", day: "numeric",
        hour: "2-digit", minute: "2-digit", hourCycle: "h23",
      }).format(d);
    } catch (e) {
      return d.toISOString();
    }
  }

  /**
   * §20.3's `local` is the wall clock AS THE SCHEDULER COMPUTED IT ("2026-11-01T01:30") —
   * shown verbatim-in-meaning, never re-derived in the browser: the DST rule is the server's.
   * The year is written only when it is not this one (`nowMs`, default now).
   */
  function occurrenceLocalText(local, locale, nowMs) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(local || ""));
    if (!m) return String(local || "");
    var d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]));
    var thisYear = new Date(typeof nowMs === "number" ? nowMs : Date.now()).getUTCFullYear();
    var options = { timeZone: "UTC", weekday: "short", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" };
    if (+m[1] !== thisYear) options.year = "numeric";
    try {
      return new Intl.DateTimeFormat(locale || undefined, options).format(d);
    } catch (e) {
      return m[0].replace("T", " ");
    }
  }

  /**
   * The compact form the run rows and the UTC column use: month, day and time in `timeZone`,
   * the year only when it is not this one. The exact instant rides on the element's title.
   */
  function shortText(iso, timeZone, nowMs, locale) {
    if (!iso) return "";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    var options = { timeZone: timeZone || undefined, month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" };
    try {
      var year = function (t) { return new Intl.DateTimeFormat("en-US", { timeZone: timeZone || undefined, year: "numeric" }).format(t); };
      if (year(d) !== year(new Date(typeof nowMs === "number" ? nowMs : Date.now()))) options.year = "numeric";
      return new Intl.DateTimeFormat(locale || undefined, options).format(d);
    } catch (e) {
      return d.toISOString();
    }
  }

  /** "+02:00" → "UTC+02:00"; "Z" → "UTC". The minus is the typographic one. */
  function offsetText(offset) {
    if (!offset || offset === "Z") return "UTC";
    return "UTC" + String(offset).replace("-", "−");
  }

  function durationText(startIso, endIso) {
    if (!startIso || !endIso) return "";
    return msText(new Date(endIso).getTime() - new Date(startIso).getTime());
  }

  /** A duration in milliseconds as the lists write one: "250 ms", "12.3 s", "3 min 5 s". */
  function msText(ms) {
    if (typeof ms !== "number" || isNaN(ms) || ms < 0) return "";
    if (ms < 1000) return ms + " ms";
    var s = Math.round(ms / 100) / 10;
    if (s < 60) return s + " s";
    var m = Math.floor(s / 60);
    var rs = Math.round(s - m * 60);
    if (m < 60) return m + " min " + rs + " s";
    var h = Math.floor(m / 60);
    return h + " h " + (m - h * 60) + " min";
  }

  /** "in 3 h", "5 min ago" — the short relative form the tables show beside an absolute title. */
  function relativeText(iso, nowMs) {
    if (!iso) return "";
    var t = new Date(iso).getTime();
    if (isNaN(t)) return "";
    var diff = t - (typeof nowMs === "number" ? nowMs : Date.now());
    var abs = Math.abs(diff);
    var unit;
    if (abs < 60000) unit = Math.round(abs / 1000) + " s";
    else if (abs < 3600000) unit = Math.round(abs / 60000) + " min";
    else if (abs < 172800000) unit = Math.round(abs / 3600000) + " h";
    else unit = Math.round(abs / 86400000) + " d";
    return diff >= 0 ? "in " + unit : unit + " ago";
  }

  // ------------------------------------------------------------------ runs

  var RUN_CHIP = {
    succeeded: "app-chip-ok",
    failed: "app-chip-bad",
    unknown: "app-chip-bad",
    queued: "app-chip-run",
    starting: "app-chip-run",
    running: "app-chip-run",
  };

  function runChip(state) {
    return RUN_CHIP[state] || "app-chip-warn";
  }

  function runStateText(state) {
    return String(state || "").replace(/_/g, " ");
  }

  var ACTIVE = { queued: true, starting: true, running: true };

  function isActive(run) {
    return !!(run && ACTIVE[run.state]);
  }

  // A manual run's badge says "manual", not "Run now": beside the verb of that name a badge
  // reading like a button is a second button that does nothing.
  var ORIGIN_TEXT = { cron: "scheduled", catch_up: "catch-up", manual: "manual" };

  function originText(origin) {
    return ORIGIN_TEXT[origin] || String(origin || "");
  }

  /**
   * Why a schedule is blocked, in words (scheduler.md §5.2). The reason CODE is always shown
   * beside this text, so an unknown code is never hidden — it just has no sentence yet.
   */
  var BLOCK_TEXT = {
    run_unknown: "A run's outcome is unknown — nobody can say whether its work happened. Check that run's execution and the pipeline's targets before unblocking.",
    pointer_null: "The pipeline has no current version to follow. Release it, or switch its current version, then unblock.",
    target_not_found: "The pipeline this schedule runs no longer exists in this workspace.",
    payload_invalid: "The saved job no longer validates against the pipeline.",
    parameters_invalid: "The saved parameter values no longer fit the pipeline's declared parameters. Edit the schedule's parameters, then unblock.",
    authority_refused: "The system identity was refused permission to run the pipeline here.",
    start_refused: "Starting the pipeline was refused before anything ran.",
    executor_unavailable: "No executor is registered for this kind of job on this instance.",
  };

  function blockedText(reason) {
    return BLOCK_TEXT[reason] || "The scheduler stopped this schedule; the code below names why.";
  }

  var UNKNOWN_TEXT = {
    instance_lost: "The instance running it stopped mid-run.",
    start_unconfirmed: "The start was claimed but its execution never appeared.",
    start_failed: "The launch failed partway.",
    execution_missing: "A running execution's record disappeared.",
  };

  function unknownText(reason) {
    return UNKNOWN_TEXT[reason] || "";
  }

  // ------------------------------------------------------------------ the merged trail (R10)

  function compactJson(value) {
    if (value === null || value === undefined) return "";
    if (typeof value === "string") return value;
    try { return JSON.stringify(value); } catch (e) { return String(value); }
  }

  /** One scheduler trail row (§20.11) as a message line. */
  function schedulerLine(t) {
    var bits = [];
    if (t.reason) bits.push(t.reason);
    var d = t.details || {};
    Object.keys(d).sort().forEach(function (k) { bits.push(k + " " + compactJson(d[k])); });
    return { at: t.at, source: "scheduler", what: t.kind, detail: bits.join(" · "), order: 0, seq: t.seq };
  }

  function nodeOf(data) {
    return data && data.node_id ? String(data.node_id) : "";
  }

  function errorOf(data) {
    var e = data && data.error;
    if (!e) return "";
    return [e.code, e.user_message || e.message].filter(Boolean).join(": ");
  }

  /** One durable execution event (§10.3A) as a message line — a short summary per event kind. */
  function pipelineLine(e) {
    var d = e.data || {};
    var bits = [];
    switch (e.event) {
      case "execution_started":
        if (d.pipeline_version !== undefined) bits.push("v" + d.pipeline_version);
        break;
      case "node_started":
        bits.push(nodeOf(d));
        if (d.attempt && d.attempt > 1) bits.push("attempt " + d.attempt);
        break;
      case "node_progress":
        bits.push(nodeOf(d));
        if (d.operation) bits.push(d.operation);
        if (d.state) bits.push(d.state);
        break;
      case "node_completed":
        bits.push(nodeOf(d));
        if (d.rows_out !== undefined) bits.push(d.rows_out + " rows");
        if (d.duration_ms !== undefined) bits.push(d.duration_ms + " ms");
        break;
      case "node_failed":
        bits.push(nodeOf(d));
        bits.push(errorOf(d));
        break;
      case "pipeline_completed":
        if (d.duration_ms !== undefined) bits.push(d.duration_ms + " ms");
        break;
      case "pipeline_failed":
        if (d.failed_node_id) bits.push("at " + d.failed_node_id);
        bits.push(errorOf(d));
        break;
      case "execution_aborted":
        if (d.reason) bits.push(d.reason);
        break;
      case "data_ready":
        if (d.row_count !== undefined) bits.push(d.row_count + " rows");
        break;
      default:
        break;
    }
    return {
      at: e.timestamp,
      source: "pipeline",
      what: e.event,
      detail: bits.filter(Boolean).join(" · "),
      order: 1,
      seq: e.event_id,
    };
  }

  /**
   * The Messages pane: the two logs merged ON READ, in time order (the record's §5.4). Equal
   * timestamps keep each log's own order and put the scheduler's line first — its "execution
   * started" is what hands over to the pipeline's.
   */
  function mergeMessages(trail, events) {
    var lines = (trail || []).map(schedulerLine).concat((events || []).map(pipelineLine));
    return lines
      .map(function (l, i) { return { line: l, t: new Date(l.at).getTime(), i: i }; })
      .sort(function (a, b) {
        if (a.t !== b.t) return (isNaN(a.t) ? 0 : a.t) - (isNaN(b.t) ? 0 : b.t);
        if (a.line.order !== b.line.order) return a.line.order - b.line.order;
        return a.i - b.i;
      })
      .map(function (x) { return x.line; });
  }

  // ------------------------------------------------------------------ §20 refusals → fields

  var FIELD_BY_CODE = {
    "schedule.validation.name_invalid": "name",
    "schedule.name_taken": "name",
    "schedule.validation.cron_invalid": "cron",
    "schedule.validation.interval_too_short": "cron",
    "schedule.validation.timezone_invalid": "timezone",
    "schedule.validation.payload_invalid": "pipeline",
    "schedule.validation.target_not_found": "pipeline",
    "schedule.validation.executor_unknown": "pipeline",
  };

  var FIELD_BY_REQUEST_FIELD = {
    name: "name",
    cron: "cron",
    timezone: "timezone",
    payload: "pipeline",
    executor: "pipeline",
    parameters: "parameters",
    missed_run_policy: "missed_run_policy",
  };

  /**
   * Which form field a §20 refusal belongs beside, or null for "a toast": the schedule codes by
   * name, `request_invalid` by its `details.field`, and a parameter refusal — the binder's own
   * `pipeline.execution.*` codes, exactly an interactive run's — by the parameter it names.
   */
  function fieldForError(error) {
    if (!error || !error.code) return null;
    var code = error.code;
    var details = error.details || {};
    if (FIELD_BY_CODE[code]) return { field: FIELD_BY_CODE[code] };
    if (code === "schedule.validation.request_invalid") {
      var f = FIELD_BY_REQUEST_FIELD[details.field];
      return f ? { field: f } : null;
    }
    if (code.indexOf("pipeline.execution.") === 0) return { field: "parameters", parameters: parameterErrors(error) };
    return null;
  }

  /**
   * The binder's refusals, one per parameter it names. The binder reports EVERY failure at
   * once (`details.failures`, each `{code, path: "parameters.<name>", message, details:
   * {parameter}}` — PipelineValidationException), so each field gets its own message.
   */
  function parameterErrors(error) {
    var details = (error && error.details) || {};
    var failures = Array.isArray(details.failures) ? details.failures : [];
    var out = failures.map(function (f) {
      var d = f.details || {};
      var fromPath = typeof f.path === "string" && f.path.indexOf("parameters.") === 0 ? f.path.substring(11) : null;
      return { parameter: d.parameter || fromPath || null, message: f.message || f.code || "" };
    });
    if (!out.length && details.parameter) out.push({ parameter: details.parameter, message: error.message || "" });
    return out;
  }

  // ------------------------------------------------------------------ parameters (R7)

  var NUMBER_TYPES = { INTEGER: /^-?\d+$/, DECIMAL: /^-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/ };

  /**
   * A typed text as its wire value (pipeline-contract §6.3): INTEGER and DECIMAL are JSON
   * numbers, BOOLEAN a JSON boolean, everything else — BIGINTEGER and BIGDECIMAL included — a
   * string. Text that is NOT a clean number is sent as typed, so the server's binder refuses it
   * with its own message beside the field instead of the browser silently truncating "12abc"
   * to 12 (the editor's lenient parseInt would).
   */
  function coerceParameter(text, type) {
    var t = String(type || "STRING").toUpperCase();
    if (NUMBER_TYPES[t]) return NUMBER_TYPES[t].test(text) ? Number(text) : text;
    if (t === "BOOLEAN") return text === "true" ? true : text === "false" ? false : text;
    return text;
  }

  /** The literal values to send: every non-blank field, coerced by its declared type. */
  function collectParameters(declared, texts) {
    var out = {};
    Object.keys(texts || {}).forEach(function (k) {
      var v = texts[k];
      if (v === undefined || v === null || v === "") return;
      out[k] = coerceParameter(String(v), declared && declared[k] ? declared[k].type : "STRING");
    });
    return out;
  }

  /** A saved literal as the text its field shows. */
  function parameterText(value) {
    if (value === null || value === undefined) return "";
    return typeof value === "string" ? value : JSON.stringify(value);
  }

  /** The declared (not derived) parameters of a version body, sorted by name. */
  function declaredParameters(parameters) {
    return Object.keys(parameters || {})
      .filter(function (k) { return !(parameters[k] && parameters[k].derived); })
      .sort()
      .map(function (k) { return { name: k, spec: parameters[k] || {} }; });
  }

  // ------------------------------------------------------------------ idempotency (L1)

  /**
   * A fresh key per ATTEMPT; the same key only for a retry of the SAME request whose previous
   * try got no answer (a network failure). §20.2/§20.12: a replayed key answers the original,
   * and a key replayed with a different request is refused — so a changed body always gets a
   * new key, and an answered attempt is never replayed.
   */
  function nextAttempt(previous, body, newKey) {
    if (previous && previous.unanswered && previous.body === body) return { key: previous.key, body: body, unanswered: false };
    return { key: newKey(), body: body, unanswered: false };
  }

  /** An RFC 4122 v4 id from a byte source (crypto.getRandomValues — present outside secure contexts too). */
  function uuidFrom(bytes) {
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = [];
    for (var i = 0; i < 16; i++) hex.push((bytes[i] + 0x100).toString(16).substring(1));
    return hex.slice(0, 4).join("") + "-" + hex.slice(4, 6).join("") + "-" + hex.slice(6, 8).join("") + "-" +
      hex.slice(8, 10).join("") + "-" + hex.slice(10, 16).join("");
  }

  var api = {
    buildLevel: buildLevel,
    folderSuggestions: folderSuggestions,
    splitName: splitName,
    conditionBadge: conditionBadge,
    pipelineOf: pipelineOf,
    search: search,
    describeCron: describeCron,
    buildCron: buildCron,
    localText: localText,
    occurrenceLocalText: occurrenceLocalText,
    shortText: shortText,
    offsetText: offsetText,
    durationText: durationText,
    msText: msText,
    relativeText: relativeText,
    runChip: runChip,
    runStateText: runStateText,
    isActive: isActive,
    originText: originText,
    blockedText: blockedText,
    unknownText: unknownText,
    schedulerLine: schedulerLine,
    pipelineLine: pipelineLine,
    mergeMessages: mergeMessages,
    fieldForError: fieldForError,
    parameterErrors: parameterErrors,
    coerceParameter: coerceParameter,
    collectParameters: collectParameters,
    parameterText: parameterText,
    declaredParameters: declaredParameters,
    nextAttempt: nextAttempt,
    uuidFrom: uuidFrom,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DpSchedulesModel = api;
})();
