// Clipboard-only text copying shared by the overview prompt and MCP connection card (#622).
export const COPIED_MS = 1500;

/** Copies text; on an absent/refused clipboard calls select and resolves "manual" instead. */
export async function copyText(text, { button, status, select, copiedStatus, manualStatus, manualLabel }, clipboard, schedule = setTimeout) {
  const label = button.getAttribute("data-copy-label") || button.textContent;
  try {
    if (!clipboard || typeof clipboard.writeText !== "function") throw new Error("clipboard unavailable");
    await clipboard.writeText(text);
  } catch (_) {
    select();
    button.textContent = manualLabel;
    status.textContent = manualStatus;
    return "manual";
  }
  button.textContent = "Copied";
  status.textContent = copiedStatus;
  schedule(() => { button.textContent = label; }, COPIED_MS);
  return "copied";
}
