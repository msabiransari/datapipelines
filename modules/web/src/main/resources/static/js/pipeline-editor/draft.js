(function () {
  "use strict";

  /*
   * The draft lifecycle affordances of the pipeline editor (versioning §3.5/§7):
   * a pending-release banner and two human actions — Release and Discard — both
   * hash-guarded through the SAME REST endpoints and the SAME CSRF double-submit
   * pair every other cookie-authenticated state-changing fetch on this page uses
   * (pipeline-editor.md §7.2). Agents never release (D4); this is the human half.
   *
   * `window.PEDraft` is the read side: {version, bodyHash} when the editor is
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

  function request(url, method, ifMatch) {
    return fetch(url, {
      method: method,
      headers: {
        "DP-CSRF-Token": readCookie("dp_csrf"),
        "If-Match": ifMatch,
      },
      credentials: "same-origin",
    }).then(function (response) {
      if (response.ok) return response;
      return response
        .json()
        .catch(function () { return {}; })
        .then(function (err) {
          var detail = err && err.error ? err.error.message : "HTTP " + response.status;
          var code = err && err.error ? err.error.code : "";
          throw new Error(detail + (code ? " (" + code + ")" : ""));
        });
    });
  }

  function wireButtons(lifecycle) {
    var release = document.getElementById("pe-release-draft");
    var discard = document.getElementById("pe-discard-draft");

    if (release) {
      release.addEventListener("click", function () {
        release.disabled = true;
        request("/api/v1/pipelines/" + encodeURIComponent(release.getAttribute("data-id")) + "/release", "POST", lifecycle.draftHash)
          .then(function () {
            window.location.reload();
          })
          .catch(function (err) {
            release.disabled = false;
            window.alert("Release refused: " + err.message);
          });
      });
    }

    if (discard) {
      discard.addEventListener("click", function () {
        if (!window.confirm("Discard this draft? An executed draft is kept as history (DISCARDED); a never-executed one is deleted and its version number returns to the pool.")) {
          return;
        }
        discard.disabled = true;
        request("/api/v1/pipelines/" + encodeURIComponent(discard.getAttribute("data-id")) + "/draft/discard", "POST", lifecycle.draftHash)
          .then(function () {
            window.location.reload();
          })
          .catch(function (err) {
            discard.disabled = false;
            window.alert("Discard refused: " + err.message);
          });
      });
    }
  }

  function init() {
    var lifecycle = readLifecycle();
    window.PEDraft = executeVersion(lifecycle)
      ? { version: lifecycle.draftVersion, bodyHash: lifecycle.draftHash }
      : null;
    wireButtons(lifecycle);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  window.PEDraftLogic = { executeVersion: executeVersion };
})();
