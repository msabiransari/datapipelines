package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** [DuckDbStagingProperties]: what a wiring defect cannot get past, and the derived reservation. */
class DuckDbStagingPropertiesTest {
    private val dir: Path = Path.of("/var/tmp/dp-staging")

    private fun props(
        scratchDir: Path = dir,
        memoryLimitBytes: Long = 1024 * MIB,
        expansionFactor: Int = 3,
        threads: Int = 4,
        maxConnections: Int = 4,
    ) = DuckDbStagingProperties(
        scratchDir = scratchDir,
        memoryLimitBytes = memoryLimitBytes,
        expansionFactor = expansionFactor,
        scratchCapacityBytes = 20_000_000_000,
        threads = threads,
        maxConnections = maxConnections,
    )

    @Test
    fun `the default reservation is memory limit times expansion factor and saturates`() {
        props().defaultReservationBytes shouldBe 3L * 1024 * MIB
        props(memoryLimitBytes = Long.MAX_VALUE / 2, expansionFactor = 3).defaultReservationBytes shouldBe Long.MAX_VALUE
    }

    @Test
    fun `a relative path, a semicolon or an out-of-range bound is refused`() {
        shouldThrow<IllegalArgumentException> { props(scratchDir = Path.of("relative/dir")) }
        shouldThrow<IllegalArgumentException> { props(scratchDir = Path.of("/tmp/a;access_mode=READ_WRITE")) }
        shouldThrow<IllegalArgumentException> { props(memoryLimitBytes = MIB - 1) }
        shouldThrow<IllegalArgumentException> { props(expansionFactor = 0) }
        shouldThrow<IllegalArgumentException> { props(threads = 0) }
        shouldThrow<IllegalArgumentException> { props(maxConnections = 0) }
    }

    @Test
    fun `threads are bounded at four and never above the host's cores`() {
        DuckDbStagingProperties.boundedThreads(availableProcessors = 32) shouldBe 4
        DuckDbStagingProperties.boundedThreads(availableProcessors = 2) shouldBe 2
        DuckDbStagingProperties.boundedThreads(availableProcessors = 0) shouldBe 1
    }
}
