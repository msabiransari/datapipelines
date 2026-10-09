package co.datapipelines.web

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.DurationStyle
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.time.Duration

/**
 * The Redis client bounds are stated in production config and repeated in each module's test
 * factory to reproduce the production posture (#482, #486) — so a guard reads all three and
 * refuses the drift, the way `DbKeyProviderConfigKeysSpecDriftTest` pins the `db` block.
 *
 * Four places must agree:
 *
 *  1. the §3.14 bridge (`spring.data.redis.timeout` / `.connect-timeout`) — what Lettuce
 *     actually reads — binds the operator variables;
 *  2. the operator block (`datapipelines.redis.command-timeout` / `.connect-timeout`, §3.1)
 *     carries the SAME placeholders with the SAME defaults — the two blocks are one contract,
 *     and a default changed in one but not the other is a silent second authority;
 *  3. [TestRedis] builds every factory with exactly the shipped defaults. Before #482 the test
 *     factories mirrored production's MISSING timeout (Lettuce's 60 s silence) — the stopped-
 *     Redis cases took ~242 s each. When the fix landed, the factories deliberately carried it
 *     too; this class is what stops either side from drifting back.
 *  4. dag's RedisSupport repeats both bounds because it cannot import web test sources; this
 *     guard reads its source and also sweeps all test factories for explicit client config.
 *
 * Red when either side changes alone: an `application.yml` default edited without [TestRedis]
 * fails `TestRedis builds its factories with the shipped defaults`; a [TestRedis] constant
 * edited without the yml fails the same test; a block re-pointed at another variable, or one
 * block's default changed without the other's, fails the placeholder test.
 */
