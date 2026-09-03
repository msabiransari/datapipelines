package co.datapipelines.config

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.OidcDiscoveryStub
import co.datapipelines.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * **The round's most important gate** (056 §A): a `@Transactional` method that does not roll back
 * is indistinguishable from no transaction at all, until the data is already wrong.
 *
 * Before 056 there was no transaction manager in main and `ArchitectureGuardTest` banned
 * `@Transactional` outright. Turning that on introduces two CGLIB failure modes, and — measured
 * rather than assumed — they behave in opposite ways:
 *
 * - A **final CLASS** is LOUD: Spring cannot subclass it and the context refuses to start
 *   (`AopConfigException: Cannot subclass final class`). Proven by falsification: making
 *   [TwoWriteFixture] final fails all five tests here at context load. A loud failure cannot ship.
 * - A **final METHOD on an opened class** is SILENT: the proxy is built with Objenesis, its own
 *   fields are null, and an un-interceptable method runs on the proxy and throws
 *   `NullPointerException` at runtime. Compile, lint and every module test stay green.
 *
 * Both were met in this round. `PipelineService` first compiled `public final class` — because
 * `kotlin("plugin.spring")` opens a class **annotated** with `@Transactional` and not one whose
 * **methods** carry it — caught with `javap`. Marking only the transactional methods `open` then
 * left every other method final, and that is what sixteen failing E2E tests found.
 *
 * ## Why this suite lives in `app`
 *
 * `app` declares the one transaction manager, and module-structure §4.2 lets it depend on `web`
 * only — so, exactly as `ApplicationSmokeTest` does for the composition beans, everything below
 * is asserted **by bean name and by SQL**, never by importing a domain type. The equivalent proof
 * one layer down, over the service's own semantics, is `PipelineServiceIntegrationTest`.
 *
 * ## What each test is for
 *
 * 1. [two writes then a throw persist nothing] — the falsifiable claim.
 * 2. [two writes that return commit both] — the control. Without it, "neither row is there" is
 *    also satisfied by a fixture that never wrote, which is the vacuous pass a rollback test is
 *    most likely to hide behind.
 * 3. [a nonexistent manager name is refused, not silently ignored] — a typo in the qualifier must
 *    fail loudly rather than quietly run outside a transaction.
 * 4. [every bean with a transactional method is actually proxied] — the general form, over the
 *    whole context, so slices B and C cannot ship inert advice either.
 * 5. [no proxied service declares a final public method] — the SILENT trap, which a proxy check
 *    alone misses: the bean IS a proxy, and a final method on it still throws NPE at runtime. It
 *    cost sixteen E2E failures in this round before it was understood.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(TransactionRollbackIntegrationTest.Fixtures::class)
class TransactionRollbackIntegrationTest {
    @Autowired
    private lateinit var twoWrites: TwoWriteFixture

    @Autowired
    private lateinit var bogusManager: BogusManagerFixture

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `two writes then a throw persist nothing`() {
        val first = "rollback_first_${suffix()}"
        val second = "rollback_second_${suffix()}"

        shouldThrow<IllegalStateException> { twoWrites.writeTwiceThenThrow(first, second) }

        withClue("the FIRST write must be gone: it committed on its own if the advice was not applied") {
            countByName(first) shouldBe 0
        }
        withClue("the second write must be gone too") { countByName(second) shouldBe 0 }
    }

    @Test
    fun `two writes that return commit both`() {
        val first = "commit_first_${suffix()}"
        val second = "commit_second_${suffix()}"

        twoWrites.writeTwice(first, second)

        countByName(first) shouldBe 1
        countByName(second) shouldBe 1
    }

    @Test
    fun `a nonexistent manager name is refused, not silently ignored`() {
        val name = "bogus_manager_${suffix()}"

        shouldThrow<NoSuchBeanDefinitionException> { bogusManager.write(name) }

        withClue("nothing may be written by a method whose transaction manager does not resolve") {
            countByName(name) shouldBe 0
        }
    }

    @Test
    fun `every bean with a transactional method is actually proxied`() {
        val transactional = transactionalBeans()
        val unproxied =
            transactional.filterNot { AopUtils.isAopProxy(it.bean) }.map { candidate ->
                val finalClass = if (Modifier.isFinal(candidate.target.modifiers)) ", FINAL class" else ""
                "${candidate.name} (${candidate.target.name}$finalClass)"
            }

        withClue("the scan must SEE the service layer — an empty scan proves nothing") {
            transactional.shouldNotBeEmpty()
        }
        withClue(
            "a bean has a @Transactional method but is not proxied, so the advice is silently absent. " +
                "Mark the class AND the annotated methods `open` — see PipelineService's KDoc.",
        ) {
            unproxied.shouldBeEmpty()
        }
    }

