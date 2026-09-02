package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.staging.StagingFactory
import co.datapipelines.templates.TemplateEngine
import java.io.Closeable

/**
 * A fully wired [PipelineExecutor] over real staging (H2), a real dispatcher, and in-memory
 * stand-ins for Redis.
 *
 * Everything the executor's own logic depends on is real: the DAG, the semaphores, the
 * coroutines, the staging database, the JDBC drivers. Only Redis is substituted, and the Redis
 * implementations have their own container-backed suites.
 */
class ExecutorHarness(
    templateEngine: TemplateEngine,
    registry: DatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
    val config: ExecutorConfig = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60),
    val resultStore: ResultStore = InMemoryResultStore(),
    stagingFactory: StagingFactory = Fixtures.stagingFactory(),
    val auditSink: ExecutionAwareAuditSink? = null,
    val cancellations: InMemoryCancellationRegistry = InMemoryCancellationRegistry(),
    val metrics: ExecutorMetrics = ExecutorMetrics.inMemory(),
    /** Injected only by tests that must observe (or forbid) slot acquisition — normally real. */
    executionSlots: ExecutionSlots? = null,
    /** The composition port (design §4.1) — wired by tests that exercise PIPELINE nodes. */
    subPipelineRunner: SubPipelineRunner? = null,
) : Closeable {
    val emitter = RecordingEmitter()
    val flags = InMemoryCancellationFlags()
    val slots = executionSlots ?: ExecutionSlots(config.maxConcurrentExecutionsPerUser, config.maxConcurrentExecutionsPerInstance)

    /** Deliberately small: a bounded pool is what the deadlock test needs to be honest. */
    private val dispatcher = ExecutorDispatcher.forConfig(config, maxThreads = DISPATCHER_THREADS)

    val executor: PipelineExecutor =
        pipelineExecutor(
            templateEngine = templateEngine,
            datasourceRegistry = registry,
            stagingFactory = stagingFactory,
            writebackRunner = JdbcWritebackRunner(registry),
            resultStore = resultStore,
            eventEmitter = emitter,
            cancellationRegistry = cancellations,
            cancellationFlags = flags,
            executionSlots = slots,
            dispatcher = dispatcher,
            config = config,
            // Test-only fixture: production must inject an absolute builder (F6).
            resultUrls = ResultUrlFactory.RELATIVE,
            metrics = metrics,
            auditSink = auditSink,
            subPipelineRunner = subPipelineRunner,
        )

    override fun close() {
        dispatcher.close()
    }

    private companion object {
        const val DISPATCHER_THREADS = 8
    }
}
