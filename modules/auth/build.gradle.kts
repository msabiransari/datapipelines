// module-structure.md §5.7 — allowed internal deps: typesystem (shared exception base only).
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))

    implementation(libs.argon2.jvm)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    // Spring Security web/config + OAuth2 client + Jose (auth.md §12.2).
    implementation(libs.spring.boot.starter.oauth2.client)
    // No BouncyCastle (removed 2026-08-07, security review MEDIUM-6 — see §5.7).
    // Argon2id comes from argon2-jvm; JWT signing from jjwt over the JDK providers.
    implementation(libs.spring.boot.starter.jdbc) // user / key / audit repositories
}
