package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The `datapipelines.script.*` keys (7b, #7, configuration.md §3.28), pinned in the three
 * places they have to agree: the doc (the authority), `application.yml` (the shipped defaults)
 * and [ConfigValidator] (the §7 rule that reads them). Same shape and the same two targets as
 * [OrgConfigKeysSpecDriftTest]: doc drift and YAML reparenting — the script block is
 * APPENDED after the whole `datapipelines:` tree for the same reason.
 */
class ScriptConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }

    @Test
    fun `configuration_md, application_yml and ConfigValidator name exactly the same script keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val inYaml = shipped.keys.filter { it.startsWith(PREFIX) }.sorted()
        val readByValidator = keysIn(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt").readText())

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        readByValidator shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults are the documented ones`() {
        shipped["$PREFIX.evaluate-timeout-seconds"] shouldBe "\${DATAPIPELINES_SCRIPT_EVALUATE_TIMEOUT_SECONDS:10}"
        shipped["$PREFIX.suite-timeout-seconds"] shouldBe "\${DATAPIPELINES_SCRIPT_SUITE_TIMEOUT_SECONDS:60}"
        shipped["$PREFIX.pool-size"] shouldBe "\${DATAPIPELINES_SCRIPT_POOL_SIZE:4}"
        shipped["$PREFIX.pool-queue"] shouldBe "\${DATAPIPELINES_SCRIPT_POOL_QUEUE:64}"
        shipped["$PREFIX.abandon-grace-seconds"] shouldBe "\${DATAPIPELINES_SCRIPT_ABANDON_GRACE_SECONDS:30}"
        shipped["$PREFIX.max-input-rows"] shouldBe "\${DATAPIPELINES_SCRIPT_MAX_INPUT_ROWS:100000}"
        shipped["$PREFIX.max-value-bytes"] shouldBe "\${DATAPIPELINES_SCRIPT_MAX_VALUE_BYTES:1048576}"
        shipped["$PREFIX.max-string-bytes"] shouldBe "\${DATAPIPELINES_SCRIPT_MAX_STRING_BYTES:1048576}"
        shipped["$PREFIX.max-depth"] shouldBe "\${DATAPIPELINES_SCRIPT_MAX_DEPTH:100}"
        shipped["$PREFIX.js.isolate-dir"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR:}"
        shipped["$PREFIX.js.max-heap-mb"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB:256}"
        shipped["$PREFIX.js.isolate-memory-mb"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_ISOLATE_MEMORY_MB:}"
        shipped["$PREFIX.js.max-statements"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_STATEMENTS:100000000}"
        shipped["$PREFIX.js.max-ast-depth"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_AST_DEPTH:1000}"
        shipped["$PREFIX.js.max-stack-frames"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_STACK_FRAMES:1000}"
        shipped["$PREFIX.js.max-output-bytes"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_OUTPUT_BYTES:1048576}"
        shipped["$PREFIX.js.max-result-mb"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_RESULT_MB:64}"
        shipped["$PREFIX.js.max-input-mb"] shouldBe "\${DATAPIPELINES_SCRIPT_JS_MAX_INPUT_MB:64}"
    }

    @Test
    fun `the shipped script defaults pass the production rules`() {
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(
                    scriptEvaluateTimeoutSeconds = defaultOf("$PREFIX.evaluate-timeout-seconds").toLong(),
                    scriptSuiteTimeoutSeconds = defaultOf("$PREFIX.suite-timeout-seconds").toLong(),
                    scriptAbandonGraceSeconds = defaultOf("$PREFIX.abandon-grace-seconds").toLong(),
                    scriptPoolSize = defaultOf("$PREFIX.pool-size").toLong(),
                    scriptPoolQueue = defaultOf("$PREFIX.pool-queue").toLong(),
                    scriptMaxInputRows = defaultOf("$PREFIX.max-input-rows").toLong(),
                    scriptMaxValueBytes = defaultOf("$PREFIX.max-value-bytes").toLong(),
                    scriptMaxStringBytes = defaultOf("$PREFIX.max-string-bytes").toLong(),
                    scriptMaxDepth = defaultOf("$PREFIX.max-depth").toLong(),
                ),
            )
        report.violations shouldContainExactly emptyList()
    }

    @Test
    fun `the bounds check refuses an evaluate above its suite and a sub-second grace`() {
        val inverted =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(scriptEvaluateTimeoutSeconds = 61, scriptSuiteTimeoutSeconds = 60),
            )
        inverted.violations.any { it.startsWith("datapipelines.script.evaluate-timeout-seconds") } shouldBe true

        val zeroGrace =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(scriptAbandonGraceSeconds = 0),
            )
        zeroGrace.violations.any { it.startsWith("datapipelines.script.abandon-grace-seconds") } shouldBe true
    }

    @Test
    fun `the YAML binds JavaScript overrides and preserves the neighboring root settings`() {
        val environment =
            org.springframework.mock.env
                .MockEnvironment()
        environment.propertySources.addLast(
            org.springframework.core.env
                .MapPropertySource("shipped", shipped),
        )
        mapOf(
            "$PREFIX.pool-size" to "2",
            "$PREFIX.js.isolate-dir" to "/opt/graaljs",
            "$PREFIX.js.max-heap-mb" to "128",
            "$PREFIX.js.max-statements" to "123",
            "$PREFIX.js.max-ast-depth" to "456",
            "$PREFIX.js.max-stack-frames" to "789",
            "$PREFIX.js.max-output-bytes" to "8192",
            "$PREFIX.js.max-result-mb" to "2",
            "$PREFIX.js.max-input-mb" to "3",
        ).forEach { (key, value) -> environment.setProperty(key, value) }

        fun bind() =
            org.springframework.boot.context.properties.bind.Binder
                .get(environment)
                .bind(
                    PREFIX,
                    org.springframework.boot.context.properties.bind.Bindable
                        .of(co.datapipelines.web.config.ScriptProperties::class.java),
                ).get()
        val properties = bind()
        properties.poolSize shouldBe 2
        properties.evaluateTimeoutSeconds shouldBe 10
        properties.maxDepth shouldBe 100
        properties.isolateMemoryMb shouldBe 512
        properties.js shouldBe
            co.datapipelines.web.config.ScriptProperties.Js(
                isolateDir = "/opt/graaljs",
                maxHeapMb = 128,
                maxStatements = 123,
                maxAstDepth = 456,
                maxStackFrames = 789,
                maxOutputBytes = 8192,
                maxResultMb = 2,
                maxInputMb = 3,
            )
        environment.setProperty("$PREFIX.js.isolate-memory-mb", "1024")
        bind().isolateMemoryMb shouldBe 1024
        environment.getProperty("datapipelines.scheduler.enabled", Boolean::class.java) shouldBe true
        environment.getProperty("datapipelines.mail.message-stream") shouldBe ""
    }

    /** The default out of a `${'$'}{VAR:default}` placeholder — what an unset variable resolves to. */
    private fun defaultOf(key: String): String {
        val raw = checkNotNull(shipped[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER_DEFAULT.find(raw)) { "$key is not a placeholder with a default: $raw" }
            .groupValues[1]
    }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

    private fun keysIn(text: String): List<String> =
        KEY_REGEX
            .findAll(text)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val PREFIX = "datapipelines.script"

        /** `${'$'}{VAR:default}` — group 1 is the default. */
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}${'$'}""")

        val EXPECTED =
            listOf(
                "$PREFIX.abandon-grace-seconds",
                "$PREFIX.evaluate-timeout-seconds",
                "$PREFIX.js.isolate-dir",
                "$PREFIX.js.isolate-memory-mb",
                "$PREFIX.js.max-ast-depth",
                "$PREFIX.js.max-heap-mb",
                "$PREFIX.js.max-input-mb",
                "$PREFIX.js.max-output-bytes",
                "$PREFIX.js.max-result-mb",
                "$PREFIX.js.max-stack-frames",
                "$PREFIX.js.max-statements",
                "$PREFIX.max-depth",
                "$PREFIX.max-input-rows",
                "$PREFIX.max-string-bytes",
                "$PREFIX.max-value-bytes",
                "$PREFIX.pool-queue",
                "$PREFIX.pool-size",
                "$PREFIX.suite-timeout-seconds",
            )

        val KEY_REGEX = Regex("""datapipelines\.script(\.js)?\.[a-z0-9-]+""")
    }
}
