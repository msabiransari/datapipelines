package co.datapipelines.datasources

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * #874 G3 (V59, dependency-graph record §5.1, §5.3, §15.1, acceptance A09) — the datasource's local identity on the
 * real schema: the incarnation UUID every row carries and which writer advances the generation. A separate class beside
 * `DatasourceRepositoryIntegrationTest` (already 470 lines) so the identity rules read as one unit; the same shared,
 * already-migrated container and the same cleaning rule.
 *
 * Ruling (b) of 2026-11-10 (brief §A.0): the product has no restore and no purge, so the A09 halves are proven here at
 * the repository level with SQL — a restore can only ever flip `is_deleted` back, and a recreation is a delete-and-
 * insert of the same name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceIdentityIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: DatasourceRepository
    private lateinit var owner: UUID

    private val encryptor = testEncryptor()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = DatasourceRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, workspaces, users CASCADE")
        owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject) " +
                        "VALUES ('owner@example.com', 'Owner', 'google', 'sub-1') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
    }

    @Test
    fun `every row carries a minted incarnation and generation 1 - a soft-deleted row inserted by SQL included`() {
        create("a")
        create("b")
        // A row that never crossed the repository, already soft-deleted: the DEFAULT mints its identity too.
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, is_deleted) " +
                "VALUES ('gone', 'Gone', 'H2', 'jdbc:h2:mem:gone', 'none', :owner, TRUE)",
            mapOf("owner" to owner),
        )

        val identities = listOf("a", "b", "gone").map { checkNotNull(repository.findIncarnation(it)) { it } }

        identities.map { it.generation } shouldBe listOf(1L, 1L, 1L)
        identities.map { it.isDeleted } shouldBe listOf(false, false, true)
        identities.map { it.incarnationId }.toSet().size shouldBe 3
        scalar("SELECT count(*) FROM datasources WHERE incarnation_id IS NULL") shouldBe 0L
        repository.findIncarnation("absent").shouldBeNull()
    }

    @Test
    fun `a definition update advances the generation by exactly one and keeps the incarnation`() {
        create("upd")
        val before = checkNotNull(repository.findIncarnation("upd"))

        repository.update(Fixtures.h2(name = "upd").copy(displayName = "Renamed"), null)
        val once = checkNotNull(repository.findIncarnation("upd"))
        repository.update(Fixtures.h2(name = "upd"), encryptor.encrypt("other", "upd"))
        val twice = checkNotNull(repository.findIncarnation("upd"))

        once.generation shouldBe before.generation + 1
        twice.generation shouldBe before.generation + 2
        twice.incarnationId shouldBe before.incarnationId
    }

    @Test
    fun `a credential change advances the generation by exactly one`() {
        create("cred")
        val before = checkNotNull(repository.findIncarnation("cred"))

        repository.updateCredential("cred", encryptor.encrypt("rotated", "cred")) shouldBe true

        val after = checkNotNull(repository.findIncarnation("cred"))
        after.generation shouldBe before.generation + 1
        after.incarnationId shouldBe before.incarnationId
    }

    @Test
    fun `a recorded connection test is an observation - the generation does not move`() {
        create("probe")
        val before = checkNotNull(repository.findIncarnation("probe"))

        repository.recordTestOutcome("probe", DatasourceTestOutcome(Instant.parse("2026-11-11T00:00:00Z"), ok = false)) shouldBe true

        val after = checkNotNull(repository.findIncarnation("probe"))
        after.generation shouldBe before.generation
        after.incarnationId shouldBe before.incarnationId
    }

    @Test
    fun `a soft delete advances the generation once and keeps the incarnation`() {
        create("del")
        val before = checkNotNull(repository.findIncarnation("del"))

        repository.softDelete("del") shouldBe true
        repository.softDelete("del") shouldBe false

        val after = checkNotNull(repository.findIncarnation("del"))
        after.isDeleted shouldBe true
        after.generation shouldBe before.generation + 1
        after.incarnationId shouldBe before.incarnationId
    }

    /** A09's restore half (ruling b): the only path a future restore could take keeps both identity facts. */
    @Test
    fun `flipping is_deleted back by SQL keeps the incarnation and the generation`() {
        create("back")
        repository.softDelete("back") shouldBe true
        val deleted = checkNotNull(repository.findIncarnation("back"))

        jdbc.update("UPDATE datasources SET is_deleted = FALSE WHERE name = :n", mapOf("n" to "back"))

        val restored = checkNotNull(repository.findIncarnation("back"))
        restored.isDeleted shouldBe false
        restored.incarnationId shouldBe deleted.incarnationId
        restored.generation shouldBe deleted.generation
    }

    /** A09's recreation half: the same name and body inserted again is a new incarnation, its generation from 1. */
    @Test
    fun `deleting the row and inserting the same name and body again mints a new incarnation at generation 1`() {
        create("again")
        repository.update(Fixtures.h2(name = "again"), null)
        val first = checkNotNull(repository.findIncarnation("again"))
        first.generation shouldBe 2L

        jdbc.update("DELETE FROM datasources WHERE name = :n", mapOf("n" to "again"))
        create("again")

        val second = checkNotNull(repository.findIncarnation("again"))
        second.incarnationId shouldNotBe first.incarnationId
        second.generation shouldBe 1L
    }

    /** Durability: a second, independent connection to the same database reads exactly the stored values. */
    @Test
    fun `a second connection reads the same identity`() {
        create("durable")
        repository.update(Fixtures.h2(name = "durable"), null)
        val seen = checkNotNull(repository.findIncarnation("durable"))

        val other = DatasourceRepository(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
        val again = checkNotNull(other.findIncarnation("durable"))

        again.incarnationId shouldBe seen.incarnationId
        again.generation shouldBe seen.generation
    }

    /** Stored-row authority, repository half: the batch reads return exactly what the row holds. */
    @Test
    fun `the graph-facts batch reads carry the stored incarnation and generation`() {
        create("auth")
        repository.updateCredential("auth", encryptor.encrypt("x", "auth"))
        val stored =
            jdbc.queryForMap("SELECT incarnation_id, generation FROM datasources WHERE name = :n", mapOf("n" to "auth"))
        val workspace = insertWorkspace("ws-auth")
        grant("auth", workspace)

        val live = repository.findLiveGraphFacts(listOf("auth")).single()
        val visible = repository.findVisibleGraphFacts(listOf("auth"), workspace).single()

        listOf(live, visible).forEach { facts ->
            facts.incarnationId shouldBe stored["incarnation_id"]
            facts.generation shouldBe stored["generation"]
            facts.name shouldBe "auth"
            facts.displayName shouldBe "H2"
            facts.isReadonly shouldBe false
        }
    }

    @Test
    fun `the workspace batch read returns only live granted names - the super-admin read only live requested names`() {
        val mine = insertWorkspace("ws-mine")
        val theirs = insertWorkspace("ws-theirs")
        listOf("granted", "other", "deleted", "unrequested").forEach { create(it) }
        grant("granted", mine)
        grant("deleted", mine)
        grant("unrequested", mine)
        grant("other", theirs)
        repository.softDelete("deleted")
        val requested = listOf("granted", "other", "deleted", "absent")

        repository.findVisibleGraphFacts(requested, mine).map { it.name } shouldBe listOf("granted")
        repository.findLiveGraphFacts(requested).map { it.name } shouldContainExactlyInAnyOrder listOf("granted", "other")
        repository.findVisibleGraphFacts(emptyList(), mine) shouldBe emptyList()
        repository.findLiveGraphFacts(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `neither identity type prints its identity facts`() {
        create("quiet")
        val facts = repository.findLiveGraphFacts(listOf("quiet")).single()
        val identity = checkNotNull(repository.findIncarnation("quiet"))

        listOf(facts.toString(), identity.toString()).forEach { text ->
            text shouldNotContain facts.incarnationId.toString()
            text shouldNotContain "generation"
            text shouldNotContain "incarnation"
        }
    }

    /**
     * The identity facts have ONE construction site in production code: the repository's graph-facts mapper (the
     * constructor is `internal` too). A call site is `DatasourceGraphFacts(` not preceded by a quote (the redacted
     * `toString` literal starts with the class name).
     */
    @Test
    fun `DatasourceGraphFacts is constructed in exactly one place under every src main`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val call = Regex("""(?<!")\bDatasourceGraphFacts\(""")
        val sites =
            root
                .resolve("modules")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
                .flatMap { file -> call.findAll(file.readText()).map { file.name } }
                .toList()

        sites shouldBe listOf("DatasourceRepository.kt")
    }

    private fun create(name: String) {
        repository.create(Fixtures.h2(name = name).copy(displayName = "H2"), encryptor.encrypt("p", name), owner)
    }

    private fun scalar(sql: String): Long = checkNotNull(jdbc.queryForObject(sql, emptyMap<String, Any>(), Long::class.java))

    private fun insertWorkspace(name: String): UUID =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO workspaces (name, display_name, created_by) VALUES (:name, :name, :createdBy) RETURNING id",
                mapOf("name" to name, "createdBy" to owner),
                UUID::class.java,
            ),
        )

    private fun grant(
        name: String,
        workspaceId: UUID,
    ) {
        jdbc.update(
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (:name, :workspace, :by)",
            mapOf("name" to name, "workspace" to workspaceId, "by" to owner),
        )
    }
}
