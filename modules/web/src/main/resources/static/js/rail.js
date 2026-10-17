/*
 * 079 §A / 188 — the menu's collapsed state, applied BEFORE the first paint; #554 adds the
 * resource browser's two presentation choices.
 *
 * A PARSER-BLOCKING script in <head> (no `defer`, no `async`): it runs before <body>
 * exists, so the class lands on <html> and the menu renders at its remembered width
 * in the FIRST frame — a deferred script would paint 202px and snap to 64px in front
 * of the user on every navigation. Until 188 this was the layout's one inline
 * <script>; the enforced CSP (SecurityHeaders: `script-src 'self'`, no
 * 'unsafe-inline') moved it here unchanged, where it is also inside the audits that
 * read `static/js`. shell.js toggles the same classes on the same element, so there
 * is one source of truth for them.
 *
 * `dp-rail` = "1" → collapsed; "0" → explicitly expanded (110: from 761 to 1100px the
 * default is compact by a media rule, and a user who expanded it there must not be
 * re-collapsed by that default). At 760px and below neither class matters — the menu is
 * an off-canvas drawer. Wrapped in try/catch: localStorage throws outright in some
 * privacy modes, and a shell that cannot render because a preference could not be
 * read is a worse failure than a menu that opens.
 */
try {
  var dpRail = localStorage.getItem("dp-rail");
  if (dpRail === "1") document.documentElement.classList.add("rail-collapsed");
  else if (dpRail === "0") document.documentElement.classList.add("rail-expanded");
} catch (e) {}

/*
 * #554 — the resource browser, per workspace (record §4 "Browser resizing"): the PREFERRED
 * width (240–480; anything else is not a preference and is removed) and the user's Hide
 * ("1"; anything else is removed). Both are presentation only — a width and a boolean, never
 * a query, a path or a name. The retired keys go on sight and are never reinterpreted:
 * `dp-rail-width:<ws>` measured the rail+tree composition, not this column.
 * browser-resize.mjs writes the width and shell.js the Hide, under the same rules.
 */
try {
  var dpRoot = document.documentElement;
  var dpWorkspace = dpRoot.getAttribute("data-dp-workspace");
  if (dpWorkspace) {
    localStorage.removeItem("dp-rail-width:" + dpWorkspace);
    var dpWidthKey = "dp-browser-width:" + dpWorkspace;
    var dpWidthRaw = localStorage.getItem(dpWidthKey);
    var dpWidth = Number(dpWidthRaw);
    if (dpWidthRaw !== null && /^\d+$/.test(dpWidthRaw) && dpWidth >= 240 && dpWidth <= 480) {
      dpRoot.style.setProperty("--app-browser-preferred", dpWidth + "px");
    } else if (dpWidthRaw !== null) {
      localStorage.removeItem(dpWidthKey);
    }
    var dpHiddenKey = "dp-browser-hidden:" + dpWorkspace;
    var dpHidden = localStorage.getItem(dpHiddenKey);
    if (dpHidden === "1") dpRoot.classList.add("browser-hidden");
    else if (dpHidden !== null) localStorage.removeItem(dpHiddenKey);
  }
} catch (e) {}
