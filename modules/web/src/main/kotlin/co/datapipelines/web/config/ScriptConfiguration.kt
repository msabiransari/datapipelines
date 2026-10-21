package co.datapipelines.web.config

import co.datapipelines.application.templates.TemplateEvaluateService
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.templates.ScriptTestRunner
import co.datapipelines.templates.TemplateRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The script evaluation wiring (7b, #7; transform-nodes design §4.3/§8.1/§9.1): the
 * bounded script-evaluation pool every production evaluation is admitted to — the save and
 * release test suite, `templates_evaluate`, the REST evaluate route, and 7c's node runner —
 * plus the runner/service beans that share it. Kept out of `EngineConfiguration` (the
 * build's per-class bean-count guard): one concern, one file, the §4.3 bulkhead in one place.
 */
@Configuration
class ScriptConfiguration {
    /**
     * The bounded script-evaluation pool (transform-nodes design §4.3, R7; the 7a bulkhead):
     * every production evaluation — the save/release test suite, `templates_evaluate`, the
     * editor preview, and 7c's node runner — is admitted here and runs on its own thread,
     * never on a request thread or the executor's. [scriptProperties] sizes it.
     */
    @Bean
    fun scriptEvaluationPool(properties: ScriptProperties): ScriptEvaluationPool =
        ScriptEvaluationPool(
            size = properties.poolSize,
            queue = properties.poolQueue,
            abandonGrace = Duration.ofSeconds(properties.abandonGraceSeconds),
            clock = ScriptEvaluationPool.SYSTEM,
        )

    /**
     * The §8.1 test runner / §9.1 evaluator — one service behind the save-time suite, the
     * release re-run, `templates_evaluate` and the REST route (record §9.1). Templates'
     * validator bean picks it up (ObjectProvider) so the suite rides every save path.
     */
    @Bean
    fun scriptTestRunner(
        pool: ScriptEvaluationPool,
        properties: ScriptProperties,
    ): ScriptTestRunner =
        ScriptTestRunner(
            engines = mapOf(co.datapipelines.scripting.ScriptLanguage.JSONATA to co.datapipelines.scripting.JsonataEngine()),
            pool = pool,
            evaluateTimeout = Duration.ofSeconds(properties.evaluateTimeoutSeconds),
            suiteTimeout = Duration.ofSeconds(properties.suiteTimeoutSeconds),
            maxInputRows = properties.maxInputRows,
            maxStringBytes = properties.maxStringBytes,
            maxValueBytes = properties.maxValueBytes,
            maxDepth = properties.maxDepth,
        )

    /**
     * `script.evaluations.abandoned` (record §9.5): the pool's abandoned-evaluation count
     * as a gauge, so a runaway that outlived its budget and grace is visible rather than only
     * logged.
     */
    @Bean
    fun scriptEvaluationsAbandonedGauge(
        pool: ScriptEvaluationPool,
        meters: MeterRegistry,
    ): Gauge =
        Gauge
            .builder("script.evaluations.abandoned") { pool.abandoned.sum().toDouble() }
            .description(
                "Script evaluations abandoned past their wall clock plus grace (the pool's bulkhead holds the thread's slot until it ends)",
            ).register(meters)

    /**
     * The ONE evaluation path the MCP `templates_evaluate` tool and REST
     * `POST /api/v1/templates/evaluate` share (script-nodes §9.1/§9.2) — the same service,
     * the same pool, the same timeout; declared here like every cross-module collaborator.
     */
    @Bean
    fun templateEvaluateService(
        templates: TemplateRepository,
        runner: ScriptTestRunner,
    ): TemplateEvaluateService = TemplateEvaluateService(templates, runner)

    /**
     * The SCRIPT node's runtime collaborators (7c, #7; record §4.3/§5), handed to every
     * `pipelineExecutor(...)` construction: the pinned-version resolver (the workspace-scoped
     * repository read), the same bounded pool the template surfaces use, the engines, and the
     * `datapipelines.script.*` bounds. One bean so the four executor call sites cannot
     * disagree about what a SCRIPT run is allowed to cost.
     */
    @Bean
    fun scriptSupport(
        templates: TemplateRepository,
        pool: ScriptEvaluationPool,
        properties: ScriptProperties,
        stagingProperties: StagingH2Properties,
    ): co.datapipelines.executor.ScriptSupport =
        co.datapipelines.executor.ScriptSupport(
            resolver =
                co.datapipelines.executor.ScriptVersionResolver { workspaceId, ref ->
                    templates.findVersion(workspaceId, ref.id, ref.version)?.let { version ->
                        co.datapipelines.executor.ResolvedScript(
                            type = version.type,
                            status = version.status,
                            body = version.body,
                            contract =
                                version.contract
                                    ?: error("script version ${ref.key} has no contract (chk_script_blocks)"),
                            invariants = version.invariants.orEmpty(),
                        )
                    }
                },
            pool = pool,
            engines = mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
            maxInputRows = properties.maxInputRows,
            maxValueBytes = properties.maxValueBytes,
            maxStringBytes = properties.maxStringBytes,
            maxDepth = properties.maxDepth,
            readBatchSize = stagingProperties.resultBatchSize,
        )
}
