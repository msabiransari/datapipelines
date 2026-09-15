package co.datapipelines.mcp

import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.auth.Capability
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TablesPage
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The three learned-semantics tools (mcp-server.md §6.2.36–38) — the [LakeTableToolsTest] shape.
 *
 * The tools own almost nothing: recording, listing and retiring are [SemanticsService], the
 * path a REST twin would share. Proven here is the tools' own half — the catalog entries
 * (names, mutating flags, both matrix axes), the §5.3 visibility gate BEFORE the service runs
 * (an ungranted datasource is not-found, which for a DATASOURCE-scope record IS the grant
 * rule), the argument-to-command binding, and the `-32602` shape faults. The input schemas are
 * pinned against mcp-server.md by `McpToolSurfaceSpecDriftTest`; the service's rules — viewer
 * refused through the real matrix, ws_admin on a foreign retire, the audit rows — by
 * `SemanticsServiceTest`, `McpToolDispatcherTest` and the E2E.
 */
class SemanticsToolsTest {
    private val warehouse =
        Datasource(
            name = "warehouse",
            displayName = "Warehouse",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db.internal:5432/app",
            username = "app",
            secret = "secret",
            ownerWorkspaceId = McpFixtures.WORKSPACE_ID,
        )
    private val registry = FakeDatasourceRegistry(listOf(warehouse))
    private val service = mockk<SemanticsService>()
    private val introspector = mockk<SchemaIntrospector>()

    /** A tool whose catalog listing is [tables] — an empty page skips the text/refs check (125 §B). */
    private fun recordTool(tables: List<TableInfo> = emptyList()): SemanticsRecordTool {
        every { introspector.tables(any<Datasource>()) } returns TablesPage(tables, false)
        return SemanticsRecordTool(registry, service, introspector)
    }

    @Test
    fun `all three are catalogued on both matrix axes - record and retire mutating and author, list a viewer read`() {
        assertAll(
            { McpToolCatalog.NAMES shouldContain "semantics_record" },
            { McpToolCatalog.isMutating("semantics_record") shouldBe true },
            { ScopeMatrix.requiredScopeForTool("semantics_record") shouldBe Scope.AUTHOR },
            { ScopeMatrix.requiredCapabilityForTool("semantics_record") shouldBe Capability.AUTHOR },
            { McpToolCatalog.isMutating("semantics_list") shouldBe false },
            { ScopeMatrix.requiredScopeForTool("semantics_list") shouldBe Scope.READ },
            { ScopeMatrix.requiredCapabilityForTool("semantics_list") shouldBe Capability.VIEW },
            { McpToolCatalog.isMutating("semantics_retire") shouldBe true },
            { ScopeMatrix.requiredScopeForTool("semantics_retire") shouldBe Scope.AUTHOR },
            { ScopeMatrix.requiredCapabilityForTool("semantics_retire") shouldBe Capability.AUTHOR },
            {
                // 120 appended the two docs tools after these three and 140 the check run after
                // those — the tail is now six.
                realShippedTools().map { it.name }.takeLast(6) shouldContainExactly
                    listOf("semantics_record", "semantics_list", "semantics_retire", "docs_list", "docs_get", "pipelines_run_checks")
            },
        )
    }

