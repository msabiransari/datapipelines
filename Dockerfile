FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S datapipelines && adduser -S datapipelines -G datapipelines

COPY modules/app/build/libs/datapipelines-*.jar /app/datapipelines.jar

USER datapipelines
EXPOSE 8080

# The actuator /health endpoint (rest-api.md §11.1) — root-level, no auth.
# busybox wget ships with the alpine base image.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO /dev/null http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/datapipelines.jar"]
