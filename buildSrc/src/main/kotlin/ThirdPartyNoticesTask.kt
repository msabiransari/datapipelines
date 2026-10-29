import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedComponentResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Generates distribution notices from the exact resolved production component versions. */
abstract class ThirdPartyNoticesTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>

    @get:Input abstract val mysql: Property<Boolean>

    @get:Input abstract val oracle: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dockerfile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val duckdbChecksums: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTexts: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val duckdbClosures: DirectoryProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        group = "documentation"
        description = "Generates THIRD-PARTY-NOTICES from production runtime POMs, GraalJS and pinned DuckDB extensions."
        notCompatibleWithConfigurationCache("ArtifactResolutionQuery resolves parent POMs during execution")
        // POM contents are verified by Gradle, but not available as task inputs before parent walking.
        // Always revalidate the graph and stale allowlist entries, including on warm builds.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun generate() {
        val graph = coordinates.get() + ThirdPartyNotices.isolates(dockerfile.get().asFile.readText())
        val extensions =
            ThirdPartyNotices.duckdbExtensions(
                dockerfile.get().asFile.readText(),
                duckdbChecksums.get().asFile.readText(),
                ThirdPartyNotices.closureFiles(duckdbClosures.get().asFile),
            )
        val renderer = ThirdPartyNotices(::resolvePom)
        val report =
            renderer.render(graph, ThirdPartyLicenseAllowlist.mappings(mysql.get(), oracle.get()), extensions) { id ->
                val text = licenseTexts.file("$id.txt").get().asFile
                check(text.isFile && text.readText().isNotBlank()) { "Missing vendored licence text: $id at $text" }
                text.readText().replace("\r\n", "\n").trimEnd()
            }
        val output = outputDirectory.file("THIRD-PARTY-NOTICES").get().asFile
        output.parentFile.mkdirs()
        output.writeText(report, Charsets.UTF_8)
        logger.lifecycle("Third-party notices: ${graph.distinct().size} artifacts, ${renderer.parentWalks} parent walks -> $output")
    }

    private fun resolvePom(coordinate: String): File {
        val (group, name, version) = coordinate.split(':')
        val result =
            project.dependencies
                .createArtifactResolutionQuery()
                .forModule(group, name, version)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                .execute()
        val component =
            result.resolvedComponents.singleOrNull()
                ?: throw GradleException(
                    "Cannot resolve POM for $coordinate: ${result.components.filterIsInstance<UnresolvedComponentResult>().map {
                        it.failure.message
                    }}",
                )
        val artifact = component.getArtifacts(MavenPomArtifact::class.java).singleOrNull()
        return (artifact as? ResolvedArtifactResult)?.file
            ?: throw GradleException("Cannot resolve POM artifact for $coordinate: $artifact")
    }
}

