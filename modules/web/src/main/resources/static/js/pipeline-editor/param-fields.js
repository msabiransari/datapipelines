(function () {
  "use strict";

  /*
   * 195 — the Parameters sidebar's per-field view model, extracted from the
   * template expressions the Alpine CSP build can no longer spell.
   *
   * The template used to compute, per `key` of `paramKeys`: the placeholder
   * (default / required / optional), the description fallback, the type badge,
   * and the "is an override present" branch, all inline with operators and
   * literals. The CSP build's expressions are pure property paths, so every one
   * of those strings and branches is materialized HERE, once at init, and the
   * template reads `field.placeholderText` and friends.
   *
   * PURE — no DOM, no Alpine, no fetch — so `node --test` owns it
   * (param-fields.test.mjs), the same harness decision dock.js gets.
   */

  /**
   * One field per parameter key, in Object.keys order (the order `paramKeys`
   * had, which the list rendered in). `hasOverride`/`hasNoOverride` preserve the
   * old template's pair of branches verbatim: init() pre-seeds every key's
   * override to "", so the input branch was ALWAYS the taken one and the muted
   * branch never rendered — that shape is kept exactly, not "fixed" here.
   */
  function buildParamFields(parameters) {
    var fields = [];
    Object.keys(parameters || {}).forEach(function (key) {
      var p = parameters[key] || {};
      /* Truthiness preserved from the template: a default of 0 or false or ""
         falls through to Required/Optional exactly as `p.default ?` did. */
      var placeholder;
      if (p.default) {
        placeholder = "Default: " + JSON.stringify(p.default);
      } else if (p.required) {
        placeholder = "Required";
      } else {
        placeholder = "Optional";
      }
      fields.push({
        key: key,
        type: p.type,
        descriptionText: p.description || "—",
        placeholderText: placeholder,
        hasOverride: true,
        hasNoOverride: false,
        override: "",
      });
    });
    return fields;
  }

  /**
   * One keystroke: the input's value lands in BOTH places the page reads —
   * `parameterOverrides[key]` (the execute and SQL-render paths are unchanged)
   * and the field's own `override` (the value binding the template displays).
   * Returns true when the key was known; an unknown key is ignored, never
   * invented (the old x-model could only bind declared keys).
   */
  function applyParameterInput(editor, key, value) {
    if (!editor || !key) return false;
    if (!Object.prototype.hasOwnProperty.call(editor.parameterOverrides, key)) return false;
    editor.parameterOverrides[key] = value;
    var fields = editor.paramFields || [];
    for (var i = 0; i < fields.length; i++) {
      if (fields[i].key === key) {
        fields[i].override = value;
        return true;
      }
    }
    return false;
  }

  var api = {
    buildParamFields: buildParamFields,
    applyParameterInput: applyParameterInput,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PEParamFields = api;
})();
