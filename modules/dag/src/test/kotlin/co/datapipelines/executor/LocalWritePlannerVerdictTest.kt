package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.LocalWritePlanner.Destination
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `LocalWritePlanner.verdict` — the pure decision table over collected destinations (design
 * §3.2): which plans enroll one local transaction, which stay ordinary, and which are refused
 * with which reason and details. The walk that collects the destinations is exercised through
 * the executor seams; every rule below is a verdict-table row, so no registry is needed.
 */
class LocalWritePlannerVerdictTest {
    private fun destination(
        registration: String,
        nodeId: String = registration,
    ) = Destination(registration, nodeId, trail = listOf(nodeId))

    @Test
    fun `a plan with no durable destination is ordinary`() {
        val verdict =
            planner.verdict(
                writers = emptyList(),
                rowWrites = emptyList(),
                externalDdl = emptyList(),
                unreadableChildren = emptyList(),
                eligibility = emptyMap(),
            )
        verdict shouldBe LocalWriteVerdict.Ordinary
    }

    @Test
    fun `writers on excluded engines only stay ordinary`() {
        val verdict =
            planner.verdict(
                writers = listOf(destination("warehouse")),
                rowWrites = emptyList(),
                externalDdl = emptyList(),
                unreadableChildren = emptyList(),
                eligibility = mapOf("warehouse" to false),
            )
        verdict shouldBe LocalWriteVerdict.Ordinary
    }

    @Test
    fun `an unresolvable writer alone stays ordinary and fails at execution as today`() {
        // §3.2: alone, an unknown name is not this planner's refusal — the node fails at
        // execution with pipeline.node.datasource_not_found, exactly as without the feature.
        val verdict =
            planner.verdict(
                writers = listOf(destination("vanished")),
                rowWrites = emptyList(),
                externalDdl = emptyList(),
                unreadableChildren = emptyList(),
                eligibility = emptyMap(),
            )
        verdict shouldBe LocalWriteVerdict.Ordinary
    }

    @Test
    fun `one eligible registration across writers and row writes enrolls`() {
        val verdict =
            planner.verdict(
                writers = listOf(destination("pg_main", nodeId = "dml")),
                rowWrites = listOf(destination("pg_main", nodeId = "writeback")),
                externalDdl = emptyList(),
                unreadableChildren = emptyList(),
                eligibility = mapOf("pg_main" to true),
            )
        verdict shouldBe LocalWriteVerdict.Enroll("pg_main")
    }

    @Test
    fun `two nodes on the same name are one registration and enroll`() {
        val verdict =
            planner.verdict(
                writers = listOf(destination("pg_main", nodeId = "a"), destination("pg_main", nodeId = "b")),
                rowWrites = emptyList(),
                externalDdl = emptyList(),
                unreadableChildren = emptyList(),
                eligibility = mapOf("pg_main" to true),
            )
        verdict shouldBe LocalWriteVerdict.Enroll("pg_main")
    }

    @Test
    fun `a second destination name refuses with sorted registrations and described nodes`() {
        val verdict =
            planner.verdict(
                writers = listOf(destination("zeta", nodeId = "dml")),
                rowWrites = listOf(destination("alpha", nodeId = "writeback")),
                externalDdl = emptyList(),
                unreadableChildren = emptyList(),
                eligibility = mapOf("zeta" to true, "alpha" to false),
            )
        verdict as LocalWriteVerdict.Refuse
        verdict.reason shouldBe "second_durable_destination"
        (verdict.details["registrations"] as List<*>) shouldContainExactly listOf("alpha", "zeta")
        @Suppress("UNCHECKED_CAST")
        val writers = verdict.details["writers"] as List<Map<String, Any?>>
        writers.single() shouldBe
            mapOf(
                "registration" to "zeta",
                "node_id" to "dml",
                "via" to listOf("dml"),
            )
        @Suppress("UNCHECKED_CAST")
        val rowWrites = verdict.details["row_writes"] as List<Map<String, Any?>>
        rowWrites.single()["node_id"] shouldBe "writeback"
        verdict.details["external_ddl"] shouldBe emptyList<Map<String, Any?>>()
    }

