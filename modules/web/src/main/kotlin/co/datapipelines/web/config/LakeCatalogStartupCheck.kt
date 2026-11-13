package co.datapipelines.web.config

import co.datapipelines.lake.LakeBootCheck
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider

/**
 * The managed lake's after-Flyway boot check (#785 A6; configuration.md §7), in the [AuthoringStartupCheck]
 * shape: a bean whose `@PostConstruct` runs once the schema is migrated, because the [LakeBootCheck] it calls
 * reads through the metadata `JdbcTemplate`, which Spring Boot orders after Flyway.
 *
 * A no-op when `datapipelines.lake.enabled=false`: `lake`'s auto-configuration then declares no [LakeBootCheck],
 * and the provider is empty. When the lake is enabled, a failed check throws and refuses boot, naming the
 * catalogued refusal (`lake.boot.*`).
 */
class LakeCatalogStartupCheck(
    private val bootCheck: ObjectProvider<LakeBootCheck>,
) {
    @PostConstruct
    fun check() {
        bootCheck.ifAvailable { it.verify() }
    }
}
