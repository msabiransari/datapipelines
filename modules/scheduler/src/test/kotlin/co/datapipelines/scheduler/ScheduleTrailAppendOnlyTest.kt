package co.datapipelines.scheduler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/**
 * **The trail is append-only by construction** (R10, record §5.4, §7). This schema has no triggers
 * (metadata-db §2/§7.2), so the guarantee is the code's: no statement in the module's main sources
 * UPDATEs or DELETEs `schedule_run_events`, and the table is written only by the one INSERT
 * [ScheduleRunRepository.appendTrail] carries. A new writer fails here by name.
 */
class ScheduleTrailAppendOnlyTest {
    @Test
    fun `no main source updates or deletes a trail row, and exactly one inserts one`() {
        val sources =
            SchedulerTestDb
                .repoFile("modules/scheduler/src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .toList()
        val text = sources.associate { it.name to it.readText().replace(WHITESPACE, " ") }

        text.flatMap { (file, body) -> MUTATION.findAll(body).map { "$file: ${it.value}" } }.shouldBeEmpty()
        val inserts = text.flatMap { (file, body) -> INSERT.findAll(body).map { file } }
        inserts.size shouldBeGreaterThan 0
        inserts.distinct().filterNot { it == "ScheduleRunRepository.kt" }.shouldBeEmpty()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val MUTATION = Regex("(?i)(UPDATE\\s+schedule_run_events|DELETE\\s+FROM\\s+schedule_run_events|TRUNCATE[^;\"]*schedule_run_events)")
        val INSERT = Regex("(?i)INSERT\\s+INTO\\s+schedule_run_events")
    }
}
