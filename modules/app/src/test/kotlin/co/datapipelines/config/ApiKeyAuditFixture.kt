package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.aop.support.AopUtils
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

/** Database fixture and scoped trigger lifecycle for [ApiKeyAuditRefusalIntegrationTest]. */
internal class ApiKeyAuditFixture(
    private val context: ApplicationContext,
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val workspaceService: Any by lazy { context.getBean(WORKSPACE_SERVICE_BEAN) }
    private val apiKeyService: Any by lazy { context.getBean(API_KEY_SERVICE_BEAN) }
    private val userRepository: Any by lazy { context.getBean(USER_REPOSITORY_BEAN) }

    // Every created row is tracked the moment it exists; afterAll sweeps them. Never TRUNCATE.
    private val userIds = mutableListOf<UUID>()
    private val workspaceIds = mutableListOf<UUID>()
    private val workspaceNames = mutableListOf<String>()
    private val keyNames = mutableListOf<String>()

    /** A workspace, an AUTHOR member who mints keys, and the name the next key will carry. */
    data class Fixture(
        val workspaceId: UUID,
        val workspaceName: String,
        val member: Any,
        val memberId: UUID,
        val keyName: String,
    )

    fun cleanup() {
        dropPlant()
        workspaceNames.forEach { name -> jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE details_json ->> 'workspace' = ?", name) }
        workspaceIds.forEach { wsId ->
            jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE key_id IN (SELECT id FROM api_keys WHERE workspace_id = ?)", wsId)
        }
        userIds.forEach { id -> jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE user_id = ?", id) }
        workspaceIds.forEach { wsId -> jdbc.jdbcTemplate.update("DELETE FROM api_keys WHERE workspace_id = ?", wsId) }
        keyNames.forEach { name ->
            jdbc.jdbcTemplate.update("DELETE FROM audit_log WHERE user_id IN (SELECT id FROM users WHERE display_name = ?)", name)
            jdbc.jdbcTemplate.update("DELETE FROM users WHERE display_name = ? AND provider_subject LIKE 'dpk_%'", name)
        }
        workspaceIds.forEach { wsId ->
            jdbc.jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id = ?", wsId)
            jdbc.jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", wsId)
        }
        userIds.forEach { id -> jdbc.jdbcTemplate.update("DELETE FROM users WHERE id = ?", id) }
        withClue("every #704 trigger must be dropped - the plant may never outlive the class") {
            count("SELECT COUNT(*) FROM pg_trigger WHERE tgname LIKE 'dp704%'", emptyMap()) shouldBe 0
        }
    }

    fun fixture(): Fixture {
        val sfx = UUID.randomUUID().toString().substring(0, SUFFIX_CHARS)
        val actorEmail = "root-704-$sfx@example.com"
        val memberEmail = "alice-704-$sfx@example.com"
        insertUser(actorEmail, "Root 704", "sub-704-root-$sfx", isAdmin = true)
        insertUser(memberEmail, "Alice 704", "sub-704-alice-$sfx", isAdmin = false)
        val actorId = userIdByEmail(actorEmail).also { userIds.add(it) }
        val memberId = userIdByEmail(memberEmail).also { userIds.add(it) }
        val actor = principalFor(actorId, actorEmail, "Root 704", superAdmin = true)
        val member = principalFor(memberId, memberEmail, "Alice 704", superAdmin = false)
        val wsName = "acme-704-$sfx"
        invoke(workspaceService, "create", actor, wsName, "Acme 704")
        val wsId =
            workspaceIdByName(wsName).also {
                workspaceIds.add(it)
                workspaceNames.add(wsName)
            }
        invoke(workspaceService, "addMember", actor, wsName, memberEmail, enumConstant(WORKSPACE_ROLE_CLASS, "AUTHOR"))
        return Fixture(wsId, wsName, member, memberId, "mcp/704-$sfx".also { keyNames.add(it) })
    }

    /** The member mints an MCP key named [Fixture.keyName] through the PROXIED service (a SERVICE identity is provisioned). */
    fun issue(fx: Fixture) {
        invoke(
            apiKeyService,
            "issue",
            fx.member,
            fx.keyName,
            fx.workspaceId,
            null,
            enumConstant(API_KEY_KIND_CLASS, "MCP"),
            enumConstant(KEY_ROLE_CLASS, "AUTHOR"),
        )
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

    fun assertProxied() {
        withClue("the service under test must be the transaction-proxied bean") { AopUtils.isAopProxy(apiKeyService) shouldBe true }
    }

    fun keyCount(fx: Fixture): Int =
        count(
            "SELECT COUNT(*) FROM api_keys WHERE workspace_id = :ws AND name = :name",
            mapOf("ws" to fx.workspaceId, "name" to fx.keyName),
        )

    fun identityCount(fx: Fixture): Int =
        count("SELECT COUNT(*) FROM users WHERE display_name = :name AND provider_subject LIKE 'dpk_%'", mapOf("name" to fx.keyName))

    fun createdAuditCount(fx: Fixture): Int =
        count(
            "SELECT COUNT(*) FROM audit_log WHERE event = 'auth.api_key.created' AND details_json ->> 'name' = :name",
            mapOf("name" to fx.keyName),
        )

    fun revokedAuditCount(keyId: String): Int =
        count("SELECT COUNT(*) FROM audit_log WHERE event = 'auth.api_key.revoked' AND key_id = :id", mapOf("id" to keyId))

    fun keyRevoked(keyId: String): Boolean =
        requireNotNull(jdbc.queryForObject("SELECT is_revoked FROM api_keys WHERE id = :id", mapOf("id" to keyId), Boolean::class.java)) {
            "the fixture key must still exist when the refusal is asserted"
        }

    fun identityActive(identityId: UUID): Boolean =
        requireNotNull(jdbc.queryForObject("SELECT is_active FROM users WHERE id = :id", mapOf("id" to identityId), Boolean::class.java)) {
            "the fixture identity must still exist when the refusal is asserted"
        }

    /** The fixture key's id and the SERVICE identity that acts as it. */
    fun liveKeyOf(fx: Fixture): Pair<String, UUID> =
        jdbc
            .query(
                "SELECT id, user_id FROM api_keys WHERE workspace_id = :ws AND name = :name AND is_revoked = FALSE",
                mapOf("ws" to fx.workspaceId, "name" to fx.keyName),
            ) { rs, _ -> rs.getString("id") to rs.getObject("user_id", UUID::class.java) }
            .single()

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

    /**
     * The refusal and ONE trigger narrowed to [event] and [narrowing] — this run's own row. The
     * narrowing's literals are this class's generated names and ids, never external input.
     */
    fun plant(
        event: String,
        narrowing: String,
    ) {
        jdbc.jdbcTemplate.execute(REFUSE_FUNCTION_SQL)
        jdbc.jdbcTemplate.execute(
            "CREATE TRIGGER dp704_audit_refuse BEFORE INSERT ON audit_log FOR EACH ROW " +
                "WHEN (NEW.event = '$event' AND $narrowing) EXECUTE FUNCTION dp704_refuse()",
        )
    }

    /** CASCADE on the lane-namespaced function: it can only ever drop THIS lane's trigger. */
    fun dropPlant() {
        jdbc.jdbcTemplate.execute("DROP TRIGGER IF EXISTS dp704_audit_refuse ON audit_log")
        jdbc.jdbcTemplate.execute("DROP FUNCTION IF EXISTS dp704_refuse() CASCADE")
    }

    // ------------------------------------------------------------------ off-classpath access (RemoveMemberRollbackIntegrationTest's)

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

    private fun principalFor(
        userId: UUID,
        email: String,
        displayName: String,
        superAdmin: Boolean,
    ): Any {
        val constructor =
            Class.forName(PRINCIPAL_CLASS).kotlin.primaryConstructor
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

    fun revokeOwn(
        keyId: String,
        memberId: UUID,
    ) {
        invoke(apiKeyService, "revokeOwn", keyId, memberId)
    }

    private companion object {
        private const val SUFFIX_CHARS = 8

        private const val WORKSPACE_SERVICE_BEAN = "workspaceService"
        private const val API_KEY_SERVICE_BEAN = "apiKeyService"
        private const val USER_REPOSITORY_BEAN = "userRepository"

        private const val PRINCIPAL_CLASS = "co.datapipelines.auth.AuthenticatedPrincipal"
        private const val AUTH_METHOD_CLASS = "co.datapipelines.auth.AuthMethod"
        private const val WORKSPACE_ROLE_CLASS = "co.datapipelines.auth.WorkspaceRole"
        private const val USER_KIND_CLASS = "co.datapipelines.auth.UserKind"
        private const val API_KEY_KIND_CLASS = "co.datapipelines.auth.ApiKeyKind"
        private const val KEY_ROLE_CLASS = "co.datapipelines.auth.KeyRole"

        private const val REFUSE_FUNCTION_SQL =
            "CREATE OR REPLACE FUNCTION dp704_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
                "BEGIN RAISE EXCEPTION 'planted by #704: %', TG_TABLE_NAME; END; \$\$"
    }
}
