package co.datapipelines.auth

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.MountableFile
import java.time.Duration

/**
 * ONE Keycloak container for this module's whole test JVM (round 141) — the singleton pattern
 * [SharedPostgres] follows. First touch starts the container with EVERY realm fixture under
 * `src/test/resources/keycloak/` imported at once; each OIDC suite then points its provider at
 * its own realm's issuer instead of booting a private Keycloak. A Keycloak boot is the most
 * expensive cold start in this repo (Quarkus augmentation plus a realm import, CPU-bound,
 * ~22 s on an idle box and measured at ~11 minutes on a loaded one — the reason for the
 * 20-minute ceiling below), and two suites booting two of them concurrently contend for the
 * same cores. A module gate now pays that once.
 *
 * ## The isolation model that makes sharing safe
 *
 * Keycloak's `--import-realm` imports every file in `/opt/keycloak/data/import/` at boot, and a
 * realm is a complete namespace: its own users, clients, sessions and issuer URI. The suites'
 * realms are distinct (`datapipelines`, `invites`), so alice-of-one-realm is a different
 * Keycloak user from alice-of-the-other and a session in one cannot satisfy a login in the
 * other. Sharing rests on ONE invariant the suites must keep: **the realm fixtures are read-only
 * — no suite creates, edits or deletes users, clients or sessions through the admin API.** A
 * suite that needs to mutate Keycloak declares its own container, as the per-deployment and
 * per-engine suites in the E2E module do.
 *
 * ## First touch
 *
 * There is nothing to reset: the realms are imported from the fixture files on every fresh
 * boot, and the invariant above means a reused container (below) still holds exactly the
 * imported state. The wait strategy demands BOTH realms' discovery documents, so a realm that
 * failed to import is a startup failure, never a suite that runs against a missing issuer.
 *
 * ## Reuse (DEVELOPMENT.md §9.2)
 *
 * `withReuse(true)` is declared like the other shared containers: a no-op unless the local
 * opt-in is set, and a Keycloak boot saved when it is. The realm import re-runs nothing on
 * reuse — the realms persist. A container wedged by a killed run is removed with `docker rm`.
 */
internal object SharedKeycloak {
    private const val IMAGE = "quay.io/keycloak/keycloak:26.0"
    private const val PORT = 8080

    /** Every realm fixture the module ships, imported together. Add a realm here AND a file. */
    private val realms = listOf("datapipelines", "invites")

    /**
     * Generous, and raised from 10 minutes in 083 §C because 10 was not generous enough: this
     * container's boot is set by the BOX, not by us — Quarkus augmentation plus a realm import,
     * CPU-heavy, while other lanes' Testcontainers compete for the same cores. Measured on a
     * loaded box: ~11 minutes, which timed out and turned a green suite into
     * `initializationError`. Twenty minutes is roughly twice the worst boot observed. The cost
     * of the ceiling being high is that a genuinely broken container stalls a gate for 20
     * minutes; the cost of it being low is a red gate every time the box is busy.
     */
    private val startupCeiling: Duration = Duration.ofMinutes(20)

    /** The shared container, started with every realm imported on first touch. */
    val keycloak: GenericContainer<*> by lazy {
        GenericContainer(IMAGE)
            .withExposedPorts(PORT)
            .also { container ->
                realms.forEach { realm ->
                    container.withCopyFileToContainer(
                        MountableFile.forClasspathResource("keycloak/realm-$realm.json"),
                        "/opt/keycloak/data/import/realm-$realm.json",
                    )
                }
            }.withCommand("start-dev", "--import-realm")
            .withReuse(true)
            .waitingFor(
                // WITH_OUTER_TIMEOUT: the one ceiling covers the whole boot, however many realms
                // it imports — each inner strategy's own default (60 s) would otherwise apply.
                WaitAllStrategy(WaitAllStrategy.Mode.WITH_OUTER_TIMEOUT)
                    .also { all ->
                        realms.forEach { realm ->
                            all.withStrategy(
                                Wait
                                    .forHttp("/realms/$realm/.well-known/openid-configuration")
                                    .forPort(PORT)
                                    .forStatusCode(200),
                            )
                        }
                    }.withStartupTimeout(startupCeiling),
            ).also { it.start() }
    }

    /**
     * The issuer URI a suite's provider config names for [realm] — `http://host:port/realms/…`,
     * the exact string Keycloak's discovery document reports as `issuer`.
     */
    fun issuerUri(realm: String): String {
        require(realm in realms) { "realm '$realm' is not one of the imported fixtures $realms" }
        return "http://${keycloak.host}:${keycloak.getMappedPort(PORT)}/realms/$realm"
    }
}
