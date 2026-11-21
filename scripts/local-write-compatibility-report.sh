#!/usr/bin/env bash
#
# local-write-compatibility-report.sh — the offline pre-upgrade compatibility report of the
# single-database pipeline transaction (docs/dag-executor.md §16.7, #1036).
#
#   SPRING_DATASOURCE_URL=jdbc:postgresql://<metadata-host>:5432/datapipelines \
#   SPRING_DATASOURCE_USERNAME=<read-only role> SPRING_DATASOURCE_PASSWORD=<its password> \
#     ./scripts/local-write-compatibility-report.sh [path/to/datapipelines-app.jar]
#
# Runs the report class from the packaged application jar (default
# modules/app/build/libs/datapipelines-app.jar, or $DATAPIPELINES_APP_JAR) WITHOUT starting
# the application: no scheduler, sweeper or executor, no customer connection. It reads the
# metadata store through one read-only session and prints one line per current,
# schedule-pinned or API-pinned pipeline version, then a SUMMARY line. Exit 0 = report produced; 1 = it
# could not be (the reason on stderr, without the URL); 2 = usage.
#
# The connection settings are read from the environment ONLY — never from arguments — so the
# password never appears in a process listing or shell history. The same variable names the
# application reads; DATAPIPELINES_PIPELINES_MAX_COMPOSITION_DEPTH is honoured when set.
set -euo pipefail

main() {
    if [[ $# -gt 1 || ${1:-} == "-h" || ${1:-} == "--help" ]]; then
        sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'
        return 2
    fi
    local root jar
    root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    jar="${1:-${DATAPIPELINES_APP_JAR:-$root/modules/app/build/libs/datapipelines-app.jar}}"
    if [[ ! -f $jar ]]; then
        echo "local-write-compatibility-report: no application jar at $jar (build it: ./gradlew :modules:app:bootJar)" >&2
        return 2
    fi
    if [[ -z ${SPRING_DATASOURCE_URL:-} ]]; then
        echo "local-write-compatibility-report: SPRING_DATASOURCE_URL is not set" >&2
        return 2
    fi
    java -Dloader.main=co.datapipelines.application.compatibility.LocalWriteCompatibilityCli -jar "$jar"
}

main "$@"
exit $?
