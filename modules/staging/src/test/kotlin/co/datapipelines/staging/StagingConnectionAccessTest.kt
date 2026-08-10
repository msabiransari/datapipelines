package co.datapipelines.staging

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The §9.2 containment rule: the staging JDBC connection is reachable **only** under the
 * instance's own lock, and that lock is reachable from nowhere at all.
 *
 * The earlier contract exposed `Staging.connection` as a property and asked callers to "hold
 * the mutex" — a mutex that was `private` to `H2Staging` and therefore impossible for any
 * caller to take. Every direct user of the connection was consequently unserialized, on a
 * connection the spec itself says is not safe for concurrent callers. [Staging.withConnection]
 * replaces it: the implementation takes the lock, runs the block, releases it.
 *
 * Two halves, because either alone is a false green:
 *  - **Compile surface** — reflection proves no public member of the interface or the
 *    implementation hands out a [Connection] or a [Mutex]. A behavior test cannot see a
 *    property that a future edit re-adds.
 *  - **Behavior** — the lock is really held for the whole block, across suspension points, so
 *    a concurrent staging operation waits. A reflection test cannot see an accessor that takes
 *    no lock.
 */
class StagingConnectionAccessTest {
    private val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `no public member of the Staging contract yields a connection or a mutex`() {
        val leaks =
            Staging::class.java.methods
                .filter { it.returnType in GUARDED_TYPES || it.parameterTypes.any { p -> p in GUARDED_TYPES } }
                .map { it.name }

        leaks.shouldBeEmpty()
    }

    @Test
    fun `the H2 implementation keeps its connection and mutex private`() {
        val leakingMethods =
            H2Staging::class.java.methods
                .filter { it.declaringClass == H2Staging::class.java && Modifier.isPublic(it.modifiers) }
                .filter { it.returnType in GUARDED_TYPES }
                .map { it.name }

        // A `private val` compiles to a private field with no getter; making either member
        // public again would add a getter here or flip its field's modifier below. (The
        // constructor still takes the connection — that is the factory's hand-off, not an
        // accessor a caller can reach through an instance.)
        leakingMethods.shouldBeEmpty()

        val guardedFields = H2Staging::class.java.declaredFields.filter { it.type in GUARDED_TYPES }
        // Guard the guard: if the fields were renamed away, "all of none are private" is vacuous.
        guardedFields.map { it.type }.toSet() shouldBe GUARDED_TYPES
        guardedFields
            .filterNot { Modifier.isPrivate(it.modifiers) }
            .map { it.name }
            .shouldBeEmpty()
    }

    @Test
    fun `withConnection holds the lock for the whole block, so a concurrent operation waits`() {
        val events = CopyOnWriteArrayList<String>()

        runBlocking {
            val holding = CompletableDeferred<Unit>()
            val holder =
                async(Dispatchers.IO) {
                    staging.withConnection { connection ->
                        events += "block-start"
                        holding.complete(Unit)
                        // A suspension point inside the block: the lock must survive it.
                        delay(BLOCK_HOLD_MILLIS)
                        connection.createStatement().use { it.execute("CREATE TABLE \"stg_held\" (id INTEGER)") }
                        events += "block-end"
                    }
                }
            holding.await()
            val contender =
                async(Dispatchers.IO) {
                    staging.execute("CREATE TABLE \"stg_contender\" (id INTEGER)")
                    events += "contender"
                }
            awaitAll(holder, contender)
        }

        // Without the lock, "contender" lands inside the delay window, between the two markers.
        events.toList() shouldBe listOf("block-start", "block-end", "contender")
    }

    @Test
    fun `two concurrent withConnection blocks do not interleave`() {
        val events = CopyOnWriteArrayList<String>()

        runBlocking {
            val blocks =
                (1..2).map { i ->
                    async(Dispatchers.IO) {
                        staging.withConnection {
                            events += "start-$i"
                            delay(BLOCK_HOLD_MILLIS)
                            events += "end-$i"
                        }
                    }
                }
            blocks.awaitAll()
        }

        // Whichever ran first, its end precedes the other's start — no interleaving.
        val order = events.toList()
        order.size shouldBe 4
        order[1] shouldBe order[0].replace("start", "end")
        order[3] shouldBe order[2].replace("start", "end")
    }

    private companion object {
        val GUARDED_TYPES = setOf<Class<*>>(Connection::class.java, Mutex::class.java)

        /** Long enough that an unlocked contender would reliably slip in; short enough to be cheap. */
        const val BLOCK_HOLD_MILLIS = 300L
    }
}
