const FAMILIES = new Set(["pipelines", "templates", "dashboards", "visualizations", "parameter-sets"]);

/** A first-party REST adapter; source validation and safe destinations stay outside the widget. */
export function restTreeSource(family, fetchPage = fetch) {
  if (!FAMILIES.has(family)) throw new Error("Unknown tree source");
  async function request(parent, query, cursor, options, signal) {
    const root = options.root || "";
    const path = parent === null ? root : parent.replace(/^folder:/, "");
    const parameters = new URLSearchParams({ root });
    if (query !== null) parameters.set("q", query); else parameters.set("parent", path);
    if (cursor !== null) parameters.set("cursor", cursor);
    const response = await fetchPage(`/api/v1/${family}/tree${query === null ? "" : "/search"}?${parameters}`, {
      signal, credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" },
    });
    if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) {
      const error = new Error("Could not load tree");
      if (response.status === 401 || (response.status === 400 && cursor !== null)) {
        error.message = "Navigation context changed. Retry to reload."; error.resetTree = true;
      }
      throw error;
    }
    const envelope = await response.json(); const data = envelope?.data;
    if (envelope.schema_version !== 1 || !data || data.family !== family || data.root !== root ||
        data.parent !== (query === null ? path : root) || data.query !== query || data.workspace_id !== options.context?.workspace ||
        data.mode !== (query === null ? "browse" : "search") || typeof data.view_token !== "string" || !Array.isArray(data.nodes)) {
      const error = new Error("Navigation context changed. Retry to reload."); error.resetTree = true; throw error;
    }
    const nodes = data.nodes.map(node => {
      if (node.href !== null && node.href !== undefined && !safeHref(node.href, family)) throw new Error("Invalid navigation destination");
      return { key: node.key, parentKey: node.parent_key, kind: node.kind, name: node.name, path: node.path,
        hasChildren: node.has_children, match: node.match, resourceId: node.resource_id, href: node.href,
        version: node.version, draftVersion: node.draft_version };
    });
    return { nodes, nextCursor: data.next_cursor,
      identity: { root, parent, query, mode: data.mode }, context: { workspace: data.workspace_id, viewToken: data.view_token } };
  }
  return {
    loadChildren(parent, cursor, context, signal) { return request(parent, null, cursor, context, signal); },
    search(query, cursor, context, signal) { return request(null, query, cursor, context, signal); },
  };
}

/** Only canonical relative family destinations; no scheme, authority, fragment or control characters. */
export function safeHref(href, family) {
  if (typeof href !== "string" || !href.startsWith(`/${family}/`) || /[\\\s#?]/.test(href)) return false;
  try {
    return href.split("/").every(part => {
      const decoded = decodeURIComponent(part);
      return decoded !== "." && decoded !== ".." && !/[\\/\s\x00-\x1f\x7f]/.test(decoded);
    });
  } catch { return false; }
}
