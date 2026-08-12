package co.datapipelines.integration

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A minimal, in-process OpenID Provider **discovery** endpoint for app-level tests.
 *
 * `OidcConfig` (auth.md §5.2) performs a real HTTP fetch of each provider's
 * `.well-known/openid-configuration` at startup, and every test in this module boots
 * the FULL application — auth's chain included — so *something* must answer that
 * fetch. Standing up the JDK's own `HttpServer` on an ephemeral loopback port costs
 * milliseconds and no container; the production bean and its discovery behavior are
 * exercised exactly as shipped, only the issuer is a stub. (Same pattern as `auth`'s
 * own `OidcDiscoveryStub`, duplicated because test sources do not cross module bounds.)
 *
 * Only the discovery document is served; nothing here authenticates anybody.
 */
class OidcDiscoveryStub : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** The issuer URI to configure as `datapipelines.auth.oidc.providers[n].issuer-uri`. */
    val issuer: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/.well-known/openid-configuration") { exchange ->
            val body = metadata().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    override fun close() = server.stop(0)

    /** The mandatory subset `ClientRegistrations` requires, with `issuer` self-consistent. */
    private fun metadata(): String =
        """
        {
          "issuer": "$issuer",
          "authorization_endpoint": "$issuer/protocol/openid-connect/auth",
          "token_endpoint": "$issuer/protocol/openid-connect/token",
          "userinfo_endpoint": "$issuer/protocol/openid-connect/userinfo",
          "jwks_uri": "$issuer/protocol/openid-connect/certs",
          "response_types_supported": ["code"],
          "grant_types_supported": ["authorization_code"],
          "subject_types_supported": ["public"],
          "id_token_signing_alg_values_supported": ["RS256"],
          "scopes_supported": ["openid", "profile", "email"]
        }
        """.trimIndent()
}
