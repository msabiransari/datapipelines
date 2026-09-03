package co.datapipelines.integration

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAnnotationNamed
import com.lemonappdev.konsist.api.verify.assertEmpty
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Cross-module architecture guards (module-structure.md §7.8), encoded with Konsist
 * over every module's PRODUCTION sources. Each guard mirrors a rule that previously
 * existed only as prose or convention, so a violation fails the build instead of
 * waiting for a reviewer to spot it.
 *
 * The scope is sliced to `src/main` deliberately: Spring's TestContext framework
 * field-injects `@Autowired lateinit var` into test classes by design, so the
 * constructor-injection rule below applies to production code only.
 */
class ArchitectureGuardTest {
    /**
     * Constructor injection only (house rule): no `@Autowired` on any property or
     * field in production sources. Spring test classes are exempt by scope, not by
     * silence — see the class KDoc.
     */
    @Test
    fun `no field injection in production code`() {
        productionScope()
            .properties()
            .withAnnotationNamed("Autowired")
            .assertEmpty()
    }

    /**
     * Zero DI stereotypes in production code (015, spec D1): every bean is
     * declared explicitly as a `@Bean` method in a `@Configuration` class, so
     * no class, interface, or object may carry `@Service`, `@Component`, or
     * `@Repository`. Zero allowlist. Component scanning stays ON for
     * `@Configuration` classes and the web edge (`@Controller`, `@RestController`,
     * `@ControllerAdvice`) — those annotations are not matched here (the match is
     * by annotation NAME, so `@Controller`-meta-annotated classes are unaffected).
     */
    @Test
    fun `no stereotype annotations in production code`() {
        productionScope()
            .classesAndInterfacesAndObjects()
            .withAnnotationNamed("Service", "Component", "Repository")
            .assertEmpty()
    }

    /**
     * **Every `@Transactional` in `modules/&#42;/src/main` names the metadata transaction manager**
     * (056, ARCH-AUDIT S3 / ruling R6 §E.1).
     *
     * This replaces the pre-056 guard, which banned `@Transactional` outright because there was
     * no transaction manager at all and single-statement CTEs were the whole atomicity story.
     * There is a manager now — exactly one, `metadataTransactionManager` — and N Hikari pools for
     * CUSTOMER databases which are not Spring transaction resources and must never become one.
     *
     * A bare `@Transactional` binds to whichever manager Spring finds. With one manager that
     * works **by accident**, which is the worst state to be in: correct today, silently wrong the
     * day someone registers a second manager, and nothing in the source says which database a
     * transaction belongs to. So the name is mandatory, and this test is what makes it so.
     *
     * Deliberately a text scan rather than a Konsist annotation-argument walk: what must be true
     * is a property of the SOURCE a reviewer reads, the failure can then name file and line, and
     * it cannot be satisfied by a constant reference that resolves to the right string while
     * reading as a bare `@Transactional` on the page.
     */
    @Test
    fun `every @Transactional in production code names the metadata transaction manager`() {
        val occurrences = transactionalOccurrences()

        // Non-vacuity first: a guard that cannot go red is not a guard. If the service layer's
        // annotations ever vanish, this must fail loudly rather than pass over an empty set.
        withClue("No @Transactional found in modules/*/src/main — the scan is vacuous, not clean") {
            occurrences.shouldNotBeEmpty()
        }

        val bare = occurrences.filterNot { it.namesManager }
        withClue(
            "Bare @Transactional (or one naming a different manager) in production code. Every one " +
                "must read @Transactional(\"$METADATA_MANAGER\") — see app's TransactionConfiguration " +
                "for why the name is not optional.",
        ) {
            bare.map { "${it.path}:${it.line}" }.shouldBeEmpty()
        }
    }

