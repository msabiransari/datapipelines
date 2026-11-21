package co.datapipelines.application.compatibility

import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.executor.LocalWritePlanner
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.scheduler.ScheduleRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.PrintStream
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * The operator entry point of the offline compatibility report (dag-executor.md §16.7). It runs
 * OUTSIDE the application — no Spring context, so no scheduler, sweeper or executor starts — from
 * the packaged jar:
 *
 * ```
 * SPRING_DATASOURCE_URL=… SPRING_DATASOURCE_USERNAME=… SPRING_DATASOURCE_PASSWORD=… \
 *   java -Dloader.main=co.datapipelines.application.compatibility.LocalWriteCompatibilityCli -jar datapipelines-app.jar
 * ```
 *
 * (`scripts/local-write-compatibility-report.sh` wraps that line.) It reads the metadata store
 * through ONE read-only session — `readOnly` on the connection and
 * `default_transaction_read_only` set at connect, so a write is refused by the server — and the
 * same environment variables the application reads; it prints no connection detail, statement or
 * credential. Exit 0 when the report was produced, 1 when it could not be (the reason on stderr,
 * without the URL).
 */
object LocalWriteCompatibilityCli {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(System.getenv(), System.out, System.err))
    }

    /** Produces the report from [env] onto [out]; diagnostics go to [err]. */
    fun run(
        env: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val url = env[ENV_URL]
        if (url.isNullOrBlank()) {
            err.println("local-write-compatibility-report: $ENV_URL is not set")
            return EXIT_FAILED
        }
        return try {
            readOnlyDataSource(url, env[ENV_USERNAME], env[ENV_PASSWORD]).use { dataSource ->
                report(dataSource, env[ENV_DEPTH]?.toIntOrNull() ?: DEFAULT_DEPTH).forEach(out::println)
            }
            EXIT_OK
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The class only: a driver message can carry the URL or the user name.
            err.println("local-write-compatibility-report: the report could not be produced (${e.javaClass.simpleName})")
            EXIT_FAILED
        }
    }

    /** The report's lines over [dataSource] — the seam the integration test drives. */
    fun report(
        dataSource: DataSource,
        maxCompositionDepth: Int = DEFAULT_DEPTH,
    ): List<String> {
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        val pipelines = PipelineRepository(jdbc)
        val candidates =
            StoredCompatibilityCandidates(
                workspaces = WorkspaceRepository(jdbc),
                pipelines = pipelines,
                schedules = ScheduleRepository(jdbc, PipelineJson.objectMapper()),
            )
        val registrations = RegistrationView.over(DatasourceRepository(jdbc))
        val planner = LocalWritePlanner(candidates.resolver(), registrations, maxCompositionDepth)
        val entries = LocalWriteCompatibilityReport(planner).inspect(candidates.all())
        check(registrations.refusedCalls.get() == 0) { "the report asked for a customer connection" }
        return LocalWriteCompatibilityReport.render(entries)
    }

    /** One pooled connection, read-only on the client AND the server session. */
    fun readOnlyDataSource(
        url: String,
        username: String?,
        password: String?,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                this.username = username
                this.password = password
                isReadOnly = true
                maximumPoolSize = 1
                poolName = "local-write-compatibility-report"
                connectionInitSql = "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"
            },
        )

    private const val ENV_URL = "SPRING_DATASOURCE_URL"
    private const val ENV_USERNAME = "SPRING_DATASOURCE_USERNAME"
    private const val ENV_PASSWORD = "SPRING_DATASOURCE_PASSWORD"

    /** The application's own `datapipelines.pipelines.max-composition-depth` variable and default. */
    private const val ENV_DEPTH = "DATAPIPELINES_PIPELINES_MAX_COMPOSITION_DEPTH"
    private const val DEFAULT_DEPTH = 5
    private const val EXIT_OK = 0
    private const val EXIT_FAILED = 1
}