    @Test
    fun `no proxied service declares a final public method`() {
        // The SECOND CGLIB trap, and the one a proxy check alone does not catch. Spring builds the
        // proxy with Objenesis, bypassing the constructor, so the PROXY's own fields are null and
        // correctness depends on every call being intercepted and delegated to the real target. A
        // `final` method cannot be intercepted: it runs ON THE PROXY and dies with
        // `NullPointerException: ... is null`. Not hypothetical — during 056 `PipelineService` was
        // proxied correctly and every non-transactional read still threw, because only the
        // @Transactional methods had been marked `open`. Sixteen E2E tests found it; this finds it
        // at the module gate instead.
        val offenders =
            transactionalBeans().flatMap { candidate ->
                candidate.target.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) && !it.isSynthetic }
                    .map { "${candidate.target.simpleName}.${it.name}" }
            }

        withClue(
            "a public method of a proxied service is final, so it executes on the constructor-less " +
                "proxy instead of the target and will throw NullPointerException at runtime. Mark it `open`.",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    /** Every bean whose target class declares at least one `@Transactional` method. */
    private fun transactionalBeans(): List<TransactionalBean> =
        context.beanDefinitionNames.mapNotNull { name ->
            val bean = runCatching { context.getBean(name) }.getOrNull() ?: return@mapNotNull null
            val target = AopProxyUtils.ultimateTargetClass(bean)
            val declaresTransactional =
                runCatching {
                    target.declaredMethods.any { it.isAnnotationPresent(Transactional::class.java) }
                }.getOrDefault(false)
            if (declaresTransactional) TransactionalBean(name, bean, target) else null
        }

    private data class TransactionalBean(
        val name: String,
        val bean: Any,
        val target: Class<*>,
    )

    @Test
    fun `the one transaction manager is the named metadata manager`() {
        context.getBean(METADATA_MANAGER) shouldNotBe null
        val managers = context.getBeanNamesForType(PlatformTransactionManager::class.java).toList()
        withClue("exactly one manager: a second would make every bare @Transactional ambiguous") {
            managers shouldBe listOf(METADATA_MANAGER)
        }
    }

    // -------------------------------------------------------------------------------- fixtures

    /**
     * Two metadata writes in one transaction, with a throw between the second and the return.
     *
     * Raw SQL rather than `PipelineRepository`, because `app` may not depend on
     * `pipeline-contract` (§4.2) — the same reason `ApplicationSmokeTest` asserts its beans by
     * name. Two INSERTs to two different rows is the shape §A asks for: each is atomic ON ITS OWN,
     * which is precisely why the test means something — without the transaction the first commits
     * and stays.
     *
     * `open` on the class and on both methods is not style: see the class KDoc.
     */
    open class TwoWriteFixture(
        private val jdbc: JdbcTemplate,
    ) {
        @Transactional(METADATA_MANAGER)
        open fun writeTwiceThenThrow(
            first: String,
            second: String,
        ) {
            insertPipeline(jdbc, first)
            insertPipeline(jdbc, second)
            error("boom, after the second write")
        }

        @Transactional(METADATA_MANAGER)
        open fun writeTwice(
            first: String,
            second: String,
        ) {
            insertPipeline(jdbc, first)
            insertPipeline(jdbc, second)
        }
    }

    /** A qualifier that names no bean — the typo case. */
    open class BogusManagerFixture(
        private val jdbc: JdbcTemplate,
    ) {
        @Transactional("noSuchTransactionManager")
        open fun write(name: String) {
            insertPipeline(jdbc, name)
        }
    }

    @TestConfiguration
    class Fixtures {
        @Bean
        fun twoWriteFixture(jdbc: JdbcTemplate): TwoWriteFixture = TwoWriteFixture(jdbc)

        @Bean
        fun bogusManagerFixture(jdbc: JdbcTemplate): BogusManagerFixture = BogusManagerFixture(jdbc)
    }

    private fun countByName(name: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM pipelines WHERE name = ?", Int::class.java, name) ?: 0

    /** A per-test row-name suffix, so the suite needs no truncation and can run in any order. */
    private fun suffix(): String = UUID.randomUUID().toString().substringBefore('-')

    companion object {
        const val METADATA_MANAGER = "metadataTransactionManager"

        private const val SECRET_BYTES = 32

        /** The V4-seeded `default` workspace; the seeded system actor owns the fixture rows. */
        private const val WORKSPACE = "defa0000-0000-0000-0000-000000000001"

        /**
         * One `pipelines` row, written the way the shipped schema requires and nothing more. The
         * owner is resolved from `users` at insert time, so the fixture seeds nothing of its own —
         * the app's own boot provisions the system actor (auth.md §4.5).
         */
        private fun insertPipeline(
            jdbc: JdbcTemplate,
            name: String,
        ) {
            jdbc.update(
                """
                INSERT INTO pipelines (name, display_name, description, owner_id, workspace_id)
                SELECT ?, ?, '056 transaction fixture', u.id, CAST(? AS uuid)
                  FROM users u ORDER BY u.created_at LIMIT 1
                """.trimIndent(),
                name,
                name,
                WORKSPACE,
            )
        }

        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedRedis.port }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
