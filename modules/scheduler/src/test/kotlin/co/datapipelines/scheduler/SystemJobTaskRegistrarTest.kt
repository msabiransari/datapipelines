package co.datapipelines.scheduler

import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * #957 D1 — a module declares a job bean and names no db-scheduler type; the registrar gives the context one `Task`
 * bean per job, which is the list the starter builds the `Scheduler` from. The jobs are created lazily, only when
 * their task is.
 */
class SystemJobTaskRegistrarTest {
    @Test
    fun `one task bean per keyed and per recurring job bean, carrying the job's name and instances sentence`() {
        AnnotationConfigApplicationContext(Jobs::class.java).use { context ->
            val tasks = context.getBeansOfType(Task::class.java)
            tasks.keys shouldContainExactlyInAnyOrder listOf("keyedJobSystemJobTask", "recurringJobSystemJobTask")
            val keyed = tasks.getValue("keyedJobSystemJobTask")
            keyed.shouldBeInstanceOf<KeyedSystemTask>()
            keyed.name shouldBe "keyed-test-job"
            keyed.instances shouldBe "one instance per key"
            val recurring = tasks.getValue("recurringJobSystemJobTask")
            recurring.shouldBeInstanceOf<RecurringTask<*>>()
            recurring.name shouldBe "recurring-test-job"
        }
    }

    @Test
    fun `no job bean means no task bean`() {
        AnnotationConfigApplicationContext(NoJobs::class.java).use { context ->
            context.getBeansOfType(Task::class.java).keys shouldBe emptySet()
        }
    }

    @Configuration(proxyBeanMethods = false)
    open class Jobs {
        @Bean
        open fun keyedJob(): KeyedSystemJob =
            object : KeyedSystemJob {
                override val name = "keyed-test-job"
                override val instances = "one instance per key"

                override fun run(key: String) = SystemJobOutcome.Done
            }

        @Bean
        open fun recurringJob(): RecurringSystemJob =
            object : RecurringSystemJob {
                override val name = "recurring-test-job"
                override val fixedDelay: Duration = Duration.ofMinutes(1)

                override fun run() = Unit
            }

        companion object {
            @Bean
            @JvmStatic
            fun systemJobTaskRegistrar(): SystemJobTaskRegistrar = SystemJobTaskRegistrar()
        }
    }

    @Configuration(proxyBeanMethods = false)
    open class NoJobs {
        companion object {
            @Bean
            @JvmStatic
            fun systemJobTaskRegistrar(): SystemJobTaskRegistrar = SystemJobTaskRegistrar()
        }
    }
}
