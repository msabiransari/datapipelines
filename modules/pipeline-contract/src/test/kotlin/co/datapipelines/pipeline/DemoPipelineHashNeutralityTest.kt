package co.datapipelines.pipeline

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The body-hash neutrality guard for additive node-shape changes (versioning §9.2, §15.2).
 *
 * `body_hash` is computed in Postgres over the stored body text, so a model change that let a
 * NEW field leak into the serialized form of an existing pipeline would move the hash of every
 * stored version and force every release to be re-signed. The shipped demo pipelines are the
 * witness set: each body is parsed and re-serialized through the current model, and the trees
 * must be EQUAL — `strict` on the node and `rejects` on a tempdb output (7c, #7) exist only
 * when declared, exactly like `kind`/`inputs`/`context_key` before them.
 */
class DemoPipelineHashNeutralityTest {
    @Test
    fun `every shipped demo pipeline body round-trips unchanged - the body hash cannot move`() {
        var count = 0
        DEMO_FILES.forEach { path ->
            val root = Fixtures.mapper.readTree(Fixtures.repoFile(path))
            require(root.path("pipelines").isArray && !root.path("pipelines").isEmpty) {
                "$path carries no pipelines — the witness set must not silently be empty"
            }
            root.path("pipelines").forEach { body ->
                val pipeline = Fixtures.mapper.treeToValue(body, Pipeline::class.java)
                val roundTripped = Fixtures.mapper.valueToTree<JsonNode>(pipeline)
                withClue("${body.path("name").asText()} ($path)") {
                    normalized(roundTripped) shouldBe normalized(body)
                }
                count++
            }
        }
        // The count is the evidence: the brief asks for it printed, and a suite that found no
        // pipelines would be vacuous (guarded above).
        println("demo pipeline bodies round-tripped unchanged: $count")
    }

    @Test
    fun `a node declaring strict and rejects round-trips them - the additive fields work when present`() {
        val body =
            Fixtures.mapper.readTree(
                """
                {
                  "schema_version": 1, "name": "test/hash_witness", "display_name": "x", "description": "x",
                  "settings": {"tempdb": {"engine": "H2"}},
                  "parameters": {},
                  "nodes": [
                    { "id": "shape", "description": "x", "type": "TRANSFORM",
                      "template": {"id": "acme/shape/x.jsonata", "version": 3},
                      "inputs": {"orders": "stg_orders"},
                      "output": {"target": "tempdb", "table": "order_lines", "rejects": "order_lines_rejected"},
                      "strict": true,
                      "depends_on": [] }
                  ]
                }
                """.trimIndent(),
            )
        val roundTripped = Fixtures.mapper.valueToTree<JsonNode>(Fixtures.mapper.treeToValue(body, Pipeline::class.java))
        withClue("a TRANSFORM node's own fields survive the round trip byte-identically") {
            normalized(roundTripped) shouldBe normalized(body)
        }
    }

    private companion object {
        val DEMO_FILES =
            listOf(
                "scripts/sample-data/content/examples.json",
                "scripts/sample-data/content/examples-lake.json",
            )

        /**
         * Drops the FOUR pre-existing serialization asymmetries this test is not about, each
         * verified older than this lane: `settings.tempdb.config` (absent → `{}`), the derived
         * `template.key` ("{id}@{version}"), and a CALCULATOR node's `source: ""` and
         * `template: {"id":"","version":0}` (the model's lenient defaults — the stored body
         * simply omits them). Everything ELSE must be equal, which is the body-hash
         * neutrality the guard exists to prove.
         */
        fun normalized(tree: JsonNode): JsonNode {
            val copy = tree.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            val tempdb = copy.path("settings").path("tempdb")
            if (tempdb is com.fasterxml.jackson.databind.node.ObjectNode) tempdb.remove("config")
            copy.path("nodes").forEach { node ->
                if (node is com.fasterxml.jackson.databind.node.ObjectNode) {
                    if (node.path("source").asText() == "") node.remove("source")
                    val template = node.path("template")
                    if (template is com.fasterxml.jackson.databind.node.ObjectNode) {
                        template.remove("key")
                        if (template.path("id").asText() == "" && template.path("version").asInt() == 0) node.remove("template")
                    }
                }
            }
            return copy
        }
    }
}
