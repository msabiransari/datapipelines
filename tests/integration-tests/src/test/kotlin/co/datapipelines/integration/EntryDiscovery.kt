package co.datapipelines.integration

import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.util.AntPathMatcher
import java.time.Duration

/**
 * **How the running application's entry points are read** — the handler discovery the role walk
 * built (`RoleWalkE2eTest`, #215), shared since lane 217a with the three assurance suites that need
 * the same answer: the entry inventory (`EntryInventoryE2eTest`), the public contract
 * (`PublicContractE2eTest`) and the isolated-permission witness (`PermissionSeamE2eTest`).
 *
 * Everything is read off the LIVE context by reflection: spring-webmvc and the auth module are not
 * on this module's compile classpath (module-structure §4.2 gives it `:modules:app` alone), and the
 * information is the same either way. A route added tomorrow is discovered without anybody
 * remembering to list it — which is the whole point: the tests compare what the application
 * REGISTERS with what the reviewed docs say, never a hand-kept list with another.
 */
internal object EntryDiscovery {
    /**
     * One (verb, pattern) of one handler method: its declared permission's wire name, and whether a
     * public glob covers it. [anyVerb]: the mapping names no verb, so it answers every one — it is
     * recorded once, as [method] GET (the walk's convention), and [verbs] says what it really serves.
     */
    data class HandlerEntry(
        val method: String,
        val pattern: String,
        val handler: String,
        val permission: String?,
        val public: Boolean,
        val anyVerb: Boolean = false,
    ) {
        val verbs: List<String> get() = if (anyVerb) ANY_VERB else listOf(method)
    }

    /** What a verb-less mapping serves, as auth.md §7.6 spells it. */
    val ANY_VERB = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

