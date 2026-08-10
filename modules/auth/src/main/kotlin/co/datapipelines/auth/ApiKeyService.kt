package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
@Service
class ApiKeyService(
    private val apiKeyRepository: ApiKeyRepository,
    private val userService: UserService,
    private val authCache: AuthCache,
    private val auditLogger: AuditLogger,
    private val secretHasher: SecretHasher,
    private val authProperties: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(ApiKeyService::class.java)
    private val random = SecureRandom()

    /**
     * Issues a key for [ownerId]. The requested [scopes] MUST be a subset of the
     * creator's effective scopes ([creatorScopes]) — the privilege-escalation guard
     * (§7.4), enforced server-side. Throws [ScopeInsufficientException] otherwise.
     *
     * An empty [scopes] falls back to `datapipelines.auth.api-keys.default-scopes`
     * ([Configuration §3.4]) — the operator's default, not a hard-coded `read`.
     */
    fun issue(
        ownerId: UUID,
        name: String,
        scopes: Set<Scope>,
        creatorScopes: Set<Scope>,
        expiresAt: Instant? = null,
    ): IssuedApiKey {
        val requested = scopes.ifEmpty { defaultScopes() }
        if (!ScopeMatrix.keyScopesWithinCreator(requested, creatorScopes)) {
            val overreach = requested.maxByOrNull { s -> Scope.entries.indexOf(s) } ?: Scope.READ
            throw ScopeInsufficientException(required = overreach, held = creatorScopes)
        }

        val keyId = "$KEY_PREFIX${randomBase32(ID_LEN)}"
        val secret = randomBase32(SECRET_LEN)
        val fullKey = "$keyId.$secret"
        val hash = secretHasher.hash(fullKey)

        val record = apiKeyRepository.insert(keyId, ownerId, name, hash, requested, expiresAt)
        authCache.invalidateKey(keyId)
        auditLogger.log(
            event = "auth.api_key.created",
            userId = ownerId,
            keyId = keyId,
            details = mapOf("name" to name, "scopes" to requested.map { it.wire }),
        )
        return IssuedApiKey(record = record, plaintext = fullKey)
    }

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
        // Shape gate FIRST — before any cache or database touch (AUTH-SEC-4).
        if (!ApiKeyCredential.hasValidShape(presentedKey)) throw ApiKeyInvalidException("Malformed API key")
        val keyId = presentedKey.substringBefore('.')
        val record = usableRecord(keyId)
        verifySecret(record, presentedKey)
        val owner = liveOwner(record)
        return AuthenticatedPrincipal(
            userId = owner.id,
            email = owner.email,
            displayName = owner.displayName,
            scopes = record.scopes,
            authMethod = AuthMethod.API_KEY,
            keyId = keyId,
        )
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

    private fun randomBase32(len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(BASE32[random.nextInt(BASE32.length)]) }
        return sb.toString()
    }

    private companion object {
        const val KEY_PREFIX = ApiKeyCredential.KEY_PREFIX
        const val ID_LEN = 12
        const val SECRET_LEN = 48

        // RFC 4648 base32 alphabet (no padding, unambiguous, scanner-friendly).
        const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
