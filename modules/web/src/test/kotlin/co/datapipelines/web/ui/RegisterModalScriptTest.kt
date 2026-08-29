package co.datapipelines.web.ui

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A static pin for the register modal's refusal handling (022 review F9): the page must
 * handle `htmx:responseError` explicitly (the response-targets extension the layout's
 * `hx-target-error` needs is not loaded, and htmx does not swap 4xx into `hx-target`),
 * and the MutationObserver must not close the modal over error content. This asserts the
 * mechanism's presence in the template, not its runtime behavior — the wire half (the
 * partial route returns 400 with the refusal markup) is pinned by the E2E smoke.
 */
class RegisterModalScriptTest {
    private val template =
        checkNotNull(javaClass.getResource("/templates/datasources/list.html")) {
            "datasources/list.html not on the test classpath"
        }.readText()

    @Test
    fun `the register modal handles htmx responseError explicitly and tags error content`() {
        template shouldContain "htmx:responseError"
        template shouldContain "data-error"
    }

    @Test
    fun `the observer skips error content when closing the modal`() {
        template shouldContain "getAttribute('data-error') !== 'true'"
    }

    @Test
    fun `the register form does not rely on the unloaded response-targets extension`() {
        template shouldNotContain "hx-target-error="
    }
}
