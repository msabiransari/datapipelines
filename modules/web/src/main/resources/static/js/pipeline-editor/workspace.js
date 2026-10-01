(function () {
  "use strict";

  /*
   * #348 — the workspace's ONE version-state source (workspace spec §3.4). The page
   * resolves a viewed version server-side and serialises it into #pipeline-workspace;
   * this module reads it once and publishes the contract every run and SQL request
   * pins to:
   *
   *   window.PEWorkspace        = { pipelineId, viewedVersion, hasBody, canExecute }
   *   window.PEWorkspaceInvalid = true   (block missing or unparsable)
   *
   * The old #pipeline-lifecycle draft pin is gone with draft.js: a run ALWAYS targets
   * the version the page is viewing — released or draft — and a missing or malformed
   * block refuses with a visible error and ZERO execute requests. It never falls back
   * to the server's execute-default, because that default (the working version) can be
   * a different body than the one the person is looking at.
   */

  function readWorkspace() {
    var el = document.getElementById("pipeline-workspace");
    if (!el) return undefined;
    try {
      return JSON.parse(el.textContent);
    } catch (e) {
      return undefined;
    }
  }

  /* The pure rule the execute path shares with the node tests: the version a run
     targets. A valid state with a positive viewed version returns it; anything else —
     a missing block, an unparsable one, a choose-a-version page — is null, which the
     execute path refuses on. Never a default. */
  function executeVersion(state) {
    if (state && typeof state.viewedVersion === "number" && state.viewedVersion > 0) {
      return state.viewedVersion;
    }
    return null;
  }

  function init() {
    var state = readWorkspace();
    if (state === undefined) {
      // The pin is unknown: running would target whatever the server defaults to while
      // the page may be showing another body. Record the refusal; execute.js stops
      // with a visible error before any request is made.
      window.PEWorkspaceInvalid = true;
      window.PEWorkspace = null;
      return;
    }
    window.PEWorkspace = state;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  window.PEWorkspaceLogic = { executeVersion: executeVersion };
})();
