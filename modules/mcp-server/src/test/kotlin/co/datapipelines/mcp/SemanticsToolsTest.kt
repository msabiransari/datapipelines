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
                // 120 appended the two docs tools after these three — the tail is now five.
                realShippedTools().map { it.name }.takeLast(5) shouldContainExactly
                    listOf("semantics_record", "semantics_list", "semantics_retire", "docs_list", "docs_get")
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

    @Test
    fun `a fact whose text names a table its refs do not is refused before the service runs - 125 B`() {
        val catalog =
            listOf(
                TableInfo(listOf("public"), "orders", "TABLE", null),
                TableInfo(listOf("public"), "order_items", "TABLE", null),
            )
        every { service.record(any(), any(), any(), any()) } returns mapOf("id" to "f1")
        val tool = recordTool(catalog)

        // The misspelling, with CORRECT refs: the text lies, the refusal names the nearest listed table.
        val misspelled =
            shouldThrow<DatapipelinesException> {
                tool.call(
                    McpArguments(recordArgs() + ("fact" to "order_itemz joins to orders on order_id")),
                    McpFixtures.ctx(Scope.AUTHOR),
                )
            }

        // A listed table spelled exactly but absent from refs: same code, no "did you mean".
        val exact =
            shouldThrow<DatapipelinesException> {
                tool.call(
                    McpArguments(recordArgs() + ("fact" to "order_items joins to orders on order_id")),
                    McpFixtures.ctx(Scope.AUTHOR),
                )
            }

        // The same fact spelled as the refs spell it, and a fact whose underscores are prose: both reach the service.
        val spelledRight =
            tool.call(
                McpArguments(
                    recordArgs() +
                        mapOf(
                            "fact" to "order_items joins to orders on order_id",
                            "refs" to listOf(mapOf("table" to "order_items"), mapOf("table" to "orders")),
                        ),
                ),
                McpFixtures.ctx(Scope.AUTHOR),
            )
        val proseUnderscores =
            tool.call(
                McpArguments(recordArgs() + ("fact" to "amount is a row_count-weighted average, closing at the as_of date")),
                McpFixtures.ctx(Scope.AUTHOR),
            )

        assertAll(
            { misspelled.code shouldBe PipelineErrorCodes.Semantics.REF_MISMATCH },
            { misspelled.message shouldContain "did you mean 'order_items'" },
            { exact.code shouldBe PipelineErrorCodes.Semantics.REF_MISMATCH },
            { (spelledRight as Map<*, *>)["id"] shouldBe "f1" },
            { (proseUnderscores as Map<*, *>)["id"] shouldBe "f1" },
            { verify(exactly = 2) { service.record(any(), any(), any(), any()) } },
        )
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
