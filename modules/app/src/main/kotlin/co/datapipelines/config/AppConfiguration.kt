package co.datapipelines.config

import co.datapipelines.web.endpoints.ConversionLocks
import co.datapipelines.web.endpoints.StartupConversion
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The app module's own bean wiring (015, module-structure.md §8.4): `app` is the
 * composition root, and its one collaborator is declared here explicitly like every
 * other module's beans.
 */
@Configuration
class AppConfiguration {
    @Bean
    fun configValidator(environment: Environment): ConfigValidator = ConfigValidator(environment)

    /**
     * #786 §E.1 — the upgrade's in-place conversion families, run after Flyway and before serving or dispatch; #895 —
     * in ONE transaction on the metadata transaction manager, under the runner's G0 acquisitions ([ConversionLocks]).
     * [steps] arrive in their beans' declared order (the APIs, then the schedules).
     */
    @Bean
    fun conversionStartup(
        steps: List<StartupConversion>,
        transactionManager: PlatformTransactionManager,
        locks: ConversionLocks,
    ): ConversionStartup = ConversionStartup(steps, TransactionTemplate(transactionManager), locks)
}
