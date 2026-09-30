# Non-JVM exception and failure-suppression inventory

Snapshot: working tree at `e4cd69e2` on 2026-09-28. Read-only static analysis; no failures injected, applications run, or tests executed. This inventory is evidence for a larger audit, not an issue tracker or proof of runtime impact.

[Main report](2026-09-28-exceptions-and-telemetry-audit.md).

## Scope and method

Selected 340 tracked files: JS/MJS/CJS/TS/TSX/JSX/Python/shell/HTML/YAML/BAT plus Dockerfile, gradlew, and .githooks/pre-commit. Excluding six vendored JavaScript libraries and two Gradle-generated wrappers leaves **332 first-party files**: 39 JS, 46 MJS test files, 5 Python, 70 shell, 148 HTML, 22 YAML, Dockerfile, and the pre-commit hook. There are no tracked TS/TSX/JSX/CJS files in this selection. A separate root sweep of tracked Kotlin build scripts and buildSrc production code found no catch/runCatching sites; the build version Provider.getOrElse is not an exception handler.

Searches: `git grep -n -E '\bcatch\b|\bexcept\b|\|\| true|\|\| :'` over selected extensions; a second all-tracked-files search excluding JVM/docs/data/vendored assets checked omissions; inspected `.then` rejection arguments, `onerror`, `unhandledrejection`, `allSettled`, and `contextlib.suppress`. Read surrounding code and key callers. No additional inline HTML catch clauses or MJS test catch clauses were found (their lexical matches are comments/text). HTML is included, not assumed safe. Excluded generated/build/dependency outputs are not a substitute for inspecting their first-party sources. Generated Gradle wrappers had only license-comment matches.

Excluded vendor files: `modules/web/src/main/resources/static/vendor/{alpinejs/alpine.min.js,cytoscape/cytoscape-dagre.js,cytoscape/cytoscape-node-html-label.js,cytoscape/cytoscape.min.js,dagre/dagre.min.js,htmx/htmx.min.js}`. Excluded wrappers: `gradlew`, `gradlew.bat`. No tracked `node_modules`, `build`, or `dist` trees were included. External library internals and uncaught exceptions are outside this catch-site inventory. Shell status handling is a static candidate scan, not a complete shell control-flow proof.

**46 actual first-party JavaScript catch clauses**, plus **3 explicit Promise rejection handlers** passed as the second `.then` argument. The original grep returned 48 JS lines; the two extra matches are a rail comment and the string `catch-up`.

Classification: **Silent** = failure/cause discarded with consequential state or no degraded-state signal; **Fallback** = explicit recovery/default/optional behavior, generally reasonable but cause not recorded; **Reported** = visible error/log or failure status; **Mixed** = classification depends on failure or caller. None of these labels implies all catches must rethrow. Many fallbacks should remain, with narrowed handling and an explicit observability policy.

## Findings that deserve the closest follow-up

- `pipeline-editor/sse.js:455`: cancellation's DELETE rejection is swallowed; HTTP responses are not checked at all. After either outcome the code schedules aborting the stream reader after five seconds. This can disconnect the browser from a still-running execution without explaining that cancellation failed. Verify real network/403/500 cancellation behavior before choosing remediation.
- `pipeline-editor/draft.js:27`: invalid server-rendered lifecycle JSON becomes `hasDraft:false`; initialization sets `window.PEDraft=null`. `executeVersion()` and the run path use that to omit the draft pin and use the released default. This converts malformed state into a different execution target.
- `schedules/api.js:87`: malformed response JSON becomes null. A 2xx response returns `data:null`, so list consumers can render empty state and lose malformed-response evidence; non-2xx still throws a generic API error.
- `schedules/detail.js:366`: run refresh failures only reschedule polling. While an active run remains in stale state, retries continue without a connectivity/degraded-state message. Manual refresh failures with no active run do not retry because `schedulePoll()` returns.
- `scripts/sample-data-trade/transform.sh:43`: discards every DuckDB process failure and treats output containing the exact substring `Error` as the sole error signal. A nonzero exit without that substring (e.g. a signal/crash) can make the helper return success. Later SQL/artifact operations may reveal it, but this boundary loses the original verdict.
- `scripts/sample-data-lake/verify.sh:143`: ignores `diff` status and checks only stdout. A diff error with empty stdout can reach PASS; process substitution producer exit codes are not propagated. The displayed equality is not proof that both producers succeeded.
- `scripts/test-gap-audit.py:40`: unreadable test files vanish from the population via `except OSError: pass`, with no unreadable count. The audit can overstate test-reference gaps. It also uses a historical hard-coded ROOT; it was not run here.

