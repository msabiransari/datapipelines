package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerName
import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter
import com.github.kagkarlsson.scheduler.exceptions.SerializationException
import com.github.kagkarlsson.scheduler.serializer.Serializer
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetAddress
import java.time.Clock
import java.util.Optional
import java.util.UUID

/**
 * The scheduler's beans (module-structure §5.16, §8.2 — the module's own auto-configuration, so no
 * db-scheduler type leaves this module: scheduler design revision §6.2).
 *
 * What `web` supplies, as beans of this module's ports: the [JobExecutor]s (the pipeline adapter),
 * the [CapacityGate] (over `dag`'s `ExecutionSlots`) and the [SystemActorSource] (the system
 * identity's row, R2). What this configuration supplies to db-scheduler's starter — which it runs
 * BEFORE, so the starter's `@ConditionalOnMissingBean` defaults step aside: the JSON serializer and
 * this instance's scheduler name ([DbSchedulerCustomizer], A8), and the start gate
 * ([SchedulerStartGate], A19). The starter builds the `Scheduler` over the metadata `DataSource`
 * wrapped in `TransactionAwareDataSourceProxy` — which is what spike 1 proves.
 *
 * Conditional on a [SystemActorSource] bean: a slice context that wires no scheduler (a web slice
 * test, a module-slice suite) gets none of it.
 */
@AutoConfiguration(before = [DbSchedulerAutoConfiguration::class])
@ConditionalOnBean(SystemActorSource::class)
@EnableConfigurationProperties(SchedulerProperties::class)
class SchedulerAutoConfiguration {
    /** The scheduler's clock — injectable, so a test can drive time (record §8's controlled clock). */
    @Bean
    @ConditionalOnMissingBean(name = ["schedulerClock"])
    fun schedulerClock(): Clock = Clock.systemUTC()

    /** This instance's worker name: the trail's `worker`, db-scheduler's `picked_by`. */
    @Bean
    fun schedulerWorkerName(): SchedulerWorkerName = SchedulerWorkerName.forThisProcess()

    /**
     * The JSON serializer (A8) and the instance name, handed to the starter. A test fails if the
     * starter's Java-serialization default ever comes back (`SchedulerSerializerTest`).
     */
    @Bean
    fun schedulerCustomizer(workerName: SchedulerWorkerName): DbSchedulerCustomizer =
        object : DbSchedulerCustomizer {
            override fun serializer(): Optional<Serializer> = Optional.of(JSON_SERIALIZER)

            override fun schedulerName(): Optional<SchedulerName> = Optional.of(SchedulerName.Fixed(workerName.value))
        }

    /** Starts db-scheduler after the application is ready — never in API mode (A19). */
    @Bean
    fun schedulerStartGate(
        scheduler: Scheduler,
        properties: SchedulerProperties,
    ): DbSchedulerStarter = SchedulerStartGate(scheduler, properties)

    @Bean
    fun scheduleRepository(jdbc: NamedParameterJdbcTemplate): ScheduleRepository = ScheduleRepository(jdbc, JSON)

    @Bean
    fun scheduleRunRepository(jdbc: NamedParameterJdbcTemplate): ScheduleRunRepository = ScheduleRunRepository(jdbc, JSON)

    /** One `TransactionTemplate` over THE metadata manager (module-structure §8.5 rule 1). */
    @Bean
    fun schedulerTransactions(
        @Qualifier(METADATA_TRANSACTION_MANAGER) manager: PlatformTransactionManager,
    ): SchedulerTransactions = SchedulerTransactions(TransactionTemplate(manager))

    @Bean
    fun schedulerMetrics(registry: ObjectProvider<MeterRegistry>): SchedulerMetrics =
        registry.ifAvailable?.let(::SchedulerMetrics) ?: SchedulerMetrics()

    @Bean
    fun jobExecutors(executors: List<JobExecutor>): JobExecutors = JobExecutors(executors)

    @Bean
    fun runLedger(
        runs: ScheduleRunRepository,
        schedules: ScheduleRepository,
        transactions: SchedulerTransactions,
        @Qualifier("schedulerClock") clock: Clock,
        metrics: SchedulerMetrics,
        workerName: SchedulerWorkerName,
    ): RunLedger = RunLedger(runs, schedules, transactions.template, clock, metrics, workerName.value)

    /**
     * The admission gate — the lifecycle bean that stops BEFORE the execution drain (record §7.2,
     * A7). The `Scheduler` is looked up lazily: it depends on the task beans, which depend on the
     * worker, which depends on this gate.
     */
    @Bean
    fun schedulerAdmission(
        properties: SchedulerProperties,
        scheduler: ObjectProvider<Scheduler>,
    ): SchedulerAdmission = SchedulerAdmission(properties.shutdownWait) { scheduler.ifAvailable?.pause() }

    @Bean
    @Suppress("LongParameterList") // the dispatcher's collaborators, each one a step of its KDoc
    fun scheduleDispatcher(
        schedules: ScheduleRepository,
        runs: ScheduleRunRepository,
        ledger: RunLedger,
        transactions: SchedulerTransactions,
        @Qualifier("schedulerClock") clock: Clock,
        properties: SchedulerProperties,
        metrics: SchedulerMetrics,
        systemActor: SystemActorSource,
    ): ScheduleDispatcher =
        ScheduleDispatcher(schedules, runs, ledger, transactions.template, clock, properties, metrics, systemActor::userId)

