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
  window.PEWorkspaceLogic = {
    executeVersion: executeVersion,

    /**
     * #349 — the run-input drafts' PER-VERSION store within the page (spec §4.3:
     * "Run input drafts are per pipeline/version within the page. Changing schema
     * cannot reuse another version's fields. Revisit restores compatible overrides").
     * `save(version, overrides)` snapshots one version's bag; `load(version,
     * schemaKeys)` rebuilds the working bag for a version's OWN schema — a key the
     * stored bag knew but the new schema does not declare is DROPPED (an incompatible
     * field never leaks across a version change), and a key the schema declares that
     * the bag never saw is the empty (unsupplied) string. Values never leave the page:
     * no localStorage, no navigation URL, no history.
     */
    createOverrideStore: function () {
      var maps = {};
      return {
        save: function (version, overrides) {
          if (version === null || version === undefined) return;
          var copy = {};
          Object.keys(overrides || {}).forEach(function (k) {
            copy[k] = overrides[k];
          });
          maps[version] = copy;
        },
        load: function (version, schemaKeys) {
          var stored = maps[version] || {};
          var out = {};
          (schemaKeys || []).forEach(function (k) {
            out[k] = Object.prototype.hasOwnProperty.call(stored, k) ? stored[k] : "";
          });
          return out;
        },
      };
    },

    /**
     * #349 — the page/view state key's staleness rule (spec §4.3: "Page/view state
     * key: workspace + pipeline + viewed version + request generation. Per-tab requests
     * and selected node belong to it. Changing any key invalidates pending completions
     * and stale errors/toasts as well as successes"). A response whose stamp disagrees
     * with the CURRENT state on any key the request carried is stale and must not be
     * applied — including A→B→A (the second A is a NEW generation; the first A's late
     * response is still stale). A key the request did not stamp is not compared, so an
     * entity-wide tab read (runs/usage — no version of their own) guards on pipeline
     * and generation alone.
     */
    stale: function (stamp, current) {
      if (!stamp || !current) return true;
      if (stamp.pipelineId !== undefined && stamp.pipelineId !== current.pipelineId) return true;
      if (stamp.version !== undefined && stamp.version !== current.version) return true;
      if (stamp.generation !== undefined && stamp.generation !== current.generation) return true;
      return false;
    },

    /**
     * The sink-token rule for the pane reads that arrive as htmx swaps (node SQL,
     * checks, runs, usage): the request stamps its sink with the token it was issued
     * under; a response is swapped only when the sink STILL carries the token the
     * component currently expects. A newer request for the same sink replaces both
     * stamps, so the older response — success or failure — is cancelled before it can
     * paint (A8: hold A's response, select B, release A: no B overwrite, no A error).
     */
    tokenMatches: function (recorded, current) {
      return typeof recorded === "string" && recorded !== "" && recorded === current;
    },
  };
})();
