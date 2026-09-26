package co.datapipelines.scheduler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/**
 * **The scheduler knows no pipeline** (scheduler design revision §5, §6.2, A2). The module may use
 * `pipeline-contract` for exactly one thing — the published `PipelineNameGrammar` — and nothing of
 * `dag`, `application`, `auth`, `calculators` or `web` at all. The Gradle table fences the modules;
 * this fences the ONE allowed edge to the one allowed type, so the edge cannot quietly become
 * pipeline knowledge. Import lines are read from the main sources, fully-qualified uses included
 * (the lesson of 217a: a qualified spelling must not hide a use).
 */
class SchedulerBoundaryTest {
    @Test
    fun `main sources name nothing of co_datapipelines outside this module, typesystem and the name grammar`() {
        val sources =
            SchedulerTestDb
                .repoFile("modules/scheduler/src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .toList()
        sources.size shouldBeGreaterThan 5 // non-vacuity: the scan saw the module
        val offenders =
            sources.flatMap { file ->
                REFERENCE
                    .findAll(file.readText())
                    .map { it.value }
                    .filterNot {
                        it in ALLOWED || it.startsWith(OWN) ||
                            it.startsWith(TYPESYSTEM)
                    }.map { "${file.name}: $it" }
            }
        offenders.shouldBeEmpty()
    }

    private companion object {
        val REFERENCE = Regex("""co\.datapipelines\.[a-z]+(?:\.[A-Za-z0-9_]+)+""")
        const val OWN = "co.datapipelines.scheduler"
        const val TYPESYSTEM = "co.datapipelines.typesystem."
        val ALLOWED = setOf("co.datapipelines.pipeline.PipelineNameGrammar")
    }
}
