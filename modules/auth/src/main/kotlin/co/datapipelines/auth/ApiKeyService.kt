package co.datapipelines.auth

import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Issues, validates and revokes API keys (auth.md §7). Keys are `dpk_<id>.<secret>`;
 * only the Argon2id hash of the *full* key is stored (§7.2), and the plaintext is
 * returned exactly once at creation (§7.4).
 *
 * Validation (§7.3) reads the key record and the owner snapshot through the 60s
 * [AuthCache] (D13): a revoked key or a deactivated owner is rejected within one
 * TTL — immediately on the instance that performed the mutation.
 *
 * ## Cost of a rejected credential (AUTH-SEC-3 / AUTH-SEC-4)
 * A presented credential is shape-checked ([ApiKeyCredential.hasValidShape]) before
 * anything reads the cache or the database, and a *successful* Argon2id verification
 * is cached for the TTL ([AuthCache.verifiedSecret]) so a busy agent hashes once per
 * key per TTL rather than once per request. The record, its revocation flag, its
 * expiry and the owner's liveness are still re-read on **every** request — the D13
 * revocation-latency contract is untouched by the hash cache.
 */
class ApiKeyService(
    private val apiKeyRepository: ApiKeyRepository,
    private val userService: UserService,
    private val authCache: AuthCache,
    private val auditLogger: AuditLogger,
    private val secretHasher: SecretHasher,
    private val authProperties: AuthProperties,
    private val workspaceService: WorkspaceService,
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
     * Issues a key for [ownerId], pinned to [workspaceId] (D3). Two guards, both
     * server-side: the requested [scopes] MUST be a subset of the creator's effective
     * scopes ([ceilingFor] the issuer) — the privilege-escalation guard (§7.4) — and the creator
     * MUST be a member of [workspaceId] (the §7.4 workspace restriction, design §5.2),
     * else [WorkspaceMembershipRequiredException]. No default on [workspaceId]: issuance
     * without an explicit workspace decision must not compile.
     *
     * An empty [scopes] falls back to `datapipelines.auth.api-keys.default-scopes`
     * ([Configuration §3.4]) — the operator's default, not a hard-coded `read`.
     */
    @Suppress("LongParameterList", "ThrowsCount") // the issuance contract; each refusal has its own catalogued code
    fun issue(
        issuer: AuthenticatedPrincipal,
        ownerId: UUID,
        name: String,
        scopes: Set<Scope>,
        workspaceId: UUID,
        expiresAt: Instant? = null,
        kind: ApiKeyKind = ApiKeyKind.DEFAULT,
    ): IssuedApiKey {
        // §7.7 — a SCOPELESS kind (endpoint, server) carries no scopes by design, so the
        // default-scopes fallback must not apply to it: falling back would hand it `read` across
        // the whole API and make "its authority is its bindings / its route family" false. Caught
        // by the 074 E2E, which asserted the minted key's scope set was empty and found `[read]`.
        val requested = if (kind in ApiKeyKind.SCOPELESS) emptySet() else scopes.ifEmpty { defaultScopes() }
        // D-R12 / O-2 — `admin` left the key wire in RBAC round 1: release, promote and
        // membership are human verbs, and `admin` was the only scope that ever bought a key an
        // INSTANCE verb. Refused by name so a caller learns which scopes exist rather than
        // silently receiving a weaker key than it asked for.
        requested.firstOrNull { it !in KEY_SCOPES }?.let { throw KeyScopeUnavailableException(it) }
        val ceiling = ceilingFor(issuer)
        if (!ScopeMatrix.keyScopesWithinCreator(requested, ceiling)) {
            val overreach = requested.maxByOrNull { s -> Scope.entries.indexOf(s) } ?: Scope.READ
            throw ScopeInsufficientException(required = overreach, held = ceiling)
        }
        // §7.7 — a SERVER key is the promotion receiver's whole credential: whoever holds it can
        // write pipelines, templates and datasource references into this deployment. Minting one
        // is therefore an ADMIN act, and the check is here rather than at a surface so that every
        // caller — REST, the partial, a future CLI — inherits it. It is not a scope subset check
        // (a server key HAS no scopes, so the guard above is vacuous for it) but a floor on the
        // CREATOR, which is a different question and needs its own answer.
        if (kind == ApiKeyKind.SERVER && !issuer.isSuperAdmin) {
            throw RoleRequiredException(Permission.SUPER_ADMIN, emptySet())
        }
        // O-2: viewers never mint keys. The issuance gate is the ISSUER's permission in the
        // pinned workspace — `author` — and the workspace must be one they can reach at all
        // (D-R5's 404 otherwise). Both answers come from ONE resolution, so "can they see it"
        // and "may they act in it" cannot disagree.
        workspaceService.requireIssuancePermission(issuer, workspaceId)

        val keyId = "$KEY_PREFIX${randomBase32(ID_LEN)}"
        val secret = randomBase32(SECRET_LEN)
        val fullKey = "$keyId.$secret"
        val hash = secretHasher.hash(fullKey)

        val record = apiKeyRepository.insert(keyId, ownerId, name, hash, requested, expiresAt, workspaceId, kind)
        authCache.invalidateKey(keyId)
        auditLogger.log(
            event = "auth.api_key.created",
            userId = ownerId,
            keyId = keyId,
            details =
                mapOf(
                    "name" to name,
                    "scopes" to requested.map { it.wire },
                    "workspace_id" to workspaceId.toString(),
                    "kind" to kind.wire,
                ),
        )
        return IssuedApiKey(record = record, plaintext = fullKey)
    }

    /**
     * The most a key this issuer mints may hold (§7.4, D-R12).
     *
     * It used to be the creator's own SCOPE set, passed in by the caller. That stopped being
     * answerable in RBAC round 1: a session carries no scopes at all (D-R1), so the subset
     * guard compared every request against the empty set and **no signed-in person could mint
     * any key**. Found by `JarSmokeE2eTest`, whose whole subject is a real jar minting one.
     *
     * The honest ceiling is the one the design states: *scope ≤ the issuer's capability in
     * that workspace*. Issuance already requires `author` there (O-2 — viewers never mint
     * keys), so a human's ceiling is every scope a key may hold. A KEY minting a key is capped
     * additionally by its OWN scopes, which is the original privilege-escalation guard and the
     * half that must not be lost: a `read` key must never mint an `author` one.
     */
    private fun ceilingFor(issuer: AuthenticatedPrincipal): Set<Scope> = issuanceCeiling(issuer)

    /**
     * The configured default scopes for a new key, falling back to `read` when the
     * operator configured nothing usable (§7.5: "Default scope on key creation: read").
     */
    private fun defaultScopes(): Set<Scope> =
        authProperties.apiKeys.defaultScopes
            .mapNotNull { token -> parseConfiguredScope(token.trim()) }
            .toSet()
            .ifEmpty { setOf(Scope.READ) }

    /**
     * Parses one configured `default-scopes` token, or `null` if it is not a §7.5 wire
     * scope. An operator typo silently degrading key issuance to `read` is exactly the
     * kind of misconfiguration nobody discovers until a key mysteriously lacks
     * permission, so the bad token is named in a WARN (rules/02) rather than dropped.
     */
    private fun parseConfiguredScope(token: String): Scope? =
        runCatching { Scope.fromWire(token) }.getOrElse {
            log.warn(
                "Ignoring unrecognized datapipelines.auth.api-keys.default-scopes token '{}'; valid scopes are {}",
                token,
                Scope.entries.map { it.wire },
            )
            null
        }

    /**
     * Validates a presented full key and resolves the principal (§7.3). Throws the
     * specific [AuthException] for each rejection so the entry point emits the exact
     * §13.7 code (`auth.api_key.invalid` / `auth.api_key.expired`).
     */
    fun validate(presentedKey: String): AuthenticatedPrincipal {
        val record = verifiedRecord(presentedKey)
        val owner = liveOwner(record)
        return AuthenticatedPrincipal(
            userId = owner.id,
            email = owner.email,
            displayName = owner.displayName,
            // D-R12 — capped at what a key MAY hold, not at what its row says. A key minted
            // before round 1 can carry `admin` in `api_keys.scopes`; trusting the row would let
            // exactly the credential the rule removes keep working until it expires. The
            // migration strips the value too; this is the belt that does not depend on it.
            scopes = record.scopes.filterTo(mutableSetOf()) { it in KEY_SCOPES },
            authMethod = AuthMethod.API_KEY,
            keyId = record.id,
            // D3: the key's pinned workspace IS the context — resolved at validation,
            // no per-request switch exists (design §5.2).
            workspaceName = record.workspaceName,
            workspace = pinnedContext(record, owner),
            // §7.7 — what the credential IS travels with it, so every downstream gate reads one
            // answer rather than re-deriving it.
            keyKind = record.kind,
            superAdmin = owner.isAdmin,
        )
    }

    /**
     * The pinned workspace as this key's ISSUER can currently act in it (D-R12) — the fourth
     * of the four per-request re-reads (key active, issuer active, issuer still holds the role,
     * workspace active), all inside the same `AuthCache` TTL.
     *
     * A removed issuer resolves to no role, which becomes VIEWER rather than a refusal: the
     * key still authenticates and every permission above viewer then refuses with
     * `auth.key_issuer_role_lost`, which is the answer the caller can act on. Refusing the
     * credential outright would report "your key is invalid" for a key that is entirely valid.
     *
     * A DEACTIVATED workspace is different and does refuse here: design §6 says its keys are
     * refused, full stop, and there is no operation left to scope.
     */
    private fun pinnedContext(
        record: ApiKey,
        owner: User,
    ): WorkspaceContext {
        if (!workspaceService.isActive(record.workspaceId)) {
            throw KeyWorkspaceInactiveException(record.workspaceName)
        }
        return workspaceService.issuerContext(owner.id, owner.isAdmin, record.workspaceId, record.workspaceName)
            ?: WorkspaceContext(record.workspaceId, record.workspaceName, WorkspaceRole.VIEWER)
    }

    /**
     * Validates a presented key as the promotion peer's credential (§7.7, versioning §10.6):
     * the same shape gate, record read, revocation/expiry re-check, Argon2id verify and owner
     * liveness check [validate] applies — and then the kind, which is the whole point.
     *
     * A key of any other kind is refused with the SAME [ApiKeyInvalidException] a wrong key
     * gets, because `PromotionServerKeyFilter` answers every refusal with one code: a caller
     * must not be able to tell "your key is the wrong kind" from "your key is wrong", which
     * would turn the promotion route into an oracle for classifying stolen keys.
     *
     * Returns the RECORD, not a principal: the promotion filter authenticates the peer as R7's
     * system service account (the credential is not a human, §10.6) and needs the key's id for
     * the audit trail, not its owner's identity.
     */
    fun validateServerKey(presentedKey: String): ApiKey {
        val record = verifiedRecord(presentedKey)
        if (!record.isServerKey) throw ApiKeyInvalidException()
        liveOwner(record)
        return record
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

    /** D13 owner-liveness re-check via the cached snapshot (§7.3 step 8). */
    private fun liveOwner(record: ApiKey): User {
        val owner = userService.snapshot(record.userId) ?: throw ApiKeyInvalidException()
        if (!owner.isActive) throw ApiKeyInvalidException()
        return owner
    }

    /** Revokes a key the caller owns, evicting the local cache immediately (§11.4). */
    fun revoke(
        keyId: String,
        ownerId: UUID,
    ): Boolean {
        val revoked = apiKeyRepository.revoke(keyId, ownerId)
        if (revoked) {
            authCache.invalidateKey(keyId)
            auditLogger.log(event = "auth.api_key.revoked", userId = ownerId, keyId = keyId)
        }
        return revoked
    }

    /**
     * Revokes an `endpoint` key of [workspaceId], whoever owns it — the `/api-keys` page's
     * delete (D17, `MANAGE_API_KEYS`). The caller's ROLE was judged at the route; the kind
     * and workspace predicates in SQL are what keep this from ever touching a user's MCP
     * key or another workspace's.
     */
    fun revokeWorkspaceEndpointKey(
        keyId: String,
        workspaceId: UUID,
        actorId: UUID,
    ): Boolean {
        val revoked = apiKeyRepository.revokeInWorkspace(keyId, workspaceId, ApiKeyKind.ENDPOINT)
        if (revoked) {
            authCache.invalidateKey(keyId)
            auditLogger.log(
                event = "auth.api_key.revoked",
                userId = actorId,
                keyId = keyId,
                details = mapOf("workspace_id" to workspaceId.toString(), "kind" to ApiKeyKind.ENDPOINT.wire),
            )
        }
        return revoked
    }

    /**
     * **The login mint (D16, §3.3)** — called by [WorkspaceService.workspaceForLogin] and the
     * workspace switch through the `McpKeyMint` port, never from a request surface. If the
     * user holds no live `user` key in [context]'s workspace, mint one:
     *
     * - scopes = the role's reach on the CREDENTIAL axis, derived from the matrix rather than
     *   chosen by anyone: an AUTHOR-or-above context gets `author` (which subsumes execute
     *   and read, §7.5), an EXECUTE-only context (the viewer) `execute`, everything else
     *   (the promoter) `read`. With D16 the key IS the member's credential, so its scope
     *   set is the role's, re-read per request as today (§7.4's issuer check is unchanged);
     * - no expiry, name `mcp/<workspace>`;
     * - `minted_at_login = TRUE`, and the plaintext SEALED into `secret_sealed` so the top
     *   bar can offer Copy later — the plaintext is never returned to anyone at mint time,
     *   because there is no screen in a login redirect.
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
    fun mintLoginKey(
        user: User,
        context: WorkspaceContext,
    ): ApiKey? {
        if (user.mustChangePassword) return null
        if (apiKeyRepository.findLiveUserKey(user.id, context.id) != null) return null

        val keyId = "$KEY_PREFIX${randomBase32(ID_LEN)}"
        val secret = randomBase32(SECRET_LEN)
        val fullKey = "$keyId.$secret"
        val scopes =
            when {
                context.permits(Permission.AUTHOR) -> Scope.AUTHOR.expand()
                context.permits(Permission.EXECUTE) -> Scope.EXECUTE.expand()
                else -> setOf(Scope.READ)
            }
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
                    name = loginKeyName(context.name),
                    keyHash = secretHasher.hash(fullKey),
                    scopes = scopes,
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
                    "scopes" to scopes.map { it.wire },
                    "workspace_id" to context.id.toString(),
                    "kind" to ApiKeyKind.USER.wire,
                    "minted_at_login" to true,
                ),
        )
        return record
    }

    /**
     * The caller's own MCP key's plaintext, opened from `secret_sealed` — the top bar's Copy
     * (D16: the secret is served by THIS call, never rendered into a page). Null when the key
     * is not the caller's own live `user` key in [workspaceId], or when it predates R3 and
     * carries no sealed copy — the bar then shows "delete and sign in again" instead.
     *
     * Not cached and deliberately not routed through [AuthCache]: a copy click is rare, and
     * the sealed blob is the one value that must not outlive a rotation in a cache.
     */
    fun openOwnMcpKey(
        userId: UUID,
        workspaceId: UUID,
    ): String? {
        val record = apiKeyRepository.findLiveUserKey(userId, workspaceId) ?: return null
        val sealed = apiKeyRepository.sealedSecretOf(record.id) ?: return null
        val sealer = secretSealer ?: return null
        return sealer.open(sealed, record.id)
    }

    private fun randomBase32(len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(BASE32[random.nextInt(BASE32.length)]) }
        return sb.toString()
    }

    companion object {
        /**
         * The scopes a key minted by [issuer] may hold — the same rule [issue] enforces, exposed
         * so the console's form offers exactly what the service would accept (112 merge review:
         * the form used to read the session's scope set, which is EMPTY for every human since
         * D-R1, and offered nothing). A person's ceiling is every key scope; a key minting a key
         * is capped by its own scopes — the privilege-escalation guard that must not be lost.
         */
        fun issuanceCeiling(issuer: AuthenticatedPrincipal): Set<Scope> =
            if (issuer.authMethod == AuthMethod.API_KEY) {
                Scope.effective(issuer.scopes).intersect(KEY_SCOPES)
            } else {
                KEY_SCOPES
            }

        private const val KEY_PREFIX = ApiKeyCredential.KEY_PREFIX
        private const val ID_LEN = 12
        private const val SECRET_LEN = 48

        /** The login-minted key's name (D16): which workspace this MCP key belongs to. */
        fun loginKeyName(workspaceName: String): String = "mcp/$workspaceName"

        // RFC 4648 base32 alphabet (no padding, unambiguous, scanner-friendly).
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
