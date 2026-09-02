package co.datapipelines.web.bootstrap

import co.datapipelines.auth.PersonalWorkspaceSeeder
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.PipelineImportService
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A malformed or unreadable examples file — a refused startup, same shape as `ConfigValidator`'s. */
class ExampleContentFileException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The D9 seeder (sample-data design §6.1): imports the configured example templates and pipelines
 * into every freshly provisioned `auto-per-user` personal workspace, **through the same import
 * services the REST endpoints use** — so §12 validation, id handling and error codes are
 * identical on both paths.
 *
 * ## File shape (`datapipelines.bootstrap.examples-file`)
 * ```json
 * { "templates": [ <template draft>, ... ], "pipelines": [ <pipeline json>, ... ] }
 * ```
 * `templates` entries are exactly the `POST /api/v1/templates/import` array elements and
 * `pipelines` entries exactly a `POST /api/v1/pipelines/import` body; both arrays are optional.
 *
 * ## Two different failure moments, both loud
 * The file is read and structurally checked **in the constructor**, i.e. while the context is
 * building — a typo in a mounted file fails startup, not somebody's first login. Content
 * validation is the import services' job and therefore happens at seeding time; a fixture that
 * references a datasource this deployment lacks fails workspace provisioning, and the login with
 * it. That is deliberate (see [PersonalWorkspaceSeeder]): a personal workspace silently missing
 * its examples is indistinguishable from a seeded one, so it must never be handed out.
 *
 * Templates are imported before pipelines because a pipeline's node references a template version
 * and §12 validation resolves it at save time.
 */
