// #622 — the shared MCP card copies its server-rendered placeholder config, without a request.
// Document delegation also serves cards brought in by boosted navigation; bind once per document.
import { copyText } from "./copy-text.mjs";

const COPIED_STATUS = "Config copied to the clipboard.";
const MANUAL_STATUS = "Copy is not available here. The config is selected: press Ctrl+C (⌘C on a Mac) to copy it.";
const MANUAL_LABEL = "Select and copy";

/** The nearest card's Copy button, pre and live status, or null for an unrelated/incomplete target. */
export function configParts(target) {
  const button = target?.closest?.("[data-mcp-copy]");
  const root = button?.closest("[data-mcp-connection]");
  const pre = root?.querySelector("pre");
  const status = root?.querySelector("[data-mcp-copy-status]");
  return button && pre && status ? { button, pre, status } : null;
}

/** Copies only the clicked card's pre text; a refused clipboard selects that pre for manual copy. */
export function onClick(event) {
  const parts = configParts(event.target);
  if (!parts) return;
  const { pre, button, status } = parts;
  return copyText(pre.textContent, {
    button, status,
    select: () => globalThis.getSelection()?.selectAllChildren(pre),
    copiedStatus: COPIED_STATUS, manualStatus: MANUAL_STATUS, manualLabel: MANUAL_LABEL,
  }, globalThis.navigator?.clipboard);
}

if (globalThis.document?.addEventListener && !globalThis.DatapipelinesMcpConnection) {
  globalThis.DatapipelinesMcpConnection = { onClick };
  document.addEventListener("click", onClick);
}
