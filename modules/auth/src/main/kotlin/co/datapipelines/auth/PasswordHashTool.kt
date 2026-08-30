package co.datapipelines.auth

/**
 * Prints the Argon2id encoded hash of a password, for
 * `datapipelines.auth.local.bootstrap-password-hash` (auth.md §5A.2) — the
 * preferred seed form, because the plaintext never has to sit in a config file.
 *
 * Run it with the `hashPassword` Gradle task (never with the password as a shell
 * argument — that would leak it into shell history and the process table):
 *
 * ```
 * ./gradlew :modules:auth:hashPassword                    # prompts (no echo)
 * DATAPIPELINES_SEED_PASSWORD=... ./gradlew :modules:auth:hashPassword
 * ```
 *
 * Uses the production [Argon2SecretHasher] unchanged, so the hash it prints is
 * exactly what the server's `SecretHasher.verify` accepts.
 */
fun main() {
    val raw =
        System.getenv("DATAPIPELINES_SEED_PASSWORD")?.takeIf { it.isNotEmpty() }
            ?: run {
                print("Password to hash (no echo): ")
                System.console()?.readPassword()?.let { String(it) }
                    ?: error("No console available — set DATAPIPELINES_SEED_PASSWORD instead")
            }
    println(Argon2SecretHasher().hash(raw))
}
