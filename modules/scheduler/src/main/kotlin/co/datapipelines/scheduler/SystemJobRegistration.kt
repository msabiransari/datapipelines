package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException
import com.github.kagkarlsson.scheduler.task.TaskInstance
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import java.time.Instant

/**
 * #957 D1 — [SystemJobQueue] over db-scheduler. The client is looked up lazily: the `Scheduler` depends on every task
 * bean, a task on its job, and a job may depend on this queue.
 *
 * [enqueueIfAbsent] is `scheduleIfNotExists` (one row per task name and instance id, the table's primary key).
 * [move] is the library's conditional `reschedule`, which refuses a picked instance
 * (`TaskInstanceCurrentlyExecutingException`) and an absent one (`TaskInstanceNotFoundException`); both answer false,
 * and a lost version race (the instance was picked or changed between the read and the update) answers the library's
 * false too.
 */
class DbSchedulerSystemJobQueue(
    private val client: () -> SchedulerClient,
) : SystemJobQueue {
    override fun enqueueIfAbsent(
        job: KeyedSystemJob,
        key: String,
        at: Instant,
    ): Boolean = client().scheduleIfNotExists(TaskInstance(job.name, key, key), at)

    override fun move(
        job: KeyedSystemJob,
        key: String,
        at: Instant,
    ): Boolean =
        try {
            client().reschedule(TaskInstanceId.of(job.name, key), at)
        } catch (_: TaskInstanceCurrentlyExecutingException) {
            false
        } catch (_: TaskInstanceNotFoundException) {
            false
        }
}

/**
 * #957 D1 — registers one db-scheduler task bean per [KeyedSystemJob] and per [RecurringSystemJob] bean, so a module
 * that declares a job never names a db-scheduler type, and the starter's task list (every `Task` bean) carries it.
 * Runs after the configuration classes registered their beans; reads the job beans' TYPES without creating them, and
 * the task's supplier fetches the job when the `Scheduler` asks for its tasks.
 */
class SystemJobTaskRegistrar : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val factory = registry as? ListableBeanFactory ?: return
        val beans = registry as BeanFactory
        factory.getBeanNamesForType(KeyedSystemJob::class.java, true, false).forEach { jobBean ->
            registry.registerBeanDefinition(
                "$jobBean$TASK_SUFFIX",
                RootBeanDefinition(KeyedSystemTask::class.java) { SchedulerTasks.keyedTask(beans.getBean(jobBean, KeyedSystemJob::class.java)) },
            )
        }
        factory.getBeanNamesForType(RecurringSystemJob::class.java, true, false).forEach { jobBean ->
            registry.registerBeanDefinition(
                "$jobBean$TASK_SUFFIX",
                RootBeanDefinition(RecurringTask::class.java) {
                    SchedulerTasks.recurringTask(beans.getBean(jobBean, RecurringSystemJob::class.java))
                },
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit

    companion object {
        /** A job bean's task bean is named `<job bean name>SystemJobTask`. */
        const val TASK_SUFFIX = "SystemJobTask"
    }
}
