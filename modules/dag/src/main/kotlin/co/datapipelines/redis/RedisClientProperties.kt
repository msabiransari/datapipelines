package co.datapipelines.redis

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The sole Redis client binding (configuration.md §2/§3.1).
 * App's ConfigValidator judges the required host, timeout bounds and authentication posture.
 * Passwords are deliberately excluded from the generated data-class representation.
 */
@ConfigurationProperties("datapipelines.redis")
data class RedisClientProperties(
    val host: String,
    val port: Int = 6379,
    val password: String = "",
    val commandTimeout: Duration = Duration.ofSeconds(2),
    val connectTimeout: Duration = Duration.ofSeconds(2),
) {
    init {
        require(port in 1..MAX_PORT) { "datapipelines.redis.port must be in 1..65535" }
    }

    override fun toString(): String =
        "RedisClientProperties(host=$host, port=$port, commandTimeout=$commandTimeout, connectTimeout=$connectTimeout)"

    private companion object {
        const val MAX_PORT = 65535
    }
}