    /**
     * `RequestMappingHandlerMapping.getHandlerMethods()`, read by reflection — every (verb, pattern)
     * of every `@RequestMapping` method, with the permission it declares (method annotation, else
     * the class's). A mapping with no verb condition answers every verb; it is recorded as GET, the
     * one a browser sends.
     */
    fun handlerMethods(context: ApplicationContext): List<HandlerEntry> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.entries.flatMap { (info, handlerMethod) ->
            val patterns = patternsOf(info!!)
            val declared = declaredVerbsOf(info)
            val verbs = declared.ifEmpty { setOf("GET") }
            val method = handlerMethod!!.javaClass.getMethod("getMethod").invoke(handlerMethod) as java.lang.reflect.Method
            val beanType = handlerMethod.javaClass.getMethod("getBeanType").invoke(handlerMethod) as Class<*>
            val permission = requiredScopeOf(method) ?: requiredScopeOf(beanType)
            patterns.flatMap { pattern ->
                verbs.map { verb ->
                    HandlerEntry(verb, pattern, "${beanType.simpleName}#${method.name}", permission, isPublic(pattern), declared.isEmpty())
                }
            }
        }
    }

    /** The runtime's own allowlist (`PublicPaths.ENTRIES`): `pattern -> reason`, in declaration order. */
    val publicEntries: Map<String, String> by lazy {
        val type = Class.forName("co.datapipelines.auth.PublicPaths")
        val instance = type.getField("INSTANCE").get(null)
        val entries = type.getMethod("getENTRIES").invoke(instance) as List<*>
        entries.associate { entry ->
            val pattern = entry!!.javaClass.getMethod("getPattern").invoke(entry) as String
            pattern to entry.javaClass.getMethod("getReason").invoke(entry) as String
        }
    }

    /** `PublicPaths.PATTERNS`, read by reflection — what `SecurityConfig` feeds `permitAll`. */
    val publicPatterns: List<String> by lazy {
        val type = Class.forName("co.datapipelines.auth.PublicPaths")
        val instance = type.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        type.getMethod("getPATTERNS").invoke(instance) as List<String>
    }

    /** Is [pattern] under a public glob — matched Ant-style on a concrete path, as the interceptor matches it. */
    fun isPublic(pattern: String): Boolean {
        val concrete = concretePath(pattern)
        return publicPatterns.any { antMatcher.match(it, concrete) } || pattern == "/"
    }

    /** The public globs [pattern] falls under (empty: a governed route). */
    fun publicGlobsOf(pattern: String): List<String> {
        val concrete = concretePath(pattern)
        return publicPatterns.filter { antMatcher.match(it, concrete) }
    }

    /** `{name:regex}` → `{name}`: the doc spells a route without the constraint a handler maps it with (web's `HandlerInventory` rule). */
    fun canonical(pattern: String): String = PATH_VARIABLE_CONSTRAINT.replace(pattern) { "{${it.groupValues[1]}}" }

    /** Well-formed identifiers that exist NOWHERE, by route variable — an admitted caller reaches the handler and finds no row. */
    fun concretePath(pattern: String): String =
        VARIABLE_PATTERN
            .replace(pattern) { match ->
                val variable = match.groupValues[1].substringBefore(':')
                ABSENT_VALUES[variable] ?: if (variable.lowercase().endsWith("id")) ABSENT_UUID else "nobody-owns-this"
            }.replace("/**", "/x")
            .replace("*", "x")

    /** The declared permission's WIRE name (`pipeline.read`) — the catalog's first column — read by reflection. */
    fun requiredScopeOf(element: java.lang.reflect.AnnotatedElement): String? =
        element.annotations
            .firstOrNull { it.annotationClass.simpleName == "RequiredScope" }
            ?.let { annotation ->
                val permission = annotation.javaClass.getMethod("value").invoke(annotation)
                permission.javaClass.getMethod("getWire").invoke(permission) as String
            }

    private fun patternsOf(info: Any): Set<String> {
        val condition = info.javaClass.getMethod("getPathPatternsCondition").invoke(info)
        @Suppress("UNCHECKED_CAST")
        return condition.javaClass.getMethod("getPatternValues").invoke(condition) as Set<String>
    }

    private fun declaredVerbsOf(info: Any): Set<String> {
        val condition = info.javaClass.getMethod("getMethodsCondition").invoke(info)
        val methods = condition.javaClass.getMethod("getMethods").invoke(condition) as Set<*>
        return methods.map { (it as Enum<*>).name }.toSet()
    }

    private val antMatcher = AntPathMatcher()

    const val ABSENT_UUID = "0d0e0000-0000-0000-0000-0000000000ff"

    private val VARIABLE_PATTERN = Regex("\\{([^}]+)\\}")
    private val PATH_VARIABLE_CONSTRAINT = Regex("\\{([A-Za-z0-9_]+):[^}]*}")

    /** Identifiers by the variable NAME a route uses — the same table the isolation sweep keys on. */
    private val ABSENT_VALUES: Map<String, String> =
        mapOf(
            "id" to ABSENT_UUID,
            "pipelineId" to ABSENT_UUID,
            "executionId" to ABSENT_UUID,
            "userId" to ABSENT_UUID,
            "name" to "nobody_owns_this",
            "templateName" to "nobody_owns_this",
            "version" to "1",
            "workspace" to "no-such-workspace",
            "email" to "nobody@nowhere.test",
            "kind" to "welcome",
            "action" to "activate",
            "ns" to "nobody",
            "t" to "nobody",
            "slug" to "nobody",
            "engine" to "postgres",
        )
}

/**
 * **What the reviewed doc says about entries** — auth.md §8.6, the two tables lane 217a added
 * (security-assurance record §4, B3/B5): the entry inventory (every registration family that is not
 * a handler method — servlets, container filters, the security chain's filters, the other handler
 * mappings, scheduled jobs, listening ports and actuator endpoints) and the public contract (every
 * handler a `PublicPaths` glob covers). Handler methods themselves are §7.6's rows (B3: one reviewed
 * table, never two); the inventory's `handler-method` row only says so.
 *
 * ONE walker per table, keyed by the bold marker that opens it; a table ends at the first non-blank
 * line after its rows that is not a row — the [RoleMatrixDocE2e] rule. Cells are compared as
 * written, with code spans unwrapped.
 */
internal object EntryContract {
    const val INVENTORY_MARKER = "**The entry inventory:**"
    const val PUBLIC_CONTRACT_MARKER = "**The public contract:**"
    const val CONDITIONAL_INVENTORY_MARKER = "**The conditional entry inventory:**"

    /** One §8.6.1 row. [reachable] is the doc's "reachable from a request" cell: `yes` or `no`, nothing else. */
    data class InventoryRow(
        val family: String,
        val name: String,
        val type: String,
        val pattern: String,
        val purpose: String,
        val reachable: Boolean,
        val bounded: String,
    ) {
        /** What the runtime must match exactly. */
        val key: String get() = "$family | $name | $type | $pattern"
    }

