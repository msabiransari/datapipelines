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
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO /dev/null http://localhost:8080/ready || exit 1

ENTRYPOINT ["java", "-jar", "/app/datapipelines.jar"]
