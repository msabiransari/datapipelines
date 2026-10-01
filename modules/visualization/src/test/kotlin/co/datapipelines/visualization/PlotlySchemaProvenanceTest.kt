package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest

/**
 * The vendored Plotly schema's provenance (modules/visualization/schema/plotly/PROVENANCE.md): the
 * committed reduced resource IS the deterministic reducer's output over the checked-in upstream source,
 * the recorded hashes and facts still hold, and the reduction is byte-stable across runs. A drift here is
 * a red build, never a silent schema change.
 */
class PlotlySchemaProvenanceTest {
    private val source: JsonNode by lazy { ArtifactJson.mapper.readTree(SOURCE_FILE.readBytes()) }
    private val committed: ByteArray by lazy { ClassPathResource(PlotlySchema.SCHEMA_RESOURCE).inputStream.readBytes() }

    @Test
    fun `the committed resource is the reducer's output over the checked-in source - byte for byte`() {
        val sourceSha = sha256(SOURCE_FILE.readBytes())
        val reduced =
            PlotlySchemaReducer.reduce(source, sourceSha, PROVENANCE_COMMIT, PROVENANCE_VERSION)
        val regenerated = (ArtifactJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reduced) + "\n").toByteArray()
        withClue("run ./gradlew :modules:visualization:reducePlotlySchema and commit the diff") {
            regenerated shouldBe committed
        }
    }

    @Test
    fun `the reduction is deterministic - a second run over the same source yields the same bytes`() {
        val sourceSha = sha256(SOURCE_FILE.readBytes())
        val first = ArtifactJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            PlotlySchemaReducer.reduce(source, sourceSha, PROVENANCE_COMMIT, PROVENANCE_VERSION),
        )
        val second = ArtifactJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            PlotlySchemaReducer.reduce(source, sourceSha, PROVENANCE_COMMIT, PROVENANCE_VERSION),
        )
        first shouldBe second
    }

    @Test
    fun `the recorded provenance holds - source hash, upstream commit, release, license`() {
        val root = ArtifactJson.mapper.readTree(committed)
        root.path("source_sha256").asText() shouldBe sha256(SOURCE_FILE.readBytes())
        root.path("source_sha256").asText() shouldBe "64895178a4f8cbc3cd10d7824e7ee44439066c15c08d258a09f1e60f463b6d1a"
        root.path("upstream_commit").asText() shouldBe PROVENANCE_COMMIT
        root.path("upstream_commit").asText() shouldBe "0aabc3c5cc4f1d91fd6dd3846c5353de1beb0885"
        root.path("plotly_version").asText() shouldBe "4.1.1"
        root.path("plotly_version").asText() shouldBe PROVENANCE_VERSION
        PlotlySchema.plotlyVersion shouldBe "4.1.1"
        PlotlySchema.sourceSha256() shouldBe root.path("source_sha256").asText()
        PlotlySchemaReducer.SUPPORTED_TRACES.forEach { trace ->
            withClue(trace) { checkNotNull(PlotlySchema.traceAttributes(trace)).isObject shouldBe true }
        }
        LICENSE_FILE.readText().shouldNotBeEmpty()
        LICENSE_FILE.readText() shouldContain "MIT License"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val SOURCE_FILE = VisualizationTestFiles.repoFile("modules/visualization/schema/plotly/plot-schema-4.1.1.json")
        val LICENSE_FILE = VisualizationTestFiles.repoFile("modules/visualization/schema/plotly/LICENSE")
    }
}
