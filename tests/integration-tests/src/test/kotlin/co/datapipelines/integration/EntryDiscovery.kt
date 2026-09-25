package co.datapipelines.integration

import org.springframework.context.ApplicationContext
import org.springframework.util.AntPathMatcher

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
    private const val PUBLIC_COLUMNS = 7
    private val CODE_SPAN = Regex("`([^`]+)`")
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
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
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
