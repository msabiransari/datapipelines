package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.authentication.logout.LogoutFilter
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.Base64

/**
 * The #802 chain-order guard: the credential filters sit AHEAD of `LogoutFilter`, so the
 * `auth.logout` row carries the identity the session or key credential produced
 * (auth.md §6.5, §8.2 step 8) instead of the anonymous marker. Read from the assembled
 * `FilterChainProxy` — the same object `EntryInventoryE2eTest` measures — because the
 * defect was not in any filter but in where the chain placed them.
 *
 * Boots its own `@SpringBootTest`: `AuthHttpBoundaryTest`'s context is keyed on its
 * private `OidcDiscoveryStub` (a random-port issuer inside the context cache key), so
 * the boots cannot be shared without editing outside this lane's fence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityFilterChainOrderTest {
    @Autowired
    private lateinit var filterChainProxy: FilterChainProxy

    @Test
    fun `the credential filters run ahead of LogoutFilter`() {
        val order =
            filterChainProxy
                .filterChains
                .first()
                .filters
                .map { it.javaClass.simpleName }

        order shouldContain "CsrfFilter"
        order shouldContain PromotionServerKeyFilter::class.java.simpleName
        order shouldContain LoginRateLimitFilter::class.java.simpleName
        order shouldContain ApiKeyFilter::class.java.simpleName
        order shouldContain JwtAuthenticationFilter::class.java.simpleName
        order shouldContain WorkspaceResolutionFilter::class.java.simpleName
        order shouldContain LogoutFilter::class.java.simpleName

        val csrf = order.indexOf("CsrfFilter")
        val promotion = order.indexOf(PromotionServerKeyFilter::class.java.simpleName)
        val apiKey = order.indexOf(ApiKeyFilter::class.java.simpleName)
        val jwt = order.indexOf(JwtAuthenticationFilter::class.java.simpleName)
        val workspace = order.indexOf(WorkspaceResolutionFilter::class.java.simpleName)
        val logout = order.indexOf(LogoutFilter::class.java.simpleName)

        csrf shouldBeLessThan promotion
        apiKey shouldBeLessThan jwt
        jwt shouldBeLessThan workspace
        workspace shouldBeLessThan logout
    }

    private companion object {
        val JWT_SECRET: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 11).toByte() })

        // Required once a provider is configured (§5.2); no OIDC login happens here.
        val discovery = OidcDiscoveryStub()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { SharedPostgres.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedPostgres.postgres.username }
            registry.add("spring.datasource.password") { SharedPostgres.postgres.password }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.auth.base-url") { "https://dp.example.com" }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "stub" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "dp-client" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "dp-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { discovery.issuer }
        }
    }
}
