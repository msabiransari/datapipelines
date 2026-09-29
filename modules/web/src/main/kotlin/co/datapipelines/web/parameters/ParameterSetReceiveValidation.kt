package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterSetDocument
import co.datapipelines.parameters.ParameterSetValidation
import co.datapipelines.parameters.ParameterSetValidationException
import co.datapipelines.parameters.ParameterSetValidator
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.parameters.SelectorProbe
import co.datapipelines.parameters.SelectorRender
import co.datapipelines.parameters.importRefusals
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.templates.InterpolatedParameterScanner
import co.datapipelines.templates.SqlBindScanner
import co.datapipelines.templates.TemplateDeserializationOutcome
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRegistry
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.WorkspaceTemplateEngines
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * The promotion receive's SET VALIDATION (#302, record §18 C36) — the record §4 steps 1–6 of every
 * batch set entry, run BEFORE the receive's one §10.4 transaction opens, so the selector probe (step
 * 6, a CUSTOMER-datasource connection) never leases inside a metadata transaction. The transaction
 * body lands what this answered ([ParameterSetPromotion.land] →
 * [ParameterSetService.importValidated]); a refusal here means NOTHING has landed.
 *
 * ## The pins a batch brings (the brief's judge-from-the-payloads rule)
 *
 * A set's template pin must resolve before the templates of the SAME batch have landed. This view
 * overlays the batch's §21.4 template payloads on the receiver's registry: a pin the batch brings
 * resolves from its payload (dialect, type, body), and everything else — statuses included — reads
 * the receiver's stored releases, exactly as a save validates against them. The payload bodies render
 * through a caller-owned [TemplateEngine] ([WorkspaceTemplateEngines.engineOver]) — the SAME hardened
 * engine the workspace renders use, over the overlay registry; no second engine configuration exists
 * anywhere, and the engine is closed when the validation returns.
 *
 * ## What the view judges, and what it deliberately does not
 *
 * The view judges PINS: a payload backs a pin at its exact `{id, version}`. It does NOT judge
 * template payloads — an entry the deserializer rejects is simply absent from the overlay, so a pin
 * riding it refuses `parameter.import.missing_template` here, and a payload nobody pins is still
 * judged (and refused) by `TemplateImportService` inside the transaction, as today. The import
 * remains the authority on template shape; the hash check on a brought template happens at its
 * landing inside the transaction and rolls the batch back whole on a mismatch (C36).
 */
class ParameterSetReceiveValidation(
    private val sets: co.datapipelines.parameters.ParameterSetService,
    private val engines: WorkspaceTemplateEngines,
    /** The receiver's production renderer — what non-batch pins read (delegation). */
    private val renderer: TemplateDryRenderer,
    /** The receiver's production statuses — what non-batch pins read (delegation). */
    private val statuses: TemplateVersionStatuses,
    /** The contract-side datasource port the save-time validator reads (PinRules' visibility + dialect). */
    private val datasources: DatasourceRegistry,
    /** The production probe — step 6's execution is pin-INDEPENDENT and always delegates. */
    private val probe: SelectorProbe,
    private val config: ParametersConfig,
) {
    /**
     * Validates every bound entry against the batch's template payloads overlaying the receiver's
     * registry — the FULL §4 (probe included, on the receiver's own datasources, [workspaceId] the
     * TARGET workspace as an argument) — and answers the canonical bodies the transaction body lands.
     * A refusal is the entry's own catalogued code, through the ONE §8.3 import lens
     * ([importRefusals]): a pin neither the batch brings nor the receiver holds is
     * `parameter.import.missing_template`.
     */
    fun validate(
        workspaceId: UUID,
        batchTemplates: List<JsonNode>,
        entries: List<ParameterSetPromotion.Bound>,
    ): List<ParameterSetPromotion.Validated> {
        val payloads = PayloadTemplates.of(batchTemplates)
        val registry = OverlayRegistry(payloads, engines.registryFor(workspaceId))
        return engines.engineOver(registry).use { engine ->
            val validator =
                ParameterSetValidator(
                    config,
                    OverlayRenderer(payloads, engine, renderer),
                    OverlayStatuses(payloads, statuses),
                    datasources,
                    OverlayProbe(payloads, engine, probe),
                )
            entries.map { bound ->
                val validation =
                    importRefusals(validator.validate(workspaceId, ParameterSetDocument(bound.name, bound.body)))
                val canonical =
                    when (validation) {
                        is ParameterSetValidation.Valid -> validation.document
                        is ParameterSetValidation.Invalid -> throw ParameterSetValidationException(validation.result)
                    }
                ParameterSetPromotion.Validated(bound, canonical.body)
            }
        }
    }
}

/** The batch's template payloads, keyed for pin lookup — the overlay's half of the registry. */
internal class PayloadTemplates private constructor(
    private val byKey: Map<String, TemplateVersion>,
) {
    fun versionOf(
        id: String,
        version: Int,
    ): TemplateVersion? = byKey["$id@$version"]

    fun holdsId(id: String): Boolean = byKey.keys.any { key -> key.substringBeforeLast('@') == id }

    companion object {
        /**
         * Parses the batch's §21.4 template envelopes. An entry the deserializer rejects — or one
         * without the exact `{id, version}` a pin needs — backs no pin (the class KDoc's split);
         * the import inside the transaction remains the authority on template payload shape.
         */
        fun of(entries: List<JsonNode>): PayloadTemplates {
            val deserializer = TemplateDeserializer()
            val byKey = HashMap<String, TemplateVersion>()
            entries.forEach { entry ->
                val version = entry.get("version")?.takeIf(JsonNode::isInt)?.asInt() ?: return@forEach
                when (val outcome = deserializer.fromTree(entry)) {
                    is TemplateDeserializationOutcome.Parsed ->
                        outcome.draft.id?.let { id ->
                            byKey["$id@$version"] =
                                TemplateVersion(
                                    id = id,
                                    version = version,
                                    type = outcome.draft.type ?: TemplateType.SQL,
                                    dialect = outcome.draft.dialect,
                                    isLibrary = outcome.draft.isLibrary,
                                    imports = outcome.draft.imports,
                                    body = outcome.draft.body,
                                    // Never persisted — stamps only; the blank hash keeps the
                                    // one-shot engine from caching the parsed tree.
                                    createdAt = Instant.now(),
                                    createdBy = UUID(0L, 0L),
                                    bodyHash = "",
                                )
                        }
                    is TemplateDeserializationOutcome.Rejected -> Unit
                }
            }
            return PayloadTemplates(byKey)
        }
    }
}

