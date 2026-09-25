package co.datapipelines.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * #241 — the connect-time text is judged by what a client SHOWS the model, not by what the
 * server sends.
 *
 * Claude Code cuts an MCP server's `instructions`, and every tool's `description`, at
 * [CLIENT_CAP] characters and appends `… [truncated]`; the rest never reaches the model.
 * The 095 guard capped BYTES at 4 KB and looked for its phrases anywhere in the string, so on
 * `a3e79706` the handshake was 3,157 characters, its manual-discovery paragraph began at
 * character 2,687 — and the file, the test and the jar were all green while every default
 * Claude Code session read a handshake that ended mid-word (`a datasou… [truncated]`) and
 * never learned the manual exists.
 *
 * So every assertion here runs over [clientVisible] — the text as the runtime truncates it —
 * and the handshake keeps [INSTRUCTIONS_BUDGET]'s headroom under the cap, so a sentence added
 * in a hurry does not land exactly on the cut. Falsified at birth (#241 evidence): padding the
 * file past the cap turns the budget red; moving the discovery paragraph past the cut turns the
 * prefix red; a planted 2,100-character tool description turns the catalog red.
 */
class McpClientCapTest {
    private val instructions = McpServerFactory.SERVER_INSTRUCTIONS

    @Test
    fun `the handshake fits the client cap with headroom, in characters and in bytes`() {
        val bytes = instructions.toByteArray(Charsets.UTF_8).size

        // Both units: the runtime counts characters (UTF-16 units, Kotlin's `length`); bytes
        // are what we ship and are never fewer, so a byte budget cannot be met by accident.
        assertAll(
            { withClue("handshake characters") { instructions.length shouldBeLessThanOrEqual INSTRUCTIONS_BUDGET } },
            { withClue("handshake UTF-8 bytes") { bytes shouldBeLessThanOrEqual INSTRUCTIONS_BUDGET } },
        )
    }

    @Test
    fun `everything a truncated client must still get is inside the visible prefix`() {
        val visible = clientVisible(instructions)
        val missing = REQUIRED_IN_PREFIX.filterNot { visible.contains(it) }

        withClue(
            "past the first $CLIENT_CAP characters, so a default Claude Code session never sees them: $missing",
        ) { missing.shouldBeEmpty() }
    }

    @Test
    fun `the workspace scope comes first and manual discovery is the first rule after it`() {
        // A.1's ranking — what a truncated client must still get, most important first. Only
        // the markers' ORDER is pinned; the wording between them is free.
        instructions shouldStartWith WORKSPACE_STATEMENT
        val positions = RANKED_MARKERS.map { it to instructions.indexOf(it) }

        withClue("a ranked marker is missing or out of order: $positions") {
            positions.all { it.second >= 0 } shouldBe true
            positions.map { it.second } shouldBe positions.map { it.second }.sorted()
        }
    }

    @Test
    fun `every shipped tool description fits the client cap`() {
        val shipped = realShippedTools().associate { it.name to it.definition }
        // Resolved through the catalog, so a tool the bean stops shipping is an error here,
        // not a silently smaller sweep.
        val definitions = McpToolCatalog.NAMES.map { name -> requireNotNull(shipped[name]) { "not shipped: $name" } }

        definitions.size shouldBeGreaterThan 0
        withClue("tool descriptions a default Claude Code session would truncate") {
            oversized(definitions).shouldBeEmpty()
        }
    }

    @Test
    fun `the description guard flags a planted oversized tool and passes one exactly at the cap`() {
        val atCap = McpTools.tool("at_cap", "x".repeat(CLIENT_CAP), EMPTY_SCHEMA)
        val planted = McpTools.tool("planted", "x".repeat(PLANTED_LENGTH), EMPTY_SCHEMA)

        // The runtime keeps a description whose length is <= the cap, whole.
        oversized(listOf(atCap, planted)) shouldContainExactly listOf("planted ($PLANTED_LENGTH chars)")
    }

    private fun oversized(tools: List<McpSchema.Tool>): List<String> =
        tools
            .filter { clientVisible(it.description().orEmpty()) != it.description().orEmpty() }
            .map { "${it.name()} (${it.description().orEmpty().length} chars)" }

    private companion object {
        /**
         * Claude Code's default cap on an MCP server's instructions and on each tool's
         * description, in characters: `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`, which a session
         * may raise and a customer should never have to. Read from the runtime, not a doc page —
         * Claude Code 2.1.283's bundle has `Qyo=2048` and `YU()` returning
         * `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH ?? Qyo`, compared with the JS `String.length`
         * (UTF-16 units — the same count as Kotlin's `String.length`).
         */
        const val CLIENT_CAP = 2048

        /** The handshake's own budget: 10 % headroom under [CLIENT_CAP] (the #242 record targets 1,800). */
        const val INSTRUCTIONS_BUDGET = CLIENT_CAP * 9 / 10

        const val PLANTED_LENGTH = 2100

        const val EMPTY_SCHEMA = """{"type": "object", "properties": {}}"""

        /** Workspaces design §9 — the fact an agent needs before its first tool call. */
        const val WORKSPACE_STATEMENT = "This server is workspace-scoped"

        /** The text a client shows the model: the runtime keeps `length <= cap` whole, else cuts. */
        fun clientVisible(text: String): String = if (text.length <= CLIENT_CAP) text else text.take(CLIENT_CAP)

        /**
         * A.1 — scope, then documentation discovery (the core, the playbook beyond two nodes,
         * the task's reference, the resource and the HTTP twin for a client without MCP, for
         * creating and updating alike), then the draft rule, the names rule, the datasource
         * rule and the three recoveries.
         */
        val REQUIRED_IN_PREFIX =
            listOf(
                WORKSPACE_STATEMENT,
                """docs_get {"name": "skill"}""",
                """docs_get {"name": "authoring-playbook"}""",
                "docs_list",
                McpResourceUri.skill(),
                "GET /skill.md",
                "updating",
                "DRAFT",
                "until a person releases it",
                "confirm_new_root",
                "HUMANS REGISTER DATASOURCES",
                "pipeline.version.conflict",
                "error.exception.caused_by",
                "error.correlation_id",
            )

        /** The rules' markers, in A.1's order. */
        val RANKED_MARKERS =
            listOf(
                WORKSPACE_STATEMENT,
                """docs_get {"name": "skill"}""",
                "DRAFT",
                "confirm_new_root",
                "HUMANS REGISTER DATASOURCES",
                "pipeline.version.conflict",
            )
    }
}
