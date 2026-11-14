package co.datapipelines.web.config

import co.datapipelines.lake.profiles.LakeProfileSeed
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider

/**
 * The `production` lake profile's one-time seed at start-up (#956 D3, lane cut L5), in the [LakeCatalogStartupCheck]
 * shape: a bean whose `@PostConstruct` runs once the schema is migrated. It takes the catalog check as a constructor
 * argument only so Spring builds that check, and so verifies the catalog, first.
 *
 * A no-op when `datapipelines.lake.enabled=false`: `lake`'s auto-configuration then declares no [LakeProfileSeed],
 * and `production` keeps its `seed_pending` mark until the lake is first enabled. When the lake is enabled, a seed
 * the profile rules refuse throws and refuses boot with `lake.profile.invalid`.
 */
class LakeProfileSeedStartup(
    private val seed: ObjectProvider<LakeProfileSeed>,
    @Suppress("UnusedPrivateProperty") private val catalogCheck: LakeCatalogStartupCheck,
) {
    @PostConstruct
    fun seedProduction() {
        seed.ifAvailable { it.seed() }
    }
}
