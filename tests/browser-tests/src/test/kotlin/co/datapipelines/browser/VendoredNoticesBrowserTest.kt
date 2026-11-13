package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** CI-only HTTP witness for the generated browser-asset notices and their public static transport. */
class VendoredNoticesBrowserTest : BrowserSuite() {
    @Test
    fun `anonymous notices request serves the packaged bytes as plain text`() {
        startTrace()
        val path = "/vendor/THIRD-PARTY-NOTICES.txt"
        val expected =
            requireNotNull(javaClass.classLoader.getResourceAsStream("static$path")) { "Missing packaged notices" }
                .use { it.readBytes() }
        val response = page.request().get("$baseUrl$path")
        try {
            withClue("anonymous notices transport at $path") {
                response.status() shouldBe 200
                response.headers()["content-type"].orEmpty() shouldContain "text/plain"
                response.body().toList() shouldBe expected.toList()
            }
        } finally {
            response.dispose()
        }
    }
}
