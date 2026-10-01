package co.datapipelines.web.config

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactTransferService
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationKey
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.SharedPostgres
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import java.io.File
import java.util.UUID

/**
 * C1 of the L1c-c round (#10): [ArtifactTransferService]'s envelope ceiling is the OPERATOR'S configured
 * `datapipelines.visualization.max-visualizations-per-dashboard`, and it reaches the service through the
 * PRODUCTION bean factory ([VisualizationConfiguration.artifactTransferService]) — not through a direct
 * constructor call, which is exactly the call a missing factory argument cannot be caught by (the
 * constructor's default would silently stand in for the operator's value).
 *
 * Every case here takes the service OUT OF THE FACTORY (`context.getBean`) and exercises ACTUAL artifacts:
 * a real released visualization's envelope, a real dashboard-shaped envelope, real refusals, real landings
 * asserted against the database — never a reflective assertion on a field. The override is proven BOTH ways:
 * a ceiling BELOW the default refuses a bundle the default admits, and a ceiling ABOVE the default admits a
 * closure the default refuses — so a test cannot pass because the override was ignored.
 *
 * The residue claims say exactly what they mean: a refusal ADDS NO RESIDUE. Every count is extracted into a
 * LOCAL value and asserted unconditionally — an infix matcher bound to an Elvis fallback is part of that
 * fallback and executes only when the left side is null, and a SQL COUNT is never null, so the delivered
 * residue checks executed ZERO assertions (the L1c-d defect; the root reviewer's compiled witness holds the
 * runtime proof). Where a case INTENDS a prior success, the captured before-state is pinned explicitly and
 * the refusal is asserted unchanged against it — never against an empty database the method no longer has.
 *
 * Releases are stamped by SQL (the L2/E2E precedent): the production evidence gate refuses every release
 * until L4, and this wiring is not that gate's subject.
 */
