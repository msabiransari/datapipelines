package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.VisualizationReleaseCascadeFixtures.Companion.checkStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Real production wiring; no test transaction, replacement release port, or substituted evidence gate. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VisualizationReleaseCascadeRollbackE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `stale A with GREEN B rolls back the eligible template cascade - matching B commits both`() {
        VisualizationReleaseCascadeFixtures(port, JWT_SECRET).use { fixture ->
            fixture.prepare()
            val templateBefore = fixture.templateState()
            val visualizationBefore = fixture.visualizationState()
            templateBefore[0] shouldBe "DRAFT"
            templateBefore[2] shouldBe null
            visualizationBefore shouldBe listOf("DRAFT", fixture.hash, null, "1")
            val refused = fixture.release(fixture.staleHash, consent = true)
            checkStatus(refused, 409)
            refused.jsonPath().getString("error.code") shouldBe "visualization.version.conflict"
            val templateAfter = fixture.templateState()
            val visualizationAfter = fixture.visualizationState()
            println("event=cascade453.stale template=$templateAfter visualization=$visualizationAfter")
            withClue("the production outer transaction must roll back the already executed template release") {
                templateAfter shouldBe templateBefore
            }
            visualizationAfter shouldBe visualizationBefore
            committed(fixture, templateBefore, visualizationBefore)
        }
    }

    @Test
    fun `matching B with GREEN evidence and consent independently commits both releases and pointers`() {
        VisualizationReleaseCascadeFixtures(port, JWT_SECRET).use { fixture ->
            fixture.prepare()
            committed(fixture, fixture.templateState(), fixture.visualizationState())
        }
    }

    @Test
    fun `matching B without consent retains dependency refusal and commits neither release`() {
        VisualizationReleaseCascadeFixtures(port, JWT_SECRET).use { fixture ->
            fixture.prepare()
            val templateBefore = fixture.templateState()
            val visualizationBefore = fixture.visualizationState()
            val refused = fixture.release(fixture.hash, consent = false)
            checkStatus(refused, 409)
            refused.jsonPath().getString("error.code") shouldBe "visualization.release.dependency_not_released"
            fixture.templateState() shouldBe templateBefore
            fixture.visualizationState() shouldBe visualizationBefore
            println("event=cascade453.no_consent template=$templateBefore visualization=$visualizationBefore")
        }
    }

    private fun committed(
        fixture: VisualizationReleaseCascadeFixtures,
        template: List<String?>,
        visualization: List<String?>,
    ) {
        val released = fixture.release(fixture.hash, consent = true)
        checkStatus(released, 200)
        val pins = released.jsonPath().getList<String>("data.templates_released.template_id")
        pins shouldBe listOf(fixture.template)
        released.jsonPath().getList<Int>("data.templates_released.version") shouldBe listOf(1)
        val templateAfter = fixture.templateState()
        val visualizationAfter = fixture.visualizationState()
        templateAfter shouldBe listOf("RELEASED", template[1], "1", "1")
        visualizationAfter shouldBe listOf("RELEASED", visualization[1], "1", "1")
        println("event=cascade453.matching template=$templateAfter visualization=$visualizationAfter cascade_count=${pins.size}")
    }

    private companion object {
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
