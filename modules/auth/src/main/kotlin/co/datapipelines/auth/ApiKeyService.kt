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
 * ## Who a key acts as (keys v2, #233 A13/A14 — record §3)
 * EVERY kind acts as its OWN `service` identity (PK5), created with it in one transaction (B4).
 * The identity holds the key's [KeyRole] in the key's workspace exactly as a member holds a
 * membership role — for an `mcp` key that is one of the MEMBER roles (`author`, `promoter`,
 * `workspace_admin`, A15: never viewer, B1: never super admin), for an `endpoint` key
 * `api_caller`, for a `server` key `promotion_receiver`. Who created a key does not matter when
 * it is used (PK2), and nothing is derived from a membership at request time: no cap (PK4
 * retired), no freshness rule (C2 retired).
 *
 * ## What a creator may mint (the subset rule, A14)
 * A creator may give a key any role whose permission set (the one [RolePermissions] table) is a
 * subset of the creator's own permissions in that workspace. The check lives HERE, not only in
 * the dialog, so a forged request for a role the creator does not hold is refused; the offerable
 * set itself is [RolePermissions.offerable].
 *
 * Validation (§7.3) reads the key record, the acting identity and the pinned workspace through
 * the 60 s [AuthCache] (D13, B5): a revoked key, a deactivated identity, and a deactivated
 * workspace all take effect within one TTL — immediately on the instance that made the change.
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
     * The sealer for a migrated login key's stored plaintext (V31 leftovers; keys v2 A2 — no
     * NEW key is ever minted with a sealed copy). Optional so auth-only test slices construct
     * the service without the encryptor; the application always wires it (`AuthConfiguration`).
     */
    private val secretSealer: SecretSealer? = null,
) {
    private val log = LoggerFactory.getLogger(ApiKeyService::class.java)
    private val random = SecureRandom()

    /**
     * Issues a key of [kind] pinned to [workspaceId] (D3), created by [issuer], acting as a NEW
     * `service` identity named [name] (PK5). Key and identity are created in ONE transaction
     * (B4): a half-created pair cannot exist.
     *
     * The ROLE (A13/A14): an `mcp` key carries the requested member [role] — required, one of
     * [KeyRole.MEMBER_KEY_ROLES]; an `endpoint` or `server` key carries its kind's fixed role
     * ([KeyRole.forKind]) and passing anything else is refused rather than quietly corrected.
     *
     * Guards, all server-side, in this order:
     * - the ISSUER must reach the pinned workspace at all (D-R5's 404 otherwise) and hold the
     *   kind's create permission (PK7 as amended by A14): `mcp_key.create` for an `mcp` key
     *   (author, promoter, workspace admin), `api_key.create` for an `endpoint` key (workspace
     *   admin, super admin), `server_key.create` for a `server` key (super admin);
     * - the SUBSET RULE (A14): the requested role's permission set must be a subset of the
     *   creator's own permissions in that workspace — [RolePermissions.offerable] is the
     *   dialog's view of the same predicate. The FENCED promotion-receiving permissions stay
     *   outside the comparison for the `promotion_receiver` role: no person holds them, and the
     *   super-admin floor on `server_key.create` stands in for them.
     */
    @Transactional("metadataTransactionManager")
    @Suppress("LongParameterList") // the issuance contract
    open fun issue(
        issuer: AuthenticatedPrincipal,
        name: String,
        workspaceId: UUID,
        expiresAt: Instant? = null,
        kind: ApiKeyKind,
        role: KeyRole? = null,
    ): IssuedApiKey =
        issueInternal(issuer, name, workspaceId, expiresAt, kind, role) {
            val keyId = "$KEY_PREFIX${randomBase32(ID_LEN)}"
            "$keyId.${randomBase32(SECRET_LEN)}"
        }

    /**
     * The issuance contract with the credential SUPPLIED (#224) — the bootstrap seeder's mint:
     * the demo workspace's public `api_caller` key is minted from the configured plaintext, so
     * the value on the demo-data page is the value that works. Every guard of [issue] runs
     * unchanged (the issuer's create permission in the pinned workspace, the subset rule); the
     * only difference is where the credential comes from, and that the supplied value must
     * already pass the §7.1 shape gate — a malformed configured key is a configuration error and
     * refuses the boot, not a first failed login.
     *
     * The overload exists for the bootstrap actor alone (there is exactly one caller); it is a
     * companion to the random mint, not a second credential policy.
     *
     * @throws IllegalArgumentException when [plaintext] is not a well-formed `dpk_<id>.<secret>` credential.
     */
    @Transactional("metadataTransactionManager")
    @Suppress("LongParameterList") // the issuance contract
    open fun issue(
        issuer: AuthenticatedPrincipal,
        name: String,
        workspaceId: UUID,
        expiresAt: Instant?,
        kind: ApiKeyKind,
        role: KeyRole? = null,
        plaintext: String,
    ): IssuedApiKey {
        require(ApiKeyCredential.hasValidShape(plaintext)) {
            "The supplied key plaintext is not a well-formed dpk_<id>.<secret> credential (auth.md §7.1)."
        }
        return issueInternal(issuer, name, workspaceId, expiresAt, kind, role) { plaintext }
    }

    /** The shared mint: [credential] supplies the plaintext, randomly when the caller does not. */
    private fun issueInternal(
        issuer: AuthenticatedPrincipal,
        name: String,
        workspaceId: UUID,
        expiresAt: Instant?,
        kind: ApiKeyKind,
        requestedRole: KeyRole?,
        credential: () -> String,
    ): IssuedApiKey {
        val role = resolveRole(kind, requestedRole)
        val context = workspaceService.requireIssuancePermission(issuer, workspaceId, kind)
        requireWithinCreator(role, issuer, context)

        val fullKey = credential()
        val keyId = fullKey.substringBefore('.')
        val hash = secretHasher.hash(fullKey)

        // A18: a live key's name is unique in its workspace. Checked here so the create path
        // answers a catalogued conflict rather than the unique index's raw failure — the index
        // stays the arbiter of a lost race.
        if (apiKeyRepository.liveNameExists(workspaceId, name)) throw KeyNameTakenException(name, context.name)

        val identity = userService.provisionIdentity(keyId, name)
        val record =
            apiKeyRepository.insert(
                id = keyId,
                userId = identity.id,
                createdBy = issuer.userId,
                name = name,
                keyHash = hash,
                role = role,
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
     * The kind/role contract (keys v2 A13): an `mcp` key's role is the request's member role —
     * REQUIRED, because a live `mcp` key without a role is a row the database CHECK refuses; an
     * `endpoint`/`server` key's role is its kind's, fixed.
     */
    private fun resolveRole(
        kind: ApiKeyKind,
        requested: KeyRole?,
    ): KeyRole =
        when (kind) {
            ApiKeyKind.MCP -> {
                requested?.takeIf { it.isMemberKeyRole }
                    ?: throw IllegalArgumentException(
                        "An mcp key carries a member role — one of ${KeyRole.MEMBER_KEY_ROLES.map { it.wire }}.",
                    )
            }

            // A transport kind's role is its kind's: null means "the fixed one", anything else
            // is refused rather than quietly corrected (EndpointKeyService's rule, kept here).
            ApiKeyKind.ENDPOINT -> {
                when (requested) {
                    null, KeyRole.API_CALLER -> KeyRole.API_CALLER
                    else -> throw IllegalArgumentException("An endpoint key's role is api_caller.")
                }
            }

            ApiKeyKind.SERVER -> {
                when (requested) {
                    null, KeyRole.PROMOTION_RECEIVER -> KeyRole.PROMOTION_RECEIVER
                    else -> throw IllegalArgumentException("A server key's role is promotion_receiver.")
                }
            }
        }

    /**
     * The creation limit — the subset rule (keys v2 A14; record O3, ruled 2026-09-23): a key's
     * role must not hold a permission its creator lacks. For the MEMBER roles this IS the
     * subset rule, and the refusal names the ROLE that was asked for (A14's `required = <the
     * role>`); for the transport roles it holds by construction (`api_caller` — a workspace
     * admin holds every one of its permissions) or by the super-admin floor
     * (`promotion_receiver`'s two FENCED permissions are held by NO person and exist only for
     * that key, so they sit outside the comparison). Kept as a runtime check so a later key
     * role cannot slip past it; the subset-matrix test pins every ordered pair.
     */
    private fun requireWithinCreator(
        role: KeyRole,
        issuer: AuthenticatedPrincipal,
        context: WorkspaceContext,
    ) {
        val creator = issuer.copy(workspace = context)
        val memberRole = role.asMemberRole()
        if (memberRole != null) {
            // The subset rule, on the one predicate the dialog reads (B2): offerable set ∋ role.
            val creatorPermissions = creatorPermissions(creator)
            if (memberRole !in RolePermissions.offerable(creatorPermissions)) {
                throw KeyRoleNotOfferableException(role, creator.heldRole, context.name)
            }
            return
        }
        val excess = (RolePermissions.of(role) - RolePermissions.FENCED).firstOrNull { !creator.holds(it) }
        if (excess != null) throw RoleRequiredException(excess, creator.heldRole, context.name)
    }

    /** The creator's permission set in the target workspace — super admin = everything but the FENCED rows (D7). */
    private fun creatorPermissions(creator: AuthenticatedPrincipal): Set<Permission> =
        when {
            creator.isSuperAdmin -> RolePermissions.SUPER_ADMIN
            else -> creator.workspaceRole?.let { RolePermissions.of(it) } ?: emptySet()
        }

    /**
     * Validates a presented full key and resolves the principal (§7.3). Throws the specific
     * [AuthException] for each rejection so the entry point emits the exact §13.7 code
     * (`auth.api_key.invalid` / `auth.api_key.expired` / `auth.principal_deactivated` /
     * `auth.key_workspace_inactive`).
     *
     * The principal is the key's OWN identity ([ApiKey.userId]), for every kind (keys v2 A13);
     * its authority is the KEY's role ([ApiKey.role]) — nothing is read from a membership.
     * `superAdmin` is FALSE for every key (B1): no key resolves an instance permission,
     * whoever minted it.
     */
    open fun validate(presentedKey: String): AuthenticatedPrincipal {
        val record = verifiedRecord(presentedKey)
        val actor = liveActor(record)
        // The database CHECK makes a live key without a role impossible; refusing here keeps a
        // defect from becoming a principal that authorises by an empty column.
        val role =
            record.role
                ?: run {
                    log.error("event=api_key.role_missing key={}", record.id)
                    throw ApiKeyInvalidException()
                }
        return AuthenticatedPrincipal(
            userId = actor.id,
            email = actor.email,
            displayName = actor.displayName,
            authMethod = AuthMethod.API_KEY,
            keyId = record.id,
            // D3: the key's pinned workspace IS the context — resolved at validation,
            // no per-request switch exists (design §5.2).
            workspaceName = record.workspaceName,
            // The identity's context floor; the KEY ROLE below is the whole authority
            // (ScopeMatrix.allowed judges a key by its keyRole column alone).
            workspace = identityContext(record),
            // §7.7 — what the credential IS travels with it, so every downstream gate reads one
            // answer rather than re-deriving it.
            keyKind = record.kind,
            superAdmin = false,
            keyRole = role,
        )
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
     * §7.3 step 8 — the identity the key ACTS AS ([ApiKey.userId]), and the D13/D15/A2 liveness
     * re-check through [PrincipalLiveness]: a deactivated identity is
     * `auth.principal_deactivated`, a deactivated pin `auth.key_workspace_inactive`
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
     * Revokes a key the caller CREATED (`mcp_key.revoke_own` — keys v2 A14), evicting the local
     * cache immediately (§11.4) and deactivating the key's identity in the same transaction
     * (record §3.3). Null when no live key of theirs had that id — "already gone" is the
     * idempotent answer.
     */
    @Transactional("metadataTransactionManager")
    open fun revokeOwn(
        keyId: String,
        ownerId: UUID,
    ): Boolean {
        val revoked = apiKeyRepository.revoke(keyId, ownerId) ?: return false
        retire(revoked)
        auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = keyId)
        return true
    }

    /**
     * The ONE revocation verb behind both delete routes (keys v2 A14): a creator revokes a key
     * they created ([revokeOwn], `mcp_key.revoke_own`); a caller holding `api_key.revoke`
     * revokes ANY live key of the ACTIVE workspace, a `server` key additionally needing
     * `server_key.revoke` (asked here, so every caller inherits the floor).
     *
     * Non-disclosure: a key of ANOTHER workspace, and a foreign key asked for by a caller who
     * holds neither permission, answer `false` — the same silence the route's idempotent 204
     * already speaks, so a key id cannot be probed through the delete. Only the one case the
     * page already renders as a refusal — a non-super-admin asking for a server key they did
     * not create — throws.
     */
    @Transactional("metadataTransactionManager")
    open fun revokeAs(
        principal: AuthenticatedPrincipal,
        keyId: String,
    ): Boolean {
        val key = apiKeyRepository.findById(keyId) ?: return false
        val workspaceId = principal.workspace?.id ?: return false
        if (key.workspaceId != workspaceId) return false
        if (key.createdBy == principal.userId) return revokeOwn(keyId, principal.userId)
        if (!principal.holds(Permission.API_KEY_REVOKE)) return false
        if (key.kind == ApiKeyKind.SERVER && !principal.holds(Permission.SERVER_KEY_REVOKE)) {
            throw RoleRequiredException(Permission.SERVER_KEY_REVOKE, principal.heldRole, principal.workspace.name)
        }
        if (!apiKeyRepository.revokeInWorkspace(keyId, workspaceId, key.kind)) return false
        retire(key)
        auditLogger.log(
            event = "auth.api_key.revoked",
            userId = principal.userId,
            keyId = keyId,
            details = mapOf("workspace_id" to workspaceId.toString(), "kind" to key.kind.wire),
        )
        return true
    }

    /**
     * Revokes an `endpoint` key of [workspaceId], whoever created it — the `/api-keys` page's
     * delete (D17, `api_key.revoke`). The caller's ROLE was judged at the route; the kind and
     * workspace predicates in SQL are what keep this from ever touching another workspace's.
     * Its identity is deactivated in the same transaction.
     */
    @Transactional("metadataTransactionManager")
    open fun revokeWorkspaceEndpointKey(
        keyId: String,
        workspaceId: UUID,
        actorId: UUID,
    ): Boolean = revokeInWorkspace(keyId, workspaceId, ApiKeyKind.ENDPOINT, actorId)

    /**
     * Revokes a `server` key of [workspaceId] — the `/api-keys` page's delete extended to the
     * whole table it renders (#191 functional note, D17/D18).
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

    /**
     * Revokes EVERY live key [creatorId] created in [workspaceId] (keys v2 A17/B6 — the
     * member-removal safety, restated for created-by), deactivating each key's identity and
     * evicting each cache entry in the same transaction. Returns the revoked key ids; the
     * caller audits each with its own reason. Empty when the member created nothing live here.
     */
    @Transactional("metadataTransactionManager")
    open fun revokeCreatedKeys(
        creatorId: UUID,
        workspaceId: UUID,
    ): List<String> {
        val revokedIds = apiKeyRepository.revokeLiveByCreator(creatorId, workspaceId)
        revokedIds.forEach { id ->
            apiKeyRepository.findById(id)?.let(::retire)
            authCache.invalidateKey(id)
        }
        return revokedIds
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

    /** A revoked key's aftermath: its cache entry goes, and its identity is deactivated (record §3.3). */
    private fun retire(revoked: ApiKey) {
        authCache.invalidateKey(revoked.id)
        userService.deactivateIdentity(revoked.userId)
    }

    /**
     * The one-time read of a key's SEALED plaintext (keys v2: the sealed copies in flight are
     * the login-minted keys the V35 migration converted — their creators may read the copy
     * ONCE, #213 — after which the key is hash-only; no new key is ever minted with one).
     * Creator-scoped; null when the caller did not create the key, when its copy was already
     * read, or when it never carried one.
     *
     * Not cached and deliberately not routed through [AuthCache]: a copy click is rare, and
     * a cached copyable secret would be exactly the recoverable-at-rest copy show-once removes.
     */
    open fun openSealedSecret(
        keyId: String,
        createdBy: UUID,
    ): String? {
        val sealed = apiKeyRepository.openAndClearSealedSecret(keyId, createdBy) ?: return null
        val sealer = secretSealer ?: return null
        return sealer.open(sealed, keyId)
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

        // RFC 4648 base32 alphabet (no padding, unambiguous, scanner-friendly).
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