    @Bean
    @Suppress("LongParameterList") // the worker's collaborators, each one a step of its KDoc
    fun scheduledRunWorker(
        runs: ScheduleRunRepository,
        schedules: ScheduleRepository,
        executors: JobExecutors,
        capacity: CapacityGate,
        admission: SchedulerAdmission,
        ledger: RunLedger,
        transactions: SchedulerTransactions,
        @Qualifier("schedulerClock") clock: Clock,
        metrics: SchedulerMetrics,
    ): ScheduledRunWorker =
        ScheduledRunWorker(runs, schedules, executors, capacity, admission, ledger, transactions.template, clock, metrics)

    @Bean
    fun runReconciler(
        runs: ScheduleRunRepository,
        executors: JobExecutors,
        ledger: RunLedger,
        @Qualifier("schedulerClock") clock: Clock,
        properties: SchedulerProperties,
    ): RunReconciler = RunReconciler(runs, executors, ledger, clock, properties)

    @Bean
    fun scheduleRunTask(worker: ScheduledRunWorker): CustomTask<String> = SchedulerTasks.runTask(worker)

    @Bean
    fun scheduleDispatcherTask(
        dispatcher: ScheduleDispatcher,
        properties: SchedulerProperties,
    ): RecurringTask<Void> = SchedulerTasks.dispatcherTask(dispatcher, properties)

    @Bean
    fun scheduleReconcilerTask(
        reconciler: RunReconciler,
        properties: SchedulerProperties,
    ): RecurringTask<Void> = SchedulerTasks.reconcilerTask(reconciler, properties)

    /** The management use cases REST calls — the one scheduler bean a transport may name. */
    @Bean
    @Suppress("LongParameterList")
    fun scheduleService(
        schedules: ScheduleRepository,
        runs: ScheduleRunRepository,
        executors: JobExecutors,
        ledger: RunLedger,
        transactions: SchedulerTransactions,
        @Qualifier("schedulerClock") clock: Clock,
        properties: SchedulerProperties,
        scheduler: Scheduler,
        systemActor: SystemActorSource,
    ): ScheduleService =
        ScheduleService(
            schedules,
            runs,
            executors,
            ledger,
            transactions.template,
            clock,
            properties,
            DbSchedulerRunQueue(scheduler, SchedulerTasks.RUN_TASK),
            JSON,
            systemActor::userId,
        )

    companion object {
        /** The one metadata transaction manager's bean name (module-structure §8.5; declared in `app`). */
        const val METADATA_TRANSACTION_MANAGER = "metadataTransactionManager"

        /** The module's JSON stack for the jsonb columns — payloads are opaque trees, so no custom modules. */
        val JSON: ObjectMapper = jacksonObjectMapper()

        /**
         * The JSON serializer (A8) over THIS module's mapper — [TaskDataJsonSerializer], not the
         * library's `JacksonSerializer`, whose class links `JavaTimeModule` (jackson-datatype-jsr310,
         * an optional db-scheduler dependency this module does not take) and fails to LOAD without
         * it. Task data here is a run id string at most, so the plain mapper is all it needs.
         */
        val JSON_SERIALIZER: Serializer = TaskDataJsonSerializer(JSON)
    }
}

/** The system identity's user id (R2) — `web` supplies it from `UserService.systemActor()`. */
fun interface SystemActorSource {
    fun userId(): UUID
}

/**
 * This instance's worker name — hostname, process id and a random suffix, so two JVMs on one host
 * differ. A plain class, deliberately NOT a Kotlin value class: a value class erases to `String` at
 * a bean method's JVM signature, and a second `String` bean breaks every by-type `String` injection
 * in the context (found by the first full-context boot).
 */
data class SchedulerWorkerName(
    val value: String,
) {
    companion object {
        fun forThisProcess(): SchedulerWorkerName {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
            val suffix = UUID.randomUUID().toString().take(SUFFIX_CHARS)
            return SchedulerWorkerName("$host:${ProcessHandle.current().pid()}:$suffix".take(MAX_CHARS))
        }

        private const val SUFFIX_CHARS = 8
        private const val MAX_CHARS = 120
    }
}

/** A named wrapper so the scheduler's template never competes with another `TransactionTemplate` bean. */
class SchedulerTransactions(
    val template: TransactionTemplate,
)

/**
 * db-scheduler's task data as JSON (record §2.2, A8) — never the starter's Java-serialization default,
 * which is an unsafe-deserialization surface and breaks when a class is renamed. The scheduler's
 * task data is a run id string (the run task) or nothing (the two recurring tasks).
 */
class TaskDataJsonSerializer(
    private val mapper: ObjectMapper,
) : Serializer {
    override fun serialize(data: Any?): ByteArray? =
        data?.let {
            try {
                mapper.writeValueAsBytes(it)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                throw SerializationException("Failed to serialize task data.", e)
            }
        }

    override fun <T : Any?> deserialize(
        clazz: Class<T>,
        serializedData: ByteArray?,
    ): T? =
        serializedData?.let {
            try {
                mapper.readValue(it, clazz)
            } catch (e: java.io.IOException) {
                throw SerializationException("Failed to deserialize task data.", e)
            }
        }
}