## JavaScript complete catch/rejection inventory

Paths below are relative to `modules/web/src/main/resources/static/`.

| Location | Class | Behavior / consequence |
|---|---|---|
| `js/csrf.js:25` | Fallback | Invalid URI encoding returns the raw cookie; server remains token authority. |
| `js/data-table.js:462` | Fallback | Ignores setPointerCapture failure, documented for synthetic events; dragging proceeds without capture. |
| `js/pipeline-editor/draft.js:27` | Silent | Malformed lifecycle JSON becomes no draft; affects run version selection, as above. |
| `js/pipeline-editor/graph.js:235` | Fallback | Canvas color sampling failure returns null; caller retains raw design token. |
| `js/pipeline-editor/graph.js:1214` | Fallback | Datasource dialect enrichment failure leaves source name only, no diagnostic. |
| `js/pipeline-editor/graph.js:1250` | Fallback | Template pin lookup failure returns null; cards/details omit resolved language/template metadata. |
| `js/pipeline-editor/init.js:63` | Fallback | Promise rejection handler: clipboard API failure invokes legacy copy. |
| `js/pipeline-editor/init.js:83` | Silent | Legacy copy exception leaves button unchanged; no failure feedback. Also calls success without checking execCommand's Boolean result. |
| `js/pipeline-editor/init.js:292` | Reported | console.error includes initialization exception; no user-facing initialization failure in this catch. |
| `js/pipeline-editor/result.js:185` | Reported | console.error includes page-load exception; current result UI remains without a catch-provided failure message. |
| `js/pipeline-editor/sse.js:79` | Mixed | AbortError intentionally ignored; other preterminal stream failures enter handleConnectionLoss; postterminal teardown failures ignored. Original cause not recorded here. |
| `js/pipeline-editor/sse.js:145` | Mixed | Same policy for reader/pump rejection; connection-loss path provides recovery/banner. |
| `js/pipeline-editor/sse.js:163` | Fallback | Invalid JSON event becomes raw data and is sent to event log; malformed protocol cause not diagnosed separately. |
| `js/pipeline-editor/sse.js:455` | Silent | Cancel DELETE rejection ignored, response status unchecked, reader later aborted. |
| `js/pipeline-editor/sse.js:565` | Reported | Poll retries are bounded; exhaustion produces connection-lost banner, no original cause. |
| `js/rail.js:24` | Fallback | localStorage read refused: use default rail state; explicitly documented private-mode tolerance. |
| `js/schedules/api.js:87` | Mixed | Invalid body on non-2xx still becomes API error; invalid 2xx body becomes successful null data. |
| `js/schedules/api.js:92` | Reported | Promise rejection handler rethrows normalized networkError(cause). |
| `js/schedules/detail.js:91` | Reported | Error pane for active view; obsolete/off-page response intentionally ignored. |
| `js/schedules/detail.js:229` | Fallback | Failed pipeline-link resolution uses explorer link. |
| `js/schedules/detail.js:327` | Fallback | Failed execution-duration enrichment leaves ellipsis; execution page remains available. |
| `js/schedules/detail.js:366` | Silent | Refresh failure reschedules polling only; no freshness/connectivity status. |
| `js/schedules/detail.js:383` | Reported | Older-runs failure toast. |
| `js/schedules/detail.js:423` | Reported | Pause/resume/simple action resets busy state and shows failure toast. |
| `js/schedules/detail.js:465` | Reported | Run-now failure toast; preserves unanswered network attempt for idempotent retry. |
| `js/schedules/detail.js:504` | Reported | Delete failure shown in dialog, including revision conflict. |
| `js/schedules/dom.js:134` | Fallback | Refused history.replaceState loses deep link only. |
| `js/schedules/explorer.js:152` | Reported | Active tree gets error block; abandoned page ignored. |
| `js/schedules/explorer.js:193` | Reported | Folder load gets error/retry state; detached folder ignored. |
| `js/schedules/form.js:48` | Fallback | Intl timezone discovery failure defaults to UTC, shown in form selection. |
| `js/schedules/form.js:95` | Reported | Edit-open failure toast. |
| `js/schedules/form.js:238` | Reported | Preview failure becomes field or preview-status message; stale response ignored. |
| `js/schedules/form.js:285` | Fallback | Suggestion search failure ignored; saving remains server-validated. Missing suggestions unannounced. |
| `js/schedules/form.js:375` | Reported | Parameter read failure renders explanation, retaining values; save still validates. |
| `js/schedules/form.js:603` | Reported | Save conflict/field/global feedback; closed dialog ignored; unanswered network attempt retained. |
| `js/schedules/model.js:178` | Fallback | Intl localText failure displays ISO timestamp. |
| `js/schedules/model.js:197` | Fallback | Occurrence formatting failure displays original wall-clock text. |
| `js/schedules/model.js:215` | Fallback | Compact date formatting failure displays ISO timestamp. |
| `js/schedules/model.js:338` | Fallback | JSON formatting failure falls back to String(value). |
| `js/schedules/run.js:62` | Reported | Run read failure shows messages-note; closed dialog ignored. |
| `js/schedules/run.js:101` | Fallback | Failed execution-duration enrichment ignored; Messages remain execution authority. |
| `js/schedules/run.js:166` | Reported | Event read failure renders scheduler trail plus explicit missing/expired pipeline-message notice. |
| `js/schedules/run.js:203` | Fallback | Intl clock formatting failure displays ISO timestamp. |
| `js/shell.js:621` | Fallback | localStorage preference save failure ignored; state applies for current page. |
| `js/splitter.js:141` | Fallback | Storage read failure returns null; stylesheet pane size applies. |
| `js/splitter.js:151` | Fallback | Storage write/remove failure ignored; pane size applies but is not remembered. |
| `js/splitter.js:342` | Fallback | releasePointerCapture failure ignored as already released; drag cleanup continues. |
| `js/template-editor/lifecycle.js:174` | Reported | Preview error card; finally restores button/spinner. |
| `site/js/site.js:213` | Fallback | Promise rejection invokes text-selection fallback, button says Select and copy. |

