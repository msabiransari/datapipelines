/** Toggle one accordion family; null represents an entirely closed accordion. */
export function nextOpen(open, clicked) {
  return clicked === open ? null : clicked;
}

/** Restore only live families, preferring the current page among legacy open panels.
 * Lazy preference getters may throw when browser storage is unavailable.
 */
export function restoreOpen(preferences) {
  try {
    const { stored, current, order } = preferences;
    if (stored != null) return order.includes(stored) ? stored : null;
    const { legacyFlags } = preferences;
    const candidates = order.filter(family => legacyFlags[family] === "1");
    return candidates.includes(current) ? current : candidates[0] ?? null;
  } catch (_) {
    return null;
  }
}
