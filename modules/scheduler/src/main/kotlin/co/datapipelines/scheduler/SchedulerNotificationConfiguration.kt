package co.datapipelines.scheduler

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * Schedule notification mail's beans (#442b; scheduler design revision §6.1–§6.2), imported by
 * [SchedulerAutoConfiguration] so they exist exactly where the scheduler does. The scheduler owns
 * the decision, the outbox and the sender; the mail transport stays behind a port `web` implements
 * over auth's shared sender.
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
}
