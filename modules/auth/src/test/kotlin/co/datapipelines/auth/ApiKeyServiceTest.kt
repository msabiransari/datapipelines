package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * API keys (auth.md §7) since #215 slice (b): Argon2id issue/validate, and WHO a key acts as —
 * an `endpoint` or `server` key its own `service` identity (PK5, B4), the MCP key its member with
 * the role capped at author (PK4) and never a super admin (B1) — revocation deactivating the
 * identity, the creation limit (O3) and the login mint, which freezes nothing about the role.
 */
class ApiKeyServiceTest {
    private val repo = mockk<ApiKeyRepository>(relaxed = true)
    private val userService = mockk<UserService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties())

    private val ownerId = UUID.randomUUID()
    private val identityId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    /**
     * Relaxed, then STUBBED for what issuance and validation ask. A relaxed mock answers `isActive`
     * with `false`, which would refuse every key here for the wrong reason — the default is the
     * live workspace, a workspace-admin creator, and an author member; a test that cares says so.
     */
    private val workspaceService =
        mockk<WorkspaceService>(relaxed = true) {
            every { isActive(any()) } returns true
            every { requireIssuancePermission(any(), any(), any()) } answers {
                WorkspaceContext(secondArg(), "acme", WorkspaceRole.WORKSPACE_ADMIN)
            }
            every { activeRoleIn(any(), any()) } returns WorkspaceRole.AUTHOR
        }
    private val liveness = PrincipalLiveness(userService, workspaceService)
    private val service = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), workspaceService, liveness)

    init {
        // 180: the liveness predicate reads `isActive`. Live by default, like the workspace
        // above; the tests about deactivation say so themselves.
        every { userService.isActive(any()) } returns true
        every { userService.provisionIdentity(any(), any()) } answers { identity(keyId = firstArg(), name = secondArg()) }
        every { userService.snapshot(identityId) } answers { identity() }
        every { userService.snapshot(ownerId) } answers { member() }
        every { userService.deactivateIdentity(any()) } returns Unit
    }

    /** The CREATOR: a workspace admin's session in the pinned workspace. */
    private val creator =
        AuthenticatedPrincipal(
            userId = ownerId,
            email = "owner@company.com",
            displayName = "Owner",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN),
        )

    private fun member(superAdmin: Boolean = false) =
        User(ownerId, "owner@company.com", "Owner", null, "keycloak", "sub", true, superAdmin, Instant.now(), Instant.now(), null)

    /** The key's own identity (#215 record §3.3), built as `UserService.provisionIdentity` builds it. */
    private fun identity(
        keyId: String = "dpk_IDENTITYKEY1",
        name: String = "ci",
    ) = User(
        id = identityId,
        email = "${keyId.lowercase()}@keys.invalid",
        displayName = name,
        provider = UserService.KEY_PROVIDER,
        providerSubject = keyId,
        isActive = true,
        isAdmin = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        kind = UserKind.SERVICE,
    )

    /**
     * Stubs `insert` for an identity-acting [kind] to echo back the row it was given (real hash). The
     * KIND is matched exactly — a test that meant to mint one kind and got the other fails loudly.
     */
    private fun echoIdentityInsert(kind: ApiKeyKind) {
        val hash = slot<String>()
        every { repo.insert(any(), any(), ownerId, any(), capture(hash), any(), any(), kind) } answers {
            record(id = firstArg(), userId = secondArg(), name = arg(3), hash = hash.captured, expiresAt = arg(5), kind = kind)
        }
    }

    /** One `api_keys` row as the repository would return it. */
    @Suppress("LongParameterList") // a row, spelled out
    private fun record(
        id: String,
        userId: UUID = identityId,
        name: String = "ci",
        hash: String = "\$argon2id\$fixture",
        expiresAt: Instant? = null,
        kind: ApiKeyKind = ApiKeyKind.ENDPOINT,
    ) = ApiKey(
        id = id,
        userId = userId,
        name = name,
        keyHash = hash,
        isRevoked = false,
        createdAt = Instant.now(),
        lastUsedAt = null,
        expiresAt = expiresAt,
        workspaceId = workspaceId,
        workspaceName = "acme",
        kind = kind,
        createdBy = ownerId,
    )

    private fun issueEndpointKey(): IssuedApiKey {
        echoIdentityInsert(ApiKeyKind.ENDPOINT)
        val issued = service.issue(creator, "ci", workspaceId, kind = ApiKeyKind.ENDPOINT)
        every { repo.findById(issued.record.id) } returns issued.record
        return issued
    }

    private fun issueServerKey(): IssuedApiKey {
        echoIdentityInsert(ApiKeyKind.SERVER)
        val issued = service.issue(creator.copy(superAdmin = true), "uat receiver", workspaceId, kind = ApiKeyKind.SERVER)
        every { repo.findById(issued.record.id) } returns issued.record
        return issued
    }

    // ------------------------------------------------------------------ issuance (A.2, B4)

    @Test
    fun `issue returns a dpk_ plaintext and persists only the hash`() {
        val issued = issueEndpointKey()

        issued.plaintext shouldStartWith "dpk_"
        issued.record.id shouldStartWith "dpk_"
        // The stored hash is an Argon2id hash, never the plaintext.
        issued.record.keyHash shouldStartWith "\$argon2id\$"
        (issued.record.keyHash == issued.plaintext) shouldBe false
    }

    /**
     * B4 / record §3.3: an `endpoint` key and its `service` identity are created TOGETHER — the key
     * row's `user_id` is the identity, `created_by` the creator, and the role is the kind's. (That
     * the two writes share one transaction is `TransactionRollbackIntegrationTest`'s to prove.)
     */
    @Test
    fun `issue creates the key's own identity with it - the key acts as the identity, the creator is recorded`() {
        val issued = issueEndpointKey()

        assertAll(
            { verify(exactly = 1) { userService.provisionIdentity(issued.record.id, "ci") } },
            {
                verify(
                    exactly = 1,
                ) { repo.insert(issued.record.id, identityId, ownerId, "ci", any(), null, workspaceId, ApiKeyKind.ENDPOINT) }
            },
            { issued.record.userId shouldBe identityId },
            { issued.record.createdBy shouldBe ownerId },
            { issued.record.role shouldBe KeyRole.API_CALLER },
            {
                verify {
                    auditLogger.log(
                        event = "auth.api_key.created",
                        userId = ownerId,
                        keyId = issued.record.id,
                        details = match { it["role"] == "api_caller" && it["identity_id"] == identityId.toString() },
                    )
                }
            },
        )
    }

    @Test
    fun `the MCP key is not mintable on demand - no identity and no row (D16)`() {
        shouldThrow<KeyKindNotMintableException> { service.issue(creator, "x", workspaceId, kind = ApiKeyKind.USER) }

        verify(exactly = 0) { userService.provisionIdentity(any(), any()) }
        verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /** Gate 7: the per-kind create permission is asked BEFORE anything is written. */
    @Test
    fun `a creator refused the kind's create permission gets no identity and no key`() {
        every { workspaceService.requireIssuancePermission(any(), any(), ApiKeyKind.SERVER) } throws
            RoleRequiredException(Permission.SERVER_KEY_CREATE, "workspace_admin", "acme")

        val refusal = shouldThrow<RoleRequiredException> { service.issue(creator, "uat receiver", workspaceId, kind = ApiKeyKind.SERVER) }

        refusal.details["required"] shouldBe Permission.SERVER_KEY_CREATE.wire
        verify(exactly = 0) { userService.provisionIdentity(any(), any()) }
        verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * The creation limit (record O3, ruled): a key's role must not hold a permission its creator
     * lacks. It holds by construction for the two pre-created roles and the permission checks that
     * pick who may create them — so this pins the RUNTIME check with a creator context that would
     * slip past it: a promoter (who reads no executions, D5) asking for an `api_caller` key.
     */
    @Test
    fun `the creation limit refuses a key role that holds more than its creator (O3)`() {
        every { workspaceService.requireIssuancePermission(any(), any(), ApiKeyKind.ENDPOINT) } returns
            WorkspaceContext(workspaceId, "acme", WorkspaceRole.PROMOTER)

        val refusal = shouldThrow<RoleRequiredException> { service.issue(creator, "ci", workspaceId, kind = ApiKeyKind.ENDPOINT) }

        refusal.details["held"] shouldBe "promoter"
        verify(exactly = 0) { userService.provisionIdentity(any(), any()) }
    }

    // ------------------------------------------------------------------ validation: the identity

    @Test
    fun `an endpoint key validates as its OWN identity, holding its key role and nothing else`() {
        val issued = issueEndpointKey()

        val principal = service.validate(issued.plaintext)

        assertAll(
            { principal.userId shouldBe identityId },
            { principal.displayName shouldBe "ci" },
            { principal.authMethod shouldBe AuthMethod.API_KEY },
            { principal.keyId shouldBe issued.record.id },
            { principal.keyKind shouldBe ApiKeyKind.ENDPOINT },
            { principal.keyRole shouldBe KeyRole.API_CALLER },
            { principal.isSuperAdmin shouldBe false },
            { principal.holds(Permission.ENDPOINT_SERVE) shouldBe true },
            { principal.holds(Permission.EXECUTION_RESULT_READ) shouldBe true },
            { principal.holds(Permission.PIPELINE_READ) shouldBe false },
        )
    }

    /** PK2 / A2: who created the key does not matter when it is used — the identity's liveness does. */
    @Test
    fun `an endpoint key lives with its identity, not its creator`() {
        val issued = issueEndpointKey()

        every { userService.isActive(ownerId) } returns false
        service.validate(issued.plaintext).userId shouldBe identityId

        every { userService.isActive(identityId) } returns false
        shouldThrow<PrincipalDeactivatedException> { service.validate(issued.plaintext) }.code shouldBe AuthErrorCodes.PRINCIPAL_DEACTIVATED
    }

    @Test
    fun `a wrong secret for a real key id is rejected as invalid`() {
        val issued = issueEndpointKey()

        val forged = "${issued.record.id}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        shouldThrow<ApiKeyInvalidException> { service.validate(forged) }
    }

    @Test
    fun `a revoked key is rejected as invalid`() {
        val issued = issueEndpointKey()
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `an expired key maps to api_key expired`() {
        val issued = issueEndpointKey()
        every { repo.findById(issued.record.id) } returns issued.record.copy(expiresAt = Instant.now().minusSeconds(60))

        shouldThrow<ApiKeyExpiredException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key whose identity row is gone is rejected as invalid - there is no principal to be deactivated`() {
        val issued = issueEndpointKey()
        every { userService.snapshot(identityId) } returns null

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key pinned to a deactivated workspace keeps the 404 rule (auth key_workspace_inactive)`() {
        val issued = issueEndpointKey()
        every { workspaceService.isActive(workspaceId) } returns false

        shouldThrow<KeyWorkspaceInactiveException> { service.validate(issued.plaintext) }.status shouldBe 404
    }

    @Test
    fun `an unknown key id is rejected as invalid`() {
        every { repo.findById(any()) } returns null
        shouldThrow<ApiKeyInvalidException> { service.validate("dpk_UNKNOWNKEYID.AAAAAAAAAAAAAAAAAAAAAAAA") }
    }

    // ------------------------------------------------------------------ validation: the MCP key (PK4, B1)

    private fun mcpKey(): String {
        val plaintext = "dpk_MCPKEYAAAAAA.${"B".repeat(48)}"
        val row =
            record(
                id = "dpk_MCPKEYAAAAAA",
                userId = ownerId,
                name = "mcp/acme",
                hash = Argon2SecretHasher().hash(plaintext),
                kind = ApiKeyKind.USER,
            )
        every { repo.findById(row.id) } returns row
        return plaintext
    }

    /**
     * PK4, record §3.4: the MCP key acts as its MEMBER, with the member's CURRENT role capped at
     * author — every member role, re-read per request (B5) — and a super admin's key is a member's
     * key: capped the same way, or a viewer where they hold no membership. No key is ever a super
     * admin (B1): neither the principal's flag nor its context's is set.
     */
    @Test
    fun `the MCP key acts as its member's current role capped at author - and is never a super admin (PK4, B1)`() {
        val key = mcpKey()

        data class Case(
            val role: WorkspaceRole?,
            val superAdmin: Boolean,
            val expected: WorkspaceRole,
            val implicit: Boolean,
        )
        val cases =
            listOf(
                Case(WorkspaceRole.VIEWER, superAdmin = false, expected = WorkspaceRole.VIEWER, implicit = false),
                Case(WorkspaceRole.AUTHOR, superAdmin = false, expected = WorkspaceRole.AUTHOR, implicit = false),
                Case(WorkspaceRole.PROMOTER, superAdmin = false, expected = WorkspaceRole.PROMOTER, implicit = false),
                Case(WorkspaceRole.WORKSPACE_ADMIN, superAdmin = false, expected = WorkspaceRole.AUTHOR, implicit = false),
                Case(WorkspaceRole.WORKSPACE_ADMIN, superAdmin = true, expected = WorkspaceRole.AUTHOR, implicit = false),
                Case(null, superAdmin = true, expected = WorkspaceRole.VIEWER, implicit = true),
            )
        cases.forEach { case ->
            cache.invalidateKey("dpk_MCPKEYAAAAAA")
            every { userService.snapshot(ownerId) } returns member(superAdmin = case.superAdmin)
            every { workspaceService.activeRoleIn(ownerId, workspaceId) } returns case.role
            val principal = service.validate(key)
            withClue("member ${case.role?.wire}, super admin ${case.superAdmin}") {
                principal.userId shouldBe ownerId
                principal.keyKind shouldBe ApiKeyKind.USER
                principal.keyRole.shouldBeNull()
                principal.workspace?.role shouldBe case.expected
                principal.workspace?.implicit shouldBe case.implicit
                principal.isSuperAdmin shouldBe false
                principal.workspace?.superAdmin shouldBe false
                RolePermissions.INSTANCE.forEach { principal.holds(it) shouldBe false }
            }
        }
    }

    @Test
    fun `a revoked MCP key is refused BEFORE its member's role is re-read (#200)`() {
        val key = mcpKey()
        every { repo.findById("dpk_MCPKEYAAAAAA") } answers
            { record(id = firstArg(), userId = ownerId, kind = ApiKeyKind.USER).copy(isRevoked = true) }

        shouldThrow<ApiKeyInvalidException> { service.validate(key) }
        verify(exactly = 0) { workspaceService.activeRoleIn(any(), any()) }
    }

    // ------------------------------------------------------------------ the server kind (§7.7, C4, B6)

    @Test
    fun `a server key is created with its own identity and the promotion_receiver role`() {
        val issued = issueServerKey()

        issued.record.userId shouldBe identityId
        issued.record.role shouldBe KeyRole.PROMOTION_RECEIVER
        issued.record.isServerKey shouldBe true
    }

    @Test
    fun `validateServerKey returns the key and the identity it acts as, and refuses every other kind with the SAME answer`() {
        val server = issueServerKey()
        val user = mcpKey()

        val validated = service.validateServerKey(server.plaintext)
        validated.key.id shouldBe server.record.id
        validated.identity.id shouldBe identityId
        // A perfectly valid MCP key is not a promotion credential — refused with the same
        // exception an unknown key gets, so the route cannot classify a stolen key.
        shouldThrow<ApiKeyInvalidException> { service.validateServerKey(user) }
        shouldThrow<ApiKeyInvalidException> { service.validateServerKey("dpk_UNKNOWNKEYID.AAAAAAAAAAAAAAAAAAAAAAAA") }
    }

    @Test
    fun `a server key stops opening the promotion route when revoked, expired, its identity or its pin is deactivated - not its creator`() {
        val issued = issueServerKey()

        every { userService.isActive(ownerId) } returns false
        service.validateServerKey(issued.plaintext).identity.id shouldBe identityId

        every { userService.isActive(identityId) } returns false
        shouldThrow<PrincipalDeactivatedException> { service.validateServerKey(issued.plaintext) }
        every { userService.isActive(identityId) } returns true

        every { workspaceService.isActive(workspaceId) } returns false
        shouldThrow<KeyWorkspaceInactiveException> { service.validateServerKey(issued.plaintext) }
        every { workspaceService.isActive(workspaceId) } returns true

        every { repo.findById(issued.record.id) } returns issued.record.copy(expiresAt = Instant.now().minusSeconds(1))
        cache.invalidateKey(issued.record.id)
        shouldThrow<ApiKeyExpiredException> { service.validateServerKey(issued.plaintext) }

        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        cache.invalidateKey(issued.record.id)
        shouldThrow<ApiKeyInvalidException> { service.validateServerKey(issued.plaintext) }
    }

    // ------------------------------------------------------------------ revocation deactivates the identity

    @Test
    fun `revoking an identity-acting key deactivates its identity - revoking an MCP key deactivates nobody`() {
        every { repo.revoke("dpk_EP0000000001", ownerId) } returns record(id = "dpk_EP0000000001").copy(isRevoked = true)
        service.revoke("dpk_EP0000000001", ownerId) shouldBe true
        verify(exactly = 1) { userService.deactivateIdentity(identityId) }

        every { repo.revoke("dpk_MCP000000001", ownerId) } returns
            record(id = "dpk_MCP000000001", userId = ownerId, kind = ApiKeyKind.USER).copy(isRevoked = true)
        service.revoke("dpk_MCP000000001", ownerId) shouldBe true
        verify(exactly = 0) { userService.deactivateIdentity(ownerId) }

        every { repo.revoke("dpk_NOTMINE00001", ownerId) } returns null
        service.revoke("dpk_NOTMINE00001", ownerId) shouldBe false
        verify(exactly = 1) { userService.deactivateIdentity(any()) }
    }

    @Test
    fun `the workspace revoke is the endpoint kind's only - a flipped row audits and deactivates, a foreign id does neither`() {
        every { repo.revokeInWorkspace("dpk_EP0000000001", workspaceId, ApiKeyKind.ENDPOINT) } returns true
        every { repo.findById("dpk_EP0000000001") } returns record(id = "dpk_EP0000000001").copy(isRevoked = true)
        service.revokeWorkspaceEndpointKey("dpk_EP0000000001", workspaceId, ownerId) shouldBe true
        verify { auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = "dpk_EP0000000001", details = any()) }
        verify(exactly = 1) { userService.deactivateIdentity(identityId) }

        every { repo.revokeInWorkspace("dpk_FOREIGN00001", workspaceId, ApiKeyKind.ENDPOINT) } returns false
        service.revokeWorkspaceEndpointKey("dpk_FOREIGN00001", workspaceId, ownerId) shouldBe false
        verify(exactly = 1) { auditLogger.log(event = "auth.api_key.revoked", userId = any(), keyId = any(), details = any()) }
    }

    /**
     * #215 (owner ruling 2026-09-24, the record's `server_key.revoke` row): revoking a server key
     * is a super admin's verb — refused BEFORE the SQL: a workspace admin's attempt flips no row,
     * audits nothing and deactivates no identity.
     */
    @Test
    fun `revoking a server key is a super admin's - a workspace admin is refused and nothing is revoked`() {
        shouldThrow<RoleRequiredException> { service.revokeWorkspaceServerKey("dpk_SRV000000001", workspaceId, creator) }
            .code shouldBe AuthErrorCodes.ROLE_REQUIRED
        verify(exactly = 0) { repo.revokeInWorkspace(any(), any(), any()) }
        verify(exactly = 0) { userService.deactivateIdentity(any()) }

        every { repo.revokeInWorkspace("dpk_SRV000000001", workspaceId, ApiKeyKind.SERVER) } returns true
        every { repo.findById("dpk_SRV000000001") } returns record(id = "dpk_SRV000000001", kind = ApiKeyKind.SERVER).copy(isRevoked = true)
        service.revokeWorkspaceServerKey("dpk_SRV000000001", workspaceId, creator.copy(superAdmin = true)) shouldBe true
        verify { auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = "dpk_SRV000000001", details = any()) }
        verify(exactly = 1) { userService.deactivateIdentity(identityId) }
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
        ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), workspaceService, liveness, sealer)

    private fun owner(mustChange: Boolean = false) = member().copy(mustChangePassword = mustChange)

    private fun contextOf(role: WorkspaceRole) = WorkspaceContext(workspaceId, "acme", role)

    /** Captures one mint's insert and answers with the record the repository would return. */
    private fun captureMint(): io.mockk.CapturingSlot<Boolean> {
        val mintedAtLogin = slot<Boolean>()
        every { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(mintedAtLogin)) } answers {
            record(id = firstArg(), userId = secondArg(), name = arg(3), kind = ApiKeyKind.USER)
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
        verify { repo.insert(any(), ownerId, ownerId, "mcp/acme", any(), null, workspaceId, ApiKeyKind.USER, any(), true) }

        // Second call: the key exists, nothing happens.
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns minted
        mintingService.mintLoginKey(owner(), contextOf(WorkspaceRole.AUTHOR), LoginMethod.PWD) shouldBe null
    }

    /**
     * PK8/C2: the mint freezes NOTHING about the member's role — the row carries no role and no
     * scope; what the key may do is re-read on every request (the PK4 test above). So the mint is
     * the same act for every role.
     */
    @Test
    fun `the login mint freezes nothing about the role - the MCP key row carries no role for any member`() {
        captureMint()
        every { repo.findLiveUserKey(any(), any()) } returns null

        WorkspaceRole.entries.forEach { role ->
            withClue(role.wire) {
                mintingService.mintLoginKey(owner(), contextOf(role), LoginMethod.PWD)!!.role.shouldBeNull()
            }
        }
    }

    @Test
    fun `a user owing a password change gets no key`() {
        mintingService.mintLoginKey(owner(mustChange = true), contextOf(WorkspaceRole.AUTHOR), LoginMethod.PWD) shouldBe null
        verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * #210 — the flag belongs to the LOCAL credential: an OIDC session is not gated by it
     * (ForcedPasswordChangeInterceptor) and gets its key like any other entry.
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
        val key = record(id = "dpk_SEALED000001", userId = ownerId, kind = ApiKeyKind.USER)
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns key
        val fullKey = "dpk_SEALED000001.${"A".repeat(48)}"
        every { repo.openAndClearSealedSecret(key.id, ownerId) } returns sealer.seal(fullKey, key.id)

        mintingService.openOwnMcpKey(ownerId, workspaceId) shouldBe fullKey
        // The open is owner-scoped: the caller's id, never just the key's, reaches the clear.
        verify { repo.openAndClearSealedSecret(key.id, ownerId) }

        // #213: the first read destroyed the copy — the repository answers null from then on.
        every { repo.openAndClearSealedSecret(key.id, ownerId) } returns null
        mintingService.openOwnMcpKey(ownerId, workspaceId) shouldBe null
        every { repo.findLiveUserKey(ownerId, workspaceId) } returns null
        mintingService.openOwnMcpKey(ownerId, workspaceId) shouldBe null
    }
}
