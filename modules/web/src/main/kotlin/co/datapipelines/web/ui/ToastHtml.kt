package co.datapipelines.web.ui

/**
 * The string form of `partials/toast-oob` (ui-screens.md §5.1) for controllers that
 * already build their primary swap as strings (key tables, admin user rows) —
 * everything else renders the fragment through Thymeleaf. htmx swaps the CHILDREN of
 * a non-`outerHTML` OOB element ("we use the content of the node, not the node
 * itself" — htmx.js), so `hx-swap-oob` lives on the WRAPPER, never on the `.ds-toast`
 * itself; `ToastMarkupParityTest` pins the structure every definition emits.
 *
 * [title] and [message] are interpolated RAW — escape any user-supplied value with
 * [esc] first. A toast auto-dismisses after 6s, so neither may carry anything the
 * user must keep (§5.1's hard rule): secrets stay in their persistent inline panels.
 */
object ToastHtml {
    fun oob(
        variant: String,
        title: String,
        message: String,
    ): String =
        """<div hx-swap-oob="beforeend:#toast"><div class="ds-toast ds-toast-$variant" role="status">""" +
            """<button type="button" class="ds-toast-close" aria-label="Dismiss">&times;</button>""" +
            """<div class="ds-toast-title">$title</div>""" +
            """<div class="ds-toast-body">$message</div></div></div>"""

    fun esc(text: String?): String =
        (text ?: "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