    @Test
    fun `record binds every argument into the command, stamps MCP as the surface, and passes the gated datasource`() {
        val command = slot<SemanticsService.RecordCommand>()
        every { service.record(any(), warehouse, capture(command), WriteSurface.MCP) } returns mapOf("id" to "f1", "trust" to "observed")
        val supersedes = UUID.randomUUID()

        val result =
            recordTool().call(
                McpArguments(
                    mapOf(
                        "scope" to "DATASOURCE",
                        "datasource" to "warehouse",
                        "kind" to "unit",
                        "fact" to "amount is in cents, never dollars",
                        "refs" to
                            listOf(mapOf("table" to "orders", "column" to "amount"), mapOf("schema" to "public", "table" to "customers")),
                        "evidence_sql" to "SELECT amount FROM orders LIMIT 5",
                        "evidence_summary" to "cents",
                        "source_pipeline_id" to McpFixtures.PIPELINE_ID.toString(),
                        "source_version" to 3,
                        "supersedes" to supersedes.toString(),
                    ),
                ),
                McpFixtures.ctx(Scope.AUTHOR),
            )

        assertAll(
            { command.captured.scope shouldBe LearnedFactScope.DATASOURCE },
            { command.captured.kind shouldBe "unit" },
            { command.captured.refs shouldContainExactly listOf(FactRef(null, "orders", "amount"), FactRef("public", "customers", null)) },
            { command.captured.evidenceSql shouldBe "SELECT amount FROM orders LIMIT 5" },
            { command.captured.evidenceSummary shouldBe "cents" },
            { command.captured.sourcePipelineId shouldBe McpFixtures.PIPELINE_ID },
            { command.captured.sourceVersion shouldBe 3 },
            { command.captured.supersedes shouldBe supersedes },
            { (result as Map<*, *>)["id"] shouldBe "f1" },
        )
    }

    @Test
    fun `an ungranted datasource is not-found BEFORE the service runs - the grant rule for a DATASOURCE fact`() {
        val other =
            McpFixtures.ctx(
                Scope.AUTHOR,
                workspace = co.datapipelines.auth.WorkspaceContext(UUID.randomUUID(), "globex", McpFixtures.WORKSPACE.flags),
            )

        val record = shouldThrow<DatapipelinesException> { recordTool().call(McpArguments(recordArgs()), other) }
        val list =
            shouldThrow<DatapipelinesException> {
                SemanticsListTool(registry, service).call(
                    McpArguments(
                        mapOf("datasource" to "warehouse"),
                    ),
                    other,
                )
            }

        assertAll(
            { record.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND },
            { list.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND },
        )
        verify(exactly = 0) { service.record(any(), any(), any(), any()) }
        verify(exactly = 0) { service.list(any(), any(), any()) }
    }

    @Test
    fun `argument-shape faults are -32602 - a bad scope, a ref without a table, a malformed since`() {
        val badScope =
            shouldThrow<McpError> {
                recordTool().call(McpArguments(recordArgs() + ("scope" to "GLOBAL")), McpFixtures.ctx(Scope.AUTHOR))
            }
        val badRef =
            shouldThrow<McpError> {
                recordTool().call(
                    McpArguments(recordArgs() + ("refs" to listOf(mapOf("column" to "x")))),
                    McpFixtures.ctx(Scope.AUTHOR),
                )
            }
        val badSince =
            shouldThrow<McpError> {
                SemanticsListTool(registry, service).call(
                    McpArguments(
                        mapOf(
                            "datasource" to "warehouse",
                            "since" to "yesterday",
                        ),
                    ),
                    McpFixtures.ctx(Scope.READ),
                )
            }
        val shortReason =
            shouldThrow<McpError> {
                SemanticsRetireTool(service).call(
                    McpArguments(
                        mapOf(
                            "id" to UUID.randomUUID().toString(),
                            "reason" to "x",
                        ),
                    ),
                    McpFixtures.ctx(Scope.AUTHOR),
                )
            }

        assertAll(
            { badScope.message shouldContain "scope" },
            { badRef.message shouldContain "table" },
            { badSince.message shouldContain "since" },
            { shortReason.message shouldContain "reason" },
        )
    }

