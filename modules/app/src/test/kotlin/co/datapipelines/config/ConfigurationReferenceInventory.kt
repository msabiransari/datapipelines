package co.datapipelines.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.type.filter.AnnotationTypeFilter
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** Discovers the configuration surface independently of the documentation's hand-maintained rows. */
internal object ConfigurationReferenceInventory {
    val root: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    fun yaml(text: String): Map<String, String> =
        YamlPropertySourceLoader()
            .load("configuration-reference", ByteArrayResource(text.toByteArray()))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { it to source.getProperty(it).toString() } }
            .toMap()

    fun blocks(doc: String): List<String> =
        Regex("```yaml\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .findAll(doc)
            .map {
                it.groupValues[1]
            }.toList()

    /** Only table first cells define keys; mentioning a missing key in prose cannot satisfy coverage. */
    fun rows(doc: String): Set<String> =
        doc
            .lineSequence()
            .filter { it.startsWith("| `") }
            .flatMap { line -> Regex("`([^`]+)`").findAll(line.substringBefore(" |", "")).map { it.groupValues[1] } }
            .filter {
                it.startsWith("datapipelines.") || it.startsWith("spring.") || it.startsWith("server.") ||
                    it.startsWith("management.") ||
                    it.startsWith("db-scheduler.")
            }.toSet()

    fun normalized(key: String): String = key.replace(Regex("\\[\\d*]"), "[]").replace(Regex("<[^>]+>"), "*")

    /** Scalar defaults only; derived/map defaults retain their domain-specific guards. */
    fun scalarDefaults(doc: String): Map<String, String> {
        val defaults = mutableMapOf<String, String>()
        var defaultColumn: Int? = null
        doc.lineSequence().forEach { line ->
            if (line.startsWith('#')) defaultColumn = null
            if (line.startsWith('|')) {
                val cells =
                    line
                        .split('|')
                        .drop(1)
                        .dropLast(1)
                        .map(String::trim)
                cells.indexOfFirst { it == "Default" || it == "Tracked default" }.takeIf { it >= 0 }?.let { defaultColumn = it }
                val raw = defaultColumn?.let { cells.getOrNull(it) }
                val key = cells.firstOrNull()?.removeSurrounding("`")
                val scalar = raw?.let { Regex("^`([^`]+)`(?: .*)?$").matchEntire(it)?.groupValues?.get(1) }
                val value =
                    when {
                        scalar == "derived" -> null
                        scalar == "empty" || raw in setOf("(none)", "(empty)", "(empty string)") -> ""
                        else -> scalar
                    }
                if (key != null && value != null) defaults[key] = value
            }
        }
        return defaults
    }

    fun covered(
        key: String,
        rows: Set<String>,
    ): Boolean {
        val candidate = normalized(key)
        return rows.any { row ->
            val pattern = normalized(row)
            candidate == pattern || candidate.removeSuffix("[]") == pattern ||
                (pattern.contains('*') && Regex(pattern.split('*').joinToString("[^.]+") { Regex.escape(it) }).matches(candidate))
        }
    }

    /** Compiled annotations include constant prefixes and qualified annotation spellings. */
    fun bindings(): Map<String, Set<String>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(ConfigurationProperties::class.java))
        return scanner.findCandidateComponents("co.datapipelines").associate { definition ->
            val type = Class.forName(requireNotNull(definition.beanClassName))
            val annotation = type.getAnnotation(ConfigurationProperties::class.java)
            val prefix = annotation.prefix.ifBlank { annotation.value }
            type.name to leaves(type, prefix)
        }
    }

    /** Literal environment reads and named key constants supplement constructor-bound properties. */
    fun directKeys(): Set<String> =
        File(root, "modules")
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                File(module, "src/main/kotlin")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        val source = file.readText()
                        val reads = Regex("""(?:getProperty|getRequiredProperty)(?:<[^>]+>)?\s*\(\s*"(datapipelines\.[a-z0-9.-]+)"""")
                        val constants =
                            Regex("""const val [A-Z_]*(?:KEY|PROPERTY)[A-Z_]*(?::\s*String)?\s*=\s*"(datapipelines\.[a-z0-9.-]+)"""")
                        (reads.findAll(source) + constants.findAll(source)).map { it.groupValues[1] }
                    }.toList()
            }.toSet()

    internal fun leaves(
        type: Type,
        path: String,
    ): Set<String> =
        when (type) {
            is ParameterizedType -> {
                val raw = type.rawType as Class<*>
                when {
                    Map::class.java.isAssignableFrom(raw) -> leaves(type.actualTypeArguments[1], "$path.*")
                    Collection::class.java.isAssignableFrom(raw) -> leaves(type.actualTypeArguments[0], "$path[]")
                    else -> leaves(raw, path)
                }
            }

            is WildcardType -> {
                leaves(type.upperBounds.single(), path)
            }

            is Class<*> -> {
                when {
                    type.isArray -> leaves(type.componentType, "$path[]")
                    type.isEnum || !type.name.startsWith("co.datapipelines.") -> setOf(path)
                    else -> constructorLeaves(type, path)
                }
            }

            else -> {
                error("Unsupported configuration type $type at $path; extend the reference inventory")
            }
        }

    private fun constructorLeaves(
        type: Class<*>,
        path: String,
    ): Set<String> {
        val constructor = type.declaredConstructors.filterNot { it.isSynthetic }.maxByOrNull { it.parameterCount }
        requireNotNull(constructor) { "No configuration constructor: ${type.name}" }
        check(constructor.parameterCount > 0) { "No constructor-bound fields: ${type.name}; extend the reference inventory" }
        val names =
            requireNotNull(DefaultParameterNameDiscoverer().getParameterNames(constructor)) {
                "Configuration parameter names missing: ${type.name}"
            }
        return constructor.parameters
            .flatMapIndexed { index, parameter ->
                val name = names[index].replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
                leaves(parameter.parameterizedType, "$path.$name")
            }.toSet()
    }
}
