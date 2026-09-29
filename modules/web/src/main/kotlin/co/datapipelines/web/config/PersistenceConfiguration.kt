package co.datapipelines.web.config

import co.datapipelines.auth.PersistenceProperties
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.web.sse.BatchedEventRecorder
import co.datapipelines.web.sse.DirectEventRecorder
import co.datapipelines.web.sse.ExecutionEventRecorder
import co.datapipelines.web.sse.ExecutionEventRowSink
import co.datapipelines.web.sse.ReplayLogEntry
import co.datapipelines.web.sse.ReplayLogSink
import co.datapipelines.web.sse.SseEventLog
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * #266 — the execution-event record's and the replay log's batching writers, the recorder every
 * emitter records through, and every writer's shutdown drain (dag-executor §10.1, configuration
 * §3.32). The audit log's writer is `auth`'s (`AuthConfiguration`); the drain collects it too.
 *
 * Kept out of [EngineConfiguration] for its size ceiling, as `TransformConfiguration` is; the pool
 * the direct fallbacks run on, `eventPersistenceExecutor`, stays there beside its dispatcher.
 */
@Configuration
class PersistenceConfiguration {
    /**
     * #266 — the durable event record's batching writer (dag-executor §10): partitioned by execution
     * id, one JDBC batch per commit through [ExecutionEventRepository.appendAll].
     */
    @Bean(destroyMethod = "close")
    fun executionEventWriter(
        repository: ExecutionEventRepository,
        persistence: PersistenceProperties,
    ): BatchingWriter<ExecutionEventRecord> =
        BatchingWriter(EXECUTION_EVENTS_STORE, persistence.toConfig(), ExecutionEventRowSink(repository))

    /** #266 — the replay log's batching writer: one Lua script (`EVALSHA`) per commit through [SseEventLog.appendAll]. */
    @Bean(destroyMethod = "close")
    fun replayLogWriter(
        eventLog: SseEventLog,
        persistence: PersistenceProperties,
    ): BatchingWriter<ReplayLogEntry> = BatchingWriter(REPLAY_LOG_STORE, persistence.toConfig(), ReplayLogSink(eventLog))

    /**
     * What every emitter the runners build records its events through: the two writers above, or —
     * `datapipelines.persistence.enabled: false` — one direct write per event per store, the pre-#266
     * path. Either way the emit awaits it (the #266 ruling).
     */
    @Bean
    fun executionEventRecorder(
        persistence: PersistenceProperties,
        executionEventWriter: BatchingWriter<ExecutionEventRecord>,
        replayLogWriter: BatchingWriter<ReplayLogEntry>,
        repository: ExecutionEventRepository,
        eventLog: SseEventLog,
        @Qualifier("eventPersistenceExecutor") executor: java.util.concurrent.ExecutorService,
        @Qualifier("eventPersistenceDispatcher") dispatcher: CoroutineDispatcher,
    ): ExecutionEventRecorder =
        if (persistence.enabled) {
            BatchedEventRecorder(executionEventWriter, replayLogWriter, eventLog, executor)
        } else {
            DirectEventRecorder(repository, eventLog, dispatcher)
        }

    /**
     * #266 — every batching writer's drain, AFTER the web server's graceful drain (no request is in
     * flight any more, so no new audit row is coming) and after the execution drain (every awaited
     * event already committed), BEFORE the server stops and the pools close. What the drain could not
     * write by `shutdown-drain-ms` is logged by count — the documented loss window of `submit`.
     */
    @Bean
    fun persistenceDrainLifecycle(writers: ObjectProvider<BatchingWriter<*>>): PersistenceDrainLifecycle =
        PersistenceDrainLifecycle(writers.orderedStream().toList())

    companion object {
        /** The writers' `store` tags and thread-name stems (observability §4). */
        const val EXECUTION_EVENTS_STORE = "execution_events"
        const val REPLAY_LOG_STORE = "replay_log"
    }
}
