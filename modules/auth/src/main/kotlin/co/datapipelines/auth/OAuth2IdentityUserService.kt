package co.datapipelines.auth

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestOperations

/**
 * The identity of an OAuth 2.0 (non-OIDC) login (auth.md §5.5, #655). Spring calls this
 * service only for a registration WITHOUT the `openid` scope — `protocol: oauth2` entries
 * ([OidcConfig]); OIDC registrations go through Spring's own OIDC user service and never
 * reach it.
 *
 * It reads the user-info endpoint (through Spring's [DefaultOAuth2UserService]) and returns a
 * principal whose attributes carry the OIDC-shaped keys [OidcSuccessHandler] reads, so the
 * provisioning, allowlist, identity-link and session code stays ONE path for both protocols:
 *
 * | Attribute | From |
 * |---|---|
 * | `sub` | the entry's `user-name-attribute` value, as a string (GitHub: the numeric `id`) |
 * | `name` | user-info `name`, else `login` (GitHub's `name` is nullable) |
 * | `picture` | user-info `picture`, else `avatar_url` |
 * | `email`, `email_verified` | with `emails-uri`: that endpoint's PRIMARY row and its `verified` flag; else as user-info returned them |
 *
 * With `emails-uri` the user-info `email` is DISCARDED: GitHub's is the public profile
 * address, neither verified nor necessarily the account's — provisioning is keyed on email
 * (§4.2), so trusting it would let anyone who types a victim's address into their public
 * profile take the victim's row. No primary row → no `email` (the handler's `missing_email`);
 * a primary row that is not `verified: true` → `email_verified: false` (`email_not_verified`).
 *
 * The access token lives only inside [OAuth2UserRequest]: it is sent as the bearer of the two
 * reads and is never stored, logged or returned ([NoStoreAuthorizedClientRepository]). A
 * failed or malformed emails read is an [OAuth2AuthenticationException] with
 * [INVALID_EMAILS_RESPONSE] — the login failure handler audits and redirects it.
 */
class OAuth2IdentityUserService(
    private val authProperties: AuthProperties,
    private val restOperations: RestOperations = OidcConfig.boundedRestTemplate(authProperties),
    private val delegate: OAuth2UserService<OAuth2UserRequest, OAuth2User> =
        DefaultOAuth2UserService().apply {
            setRestOperations(OidcConfig.boundedRestTemplate(authProperties).apply { errorHandler = OAuth2ErrorResponseErrorHandler() })
        },
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val registration = userRequest.clientRegistration
        val nameAttribute = registration.providerDetails.userInfoEndpoint.userNameAttributeName
        val user =
            try {
                delegate.loadUser(userRequest)
            } catch (e: IllegalArgumentException) {
                // DefaultOAuth2User refuses attributes without the configured subject attribute
                // with an IllegalArgumentException — outside Spring's authentication-failure path
                // it would surface as a 500. It is a malformed user-info answer.
                throw failure(INVALID_USER_INFO_RESPONSE, "user-info has no '$nameAttribute' attribute", e)
            }
        val attributes = LinkedHashMap<String, Any?>(user.attributes)
        attributes["sub"] = attributes[nameAttribute]?.toString()
            ?: throw failure(INVALID_USER_INFO_RESPONSE, "user-info '$nameAttribute' is null", null)
        if (attributes["name"] == null) attributes["name"] = attributes["login"]
        if (attributes["picture"] == null) attributes["picture"] = attributes["avatar_url"]

        val emailsUri = authProperties.oidcProvider(registration.registrationId)?.emailsUri?.takeIf { it.isNotBlank() }
        if (emailsUri != null) {
            attributes.remove("email")
            attributes.remove("email_verified")
            primaryEmail(emailsUri, userRequest.accessToken)?.let { (email, verified) ->
                attributes["email"] = email
                attributes["email_verified"] = verified
            }
        }
        val present = attributes.entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        return DefaultOAuth2User(user.authorities, present, nameAttribute)
    }

    /** The primary row of the emails endpoint as (address, verified), or null when there is none. */
    private fun primaryEmail(
        emailsUri: String,
        accessToken: OAuth2AccessToken,
    ): Pair<String, Boolean>? {
        val primary = primaryRow(emailRows(emailsUri, accessToken)) ?: return null
        val email =
            (primary["email"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw failure(INVALID_EMAILS_RESPONSE, "primary emails row has no address", null)
        // Fail closed: only a JSON `true` verifies; anything else is unverified.
        return email to (primary["verified"] == true)
    }

    /** The emails endpoint's rows; a failed, non-2xx, unreadable or empty answer is the named error. */
    private fun emailRows(
        emailsUri: String,
        accessToken: OAuth2AccessToken,
    ): List<Any?> {
        val request =
            RequestEntity
                .get(emailsUri)
                .headers { it.setBearerAuth(accessToken.tokenValue) }
                .accept(MediaType.APPLICATION_JSON)
                .build()
        return exchange(request) ?: throw failure(INVALID_EMAILS_RESPONSE, "emails endpoint answered no body", null)
    }

    /** One emails read; each description names what failed, never the token. */
    private fun exchange(request: RequestEntity<Void>): List<Any?>? =
        try {
            restOperations.exchange(request, ROWS).body
        } catch (e: RestClientResponseException) {
            throw failure(INVALID_EMAILS_RESPONSE, "emails endpoint answered HTTP ${e.statusCode.value()}", e)
        } catch (e: RestClientException) {
            // Timeouts, refused connections and unreadable bodies (an object, not a list).
            throw failure(INVALID_EMAILS_RESPONSE, "emails endpoint read failed (${e.javaClass.simpleName})", e)
        }

    /** The single row flagged `primary: true`, or null; a non-object row or two primaries are malformed. */
    private fun primaryRow(rows: List<Any?>): Map<*, *>? {
        val primaries =
            rows
                .map { row -> row as? Map<*, *> ?: throw failure(INVALID_EMAILS_RESPONSE, "emails row is not an object", null) }
                .filter { it["primary"] == true }
        if (primaries.size > 1) throw failure(INVALID_EMAILS_RESPONSE, "emails endpoint names ${primaries.size} primary rows", null)
        return primaries.singleOrNull()
    }

    private fun failure(
        code: String,
        description: String,
        cause: Throwable?,
    ): OAuth2AuthenticationException {
        val error = OAuth2Error(code, description, null)
        return if (cause == null) OAuth2AuthenticationException(error) else OAuth2AuthenticationException(error, cause)
    }

    companion object {
        /** The error code of a failed, non-2xx or malformed emails read (auth.md §5.5). */
        const val INVALID_EMAILS_RESPONSE = "invalid_emails_response"

        /** Spring's own code for a malformed user-info answer, reused for the missing subject. */
        const val INVALID_USER_INFO_RESPONSE = "invalid_user_info_response"

        private val ROWS = object : ParameterizedTypeReference<List<Any?>>() {}
    }
}
