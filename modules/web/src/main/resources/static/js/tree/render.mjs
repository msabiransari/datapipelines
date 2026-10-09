/** Fixed vendored glyph, with no server supplied markup. */
export function icon(document, name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("ds-icon", "ds-icon-xs"); svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(svg.namespaceURI, "use");
  use.setAttribute("href", `/vendor/icons/lucide-sprite.svg#${name}`); svg.append(use); return svg;
}

/** Keyed reconciliation touches only groups whose children or status changed. */
export function createTreeRenderer(host, state, options) {
  const document = host.ownerDocument;
  const rows = new Map(); const groups = new Map(); const notices = new Map();
  const root = document.createElement("div"); root.className = "dp-tree-rows"; root.setAttribute("role", "tree");
  root.setAttribute("aria-label", options.label || "Items"); host.append(root); groups.set(null, root);
  let focusedKey = null; let selectedKey = options.selectedKey || null;
  function activate(node, event) {
    if (!options.getHref?.(node)) selectedKey = node.key;
    options.onSelectionChange?.(node);
    if (options.onActivate) { event.preventDefault(); options.onActivate(node, event); }
    render();
  }
  function makeRow(node) {
    const item = document.createElement("div"); item.className = "dp-tree-item"; item.setAttribute("role", "treeitem");
    item.setAttribute("data-tree-key", node.key); item.tabIndex = -1;
    const line = document.createElement("div"); line.className = "dp-tree-line"; item.append(line);
    const control = document.createElement(node.kind === "folder" ? "button" : (options.getHref?.(node) ? "a" : "button"));
    if (control.tagName === "BUTTON") control.type = "button";
    control.tabIndex = -1; control.className = "dp-tree-activate";
    control.append(icon(document, node.kind === "folder" ? "chevron-right" : "file"));
    const label = document.createElement("span"); control.append(label);
    const version = document.createElement("span"); version.className = "ds-badge ds-badge-default dp-tree-version";
    const draft = document.createElement("span"); draft.className = "ds-badge ds-badge-warning dp-tree-draft";
    draft.textContent = "draft"; draft.title = "pending release";
    control.append(version, draft); line.append(control);
    const group = document.createElement("div"); group.className = "dp-tree-group"; group.setAttribute("role", "group"); item.append(group);
    groups.set(node.key, group);
    const row = { item, control, label, version, draft, group, node };
    control.addEventListener("click", event => {
      const live = row.node; focusedKey = live.key; item.focus();
      if (live.kind === "folder") { event.preventDefault(); toggle(live.key); }
      else if (event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) activate(live, event);
    });
    item.addEventListener("focus", () => { focusedKey = row.node.key; setTabs(); });
    rows.set(node.key, row); return row;
  }
  function toggle(key) { state.open.has(key) ? state.collapse(key) : state.expand(key); }
  function visibleRows() { return Array.from(root.querySelectorAll('[role="treeitem"]')).filter(item => !item.closest("[hidden]")); }
  function setTabs() {
    const visible = visibleRows();
    if (!visible.some(item => item.getAttribute("data-tree-key") === focusedKey)) focusedKey = visible[0]?.getAttribute("data-tree-key") || null;
    visible.forEach(item => { item.tabIndex = item.getAttribute("data-tree-key") === focusedKey ? 0 : -1; });
  }
  function notice(parent, group) {
    if (state.query && parent !== null) return;
    const status = state.status(parent);
    let element = notices.get(parent);
    if (!element) {
      element = document.createElement("div"); element.className = "dp-tree-status";
      element.setAttribute("role", "status"); element.setAttribute("aria-live", "polite"); notices.set(parent, element);
    }
    const text = status.status === "loading" ? `Loading… ${status.nodes.size} loaded` :
      ["error", "incomplete"].includes(status.status) ? (status.nodes.size ? "Incomplete. " : "Could not load. ") :
      status.complete && !state.nodes(parent).length ? (state.query ? "No matches" : "No items") : "";
    if (element.dataset.status !== text) {
      element.replaceChildren(); element.append(document.createTextNode(text)); element.dataset.status = text;
      if (["error", "incomplete"].includes(status.status)) {
        const retry = document.createElement("button"); retry.type = "button"; retry.className = "ds-button ds-button-ghost ds-button-sm";
        retry.textContent = "Retry"; retry.addEventListener("click", () => state.retry(parent)); element.append(retry);
      }
    }
    if (text) { if (element.parentNode !== group || group.lastChild !== element) group.append(element); }
    else element.remove();
    group.setAttribute("aria-busy", status.status === "loading" ? "true" : "false");
  }
  function reconcile(parent, depth) {
    const group = groups.get(parent); if (!group) return;
    const nodes = state.nodes(parent); const wanted = new Set(nodes.map(node => node.key));
    Array.from(group.children).forEach(child => { if (child.hasAttribute("data-tree-key") && !wanted.has(child.getAttribute("data-tree-key"))) child.remove(); });
    nodes.forEach((node, index) => {
      const row = rows.get(node.key) || makeRow(node); row.node = node;
      if (row.label.textContent !== node.name) row.label.textContent = node.name;
      row.control.title = node.path; row.label.title = node.path;
      const number = node.draftVersion ?? node.version;
      row.version.hidden = node.kind !== "artifact" || !number;
      if (number && row.version.textContent !== `v${number}`) row.version.textContent = `v${number}`;
      row.draft.hidden = node.kind !== "artifact" || !node.draftVersion;
      if (row.draft.hidden) row.draft.remove(); else if (!row.draft.isConnected) row.control.append(row.draft);
      if (node.kind === "artifact" && row.control.tagName === "A") {
        const href = options.getHref(node); if (row.control.getAttribute("href") !== href) row.control.setAttribute("href", href);
        if (selectedKey === node.key) row.control.setAttribute("aria-current", "page"); else row.control.removeAttribute("aria-current");
      }
      row.item.setAttribute("aria-label", node.name);
      row.item.setAttribute("aria-level", String(depth)); row.item.setAttribute("aria-posinset", String(index + 1));
      const complete = state.status(parent).complete;
      row.item.setAttribute("aria-setsize", complete ? String(nodes.length) : "-1");
      row.item.setAttribute("aria-selected", selectedKey === node.key ? "true" : "false");
      if (node.kind === "folder") {
        const expanded = state.open.has(node.key); row.item.setAttribute("aria-expanded", String(expanded));
        row.group.hidden = !expanded; row.control.setAttribute("aria-label", `${expanded ? "Collapse" : "Expand"} ${node.name}`);
        if (expanded) reconcile(node.key, depth + 1);
      } else { row.group.hidden = true; row.item.removeAttribute("aria-expanded"); }
      const current = group.children[index]; if (current !== row.item) group.insertBefore(row.item, current || null);
    });
    notice(parent, group);
  }
  function render() {
    const hadFocus = root.contains(document.activeElement);
    const valid = new Set(Array.from(state.levels.values()).flatMap(level => Array.from(level.nodes.keys())));
    state.search.nodes.forEach((node, key) => valid.add(key));
    rows.forEach((row, key) => {
      if (valid.has(key)) return;
      row.item.remove(); rows.delete(key); groups.delete(key); notices.get(key)?.remove(); notices.delete(key);
    });
    reconcile(null, 1);
    rows.forEach(row => {
      if (row.node.kind === "folder") {
        row.item.setAttribute("aria-expanded", String(state.open.has(row.node.key)));
        row.group.hidden = !state.open.has(row.node.key);
      }
    });
    setTabs();
    if (hadFocus && !root.contains(document.activeElement)) rows.get(focusedKey)?.item.focus();
  }
  const keydown = event => {
    const item = event.target.closest('[role="treeitem"]'); if (!item || !root.contains(item)) return;
    const node = rows.get(item.getAttribute("data-tree-key"))?.node; if (!node) return;
    const visible = visibleRows(); const index = visible.indexOf(item); let next = null;
    if (event.key === "ArrowDown") next = visible[Math.min(index + 1, visible.length - 1)];
    else if (event.key === "ArrowUp") next = visible[Math.max(index - 1, 0)];
    else if (event.key === "Home") next = visible[0];
    else if (event.key === "End") next = visible.at(-1);
    else if (event.key === "ArrowRight" && node.kind === "folder") {
      if (!state.open.has(node.key)) state.expand(node.key); else next = groups.get(node.key)?.querySelector('[role="treeitem"]');
    } else if (event.key === "ArrowLeft") {
      if (node.kind === "folder" && state.open.has(node.key)) state.collapse(node.key); else next = rows.get(node.parentKey)?.item;
    } else if (event.key === "Enter" || event.key === " ") {
      rows.get(node.key).control.click();
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const ordered = visible.slice(index + 1).concat(visible.slice(0, index + 1));
      next = ordered.find(row => rows.get(row.getAttribute("data-tree-key"))?.node.name.toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()));
    } else return;
    event.preventDefault(); next?.focus();
  };
  root.addEventListener("keydown", keydown);
  return { render, focusFirst() { visibleRows()[0]?.focus(); }, select(key) { selectedKey = key; render(); }, dispose() { root.removeEventListener("keydown", keydown); root.remove(); rows.clear(); groups.clear(); } };
}
