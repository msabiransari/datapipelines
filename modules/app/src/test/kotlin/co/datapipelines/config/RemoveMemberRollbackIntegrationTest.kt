package co.datapipelines.config

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.OidcDiscoveryStub
import co.datapipelines.SharedPostgres
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.jvm.kotlin
import kotlin.reflect.full.primaryConstructor

/**
 * #205 — the proof behind `WorkspaceService.removeMember`'s KDoc claim: "The whole sequence is
 * ONE metadata transaction: a failure removes nothing and revokes nothing."
 *
 * The claim holds by construction — one `NamedParameterJdbcTemplate`, one
 * `metadataTransactionManager`, `@Transactional` on an `open fun` — but no test had ever thrown
 * BETWEEN the membership delete and the key revoke and asserted the row is back. This class does
 * exactly that, against the PROXIED `workspaceService` bean of the real application context on
 * the shared test Postgres, using the plant the issue's brief decided on (R1): a plpgsql
 * function `dp205_refuse()` that raises `planted by #205`, wired by two triggers, each narrowed
 * by a `WHEN` clause to the exact row transition `removeMember` performs, so nothing else
 * sharing the database is touched:
 *
 * - T1 `dp205_revoke_refuse` — `BEFORE UPDATE ON api_keys WHEN (NEW.is_revoked AND NOT
 *   OLD.is_revoked)`: throws BETWEEN the delete and the revoke (the issue's ask).
 * - T2 `dp205_deactivate_refuse` — `BEFORE UPDATE ON users WHEN (OLD.is_active AND NOT
 *   NEW.is_active)`: throws at the LAST row write, after the delete AND the revoke have both
 *   run — the case that distinguishes "rolled back the earlier writes" from "never reached
 *   them".
 *
 * The plant is created inside the test and dropped in `finally`; [afterAll] proves the drop
 * (`pg_trigger` count = 0). The error reaches the service as a [DataAccessException] (nothing in
 * the chain catches it — `AuditLogger` swallows only its OWN insert), the proxy marks the
 * transaction rollback-only, and the commit never happens. The assertions read the committed
 * state straight from SQL: READ COMMITTED on a fresh statement sees only committed rows, so a
 * row that "survived" here really was rolled back, never merely uncommitted.
 *
 * ## Why the auth beans are reached by name and reflection
 *
 * `app` depends on `web` only (module-structure §4.2), so `co.datapipelines.auth` types are NOT
 * on this module's compile classpath — `ApplicationSmokeTest` reaches off-classpath beans
 * through `Class.forName` for exactly this reason. The three auth beans are fetched by bean
 * name, their methods invoked reflectively (the proxy intercepts reflective calls the same as
 * direct ones, so the transaction advice applies), and every value this class asserts or cleans
 * up is read straight from SQL. The member's key is minted through the proxied `ApiKeyService`,
 * which provisions a SERVICE identity — a hand-inserted key whose `user_id` is a HUMAN row
 * would make `UserService.deactivateIdentity` throw its own `check` instead of the plant.
 * `IssuedApiKey.plaintext` is never read: the key is identified by SQL, so the credential
 * cannot leak into a log, an assertion message or evidence.
 *
 * No row cleanup is inherited from `TransactionRollbackIntegrationTest`; this class deletes every
 * row it created in [afterAll] and never truncates — other classes in this module boot the same
 * database. Names carry a per-run suffix so the class is order-free and can share its JVM.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoveMemberRollbackIntegrationTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private val workspaceService: Any by lazy { context.getBean(WORKSPACE_SERVICE_BEAN) }
    private val apiKeyService: Any by lazy { context.getBean(API_KEY_SERVICE_BEAN) }
    private val userRepository: Any by lazy { context.getBean(USER_REPOSITORY_BEAN) }

    // Every created row is tracked the moment it exists, so even a fixture that fails
    // half-built is deleted in the afterAll sweep. Never TRUNCATE in the shared context.
    private val userIds = mutableListOf<UUID>()
    private val workspaceIds = mutableListOf<UUID>()
    private val workspaceNames = mutableListOf<String>()
    private val keyIds = mutableListOf<String>()

    /** One removal scenario: a super-admin actor, an AUTHOR member, and the member's minted key. */
    private data class Fixture(
        val workspaceId: UUID,
        val workspaceName: String,
        val actor: Any,
        val memberId: UUID,
        val keyId: String,
        val identityId: UUID,
    )

    @Test
    fun `a failure AFTER the membership delete rolls the row back - the revoke is refused (T1)`() {
        val fx = fixture()
        plantRefuseFunction()
        jdbc.jdbcTemplate.execute(
            "CREATE TRIGGER dp205_revoke_refuse BEFORE UPDATE ON api_keys FOR EACH ROW " +
                "WHEN (NEW.is_revoked AND NOT OLD.is_revoked) EXECUTE FUNCTION dp205_refuse()",
        )
        try {
            val refusal =
                shouldThrow<DataAccessException> { invoke(workspaceService, "removeMember", fx.actor, fx.workspaceName, fx.memberId) }
            assertPlanted(refusal)
            assertNothingRemoved(fx)
            assertProxied()
        } finally {
            dropPlantedRefusal()
        }
    }

    @Test
    fun `a failure at the LAST write rolls the delete AND the revoke back - the deactivation is refused (T2)`() {
        val fx = fixture()
        plantRefuseFunction()
        jdbc.jdbcTemplate.execute(
            "CREATE TRIGGER dp205_deactivate_refuse BEFORE UPDATE ON users FOR EACH ROW " +
                "WHEN (OLD.is_active AND NOT NEW.is_active) EXECUTE FUNCTION dp205_refuse()",
        )
        try {
            val refusal =
                shouldThrow<DataAccessException> { invoke(workspaceService, "removeMember", fx.actor, fx.workspaceName, fx.memberId) }
            assertPlanted(refusal)
            assertNothingRemoved(fx)
            assertProxied()
        } finally {
            dropPlantedRefusal()
        }
    }

    /**
     * The control `TransactionRollbackIntegrationTest` taught: without a plant the same call
     * commits — the membership goes, the key is revoked, the key identity is deactivated and
     * exactly one `workspace.member_removed` audit row commits with the transaction. Without
     * it, "nothing removed" would also be satisfied by a fixture that never wrote.
     */
    @Test
    fun `positive control - with no plant the removal commits row, key, identity and one audit row`() {
        val fx = fixture()

        assertProxied()
        invoke(workspaceService, "removeMember", fx.actor, fx.workspaceName, fx.memberId)

        assertSoftly {
            withClue("(control) the membership row is gone") { memberCount(fx) shouldBe 0 }
            withClue("(control) the created key is revoked") { keyRevoked(fx) shouldBe true }
            withClue("(control) the key identity is deactivated") { identityActive(fx) shouldBe false }
            withClue("(control) exactly one removal audit row commits") { memberRemovedAuditCount(fx) shouldBe 1 }
        }
        assertProxied()
    }

    @AfterAll
    fun afterAll() {
        dropPlantedRefusal()
        workspaceNames.forEach { name ->
            jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE details_json ->> 'workspace' = ?", name)
        }
        userIds.forEach { id -> jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE user_id = ?", id) }
        keyIds.forEach { keyId -> jdbc.jdbcTemplate.update("DELETE FROM api_keys WHERE id = ?", keyId) }
        workspaceIds.forEach { wsId ->
            jdbc.jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id = ?", wsId)
            jdbc.jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", wsId)
        }
        userIds.forEach { id -> jdbc.jdbcTemplate.update("DELETE FROM users WHERE id = ?", id) }
        withClue("every #205 trigger must be dropped - the plant may never outlive the class") {
            triggerCount() shouldBe 0
        }
    }

    // ------------------------------------------------------------------ the fixture

    private fun fixture(): Fixture {
        val sfx = UUID.randomUUID().toString().substring(0, SUFFIX_CHARS)
        val actorEmail = "root-205-$sfx@example.com"
        val memberEmail = "alice-205-$sfx@example.com"
        insertUser(actorEmail, "Root 205", "sub-205-root-$sfx", isAdmin = true)
        insertUser(memberEmail, "Alice 205", "sub-205-alice-$sfx", isAdmin = false)
        val actorId = userIdByEmail(actorEmail).also { userIds.add(it) }
        val memberId = userIdByEmail(memberEmail).also { userIds.add(it) }
        val actor = principalFor(actorId, actorEmail, "Root 205", superAdmin = true)
        val member = principalFor(memberId, memberEmail, "Alice 205", superAdmin = false)
        val wsName = "acme-205-$sfx"
        invoke(workspaceService, "create", actor, wsName, "Acme 205")
        val wsId =
            workspaceIdByName(wsName).also {
                workspaceIds.add(it)
                workspaceNames.add(wsName)
            }
        invoke(workspaceService, "addMember", actor, wsName, memberEmail, enumConstant(WORKSPACE_ROLE_CLASS, "AUTHOR"))
        // The member (an AUTHOR) mints their own key: issuance provisions a SERVICE identity
        // (provisionIdentity), so step (3) of the sequence — deactivateIdentity's `check` —
        // meets a service row and not the human-row IllegalStateException.
        invoke(
            apiKeyService,
            "issue",
            member,
            "mcp/205-$sfx",
            wsId,
            null,
            enumConstant(API_KEY_KIND_CLASS, "MCP"),
            enumConstant(KEY_ROLE_CLASS, "AUTHOR"),
        )
        val (keyId, identityId) = liveKeyOf(wsId)
        keyIds.add(keyId)
        userIds.add(identityId)
        return Fixture(wsId, wsName, actor, memberId, keyId, identityId)
    }

    private fun insertUser(
        email: String,
        displayName: String,
        providerSubject: String,
        isAdmin: Boolean,
    ) {
        invoke(
            userRepository,
            "insert",
            email,
            displayName,
            null,
            "google",
            providerSubject,
            isAdmin,
            enumConstant(USER_KIND_CLASS, "HUMAN"),
        )
    }

    // ------------------------------------------------------------------ the assertions

    /**
     * (f) — the claim is about the PROXIED bean; a test on an unproxied instance proves nothing.
     * Asserted AFTER the state on purpose: removing `removeMember`'s annotation (plant 1)
     * un-proxies the whole bean — it is `WorkspaceService`'s ONLY `@Transactional` method — and
     * a precondition-first check would fail all three tests on (f) before the call, masking the
     * (a) red the scratch run exists to show. In the green run the assertion still proves the
     * bean that rolled the writes back is the transaction-proxied one.
     */
    private fun assertProxied() {
        withClue("the service under test must be the transaction-proxied bean") {
            AopUtils.isAopProxy(workspaceService) shouldBe true
        }
    }

    /** (e) — the oracle is the plant: the refusal must carry the trigger's own message. */
    private fun assertPlanted(refusal: DataAccessException) {
        withClue("the exception must be the planted trigger, not an incidental failure") {
            refusal.message shouldContain "planted by #205"
        }
    }

    /** (a)–(d) — the whole write sequence rolled back: soft-asserted so a red run reports every violated state. */
    private fun assertNothingRemoved(fx: Fixture) {
        assertSoftly {
            withClue("(a) the membership row survives") { memberCount(fx) shouldBe 1 }
            withClue("(b) the created key stays live") { keyRevoked(fx) shouldBe false }
            withClue("(c) the key identity stays active") { identityActive(fx) shouldBe true }
            withClue("(d) the removal's audit row rolled back with the rest") { memberRemovedAuditCount(fx) shouldBe 0 }
        }
    }

    private fun memberCount(fx: Fixture): Int =
        count(
            "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = :ws AND user_id = :uid",
            mapOf("ws" to fx.workspaceId, "uid" to fx.memberId),
        )

    private fun keyRevoked(fx: Fixture): Boolean =
        requireNotNull(
            jdbc.queryForObject("SELECT is_revoked FROM api_keys WHERE id = :id", mapOf("id" to fx.keyId), Boolean::class.java),
        ) {
            "the fixture key must still exist when the rollback is asserted"
        }

    private fun identityActive(fx: Fixture): Boolean =
        requireNotNull(
            jdbc.queryForObject("SELECT is_active FROM users WHERE id = :id", mapOf("id" to fx.identityId), Boolean::class.java),
        ) {
            "the fixture identity must still exist when the rollback is asserted"
        }

    private fun memberRemovedAuditCount(fx: Fixture): Int =
        count(
            "SELECT COUNT(*) FROM audit_log WHERE event = :event AND details_json ->> 'workspace' = :wsName",
            mapOf("event" to REMOVAL_EVENT, "wsName" to fx.workspaceName),
        )

    private fun triggerCount(): Int = count("SELECT COUNT(*) FROM pg_trigger WHERE tgname LIKE 'dp205%'", emptyMap())

    private fun count(
        sql: String,
        params: Map<String, Any?>,
    ): Int = jdbc.queryForObject(sql, params, Int::class.java) ?: 0

    private fun userIdByEmail(email: String): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM users WHERE email = :email", mapOf("email" to email), UUID::class.java)) {
            "the fixture user $email must exist"
        }

    private fun workspaceIdByName(name: String): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM workspaces WHERE name = :name", mapOf("name" to name), UUID::class.java)) {
            "the fixture workspace $name must exist"
        }

    /** The fixture workspace's one live key and the SERVICE identity that acts as it. */
    private fun liveKeyOf(workspaceId: UUID): Pair<String, UUID> =
        jdbc
            .query(
                "SELECT id, user_id FROM api_keys WHERE workspace_id = :ws AND is_revoked = FALSE",
                mapOf("ws" to workspaceId),
            ) { rs, _ -> rs.getString("id") to rs.getObject("user_id", UUID::class.java) }
            .single()

    // ------------------------------------------------------------------ the plant

    /** The one RAISE both triggers share: the message is the oracle, `TG_TABLE_NAME` the diagnosis. */
    private fun plantRefuseFunction() {
        jdbc.jdbcTemplate.execute(REFUSE_FUNCTION_SQL)
    }

    /**
     * CASCADE on the function: a trigger left pointing at an unexpected table (any future
     * scratch plant) still binds the function, and a plain DROP would fail the finally and
     * mask the real refusal. The name is lane-namespaced, so CASCADE can only ever drop
     * THIS lane's own triggers.
     */
    private fun dropPlantedRefusal() {
        jdbc.jdbcTemplate.execute("DROP TRIGGER IF EXISTS dp205_revoke_refuse ON api_keys")
        jdbc.jdbcTemplate.execute("DROP TRIGGER IF EXISTS dp205_deactivate_refuse ON users")
        jdbc.jdbcTemplate.execute("DROP FUNCTION IF EXISTS dp205_refuse() CASCADE")
    }

    // ------------------------------------------------------------------ off-classpath access
    // app depends on web only (module-structure §4.2), so the auth module's types are not on
    // this module's compile classpath; ApplicationSmokeTest establishes the Class.forName
    // precedent. The proxy intercepts reflective calls like direct ones, so @Transactional
    // advice applies to `removeMember` invoked below.

    /** Invokes [method] on [bean] reflectively, rethrowing the REAL cause (not the ITE wrapper). */
    private fun invoke(
        bean: Any,
        method: String,
        vararg args: Any?,
    ): Any? =
        runCatching {
            bean.javaClass.methods
                .filter { it.name == method && it.parameterCount == args.size }
                .single()
                .invoke(bean, *args)
        }.getOrElse { throw it.cause ?: it }

    /** An `AuthenticatedPrincipal` by parameter NAME: the defaulted tail is stable, its order is not the point. */
    private fun principalFor(
        userId: UUID,
        email: String,
        displayName: String,
        superAdmin: Boolean,
    ): Any {
        val constructor = Class.forName(PRINCIPAL_CLASS).kotlin.primaryConstructor
            ?: error("AuthenticatedPrincipal must have a primary constructor")
        val byName = constructor.parameters.associateBy { it.name }
        return constructor.callBy(
            buildMap {
                put(byName.getValue("userId"), userId)
                put(byName.getValue("email"), email)
                put(byName.getValue("displayName"), displayName)
                put(byName.getValue("authMethod"), enumConstant(AUTH_METHOD_CLASS, "OIDC"))
                put(byName.getValue("superAdmin"), superAdmin)
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumConstant(
        className: String,
        constant: String,
    ): Any = java.lang.Enum.valueOf(Class.forName(className) as Class<out Enum<*>>, constant)

    companion object {
        private const val SUFFIX_CHARS = 8

        private const val SECRET_BYTES = 32

        private const val REMOVAL_EVENT = "workspace.member_removed"

        private const val WORKSPACE_SERVICE_BEAN = "workspaceService"
        private const val API_KEY_SERVICE_BEAN = "apiKeyService"
        private const val USER_REPOSITORY_BEAN = "userRepository"

        private const val PRINCIPAL_CLASS = "co.datapipelines.auth.AuthenticatedPrincipal"
        private const val AUTH_METHOD_CLASS = "co.datapipelines.auth.AuthMethod"
        private const val WORKSPACE_ROLE_CLASS = "co.datapipelines.auth.WorkspaceRole"
        private const val USER_KIND_CLASS = "co.datapipelines.auth.UserKind"
        private const val API_KEY_KIND_CLASS = "co.datapipelines.auth.ApiKeyKind"
        private const val KEY_ROLE_CLASS = "co.datapipelines.auth.KeyRole"

        /** The one RAISE both triggers share: the message is the oracle, `TG_TABLE_NAME` the diagnosis. */
        private const val REFUSE_FUNCTION_SQL =
            "CREATE OR REPLACE FUNCTION dp205_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
                "BEGIN RAISE EXCEPTION 'planted by #205: %', TG_TABLE_NAME; END; \$\$"

        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedRedis.port }
            registry.add("spring.data.redis.password") { SharedRedis.PASSWORD }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }
            registry.add("datapipelines.redis.password") { SharedRedis.PASSWORD }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
