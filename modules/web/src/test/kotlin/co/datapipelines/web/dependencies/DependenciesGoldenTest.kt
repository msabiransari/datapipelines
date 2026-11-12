package co.datapipelines.web.dependencies

import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.config.ReleaseDependencyProperties
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * **The enveloped goldens no authored workspace can produce** (#875 G4 §E, record A47) — a cycle (`acyclic: false`; the
 * product refuses cycles at save), each traversal `LIMIT` (`complete: false`, `acyclic: null`; the limits are process
 * configuration) and the two 503s. Each is the whole answer of the real [DependenciesController], projection and
 * [ApiExceptionHandler] over a stub pipeline family, normalised exactly as `DependenciesGoldenE2eTest` normalises the
 * route cases, and compared with its file in `src/test/resources/dependencies/goldens/` — the same directory, which that
 * test asserts holds exactly both halves. Every normalised answer is also written to `build/dependencies-goldens/`.
 */
class DependenciesGoldenTest {
    private val clock = MovingTreeClock()
    private val pipelines = StubFamily(FamilyKey.PIPELINE)

    private val limits: GraphLimits =
        ReleaseDependencyProperties(metadataTimeoutSeconds = 1, statementTimeoutSeconds = 1).let {
            DependenciesConfiguration.treeLimits(it.toBounds(), it)
        }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `every case the stub family produces equals its committed golden, with the token it exists for`() {
        val answers = cases()
        val written = Files.createDirectories(Path.of("build/dependencies-goldens"))
        val mismatched =
            answers.mapNotNull { (case, body) ->
                val text = normalised(body)
                Files.writeString(written.resolve("$case.json"), text)
                val golden = GOLDENS.resolve("$case.json")
                case.takeUnless { Files.exists(golden) && Files.readString(golden) == text }
            }
        withClue("cases whose answer differs from (or has no) golden; the normalised answers are under ${written.toAbsolutePath()}") {
            mismatched.shouldBeEmpty()
        }
        val data = answers.mapValues { (_, body) -> MAPPER.readTree(body).path("data") }
        data.getValue("tree-acyclic-false").path("acyclic").asText() shouldBe "false"
        LIMITS.forEach { kind ->
            val tree = data.getValue("tree-limit-$kind")
            withClue(kind) {
                tree.path("complete").asBoolean() shouldBe false
                tree.path("acyclic").isNull shouldBe true
                tree.path("diagnostics").map { it.path("code").asText() } shouldContain "LIMIT"
            }
        }
        MAPPER
            .readTree(answers.getValue("metadata-unavailable"))
            .path("error")
            .path("code")
            .asText() shouldBe METADATA_UNAVAILABLE
        MAPPER
            .readTree(answers.getValue("response-too-large"))
            .path("error")
            .path("code")
            .asText() shouldBe TOO_LARGE
    }

    /** Each case: its world on the stub family, then the answer of one GET of `acme/root` at version 1. */
    private fun cases(): Map<String, String> =
        linkedMapOf(
            "tree-acyclic-false" to answer { cycle() },
            "tree-limit-depth" to answer(limits.copy(depth = 2)) { chain(4) },
            "tree-limit-nodes" to answer(limits.copy(nodes = 2)) { chain(4) },
            "tree-limit-edges" to answer(limits.copy(edges = 1)) { fanOut(missing = false) },
            "tree-limit-diagnostics" to answer(limits.copy(diagnostics = 1)) { fanOut(missing = true) },
            "metadata-unavailable" to
                answer {
                    chain(1)
                    pipelines.beforeBatch = { _, _ -> clock.advance(Duration.ofMillis(OVERRUN_MILLIS)) }
                },
            "response-too-large" to answer(limits.copy(wireBytes = SMALL_WIRE_BYTES)) { chain(2) },
        )

    private fun answer(
        limits: GraphLimits = this.limits,
        world: () -> Unit,
    ): String {
        pipelines.clear()
        world()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(member(), null, emptyList())
        val mvc =
            MockMvcBuilders
                .standaloneSetup(DependenciesController(treeService(listOf(pipelines), listOf(PIPELINE_ROW), limits, clock = clock)))
                .setControllerAdvice(ApiExceptionHandler())
                .build()
        return mvc
            .perform(tree("pipeline", "name=${enc(ROOT)}&version=1"))
            .andReturn()
            .response.contentAsString
    }

    /** `acme/root` → `acme/peer` → `acme/root`: the only shape `acyclic: false` has. */
    private fun cycle() {
        val root = exact(FamilyKey.PIPELINE, ROOT, 1)
        val peer = pipelines.put(exact(FamilyKey.PIPELINE, "acme/peer", 1), Lifecycle.RELEASED, root)
        pipelines.put(root, Lifecycle.RELEASED, peer)
    }

    /** `acme/root` over [depth] chained pipelines. */
    private fun chain(depth: Int) {
        var below = emptyArray<co.datapipelines.dependencies.ObjectRequest>()
        (depth downTo 1).forEach { level ->
            below =
                arrayOf(pipelines.put(exact(FamilyKey.PIPELINE, "acme/level_$level", 1), Lifecycle.RELEASED, *below))
        }
        pipelines.put(exact(FamilyKey.PIPELINE, ROOT, 1), Lifecycle.RELEASED, *below)
    }

    /** `acme/root` pinning two pipelines — resolved, or ([missing]) absent, each then a `MISSING` diagnostic. */
    private fun fanOut(missing: Boolean) {
        val children = listOf("acme/left", "acme/right").map { exact(FamilyKey.PIPELINE, it, 1) }
        if (!missing) children.forEach { pipelines.put(it) }
        pipelines.put(exact(FamilyKey.PIPELINE, ROOT, 1), Lifecycle.RELEASED, *children.toTypedArray())
    }

    private companion object {
        const val ROOT = "acme/root"
        const val OVERRUN_MILLIS = 1_100L
        const val SMALL_WIRE_BYTES = 256
        const val METADATA_UNAVAILABLE = "dependencies.metadata.unavailable"
        const val TOO_LARGE = "dependencies.response.too_large"
        const val OBSERVED_AT = "2026-01-01T00:00:00Z"
        val LIMITS = listOf("depth", "nodes", "edges", "diagnostics")
        val MAPPER = jacksonObjectMapper()
        val PIPELINE_ROW = DependencyFamilyTransport.production(DependenciesRequestTest.LENSES).row(FamilyKey.PIPELINE)
        val GOLDENS: Path = Path.of("src/test/resources/dependencies/goldens")

        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH_PATTERN = Regex("\"[0-9a-f]{64}\"")
        private val PRINTER =
            DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .apply { indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE) }

        /** `DependenciesGoldenE2eTest.normalised`, the same rules: one golden directory, one normal form. */
        fun normalised(body: String): String {
            val tree = MAPPER.readTree(body)
            (tree.path("data") as? ObjectNode)?.takeIf { it.has("observed_at") }?.put("observed_at", OBSERVED_AT)
            val printed = MAPPER.writer(PRINTER).writeValueAsString(tree)
            val uuids = LinkedHashMap<String, String>()
            val hashes = LinkedHashMap<String, String>()
            val ids = UUID_PATTERN.replace(printed) { m -> uuids.getOrPut(m.value) { "00000000-0000-4000-8000-%012d".format(uuids.size) } }
            return HASH_PATTERN.replace(ids) { m -> hashes.getOrPut(m.value) { "\"%064d\"".format(hashes.size + 1) } } + "\n"
        }
    }
}
