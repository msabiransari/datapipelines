package co.datapipelines.web.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * `datapipelines.executor.node-query-timeout-seconds-by-dialect.<DIALECT>` (156, #2): Spring's
 * relaxed binding of a `Map<Dialect, Int>`.
 *
 * ## The dotted/YAML form binds cleanly; the RAW env-var form does not
 *
 * The dotted property path (what a YAML `lake: 180` entry under the map key produces once
 * loaded) binds without ceremony — proven below. A raw environment variable
 * (`DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS_BY_DIALECT_LAKE`) reaching the binder with
 * NO corresponding `application.yml` key does **not**: measured directly against a real
 * [SystemEnvironmentPropertySource] (replacing [StandardEnvironment]'s own "systemEnvironment"
 * source, which is the exact name Spring's relaxed mapper requires to activate its
 * SCREAMING_SNAKE_CASE rule), the whole `ExecutorProperties` bind comes back empty. Spring's
 * `SystemEnvironmentPropertyMapper` maps an env name to a property name by turning EVERY
 * underscore into a dot — it has no way to know that `NODE_QUERY_TIMEOUT_SECONDS_BY_DIALECT` is
 * one dashed segment (`node-query-timeout-seconds-by-dialect`) rather than eight dotted ones, and
 * a genuinely open-ended trailing map key (`_LAKE`, `_POSTGRES`, …) makes that ambiguity worse,
 * not better: nothing marks where the fixed prefix ends and the map key begins.
 *
 * ## The shape that does bind (and the one shipped)
 *
 * Every OTHER key in `application.yml` already sidesteps this exact ambiguity by never relying
 * on raw relaxed env-var binding for a nested path: the YAML always spells the full dotted/dashed
 * key explicitly, and an env var reaches it only as a `${VAR:default}` PLACEHOLDER VALUE, resolved
 * before `@ConfigurationProperties` ever binds. This map follows the same house rule — one
 * explicit YAML entry per configured dialect, e.g.
 * ```yaml
 * node-query-timeout-seconds-by-dialect:
 *   lake: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS_BY_DIALECT_LAKE:180}
 * ```
 * — never a bare env var an operator invents for a dialect application.yml does not already
 * name. Adding another dialect's default is a one-line YAML addition, exactly like any other key.
 */
class ExecutorPropertiesDialectMapBindingTest {
    @Test
    fun `the YAML-anchored dotted form (what application-yml produces) binds a dialect key`() {
        val bound =
            Binder(MapConfigurationPropertySource(mapOf("datapipelines.executor.node-query-timeout-seconds-by-dialect.lake" to "240")))
                .bind("datapipelines.executor", Bindable.of(ExecutorProperties::class.java))
                .get()

        bound.nodeQueryTimeoutSecondsByDialect shouldBe mapOf(co.datapipelines.typesystem.Dialect.LAKE to 240)
    }

    @Test
    fun `a raw env var with no YAML anchor does not bind - the finding this class's KDoc documents`() {
        val env = StandardEnvironment()
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf<String, Any>("DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS_BY_DIALECT_LAKE" to "240"),
            ),
        )

        val bound = Binder.get(env).bind("datapipelines.executor", Bindable.of(ExecutorProperties::class.java))

        bound.isBound.shouldBe(false)
    }

    @Test
    fun `an absent map binds to the shipped LAKE default`() {
        val bound =
            Binder(MapConfigurationPropertySource(emptyMap<String, String>()))
                .bind("datapipelines.executor", Bindable.of(ExecutorProperties::class.java))
                .orElseGet { ExecutorProperties() }

        bound.nodeQueryTimeoutSecondsByDialect shouldBe mapOf(co.datapipelines.typesystem.Dialect.LAKE to 180)
    }
}
