package co.datapipelines.lake.maintenance.manifests

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Brief 963 D4 — **how long is the table lease held while the manifests are rewritten?** Tables of 1,000 and 10,000
 * fabricated data files with column metrics (bounds and counts, as a writer records them) in 200 and 1,000 small
 * manifests (default manifest target, so every manifest is a candidate and each spec folds into one), each rewritten
 * by the part through 957's worker on the module's Postgres and MinIO; the figure is the part's own
 * `lease_held_millis`, from the lease's acquisition (inside `underLease`) to the commit's return. [REPEATS] tables per
 * shape. Reports, never asserts beyond the fold itself — gated on `DP_MEASURE=1` like the
 * other measurements, and named without the `Test` suffix so the suite and `verifyTestsExecuted` leave it out.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class ManifestRewriteLeaseMeasurement {
    private val h = ManifestRewriteHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `lease held while 1,000 and 10,000 files in 200 and 1,000 manifests are rewritten`() {
        val lines = mutableListOf("files,manifests,repeat,manifests_after,lease_held_millis,part_bytes")
        for ((files, manifests) in SHAPES) {
            repeat(REPEATS) { r ->
                val table = h.table("m_${files}_${manifests}_$r")
                h.bulk(table, count = manifests, perManifest = files / manifests, withMetrics = true)
                val entry = h.entry(h.run(table))
                check(entry["outcome"].asText() == "done" && entry["folded"].asBoolean()) { "no fold: $entry" }
                lines +=
                    listOf(
                        files,
                        manifests,
                        r,
                        entry["manifests_after"].asInt(),
                        entry["lease_held_millis"].asLong(),
                        entry["bytes"].asLong(),
                    ).joinToString(",")
            }
        }
        println("(963 D4)\n" + lines.joinToString("\n"))
    }

    private companion object {
        val SHAPES = listOf(1_000 to 200, 1_000 to 1_000, 10_000 to 200, 10_000 to 1_000)
        const val REPEATS = 3
    }
}
