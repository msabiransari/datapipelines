/*
 * #9 slice 2 — the schedule form: create (§20.2) and edit (§20.5), ONE component.
 *
 *  - Name: free text with the workspace's existing folders suggested; the grammar is the
 *    server's (PipelineNameGrammar — the ONE grammar, record A2) and its refusal is shown
 *    beside the field. Nothing here re-implements it (template-hierarchy §9.5).
 *  - Pipeline: suggestions from §5.7's search over the pipelines the reader may read; the job
 *    is `{pipeline, version: "current"}` — the only payload slice 1 accepts.
 *  - When: presets that WRITE a five-field pattern (hourly / daily / weekly / monthly) or a
 *    custom pattern, a timezone (the browser's own preselected on create), and a live preview
 *    of the next five from §20.3 — the dispatcher's own function, so the DST rule shown is
 *    the one that will fire.
 *  - Parameters: the declared parameters of the version the pipeline's current pointer names
 *    NOW (the version §20.2 validates against), as literal values (R7).
 *
 * Create carries an idempotency key per attempt (model.nextAttempt); edit carries the
 * revision the form was opened at in If-Match, and a stale one (409
 * `schedule.revision_conflict`) is rendered in the form with a reload.
 */
(function () {
  "use strict";

  var S = (window.DpSchedules = window.DpSchedules || {});
  var M = function () { return window.DpSchedulesModel; };
  var API = function () { return window.DpSchedulesApi; };

  var PREVIEW_DELAY_MS = 300;
  var SEARCH_DELAY_MS = 250;

  function f() {
    return S.state.form || null;
  }

  function el(id) {
    var dlg = S.dialog();
    return dlg ? dlg.querySelector("#" + id) : null;
  }

  function browserZone() {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    } catch (e) {
      return "UTC";
    }
  }

  /** Select `zone`; a saved zone the list does not offer (an `Etc/` id saved over REST) is added. */
  function chooseZone(select, zone) {
    var has = Array.prototype.some.call(select.options, function (o) { return o.value === zone; });
    if (!has) {
      var o = document.createElement("option");
      o.value = zone;
      o.textContent = zone;
      select.insertBefore(o, select.firstChild);
    }
    select.value = zone;
  }

  // ------------------------------------------------------------------ open

  function openCreate() {
    var dlg = S.clone("sch-tpl-form");
    if (!dlg) return;
    S.state.form = { mode: "create", attempt: null, declared: {}, pipelineVersion: null };
    S.openDialog(dlg);
    wire(dlg);
    chooseZone(el("sch-f-timezone"), browserZone());
    applyPreset("daily");
    refreshFolders();
    el("sch-f-name").focus();
  }

  /** Edit reads the schedule afresh: its ETag is the revision the save will claim. */
  function openEdit(scheduleId) {
    return API()
      .get(scheduleId)
      .then(function (r) {
        var s = r.data;
        var dlg = S.clone("sch-tpl-form");
        if (!dlg) return;
        S.state.form = { mode: "edit", id: s.id, etag: r.etag, revision: s.revision, declared: {}, pipelineVersion: null, saved: s };
        S.openDialog(dlg);
        wire(dlg);
        S.text(dlg, "title", "Edit schedule");
        S.text(dlg, "submit", "Save changes");
        fillFrom(s);
        refreshFolders();
      })
      .catch(function (err) { S.toastError(err, "The schedule could not be opened for editing"); });
  }

  function fillFrom(s) {
    el("sch-f-name").value = s.name;
    el("sch-f-pipeline").value = M().pipelineOf(s);
    chooseZone(el("sch-f-timezone"), s.timezone);
    var policy = S.dialog().querySelector('input[name="missed_run_policy"][value="' + (s.missed_run_policy === "latest" ? "latest" : "skip") + '"]');
    if (policy) policy.checked = true;
    var d = M().describeCron(s.cron);
    if (d.preset === "hourly") el("sch-f-minute").value = d.minute;
    if (d.hour !== undefined) el("sch-f-time").value = pad(d.hour) + ":" + pad(d.minute);
    if (d.dow !== undefined) el("sch-f-dow").value = String(d.dow);
    if (d.dom !== undefined) el("sch-f-dom").value = String(d.dom);
    el("sch-f-cron").value = s.cron;
    applyPreset(d.preset, true);
    loadParameters(M().pipelineOf(s), s.parameters || {});
  }

  function pad(n) {
    return (n < 10 ? "0" : "") + n;
  }

  // ------------------------------------------------------------------ wiring

  function wire(dlg) {
    var form = dlg.querySelector("[data-sch-form]");
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      submit();
    });
    dlg.querySelector("#sch-f-preset").addEventListener("change", function (event) {
      applyPreset(event.target.value);
    });
    Array.prototype.forEach.call(dlg.querySelectorAll("[data-cron-part]"), function (input) {
      if (input.id === "sch-f-preset") return;
      input.addEventListener("input", rebuildCron);
      input.addEventListener("change", rebuildCron);
    });
    var cron = dlg.querySelector("#sch-f-cron");
    cron.addEventListener("input", function () { schedulePreview(); });
    dlg.querySelector("#sch-f-timezone").addEventListener("change", function () { schedulePreview(); });
    var pipeline = dlg.querySelector("#sch-f-pipeline");
    pipeline.addEventListener("input", function () { searchPipelines(pipeline.value); });
    // `change` fires on BLUR, typically as the author moves on to type a parameter: re-reading
    // the parameters of the pipeline already loaded would re-render the fields under their
    // typing (the lane walk lost a value exactly so). Only a different pipeline reloads them.
    pipeline.addEventListener("change", function () {
      var name = pipeline.value.trim();
      if (name && name !== (f() && f().parametersFor)) loadParameters(name, currentTexts());
    });
    dlg.querySelector("#sch-f-name").addEventListener("input", function () { clearError("name"); });
  }

  /** The existing folders as the name field's suggestions (the pipelines' folder-suggest idiom). */
  function refreshFolders() {
    var list = el("sch-f-folders");
    if (!list) return;
    S.clear(list);
    M().folderSuggestions(S.state.all || []).forEach(function (path) {
      var o = document.createElement("option");
      o.value = path;
      list.appendChild(o);
    });
  }

  // ------------------------------------------------------------------ when

  function applyPreset(preset, keepCron) {
    var dlg = S.dialog();
    if (!dlg) return;
    el("sch-f-preset").value = preset;
    Array.prototype.forEach.call(dlg.querySelectorAll("[data-preset-only]"), function (field) {
      field.hidden = field.getAttribute("data-preset-only").split(" ").indexOf(preset) === -1;
    });
    var cron = el("sch-f-cron");
    var custom = preset === "custom";
    cron.readOnly = !custom;
    if (!custom && !keepCron) rebuildCron();
    else schedulePreview();
    if (custom && !keepCron) cron.focus();
  }

  function rebuildCron() {
    var preset = el("sch-f-preset").value;
    if (preset === "custom") return;
    el("sch-f-cron").value = M().buildCron(preset, {
      minute: el("sch-f-minute").value,
      time: el("sch-f-time").value,
      dow: el("sch-f-dow").value,
      dom: el("sch-f-dom").value,
    });
    S.show(S.slot(S.dialog(), "monthly-note"), preset === "monthly" && parseInt(el("sch-f-dom").value, 10) > 28);
    schedulePreview();
  }

  function schedulePreview() {
    var state = f();
    if (!state) return;
    if (state.previewTimer) clearTimeout(state.previewTimer);
    state.previewTimer = setTimeout(preview, PREVIEW_DELAY_MS);
  }

  /** §20.3 for the pattern as typed. A refusal here is the save's refusal, shown early. */
  function preview() {
    var state = f();
    var dlg = S.dialog();
    if (!state || !dlg) return;
    state.previewTimer = null;
    var cron = el("sch-f-cron").value.trim();
    var zone = el("sch-f-timezone").value;
    var list = S.slot(dlg, "preview");
    var seq = (state.previewSeq = (state.previewSeq || 0) + 1);
    list.removeAttribute("data-cron");
    list.removeAttribute("data-timezone");
    if (!cron) {
      S.clear(list);
      S.text(dlg, "preview-status", "Enter a pattern to see when it runs.");
      return;
    }
    S.text(dlg, "preview-status", "Working it out…");
    API()
      .preview(cron, zone, 5)
      .then(function (r) {
        if (!dlg.isConnected || seq !== state.previewSeq) return;
        clearError("cron");
        clearError("timezone");
        S.clear(list);
        ((r.data || {}).occurrences || []).forEach(function (o) {
          var li = document.createElement("li");
          li.setAttribute("data-at", o.at);
          li.textContent = M().occurrenceLocalText(o.local) + " · " + M().offsetText(o.offset);
          li.setAttribute("title", o.at);
          list.appendChild(li);
        });
        // What the list was computed FOR — the pattern and zone as they were typed then.
        list.setAttribute("data-cron", cron);
        list.setAttribute("data-timezone", zone);
        S.text(dlg, "preview-status", "In " + zone + ", as the scheduler computes it:");
      })
      .catch(function (err) {
        if (!dlg.isConnected || seq !== state.previewSeq) return;
        S.clear(list);
        S.text(dlg, "preview-status", "");
        var target = M().fieldForError(err);
        if (target && (target.field === "cron" || target.field === "timezone")) showError(target.field, err.userMessage || err.message);
        else S.text(dlg, "preview-status", "No preview: " + (err.userMessage || err.message));
      });
  }

  // ------------------------------------------------------------------ pipeline and parameters

  function searchPipelines(text) {
    var state = f();
    if (!state) return;
    clearError("pipeline");
    if (state.searchTimer) clearTimeout(state.searchTimer);
    state.searchTimer = setTimeout(function () {
      var seq = (state.searchSeq = (state.searchSeq || 0) + 1);
      API()
        .findPipelines(text.trim())
        .then(function (r) {
          var list = el("sch-f-pipelines");
          if (!list || seq !== state.searchSeq) return;
          state.found = (r.data || {}).items || [];
          S.clear(list);
          state.found.forEach(function (p) {
            var o = document.createElement("option");
            o.value = p.name;
            list.appendChild(o);
          });
          // A name typed (or picked) in full needs no separate "change" to load its parameters.
          var exact = state.found.filter(function (p) { return p.name === el("sch-f-pipeline").value.trim(); })[0];
          if (exact && state.parametersFor !== exact.name) loadParameters(exact.name, currentTexts());
        })
        .catch(function () { /* suggestions are a convenience; the save is the authority */ });
    }, SEARCH_DELAY_MS);
  }

  /** The texts currently typed into the parameter fields, by name (kept across a reload of them). */
  function currentTexts() {
    var out = {};
    var dlg = S.dialog();
    if (!dlg) return out;
    Array.prototype.forEach.call(dlg.querySelectorAll("[data-param-name]"), function (field) {
      var name = field.getAttribute("data-param-name");
      var input = field.querySelector("[data-param-input]:not([hidden]), [data-param-select]:not([hidden])");
      if (input) out[name] = input.value;
    });
    return out;
  }

  function paramStatus(text) {
    S.text(S.dialog(), "params-status", text);
  }

  /**
   * The declared parameters of the version the pipeline's CURRENT pointer names — what §20.2
   * binds the values against at save (PipelineJobExecutor.validate) and what each run
   * re-binds. `values` prefill the fields (a saved schedule's literals, or what was typed).
   */
  function loadParameters(name, values) {
    var state = f();
    if (!state || !name) return;
    state.parametersFor = name;
    var seq = (state.paramSeq = (state.paramSeq || 0) + 1);
    paramStatus("Reading " + name + "'s parameters…");
    API()
      .findPipelines(name)
      .then(function (r) {
        var hit = ((r.data || {}).items || []).filter(function (p) { return p.name === name; })[0];
        if (!hit) throw { notFound: true };
        return API().pipeline(hit.id).then(function (p) {
          var working = p.data;
          var current = working.current_version;
          if (current === null || current === undefined) return { none: true };
          if (working.version === current) return { body: working, version: current };
          return API().pipelineVersion(hit.id, current).then(function (v) { return { body: v.data, version: current }; });
        });
      })
      .then(function (found) {
        if (seq !== state.paramSeq || !S.dialog()) return;
        if (found.none) {
          renderParameters({}, values, name + " has no current version yet — a schedule on it is refused until one is released (or its current version is switched to a draft).");
          return;
        }
        state.pipelineVersion = found.version;
        var declared = {};
        M().declaredParameters(found.body.parameters).forEach(function (d) { declared[d.name] = d.spec; });
        state.declared = declared;
        var count = Object.keys(declared).length;
        renderParameters(declared, values, count
          ? (count === 1 ? "The parameter" : "The " + count + " parameters") + " v" + found.version +
            " (its current version) declares — a blank field takes the declared default."
          : "v" + found.version + " (its current version) declares no parameters.");
      })
      .catch(function (err) {
        if (seq !== state.paramSeq || !S.dialog()) return;
        state.declared = {};
        renderParameters({}, values, err && err.notFound
          ? "No pipeline named " + name + " that you can read in this workspace."
          : "The pipeline's parameters could not be read; the save still checks them.");
      });
  }

  function renderParameters(declared, values, status) {
    var dlg = S.dialog();
    var box = S.slot(dlg, "params");
    // What is typed NOW wins over what was captured when the read began: the read is async, and
    // a value typed while it was in flight must survive the re-render.
    values = Object.assign({}, values || {}, currentTexts());
    S.clear(box);
    paramStatus(status);
    var names = Object.keys(declared).sort();
    // A saved value the current version no longer declares stays VISIBLE (and is sent as it
    // is): dropping it silently would change the schedule behind the author's back.
    Object.keys(values || {}).forEach(function (k) {
      if (names.indexOf(k) === -1 && values[k] !== "" && values[k] !== undefined) names.push(k);
    });
    names.forEach(function (name) {
      var spec = declared[name];
      var field = S.clone("sch-tpl-param");
      field.setAttribute("data-param-name", name);
      S.text(field, "name", name);
      S.text(field, "type", spec ? spec.type : "not declared");
      S.show(S.slot(field, "required"), !!(spec && spec.required));
      var help = [];
      if (spec && spec.description) help.push(spec.description);
      if (spec && spec.default !== undefined && spec.default !== null) help.push("Default: " + M().parameterText(spec.default));
      if (!spec) help.push("The pipeline's current version does not declare this parameter.");
      S.text(field, "help", help.join(" · "));
      var input = field.querySelector("[data-param-input]");
      var select = field.querySelector("[data-param-select]");
      var id = "sch-f-param-" + name;
      field.querySelector("label").setAttribute("for", id);
      var value = values && values[name] !== undefined ? M().parameterText(values[name]) : "";
      if (spec && String(spec.type).toUpperCase() === "BOOLEAN") {
        input.remove();
        select.hidden = false;
        select.id = id;
        select.value = value;
      } else {
        select.remove();
        input.id = id;
        input.value = value;
        if (spec && (spec.type === "INTEGER" || spec.type === "DECIMAL")) input.setAttribute("inputmode", "decimal");
        if (spec && spec.type === "DATE") input.setAttribute("placeholder", "YYYY-MM-DD");
        if (spec && spec.type === "TIMESTAMP") input.setAttribute("placeholder", "2026-01-31T06:00:00Z");
        if (spec && spec.type === "TIME") input.setAttribute("placeholder", "HH:MM:SS");
      }
      box.appendChild(field);
    });
  }

  // ------------------------------------------------------------------ errors

  function showError(field, message) {
    var dlg = S.dialog();
    var p = dlg && dlg.querySelector('[data-field-error="' + field + '"]');
    if (!p) return false;
    p.textContent = message;
    p.hidden = false;
    var input = { name: "sch-f-name", pipeline: "sch-f-pipeline", cron: "sch-f-cron", timezone: "sch-f-timezone" }[field];
    if (input && el(input)) {
      el(input).classList.add("ds-input-error");
      el(input).setAttribute("aria-invalid", "true");
    }
    return true;
  }

  function clearError(field) {
    var dlg = S.dialog();
    var p = dlg && dlg.querySelector('[data-field-error="' + field + '"]');
    if (p) {
      p.hidden = true;
      p.textContent = "";
    }
    var input = { name: "sch-f-name", pipeline: "sch-f-pipeline", cron: "sch-f-cron", timezone: "sch-f-timezone" }[field];
    if (input && el(input)) {
      el(input).classList.remove("ds-input-error");
      el(input).removeAttribute("aria-invalid");
    }
  }

  function clearAllErrors() {
    ["name", "pipeline", "cron", "timezone", "missed_run_policy", "parameters"].forEach(clearError);
    var dlg = S.dialog();
    Array.prototype.forEach.call(dlg.querySelectorAll("[data-param-error]"), function (p) {
      p.hidden = true;
      p.textContent = "";
    });
  }

  /** A §20 refusal beside its field (the catalog's own message), or a toast when it names none. */
  function renderRefusal(err) {
    var target = M().fieldForError(err);
    if (!target) {
      S.toastError(err, "The schedule was not saved");
      return;
    }
    if (target.field === "parameters") {
      var placed = 0;
      (target.parameters || []).forEach(function (pe) {
        var field = pe.parameter && S.dialog().querySelector('[data-param-name="' + pe.parameter.replace(/"/g, "") + '"]');
        var p = field && field.querySelector("[data-param-error]");
        if (p) {
          p.textContent = pe.message;
          p.hidden = false;
          placed += 1;
        }
      });
      if (!placed) showError("parameters", err.userMessage || err.message);
      return;
    }
    if (!showError(target.field, err.userMessage || err.message)) S.toastError(err, "The schedule was not saved");
    var input = { name: "sch-f-name", pipeline: "sch-f-pipeline", cron: "sch-f-cron", timezone: "sch-f-timezone" }[target.field];
    if (input && el(input)) el(input).focus();
  }

  // ------------------------------------------------------------------ save

  function body() {
    var dlg = S.dialog();
    var policy = dlg.querySelector('input[name="missed_run_policy"]:checked');
    return {
      name: el("sch-f-name").value.trim(),
      executor: "pipeline",
      payload: { pipeline: el("sch-f-pipeline").value.trim(), version: "current" },
      parameters: M().collectParameters(f().declared, currentTexts()),
      cron: el("sch-f-cron").value.trim(),
      timezone: el("sch-f-timezone").value,
      missed_run_policy: policy ? policy.value : "skip",
    };
  }

  function submit() {
    var state = f();
    var dlg = S.dialog();
    if (!state || !dlg) return;
    clearAllErrors();
    S.show(S.slot(dlg, "conflict"), false);
    var b = body();
    var button = S.slot(dlg, "submit");
    button.disabled = true;
    var call;
    if (state.mode === "create") {
      state.attempt = M().nextAttempt(state.attempt, JSON.stringify(b), S.newKey);
      call = API().create(b, state.attempt.key);
    } else {
      call = API().update(state.id, b, state.etag);
    }
    var attempt = state.attempt;
    call
      .then(function (r) {
        var saved = r.data;
        S.closeDialog(true);
        S.toast("success", state.mode === "create" ? "Schedule created" : "Schedule saved",
          saved.name + (saved.next_due_at ? " — next run " + M().localText(saved.next_due_at, saved.timezone) + "." : "."));
        return S.explorer.load().then(function () { return S.detail.show(saved.id); });
      })
      .catch(function (err) {
        if (!dlg.isConnected) return;
        button.disabled = false;
        if (err.network && attempt) attempt.unanswered = true;
        if (err.code === "schedule.revision_conflict") {
          var now = err.details && err.details.current_revision;
          S.text(dlg, "conflict-text", "You opened revision " + state.revision + (now ? "; it is at revision " + now + " now" : "") +
            ". Reload it to see what changed — the form then shows the current schedule, and your unsaved changes are replaced — and save again.");
          S.show(S.slot(dlg, "conflict"), true);
          S.slot(dlg, "conflict").querySelector("button").focus();
          return;
        }
        renderRefusal(err);
      });
  }

  /** The conflict's way forward: the current schedule, re-read, in a fresh form. */
  function reload() {
    var state = f();
    if (!state || state.mode !== "edit") return;
    openEdit(state.id);
  }

  S.form = {
    openCreate: openCreate,
    openEdit: openEdit,
    reload: reload,
    refreshFolders: refreshFolders,
  };
})();