    /**
     * One row of §8.6.1's conditional table (#838): a registration the application holds only when
     * [condition] — the property literal exactly as the configuration tests it — admits it. The
     * default profile must hold NONE of these rows (`EntryInventoryE2eTest`); a profile suite holds
     * its profile to the default inventory plus every row whose condition that profile admits
     * (`LakeEnabledEntryInventoryE2eTest`). [reachable] is the doc's "reachable from a request"
     * cell: `yes` or `no`, nothing else.
     */
    data class ConditionalRow(
        val family: String,
        val name: String,
        val type: String,
        val pattern: String,
        val condition: String,
        val purpose: String,
        val reachable: Boolean,
        val bounded: String,
    ) {
        /** What the runtime must match exactly — the same four cells as the default inventory's key. */
        val key: String get() = "$family | $name | $type | $pattern"
    }

    /**
     * One §8.6.2 row — one per `PublicPaths` glob: its [reason] exactly as `PublicPaths` states it, the
     * [handlers] it covers (code spans: `VERB /pattern Controller#method`, or `resources` for the
     * resource handler, or `filter:<Class>` for a security-chain endpoint), the anonymous [probe] and
     * the status it must [answer], what the route touches, and what happens on the other verbs
     * (`refused`, or `own E2E: <Suite>, <Suite>` for the flows that are tested where they live).
     */
    data class PublicRow(
        val glob: String,
        val reason: String,
        val handlers: List<String>,
        val probe: String,
        val answer: Int,
        val touches: String,
        val otherVerbs: String,
    )

    fun inventory(doc: String): List<InventoryRow> =
        rows(doc, INVENTORY_MARKER, INVENTORY_COLUMNS).map { c ->
            InventoryRow(c[0], c[1], c[2], c[3], c[4], yesNo(c[5], c), c[6])
        }

    fun conditionalInventory(doc: String): List<ConditionalRow> =
        rows(doc, CONDITIONAL_INVENTORY_MARKER, CONDITIONAL_INVENTORY_COLUMNS).map { c ->
            ConditionalRow(c[0], c[1], c[2], c[3], c[4], c[5], yesNo(c[6], c), c[7])
        }

    fun publicContract(doc: String): List<PublicRow> =
        rawRows(doc, PUBLIC_CONTRACT_MARKER, PUBLIC_COLUMNS).map { raw ->
            val c = raw.map { it.removeSurrounding("`") }
            PublicRow(
                glob = c[0],
                reason = c[1],
                handlers = CODE_SPAN.findAll(raw[2]).map { it.groupValues[1] }.toList(),
                probe = c[3],
                answer = requireNotNull(c[4].toIntOrNull()) { "a public-contract answer is a status code: $raw" },
                touches = c[5],
                otherVerbs = c[6],
            )
        }

    private fun yesNo(
        cell: String,
        row: List<String>,
    ): Boolean =
        when (cell) {
            "yes" -> true
            "no" -> false
            else -> throw IllegalArgumentException("'reachable from a request' is yes or no, got '$cell' in $row")
        }

    private fun rows(
        doc: String,
        marker: String,
        columns: Int,
    ): List<List<String>> = rawRows(doc, marker, columns).map { row -> row.map { it.removeSurrounding("`") } }

    private fun rawRows(
        doc: String,
        marker: String,
        columns: Int,
    ): List<List<String>> {
        val start = doc.indexOf(marker)
        require(start >= 0) { "Could not find '$marker' in ${RoleMatrixDocE2e.AUTH_SPEC_PATH} §8.6" }
        val lines = mutableListOf<String>()
        var seenRow = false
        for (line in doc.substring(start + marker.length).lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("|")) {
                seenRow = true
                lines += trimmed
            } else if (seenRow && trimmed.isNotEmpty()) {
                break
            }
        }
        return lines
            .map { row -> row.trim('|').split("|").map { cell -> cell.trim() } }
            .drop(2) // the header and its separator
            .onEach { require(it.size == columns) { "expected $columns cells in a $marker row, got ${it.size}: $it" } }
    }

    private const val INVENTORY_COLUMNS = 7
    private const val CONDITIONAL_INVENTORY_COLUMNS = 8
    private const val PUBLIC_COLUMNS = 7
    private val CODE_SPAN = Regex("`([^`]+)`")
}

