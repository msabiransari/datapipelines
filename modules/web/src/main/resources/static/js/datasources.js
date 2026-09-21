/*
 * 188 (#188) — the datasources screen: the register modal's open/close, the #ds-dialog
 * close path (the edit / delete / facts / grants dialogs htmx fetches into it), and the
 * arm-on-arrival inits. Until 188 this was an inline <script> at the foot of
 * templates/datasources/list.html and six controls (two here, one in each of the four
 * dialog partials) were `onclick` attributes; the enforced CSP (SecurityHeaders:
 * `script-src 'self'`, no 'unsafe-inline') moved the body here UNCHANGED and the
 * attributes became `data-action` values read by the delegated listener at the end of
 * this file — delegated precisely because the dialogs' close buttons arrive by swap.
 *
 * Loaded from the page's content fragment, so under hx-boost it is re-evaluated on
 * every arrival — exactly as the inline script was (076 §D). The document-level
 * listener is armed ONCE (window flag, shell.js's own pattern); the per-arrival inits
 * re-run because the modal and the dialog container are new elements each time.
 */
(function () {
  "use strict";
  // 094 §A/§B: the edit and delete dialogs live in #ds-dialog. Emptying it is the ONLY
  // close path — no display toggling, so the container can never be left holding a hidden
  // dialog whose stale form would post on the next Enter.
  function closeDsDialog() {
    document.getElementById('ds-dialog').innerHTML = '';
  }

  function showRegisterModal() {
    document.getElementById('register-result').removeAttribute('data-error');
    document.getElementById('register-result').innerHTML = '';
    document.getElementById('register-modal').style.display = 'flex';
  }
  function hideRegisterModal() {
    document.getElementById('register-modal').style.display = 'none';
  }
  // 076 §D: arm immediately when this fragment arrives in a boosted swap
  // (DOMContentLoaded has long fired); wait for the parse only on a cold load.
  var initRegisterModal = function() {
    // 114: a role below `ws_admin` gets no register modal at all — nothing to arm.
    var result = document.getElementById('register-result');
    if (!result) return;

    // Refusals arrive as 4xx and htmx does not swap those. The layout now carries
    // toast.js's bridgeErrors, which admits a 4xx retargeted at #toast by header —
    // but the register refusal deliberately carries NO HX-Retarget: this screen
    // still owns its error path because the modal must not close over an error,
    // and a 6s toast is the wrong place for form-level feedback (022 review F9).
    // Handle the failure explicitly: the refusal markup lands in #register-result,
    // tagged so the observer below does NOT close the modal over it.
    document.body.addEventListener('htmx:responseError', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#register-modal')) return;
      result.setAttribute('data-error', 'true');
      result.innerHTML = event.detail.xhr.responseText;
    });

    // A fresh attempt clears the previous refusal, so a later success is not masked by it.
    document.body.addEventListener('htmx:beforeRequest', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#register-modal')) return;
      result.removeAttribute('data-error');
      result.innerHTML = '';
    });

    // Close the modal only on SUCCESS content — never over an error (F9).
    var observer = new MutationObserver(function() {
      var modal = document.getElementById('register-modal');
      if (modal.style.display === 'flex' &&
          result.children.length > 0 &&
          result.getAttribute('data-error') !== 'true') {
        hideRegisterModal();
      }
    });
    if (result) observer.observe(result, {childList: true, subtree: true});
  };
  // 094: the edit/delete dialogs use the SAME success/refusal contract as the register
  // modal (022/F9) — a 4xx refusal renders inline and must NOT close the dialog, while the
  // success node's arrival closes it. The dialog's markup is fetched, so this listener is
  // delegated on document.body rather than bound to an element that may not exist yet.
  var initDsDialog = function() {
    var container = document.getElementById('ds-dialog');

    document.body.addEventListener('htmx:responseError', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#ds-dialog')) return;
      var result = container.querySelector('#ds-dialog-result');
      if (!result) return;
      result.setAttribute('data-error', 'true');
      result.innerHTML = event.detail.xhr.responseText;
    });

    document.body.addEventListener('htmx:beforeRequest', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#ds-dialog')) return;
      var result = container.querySelector('#ds-dialog-result');
      if (!result) return;
      result.removeAttribute('data-error');
      result.innerHTML = '';
    });

    // The success node carries data-ds-saved; the refusal never does. Closing on the
    // ATTRIBUTE rather than on "the result box is non-empty" is what keeps a refusal on
    // screen — the same distinction the register modal draws with data-error.
    document.body.addEventListener('htmx:afterSwap', function(event) {
      if (!container.contains(event.target)) return;
      if (event.target.querySelector('[data-ds-saved]')) closeDsDialog();
    });
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      initRegisterModal();
      initDsDialog();
    });
  } else {
    initRegisterModal();
    initDsDialog();
  }

  if (!window.__dpDatasourcesArmed) {
    window.__dpDatasourcesArmed = true;
    document.body.addEventListener("click", function (evt) {
      var el = evt.target.closest && evt.target.closest("[data-action]");
      if (!el) return;
      var action = el.getAttribute("data-action");
      if (action === "datasource-register-open") { evt.preventDefault(); showRegisterModal(); }
      else if (action === "datasource-register-close") { evt.preventDefault(); hideRegisterModal(); }
      else if (action === "ds-dialog-close") { evt.preventDefault(); closeDsDialog(); }
    });
  }
})();
