package co.datapipelines.datasources

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * The datasources module's Spring seam (module-structure §8.2: no component-scanned
 * stereotypes, so this class is where the module's configuration enters the context).
 *
 * Today it declares no beans — the module's components are assembled in `web`'s
 * `DomainConfiguration` — and exists to bind [DatasourceFileRootsProperties] (#186): the
 * file-roots rule is a property of this aggregate, so its binding lives here and the
 * `DatasourceValidator` receives the resolved [DatasourceFileRoots] at wiring.
 */
@Configuration
@EnableConfigurationProperties(DatasourceFileRootsProperties::class)
class DatasourcesConfiguration
