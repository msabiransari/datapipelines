package co.datapipelines.web.config

import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeHttpBounds
import co.datapipelines.datasources.MANAGED_DIALECT_KEY
import co.datapipelines.datasources.ManagedLakeSync
import co.datapipelines.lake.LakeBootCheck
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.definitions.LakeApplyRepository
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.util.unit.DataSize
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * #818 (dp-lake P5, design record §6): the row [ManagedLakeDatasourceRegistrar] asks the registry for, and the
 * boot/tick flow around it. The registry's own sync semantics (CREATED / RESYNCED / UNCHANGED / NAME_TAKEN against a
 * real Postgres) are `ManagedLakeRegistryIntegrationTest`'s; the booted path is `ManagedLakeReadsE2eTest`'s.
 */
class ManagedLakeDatasourceRegistrarTest {
    private val actor = UUID.randomUUID()

    @Test
    fun `the reader pair wins over the writer pair and travels as the credential, never in the dialect`() {
        val desired = desired(storage(readerAccess = "READER-ID", readerSecret = "reader-secret-value"))

        assertAll(
            { desired.name shouldBe "lake" },
            { desired.dialect shouldBe Dialect.LAKE },
            { desired.jdbcUrl shouldBe "jdbc:duckdb::memory:" },
            { desired.isReadonly shouldBe true },
            { desired.ownerWorkspaceId.shouldBeNull() },
            { desired.credentialKind shouldBe CredentialKind.PASSWORD },
            { desired.username shouldBe "READER-ID" },
            { desired.secret shouldBe "reader-secret-value" },
            { desired.properties.dialect.toString() shouldNotContain "secret-value" },
            { desired.properties.dialect.toString() shouldNotContain "READER-ID" },
            { desired.properties.dialect.toString() shouldNotContain "WRITER-ID" },
        )
    }

    @Test
    fun `a half-set reader pair falls back to the writer pair`() {
        val desired = desired(storage(readerAccess = "READER-ID", readerSecret = ""))

        assertAll(
            { desired.credentialKind shouldBe CredentialKind.PASSWORD },
            { desired.username shouldBe "WRITER-ID" },
            { desired.secret shouldBe "writer-secret-value" },
        )
    }

    @Test
    fun `no pair at all means the instance credential chain`() {
        val desired = desired(storage(writerAccess = "", writerSecret = ""))

        assertAll(
            { desired.credentialKind shouldBe CredentialKind.NONE },
            { desired.username.shouldBeNull() },
            { desired.secret.shouldBeNull() },
        )
    }

    @Test
    fun `the dialect map carries the managed marker, the store's addressing and the reader memory limit`() {
        val desired = desired(storage(), reads = LakeProperties.Reads(memoryLimit = DataSize.ofGigabytes(2)))

        desired.properties.dialect shouldBe
            mapOf(
                "catalog.kind" to "s3",
                MANAGED_DIALECT_KEY to "true",
                "region" to "eu-west-1",
                "endpoint" to "http://minio:9000",
                "url_style" to "path",
                "memory_limit" to "2048MB",
            )
    }

    @Test
    fun `blank region and endpoint are left out, virtual-host style is spelled vhost, a non-MB size falls to KB`() {
        val dialect =
            desired(
                storage(region = "", endpoint = "", pathStyle = false),
                reads = LakeProperties.Reads(memoryLimit = DataSize.ofKilobytes(1536)),
            ).properties.dialect

        assertAll(
            { dialect.containsKey("region") shouldBe false },
            { dialect.containsKey("endpoint") shouldBe false },
            { dialect["url_style"] shouldBe "vhost" },
            { dialect["memory_limit"] shouldBe "1536KB" },
        )
    }

    @Test
    fun `845 - the dialect map carries the egress proxy trimmed when configured, and no key when it is empty`() {
        val withProxy = desired(storage(), reads = LakeProperties.Reads(egressProxy = " egress-proxy:3128 ")).properties.dialect
        val withoutProxy = desired(storage(), reads = LakeProperties.Reads(egressProxy = "")).properties.dialect

        assertAll(
            { withProxy[LakeHttpBounds.EGRESS_PROXY_KEY] shouldBe "egress-proxy:3128" },
            { withoutProxy.containsKey(LakeHttpBounds.EGRESS_PROXY_KEY) shouldBe false },
        )
    }

