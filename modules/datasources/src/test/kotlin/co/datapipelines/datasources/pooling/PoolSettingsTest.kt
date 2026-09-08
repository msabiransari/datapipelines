package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DialectAdapters
import co.datapipelines.datasources.Fixtures
import co.datapipelines.datasources.RefusedPropertyKeys
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §5 pool-settings catalog (094): what the create/edit dialog offers, what it prefills, and
 * the range rules that keep the two honest.
 *
 * The load-bearing test in this file is
 * [every catalogued floor is one HikariCP would SILENTLY apply for us] — it constructs a real
 * `HikariDataSource` with each out-of-range value and asserts that HikariCP quietly rewrote it.
 * That is the whole justification for validating here at all: without it, this catalog is a
 * pile of numbers somebody typed, and the next HikariCP bump could move one with nothing to
 * notice.
 */
class PoolSettingsTest {
    private val h2 = DialectAdapters.forDialect(Dialect.H2)

    private fun withHikari(vararg pairs: Pair<String, Any?>) =
        Fixtures.h2(name = "pool_ds", properties = DatasourceProperties(hikari = mapOf(*pairs)))

    // ------------------------------------------------------------------ the catalog

    @Test
    fun `the catalog is exactly the keys the round names, in render order`() {
        PoolSettings.CATALOG.map { it.key } shouldBe
            listOf(
                "maximumPoolSize",
                "minimumIdle",
                "connectionTimeout",
                "idleTimeout",
                "maxLifetime",
                "keepaliveTime",
                "validationTimeout",
                "leakDetectionThreshold",
            )
    }

    @Test
    fun `no catalogued key is a refused key - the dialog can never render one`() {
        // §5.6: `readOnly` is the datasource's own flag, `connectionInitSql` is adapter-derived,
        // and the credential slots are never a form field. A catalog entry that collided with
        // one of those would render an input whose every value the validator refuses.
        val refused = RefusedPropertyKeys.forDialect(Dialect.H2, h2)
        PoolSettings.KEYS.filter { RefusedPropertyKeys.isRefused(it, refused) }.shouldBeEmpty()
    }

    @Test
    fun `every catalogued key resolves to an effective default - the prefill is total`() {
        val defaults = PoolSettings.defaults(h2)

        defaults.map { it.setting.key } shouldContainExactlyInAnyOrder PoolSettings.KEYS.toList()
    }

    @Test
    fun `the shipped defaults are the documented layering - minimumIdle ours, the rest HikariCP's`() {
        val bySource = PoolSettings.defaults(h2).associate { it.setting.key to (it.source to it.value) }

        // No shipped dialect overrides a pool setting today, so the DIALECT layer is empty and
        // every default comes from one of the other two. This is the table the docs publish.
        bySource["minimumIdle"] shouldBe (PoolSettingSource.APPLICATION to 2L)
        bySource["maximumPoolSize"] shouldBe (PoolSettingSource.HIKARI to 10L)
        bySource["connectionTimeout"] shouldBe (PoolSettingSource.HIKARI to 30_000L)
        bySource["idleTimeout"] shouldBe (PoolSettingSource.HIKARI to 600_000L)
        bySource["maxLifetime"] shouldBe (PoolSettingSource.HIKARI to 1_800_000L)
        bySource["keepaliveTime"] shouldBe (PoolSettingSource.HIKARI to 120_000L)
        bySource["validationTimeout"] shouldBe (PoolSettingSource.HIKARI to 5_000L)
        bySource["leakDetectionThreshold"] shouldBe (PoolSettingSource.HIKARI to 0L)
    }

    @Test
    fun `the HikariCP defaults are READ from the pinned library, never transcribed`() {
        // If this ever disagrees, the catalog transcribed a number instead of reading it.
        val fresh = HikariConfig()

        PoolSettings.HIKARI_DEFAULTS["maximumPoolSize"] shouldBe fresh.maximumPoolSize.toLong()
        PoolSettings.HIKARI_DEFAULTS["maxLifetime"] shouldBe fresh.maxLifetime
        PoolSettings.HIKARI_DEFAULTS["keepaliveTime"] shouldBe fresh.keepaliveTime
    }

    @Test
    fun `a dialect declaration outranks the application default`() {
        // The seam is empty on every shipped adapter; this proves the LAYER exists and wins,
        // so the form's "which layer supplied this" label is a fact rather than a description.
        val adapter =
            object : co.datapipelines.datasources.AbstractDialectAdapter(Dialect.H2, "h2") {
                override val defaultHikariProperties: Map<String, String> = mapOf("minimumIdle" to "7")
            }

        val minimumIdle = PoolSettings.defaults(adapter).single { it.setting.key == "minimumIdle" }

        minimumIdle.value shouldBe 7L
        minimumIdle.source shouldBe PoolSettingSource.DIALECT
    }

