# glibc base (Ubuntu), NOT -alpine: argon2-jvm loads its native library through
# JNA, which ships glibc binaries. On musl the loader is absent, so API-key
# minting (Argon2id, auth.md §7.2) died with UnsatisfiedLinkError — and /mcp
# accepts nothing but API keys, so the whole agent path was down (T37, found by
# 023's demo E2E). Alpine + gcompat was probed as the smaller fix and REJECTED
# with evidence: the shim SIGSEGVs the whole JVM inside the JNA temp library
# (hs_err, "Problematic frame: C [jna….tmp]") — worse than the 500. This image
# ships wget (healthcheck below) and curl.
FROM eclipse-temurin:21-jre

RUN groupadd --system datapipelines && useradd --system --gid datapipelines datapipelines

# dp-lake (089 §D): bundle the DuckDB extensions a LAKE datasource loads, so the runtime
# never INSTALLs and a hardened deployment needs no egress to extensions.duckdb.org
# (configuration.md §3.25). The version is the CORE version the pinned duckdb_jdbc reports
# (`PRAGMA version` -> v1.5.5 for duckdb_jdbc 1.5.5.1), NOT the JDBC patch version — the
# repository path 404s on the latter. A duckdb_jdbc upgrade must re-pin this ARG and the
# `v<ver>` directory with it; the binaries are valid for exactly one core version.
# `avro` is bundled because `LOAD iceberg` auto-loads it from the directory. The four add
# ~108 MB uncompressed to the image (~39 MB downloaded at build). The extension repository
# publishes no checksums for these artifacts, so the pin is the versioned URL itself.
# linux_amd64 only — the platform string this JRE image reports; a multi-arch image would
# add a linux_arm64 directory beside it. The layout (<dir>/v<ver>/<platform>/<name>) is what
# a custom extension_directory still requires, verified by the 089 §7.3 spike on this exact
# base image with --network none. `|| exit 1` because /bin/sh runs this loop without
# `set -e`: a failed curl must fail the build, not just one iteration.
ARG DUCKDB_CORE_VERSION=1.5.5
RUN mkdir -p /opt/duckdb/extensions/v${DUCKDB_CORE_VERSION}/linux_amd64 && \
    for ext in httpfs aws iceberg avro; do \
      curl -fsSL "https://extensions.duckdb.org/v${DUCKDB_CORE_VERSION}/linux_amd64/${ext}.duckdb_extension.gz" \
        | gunzip > "/opt/duckdb/extensions/v${DUCKDB_CORE_VERSION}/linux_amd64/${ext}.duckdb_extension" || exit 1; \
    done

# The knob the lake adapter reads (application.yml datapipelines.duckdb.extension-directory):
# present here so a bare `docker run` of this image is LOAD-only out of the box. Compose's
# pass-through defaults to the same path (deploy/compose.yml).
ENV DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions

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
