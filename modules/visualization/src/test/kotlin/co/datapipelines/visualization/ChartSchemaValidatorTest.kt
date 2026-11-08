package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.SchemaException
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * The v1 schema validator's own guarantees (the 860 brief's Security section): it compiles the classpath copy and
 * nothing else, it NEVER fetches (an unmapped `$ref` fails fast and offline, proven against a listening socket that sees
 * no connection — and falsified by the library's own fetch, which the same socket does see), and every regex pattern
 * in the files is pinned and linear on adversarial input.
 */
class ChartSchemaValidatorTest {
    @Test
    fun `the classpath schemas compile and accept the worked document's stored form`() {
        val stored: JsonNode = ArtifactJson.mapper.valueToTree(ValidatorFakes.visualizationDocument(DocumentFixtures.visualization()).body)
        ChartSchemaValidator().validate(stored).shouldBeEmpty()
        // And refuse at the instance it fails, by keyword and pointer — never by message.
        (stored as com.fasterxml.jackson.databind.node.ObjectNode).put("bogus", 1)
        ChartSchemaValidator().validate(stored) shouldBe listOf(ChartSchemaProblem("additionalProperties", ""))
    }

    @Test
    fun `every served file carries its v1 id and the schema files are exactly the served list`() {
        ChartSchemas.FILES.forEach { file ->
            val tree = ArtifactJson.mapper.readTree(ChartSchemas.bytes(file))
            tree.path("\$id").asText() shouldBe ChartSchemas.ID_PREFIX + file
            tree.path("\$schema").asText() shouldBe "https://json-schema.org/draft/2020-12/schema"
        }
        shouldThrow<IllegalArgumentException> { ChartSchemas.bytes("../../application.yml") }
    }

    @Test
    fun `the validator never fetches - an unmapped remote ref fails fast and the listening socket sees no connection`() {
        Listener().use { listener ->
            val validator = ChartSchemaValidator(resources = mapOf(ENTRY to referring(listener.url)), entry = ENTRY)
            val started = System.nanoTime()
            shouldThrow<SchemaException> { validator.validate(ArtifactJson.mapper.readTree("""{"x":1}""")) }
            (System.nanoTime() - started) / NANOS_PER_MILLI shouldBeLessThan FAIL_FAST_MILLIS
            listener.settle()
            listener.accepted shouldBe 0
        }
    }

    @Test
    fun `falsified - the same registry with the library's fetch ON does reach the socket, so a silent fetch would be seen`() {
        Listener().use { listener ->
            val resources = mapOf(ENTRY to referring(listener.url))
            val fetching =
                SchemaRegistry
                    .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                        builder.schemaLoader { loader -> loader.fetchRemoteResources(true).resourceLoaders { it.resources(resources) } }
                    }.getSchema(SchemaLocation.of(ENTRY))
            runCatching { fetching.validate(ArtifactJson.mapper.readTree("""{"x":1}""")) }
            listener.settle()
            listener.accepted shouldBeGreaterThan 0
        }
    }

    @Test
    fun `every pattern in the v1 schemas is pinned - a new one is reviewed here - and linear on adversarial input`() {
        val patterns = ChartSchemas.FILES.flatMap { patternsOf(ArtifactJson.mapper.readTree(ChartSchemas.bytes(it))) }.toSet()
        patterns shouldBe PINNED_PATTERNS.keys
        PINNED_PATTERNS.forEach { (pattern, adversary) ->
            withClue(pattern) {
                val compiled = Pattern.compile(pattern)
                val started = System.nanoTime()
                compiled.matcher(adversary).find() shouldBe false
                (System.nanoTime() - started) / NANOS_PER_MILLI shouldBeLessThan LINEAR_MILLIS
            }
        }
    }

    private fun patternsOf(node: JsonNode): List<String> =
        when {
            node.isObject -> {
                node.properties().flatMap { (key, value) ->
                    if (key == "pattern" && value.isTextual) listOf(value.asText()) else patternsOf(value)
                }
            }

            node.isArray -> {
                node.flatMap(::patternsOf)
            }

            else -> {
                emptyList()
            }
        }

    private fun referring(url: String): String =
        """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","${'$'}id":"$ENTRY",
            "properties":{"x":{"${'$'}ref":"$url"}}}"""

    /** A loopback socket that counts every connection it accepts (and closes each at once). */
    private class Listener : AutoCloseable {
        private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        private val count = AtomicInteger()
        private val thread =
            Thread {
                runCatching {
                    while (!server.isClosed) {
                        server.accept().close()
                        count.incrementAndGet()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

        val url: String = "http://127.0.0.1:${server.localPort}/remote.schema.json"
        val accepted: Int get() = count.get()

        /** Lets an in-flight accept land before the count is read (bounded; the zero case simply waits it out). */
        fun settle() {
            thread.join(SETTLE_MILLIS)
        }

        override fun close() = server.close()
    }

    private companion object {
        const val ENTRY = "https://datapipelines.co/schemas/chart/1/probe.schema.json"
        const val BACKLOG = 50
        const val NANOS_PER_MILLI = 1_000_000L
        const val FAIL_FAST_MILLIS = 5_000L
        const val LINEAR_MILLIS = 2_000L
        const val SETTLE_MILLIS = 300L
        const val ADVERSARY_LENGTH = 100_000

        /** Each pattern in the files, with an input that nearly matches for its whole length and fails at the end. */
        val PINNED_PATTERNS: Map<String, String> =
            mapOf(
                "^[^\\u0000-\\u001f\\u007f-\\u009f]*$" to "a".repeat(ADVERSARY_LENGTH) + "\u0001",
                "^[^\\u0000-\\u0009\\u000b-\\u001f\\u007f-\\u009f]*$" to "a\n".repeat(ADVERSARY_LENGTH / 2) + "\u0001",
                "^[a-z][a-z0-9_]{0,63}$" to "a" + "b".repeat(ADVERSARY_LENGTH),
                "^[^\\u0000-\\u001f\\u007f-\\u009f]+$" to "a".repeat(ADVERSARY_LENGTH) + "\u0001",
                "^[a-z0-9_.-]+(/[a-z0-9_.-]+){1,9}$" to "a/".repeat(ADVERSARY_LENGTH / 2) + "!",
                "^(integer|percent|compact|decimal\\([0-6]\\)|currency\\([A-Z]{3}\\))$" to "decimal(" + "1".repeat(ADVERSARY_LENGTH),
            )
    }
}
