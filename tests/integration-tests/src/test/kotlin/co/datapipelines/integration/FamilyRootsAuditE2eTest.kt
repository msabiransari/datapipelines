package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import co.datapipelines.integration.FamilyRootsFixture.Edge
import co.datapipelines.integration.FamilyRootsFixture.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #873 §C.7 and A14 (apply half) over the REAL wired application on PostgreSQL, for the template, parameter-set,
 * visualization and dashboard roots, every artifact authored through the product and every fact read back on an
 * INDEPENDENT connection (round 2, verdict R4).
 *
 * - **Strict audit, both entries.** Per root, through the planned REST entry (a `tree_hash` plan selecting the whole
 *   all-DRAFT closure) AND the family's release dialog POST carrying the same plan (`tree_hash` and repeated
 *   `include_dependencies` form fields; every family has a dialog since #876 §F): the production strict sink, behind
 *   [FailingAuditConfiguration], throws a JAVA exception on the release's LAST row — the root's own, written after
 *   every flip and every dependency row. A witness trigger on
 *   the root's DRAFT → RELEASED flip proves the flips ran (`nextval` survives the rollback), and that every selected
 *   dependency was already RELEASED inside the transaction then. Every flip, pointer and audit row must roll back.
 *   Then the same request, unarmed, releases: exactly one persisted event per actual flip (the read is not filtered on
 *   `release_id`, so a second writer's post-commit row is counted and red), one shared `release_id`, dependencies first.
 * - **Selected-child discard interleaving.** A second connection takes the release guard and discards a DRAFT child
 *   the plan selected, uncommitted; the apply plans (it still sees the DRAFT), reaches the guard and waits — proved on
 *   `pg_stat_activity` — then the discard commits and the guarded re-walk refuses (`plan_conflict` or
 *   `dependency_not_released`) with nothing flipped and no row. Reverting the plant, the same plan then releases.
 * - **A34 through the wired coordinator.** A dashboard binding the set's `region` is authored valid; a SQL plant past
 *   the save validator renames the set's parameter. The planned REST entry and the plan-less REST entry both refuse with
 *   the dashboard's native `parameter_unbound` located at the set edge (`path` and `edge_id`), nothing flipped; the
 *   plant reverted, the satisfied pair releases.
 * - **The retired alias, refused on purpose (#876 round 3).** Per root, the dialog POST without a plan field and the
 *   family's REST consent flag without a plan are refused `428 <family>.release.plan_required`; the database still
 *   shows the root and every dependency DRAFT, and no release row was written.
 */
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FailingAuditConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FamilyRootsAuditE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val fx by lazy { FamilyRootsFixture(port, SESSION, WORKSPACE_ID, DATASOURCE) }
    private val pool = Executors.newFixedThreadPool(2)

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun scalar(
        sql: String,
        vararg args: String,
    ): String? =
        connect().use { c ->
            c.prepareStatement(sql).use { s ->
                args.forEachIndexed { index, arg -> s.setString(index + 1, arg) }
                s.executeQuery().use(::firstColumn)
            }
        }

    private fun firstColumn(rows: ResultSet): String? = if (rows.next()) rows.getString(1) else null

    /** Every `*.version.released` row this class's admin caused after audit id [after] — `event | release_id`, in order. */
    private fun auditAfter(after: Long): List<Pair<String, String?>> =
        connect().use { c ->
            c
                .prepareStatement(
                    "SELECT event, details_json ->> 'release_id' FROM audit_log WHERE id > ? AND user_id = ?::uuid" +
                        " AND event LIKE '%.version.released' ORDER BY id",
                ).use { s ->
                    s.setLong(1, after)
                    s.setString(2, ADMIN_ID)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1) to r.getString(2)) } }
                }
        }

    private fun lastAuditId(): Long = checkNotNull(scalar("SELECT COALESCE(MAX(id), 0)::text FROM audit_log")).toLong()

    private fun state(
        root: Node,
        edges: List<Edge>,
    ): Map<Node, FamilyRootsFixture.Row> = fx.nodeIds(root, edges).keys.associateWith(fx::row)

    // ---- the cases' closures ----------------------------------------------------------------------------------

    /** One release: its root and closure, the nodes it flips besides the root, and its rows in insert order. */
    private data class Case(
        val root: Node,
        val edges: List<Edge>,
        val flipped: List<Node>,
        val events: List<String>,
        val send: () -> Response,
        val dialog: Boolean,
    )

    private fun case(
        family: String,
        entry: String,
    ): Case {
        val (root, edges) = fx.closure(family, "test/fra873/$family-$entry")
        val nodes = fx.nodeIds(root, edges).keys.toList()
        val send: () -> Response =
            if (entry == PLANNED) {
                { fx.releaseWithPlan(root, edges) }
            } else {
                { fx.releaseDialog(root, edges) }
            }
        return Case(root, edges, nodes - root, PLAN_EVENTS.getValue(family), send, dialog = entry != PLANNED)
    }

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - the H2 datasource the pipelines and selectors read`() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .asSession(SESSION)
            .contentType(ContentType.JSON)
            .body(
                """{"name": "$DATASOURCE", "display_name": "Family roots audit", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
            ).post("/api/v1/datasources")
            .then()
            .statusCode(CREATED)
        withClue("no witness or plant is left from an earlier run") { leftovers() shouldBe "0" }
    }

    // The multiline source sits ABOVE @ParameterizedTest: OrderedTestAnnotationGuardTest reads only the contiguous
    // annotation lines around @Order, so @Order must share its block with the test annotation and the fun.
    @CsvSource(
        "template, planned",
        "template, dialog",
        "parameter_set, planned",
        "parameter_set, dialog",
        "visualization, planned",
        "visualization, dialog",
        "dashboard, planned",
        "dashboard, dialog",
    )
    @ParameterizedTest(name = "{0} through the {1} entry")
    @Order(2)
    fun `the root's audit row fails after every flip - all rolls back, then the same request writes one row per flip`(
        family: String,
        entry: String,
    ) {
        val case = case(family, entry)
        val before = state(case.root, case.edges)
        withClue("the case flips something: ${case.flipped}") { case.events.size shouldBe case.flipped.size + 1 }
        val audit = lastAuditId()

        plantWitness(case)
        FailingAuditConfiguration.arm(case.events.size)
        val failed =
            try {
                case.send()
            } finally {
                FailingAuditConfiguration.disarm()
            }
        val refusedRow = FailingAuditConfiguration.refused.get()
        val (rootFlips, childFirst) = witnessed()
        dropWitness()
        val afterFailure = state(case.root, case.edges)
        println(
            "event=family_roots.c7 root=$family entry=$entry refused_row=$refusedRow root_flips=$rootFlips child_first=$childFirst" +
                " status=${failed.statusCode} redirect=${failed.header(HX_REDIRECT)}",
        )
        rolledBack(Observed(refusedRow, rootFlips, childFirst, afterFailure, failed), family, before, audit)

        released(case, before, audit)
    }

    /** What one armed attempt left: the refused row, the witness counts, the committed state and the response. */
    private data class Observed(
        val refusedRow: String?,
        val rootFlips: Int,
        val childFirst: Int,
        val state: Map<Node, FamilyRootsFixture.Row>,
        val response: Response,
    )

    private fun rolledBack(
        observed: Observed,
        family: String,
        before: Map<Node, FamilyRootsFixture.Row>,
        audit: Long,
    ) {
        withClue("the plant refused the ROOT's row, the release's last") { observed.refusedRow shouldBe EVENTS.getValue(family) }
        withClue("the root flipped before its audit row failed, every selected dependency already RELEASED") {
            observed.rootFlips shouldBe 1
            observed.childFirst shouldBe 1
        }
        withClue("independent-connection read: every flip and pointer rolled back") { observed.state shouldBe before }
        withClue("no audit row survives the rollback") { auditAfter(audit) shouldBe emptyList() }
        withClue(observed.response.asString().take(EXCERPT)) {
            observed.response.header(HX_REDIRECT) shouldBe null
            observed.response.statusCode shouldBe INTERNAL_ERROR
        }
    }

    /** The same request unarmed: one persisted row per actual flip, one `release_id`, every node RELEASED unchanged. */
    private fun released(
        case: Case,
        before: Map<Node, FamilyRootsFixture.Row>,
        audit: Long,
    ) {
        plantWitness(case)
        val released =
            try {
                case.send()
            } finally {
                FailingAuditConfiguration.refused.set(null)
            }
        val (controlRootFlips, controlChildFirst) = witnessed()
        dropWitness()
        withClue(released.asString().take(EXCERPT)) {
            released.statusCode shouldBe OK
            if (case.dialog) released.header(HX_REDIRECT) shouldNotBe null
        }
        controlRootFlips shouldBe 1
        controlChildFirst shouldBe 1
        state(case.root, case.edges).forEach { (node, row) ->
            withClue(node) {
                row.status shouldBe "RELEASED"
                row.current shouldBe "1"
                row.hash shouldBe before.getValue(node).hash
            }
        }
        val rows = auditAfter(audit)
        println("event=family_roots.c7.control root=${case.root} rows=$rows")
        withClue("exactly one persisted event per actual flip, dependencies first, the root last") {
            rows.map { it.first } shouldContainExactly case.events
        }
        withClue("one shared release_id") {
            rows.map { it.second }.toSet().size shouldBe 1
            rows.first().second shouldNotBe null
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["template", "parameter_set", "visualization", "dashboard"])
    @Order(3)
    fun `a selected DRAFT child discarded on a second connection between plan and apply refuses, nothing flipped`(family: String) {
        val (root, edges) = fx.closure(family, "test/fra873/a14-$family")
        val child = if (family == "dashboard") edges.single { it.path == "visualizations[0].visualization" }.to else edges.first().to
        val previewed = fx.treeHash(root, edges)
        val before = state(root, edges)
        val audit = lastAuditId()

        val response = discardDuring(child) { fx.releaseWithPlan(root, edges, previewed) }

        val after = state(root, edges)
        println("event=family_roots.a14 root=$family child=${child.family}:${child.name} status=${response.statusCode} after=$after")
        withClue(response.asString().take(EXCERPT)) {
            response.statusCode shouldBe CONFLICT
            response.jsonPath().getString("error.code") shouldBeIn
                CODE_PREFIX.getValue(family).let { listOf("$it.release.plan_conflict", "$it.release.dependency_not_released") }
        }
        withClue("independent-connection read: nothing flipped; the child is the plant's DISCARDED") {
            after - child shouldBe before - child
            after.getValue(child).status shouldBe "DISCARDED"
        }
        auditAfter(audit) shouldBe emptyList()

        undiscard(child)
        val released = fx.releaseWithPlan(root, edges, previewed)
        withClue("the refusal was the interleaved discard's: reverted, the same plan releases ${released.asString().take(EXCERPT)}") {
            released.statusCode shouldBe OK
        }
        auditAfter(audit).size shouldBe before.size
    }

    @Test
    @Order(4)
    fun `A34 - a stored dashboard binding a name its pinned set lacks refuses at the set edge on both entries, then releases`() {
        val (root, edges) = fx.dashboardClosure("test/fra873/a34", bindRegion = true)
        val set = edges.single { it.path == "parameter_set" }.to
        val previewed = fx.treeHash(root, edges)
        renameSetParameter(set, from = "region", to = "area")
        val before = state(root, edges)
        val audit = lastAuditId()

        val planned = fx.releaseWithPlan(root, edges, previewed)
        val planless = fx.send("POST", "/api/v1/dashboards/${fx.row(root).uuid}/release", "", fx.row(root).hash)

        listOf("planned" to planned, "planless" to planless).forEach { (entry, response) ->
            println("event=family_roots.a34 entry=$entry status=${response.statusCode} body=${response.asString().take(EXCERPT)}")
            withClue("$entry: ${response.asString().take(EXCERPT)}") {
                response.statusCode shouldBe BAD_REQUEST
                response.jsonPath().getString("error.code") shouldBe "dashboard.validation.parameter_unbound"
                val failure = response.jsonPath().getMap<String, Any?>("error.details.failures[0]")
                failure["path"] shouldBe "parameter_set"
                @Suppress("UNCHECKED_CAST")
                val details = failure["details"] as Map<String, Any?>
                details["parameter"] shouldBe "region"
                details["reason"] shouldBe "set_parameter_unknown"
                (details["edge_id"] as String?).isNullOrEmpty() shouldBe false
                details["parameter_set"] shouldBe mapOf("name" to set.name, "version" to 1)
                response.jsonPath().getList<Any>("error.details.failures").size shouldBe 1
            }
        }
        withClue("independent-connection read: nothing flipped, no row") {
            state(root, edges) shouldBe before
            auditAfter(audit) shouldBe emptyList()
        }

        renameSetParameter(set, from = "area", to = "region")
        val released = fx.releaseWithPlan(root, edges, previewed)
        withClue("the satisfied pair releases: ${released.asString().take(EXCERPT)}") { released.statusCode shouldBe OK }
        state(root, edges).values.map { it.status }.toSet() shouldBe setOf("RELEASED")
    }

    // The multiline source sits ABOVE @ParameterizedTest for the same reason as the order-2 case's.
    @CsvSource(
        "template, dialog",
        "parameter_set, dialog",
        "parameter_set, rest",
        "visualization, dialog",
        "visualization, rest",
        "dashboard, dialog",
        "dashboard, rest",
    )
    @ParameterizedTest(name = "{0} through the retired alias, {1} entry")
    @Order(5)
    fun `the retired alias is refused plan_required on purpose - the root and every dependency stay DRAFT, no row is written`(
        family: String,
        entry: String,
    ) {
        val (root, edges) = fx.closure(family, "test/fra873/alias-$family-$entry")
        val before = state(root, edges)
        before.values.map { it.status }.toSet() shouldBe setOf("DRAFT")
        val audit = lastAuditId()

        val refused = if (entry == "dialog") fx.releaseDialogWithoutPlan(root) else fx.releaseLegacyAlias(root)

        withClue(refused.asString().take(EXCERPT)) {
            refused.statusCode shouldBe PRECONDITION_REQUIRED
            if (entry == "rest") refused.jsonPath().getString("error.code") shouldBe "${CODE_PREFIX.getValue(family)}.release.plan_required"
            refused.header(HX_REDIRECT) shouldBe null
        }
        withClue("independent-connection read: nothing flipped, every pointer unmoved, no release row") {
            state(root, edges) shouldBe before
            auditAfter(audit) shouldBe emptyList()
        }
    }

    /**
     * A SQL plant past the save validator: [set]'s DRAFT v1 renames its one parameter. Its stored `body_hash` is left as
     * is, so the plan's tree hash still matches and the refusal is the bound-name check's alone.
     */
    private fun renameSetParameter(
        set: Node,
        from: String,
        to: String,
    ) {
        connect().use { c ->
            c
                .prepareStatement(
                    "UPDATE parameter_set_versions SET body_json = jsonb_set(body_json, '{parameters,0,name}', to_jsonb(?::text))" +
                        " WHERE parameter_set_id = ?::uuid AND version = 1 AND body_json #>> '{parameters,0,name}' = ?",
                ).use { ps ->
                    ps.setString(1, to)
                    ps.setString(2, fx.row(set).uuid)
                    ps.setString(3, from)
                    withClue("the plant renames exactly one parameter") { ps.executeUpdate() shouldBe 1 }
                }
        }
    }

    // ---- the witness and the interleaving ---------------------------------------------------------------------

    /** Bumps [ROOT_FLIPS] on the root's DRAFT → RELEASED flip, and [CHILD_FIRST] when every flipped dependency is RELEASED. */
    private fun plantWitness(case: Case) {
        val (_, versions, fk) = FamilyRootsFixture.TABLES.getValue(case.root.family)
        val rootId = UUID.fromString(fx.row(case.root).uuid)
        val released =
            (
                listOf("TRUE") +
                    case.flipped.map { node ->
                        val (table, nodeVersions, nodeFk) = FamilyRootsFixture.TABLES.getValue(node.family)
                        "EXISTS (SELECT 1 FROM $nodeVersions v JOIN $table a ON a.id = v.$nodeFk" +
                            " WHERE a.workspace_id = '$WORKSPACE_ID'::uuid AND a.name = '${literal(node.name)}'" +
                            " AND v.version = 1 AND v.status = 'RELEASED')"
                    }
            ).joinToString(" AND ")
        connect().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE SEQUENCE $ROOT_FLIPS")
                s.execute("CREATE SEQUENCE $CHILD_FIRST")
                s.execute(
                    """
                    CREATE FUNCTION $FUNCTION() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        PERFORM nextval('$ROOT_FLIPS');
                        IF $released THEN
                            PERFORM nextval('$CHILD_FIRST');
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                s.execute(
                    "CREATE TRIGGER $TRIGGER BEFORE UPDATE ON $versions FOR EACH ROW WHEN" +
                        " (OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' AND OLD.$fk = '$rootId'::uuid) EXECUTE FUNCTION $FUNCTION()",
                )
            }
        }
        witnessTable = versions
    }

    private var witnessTable: String? = null

    private fun witnessed(): Pair<Int, Int> = count(ROOT_FLIPS) to count(CHILD_FIRST)

    private fun count(sequence: String): Int =
        checkNotNull(scalar("SELECT (CASE WHEN is_called THEN last_value ELSE 0 END)::text FROM $sequence")).toInt()

    private fun dropWitness() {
        connect().use { c ->
            c.createStatement().use { s ->
                (witnessTable?.let { listOf(it) } ?: FamilyRootsFixture.TABLES.values.map { it.second }).forEach { table ->
                    s.execute("DROP TRIGGER IF EXISTS $TRIGGER ON $table")
                }
                s.execute("DROP FUNCTION IF EXISTS $FUNCTION()")
                s.execute("DROP SEQUENCE IF EXISTS $ROOT_FLIPS")
                s.execute("DROP SEQUENCE IF EXISTS $CHILD_FIRST")
            }
        }
        witnessTable = null
    }

    /** A test-generated artifact name as a SQL literal body: names are this class's constants, never request input. */
    private fun literal(name: String): String = name.also { require(SAFE_NAME.matches(it)) { "unexpected name $it" } }

    /**
     * A SECOND connection takes the release guard (the workspace row, `JdbcReleaseMutationGuard`'s lock) and flips
     * [child]'s DRAFT v1 to DISCARDED, uncommitted — a SQL plant: the product purges a draft, it never discards one,
     * and the property under test is the guarded re-walk's read. [apply] runs meanwhile; it must be seen WAITING on
     * the guard (so it planned before the discard was visible), then the discard commits.
     */
    private fun discardDuring(
        child: Node,
        apply: () -> Response,
    ): Response =
        connect().use { holder ->
            holder.autoCommit = false
            val holderPid =
                holder.createStatement().use { s ->
                    s.executeQuery("SELECT pg_backend_pid()").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            holder.prepareStatement("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE").use { ps ->
                ps.setString(1, WORKSPACE_ID)
                ps.executeQuery().use { withClue("the guard row exists") { it.next() shouldBe true } }
            }
            holder.prepareStatement(stampSql(child, discard = true)).use { ps ->
                ps.setString(1, ADMIN_ID)
                ps.setString(2, ADMIN_ID)
                ps.setString(3, fx.row(child).uuid)
                withClue("the planted discard flips exactly one DRAFT") { ps.executeUpdate() shouldBe 1 }
            }
            val applied = CompletableFuture.supplyAsync({ apply() }, pool)
            val waiting = awaitWaiter(holderPid, applied)
            println("event=family_roots.a14.wait child=${child.name} waiting_on_holder=$waiting")
            withClue("the apply must plan, then wait on the holder's uncommitted discard (answered first: ${applied.isDone})") {
                (waiting != null) shouldBe true
            }
            holder.commit()
            applied.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    /** The plant reverted: [child] back to an unreleased DRAFT, stamps cleared. */
    private fun undiscard(child: Node) {
        connect().use { c ->
            c.prepareStatement(stampSql(child, discard = false)).use { ps ->
                ps.setString(1, fx.row(child).uuid)
                ps.executeUpdate() shouldBe 1
            }
        }
    }

    private fun stampSql(
        child: Node,
        discard: Boolean,
    ): String {
        val (_, versions, fk) = FamilyRootsFixture.TABLES.getValue(child.family)
        return if (discard) {
            "UPDATE $versions SET status = 'DISCARDED', released_at = NOW(), released_by = ?::uuid, discarded_at = NOW()," +
                " discarded_by = ?::uuid WHERE $fk = ?::uuid AND version = 1 AND status = 'DRAFT'"
        } else {
            "UPDATE $versions SET status = 'DRAFT', released_at = NULL, released_by = NULL, discarded_at = NULL, discarded_by = NULL" +
                " WHERE $fk = ?::uuid AND version = 1 AND status = 'DISCARDED'"
        }
    }

    /**
     * The statement of a backend [holderPid] blocks (fresh connection per probe), or null: the release guard's
     * workspace lock, or — where the apply's preparation locks the planted child's own row first — that row. Either way
     * the apply read its plan before the discard was visible and resumes only after it commits.
     */
    private fun awaitWaiter(
        holderPid: Int,
        writer: CompletableFuture<*>,
    ): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while (!writer.isDone && System.nanoTime() < deadline) {
            scalar(
                "SELECT left(query, $QUERY_EXCERPT) FROM pg_stat_activity WHERE wait_event_type = 'Lock'" +
                    " AND ?::int = ANY(pg_blocking_pids(pid)) ORDER BY pid LIMIT 1",
                holderPid.toString(),
            )?.let { return it }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        return null
    }

    private fun leftovers(): String? =
        scalar(
            "SELECT ((SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'dp873a%')" +
                " + (SELECT count(*) FROM pg_proc WHERE proname LIKE 'dp873a%')" +
                " + (SELECT count(*) FROM pg_class WHERE relname LIKE 'dp873a%'))::text",
        )

    @AfterAll
    fun dropAndProve() {
        dropWitness()
        pool.shutdownNow()
        val leftovers = leftovers()
        println("pg_trigger/pg_proc/pg_class dp873a objects after the class = $leftovers")
        oidc.close()
        leftovers shouldBe "0"
    }

    companion object {
        private const val OK = 200
        private const val CREATED = 201
        private const val BAD_REQUEST = 400
        private const val PRECONDITION_REQUIRED = 428
        private const val CONFLICT = 409
        private const val INTERNAL_ERROR = 500
        private const val EXCERPT = 800
        private const val HX_REDIRECT = "HX-Redirect"
        private const val PLANNED = "planned"
        private const val DATASOURCE = "h2-family-roots-audit-873"
        private const val H2_JDBC_URL = "jdbc:h2:mem:familyrootsaudit873;DB_CLOSE_DELAY=-1"
        private const val FUNCTION = "dp873a_witness"
        private const val ROOT_FLIPS = "dp873a_root_flips"
        private const val CHILD_FIRST = "dp873a_child_first"
        private const val TRIGGER = "dp873a_root_flip"
        private const val WORKSPACE = "family-roots-audit-873"

        /** The probe gives up before the writer's own lock_timeout would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L
        private const val QUERY_EXCERPT = 160
        private val SAFE_NAME = Regex("^[a-z0-9_/.-]+$")

        private const val TEMPLATE_EVENT = "template.version.released"

        /** Each root family's error-code prefix (the parameter-set family's catalogue says `parameter`). */
        private val CODE_PREFIX =
            mapOf("template" to "template", "parameter_set" to "parameter", "visualization" to "visualization", "dashboard" to "dashboard")

        /** Each family's own release row. */
        private val EVENTS =
            mapOf(
                "template" to TEMPLATE_EVENT,
                "pipeline" to "pipeline.version.released",
                "parameter_set" to "parameter_set.version.released",
                "visualization" to "visualization.version.released",
                "dashboard" to "dashboard.version.released",
            )

        /** The planned entry's rows per root (the whole closure selected): dependency-first, the root last. */
        private val PLAN_EVENTS =
            mapOf(
                "template" to listOf(TEMPLATE_EVENT, TEMPLATE_EVENT),
                "parameter_set" to listOf(TEMPLATE_EVENT, "parameter_set.version.released"),
                "visualization" to listOf(TEMPLATE_EVENT, "visualization.version.released"),
                "dashboard" to
                    listOf(
                        TEMPLATE_EVENT,
                        "parameter_set.version.released",
                        TEMPLATE_EVENT,
                        "pipeline.version.released",
                        TEMPLATE_EVENT,
                        "visualization.version.released",
                        "dashboard.version.released",
                    ),
            )

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-873-audit@datapipelines.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Family roots audit 873')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-873-audit@datapipelines.test', 'E2E 873 audit', 'test', 'e2e-873-audit-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                }
            }
        }
    }
}
