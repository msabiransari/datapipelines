package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The promotion peer's credential gate (versioning §10.6, auth.md §8.6).
 *
 * Promotion is one deployment writing to another. This filter is the receiver's whole
 * enforcement of who may do it; what the accepted peer may then do is the `promotion_receiver`
 * key role's two permissions (#215, record §3.2) — nothing else.
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
 * ## The actor the request acts as (#215 record C4, B6)
 * - A **stored `server` key** acts as its OWN `service` identity ([ValidatedServerKey.identity],
 *   record §3.3): received versions and the audit row name that identity — a promotion is not
 *   stamped with the System account, and not with the person who minted the key.
 * - The **deprecated config value** is a credential with no row to attach an identity to, so it
 *   stays R7's System service account ([UserService.systemActor]), which holds no credential of
 *   its own and which nothing can log in as (auth.md §4.5).
 *
 * Both carry [KeyRole.PROMOTION_RECEIVER] — `promotion.inventory.read` and `promotion.push`, and
 * nothing else — and NO workspace: promotion intake is instance-wide (B6). A batch names its own
 * target workspace and the receiver resolves it there by name; the key's pinned workspace is where
 * the key is LISTED, not what it may receive into. [AuthMethod.PROMOTION] keeps the provenance
 * visible everywhere a principal is read: it is not a session, and it is not an API key. The key's
 * id rides on the principal ([AuthenticatedPrincipal.keyId]) so the audit trail can say WHICH key
 * opened the route across a rotation.
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
            UsernamePasswordAuthenticationToken(peerPrincipal(accepted), null, emptyList())
        accepted.key?.let { touchUsage(it.key.id, request) }
        filterChain.doFilter(request, response)
    }

    /**
     * The credential decision: the configured value first (constant-time, no database read for a
     * deployment that has not migrated), then the key store.
     *
     * Returns how the request was accepted, or `null` for every refusal — the caller turns that
     * one null into the one answer. [ApiKeyService.validateServerKey] throws the ordinary
     * [AuthException] family for a malformed, unknown, revoked, expired, wrong-kind key, a
     * deactivated identity or pinned workspace; all of them are the same refusal here, and none of them reaches a log
     * line that would distinguish them.
     */
    private fun accept(presented: String?): Accepted? {
        if (PromotionServerKeys.matches(promotionProperties.serverKey, presented)) return Accepted(key = null)
        if (presented.isNullOrBlank()) return null
        return runCatching { apiKeyService.validateServerKey(presented) }
            .map { Accepted(key = it) }
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

    /** How a promotion request was accepted: by a stored key ([key] set) or the config value. */
    private data class Accepted(
        val key: ValidatedServerKey?,
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
     * The peer's principal: a stored key's identity, or — for the config value — the System actor,
     * read per request (promotion is rare and human-triggered, so one indexed lookup per push beats
     * caching a row a restart would have to invalidate). `workspace` is deliberately null: intake
     * is instance-wide (B6), and the batch names its own target workspace.
     */
    private fun peerPrincipal(accepted: Accepted): AuthenticatedPrincipal {
        val actor = accepted.key?.identity ?: userService.systemActor()
        return AuthenticatedPrincipal(
            userId = actor.id,
            email = actor.email,
            displayName = actor.displayName,
            authMethod = AuthMethod.PROMOTION,
            keyId = accepted.key?.key?.id,
            // §7.7 — what the credential IS, when it was one. `ScopeInterceptor` allows a
            // SERVER kind on this route family and refuses it on every other, so the
            // confinement is stated in the same place for all three kinds rather than resting
            // on "this filter would have stopped it anyway".
            keyKind = accepted.key?.let { ApiKeyKind.SERVER },
            // Both credential shapes: the receiver's two permissions and nothing else (record §3.2).
            keyRole = KeyRole.PROMOTION_RECEIVER,
        )
    }

    private fun isPromotionRoute(request: HttpServletRequest): Boolean = request.appPath().startsWith(PROMOTION_PREFIX)

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
    }
}
