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
# The platform directory follows the build's TARGETARCH (BuildKit fills it): `docker build`
# on Apple Silicon produces a linux/arm64 image, whose DuckDB looks for linux_arm64
# extensions — hard-coding linux_amd64 here made the runtime LOAD-only against an EMPTY
# directory on arm64 (found by the 089 live gate: pool init failed with
# ".../v1.5.5/linux_arm64/httpfs.duckdb_extension not found"). The layout
# (<dir>/v<ver>/<platform>/<name>) is what a custom extension_directory still requires,
# verified by the 089 §7.3 spike on this exact base image with --network none.
# `|| exit 1` because /bin/sh runs this loop without `set -e`: a failed curl must fail the
# build, not just one iteration.
ARG DUCKDB_CORE_VERSION=1.5.5
ARG TARGETARCH
RUN case "$TARGETARCH" in \
      amd64) dp=linux_amd64 ;; \
      arm64) dp=linux_arm64 ;; \
      *) echo "unsupported TARGETARCH for the DuckDB extension bundle: '$TARGETARCH'" >&2; exit 1 ;; \
    esac && \
    mkdir -p /opt/duckdb/extensions/v${DUCKDB_CORE_VERSION}/$dp && \
    for ext in httpfs aws iceberg avro; do \
      curl -fsSL "https://extensions.duckdb.org/v${DUCKDB_CORE_VERSION}/$dp/${ext}.duckdb_extension.gz" \
        | gunzip > "/opt/duckdb/extensions/v${DUCKDB_CORE_VERSION}/$dp/${ext}.duckdb_extension" || exit 1; \
    done

# The knob the lake adapter reads (application.yml datapipelines.duckdb.extension-directory):
# present here so a bare `docker run` of this image is LOAD-only out of the box. Compose's
# pass-through defaults to the same path (deploy/compose.yml).
ENV DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions

# The JavaScript engine's isolate (#8, transform-nodes design §4.4, D-T16): GraalJS runs author
# scripts inside a polyglot ISOLATE, a native library with its own heap, so the jar carries the
# polyglot API and the isolate's two bridge dependencies but never the binary itself — one
# platform's isolate is ~62 MB compressed, and a jar holding all four would carry ~245 MB of which
# a container uses one. The image fetches the one its TARGETARCH needs from Maven Central and
# checks it against the pinned sha256 (Maven Central publishes the sums; the ARG pins them so a
# swapped artifact fails the build at `sha256sum -c`, not at runtime). The version ARG is the
# catalog's `graalvm` version (gradle/libs.versions.toml): a polyglot upgrade re-pins the version
# AND both sums here, and DockerfileGraalJsPinTest fails until it does. A jar run without this
# directory on its loader path reports JavaScript unavailable; nothing else changes.
ARG GRAALJS_VERSION=25.4.4.1.1
ARG GRAALJS_SHA256_LINUX_AMD64=8a892a57d16eaac0460b413d0773751a0f3e4ce7714029a40d957220675acc72
ARG GRAALJS_SHA256_LINUX_AARCH64=94a8529711a72678b219104a13c2875b25717dfbff1f109685430e2061899dde
RUN case "$TARGETARCH" in \
      amd64) gp=linux-amd64; sum=$GRAALJS_SHA256_LINUX_AMD64 ;; \
      arm64) gp=linux-aarch64; sum=$GRAALJS_SHA256_LINUX_AARCH64 ;; \
      *) echo "unsupported TARGETARCH for the GraalJS isolate: '$TARGETARCH'" >&2; exit 1 ;; \
    esac && \
    mkdir -p /opt/graaljs && \
    curl -fsSL -o /opt/graaljs/js-isolate.jar \
      "https://repo1.maven.org/maven2/org/graalvm/js/js-isolate-$gp-community/${GRAALJS_VERSION}/js-isolate-$gp-community-${GRAALJS_VERSION}.jar" && \
    echo "$sum  /opt/graaljs/js-isolate.jar" | sha256sum -c -

# The isolate directory the engine reports in its availability reason (8b binds the key; until
# then the variable is read by nothing and harmless).
ENV DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR=/opt/graaljs

COPY modules/app/build/libs/datapipelines-*.jar /app/datapipelines.jar
COPY modules/app/build/generated/third-party-notices/THIRD-PARTY-NOTICES /app/THIRD-PARTY-NOTICES

# The JDBC driver drop-in (module-structure.md §5.4.1): the jar's PropertiesLauncher adds
# every jar in LOADER_PATH to the classpath, so an operator mounts ojdbc11.jar or
# mysql-connector-j.jar here with no rebuild (deployment.md §3.5). ABSOLUTE on purpose: a
# relative entry resolves against loader.home, which defaults to the working directory, and
# this image sets no WORKDIR, so `lib` would mean /lib, the OS library directory. Created
# root:root 0555 BEFORE `USER`: a directory the app user could write would be
# code-execution-by-file-drop (deployment.md §9, the lib/ checklist line). The value lives
# here, never in compose or the chart (ImageLoaderPathSpecDriftTest refuses a deploy file naming it).
# The second entry is the GraalJS isolate directory above (#8): ONE list, so the drop-in and the
# isolate share a single source of truth — a `-Dloader.path` on the command line would REPLACE
# this variable, not extend it (PropertiesLauncher reads the system property first; measured in
# lane 8a), and the drop-in would silently vanish.
RUN mkdir -p /app/lib && chmod 0555 /app/lib
ENV LOADER_PATH=/app/lib,/opt/graaljs

# Pre-install the isolate's native resources (D5) as root, at build time: on its first Engine,
# polyglot unpacks the isolate library and libtruffleattach into a resource cache, by default
# under the user's home, which a read-only root refuses. JavaScriptResourcesMain points that
# cache at /opt/graaljs/resources and starts one Engine through the engine's own factory, so
# polyglot's installer writes the cache in its own layout (Engine.copyResources is NOT used: it
# copies the isolate but not libtruffleattach, which HotSpot needs — measured in lane 8a) and the
# build fails here if the isolate cannot start. The ENTRYPOINT points the same property at the same
# directory. The cache stays root-owned and is made readable by all and writable by none:
# polyglot creates its per-resource directories 0700, which the app user cannot read, and a
# cache it cannot read is one it tries to re-install into (measured in lane 8a: refused, the
# Engine fails). So nothing is written at runtime, and the app user cannot replace a library it
# loads (the /app/lib reasoning above). The chmod names the cache, not /opt/graaljs: touching the
# fetched jar's mode here would copy all 62 MB of it into this layer a second time (measured in
# lane 8a); the jar is already root-owned 0644 from the fetch, readable and not writable by the app.
RUN java -Dloader.main=co.datapipelines.scripting.JavaScriptResourcesMain -cp /app/datapipelines.jar \
      org.springframework.boot.loader.launch.PropertiesLauncher /opt/graaljs/resources && \
    chmod -R a+rX,a-w /opt/graaljs/resources

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

# The resource cache JavaScriptResourcesMain filled at build time (above); no other runtime flag
# for the engine — the isolate reaches the classpath through LOADER_PATH.
ENTRYPOINT ["java", "-Dpolyglot.engine.userResourceCache=/opt/graaljs/resources", "-jar", "/app/datapipelines.jar"]