/**
 * **What the running application registers in context** — the in-context families of auth.md
 * §8.6.1, read off a live `ApplicationContext` by reflection: the servlet container's own servlet
 * and filter registrations (with each filter's order from Spring Boot's sorted initializers), every
 * filter of every Spring Security chain in the order the chain runs them, every handler mapping,
 * every `@Scheduled` task and every db-scheduler `Task` bean. Lived inside `EntryInventoryE2eTest`
 * until #838 needed the same readers for the lake-enabled profile
 * (`LakeEnabledEntryInventoryE2eTest`); both suites compare what the application REGISTERS with
 * what the reviewed docs say — the default profile to the default inventory and NONE of the
 * conditional rows, the lake-enabled profile to the default inventory plus every conditional row
 * whose condition it admits.
 */
internal object EntryRuntime {
    /** One runtime entry, rendered exactly as its §8.6.1 row must read. */
    data class Entry(
        val family: String,
        val name: String,
        val type: String,
        val pattern: String,
    ) {
        val key: String get() = "$family | $name | $type | $pattern"
    }

    fun servlets(context: ApplicationContext): List<Entry> {
        val registrations = SERVLET_CONTEXT.getMethod("getServletRegistrations").invoke(servletContextOf(context)) as Map<*, *>
        return registrations.map { (name, registration) ->
            @Suppress("UNCHECKED_CAST")
            val mappings = SERVLET_REGISTRATION.getMethod("getMappings").invoke(registration) as Collection<String>
            Entry("servlet", name as String, className(registration!!), mappings.sorted().joinToString(", ").ifEmpty { "no mapping" })
        }
    }