class TestRedisTimeoutParityTest {
    private val app: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(repoFile(APP_YML).absolutePath))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()
    }

    @Test
    fun `the bridge and the operator block carry the SAME placeholder for each bound`() {
        defaultOf(BRIDGE_COMMAND, COMMAND_VAR) shouldBe defaultOf(OPERATOR_COMMAND, COMMAND_VAR)
        defaultOf(BRIDGE_CONNECT, CONNECT_VAR) shouldBe defaultOf(OPERATOR_CONNECT, CONNECT_VAR)
    }

    @Test
    fun `TestRedis builds its factories with the shipped defaults, not Lettuce's 60 s silence`() {
        TestRedis.COMMAND_TIMEOUT shouldBe DurationStyle.detectAndParse(defaultOf(BRIDGE_COMMAND, COMMAND_VAR))
        TestRedis.CONNECT_TIMEOUT shouldBe DurationStyle.detectAndParse(defaultOf(BRIDGE_CONNECT, CONNECT_VAR))
        val client = TestRedis.clientConfiguration()
        client.commandTimeout shouldBe TestRedis.COMMAND_TIMEOUT
        client.clientOptions
            .orElseThrow()
            .socketOptions.connectTimeout shouldBe TestRedis.CONNECT_TIMEOUT
    }

    @Test
    fun `dag's RedisSupport carries both shipped defaults`() {
        val source = codeOnly(repoFile(DAG_SUPPORT).readText())
        listOf("COMMAND_TIMEOUT" to BRIDGE_COMMAND, "CONNECT_TIMEOUT" to BRIDGE_CONNECT).forEach { (constant, key) ->
            withClue("$DAG_SUPPORT $constant") {
                val matches = Regex("""val\s+$constant\s*:\s*Duration\s*=\s*Duration\.ofSeconds\((\d+)\)""").findAll(source).toList()
                matches.size shouldBe 1
                Duration.ofSeconds(matches.single().groupValues[1].toLong()) shouldBe
                    DurationStyle.detectAndParse(defaultOf(key, if (key == BRIDGE_COMMAND) COMMAND_VAR else CONNECT_VAR))
            }
        }
    }

    @Test
    fun `every test Redis factory supplies a client configuration`() {
        val root = repoRoot()
        val moduleSources =
            File(root, "modules").listFiles().orEmpty().filter { it.isDirectory }.flatMap { module ->
                File(module, "src/test").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
        val testSources =
            File(root, "tests")
                .walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" && it.relativeTo(root).invariantSeparatorsPath.contains("/src/test/") }
                .toList()
        val sources = moduleSources + testSources
        val sites =
            sources.flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                withClue(path) { factoryCalls(file.readText()).map { path to it } }
            }
        withClue("test Redis factory sweep must find at least $FACTORY_FLOOR call sites; found ${sites.size}") {
            (sites.size >= FACTORY_FLOOR) shouldBe true
        }
        sites.forEach { (file, site) ->
            withClue("$file:${site.line}: LettuceConnectionFactory must pass a client configuration as its second argument") {
                (site.arguments >= 2) shouldBe true
            }
        }
        println("#486 test Redis factory sweep sites=${sites.size}")
    }

    @Test
    fun `the sweep ignores nested comments and strings, preserving source lines`() {
        val source =
            listOf(
                "/** LettuceConnectionFactory(config) /* nested */ */",
                "// LettuceConnectionFactory(config)",
                "val text = \"LettuceConnectionFactory(config) // literal\"",
                "val raw = \"\"\"LettuceConnectionFactory(config) /* literal */\"\"\"",
                "val quote = '\"'",
                "LettuceConnectionFactory(config, clientConfiguration())",
            ).joinToString("\n")
        factoryCalls(source) shouldBe listOf(FactoryCall(line = 6, arguments = 2))
    }

    @Test
    fun `the sweep distinguishes nested commas and trailing commas from a second argument`() {
        val source =
            """
            LettuceConnectionFactory(RedisStandaloneConfiguration(host, port),)
            org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                RedisStandaloneConfiguration(host, port), /* config comment */
                clientConfiguration(),
            )
            LettuceConnectionFactory(config /* comma, parentheses () */,)
            """.trimIndent()
        factoryCalls(source) shouldBe
            listOf(FactoryCall(line = 1, arguments = 1), FactoryCall(line = 2, arguments = 2), FactoryCall(line = 6, arguments = 1))
    }

    private data class FactoryCall(
        val line: Int,
        val arguments: Int,
    )

    /** Count only top-level nonempty arguments, so nested calls and trailing commas cannot fake a second argument. */
    private fun factoryCalls(source: String): List<FactoryCall> {
        val code = codeOnly(source)
        return Regex("""\bLettuceConnectionFactory\s*\(""")
            .findAll(code)
            .map { match ->
                val start = match.range.last + 1
                var depth = 0
                var arguments = 0
                var argumentStart = start
                var cursor = start
                while (cursor < code.length) {
                    val char = code[cursor]
                    if (depth == 0 && (char == ',' || char == ')')) {
                        if (code.substring(argumentStart, cursor).isNotBlank()) arguments++
                        argumentStart = cursor + 1
                        if (char == ')') break
                    } else {
                        when (char) {
                            '(', '[', '{', '<' -> depth++
                            ')', ']', '}', '>' -> depth--
                        }
                    }
                    cursor++
                }
                check(cursor < code.length) { "Unclosed LettuceConnectionFactory call" }
                FactoryCall(line = source.take(match.range.first).count { it == '\n' } + 1, arguments = arguments)
            }.toList()
    }

    /** Mask comments (including nested Kotlin block comments) and literals, retaining offsets and newlines. */
    private fun codeOnly(source: String): String {
        val code = source.toCharArray()
        val starts = Regex("//|/\\*|\"\"\"|\"|'")
        var cursor = 0
        while (cursor < source.length) {
            val match = starts.find(source, cursor) ?: break
            val start = match.range.first
            val end = tokenEnd(source, start, match.value)
            for (index in start until end) {
                if (code[index] != '\n') code[index] = ' '
            }
            // A literal is still an argument; its contents must not look like code or punctuation.
            if (!match.value.startsWith('/')) code[start] = '_'
            cursor = end
        }
        return String(code)
    }

    private fun tokenEnd(
        source: String,
        start: Int,
        token: String,
    ): Int =
        when (token) {
            "//" -> {
                source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
            }

            "/*" -> {
                var depth = 1
                var cursor = start + token.length
                val delimiters = Regex("/\\*|\\*/")
                while (depth > 0) {
                    val next = delimiters.find(source, cursor) ?: error("Unclosed Kotlin comment")
                    depth += if (next.value == "/*") 1 else -1
                    cursor = next.range.last + 1
                }
                cursor
            }

            "\"\"\"" -> {
                source.indexOf(token, start + token.length).takeIf { it >= 0 }?.plus(token.length) ?: source.length
            }

            else -> {
                var cursor = start + 1
                while (cursor < source.length && source[cursor].toString() != token) {
                    cursor += if (source[cursor] == '\\') 2 else 1
                }
                (cursor + 1).coerceAtMost(source.length)
            }
        }

    /**
     * The default text inside the key's `${VAR:default}` placeholder. Refuses when the key is
     * missing or binds ANY other variable — a bridge re-pointed away from the operator variable
     * is exactly the drift this refuses (the key would bind nothing an operator can set).
     */
    private fun defaultOf(
        key: String,
        varName: String,
    ): String {
        val text = app[key]?.toString() ?: ""
        val match = Regex("^\\$\\{" + Regex.escape(varName) + ":([^}]*)\\}$").find(text)
        require(match != null) { "$key = '$text' does not bind the operator variable $varName" }
        return match.groupValues[1]
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File = File(repoRoot(), relative).also { check(it.isFile) { "missing $relative" } }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return dir
    }

    private companion object {
        const val FACTORY_FLOOR = 6
        const val DAG_SUPPORT = "modules/dag/src/test/kotlin/co/datapipelines/executor/RedisSupport.kt"
        const val APP_YML = "modules/app/src/main/resources/application.yml"
        const val COMMAND_VAR = "DATAPIPELINES_REDIS_COMMAND_TIMEOUT"
        const val CONNECT_VAR = "DATAPIPELINES_REDIS_CONNECT_TIMEOUT"
        const val BRIDGE_COMMAND = "spring.data.redis.timeout"
        const val BRIDGE_CONNECT = "spring.data.redis.connect-timeout"
        const val OPERATOR_COMMAND = "datapipelines.redis.command-timeout"
        const val OPERATOR_CONNECT = "datapipelines.redis.connect-timeout"
    }
}
