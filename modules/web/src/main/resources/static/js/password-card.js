/*
 * 188 (#188) — the password card's error path (partials/password-card.html, rendered on
 * settings/password.html and the forced-change gate). Until 188 this was an inline
 * <script> inside the partial; the enforced CSP (SecurityHeaders: `script-src 'self'`,
 * no 'unsafe-inline') moved the body here UNCHANGED. Loaded from the partial itself,
 * so it runs wherever the card renders — a cold load or a boosted arrival (076 §D).
 */
(function () {
  "use strict";
  // 076 §D: arm immediately when this fragment arrives in a boosted swap
  // (DOMContentLoaded has long fired); wait for the parse only on a cold load.
  var initPasswordForm = function() {
    var result = document.getElementById('password-change-result');
    // htmx never swaps 4xx, and these refusals deliberately carry no
    // HX-Retarget — this screen owns its error path (see the form's comment).
    document.body.addEventListener('htmx:responseError', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#password-change-form')) return;
      result.innerHTML = event.detail.xhr.responseText;
    });
    // A fresh attempt clears the previous error.
    document.body.addEventListener('htmx:beforeRequest', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#password-change-form')) return;
      result.innerHTML = '';
    });
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initPasswordForm);
  } else {
    initPasswordForm();
  }
})();
