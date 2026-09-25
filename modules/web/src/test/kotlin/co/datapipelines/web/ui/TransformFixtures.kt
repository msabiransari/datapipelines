package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.templates.LibraryResolver
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRegistry
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TransformTestRunner
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 7d — the transform face's test world: 7b's REAL save gate (the `TemplateValidator` with its
 * suite runner, on a real evaluation pool, over the real JSONata engine) and a stored transform
 * version built from the create skeleton — the design record's §2.2 example. Nothing here is a
 * double of the evaluation: a face test that passes does so because 7b's machinery agreed.
 */
internal object TransformFixtures {
    val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000007d")
    const val NAME = "test/shape/order_lines.jsonata"

    /** A real pool, sized for tests — the production bean's shape (TransformConfiguration). */
    fun pool(): ScriptEvaluationPool = ScriptEvaluationPool(2, 32, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM)

    fun runner(suiteTimeout: Duration = Duration.ofSeconds(30)): TransformTestRunner =
        TransformTestRunner(
            engines = mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
            pool = pool(),
            evaluateTimeout = Duration.ofSeconds(5),
            suiteTimeout = suiteTimeout,
        )

    /** 7b's validator bean, as TemplatesConfiguration builds it — the suite runner included. */
    fun validator(runner: TransformTestRunner = runner()): TemplateValidator =
        TemplateValidator(LibraryResolver { _ -> EMPTY_REGISTRY }, suiteRunner = runner)

    /** The skeleton's panes, as the create modal submits them. */
    val skeletonPanes: TransformPanes
        get() = TransformPanes(TransformSkeleton.body, TransformSkeleton.contract, TransformSkeleton.invariants, TransformSkeleton.tests)

    /** A stored transform version whose content is the skeleton — bound through the face's own binder. */
    fun storedSkeleton(
        version: Int = 1,
        status: PipelineVersionStatus = PipelineVersionStatus.DRAFT,
        bodyHash: String = "hash-v$version-0000000000",
    ): Template {
        val identity = TransformFace.Identity(NAME, TemplateType.JSONATA.wire, Template.NONE_ENGINE, "Order lines", "The record's example.")
        val draft = (TransformFace.bind(identity, skeletonPanes) as TransformFace.Bound.Draft).draft
        return Template(
            id = NAME,
            version = version,
            engine = Template.NONE_ENGINE,
            type = TemplateType.JSONATA,
            dialect = null,
            displayName = draft.displayName,
            description = draft.description,
            body = draft.body,
            createdAt = Instant.parse("2026-09-25T10:00:00Z"),
            createdBy = ACTOR,
            status = status,
            bodyHash = bodyHash,
            contract = draft.contract,
            invariants = draft.invariants,
            tests = draft.tests,
        )
    }

    private val EMPTY_REGISTRY =
        object : TemplateRegistry {
            override fun lookup(
                id: String,
                version: Int,
            ): TemplateVersion? = null

            override fun existsId(id: String): Boolean = false
        }
}
