/*
 * 188 (#188) — the API keys screen: the create modal's open/close, the kind→bindings and
 * expiry→date syncs, the arm-on-arrival init, and the one-time secret's Copy button
 * (partials/api-key-created.html, swapped in by htmx after a mint). Until 188 this was
 * an inline <script> at the foot of templates/api/keys.html and five controls were
 * `onclick`/`onchange` attributes; the enforced CSP (SecurityHeaders: `script-src
 * 'self'`, no 'unsafe-inline') moved the body here UNCHANGED and the attributes became
 * `data-action` values read by the delegated listeners at the end of this file.
 *
 * Loaded from the page's content fragment, so under hx-boost it is re-evaluated on
 * every arrival — exactly as the inline script was (076 §D). The document-level
 * listeners are armed ONCE (window flag, shell.js's own pattern); the per-arrival
 * init re-runs because the form and the created-panel are new elements each time.
 */
(function () {
  "use strict";
  function showKeyModal() {
    document.getElementById('key-modal').style.display = 'flex';
  }
  function hideKeyModal() {
    document.getElementById('key-modal').style.display = 'none';
  }
  // The kind decides whether associations exist at all (auth §7.7): bindings on a server
  // key are REFUSED by the server, not dropped, so the form must not carry any. Disabled
  // inputs are not submitted, which is the point — hiding alone would still POST a stale
  // value.
  function keyKindChanged() {
    var checked = document.querySelector('#createKeyForm input[name=kind]:checked');
    var wantsBindings = checked && checked.getAttribute('data-bindings') === 'true';
    var bindingsField = document.getElementById('key-bindings-field');
    bindingsField.classList.toggle('u-hidden', !wantsBindings);
    var boxes = bindingsField.querySelectorAll('input[name=bindings]');
    for (var i = 0; i < boxes.length; i++) { boxes[i].disabled = !wantsBindings; }
  }
  function keyExpiryChanged() {
    var custom = document.getElementById('key-expiry').value === 'custom';
    var date = document.getElementById('key-expiry-date');
    date.classList.toggle('u-hidden', !custom);
    date.disabled = !custom;
  }
  // 076 §D: arm immediately when this fragment arrives in a boosted swap
  // (DOMContentLoaded has long fired); wait for the parse only on a cold load.
  var initKeyForm = function() {
    if (!document.getElementById('createKeyForm')) return;
    keyKindChanged();
    keyExpiryChanged();
    var created = document.getElementById('keyCreated');
    if (!created) return;
    var observer = new MutationObserver(function() {
      // Condition on CONTENT ARRIVING, never on the panel still being hidden: the second
      // mint of a session finds the panel already visible. The OOB swap already refreshed
      // the table and pointed a toast at this panel — no refetch; just select the secret.
      if (created.children.length > 0) {
        created.classList.remove('u-hidden');
        hideKeyModal();
        document.getElementById('createKeyForm').reset();
        keyKindChanged();
        keyExpiryChanged();
        setTimeout(function() {
          var secret = created.querySelector('input');
          if (secret) secret.select();
        }, 50);
      }
    });
    observer.observe(created, {childList: true, subtree: true});
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initKeyForm);
  } else {
    initKeyForm();
  }

  if (!window.__dpApiKeysArmed) {
    window.__dpApiKeysArmed = true;
    document.body.addEventListener("click", function (evt) {
      var el = evt.target.closest && evt.target.closest("[data-action]");
      if (!el) return;
      var action = el.getAttribute("data-action");
      if (action === "key-modal-open") { evt.preventDefault(); showKeyModal(); }
      else if (action === "key-modal-close") { evt.preventDefault(); hideKeyModal(); }
      else if (action === "copy-previous-value") {
        // The one-time secret panel: the field before the button holds the plaintext.
        evt.preventDefault();
        var field = el.previousElementSibling;
        if (field && navigator.clipboard) navigator.clipboard.writeText(field.value);
      }
    });
    document.body.addEventListener("change", function (evt) {
      var t = evt.target;
      if (!t) return;
      if (t.name === "kind" && t.closest && t.closest("#createKeyForm")) keyKindChanged();
      else if (t.id === "key-expiry") keyExpiryChanged();
    });
  }
})();