class ArtifactTransferConfigWiringTest {
    private val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = SharedPostgres.postgres.jdbcUrl
                username = SharedPostgres.postgres.username
                password = SharedPostgres.postgres.password
                maximumPoolSize = 4
            },
        )
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    /** The production factory plus collaborators over the REAL database — mocks only where no row is read. */
    private fun context(vararg properties: String): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(VisualizationConfiguration::class.java, RealCollaborators::class.java)
            .withPropertyValues(*properties)

    private fun transfer(ctx: org.springframework.context.ConfigurableApplicationContext): ArtifactTransferService =
        ctx.getBean(ArtifactTransferService::class.java)

    @BeforeEach
    fun reset() {
        // The cleaning rule: this suite truncates exactly the artifact tables it touches and reseeds its
        // own suite-unique workspaces and users — never another suite's rows.
        jdbc.jdbcTemplate.execute(
            "TRUNCATE dashboard_versions, dashboards, visualization_versions, visualizations CASCADE",
        )
        jdbc.jdbcTemplate.execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, kind) VALUES " +
                "('$AUTHOR', 'author@transfer-config.test', 'Author', 'local', 'author@transfer-config.test', 'human') " +
                "ON CONFLICT (id) DO NOTHING",
        )
        listOf(WS_A to "cfg-a", WS_B to "cfg-b").forEach { (id, name) ->
            jdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name, is_personal, created_by) VALUES " +
                    "('$id', '$name', 'Cfg $name', FALSE, NULL) ON CONFLICT (id) DO NOTHING",
            )
        }
    }

    @Test
    fun `an override below the default refuses a two-entry bundle the default admits`() {
        val envelope = dashboardEnvelope(bundledCopies = 2)

        context("${VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path}=1").run { ctx ->
            val refusal = shouldThrow<DatapipelinesException> { transfer(ctx).importDashboard(WS_B, envelope, AUTHOR) }

            withClue("the refusal must be the OPERATOR's ceiling of 1, not the default: ${refusal.details}") {
                refusal.code shouldBe DashboardErrorCodes.BODY_INVALID
                refusal.details["reason"] shouldBe "too_many"
                refusal.details["count"] shouldBe 2
                refusal.details["max"] shouldBe 1
                refusal.details["config_key"] shouldBe VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path
            }
            nothingLanded()
        }

        // The boundary control: the SAME envelope through the default factory product is NOT refused by the
        // bound — both members bind, the dashboard then refuses on its own missing pin (`cfg/charts/a` does
        // not exist here), and the ONE transaction (F1) rolls the landed members back. The refusals differ,
        // so the bound refusal above is the override's, not the shape's.
        context().run { ctx ->
            val refused =
                shouldThrow<DatapipelinesException> {
                    transfer(ctx).importDashboard(WS_B, dashboardEnvelope(bundledCopies = 2), AUTHOR)
                }
            refused.code shouldBe DashboardErrorCodes.IMPORT_MISSING_DEPENDENCY
            nothingLanded()
        }
    }

    @Test
    fun `an override above the default admits a 51-entry closure the default refuses`() {
        context("${VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path}=75").run { ctx ->
            val payload = releasedPlainPayload(ctx.getBean(VisualizationService::class.java))
            // Ids are global (C29): the source rows must go before the same envelope lands here.
            VisualizationRepository(jdbc).deleteEntity(WS_A, UUID.fromString(payload.path("id").asText()))

            val closure = visualizationEnvelope(payload, templates = List(51) { templateNode(it) })
            // The mocked seam, named precisely: the closure's 51 template ENTRIES import through the
            // RELAXED template-import fake (`RealCollaborators.templateImportService`) — no template
            // row persists anywhere. What this case proves is the envelope's count bound (the
            // operator's 75 admits what the default refuses) and the ARTIFACT's real landing; it is
            // not proof of 51 persisted real templates.
            transfer(ctx).importVisualization(WS_B, closure, AUTHOR)

            withClue("51 entries are under the operator's 75: the closure passes the bound and the artifact lands") {
                countVisualizations(WS_B) shouldBe 1
            }
        }

        // The boundary control: the SAME 51-entry closure through the default factory product is refused
        // before any member is imported — the default 50 is the ceiling there.
        context().run { ctx ->
            val payload = releasedPlainPayload(ctx.getBean(VisualizationService::class.java))
            VisualizationRepository(jdbc).deleteEntity(WS_A, UUID.fromString(payload.path("id").asText()))

            // The intended prior success, pinned: the override block above landed EXACTLY one
            // visualization into WS_B, and that success is PRESERVED — the refusal below is asserted
            // against the captured before-state, never against an empty database this method no
            // longer has (the L1c-d fixture defect: the global residue check sat beside a landing
            // it denied existed).
            val before = residue()
            withClue("the one visualization is the override block's intended success — nothing unexplained may sit beside it") {
                before shouldBe Residue(visualizations = 1, dashboards = 0)
            }

            val refusal =
                shouldThrow<DatapipelinesException> {
                    val entry = visualizationEnvelope(payload, templates = List(51) { templateNode(it) })
                    transfer(ctx).importVisualization(WS_B, entry, AUTHOR)
                }

            withClue("the default ceiling refuses what the operator's 75 admitted: ${refusal.details}") {
                refusal.code shouldBe VisualizationErrorCodes.BODY_INVALID
                refusal.details["reason"] shouldBe "too_many"
                refusal.details["count"] shouldBe 51
                refusal.details["max"] shouldBe 50
            }
            assertResidueUnchanged(before)
        }
    }

    @Test
    fun `the configured ceiling is inclusive - exactly at it imports, one over refuses`() {
        context("${VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path}=75").run { ctx ->
            val transfer = transfer(ctx)
            val payload = releasedPlainPayload(ctx.getBean(VisualizationService::class.java))
            VisualizationRepository(jdbc).deleteEntity(WS_A, UUID.fromString(payload.path("id").asText()))

            val atCap = visualizationEnvelope(payload, templates = List(AT_CAP) { templateNode(it) })
            transfer.importVisualization(WS_B, atCap, AUTHOR)
            countVisualizations(WS_B) shouldBe 1

            // The at-cap landing is the intended prior success — pinned, and the one-over refusal
            // below is asserted to add NOTHING to it (captured before/after; the delivered suite had
            // no residue check after this refusal at all).
            val before = residue()
            withClue("the one visualization is the at-cap landing, the intended prior success") {
                before shouldBe Residue(visualizations = 1, dashboards = 0)
            }

            val over =
                shouldThrow<DatapipelinesException> {
                    val overCap = visualizationEnvelope(payload, templates = List(AT_CAP + 1) { templateNode(it) })
                    transfer.importVisualization(WS_A, overCap, AUTHOR)
                }
            withClue("one over the operator's ceiling is the count bound, not a landing refusal: ${over.details}") {
                over.details["reason"] shouldBe "too_many"
                over.details["count"] shouldBe AT_CAP + 1
                over.details["max"] shouldBe AT_CAP
            }
            assertResidueUnchanged(before)
        }
    }

    @Test
    fun `a real envelope round-trips through the factory bean with an override active`() {
        context("${VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path}=1").run { ctx ->
            val svc = ctx.getBean(VisualizationService::class.java)
            val transfer = transfer(ctx)
            val created = createdVisualization(svc)
            stampReleased(created)

            val envelope = transfer.exportVisualization(WS_A, created.record.id)
            val exportedHash = envelope.get("visualization").get("body_hash").asText()

            // Ids are global (C29): a fresh target is simulated by removing the source rows, then importing
            // the envelope where they were — the module tests' shape.
            ctx.getBean(VisualizationRepository::class.java).deleteEntity(WS_A, created.record.id)

            val imported = transfer.importVisualization(WS_A, envelope, AUTHOR)

            imported.created shouldBe true
            imported.detail.artifactId shouldBe created.record.id
            imported.detail.status shouldBe PipelineVersionStatus.RELEASED
            imported.detail.bodyHash shouldBe exportedHash
        }
    }

    // ---- the guard -----------------------------------------------------------------------------------------

    /**
     * The residue assertions are UNCONDITIONAL — no infix matcher dangles off an Elvis fallback in this
     * file's source. An Elvis's right operand binds tighter than an infix function: a statement of the
     * shape "query, Elvis-zero, matcher" parses as "query, Elvis (zero, matcher)" — the matcher is part
     * of the FALLBACK and executes only when the left side is null. A non-null SQL COUNT therefore
     * returns from such a statement having asserted nothing. The delivered L1c-d suite shipped both
     * residue checks in that shape, reported green with zero executed assertions, and the root
     * reviewer's compiled witness (count 7 in, assertions 0 out) is the independent proof.
     *
     * Deliberately a text scan of THIS file in the OrderedTestAnnotationGuardTest style, not a helper
     * test that mirrors the faulty expression — a helper would stay green beside the bypass it copies.
     * The paren depth decides: a matcher reached while still at or inside the Elvis's own depth is part
     * of its fallback (the defect, explicitly parenthesized or not — grouping the matcher does not
     * change which operand it belongs to); only a matcher first reached AFTER the Elvis's enclosing
     * group has closed asserts the Elvis expression itself. Comment lines are skipped, so this KDoc
     * names the shape in prose, and the floor below keeps the scan from passing vacuously.
     */
    @Test
    fun `residue assertions execute - no matcher is bound to an Elvis fallback in this source`() {
        val lines = sourceFile().readLines()
        var matcherLines = 0
        val offenders =
            lines
                .withIndex()
                .mapNotNull { (index, line) ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@mapNotNull null
                    }
                    if (MATCHER.containsMatchIn(line)) matcherLines++
                    elvisBoundMatcher(line)?.let { "${index + 1}: $it" }
                }

        withClue(
            "An Elvis's fallback swallows the infix matcher: the assertion runs only when the left " +
                "side is null, and a SQL COUNT is never null — extract the query into a local value " +
                "and assert on it (the L1c-d defect, which executed zero residue assertions).",
        ) {
            offenders.shouldBeEmpty()
        }
        withClue("the scan must see matcher-bearing code lines — vacuous is not clean") {
            matcherLines shouldBeGreaterThanOrEqual MATCHER_FLOOR
        }
    }

    /** The offending line when an infix matcher sits inside an Elvis's fallback on [line], else null. */
    private fun elvisBoundMatcher(line: String): String? {
        val matcherStarts = MATCHER.findAll(line).map { it.range.first }.toSet()
        if (matcherStarts.isEmpty()) return null
        var from = 0
        while (true) {
            val elvis = line.indexOf("?:", from)
            if (elvis < 0) return null
            from = elvis + "?:".length
            val elvisDepth = depthAt(line, elvis)
            var depth = elvisDepth
            var at = elvis + "?:".length
            while (at < line.length) {
                if (depth < elvisDepth) break // the Elvis's enclosing group closed — a later matcher is outside it
                if (at in matcherStarts) return line.trim()
                when (line[at]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                at++
            }
        }
    }

    private fun depthAt(line: String, index: Int): Int {
        var depth = 0
        for (i in 0 until index) {
            when (line[i]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        return depth
    }

    /** This suite's own source, resolved by walking up to the repository root (the house guard pattern). */
    private fun sourceFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "modules/pipeline-contract").isDirectory) dir = dir.parentFile
        val root = checkNotNull(dir) { "repository root not found walking up from ${File("").absolutePath}" }
        return File(root, "modules/web/src/test/kotlin/co/datapipelines/web/config/ArtifactTransferConfigWiringTest.kt")
            .also { found -> check(found.isFile) { "guard source not found at ${found.path}" } }
    }

    // ---- fixtures ------------------------------------------------------------------------------------------

    /** A real visualization created through the context's service (the real save path), still DRAFT. */
    private fun createdVisualization(svc: VisualizationService) =
        svc.create(
            WS_A,
            VisualizationReader().readOrThrow(ArtifactJson.mapper.readTree(DocumentFixturesJson.VISUALIZATION)),
            AUTHOR,
            WriteSurface.MCP,
        )

    /** The SQL stamp the real release would write — the evidence gate is not this suite's subject. */
    private fun stampReleased(created: co.datapipelines.visualization.ArtifactVersion<*>) {
        jdbc.jdbcTemplate.execute(
            "UPDATE visualization_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$AUTHOR'" +
                " WHERE visualization_id = '${created.record.id}' AND version = 1",
        )
        jdbc.jdbcTemplate.execute("UPDATE visualizations SET current_version = 1 WHERE id = '${created.record.id}'")
        val status =
            jdbc.jdbcTemplate.queryForObject(
                "SELECT v.status FROM visualizations s JOIN visualization_versions v" +
                    " ON v.visualization_id = s.id AND v.version = 1 WHERE s.id = '${created.record.id}'",
                String::class.java,
            )
        withClue("the stamp must leave the visualization RELEASED") { status shouldBe "RELEASED" }
    }

    /** A released plain visualization's payload, read back through the service's repository. */
    private fun releasedPlainPayload(svc: VisualizationService): ObjectNode {
        val created = createdVisualization(svc)
        stampReleased(created)
        return ArtifactTransferService.payloadOf(checkNotNull(VisualizationRepository(jdbc).findVersion(WS_A, created.record.id, 1)))
    }

    /**
     * A dashboard-shaped envelope whose `visualizations` bundle carries [bundledCopies] entries. The
     * dashboard BODY itself carries ONE occurrence — at an override of 1 the reader's own document bound
     * (the same key) must admit the body, so the bound under test here is the ENVELOPE bundle's, not the
     * document occurrences'.
     */
    private fun dashboardEnvelope(bundledCopies: Int): ObjectNode {
        val body = ArtifactJson.mapper.readTree(DocumentFixturesJson.DASHBOARD).deepCopy() as ObjectNode
        body.put("id", UUID.randomUUID().toString())
        body.put("name", "cfg/dashboards/envelope")
        body.put("body_hash", "unverified-when-version-is-absent")
        val envelope = ArtifactJson.mapper.createObjectNode() as ObjectNode
        envelope.set<JsonNode>("dashboard", body)
        val bundle = ArtifactJson.mapper.createArrayNode()
        val entry = ArtifactJson.mapper.createObjectNode().set<JsonNode>("visualization", templatelessVisualizationPayload()) as ObjectNode
        repeat(bundledCopies) { bundle.add(entry.deepCopy()) }
        envelope.set<JsonNode>("visualizations", bundle)
        return envelope
    }

    /** A version-less plain visualization payload — the bundle's bound fires before any member is parsed. */
    private fun templatelessVisualizationPayload(): JsonNode {
        val payload = ArtifactJson.mapper.readTree(DocumentFixturesJson.VISUALIZATION).deepCopy() as ObjectNode
        payload.put("id", UUID.randomUUID().toString())
        payload.put("name", "cfg/charts/bundled")
        payload.put("body_hash", "unverified-when-version-is-absent")
        return payload
    }

    private fun visualizationEnvelope(
        payload: ObjectNode,
        templates: List<JsonNode>,
    ): ObjectNode {
        val envelope = ArtifactJson.mapper.createObjectNode() as ObjectNode
        envelope.set<JsonNode>("visualization", payload)
        envelope.set<JsonNode>("templates", ArtifactJson.mapper.createArrayNode().addAll(templates))
        return envelope
    }

    private fun templateNode(index: Int): JsonNode =
        ArtifactJson.mapper.createObjectNode().put("id", "cfg$RUN/templates/carried_$index.sql")

    private fun countVisualizations(workspace: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM visualizations WHERE workspace_id = :ws", mapOf("ws" to workspace), Int::class.java) ?: 0

    /**
     * The attempt's residue: this suite truncates the artifact tables before EVERY test, so the GLOBAL
     * counts are exactly this test method's attempt — no other case's rows can sit inside them. The
     * queries are extracted into LOCAL values so that the assertions in [nothingLanded] are
     * unconditional: an infix matcher bound to an Elvis fallback executes only when the left side is
     * null, and a SQL COUNT is never null — the delivered suite's residue checks were of that shape
     * and executed zero assertions (L1c-d).
     */
    private fun residue(): Residue =
        Residue(
            jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM visualizations", Int::class.java) ?: 0,
            jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM dashboards", Int::class.java) ?: 0,
        )

    private data class Residue(val visualizations: Int, val dashboards: Int)

    private fun nothingLanded() {
        val after = residue()
        withClue("nothing may land when the bound refuses — the counts are unconditional assertions on local values") {
            after.visualizations shouldBe 0
            after.dashboards shouldBe 0
        }
    }

    /**
     * The honest residue claim where the method INTENDS a prior success: the refusal adds NOTHING —
     * the counts after the refused attempt are exactly the captured before-state, whatever that
     * state was.
     */
    private fun assertResidueUnchanged(before: Residue) {
        withClue("a refused import adds no residue — the counts must be exactly the captured $before") {
            residue() shouldBe before
        }
    }

    private companion object {
        val WS_A: UUID = UUID.fromString("c0a10000-0000-0000-0000-000000000001")
        val WS_B: UUID = UUID.fromString("c0b20000-0000-0000-0000-000000000002")
        val AUTHOR: UUID = UUID.fromString("c0a90000-0000-0000-0000-000000000003")
        val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        const val AT_CAP = 75

        /** A Kotest infix matcher: the bare word, or the word followed by a capital continuation. */
        val MATCHER = Regex("""\bshould(?:[A-Z]\w*)?\b""")

        /** Matcher-bearing CODE lines this file held at the guard's birth (the non-vacuity floor). */
        const val MATCHER_FLOOR = 25

        /** The reader-valid minimal documents (the module fixtures' shapes, reader-bound before use). */
        object DocumentFixturesJson {
            const val VISUALIZATION =
                """{"name":"cfg/charts/plain","display_name":"Plain","description":"config wiring",
                   "renderer":{"kind":"plotly","version":"4"},
                   "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                                   {"name":"amount","type":"DECIMAL","nullable":false}]}},
                   "config":{"data":[{"type":"bar","x":[],"y":[]}],"layout":{}},
                   "bindings":{"data[0].x":"month","data[0].y":"amount"},
                   "presentation":{"title":"Plain","tokens":{"series":"categorical"}},
                   "tests":{"cases":[{"name":"one","fixtures":{"revenue":[{"month":"2026-01-01","amount":10.5}]},
                                      "assertions":[{"kind":"rendered"}]}]}}"""

            const val DASHBOARD =
                """{"name":"cfg/dashboards/envelope","display_name":"Envelope","description":"config wiring",
                   "sources":[{"name":"src","pipeline":{"name":"cfg/pipelines/src","version":1},"parameters":{}}],
                   "visualizations":[
                     {"name":"chart_a","type":"visualization","visualization":{"name":"cfg/charts/a","version":1},
                      "inputs":{"revenue":{"source":"src"}},"timeout_seconds":120}],
                   "actions":[{"name":"refresh","type":"refresh","scope":"targets","targets":["chart_a"],"initial":true}],
                   "action_controls":[{"name":"btn","type":"action_control","action":"refresh","label":"Apply"}],
                   "layout":{"grid":[{"name":"chart_a","x":0,"y":0,"w":6,"h":4},
                                     {"name":"btn","x":0,"y":4,"w":2,"h":1}],"columns":12},
                   "timeouts":{"refresh_seconds":300}}"""
        }

        /**
         * The collaborators the production factory needs, over the REAL database — real repositories,
         * services and transaction manager; relaxed fakes only where the exercised path reads no row
         * (the transfer's pinless artifacts consult no template or pipeline fact).
         *
         * The ONE [DataSource] bean is load-bearing (the L1c-d finding): a transaction manager binds
         * its transaction resource PER DATASOURCE INSTANCE, and `SharedPostgres.dataSource()` hands
         * out a NEW instance on every call — wiring the repositories and the manager to two different
         * instances left every repository statement auto-committing OUTSIDE the transfer's
         * transactions, and the F1 "rollback" of the default-context control silently rolled back
         * nothing. The repaired residue assertion caught it on its first honest run (expected 0, the
         * first bundled member had landed). Production shares THE one DataSource bean
         * (`TransactionConfiguration.metadataTransactionManager(dataSource: DataSource)`), and the
         * three-deployment E2E witnesses the real rollback against that wiring.
         */
        @Configuration
        @Suppress("unused")
        class RealCollaborators {
            @Bean
            fun dataSource(): javax.sql.DataSource = SharedPostgres.dataSource()

            @Bean
            fun jdbcTemplate(dataSource: javax.sql.DataSource): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource)

            @Bean
            fun templateRepository(): co.datapipelines.templates.TemplateRepository = io.mockk.mockk(relaxed = true)

            @Bean
            fun templateImportService(): co.datapipelines.web.templates.TemplateImportService = io.mockk.mockk(relaxed = true)

            @Bean
            fun templateDryRenderer(): co.datapipelines.pipeline.TemplateDryRenderer = io.mockk.mockk(relaxed = true)

            @Bean
            fun templateVersionStatuses(): co.datapipelines.pipeline.TemplateVersionStatuses =
                co.datapipelines.pipeline.TemplateVersionStatuses { _, _, _ -> null }

            @Bean
            fun templateReleaser(): co.datapipelines.pipeline.TemplateReleaser =
                co.datapipelines.pipeline.TemplateReleaser { _, _, _, _ -> error("not reached in the transfer wiring test") }

            @Bean
            fun pipelineResolver(): co.datapipelines.pipeline.PipelineResolver =
                co.datapipelines.pipeline.PipelineResolver { _, _, _ -> null }

            @Bean
            fun readOnlyPipelineRule(
                resolver: co.datapipelines.pipeline.PipelineResolver,
            ): co.datapipelines.application.endpoints.ReadOnlyPipelineRule =
                co.datapipelines.application.endpoints
                    .ReadOnlyPipelineRule(resolver, maxCompositionDepth = 5)

            @Bean
            fun parameterSetRepository(jdbc: NamedParameterJdbcTemplate): co.datapipelines.parameters.ParameterSetRepository =
                co.datapipelines.parameters.ParameterSetRepository(jdbc)

            @Bean
            fun authoringGuard(): co.datapipelines.pipeline.AuthoringGuard = co.datapipelines.pipeline.AuthoringGuard(true)

            @Bean
            fun transactionManager(dataSource: javax.sql.DataSource): PlatformTransactionManager =
                DataSourceTransactionManager(dataSource)
        }
    }
}
