package co.datapipelines.lake.profiles

import co.datapipelines.lake.profiles.LakeTuningSourceGuard.LAKE
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M8 (brief 956 C.2, P11): append and maintenance code get a tuning value (a byte size, a file count, a ratio, an
 * interval, a window) from a `LakeProfile` property and nowhere else. Five rules over the main sources, red when:
 * - (a) code under `append/` or `maintenance/` holds a numeric literal other than 0 and 1 that the allowlist does not
 *   name (a constant's declaration is its literal);
 * - (b) that code refers to a numeric constant (a `const val`, or an UPPER-case `val` set from a number, a `Duration` or
 *   a `DataSize`) declared anywhere else in the main sources, `profiles/` included, by `Owner.NAME`,
 *   `Owner.Companion.NAME` or an import: moving a constant to a helper does not pass;
 * - (c) that code reads an Iceberg table property (`properties()`, `PropertyUtil`, a `TableProperties` name) or a
 *   LakeProperties group the allowlist does not name; the writer SETS its two write properties and reads none back;
 * - (d) anything in `modules/lake`, `modules/web` or `modules/app` main but the start-up seed, its wiring and the boot
 *   rule reads `LakeProperties.maintenance.window` or `.maxBytesRewrittenPerRun` (as a member or by its key);
 * - (e) code under `maintenance/` reads protection days or the orphan grace from anything but LakeProperties.
 *
 * What the scan cannot see, so M8 claims exactly these five rules: a value computed from an admitted constant or
 * from 0 and 1; a number reached by reflection, a resource file or a string parsed at run time; a constant of another
 * shape (a lower-case `val`, a function returning a number) declared outside the two directories; a LakeProperties
 * read through a receiver not named `properties` or `lakeProperties`; a retention value read through a variable typed
 * `Retention` that was filled from elsewhere. Comments and string contents are not scanned, except that (c), (d) and
 * (e) read string literals for keys.
 *
 * The fixtures each add or replace sources over the real tree and must turn red on their own rule and on no other.
 */
class LakeTuningSourceGuardTest {
    private val writerPath = "${LAKE}append/ParquetAppendWriter.kt"
    private val writer: String = real.single { it.path == writerPath }.text

    @Test
    fun `the real tree passes, the real writer, seed and boot rule included`() {
        withClue("the scan read the scoped code") {
            real.count { it.path.startsWith("${LAKE}append/") } shouldBeGreaterThanOrEqual MIN_APPEND_FILES
        }
        LakeTuningSourceGuard.SEED_READERS.forEach { path -> withClue(path) { real.map { it.path } shouldContain path } }
        realViolations.shouldBeEmpty()
    }

    @Test
    fun `every allowlist entry names a file that holds its token, so no stale entry admits a later constant`() {
        LakeTuningSourceGuard.ALLOWLIST.forEach { entry ->
            val source = real.singleOrNull { it.path == LAKE + entry.file }
            withClue("${entry.file}: ${entry.token}") {
                (source != null) shouldBe true
                KotlinText.withoutComments(requireNotNull(source).text) shouldContain entry.token
                entry.reason.isNotBlank() shouldBe true
            }
        }
    }

    @Test
    fun `the base writer's two size constants are red on rule a`() {
        val base = resource("ParquetAppendWriter.base.txt")
        val found = redOnly('a', mapOf(writerPath to base))
        found.any { it.what.contains("ROW_GROUP_BYTES") } shouldBe true
        found.any { it.what.contains("FILE_BYTES = 64L") } shouldBe true
    }

    @Test
    fun `a size constant in a helper outside append imported by the writer is red on rule b`() {
        val helper = "package co.datapipelines.lake.tuning\n\nobject WriterSizes {\n    const val FILE_BYTES = 64L * 1024 * 1024\n}\n"
        val edited =
            writer
                .replaceOnce(PROFILE_IMPORT, "$PROFILE_IMPORT\nimport co.datapipelines.lake.tuning.WriterSizes")
                .replace("profile.targetFileBytes", "WriterSizes.FILE_BYTES")
        val found = redOnly('b', mapOf(writerPath to edited, "${LAKE}tuning/WriterSizes.kt" to helper))
        found.map { it.what }.single() shouldContain "WriterSizes.FILE_BYTES"
    }

    @Test
    fun `the same constant in an object under profiles is red on rule b, while the profile's own property read passes`() {
        val helper =
            "package co.datapipelines.lake.profiles\n\nobject ProfileDefaults {\n    const val TARGET_FILE_BYTES = 64L * 1024 * 1024\n}\n"
        val edited =
            writer
                .replaceOnce(PROFILE_IMPORT, "$PROFILE_IMPORT\nimport co.datapipelines.lake.profiles.ProfileDefaults.TARGET_FILE_BYTES")
                .replaceOnce("PlainWriter(table, factory, output, profile.targetFileBytes)", "PlainWriter(table, factory, output, $TARGET)")
        withClue("the fixture still reads the LakeProfile property in the other writer") { edited shouldContain "profile.targetFileBytes" }
        val found = redOnly('b', mapOf(writerPath to edited, "${LAKE}profiles/ProfileDefaults.kt" to helper))
        found.map { it.what }.single() shouldContain "ProfileDefaults.TARGET_FILE_BYTES"
    }

    @Test
    fun `the target read back from a table property is red on rule c`() {
        val edited =
            writer
                .replaceOnce(PROFILE_IMPORT, "$PROFILE_IMPORT\nimport org.apache.iceberg.util.PropertyUtil")
                .replace(
                    "profile.targetFileBytes",
                    "PropertyUtil.propertyAsLong(table.properties(), TableProperties.WRITE_TARGET_FILE_SIZE_BYTES, " +
                        "profile.targetFileBytes)",
                )
        val found = redOnly('c', mapOf(writerPath to edited))
        found.map { it.what } shouldContain "TableProperties.WRITE_TARGET_FILE_SIZE_BYTES"
    }

    @Test
    fun `a second reader of the seed's window in modules web is red on rule d`() {
        val reader =
            "package co.datapipelines.web.config\n\nimport co.datapipelines.lake.LakeProperties\n\n" +
                "class MaintenanceWindowBanner(\n    private val properties: LakeProperties,\n) {\n" +
                "    fun window(): String = properties.maintenance.window\n}\n"
        redOnly('d', mapOf("modules/web/src/main/kotlin/co/datapipelines/web/config/MaintenanceWindowBanner.kt" to reader))
    }

    @Test
    fun `a retention value read from a profile under maintenance is red on rule e`() {
        val reader =
            "package co.datapipelines.lake.maintenance\n\nimport co.datapipelines.lake.profiles.LakeProfile\n\n" +
                "class RetentionFromProfile(\n    private val profile: LakeProfile,\n) {\n" +
                "    fun protection(): Int = profile.retention.protectionDays\n}\n"
        redOnly('e', mapOf("${LAKE}maintenance/RetentionFromProfile.kt" to reader))
    }

    @Test
    fun `the stripper blanks nested comments and strings but keeps the line count`() {
        val text = "val a = 1 /* x /* 7 */ 8 */ + 2\n// 9\nval s = \"10\" + \"\"\"11\n12\"\"\"\n"
        val code = KotlinText.code(text)
        code.lines().size shouldBe text.lines().size
        listOf("7", "8", "9", "10", "11", "12").forEach { withClue(it) { code.contains(it) shouldBe false } }
        code shouldContain "+ 2"
    }

    /** The violations [fixture] adds over the real tree; asserts there are some and every one is on [rule]. */
    private fun redOnly(
        rule: Char,
        fixture: Map<String, String>,
    ): List<GuardViolation> {
        val merged = real.filter { it.path !in fixture } + fixture.map { (path, text) -> GuardSource(path, text) }
        val added = LakeTuningSourceGuard.violations(merged) - realViolations.toSet()
        withClue("the fixture is red: $added") { added.shouldNotBeEmpty() }
        withClue("red on rule $rule only: $added") { added.map { it.rule }.toSet() shouldBe setOf(rule) }
        return added
    }

    private fun String.replaceOnce(
        old: String,
        new: String,
    ): String {
        withClue("the fixture's anchor occurs exactly once: $old") { split(old).size shouldBe 2 }
        return replace(old, new)
    }

    private companion object {
        const val PROFILE_IMPORT = "import co.datapipelines.lake.profiles.LakeProfile"
        const val TARGET = "TARGET_FILE_BYTES"
        const val MIN_APPEND_FILES = 10

        /** The main sources and their verdict, read and scanned once for the class (each test reuses them). */
        val real: List<GuardSource> by lazy { mainSources() }
        val realViolations: List<GuardViolation> by lazy { LakeTuningSourceGuard.violations(real) }

        val root: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
            requireNotNull(dir) { "repository root not found" }
        }

        fun mainSources(): List<GuardSource> =
            requireNotNull(File(root, "modules").listFiles()) { "no modules directory" }
                .map { File(it, "src/main/kotlin") }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .map { GuardSource(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }

        fun resource(name: String): String =
            requireNotNull(LakeTuningSourceGuardTest::class.java.getResource("/lake-tuning-guard/$name")) { "missing $name" }.readText()
    }
}
