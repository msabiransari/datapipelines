import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Keeps [RepoTestInputs] — the repository files every test task declares as inputs (#718) —
 * in step with the repo's `.gitignore`, the source of truth for what is not repository
 * content.
 *
 * For every `.gitignore` entry the test materialises sample paths the entry matches (a
 * root-level and a nested one for an unanchored entry) inside a scratch project, resolves
 * [RepoTestInputs.files] there through a real Gradle project, and asserts each sample is
 * EXCLUDED; for every negation, each sample is INCLUDED. A `.gitignore` line added without
 * its mirror in [RepoTestInputs.EXCLUDES] fails here, naming the line — a new ignored data
 * directory would otherwise be hashed into every test task's history, and a new secret
 * pattern would put a secret file's content hash there.
 *
 * The content side is pinned too: the files the 718 inventory found tests reading through
 * the repo-root walk stay inputs.
 */
class RepoTestInputsTest {
    @TempDir
    lateinit var scratch: File

    private val repoRoot: File
        get() =
            File(requireNotNull(System.getProperty("repo.catalog")) { "repo.catalog not set — run via buildSrc's test task" })
                .parentFile
                .parentFile

    /** `.gitignore` negations deliberately NOT honoured, each with the reason it is safe. */
    private val unhonouredNegations =
        mapOf(
            // .claude/ is agent state and lane worktrees; the one tracked entry under it is a
            // symlink into .agents/ that no test reads.
            "!.claude/skills/" to "agent state stays out",
            // No tracked file lives there; build/ anywhere is Gradle output.
            "!buildSrc/src/**/build/" to "build output stays out",
        )

    private fun gitignoreEntries(): List<String> =
        repoRoot
            .resolve(".gitignore")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Concrete relative paths the gitignore entry matches. */
    private fun samples(entry: String): List<String> {
        val directory = entry.endsWith("/")
        val body = entry.trimEnd('/')
        val anchored = body.startsWith("/") || "/" in body
        val concrete = body.removePrefix("/").replace("**", "a").replace("*", "x")
        val bases = if (anchored) listOf(concrete) else listOf(concrete, "nested/deeper/$concrete")
        return bases.map { if (directory) "$it/sample.txt" else it }
    }

    /** The repo-relative paths [RepoTestInputs.files] resolves to over [paths] materialised in a scratch project. */
    private fun declared(paths: Collection<String>): Set<String> {
        paths.forEach { scratch.resolve(it).apply { parentFile.mkdirs() }.writeText("x\n") }
        val project = ProjectBuilder.builder().withProjectDir(scratch).build()
        return RepoTestInputs
            .files(project)
            .files
            .map { it.relativeTo(scratch).invariantSeparatorsPath }
            .toSet()
    }

    @Test
    fun `every gitignore entry is mirrored - ignored samples are excluded and negated ones included`() {
        val entries = gitignoreEntries()
        // Non-vacuity: the real .gitignore has dozens of entries, among them the secrets.
        assertTrue(entries.size >= 40, "expected the repo .gitignore, read ${entries.size} entries")
        assertTrue("deploy/secrets.env" in entries && "secrets.*" in entries, "the secrets entries moved: $entries")

        val positive = entries.filterNot { it.startsWith("!") }.associateWith { samples(it) }
        val negated = entries.filter { it.startsWith("!") && it !in unhonouredNegations }.associateWith { samples(it.drop(1)) }
        val inputs = declared(positive.values.flatten() + negated.values.flatten())

        val leaked = positive.flatMap { (entry, paths) -> paths.filter { it in inputs }.map { "$entry -> $it" } }
        assertEquals(emptyList<String>(), leaked, "gitignored paths that would become test inputs; mirror them in RepoTestInputs.EXCLUDES")
        val hidden = negated.flatMap { (entry, paths) -> paths.filterNot { it in inputs }.map { "$entry -> $it" } }
        assertEquals(
            emptyList<String>(),
            hidden,
            "tracked paths a .gitignore negation keeps that the inputs drop; mirror them in REINCLUDES",
        )
    }

    @Test
    fun `the files tests read through the repo-root walk stay inputs`() {
        val read =
            listOf(
                "docs/configuration.md",
                "docs/superpowers/specs/record.md",
                "deploy/helm/datapipelines/templates/deployment.yaml",
                "deploy/env/defaults.env",
                "deploy/secrets.env.example",
                "deploy/compose.yml",
                "scripts/sample-data/content/examples.json",
                "modules/app/src/main/resources/application.yml",
                "modules/app/src/main/resources/db/migration/V1__initial_schema.sql",
                "modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt",
                "modules/visualization/schema/plotly/LICENSE",
                "plugins/datapipelines/skills/datapipelines/SKILL.md",
                "gradle/libs.versions.toml",
                "Dockerfile",
                "LICENSE",
                "DEVELOPMENT.md",
                "AGENTS.md",
                "README.md",
                "settings.gradle.kts",
            )
        val secret =
            listOf(
                "deploy/secrets.env",
                "modules/app/build/tmp/x.txt",
                "scripts/sample-data-lake/work/t.parquet",
                ".git/HEAD",
                "gate.log",
            )

        val inputs = declared(read + secret)

        assertEquals(emptyList<String>(), read.filterNot { it in inputs }, "repo files tests read dropped from the inputs")
        assertEquals(
            emptyList<String>(),
            secret.filter { it in inputs },
            "secrets, build output, gate logs or local data leaked into the inputs",
        )
    }
}
