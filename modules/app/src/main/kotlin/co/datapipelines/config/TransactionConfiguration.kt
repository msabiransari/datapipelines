package co.datapipelines.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * The metadata database's transaction manager, and the only one in this application
 * (ARCH-AUDIT-2026-08 S3, ruling R6; the consistency model is dag-executor.md §16).
 *
 * ## Why the bean is NAMED, and why every `@Transactional` names it back
 *
 * There is exactly one Spring transaction resource here — the metadata `DataSource` — and there
 * are N Hikari pools for CUSTOMER databases which are **not** Spring transaction resources and
 * must never become one. A bare `@Transactional` binds to whichever manager Spring happens to
 * find: correct by accident today, a trap the moment anyone registers a second manager for a
 * customer pool. So the bean is `metadataTransactionManager`, every annotation in
 * `modules/&#42;/src/main` spells that name, and `ArchitectureGuardTest` fails the build on a bare
 * one. A name that is merely conventional is not a control; a name a test enforces is.
 *
 * **One transaction, one database.** No XA, no JTA, no two-phase commit: the customer databases
 * are not ours (SQLite and DuckDB have no meaningful XA), and an in-doubt distributed transaction
 * on a customer's database is a worse failure than any it would prevent. Every seam between two
 * databases is handled by design instead — dag-executor.md §16 states the whole table, and
 * `ConnectionLease` refuses to lease a customer connection while a metadata transaction is open
 * on the thread, so the rule is mechanical rather than remembered.
 *
 * ## Why it lives here
 *
 * `app` is the composition root and owns the `DataSource` (module-structure §3.1 rule 2: schema
 * and datasource ownership are `app`'s). Declaring the manager explicitly also takes Spring
 * Boot's `DataSourceTransactionManagerAutoConfiguration` out of the picture
 * (`@ConditionalOnMissingBean(TransactionManager)`), so there is one manager with one known name
 * rather than an auto-configured one called `transactionManager`.
 *
 * `proxyTargetClass = true` is explicit rather than inherited from `spring.aop.proxy-target-class`:
 * the services are Kotlin classes with no interface, so CGLIB is the only proxy that can work,
 * and this is not a setting that should be silently flippable from a property file. The classes
 * themselves are opened by `kotlin("plugin.spring")`, applied to every module by
 * `CommonConventionsPlugin` — without it a `@Transactional` method would run on the un-proxied
 * class with **no error and no transaction**, which is why `TransactionRollbackIntegrationTest`
 * exists and why its falsification matters more than its green run.
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
class TransactionConfiguration {
    @Bean(METADATA_TRANSACTION_MANAGER)
    fun metadataTransactionManager(dataSource: DataSource): PlatformTransactionManager = DataSourceTransactionManager(dataSource)

    companion object {
        /** The one manager's bean name — the string every `@Transactional` in main must carry. */
        const val METADATA_TRANSACTION_MANAGER: String = "metadataTransactionManager"
    }
}