    /**
     * **The layering edge 056 created** (module-structure.md §4.2/§5.10): `web` and `mcp-server`
     * depend on `application`, `application` depends on the domain modules, and never the
     * reverse. A service that imports a web or MCP type is the layering violation the module
     * graph exists to prevent — S4's finding, made mechanical.
     *
     * Gradle's `verifyModuleDependencies` task enforces the DECLARED edges. This is the
     * source-level half: it refuses the import itself, names file and line, and would still fire
     * on a type reached through some future transitive edge.
     *
     * `mcp` is forbidden to `application` and the domain modules but obviously not to `web`,
     * which legitimately depends on `mcp-server` (the `McpExecutionRunner` port).
     */
    @Test
    fun `neither application nor the domain modules import a web or MCP type`() {
        val offenders =
            productionFiles()
                .filter { file -> BELOW_THE_SURFACES.any { file.path.contains(moduleMainPath(it)) } }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> FORBIDDEN_IMPORT.containsMatchIn(line) }
                        .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
                }

        withClue(
            "A module below the surfaces imports a web or MCP type. `application` and the domain " +
                "modules sit BELOW `web` and `mcp-server` (module-structure §5.10); the mapping to " +
                "an HTTP status stays in ApiErrorCatalog and the mapping to an MCP error stays in " +
                "the tool.",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    /** The layering scan above proves nothing if it never looked at `modules/application`. */
    @Test
    fun `the layering scan actually covers the application module`() {
        productionFiles()
            .count { it.path.contains(moduleMainPath("application")) }
            .let { it >= EXPECTED_APPLICATION_MAIN_FILES } shouldBe true
    }

    /** A guard scanning an empty scope proves nothing — the scope must see production code. */
    @Test
    fun `the production scope actually covers the modules`() {
        productionScope().classes().map { it.name }.shouldNotBeEmpty()
    }

    /**
     * Konsist resolves a scope path against the ROOT PROJECT it detects, not against the test
     * task's working directory, so the plain relative name is correct here — an absolute path is
     * double-prefixed and throws. The plain-file scans below have the opposite problem and use
     * [modulesDirectory]; the two are not interchangeable, which is why both exist.
     */
    private fun productionScope() =
        Konsist
            .scopeFromDirectory("modules")
            .slice { file -> !file.path.contains("/src/test/") }

    /**
     * Every production Kotlin source file under `modules/`, as plain files.
     *
     * The directory is resolved by walking UP from the working directory, not taken relative to
     * it: a Gradle test task runs in its own module's directory, so a bare `File("modules")` finds
     * nothing and every scan below silently passes over an empty set. That is the failure the
     * non-vacuity assertions exist to catch, and it caught exactly this.
     */
    private fun productionFiles(): List<File> =
        modulesDirectory()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains(TEST_SOURCE_PATH) }
            .toList()

    /** `modules/` at the repository root — the same walk-up `Fixtures.repoDirectory` uses. */
    private fun modulesDirectory(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "modules")
            if (candidate.isDirectory && File(candidate, "pipeline-contract").isDirectory) return candidate
            dir = dir.parentFile
        }
        error("modules/ not found walking up from ${File("").absolutePath}")
    }

    private fun moduleMainPath(module: String): String =
        listOf("modules", module, "src", "main").joinToString(File.separator, prefix = File.separator, postfix = File.separator)

    /** One `@Transactional` site: where it is, and whether it names the manager. */
    private data class TransactionalSite(
        val path: String,
        val line: Int,
        val namesManager: Boolean,
    )

    private fun transactionalOccurrences(): List<TransactionalSite> =
        productionFiles().flatMap { file ->
            file
                .readLines()
                .withIndex()
                .filter { (_, line) -> TRANSACTIONAL.containsMatchIn(line) }
                .map { (index, line) ->
                    TransactionalSite(file.path, index + 1, line.contains("\"$METADATA_MANAGER\""))
                }
        }

    private companion object {
        const val METADATA_MANAGER = "metadataTransactionManager"

        val TEST_SOURCE_PATH =
            listOf("src", "test").joinToString(File.separator, prefix = File.separator, postfix = File.separator)

        /** Modules that sit below the two surfaces and may not import from either. */
        val BELOW_THE_SURFACES =
            listOf(
                "application",
                "pipeline-contract",
                "templates",
                "datasources",
                "staging",
                "dag",
                "auth",
                "typesystem",
            )

        /**
         * The annotation at a USE site, not in prose. The negative lookahead skips KDoc
         * continuation lines (` * … @Transactional …`), so a comment explaining the rule is not
         * mistaken for a violation of it — `PipelineRepository`'s KDoc has said "`@Transactional`
         * belongs on the service layer" since long before there was one.
         */
        val TRANSACTIONAL = Regex("^(?!\\s*\\*)\\s*@Transactional\\b")

        /** An import of either surface's package from a module that sits below them. */
        val FORBIDDEN_IMPORT = Regex("^import co\\.datapipelines\\.(web|mcp)\\.")

        /**
         * A floor on `modules/application/src/main`, so "the module was renamed or emptied" fails
         * instead of quietly making the layering scan vacuous. Slices B and C will raise it.
         */
        const val EXPECTED_APPLICATION_MAIN_FILES = 1
    }
}
