package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * [VendoredThemes.names] against REAL packaged classpaths (025 B1 — the T21 class): a
 * fixture jar built at test time, loaded through its own [URLClassLoader], is the exact
 * shape a Spring Boot fat jar presents — `jar:` URLs the old `File(getResource(...).
 * toURI())` resolution died on with `URI is not hierarchical`.
 */
class VendoredThemesTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `theme names enumerate from inside a jar`() {
        val jar = jarWith("static/vendor/design-system/themes/ocean.css", "static/vendor/design-system/themes/saas.css")
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
            VendoredThemes.names(loader) shouldContainExactly listOf("ocean", "saas")
        }
    }

    @Test
    fun `no vendored assets anywhere is null - the pre-P8 defer signal`() {
        // A loader over an EMPTY jar: the directory matches nothing on any root.
        val empty = File(tempDir, "empty.jar")
        JarOutputStream(empty.outputStream()).use {}
        URLClassLoader(arrayOf(empty.toURI().toURL()), null).use { loader ->
            VendoredThemes.names(loader) shouldBe null
        }
    }

    /** The real classpath carries the vendored design system — an absent-result pass would be vacuous. */
    @Test
    fun `the runtime classpath lists the real vendored themes`() {
        VendoredThemes.names().shouldNotBeNull().shouldContain("saas")
    }

    private fun jarWith(vararg entries: String): File {
        val jar = File(tempDir, "themes.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            // Directory entries: `getResources(".../themes/")` only answers when the jar
            // carries them, and the resolver's root-dir probe needs that answer — the
            // fixture must be a well-formed archive, not a bare entry list.
            val directories =
                listOf(
                    "static/",
                    "static/vendor/",
                    "static/vendor/design-system/",
                    "static/vendor/design-system/themes/",
                )
            (directories + entries).forEach { path ->
                out.putNextEntry(ZipEntry(path))
                if (path.endsWith(".css")) out.write("/* theme */".toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }
}
