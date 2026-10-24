package co.datapipelines.scheduler

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
}