Console reporting does not itself establish shipment to a backend. Browser-only failures need a separate collection boundary if centralized visibility is required. Rethrowing optional storage errors would harm graceful behavior; review their intent separately from operational failures.

## Python complete catch inventory (including embedded Python in shell)

| Location | Class | Behavior / consequence |
|---|---|---|
| `app.sh:413` | Fallback | Compose JSON array parse falls back to newline-delimited JSON; malformed fallback still fails. |
| `app.sh:549` | Fallback | Same Compose compatibility parser; outer shell produces unknown state, which loader verifier treats as failed. |
| `deploy/sample-data/check-baselines.sh:55` | Reported | KeyError/ValueError becomes explicit SystemExit explaining unreadable pipeline list. |
| `deploy/sample-data/check-baselines.sh:76` | Mixed | Invalid SSE JSON line skipped; no-terminal-event result fails, but nonterminal corruption may go unnoticed when a valid terminal event follows. |
| `scripts/lib/gate-stages.sh:59` | Mixed | Malformed test-result XML skipped while counting genuine test failures; documented partial-crash tolerance. Other crash/exit/network classifiers govern gate, but omitted XML has no individual signal. |
| `scripts/measure/04-three-source-pipeline.sh:92` | Mixed | Any Exception parsing polling response becomes empty status and retry; final refetch is explicitly parsed and diagnosed. Transient parse causes discarded. |
| `scripts/site-lighthouse.sh:92` | Reported | Any file-open OSError becomes HTTP 404; caller sees failure but permissions/I/O errors misclassified as not found. Local static test server only. |
| `scripts/test-gap-audit.py:40` | Silent | Unreadable test file ignored entirely, no completeness warning. |

