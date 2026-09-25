package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.asm.ClassReader
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * **The permission-resolution seam cannot ship twice** (security-assurance record §7.1, ratified B4;
 * lane 217a, A.3) — read off the application jar people actually run, never off the source tree.
 *
 * The seam is an interface in `auth` main so that a TEST context can put a synthetic grant behind
 * it (`PermissionSeamE2eTest`). The production shape the record ratified is: exactly ONE packaged
 * implementation, no test class in the artifact, and no profile or property that could select
 * another. So this suite:
 *
 * - opens `modules/app/build/libs/datapipelines-app.jar` (the integration test task depends on
 *   `bootJar`, so it is always the current tree's), walks `BOOT-INF/classes` and every nested jar
 *   that carries `co/datapipelines/` classes, and reads each class's super class and interfaces
 *   with Spring's repackaged ASM — then closes over subclasses, so an implementation hidden behind
 *   an abstract class is still counted. Exactly one implementor, `RolePermissionsResolver`, passes.
 *   A Kotlin lambda cannot implement the interface (it is not a `fun interface`), so every
 *   implementation is a class file here.
 * - fails on any packaged project class that references a test library (JUnit, Kotest, MockK —
 *   none of them is on the production classpath, so a reference is a test class that shipped), on
 *   any class of the test module's package, and on anything named `SyntheticGrantResolver` — the
 *   test-sources grant must never be packaged.
 * - fails on `@Profile` or any `@Conditional…` in the `auth` main sources that declare or
 *   register a resolver: a profile name in an environment variable is a request-free switch
 *   nobody would notice (B4).
 *
 * This lives beside `JarSmokeE2eTest`, the other test of the packaged artifact, because this
 * module's test task is the one wired to `bootJar`.
 */
class PackagedResolverTest {
    private val scan: JarScan by lazy { JarScan.of(jarFile()) }

    @Test
    fun `the application jar packages exactly one PermissionResolver implementation`() {
        println(
            "event=seam.jar classes=${scan.classes.size} project_jars=${scan.projectJars.size} implementors=${scan.implementorsOf(
                RESOLVER,
            )}",
        )
        withClue("classes implementing $RESOLVER in the packaged application — B4 allows exactly one, the production table") {
            scan.implementorsOf(RESOLVER).sorted() shouldBe listOf(PRODUCTION)
        }
        scan.classes.size shouldBeGreaterThanOrEqual CLASS_FLOOR
        scan.projectJars.size shouldBeGreaterThanOrEqual PROJECT_JAR_FLOOR
    }

    @Test
    fun `no test class is packaged - no test-library reference, no test-module class, no synthetic grant`() {
        val offenders =
            scan.classes.values
                .filter { c ->
                    c.referencesTestLibrary ||
                        c.name.startsWith(TEST_MODULE_PACKAGE) ||
                        c.name.substringAfterLast('/').startsWith("SyntheticGrantResolver")
                }.map { it.name }
                .sorted()
        withClue("packaged classes that are tests or test fixtures") { offenders.joinToString("\n") shouldBe "" }
    }

    @Test
    fun `no profile and no condition selects a resolver - in any auth source that declares or registers one`() {
        val sources =
            File(repoRoot(), "modules/auth/src/main")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains("PermissionResolver") }
                .toList()
        val switches =
            sources.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> !COMMENT.containsMatchIn(line) && SWITCH.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
            }
        println("event=seam.sources files=${sources.map { it.name }.sorted()}")
        withClue(
            "a profile or condition in a file that declares or registers the resolver (B4)",
        ) { switches.joinToString("\n") shouldBe "" }
        sources.map { it.name } shouldContainAll listOf("PermissionResolver.kt", "AuthConfiguration.kt")
    }

    // ------------------------------------------------------------------ the scan

    /** One packaged class: its internal name, super class, interfaces, and whether its constant pool names a test library. */
    private data class PackagedClass(
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val referencesTestLibrary: Boolean,
    )

    private class JarScan(
        val classes: Map<String, PackagedClass>,
        val projectJars: List<String>,
    ) {
        /** Every class that implements [type], directly or through a super class chain. */
        fun implementorsOf(type: String): List<String> {
            val direct =
                classes.values
                    .filter { type in it.interfaces }
                    .map { it.name }
                    .toMutableSet()
            var grew = true
            while (grew) {
                val next = classes.values.filter { it.superName in direct && it.name !in direct }.map { it.name }
                grew = direct.addAll(next)
            }
            return direct.map { it.replace('/', '.') }
        }

        companion object {
            fun of(jar: File): JarScan {
                val classes = mutableMapOf<String, PackagedClass>()
                val projectJars = mutableListOf<String>()
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val found = entryClasses(entry.name) { zip.getInputStream(entry).readBytes() }
                        if (found.isNotEmpty() && entry.name.startsWith(LIB)) projectJars += entry.name.removePrefix(LIB)
                        found.forEach { classes[it.name] = it }
                    }
                }
                return JarScan(classes, projectJars)
            }

            /** The project classes one jar entry carries: itself (a `BOOT-INF/classes` class), a nested jar's, or none. */
            private fun entryClasses(
                name: String,
                bytes: () -> ByteArray,
            ): List<PackagedClass> =
                when {
                    name.startsWith(CLASSES) && name.endsWith(".class") -> listOfNotNull(read(bytes()))
                    name.startsWith(LIB) && name.endsWith(".jar") -> readNested(bytes())
                    else -> emptyList()
                }

            /** The project classes of one nested jar; an empty list for a third-party jar (no `co/datapipelines/` class). */
            private fun readNested(bytes: ByteArray): List<PackagedClass> {
                val found = mutableListOf<PackagedClass>()
                ZipInputStream(bytes.inputStream()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (entry.name.startsWith(PROJECT_PACKAGE) && entry.name.endsWith(".class")) read(zip.readBytes())?.let(found::add)
                    }
                }
                return found
            }

            private fun read(bytes: ByteArray): PackagedClass? {
                val reader = ClassReader(bytes)
                if (!reader.className.startsWith(PROJECT_PACKAGE)) return null
                val text = String(bytes, Charsets.ISO_8859_1)
                return PackagedClass(reader.className, reader.superName, reader.interfaces.toList(), TEST_LIBRARIES.any { it in text })
            }
        }
    }

    private fun jarFile(): File {
        val jar = File(repoRoot(), "modules/app/build/libs/datapipelines-app.jar")
        check(jar.isFile) { "bootJar output not found at ${jar.absolutePath} — ./gradlew :modules:app:bootJar" }
        return jar
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private companion object {
        const val RESOLVER = "co/datapipelines/auth/PermissionResolver"
        const val PRODUCTION = "co.datapipelines.auth.RolePermissionsResolver"
        const val CLASSES = "BOOT-INF/classes/"
        const val LIB = "BOOT-INF/lib/"
        const val PROJECT_PACKAGE = "co/datapipelines/"
        const val TEST_MODULE_PACKAGE = "co/datapipelines/integration/"
        val TEST_LIBRARIES = listOf("org/junit/", "io/kotest/", "io/mockk/")
        val SWITCH = Regex("@(Profile|Conditional\\w*)\\b")

        /** A KDoc or line-comment line: prose about the rule, not a use of it. */
        val COMMENT = Regex("^\\s*(\\*|/\\*|//)")

        /** Floors, not targets (measured on this lane's base, 2026-09-24 — see the handback). */
        const val CLASS_FLOOR = 2000
        const val PROJECT_JAR_FLOOR = 12
    }
}
