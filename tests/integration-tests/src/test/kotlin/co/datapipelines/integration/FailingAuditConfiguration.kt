package co.datapipelines.integration

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The release audit sink of a [FamilyRootsAuditE2eTest] context (#873 §C.7, round 2): the PRODUCTION strict sink (bean
 * `releaseAuditSink`) wrapped in a JDK proxy that, when armed for `n` rows, throws a JAVA exception on the n-th
 * `record` call — the release's last row, the root's own, written after every flip — and records which event it
 * refused in [refused]. Otherwise it delegates unchanged. Java, not a database error, on purpose (the API-root mold,
 * `EndpointReleaseCascadeE2eTest.FailingAuditConfiguration`): a PostgreSQL error would abort the transaction by itself
 * and hide a coordinator that swallowed the failure. Wrapped by name and by reflection because this module compiles
 * against the app only; imported explicitly because `@SpringBootTest(classes = …)` does not pick a configuration up.
 */
@TestConfiguration
@Suppress("UtilityClassWithPublicConstructor")
class FailingAuditConfiguration {
    companion object {
        private val remaining = AtomicInteger(0)

        /** The event of the row the plant refused; null until it fires. */
        val refused = AtomicReference<String?>(null)

        /** Fails the [rows]-th `record` call from now. */
        fun arm(rows: Int) {
            refused.set(null)
            remaining.set(rows)
        }

        fun disarm() {
            remaining.set(0)
        }

        @JvmStatic
        @Bean
        fun failingFamilyRootsAuditSink(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (beanName != SINK_BEAN) {
                        bean
                    } else {
                        Proxy.newProxyInstance(bean.javaClass.classLoader, bean.javaClass.interfaces) { _, method, args ->
                            if (method.name == "record" && remaining.get() > 0 && remaining.decrementAndGet() == 0) {
                                val row = args?.firstOrNull()
                                refused.set(row?.let { it.javaClass.getMethod("getEvent").invoke(it) as String })
                                error("planted by #873: the release's last audit row is refused")
                            }
                            try {
                                method.invoke(bean, *(args ?: emptyArray()))
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                        }
                    }
            }

        private const val SINK_BEAN = "releaseAuditSink"
    }
}
