package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Issues, validates and revokes API keys (auth.md §7). Keys are `dpk_<id>.<secret>`;
 * only the Argon2id hash of the *full* key is stored (§7.2), and the plaintext is
 * returned exactly once at creation (§7.4).
 *
 * ## Who a key acts as (#215 slice (b), record §3)
 * - the MCP (`user`) key acts as its MEMBER, with the member's role capped at author (PK4) —
 *   `workspace_admin` → author, a super admin's membership role capped the same way, a super admin
 *   with no membership → viewer — and NEVER as a super admin (B1);
 * - an `endpoint` or `server` key acts as its OWN `service` identity (PK5), created with it in one
 *   transaction (B4), holding its [KeyRole] (`api_caller`, `promotion_receiver`) and nothing else.
 *   Who created it does not matter when it is used (PK2).
 *
 * Validation (§7.3) reads the key record, the acting user and the pinned workspace through the
 * 60 s [AuthCache] (D13, B5): a revoked key, a deactivated member or identity, a demoted member
 * and a deactivated workspace all take effect within one TTL — immediately on the instance that
 * made the change.
 *
 * ## Cost of a rejected credential (AUTH-SEC-3 / AUTH-SEC-4)
 * A presented credential is shape-checked ([ApiKeyCredential.hasValidShape]) before
 * anything reads the cache or the database, and a *successful* Argon2id verification
 * is cached for the TTL ([AuthCache.verifiedSecret]) so a busy agent hashes once per
 * key per TTL rather than once per request.
 *
 * ## Why the class and every public method are `open`
 * [issue] and the revocations are multi-statement metadata writes carried by
 * `@Transactional("metadataTransactionManager")`; CGLIB must intercept every public method or a
 * final one runs on the proxy with null fields (`TransactionRollbackIntegrationTest`).
 */
