/*
 * 079 §A / 188 — the rail's collapsed state, applied BEFORE the first paint.
 *
 * A PARSER-BLOCKING script in <head> (no `defer`, no `async`): it runs before <body>
 * exists, so the class lands on <html> and the rail renders at its remembered width
 * in the FIRST frame — a deferred script would paint 232px and snap to 60px in front
 * of the user on every navigation. Until 188 this was the layout's one inline
 * <script>; the enforced CSP (SecurityHeaders: `script-src 'self'`, no
 * 'unsafe-inline') moved it here unchanged, where it is also inside the audits that
 * read `static/js`. shell.js toggles the same classes on the same element, so there
 * is one source of truth for them.
 *
 * `dp-rail` = "1" → collapsed; "0" → explicitly expanded (110: from 768 to 1099px the
 * default is collapsed by a media rule, and a user who expanded it there must not be
 * re-collapsed by that default). Below 768 neither class matters — the rail is an
 * off-canvas drawer. Wrapped in try/catch: localStorage throws outright in some
 * privacy modes, and a shell that cannot render because a preference could not be
 * read is a worse failure than a rail that opens.
 */
try {
  var dpRail = localStorage.getItem("dp-rail");
  if (dpRail === "1") document.documentElement.classList.add("rail-collapsed");
  else if (dpRail === "0") document.documentElement.classList.add("rail-expanded");
} catch (e) {}