    @Test
    fun `external DDL on the enrolled registration refuses as external_ddl`() {
        // Same single name, so registrations do not refuse it — the DDL rule does.
        val verdict =
            planner.verdict(
                writers = listOf(destination("pg_main", nodeId = "dml")),
                rowWrites = emptyList(),
                externalDdl = listOf(destination("pg_main", nodeId = "ddl")),
                unreadableChildren = emptyList(),
                eligibility = mapOf("pg_main" to true),
            )
        verdict as LocalWriteVerdict.Refuse
        verdict.reason shouldBe "external_ddl"
        verdict.details.keys shouldContainExactly listOf("external_ddl")
    }

    @Test
    fun `a DDL-only plan on an eligible engine stays ordinary (R1)`() {
        // DDL never enrolls (§3.2): however eligible its engine, a plan with no ROW writer must
        // keep today's ordinary path rather than trip the `external_ddl` refusal.
        val verdict =
            planner.verdict(
                writers = emptyList(),
                rowWrites = emptyList(),
                externalDdl = listOf(destination("pg_main", nodeId = "ddl")),
                unreadableChildren = emptyList(),
                eligibility = mapOf("pg_main" to true),
            )
        verdict shouldBe LocalWriteVerdict.Ordinary
    }

    @Test
    fun `DDL beside excluded-only writers stays ordinary (R1)`() {
        // The excluded H2 writer never participates, so the eligible-engine DDL cannot refuse a
        // plan that has no eligible row writer.
        val verdict =
            planner.verdict(
                writers = listOf(destination("warehouse", nodeId = "dml")),
                rowWrites = emptyList(),
                externalDdl = listOf(destination("pg_main", nodeId = "ddl")),
                unreadableChildren = emptyList(),
                eligibility = mapOf("warehouse" to false, "pg_main" to true),
            )
        verdict shouldBe LocalWriteVerdict.Ordinary
    }

    @Test
    fun `an unreadable child on an otherwise single-destination plan refuses as unknown_child_writes`() {
        val verdict =
            planner.verdict(
                writers = listOf(destination("pg_main")),
                rowWrites = emptyList(),
                externalDdl = emptyList(),
                unreadableChildren = listOf("node 'child' pins 'gone' v3, which is not in the registry"),
                eligibility = mapOf("pg_main" to true),
            )
        verdict as LocalWriteVerdict.Refuse
        verdict.reason shouldBe "unknown_child_writes"
        (verdict.details["children"] as List<*>) shouldContainExactly
            listOf("node 'child' pins 'gone' v3, which is not in the registry")
    }

    @Test
    fun `a second destination outranks external DDL and unknown children`() {
        // The precedence order is part of the contract: registrations first, DDL second,
        // unreadable children last — a reader of the refusal sees the strongest reason.
        val verdict =
            planner.verdict(
                writers = listOf(destination("pg_main")),
                rowWrites = listOf(destination("other")),
                externalDdl = listOf(destination("pg_main", nodeId = "ddl")),
                unreadableChildren = listOf("node 'child' carries no pipeline reference"),
                eligibility = mapOf("pg_main" to true, "other" to true),
            )
        verdict as LocalWriteVerdict.Refuse
        verdict.reason shouldBe "second_durable_destination"
    }

    private val planner =
        LocalWritePlanner(
            pipelines = co.datapipelines.pipeline.PipelineResolver { _, _, _ -> null },
            datasources = unsupportedRegistry(),
            maxCompositionDepth = 8,
        )

    /**
     * The verdict table needs no registry; the constructor only stores one. A proxy trap
     * (not `error()` in an initializer, which fires eagerly at construction) so any registry
     * call the walk might someday make fails THIS suite loudly instead of returning null.
     */
    private fun unsupportedRegistry(): DatasourceRegistry =
        java.lang.reflect.Proxy.newProxyInstance(
            DatasourceRegistry::class.java.classLoader,
            arrayOf(DatasourceRegistry::class.java),
        ) { _, method, _ ->
            throw IllegalStateException("verdict() never touches the registry — ${method.name} was called")
        } as DatasourceRegistry
}