/** POM inheritance and rendering shared by the Gradle task and offline fixture tests. */
class ThirdPartyNotices(
    private val pomFile: (String) -> File,
) {
    private data class License(
        val name: String,
        val raw: String,
    )

    private data class Pom(
        val licenses: List<License>,
        val url: String,
        val parent: String?,
    )

    private val poms = mutableMapOf<String, Pom>()
    var parentWalks: Int = 0
        private set

    /** Missing, unknown and unused licence mappings all fail closed. All declared alternatives are retained. */
    fun render(
        coordinates: List<String>,
        mappings: Map<String, String>,
        extensions: List<DuckDbExtensionNotice> = emptyList(),
        text: (String) -> String,
    ): String {
        val used = mutableSetOf<String>()
        val ids = sortedSetOf<String>()
        val rows =
            coordinates.distinct().sorted().map { coordinate ->
                val pom = readPom(coordinate)
                val licenses = licenses(coordinate, coordinate, emptySet())
                check(licenses.isNotEmpty()) { "Unresolved licence for $coordinate: raw POM licenses=<none> (including parents)" }
                val labels =
                    licenses
                        .map { license ->
                            val expression =
                                mappings[license.name]
                                    ?: error("Unresolved licence for $coordinate: raw POM license=${license.raw}")
                            used.add(license.name)
                            ids.addAll(expression.split(" WITH "))
                            "$expression (${license.name})"
                        }.distinct()
                        .sorted()
                        .joinToString("; ")
                val copyright = if (coordinate.startsWith("org.graalvm.")) " — Copyright (c) Oracle and/or its affiliates." else ""
                "$coordinate — $labels — ${pom.url}$copyright"
            }
        val stale = mappings.keys - used
        check(stale.isEmpty()) { "STALE licence allowlist entries (matched no POM): ${stale.sorted()}" }
        val extensionRows = extensionRows(extensions, ids)
        return buildString {
            append("Datapipelines — THIRD-PARTY-NOTICES\nProduct code: AGPL-3.0 (see LICENSE).\n")
            append("Generated by :modules:app:generateThirdPartyNotices; do not edit.\n")
            append("Scope: resolved productionRuntimeClasspath (including platform POMs), boot jar tools, ")
            append("both image isolates, and bundled DuckDB extensions.\n")
            append("All POM-declared licences are listed; alternatives retain their upstream terms.\n\n")
            (rows + extensionRows).sorted().forEach { append(it).append('\n') }
            append(componentSections(extensions))
            ids.forEach { id ->
                append("\n===== $id =====\nSource: ${ThirdPartyLicenseAllowlist.source(id)}\n\n")
                append(text(id).trimEnd()).append('\n')
            }
        }
    }

    private fun extensionRows(
        extensions: List<DuckDbExtensionNotice>,
        ids: MutableSet<String>,
    ): List<String> =
        extensions.sortedBy { it.coordinate }.map { extension ->
            ids.addAll(DuckDbExtensionLicenses.licenseIds(extension.license.spdx))
            extension.license.components.forEach { ids.addAll(DuckDbExtensionLicenses.licenseIds(it.spdx)) }
            "${extension.coordinate} — ${extension.license.spdx} — ${extension.source} — ${extension.license.copyright}"
        }

    private fun componentSections(extensions: List<DuckDbExtensionNotice>): String =
        buildString {
            extensions.sortedBy { it.name }.forEach { extension ->
                val closure = extension.closure
                val versions = closure.entries.associate { it.port to it.version }
                append("\n----- ${extension.coordinate}: statically linked components (direct and transitive) -----\n")
                append("Manifest: ${extension.source}/vcpkg.json\n")
                append("Closure: vcpkg baseline ${closure.baseline}; overlays ${closure.overlays}; features ${closure.features}\n")
                extension.license.components.sortedBy { it.name }.forEach { component ->
                    val via = if (component.via.isEmpty()) "" else " — via ${component.via}"
                    append("${component.name} ${versions.getValue(component.name)} — ${component.spdx} — ${component.source}$via\n")
                    if (component.attribution.isNotBlank()) append(component.attribution).append('\n')
                }
                val buildOnly = closure.entries.filter { it.kind == "build-only" }.map { "${it.port} ${it.version}" }
                append("Build-only (not distributed): ${buildOnly.ifEmpty { listOf("none") }.joinToString(", ")}\n")
            }
        }

    private fun licenses(
        root: String,
        coordinate: String,
        seen: Set<String>,
    ): List<License> {
        check(coordinate !in seen) { "Cyclic POM parents for $root: ${seen + coordinate}" }
        val pom = readPom(coordinate)
        if (pom.licenses.isNotEmpty()) return pom.licenses
        val parent = pom.parent ?: return emptyList()
        parentWalks++
        return licenses(root, parent, seen + coordinate)
    }

    private fun readPom(coordinate: String): Pom =
        poms.getOrPut(coordinate) {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val project = factory.newDocumentBuilder().parse(pomFile(coordinate)).documentElement
            val parent =
                project.child("parent")?.let {
                    listOf("groupId", "artifactId", "version").joinToString(":") { key ->
                        it.value(key).also { value -> check(value.isNotEmpty()) { "Invalid parent $key for $coordinate" } }
                    }
                }
            val licenses =
                project.child("licenses")?.children("license").orEmpty().map {
                    License(it.value("name"), it.textContent.trim().replace(Regex("\\s+"), " "))
                }
            Pom(licenses, project.value("url").ifBlank { project.child("scm")?.value("url").orEmpty() }, parent)
        }

    companion object {
        /** Non-POM rows require complete checksums, a closure file and a reviewed inventory at each exact footer commit. */
        fun duckdbExtensions(
            dockerfile: String,
            checksumFile: String,
            closures: Map<String, String>,
            inventory: Map<String, DuckDbExtensionLicense> = DuckDbExtensionLicenses.inventory(),
        ): List<DuckDbExtensionNotice> = DuckDbExtensionLicenses.read(dockerfile, checksumFile, closures, inventory)

        /** The committed closure files by extension name; anything but a `.tsv` file in the directory fails. */
        fun closureFiles(directory: File): Map<String, String> {
            val files = directory.listFiles().orEmpty().sortedBy { it.name }
            check(files.all { it.isFile && it.name.endsWith(".tsv") }) { "Unexpected entries in $directory: ${files.map { it.name }}" }
            return files.associate { it.name.removeSuffix(".tsv") to it.readText(Charsets.UTF_8) }
        }

        /** Both architectures travel in the notice; the image redistributes one, selected by TARGETARCH. */
        fun isolates(dockerfile: String): List<String> {
            val versions = Regex("(?m)^ARG GRAALJS_VERSION=([^\\s]+)$").findAll(dockerfile).toList()
            check(versions.size == 1) { "Dockerfile must declare exactly one GRAALJS_VERSION" }
            val version = versions.single().groupValues[1]
            check(version.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))) { "Invalid GRAALJS_VERSION: $version" }
            return listOf("amd64", "aarch64").map { "org.graalvm.js:js-isolate-linux-$it-community:$version" }
        }
    }
}

private fun Element.children(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { (it.localName ?: it.tagName) == name }

private fun Element.child(name: String): Element? = children(name).singleOrNull()

private fun Element.value(name: String): String = child(name)?.textContent?.trim().orEmpty()
