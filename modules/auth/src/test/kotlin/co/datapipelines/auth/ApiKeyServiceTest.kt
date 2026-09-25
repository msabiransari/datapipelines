package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
 * API keys (auth.md §7) since keys v2 (#233): Argon2id issue/validate, and WHO a key acts as —
 * EVERY kind its own `service` identity (A13), holding the role chosen at creation (A14) under
 * the subset rule — revocation deactivating the identity, no login mint (A15), and never a
 * super admin (B1).
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
     * Relaxed, then STUBBED for what issuance asks: the default creator is a workspace admin in
     * the pinned workspace; a test that means a different role says so in its own context stub.
     */
    private val workspaceService =
        mockk<WorkspaceService>(relaxed = true) {
            every { isActive(any()) } returns true
            every { requireIssuancePermission(any(), any(), any()) } answers {
                WorkspaceContext(secondArg(), "acme", WorkspaceRole.WORKSPACE_ADMIN)
            }
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
     * Stubs `insert` for an identity-acting [kind] to echo back the row it was given (real hash).
     * The KIND is matched exactly — a test that meant to mint one kind and got the other fails
     * loudly.
     */
    private fun echoIdentityInsert(kind: ApiKeyKind) {
        val hash = slot<String>()
        every {
            repo.insert(any(), any(), ownerId, any(), capture(hash), any(), any(), any(), kind)
        } answers {
            record(
                id = firstArg(),
                userId = secondArg(),
                name = arg(3),
                hash = hash.captured,
                role = arg(5),
                expiresAt = arg(6),
                kind = kind,
            )
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
        role: KeyRole? = KeyRole.forKind(kind),
        createdBy: UUID = ownerId,
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
        role = role,
        createdBy = createdBy,
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

    // ------------------------------------------------------------------ issuance (A.1, B4)

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
                ) {
                    repo.insert(issued.record.id, identityId, ownerId, "ci", any(), KeyRole.API_CALLER, null, workspaceId, ApiKeyKind.ENDPOINT)
                }
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

    /**
     * An `mcp` key's role is CHOSEN at creation (keys v2 A13) and REQUIRED: the key carries it in
     * `api_keys.role`, its identity acts with it, and no membership is read at request time —
     * no cap, no derivation, no freshness rule (A13 retires PK4/C2/C3).
     */
    @Test
    fun `an mcp key is created with the role its creator chose, held by its own identity`() {
        echoIdentityInsert(ApiKeyKind.MCP)
        val issued = service.issue(creator, "agent", workspaceId, kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR)
        every { repo.findById(issued.record.id) } returns issued.record

        assertAll(
            { issued.record.role shouldBe KeyRole.AUTHOR },
            { issued.record.userId shouldBe identityId },
            { issued.record.createdBy shouldBe ownerId },
            {
                verify {
                    repo.insert(issued.record.id, identityId, ownerId, "agent", any(), KeyRole.AUTHOR, null, workspaceId, ApiKeyKind.MCP)
                }
            },
        )
        val principal = service.validate(issued.plaintext)
        assertAll(
            { principal.keyKind shouldBe ApiKeyKind.MCP },
            { principal.keyRole shouldBe KeyRole.AUTHOR },
            { principal.userId shouldBe identityId },
            { principal.holds(Permission.PIPELINE_CREATE) shouldBe true },
            { principal.holds(Permission.WORKSPACE_MEMBERS_MANAGE) shouldBe false },
            { principal.isSuperAdmin shouldBe false },
        )
    }

    @Test
    fun `an mcp key without a member role is refused before anything is written`() {
        shouldThrow<IllegalArgumentException> { service.issue(creator, "x", workspaceId, kind = ApiKeyKind.MCP, role = null) }
        verify(exactly = 0) { userService.provisionIdentity(any(), any()) }
        verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /** Gate 7: the per-kind create permission is asked BEFORE anything is written. */
    @Test
    fun `a creator refused the kind's create permission gets no identity and no key`() {
        every { workspaceService.requireIssuancePermission(any(), any(), ApiKeyKind.SERVER) } throws
            RoleRequiredException(Permission.SERVER_KEY_CREATE, "workspace_admin", "acme")

        val refusal = shouldThrow<RoleRequiredException> { service.issue(creator, "uat receiver", workspaceId, kind = ApiKeyKind.SERVER) }

        refusal.details["required"] shouldBe Permission.SERVER_KEY_CREATE.wire
        verify(exactly = 0) { userService.provisionIdentity(any(), any()) }
        verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
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

    // ------------------------------------------------------------------ the subset rule (keys v2 A14/B2)

    /**
     * B2's matrix, over every ordered pair of MEMBER roles as the CREATOR and as the REQUESTED
     * key role: an author may mint `author` only, a promoter `promoter` only, a workspace admin
     * any of the three, a super admin any — a viewer holds no `mcp_key.create` at all, so no
     * pair starts there (and the empty creator set makes every cell refused anyway).
     */
    @Test
    fun `the subset rule - a creator may give a key only a role whose permissions the creator holds`() {
        val creatorRoles =
            listOf(
                WorkspaceRole.AUTHOR to setOf(KeyRole.AUTHOR),
                WorkspaceRole.PROMOTER to setOf(KeyRole.PROMOTER),
                WorkspaceRole.WORKSPACE_ADMIN to setOf(KeyRole.AUTHOR, KeyRole.PROMOTER, KeyRole.WORKSPACE_ADMIN),
            )
        creatorRoles.forEach { (creatorRole, offerable) ->
            every { workspaceService.requireIssuancePermission(any(), any(), ApiKeyKind.MCP) } returns
                WorkspaceContext(workspaceId, "acme", creatorRole)
            KeyRole.MEMBER_KEY_ROLES.forEach { requested ->
                echoIdentityInsert(ApiKeyKind.MCP)
                withClue("$creatorRole minting a ${requested.wire} key") {
                    if (requested in offerable) {
                        service.issue(creator, "agent", workspaceId, kind = ApiKeyKind.MCP, role = requested)
                    } else {
                        val refusal =
                            shouldThrow<KeyRoleNotOfferableException> {
                                service.issue(creator, "agent", workspaceId, kind = ApiKeyKind.MCP, role = requested)
                            }
                        // A14's refusal shape: `auth.role_required` naming the ROLE that was
                        // asked for and the role the creator was judged as.
                        refusal.code shouldBe AuthErrorCodes.ROLE_REQUIRED
                        refusal.details["required"] shouldBe requested.wire
                        refusal.details["held"] shouldBe creatorRole.wire
                    }
                }
            }
        }
    }

    /** A super admin may mint any of the three (A14: they hold every permission). */
    @Test
    fun `a super admin may mint any member role`() {
        every { workspaceService.requireIssuancePermission(any(), any(), ApiKeyKind.MCP) } returns
            WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null)
        KeyRole.MEMBER_KEY_ROLES.forEach { requested ->
            echoIdentityInsert(ApiKeyKind.MCP)
            service.issue(creator.copy(superAdmin = true), "agent", workspaceId, kind = ApiKeyKind.MCP, role = requested)
        }
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

    // ------------------------------------------------------------------ validation: the MCP key (A13, B1)

    /**
     * Keys v2 A13/B1: the MCP key acts as its OWN identity with its OWN member role — no
     * membership is read, nothing is capped, and a member's role change does not flow to the key
     * (C2 retired). No key is ever a super admin (B1): neither the principal's flag nor its
     * context's is set.
     */
    @Test
    fun `an mcp key validates against its OWN role - its owner's role is never consulted`() {
        val key = mcpKey(KeyRole.AUTHOR)

        // The owner is demoted, deactivated, gone — the key is unaffected (no derivation).
        every { userService.snapshot(identityId) } returns identity()

        val principal = service.validate(key)
        assertAll(
            { principal.userId shouldBe identityId },
            { principal.keyKind shouldBe ApiKeyKind.MCP },
            { principal.keyRole shouldBe KeyRole.AUTHOR },
            { principal.isSuperAdmin shouldBe false },
            { principal.workspace?.superAdmin shouldBe false },
            { RolePermissions.INSTANCE.forEach { principal.holds(it) shouldBe false } },
            // The author column is the whole answer — nothing MORE than an author session has.
            { principal.holds(Permission.PIPELINE_CREATE) shouldBe true },
            { principal.holds(Permission.EXECUTION_READ_ALL) shouldBe false },
        )
    }

    @Test
    fun `a workspace-admin mcp key holds the workspace-admin column - the cap is gone (A13)`() {
        val key = mcpKey(KeyRole.WORKSPACE_ADMIN)

        val principal = service.validate(key)
        assertAll(
            { principal.keyRole shouldBe KeyRole.WORKSPACE_ADMIN },
            { principal.holds(Permission.EXECUTION_READ_ALL) shouldBe true },
            { principal.holds(Permission.WORKSPACE_MEMBERS_MANAGE) shouldBe true },
        )
    }

    @Test
    fun `a revoked MCP key is refused before anything else about it is judged`() {
        val key = mcpKey(KeyRole.AUTHOR)
        every { repo.findById("dpk_MCPKEYAAAAAA") } answers
            {
                record(id = firstArg(), userId = identityId, role = KeyRole.AUTHOR, kind = ApiKeyKind.MCP).copy(isRevoked = true)
            }

        shouldThrow<ApiKeyInvalidException> { service.validate(key) }
    }

    private fun mcpKey(role: KeyRole): String {
        val plaintext = "dpk_MCPKEYAAAAAA.${"B".repeat(48)}"
        val row =
            record(
                id = "dpk_MCPKEYAAAAAA",
                userId = identityId,
                name = "agent",
                hash = Argon2SecretHasher().hash(plaintext),
                role = role,
                kind = ApiKeyKind.MCP,
            )
        every { repo.findById(row.id) } returns row
        return plaintext
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
        val mcp = mcpKey(KeyRole.AUTHOR)

        val validated = service.validateServerKey(server.plaintext)
        validated.key.id shouldBe server.record.id
        validated.identity.id shouldBe identityId
        // A perfectly valid MCP key is not a promotion credential — refused with the same
        // exception an unknown key gets, so the route cannot classify a stolen key.
        shouldThrow<ApiKeyInvalidException> { service.validateServerKey(mcp) }
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

    // ------------------------------------------------------------------ revocation (A14/A17)

    @Test
    fun `revoking own deactivates the identity - a key that is not the caller's is simply not theirs`() {
        every { repo.revoke("dpk_EP0000000001", ownerId) } returns record(id = "dpk_EP0000000001").copy(isRevoked = true)
        service.revokeOwn("dpk_EP0000000001", ownerId) shouldBe true
        verify(exactly = 1) { userService.deactivateIdentity(identityId) }

        every { repo.revoke("dpk_NOTMINE00001", ownerId) } returns null
        service.revokeOwn("dpk_NOTMINE00001", ownerId) shouldBe false
        verify(exactly = 1) { userService.deactivateIdentity(any()) }
    }

    /**
     * The ONE revocation verb behind both delete routes (A14): own → revokeOwn; a holder of
     * `api_key.revoke` → any key of the active workspace; a foreign key, or a permission the
     * caller lacks, answers silently — no existence disclosure through the delete.
     */
    @Test
    fun `revokeAs - own key revokes, a foreign key without api_key-revoke stays silent, and a server key needs its floor`() {
        // Own key (a person's own mcp key): revoked, identity deactivated.
        val own = record(id = "dpk_OWNSRV00001", createdBy = ownerId, kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR)
        every { repo.findById(own.id) } returns own
        every { repo.revoke(own.id, ownerId) } returns own.copy(isRevoked = true)
        service.revokeAs(creator, own.id) shouldBe true
        verify(exactly = 1) { userService.deactivateIdentity(identityId) }

        // A foreign key and no api_key.revoke: silent false, nothing revoked.
        val foreign = record(id = "dpk_ADMINKEY001", createdBy = UUID.randomUUID(), kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR)
        every { repo.findById(foreign.id) } returns foreign
        service.revokeAs(creator.copy(workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR)), foreign.id) shouldBe false
        verify(exactly = 0) { repo.revokeInWorkspace(any(), any(), any()) }

        // api_key.revoke: any live key of the workspace, identity deactivated, audited.
        every { repo.revokeInWorkspace(foreign.id, workspaceId, ApiKeyKind.MCP) } returns true
        service.revokeAs(creator, foreign.id) shouldBe true
        verify(exactly = 1) { repo.revokeInWorkspace(foreign.id, workspaceId, ApiKeyKind.MCP) }
        verify(exactly = 2) { userService.deactivateIdentity(identityId) }

        // A server key needs server_key.revoke: refused before the SQL.
        val server = record(id = "dpk_SRVDROP0001", createdBy = UUID.randomUUID(), kind = ApiKeyKind.SERVER, role = KeyRole.PROMOTION_RECEIVER)
        every { repo.findById(server.id) } returns server
        shouldThrow<RoleRequiredException> { service.revokeAs(creator, server.id) }
            .details["required"] shouldBe Permission.SERVER_KEY_REVOKE.wire
        verify(exactly = 0) { repo.revokeInWorkspace(server.id, any(), any()) }
        every { repo.revokeInWorkspace(server.id, workspaceId, ApiKeyKind.SERVER) } returns true
        service.revokeAs(creator.copy(superAdmin = true), server.id) shouldBe true
        verify(exactly = 3) { userService.deactivateIdentity(identityId) }
    }

    /**
     * Keys v2 A17/B6: member removal revokes EVERY live key the member created here, and each
     * revoked key's identity is deactivated — the mechanics behind [WorkspaceService.removeMember].
     */
    @Test
    fun `revokeCreatedKeys revokes every key the member created here and deactivates each identity`() {
        val first = record(id = "dpk_CREATED001", kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR)
        val second = record(id = "dpk_CREATED002", kind = ApiKeyKind.ENDPOINT, role = KeyRole.API_CALLER)
        every { repo.revokeLiveByCreator(ownerId, workspaceId) } returns listOf(first.id, second.id)
        every { repo.findById(first.id) } returns first
        every { repo.findById(second.id) } returns second

        service.revokeCreatedKeys(ownerId, workspaceId) shouldBe listOf(first.id, second.id)
        verify(exactly = 2) { userService.deactivateIdentity(identityId) }
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
        verify(exactly = 1) { userService.deactivateIdentity(any()) }
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

        every { repo.revokeInWorkspace("dpk_SRV000000001", workspaceId, ApiKeyKind.SERVER) } returns true
        every { repo.findById("dpk_SRV000000001") } returns
            record(id = "dpk_SRV000000001", kind = ApiKeyKind.SERVER, role = KeyRole.PROMOTION_RECEIVER).copy(isRevoked = true)
        service.revokeWorkspaceServerKey("dpk_SRV000000001", workspaceId, creator.copy(superAdmin = true)) shouldBe true
        verify { auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = "dpk_SRV000000001", details = any()) }
    }

    // ------------------------------------------------------- A15: no key is minted at login

    /** A REAL in-memory sealer, never a strict mock (the "must be CALLED" lesson). */
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
    private val serviceWithSealer =
        ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), workspaceService, liveness, sealer)

    @Test
    fun `the show-once copy opens only the creator's key's sealed secret - exactly once`() {
        val key = record(id = "dpk_SEALED000001", createdBy = ownerId, kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR)
        val fullKey = "dpk_SEALED000001.${"A".repeat(48)}"
        every { repo.openAndClearSealedSecret(key.id, ownerId) } returns sealer.seal(fullKey, key.id)

        serviceWithSealer.openSealedSecret(key.id, ownerId) shouldBe fullKey
        // The open is creator-scoped: the caller's id, never just the key's, reaches the clear.
        verify { repo.openAndClearSealedSecret(key.id, ownerId) }

        // #213: the first read destroyed the copy — the repository answers null from then on.
        every { repo.openAndClearSealedSecret(key.id, ownerId) } returns null
        serviceWithSealer.openSealedSecret(key.id, ownerId) shouldBe null
    }
}
