// #556 — the family overview's one interaction: Copy prompt (templates/overview/guide-*.html).
//
// The prompt is the textarea's CURRENT text, edits included, and it goes to the clipboard and
// nowhere else: no request, no create call, no send (shell design §8). Success relabels the button
// "Copied" for a moment and says so in the status line; a refused or absent clipboard (a non-secure
// origin, a denied permission) selects the text instead, relabels the button "Select and copy" and
// says how to finish by hand. No path throws to the console.
//
// Loaded through the page's <template data-page-assets> catalog (page-assets.mjs), once per
// document: the click listener is document-level, so it serves every overview a boosted
// navigation brings in without re-binding.

export const COPIED_MS = 1500;
export const COPIED_STATUS = "Prompt copied to the clipboard.";
export const MANUAL_STATUS = "Copy is not available here. The prompt is selected: press Ctrl+C (⌘C on a Mac) to copy it.";
export const MANUAL_LABEL = "Select and copy";

/** Copies [field]'s text through [clipboard]; resolves "copied" or "manual", never rejects. */
export async function copyPrompt(field, button, status, clipboard, schedule = setTimeout) {
  const label = button.getAttribute("data-copy-label") || button.textContent;
  try {
    if (!clipboard || typeof clipboard.writeText !== "function") throw new Error("clipboard unavailable");
    await clipboard.writeText(field.value);
  } catch (_) {
    field.focus();
    field.select();
    button.textContent = MANUAL_LABEL;
    status.textContent = MANUAL_STATUS;
    return "manual";
  }
  button.textContent = "Copied";
  status.textContent = COPIED_STATUS;
  schedule(() => { button.textContent = label; }, COPIED_MS);
  return "copied";
}

/** The click target's prompt parts, or null when the click is not a Copy prompt button. */
export function promptParts(target) {
  const button = target?.closest?.("[data-overview-copy]");
  const root = button?.closest("[data-overview-prompt]");
  const field = root?.querySelector("textarea");
  const status = root?.querySelector("[data-overview-copy-status]");
  return button && field && status ? { button, field, status } : null;
}

if (globalThis.document?.addEventListener && !globalThis.DatapipelinesOverview) {
  globalThis.DatapipelinesOverview = { copyPrompt };
  document.addEventListener("click", event => {
    const parts = promptParts(event.target);
    if (!parts) return;
    copyPrompt(parts.field, parts.button, parts.status, globalThis.navigator?.clipboard).catch(() => {});
  });
}
