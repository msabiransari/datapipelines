package co.datapipelines.web.ui

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.stereotype.Component

data class Provider(
    val registrationId: String,
    val displayName: String,
)

@Component
class OidcRegistrations(
    private val repository: ClientRegistrationRepository,
) {
    fun providers(): List<Provider> {
        val registrations =
            when (repository) {
                is InMemoryClientRegistrationRepository -> repository.toList()
                else -> emptyList()
            }
        val authorizationCode =
            org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE
        return registrations
            .filter { it.authorizationGrantType == authorizationCode }
            .map { reg -> Provider(reg.registrationId, reg.clientName) }
    }
}
