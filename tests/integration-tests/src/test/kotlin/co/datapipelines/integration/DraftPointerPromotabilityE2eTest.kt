package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.UUID

/**
 * This module imports no domain types (module-structure §5.11), so the five families ride a
 * local name table: the tree route segment and the wire inventory's field per family.
 */
private enum class Family(
    val segment: String,
    val wireField: String,
) {
    TEMPLATES("templates", "templates"),
    PIPELINES("pipelines", "pipelines"),
    PARAMETER_SETS("parameter-sets", "parameter_sets"),
    VISUALIZATIONS("visualizations", "visualizations"),
    DASHBOARDS("dashboards", "dashboards"),
}

/**
 * #563 — an object whose current pointer names a DRAFT is not promotable, read off the real
 * HTTP stack (owner ruling 2026-10-17): the §10.2 listing, the promoter's narrowed tree and
 * the wire inventory all stop carrying it, and agree again once the pointer returns to the
 * release. FamilyInventory (surface c of the brief) has no route and its domain types are
 * hidden from this module (module-structure §5.11), so that assertion lives with the real
 * derivation in web's `FamilyInventoryE2eTest` — the draft-pointer state seeded there is the
 * exact final state the chains below reach by verbs.
 *
 * The draft-pointer state is reached with the supported verbs of versioning §3.4 only: create
 * (v1 DRAFT), release v1, draft v2, release v2, draft v3, DISCARD the current v2 — the pointer
 * falls to the highest eligible live version, the DRAFT v3 — and separately the MANUAL SWITCH
 * onto the draft. Every pointer move here is a REST verb; no SQL touches a pointer or a status.
 *
 * Per family, at the draft pointer: the promotion page's plan rows carry no candidate naming
 * the object (the listing rule lives in `PromotionService.plan`, which this page renders
 * exactly); the promoter's narrowed tree omits the object while the author's tree still lists
 * it (the differential keeps the assertion non-vacuous); and the wire inventory is OBSERVED,
 * not pinned — the set and artifact arms exclude the draft pointer, while the pipeline and
 * template arms still ship its hash: the follow-up this lane files, not the rule under test.
 * After the pointer returns to the release, the page lists it, the promoter's tree lists it
 * and the wire inventory carries version 1.
 *
 * The one seeding shortcut is the visualization family's two RELEASED versions: nothing can
 * release a visualization before L4's evidence gate (`DashboardLifecycleE2eTest` seeds its
 * released artifacts the same way, for the same reason). Every pointer move is still a verb.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk per family keeps each chain readable; a shared harness would hide the routes
class DraftPointerPromotabilityE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /** Per family and chain, the states this suite actually walked — the non-vacuity floor. */
    private val walked = mutableListOf<String>()

    private lateinit var templateId: String
    private lateinit var pipelineId: String
    private lateinit var setId: String
    private lateinit var visualizationId: String
    private lateinit var dashboardId: String

    @Test
    @Order(1)
    fun `fixture - the workspace, the two roles, the H2 datasource and the released visualization`() {
        E2eClean.beforeSeeding()
        // The truncate wiped the boot-seeded system actor (auth.md §4.5); the promotion peer
        // filter stamps the pre-shared key with it, so re-provision the row the seeder writes.
        sql(
            """
            INSERT INTO users (email, display_name, provider, provider_subject, is_admin, kind)
            VALUES ('system@system.invalid', 'System', 'system', 'system', FALSE, 'system')
            ON CONFLICT (email) DO NOTHING
            """.trimIndent(),
        )
        seedAuthRows()
        registerH2Datasource()
        seedReleasedVisualization()
    }

    @Test
    @Order(2)
    fun `templates - a draft pointer is not promotable, by discard-of-current and by manual switch`() {
        templateId = TEMPLATE_ID
        val created =
            post(
                "/api/v1/templates",
                """
                {"id": "$TEMPLATE_ID", "dialect": "H2", "display_name": "D563 template",
                 "description": "select one", "imports": [], "body": "select 1 as x"}
                """.trimIndent(),
                ADMIN_SESSION,
            )
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        releaseTemplate(1)
        writeTemplateDraft(2, "select 1 as x, 2 as y")
        releaseTemplate(2)

        writeTemplateDraft(3, "select 1 as x, 2 as y, 3 as z")
        // Discard the CURRENT v2: the pointer falls to the highest eligible live version — the DRAFT v3.
        post(
            "/api/v1/templates/version/discard",
            """{"name": "$TEMPLATE_ID", "version": 2}""",
            ADMIN_SESSION,
        ).then().statusCode(200)
        withClue("the discard-of-current leaves the pointer on the draft") { templatePointerVersion() shouldBe 3 }
        assertDraftPointerExcluded(Family.TEMPLATES, TEMPLATE_ID)
        writeEvidence(Family.TEMPLATES, "discard-of-current")

        switchTemplate(1)
        assertReleasedAdmitted(Family.TEMPLATES, TEMPLATE_ID)
        // The manual switch onto the draft is the second way the state arises (§3.4).
        switchTemplate(3)
        assertDraftPointerExcluded(Family.TEMPLATES, TEMPLATE_ID)
        writeEvidence(Family.TEMPLATES, "manual-switch")
        switchTemplate(1)
        assertReleasedAdmitted(Family.TEMPLATES, TEMPLATE_ID)
    }

    @Test
    @Order(3)
    fun `pipelines - a draft pointer is not promotable, by discard-of-current and by manual switch`() {
        val created = post("/api/v1/pipelines", pipelineBody(SELECT_ID_ONLY), ADMIN_SESSION)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        pipelineId = created.jsonPath().getString("data.id")
        val v1Hash = created.jsonPath().getString("data.body_hash")
        val v1Released = post("/api/v1/pipelines/$pipelineId/release", "", ADMIN_SESSION, ifMatch = v1Hash)
        v1Released.then().statusCode(200)
        writePipelineDraft(SELECT_ID_AND_LABEL)
        val v2Released = post("/api/v1/pipelines/$pipelineId/release", "", ADMIN_SESSION, ifMatch = pipelineHash())
        v2Released.then().statusCode(200)

        writePipelineDraft(SELECT_ALL_THREE)
        post("/api/v1/pipelines/$pipelineId/versions/2/discard", "", ADMIN_SESSION).then().statusCode(200)
        withClue("the discard-of-current leaves the pointer on the draft") { pipelineCurrentVersion() shouldBe 3 }
        assertDraftPointerExcluded(Family.PIPELINES, PIPELINE_NAME)
        writeEvidence(Family.PIPELINES, "discard-of-current")

        switchCurrent(Family.PIPELINES, pipelineId, 1)
        assertReleasedAdmitted(Family.PIPELINES, PIPELINE_NAME)
        switchCurrent(Family.PIPELINES, pipelineId, 3)
        assertDraftPointerExcluded(Family.PIPELINES, PIPELINE_NAME)
        writeEvidence(Family.PIPELINES, "manual-switch")
        switchCurrent(Family.PIPELINES, pipelineId, 1)
        assertReleasedAdmitted(Family.PIPELINES, PIPELINE_NAME)
    }

    @Test
    @Order(4)
    fun `parameter sets - a draft pointer is not promotable, by discard-of-current and by manual switch`() {
        val created = post("/api/v1/parameter-sets", setDocument("D563 set"), ADMIN_SESSION)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        setId = created.jsonPath().getString("data.id")
        val v1Hash = created.jsonPath().getString("data.body_hash")
        val v1Released = post("/api/v1/parameter-sets/$setId/release", "", ADMIN_SESSION, ifMatch = v1Hash)
        v1Released.then().statusCode(200)
        writeSetDraft("D563 set v2")
        val v2Released = post("/api/v1/parameter-sets/$setId/release", "", ADMIN_SESSION, ifMatch = setHash())
        v2Released.then().statusCode(200)

        writeSetDraft("D563 set v3")
        post("/api/v1/parameter-sets/$setId/versions/2/discard", "", ADMIN_SESSION).then().statusCode(200)
        withClue("the discard-of-current leaves the pointer on the draft") { setVersion() shouldBe 3 }
        assertDraftPointerExcluded(Family.PARAMETER_SETS, SET_NAME)
        writeEvidence(Family.PARAMETER_SETS, "discard-of-current")

        switchCurrent(Family.PARAMETER_SETS, setId, 1)
        assertReleasedAdmitted(Family.PARAMETER_SETS, SET_NAME)
        switchCurrent(Family.PARAMETER_SETS, setId, 3)
        assertDraftPointerExcluded(Family.PARAMETER_SETS, SET_NAME)
        writeEvidence(Family.PARAMETER_SETS, "manual-switch")
        switchCurrent(Family.PARAMETER_SETS, setId, 1)
        assertReleasedAdmitted(Family.PARAMETER_SETS, SET_NAME)
    }

    @Test
    @Order(5)
    fun `visualizations - a draft pointer is not promotable, by discard-of-current and by manual switch`() {
        // v1 and v2 exist as RELEASED from the fixture (pre-L4 seeding, see the class KDoc).
        visualizationId = visualizationRowId()
        writeVisualizationDraft("Revenue v3")
        post("/api/v1/visualizations/$visualizationId/versions/2/discard", "", ADMIN_SESSION).then().statusCode(200)
        withClue("the discard-of-current leaves the pointer on the draft") { visualizationVersion() shouldBe 3 }
        assertDraftPointerExcluded(Family.VISUALIZATIONS, VISUALIZATION_NAME)
        writeEvidence(Family.VISUALIZATIONS, "discard-of-current")

        switchCurrent(Family.VISUALIZATIONS, visualizationId, 1)
        assertReleasedAdmitted(Family.VISUALIZATIONS, VISUALIZATION_NAME)
        switchCurrent(Family.VISUALIZATIONS, visualizationId, 3)
        assertDraftPointerExcluded(Family.VISUALIZATIONS, VISUALIZATION_NAME)
        writeEvidence(Family.VISUALIZATIONS, "manual-switch")
        switchCurrent(Family.VISUALIZATIONS, visualizationId, 1)
        assertReleasedAdmitted(Family.VISUALIZATIONS, VISUALIZATION_NAME)
    }

    @Test
    @Order(6)
    fun `dashboards - a draft pointer is not promotable, by discard-of-current and by manual switch`() {
        val created = post("/api/v1/dashboards", dashboardDocument("Revenue"), ADMIN_SESSION)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        dashboardId = created.jsonPath().getString("data.id")
        val v1Hash = created.jsonPath().getString("data.body_hash")
        val v1Released = post("/api/v1/dashboards/$dashboardId/release", "", ADMIN_SESSION, ifMatch = v1Hash)
        v1Released.then().statusCode(200)
        writeDashboardDraft("Revenue v2")
        val v2Released = post("/api/v1/dashboards/$dashboardId/release", "", ADMIN_SESSION, ifMatch = dashboardHash())
        v2Released.then().statusCode(200)

        writeDashboardDraft("Revenue v3")
        post("/api/v1/dashboards/$dashboardId/versions/2/discard", "", ADMIN_SESSION).then().statusCode(200)
        withClue("the discard-of-current leaves the pointer on the draft") { dashboardVersion() shouldBe 3 }
        assertDraftPointerExcluded(Family.DASHBOARDS, DASHBOARD_NAME)
        writeEvidence(Family.DASHBOARDS, "discard-of-current")

        switchCurrent(Family.DASHBOARDS, dashboardId, 1)
        assertReleasedAdmitted(Family.DASHBOARDS, DASHBOARD_NAME)
        switchCurrent(Family.DASHBOARDS, dashboardId, 3)
        assertDraftPointerExcluded(Family.DASHBOARDS, DASHBOARD_NAME)
        writeEvidence(Family.DASHBOARDS, "manual-switch")
        switchCurrent(Family.DASHBOARDS, dashboardId, 1)
        assertReleasedAdmitted(Family.DASHBOARDS, DASHBOARD_NAME)
    }

    @Test
    @Order(7)
    fun `non-vacuity - every family walked both chains through both pointer states`() {
        val expected =
            Family.entries.flatMap { family ->
                listOf("discard-of-current", "manual-switch").map { chain -> "$family/$chain/excluded" }
            } + Family.entries.map { family -> "$family/released-admitted" }
        walked.distinct().sorted() shouldBe expected.sorted()
    }

    // ------------------------------------------------------------------ the surfaces, per pointer state

    /**
     * The families the §10.2 listing can carry by name in THIS suite: a template promotes only
     * as a pipeline's closure (never a row of its own) and a visualization only as a dashboard
     * pin (`PromotableView.of`'s arms) — this suite's visualization is unpinned, so for both the
     * tree and the wire are the promotion observables, and a page assertion would be theatre.
     */
    private val pageListed = setOf(Family.PIPELINES, Family.PARAMETER_SETS, Family.DASHBOARDS)

    private fun assertDraftPointerExcluded(
        family: Family,
        name: String,
    ) {
        // (a) The §10.2 listing — the promotion page renders exactly PromotionService.plan —
        // carries no candidate naming the draft pointer, for the author and the promoter alike.
        val authorPage = get("/promotion", ADMIN_SESSION)
        withClue("the promotion page must render for the author at the $family draft pointer") {
            authorPage.statusCode shouldBe 200
        }
        if (family in pageListed) {
            withClue("(a) the §10.2 listing must carry no candidate naming the $family draft pointer") {
                authorPage.asString() shouldNotContain name
            }
            withClue("(a) non-vacuity: the page does render this deployment's target") {
                authorPage.asString() shouldContain "uat"
            }
        }
        withClue("(a) the promoter reads the same page (PROMOTION_READ)") {
            get("/promotion", PROMOTER_SESSION).statusCode shouldBe 200
        }

        // (b) The promoter's narrowed tree omits the object; the author's tree still lists it.
        withClue("(b) the promoter's narrowed tree must omit the $family draft pointer") {
            treeNames(family, PROMOTER_SESSION) shouldNotContain name
        }
        withClue("(b) non-vacuity: the author's tree still lists the object") {
            treeNames(family, ADMIN_SESSION) shouldContain name
        }

        // The wire inventory is OBSERVED: the set and artifact arms exclude a draft pointer,
        // the pipeline and template arms still ship its hash — the #563 follow-up, not this rule.
        val wire = wireInventory()
        when (family) {
            Family.PIPELINES,
            Family.TEMPLATES,
            -> {
                withClue("OBSERVED follow-up: the wire inventory still ships the draft pointer") {
                    wireEntryVersions(wire, family, name) shouldContain 3
                }
            }

            Family.PARAMETER_SETS,
            Family.VISUALIZATIONS,
            Family.DASHBOARDS,
            -> {
                withClue("the wire inventory's released-only arms must exclude the draft pointer") {
                    wireEntryVersions(wire, family, name) shouldBe emptyList()
                }
            }
        }
    }

    private fun assertReleasedAdmitted(
        family: Family,
        name: String,
    ) {
        // (d) The §10.2 listing carries the release again — for the families that promote on
        // their own; for the closure riders the tree and the wire below are the observables.
        if (family in pageListed) {
            val page = get("/promotion", ADMIN_SESSION)
            withClue("(d) the §10.2 listing must carry the $family release") { page.asString() shouldContain name }
        }
        // The promoter's visualization tree admits only dashboard-PINNED visualizations (L1c's
        // lens arm), so an unpinned one never appears there at ANY pointer state; the author's
        // tree is the read surface that can see it, next to the wire below.
        if (family == Family.VISUALIZATIONS) {
            withClue("(d) the author's tree must list the release again") {
                treeNames(family, ADMIN_SESSION) shouldContain name
            }
        } else {
            withClue("(d) the promoter's tree must list the release") {
                treeNames(family, PROMOTER_SESSION) shouldContain name
            }
        }
        withClue("(d) the wire inventory must carry the release at version 1") {
            wireEntryVersions(wireInventory(), family, name) shouldContain 1
        }
        walked += "$family/released-admitted"
    }

    // ------------------------------------------------------------------ reads

    /**
     * The full name set of the tree: the route serves one level per call (immediate folders and
     * leaves, paged), so walk folders breadth-first and follow `next_cursor` — a truncated page
     * must not silently turn an exclusion assertion vacuous.
     */
    private fun treeNames(
        family: Family,
        session: String,
    ): List<String> {
        val names = mutableListOf<String>()
        var frontier = listOf("")
        while (frontier.isNotEmpty()) {
            val folders =
                frontier.flatMap { parent ->
                    appendTreeLevel(family, parent, session, names)
                }
            frontier = folders
        }
        return names
    }

    /** One paged GET per call: appends this level's names and returns its child folders for the next frontier. */
    private fun appendTreeLevel(
        family: Family,
        parent: String,
        session: String,
        names: MutableList<String>,
    ): List<String> {
        val folders = mutableListOf<String>()
        var cursor: String? = null
        do {
            val params =
                buildList {
                    if (parent.isNotEmpty()) add("parent=$parent")
                    if (cursor != null) add("cursor=$cursor")
                }
            val page =
                get(
                    treeUri(family, params),
                    session,
                )
            val nodes: List<Map<String, Any?>> = page.jsonPath().get("data.nodes") ?: emptyList()
            nodes.forEach { node ->
                val path = node["path"] as String
                names.add(path)
                if (node["has_children"] == true) folders.add(path)
            }
            cursor = page.jsonPath().getString("data.next_cursor")
        } while (cursor != null)
        return folders
    }

    private fun treeUri(
        family: Family,
        params: List<String>,
    ): String {
        if (params.isEmpty()) return "/api/v1/${family.segment}/tree"
        return "/api/v1/${family.segment}/tree?${params.joinToString("&")}"
    }

    /** §18.1 answered as the PEER (auth.md §8.6): the route reads only the DP-Promotion-Key header. */
    private fun wireInventory(): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("DP-Promotion-Key", RECEIVER_SERVER_KEY)
            .get("/api/v1/promotion/inventory?workspace=$WORKSPACE")
            .also {
                withClue("wire inventory: ${it.asString().take(EXCERPT)}") { it.statusCode shouldBe 200 }
            }

    private fun wireEntryVersions(
        wire: Response,
        family: Family,
        name: String,
    ): List<Int> {
        val rows: List<Map<String, Any?>> = wire.jsonPath().get("data.${family.wireField}") ?: emptyList()
        return rows.filter { it["name"] == name }.map { (it["current_version"] as Number).toInt() }
    }

    private fun writeEvidence(
        family: Family,
        chain: String,
    ) {
        walked += "$family/$chain/excluded"
    }

    // ------------------------------------------------------------------ verbs

    private fun get(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).get(path)

    private fun post(
        path: String,
        body: String,
        session: String,
        ifMatch: String? = null,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .post(path)

    private fun put(
        path: String,
        body: String,
        ifMatch: String,
    ): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .header("If-Match", ifMatch)
            .body(body)
            .put(path)

    private fun switchCurrent(
        family: Family,
        id: String,
        version: Int,
    ) {
        val response = post("/api/v1/${family.segment}/$id/current", """{"version": $version}""", ADMIN_SESSION)
        withClue("switch $family to $version: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 200 }
    }

    private fun switchTemplate(version: Int) {
        val response = post("/api/v1/templates/current", """{"name": "$TEMPLATE_ID", "version": $version}""", ADMIN_SESSION)
        withClue("switch template to $version: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 200 }
    }

    private fun releaseTemplate(version: Int) {
        val response =
            post("/api/v1/templates/release", """{"name": "$TEMPLATE_ID"}""", ADMIN_SESSION, ifMatch = templateHash())
        withClue("release template v$version: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 200 }
    }

    private fun writeTemplateDraft(
        version: Int,
        body: String,
    ) {
        val written =
            put(
                "/api/v1/templates",
                """
                {"id": "$TEMPLATE_ID", "dialect": "H2", "display_name": "D563 template v$version",
                 "description": "select one", "imports": [], "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
                templateHash(),
            )
        withClue("template draft v$version: ${written.asString().take(EXCERPT)}") { written.statusCode shouldBe 200 }
        written.jsonPath().getString("data.status") shouldBe "DRAFT"
    }

    /** The working version when no draft rides on top, else the draft pointer's version (templates.md §7). */
    private fun templatePointerVersion(): Int {
        val body = get("/api/v1/templates?name=$TEMPLATE_ID", ADMIN_SESSION).jsonPath()
        return body.get<Int>("data.draft.version") ?: body.getInt("data.version")
    }

    private fun templateHash(): String = get("/api/v1/templates?name=$TEMPLATE_ID", ADMIN_SESSION).jsonPath().getString("data.body_hash")

    private fun pipelineBody(default: String): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to PIPELINE_NAME,
                "display_name" to "D563 pipeline",
                "description" to "",
                "parameters" to
                    mapOf(
                        "columns" to
                            mapOf(
                                "type" to "STRING",
                                "required" to false,
                                "default" to default,
                                "description" to "The projection this version selects.",
                            ),
                    ),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "n1",
                            "type" to "DQL",
                            "source" to H2_DATASOURCE,
                            "template" to mapOf("id" to TEMPLATE_ID, "version" to 1),
                        ),
                    ),
            ),
        )

    private fun writePipelineDraft(default: String) {
        val written = put("/api/v1/pipelines/$pipelineId", pipelineBody(default), pipelineHash())
        withClue("pipeline draft v3: ${written.asString().take(EXCERPT)}") { written.statusCode shouldBe 200 }
        written.jsonPath().getString("data.status") shouldBe "DRAFT"
    }

    private fun pipelineHash(): String = get("/api/v1/pipelines/$pipelineId", ADMIN_SESSION).jsonPath().getString("data.body_hash")

    private fun pipelineCurrentVersion(): Int {
        val current = get("/api/v1/pipelines/$pipelineId", ADMIN_SESSION).jsonPath()
        return current.getInt("data.current_version")
    }

    private fun setDocument(displayName: String): String =
        mapper.writeValueAsString(
            mapOf(
                "name" to SET_NAME,
                "display_name" to displayName,
                "description" to "",
                "parameters" to listOf(constants("mode", listOf("fast", "slow"))),
            ),
        )

    private fun writeSetDraft(displayName: String) {
        val written = put("/api/v1/parameter-sets/$setId", setDocument(displayName), setHash())
        withClue("set draft $displayName: ${written.asString().take(EXCERPT)}") { written.statusCode shouldBe 200 }
        written.jsonPath().getString("data.status") shouldBe "DRAFT"
    }

    private fun setHash(): String = get("/api/v1/parameter-sets/$setId", ADMIN_SESSION).jsonPath().getString("data.body_hash")

    private fun setVersion(): Int = get("/api/v1/parameter-sets/$setId", ADMIN_SESSION).jsonPath().getInt("data.current_version")

    private fun writeVisualizationDraft(displayName: String) {
        val document = mapper.readTree(VISUALIZATION.replace("__NAME__", VISUALIZATION_NAME)) as ObjectNode
        document.put("display_name", displayName)
        val written = put("/api/v1/visualizations/$visualizationId", document.toString(), SEEDED_VIZ_HASH_2)
        withClue("visualization draft v3: ${written.asString().take(EXCERPT)}") { written.statusCode shouldBe 200 }
        written.jsonPath().getString("data.status") shouldBe "DRAFT"
    }

    private fun visualizationRowId(): String =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM visualizations WHERE name = '$VISUALIZATION_NAME'").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    private fun visualizationVersion(): Int =
        get("/api/v1/visualizations/$visualizationId", ADMIN_SESSION).jsonPath().getInt("data.current_version")

    private fun dashboardDocument(displayName: String): String =
        DASHBOARD
            .replace("__NAME__", DASHBOARD_NAME)
            .replace("__DISPLAY__", displayName)
            .replace("__PIPELINE__", PIPELINE_NAME)
            .replace("__VISUALIZATION__", VISUALIZATION_NAME)

    private fun writeDashboardDraft(displayName: String) {
        val written = put("/api/v1/dashboards/$dashboardId", dashboardDocument(displayName), dashboardHash())
        withClue("dashboard draft $displayName: ${written.asString().take(EXCERPT)}") { written.statusCode shouldBe 200 }
    }

    private fun dashboardHash(): String = get("/api/v1/dashboards/$dashboardId", ADMIN_SESSION).jsonPath().getString("data.body_hash")

    private fun dashboardVersion(): Int = get("/api/v1/dashboards/$dashboardId", ADMIN_SESSION).jsonPath().getInt("data.current_version")

    // ------------------------------------------------------------------ fixture

    private fun seedAuthRows() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ID', '$WORKSPACE', 'D563 E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'd563-admin@e2e.test', 'D563 Admin', 'test', 'd563-admin-sub', TRUE, TRUE),
                ('$PROMOTER_ID', 'd563-promoter@e2e.test', 'D563 Promoter', 'test', 'd563-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WS_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WS_ID', '$PROMOTER_ID', 'promoter')
            """.trimIndent(),
        )
    }

    private fun registerH2Datasource() {
        val response =
            post(
                "/api/v1/datasources",
                """
                {"name": "$H2_DATASOURCE", "display_name": "D563 H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}
                """.trimIndent(),
                ADMIN_SESSION,
            )
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 201 }
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE draft_first_rows (id INT PRIMARY KEY, label VARCHAR(64) NOT NULL)")
                statement.execute("INSERT INTO draft_first_rows VALUES (1, 'row-1')")
            }
        }
    }

    /** The visualization's v1 and v2, both RELEASED — nothing can release a visualization before L4 (see the class KDoc). */
    private fun seedReleasedVisualization() {
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$VISUALIZATION_ID', '$WS_ID', '$VISUALIZATION_NAME', 'Revenue', '', 2, '$ADMIN_ID')",
        )
        // The STORED body is the document without its `name` (V42's `chk_*_body`); the PUT route takes the full document.
        val body =
            (mapper.readTree(VISUALIZATION.replace("__NAME__", VISUALIZATION_NAME)) as ObjectNode)
                .apply { remove("name") }
                .toString()
                .replace("'", "''")
        listOf(1 to SEEDED_VIZ_HASH_1, 2 to SEEDED_VIZ_HASH_2).forEach { (version, hash) ->
            sql(
                "INSERT INTO visualization_versions " +
                    "(visualization_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                    "VALUES ('$VISUALIZATION_ID', $version, '$body'::jsonb, 'RELEASED', '$hash', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
            )
        }
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun constants(
        name: String,
        values: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "label" to name.replaceFirstChar { it.uppercase() },
            "type" to "STRING",
            "kind" to "SELECT",
            "cardinality" to "SINGLE",
            "source" to
                mapOf(
                    "constants" to
                        values.mapIndexed { i, v -> mapOf("value" to v, "display_value" to v.uppercase(), "is_default" to (i == 0)) },
                ),
        )

    private val pg get() = SharedE2e.postgres

    private companion object {
        const val EXCERPT = 600
        const val WORKSPACE = "d563-ws"
        const val TEMPLATE_ID = "d563/tpl"
        const val PIPELINE_NAME = "d563/pipe"
        const val SET_NAME = "d563/set"
        const val VISUALIZATION_NAME = "d563/charts/revenue"
        const val DASHBOARD_NAME = "d563/boards/revenue"
        const val H2_DATASOURCE = "h2-d563"

        /** The receiver's pre-shared promotion key (§10.6) — the wire inventory's credential. */
        const val RECEIVER_SERVER_KEY = "d563-receiver-server-key"
        const val H2_JDBC_URL = "jdbc:h2:mem:d563db;DB_CLOSE_DELAY=-1"
        const val SELECT_ID_ONLY = "select id from draft_first_rows"
        const val SELECT_ID_AND_LABEL = "select id, label from draft_first_rows"
        const val SELECT_ALL_THREE = "select id, label, id + label as total from draft_first_rows"
        const val SEEDED_VIZ_HASH_1 = "d563-seeded-viz-1"
        const val SEEDED_VIZ_HASH_2 = "d563-seeded-viz-2"

        val WS_ID = UUID.randomUUID()
        val VISUALIZATION_ID = UUID.randomUUID().toString()
        val ADMIN_ID = UUID.randomUUID().toString()
        val PROMOTER_ID = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "d563-admin@e2e.test", WORKSPACE)
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "d563-promoter@e2e.test", WORKSPACE)

        private val VISUALIZATION =
            """
            {"name": "__NAME__", "display_name": "Revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        private val DASHBOARD =
            """
            {"name": "__NAME__", "display_name": "__DISPLAY__", "description": "",
             "sources": [{"name": "revenue_source", "pipeline": {"name": "__PIPELINE__", "version": 1}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                 "visualization": {"name": "__VISUALIZATION__", "version": 1},
                                 "inputs": {"revenue": {"source": "revenue_source"}}}],
             "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        /**
         * The stub higher environment: an EMPTY inventory, so every released row here is newer
         * than anything the target holds and the only narrowing under test is the rule itself.
         */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"$WORKSPACE","templates":[],"datasources":[],""" +
                                """"parameter_sets":[],"pipelines":[],"visualizations":[],"dashboards":[]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }
        }

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
            // The promoter lens reads the stub higher environment.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "d563-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
            // The receiver half: without a server key the promotion prefix refuses everything (§10.6).
            registry.add("datapipelines.deployment.promotion.server-key") { RECEIVER_SERVER_KEY }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