class ExampleContentSeeder(
    properties: BootstrapProperties,
    private val pipelineImportService: PipelineImportService,
    private val templateImportService: TemplateImportService,
) : PersonalWorkspaceSeeder {
    private val log = LoggerFactory.getLogger(ExampleContentSeeder::class.java)

    // NOT a constructor parameter: Spring injects the app's servlet ObjectMapper into an
    // ObjectMapper-typed parameter even when it has a default (guarded by
    // ObjectMapperDefaultParameterKonsistTest). Declared before `content`, which uses it.
    private val mapper: ObjectMapper = ObjectMapper()

    /** Null when no examples file is configured — the seeder is then a deliberate no-op. */
    private val content: Content? = properties.examplesPath()?.let(::load)

    override fun seed(
        workspaceId: UUID,
        userId: UUID,
    ) {
        val examples = content ?: return
        examples.templatesBody?.let { body ->
            reporting(workspaceId, userId, kind = "templates", fixture = examples.templateIds.joinToString(",")) {
                templateImportService.import(body, workspaceId, userId)
            }
        }
        examples.pipelines.forEach { fixture ->
            reporting(workspaceId, userId, kind = "pipeline", fixture = fixture.name) {
                pipelineImportService.import(fixture.body, workspaceId, userId)
            }
        }
        log.info(
            "event=workspace.examples_seeded workspace_id={} templates={} pipelines={}",
            workspaceId,
            examples.templateIds.size,
            examples.pipelines.size,
        )
    }

    /**
     * Makes the refusal legible without softening it (021/F1 residual, reported by 042 as T63).
     *
     * A fixture the deployment cannot import fails workspace provisioning and the login with it —
     * deliberately (see [PersonalWorkspaceSeeder]). What the operator saw was a bare 500 with
     * nothing to grep: the support report "I can't log in" had no event behind it. So every
     * import this seeder performs is bracketed with the structured counterpart of the success
     * line, carrying the workspace, the user, the fixture and the catalogued code, and then
     * **rethrows unchanged** — this adds a log line, not a recovery.
     *
     * [fixture] is one pipeline's name, or — because templates travel as the single §8.8
     * `{"templates":[...]}` body a REST caller would send, and that call is atomic from here —
     * the ids of the envelope the failure came from.
     *
     * `TooGenericExceptionCaught` is suppressed deliberately: the point is that EVERY way an
     * import can fail becomes greppable. Narrowing the catch would silently return the
     * unlogged 500 for whichever failure the list forgot, which is the defect being fixed.
     */
    @Suppress("TooGenericExceptionCaught") // the log line must not narrow what the refusal reports — see KDoc
    private fun <T> reporting(
        workspaceId: UUID,
        userId: UUID,
        kind: String,
        fixture: String,
        importing: () -> T,
    ): T =
        try {
            importing()
        } catch (e: Exception) {
            log.error(
                "event=workspace.examples_seed_failed workspace_id={} user_id={} fixture_kind={} fixture={} " +
                    "error_code={} message=\"{}\"",
                workspaceId,
                userId,
                kind,
                fixture,
                errorCode(e),
                e.message,
                e,
            )
            throw e
        }

    /** The §13 code when the failure carries one, else the type — never "unknown". */
    private fun errorCode(e: Exception): String = (e as? DatapipelinesException)?.code ?: e.javaClass.simpleName

    /**
     * Reads and structurally checks the file. `ThrowsCount` is suppressed for the reason the
     * bootstrap datasources reader gives: each throw is a different refusal with a different
     * remedy (unreadable / bad JSON / not an object / wrong field type / nothing to seed), and
     * this message is all the operator gets to work from.
     */
    @Suppress("ThrowsCount")
    private fun load(path: Path): Content {
        val text =
            try {
                Files.readString(path)
            } catch (e: java.io.IOException) {
                throw ExampleContentFileException("Bootstrap examples file '$path' could not be read: ${e.message}", e)
            }
        val tree =
            try {
                mapper.readTree(text)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw ExampleContentFileException("Bootstrap examples file '$path' is not valid JSON: ${e.originalMessage}", e)
            }
        if (tree !is ObjectNode) {
            throw ExampleContentFileException("Bootstrap examples file '$path' must be a JSON object.")
        }
        val templates = arrayAt(tree, "templates", path)
        val pipelines = arrayAt(tree, "pipelines", path)
        if (templates == null && pipelines == null) {
            throw ExampleContentFileException(
                "Bootstrap examples file '$path' declares neither 'templates' nor 'pipelines' — unset " +
                    "datapipelines.bootstrap.examples-file to turn example seeding off instead.",
            )
        }
        return Content(
            // PRESENT-but-empty is a deployment saying "seed no templates", and is why the
            // refusal above tests presence, not size (021/F7: an explicitly empty array was
            // normalized to absent and then reported as a file declaring neither array — a
            // factually wrong diagnosis of valid input). Empty means no import call, not a
            // call with an empty body: the import services own their own emptiness rules.
            templatesBody =
                templates
                    ?.takeIf { !it.isEmpty }
                    ?.let { mapper.writeValueAsString(mapper.createObjectNode().set<ObjectNode>("templates", it)) },
            templateIds = templates?.map { it.get("id")?.asText() ?: UNNAMED }.orEmpty(),
            pipelines =
                pipelines?.mapIndexed { index, node ->
                    Fixture(name = node.get("name")?.asText() ?: "pipelines[$index]", body = mapper.writeValueAsString(node))
                } ?: emptyList(),
        )
    }

    /** The field as an array node when it is DECLARED (empty or not), null when absent. */
    private fun arrayAt(
        tree: ObjectNode,
        field: String,
        path: Path,
    ): JsonNode? {
        val node = tree.get(field) ?: return null
        if (!node.isArray) throw ExampleContentFileException("Bootstrap examples file '$path' field '$field' must be an array.")
        return node
    }

    /** One pipeline fixture: the body handed to the import service, and the name a failure names. */
    private class Fixture(
        val name: String,
        val body: String,
    )

    /** The import request bodies, derived once at startup so seeding is pure string handoff. */
    private class Content(
        val templatesBody: String?,
        val templateIds: List<String>,
        val pipelines: List<Fixture>,
    )

    private companion object {
        /** A fixture that names itself nothing still has to be nameable in a failure line. */
        const val UNNAMED = "<unnamed>"
    }
}
