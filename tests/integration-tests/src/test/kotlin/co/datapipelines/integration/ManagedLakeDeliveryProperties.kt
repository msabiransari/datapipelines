package co.datapipelines.integration

import org.springframework.test.context.DynamicPropertyRegistry

/** The full application fixture's bounded lake settings, shared without exposing internal module types. */
internal object ManagedLakeDeliveryProperties {
    fun register(
        registry: DynamicPropertyRegistry,
        secret: String,
        issuer: String,
        lake: String,
        bucket: String,
    ) {
        SharedE2e.s3Client().use { it.createBucket { request -> request.bucket(bucket) } }
        registry.add("management.server.port") { "0" }
        registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
        registry.add("spring.datasource.username") { SharedE2e.postgres.username }
        registry.add("spring.datasource.password") { SharedE2e.postgres.password }
        registry.add("datapipelines.redis.password") { "" }
        registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
        registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
        registry.add("datapipelines.jwt.secret") { secret }
        registry.add("datapipelines.db.encryption-key") { secret }
        registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
        registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-client" }
        registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-client-secret" }
        registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { issuer }
        registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        registry.add("datapipelines.lake.enabled") { "true" }
        registry.add("datapipelines.lake.name") { lake }
        registry.add("datapipelines.lake.storage.bucket") { bucket }
        registry.add("datapipelines.lake.storage.region") { "us-east-1" }
        registry.add("datapipelines.lake.storage.endpoint") { "http://${SharedE2e.minioEndpoint}" }
        registry.add("datapipelines.lake.storage.path-style") { "true" }
        registry.add("datapipelines.lake.storage.access-key") { SharedE2e.MINIO_USER }
        registry.add("datapipelines.lake.storage.secret-key") { SharedE2e.MINIO_PASSWORD }
        registry.add("datapipelines.lake.admission.max-delivery-bytes") { "16MB" }
        registry.add("datapipelines.lake.admission.staging-expansion-factor") { "2" }
        registry.add("datapipelines.lake.admission.staging-memory-limit") { "64MB" }
        registry.add("datapipelines.lake.admission.scratch-bytes") { "128MB" }
        registry.add("datapipelines.lake.admission.lease-wait-seconds") { "1" }
        registry.add("datapipelines.lake.admission.job-timeout") { "PT2M" }
    }
}
