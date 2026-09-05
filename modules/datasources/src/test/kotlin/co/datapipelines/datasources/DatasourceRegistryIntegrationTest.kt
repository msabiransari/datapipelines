package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [DefaultDatasourceRegistry] end to end: metadata in a real Postgres, a reachable **H2**
 * user datasource (in-memory, so no second container), and the credential lifecycle §7.4
 * describes — encrypt on save, decrypt once at pool build, lease a real connection, and the
 * §6.2 in-use delete guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceRegistryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var owner: UUID

    private val encryptor = testEncryptor()

    /**
     * Binds the JDBC template to the module's shared, already-migrated container, over its
     * **pooled** source (round 062). This template carries only the metadata-schema fixtures
     * and reads; the pools this suite is actually about are the ones the registry builds for
     * the datasources UNDER test, which are untouched by this and stay real Hikari pools.
     */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        owner = insertUser()
    }

    /** One any-version reference (061/T79) — the delete guard's unit is the NODE, with its version. */
    private fun reference(
        pipeline: String,
        version: Int = 1,
        nodeId: String = "n1",
    ) = DatasourceReference(pipelineName = pipeline, pipelineVersion = version, versionStatus = "RELEASED", nodeId = nodeId)

    private fun registry(
        references: DatasourceReferences = DatasourceReferences.NONE,
        auditSink: DatasourceAuditSink = DatasourceAuditSink.NONE,
        invalidation: PoolInvalidationPublisher = PoolInvalidationPublisher.NONE,
    ): DefaultDatasourceRegistry =
        DefaultDatasourceRegistry(
            DatasourceRepository(jdbc),
            encryptor,
            references = references,
            auditSink = auditSink,
            invalidation = invalidation,
        )

    @Test
    fun `save encrypts the password and get never returns it`() {
        val registry = registry()
        val stored = registry.save(Fixtures.h2(name = "warehouse", password = "topsecret"), owner)

        // Neither the returned object nor a subsequent get carries the plaintext.
        stored.password.shouldBeNull()
        registry
            .get("warehouse")
            .shouldNotBeNull()
            .password
            .shouldBeNull()
        // The row on disk holds ciphertext, not the plaintext.
        val onDisk =
            jdbc.queryForObject(
                "SELECT encode(password_encrypted, 'hex') FROM datasources WHERE name = 'warehouse'",
                emptyMap<String, Any>(),
                String::class.java,
            )
        checkNotNull(onDisk).contains("topsecret".toByteArray().joinToString("") { "%02x".format(it) }) shouldBe false
    }

    @Test
    fun `poolFor builds a real pool - decrypting once - and leases a working connection`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "live", password = "pw"), owner)

        val datasource = registry.get("live").shouldNotBeNull()
        registry.poolFor(datasource).leaseConnection().use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    // ------------------ readonly flag (workspaces design §6; writable via the D8-gated save since the surfaces slice)

    @Test
    fun `is_readonly is persisted by save in both directions - the surfaces slice's writable flag`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "flagged", password = "pw", isReadonly = true), owner)

        registry.get("flagged").shouldNotBeNull().isReadonly shouldBe true

        // A save carrying the flag persists it (the flag write crosses the same save path
        // that evicts the pool); the WEB layer's absent-flag-keeps-stored rule is enforced
        // by the controller reading the stored row first — this module persists what it is
        // handed, by design.
        registry.save(Fixtures.h2(name = "flagged", password = "pw2", isReadonly = false), owner)
        jdbc.queryForObject(
            "SELECT is_readonly FROM datasources WHERE name = 'flagged'",
            emptyMap<String, Any>(),
            Boolean::class.java,
        ) shouldBe false
        registry.save(Fixtures.h2(name = "flagged", password = "pw3", isReadonly = true), owner)
        jdbc.queryForObject(
            "SELECT is_readonly FROM datasources WHERE name = 'flagged'",
            emptyMap<String, Any>(),
            Boolean::class.java,
        ) shouldBe true
    }

    @Test
    fun `getLive sees a row-level readonly flip immediately - past the metadata cache (D10)`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "flippy", password = "pw"), owner)
        // Warm the cache with the writable entry — this is exactly what save-time validation
        // read when the pipeline was saved.
        registry.get("flippy").shouldNotBeNull().isReadonly shouldBe false

        // A manual SQL flip never crosses the save boundary, so no invalidation fires; within
        // the 60s TTL the cached get() still serves the pre-flip entry — but the executor's
        // LIVE read must see the flip NOW, or the D10 flip window ships the write.
        jdbc.update("UPDATE datasources SET is_readonly = TRUE WHERE name = 'flippy'", emptyMap<String, Any>())

        registry.getLive("flippy").shouldNotBeNull().isReadonly shouldBe true
    }

    @Test
    fun `isReadonlyLive is the flag-only live read - sees a row-level flip, and null for an out-of-band soft-delete (044 F2 F7)`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "flagged_live", password = "pw"), owner)
        registry.get("flagged_live") // warm the cache with the writable entry

        jdbc.update("UPDATE datasources SET is_readonly = TRUE WHERE name = 'flagged_live'", emptyMap<String, Any>())

        // The executor backstop's actual read: flag-only, live, three-valued.
        registry.isReadonlyLive("flagged_live") shouldBe true
        // The un-flip direction reads live too — F4's both-directions rule at the primitive level.
        jdbc.update("UPDATE datasources SET is_readonly = FALSE WHERE name = 'flagged_live'", emptyMap<String, Any>())
        registry.isReadonlyLive("flagged_live") shouldBe false

        // The D10 channel's other half: a row-level soft-delete makes the live read NULL while
        // the cached entry survives — the backstop's "absent ⇒ refuse" case is fed by exactly this.
        jdbc.update("UPDATE datasources SET is_deleted = TRUE WHERE name = 'flagged_live'", emptyMap<String, Any>())
        registry.isReadonlyLive("flagged_live") shouldBe null
        registry.get("flagged_live").shouldNotBeNull() // the cached view still serves it
    }

    @Test
    fun `getVisibleLive sees a row-level flip immediately while getVisible serves the cache (044 F4)`() {
        val registry = registry()
        val workspace = insertWorkspace("live_ws")
        registry.save(Fixtures.h2(name = "bound_live", password = "pw").copy(workspaceId = workspace), owner)
        // Warm the visibility cache with the writable entry — what a save validated against.
        registry.getVisible("bound_live", workspace).shouldNotBeNull().isReadonly shouldBe false

        jdbc.update("UPDATE datasources SET is_readonly = TRUE WHERE name = 'bound_live'", emptyMap<String, Any>())

        // Save-time validation reads the LIVE visible row, so the next save honors the flip in
        // BOTH directions — the cached getVisible keeps serving the stale flag until TTL, but
        // nothing reads it for validation anymore.
        registry.getVisible("bound_live", workspace).shouldNotBeNull().isReadonly shouldBe false
        registry.getVisibleLive("bound_live", workspace).shouldNotBeNull().isReadonly shouldBe true

        // Same visibility predicate as the cached twin: another workspace's bound row is null.
        registry.getVisibleLive("bound_live", UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `a readonly row builds a read-only pool - real HikariConfig and pool build (layer 2b)`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "ro_pool", password = "pw"), owner)
        jdbc.update("UPDATE datasources SET is_readonly = TRUE WHERE name = 'ro_pool'", emptyMap<String, Any>())

        // The pool factory reloads exactly this row (past the cache) and re-attaches the
        // decrypted credential (§7.4 — getLive never carries it); the adapter is where the
        // flag becomes Hikari's. Proven on a REAL HikariConfig AND a real HikariDataSource
        // built from it — an assertion on a mocked adapter would prove the mock.
        val live = registry.getLive("ro_pool").shouldNotBeNull()
        live.isReadonly shouldBe true
        val config = DialectAdapters.forDialect(live.dialect).buildHikariConfig(live.copy(password = "pw"))
        config.isReadOnly shouldBe true
        config.initializationFailTimeout = -1
        HikariDataSource(config).use { pool -> pool.isReadOnly shouldBe true }

        // Deliberately NOT asserting the leased CONNECTION's isReadOnly: verified against the
        // pinned HikariCP 6.3.0 + H2 2.3.232, the pool-level flag is not propagated to
        // `Connection.setReadOnly` on lease — which is exactly why §5.7 claims the pool flag
        // only, as defense in depth, and puts the real boundary on the SELECT-only DB user.
    }

    @Test
    fun `testConnection reports connected with a server version for a reachable datasource`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "probe", password = "pw"), owner)

        val result = registry.testConnection("probe").shouldNotBeNull()

        result.connected shouldBe true
        result.serverVersion.shouldNotBeNull()
        result.latencyMs.shouldNotBeNull()
    }

    /**
     * §8.1B (061/T84), gate 4: the probe RECORDS its outcome, so a datasource whose credential
     * has stopped working says so on the list screen with no execution having run. That is the
     * hole the 2026-09-02 incident fell through — the list showed `sample-trips` as fine
     * because listing does not connect.
     *
     * The §6.3 cache is invalidated by the write: a badge a minute out of date on the screen
     * an operator opened BECAUSE something is wrong is the wrong kind of stale.
     */
    @Test
    fun `testConnection records its outcome on the row and refreshes the cached read`() {
        val registry = registry()
        val repository = DatasourceRepository(jdbc)
        registry.save(Fixtures.h2(name = "recorded", password = "pw"), owner)
        // Warm the cache so the assertion below cannot pass on a cold read by accident.
        registry
            .get("recorded")
            .shouldNotBeNull()
            .lastTest
            .shouldBeNull()

        registry.testConnection("recorded").shouldNotBeNull().connected shouldBe true

        checkNotNull(checkNotNull(repository.findByName("recorded")).lastTest).ok shouldBe true
        registry
            .get("recorded")
            .shouldNotBeNull()
            .lastTest
            .shouldNotBeNull()
            .ok shouldBe true
    }

    /**
     * The failing probe is the one that has to reach the screen, and the row must carry
     * exactly what the probe said — the scrubbed message, unedited.
     *
     * Which message that is, is decided by `rootMessage`: the DEEPEST cause with text, because
     * HikariCP wraps a rejected login in its own "Connection is not available, request timed
     * out", which names no database and diagnoses nothing. An unreachable HOST has no deeper
     * sentence to find and keeps the pool's, honestly; the login case — where the driver's
     * `FATAL: password authentication failed` is two causes down — is proven in
     * [BootstrapCredentialResyncIntegrationTest], against a real rejected login.
     */
    @Test
    fun `a failed probe records the failure and the row carries the probe's own message`() {
        val registry = registry()
        registry.save(Fixtures.postgres(name = "unreachable", jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nope"), owner)

        val result = registry.testConnection("unreachable").shouldNotBeNull()
        result.connected shouldBe false

        val recorded = checkNotNull(checkNotNull(DatasourceRepository(jdbc).findByName("unreachable")).lastTest)
        recorded.ok shouldBe false
        recorded.message shouldBe result.error
    }

    @Test
    fun `testConnection on an unknown name is null, not a synthetic failed result`() {
        // §6.1 (v1.4): null = no such datasource, which the web layer maps to 404. The previous
        // behaviour returned connected = false with errorClass = "NotFound" — not an FQCN, and
        // indistinguishable from a real datasource that is merely unreachable.
        registry().testConnection("no_such_datasource").shouldBeNull()
    }

    @Test
    fun `testConnection on an unreachable host is data, not an exception`() {
        val registry = registry()
        registry.save(
            Fixtures.postgres(name = "dead", jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nope"),
            owner,
        )

        val result = registry.testConnection("dead").shouldNotBeNull()

        result.connected shouldBe false
        result.error.shouldNotBeNull()
        // §6.1: errorClass is an exception FQCN.
        checkNotNull(result.errorClass).contains('.') shouldBe true
    }

    @Test
    fun `a probe failure never leaks the password or the URL credential segment`() {
        // §6.1: TestResult.error is redaction-scrubbed. Drivers routinely put the whole
        // connection string, credentials included, into their exception text — and this string
        // goes straight into an API response.
        val registry = registry()
        registry.save(
            Fixtures
                .postgres(name = "leaky", jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nope?ApplicationName=dp")
                .copy(username = "admin", password = "hunter2-the-secret"),
            owner,
        )

        val result = registry.testConnection("leaky").shouldNotBeNull()

        result.connected shouldBe false
        val text = checkNotNull(result.error)
        text shouldNotContain "hunter2-the-secret"
        text shouldNotContain "admin"
    }

    @Test
    fun `a driverless dialect makes testConnection return data, not throw`() {
        // DS-SEC-6: HikariDataSource construction and PoolInitializationException are
        // RuntimeExceptions, so a probe that caught only SQLException would throw out of a method
        // whose contract (and §8.1's failure-as-data rule) says it never does. Oracle's driver is
        // absent without -Poracle, so its pool cannot even be constructed.
        val registry = registry()
        jdbc.update(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted, created_by)
            VALUES ('nodriver', 'No driver', 'ORACLE', 'jdbc:oracle:thin:@//h:1521/svc', 'app', :pw, :owner)
            """.trimIndent(),
            mapOf("pw" to encryptor.encrypt("pw", "nodriver"), "owner" to owner),
        )

        val result = registry.testConnection("nodriver").shouldNotBeNull()

        result.connected shouldBe false
        checkNotNull(result.errorClass).contains('.') shouldBe true
    }

    @Test
    fun `delete is blocked while a pipeline references the datasource`() {
        val registry = registry(references = { name -> if (name == "used") listOf(reference("pipeline_a")) else emptyList() })
        registry.save(Fixtures.h2(name = "used", password = "pw"), owner)

        val result = registry.delete("used")

        result.deleted shouldBe false
        result.errorCode shouldBe DatasourceErrorCodes.IN_USE
        result.referencingPipelines shouldBe listOf("pipeline_a")
        // Still live.
        registry.exists("used") shouldBe true
    }

    @Test
    fun `delete soft-deletes an unreferenced datasource`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "free", password = "pw"), owner)

        registry.delete("free").deleted shouldBe true
        registry.exists("free") shouldBe false
    }

    @Test
    fun `save on an existing name updates in place and keeps the password when omitted`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "cfg", password = "keepme"), owner)

        val updated = registry.save(Fixtures.h2(name = "cfg", password = null).copy(displayName = "Config v2"), owner)

        updated.displayName shouldBe "Config v2"
        // The datasource remains usable after an update that omitted the password. (Retention of
        // the exact stored ciphertext is proven at the DB level in DatasourceRepositoryIntegrationTest.)
        registry.testConnection("cfg").shouldNotBeNull().connected shouldBe true
    }

    @Test
    fun `validate validates the normalized form - it agrees with save on unnormalized input`() {
        // R5 F6: save() validates the NORMALIZED copy (blanks dropped per F2), so a [" "]
        // allowlist saves successfully — but validate() checked the RAW input and called the
        // same entry invalid. A dry-run endpoint built on validate() would diverge from what
        // save actually persists. Both paths now normalize first: agreement by construction.
        val registry = registry()
        val raw = Fixtures.h2(name = "blank_entry", password = "pw").copy(introspectionIncludeSchemas = listOf(" ", "apex"))

        registry.validate(raw).valid shouldBe true

        registry.save(raw, owner)
        checkNotNull(registry.get("blank_entry")).introspectionIncludeSchemas shouldBe listOf("apex")
    }

    @Test
    fun `a GET-PUT round-trip of a restored dirty-allowlist row succeeds - and stores the clean list`() {
        // R5 F2: a row whose allowlist landed by restore/manual edit used to read back with
        // blank entries (`[""]`), which the save-time validator REJECTS — so re-saving what
        // GET returned, unmodified, 400'd. The ONE normalization rule (trim -> lowercase ->
        // drop blanks -> dedupe) applies at both boundaries, so what GET projects is always
        // exactly what PUT accepts.
        jdbc.update(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted,
                                     introspection_include_schemas_json, created_by)
            VALUES ('roundtrip', 'Round Trip', 'H2', 'jdbc:h2:mem:roundtrip', 'sa', :pw,
                    CAST('[" ", "apex", "APEX"]' AS jsonb), :owner)
            """.trimIndent(),
            mapOf("pw" to encryptor.encrypt("p", "roundtrip"), "owner" to owner),
        )
        val registry = registry()

        val restored = registry.get("roundtrip").shouldNotBeNull()
        restored.introspectionIncludeSchemas shouldBe listOf("apex")

        val resaved = registry.save(restored.copy(password = "p"), owner)

        resaved.introspectionIncludeSchemas shouldBe listOf("apex")
        checkNotNull(registry.get("roundtrip")).introspectionIncludeSchemas shouldBe listOf("apex")
    }

    @Test
    fun `an out-of-band hikari readOnly is inert - round-trip saves, the pool keeps the entity's flag (050 R3)`() {
        // The second instance of the normalize-at-both-boundaries rule (R5 F2's, above): a row
        // written outside the API may carry a SERVER_MANAGED key in `properties.hikari`. Before
        // 050 it failed an unmodified GET→PUT round-trip with 400 — while STILL flipping the
        // real pool flag at build time. toDatasource strips the key on read, at the one
        // boundary GET, PUT-revalidation and pool build all cross.
        jdbc.update(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted,
                                     properties_json, created_by)
            VALUES ('oob_managed', 'Out of band', 'H2', 'jdbc:h2:mem:oob_managed', 'sa', :pw,
                    CAST('{"hikari": {"readOnly": true, "maximumPoolSize": 5}}' AS jsonb), :owner)
            """.trimIndent(),
            mapOf("pw" to encryptor.encrypt("p", "oob_managed"), "owner" to owner),
        )
        val registry = registry()

        // GET projects the row WITHOUT the server-managed key — and with the legitimate
        // hikari keys the operator actually set.
        val restored = registry.get("oob_managed").shouldNotBeNull()
        restored.properties.hikari shouldBe mapOf("maximumPoolSize" to 5)

        // The unmodified round-trip saves (400 before 050).
        val resaved = registry.save(restored.copy(password = "p"), owner)
        resaved.properties.hikari shouldBe mapOf("maximumPoolSize" to 5)

        // And the pool the row builds carries the ENTITY's flag (false), never the stored key's
        // (true) — proven on a real HikariConfig, the layer-2b test's discipline.
        val live = registry.getLive("oob_managed").shouldNotBeNull()
        val config = DialectAdapters.forDialect(live.dialect).buildHikariConfig(live.copy(password = "p"))
        config.isReadOnly shouldBe false
        config.maximumPoolSize shouldBe 5
    }

    @Test
    fun `an update really rebuilds the pool - the next lease reaches the new target`() {
        // §5.2. The previous coverage only asserted that save() ran; nothing proved the live pool
        // was replaced, so a registry that forgot to evict would have stayed green while every
        // execution kept querying the OLD database after a PUT repointed the datasource.
        val registry = registry()
        registry.save(Fixtures.h2(name = "movable", jdbcUrl = "jdbc:h2:mem:before_move", password = "pw"), owner)

        val before = registry.get("movable").shouldNotBeNull()
        registry.poolFor(before).leaseConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE marker_before (id INT)") }
        }

        registry.save(Fixtures.h2(name = "movable", jdbcUrl = "jdbc:h2:mem:after_move", password = "pw"), owner)

        val after = registry.get("movable").shouldNotBeNull()
        after.jdbcUrl shouldBe "jdbc:h2:mem:after_move"
        registry.poolFor(after).leaseConnection().use { connection ->
            // The new pool points at a different in-memory database, so the old marker is absent
            // — a stale pool would still see it.
            connection.metaData.getTables(null, null, "MARKER_BEFORE", null).use { rs -> rs.next() shouldBe false }
            connection.createStatement().use { it.execute("CREATE TABLE marker_after (id INT)") }
        }
    }

    @Test
    fun `the metadata cache serves get and dialectOf, and every write invalidates it`() {
        // §6.3. The load-bearing half is invalidation: a cache that never dropped an entry would
        // keep serving a deleted datasource, and pipeline validation would keep accepting it.
        val registry = registry()
        registry.save(Fixtures.h2(name = "cached", password = "pw"), owner)

        registry.get("cached").shouldNotBeNull().displayName shouldBe "Test H2"
        registry.dialectOf("cached") shouldBe Dialect.H2

        registry.save(Fixtures.h2(name = "cached", password = "pw").copy(displayName = "Renamed"), owner)
        registry.get("cached").shouldNotBeNull().displayName shouldBe "Renamed"

        registry.delete("cached").deleted shouldBe true
        registry.get("cached").shouldBeNull()
        registry.dialectOf("cached").shouldBeNull()
    }

    // ---------------------------------------------------------------------------------
    // §5.7 cross-instance pool invalidation (050/R1, ARCH-AUDIT M3) — the publish contract.
    // The two-instance propagation itself is proven by the E2E that boots two application
    // contexts against one Redis; these pin the registry's half of the contract.
    // ---------------------------------------------------------------------------------

    /** Records every name handed to the port, in order. */
    private class RecordingPublisher : PoolInvalidationPublisher {
        val published = mutableListOf<String>()

        override fun publish(datasourceName: String) {
            published += datasourceName
        }
    }

    @Test
    fun `an update publishes the name after the row changed - the channel's only payload`() {
        val publisher = RecordingPublisher()
        val registry = registry(invalidation = publisher)
        registry.save(Fixtures.h2(name = "repointed", jdbcUrl = "jdbc:h2:mem:repoint_a", password = "pw"), owner)

        registry.save(Fixtures.h2(name = "repointed", jdbcUrl = "jdbc:h2:mem:repoint_b", password = "pw"), owner)

        // Publish fires on the UPDATE — the save shape that can leave a peer's pool stale.
        // (Create publishes nothing: no pool for the name can exist anywhere until first use,
        // and a delete of the same name already fanned out.)
        publisher.published shouldBe listOf("repointed")
        // And only after the row itself is durable — the update is already visible.
        registry.getLive("repointed").shouldNotBeNull().jdbcUrl shouldBe "jdbc:h2:mem:repoint_b"
    }

    @Test
    fun `delete publishes - and a refused or no-op delete publishes nothing`() {
        val publisher = RecordingPublisher()
        val usedUp =
            registry(
                invalidation = publisher,
                references = { name -> if (name == "guarded") listOf(reference("p1")) else emptyList() },
            )
        usedUp.save(Fixtures.h2(name = "guarded", password = "pw"), owner)
        usedUp.delete("guarded")
        publisher.published.shouldBeEmpty()

        val registry = registry(invalidation = publisher)
        registry.delete("never_existed")
        publisher.published.shouldBeEmpty()

        registry.save(Fixtures.h2(name = "dropped", password = "pw"), owner)
        registry.delete("dropped").deleted shouldBe true
        publisher.published shouldBe listOf("dropped")
    }

    @Test
    fun `evictPool is the subscriber's target - drains a live pool, no-ops without one`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "pooled", jdbcUrl = "jdbc:h2:mem:evict_target", password = "pw"), owner)
        val ds = registry.get("pooled").shouldNotBeNull()
        // Build the pool, then drain it through the interface the Redis subscriber calls.
        registry.poolFor(ds)
        registry.evictPool("pooled") shouldBe true
        registry.evictPool("pooled") shouldBe false
    }

    @Test
    fun `a soft-deleted name cannot be re-created through the registry either`() {
        // §9 global uniqueness, through the full save path: `exists` is false after a soft delete,
        // so save() takes the create branch and the database — the only atomic authority — raises
        // duplicate_name from the primary-key violation.
        val registry = registry()
        registry.save(Fixtures.h2(name = "gone", password = "pw"), owner)
        registry.delete("gone").deleted shouldBe true

        val thrown = shouldThrow<DatapipelinesException> { registry.save(Fixtures.h2(name = "gone", password = "pw"), owner) }

        thrown.code shouldBe DatasourceErrorCodes.DUPLICATE_NAME
    }

    @Test
    fun `dialectOf resolves a registered datasource and is null otherwise`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "known", password = "pw"), owner)

        registry.dialectOf("known") shouldBe Dialect.H2
        registry.dialectOf("missing").shouldBeNull()
    }

    @Test
    fun `save rejects an invalid datasource before writing`() {
        val registry = registry()

        val thrown =
            shouldThrow<DatasourceValidationException> {
                registry.save(Fixtures.h2(name = "Bad Name!"), owner)
            }

        thrown.code shouldBe DatasourceErrorCodes.NAME_INVALID
        registry.exists("Bad Name!") shouldBe false
    }

    @Test
    fun `a pool build emits exactly one datasource_pool_build audit event`() {
        // §7.4: one event per credential DECRYPTION, never per lease. Two leases, one event —
        // that difference is the whole point of the amended model.
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "audited", password = "pw"), owner)
        val datasource = registry.get("audited").shouldNotBeNull()

        registry.poolFor(datasource).leaseConnection().use { }
        registry.poolFor(datasource).leaseConnection().use { }

        sink.countOf(DatasourceAuditEvents.POOL_BUILD) shouldBe 1
        val event = sink.events.single { it.event == DatasourceAuditEvents.POOL_BUILD }
        event.datasourceName shouldBe "audited"
        event.actor shouldBe DatasourceAuditEvent.SYSTEM_ACTOR
    }

    @Test
    fun `an update that evicts a live pool emits exactly one datasource_pool_rebuild, with the actor`() {
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "rebuilt", password = "pw"), owner)
        registry.poolFor(registry.get("rebuilt").shouldNotBeNull()).leaseConnection().use { }

        registry.save(Fixtures.h2(name = "rebuilt", password = "pw").copy(displayName = "v2"), owner)

        sink.countOf(DatasourceAuditEvents.POOL_REBUILD) shouldBe 1
        sink.events.single { it.event == DatasourceAuditEvents.POOL_REBUILD }.actor shouldBe owner.toString()
    }

    @Test
    fun `an update with no live pool decrypts nothing and so emits no rebuild event`() {
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "never_leased", password = "pw"), owner)

        registry.save(Fixtures.h2(name = "never_leased", password = "pw").copy(displayName = "v2"), owner)

        sink.eventNames() shouldBe emptyList()
    }

    @Test
    fun `testConnection emits exactly one datasource_connection_test audit event`() {
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "tested", password = "pw"), owner)

        registry.testConnection("tested").shouldNotBeNull()

        sink.countOf(DatasourceAuditEvents.CONNECTION_TEST) shouldBe 1
        sink.events.single().datasourceName shouldBe "tested"
    }

    @Test
    fun `an unknown name decrypts nothing, so no connection_test event is written`() {
        val sink = RecordingAuditSink()

        registry(auditSink = sink).testConnection("absent").shouldBeNull()

        sink.eventNames() shouldBe emptyList()
    }

    @Test
    fun `no audit event is written per lease`() {
        // The pre-v1.4 "audit every lease" model produced one row per query, per node, per
        // execution — unbounded volume recording nothing the execution record did not hold, and
        // implying a decrypt that does not happen.
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "leased", password = "pw"), owner)
        val datasource = registry.get("leased").shouldNotBeNull()
        val pool = registry.poolFor(datasource)

        repeat(5) { pool.leaseConnection().use { } }

        sink.eventNames() shouldBe listOf(DatasourceAuditEvents.POOL_BUILD)
    }

    @Test
    fun `a mixed-case allowlist saved through the registry lands normalized - never silently inert`() {
        // R4 F3: lowercase normalization used to live ONLY in the REST bind, so any
        // non-controller write path (a future MCP create tool, restore tooling) stored mixed
        // case — and matching lowercases the driver-reported schema but compares stored
        // entries verbatim, so such an allowlist silently exempted nothing. The registry's
        // save is the single boundary every write path crosses: the stored and returned
        // entries are normalized, and the exemption matches the driver-reported spelling.
        val registry = registry()
        registry.save(
            Fixtures.h2(name = "mixed", password = "pw", introspectionIncludeSchemas = listOf(" APEX_Reporting ", "Sales")),
            owner,
        )

        checkNotNull(registry.get("mixed")).introspectionIncludeSchemas shouldBe listOf("apex_reporting", "sales")
    }

    private fun insertUser(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES ('owner@example.com', 'Owner', 'google', 'sub-1')
                RETURNING id
                """.trimIndent(),
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private fun insertWorkspace(name: String): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO workspaces (name, display_name, is_personal, created_by)
                VALUES ('$name', '$name', FALSE, :owner)
                RETURNING id
                """.trimIndent(),
                mapOf("owner" to owner),
                UUID::class.java,
            ),
        )
}
