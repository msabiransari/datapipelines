package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * **Every entry point the application registers has a reviewed row, and every row a registration**
 * (security-assurance record §4, ratified B3/B5; lane 217a, A.1).
 *
 * The running application is read, never a list someone kept: the servlet container's own servlet
 * and filter registrations (with each filter's order from Spring Boot's sorted initializers), every
 * filter of every Spring Security chain in the order the chain runs them, every handler mapping, and
 * every `@Scheduled` task — and, from the PACKAGED jar booted as its own process, the sockets that
 * process listens on and the actuator endpoints its management port exposes. Each is compared BOTH
 * ways with auth.md §8.6's entry inventory: a registration with no row fails by name (somebody added
 * a filter, a servlet, a job or a listener nobody reviewed), and a row with no registration fails by
 * name (the doc promises a guard that is not there).
 *
 * Handler methods are not in that table: their contract is auth.md §7.6 (B3 — one reviewed table,
 * never two). The second test places every handler the application registers — from every module,
 * not only `web`'s, which is what `MatrixRowReachabilityTest` sees at build time — on the §7.6 row
 * of the permission it declares, or in §8.6.2's public contract; and every route §7.6 names must be
 * registered.
 *
 * ## B5 — a job is not an entry point a request can reach
 * Every scheduled row must say "reachable from a request: no", and no scheduled task's bean may be a
 * handler bean. `ArchitectureGuardTest` carries the source half: no transport imports a job.
 *
 * ## Non-vacuity
 * The counts per family are printed (`event=entry.inventory …`) and floored; a family whose
 * discovery found nothing fails rather than agreeing with an empty table.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class EntryInventoryE2eTest : EntryAssuranceE2eBase() {
    @Autowired
    private lateinit var context: ApplicationContext

    private val doc = RoleMatrixDocE2e.read()
    private val inventory = EntryContract.inventory(doc)

    @Test
    fun `every registration the running application holds has an inventory row - and every row a registration`() {
        val runtime = servlets() + filters() + securityChain() + handlerMappings() + scheduledTasks() + schedulerTasks()
        val documented = inventory.filter { it.family in IN_CONTEXT_FAMILIES }

        val counts = runtime.groupingBy { it.family }.eachCount()
        println("event=entry.inventory ${IN_CONTEXT_FAMILIES.joinToString(" ") { "$it=${counts[it] ?: 0}" }}")
        println(runtime.joinToString("\n") { "event=entry.inventory.row ${it.key}" })

        // Joined into ONE string (the role walk's rule): a collection assertion prints its first
        // element and elides the rest, and the point of an inventory's failure is the LIST.
        withClue("registrations with no auth.md §8.6.1 row — an entry point nobody reviewed") {
            (runtime.map { it.key } - documented.map { it.key }.toSet()).joinToString("\n") shouldBe ""
        }
        withClue("auth.md §8.6.1 rows with no registration — the doc promises an entry that is not there") {
            (documented.map { it.key } - runtime.map { it.key }.toSet()).joinToString("\n") shouldBe ""
        }
        documented.filter { it.purpose.isBlank() || it.bounded.isBlank() }.map { it.key }.shouldBeEmpty()

        counts.getOrDefault("servlet", 0) shouldBeGreaterThanOrEqual SERVLET_FLOOR
        counts.getOrDefault("filter", 0) shouldBeGreaterThanOrEqual FILTER_FLOOR
        counts.getOrDefault("security-chain", 0) shouldBeGreaterThanOrEqual SECURITY_CHAIN_FLOOR
        counts.getOrDefault("handler-mapping", 0) shouldBeGreaterThanOrEqual HANDLER_MAPPING_FLOOR
        counts.getOrDefault("scheduled", 0) shouldBeGreaterThanOrEqual SCHEDULED_FLOOR
        counts.getOrDefault("scheduler-task", 0) shouldBeGreaterThanOrEqual SCHEDULER_TASK_FLOOR
    }

    @Test
    fun `every handler is placed - on its permission's §7-6 row or in the §8-6-2 public contract - and every placed route exists`() {
        val handlers = EntryDiscovery.handlerMethods(context)
        val rows = RoleMatrixDocE2e.permissionRows(doc)
        val publicHandlers = EntryContract.publicContract(doc).flatMap { it.handlers }.toSet()

        val unplaced =
            handlers.mapNotNull { h ->
                val route = "${h.method} ${EntryDiscovery.canonical(h.pattern)}"
                val placed =
                    if (h.public) {
                        "$route ${h.handler}" in publicHandlers
                    } else {
                        h.permission != null && route in rows[h.permission]?.routes.orEmpty()
                    }
                if (placed) null else "$route ${h.handler} (${h.permission ?: if (h.public) "public" else "no permission"})"
            }
        val registered =
            handlers
                .filterNot { it.public }
                .flatMap { h -> h.verbs.map { "${h.permission} $it ${EntryDiscovery.canonical(h.pattern)}" } }
                .toSet()
        val phantom =
            rows.flatMap { (permission, cells) -> cells.routes.map { "$permission $it" } }.filterNot { it in registered }

        println("event=entry.inventory.handlers total=${handlers.size} public=${handlers.count { it.public }}")
        withClue("handlers placed on no §7.6 row and in no §8.6.2 row") { unplaced.joinToString("\n") shouldBe "" }
        withClue("§7.6 routes (permission VERB pattern) no registered handler declares") { phantom.joinToString("\n") shouldBe "" }
        handlers.size shouldBeGreaterThanOrEqual HANDLER_FLOOR
    }

    @Test
    fun `no scheduled job is reachable from a request - B5`() {
        val jobs = inventory.filter { it.family == "scheduled" || it.family == "scheduler-task" }
        withClue("scheduled rows that say 'reachable from a request: yes' — a job is not an entry point (B5)") {
            jobs.filter { it.reachable }.map { it.key }.shouldBeEmpty()
        }
        val handlerTypes = handlerBeanTypes()
        val reachable = scheduledTargets().filter { target -> handlerTypes.any { it.isAssignableFrom(target) } }
        withClue("@Scheduled targets that are also request handlers") { reachable.map { it.name }.shouldBeEmpty() }
        // #9: a db-scheduler task bean is a job too — never a request handler.
        val taskBeans = beansOf(SCHEDULER_TASK).values.map { it.javaClass }
        withClue("db-scheduler task beans that are also request handlers") {
            taskBeans.filter { task -> handlerTypes.any { it.isAssignableFrom(task) } }.map { it.name }.shouldBeEmpty()
        }
        jobs.size shouldBeGreaterThanOrEqual SCHEDULED_FLOOR + SCHEDULER_TASK_FLOOR
    }

    /**
     * The packaged jar as its own process — the only honest subject for "what does this application
     * listen on": the test JVM holds every cached context's server at once. Its sockets are read from
     * `/proc/<pid>/fd` (the socket inodes it owns) joined with `/proc/<pid>/net/tcp{,6}` (LISTEN
     * rows), so another process's listener on the same box can never be counted as the app's.
     */
    @Test
    fun `the packaged app listens only on its declared ports and addresses - and exposes only its declared actuator endpoints`() {
        val session = seedObserver()
        val runtime = PackagedApp.boot().use { app -> app.listeningRows() + app.actuatorRows(session) }
        val documented = inventory.filter { it.family in PROCESS_FAMILIES }

        println(runtime.joinToString("\n") { "event=entry.inventory.row ${it.key}" })
        withClue("listening sockets or actuator endpoints with no auth.md §8.6.1 row") {
            (runtime.map { it.key } - documented.map { it.key }.toSet()).joinToString("\n") shouldBe ""
        }
        withClue("auth.md §8.6.1 port/actuator rows the packaged application does not have") {
            (documented.map { it.key } - runtime.map { it.key }.toSet()).joinToString("\n") shouldBe ""
        }
        runtime.count { it.family == "port" } shouldBe PORT_COUNT
    }

    /** A person the packaged process accepts a session from — only to read the management port's own index. */
    private fun seedObserver(): String {
        val pg = SharedE2e.postgres
        java.sql.DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$OBSERVER_ID', '$OBSERVER_EMAIL', 'Inventory', 'test', 'inventory-observer', TRUE, FALSE) " +
                        "ON CONFLICT (id) DO NOTHING",
                )
            }
        }
        return E2eSession.jwt(jwtSecret, OBSERVER_ID, OBSERVER_EMAIL, workspace = null)
    }

    // ------------------------------------------------------------------ the families, read off the context

    /** One runtime entry, rendered exactly as its §8.6.1 row must read. */
    data class Entry(
        val family: String,
        val name: String,
        val type: String,
        val pattern: String,
    ) {
        val key: String get() = "$family | $name | $type | $pattern"
    }

    private val servletContext: Any by lazy { context.javaClass.getMethod("getServletContext").invoke(context) }

    private fun servlets(): List<Entry> {
        val registrations = SERVLET_CONTEXT.getMethod("getServletRegistrations").invoke(servletContext) as Map<*, *>
        return registrations.map { (name, registration) ->
            @Suppress("UNCHECKED_CAST")
            val mappings = SERVLET_REGISTRATION.getMethod("getMappings").invoke(registration) as Collection<String>
            Entry("servlet", name as String, className(registration!!), mappings.sorted().joinToString(", ").ifEmpty { "no mapping" })
        }
    }

    /** Container filter registrations, each with the order Spring Boot's sorted initializers gave it (`container` when none did). */
    private fun filters(): List<Entry> {
        val registrations = SERVLET_CONTEXT.getMethod("getFilterRegistrations").invoke(servletContext) as Map<*, *>
        val orders = initializerOrders()
        return registrations.map { (name, registration) ->
            @Suppress("UNCHECKED_CAST")
            val urls = FILTER_REGISTRATION.getMethod("getUrlPatternMappings").invoke(registration) as Collection<String>

            @Suppress("UNCHECKED_CAST")
            val servlets = FILTER_REGISTRATION.getMethod("getServletNameMappings").invoke(registration) as Collection<String>
            val type = className(registration!!)
            val mapped = (urls.sorted() + servlets.sorted().map { "servlet:$it" }).joinToString(", ")
            Entry("filter", name as String, type, "$mapped · order ${orders[type] ?: "container"}")
        }
    }

    /** `filter class -> order`, from `ServletContextInitializerBeans` — the sorted list Spring Boot registered from. */
    private fun initializerOrders(): Map<String, String> {
        val beanFactory = (context as ConfigurableApplicationContext).beanFactory
        val initializerBeans =
            Class
                .forName("org.springframework.boot.web.servlet.ServletContextInitializerBeans")
                .getConstructor(ListableBeanFactory::class.java, emptyArray<Class<*>>().javaClass)
                .newInstance(beanFactory, emptyArray<Class<*>>()) as Iterable<*>
        return initializerBeans
            .filter { FILTER_REGISTRATION_BEAN.isInstance(it) }
            .associate { bean ->
                val filter = FILTER_REGISTRATION_BEAN.getMethod("getFilter").invoke(bean)
                simpleName(filter.javaClass) to REGISTRATION_BEAN.getMethod("getOrder").invoke(bean).toString()
            }
    }

    /** Every filter of every Spring Security chain, in the order `FilterChainProxy` runs them. */
    private fun securityChain(): List<Entry> {
        val proxy = context.getBean("springSecurityFilterChain")
        val chains = FILTER_CHAIN_PROXY.getMethod("getFilterChains").invoke(proxy) as List<*>
        return chains.withIndex().flatMap { (chainIndex, chain) ->
            val matcher = DEFAULT_SECURITY_FILTER_CHAIN.getMethod("getRequestMatcher").invoke(chain).toString()
            val filters = SECURITY_FILTER_CHAIN.getMethod("getFilters").invoke(chain) as List<*>
            filters.withIndex().map { (index, filter) ->
                Entry("security-chain", "chain ${chainIndex + 1} #%02d".format(index + 1), simpleName(filter!!.javaClass), matcher)
            }
        }
    }

    /**
     * Every `HandlerMapping` bean. The annotated-handler mapping is a pointer (its routes are §7.6's
     * and §8.6.2's); a URL mapping lists its URL keys; a functional-route mapping says whether any
     * route function exists. A mapping of any other kind fails: it is a new entry mechanism and needs
     * its own adapter here before it ships (record §4).
     */
    private fun handlerMappings(): List<Entry> =
        beansOf(HANDLER_MAPPING).map { (name, mapping) ->
            val pattern =
                when {
                    REQUEST_MAPPING_HANDLER_MAPPING.isInstance(mapping) -> {
                        "handler methods — auth.md §7.6 and §8.6.2"
                    }

                    REQUEST_MAPPING_INFO_HANDLER_MAPPING.isInstance(mapping) -> {
                        // A second annotated-style mapping (actuator's additional health paths): its
                        // mappings are listed here, because §7.6 knows only the application's handlers.
                        val infos = REQUEST_MAPPING_INFO_HANDLER_MAPPING.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
                        infos.keys
                            .map { it.toString() }
                            .sorted()
                            .joinToString(", ")
                            .ifEmpty { "no mapping" }
                    }

                    URL_HANDLER_MAPPING.isInstance(mapping) -> {
                        val keys = (URL_HANDLER_MAPPING.getMethod("getHandlerMap").invoke(mapping) as Map<*, *>).keys.map { it.toString() }
                        val root =
                            URL_HANDLER_MAPPING
                                .getMethod("getRootHandler")
                                .invoke(mapping)
                                ?.let { listOf("(root)") }
                                .orEmpty()
                        (keys.sorted() + root).joinToString(", ").ifEmpty { "no URL" }
                    }

                    ROUTER_FUNCTION_MAPPING.isInstance(mapping) -> {
                        val routes = ROUTER_FUNCTION_MAPPING.getMethod("getRouterFunction").invoke(mapping)
                        if (routes == null) "no route function" else "route functions: $routes"
                    }

                    else -> {
                        error("handler mapping '$name' (${mapping.javaClass.name}) is a kind this inventory cannot read — add its adapter")
                    }
                }
            Entry("handler-mapping", name, simpleName(mapping.javaClass), pattern)
        }

    private fun scheduledTasks(): List<Entry> =
        scheduled().map { (runnable, trigger) ->
            val qualified = runnable.toString()
            val name = qualified.substringBeforeLast('.').substringAfterLast('.') + "#" + qualified.substringAfterLast('.')
            Entry("scheduled", name, trigger.first, trigger.second)
        }

    /**
     * #9 — every db-scheduler `Task` bean the context holds (the scheduler's jobs), as
     * `scheduler-task | <task name> | recurring|one-time | <schedule>`. Read by reflection: the
     * library's types are not on this module's compile classpath (they stay behind `modules/scheduler`).
     */
    private fun schedulerTasks(): List<Entry> =
        beansOf(SCHEDULER_TASK).values.map { task ->
            val name = SCHEDULER_TASK.getMethod("getName").invoke(task) as String
            if (RECURRING_TASK.isInstance(task)) {
                // The library exposes neither a recurring task's schedule nor a FixedDelay's duration;
                // both are private fields. A schedule of another shape renders as itself (and so
                // fails the comparison until its row is written).
                val schedule = RECURRING_TASK.getDeclaredField("schedule").apply { isAccessible = true }.get(task)
                val rendered =
                    if (FIXED_DELAY.isInstance(schedule)) {
                        "every " + FIXED_DELAY.getDeclaredField("duration").apply { isAccessible = true }.get(schedule)
                    } else {
                        schedule.toString()
                    }
                Entry("scheduler-task", name, "recurring", rendered)
            } else {
                Entry("scheduler-task", name, "one-time", "one instance per recorded run")
            }
        }

    /** `(the task's runnable, (trigger kind, trigger rendering))` for every `@Scheduled` task the holders registered. */
    private fun scheduled(): List<Pair<Any, Pair<String, String>>> =
        beansOf(SCHEDULED_TASK_HOLDER)
            .values
            .flatMap { holder ->
                (SCHEDULED_TASK_HOLDER.getMethod("getScheduledTasks").invoke(holder) as Set<*>).map { scheduledTask ->
                    val task = scheduledTask!!.javaClass.getMethod("getTask").invoke(scheduledTask)
                    val runnable = TASK.getMethod("getRunnable").invoke(task)
                    runnable to trigger(task)
                }
            }.distinctBy { it.first.toString() }

    private fun trigger(task: Any): Pair<String, String> =
        when {
            INTERVAL_TASK.isInstance(task) -> {
                val kind = if (task.javaClass.simpleName == "FixedRateTask") "fixedRate" else "fixedDelay"
                val interval = INTERVAL_TASK.getMethod("getIntervalDuration").invoke(task) as Duration
                val initial = INTERVAL_TASK.getMethod("getInitialDelayDuration").invoke(task) as Duration
                kind to if (initial.isZero) "every $interval" else "every $interval, first after $initial"
            }

            CRON_TASK.isInstance(task) -> {
                "cron" to CRON_TASK.getMethod("getExpression").invoke(task).toString()
            }

            else -> {
                "trigger" to task.toString()
            }
        }

    /** The classes whose methods the scheduled tasks run. */
    private fun scheduledTargets(): List<Class<*>> =
        scheduled().map { (runnable, _) ->
            val qualified = runnable.toString()
            Class.forName(qualified.substringBeforeLast('.'))
        }

    private fun handlerBeanTypes(): Set<Class<*>> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.values.map { it!!.javaClass.getMethod("getBeanType").invoke(it) as Class<*> }.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun beansOf(type: Class<*>): Map<String, Any> = context.getBeansOfType(type as Class<Any>)

    private fun className(registration: Any): String =
        (REGISTRATION.getMethod("getClassName").invoke(registration) as String).substringAfterLast('.')

    private fun simpleName(type: Class<*>): String = type.name.substringAfterLast('.')

    // ------------------------------------------------------------------ the packaged application, as a process

    /**
     * The application jar booted as `java -jar` against the shared containers — the same launch
     * `JarSmokeE2eTest` makes (its boot machinery is private to it; consolidating the two is a
     * follow-up). Two ports, both chosen here, so "declared" is a fact of this run, not a guess.
     */
    private class PackagedApp private constructor(
        private val process: Process,
        private val log: File,
        private val appPort: Int,
        private val managementPort: Int,
    ) : AutoCloseable {
        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        /** One row per LISTEN socket this process owns: the declared name of its port and its bind address. */
        fun listeningRows(): List<Entry> {
            val deadline = System.currentTimeMillis() + LISTEN_SETTLE_MS
            var sockets = listening()
            // The management server starts after the application's; wait until both are bound.
            while (sockets.map { it.second }.toSet() != setOf(appPort, managementPort) && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
                sockets = listening()
            }
            return sockets.map { (address, port) ->
                val name =
                    when (port) {
                        appPort -> "server.port"
                        managementPort -> "management.server.port"
                        else -> "undeclared port $port"
                    }
                Entry("port", name, "listening socket", "bound to $address")
            }
        }

        /**
         * The actuator endpoints the management port's discovery page links, read with a real session
         * (the security chain guards the management context too), each with the status it gives an
         * ANONYMOUS caller — so opening the port to anonymous callers is a diff in the inventory, not a
         * silent change. The `-path` template links are the same endpoint and are not rows.
         */
        fun actuatorRows(session: String): List<Entry> {
            val response = send("http://127.0.0.1:$managementPort/actuator", session)
            val body = response.body()
            check(
                response.statusCode() == HTTP_OK,
            ) { "the management port's discovery page answered ${response.statusCode()}: ${body.take(LOG_TAIL)}" }
            val links = Regex("\"([a-z-]+)\"\\s*:\\s*\\{\\s*\"href\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
            return links
                .map { it.groupValues[1] to it.groupValues[2] }
                .filterNot { (name, _) -> name == "self" || name.endsWith("-path") }
                .map { (name, href) -> name to URI.create(href).path }
                .map { (name, path) ->
                    val anonymous = send("http://127.0.0.1:$managementPort$path").statusCode()
                    Entry("actuator", name, "management endpoint", "$path · anonymous $anonymous")
                }.toList()
        }

        /** `(bind address, port)` of every TCP socket in LISTEN state whose inode this process holds open. */
        private fun listening(): List<Pair<String, Int>> {
            val pid = process.pid()
            val inodes =
                File("/proc/$pid/fd")
                    .listFiles()
                    .orEmpty()
                    .mapNotNull { fd ->
                        runCatching { Files.readSymbolicLink(fd.toPath()).toString() }
                            .getOrNull()
                            ?.let { Regex("socket:\\[(\\d+)]").matchEntire(it)?.groupValues?.get(1) }
                    }.toSet()
            return listOf("tcp", "tcp6")
                .flatMap { table ->
                    Files.readAllLines(Path.of("/proc/$pid/net/$table")).drop(1).mapNotNull { line ->
                        val cells = line.trim().split(Regex("\\s+"))
                        val local = cells[1]
                        if (cells[3] != TCP_LISTEN || cells[9] !in inodes) {
                            null
                        } else {
                            address(local.substringBefore(':')) to local.substringAfter(':').toInt(HEX)
                        }
                    }
                }.distinct()
        }

        /**
         * `*` for the any-address (v4 or v6), dotted form for v4 and for a v4-mapped v6 address (a
         * dual-stack JVM binds `127.0.0.1` as `::ffff:127.0.0.1`), `::1` for v6 loopback. /proc writes
         * each 32-bit word little-endian.
         */
        private fun address(hex: String): String =
            when {
                hex.all { it == '0' } -> "*"
                hex.length == IPV4_HEX -> ipv4(hex)
                hex.startsWith(IPV4_MAPPED_PREFIX) -> ipv4(hex.removePrefix(IPV4_MAPPED_PREFIX))
                hex == IPV6_LOOPBACK -> "::1"
                else -> hex
            }

        private fun ipv4(hex: String): String = hex.chunked(2).reversed().joinToString(".") { it.toInt(HEX).toString() }

        fun get(url: String): String = send(url).body()

        private fun send(
            url: String,
            session: String? = null,
        ): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(HTTP_TIMEOUT_S)).GET()
            session?.let { request.header("Cookie", E2eSession.cookieHeader(it)) }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        override fun close() {
            process.destroy()
            if (!process.waitFor(STOP_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        }

        companion object {
            private const val TCP_LISTEN = "0A"
            private const val HTTP_OK = 200
            private const val HEX = 16
            private const val IPV4_HEX = 8
            private const val IPV6_LOOPBACK = "00000000000000000000000001000000"
            private const val IPV4_MAPPED_PREFIX = "0000000000000000FFFF0000"
            private const val BOOT_TIMEOUT_MS = 180_000L
            private const val LISTEN_SETTLE_MS = 30_000L
            private const val POLL_MS = 250L
            private const val HTTP_TIMEOUT_S = 5L
            private const val STOP_WAIT_S = 30L

            fun boot(): PackagedApp {
                val appPort = freePort()
                val managementPort = freePort()
                val log = Files.createTempFile("entry-inventory-app", ".log").toFile()
                val builder =
                    ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath, "-jar", jar().absolutePath)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                builder.environment().putAll(
                    mapOf(
                        "SERVER_PORT" to appPort.toString(),
                        "MANAGEMENT_SERVER_PORT" to managementPort.toString(),
                        "SPRING_DATASOURCE_URL" to SharedE2e.postgres.jdbcUrl,
                        "SPRING_DATASOURCE_USERNAME" to SharedE2e.postgres.username,
                        "SPRING_DATASOURCE_PASSWORD" to SharedE2e.postgres.password,
                        "DATAPIPELINES_REDIS_HOST" to SharedE2e.redisHost,
                        "DATAPIPELINES_REDIS_PORT" to SharedE2e.redisPort.toString(),
                        "DATAPIPELINES_JWT_SECRET" to EntryAssuranceE2eBase.jwtSecret,
                        "DATAPIPELINES_DB_ENCRYPTION_KEY" to EntryAssuranceE2eBase.encryptionKey,
                        "DATAPIPELINES_AUTH_BASE_URL" to "http://127.0.0.1:$appPort",
                        "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_NAME" to "google",
                        "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_CLIENT_ID" to "inventory",
                        "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_CLIENT_SECRET" to "inventory",
                        "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_ISSUER_URI" to EntryAssuranceE2eBase.oidc.issuer,
                    ),
                )
                val app = PackagedApp(builder.start(), log, appPort, managementPort)
                app.awaitHealthy()
                return app
            }

            private fun freePort(): Int = ServerSocket(0).use { it.localPort }

            private fun jar(): File {
                var dir: File? = File("").absoluteFile
                while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
                val jar = File(requireNotNull(dir) { "repository root not found" }, "modules/app/build/libs/datapipelines-app.jar")
                check(jar.isFile) { "bootJar output not found at ${jar.absolutePath} — ./gradlew :modules:app:bootJar" }
                return jar
            }
        }

        private fun awaitHealthy() {
            val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                check(process.isAlive) { "the jar died during boot — log:\n${log.readText().takeLast(LOG_TAIL)}" }
                if (runCatching { get("http://127.0.0.1:$appPort/health") }.getOrNull()?.contains("UP") == true) return
                Thread.sleep(POLL_MS)
            }
            close()
            error("the jar did not become healthy in ${BOOT_TIMEOUT_MS / 1000}s — log:\n${log.readText().takeLast(LOG_TAIL)}")
        }
    }

    private companion object {
        /** The families read off this context, and the two read off the packaged process. */
        val IN_CONTEXT_FAMILIES = listOf("servlet", "filter", "security-chain", "handler-mapping", "scheduled", "scheduler-task")
        val PROCESS_FAMILIES = listOf("port", "actuator")

        const val LOG_TAIL = 4000
        const val OBSERVER_ID = "5a170000-0000-0000-0000-000000000217"
        const val OBSERVER_EMAIL = "inventory-observer@entry.test"

        /**
         * Floors, not targets — measured on this lane's base (2026-09-24, cc779dcc): see the
         * handback's inventory counts. A discovery well under these is a broken scan, not a leaner app.
         */
        const val SERVLET_FLOOR = 2
        const val FILTER_FLOOR = 8
        const val SECURITY_CHAIN_FLOOR = 15
        const val HANDLER_MAPPING_FLOOR = 3
        const val SCHEDULED_FLOOR = 3

        /** #9 — the dispatcher, the reconciler and the run task. */
        const val SCHEDULER_TASK_FLOOR = 3
        const val HANDLER_FLOOR = 180

        /** The application port and the management port — nothing else may listen. */
        const val PORT_COUNT = 2

        val SERVLET_CONTEXT: Class<*> = Class.forName("jakarta.servlet.ServletContext")
        val REGISTRATION: Class<*> = Class.forName("jakarta.servlet.Registration")
        val SERVLET_REGISTRATION: Class<*> = Class.forName("jakarta.servlet.ServletRegistration")
        val FILTER_REGISTRATION: Class<*> = Class.forName("jakarta.servlet.FilterRegistration")
        val REGISTRATION_BEAN: Class<*> = Class.forName("org.springframework.boot.web.servlet.RegistrationBean")
        val FILTER_REGISTRATION_BEAN: Class<*> = Class.forName("org.springframework.boot.web.servlet.AbstractFilterRegistrationBean")
        val SECURITY_FILTER_CHAIN: Class<*> = Class.forName("org.springframework.security.web.SecurityFilterChain")
        val FILTER_CHAIN_PROXY: Class<*> = Class.forName("org.springframework.security.web.FilterChainProxy")
        val DEFAULT_SECURITY_FILTER_CHAIN: Class<*> = Class.forName("org.springframework.security.web.DefaultSecurityFilterChain")
        val HANDLER_MAPPING: Class<*> = Class.forName("org.springframework.web.servlet.HandlerMapping")
        val REQUEST_MAPPING_HANDLER_MAPPING: Class<*> =
            Class.forName("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping")
        val REQUEST_MAPPING_INFO_HANDLER_MAPPING: Class<*> =
            Class.forName("org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping")
        val URL_HANDLER_MAPPING: Class<*> = Class.forName("org.springframework.web.servlet.handler.AbstractUrlHandlerMapping")
        val ROUTER_FUNCTION_MAPPING: Class<*> = Class.forName("org.springframework.web.servlet.function.support.RouterFunctionMapping")
        val SCHEDULED_TASK_HOLDER: Class<*> = Class.forName("org.springframework.scheduling.config.ScheduledTaskHolder")
        val TASK: Class<*> = Class.forName("org.springframework.scheduling.config.Task")
        val INTERVAL_TASK: Class<*> = Class.forName("org.springframework.scheduling.config.IntervalTask")
        val CRON_TASK: Class<*> = Class.forName("org.springframework.scheduling.config.CronTask")
        val SCHEDULER_TASK: Class<*> = Class.forName("com.github.kagkarlsson.scheduler.task.Task")
        val RECURRING_TASK: Class<*> = Class.forName("com.github.kagkarlsson.scheduler.task.helper.RecurringTask")
        val FIXED_DELAY: Class<*> = Class.forName("com.github.kagkarlsson.scheduler.task.schedule.FixedDelay")
    }
}
