package co.datapipelines.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.io.File

/**
 * The skill is a DISTRIBUTED artifact now (095), not a file that happens to sit in the repo:
 * it is packaged into the jar, served over MCP and HTTP, and copied into a Claude Code
 * plugin. Every copy is derived from `.agents/skills/datapipelines/`, and each of the four
 * deliveries has an assertion here that goes red when its copy drifts.
 *
 * The caps are the other half. A skill that grows without a limit stops being read: `SKILL.md`
 * is paid for by every trigger (400 lines), and the references are what grows. The connect-time
 * `instructions` string is capped by what a client SHOWS, which is [McpClientCapTest]'s (#241);
 * its spec copy, the §15 Delivery 1 block, is pinned here like every other copy.
 */
class SkillDistributionTest {
    private val repoSkill = File(SpecFiles.root, SpecFiles.SKILL_DIR)
    private val repoReferences = File(repoSkill, "references")

    @Test
    fun `SKILL_md stays inside the format's budget`() {
        val lines = File(repoSkill, "SKILL.md").readLines()

        // The Agent Skills guidance is ~500 lines; this file was 697 before the split, and the
        // whole point of references/ is that the core does not grow back. 400 is the cap the
        // build enforces; 300 is the target the split landed at.
        lines.size shouldBeLessThanOrEqual MAX_SKILL_LINES
    }