    @Test
    fun `a value on the row outranks every default and is reported as configured`() {
        val effective = PoolSettings.effective(withHikari("maximumPoolSize" to 4), h2)
        val maximumPoolSize = effective.single { it.setting.key == "maximumPoolSize" }

        maximumPoolSize.value shouldBe 4L
        maximumPoolSize.source shouldBe PoolSettingSource.CONFIGURED
        maximumPoolSize.configured shouldBe true
        // Everything it did not set still falls through.
        effective.single { it.setting.key == "maxLifetime" }.source shouldBe PoolSettingSource.HIKARI
    }

    @Test
    fun `a JSONB round-trip's string value is read as the number it is`() {
        // properties_json comes back with whatever Jackson made of it; the form posts strings.
        PoolSettings
            .effective(withHikari("maximumPoolSize" to "4"), h2)
            .single { it.setting.key == "maximumPoolSize" }
            .value shouldBe 4L
    }

    // ------------------------------------------------------------------ the wire shape

    @Test
    fun `the wire object carries value, unit and source under HikariCP's own key spelling`() {
        val wire = PoolSettings.wire(withHikari("connectionTimeout" to 5_000), h2)

        wire.getValue("connectionTimeout") shouldBe mapOf("value" to 5_000L, "unit" to "ms", "source" to "configured")
        wire.getValue("maximumPoolSize") shouldBe mapOf("value" to 10L, "unit" to "count", "source" to "hikari_default")
        // The key a caller writes back under properties.hikari.* is the key they were given.
        wire.keys shouldContainExactlyInAnyOrder PoolSettings.KEYS.toList()
    }

    // ------------------------------------------------------------------ the range rules

    @Test
    fun `values at or above every floor are accepted`() {
        PoolSettings
            .validate(
                withHikari(
                    "maximumPoolSize" to 1,
                    "minimumIdle" to 0,
                    "connectionTimeout" to 250,
                    "idleTimeout" to 10_000,
                    "maxLifetime" to 30_000,
                    "keepaliveTime" to 0,
                    "validationTimeout" to 250,
                    "leakDetectionThreshold" to 0,
                ),
                h2,
            ).shouldBeEmpty()
    }

    @Test
    fun `a value under a floor is refused, naming the floor, and nothing cascades off it`() {
        // 5 s is under maxLifetime's own 30 s floor AND under the default idle timeout, so a
        // naive implementation reports the same typo twice. One mistake, one error.
        val violation = PoolSettings.validate(withHikari("maxLifetime" to 5_000), h2).single()

        violation.key shouldBe "maxLifetime"
        violation.message shouldContain "at least 30000"
        violation.message shouldContain "silently replace"
    }

    @Test
    fun `zero is accepted only where HikariCP gives it a meaning`() {
        // `0` disables the keepalive and leak detection and means "wait forever" for the
        // connection timeout; for `maximumPoolSize` and `validationTimeout` it is nonsense.
        PoolSettings.validate(withHikari("keepaliveTime" to 0, "leakDetectionThreshold" to 0), h2).shouldBeEmpty()
        PoolSettings.validate(withHikari("maximumPoolSize" to 0), h2).single().key shouldBe "maximumPoolSize"
        PoolSettings.validate(withHikari("validationTimeout" to 0), h2).single().key shouldBe "validationTimeout"
    }

    @Test
    fun `a negative value is refused before any floor is consulted`() {
        PoolSettings.validate(withHikari("idleTimeout" to -1), h2).single().message shouldContain "cannot be negative"
    }

    @Test
    fun `minimumIdle above maximumPoolSize is refused - Hikari would silently lower it`() {
        val violation = PoolSettings.validate(withHikari("minimumIdle" to 50), h2).single()

        violation.key shouldBe "minimumIdle"
        violation.message shouldContain "cannot exceed 'maximumPoolSize' (10)"
    }

    @Test
    fun `keepalive at or above maxLifetime is refused - Hikari would silently disable it`() {
        val violation = PoolSettings.validate(withHikari("keepaliveTime" to 1_800_000), h2).single()

        violation.key shouldBe "keepaliveTime"
        violation.message shouldContain "less than 'maxLifetime'"
    }