    @Test
    fun `845 - an unreachable egress proxy refuses the boot with the catalogued id, before anything is written`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val fixture =
            Fixture(enabled = true, sync = ManagedLakeSync.CREATED, reads = LakeProperties.Reads(egressProxy = "127.0.0.1:$closedPort"))

        val refused = shouldThrow<IllegalStateException> { fixture.registrar.register() }

        assertAll(
            { refused.message.orEmpty() shouldStartWith "${LakeBootCheck.Refusal.EGRESS_PROXY.id}: " },
            { verify(exactly = 0) { fixture.users.provisionSystemActor() } },
            { verify(exactly = 0) { fixture.registry.syncManagedLake(any(), any()) } },
            { verify(exactly = 0) { fixture.grants.grantToEveryWorkspace(any(), any()) } },
        )
    }

    @Test
    fun `register syncs the desired row, backfills and grants every workspace`() {
        val fixture = Fixture(enabled = true, sync = ManagedLakeSync.CREATED)

        fixture.registrar.register()

        assertAll(
            { fixture.synced.captured.name shouldBe "lake" },
            { verify(exactly = 1) { fixture.apply.backfillRegistryRows("lake", actor) } },
            { verify(exactly = 1) { fixture.grants.grantToEveryWorkspace("lake", actor) } },
        )
    }

    @Test
    fun `a name held by another datasource refuses the boot with the catalogued id, before backfill or grant`() {
        val fixture = Fixture(enabled = true, sync = ManagedLakeSync.NAME_TAKEN)

        val refused = shouldThrow<IllegalStateException> { fixture.registrar.register() }

        assertAll(
            { refused.message.orEmpty() shouldStartWith "${LakeBootCheck.Refusal.DATASOURCE_NAME_TAKEN.id}: " },
            { verify(exactly = 0) { fixture.apply.backfillRegistryRows(any(), any()) } },
            { verify(exactly = 0) { fixture.grants.grantToEveryWorkspace(any(), any()) } },
        )
    }

    @Test
    fun `with the lake disabled nothing is written, at boot or on the tick`() {
        val fixture = Fixture(enabled = false, sync = ManagedLakeSync.CREATED)

        fixture.registrar.register()
        fixture.registrar.regrant(fixture.tickGrants)

        assertAll(
            { verify(exactly = 0) { fixture.registry.syncManagedLake(any(), any()) } },
            { verify(exactly = 0) { fixture.grants.grantToEveryWorkspace(any(), any()) } },
            { verify(exactly = 0) { fixture.tickGrants.grantToEveryWorkspace(any(), any()) } },
        )
    }

    @Test
    fun `the tick regrants through the tick's repository as the boot actor, provisioning nothing - #818b A`() {
        val fixture = Fixture(enabled = true, sync = ManagedLakeSync.UNCHANGED)
        fixture.registrar.register()

        fixture.registrar.regrant(fixture.tickGrants)
        fixture.registrar.regrant(fixture.tickGrants)

        assertAll(
            { verify(exactly = 1) { fixture.registry.syncManagedLake(any(), any()) } },
            // The boot's own grant only: the tick never writes through the boot repository.
            { verify(exactly = 1) { fixture.grants.grantToEveryWorkspace("lake", actor) } },
            { verify(exactly = 2) { fixture.tickGrants.grantToEveryWorkspace("lake", actor) } },
            // Provisioned once, at boot: a tick issues the grant INSERT and nothing else.
            { verify(exactly = 1) { fixture.users.provisionSystemActor() } },
        )
    }

    @Test
    fun `a tick before the boot registration grants nothing`() {
        val fixture = Fixture(enabled = true, sync = ManagedLakeSync.UNCHANGED)

        fixture.registrar.regrant(fixture.tickGrants)

        assertAll(
            { verify(exactly = 0) { fixture.tickGrants.grantToEveryWorkspace(any(), any()) } },
            { verify(exactly = 0) { fixture.users.provisionSystemActor() } },
        )
    }

    @Test
    fun `823 - grantTo writes ONE grant for the named workspace as the boot actor, through the registrar's own repository`() {
        val fixture = Fixture(enabled = true, sync = ManagedLakeSync.UNCHANGED)
        val workspace = UUID.randomUUID()
        every { fixture.grants.grant("lake", workspace, actor) } returns true
        fixture.registrar.register()

        val created = fixture.registrar.grantTo(workspace)

        assertAll(
            { created shouldBe true },
            { verify(exactly = 1) { fixture.grants.grant("lake", workspace, actor) } },
            // Never the tick's bounded repository, never a grant to every workspace: one row, on the caller's transaction.
            { verify(exactly = 0) { fixture.tickGrants.grantToEveryWorkspace(any(), any()) } },
            { verify(exactly = 1) { fixture.users.provisionSystemActor() } },
        )
    }

    @Test
    fun `823 - grantTo reports an existing grant as false, and grants nothing with the lake disabled or before boot`() {
        val workspace = UUID.randomUUID()

        val existing = Fixture(enabled = true, sync = ManagedLakeSync.UNCHANGED)
        every { existing.grants.grant("lake", workspace, actor) } returns false
        existing.registrar.register()
        val disabled = Fixture(enabled = false, sync = ManagedLakeSync.UNCHANGED)
        disabled.registrar.register()
        val beforeBoot = Fixture(enabled = true, sync = ManagedLakeSync.UNCHANGED)

        assertAll(
            { existing.registrar.grantTo(workspace) shouldBe false },
            { disabled.registrar.grantTo(workspace) shouldBe false },
            { beforeBoot.registrar.grantTo(workspace) shouldBe false },
            { verify(exactly = 0) { disabled.grants.grant(any(), any(), any()) } },
            { verify(exactly = 0) { beforeBoot.grants.grant(any(), any(), any()) } },
        )
    }

    // ------------------ fixtures

    private inner class Fixture(
        enabled: Boolean,
        sync: ManagedLakeSync,
        reads: LakeProperties.Reads = LakeProperties.Reads(),
    ) {
        val synced = slot<Datasource>()
        val registry = mockk<DatasourceRegistry> { every { syncManagedLake(capture(synced), actor) } returns sync }
        val grants = mockk<DatasourceGrantRepository> { every { grantToEveryWorkspace(any(), any()) } returns 2 }
        val tickGrants = mockk<DatasourceGrantRepository> { every { grantToEveryWorkspace(any(), any()) } returns 0 }
        val apply = mockk<LakeApplyRepository> { every { backfillRegistryRows(any(), any()) } returns 0 }
        val users =
            mockk<UserService> {
                every { provisionSystemActor() } returns mockk<User> { every { id } returns actor }
            }
        private val properties = LakeProperties(enabled = enabled, storage = storage(), reads = reads)
        val registrar =
            ManagedLakeDatasourceRegistrar(
                lakeProperties = providerOf(properties),
                registry = registry,
                grants = grants,
                applyRepository = providerOf(apply),
                userService = users,
                bootCheck = LakeCatalogStartupCheck(StaticListableBeanFactory().getBeanProvider(LakeBootCheck::class.java)),
            )
    }

    private inline fun <reified T : Any> providerOf(bean: T) =
        StaticListableBeanFactory(mapOf("bean" to bean)).getBeanProvider(T::class.java)

    private fun desired(
        storage: LakeProperties.Storage,
        reads: LakeProperties.Reads = LakeProperties.Reads(),
    ): Datasource = ManagedLakeDatasourceRegistrar.desiredDatasource(LakeProperties(enabled = true, storage = storage, reads = reads))

    private fun storage(
        writerAccess: String = "WRITER-ID",
        writerSecret: String = "writer-secret-value",
        readerAccess: String = "",
        readerSecret: String = "",
        region: String = "eu-west-1",
        endpoint: String = "http://minio:9000",
        pathStyle: Boolean = true,
    ) = LakeProperties.Storage(
        bucket = "dp-lake",
        region = region,
        endpoint = endpoint,
        pathStyle = pathStyle,
        accessKey = writerAccess,
        secretKey = writerSecret,
        readerAccessKey = readerAccess,
        readerSecretKey = readerSecret,
    )
}
