FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S datapipelines && adduser -S datapipelines -G datapipelines

COPY modules/app/build/libs/datapipelines-*.jar /app/datapipelines.jar

USER datapipelines
EXPOSE 8080

# Probe /ready, not /health: HealthController.health() returns its body with
# HTTP 200 even when components are DOWN, so a /health probe can never go
# unhealthy short of a dead listener. /ready maps not-ready (startup, draining,
# or a DOWN component) to 503 — that is the signal `service_healthy` dependants
# need. Root-level, no auth (rest-api.md §11.1); actuator itself is confined to
# the separate management port. busybox wget ships with the alpine base image.
#
# ACCEPTED TRADEOFF (012/F7): /ready couples CONTAINER health to DEPENDENCY
# health (accepting && aggregated UP — db, redis, h2_factory). A dependency
# outage longer than the retry window (3 failures × 30s interval ≈ 90s past
# start-period) flips the container unhealthy; anything that ACTS on Docker
# health (autoheal, future service_healthy dependants) would convert a
# degraded-but-recovering app into a restart loop. Accepted because nothing
# consumes Docker health today; the tradeoff is recorded, not reversed. The
# operator lever is the healthcheck's own flags below — e.g. raise --timeout
# (with Postgres dead, the db health indicator blocks ~30s on connect timeout,
# so a 3s --timeout reports a dead listener rather than a slow probe) or
# --retries/--interval to lengthen the grace window before unhealthy.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO /dev/null http://localhost:8080/ready || exit 1

ENTRYPOINT ["java", "-jar", "/app/datapipelines.jar"]