    @Test
    fun `leak detection above maxLifetime is refused - Hikari would silently disable it`() {
        // maxLifetime stays at its 30-minute default: lowering it far enough to break the leak
        // threshold would also break the default keepalive and idle timeout, and this test is
        // about ONE rule.
        val violation = PoolSettings.validate(withHikari("leakDetectionThreshold" to 2_000_000), h2).single()

        violation.key shouldBe "leakDetectionThreshold"
        violation.message shouldContain "cannot exceed 'maxLifetime'"
    }

    @Test
    fun `idleTimeout within a second of maxLifetime is refused - Hikari would silently disable it`() {
        val violation = PoolSettings.validate(withHikari("idleTimeout" to 1_800_000), h2).single()

        violation.key shouldBe "idleTimeout"
        violation.message shouldContain "1000 ms below 'maxLifetime'"
    }

    @Test
    fun `a fixed-size pool has no idleTimeout rule - Hikari does not apply one either`() {
        // validateNumerics guards the idleTimeout rewrite with `minIdle < maxPoolSize`.
        PoolSettings
            .validate(withHikari("minimumIdle" to 10, "maximumPoolSize" to 10, "idleTimeout" to 1_800_000), h2)
            .shouldBeEmpty()
    }

    @Test
    fun `a cross-field violation is reported against the field the caller actually set`() {
        // The operator lowered maxLifetime to a minute, which breaks BOTH the default keepalive
        // (2 min) and the default idle timeout (10 min). Neither is a field they chose, so every
        // error has to point at the input they can actually fix.
        val violations = PoolSettings.validate(withHikari("maxLifetime" to 60_000), h2)

        violations.map { it.key }.toSet() shouldBe setOf("maxLifetime")
        violations.size shouldBe 2
    }

    @Test
    fun `an uncatalogued hikari key is not policed here - properties stays passthrough`() {
        // §5/§12.1: the API accepts any key HikariCP does, judged by the save-time pool build.
        // This catalog narrows what a PERSON is offered, never what the API accepts.
        PoolSettings.validate(withHikari("initializationFailTimeout" to -1), h2).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ the justification

    @Test
    fun `every catalogued floor is one HikariCP would SILENTLY apply for us`() {
        // The reason these rules exist. Each value below is legal to the SETTER and is then
        // rewritten by `validateNumerics()` with nothing but a WARN — so the row would store,
        // and the screen would show, a number the pool never uses.
        silentlyRewritten("maxLifetime" to 5_000L) { it.maxLifetime } shouldBe 1_800_000L
        silentlyRewritten("keepaliveTime" to 10_000L) { it.keepaliveTime } shouldBe 0L
        silentlyRewritten("leakDetectionThreshold" to 500L) { it.leakDetectionThreshold } shouldBe 0L
        silentlyRewritten("idleTimeout" to 5_000L) { it.idleTimeout } shouldBe 600_000L
        silentlyRewritten("minimumIdle" to 50L) { it.minimumIdle.toLong() } shouldBe 10L
    }

    /**
     * Builds a real pool with one out-of-range value and returns what HikariCP made of it.
     * `initializationFailTimeout = -1` so no database is needed; the pool is closed at once.
     */
    private fun silentlyRewritten(
        setting: Pair<String, Long>,
        read: (HikariConfig) -> Long,
    ): Long {
        val config =
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:pool_floor_probe"
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = "sa"
                initializationFailTimeout = -1
                poolName = "floor-probe-${setting.first}"
                // The application default this product actually applies (§5). It matters: with
                // HikariCP's own unset `minimumIdle`, validateNumerics() normalizes it UP to
                // maximumPoolSize first and then skips the idleTimeout rewrite entirely — so a
                // probe without this line would "prove" a rewrite that never happens in
                // production, and the idleTimeout floor would look unjustified.
                if (setting.first != "minimumIdle") minimumIdle = PoolSettings.DEFAULT_MINIMUM_IDLE.toInt()
            }
        when (setting.first) {
            "maxLifetime" -> config.maxLifetime = setting.second
            "keepaliveTime" -> config.keepaliveTime = setting.second
            "leakDetectionThreshold" -> config.leakDetectionThreshold = setting.second
            "idleTimeout" -> config.idleTimeout = setting.second
            "minimumIdle" -> config.minimumIdle = setting.second.toInt()
            else -> error("unhandled probe key ${setting.first}")
        }
        // Construction runs validate() → validateNumerics(), which is where the rewrite happens.
        HikariDataSource(config).use { it.shouldNotBeNull() }
        return read(config)
    }
}
