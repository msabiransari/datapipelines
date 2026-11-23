package co.datapipelines.mcp

import co.datapipelines.pipeline.Pipeline
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.json.McpJsonDefaults
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * #1100 — the MCP authoring surface carries the whole §3 pipeline document, `request_body` included.
 *
 * Before #1100 both write tools' strict input schemas omitted the declaration and [PipelineToolPayloads.bodyJson]
 * independently dropped it, so no MCP-authored pipeline could take a structured body. Three guards close the
 * class, not the one field: the authoritative JSON creator's wire names against each SHIPPED schema (a document
 * field a tool cannot accept is red, naming tool and field), every advertised document argument through the
 * builder (an argument the schema admits but the body loses is red), and the rendered reference an agent reads.
 */
class PipelineRequestBodyDeclarationToolTest {
    private val tools = realShippedTools().associateBy { it.name }

    /** The two tools that author a whole pipeline document. */
    private val writeTools = listOf("pipelines_create", "pipelines_update")

    /**
     * Arguments that steer the CALL, not the document: the update's target and precondition, and the two
     * entry-point acknowledgements. They need no document counterpart.
     */
    private val toolControls = setOf("id", "expected_hash", "confirm_new_root", "door_acknowledged")

    /** Server-owned: §6.2.4's schema has no such property, the server supplies v1 ([PipelineToolPayloads.bodyJson]). */
    private val serverOwned = setOf("schema_version")

    /** The wire property names of [Pipeline.fromJson] — the creator Jackson binds a stored or saved body with. */
    private fun creatorWireNames(): Set<String> {
        val creator =
            Pipeline::class.java.declaredMethods.single {
                Modifier.isStatic(it.modifiers) && it.isAnnotationPresent(JsonCreator::class.java)
            }
        return creator.parameterAnnotations
            .map { annotations -> annotations.filterIsInstance<JsonProperty>().single().value }
            .toSet()
    }

    private fun schemaProperties(tool: String): Set<String> {
        val schema =
            McpTools.readTree(
                McpJsonDefaults.getMapper().writeValueAsString(tools.getValue(tool).definition.inputSchema()),
            )
        return schema
            .path("properties")
            .properties()
            .map { it.key }
            .toSet()
    }

    @Test
    fun `the authoritative creator's wire names are non-empty and include the known optional fields`() {
        // Non-vacuity: an empty or truncated authoritative set would make the parity below pass trivially.
        creatorWireNames() shouldContainAll
            listOf("schema_version", "name", "display_name", "description", "settings", "parameters", "nodes", "checks", "request_body")
    }

    @Test
    fun `every document field the pipeline contract binds is an input of both write tools`() {
        val document = creatorWireNames() - serverOwned
        val missing =
            writeTools.flatMap { tool ->
                (document - schemaProperties(tool)).sorted().map { field -> "$tool is missing document field '$field'" }
            }
        missing.joinToString("\n") shouldBe ""
    }

    @Test
    fun `every input of both write tools is a document field or a named tool control`() {
        val document = creatorWireNames() - serverOwned
        val unexplained =
            writeTools.flatMap { tool ->
                (schemaProperties(tool) - document - toolControls).sorted().map { "$tool advertises '$it', which is not a document field" }
            }
        unexplained.joinToString("\n") shouldBe ""
    }

    @Test
    fun `the builder keeps every advertised document argument in the body it saves`() {
        // One value per advertised document argument, each distinguishable from a default the builder could invent.
        val values =
            mapOf(
                "name" to "test/request_body_parity",
                "display_name" to "Request body parity",
                "description" to "Round trip.",
                "parameters" to mapOf("region" to mapOf("type" to "STRING", "required" to false)),
                "settings" to mapOf("tempdb" to mapOf("engine" to "h2")),
                "nodes" to listOf(mapOf("id" to "read", "type" to "DQL")),
                "checks" to listOf(mapOf("id" to "c1")),
                "request_body" to mapOf("required" to true),
            )
        writeTools.forEach { tool ->
            val advertised = schemaProperties(tool) - toolControls
            withClue("$tool advertises a document argument this test supplies no value for") {
                values.keys shouldContainAll advertised
            }
            val body = McpTools.readTree(PipelineToolPayloads.bodyJson(McpArguments(values.filterKeys { it in advertised })))
            advertised.sorted().forEach { field ->
                withClue("$tool: argument '$field' did not reach the saved body") {
                    body.path(field) shouldBe McpTools.readTree(McpJsonDefaults.getMapper().writeValueAsString(values.getValue(field)))
                }
            }
            withClue("$tool: the server supplies schema_version") { body.path("schema_version").asInt() shouldBe 1 }
        }
    }

    @Test
    fun `an omitted declaration leaves the saved body without the member`() {
        val body =
            McpTools.readTree(
                PipelineToolPayloads.bodyJson(
                    McpArguments(mapOf("name" to "test/no_body", "display_name" to "No body", "nodes" to emptyList<Any>())),
                ),
            )
        body.has("request_body") shouldBe false
    }

    @Test
    fun `both schemas share one request_body property - an open object whose required is a JSON boolean defaulting to false`() {
        val expected = McpTools.readTree(PipelineToolPayloads.REQUEST_BODY_PROPERTY)
        writeTools.forEach { tool ->
            val schema =
                McpTools.readTree(McpJsonDefaults.getMapper().writeValueAsString(tools.getValue(tool).definition.inputSchema()))
            withClue(tool) {
                schema["properties"]["request_body"] shouldBe expected
                schema["additionalProperties"].asBoolean(true) shouldBe false
                expected["type"].asText() shouldBe "object"
                expected.has("additionalProperties") shouldBe false
                expected["properties"]["required"]["type"].asText() shouldBe "boolean"
                expected["properties"]["required"]["default"].asBoolean(true) shouldBe false
                expected["description"].asText() shouldContain "false by default"
            }
        }
    }

    @Test
    fun `the rendered pipelines-tools reference teaches the declaration, its default and the update omission`() {
        val reference = DocSetTestSupport.renderedDocSet().get("pipelines-tools").markdown
        writeTools.forEach { tool ->
            val section = reference.substringAfter("### `$tool`").substringBefore("\n### ")
            withClue("$tool section of pipelines-tools") {
                section shouldContain "| `request_body` | object | optional |"
                section shouldContain "required is a JSON boolean, false by default"
                section shouldContain "required defaults to false"
            }
        }
        val update = reference.substringAfter("### `pipelines_update`").substringBefore("\n### ")
        update shouldContain "omitting it removes the declaration"
    }
}
