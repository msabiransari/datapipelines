package co.datapipelines.web.bootstrap

import co.datapipelines.auth.PersonalWorkspaceSeeder
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
    private val mapper: ObjectMapper = ObjectMapper(),
) : PersonalWorkspaceSeeder {
    private val log = LoggerFactory.getLogger(ExampleContentSeeder::class.java)

    /** Null when no examples file is configured — the seeder is then a deliberate no-op. */
    private val content: Content? = properties.examplesPath()?.let(::load)

    override fun seed(
        workspaceId: UUID,
        userId: UUID,
    ) {
        val examples = content ?: return
        examples.templatesBody?.let { templateImportService.import(it, workspaceId, userId) }
        examples.pipelineBodies.forEach { pipelineImportService.import(it, workspaceId, userId) }
        log.info(
            "event=workspace.examples_seeded workspace_id={} templates={} pipelines={}",
            workspaceId,
            examples.templateCount,
            examples.pipelineBodies.size,
        )
    }

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
            templatesBody = templates?.let { mapper.writeValueAsString(mapper.createObjectNode().set<ObjectNode>("templates", it)) },
            templateCount = templates?.size() ?: 0,
            pipelineBodies = pipelines?.map(mapper::writeValueAsString).orEmpty(),
        )
    }

    private fun arrayAt(
        tree: ObjectNode,
        field: String,
        path: Path,
    ): JsonNode? {
        val node = tree.get(field) ?: return null
        if (!node.isArray) throw ExampleContentFileException("Bootstrap examples file '$path' field '$field' must be an array.")
        return node.takeIf { !it.isEmpty }
    }

    /** The import request bodies, derived once at startup so seeding is pure string handoff. */
    private class Content(
        val templatesBody: String?,
        val templateCount: Int,
        val pipelineBodies: List<String>,
    )
}