/** The batch payloads overlaying the receiver's stored registry — the validation's one template view. */
private class OverlayRegistry(
    private val payloads: PayloadTemplates,
    private val stored: TemplateRegistry,
) : TemplateRegistry {
    override fun lookup(
        id: String,
        version: Int,
    ): TemplateVersion? = payloads.versionOf(id, version) ?: stored.lookup(id, version)

    override fun existsId(id: String): Boolean = payloads.holdsId(id) || stored.existsId(id)
}

/** A batch-brought pin is RELEASED: the preserved-version import lands it RELEASED (or is the idempotent no-op of one); anything else refuses `template.version.conflict` inside the transaction and rolls the batch back whole. */
private class OverlayStatuses(
    private val payloads: PayloadTemplates,
    private val stored: TemplateVersionStatuses,
) : TemplateVersionStatuses {
    override fun statusOf(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): PipelineVersionStatus? =
        if (payloads.versionOf(templateId, version) != null) PipelineVersionStatus.RELEASED else stored.statusOf(workspaceId, templateId, version)
}

/**
 * The pin checks and the dry render against the overlay: a batch pin's facts (type, dialect) and
 * body scans (interpolation, binds) read the PAYLOAD; everything else delegates to the production
 * renderer. The transform contract of a batch pin is null — a selector pin is `type = 'sql'`, and
 * the type check refuses anything else before a contract could be read.
 */
private class OverlayRenderer(
    private val payloads: PayloadTemplates,
    private val engine: TemplateEngine,
    private val stored: TemplateDryRenderer,
) : TemplateDryRenderer {
    override fun lookup(
        workspaceId: UUID,
        ref: TemplateRef,
    ): TemplateLookup =
        payloads.versionOf(ref.id, ref.version)?.let { TemplateLookup.Found(it.dialect, it.type) }
            ?: stored.lookup(workspaceId, ref)

    override fun dryRender(
        workspaceId: UUID,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): DryRenderOutcome =
        if (payloads.versionOf(ref.id, ref.version) != null) {
            // The engine's classified (non-throwing) execute is internal to the templates module,
            // so a batch payload's render failure maps from the throwing entry point. The split
            // outcome (undeclared variable) is the pipeline validator's concern; nothing in the
            // parameters validation calls dryRender — PinRules owns lookup/scan, the probe owns
            // step 5 — and a refused render here is RenderFailed either way.
            try {
                engine.render(ref, context)
                DryRenderOutcome.Success
            } catch (e: TemplateRenderException) {
                DryRenderOutcome.RenderFailed((e.details["detail"] as? String) ?: e.message.orEmpty())
            }
        } else {
            stored.dryRender(workspaceId, ref, context)
        }

    override fun interpolatedParameters(
        workspaceId: UUID,
        ref: TemplateRef,
        declared: Set<String>,
        guarded: Set<String>,
    ): List<String> =
        payloads.versionOf(ref.id, ref.version)?.let { InterpolatedParameterScanner.scan(it.body, declared, guarded) }
            ?: stored.interpolatedParameters(workspaceId, ref, declared, guarded)

    override fun boundParameters(
        workspaceId: UUID,
        ref: TemplateRef,
    ): List<String> =
        payloads.versionOf(ref.id, ref.version)?.let { SqlBindScanner.scan(it.body) }
            ?: stored.boundParameters(workspaceId, ref)

    override fun transformContract(
        workspaceId: UUID,
        ref: TemplateRef,
    ): co.datapipelines.pipeline.TransformContractView? =
        if (payloads.versionOf(ref.id, ref.version) != null) null else stored.transformContract(workspaceId, ref)
}

/**
 * Step 5's render against the overlay — a batch pin renders through the payload-backed engine, a
 * stored pin through the production probe; step 6's EXECUTION is pin-independent and always
 * delegates ([SelectorProbe.probe] opens the receiver's own customer datasource — legal here only
 * because the receive validates BEFORE its transaction opens, the `gateChecks` precedent).
 */
private class OverlayProbe(
    private val payloads: PayloadTemplates,
    private val engine: TemplateEngine,
    private val stored: SelectorProbe,
) : SelectorProbe {
    override fun render(
        workspaceId: UUID,
        template: TemplateRef,
        context: Map<String, Any?>,
    ): SelectorRender =
        if (payloads.versionOf(template.id, template.version) != null) {
            try {
                SelectorRender.Rendered(engine.render(template, context))
            } catch (e: TemplateRenderException) {
                SelectorRender.Failed((e.details["detail"] as? String) ?: e.message.orEmpty())
            }
        } else {
            stored.render(workspaceId, template, context)
        }

    override fun probe(
        workspaceId: UUID,
        datasource: String,
        sql: String,
        binds: Map<String, Any?>,
        maxRows: Int,
    ): co.datapipelines.parameters.SelectorProbeOutcome = stored.probe(workspaceId, datasource, sql, binds, maxRows)
}
