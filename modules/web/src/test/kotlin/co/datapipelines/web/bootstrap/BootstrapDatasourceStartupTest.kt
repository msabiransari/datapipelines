package co.datapipelines.web.bootstrap

import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.datasources.BootstrapDatasourceRegistrar
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The startup step's two decisions (sample-data design §6): whether to run at all, and in what
 * order.
 *
 * The ordering assertion is the load-bearing one — `datasources.created_by` is
 * `NOT NULL REFERENCES users(id)`, so resolving the actor after the first entry would be a
 * foreign-key error at boot rather than a config error.
 */
class BootstrapDatasourceStartupTest {
    private val userService = mockk<UserService>()
    private val registrar = mockk<BootstrapDatasourceRegistrar>(relaxed = true)

    private val actor =
        User(
            id = UUID.randomUUID(),
            email = "admin@example.com",
            displayName = "admin",
            provider = "bootstrap",
            providerSubject = "admin@example.com",
            isActive = true,
            isAdmin = true,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    @Test
    fun `an unset datasources-file means the whole step does not run`() {
        BootstrapDatasourceStartup(BootstrapProperties(), userService, registrar).afterSingletonsInstantiated()
        BootstrapDatasourceStartup(BootstrapProperties(datasourcesFile = "  "), userService, registrar).afterSingletonsInstantiated()

        // Not even the actor: an unset key must not create a users row as a side effect.
        verify(exactly = 0) { userService.provisionBootstrapActor() }
        verify(exactly = 0) { registrar.register(any(), any()) }
    }

    @Test
    fun `the actor is resolved before any entry is applied, and is the created_by passed down`() {
        every { userService.provisionBootstrapActor() } returns actor

        BootstrapDatasourceStartup(
            BootstrapProperties(datasourcesFile = "/etc/datapipelines/bootstrap-datasources.yml"),
            userService,
            registrar,
        ).afterSingletonsInstantiated()

        verify(ordering = io.mockk.Ordering.ORDERED) {
            userService.provisionBootstrapActor()
            registrar.register(Path.of("/etc/datapipelines/bootstrap-datasources.yml"), actor.id)
        }
    }

    @Test
    fun `a registration failure propagates - the context must not come up half-registered`() {
        every { userService.provisionBootstrapActor() } returns actor
        every { registrar.register(any(), any()) } throws IllegalStateException("entry 2 failed validation")

        val error =
            runCatching {
                BootstrapDatasourceStartup(BootstrapProperties(datasourcesFile = "/etc/dp/x.yml"), userService, registrar)
                    .afterSingletonsInstantiated()
            }.exceptionOrNull()

        (error is IllegalStateException) shouldBe true
        error!!.message shouldBe "entry 2 failed validation"
    }

    @Test
    fun `a comma-separated list registers every file in order, one actor resolution for all`() {
        every { userService.provisionBootstrapActor() } returns actor

        BootstrapDatasourceStartup(
            BootstrapProperties(datasourcesFile = "/etc/dp/nyc.yml, /etc/dp/trade.yml"),
            userService,
            registrar,
        ).afterSingletonsInstantiated()

        verify(exactly = 1) { userService.provisionBootstrapActor() }
        verify(ordering = io.mockk.Ordering.ORDERED) {
            registrar.register(Path.of("/etc/dp/nyc.yml"), actor.id)
            registrar.register(Path.of("/etc/dp/trade.yml"), actor.id)
        }
    }

    @Test
    fun `empty entries in the list are dropped, never registered`() {
        every { userService.provisionBootstrapActor() } returns actor

        BootstrapDatasourceStartup(
            BootstrapProperties(datasourcesFile = ", /etc/dp/nyc.yml , ,"),
            userService,
            registrar,
        ).afterSingletonsInstantiated()

        verify(exactly = 1) { registrar.register(Path.of("/etc/dp/nyc.yml"), actor.id) }
    }
}
