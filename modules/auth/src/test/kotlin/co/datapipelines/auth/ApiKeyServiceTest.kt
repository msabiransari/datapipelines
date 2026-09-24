package co.datapipelines.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
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
            every { issuerContext(any(), any(), any(), any()) } answers {
                WorkspaceContext(thirdArg(), arg(3), WorkspaceRole.AUTHOR)
            }
        }
    private val liveness = PrincipalLiveness(userService, workspaceService)
    private val service =
        ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), AuthProperties(), workspaceService, liveness)

    init {
        // 180: the liveness predicate reads `isActive`, the cached form of the snapshot the
        // principal is built from. Live by default, like the workspace above; the tests that
        // are about deactivation say so themselves.
        every { userService.isActive(any()) } returns true
    }

    private val ownerId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    /** A key-borne issuer holding only `read` — the escalation guard's other side (§7.4). */
    private fun readKeyIssuer() =
        issuer.copy(
            scopes = setOf(Scope.READ),
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_READONLYISSUER",
            workspaceName = "acme",
        )

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
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
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
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)

        issued.plaintext shouldStartWith "dpk_"
        issued.record.id shouldStartWith "dpk_"
        // The stored hash is an Argon2id hash, never the plaintext.
        issued.record.keyHash shouldStartWith "\$argon2id\$"
        (issued.record.keyHash == issued.plaintext) shouldBe false
    }

    @Test
    fun `a freshly issued key validates and resolves the owner principal`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.EXECUTE), workspaceId)
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
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        val forged = "${issued.record.id}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        shouldThrow<ApiKeyInvalidException> { service.validate(forged) }
    }

    @Test
    fun `a revoked key is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a revoked pinned key is refused BEFORE the removed-member viewer fallback (#200)`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        // The removed-member answer: no membership resolves, so the fallback WOULD hand a
        // viewer context — reading and running in the workspace they were removed from, the
        // exact defect #200 closes. The revocation must refuse the key upstream of it.
        every { workspaceService.issuerContext(any(), any(), any(), any()) } returns null
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        every { userService.snapshot(ownerId) } returns activeOwner()

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
        io.mockk.verify(exactly = 0) { workspaceService.issuerContext(any(), any(), any(), any()) }
    }

    @Test
    fun `an expired key maps to api_key expired`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record.copy(expiresAt = Instant.now().minusSeconds(60))

        shouldThrow<ApiKeyExpiredException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key whose owner is deactivated is refused with auth principal_deactivated, not api_key invalid (180)`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner().copy(isActive = false)
        every { userService.isActive(ownerId) } returns false

        val thrown = shouldThrow<PrincipalDeactivatedException> { service.validate(issued.plaintext) }
        thrown.code shouldBe AuthErrorCodes.PRINCIPAL_DEACTIVATED
    }

    @Test
    fun `a key whose owner row is gone is rejected as invalid — there is no person to be deactivated`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns null
        every { userService.isActive(ownerId) } returns false

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key pinned to a deactivated workspace keeps the 404 rule (auth key_workspace_inactive)`() {
        echoInsert()
        val issued = service.issue(issuer, ownerId, "Claude", setOf(Scope.READ), workspaceId)
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()
        every { workspaceService.isActive(workspaceId) } returns false

        val thrown = shouldThrow<KeyWorkspaceInactiveException> { service.validate(issued.plaintext) }
        thrown.status shouldBe 404
    }

    @Test
    fun `an unknown key id is rejected as invalid`() {
        every { repo.findById(any()) } returns null
        shouldThrow<ApiKeyInvalidException> { service.validate("dpk_UNKNOWNKEYID.AAAAAAAAAAAAAAAAAAAAAAAA") }
    }

    @Test
    fun `escalation guard - a read creator cannot mint an author key (§7-4)`() {
        shouldThrow<ScopeInsufficientException> {
            readKeyIssuer().let { service.issue(it, ownerId, "Escalate", setOf(Scope.AUTHOR), workspaceId = workspaceId) }
        }
    }

    @Test
    fun `an empty scope request falls back to the CONFIGURED default-scopes (AU-API-3)`() {
        // A non-default configured value, so a hard-coded `read` cannot pass this test.
        val configured =
            AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("execute")))
        val withDefaults =
            ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured, workspaceService, liveness)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5), arg(6), "acme")
        }

        withDefaults.issue(issuer, ownerId, "Claude", emptySet(), workspaceId = workspaceId)

        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    @Test
    fun `an unusable configured default falls back to read (§7-5)`() {
        val configured = AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("nonsense")))
        val withDefaults =
            ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured, workspaceService, liveness)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5), arg(6), "acme")
        }

        withDefaults.issue(issuer, ownerId, "Claude", emptySet(), workspaceId = workspaceId)

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
        val withDefaults =
            ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured, workspaceService, liveness)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5), arg(6), "acme")
        }
        val logged =
            captureWarnings(ApiKeyService::class.java) {
                withDefaults.issue(issuer, ownerId, "Claude", emptySet(), workspaceId = workspaceId)
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
                    workspaceId = workspaceId,
                    kind = ApiKeyKind.SERVER,
                )
            }
        // The floor moved from a SCOPE to the issuer's super-admin flag (D-R1): a server key
        // is the promotion receiver's whole credential, and "who may mint one" is an instance
        // question, which is exactly the kind of question a scope stopped being able to answer.
        refusal.details["required"] shouldBe Permission.SERVER_KEY_CREATE.wire
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
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        echoInsert()
        val user = service.issue(issuer, ownerId, "agent", setOf(Scope.READ), workspaceId)
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
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        every { userService.snapshot(ownerId) } returns activeOwner()

        shouldThrow<ApiKeyInvalidException> { service.validateServerKey(issued.plaintext) }
    }

    @Test
    fun `a server key pinned to a deactivated workspace stops opening the promotion route (180, gap 1)`() {
        echoInsert(kind = ApiKeyKind.SERVER)
        val issued =
            service.issue(
                issuer.copy(superAdmin = true),
                ownerId,
                "uat receiver",
                emptySet(),
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()
        every { workspaceService.isActive(workspaceId) } returns false

        // An AuthException of any kind — the promotion filter folds it into its one answer.
        shouldThrow<KeyWorkspaceInactiveException> { service.validateServerKey(issued.plaintext) }
    }

    @Test
    fun `a server key whose owner is deactivated stops opening the promotion route`() {
        echoInsert(kind = ApiKeyKind.SERVER)
        val issued =
            service.issue(
                issuer.copy(superAdmin = true),
                ownerId,
                "uat receiver",
                emptySet(),
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner().copy(isActive = false)
        every { userService.isActive(ownerId) } returns false

        shouldThrow<PrincipalDeactivatedException> { service.validateServerKey(issued.plaintext) }
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
                workspaceId,
                kind = ApiKeyKind.SERVER,
            )
        every { repo.findById(issued.record.id) } returns
            issued.record.copy(expiresAt = Instant.now().minusSeconds(1))
        every { userService.snapshot(ownerId) } returns activeOwner()

        shouldThrow<ApiKeyExpiredException> { service.validateServerKey(issued.plaintext) }
    }

    // ------------------------------------------------------- D16: the login mint (179)

    /**
     * A REAL in-memory sealer, never a strict mock (the "must be CALLED" lesson): the mint's
     * contract is that the plaintext is sealed and storable, and the copy path's is that what
     * was sealed opens back to exactly it. The fake makes both assertable as EFFECTS.
     */
    private class FakeSealer : SecretSealer {
        override fun seal(
            plaintext: String,
            aad: String,
        ): ByteArray = "$aad|$plaintext".toByteArray()

        override fun open(
            sealed: ByteArray,
            aad: String,
        ): String {
            val text = String(sealed)
            check(text.startsWith("$aad|")) { "sealed under a different AAD" }
            return text.removePrefix("$aad|")
        }
    }

    private val sealer = FakeSealer()
    private val mintingService =
        ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), AuthProperties(), workspaceService, liveness, sealer)

    private fun owner(
        mustChange: Boolean = false,
        superAdmin: Boolean = false,
    ) = activeOwner().copy(mustChangePassword = mustChange, isAdmin = superAdmin)

    private fun contextOf(role: WorkspaceRole) = WorkspaceContext(workspaceId, "acme", role)

    /** Captures one mint's insert and answers with the record the repository would return. */
    private fun captureMint(): io.mockk.CapturingSlot<Boolean> {
        val mintedAtLogin = slot<Boolean>()
        every { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(mintedAtLogin)) } answers {
            record(
                id = firstArg(),
                name = thirdArg(),
                scopes = arg(4),
                workspaceId = arg(6),
            )
        }
        return mintedAtLogin
    }

    @Test
    fun `the login mint seals the secret, marks the row, and an existing key makes it a no-op`() {
        val mintedAtLogin = captureMint()
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns null

        val minted = mintingService.mintLoginKey(owner(), contextOf(WorkspaceRole.AUTHOR), LoginMethod.PWD)!!

        mintedAtLogin.captured shouldBe true
        minted.name shouldBe "mcp/acme"
        minted.kind shouldBe ApiKeyKind.USER
        minted.expiresAt shouldBe null
        minted.scopes shouldBe Scope.AUTHOR.expand()
        // The plaintext never leaves the mint — what the row holds is the hash and the seal.
        minted.keyHash shouldStartWith "\$argon2id\$"

        // Second call: the key exists, nothing happens.
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns minted
        mintingService.mintLoginKey(owner(), contextOf(WorkspaceRole.AUTHOR), LoginMethod.PWD) shouldBe null
    }

    @Test
    fun `the mint's scopes are the role's reach - and a super admin is never capped by a viewer membership`() {
        captureMint()
        every { repo.findLiveUserKey(any(), any()) } returns null

        fun scopesFor(
            role: WorkspaceRole,
            superAdmin: Boolean = false,
        ) = mintingService.mintLoginKey(owner(superAdmin = superAdmin), contextOf(role), LoginMethod.PWD)!!.scopes

        scopesFor(WorkspaceRole.VIEWER) shouldBe Scope.EXECUTE.expand()
        scopesFor(WorkspaceRole.PROMOTER) shouldBe setOf(Scope.READ)
        scopesFor(WorkspaceRole.AUTHOR) shouldBe Scope.AUTHOR.expand()
        scopesFor(WorkspaceRole.WORKSPACE_ADMIN) shouldBe Scope.AUTHOR.expand()
        // D7: the demo join's viewer row must not cap the instance's owner (found on the lane).
        scopesFor(WorkspaceRole.VIEWER, superAdmin = true) shouldBe Scope.AUTHOR.expand()
    }

    @Test
    fun `a user owing a password change gets no key`() {
        mintingService.mintLoginKey(owner(mustChange = true), contextOf(WorkspaceRole.AUTHOR), LoginMethod.PWD) shouldBe null
        io.mockk.verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * #210 — the flag belongs to the LOCAL credential: an OIDC session is not gated by it
     * (ForcedPasswordChangeInterceptor) and gets its key like any other entry. A Google user
     * whose bootstrap-seeded local password was never changed spent four sign-ins keyless
     * on datapipelines.co (2026-09-22) because the mint did not make the distinction.
     */
    @Test
    fun `an OIDC sign-in mints the key even while a local password change is owed`() {
        val mintedAtLogin = captureMint()
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns null
        mintingService.mintLoginKey(owner(mustChange = true), contextOf(WorkspaceRole.AUTHOR), LoginMethod.OIDC).shouldNotBeNull()
        mintedAtLogin.captured shouldBe true
    }

    @Test
    fun `a lost race is the index's answer, re-read as nothing-to-do`() {
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns null
        every { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            org.springframework.dao.DuplicateKeyException("api_keys_one_live_user_key")

        mintingService.mintLoginKey(owner(), contextOf(WorkspaceRole.AUTHOR), LoginMethod.PWD) shouldBe null
    }

    @Test
    fun `the copy path opens only the owner's live key's sealed secret - exactly once`() {
        val key = record(id = "dpk_SEALED000001")
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns key
        val fullKey = "dpk_SEALED000001.${"A".repeat(48)}"
        every { repo.openAndClearSealedSecret(key.id, ownerId) } returns sealer.seal(fullKey, key.id)

        mintingService.openOwnMcpKey(ownerId, workspaceId) shouldBe fullKey
        // The open is owner-scoped: the caller's id, never just the key's, reaches the clear.
        io.mockk.verify { repo.openAndClearSealedSecret(key.id, ownerId) }

        // #213: the first read destroyed the copy — the repository answers null from then on,
        // exactly as it does for a pre-V31 key (nothing sealed). The chip shows the prefix
        // without a copy button; with no key at all it is absent.
        every { repo.openAndClearSealedSecret(key.id, ownerId) } returns null
        mintingService.openOwnMcpKey(ownerId, workspaceId) shouldBe null
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns null
        mintingService.openOwnMcpKey(ownerId, workspaceId) shouldBe null
    }

    @Test
    fun `the workspace revoke is the endpoint kind's only - a flipped row audits, a foreign id does not`() {
        every { repo.revokeInWorkspace("dpk_EP0000000001", workspaceId, ApiKeyKind.ENDPOINT) } returns true
        mintingService.revokeWorkspaceEndpointKey("dpk_EP0000000001", workspaceId, ownerId) shouldBe true
        io.mockk.verify { auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = "dpk_EP0000000001", details = any()) }

        every { repo.revokeInWorkspace("dpk_FOREIGN00001", workspaceId, ApiKeyKind.ENDPOINT) } returns false
        mintingService.revokeWorkspaceEndpointKey("dpk_FOREIGN00001", workspaceId, ownerId) shouldBe false
        io.mockk.verify(exactly = 1) { auditLogger.log(event = "auth.api_key.revoked", userId = any(), keyId = any(), details = any()) }
    }

    /**
     * #215 (owner ruling 2026-09-24, the record's `server_key.revoke` row): revoking a server key
     * is a super admin's verb — the mint's floor (D18) on the way out. #191 had let the
     * `/api-keys` page's workspace admin do it; the ruling narrowed that. Refused BEFORE the SQL:
     * a workspace admin's attempt flips no row and audits nothing.
     */
    @Test
    fun `revoking a server key is a super admin's - a workspace admin is refused and nothing is revoked`() {
        val workspaceAdmin = issuer.copy(workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN))

        val refusal =
            shouldThrow<RoleRequiredException> {
                mintingService.revokeWorkspaceServerKey("dpk_SRV000000001", workspaceId, workspaceAdmin)
            }
        refusal.code shouldBe AuthErrorCodes.ROLE_REQUIRED
        io.mockk.verify(exactly = 0) { repo.revokeInWorkspace(any(), any(), any()) }
        io.mockk.verify(exactly = 0) { auditLogger.log(event = "auth.api_key.revoked", userId = any(), keyId = any(), details = any()) }

        every { repo.revokeInWorkspace("dpk_SRV000000001", workspaceId, ApiKeyKind.SERVER) } returns true
        mintingService.revokeWorkspaceServerKey("dpk_SRV000000001", workspaceId, workspaceAdmin.copy(superAdmin = true)) shouldBe true
        io.mockk.verify { auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = "dpk_SRV000000001", details = any()) }
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