    /**
     * 129 §B — the four arms of text/refs agreement through the real tool: (1) a catalog
     * table the text names exactly and refs omit is ACCEPTED, the ref added with the
     * catalog's namespace and no column, `refs_added` telling the agent; (2) a near-miss is
     * REFUSED before the service runs, naming the nearest listed table; (3) an exact table
     * the refs carry is accepted with nothing added; (4) prose with underscores is accepted
     * with nothing added.
     */
    @Test
    fun `text and refs agree in four arms - exact-missing is added for you, a near-miss is refused - 129 B`() {
        val catalog =
            listOf(
                TableInfo(listOf("public"), "orders", "TABLE", null),
                TableInfo(listOf("public"), "order_items", "TABLE", null),
            )
        every { service.record(any(), any(), any(), any()) } returns mapOf("id" to "f1")
        val tool = recordTool(catalog)

        val exactMissing =
            tool.call(
                McpArguments(recordArgs() + ("fact" to "order_items joins to orders on order_id")),
                McpFixtures.ctx(Scope.AUTHOR),
            ) as Map<*, *>

        val nearMiss =
            shouldThrow<DatapipelinesException> {
                tool.call(
                    McpArguments(recordArgs() + ("fact" to "order_itemz joins to orders on order_id")),
                    McpFixtures.ctx(Scope.AUTHOR),
                )
            }

        val exactPresent =
            tool.call(
                McpArguments(
                    recordArgs() +
                        mapOf(
                            "fact" to "order_items joins to orders on order_id",
                            "refs" to listOf(mapOf("table" to "order_items"), mapOf("table" to "orders")),
                        ),
                ),
                McpFixtures.ctx(Scope.AUTHOR),
            ) as Map<*, *>

        val proseUnderscores =
            tool.call(
                McpArguments(recordArgs() + ("fact" to "amount is a row_count-weighted average, closing at the as_of date")),
                McpFixtures.ctx(Scope.AUTHOR),
            ) as Map<*, *>

        assertAll(
            { exactMissing["id"] shouldBe "f1" },
            {
                exactMissing["refs_added"] shouldBe
                    listOf(mapOf("schema" to "public", "table" to "order_items", "column" to null))
            },
            { nearMiss.code shouldBe PipelineErrorCodes.Semantics.REF_MISMATCH },
            { nearMiss.message shouldContain "did you mean 'order_items'" },
            { exactPresent["id"] shouldBe "f1" },
            { exactPresent["refs_added"] shouldBe null },
            { proseUnderscores["id"] shouldBe "f1" },
            { proseUnderscores["refs_added"] shouldBe null },
            { verify(exactly = 3) { service.record(any(), any(), any(), any()) } },
        )
    }

    /**
     * 136 §B / T278 — `refs` is OPTIONAL at the tool: absent, the command carries the empty
     * list. A WORKSPACE rule that spans datasources reaches the service with no refs (the
     * scope-aware floor is the recorder's, not restated here); a table of ANOTHER datasource
     * its text names is prose — the check reads only THIS datasource's catalog, so a name
     * that is neither listed nor a near-miss adds nothing and refuses nothing; a table of
     * THIS datasource named exactly is still added as a ref, and a near-miss still refused.
     */
    @Test
    fun `refs may be absent - a WORKSPACE rule spanning datasources is passed through with none - 136 B`() {
        val catalog = listOf(TableInfo(listOf("public"), "taxi_trips", "TABLE", null))
        val commands = mutableListOf<SemanticsService.RecordCommand>()
        every { service.record(any(), any(), capture(commands), any()) } returns mapOf("id" to "r1")
        val tool = recordTool(catalog)
        val rule =
            mapOf(
                "scope" to "WORKSPACE",
                "datasource" to "warehouse",
                "kind" to "definition",
                "fact" to "busiest day = taxi trips + rideshare_rides COMBINED, by pickup date",
            )

        val otherDatasourceTableIsProse = tool.call(McpArguments(rule), McpFixtures.ctx(Scope.AUTHOR)) as Map<*, *>
        val ownTableIsAdded =
            tool.call(
                McpArguments(rule + ("fact" to "busiest day = taxi_trips + rideshare_rides COMBINED, by pickup date")),
                McpFixtures.ctx(Scope.AUTHOR),
            ) as Map<*, *>
        val ownNearMissIsRefused =
            shouldThrow<DatapipelinesException> {
                tool.call(
                    McpArguments(rule + ("fact" to "busiest day = taxi_tripz + rideshare_rides COMBINED, by pickup date")),
                    McpFixtures.ctx(Scope.AUTHOR),
                )
            }

        assertAll(
            { otherDatasourceTableIsProse["id"] shouldBe "r1" },
            { otherDatasourceTableIsProse["refs_added"] shouldBe null },
            { commands[0].refs shouldBe emptyList() },
            { commands[0].scope shouldBe LearnedFactScope.WORKSPACE },
            { ownTableIsAdded["refs_added"] shouldBe listOf(mapOf("schema" to "public", "table" to "taxi_trips", "column" to null)) },
            { commands[1].refs shouldBe listOf(FactRef("public", "taxi_trips", null)) },
            { ownNearMissIsRefused.code shouldBe PipelineErrorCodes.Semantics.REF_MISMATCH },
            { verify(exactly = 2) { service.record(any(), any(), any(), any()) } },
        )
    }

