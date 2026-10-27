import java.io.File

/**
 * The repo's committed dependency checksums (`gradle/verification-metadata.xml`), as the
 * TestKit probes see them (#742).
 *
 * A probe is its own ROOT build (`withProjectDir(probeDir)`), and Gradle reads dependency
 * verification only from the root build's `gradle/` directory — so without this file in the
 * probe root, every artifact a probe downloads from Maven Central (the convention plugin's
 * test set, the Kotlin compiler, the ktlint and Kover workers) arrived UNVERIFIED, on CI's
 * cold runner every run. [installRepoVerification] copies the repo's file there, so the
 * probes verify against the SAME checksums as the modules and a wrong one fails the probe
 * naming the artifact.
 *
 * Unrelated to the build cache: `KtlintCacheBoundaryTest`'s shared
 * `buildCache.local.directory` stores task outputs, while verification checks the bytes of
 * resolved dependencies; one probe file changes nothing about what the cache stores or serves.
 *
 * The file's path arrives through the `repo.verification` system property, which
 * buildSrc's `tasks.test` sets and declares as an input, so a checksum change re-runs these tests.
 */
val repoVerificationMetadata: File
    get() =
        File(
            System.getProperty("repo.verification")
                ?: error("repo.verification system property not set — run via buildSrc's test task"),
        )

/** Copies the repo's verification metadata into [probeDir]'s `gradle/` directory, optionally rewritten by [edit]. */
fun installRepoVerification(
    probeDir: File,
    edit: (String) -> String = { it },
) {
    probeDir
        .resolve("gradle/verification-metadata.xml")
        .apply { parentFile.mkdirs() }
        .writeText(edit(repoVerificationMetadata.readText()))
}
