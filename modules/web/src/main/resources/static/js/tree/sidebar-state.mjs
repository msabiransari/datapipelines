/**
 * #554 — the resource browser's pure decisions, kept apart from the DOM so `node --test`
 * drives them (src/test/js/sidebar-state.test.mjs).
 *
 * The browsable family a path belongs to: the menu's segment-boundary rule (shell.js
 * `isActiveSection`, the layout's `AppNav` answer), over the families the browser
 * hosts; a family's overview (#556: `/overview/<family>`) belongs to the family. Derived,
 * never stored (record §3 rules 4–6): null on any other page.
 */
export function familyForPath(path, families) {
  if (typeof path !== "string" || !path) return null;
  return families.find(family => ownsPath(`/${family}`, path) || ownsPath(`/overview/${family}`, path)) ?? null;
}

function ownsPath(prefix, path) {
  return path === prefix || path.indexOf(prefix + "/") === 0;
}

/**
 * The storage keys the in-rail accordion wrote, removed on sight and never reinterpreted
 * (record §4): the open-family key (#478) and the per-family panel flags it superseded.
 */
export function retiredTreeKeys(workspace, families) {
  if (!workspace) return [];
  return [`dp-tree-open:${workspace}`, ...families.map(family => `dp-tree-panel:${workspace}:${family}`)];
}

/** The folder button's family-specific name (record §3 rule 7). */
export function browseLabel(label) {
  return `Browse ${label || "resources"}`;
}
