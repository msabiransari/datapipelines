package co.datapipelines.browser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The one decision behind [BrowserSuite.patient] (#438): GitHub's runner and the local gates
 * both get [BrowserSuite.CI_ACTION_TIMEOUT_MS]; a plain local run keeps Playwright's 30 s; and
 * a mistyped setting refuses LOUD at the entry point rather than silently running impatient.
 * CI wins over `"false"` — a stray flag never slows GitHub's runner.
 */
class BrowserPatienceTest {
    @Test
    fun `CI=true gets 90 s`() {
        BrowserSuite.actionTimeoutMillis(ciEnv = "true", gatePatience = null) shouldBe
            BrowserSuite.CI_ACTION_TIMEOUT_MS
    }

    @Test
    fun `the gates' setting gets 90 s`() {
        BrowserSuite.actionTimeoutMillis(ciEnv = null, gatePatience = "true") shouldBe
            BrowserSuite.CI_ACTION_TIMEOUT_MS
    }

    @Test
    fun `both absent keeps Playwright's 30 s`() {
        BrowserSuite.actionTimeoutMillis(ciEnv = null, gatePatience = null) shouldBe null
    }

    @Test
    fun `an explicit false keeps 30 s off CI`() {
        BrowserSuite.actionTimeoutMillis(ciEnv = null, gatePatience = "false") shouldBe null
    }

    @Test
    fun `a mistyped setting refuses and names the property`() {
        val failure =
            shouldThrow<IllegalStateException> {
                BrowserSuite.actionTimeoutMillis(ciEnv = null, gatePatience = "ture")
            }
        failure.message.orEmpty() shouldContain BrowserSuite.CI_PATIENCE_PROPERTY
    }

    @Test
    fun `CI=true still wins over an explicit false`() {
        BrowserSuite.actionTimeoutMillis(ciEnv = "true", gatePatience = "false") shouldBe
            BrowserSuite.CI_ACTION_TIMEOUT_MS
    }
}
