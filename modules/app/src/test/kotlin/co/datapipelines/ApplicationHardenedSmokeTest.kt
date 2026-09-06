package co.datapipelines

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.config.SharedRedis
import co.datapipelines.web.config.AuthoringStartupCheck
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64

/**
 * The OTHER posture boots (configuration.md §3.23, 075). [ApplicationSmokeTest] is the
 * `local` / `development` deployment; this is a NAMED environment that declared
 * `hardened`, assembled the way an org's would be, and it must come up.
 *
 * The posture matrix itself is unit-tested exhaustively in `ConfigValidatorTest` — every
 * refusal AND its `development` allowance, one test per row. What only a real context can
 * prove is what this suite exists for: that `application-hardened.yml` is on the classpath
 * and selected by the posture; that the whole bean graph tolerates `authoring-enabled=false`
 * (the posture's default, not a value set here); and that the boot line an operator greps
 * for actually names the environment and the posture.
 *
 * ## The non-loopback requirement, measured
 *
 * `hardened` REFUSES a loopback metadata database or Redis — and Testcontainers' `getHost()`
 * is `localhost` on this machine, so the obvious wiring cannot satisfy the rule it is here
 * to exercise. The containers publish their ports on `0.0.0.0`, so the host's own site-local
 * address reaches them and is not loopback by any spelling. A machine with no site-local
 * IPv4 (an offline laptop with every interface down) cannot produce a non-loopback address
 * at all, and the suite is skipped there rather than weakened — the rule keeps its unit
 * coverage regardless.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // INLINED, not @DynamicPropertySource: `spring.profiles.active` is resolved during
    // config-data processing, before a DynamicPropertySource's property source joins the
    // environment, so a posture supplied that way would never select its profile — and the
    // §7 alignment rule would (correctly) refuse the boot. Inlined properties ARE visible to
    // config-data processing, so this exercises the real derivation: one variable in, the
    // right profile out. No profile is named here on purpose.
    properties = [
        "datapipelines.env=prod-us",
        "datapipelines.posture=hardened",
    ],
)
class ApplicationHardenedSmokeTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var authoringStartupCheck: AuthoringStartupCheck

    @Test
    fun `a hardened deployment boots and is healthy`() {
        rest.getForEntity("/health", String::class.java).statusCode.value() shouldBe 200
    }

    /**
     * The posture's DEFAULT, not a value this test set: `application-hardened.yml` is what
     * turns authoring off, so this assertion fails if the profile file is missing, is not
     * packaged, or is not selected by `DATAPIPELINES_POSTURE`.
     */
    @Test
    fun `the hardened profile supplies the posture defaults - authoring is off and cookies are Secure`() {
        environment.getProperty("datapipelines.deployment.authoring-enabled") shouldBe "false"
        environment.getProperty("datapipelines.auth.cookie-secure") shouldBe "true"
        environment.activeProfiles.toList() shouldBe listOf("hardened")
    }

    /**
     * The line an operator greps for. Driven through the real check against the live
     * `Environment`, so it carries the same values the boot emitted.
     */
    @Test
    fun `the boot line names the environment and the posture`() {
        val logger = LoggerFactory.getLogger(AuthoringStartupCheck::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            environment.getProperty("datapipelines.env") shouldBe ENV_NAME
            // Re-run the PRODUCTION bean against the booted context rather than scraping
            // the application's own startup output, which a parallel suite may have
            // flushed. Same object, same Environment, same line.
            authoringStartupCheck.check()
        } finally {
            logger.detachAppender(appender)
        }
        val line = appender.list.map { it.formattedMessage }.single { it.contains("event=config.posture") }
        line shouldContain "env=$ENV_NAME"
        line shouldContain "posture=hardened"
        line shouldContain "authoring=off"
        line shouldContain "demo=(none)"
    }

    private companion object {
        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        private const val SECRET_BYTES = 32

        /** The org's name for this deployment. Anything legal; nothing branches on it. */
        private const val ENV_NAME = "prod-us"

        /**
         * A site-local IPv4 of this machine, or null when it has none. Not loopback by any
         * spelling the validator knows (`localhost`, `127.*`, `::1`), and the containers'
         * published ports answer on it because Testcontainers publishes on `0.0.0.0`.
         */
        private val siteLocalHost: String? by lazy {
            NetworkInterface
                .getNetworkInterfaces()
                .asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }

        @BeforeAll
        @JvmStatic
        fun requireANonLoopbackAddress() {
            Assumptions.assumeTrue(
                siteLocalHost != null,
                "no site-local IPv4 on this machine, so no non-loopback address exists to satisfy the " +
                    "hardened posture's infrastructure rule (ConfigValidatorTest covers the rule itself)",
            )
        }

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            val host = checkNotNull(siteLocalHost) { "assumption above guarantees this" }
            registry.add("spring.datasource.url") {
                "jdbc:postgresql://$host:${postgres.getMappedPort(POSTGRES_PORT)}/${postgres.databaseName}"
            }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { host }
            registry.add("spring.data.redis.port") { SharedRedis.port }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // No OIDC provider, no seeded credential — the two things `hardened` will not
            // do for you. Local accounts plus the EXPLICIT acknowledgement is the supported
            // shape for a deployment without an IdP, and §7 logs the acknowledgement.
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.auth.allow-local-only") { "true" }
            registry.add("datapipelines.auth.base-url") { "https://prod-us.example.com" }
        }

        private const val POSTGRES_PORT = 5432
    }
}
