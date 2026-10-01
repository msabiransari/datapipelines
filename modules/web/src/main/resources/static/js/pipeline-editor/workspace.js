(function () {
  "use strict";

  /*
   * #348 — the workspace's ONE version-state source (workspace spec §3.4); #348-b owns its
   * initialization discipline. The page resolves a viewed version server-side and
   * serialises it into #pipeline-workspace; this module validates it ONCE and publishes the
   * contract every run and SQL request pins to:
   *
   *   window.PEWorkspace        = { pipelineId, viewedVersion, hasBody, canExecute }
   *   window.PEWorkspaceInvalid = true   (block missing, unparsable, or identity mismatch)
   *
   * The old #pipeline-lifecycle draft pin is gone with draft.js: a run ALWAYS targets the
   * version the page is viewing — released or draft — and a missing or malformed block
   * refuses with a visible error and ZERO execute requests. It never falls back to the
   * server's execute-default, because that default (the working version) can be a
   * different body than the one the person is looking at.
   *
   * #348-b — ONE validated initialization path for the page's THREE arrivals: the full
   * document (script load / DOMContentLoaded), a boosted swap into the page, and a cached
   * HISTORY restoration. The third is the subtle one: a history restore brings the DOM
   * back without re-running the page's scripts, so init.js's rescue calls
   * [window.PEWorkspaceRead] to re-read the restored block — clearing any stale refusal
   * flag when a valid context comes back. Without it, a restored page would wake with no
   * pin and refuse every run and preview (the stale-refusal defect).
   */

  function readBlock() {
    var el = document.getElementById("pipeline-workspace");
    if (!el) return undefined;
    try {
      return JSON.parse(el.textContent);
    } catch (e) {
      return undefined;
    }
  }

  /**
   * The pure rule the execute and SQL paths share with the node tests: the version a run
   * or preview targets, from a state that is present, identity-bearing, body-bearing and a
   * POSITIVE BOUNDED INTEGER. `1e400` parses to Infinity and serialises back as null; a
   * fractional "version" is not a row; a state without `hasBody` is the choose-a-version
   * page. Anything else is null — a refusal, never a default.
   */
  function executeVersion(state) {
    if (!state || typeof state.pipelineId !== "string" || state.pipelineId === "") return null;
    if (state.hasBody !== true) return null;
    var v = state.viewedVersion;
    if (typeof v !== "number" || !isFinite(v) || Math.floor(v) !== v) return null;
    // Pipeline versions are Kotlin Int values on the server.
    if (v <= 0 || v > 2147483647) return null;
    return v;
  }

  /** The one read+validate+publish path; idempotent, so a re-read after restore is safe. */
  function read() {
    var state = readBlock();
    if (!state || typeof state !== "object" || Array.isArray(state)) {
      // The pin is unknown: running would target whatever the server defaults to while the
      // page may be showing another body. Record the refusal; execute.js and the SQL
      // loader stop with a visible error before any request is made.
      window.PEWorkspaceInvalid = true;
      window.PEWorkspace = null;
      return;
    }
    // Identity: the block must name the pipeline whose body the page loaded. A hybrid
    // state (a block and a data blob from different documents) is a refusal, not a pin.
    var data = document.getElementById("pipeline-data");
    if (data && state.pipelineId) {
      try {
        var pipeline = JSON.parse(data.textContent);
        if (pipeline && pipeline.id && pipeline.id !== state.pipelineId) {
          window.PEWorkspaceInvalid = true;
          window.PEWorkspace = null;
          return;
        }
      } catch (e) {
        // An unparsable data block is init.js's own refusal to record; identity stays unjudged.
      }
    }
    // A valid context clears any stale refusal left by a previous page state (#348-b).
    window.PEWorkspaceInvalid = false;
    window.PEWorkspace = state;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", read);
  } else {
    read();
  }

  window.PEWorkspaceRead = read;
  window.PEWorkspaceLogic = { executeVersion: executeVersion };
})();
