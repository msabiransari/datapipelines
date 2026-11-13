package co.datapipelines.browser

import com.microsoft.playwright.Page

/**
 * #876 §F.4 — a fixture release WITH its dependency drafts, now that the bare cascade alias
 * (`release_pinned_templates=true` without a plan) is refused `428 <family>.release.plan_required`. The plan is the
 * product's own: the family's release DIALOG GET renders it (`tree_hash` and one `include_dependencies` box per DRAFT
 * dependency), and the REST release route receives the same plan as its JSON body — every reachable DRAFT selected,
 * exactly what a person consenting to every box sends. In-page fetches with the session and the CSRF pair, so the
 * caller must be on a page of the app, signed in.
 */
object PlannedRelease {
    /**
     * Releases through [releasePath] at [ifMatch] with the plan [dialogPath] renders; returns the response body.
     * Throws with the status and an excerpt when the dialog renders no plan or the release refuses.
     */
    fun release(
        page: Page,
        dialogPath: String,
        releasePath: String,
        ifMatch: String,
        extraBody: Map<String, String> = emptyMap(),
    ): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const dialog = await fetch(args.dialog, { credentials: 'same-origin', headers: { 'HX-Request': 'true' } });
                  const html = await dialog.text();
                  if (!dialog.ok) return { error: 'dialog ' + dialog.status + ' ' + html.slice(0, 300) };
                  const doc = new DOMParser().parseFromString(html, 'text/html');
                  const tree = doc.querySelector('input[name="tree_hash"]');
                  if (!tree) return { error: 'the dialog rendered no plan: ' + html.slice(0, 300) };
                  const include = Array.from(doc.querySelectorAll('input[name="include_dependencies"]')).map(i => i.value);
                  const body = Object.assign({}, args.extra, { tree_hash: tree.value, include_dependencies: include });
                  const released = await fetch(args.release, { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'If-Match': args.ifMatch,
                               'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify(body) });
                  const text = await released.text();
                  return released.ok ? { body: text } : { error: 'release ' + released.status + ' ' + text.slice(0, 300) };
                }""",
                mapOf("dialog" to dialogPath, "release" to releasePath, "ifMatch" to ifMatch, "extra" to extraBody),
            ) as Map<*, *>
        check(result["error"] == null) { "planned release of $releasePath failed: ${result["error"]}" }
        return result["body"] as String
    }
}
