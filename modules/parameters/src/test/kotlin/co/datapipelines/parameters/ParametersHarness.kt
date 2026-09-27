package co.datapipelines.parameters

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.SqlBindScanner
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The parameter-set aggregate wired as production would wire it — the real [ParameterSetRepository],
 * validator and [ParameterSetService] over the shared database, a metadata `TransactionTemplate`, and
 * the template ports implemented over the REAL `templates` tables ([TemplateRepository]): a pin's
 * status, its body's binds (`SqlBindScanner`, the production scan) and the 142 cascade's release all
 * read and write real rows, so the release's atomicity is the database's, not a fake's. The datasource
 * registry and the selector probe stay fakes (the runtime is lane C's).
 */
internal class ParametersHarness(
    authoringEnabled: Boolean = true,
    val probe: RecordingProbe = RecordingProbe(),
    datasources: FakeDatasources = FakeDatasources(),
) {
    val jdbc = ParametersTestDb.jdbc
    val repository = ParameterSetRepository(jdbc)
    val templates = TemplateRepository(jdbc)
    val transactions = TransactionTemplate(DataSourceTransactionManager(ParametersTestDb.dataSource))

    /** The template registry over the real tables — the `TemplateDryRendererImpl` split of not-found vs version-not-found. */
    val registry =
        object : TemplateDryRenderer {
            override fun lookup(
                workspaceId: UUID,
                ref: TemplateRef,
            ): TemplateLookup {
                val version = templates.lookupVersion(workspaceId, ref.id, ref.version)
                return when {
                    version != null -> TemplateLookup.Found(version.dialect, version.type)
                    templates.existsId(workspaceId, ref.id) -> TemplateLookup.VersionNotFound
                    else -> TemplateLookup.TemplateNotFound
                }
            }

            override fun dryRender(
                workspaceId: UUID,
                ref: TemplateRef,
                context: Map<String, Any?>,
            ): DryRenderOutcome = error("the set validator renders through the SelectorProbe port")

            override fun interpolatedParameters(
                workspaceId: UUID,
                ref: TemplateRef,
                declared: Set<String>,
                guarded: Set<String>,
            ): List<String> = emptyList()

            override fun boundParameters(
                workspaceId: UUID,
                ref: TemplateRef,
            ): List<String> = templates.lookupVersion(workspaceId, ref.id, ref.version)?.let { SqlBindScanner.scan(it.body) }.orEmpty()
        }

    val statuses = TemplateVersionStatuses { workspaceId, id, version -> templates.findVersionStatus(workspaceId, id, version) }

    /** The cascade's releaser over the template's own statement — the pinned version at its current hash (`releasePinned`'s rule). */
    val releaser =
        TemplateReleaser { workspaceId, id, version, actor ->
            val draft = checkNotNull(templates.findDraftDetail(workspaceId, id)) { "no draft of $id" }
            check(draft.version == version) { "the draft of $id is v${draft.version}, the pin names v$version" }
            checkNotNull(templates.releaseDraft(workspaceId, id, draft.bodyHash, actor)) { "release of $id failed" }
            TemplateRef(id, version)
        }

    val validator = ParameterSetValidator(ParametersConfig(), registry, statuses, datasources, probe)
    val service = ParameterSetService(repository, validator, AuthoringGuard(authoringEnabled), statuses, releaser, transactions)

    /** Seeds an `sql` template (POSTGRES — the fake `warehouse`'s dialect) and answers its pin. */
    fun template(
        name: String,
        body: String,
        lifecycle: CreateLifecycle = CreateLifecycle.RELEASED,
        workspaceId: UUID = ParametersTestDb.WORKSPACE,
    ): TemplateRef {
        val created =
            templates.create(
                workspaceId,
                TemplateDraft(
                    id = name,
                    type = TemplateType.SQL,
                    dialect = Dialect.POSTGRES,
                    displayName = name,
                    description = "",
                    body = body,
                ),
                ParametersTestDb.AUTHOR,
                lifecycle,
                WriteSurface.SESSION,
            )
        return TemplateRef(created.id, created.version)
    }

    fun document(json: String): ParameterSetDocument = ParameterSetReader().readOrThrow(ParameterSetFixtures.tree(json))

    fun create(
        document: ParameterSetDocument,
        workspaceId: UUID = ParametersTestDb.WORKSPACE,
    ): ParameterSetVersion = service.create(workspaceId, document, ParametersTestDb.AUTHOR, WriteSurface.MCP)
}
