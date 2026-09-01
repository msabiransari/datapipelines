package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * The authoring capability guard (versioning §5.5) — fail-closed refusal semantics and the
 * flag binding, as data. The enforcement POINTS (services, controllers, tools) are covered
 * where they live; this pins the refusal itself: the catalogued code, the reason it names,
 * and the default-on binding that a one-box deployment depends on.
 */
class AuthoringGuardTest {
    @Test
    fun `enabled refuses nothing`() {
        val guard = AuthoringGuard(true)

        guard.requirePipelineAuthoring()
        guard.requireTemplateAuthoring()
    }

    @Test
    fun `disabled refuses pipeline authoring with the catalogued code and reason`() {
        val thrown = shouldThrow<DatapipelinesException> { AuthoringGuard(false).requirePipelineAuthoring() }

        thrown.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        thrown.message shouldContain "datapipelines.authoring.enabled=false"
        thrown.details["config_key"] shouldBe AuthoringGuard.CONFIG_KEY
    }

    @Test
    fun `disabled refuses template authoring with the mirrored code`() {
        val thrown = shouldThrow<DatapipelinesException> { AuthoringGuard(false).requireTemplateAuthoring() }

        thrown.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED
        thrown.details["config_key"] shouldBe AuthoringGuard.CONFIG_KEY
    }

    @Test
    fun `from binds the flag with default ON - the one-box deployment's guarantee`() {
        // Absent ⇒ enabled: someone running a single box authors there and runs there,
        // and the flag must not lock them out of the only server they have (§5.5).
        AuthoringGuard.from(environment(null)).let { guard ->
            guard.requirePipelineAuthoring()
            guard.requireTemplateAuthoring()
        }

        shouldThrow<DatapipelinesException> {
            AuthoringGuard.from(environment("false")).requirePipelineAuthoring()
        }.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
    }

    private fun environment(
        flag: String?,
        serverKey: String? = null,
    ): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    buildMap {
                        flag?.let { put(AuthoringGuard.CONFIG_KEY, it) }
                        serverKey?.let { put(AuthoringGuard.PROMOTION_SERVER_KEY, it) }
                    },
                ),
            )
        }
}
