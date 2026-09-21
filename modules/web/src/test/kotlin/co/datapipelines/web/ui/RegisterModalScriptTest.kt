package co.datapipelines.web.ui

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A static pin for the register modal's refusal handling (022 review F9): the page must
 * handle `htmx:responseError` explicitly (htmx does not swap 4xx, and the refusal carries
 * no HX-Retarget — the modal owns its error path even now that toast.js's bridgeErrors
 * exists), and the MutationObserver must not close the modal over error content. This
 * asserts the mechanism's presence in the page's script, not its runtime behavior — the
 * wire half (the partial route returns 400 with the refusal markup) is pinned by the E2E
 * smoke. Since 188 (#188) the script is `/js/datasources.js`, loaded by the template —
 * the enforced CSP allows no inline script — so the pins read the file the template names.
 */
class RegisterModalScriptTest {
    private val template =
        checkNotNull(javaClass.getResource("/templates/datasources/list.html")) {
            "datasources/list.html not on the test classpath"
        }.readText()

    private val script =
        checkNotNull(javaClass.getResource("/static/js/datasources.js")) {
            "static/js/datasources.js not on the test classpath"
        }.readText()

    @Test
    fun `the template loads the screen's script and carries no inline one`() {
        template shouldContain "@{/js/datasources.js}"
        template shouldNotContain "<script>"
    }

    @Test
    fun `the register modal handles htmx responseError explicitly and tags error content`() {
        script shouldContain "htmx:responseError"
        script shouldContain "data-error"
    }

    @Test
    fun `the observer skips error content when closing the modal`() {
        script shouldContain "getAttribute('data-error') !== 'true'"
    }

    @Test
    fun `the modal script arms immediately after a boosted swap`() {
        // 076 §D: a DOMContentLoaded-only wrapper never fires when the screen arrives in
        // a boosted htmx swap (the event has long fired) — the readyState guard is what
        // keeps the refusal listener alive on the boosted path.
        script shouldContain "document.readyState === 'loading'"
        script shouldContain "initRegisterModal()"
    }

    @Test
    fun `the register form does not rely on the unloaded response-targets extension`() {
        template shouldNotContain "hx-target-error="
    }
}