    @Test
    fun `the added ref rides the STORED refs - the catalog's namespace, no column - 129 B`() {
        val catalog =
            listOf(
                TableInfo(listOf("public"), "orders", "TABLE", null),
                TableInfo(listOf("public"), "order_items", "TABLE", null),
            )
        every { service.record(any(), any(), any(), any()) } returns mapOf("id" to "f1")

        recordTool(catalog).call(
            McpArguments(recordArgs() + ("fact" to "order_items joins to orders on order_id")),
            McpFixtures.ctx(Scope.AUTHOR),
        )

        verify(exactly = 1) {
            service.record(
                any(),
                any(),
                match {
                    it.refs == listOf(FactRef(null, "orders", "amount"), FactRef("public", "order_items", null))
                },
                any(),
            )
        }
    }

    @Test
    fun `list binds the filters and wraps the service's rows with a count`() {
        val query = slot<SemanticsService.ListQuery>()
        every { service.list(any(), warehouse, capture(query)) } returns listOf(mapOf("id" to "f1"), mapOf("id" to "f2"))

        val result =
            SemanticsListTool(registry, service).call(
                McpArguments(
                    mapOf(
                        "datasource" to "warehouse",
                        "table" to "orders",
                        "scope" to "WORKSPACE",
                        "include_retired" to true,
                        "since" to "2026-09-11T00:00:00Z",
                    ),
                ),
                McpFixtures.ctx(Scope.READ),
            ) as Map<*, *>

        assertAll(
            { query.captured.table shouldBe "orders" },
            { query.captured.scope shouldBe LearnedFactScope.WORKSPACE },
            { query.captured.includeRetired shouldBe true },
            { query.captured.since shouldBe Instant.parse("2026-09-11T00:00:00Z") },
            { result["count"] shouldBe 2 },
            { result["datasource"] shouldBe "warehouse" },
        )
    }

    @Test
    fun `retire passes the id and the trimmed reason to the service as the principal`() {
        val id = UUID.randomUUID()
        every { service.retire(any(), id, "no longer true") } returns mapOf("id" to id.toString(), "trust" to "retired")

        val result =
            SemanticsRetireTool(
                service,
            ).call(McpArguments(mapOf("id" to id.toString(), "reason" to "  no longer true ")), McpFixtures.ctx(Scope.AUTHOR)) as Map<*, *>

        result["trust"] shouldBe "retired"
    }

    private fun recordArgs(): Map<String, Any?> =
        mapOf(
            "scope" to "DATASOURCE",
            "datasource" to "warehouse",
            "kind" to "unit",
            "fact" to "amount is in cents, never dollars",
            "refs" to listOf(mapOf("table" to "orders", "column" to "amount")),
        )
}
