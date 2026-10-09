package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * §7's Redis client bounds (§3.1, #488), in their own suite like the org and mail rules.
 *
 * `DATAPIPELINES_REDIS_COMMAND_TIMEOUT` / `_CONNECT_TIMEOUT` (#482) reach Lettuce through the
 * §3.14 bridge with no range check of their own. Zero is the value that matters: Lettuce reads a
 * command timeout of `0` as NO bound, so `0s` would silently restore the outage stall #482
 * removed (`RedisZeroCommandTimeoutIntegrationTest` in `web` shows it against a stopped Redis).
 * A blank value is the same trap by another road — Boot binds it to null and leaves Lettuce's
 * own 60 s — and 60 s is also the ceiling: the default an operator may deliberately restore.
 */
class ConfigValidatorRedisTimeoutTest {
    private fun validSnapshot() = ConfigSnapshots.valid()

    @Test
    fun `zero, negative, malformed, blank and over-the-ceiling values are each refused naming the key and value`() {
        listOf("0s", "-1s", "abc", "61s", "", "PT0S", "0", " 2s ").forEach { raw ->
            val command = ConfigValidator.validate(validSnapshot().copy(redisCommandTimeout = raw)).violations
            command.shouldHaveSize(1)
            command.single().shouldContain("datapipelines.redis.command-timeout is '$raw'")
            command.single().shouldContain("§3.1")

            val connect = ConfigValidator.validate(validSnapshot().copy(redisConnectTimeout = raw)).violations
            connect.shouldHaveSize(1)
            connect.single().shouldContain("datapipelines.redis.connect-timeout is '$raw'")
        }
    }

    @Test
    fun `the shipped default, the ceiling and the smallest positive bound pass`() {
        listOf("2s", "60s", "1ms", "2000", "PT2S").forEach { raw ->
            ConfigValidator
                .validate(validSnapshot().copy(redisCommandTimeout = raw, redisConnectTimeout = raw))
                .violations
                .shouldBeEmpty()
        }
    }

    @Test
    fun `an unset key is not a violation - the yml default applies`() {
        validSnapshot().redisCommandTimeout shouldBe null
        validSnapshot().redisConnectTimeout shouldBe null
        ConfigValidator.validate(validSnapshot()).violations.shouldBeEmpty()
    }

    @Test
    fun `both keys wrong are two violations in one pass`() {
        val violations =
            ConfigValidator.validate(validSnapshot().copy(redisCommandTimeout = "0s", redisConnectTimeout = "61s")).violations

        violations.shouldHaveSize(2)
        violations[0].shouldContain("datapipelines.redis.command-timeout is '0s'")
        violations[1].shouldContain("datapipelines.redis.connect-timeout is '61s'")
    }

    @Test
    fun `the snapshot reads the operator keys through their env-var placeholders, as application yml spells them`() {
        // application.yml's own shape: the key holds the placeholder, the operator sets the variable.
        val environment =
            MockEnvironment()
                .withProperty("datapipelines.redis.command-timeout", "\${DATAPIPELINES_REDIS_COMMAND_TIMEOUT:2s}")
                .withProperty("datapipelines.redis.connect-timeout", "\${DATAPIPELINES_REDIS_CONNECT_TIMEOUT:2s}")
                .withProperty("DATAPIPELINES_REDIS_COMMAND_TIMEOUT", "0s")
                .withProperty("DATAPIPELINES_REDIS_CONNECT_TIMEOUT", "")

        val snapshot = ConfigValidator.snapshotFrom(environment)

        snapshot.redisCommandTimeout shouldBe "0s"
        snapshot.redisConnectTimeout shouldBe ""
        ConfigValidator.validate(validSnapshot().copy(redisCommandTimeout = snapshot.redisCommandTimeout)).violations.shouldHaveSize(1)

        val defaults =
            ConfigValidator.snapshotFrom(
                MockEnvironment()
                    .withProperty("datapipelines.redis.command-timeout", "\${DATAPIPELINES_REDIS_COMMAND_TIMEOUT:2s}")
                    .withProperty("datapipelines.redis.connect-timeout", "\${DATAPIPELINES_REDIS_CONNECT_TIMEOUT:2s}"),
            )
        defaults.redisCommandTimeout shouldBe "2s"
        defaults.redisConnectTimeout shouldBe "2s"
    }
}
