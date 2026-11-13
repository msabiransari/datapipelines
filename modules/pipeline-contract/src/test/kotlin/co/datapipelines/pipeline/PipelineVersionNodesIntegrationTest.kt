package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * [PipelineRepository.findVersionNodesByName] (#887 §A) against the shipped schema: the any-status bulk read the
 * dependency graph's pipeline provider resolves a batch from. The oracle is the three per-key reads it replaces —
 * [PipelineRepository.findByNameAnyStatus], [PipelineRepository.findVersionDetail] and, unless the version is DISCARDED,
 * [PipelineRepository.findVersionBody] — run one by one for every key: the bulk read answers exactly their rows, in ONE
 * statement, and sends no statement for no key. Statements are counted on the repository's own connections
 * (`prepareStatement` calls through a counting data source).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineVersionNodesIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var owner: UUID

    private val prepared = AtomicInteger()
    private val serializer = PipelineSerializer()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(counting(SharedPostgres.pooledDataSource()))
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', 'default', 'Default')")
        jdbc.jdbcTemplate.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$OTHER_ID', 'other', 'Other')")
        owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject)" +
                        " VALUES ('owner@example.com', 'Owner', 'google', 'sub-1') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
    }

    @Test
    fun `a mixed batch answers exactly the rows the three per-key reads answer - in ONE statement`() {
        // test/alpha: RELEASED v1 (the pointer) and DRAFT v2 — two versions of one pipeline in one call.
        val alpha = create("test/alpha", CreateLifecycle.RELEASED)
        draft(alpha, "v2")
        // test/gone: its only version DISCARDED (the entity is no longer live; the any-status read still finds it).
        val gone = create("test/gone", CreateLifecycle.RELEASED)
        checkNotNull(repository.discardVersion(WORKSPACE_ID, gone.id, gone.name, 1, owner, draftEligible = true))
        // test/shared exists in BOTH workspaces with the same name and number; test/theirs only in the other one.
        create("test/shared", CreateLifecycle.RELEASED, nodeId = "mine")
        create("test/shared", CreateLifecycle.RELEASED, workspaceId = OTHER_ID, nodeId = "theirs")
        create("test/theirs", CreateLifecycle.RELEASED, workspaceId = OTHER_ID)
        val keys =
            listOf(
                "test/alpha" to 1,
                "test/alpha" to 2,
                "test/alpha" to 3,
                "test/gone" to 1,
                "test/shared" to 1,
                "test/theirs" to 1,
                "test/nobody" to 1,
            )
        val expected = keys.mapNotNull(::oneByOne)

        prepared.set(0)
        val rows = repository.findVersionNodesByName(WORKSPACE_ID, keys)

        withClue("one statement for the whole batch") { prepared.get() shouldBe 1 }
        rows shouldContainExactlyInAnyOrder expected
        withClue("non-vacuity: the oracle answered a DRAFT, a RELEASED and a DISCARDED row, and refused four keys") {
            expected.map { it.status }.toSet() shouldBe PipelineVersionStatus.entries.toSet()
            expected shouldHaveSize 4
        }
        rows.single { it.name == "test/alpha" && it.version == 2 }.let { matched ->
            withClue("the matched version is the key's, the pointer is the entity's") {
                matched.status shouldBe PipelineVersionStatus.DRAFT
                matched.currentVersion shouldBe 1
            }
        }
        rows.single { it.name == "test/gone" }.bodyJson.shouldBeNull()
        rows.single { it.name == "test/shared" }.bodyJson.shouldNotBeNull() shouldBe body("test/shared", 1)
    }

    @Test
    fun `another workspace's pipeline answers only to that workspace`() {
        create("test/theirs", CreateLifecycle.RELEASED, workspaceId = OTHER_ID)

        repository.findVersionNodesByName(WORKSPACE_ID, listOf("test/theirs" to 1)).shouldBeEmpty()
        repository.findVersionNodesByName(OTHER_ID, listOf("test/theirs" to 1)).map { it.name } shouldBe listOf("test/theirs")
    }

    @Test
    fun `no key sends no statement - and a full batch of 256 keys answers every one in the one call`() {
        prepared.set(0)
        repository.findVersionNodesByName(WORKSPACE_ID, emptyList()).shouldBeEmpty()
        prepared.get() shouldBe 0

        val names = (0 until FULL_BATCH).map { "test/p_$it" }
        names.forEach { create(it, CreateLifecycle.RELEASED) }
        prepared.set(0)
        val rows = repository.findVersionNodesByName(WORKSPACE_ID, names.map { it to 1 })

        prepared.get() shouldBe 1
        rows.map { it.name } shouldContainExactlyInAnyOrder names
    }

    /** The oracle: the three per-key reads the bulk read replaces, composed as the native release source composes them. */
    private fun oneByOne(key: Pair<String, Int>): PipelineVersionNode? {
        val (name, version) = key
        val record = repository.findByNameAnyStatus(WORKSPACE_ID, name) ?: return null
        val detail = repository.findVersionDetail(WORKSPACE_ID, record.id, version) ?: return null
        val body =
            if (detail.status == PipelineVersionStatus.DISCARDED) {
                null
            } else {
                repository.findVersionBody(WORKSPACE_ID, record.id, version) ?: return null
            }
        return PipelineVersionNode(record.id, record.name, version, record.currentVersion, detail.status, detail.bodyHash, body)
    }

    private fun create(
        name: String,
        lifecycle: CreateLifecycle,
        workspaceId: UUID = WORKSPACE_ID,
        nodeId: String = "n1",
    ): PipelineRecord {
        val body = Fixtures.pipeline(name = name, nodes = listOf(Fixtures.node(id = nodeId)))
        return repository.create(workspaceId, NewPipeline.from(body, owner), serializer.write(body), owner, lifecycle, WriteSurface.SESSION)
    }

    private fun draft(
        record: PipelineRecord,
        nodeId: String,
    ) {
        checkNotNull(
            repository.createDraft(
                WORKSPACE_ID,
                record.id,
                serializer.write(Fixtures.pipeline(name = record.name, nodes = listOf(Fixtures.node(id = nodeId)))),
                checkNotNull(repository.findVersionDetail(WORKSPACE_ID, record.id, 1)).bodyHash,
                owner,
                WriteSurface.SESSION,
            ),
        )
    }

    private fun body(
        name: String,
        version: Int,
    ): String = checkNotNull(repository.findVersionBody(WORKSPACE_ID, checkNotNull(repository.findByName(WORKSPACE_ID, name)).id, version))

    /** [target], counting every `prepareStatement` on the connections it hands out (each query the repository sends). */
    private fun counting(target: DataSource): DataSource =
        proxy<DataSource> { method, args ->
            val answer = invoke(target, method, args)
            if (method.name == "getConnection") {
                val connection = answer as Connection
                proxy<Connection> { inner, innerArgs ->
                    if (inner.name == "prepareStatement") prepared.incrementAndGet()
                    invoke(connection, inner, innerArgs)
                }
            } else {
                answer
            }
        }

    /** A proxy of [T] whose every call goes to [handle] (which closes over the target it forwards to). */
    private inline fun <reified T : Any> proxy(crossinline handle: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handle(method, args) } as T

    private fun invoke(
        target: Any,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private companion object {
        /** The V4-seeded `default` workspace, re-inserted after every truncate. */
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val OTHER_ID: UUID = UUID.fromString("0be40000-0000-0000-0000-000000000002")

        /** `GraphLimits.MAX_RESOLVE_BATCH_SIZE`, the largest batch a graph read sends (this module does not depend on it). */
        const val FULL_BATCH = 256
    }
}