    /** Container filter registrations, each with the order Spring Boot's sorted initializers gave it (`container` when none did). */
    fun filters(context: ApplicationContext): List<Entry> {
        val registrations = SERVLET_CONTEXT.getMethod("getFilterRegistrations").invoke(servletContextOf(context)) as Map<*, *>
        val orders = initializerOrders(context)
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
    private fun initializerOrders(context: ApplicationContext): Map<String, String> {
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
    fun securityChain(context: ApplicationContext): List<Entry> {
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
    fun handlerMappings(context: ApplicationContext): List<Entry> =
        beansOf(context, HANDLER_MAPPING).map { (name, mapping) ->
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

    fun scheduledTasks(context: ApplicationContext): List<Entry> =
        scheduled(context).map { (runnable, trigger) ->
            val qualified = runnable.toString()
            val name = qualified.substringBeforeLast('.').substringAfterLast('.') + "#" + qualified.substringAfterLast('.')
            Entry("scheduled", name, trigger.first, trigger.second)
        }

    /**
     * #9 — every db-scheduler `Task` bean the context holds (the scheduler's jobs), as
     * `scheduler-task | <task name> | recurring|one-time | <schedule>`. Read by reflection: the
     * library's types are not on this module's compile classpath (they stay behind `modules/scheduler`).
     */
    fun schedulerTasks(context: ApplicationContext): List<Entry> =
        beansOf(context, SCHEDULER_TASK).values.map { task ->
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

    /** The db-scheduler task beans' classes — the B5 check's subjects (`EntryInventoryE2eTest`). */
    fun schedulerTaskBeanTypes(context: ApplicationContext): List<Class<*>> = beansOf(context, SCHEDULER_TASK).values.map { it.javaClass }

    /** `(the task's runnable, (trigger kind, trigger rendering))` for every `@Scheduled` task the holders registered. */
    private fun scheduled(context: ApplicationContext): List<Pair<Any, Pair<String, String>>> =
        beansOf(context, SCHEDULED_TASK_HOLDER)
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

    /** The classes whose methods the scheduled tasks run — the B5 check's subjects. */
    fun scheduledTargets(context: ApplicationContext): List<Class<*>> =
        scheduled(context).map { (runnable, _) ->
            val qualified = runnable.toString()
            Class.forName(qualified.substringBeforeLast('.'))
        }

    /** The classes of every registered request handler — a job's bean may be none of them (B5). */
    fun handlerBeanTypes(context: ApplicationContext): Set<Class<*>> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.values.map { it!!.javaClass.getMethod("getBeanType").invoke(it) as Class<*> }.toSet()
    }

    private fun servletContextOf(context: ApplicationContext): Any = context.javaClass.getMethod("getServletContext").invoke(context)

    @Suppress("UNCHECKED_CAST")
    private fun beansOf(context: ApplicationContext, type: Class<*>): Map<String, Any> = context.getBeansOfType(type as Class<Any>)

    private fun className(registration: Any): String =
        (REGISTRATION.getMethod("getClassName").invoke(registration) as String).substringAfterLast('.')

    private fun simpleName(type: Class<*>): String = type.name.substringAfterLast('.')

    private val SERVLET_CONTEXT: Class<*> = Class.forName("jakarta.servlet.ServletContext")
    private val REGISTRATION: Class<*> = Class.forName("jakarta.servlet.Registration")
    private val SERVLET_REGISTRATION: Class<*> = Class.forName("jakarta.servlet.ServletRegistration")
    private val FILTER_REGISTRATION: Class<*> = Class.forName("jakarta.servlet.FilterRegistration")
    private val REGISTRATION_BEAN: Class<*> = Class.forName("org.springframework.boot.web.servlet.RegistrationBean")
    private val FILTER_REGISTRATION_BEAN: Class<*> = Class.forName("org.springframework.boot.web.servlet.AbstractFilterRegistrationBean")
    private val SECURITY_FILTER_CHAIN: Class<*> = Class.forName("org.springframework.security.web.SecurityFilterChain")
    private val FILTER_CHAIN_PROXY: Class<*> = Class.forName("org.springframework.security.web.FilterChainProxy")
    private val DEFAULT_SECURITY_FILTER_CHAIN: Class<*> = Class.forName("org.springframework.security.web.DefaultSecurityFilterChain")
    private val HANDLER_MAPPING: Class<*> = Class.forName("org.springframework.web.servlet.HandlerMapping")
    private val REQUEST_MAPPING_HANDLER_MAPPING: Class<*> =
        Class.forName("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping")
    private val REQUEST_MAPPING_INFO_HANDLER_MAPPING: Class<*> =
        Class.forName("org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping")
    private val URL_HANDLER_MAPPING: Class<*> = Class.forName("org.springframework.web.servlet.handler.AbstractUrlHandlerMapping")
    private val ROUTER_FUNCTION_MAPPING: Class<*> = Class.forName("org.springframework.web.servlet.function.support.RouterFunctionMapping")
    private val SCHEDULED_TASK_HOLDER: Class<*> = Class.forName("org.springframework.scheduling.config.ScheduledTaskHolder")
    private val TASK: Class<*> = Class.forName("org.springframework.scheduling.config.Task")
    private val INTERVAL_TASK: Class<*> = Class.forName("org.springframework.scheduling.config.IntervalTask")
    private val CRON_TASK: Class<*> = Class.forName("org.springframework.scheduling.config.CronTask")
    private val SCHEDULER_TASK: Class<*> = Class.forName("com.github.kagkarlsson.scheduler.task.Task")
    private val RECURRING_TASK: Class<*> = Class.forName("com.github.kagkarlsson.scheduler.task.helper.RecurringTask")
    private val FIXED_DELAY: Class<*> = Class.forName("com.github.kagkarlsson.scheduler.task.schedule.FixedDelay")
}

/**
 * The context the entry-assurance suites share: the real application on a random port, a separate
 * management port, the shared containers, a stub OIDC issuer. Declared ONCE here and inherited, so
 * suites whose configuration is otherwise identical reuse one cached context instead of booting
 * their own (#138: every extra context holds a pool on the shared Postgres).
 *
 * A base CLASS, not an object (detekt's utility-class rule suppressed for it): Spring reads the
 * `@DynamicPropertySource` a test class inherits from its superclass.
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class EntryAssuranceE2eBase {
    companion object {
        private const val SECRET_BYTES = 32
        private val random = java.security.SecureRandom()

        /** The HS256 secret sessions are minted over; one per JVM, shared by every suite on this base. */
        val jwtSecret: String =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })
        val encryptionKey: String =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        val oidc: OidcDiscoveryStub by lazy { OidcDiscoveryStub() }

        @org.springframework.test.context.DynamicPropertySource
        @JvmStatic
        fun properties(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { encryptionKey }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
