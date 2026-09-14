package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bean wiring for the mail slice (auth.md §5A.8, configuration.md §3.27) — a sibling of
 * [LocalAuthConfiguration] for the same size reason. Every bean exists in every deployment;
 * the ONE decision here is which [MailSender] backs the port: [SpringMailSender] when
 * [MailProperties.enabled] (host and from both set), [NoopMailSender] otherwise. The bean
 * graph is identical either way, and the hook points never know which they got.
 *
 * ## The pool
 * Two threads, a queue of 100, **caller-runs** on overflow. Not discard: a discarded task is a
 * welcome mail whose password nobody else has — the admin's screen says "emailed" and nothing
 * arrives. Caller-runs turns a saturated relay into a slower request (one bounded SMTP round
 * trip on the request thread, the transport's own timeouts capping it) rather than a silent
 * loss, and a queue of 100 is two orders of magnitude above any plausible burst of user
 * creations. Daemon threads, so a stuck relay cannot hold the JVM open at shutdown; the
 * executor is shut down with the context.
 */
@Configuration
@EnableConfigurationProperties(MailProperties::class)
class MailConfiguration {
    private val log = LoggerFactory.getLogger(MailConfiguration::class.java)

    @Bean
    fun mailSendRepository(jdbc: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate): MailSendRepository =
        MailSendRepository(jdbc)

    @Bean
    fun mailTemplates(): MailTemplates = MailTemplates()

    /** The port: the real transport when configured, the logging no-op when not — decided once, here. */
    @Bean
    fun mailSender(properties: MailProperties): MailSender =
        if (properties.enabled) {
            log.info(
                "event=mail.configured host={} port={} starttls={} from_domain={} ops_to={} stream={}",
                properties.host,
                properties.port,
                properties.starttls,
                properties.fromAddress()?.let(MailMessage::domainOf),
                properties.opsRecipients().size,
                properties.messageStream?.takeIf { it.isNotBlank() } != null,
            )
            SpringMailSender(properties)
        } else {
            log.info("event=mail.disabled message=\"datapipelines.mail.host and from are not both set; notices are logged, not sent\"")
            NoopMailSender()
        }

    @Bean(destroyMethod = "shutdown")
    fun mailExecutor(): ExecutorService =
        ThreadPoolExecutor(
            MAIL_THREADS,
            MAIL_THREADS,
            IDLE_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAIL_QUEUE),
            DaemonThreads,
            ThreadPoolExecutor.CallerRunsPolicy(),
        )

    @Suppress("LongParameterList") // the wiring bean — every parameter is an @Bean reference (019 precedent)
    @Bean
    fun mailNotices(
        properties: MailProperties,
        authProperties: AuthProperties,
        mailTemplates: MailTemplates,
        mailSender: MailSender,
        mailSendRepository: MailSendRepository,
        auditLogger: AuditLogger,
        mailExecutor: ExecutorService,
    ): MailNotices = MailNotifier(properties, authProperties, mailTemplates, mailSender, mailSendRepository, auditLogger, mailExecutor)

    private object DaemonThreads : ThreadFactory {
        private val counter = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "mail-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private companion object {
        const val MAIL_THREADS = 2
        const val MAIL_QUEUE = 100
        const val IDLE_KEEP_ALIVE_SECONDS = 60L
    }
}
