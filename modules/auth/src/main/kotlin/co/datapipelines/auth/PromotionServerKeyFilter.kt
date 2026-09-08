package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The promotion peer's credential gate (versioning §10.6, auth.md §8.6).
 *
 * Promotion is one deployment writing to another. §10.6 ratifies the credential as a
 * **server key, not a principal**: no `users` row for the credential itself and no scope-matrix
 * entry. This filter is the receiver's whole enforcement of that.
 *
 * ## Two credentials, one header, one release (091, auth.md §7.7)
 *
 * The header may carry either:
 *
 * - a **`server`-kind API key** ([ApiKeyKind.SERVER]) — minted by an admin, stored like every
 *   other key (Argon2id hash, `dpk_` prefix, expiry, revocation, last-used stamp), and the form
 *   an operator should use: it can be rotated and revoked without touching a file or restarting
 *   anything; or
 * - the **deprecated pre-shared config value** `datapipelines.deployment.promotion.server-key`,
 *   still accepted for one release so an existing deployment keeps working across the upgrade.
 *   `ConfigValidator` WARNs at boot when it is set (configuration.md §3.19).
 *
 * The configured value is compared FIRST and in constant time; only a non-match reaches the
 * key store, so a deployment that has not migrated pays no database read. Both refusals answer
 * the SAME `auth.promotion.key_invalid` — a caller must not be able to tell which credential
 * form a receiver holds, nor "your key is the wrong KIND" from "your key is wrong".
 *
 * ## Scoped to the promotion route, in BOTH directions
 *
 * - Off the [PROMOTION_PREFIX] the filter does nothing at all — it does not read the header,
 *   so the server key authenticates nothing anywhere else. `GET /api/v1/pipelines` carrying
 *   only a server key is an ordinary unauthenticated request and gets its ordinary 401.
 * - ON the prefix the filter is the ONLY way through: a request without a valid key is
 *   rejected here and the chain stops. An admin API key or a session cookie does not open the
 *   promotion route — the route is a deployment-to-deployment channel, not a privileged human
 *   one, and the credential that opens it is the one §10.6 names.
 *
 * ## Fail closed
 * No configured `server-key` AND no live `server`-kind key ⇒ every promotion request is
 * refused. A deployment that never provisioned either must not silently accept pushes. Missing
 * header, malformed header, wrong key, a key of the wrong kind, a revoked or expired key and
 * "nothing configured here" all answer the SAME `auth.promotion.key_invalid` 401, so a caller
 * cannot tell a wrong key from a receiver with promotion disabled.
 *
 * ## The actor the request acts as
 * A validated peer is authenticated as R7's system service account
 * ([UserService.systemActor]) with [Scope.AUTHOR] — the least scope that satisfies the §7.6
 * operations the promotion handlers declare. That row is the FK target every promoted version
 * is stamped with; it holds no credential of its own and nothing can log in as it (auth.md
 * §4.5). [AuthMethod.PROMOTION] keeps its provenance visible everywhere a principal is read:
 * it is not a session, and it is not an API key. The actor is the system account for BOTH
 * credential forms — a `server` key names the deployment that pushed, never the admin who
 * minted it, so a promoted version is not stamped with a person who did not perform it. The
 * key's id rides on the principal ([AuthenticatedPrincipal.keyId]) so the audit trail can say
 * WHICH key opened the route across a rotation.
 *
 * ## What never appears
 * The key does not reach a log line, an error message, an audit `details` map, or the
 * exception. `PromotionServerKeyFilterTest` asserts that on the filter's own log output —
 * "never logged" is a property, not an intention.
 */
