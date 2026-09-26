package co.datapipelines.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What is left of the old DISTRIBUTED-artifact guards after 242a: the manual is rendered at
 * boot from one home, so the packaged-copy, tools-render and plugin-mirror parity tests are
 * gone with the copies they held. What still exists to guard here is
 *
 *  - the **plugin pointer** (record §9, ruling O1): under 20 lines, front matter that names
 *    the product, and a body that points at the SERVER's manual — the one remaining mirror,
 *    and a pointer cannot drift, only rot, so the assertions are about what it says;
 *  - the **§15 Delivery 1 block** (241): mcp-server.md's copy of the served handshake, pinned
 *    verbatim like every other copy of the instructions;
 *  - the **handshake's learning pointers** (144): every `docs_get {"name": …}` the
 *    instructions name must resolve against the SERVED set — through the one-release
 *    aliases, so `skill` and `authoring-playbook` keep answering while they exist.
 */
class SkillDistributionTest {
    @Test
    fun `the plugin pointer stays a pointer`() {
        val file = File(SpecFiles.root, PLUGIN_SKILL)
        val lines = file.readLines()
        withClue("the plugin pointer is ${lines.size} lines — the record's ruling O1 caps it at 20") {
            lines.size shouldBeLessThanOrEqual 20
        }
        val text = file.readText()
        withClue("the pointer's front matter must name the product") {
            text shouldContain "name: datapipelines"
        }
        withClue("the pointer must point at the server's manual, not carry one") {
            text shouldContain "docs_get"
            text shouldContain "skill"
        }
        withClue("the pointer must not grow a references/ directory again") {
            File(file.parentFile, "references").exists() shouldBe false
        }
    }

    @Test
    fun `mcp-server_md §15 carries the served handshake verbatim - 241`() {
        // The spec's copy of Delivery 1 is a copy like the jar's: a reader of the spec must
        // see the text a client receives, not a paraphrase of an older one.
        val spec = SpecFiles.read(SpecFiles.MCP_SPEC_PATH)
        val from = spec.indexOf(DELIVERY_1)
        val to = spec.indexOf(DELIVERY_2)
        withClue("mcp-server.md lost its §15 Delivery 1/2 headings") { (from in 0 until to) shouldBe true }
        val block =
            requireNotNull(TEXT_BLOCK.find(spec.substring(from, to))) {
                "§15 Delivery 1 has no ```text block holding the handshake"
            }.groupValues[1]

        withClue("mcp-server.md §15 Delivery 1 is not the served text — copy server-instructions.txt into it") {
            block shouldBe McpServerFactory.SERVER_INSTRUCTIONS
        }
    }

    @Test
    fun `every document the instructions point at is one the server actually serves - 144`() {
        // A broken learning pointer is the quietest way to strand a remote agent: the
        // handshake says "read X" and X does not exist. Parse the docs_get pointers out of
        // the shipped instructions and resolve each against the SERVED set (aliases count:
        // they answer for one release, and their absence is a separate, visible removal).
        val pointed =
            INSTRUCTION_POINTER
                .findAll(McpServerFactory.SERVER_INSTRUCTIONS)
                .map { it.groupValues[1] }
                .toList()
        withClue("the instructions name no docs_get document — the learning path lost its pointer") {
            pointed.isNotEmpty() shouldBe true
        }
        val served = DocSetTestSupport.renderedDocSet()
        val unresolved = pointed.filter { served.resolve(it) == null }
        withClue("the instructions point at documents the server does not serve: $unresolved") {
            unresolved.shouldBeEmpty()
        }
        // The path's first two steps, in order: the core (by its alias, until O4 retires it),
        // then the playbook.
        pointed shouldContain "skill"
        pointed shouldContain "authoring-playbook"
    }

    private companion object {
        const val PLUGIN_SKILL = "plugins/datapipelines/skills/datapipelines/SKILL.md"

        /** 144 — a `docs_get {"name": "…"}` pointer in the connect-time instructions. */
        val INSTRUCTION_POINTER = Regex("""docs_get \{"name": "([a-z0-9-]+)"\}""")

        const val DELIVERY_1 = "**Delivery 1 — the handshake (push).**"
        const val DELIVERY_2 = "**Delivery 2 — the resource (pull, MCP).**"

        /** The one fenced `text` block between them: the served handshake, verbatim. */
        val TEXT_BLOCK = Regex("```text\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)
    }
}
