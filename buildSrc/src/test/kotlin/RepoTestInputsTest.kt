import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

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
 *
 * The existence-only list (#728) is pinned in both directions: every [RepoTestInputs.EXISTENCE_ONLY]
 * entry must be a path the content input really hides, and every excluded path a module or
 * integration test asserts ABSENT must be on the list. The scan reads source text line by line for
 * the negative-existence forms below (the statement that holds the assertion: back to the
 * enclosing `fun` or a blank line) and takes the plain string literals in it. It cannot see a
 * path assembled at runtime, a path relative to a variable directory, or an absence asserted in
 * a form not listed in `negativeExistence`; such a guard needs its path added by hand.
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

    /** Where the module and integration test sources live, relative to the repo root. */
    private val testSourceParents = listOf("modules", "tests")

    /** The forms in which a test asserts a path is absent; the inventory lane 728 ran found three sites. */
    private val negativeExistence =
        Regex("""exists\(\) shouldBe false|shouldNotExist|exists\(\)\)\.isFalse|assertFalse\([^)]*exists|Files\.notExists""")

    /** A plain string literal: no template, no escape. */
    private val plainLiteral = Regex(""""([^"\\$]+)"""")

    /** A repo-relative path: no scheme, no absolute root, no spaces. */
    private val pathShaped = Regex("""[\w.-]+(/[\w.-]+)*""")

    /** A string literal in the statement of a negative-existence assertion, with where it stands. */
    private data class AbsentCandidate(
        val where: String,
        val literal: String,
    )

    private fun testSourceFiles(): List<File> =
        testSourceParents
            .flatMap { parent ->
                repoRoot
                    .resolve(parent)
                    .listFiles { child -> child.isDirectory }
                    .orEmpty()
                    .toList()
            }.map { it.resolve("src/test/kotlin") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun absentCandidates(): List<AbsentCandidate> =
        testSourceFiles().flatMap { file ->
            val lines = file.readLines()
            lines.indices.filter { negativeExistence.containsMatchIn(lines[it]) }.flatMap { hit ->
                var start = hit
                while (start > 0 && lines[start - 1].isNotBlank() && !lines[start].contains("fun ")) start--
                (start..hit).flatMap { index ->
                    plainLiteral
                        .findAll(lines[index])
                        .map { it.groupValues[1] }
                        .filter { pathShaped.matches(it) }
                        .map { AbsentCandidate("${file.relativeTo(repoRoot).invariantSeparatorsPath}:${index + 1}", it) }
                        .toList()
                }
            }
        }

    /** Whether [RepoTestInputs.files] hides [path]: materialised alone, in a fresh scratch project. */
    private fun hidden(path: String): Boolean {
        val dir = Files.createTempDirectory(scratch.toPath(), "hidden").toFile()
        dir.resolve(path).apply { parentFile.mkdirs() }.writeText("x\n")
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        return RepoTestInputs.files(project).files.none { it.relativeTo(dir).invariantSeparatorsPath == path }
    }

    @Test
    fun `every existence-only entry is a file path the content input hides and is not a reinclude`() {
        // Non-vacuity: the list is not empty, so the checks below look at something.
        assertTrue(RepoTestInputs.EXISTENCE_ONLY.isNotEmpty(), "RepoTestInputs.EXISTENCE_ONLY is empty")
        val notLiteral = RepoTestInputs.EXISTENCE_ONLY.filterNot { pathShaped.matches(it) }
        assertEquals(emptyList<String>(), notLiteral, "EXISTENCE_ONLY holds literal repo-relative file paths, never patterns")
        val reincluded = RepoTestInputs.EXISTENCE_ONLY.filter { it in RepoTestInputs.REINCLUDES }
        assertEquals(emptyList<String>(), reincluded, "a REINCLUDES entry is tracked content, already an input")

        val visible = RepoTestInputs.EXISTENCE_ONLY.filterNot { hidden(it) }
        assertEquals(
            emptyList<String>(),
            visible,
            "EXISTENCE_ONLY entries the content input already sees (a lie about the list); drop them",
        )
    }

    @Test
    fun `every excluded path a test asserts absent is an existence-only entry`() {
        val candidates = absentCandidates()
        val excluded = candidates.filter { hidden(it.literal) }
        // Non-vacuity: the scan reaches the drift test's retired secrets template today.
        assertTrue(excluded.isNotEmpty(), "no excluded path found among ${candidates.size} candidates: the scan is blind")
        val unlisted = excluded.filter { it.literal !in RepoTestInputs.EXISTENCE_ONLY }
        assertEquals(
            emptyList<String>(),
            unlisted.map { "${it.where} asserts \"${it.literal}\" absent; add it to RepoTestInputs.EXISTENCE_ONLY" },
            "excluded paths asserted absent without an existence input: creating one would leave the guard UP-TO-DATE",
        )
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
