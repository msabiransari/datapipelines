package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * The reduced schema opens through [PlotlySchema]'s own loader, never the calling thread's context loader. The first
 * validation of a Plotly configuration may run on a shared Reactor worker whose context loader is a stopped Tomcat
 * webapp loader (a test JVM cycling `@DirtiesContext` contexts): with the default `ClassPathResource` that load fails
 * with "this web application instance has been stopped already" and an MCP `visualizations_create` answers
 * `-32603`. The stand-in here is a loader that sees NOTHING — the same outcome, deterministic. Red with
 * `ClassPathResource(SCHEMA_RESOURCE)` restored; green on the committed tree.
 */
class PlotlySchemaLoaderTest {
    @Test
    fun `the schema opens on a thread whose context loader cannot see the resource`() {
        val blind = URLClassLoader(emptyArray(), null)
        withClue("the stand-in loader must not see the resource, or this test proves nothing") {
            (blind.getResource(PlotlySchema.SCHEMA_RESOURCE) == null) shouldBe true
        }
        val outcome = AtomicReference<Result<Int>>()
        val thread =
            Thread {
                outcome.set(runCatching { PlotlySchema.openSchema().use { it.readBytes().size } })
            }
        thread.contextClassLoader = blind
        thread.start()
        thread.join()
        withClue("opened through the context loader: ${outcome.get().exceptionOrNull()}") {
            outcome.get().isSuccess shouldBe true
        }
        (outcome.get().getOrThrow() > 0) shouldBe true
    }
}
