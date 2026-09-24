package co.datapipelines.auth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * Minimal Spring Boot application for the OIDC integration test. Component scanning is
 * rooted at `co.datapipelines.auth`, so it wires the real [SecurityConfig], filters,
 * [OidcConfig], services and repositories against auto-configured Spring MVC, Security
 * and JDBC — the same beans the production app assembles, exercised end to end.
 *
 * It declares the one piece of production wiring that lives above this module: the
 * `metadataTransactionManager` (`modules/app`'s `TransactionConfiguration`), with the same
 * name and the same CGLIB proxying — since #215 slice (b) key issuance and revocation are
 * `@Transactional("metadataTransactionManager")` (identity + key in one transaction, B4), and a
 * context without the named manager fails the first call rather than running it untransacted.
 */
@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
class AuthTestApplication {
    @Bean("metadataTransactionManager")
    fun metadataTransactionManager(dataSource: DataSource): PlatformTransactionManager = DataSourceTransactionManager(dataSource)
}
