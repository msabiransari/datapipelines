package co.datapipelines.dag

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The DAG primitive lives in `modules/graph` under the package `co.datapipelines.dag` — the
 * package it had in `modules/dag` — by decision (parameter-engine record P17, #194).
 *
 * The move was a relocation whose whole point was not to touch the executor: its two importers
 * keep `import co.datapipelines.dag.Dag` unchanged. A "tidy-up" to `co.datapipelines.graph`
 * would be an executor change wearing a refactor's clothes, so the package is pinned here, where
 * the edit would have to be made, with the reason beside it.
 */
class DagPackageTest {
    @Test
    fun `the primitive keeps the executor's package`() {
        Dag::class.java.packageName shouldBe "co.datapipelines.dag"
        DagBuilder::class.java.packageName shouldBe "co.datapipelines.dag"
    }
}
