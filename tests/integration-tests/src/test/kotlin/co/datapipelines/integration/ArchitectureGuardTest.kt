package co.datapipelines.integration

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAnnotationNamed
import com.lemonappdev.konsist.api.verify.assertEmpty
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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

    /**
     * **Transports and jobs reach the data layer only through approved entry points** (#217 slice A;
     * security-assurance record §6 — "architecture guards constrain transports and jobs to approved
     * application entry services"; B5 — a job is unreachable from any transport).
     *
     * A TRANSPORT is a web controller or controller advice (`@Controller`, `@RestController`,
     * `@ControllerAdvice` in `modules/web`) or an MCP tool (`modules/mcp-server`, its `*Configuration`
     * wiring excepted). It asks a service; it never opens raw JDBC; and it touches a repository only
     * through a direct read inventoried in [DIRECT_REPOSITORY_READS] — the reads that predate this rule,
     * each workspace-scoped in its SQL (auth.md §11A.1) and walked by `WorkspaceIsolationSweepTest`.
     * The record is explicit that the tree does not already conform and must not be assumed to: the
     * list is an inventory, not an endorsement. A NEW pair fails by name (a new direct read is a
     * reviewed decision, not a convenience); a pair that no longer exists fails too, so the list only
     * shrinks. References are found by class NAME on code lines — an import, a same-package use and a
     * fully-qualified constructor parameter (two exist) are all the same reach, and a controller
     * annotated by its qualified name (`@org.springframework.stereotype.Controller`, one exists) is one.
     *
     * A JOB is an `@Scheduled` method. It lives only in [APPROVED_SCHEDULING_FILES], and no transport
     * names a job's service or scheduler — the source half of B5; `EntryInventoryE2eTest` holds the
     * runtime half (no scheduled bean is a request handler).
     */
    @Test
    fun `transports and jobs reach the data layer only through approved entry points`() {
        val repositories = repositoryTypes()
        val transports = transportFiles()
        val reads =
            transports
                .flatMap { file ->
                    codeLines(file).flatMap { line ->
                        repositories.findAll(line).map { "${file.nameWithoutExtension} → ${it.value}" }
                    }
                }.toSet()
        val rawJdbc =
            transports.flatMap { file ->
                codeLines(file).filter { RAW_JDBC.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" }
            }
        val jobReach =
            transports.flatMap { file ->
                codeLines(file).filter { JOB_SERVICE.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" }
            }
        val scheduling = productionFiles().filter { file -> codeLines(file).any { SCHEDULED.containsMatchIn(it) } }.map { it.name }.toSet()

        withClue(
            "a transport reaching a repository outside the inventoried direct reads — go through a service, " +
                "or add the pair with its review",
        ) {
            (reads - DIRECT_REPOSITORY_READS).sorted().joinToString("\n") shouldBe ""
        }
        withClue("inventoried direct reads that no longer exist — delete them from DIRECT_REPOSITORY_READS") {
            (DIRECT_REPOSITORY_READS - reads).sorted().joinToString("\n") shouldBe ""
        }
        withClue("raw JDBC in a transport") { rawJdbc.joinToString("\n") shouldBe "" }
        withClue("@Scheduled outside the approved scheduling files") {
            (scheduling - APPROVED_SCHEDULING_FILES).joinToString("\n") shouldBe
                ""
        }
        withClue("a transport naming a job's service or scheduler (B5)") { jobReach.joinToString("\n") shouldBe "" }

        // Non-vacuity: the scan saw the transports and the jobs it exists for.
        transports.size shouldBeGreaterThanOrEqual TRANSPORT_FLOOR
        scheduling shouldBe APPROVED_SCHEDULING_FILES
    }

    /** Every `class|interface|object XRepository` declared in production code, as one alternation. */
    private fun repositoryTypes(): Regex {
        val names = productionFiles().flatMap { REPOSITORY_DECLARATION.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        return Regex("\\b(" + names.sorted().joinToString("|") + ")\\b")
    }

    /** The web controllers and advices, and the MCP transport's files — see the rule above. */
    private fun transportFiles(): List<File> =
        productionFiles().filter { file ->
            (file.path.contains(moduleMainPath("web")) && CONTROLLER.containsMatchIn(file.readText())) ||
                (file.path.contains(moduleMainPath("mcp-server")) && !file.name.endsWith("Configuration.kt"))
        }

    /** A file's lines that are code — KDoc and line comments describe rules, they do not break them. */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot { COMMENT_LINE.containsMatchIn(it) }

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

        /**
         * The transports' direct repository reads, inventoried 2026-09-24 (#217 slice A) on base
         * `cc779dcc`: `transport file → repository`. They predate the rule; each is workspace-scoped in
         * its SQL (auth.md §11A.1). Moving them behind services is the record's slice C — until then this
         * list only shrinks.
         */
        val DIRECT_REPOSITORY_READS =
            setOf(
                "AdminUsersPartialController → MailSendRepository",
                "ApiConsoleController → ApiKeyRepository",
                "ApiConsoleController → EndpointKeyBindingRepository",
                "ApiKeysAdminController → ApiKeyRepository",
                "ApiKeysAdminController → EndpointKeyBindingRepository",
                "ApiKeysAdminController → UserRepository",
                "ApiKeysPartialController → ApiKeyRepository",
                "AppShellAdvice → UserRepository",
                "AuthController → ApiKeyRepository",
                "DashboardPartialController → ExecutionRepository",
                "DatasourceGrantsController → DatasourceGrantRepository",
                "DatasourceGrantsPartialController → DatasourceGrantRepository",
                "DatasourcePartialController → DatasourceGrantRepository",
                "EndpointsController → ApiKeyRepository",
                "EndpointsController → EndpointKeyBindingRepository",
                "EndpointsController → PipelineRepository",
                "EndpointsTools → PipelineRepository",
                "EntryPointChecks → TemplateRepository",
                "ExecutionDetailController → ExecutionEventRepository",
                "ExecutionDetailController → ExecutionRepository",
                "ExecutionDetailController → PipelineRepository",
                "ExecutionDetailPartialController → ExecutionRepository",
                "ExecutionHistoryController → PipelineRepository",
                "ExecutionTools → ExecutionRepository",
                "ExecutionsController → ExecutionRepository",
                "ExecutionsController → PipelineRepository",
                "ExecutionsGetResultTool → ExecutionRepository",
                "McpResourceCatalog → ExecutionRepository",
                "McpResourceReader → ExecutionEventRepository",
                "McpResourceReader → ExecutionRepository",
                "PipelineAuthoringTools → PipelineRepository",
                "PipelineChecksPartialsController → PipelineCheckRunRepository",
                "PipelineExecuteTool → ExecutionRepository",
                "PipelineNodeSqlPartialController → PipelineRepository",
                "PipelineNodeSqlPartialController → TemplateRepository",
                "PipelineTransferController → PipelineRepository",
                "PipelineTransferController → TemplateRepository",
                "PipelinesController → PipelineCheckRunRepository",
                "TemplateAuthoringTools → TemplateRepository",
                "TemplateEditorController → TemplateRepository",
                // Annotated `@org.springframework.stereotype.Controller` — found once the controller match read qualified names.
                "TemplateLifecycleDialogController → TemplateRepository",
                "TemplatePartialController → TemplateRepository",
                "TemplateReadTools → TemplateRepository",
                "TemplatesController → TemplateRepository",
                "TemplatesPurgeDraftTool → TemplateRepository",
                "UiWorkspaceAdvice → ApiKeyRepository",
                "UserSettingsController → UserRepository",
                // #197: the avatar proxy reads the signed-in principal's OWN row by id —
                // the same single-row read as UserSettingsController, scoped by construction
                // (the id comes from the principal, never from the request).
                "AvatarController → UserRepository",
            )

        /** The three `@Scheduled` homes (auth.md §8.6's scheduled rows): the stale-execution sweep, the pool reaper, event retention. */
        val APPROVED_SCHEDULING_FILES =
            setOf("SweepSchedulingConfiguration.kt", "PoolReaperSchedulingConfiguration.kt", "RetentionSchedulingConfiguration.kt")

        /** 100 transport files on the inventory base; a scan that finds far fewer is looking in the wrong place. */
        const val TRANSPORT_FLOOR = 90

        val REPOSITORY_DECLARATION =
            Regex("^\\s*(?:open |internal |abstract )*(?:class|interface|object)\\s+(\\w+Repository)\\b", RegexOption.MULTILINE)

        /** The annotation, also written fully qualified — a package prefix must not hide a transport. */
        val CONTROLLER = Regex("^\\s*@(?:[\\w.]+\\.)?(RestController|Controller|ControllerAdvice)\\b", RegexOption.MULTILINE)
        val COMMENT_LINE = Regex("^\\s*(\\*|/\\*|//)")
        val SCHEDULED = Regex("^\\s*@(?:[\\w.]+\\.)?Scheduled\\b")
        val RAW_JDBC =
            Regex(
                "^import (org\\.springframework\\.jdbc\\.|javax\\.sql\\.DataSource|" +
                    "java\\.sql\\.(Connection|DriverManager|Statement|PreparedStatement|ResultSet))",
            )
        val JOB_SERVICE =
            Regex(
                "\\b(StaleExecutionSweeper|ExecutionEventRetention|reapRetiredPools|StaleExecutionSweepScheduler|" +
                    "DatasourcePoolReaperScheduler|ExecutionEventRetentionScheduler)\\b",
            )
    }
}