open class ApiKeyService(
    private val apiKeyRepository: ApiKeyRepository,
    private val userService: UserService,
    private val authCache: AuthCache,
    private val auditLogger: AuditLogger,
    private val secretHasher: SecretHasher,
    private val workspaceService: WorkspaceService,
    /** 180 (D15) — the ONE liveness predicate, judged here for every key kind (§7.3 step 8). */
    private val principalLiveness: PrincipalLiveness,
    /**
     * The sealer for the login-minted key's plaintext (D16/§3.3). Optional so auth-only
     * test slices construct the service without the encryptor; the application always
     * wires it (`DomainConfiguration`). Without it a login mint still happens — the key
     * works — but nothing can offer its secret for copying, so a missing sealer is a WARN
     * at mint time, never a silent gap.
     */
    private val secretSealer: SecretSealer? = null,
) {
    private val log = LoggerFactory.getLogger(ApiKeyService::class.java)
    private val random = SecureRandom()

    /**
     * Issues a key of [kind] — `endpoint` or `server` — pinned to [workspaceId] (D3), created by
     * [issuer], acting as a NEW `service` identity named [name] (PK5). Key and identity are created
     * in ONE transaction (B4): a half-created pair cannot exist.
     *
     * Three guards, all server-side, in this order:
     * - the kind must be mintable on demand: the MCP key is minted at login only (D16);
     * - the ISSUER must hold the kind's create permission (PK7): `api_key.create` in the pinned
     *   workspace for an `endpoint` key (workspace admin, super admin), `server_key.create` for a
     *   `server` key (super admin) — and reach the workspace at all (D-R5's 404 otherwise);
     * - the creation limit (O3): the key's role holds nothing its creator lacks — every
     *   permission of the role but the two FENCED promotion-receiving ones, which no person holds
     *   and which exist only for the `promotion_receiver` key.
     */
    @Transactional("metadataTransactionManager")
    @Suppress("LongParameterList") // the issuance contract
    open fun issue(
        issuer: AuthenticatedPrincipal,
        name: String,
        workspaceId: UUID,
        expiresAt: Instant? = null,
        kind: ApiKeyKind,
    ): IssuedApiKey {
        if (kind !in ApiKeyKind.IDENTITY_KINDS) throw KeyKindNotMintableException(kind)
        val role = requireNotNull(KeyRole.forKind(kind)) { "an identity kind carries a key role" }
        val context = workspaceService.requireIssuancePermission(issuer, workspaceId, kind)
        requireWithinCreator(role, issuer, context)

        val keyId = "$KEY_PREFIX${randomBase32(ID_LEN)}"
        val secret = randomBase32(SECRET_LEN)
        val fullKey = "$keyId.$secret"
        val hash = secretHasher.hash(fullKey)

        val identity = userService.provisionIdentity(keyId, name)
        val record =
            apiKeyRepository.insert(
                id = keyId,
                userId = identity.id,
                createdBy = issuer.userId,
                name = name,
                keyHash = hash,
                expiresAt = expiresAt,
                workspaceId = workspaceId,
                kind = kind,
            )
        authCache.invalidateKey(keyId)
        auditLogger.log(
            event = "auth.api_key.created",
            userId = issuer.userId,
            keyId = keyId,
            details =
                mapOf(
                    "name" to name,
                    "role" to role.wire,
                    "identity_id" to identity.id.toString(),
                    "workspace_id" to workspaceId.toString(),
                    "kind" to kind.wire,
                ),
        )
        return IssuedApiKey(record = record, plaintext = fullKey)
    }

    /**
     * The creation limit (record O3, ruled 2026-09-23): a key's role must not hold a permission
     * its creator lacks. With the two pre-created roles it holds by construction for `api_caller`
     * (a workspace admin holds every one of its permissions); the two FENCED permissions of
     * `promotion_receiver` are held by NO person — they exist only for that key — so they are
     * outside the comparison, and the super-admin floor on `server_key.create` stands in for it.
     * Kept as a runtime check so a later key role cannot slip past it; `ApiKeyServiceTest` pins it.
     */
    private fun requireWithinCreator(
        role: KeyRole,
        issuer: AuthenticatedPrincipal,
        context: WorkspaceContext,
    ) {
        val creator = issuer.copy(workspace = context)
        val excess = (RolePermissions.of(role) - RolePermissions.FENCED).firstOrNull { !creator.holds(it) }
        if (excess != null) throw RoleRequiredException(excess, creator.heldRole, context.name)
    }

    /**
     * Validates a presented full key and resolves the principal (§7.3). Throws the specific
     * [AuthException] for each rejection so the entry point emits the exact §13.7 code
     * (`auth.api_key.invalid` / `auth.api_key.expired` / `auth.principal_deactivated` /
     * `auth.key_workspace_inactive`).
     *
     * The principal is the user the key ACTS AS ([ApiKey.userId]): the member for the MCP key,
     * the key's identity otherwise. `superAdmin` is FALSE for every key (B1): no key resolves an
     * instance permission, whoever minted it.
     */
    open fun validate(presentedKey: String): AuthenticatedPrincipal {
        val record = verifiedRecord(presentedKey)
        val actor = liveActor(record)
        return AuthenticatedPrincipal(
            userId = actor.id,
            email = actor.email,
            displayName = actor.displayName,
            authMethod = AuthMethod.API_KEY,
            keyId = record.id,
            // D3: the key's pinned workspace IS the context — resolved at validation,
            // no per-request switch exists (design §5.2).
            workspaceName = record.workspaceName,
            workspace = if (record.kind == ApiKeyKind.USER) mcpKeyContext(record, actor) else identityContext(record),
            // §7.7 — what the credential IS travels with it, so every downstream gate reads one
            // answer rather than re-deriving it.
            keyKind = record.kind,
            superAdmin = false,
            keyRole = record.role,
        )
    }

    /**
     * The MCP key's context (PK4): its member's role in the pinned workspace, capped at author —
     * re-read per request through the cache, so a role change takes effect within one TTL (B5).
     * A super admin with an explicit membership is capped the same way; one with NO membership
     * acts as a viewer there (marked implicit, so their reads keep the D-R8 audit). A member with
     * no membership at all lands on the viewer floor — the totality branch: since #200 removing a
     * member revokes the key in the same act, so a live key reaching here is a defect, and the
     * floor keeps even that to reads.
     */
    private fun mcpKeyContext(
        record: ApiKey,
        member: User,
    ): WorkspaceContext {
        val role = workspaceService.activeRoleIn(member.id, record.workspaceId)
        return when {
            role != null -> WorkspaceContext(record.workspaceId, record.workspaceName, role.cappedAtAuthor())
            member.isAdmin -> WorkspaceContext(record.workspaceId, record.workspaceName, WorkspaceRole.VIEWER, implicit = true)
            else -> WorkspaceContext(record.workspaceId, record.workspaceName, WorkspaceRole.VIEWER)
        }
    }

    /**
     * An identity-acting key's context: its pinned workspace, where its KEY ROLE is judged. The
     * member role here is the floor and is never consulted — `AuthenticatedPrincipal.holds` and
     * `ScopeMatrix.allowed` answer from the key role alone.
     */
    private fun identityContext(record: ApiKey): WorkspaceContext =
        WorkspaceContext(record.workspaceId, record.workspaceName, WorkspaceRole.VIEWER)

    /**
     * Validates a presented key as the promotion peer's credential (§7.7, versioning §10.6):
     * the same shape gate, record read, revocation/expiry re-check, Argon2id verify and
     * liveness predicate [validate] applies — the IDENTITY's liveness and the pinned workspace's
     * (A2), never the creator's (PK2) — and then the kind, which is the whole point.
     *
     * A key of any other kind is refused with the SAME [ApiKeyInvalidException] a wrong key
     * gets, because `PromotionServerKeyFilter` answers every refusal with one code: a caller
     * must not be able to tell "your key is the wrong kind" from "your key is wrong", which
     * would turn the promotion route into an oracle for classifying stolen keys.
     *
     * Returns the key and the identity it acts as (record C4): received versions and the audit
     * row are attributed to that identity, not to the System actor.
     */
    open fun validateServerKey(presentedKey: String): ValidatedServerKey {
        val record = verifiedRecord(presentedKey)
        if (!record.isServerKey) throw ApiKeyInvalidException()
        return ValidatedServerKey(key = record, identity = liveActor(record))
    }

    /**
     * The shared half of every validation path (§7.3 steps 1-7): shape, record, revocation,
     * expiry, secret. Extracted so [validate] and [validateServerKey] cannot drift on any of
     * them — a second, laxer path for the promotion credential is exactly the §13 checklist
     * item the Bearer form already has to answer for.
     */
    private fun verifiedRecord(presentedKey: String): ApiKey {
        // Shape gate FIRST — before any cache or database touch (AUTH-SEC-4).
        if (!ApiKeyCredential.hasValidShape(presentedKey)) throw ApiKeyInvalidException("Malformed API key")
        val record = usableRecord(presentedKey.substringBefore('.'))
        verifySecret(record, presentedKey)
        return record
    }

    /** Loads the key record and applies the D13 revocation + expiry re-checks (§7.3 steps 3-5). */
    private fun usableRecord(keyId: String): ApiKey {
        val record = authCache.keyRecord(keyId) { apiKeyRepository.findById(it) } ?: throw ApiKeyInvalidException()
        if (record.isRevoked) throw ApiKeyInvalidException()
        ensureNotExpired(record)
        return record
    }

    private fun ensureNotExpired(record: ApiKey) {
        if (record.expiresAt != null && record.expiresAt.isBefore(Instant.now())) throw ApiKeyExpiredException()
    }

    /**
     * Argon2id verify of the full key against the stored hash (§7.3 step 6), through
     * the per-key outcome cache so the hash cost is paid once per TTL (AUTH-SEC-3).
     */
    private fun verifySecret(
        record: ApiKey,
        presentedKey: String,
    ) {
        val verified =
            authCache.verifiedSecret(record.id, presentedKey) {
                secretHasher.verify(record.keyHash, presentedKey)
            }
        if (!verified) throw ApiKeyInvalidException()
    }

    /**
     * §7.3 step 8 — the user the key ACTS AS ([ApiKey.userId]: the member, or the key's identity),
     * and the D13/D15/A2 liveness re-check through [PrincipalLiveness]: a deactivated member or
     * identity is `auth.principal_deactivated`, a deactivated pin `auth.key_workspace_inactive`
     * (the 404 rule), both within the cache TTL. A row that is GONE is not a principal that was
     * deactivated — that stays the plain invalid-key answer. The key's own liveness (revoked,
     * expired) was judged by [usableRecord] before this.
     */
    private fun liveActor(record: ApiKey): User {
        val actor = userService.snapshot(record.userId) ?: throw ApiKeyInvalidException()
        principalLiveness.require(actor.id, PrincipalLiveness.Pin(record.workspaceId, record.workspaceName))
        return actor
    }

    /**
     * Revokes a key the caller CREATED — the member's own MCP key, or a key they minted — evicting
     * the local cache immediately (§11.4). An identity-acting key's identity is deactivated in the
     * same transaction (record §3.3).
     */
    @Transactional("metadataTransactionManager")
    open fun revoke(
        keyId: String,
        ownerId: UUID,
    ): Boolean {
        val revoked = apiKeyRepository.revoke(keyId, ownerId) ?: return false
        retire(revoked)
        auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = keyId)
        return true
    }

    /**
     * Revokes an `endpoint` key of [workspaceId], whoever created it — the `/api-keys` page's
     * delete (D17, `api_key.revoke`). The caller's ROLE was judged at the route; the kind and
     * workspace predicates in SQL are what keep this from ever touching a user's MCP key or
     * another workspace's. Its identity is deactivated in the same transaction.
     */
    @Transactional("metadataTransactionManager")
    open fun revokeWorkspaceEndpointKey(
        keyId: String,
        workspaceId: UUID,
        actorId: UUID,
    ): Boolean = revokeInWorkspace(keyId, workspaceId, ApiKeyKind.ENDPOINT, actorId)

    /**
     * Revokes a `server` key of [workspaceId] — the `/api-keys` page's delete extended to the
     * whole table it renders (#191 functional note, D17/D18). The same rails as the endpoint
     * twin: the kind and workspace predicates in SQL keep this off a user's MCP key and off
     * every other workspace's server key.
     *
     * **`server_key.revoke`, a super admin's** (#215, owner ruling 2026-09-24 — the permissions
     * record's row): a server key is the promotion receiver's whole credential and
     * only a super admin mints one (D18), so only a super admin ends one. The check is HERE, not
     * at the surface, for the reason [issue]'s floor is: every caller inherits it. The page
     * draws the row's Delete only for a principal who passes it.
     */
    @Transactional("metadataTransactionManager")
    open fun revokeWorkspaceServerKey(
        keyId: String,
        workspaceId: UUID,
        actor: AuthenticatedPrincipal,
    ): Boolean {
        if (!actor.holds(Permission.SERVER_KEY_REVOKE)) {
            throw RoleRequiredException(Permission.SERVER_KEY_REVOKE, actor.heldRole, actor.workspace?.name)
        }
        return revokeInWorkspace(keyId, workspaceId, ApiKeyKind.SERVER, actor.userId)
    }

    private fun revokeInWorkspace(
        keyId: String,
        workspaceId: UUID,
        kind: ApiKeyKind,
        actorId: UUID,
    ): Boolean {
        if (!apiKeyRepository.revokeInWorkspace(keyId, workspaceId, kind)) return false
        apiKeyRepository.findById(keyId)?.let(::retire)
        auditLogger.log(
            event = "auth.api_key.revoked",
            userId = actorId,
            keyId = keyId,
            details = mapOf("workspace_id" to workspaceId.toString(), "kind" to kind.wire),
        )
        return true
    }

    /** A revoked key's aftermath: its cache entry goes, and an identity-acting key's identity is deactivated. */
    private fun retire(revoked: ApiKey) {
        authCache.invalidateKey(revoked.id)
        if (revoked.kind in ApiKeyKind.IDENTITY_KINDS) userService.deactivateIdentity(revoked.userId)
    }

    /**
     * **The login mint (D16, §3.3)** — called by [WorkspaceService.workspaceForLogin] and the
     * workspace switch through the `McpKeyMint` port, never from a request surface. If the
     * user holds no live `user` key in [context]'s workspace, mint one:
     *
     * - it acts as [user] — `user_id = created_by` — and carries no role of its own: what it may
     *   do is the member's role in the pinned workspace, capped at author, re-read per request
     *   ([validate], PK4). Nothing about the member's role is frozen into the row (PK8);
     * - no expiry, name `mcp/<workspace>`;
     * - `minted_at_login = TRUE`, and the plaintext SEALED into `secret_sealed` so the top
     *   bar can offer Copy — ONCE (#213: the first open destroys the sealed copy in the same
     *   statement and the key is hash-only from then on). The plaintext is never returned to
     *   anyone at mint time, because there is no screen in a login redirect.
     *
     * A user who owes a forced password change (§5A.4) gets NO key: the gate holds every
     * governed route until the change, and a credential minted into that state would start
     * life reachable only by a session that cannot use it. The first login or switch AFTER
     * the change mints it.
     *
     * Idempotent under concurrent logins by the V31 partial unique index, not by the
     * existence check: a lost race is one `DuplicateKeyException`, re-read as "the other
     * login minted it" — the answer the check would have given a microsecond later.
     *
     * Returns the new record, or null when no mint happened (key exists, password change
     * owed). The plaintext deliberately does NOT leave this method.
     */
    open fun mintLoginKey(
        user: User,
        context: WorkspaceContext,
        loginMethod: LoginMethod,
    ): ApiKey? {
        // The must-change flag belongs to the LOCAL credential (§5A.4): a password session is
        // gated until the change and gets no key; an OIDC session is not gated — the
        // interceptor lets it through — and gets its key like any other entry. A Google user
        // whose bootstrap-seeded local password was never changed spent four sign-ins keyless
        // on datapipelines.co (2026-09-22) because this line did not make the distinction.
        if (loginMethod != LoginMethod.OIDC && user.mustChangePassword) return null
        if (apiKeyRepository.findLiveUserKey(user.id, context.id) != null) return null

        val keyId = "$KEY_PREFIX${randomBase32(ID_LEN)}"
        val secret = randomBase32(SECRET_LEN)
        val fullKey = "$keyId.$secret"
        val sealed =
            secretSealer?.seal(fullKey, keyId)
                ?: run {
                    log.warn("No SecretSealer wired: the login-minted key {} will have no copyable secret", keyId)
                    null
                }
        val record =
            try {
                apiKeyRepository.insert(
                    id = keyId,
                    userId = user.id,
                    createdBy = user.id,
                    name = loginKeyName(context.name),
                    keyHash = secretHasher.hash(fullKey),
                    expiresAt = null,
                    workspaceId = context.id,
                    kind = ApiKeyKind.USER,
                    secretSealed = sealed,
                    mintedAtLogin = true,
                )
            } catch (_: org.springframework.dao.DuplicateKeyException) {
                // The unique index is the arbiter: a concurrent login minted first.
                return null
            }
        auditLogger.log(
            event = "auth.api_key.created",
            userId = user.id,
            keyId = keyId,
            details =
                mapOf(
                    "name" to record.name,
                    "workspace_id" to context.id.toString(),
                    "kind" to ApiKeyKind.USER.wire,
                    "minted_at_login" to true,
                ),
        )
        return record
    }

    /**
     * The caller's own MCP key's plaintext — the top bar's Copy, served ONCE (#213 — D16
     * amended 2026-09-23: the key is copyable once; the open destroys the copyable secret in
     * the same statement, so the key is hash-only from the first read on). Null when the key
     * is not the caller's own live `user` key in [workspaceId], when its copy was already
     * read, or when it predates R3 and never carried one — the bar then shows "delete and
     * sign in again" instead. Nothing the server holds can reveal a key that has been read.
     *
     * Not cached and deliberately not routed through [AuthCache]: a copy click is rare, and
     * a cached copyable secret would be exactly the recoverable-at-rest copy show-once removes.
     */
    open fun openOwnMcpKey(
        userId: UUID,
        workspaceId: UUID,
    ): String? {
        val record = apiKeyRepository.findLiveUserKey(userId, workspaceId) ?: return null
        val sealed = apiKeyRepository.openAndClearSealedSecret(record.id, userId) ?: return null
        val sealer = secretSealer ?: return null
        return sealer.open(sealed, record.id)
    }

    private fun randomBase32(len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(BASE32[random.nextInt(BASE32.length)]) }
        return sb.toString()
    }

    companion object {
        private const val KEY_PREFIX = ApiKeyCredential.KEY_PREFIX
        private const val ID_LEN = 12
        private const val SECRET_LEN = 48

        /** The login-minted key's name (D16): which workspace this MCP key belongs to. */
        fun loginKeyName(workspaceName: String): String = "mcp/$workspaceName"

        // RFC 4648 base32 alphabet (no padding, unambiguous, scanner-friendly).
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
