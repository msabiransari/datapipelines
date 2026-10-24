package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock

/**
 * Schedule notification mail's beans (#442b; scheduler design revision §6.1–§6.2), imported by
 * [SchedulerAutoConfiguration] so they exist exactly where the scheduler does. The scheduler owns
 * the decision, the outbox and the sender; the mail transport stays behind a port `web` implements
 * over auth's shared sender ([NotificationMailer]; without one, every send is refused
 * `mail_unconfigured`).
 */
@Configuration(proxyBeanMethods = false)
class SchedulerNotificationConfiguration {
    /**
     * The owner's mail-safety ruling as one decision, over the deployment facts `web` supplies
     * ([NotificationDeployment]); without them, mail is off.
     */
    @Bean
    fun notificationPolicy(
        properties: SchedulerProperties,
        deployment: ObjectProvider<NotificationDeployment>,
    ): NotificationPolicy = NotificationPolicy(properties.notifications, deployment.ifAvailable ?: NotificationDeployment.UNKNOWN)

    @Bean
    fun notificationRepository(jdbc: NamedParameterJdbcTemplate): NotificationRepository = NotificationRepository(jdbc)

    /** The writer [RunLedger] calls inside every transition's transaction (record §6.1). */
    @Bean
    fun notificationOutbox(
        repository: NotificationRepository,
        runs: ScheduleRunRepository,
        policy: NotificationPolicy,
        properties: SchedulerProperties,
    ): NotificationOutbox = NotificationOutbox(repository, runs, policy, properties.notifications.backlogMax)

    /**
     * The sender's statements on a DEDICATED template carrying a `queryTimeout` (the house shape for a
     * bounded metadata statement, #311/#326 — the scheduler cannot see `dag`'s helper, so the five
     * lines are spelled here): only the sender's statements inherit the bound.
     */
    @Bean
    fun notificationDeliveryRepository(jdbc: NamedParameterJdbcTemplate): NotificationDeliveryRepository =
        NotificationDeliveryRepository(bounded(jdbc))

    @Bean
    @Suppress("LongParameterList") // the sender's collaborators, each one a step of its KDoc
    fun scheduleNotifier(
        deliveries: NotificationDeliveryRepository,
        runs: ScheduleRunRepository,
        policy: NotificationPolicy,
        properties: SchedulerProperties,
        mailer: ObjectProvider<NotificationMailer>,
        transactions: SchedulerTransactions,
        @Qualifier("schedulerClock") clock: Clock,
        metrics: SchedulerMetrics,
        workerName: SchedulerWorkerName,
    ): ScheduleNotifier =
        ScheduleNotifier(
            deliveries = deliveries,
            runs = runs,
            policy = policy,
            notifications = properties.notifications,
            mailer = mailer.ifAvailable ?: NotificationMailer.UNAVAILABLE,
            templates = NotificationTemplates(),
            transactions = transactions.template,
            clock = clock,
            metrics = metrics,
            // db-scheduler's own dead-execution window: a claim older than that belongs to an execution the
            // library has given up on, never to a send still in flight on a live one.
            staleAfter = properties.startGrace,
            worker = workerName.value,
        )

    @Bean
    fun scheduleNotifierTask(
        notifier: ScheduleNotifier,
        properties: SchedulerProperties,
    ): RecurringTask<Void> = SchedulerTasks.notifierTask(notifier, properties)

    private fun bounded(jdbc: NamedParameterJdbcTemplate): NamedParameterJdbcTemplate {
        val dataSource = requireNotNull(jdbc.jdbcTemplate.dataSource) { "the notification sender needs a DataSource-backed template" }
        return NamedParameterJdbcTemplate(JdbcTemplate(dataSource).apply { queryTimeout = SENDER_STATEMENT_TIMEOUT_SECONDS })
    }

    companion object {
        /** The bound on every sender statement — each is one indexed row (or a short batch of stale claims). */
        const val SENDER_STATEMENT_TIMEOUT_SECONDS = 30
    }
}
