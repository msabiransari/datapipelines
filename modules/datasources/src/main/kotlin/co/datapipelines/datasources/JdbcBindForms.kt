package co.datapipelines.datasources

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The JDBC form of a canonical value, the one rule both bind paths share (#339): the
 * [ReadOnlyStatementLease]'s selector binds and the SQL probe's parameters
 * (`SqlProbeParameter.toJdbcValue`). Spring-jdbc's `TYPE_UNKNOWN` path hands anything but a String,
 * a `java.util.Date` or a Calendar to `setObject`, and pgjdbc cannot infer a SQL type for an
 * `Instant` ("Can't infer the SQL type to use for an instance of java.time.Instant") — the house
 * repositories convert first, and so do both paths: an `Instant`, what the coercion produces for
 * TIMESTAMP, binds as an `OffsetDateTime` at UTC, which every pinned driver maps to `timestamptz`.
 * Every other canonical value binds through the drivers' own table: String, BigDecimal, BigInteger,
 * Boolean, Int, LocalDate and LocalTime on both paths, LocalDateTime on the lease's, ByteArray on
 * the probe's. The conversion changes only the value's Java form, never what is bound.
 *
 * Found by the 194c security pass (observation 1) for the lease and carried to the probe by #265 F2;
 * `SelectorRunnerIntegrationTest` and `SqlProbePostgresIntegrationTest` each bind one through
 * their own path.
 */
internal fun jdbcForm(value: Any?): Any? = if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value