## Shell complete literal suppression inventory

Grouped rows name every discovered executable `|| true` site; diagnostics are not equivalent to error handling. Missing-match grep exit 1 often intentionally requires normalization, but `|| true` also catches I/O/tooling failures unless separately checked.

| Location(s) | Class | Behavior / consequence |
|---|---|---|
| `app.sh:66` | Fallback | Missing dotenv key is empty/default; actual read error also suppressed. |
| `app.sh:433` | Fallback | Bootstrap user query unavailable: print configured-file login guidance instead; DB failure details suppressed. |
| `app.sh:506` | Reported | Log-dump failure ignored, then app-not-running dies explicitly. |
| `app.sh:568` | Reported | Failed read becomes unknown loader state, added to failed-loader list. |
| `app.sh:587` | Reported | Log-dump failure ignored, but loader failure reported and exits nonzero. |
| `app.sh:631` | Mixed | Compose up exit ignored; explicit app health and demo loader checks run next. Does not prove every Compose service healthy if outside those checks. |
| `deploy/sample-data/load.sh:41` | Mixed | Attempts pipefail compatibility; if shell permits fallback, missing pipefail can hide earlier pipeline errors. Supported bash/ash are intended to provide it. |
| `deploy/sample-data/load.sh:59` | Fallback | Shift guarded after required arguments validated. |
| `deploy/sample-data/load.sh:302,390` | Mixed | Missing version marker intentionally triggers restore; any database probe failure also looks like no marker (original cause lost). Restore itself remains fallible. |
| `deploy/sample-data/load.sh:328` | Mixed | Failed role-existence query treated as absent, chooses CREATE rather than ALTER; later SQL may fail, original probe cause lost. |
| `deploy/sample-data/selftest.sh:59,60,61` | Fallback | EXIT cleanup kill/container/network removal best effort, failures silent. |
| `deploy/sample-data/selftest.sh:228` | Silent | MySQL role probe error becomes empty result, potentially satisfies role-absent negative assertion. |
| `deploy/sample-data/selftest.sh:287,290,336,347,350,352` | Reported | Probe failure becomes empty value which positive equality assertion rejects. |
| `deploy/sample-data/selftest.sh:326,328` | Silent | Failure to delete version markers ignored; intended interrupted-restore setup may not actually occur, yet subsequent reload assertions may pass. |
| `scripts/app-sh-clean-test.sh:77` | Reported | Missing grep output checked immediately and fails named assertion. |
| `scripts/gate.sh:91` | Mixed | No matching build process intentionally zero; pgrep failure also looks like zero, weakening concurrent-build warning. |
| `scripts/gate.sh:290,313` | Reported | Diagnostic excerpt failure ignored; existing skipped/failing stage verdict retained. |
| `scripts/lib/docker-stub.sh:71` | Fallback | Docker command lookup absent yields empty path; test stub defaults to refusal, no real-command fallback. |
| `scripts/lib/scan-tools.sh:221,226` | Reported | Manifest/hash read errors become empty values, checked by named security failure exits. |
| `scripts/sample-data-lake/check-published.sh:54` | Fallback | Missing dotenv key normalized to empty/default; read failure not distinguished. |
| `scripts/sample-data-lake/transform.sh:227` | Mixed | grep removing the sole existing row may exit 1 legitimately; actual read/write error also suppressed before temp file replaces counts file. |
| `scripts/sample-data-lake/transform.sh:307` | Fallback | Optional final disk-size listing failure ignored. |
| `scripts/sample-data-lake/verify.sh:143` | Silent | diff error ignored; empty stdout treated as counts match, producer failures not awaited/validated. |
| `scripts/sample-data-trade/transform.sh:43` | Silent | Any DuckDB exit discarded; only output text Error triggers failure. |
| `scripts/sample-data/check-published.sh:51` | Fallback | Missing dotenv key normalized to empty/default; read failure not distinguished. |
| `scripts/sample-data/lib/engines.sh:39` | Fallback | Cleanup removal silently best effort. |
| `scripts/sample-data/lib/engines.sh:50,80,112` | Fallback | Pre-start container removal best effort; subsequent docker run can reveal collision. |
| `scripts/sample-data/lib/engines.sh:72,101,126` | Reported | Log-dump failure ignored but readiness timeout dies explicitly. |
| `scripts/security-mutations/run.sh:55` | Reported | grep count helper supports zero matches; swap checks exact expected count and rejects invalid result. |
| `scripts/site-lighthouse.sh:59` | Fallback | EXIT server kill best effort. |
| `scripts/sync-design-system.sh:105` | Mixed | comm failure suppresses curated-surface extra-file announcement. |
| `scripts/sync-design-system.sh:143,144,169,170` | Reported | comm used only for details after arrays differ; drift exit still occurs. |
| `scripts/verify-image-pins.sh:44` | Reported | Zero grep count gets explicit no-image-pins failure. |
| `scripts/vuln-scan.sh:113` | Reported | Empty/failed checksum compared with pinned digest, named failure exit. |
| `tests/integration-tests/multi-instance/run-pool-invalidation.sh:51` | Fallback | EXIT Compose teardown best effort. |
| `tests/integration-tests/multi-instance/run-pool-invalidation.sh:174,202` | Fallback | Optional log-count display tolerates absent event. |
| `tests/integration-tests/multi-instance/run-pool-invalidation.sh:203` | Mixed | Normalizes grep zero-match status inside negative assertion; docker logs remains outside the normalization, subject to shell context. |
| `tests/integration-tests/multi-instance/run-pool-retire.sh:70` | Fallback | EXIT Compose teardown best effort. |
| `tests/integration-tests/multi-instance/run-pool-retire.sh:120` | Reported | Missing CSRF extraction checked and page dumped on failure. |
| `tests/integration-tests/multi-instance/run-pool-retire.sh:229` | Reported | SSE reader exit ignored; data_ready, persisted SUCCESS, and row-value assertions verify result. |
| `tests/integration-tests/multi-instance/run-pool-retire.sh:240,241` | Fallback | Optional diagnostic log tails tolerate no matching events. |
| `tests/integration-tests/multi-instance/run-pool-retire.sh:244` | Mixed | Whole log pipeline normalized; a failed docker logs can yield grep count zero and satisfy no-hard-close assertion. Later positive event assertion still offers partial protection. |
| `tests/integration-tests/multi-instance/run.sh:50` | Fallback | EXIT Compose teardown best effort. |
| `tests/integration-tests/multi-instance/run.sh:209,261` | Fallback | SSE process exit ignored during shutdown/crash tests; persisted status/events decide outcome. |
| `tests/integration-tests/multi-instance/run.sh:210` | Fallback | Optional SSE-tail display ignored. |
| `tests/integration-tests/multi-instance/run.sh:213,215,216,217` | Reported | Log acquisition/extraction normalized; later drain verdict requires marker presence/order. |
| `tests/integration-tests/multi-instance/run.sh:264` | Reported | Missing sweep event extraction subsequently fails sweep verdict. |
| `tests/integration-tests/multi-instance/run.sh:267` | Fallback | Normalized shutdown event count is printed as diagnostic, not by itself an acceptance assertion. |

Other explicit shell recovery worth distinguishing: `app.sh:555` converts parser/process failure to `unknown 0`, handled as failed loader; `scripts/lib/gate-stages.sh:50` turns Python classifier failure into genuine-failure count 1 (fail closed); expected command probes in `if`/`case` are not all enumerated here. This does not claim every shell nonzero exit anywhere in the repository is covered.
