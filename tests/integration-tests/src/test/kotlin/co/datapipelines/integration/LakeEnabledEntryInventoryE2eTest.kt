package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * **The lake-enabled profile is held to the default entry inventory plus every conditional row its
 * condition admits** (#838, auth.md §8.6.1's conditional entry inventory).
 *
 * The managed lake's scheduling configuration registers `LakeSettlementScheduler#sweep` only when
 * `datapipelines.lake.enabled=true` — a registration the DEFAULT profile must never hold
 * (`EntryInventoryE2eTest` fails on it there) and this profile must. The suite boots the real
 * application with the lake enabled (the `ManagedLakeDeliveryProperties` fixture, its own lake and
 * bucket, so it is its own Spring context) and compares the running registrations with the reviewed
 * tables BOTH ways, joined into one string per direction: the in-context families must equal the
 * default inventory's rows plus every conditional row whose condition this profile admits.
 *
 * ## B5 — a conditional job is not an entry point a request can reach
 * Every conditional row must say "reachable from a request: no", and no scheduled task's or
 * db-scheduler task's bean may be a handler bean — the same check `EntryInventoryE2eTest` holds the
 * default profile to, via the shared `EntryRuntime` readers.
 *
 * ## Non-vacuity
 * The counts per family are printed (`event=entry.inventory.lake …`), the scheduled family is
 * floored at the default inventory's scheduled rows + 1, and the suite names the conditional row it
 * matched.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LakeEnabledEntryInventoryE2eTest {
    @Autowired
    private lateinit var context: ApplicationContext

    private val doc = RoleMatrixDocE2e.read()
    private val inventory = EntryContract.inventory(doc)
    private val conditional = EntryContract.conditionalInventory(doc)

    @Test
    fun `the lake-enabled profile registers the default rows plus its admitted conditional rows - and nothing else`() {
        val runtime =
            EntryRuntime.servlets(context) + EntryRuntime.filters(context) + EntryRuntime.securityChain(context) +
                EntryRuntime.handlerMappings(context) + EntryRuntime.scheduledTasks(context) + EntryRuntime.schedulerTasks(context)
        val default = inventory.filter { it.family in IN_CONTEXT_FAMILIES }
        val admitted = conditional.filter { it.condition == LAKE_ENABLED_CONDITION }
        val documentedKeys = default.map { it.key }.toSet() + admitted.filter { it.family in IN_CONTEXT_FAMILIES }.map { it.key }

        val counts = runtime.groupingBy { it.family }.eachCount()
        println("event=entry.inventory.lake ${IN_CONTEXT_FAMILIES.joinToString(" ") { "$it=${counts[it] ?: 0}" }}")
        println(runtime.joinToString("\n") { "event=entry.inventory.lake.row ${it.key}" })

        // Joined into ONE string (the role walk's rule): a collection assertion prints its first
        // element and elides the rest, and the point of an inventory's failure is the LIST.
        withClue("lake-profile registrations with no default or admitted-conditional row — an entry point nobody reviewed") {
            (runtime.map { it.key } - documentedKeys).joinToString("\n") shouldBe ""
        }
        withClue("default or admitted-conditional rows with no lake-profile registration — the doc promises an entry that is not there") {
            (documentedKeys - runtime.map { it.key }.toSet()).joinToString("\n") shouldBe ""
        }
        default.filter { it.purpose.isBlank() || it.bounded.isBlank() }.map { it.key }.shouldBeEmpty()
        admitted.filter { it.purpose.isBlank() || it.bounded.isBlank() || it.condition.isBlank() }.map { it.key }.shouldBeEmpty()

        // Non-vacuity: the conditional row's job really registered, past the default profile's rows.
        val runtimeKeys = runtime.map { it.key }.toSet()
        val matched = admitted.filter { it.key in runtimeKeys }
        withClue("no admitted conditional row is registered — the lake-enabled boot did not admit the condition it documents") {
            matched.map { it.key } shouldBe admitted.map { it.key }
        }
        matched.forEach { row -> println("event=entry.inventory.lake.conditional matched=${row.key}") }
        counts.getOrDefault("scheduled", 0) shouldBeGreaterThanOrEqual inventory.count { it.family == "scheduled" } + 1
    }

    @Test
    fun `no conditional job is reachable from a request - B5`() {
        withClue("conditional rows that say 'reachable from a request: yes' — a job is not an entry point (B5)") {
            conditional.filter { it.reachable }.map { it.key }.shouldBeEmpty()
        }
        val handlerTypes = EntryRuntime.handlerBeanTypes(context)
        val reachable = EntryRuntime.scheduledTargets(context).filter { target -> handlerTypes.any { it.isAssignableFrom(target) } }
        withClue("lake-profile @Scheduled targets that are also request handlers") { reachable.map { it.name }.shouldBeEmpty() }
        // #9: a db-scheduler task bean is a job too — never a request handler.
        withClue("lake-profile db-scheduler task beans that are also request handlers") {
            EntryRuntime.schedulerTaskBeanTypes(context)
                .filter { task -> handlerTypes.any { it.isAssignableFrom(task) } }
                .map { it.name }.shouldBeEmpty()
        }
        conditional.size shouldBeGreaterThanOrEqual CONDITIONAL_FLOOR
    }

    private companion object {
        /** The property literal exactly as `LakeSettlementSchedulingConfiguration` tests its condition. */
        const val LAKE_ENABLED_CONDITION = "datapipelines.lake.enabled=true"

        /** One conditional job is documented today; a scan that matches none is a broken boot, not a leaner table. */
        const val CONDITIONAL_FLOOR = 1

        val IN_CONTEXT_FAMILIES = listOf("servlet", "filter", "security-chain", "handler-mapping", "scheduled", "scheduler-task")

        private val SECRET = E2eSession.newSecret()
        private val oidc = OidcDiscoveryStub()
        private const val LAKE = "lake838entry"
        private const val BUCKET = "dp838-entry-e2e"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            ManagedLakeDeliveryProperties.register(registry, SECRET, oidc.issuer, LAKE, BUCKET)
        }
    }
}
