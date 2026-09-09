(function () {
  "use strict";

  /*
   * The pipeline editor's DRAFT READ SIDE (versioning §3.5/§7). Since 102 the
   * ACTIONS are dialogs — the topbar's Release / Purge draft buttons hx-get the
   * §4.3d partials into #pe-dialog, and the POST answers HX-Redirect with a flash
   * toast; the fetch/confirm/alert path this file carried is gone (a static test
   * pins zero native dialogs), and with it the pre-101 discard text that had
   * become false.
   *
   * `window.PEDraft` remains the read side: {version, bodyHash} when the editor is
   * showing a draft, null otherwise. The execute path (sse.js) reads it to pin the
   * run to the draft version — running a draft is the expected review loop, and
   * the run is recorded against the real draft version number by the composite FK.
   */
  function readCookie(name) {
    var match = document.cookie.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]*)"));
    return match ? decodeURIComponent(match[1]) : null;
  }

  function readLifecycle() {
    var el = document.getElementById("pipeline-lifecycle");
    if (!el) return { hasDraft: false };
    try {
      return JSON.parse(el.textContent);
    } catch (e) {
      return { hasDraft: false };
    }
  }

  /* The pure rule the execute path shares with the node tests: which version a run
     targets. A loaded draft pins the run to the draft; otherwise the server's
     execute-default (latest RELEASED) applies and NO version is sent. */
  function executeVersion(lifecycle) {
    if (lifecycle && lifecycle.hasDraft && lifecycle.draftVersion) {
      return lifecycle.draftVersion;
    }
    return null;
  }

  function init() {
    var lifecycle = readLifecycle();
    window.PEDraft = executeVersion(lifecycle)
      ? { version: lifecycle.draftVersion, bodyHash: lifecycle.draftHash }
      : null;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  window.PEDraftLogic = { executeVersion: executeVersion };
})();
