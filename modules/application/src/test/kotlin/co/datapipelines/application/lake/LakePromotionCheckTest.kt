package co.datapipelines.application.lake

import co.datapipelines.datasources.LakeRegisteredTable
import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The receiver's check (slice D-E) over a stubbed index — the answer it reads is the question here, so the index is a
 * stub of `references`; the availability is the REAL "row present" predicate over an in-memory registry.
 */
class LakePromotionCheckTest {
    private val mapper = ObjectMapper()
    private val workspace = UUID.randomUUID()
    private val pipeline = ScannedVersion(workspace, ReleaseFamily.PIPELINE, UUID.randomUUID(), 3)
    private val set = ScannedVersion(workspace, ReleaseFamily.PARAMETER_SET, UUID.randomUUID(), 2)

    private val registry = mutableMapOf<String, List<LakeRegisteredTable>>()
    private val availability = RegisteredLakeTableAvailability(LakeTableCatalog { registry[it].orEmpty() })
    private val index = mockk<LakeReferenceIndexRepository>()

    private fun registered(
        datasource: String,
        vararg tables: LakeTableIdentity,
    ) {
        registry[datasource] =
            tables.map { LakeRegisteredTable(it.namespace, it.table, "parquet", "s3://bucket/${it.table}", null, null) }
    }

    private fun reference(
        resolved: LakeTableIdentity?,
        nodeId: String? = "read",
        selector: String? = null,
        datasource: String = "lake",
        ordinal: Int = 0,
    ) = LakeTableReference(
        ordinal = ordinal,
        nodeId = nodeId,
        selector = selector,
        templateId = "t/read.sql",
        templateVersion = 1,
        datasourceName = datasource,
        writtenCatalog = null,
        writtenSchema = null,
        writtenTable = resolved?.table ?: "bare",
        resolved = resolved,
        timeTravel = false,
        statementIndex = 0,
    )

    private fun passes(
        landed: LakePromotionCheck.Landed,
        routes: PromotionRoutes = PromotionRoutes.of(emptyMap()),
    ) = LakePromotionCheck(index, availability).refuseUnavailable(listOf(landed), routes)

    private val events = LakeTableIdentity(listOf("lk"), "events")
    private val zones = LakeTableIdentity(listOf("lk"), "zones")

    private fun refusal(
        check: LakePromotionCheck,
        landed: List<LakePromotionCheck.Landed>,
        routes: PromotionRoutes = PromotionRoutes.of(emptyMap()),
    ): DatapipelinesException = shouldThrow<DatapipelinesException> { check.refuseUnavailable(landed, routes) }

    @Suppress("UNCHECKED_CAST")
    private fun unavailable(e: DatapipelinesException) = e.details["unavailable"] as List<Map<String, Any?>>

    @Test
    fun `every reference registered here - the batch passes`() {
        registered("lake", events)
        every { index.references(pipeline) } returns listOf(reference(events))
        passes(LakePromotionCheck.Landed(pipeline, "p/read"))
    }

    @Test
    fun `an absent table refuses the batch once, naming version, site, datasource, table and state`() {
        registered("lake", events)
        every { index.references(pipeline) } returns listOf(reference(events), reference(zones, ordinal = 1))
        every { index.references(set) } returns listOf(reference(zones, nodeId = null, selector = "zone"))

        val e =
            refusal(
                LakePromotionCheck(index, availability),
                listOf(LakePromotionCheck.Landed(pipeline, "p/read"), LakePromotionCheck.Landed(set, "s/filters")),
            )

        e.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_LAKE_TABLE_UNAVAILABLE
        e.details.containsKey("target") shouldBe false
        unavailable(e) shouldContainExactly
            listOf(
                mapOf(
                    "family" to "pipeline",
                    "name" to "p/read",
                    "version" to 3,
                    "node_id" to "read",
                    "datasource" to "lake",
                    "table" to "lk.zones",
                    "state" to "absent",
                    "via" to emptyList<String>(),
                ),
                mapOf(
                    "family" to "parameter_set",
                    "name" to "s/filters",
                    "version" to 2,
                    "selector" to "zone",
                    "datasource" to "lake",
                    "table" to "lk.zones",
                    "state" to "absent",
                    "via" to emptyList<String>(),
                ),
            )
        e.details["unavailable_truncated"] shouldBe false
        e.message!!.contains("lk.zones") shouldBe true
    }

    @Test
    fun `the registry is read per datasource NAME and identifiers compare case-insensitively`() {
        registered("lake", LakeTableIdentity(listOf("LK"), "Events"))
        registered("other", zones)
        every { index.references(pipeline) } returns listOf(reference(events), reference(zones, datasource = "lake", ordinal = 1))

        unavailable(refusal(LakePromotionCheck(index, availability), listOf(LakePromotionCheck.Landed(pipeline, "p"))))
            .map { it["table"] } shouldContainExactly listOf("lk.zones")
    }

    @Test
    fun `an unresolved position is admitted - the runtime gate stays its defence`() {
        every { index.references(pipeline) } returns listOf(reference(null))
        passes(LakePromotionCheck.Landed(pipeline, "p"))
    }

    @Test
    fun `one site reading a table twice is reported once, and the list is bounded`() {
        every { index.references(pipeline) } returns
            listOf(reference(zones), reference(zones, ordinal = 1)) +
            (1..5).map { reference(LakeTableIdentity(listOf("lk"), "t$it"), nodeId = "n$it", ordinal = it + 1) }

        val e = refusal(LakePromotionCheck(index, availability, maxReported = 3), listOf(LakePromotionCheck.Landed(pipeline, "p")))
        unavailable(e).size shouldBe 3
        e.details["unavailable_truncated"] shouldBe true
    }

    @Test
    fun `via names the batch's pinning chains, outermost first`() {
        every { index.references(pipeline) } returns listOf(reference(zones))
        val child = node("""{"name": "p/read", "version": 3}""")
        val parent = node("""{"name": "p/parent", "version": 1, "nodes": [{"id": "run", "pipeline": {"name": "p/read", "version": 3}}]}""")
        val viz = node("""{"name": "v/chart", "version": 4, "source": {"pipeline": {"name": "p/parent", "version": 1}}}""")
        val dash = node("""{"name": "d/board", "version": 2, "layout": [{"visualization": {"name": "v/chart", "version": 4}}]}""")
        val routes =
            PromotionRoutes.of(
                mapOf("pipeline" to listOf(child, parent), "visualization" to listOf(viz), "dashboard" to listOf(dash)),
            )

        unavailable(refusal(LakePromotionCheck(index, availability), listOf(LakePromotionCheck.Landed(pipeline, "p/read")), routes))
            .single()["via"] shouldBe listOf("dashboard:d/board@2 > visualization:v/chart@4 > pipeline:p/parent@1")
    }

    @Test
    fun `a member nothing pins has no route, and a pin of another version is not a route`() {
        val parent = node("""{"name": "p/parent", "version": 1, "x": {"pipeline": {"name": "p/read", "version": 2}}}""")
        val routes = PromotionRoutes.of(mapOf("pipeline" to listOf(parent)))
        routes.pathsTo("pipeline", "p/read", 3).shouldBeEmpty()
        routes.pathsTo("pipeline", "p/read", 2) shouldContainExactly listOf("pipeline:p/parent@1")
    }

    private fun node(json: String): JsonNode = mapper.readTree(json)
}
