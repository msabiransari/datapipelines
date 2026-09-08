package co.datapipelines.mcp

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
 * The caps are the other half. A skill that grows without a limit stops being read: the
 * connect-time `instructions` string is paid for by every session of every client (4 KB), and
 * `SKILL.md` is paid for by every trigger (400 lines). The references are what grows.
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
    fun `the connect-time instructions fit the 4 KB budget and point at the full manual`() {
        val bytes = McpServerFactory.SERVER_INSTRUCTIONS.toByteArray(Charsets.UTF_8)

        assertAll(
            { bytes.size shouldBeLessThanOrEqual MAX_INSTRUCTIONS_BYTES },
            // The workspace statement stays first: it is the fact an agent needs before its
            // first tool call (workspaces design §9, quoted in mcp-server.md §5.1).
            { McpServerFactory.SERVER_INSTRUCTIONS shouldContain "This server is workspace-scoped" },
            // …and the last line is the handshake's whole point: where the rest lives.
            { McpServerFactory.SERVER_INSTRUCTIONS shouldContain McpResourceUri.skill() },
            { McpServerFactory.SERVER_INSTRUCTIONS shouldContain "confirm_new_root" },
            { McpServerFactory.SERVER_INSTRUCTIONS shouldContain "pipeline.version.conflict" },
        )
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

        /** 095 §C1/§E — every client injects this into every session. */
        const val MAX_INSTRUCTIONS_BYTES = 4096

        const val PLUGIN_SKILL_DIR = "plugins/datapipelines/skills/datapipelines"
    }
}
