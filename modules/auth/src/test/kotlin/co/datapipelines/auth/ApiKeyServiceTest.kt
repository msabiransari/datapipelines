package co.datapipelines.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** API keys (auth.md §7): Argon2id issue/validate, revocation, expiry, escalation guard. */
class ApiKeyServiceTest {
    private val repo = mockk<ApiKeyRepository>(relaxed = true)
    private val userService = mockk<UserService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties())

    /**
     * Relaxed, then STUBBED for the two per-request re-reads validation now makes (D-R12).
     * A relaxed mock answers `isActive` with `false`, which would refuse every key in this
     * file for the wrong reason — the default must be the live workspace, so a test that
     * cares about deactivation says so itself.
     */
    private val workspaceService =
        mockk<WorkspaceService>(relaxed = true) {
            every { isActive(any()) } returns true
            every { issuerFlags(any(), any(), any()) } returns MembershipFlags(author = true)
        }
    private val service = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), AuthProperties(), workspaceService)

    private val ownerId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    /**
     * The ISSUER, as RBAC round 1 requires issuance to name it (D-R12/O-2): a key is minted by
     * a person with a role in the pinned workspace, not by a bare user id. An AUTHOR here,
     * which is the minimum O-2 allows.
     */
    private val issuer =
        AuthenticatedPrincipal(
            userId = ownerId,
            email = "owner@company.com",
            displayName = "Owner",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", MembershipFlags(author = true)),
        )

    private fun activeOwner() =
        User(ownerId, "owner@company.com", "Owner", null, "keycloak", "sub", true, false, Instant.now(), Instant.now(), null)

    /**
     * Stubs `insert` to echo back a record built from its arguments (real keyHash). [kind] is
     * matched EXACTLY — mockk turns a defaulted argument into an `eq` matcher, so a stub for
     * `user` does not answer a `server` mint, which is what we want: a test that meant to mint
     * one kind and got the other should fail loudly rather than quietly.
     */
    private fun echoInsert(kind: ApiKeyKind = ApiKeyKind.DEFAULT): io.mockk.CapturingSlot<String> {
        val hash = slot<String>()
        every { repo.insert(any(), ownerId, any(), capture(hash), any(), any(), any(), kind) } answers {
            record(
                id = firstArg(),
                name = thirdArg(),
                hash = hash.captured,
                scopes = arg(4),
                expiresAt = arg(5),
                workspaceId = arg(6),
                kind = kind,
            )
        }
        return hash
    }

    /** One `api_keys` row as the repository would return it. */
    @Suppress("LongParameterList") // a row, spelled out
    private fun record(
        id: String,
        name: String = "key",
        hash: String = "\$argon2id\$fixture",
        scopes: Set<Scope> = emptySet(),
        expiresAt: Instant? = null,
        workspaceId: UUID = this.workspaceId,
        kind: ApiKeyKind = ApiKeyKind.DEFAULT,
    ) = ApiKey(
        id = id,
        userId = ownerId,
        name = name,
        keyHash = hash,
        scopes = scopes,
        isRevoked = false,
        createdAt = Instant.now(),
        lastUsedAt = null,
        expiresAt = expiresAt,
        workspaceId = workspaceId,
        workspaceName = "acme",
        kind = kind,
    )

    @Test
    fun `issue returns a dpk_ plaintext and persists only the hash`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), setOf(Scope.AUTHOR), workspaceId)

        issued.plaintext shouldStartWith "dpk_"
        issued.record.id shouldStartWith "dpk_"
        // The stored hash is an Argon2id hash, never the plaintext.
        issued.record.keyHash shouldStartWith "\$argon2id\$"
        (issued.record.keyHash == issued.plaintext) shouldBe false
    }

    @Test
    fun `a freshly issued key validates and resolves the owner principal`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.EXECUTE), setOf(Scope.AUTHOR), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        val principal = service.validate(issued.plaintext)

        principal.userId shouldBe ownerId
        principal.authMethod shouldBe AuthMethod.API_KEY
        principal.keyId shouldBe issued.record.id
        principal.scopes shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    @Test
    fun `a wrong secret for a real key id is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        val forged = "${issued.record.id}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        shouldThrow<ApiKeyInvalidException> { service.validate(forged) }
    }

    @Test
    fun `a revoked key is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `an expired key maps to api_key expired`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record.copy(expiresAt = Instant.now().minusSeconds(60))

        shouldThrow<ApiKeyExpiredException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key whose owner is inactive is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner().copy(isActive = false)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `an unknown key id is rejected as invalid`() {
        every { repo.findById(any()) } returns null
        shouldThrow<ApiKeyInvalidException> { service.validate("dpk_UNKNOWNKEYID.AAAAAAAAAAAAAAAAAAAAAAAA") }
    }

    @Test
    fun `escalation guard - a read creator cannot mint an author key (§7-4)`() {
        shouldThrow<ScopeInsufficientException> {
            service.issue(issuer, ownerId, "Escalate", setOf(Scope.AUTHOR), creatorScopes = setOf(Scope.READ), workspaceId = workspaceId)
        }
    }

    @Test
    fun `an empty scope request falls back to the CONFIGURED default-scopes (AU-API-3)`() {
        // A non-default configured value, so a hard-coded `read` cannot pass this test.
        val configured =
            AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("execute")))
        val withDefaults = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured, workspaceService)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5), arg(6), "acme")
        }

        withDefaults.issue(issuer, ownerId, "Claude", emptySet(), creatorScopes = setOf(Scope.AUTHOR), workspaceId = workspaceId)

        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    @Test
    fun `an unusable configured default falls back to read (§7-5)`() {
        val configured = AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("nonsense")))
        val withDefaults = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured, workspaceService)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5), arg(6), "acme")
        }

        withDefaults.issue(issuer, ownerId, "Claude", emptySet(), creatorScopes = setOf(Scope.AUTHOR), workspaceId = workspaceId)

        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.READ)
    }

    /**
     * API L5: the fallback above is silent degradation — an operator typo in
     * `default-scopes` produces `read` keys and no signal at all, so nobody finds out
     * until a key mysteriously lacks permission. The WARN must NAME the bad token
     * (rules/02), which is why this asserts the message content, not merely that
     * something was logged.
     */
    @Test
    fun `an unparseable configured default-scopes token is reported at WARN, naming the token`() {
        val configured =
            AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("nonsense", "execute")))
        val withDefaults = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured, workspaceService)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5), arg(6), "acme")
        }
        val logged =
            captureWarnings(ApiKeyService::class.java) {
                withDefaults.issue(issuer, ownerId, "Claude", emptySet(), creatorScopes = setOf(Scope.AUTHOR), workspaceId = workspaceId)
            }

        logged.any { it.contains("nonsense") } shouldBe true
        // The good token still applies — one bad entry does not discard the whole list.
        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    // ------------------------------------------------------------------ §7.7 the server kind (091)

    @Test
    fun `minting a server key requires an ADMIN creator, not merely a subset of scopes`() {
        // The escalation guard above is VACUOUS for a server key — it has no scopes to be a
        // subset of — so the floor is on the CREATOR and is a different check. An `author`
        // session must not be able to mint the credential that opens this deployment's
        // promotion receiver.
        echoInsert(kind = ApiKeyKind.SERVER)

        val refusal =
            shouldThrow<RoleRequiredException> {
                service.issue(
                    issuer = issuer,
                    ownerId = ownerId,
                    name = "uat receiver",
                    scopes = emptySet(),
                    creatorScopes = setOf(Scope.AUTHOR),
                    workspaceId = workspaceId,
                    kind = ApiKeyKind.SERVER,
                )
            }
        // The floor moved from a SCOPE to the issuer's super-admin flag (D-R1): a server key
        // is the promotion receiver's whole credential, and "who may mint one" is an instance
        // question, which is exactly the kind of question a scope stopped being able to answer.
        refusal.details["required"] shouldBe Capability.SUPER_ADMIN.wire
    }

    @Test
    fun `a super admin mints a server key with NO scopes - the default-scopes fallback never applies`() {
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any(), ApiKeyKind.SERVER) } answers {
            record(id = firstArg(), scopes = arg(4), kind = ApiKeyKind.SERVER)
        }

        val issued =
            service.issue(
                issuer = issuer.copy(superAdmin = true),
                ownerId = ownerId,
                name = "uat receiver",
                // Asked for, and correctly ignored: a server key's authority is its route family.
                // `admin` here is NOT the O-2 refusal: a SCOPELESS kind never reaches the scope
                // check at all, because its requested set is emptied before it.
                scopes = setOf(Scope.ADMIN),
                creatorScopes = setOf(Scope.ADMIN),
                workspaceId = workspaceId,
                kind = ApiKeyKind.SERVER,
            )

        scopes.captured shouldBe emptySet()
        issued.record.scopes shouldBe emptySet()
        issued.record.isServerKey shouldBe true
    }

    @Test
    fun `a server key validates on the API-key path, carrying its kind - the confinement then refuses it`() {
        // It authenticates: the credential is real. What it may DO is `ScopeInterceptor`'s and
        // `McpAuthFilter`'s answer (ServerKeyConfinementTest, McpEndpointKeyRefusalTest), and
        // both need a principal that says SERVER to give it.
        echoInsert(kind = ApiKeyKind.SERVER)
        val issued =
            service.issue(
                issuer.copy(superAdmin = true),
                ownerId,
                "uat receiver",
                emptySet(),
                setOf(Scope.ADMIN),
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        val principal = service.validate(issued.plaintext)

        principal.keyKind shouldBe ApiKeyKind.SERVER
        principal.isServerKey shouldBe true
        principal.scopes shouldBe emptySet()
    }

    @Test
    fun `validateServerKey accepts a server key and refuses every other kind with the SAME answer`() {
        echoInsert(kind = ApiKeyKind.SERVER)
        val server =
            service.issue(
                issuer.copy(superAdmin = true),
                ownerId,
                "uat receiver",
                emptySet(),
                setOf(Scope.ADMIN),
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        echoInsert()
        val user = service.issue(issuer, ownerId, "agent", setOf(Scope.READ), setOf(Scope.ADMIN), workspaceId)
        every { repo.findById(server.record.id) } returns server.record
        every { repo.findById(user.record.id) } returns user.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        service.validateServerKey(server.plaintext).id shouldBe server.record.id
        // A perfectly valid USER key is not a promotion credential — and it is refused with the
        // same exception an unknown key gets, so the route cannot classify a stolen key.
        shouldThrow<ApiKeyInvalidException> { service.validateServerKey(user.plaintext) }
        shouldThrow<ApiKeyInvalidException> { service.validateServerKey("dpk_UNKNOWNKEYID.AAAAAAAAAAAAAAAAAAAAAAAA") }
    }

    @Test
    fun `a revoked server key stops opening the promotion route`() {
        // The whole point of moving the credential into the key store: revocation exists.
        echoInsert(kind = ApiKeyKind.SERVER)
        val issued =
            service.issue(
                issuer.copy(superAdmin = true),
                ownerId,
                "uat receiver",
                emptySet(),
                setOf(Scope.ADMIN),
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        every { userService.snapshot(ownerId) } returns activeOwner()

        shouldThrow<ApiKeyInvalidException> { service.validateServerKey(issued.plaintext) }
    }

    @Test
    fun `an expired server key stops opening the promotion route`() {
        echoInsert(kind = ApiKeyKind.SERVER)
        val issued =
            service.issue(
                issuer.copy(superAdmin = true),
                ownerId,
                "uat receiver",
                emptySet(),
                setOf(Scope.ADMIN),
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns
            issued.record.copy(expiresAt = Instant.now().minusSeconds(1))
        every { userService.snapshot(ownerId) } returns activeOwner()

        shouldThrow<ApiKeyExpiredException> { service.validateServerKey(issued.plaintext) }
    }

    /** Collects WARN-level messages emitted by [type]'s logger while [block] runs. */
    private fun captureWarnings(
        type: Class<*>,
        block: () -> Unit,
    ): List<String> {
        val logger = LoggerFactory.getLogger(type) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }
}
