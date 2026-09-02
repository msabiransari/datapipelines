/*
 * 041 — highlight the template editor's PREVIEW OUTPUT with the shared
 * dependency-free SQL tokenizer (js/pipeline-editor/sql-highlight.js, 032).
 *
 * The preview output is rendered SQL in a read-only <pre>, which is exactly
 * what the tokenizer was built for. The editable textarea is deliberately NOT
 * highlighted (041 D5): highlighting an editing surface means an overlay or
 * contenteditable, which drags caret handling, scroll sync, IME and paste
 * behaviour with it — its own round with its own risks.
 *
 * The render endpoint returns three shapes (TemplateEditorController):
 *   success  <div class="ds-card"><pre>escaped SQL</pre></div>
 *   empty    <div class="ds-card"><pre>(empty output)</pre></div>
 *   error    <div class="ds-card"><p>message</p></div>
 * Only <pre> blocks are touched: the error shape has none, and the empty
 * output tokenizes to plain text (no keywords), so it renders unchanged.
 *
 * textContent of the swapped-in <pre> is the UNescaped SQL (the browser
 * decoded the entities); highlight() re-escapes each token as it emits — the
 * same order-of-operations contract sql-highlight.js's apply() relies on.
 */
(function () {
  "use strict";

  function highlightPreview(pane) {
    if (!pane || !pane.querySelectorAll) return pane;
    if (typeof window !== "undefined" && !window.DpSqlHighlight) return pane;
    var blocks = pane.querySelectorAll("pre");
    for (var i = 0; i < blocks.length; i++) {
      blocks[i].innerHTML = window.DpSqlHighlight.highlight(blocks[i].textContent);
    }
    return pane;
  }

  var api = { highlightPreview: highlightPreview };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.TplPreviewHighlight = api;
})();