class PromotionServerKeyFilter(
    private val promotionProperties: PromotionProperties,
    private val userService: UserService,
    private val authErrorWriter: AuthErrorWriter,
    private val auditLogger: AuditLogger,
    private val clientAddressResolver: ClientAddressResolver,
    /** §7.7 — the key store, for the `server`-kind credential the config value is being retired for. */
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(PromotionServerKeyFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!isPromotionRoute(request)) {
            // Not our route: the header is not read, so the key grants nothing here.
            filterChain.doFilter(request, response)
            return
        }

        val presented = request.getHeader(HEADER)
        val accepted = accept(presented)
        if (accepted == null) {
            reject(request, response)
            return
        }

        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                peerPrincipal(accepted.keyId),
                null,
                PEER_SCOPES.map { SimpleGrantedAuthority("SCOPE_${it.wire}") },
            )
        accepted.keyId?.let { touchUsage(it, request) }
        filterChain.doFilter(request, response)
    }

    /**
     * The credential decision: the configured value first (constant-time, no database read for a
     * deployment that has not migrated), then the key store.
     *
     * Returns how the request was accepted, or `null` for every refusal — the caller turns that
     * one null into the one answer. [ApiKeyService.validateServerKey] throws the ordinary
     * [AuthException] family for a malformed, unknown, revoked, expired, wrong-kind key or a
     * deactivated owner; all of them are the same refusal here, and none of them reaches a log
     * line that would distinguish them.
     */
    private fun accept(presented: String?): Accepted? {
        if (PromotionServerKeys.matches(promotionProperties.serverKey, presented)) return Accepted(keyId = null)
        if (presented.isNullOrBlank()) return null
        return runCatching { apiKeyService.validateServerKey(presented) }
            .map { Accepted(keyId = it.id) }
            .getOrElse { cause ->
                if (cause !is AuthException) throw cause
                null
            }
    }

    /**
     * The `last_used_at` stamp a stored key earns like any other (§7.3 step 9), so the keys page
     * can answer "is this promotion key still in use?". Best-effort and never fatal: a failed
     * stamp must not refuse a promotion that authenticated.
     */
    private fun touchUsage(
        keyId: String,
        request: HttpServletRequest,
    ) {
        runCatching {
            apiKeyRepository.touchUsage(keyId, clientAddressResolver.clientAddressOf(request), request.getHeader("User-Agent"))
        }.onFailure { log.warn("api_keys usage stamp failed key_id={} cause={}", keyId, it.javaClass.simpleName) }
    }

    /** How a promotion request was accepted: by a stored key ([keyId] set) or the config value. */
    private data class Accepted(
        val keyId: String?,
    )

    /**
     * The refusal: the §13.7 envelope, an audit row, and a log line that names the client and
     * the path — and neither the presented key nor the configured one.
     *
     * The chain STOPS here rather than stashing the error for [AuthEntryPoint], for the same
     * reason [WorkspaceResolutionFilter]'s refusals do: continuing would leave the request to
     * be answered by whatever else could authenticate it, and the whole point of this gate is
     * that nothing else opens this route.
     */
    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val client = clientAddressResolver.clientAddressOf(request)
        auditLogger.log(
            event = AUDIT_REJECTED,
            sourceIp = client,
            details =
                mapOf(
                    "path" to request.requestURI,
                    // Named from the RECEIVER's configuration, never from what was presented: the
                    // audit row says what this deployment holds, not what the caller got wrong.
                    "reason" to if (promotionProperties.receives) "key_mismatch" else "no_configured_key",
                ),
        )
        log.info(
            "event=$AUDIT_REJECTED path={} client={} receiver_configured={} " +
                "message=\"promotion request refused; the presented credential is never logged\"",
            request.requestURI,
            client,
            promotionProperties.receives,
        )
        authErrorWriter.write(request, response, PromotionKeyInvalidException())
    }

    /**
     * The system actor, read per request. Promotion is a rare, human-triggered operation, so
     * one indexed lookup per push is the right trade against caching a row that a restart
     * would have to invalidate. `workspace` is deliberately left null: a promotion payload
     * names its own target workspace, and the receiver resolves it there — the credential
     * pins no workspace.
     */
    private fun peerPrincipal(keyId: String?): AuthenticatedPrincipal {
        val actor = userService.systemActor()
        return AuthenticatedPrincipal(
            userId = actor.id,
            email = actor.email,
            displayName = actor.displayName,
            scopes = PEER_SCOPES,
            authMethod = AuthMethod.PROMOTION,
            keyId = keyId,
            // §7.7 — what the credential IS, when it was one. `ScopeInterceptor` allows a
            // SERVER kind on this route family and refuses it on every other, so the
            // confinement is stated in the same place for all three kinds rather than resting
            // on "this filter would have stopped it anyway".
            keyKind = keyId?.let { ApiKeyKind.SERVER },
        )
    }

    private fun isPromotionRoute(request: HttpServletRequest): Boolean = request.requestURI.startsWith(PROMOTION_PREFIX)

    companion object {
        /**
         * The promotion pair's URL space (rest-api.md §18). The prefix is the filter's entire
         * scope: `no other route consults it` (§10.6) is enforced by this one comparison.
         */
        const val PROMOTION_PREFIX = "/api/v1/promotion/"

        /** The request header carrying the pre-shared key (rest-api.md §3.6's `DP-` registry). */
        const val HEADER = "DP-Promotion-Key"

        /** auth.md §10.1 — a refused promotion attempt. The credential is never in the row. */
        const val AUDIT_REJECTED = "auth.promotion.rejected"

        /**
         * The peer's granted scope: `author` satisfies both §7.6 operations the promotion
         * handlers declare (READ_RESOURCES for the inventory, MUTATE_PIPELINES_TEMPLATES for
         * the push) and nothing more. Not `admin` — the receiver resolves the target workspace
         * by name from the payload, so no membership bypass is needed.
         */
        val PEER_SCOPES: Set<Scope> = setOf(Scope.AUTHOR)
    }
}
