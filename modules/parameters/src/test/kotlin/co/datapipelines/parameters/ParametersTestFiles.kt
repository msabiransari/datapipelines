package co.datapipelines.parameters

import java.io.File

/** Repository files for the spec-drift suites — the nearest ancestor holding `settings.gradle.kts` is the root (the house locator). */
internal object ParametersTestFiles {
    fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, relative).also { require(it.isFile) { "missing: $relative" } }
    }

    fun read(relative: String): String = repoFile(relative).readText()
}