    @Test
    fun `no prose line of SKILL_md folds past the readable column`() {
        // 131 §A.3 — the 400-line cap is met by CHOOSING, never by folding: a 1,300-char
        // paragraph on one line satisfies the count and defeats the purpose (it costs the
        // same tokens and reads worse). Exempt: the YAML front matter (the description is
        // one line by the format's own rule, pinned above), fenced code blocks, and table
        // rows — none of those is prose a wrapper can reflow.
        val lines = File(repoSkill, "SKILL.md").readLines()
        val offenders = mutableListOf<String>()
        var inFrontMatter = false
        var inFence = false
        lines.forEachIndexed { index, line ->
            val n = index + 1
            when {
                n == 1 && line == "---" -> {
                    inFrontMatter = true
                }

                inFrontMatter && line == "---" -> {
                    inFrontMatter = false
                }

                inFrontMatter -> {
                    // Exempt: the description is one line by the format's own rule.
                }

                line.trimStart().startsWith("```") -> {
                    inFence = !inFence
                }

                inFence || line.trimStart().startsWith("|") -> {
                    // Exempt: code lines and table rows are not wrappable prose.
                }

                line.length > MAX_SKILL_LINE_CHARS -> {
                    offenders += "SKILL.md:$n (${line.length} chars): ${line.take(80)}…"
                }
            }
        }
        withClue(offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
    }

    @Test
    fun `the front matter is unchanged — it is the trigger, and it names the tools`() {
        val lines = File(repoSkill, "SKILL.md").readLines()

        assertAll(
            { lines[0] shouldBe "---" },
            { lines[1] shouldBe "name: datapipelines" },
            // The description IS the trigger an agent matches on; it names the tools and the
            // prompts by their wire names, so a client that only sees front matter can still
            // decide the skill applies. Reworded casually, the skill stops firing.
            { lines[2] shouldContain "Author, maintain, and execute declarative SQL data pipelines" },
            { lines[2] shouldContain "pipelines_create" },
            { lines[2] shouldContain "debug_failed_execution" },
            { lines[3] shouldBe "---" },
        )
    }

    @Test
    fun `every reference file is listed in SKILL_md's map, and every listed one exists`() {
        val skill = File(repoSkill, "SKILL.md").readText()
        val onDisk =
            repoReferences
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()

        assertAll(
            { onDisk.isNotEmpty() shouldBe true },
            // A reference nothing points at is a file no agent opens; a pointer with no file
            // is a promise the resource surface would 404 on.
            { onDisk.filterNot { skill.contains("references/$it") } shouldContainExactly emptyList() },
            {
                Regex("""references/([a-z0-9-]+\.md)""")
                    .findAll(skill)
                    .map { it.groupValues[1] }
                    .toSortedSet()
                    .toList() shouldContainExactly onDisk
            },
        )
    }

    @Test
    fun `mcp-server_md §15 carries the served handshake verbatim - 241`() {
        // The spec's copy of Delivery 1 is a copy like the jar's and the plugin's: a reader of
        // the spec must see the text a client receives, not a paraphrase of an older one.
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
        // the shipped instructions and resolve each against the served catalog.
        val pointed =
            INSTRUCTION_POINTER
                .findAll(McpServerFactory.SERVER_INSTRUCTIONS)
                .map { it.groupValues[1] }
                .toList()
        withClue("the instructions name no docs_get document — the learning path lost its pointer") {
            pointed.isNotEmpty() shouldBe true
        }
        val known = SkillDocs.catalog.map { it.name }.toSet()
        withClue("the instructions point at documents the server does not serve: ${pointed - known}") {
            (pointed - known).shouldBeEmpty()
        }
        // The path's first two steps, in order: the core, then the playbook.
        pointed shouldContain "skill"
        pointed shouldContain "authoring-playbook"
    }

    @Test
    fun `the packaged copy is the repo file, byte for byte, for every file in the skill`() {
        // Both directions: a reference the jar carries but the repo does not is just as broken
        // as the reverse, and only a set comparison catches it.
        SkillDocs.references.keys.sorted() shouldContainExactly referenceNames()
        SkillDocs.skill shouldBe File(repoSkill, "SKILL.md").readText()
        referenceNames().forEach { name ->
            SkillDocs.reference(name) shouldBe File(repoReferences, "$name.md").readText()
        }
    }

    @Test
    fun `references_tools_md is exactly what the tool catalog renders`() {
        val committed = File(repoReferences, "tools.md").readText()

        // Flip a tool's description in its §6.2 schema and this goes red — the drift guard
        // that lets the handwritten sections NAME tools without listing them.
        withDriftHint { committed shouldBe SkillToolsDoc.render(realShippedTools()) }
    }

    @Test
    fun `the Claude Code plugin's copy of the skill is the same skill`() {
        val pluginSkill = File(SpecFiles.root, PLUGIN_SKILL_DIR)
        val repoFiles = filesOf(repoSkill)
        val pluginFiles = filesOf(pluginSkill)

        // A marketplace is fetched with git, so the plugin's skill is a COPY (a symlink out of
        // the plugin directory is skipped on install). A copy is a thing that drifts, which is
        // why this test exists rather than a comment saying "keep these in sync".
        withPluginHint { pluginFiles.keys.sorted() shouldContainExactly repoFiles.keys.sorted() }
        repoFiles.forEach { (path, text) -> withPluginHint { pluginFiles[path] shouldBe text } }
    }

    private fun referenceNames(): List<String> =
        repoReferences
            .listFiles()
            .orEmpty()
            .map { it.name.removeSuffix(".md") }
            .sorted()

    /** Every markdown file of a skill directory, keyed by its path relative to that directory. */
    private fun filesOf(dir: File): Map<String, String> =
        dir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".md") }
            .associate { it.relativeTo(dir).path to it.readText() }

    private fun withDriftHint(assertion: () -> Unit) =
        runCatching(assertion)
            .onFailure {
                throw AssertionError(
                    "references/tools.md is not what the shipped tool catalog renders. It is GENERATED — " +
                        "run ./gradlew :modules:mcp-server:skillToolsDoc and commit the result.",
                    it,
                )
            }.getOrThrow()

    private fun withPluginHint(assertion: () -> Unit) =
        runCatching(assertion)
            .onFailure {
                throw AssertionError(
                    "$PLUGIN_SKILL_DIR is not a copy of ${SpecFiles.SKILL_DIR}. Run " +
                        "./gradlew :modules:mcp-server:skillArtifacts and commit the result.",
                    it,
                )
            }.getOrThrow()

    private companion object {
        /** 095 §A/§E — the format's guidance is ~500 lines; the core is capped well under it. */
        const val MAX_SKILL_LINES = 400

        /** 131 §A.3 — the line cap is met by choosing, never by folding a paragraph onto one line. */
        const val MAX_SKILL_LINE_CHARS = 200

        const val PLUGIN_SKILL_DIR = "plugins/datapipelines/skills/datapipelines"

        /** 144 — a `docs_get {"name": "…"}` pointer in the connect-time instructions. */
        val INSTRUCTION_POINTER = Regex("""docs_get \{"name": "([a-z0-9-]+)"\}""")

        const val DELIVERY_1 = "**Delivery 1 — the handshake (push).**"
        const val DELIVERY_2 = "**Delivery 2 — the resource (pull, MCP).**"

        /** The one fenced `text` block between them: the served handshake, verbatim. */
        val TEXT_BLOCK = Regex("```text\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)
    }
}
