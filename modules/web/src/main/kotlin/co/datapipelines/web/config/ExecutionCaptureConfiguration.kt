package co.datapipelines.web.config

import co.datapipelines.application.endpoints.ExecutionCaptureSessionRepository
import co.datapipelines.application.endpoints.ExecutionCaptureStore
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.executor.BoundedJdbc
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.web.endpoints.CaptureRecorders
import co.datapipelines.web.endpoints.ExecutionCaptureService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * Runtime capture of published mutations (#1043; published-mutation-apis §6, §7): the session and detail stores, the
 * per-run recorder factory the mutation launcher uses, and the administrator service behind the REST and console
 * surfaces. Capture is off until an administrator opens a window; nothing here changes an uncaptured run.
 */
@Configuration
class ExecutionCaptureConfiguration {
    /** metadata-db §4.62 — the administrator-opened windows. */
    @Bean
    fun executionCaptureSessionRepository(
        jdbc: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): ExecutionCaptureSessionRepository = ExecutionCaptureSessionRepository(jdbc, TransactionTemplate(transactionManager))

    /**
     * metadata-db §4.63/§4.64 — the private values and run summaries. Each write is its own short metadata transaction
     * (never a business one), and every statement carries the diagnostic statement bound: the executor's
     * `lifecycle-write-timeout-seconds` (default 10), through the house [BoundedJdbc] shape — a slow or unanswering
     * metadata store ends a diagnostic write, never holds it (or the recorder's drain) open.
     */
    @Bean
    fun executionCaptureStore(
        jdbc: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
        executor: ExecutorProperties,
    ): ExecutionCaptureStore =
        ExecutionCaptureStore(
            BoundedJdbc.bounded(jdbc, executor.lifecycleWriteTimeoutSeconds, "lifecycleWriteTimeoutSeconds"),
            TransactionTemplate(transactionManager),
        )

    /**
     * The recorder of a captured admission, bounded by `datapipelines.script.max-value-bytes` per execution; it owns the
     * diagnostic write scope, which the container closes at shutdown (`AutoCloseable`).
     */
    @Bean
    fun captureRecorders(
        store: ExecutionCaptureStore,
        scriptProperties: ScriptProperties,
        meters: MeterRegistry,
    ): CaptureRecorders = CaptureRecorders(store, scriptProperties.maxValueBytes, meters)

    /** rest-api §26 / ui-screens §4.18c — open, list, stop and inspect, with the window fixed at start. */
    @Bean
    @Suppress("LongParameterList") // a DI factory: every parameter is a bean the service genuinely needs
    fun executionCaptureService(
        endpoints: PublishedEndpointRepository,
        sessions: ExecutionCaptureSessionRepository,
        reservations: PublishedMutationReservationRepository,
        store: ExecutionCaptureStore,
        executions: ExecutionRepository,
        audit: AuditEventSink,
        properties: EndpointsProperties,
    ): ExecutionCaptureService =
        ExecutionCaptureService(
            endpoints = endpoints,
            sessions = sessions,
            reservations = reservations,
            store = store,
            executions = executions,
            audit = audit,
            window = Duration.ofSeconds(properties.captureWindowSeconds.toLong()),
        )
}
