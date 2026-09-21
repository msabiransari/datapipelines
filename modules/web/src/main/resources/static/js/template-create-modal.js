/*
 * 188 (#188) — the templates screen's create modal: open/close, the type→dialect sync and
 * the arm-on-arrival init. Until 188 this was an inline <script> at the foot of
 * templates/templates/list.html and the three controls were `onclick`/`onchange`
 * attributes; the enforced CSP (SecurityHeaders: `script-src 'self'`, no
 * 'unsafe-inline') moved the body here UNCHANGED and the attributes became
 * `data-action` values read by the delegated listeners at the end of this file.
 *
 * Loaded from the page's content fragment (not the layout), so under hx-boost it is
 * re-evaluated on every arrival — exactly as the inline script was (076 §D). The
 * document-level listeners are armed ONCE (window flag, shell.js's own pattern); the
 * per-arrival init re-runs because the modal, its result node and its form are new
 * elements each time.
 */
(function () {
  "use strict";
  function showCreateTemplateModal() {
    document.getElementById('template-create-result').removeAttribute('data-error');
    document.getElementById('template-create-result').innerHTML = '';
    document.getElementById('create-template-modal').style.display = 'flex';
  }
  function hideCreateTemplateModal() {
    document.getElementById('create-template-modal').style.display = 'none';
  }
  /* §9.3: dialect is required for `sql` and ABSENT for `html`. `disabled` (not just hidden)
     is what keeps the field out of the submitted form data — a hidden-but-enabled select
     would still post a dialect for an html template. The server drops it either way. */
  function syncTemplateDialect() {
    // 114: a viewer's page carries no create modal, so these three nodes are absent.
    // Returning is the whole handling — there is no form to keep in sync.
    var type = document.getElementById('create-template-type');
    var field = document.getElementById('create-template-dialect-field');
    var select = document.getElementById('create-template-dialect');
    if (!type || !field || !select) return;
    var isSql = type.value === 'sql';
    field.hidden = !isSql;
    select.disabled = !isSql;
    select.required = isSql;
  }
  // 076 §D: arm immediately when this fragment arrives in a boosted swap
  // (DOMContentLoaded has long fired); wait for the parse only on a cold load.
  var initCreateTemplateModal = function() {
    syncTemplateDialect();
    var result = document.getElementById('template-create-result');
    if (!result) return;

    /* Refusals arrive as 4xx and htmx does not swap those. The modal must not close over an
       error and a 6s toast is the wrong place for form-level feedback (the 022 review F9
       rule), so this screen owns its error path: the refusal lands in the result node,
       tagged so the observer below does NOT close the modal over it. */
    document.body.addEventListener('htmx:responseError', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#create-template-modal')) return;
      result.setAttribute('data-error', 'true');
      result.innerHTML = event.detail.xhr.responseText;
    });
    document.body.addEventListener('htmx:beforeRequest', function(event) {
      if (!event.detail.elt.closest || !event.detail.elt.closest('#create-template-modal')) return;
      result.removeAttribute('data-error');
      result.innerHTML = '';
    });

    /* Close the modal only on SUCCESS content — never over an error. */
    var observer = new MutationObserver(function() {
      var modal = document.getElementById('create-template-modal');
      if (modal.style.display === 'flex' &&
          result.children.length > 0 &&
          result.getAttribute('data-error') !== 'true') {
        hideCreateTemplateModal();
      }
    });
    observer.observe(result, {childList: true, subtree: true});
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCreateTemplateModal);
  } else {
    initCreateTemplateModal();
  }

  if (!window.__dpTemplateCreateModalArmed) {
    window.__dpTemplateCreateModalArmed = true;
    document.body.addEventListener("click", function (evt) {
      var el = evt.target.closest && evt.target.closest("[data-action]");
      if (!el) return;
      var action = el.getAttribute("data-action");
      if (action === "template-create-open") { evt.preventDefault(); showCreateTemplateModal(); }
      else if (action === "template-create-close") { evt.preventDefault(); hideCreateTemplateModal(); }
    });
    document.body.addEventListener("change", function (evt) {
      if (evt.target && evt.target.id === "create-template-type") syncTemplateDialect();
    });
  }
})();
