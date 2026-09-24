package co.datapipelines.auth

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #215 A.6/A3, the SQL half: the `kind = 'human'` predicate is in the repository's own queries,
 * so a non-human row is refused even by a caller that forgot the service-level check. The worst
 * case is staged on purpose — a `service` row that somehow HOLDS a password hash (no product path
 * writes one) — and the local-login read, the users listing, the admin grant and the identity
 * reset all still refuse it. A `human` twin with the same shape proves each query works at all.
 *
 * Rows are namespaced by a per-run suffix, so the suite neither truncates nor collides.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NonHumanRowRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository

    private val run = UUID.randomUUID().toString().substringBefore('-')
    private val identityEmail = "dpk_nonhuman$run@keys.invalid"
    private val personEmail = "person-$run@company.com"
    private lateinit var identity: User
    private lateinit var person: User

    @BeforeAll
    fun seed() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        users = UserRepository(jdbc)
        identity = users.insert(identityEmail, "nonhuman-$run", null, UserService.KEY_PROVIDER, "dpk_nonhuman$run", false, UserKind.SERVICE)
        person = users.insert(personEmail, "person-$run", null, "google", "sub-$run", false)
        // The staged worst case: both rows hold a password hash.
        users.setPassword(identity.id, "hash-$run", mustChange = false)
        users.setPassword(person.id, "hash-$run", mustChange = false)
    }

    @Test
    fun `the local-login read never surfaces a non-human row's credential`() {
        users.findLocalCredential(identityEmail).shouldBeNull()
        users.findLocalCredential(personEmail).shouldNotBeNull()
    }

    @Test
    fun `the users listing is people only`() {
        val listed = users.search(run, 0, LIMIT).map { it.email }
        listed shouldNotContain identityEmail
        listed.contains(personEmail) shouldBe true
    }

    @Test
    fun `the admin grant refuses a non-human row in SQL`() {
        users.grantAdmin(identity.id) shouldBe false
        checkNotNull(users.findById(identity.id)).isAdmin shouldBe false
    }

    @Test
    fun `the identity reset refuses a non-human row in SQL`() {
        users.resetIdentityToBootstrap(identity.id) shouldBe false
        checkNotNull(users.findById(identity.id)).provider shouldBe UserService.KEY_PROVIDER
    }

    private companion object {
        const val LIMIT = 50
    }
}
