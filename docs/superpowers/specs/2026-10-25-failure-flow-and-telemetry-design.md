# Failure flow and telemetry — the design record (draft 3 — core scope, 2026-10-25 01:14 UTC, orchestrator; draft 1 00:02 UTC, draft 2 00:07 UTC after the builder check)

**Owner's intent (2026-10-24, verbatim where quoted):** "a solid telemetry, logging, exception handling and notification flow which every
mature enterprise has … configurable for different sink destinations and … also work for small orgs which dont have top of the line
sinks." Purpose: to answer a prospective organization's question — *"what will happen when something goes wrong in production?"* — with
a demonstrable, documented flow: how a problem is caught, classified, logged, related to everything else that happened, found by search,
and how the right person is told. Not a production incident response to date (the product is in development, open source); a sales
and trust asset first, an operations asset second. "I dont care if it takes days to implement."

**Scope ruling (owner, 2026-10-25 ~01:0x UTC, after reading draft 2 and my assessment):** "Lets not build this much. Lets build the core,
at app level and may be notification level. It should just be able to hook up with any platform and thats it." Draft 3 is that cut: the
app-level flow (record, log, metrics) and notifications (mail, webhook, in-app) ship; tracing/OTLP, a shipped observability stack,
per-destination verification and the tenant export are DEFERRED (§12) with what each would take.

**How this record is used.** Draft 1 was written without owner questions, at the owner's word ("skip the questions, start drafting with
your assumptions as calls"). Every choice the owner did not make is marked **[CALL]** and collected in §17 for ratification; the owner
rules row by row (the #397 §17 method); a ruled row becomes **RULED (date)**. Until §17 is ratified nothing dispatches. After ratification
the record moves to the product repo as `docs/superpowers/specs/2026-10-25-failure-flow-and-telemetry-design.md` in the first lane's
commit, and `docs/observability.md` is CORRECTED to match it (not extended beside it). Every file:line below was read on local main
`0d4ddd5b5` on 2026-10-24/25 unless marked otherwise.

## 0. What ships, and what does not (scope ruled by the owner 2026-10-25 ~01:0x UTC: "Lets build the core, at app level and may be notification level. It should just be able to hook up with any platform and thats it.")

Ships: **one failure pipeline — classify → record → log → measure → notify — complete inside the app**, plus the three standard
protocols every observability platform already consumes. No vendor SDK, no shipped observability stack, no tracing (deferred, §12).

| Layer | What the app does | How any platform hooks in |
|---|---|---|
| **Record** | every failure becomes one `failure_records` row with a `failure_id` (§5); the **Problems** page (§9) shows them without any external tool | the database; `GET /api/v1/problems` |
| **Log** | one JSON line per event on stdout, the ten identity fields (§6) as top-level members, redacted | any shipper that tails container stdout: Grafana Alloy / Promtail, Fluent Bit, Vector, the Datadog agent, Splunk's forwarder, Filebeat — filter on `failure_id`, `execution_id`, `correlation_id` |
| **Measure** | Micrometer meters, Prometheus text on the management port (`/actuator/prometheus`, §7) | Prometheus, the OpenTelemetry Collector's `prometheus` receiver, Datadog's OpenMetrics check, Elastic's Prometheus module, a `ServiceMonitor` |
| **Notify** | mail and one signed webhook JSON per (grouped) failure (§8); in-app unseen count | any webhook receiver: Slack, Teams, PagerDuty, Opsgenie, Alertmanager's webhook receiver, n8n, a Lambda |

One deployment shape, not tiers: the app alone is complete (a small org with Postgres and nothing else gets the whole flow), and a larger
org points its existing shipper, scraper and chat/pager at the three surfaces above. `docs/observability.md` gains one page, "Connecting
your platform", with worked configuration snippets for three shippers (Alloy, Fluent Bit, Datadog agent) — snippets, not shipped compose.

Does NOT ship (each is listed in §12 DEFERRED with what it would take): distributed tracing and any OTLP export; a shipped Collector /
Loki / Tempo / Prometheus / Grafana compose with dashboards and alert rules; verified Collector pipelines per destination; a tenant-scoped
telemetry export (#270's second half); an MCP `problems_list` tool; the silent-catch classification sweep (§4.3); vendor paging SDKs; a
log search UI of our own (the Problems view is a record browser).

## 1. Why now, and the bar

The current state is a document ahead of its code: observability.md promises a Prometheus endpoint (§4.2) with no registry on the
classpath (#708), a counter nothing increments (#568), tracing "declared and not wired" (§9 table), while the real pieces that landed
(#137 mail notices, #266 audit durability, #336 silent-catch remediation, #337 JSON logging + redaction, 442a/442b schedule
notifications) are each sound and each an island. The bar for this record: **a reader (a prospective org's platform engineer) can follow
one failure from the line that threw it to the person who was told, through every artifact, with the ids that join them — and the test
suite proves each hop; and any platform the org already runs can ingest it by configuration alone.** The demo (§15) is the acceptance test.

## 2. Principles, goals, non-goals, anti-goals

Principles (each is a rule a test can check):
1. **Classify once, at the boundary that owns the failure.** An exception becomes a *failure record* exactly once: at an HTTP backstop, an
   MCP tool boundary, a node/execution boundary, a scheduler job boundary, a dashboard refresh boundary, a housekeeping job boundary, or
   the mail/notification sender boundary. Every boundary is enumerated (§4); a new boundary is a code change with a row.
2. **One failure, one id, many artifacts.** The failure record's id (`failure_id`, §5) is written into the log line, the notification,
   the Problems page and the API/UI error response (next to the correlation id).
   Search by any one and you reach all.
3. **Correlation is carried, never recomputed.** The request's correlation id (today's UUID `CorrelationId`, §3) is never re-minted
   downstream; it crosses every coroutine, thread-pool, scheduler and outbox hop (the #337 mechanism, extended to the hops §3 lists as
   missing). A scheduled run's correlation id is its run id and a dashboard
   refresh's is its refresh id, as today (both UUIDs) — the trigger has no id of its own.
4. **Redact before the first byte leaves the process.** Stdout, the database row and notifications pass the same redaction (today's converter, §3),
   applied to message, arguments, MDC, exception messages and causes. A planted secret appears in no sink — the §15 test plants it.
5. **Bounded, never blocking.** Every path has a bound, a counter for what it dropped and a documented behaviour when its sink is down
   (§10): the record has a per-kind cap, notifications a group window and a rate cap, stdout no network. The failure RECORD is written
   first and durably (the #266 pattern, §5); a lost mail is counted, a lost record is a counted defect.
6. **Levels mean something.** ERROR = a failure record was (or should have been) written; WARN = degraded, no record; INFO = lifecycle;
   DEBUG = diagnostics. A guard test fails the build on `log.error` outside a classified boundary (§13).
7. **Tags from closed sets** (observability §4.3, kept): never class names, ids of unbounded population or input text.
8. **Config over code:** every bound, notification route, recipient and severity threshold is configuration under
   `datapipelines.observability.*` / `datapipelines.notifications.*` or Spring Boot's own `management.*` / `logging.*` keys, each documented
   in configuration.md with a default that works for the app alone.
9. **The document is the contract and the test is the proof:** observability.md describes only what exists; the §13 guards fail when
   the two drift (meter list, event catalogue, failure-kind catalogue, config keys).

Goals: the §15 demo passes with the app and Postgres alone; every §4 boundary writes a failure record; every record reaches the Problems
view, the log, the metric and the configured notification route; a platform engineer can connect their existing shipper, scraper and
pager from the "Connecting your platform" page without reading code.

Non-goals: APM-grade profiling; SLO tooling; business analytics of failures; replacing the audit log (observability §7: who did what —
compliance; untouched here beyond carrying the failure id).

Anti-goals (failure even if it works): a telemetry outage that slows or fails a request or an execution; a notification storm (§8 has
grouping and rate bounds); a secret in any sink; a tag that explodes cardinality; a vendor dependency in `modules/*`; a hook-up that needs anything
but configuration on the platform side.

## 3. Current state — logging, correlation, metrics, health, notifications, deployment (inventories 2026-10-24/25)
Read by a fenced inventory subagent on local main 0d4ddd5b5 (2026-10-24 ~23:3x UTC); every file:line below was opened in that run. Nothing under `.claude/worktrees/` was read.

### 3.1 Logging today (what #337 built, merge `1f00f1bd9` 2026-09-30; follow-ups f872503e7, 1f71cce0f, c8e9d48f8, 51a759624)
- One config, one appender: `modules/app/src/main/resources/logback-spring.xml` — `CONSOLE` (`ConsoleAppender`, :14) with a `ThresholdFilter` (:15-17), root INFO (:23-25), includes Boot's `defaults.xml` (:10). No file appender. The format switch is `<springProperty … name="DP_LOG_FORMAT" source="datapipelines.observability.logging.format" defaultValue="console"/>` (:12) passed as `<format>` (:19).
- The encoder is OURS: `co.datapipelines.logging.FormatSwitchingEncoder` (`modules/app/src/main/kotlin/co/datapipelines/logging/FormatSwitchingEncoder.kt`): `json` delegates to Spring Boot 3.5's own `StructuredLogEncoder` with `setFormat("logstash")` (:74); `console` builds a programmatic `PatternLayoutEncoder` with `REDACTING_CONSOLE_PATTERN` (:76-82, :120-124); unknown value → `error(...)` (:50-54); null → `console` (:39). The JSON is Boot's structured logging in **logstash** shape — NOT the `logstash-logback-encoder` library (no catalog entry; observability.md:42, :611). It is NOT reached through `logging.structured.format.console`; the encoder constructs `StructuredLogEncoder` directly.
- Binding: `ObservabilityLoggingFormatPostProcessor` (an `EnvironmentPostProcessor`, `META-INF/spring.factories:1-2`): `SWITCH_KEY = "datapipelines.observability.logging.format"` (:49), `DEFAULT_FORMAT = "json"` (:52); for `json` it adds (:64-77) `logging.structured.json.customizer=co.datapipelines.logging.RedactingJsonMembersCustomizer`, `logging.structured.json.stacktrace.printer=co.datapipelines.logging.RedactingStackTracePrinter`, `logging.structured.json.rename.logger_name=logger`, `…rename.thread_name=thread`, `spring.main.banner-mode=off`; any other value refuses startup (:84-88). `application.yml:380-381` `logging.format: ${DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT:json}`; `:378-379` `tracing.enabled: ${DATAPIPELINES_OBSERVABILITY_TRACING_ENABLED:false}`.
- **Defaults disagree by shape** (gap): `logback-spring.xml:12` says `console`, the post-processor and yml say `json`; `deploy/env/defaults.env:273` ships `LOGGING_FORMAT=console`; `deploy/compose.yml:219` defaults `json`; Helm passes nothing (→ yml default `json`).
- Redaction, single authority `LogRedactor` (`…/logging/LogRedactor.kt`): `SENSITIVE_KEYS` = password, secret, api_key, authorization, jdbc_url, encryption_key (:30-38); `NEVER_REDACTED` = correlation_id, execution_id, pipeline_id, node_id, datasource_name, user_id (:45-53); `MASK="***"` (:56); `isSensitiveKey` matches whole key and `_key_` compounds on the last dotted segment (:59-70); `scrubText` → `RedactionScanner` single pass over `key=value` and `"key": "value"` shapes, union of overlapping spans (`RedactionScanner.kt:3-41`). Under json: layer 1 `RedactingJsonMembersCustomizer` (message through `scrubText`, sensitive member → `***`, :20-41); layer 2 `RedactingStackTracePrinter` (scrub root-first, then truncate to `MAX_LENGTH=8192` with marker, :28-42). Under console: `RedactingConverter` (`%dpRedact`, :21-25) wraps the correlation fields, `%m`, `%wEx` (FormatSwitchingEncoder :109, :118, :124), registered in `PATTERN_RULE_REGISTRY` by the encoder (:127-137).
- Guards (`modules/app/src/test/kotlin/co/datapipelines/logging/`): `SyntheticPlants.kt` (markers `planted-secret-`, `998877665544`, :25; overlapping escaped-quote plants :34+), `LogRedactorTest`, `RedactionScannerGrammarOracleTest`, `RedactingStackTracePrinterTest`, `FormatSwitchingEncoderTest`, `EncoderOutputTest`, `ObservabilityRedactionSpecDriftTest` (code lists = §9.2 table), `PlantOutputTest` (fresh JVM `PlantMain` on the TEST runtime classpath — not the boot jar, :15-24, :125-145; plants MDC `password`, `password=`/`"api_key":` in messages, `jdbc_url`, exceptions :51-71), `NormalBootOutputTest` (json startup = JSON lines only, banner off, unknown format refused). Gap: no packaged-jar (bootJar) log-format check — `JarSmokeE2eTest` has none.
- Level discipline: §3.2 table (observability.md:64-72; outage logged once :69); §3.4G failure shape (:239, :241 — `cause`/`error` = exception class, `sql_state` bounded, never the message; reused :100, :267, :302); §8.1 (:573-579). Enforced per event by `ObservabilityEventCatalogParityTest` (app; `event=` literals = §3.4 rows, computed names refused), `FailureShapeTest` (persistence), `DatasourceRefusalLogTest`, `SnapshotListAppender*Test` (auth, dag, datasources, web). **No** guard scans source for `log.error` without a throwable or outside a boundary (gap; §13 adds it).

### 3.2 Correlation today
- `CorrelationIdFilter` (`modules/web/src/main/kotlin/co/datapipelines/web/CorrelationIdFilter.kt`, `OncePerRequestFilter`): resolves the header (:38), `MDC.put` (:39), echoes the response header before the chain (:40), `MDC.remove` in `finally` (:44); `ORDER = HIGHEST_PRECEDENCE + 10` (:53); registered container-wide with async support `WebCorsConfiguration.kt:93-105`. Header in/out `DP-Correlation-Id` (`AuthErrorWriter.kt:105`), MDC key `correlation_id` (`AuthErrorWriter.kt:108`); `CorrelationId` object (`web/api/CorrelationId.kt`: `HEADER` :27, `MDC_KEY` :30, `current()/currentUuid()/resolve()` :38-50; `withId` :53-64 test-only). **An inbound value is adopted only if it is a UUID**; otherwise a new UUID is minted.
- **MCP splits the id** (gap): `McpAuthFilter.kt:77-82, :136-139` mints its own UUID into a request attribute, overwrites the response header and puts it in tool `_meta`; it never writes the MDC — so the MDC line and the `_meta` id can differ for one call. Tool lines pass it as a message argument (`McpToolDispatcher.kt:139, :232`).
- Across hops: `modules/dag/src/main/kotlin/co/datapipelines/executor/LogContext.kt` is a custom `ThreadContextElement` snapshotting correlation + execution ids (:44-67; keys :71, :74; written :50-51), installed at `PipelineExecutor.kt:126` and `:343` (per-node deadline scope). Thread pools: a module-local `MdcTask` copy/wrap, **copy-pasted in four modules** (dag `executor/MdcTask.kt` → `CancellationRegistry.kt:383-385`; `scripting/MdcTask.kt` → `ScriptEvaluationPool.kt:174-178`; `parameters/MdcTask.kt` → `SelectorPool.kt:202-206, :261`; `templates/MdcTask.kt` → `TemplateEngine.kt:206-209`, `TemplateBodyParser.kt:129`), nothing keeps them in sync. kotlinx `MDCContext` unused; no Spring `TaskDecorator`. Guards: `LogContextPropagationTest`, `CorrelationMdcKeyDriftTest.kt:16-18`. The deliberately-not-propagated list: observability.md:82.
- **Every MDC key in production code: `correlation_id` and `execution_id`. Nothing else.** Scheduled runs set `correlation_id = admission.runId` (`PipelineJobExecutor.kt:315`); dashboard refreshes set it to `refreshId` (`RecordingSourceStarter.kt:76`). `execution_id` is in the MDC only inside executor coroutines and wrapped pools; elsewhere a message argument (`H2StagingSql.kt:161, :202`) or `execution=` (`ScriptNodeRuns.kt:285`). Workspace id: message argument in ~8 calls (`FamilyRecent.kt:90`, `ScheduleOverviewController.kt:83`), not MDC. Actor: `user_id=` (`AuditLogger.kt:92`, `DemoWorkspaceSeeder.kt:73`), `user=` (`MailNotifier.kt:173`), `key_id=` (`ApiKeyFilter.kt:143`). Node id: only `node=` in script console lines (`ScriptNodeRuns.kt:284`) although §3.1's example (:53-54) and §8.1 (:574) promise `node_id`/`pipeline_id` members — a documented-not-built gap. Schedule id: absent (only indirectly as the correlation id). Dashboard id: absent (`refresh_id` at `RefreshStreamRegistry.kt:121`, `DashboardRuntime.kt:383`). Tool name: message argument only.
- Doc/config state: observability §3 headings :38/:40/:64/:74/:93 (§3.4A–O :107–351), §3.5 :362; §9.1 table :596-600 (`tracing.enabled` :599 "Declared and not wired — the export is #270's deferred part"; `tracing.endpoint` :600 `OTEL_EXPORTER_OTLP_ENDPOINT`); configuration.md §3.15 :239-245 (`tracing.enabled` default false :243; `tracing.endpoint` :244 — NOT in application.yml; `logging.format` :245). `tracing.enabled` has no production consumer (grep of `modules/*/src/main`: none).

### 3.3 Metrics today — 44 meters in code, 13 documented meters with no code
Registry: **none but Boot's `SimpleMeterRegistry`** (fallbacks `EngineConfiguration.kt:143-144`, `AuthConfiguration.kt:316-320`). Deps: `spring-boot-starter-actuator` (app :19, web :35; catalog :224), `micrometer-core` (auth :28, dag :31, scheduler :21, web :45; catalog :319); no Prometheus/OTel/OTLP artifact in any catalog, build file or `modules/app/gradle.lockfile` (which holds micrometer-commons/-core/-jakarta9/-observation 1.15.12 only).
Coded (name → tags → site): `datapipelines.executions.total{status,pipeline_id}`, `.executions.duration{pipeline_id}`, `.executions.aborted{reason}`, `.nodes.duration{pipeline_id,node_id,source}`, `.nodes.rows_out{pipeline_id,node_id}`, `.result.writes{outcome}`, `.result.bytes_written`, `.staging.rows`, `.executions.lifecycle_write_failed`, `.executions.progress_write_failed`, `.executions.concurrent` (gauge) — all `dag/ExecutorMetrics.kt:31-126`, constants :131-141; `.scheduler.occurrences{outcome}`, `.scheduler.runs.finished{state}`, `.scheduler.capacity.retries` (`SchedulerMetrics.kt:21-29`, :33-35); `.scheduler.runs.in_flight` gauge (`web/schedules/SlotCapacityGate.kt:30`, :44); `.sse.streams.active`, `.sse.stream.duration{close_reason}`, `.persistence.queue.depth{store}`, `.persistence.queue.bytes{store}`, `.persistence.batch.size{store}`, `.persistence.batch.duration{store}`, `.persistence.lag{store}`, `.persistence.batches.retried{store}`, `.persistence.dropped{store}`, `.persistence.failures{store,kind}`, `.persistence.fallbacks{store,reason}`, `.idempotency.cache.hits`, `.idempotency.conflicts`, `.result.cursor.reads{format,outcome}` (`web/metrics/WebMetrics.kt:26-137`, constants :141-155); `.datasource.pool.retired{datasource_name}`, `.datasource.pool.hard_closed{datasource_name}` (`MicrometerPoolLifecycleMetrics.kt:22, :34`); `.dashboard.refreshes{status}`, `.dashboard.refresh.duration{status}`, `.dashboard.refresh.refused{reason}`, `.dashboard.refreshes.active`, `.dashboard.executions.reserved` (`dashboards/runtime/DashboardMetrics.kt:19-40`); `.promotion.lens.inventory{outcome}` (`PromotionTargetClient.kt:139`); `.auth.login_rate_limit.saturated` (`LoginRateLimitFilter.kt:70`); `.web.endpoint_key_budget.saturated` (`EndpointKeyBudgetFilter.kt:74` — documented NOWHERE); `.audit.retention.purged` (`AuditLogRetention.kt:70-74`); `.scheduled_jobs.tick_failed{job}` (constant `persistence/ScheduledJob.kt:41`, 8 closed values, registered in 7 places incl. `RetentionSchedulingConfiguration.kt:207-214`); `script.evaluations.abandoned` and `parameters.selectors.abandoned` gauges (`ScriptConfiguration.kt:111-115`, `ParametersConfiguration.kt:154-159` — string literals, and they break §4.1's `datapipelines.` prefix rule :374).
Documented in §4.1 (:378-448) with **zero code**: `staging.bytes`, `staging.active_tables` (:390-391); `datasource.pool.active/.pending/.timeout_total` (:392-394); `templates.render.duration`, `templates.cache.hits/.misses` (:397-399); `mcp.tool.calls` (:406); `auth.login.attempts`, `auth.api_key.validations` (:408-409); `result.expiries`, `result.size` (:420-421); plus §8.1's `errors.total{domain}` (:575) — #568. `http.server.requests` (:400) is Boot's own and will exist once a registry does. Cardinality note: `executions.total`/`.duration`/`nodes.*` carry `pipeline_id` (and `node_id`) — per-pipeline series, bounded by the catalogue size, to be stated when a scraper lands (§10).
Management block `application.yml:80-124`: `server.port ${MANAGEMENT_SERVER_PORT:9090}` (:96-97), `server.address ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}` (:107; comment :98-106: Kubernetes needs 0.0.0.0 + NetworkPolicy), exposure `include: "health"` (:108-113; :112 "/actuator/prometheus joins this list with the metrics registry"), diskspace off (:114-118), db-scheduler health off (:123-124). **The management context sits behind the app's security chain → anonymous 401** (auth.md:1382; `PersistenceBatchingE2eTest:486`); the two tests that read `/actuator/metrics` add it to the include list and authenticate (`AuditDirectByDefaultE2eTest:147-149, :224`; `PersistenceBatchingE2eTest:496-498, :895`). `EntryInventoryE2eTest:144` pins the packaged jar's sockets and the actuator index against auth.md §8.6.1 (:1381-1382: "bound to 127.0.0.1", "/actuator/health · anonymous 401"). `ApplicationSmokeTest:179-183`: `/actuator/*` not 2xx on the app port.
**#231 (open, no comments, `gh issue view 231 --json`)**: "needed before `/actuator/prometheus` lands" — keep the management port authenticated (scrapers need a credential) OR permit the management context and rely on loopback + NetworkPolicy; acceptance = the decision written into observability §4.2 and auth.md §8.6's actuator row. Second item: Tomcat `WsFilter` registered with no WebSocket endpoint. **This record decides #231's first item (§17 row 17).**

### 3.4 Health today (matches observability §6 :500-547)
`HealthController.kt` (modules/app/…/health): `/health` (:38-48) `{status, version, components{database, redis, h2_factory}}` from indicators `db`, `redis`, `h2_factory` (:103-105; `StagingHealthIndicator` wired `WebSurfaceConfiguration.kt:96-97`), missing indicator → DOWN (:84-92), always HTTP 200 (Dockerfile :115-117); `/ready` (:50-57) 200 only when `readinessState == ACCEPTING_TRAFFIC` and aggregate UP, else 503; `/info` (:69-75). Public via `PublicPaths.kt:256-270` (reason P3d), exempt in `ForcedPasswordChangeInterceptor.kt:101-103`. Probes: Docker HEALTHCHECK `/ready` (Dockerfile:133-134); Helm readiness `/ready`, liveness `/health` (deployment.yaml:108-116). Guards: `HealthResponseSerializationTest:39-191`, `PublicPathsTest:31/:108`, `ApplicationSmokeTest:63/:114/:119`, `JarSmokeE2eTest:515/:673`, `ApplicationHardenedSmokeTest:71`. No scheduler component (:524-525). Health is NOT changed by this record.

### 3.5 Notifications today — mail is the only channel, and it is auth-owned
- Port `MailSender` (`MailSender.kt:50-52`), `MailMessage` (:13-27), `SendOutcome` Sent/Failed/Skipped (:30-43); impls `SpringMailSender` (JavaMailSender) and `NoopMailSender` (:59-73, logs `event=mail.skipped`). `MailConfiguration.kt:43-59` picks by `MailProperties.enabled` (true when host AND from set); `mailExecutor` 2 threads, queue 100, CallerRuns. `MailProperties` `@ConfigurationProperties("datapipelines.mail")` (:27-59): host, port=587, username, password, starttls=true, from, reply-to, **ops-to**, message-stream (not `spring.mail.*`).
- Kinds (`MailKind`, `MailSendRepository.kt:15-27`): WELCOME, PASSWORD_RESET, NEW_USER only; senders `LocalPasswordService.kt:181-182, :212`, `OidcSuccessHandler.kt:219`. Nothing else sends mail. `MailNotifier.kt`: `dispatch` claims a `mail_sends` row (`tryClaim`, :146-178), `afterCommitOrNow` (:180-190, the codebase's only afterCommit), `sendWithRetry` connect failures only 250 ms/1 s, 3 attempts (:219-243), `record` marks sent/failed + audit `mail.sent`/`mail.failed` (:279-316). Templates `modules/auth/src/main/resources/mail-templates/{welcome,new-user}.{html,txt}`. No meter for mail outcomes (audit rows + logs only).
- **Webhooks: none in production code.** Dev-time mail sink: none in any compose file; GreenMail only in tests (`MailNoticesE2eTest.kt:46`, IMAP read :339-350).
- 442a landed settings only: V49 columns (`V49__schedule_notification_settings.sql:1` "no mail transport exists yet"); `NotificationDelivery.CURRENT = {off, disabled}` hard-coded (`NotificationSettings.kt:89-90`); key `scheduler.notifications.max-recipients` (application.yml:552-553; configuration.md:522 "delivery remains off until part b"). 442b (IN FLIGHT, brief `prompts/442b-schedule-notification-delivery.md`): keys `enabled`, `recipientDomains`, `maxAttempts=3`, `retryBackoffSeconds=[30,120,600]`, `batchSize=20`, `backlogMax=1000` (A.1 :153-164); outbox V51 `schedule_notification_events` + `schedule_notification_deliveries` (pending|accepted|failed|uncertain, attempts, next_attempt_at, last_error_code, message_id; A.2 :170-186), rows written in the run ledger's transaction; scheduler port `NotificationMailer` with an adapter in `app` over auth's `MailSender` (no second SMTP config), db-scheduler task `schedule-notifier` SKIP LOCKED batches, counters `scheduler.notifications.{sent,failed,uncertain}` (A.3 :192-202); resend route (A.4 :211-217); GreenMail E2E (A.5 :219-226); `mail_sends` never holds schedule mail (:101-102).
- Live UI: execution SSE `pipeline_failed`/`node_failed`/`execution_aborted` (`ExecutionEvent.kt:35-38`; editor toasts `pipeline-editor/sse.js:564-565, :696-708, :811-812`), dashboard `source_failed` (`RefreshEvents.kt:74`), parameter `evaluation_failed`/`parameter_failed` (`ParameterEvaluationEventProjection.kt:94, :123`); schedules page polls while open (`schedules/detail.js:10-12`). No `EventSource` use, no per-user notification stream. Failures reach only the person watching their own stream.

### 3.6 Deployment today
Helm (`deploy/helm/datapipelines`): templates `_helpers.tpl`, deployment, hpa, pdb, service only — **no ServiceMonitor, no NetworkPolicy**; Service exposes `http` 8080 only (service.yaml:10-13), container port 8080 (deployment.yaml:34-36), no management containerPort; `values.yaml` `resources: {}` (:85), `extraEnv` (:27; deployment.yaml:52 refuses `JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`/`_JAVA_OPTIONS`), no logging/OTEL/MANAGEMENT value. Dockerfile: `EXPOSE 8080` (:113), HEALTHCHECK `/ready` (:133-134), ENTRYPOINT `java -Dpolyglot.engine.userResourceCache=… -jar` (:138). Compose `deploy/compose.yml`: datapipelines (:41), postgres (:455), redis (:484), mysql (:550), sample-data loaders (:576-676); publishes 8080 only (:65); `LOGGING_FORMAT` default json (:219); `DATAPIPELINES_MAIL_*` (:347-357). `compose.laptop-infra.yml` postgres+redis; `compose.local-build.yml` image override (:15-16); `tests/integration-tests/multi-instance/compose.two-instance.yml` app1/app2. **No compose has a mail sink, Prometheus, Grafana, Loki or Collector.** `deploy/env/defaults.env:52` lists MANAGEMENT_* as framework wiring not passed through; `:273` `LOGGING_FORMAT=console`; `:275` `TRACING_ENABLED=false`. `secrets.env.example:78-79, :107-111, :198-199` mail keys; no webhook/OTEL/MANAGEMENT key.
Configuration system: typed `@ConfigurationProperties` per module; `ConfigValidator` (`modules/app/…/config/ConfigValidator.kt:26`, `@PostConstruct validateOnStartup` :31-44, `CHECK_COUNT = 38` :72, `ConfigValidatorCheckCountTest:25`), per-area rule files (MailRules wired :158-160). configuration.md §3 has §3.1–§3.34 (§3.14 framework keys :221-237 — :237 relies on a NetworkPolicy for Kubernetes scraping; §3.15 Observability :239-245; §3.27 Mail :454-474; §3.29 scheduler :522); §7 Config Validation :1030 (mail rules :1070; :1073 the documented laptop setup must pass production rules). §1 (:13): configuration.md is the ONLY place a key may be defined (`scripts/docs-audit.sh`); env names derive mechanically from the yml path. House pattern: a per-block `*ConfigKeysSpecDriftTest` (doc = yml = binding/rules + "neighbours still bind"; exemplars `MailConfigKeysSpecDriftTest:27/:41/:54/:74`, `SchedulerConfigKeysSpecDriftTest:26/:53/:66/:76`); `scripts/compose-env-audit.sh` (build.gradle.kts:460-467) checks yml = compose = the two env files. **New sections this record adds: §3.35 Failure records / telemetry export, §3.36 Notifications** (numbers checked at dispatch).


## 4. Current state — the exception taxonomy and the failure boundaries (inventory 2026-10-24/25)
Read by a fenced inventory subagent on local main 0d4ddd5b5 (2026-10-24/25); every file:line was opened in that run.

### 4.1 The taxonomy
- Base `DatapipelinesException(code, message, details, cause)` (`modules/typesystem/…/DatapipelinesException.kt:20`; KDoc :10-18: codes not validated, `details` never carry secrets). No `status`, no `userMessage` at the base.
- Families: `AuthException` (`auth/…/AuthErrors.kt:240`, adds `status` + `userMessage`; 18 subclasses :249-544, 13 more in `WorkspaceErrors.kt`); sealed `PipelineException` (`dag/…/executor/PipelineExceptions.kt:22`: `NodeExecutionException` :42 with `errorRecord: MappedError?`, `PipelineExecutionFailed` ~:62, `PipelineTimeoutException` :81 `pipeline.execution.timeout`, `NodeQueryTimeoutException` :104 `node.query_timeout`, `NodeTimeoutException` :140, `PipelineConcurrencyLimitException` :158 `execution.concurrency_limit`); `ExecutionAbortedException` (:184) is a `CancellationException` carrying `AbortReason`, no code; `ScriptingException` (+5 subclasses); `ScheduleException` (`ScheduleErrorCodes.kt:111`); `ApiException` (`web/…/api/ApiErrors.kt:67`); five validation-result wrappers (pipeline-contract `ValidationFailure.kt:65`, templates `TemplateValidation.kt:54`, parameters `ParameterSetFailures.kt:68`, visualization `ArtifactFailures.kt:64`, datasources `Results.kt:53`); staging exceptions (`StagingExceptions.kt:20/:37/:50/:70`); `SearchQueryTooLongException`, `TemplateRenderException`; bare `DatapipelinesException(...)` via factories (`McpNotFound.kt:24-78`, `PipelineExecutor.kt:147-155`).
- **Outside the hierarchy (no code; mapped at a boundary or they fall to a backstop):** datasources `SqlExecutionException` (`SqlRunner.kt:33`), `SqlProbeTimeout/Execution/Refusal/ParameterException`, `DatasourceUnreachableException`, `CurrentSchemaUnknownException`, `CredentialDecryptionException` (`CredentialEncryptor.kt:152`), `BootstrapDatasourceFileException`; `CalculatorEvaluationException` (`CalculatorKind.kt:195`), `EndpointPathException` (`EndpointPath.kt:314`), `IdentityMismatchException`, `ExampleContentFileException`, `ExecutionRecordUnwritableException` (`WebEventEmitter.kt:499`).
- Mapping: `ErrorCodeMapper.map(error, phase, nodeId)` (`dag/…/executor/ErrorCodeMapper.kt:59-82`): a DatapipelinesException keeps its code (message cut to `MAX_MESSAGE_CHARS`=2000, :142); a raw SQLException maps by phase (:85-104) to `node.datasource_connection_failed` / `node.staging_failed` / `node.writeback_failed` / `node.query_execution_failed` with `sql_state`, `vendor_code`; anything else → phase fallback (:111-118; EXECUTE/MATERIALIZE → `pipeline.execution.aborted`). Code → HTTP status: `ApiErrorCatalog.statusFor` (`web/…/api/ApiErrorCatalog.kt`, 646 lines; `EXCEPTIONS` :139, `FAMILY_DEFAULTS` :59, else 500; `userMessageFor` :534/:476/:469; `NEVER_RETURNED_LIVE` :413, `GATEWAY_CODES` :443, `CALLER_DOWNSTREAM_DOWN` :458, 4 codes).
- Catalog: `pipeline-contract/…/PipelineErrorCodes.kt` (1878 lines, 24 nested objects Validation :23 … Dashboard :1797): **462 string constants, 462 distinct values; 388 three-segment, 74 two-segment** (`grep -oE 'const val [A-Z0-9_]+ *= *"[^"]+"'`). Module mirrors: Datasource 28, Semantics 8, Parameter 81, Schedule 21, Staging 8, Dashboard 36, Visualization 30, AuthErrors 26, WorkspaceErrors 11 — every checked literal also in PipelineErrorCodes. Scheme `{domain}.{entity}.{failure}` at enums.md §16 :509-547 (:514 names the two-segment exceptions) and `PipelineErrorCodes.kt:13-15`. Docs: pipeline-contract §13 :1085-1639 (§12 :898-1084); rest-api §4.2 :109-140 (example :113-127, notes :129-133); observability §8 :569-587 (§8.1 :571, disconnect :577, §8.2 :581); dag-executor §8 :1064-1303 (§8.1 :1066, §8.2 :1125, §8.3 :1167, §8.4 failure record :1292).
- Guards: `PipelineErrorCodesSpecDriftTest` (303 lines, 6 tests: domains both sides, every §12/§13 code has a constant and vice versa, no duplicates, segmentation), `ApiErrorCatalogSpecDriftTest` (382 lines, 3 tests; row-count guards), seven per-module `*ErrorCodesSpecDriftTest`, `ApiErrorCatalogGatewayCodesTest`, `ApiErrorCatalogUserMessageTest`, `ErrorCodeMapperTest` (~9 cases), `ApiEnvelopeSerializationTest` (:30), `AuthErrorWriterTest` (8: correlation echo in body + header, MDC beats header, non-UUID replaced), `ApiExceptionHandlerTest` (686 lines, 16+2: disconnect DEBUG no response :396, non-client IO → 500 + ERROR :434, cyclic cause chain :468, 502 WARN vs ERROR :507/:535), `UiExceptionHandlerTest` (814 lines, 22+2: :206 no ERROR on 400, :268 disconnect, :680 injection echoed nowhere, :727 no converter → 500 with stack), `McpToolDispatcherTest` (~20). **None asserts a meter on error; none covers the launcher's ERROR line (`ExecutionStreamLauncher.kt:387`).**

### 4.2 The boundaries today (every place a failure is caught for the last time)
| Boundary | Where | What it does today | Correlation in the log | Meter | Persisted | Notifies |
|---|---|---|---|---|---|---|
| REST backstop | `ApiExceptionHandler.kt:317` (Throwable → 500, stand-in `pipeline.execution.aborted` reason `internal_error`, ERROR + stack :323); `:280` ResponseStatusException (5xx ERROR); `logAt` :334-365 (CALLER_DOWNSTREAM_DOWN → WARN + `CauseChain.summarize`; other 5xx ERROR; else DEBUG); `INTERNAL_STAND_IN_CODE` :409; `clientDisconnected` :434-446 (≤8 causes, `AsyncRequestNotUsableException` / `ClientAbortException` → one DEBUG, no response) | envelope `ApiErrorResponse{schema_version, correlation_id, error{code,message,user_message,details,doc_url}}` (`ApiErrors.kt` ~:40-58); header `DP-Correlation-Id` set by the filter before the chain | yes (MDC) | **none** | none | no |
| UI backstop | `UiExceptionHandler.kt:229` (Throwable → 500 page/toast, ERROR + stack :235); `logAt` :279-289 — does NOT demote CALLER_DOWNSTREAM_DOWN (disagrees with the API handler); toast small text :299-308; `error/500` model `correlationId` :244/:273; stand-in :332 | yes | none | none | no |
| MCP tool | `McpToolDispatcher.kt`: scope refusal :67-70 (audit `permission_refused`); `DatapipelinesException` :96-107 audited `error` + code, **logged at INFO** `MCP tool {} failed code={} correlation_id={}`; other `Exception` :111 → `internalError` :131-144 audited `internal_error`, ERROR + stack, JSON-RPC INTERNAL_ERROR "Internal error. Quote correlation id … to an operator." — **no catalog code**. Shape `McpToolResults.kt:45-56` (`isError=true`, `{"error":{…}}`, id in `_meta.correlation_id` :30/:56; `McpErrorPayload.toMap` :81-91, PipelineExecutionFailed adds node/sql/exception :101-114). Audit `mcp.tool.called` (+ `mcp.tool.write` :292-293), WARN-and-swallow if the audit write fails :231-232 | as message arg (and the §3.2 MCP split) | none | `audit_log` | no |
| Executor, node | `PipelineExecutor.kt:275-292` `executeNode` catch Exception → `failNode` :525-574 (ErrorCodeMapper, failure record into run stats, **the only ERROR line** :563-570 `node {} failed ({code}) execution_id={} correlation_id={}` + throwable, emits `node_failed`); `NodeRunner.kt` catches (:336/:338, :677/:679, :802, :846, :1092) decorate and rethrow, none log | yes (`LogContext` :126) | none per error | `node_failed` event | no |
| Executor, execution | `:207-227`: `failWithNode` :680-694 (emit `pipeline_failed`, `executions.total{failed}`, throw `PipelineExecutionFailed`, **no log**); `failWithTimeout` :696-710 (**no log**); `abort` :735-755 (`executions.aborted{reason}` + `executions.total{aborted}`, no log); `failWithSetup` :719-733 (**no log**) | yes | `executions.total`, `.aborted` | `pipeline_executions.status/failed_node_id/error_json` (V1 :111-131; written by `WebEventEmitter.completeExecutionRow` :333-393, failure of that write → `lifecycle_write_failed` + WARN class/SQLState :389-431); `execution_events` (V1 :149) | no |
| REST/SSE launcher | `ExecutionStreamLauncher.kt:341-390`: concurrency limit :364 and aborted :370 DEBUG; a DatapipelinesException after streaming :379 **DEBUG** `Execution ended with {code}`; other Exception :387 **ERROR** "Execution failed outside the executor's mapped paths." — the launch scope `SupervisorJob + Dispatchers.Default` has no MDC and no `CoroutineExceptionHandler` (`WebSurfaceConfiguration.kt:203-207`) → the :379/:387 lines carry **no correlation id** (inferred from the wiring, not run) | likely no | none | — | no |
| Scheduler run | `ScheduledRunWorker.startOrUnknown` :159-178 (catch Exception → ERROR `event=scheduler.start_failed run_id= execution_id=` + stack, run → UNKNOWN/`start_failed`, schedule blocked); `PipelineJobExecutor.kt:359-374` start refused → WARN `scheduler.start_refused`, `not_started`/`START_REFUSED`; launch coroutine catch-all :338-343 DEBUG; failed execution reconciled later (`outcomeOf` :410-411 → `RunState.FAILED` reason `execution_failed`; `RunReconciler.finish` :140-155 → `RunLedger.move` :64-83 writes `schedule_runs.state/reason` + `schedule_run_events{kind,reason,details_json{execution_id}}`, `scheduler.runs.finished{state}`). **The run record drops the error code** — it lives only in `pipeline_executions.error_json` | run id as correlation id (:315) | `runs.finished` | `schedule_runs` (V38 :96, state CHECK 10 values :128-130, `reason` :115), `schedule_run_events` (:155), `schedules.blocked_reason/at` :62-63, db-scheduler `last_failure/consecutive_failures` :181-182 | **off** (`NotificationDelivery.CURRENT`; 442b in flight) |
| Dashboard refresh | `application/…/dashboards/RefreshEngine.kt:117-120` catch RuntimeException → ERROR `event=dashboard.refresh_work_failed refresh_id= error={class}` **no stack**, FAILED; source failures → per-target `TargetOutcome.Error(stage, code)` :398-399; `finish` writes `dashboard_refreshes.status/summary_json` (`DashboardRefresh.kt:100`) + audit `dashboard.refresh` (`DashboardAuditEvents.kt:17`), guarded :563-570 (ERROR `refresh_end_failed` class only); `DashboardRuntime.kt:339` records meters, **cancellation swallowed empty :340** | `refresh_id` only, no correlation id | `dashboard.refreshes{status}`, `.duration` | `dashboard_refreshes` (V43 :49, status CHECK 6), `dashboard_refresh_executions` :87 | no |
| Housekeeping jobs | 7 registration sites of `scheduled_jobs.tick_failed{job}` (§3.3) | per job | `tick_failed` | — | no |
| SSE/persistence side paths | send/heartbeat IOException DEBUG `connected=false` (`ExecutionStream.kt:126-162`, `SseLogStreamer.kt:330-336`); event-log append WARN (`SseEventLog.kt:92`); durable event write WARN (`ExecutionEventRecorder.kt:73/:119/:124/:146-164`); heartbeat tick WARN (`ExecutionStreamRegistry.kt:164`); authority re-judge WARN fails closed (`ExecutionStreamAuthority.kt:104`) | varies | `persistence.*`, `lifecycle_write_failed` only | — | no |
| Mail send | `MailNotifier.record` :279-316 → `mail_sends.error` (V27 :30), audit `mail.sent`/`mail.failed` | `user=` arg | none | `mail_sends` | — |

Other persisted failure columns: `parameter_evaluation_queries.outcome/refusal_code/error_code` (V48 :92-94); `audit_log` (V1 :64). **Executions are not audited** (no `audit_log` event for an execution failure; only MCP calls and dashboard refreshes write one). `pipeline_executions.error_json`'s column comment ("error envelope", V1 :124) does not match the stored MappedError projection (`WebEventEmitter.kt:345`, `ErrorCodeMapper.kt:22-29`).

### 4.3 Silent catches (method: an awk brace-tracker over `modules/*/src/main/**/*.kt`, classifying `catch` bodies EMPTY / VALUE-only / DEBUG-only; `runCatching{}.getOrNull/Default/Else` — 66 sites — NOT covered)
Strictly silent: **29** — EMPTY 5 (`DatasourceGrantRepository.kt:101`, `JavaScriptRuntime.kt:259`, `RequestBodyCapFilter.kt:133`, `ParameterEvaluationStreamController.kt:106`, `DashboardRuntime.kt:340`); VALUE 13 (`BatchingWriter.kt:386`, `JsonataEngine.kt:229`, `TemplateImplements.kt:124`, `ParameterCoercion.kt:223`, `CurrentNamespace.kt:76/:100`, `DatasourceFileRoots.kt:108`, `JdbcDrivers.kt:43`, `DashboardRefresh.kt:88`, `VisualizationTestCapabilities.kt:148`, `PipelineJobExecutor.kt:630`, `SlotCapacityGate.kt:37`, `PipelineExecuteController.kt:181`); DEBUG-only 11 (`OidcSignedInBounceFilter.kt:70/:73/:76`, `CookieOAuth2AuthorizationRequestRepository.kt:123`, `DefaultDatasourceRegistry.kt:545`, `CancellationRegistry.kt:426/:438`, `ExecutionStreamLauncher.kt:370`, `SseLogStreamer.kt:330/:334`, +1 borderline). Borderline (DEBUG + a side effect, excluded): `ExecutionStream.kt:126/:131/:156/:160`, `RefreshStream.kt:96/:100`, `ParameterEvaluationStream.kt:96/:100`, `CancellationRegistry.kt:398`, `McpResourceRequestHandler.kt:67`, `LocalPasswordService.kt:169`. Many of the 29 are legitimate (a probe that returns null is its contract); the classification into *contract* (annotated, allowlisted) and *defect* (a WARN or a record) is DEFERRED (§12) — it is a deliverable of its own, not a blanket rewrite (#336's principle).

### 4.4 Gaps the record closes (numbered; §16 names the lane for each; C = core lane)
G1 no error meter anywhere (#568) → C1/C2. G2 timeout/setup failures never logged above DEBUG (`failWithTimeout`/`failWithSetup`; launcher :379) → C3. G3 launcher ERROR line without correlation id → C3 (launch scope carries `LogContext`). G4 no generic internal code; 500s and 405s reuse `pipeline.execution.aborted`; MCP internal error has no code → C2 (`internal.unhandled`; the 405 gets `request.method_not_allowed` [CALL]). G5 API vs UI handlers disagree on CALLER_DOWNSTREAM_DOWN level; API KDoc names 2 of 4 codes → C2 (one shared `logAt`). G6 MCP domain failures at INFO even for 5xx-class codes → C2 (MCP uses the same severity rule as REST: 5xx-class → ERROR + record; 4xx-class → DEBUG). G7 scheduler run record drops the error code → C3 (`schedule_run_events.details_json` gains `code`; the failure record carries `schedule_id`). G8 dashboard refresh logs lack correlation id and stack; cancelled refresh records no meter → C3. G9 29 silent catches + 66 `runCatching` sites unclassified → DEFERRED (§12). G10 executions not audited → **not changed** (the failure record, not audit_log, is the execution-failure evidence — audit is "who did what"); the V1 column comment is corrected in C2's migration notes. G11 MCP correlation split (§3.2) → C2. G12 `MdcTask` copy-pasted ×4 → C3 (one shared module; the lane proposes where, the record requires one implementation). G13 13 documented meters without code → C1 (§4.1 cut to what exists; a "planned" table is not a contract). G14 log-format defaults disagree by shape → C3 (one default per shape, stated in deployment.md).

## 5. The failure record — the spine [CALL]

A new table `failure_records` (Flyway: main's highest is `V50__script_node_type.sql` (`git ls-files` on 0d4ddd5b5); 442b's brief takes V51 — C2 takes the next free number at dispatch and `FlywayMigrationIntegrationTest` pins it):

| column | type | meaning |
|---|---|---|
| `failure_id` | uuid pk | the id every artifact carries (principle 2); generated at classification |
| `occurred_at` | timestamptz | boundary time |
| `kind` | text, CHECK against the closed catalogue §5.1 | *what kind of thing failed*, e.g. `request.unhandled`, `execution.node_failed`, `schedule.run_failed`, `dashboard.refresh_failed`, `job.tick_failed`, `notification.delivery_failed`, `mcp.tool_failed` |
| `code` | text | the mapped error code from the single catalog (pipeline-contract §13 scheme `{domain}.{entity}.{failure}`, enums §16); `internal.unhandled` for an unmapped Throwable — a NEW code in the `internal` domain [CALL], replacing the stand-in `PipelineErrorCodes.Execution.ABORTED` the backstops use today (ApiExceptionHandler.kt:409, UiExceptionHandler.kt:332) |
| `severity` | text enum `error` / `warning` | `warning` for degraded-but-handled kinds (e.g. a notification retry) — only `error` notifies by default |
| `correlation_id` | uuid, nullable | the request / run / refresh identity (§3) |
| `workspace_id`, `actor` | nullable | the authenticated workspace and principal, when the boundary has them; `actor` is the principal id or `system` |
| `execution_id`, `node_id`, `pipeline_id`, `schedule_id`, `dashboard_id`, `tool_name` | nullable | the domain identities the boundary knows — the SAME names as the MDC keys (§3) so a log search and a SQL query use one vocabulary |
| `exception_class`, `exception_message_redacted`, `stack_redacted` | text | the redacted diagnostic; the stack bounded to N frames [CALL N=40] and the first 8 causes (the #562 cause-chain discipline) |
| `details` | jsonb | the allowlisted safe fields of the boundary (never rows, SQL text, parameter values, credentials); keys closed per kind |
| `acknowledged_at`, `acknowledged_by` | nullable | the Problems view's "seen" (§9) |

Rules. **Who writes:** a `FailureRecorder` port (interface + value types in a small Spring-free module; the JDBC implementation in `web`,
beside `WebEventEmitter`) [CALL]. REST, UI and MCP boundaries call it directly. **The executor (dag) does NOT call it**: `failNode`,
`failWithTimeout`, `failWithSetup` keep emitting their events (`node_failed`, `pipeline_failed`, `execution_aborted`, which already carry
the projected `MappedError`), and `WebEventEmitter` — the one place that already persists the execution's terminal state
(`completeExecutionRow`, :333-393) — writes the records from those events [CALL — keeps dag JDBC-free and single-sourced; the record
and the terminal UPDATE are then written by the same component in sequence]. **Atomicity, stated honestly:** `completeExecutionRow`
runs no transaction today (a time-bounded UPDATE; on failure the row stays RUNNING and `lifecycle_write_failed` counts), so "record and
terminal row commit together" is NOT a property the code offers and this record does not promise it. The contract is: the record is
written FIRST, in its own short transaction (the #266 pattern), then the terminal UPDATE; a failure of either is itself recorded
(`execution.record_write_failed` for the UPDATE; for the record write, the JSON line with `failure_record_write_failed=true` +
`datapipelines.failures.record_write_failed` — the log is the fallback of last resort, never the primary). §15.1 asserts "the record
exists whenever the row is `failed`", which this ordering guarantees; the inverse (row failed without a record) can only happen when the
record write itself failed, and then the counter and the line prove it. The scheduler and dashboard boundaries, which do run in
transactions (`RunLedger.move`, `RefreshEngine.finish`), write the record inside them. Blocking: a recorder call is one INSERT on the
caller's thread, bounded by the statement timeout the boundary already has (#326's bounded JDBC pattern). Retention: `datapipelines.observability.failures.retention-days` [CALL 90],
swept by the existing retention thread (observability §3.4H/N, #310's pattern). Volume bound: a per-minute cap on rows per `kind`
[CALL 600/min/kind], beyond which the boundary increments `datapipelines.failures.suppressed{kind}` and logs one WARN per minute.

### 5.1 The failure-kind catalogue (closed; a §13 guard pins the CHECK, the enum and the doc table to each other)
| kind | boundary (§4.2) | severity | identities carried | details allowlist |
|---|---|---|---|---|
| `request.unhandled` | REST + UI backstops (Throwable, 5xx ResponseStatusException) | error | correlation, workspace, actor | `method`, `route_pattern` (never the raw path or query), `status` |
| `request.downstream_unavailable` | REST + UI `logAt` for `CALLER_DOWNSTREAM_DOWN` codes | warning | correlation, workspace, actor | `code`, `target` (closed: `datasource`, `redis`, `promotion_target`) |
| `mcp.tool_failed` | `McpToolDispatcher` for 5xx-class codes and `internalError` | error | correlation, workspace, actor, tool_name | `code`, `elapsed_ms` |
| `execution.node_failed` | `WebEventEmitter` on `node_failed` (emitted by `failNode`) | error | correlation, workspace, execution, pipeline, node | `code`, `phase`, `sql_state`, `vendor_code`, `source` |
| `execution.failed` | `WebEventEmitter` on `pipeline_failed`/`execution_aborted` when NO `node_failed` preceded it in that execution (setup, timeout, abort; a node failure already recorded is not duplicated) | error | correlation, workspace, execution, pipeline | `code`, `elapsed_ms`, `timed_out_node_id` |
| `execution.launch_failed` | launcher :387 (outside the executor's mapped paths) | error | correlation, workspace, execution?, pipeline | `code?` |
| `execution.record_write_failed` | `completeExecutionRow` failure, `ExecutionEventRecorder` durable-write failure | error | execution, pipeline | `exception_class`, `sql_state` |
| `schedule.start_failed` | `startOrUnknown` catch | error | schedule, execution?, correlation(=run id) | `run_id`, `reason` |
| `schedule.run_failed` | `RunReconciler.finish` → FAILED | error | schedule, execution, pipeline, workspace | `run_id`, `code` (from `error_json`) |
| `schedule.blocked` | `blockScheduleOf` | warning | schedule, workspace | `blocked_reason` |
| `dashboard.refresh_failed` | `RefreshEngine` catch + FAILED/TIMED_OUT terminal | error | dashboard, workspace, correlation(=refresh id) | `refresh_id`, `status`, `failed_targets` (count), `codes` (closed list) |
| `job.tick_failed` | the 7 `tick_failed` sites (one recorder call beside the meter) | error | — | `job` (the `ScheduledJob` enum) |
| `notification.delivery_failed` | the §8 notifier (terminal failure after retries; `uncertain` too) | warning | workspace? | `route`, `error_code`, `attempts` — **never notifies by mail/webhook** (loop guard) |

### 5.2 Error code changes
- NEW domain `internal` with one code `internal.unhandled` (observability §8.1 already names `internal` as the domain for "a failure with no
  mapped code"; no catalog entry exists — pipeline-contract §13 `:1085+`, enums §16 `:509+` get the row) [CALL].
- NEW `request.method_not_allowed` for the 405 handler (`ApiExceptionHandler.kt:209` reuses the stand-in today, G4) [CALL].
- The backstops' stand-in constant is retired; `ApiErrorCatalog` gains the row; `ApiErrorCatalogSpecDriftTest`-class guards (§4 inventory
  names them) pin it.
- The REST envelope (rest-api §4.2) gains `failure_id` beside the correlation id for 5xx responses only [CALL — 4xx are the caller's
  mistakes and write no record]; the UI 500 page and toast show both ids ("Quote these when reporting").

## 6. Logging — the stdout JSON line is the product
- **Format: KEEP #337's shape** — Boot's `StructuredLogEncoder` in `logstash` format behind our `FormatSwitchingEncoder`, with the two
  redaction layers (§3.1) [CALL — the inventory shows a tested, redacting, startup-validated encoder with five guard classes and a
  child-JVM proof; switching to ECS would re-earn all of that for a field-naming preference every sink maps anyway]. What changes: static
  members `service` (name), `version` (the build's version, already in `/info`), `environment` (`datapipelines.observability.environment`
  [CALL key]) via `logging.structured.json.add.*` in the post-processor; the closed MDC set below appears as top-level members (Boot adds
  MDC automatically). The format defaults are made to agree (G14): container image and compose `json`, Helm `json` (explicit value),
  dev profile `console`; `logback-spring.xml:12`'s `defaultValue` becomes `json` so the XML and the post-processor cannot disagree.
- **Correlation in every line:** the closed MDC key list grows from two (`correlation_id`, `execution_id` — `dag/executor/LogContext.kt:71/:74`)
  to ten: `correlation_id`, `workspace_id`, `actor`, `execution_id`, `node_id`, `pipeline_id`, `schedule_id`, `dashboard_id`,
  `tool_name`, `failure_id`. Snake_case [CALL], matching the database columns and the §5 record. The list lives in ONE
  shared constants object the four `MdcTask` copies and `LogContext` import (G12: a small `modules/logging-context` with no Spring, or the
  typesystem module — the C3 lane proposes, states why). `CorrelationMdcKeyDriftTest` and `LogRedactor.NEVER_REDACTED` (§3.1) extend to
  the ten.
- **Across hops:** the §3 inventory lists where context is carried today and where it is lost; the rule: every `launch`/`async`/executor
  submission/db-scheduler task/outbox consumer re-establishes the SAME MDC set from the durable record it works on (the execution row,
  the schedule run, the outbox row), never from a thread-local it hopes survived.
- **Redaction:** unchanged in mechanism (`RedactingJsonMembersCustomizer` + `RedactingStackTracePrinter` for json, `RedactingConverter`
  for console); the recorder (§5) and the notifier (§8) call the same `LogRedactor.scrubText` on message, cause messages and `details`
  values before writing. Scenario §15.3 proves it across four sinks.
- **Levels (principle 6):** a build-time guard (`LogLevelDisciplineTest`, §13) scans `modules/*/src/main` for `log.error(`/`logger.error(`
  outside the allowlisted boundary files; every allowed site is in the §4 table.
- **Search story for the demo:** "take the `failure_id` from the mail or the webhook; in your platform, filter the app's log stream on
  that field" — the line carries `execution_id`, `node_id`, `pipeline_id`, `correlation_id`, the mapped code and the redacted cause; filter
  on `execution_id` and every node log of that run appears; the Problems page shows the same record with the same ids. With Alloy/Promtail
  tailing docker stdout into Loki the query is `{container="datapipelines"} | json | failure_id="…"`; with Fluent Bit → OpenSearch it is a
  term query on `failure_id`; with the Datadog agent it is `@failure_id:…`. Without a platform: `docker logs … | jq 'select(.failure_id=="…")'`.
  Each of these is a snippet on the "Connecting your platform" page (§0).

## 7. Metrics — a Prometheus registry on the management port [CALL]
Decision: add **`micrometer-registry-prometheus`** (managed by the Boot 3.5.16 BOM, `gradle/libs.versions.toml:53`: 1.15.12, same line as
the `micrometer-core` already on four module classpaths — §3.3) to `modules/app`, expose `health,prometheus` on the management port
(`application.yml:108-113` already says so in a comment), and resolve #231 (§17 row 11) so a scraper needs no credential. Lockfiles and
verification metadata regenerate cold (the #566 recipe). No OTLP registry, no tracing bridge, no OpenTelemetry artifact (deferred, §12).

- The 44 existing meters (§3.3) stay; the two un-prefixed gauges (`script.evaluations.abandoned`, `parameters.selectors.abandoned`) are
  renamed under `datapipelines.` [CALL — they break §4.1's own rule]. observability §4.1 is cut to the meters that exist (G13); the 13
  documented-only meters are dropped from the table, not marked "planned" (a table of promises is what produced #568/#708).
- NEW: `datapipelines.failures.total{kind,severity}` (replaces the phantom `errors.total{domain}` — #568 ruled into this record; the `domain`
  tag goes because §5's `kind` is the closed set operators alert on), `datapipelines.failures.record_write_failed`,
  `datapipelines.failures.suppressed{kind}`, `datapipelines.notifications.sent{route,outcome}`, `datapipelines.notifications.suppressed`.
- Cardinality: `executions.*`/`nodes.*` carry `pipeline_id` (and `node_id`): per-pipeline series, bounded by the catalogue; `/actuator/prometheus`
  is documented with this note and the §13 series-ceiling guard.
- `http.server.requests` (Boot's own) appears once the registry exists; observability §4.1 :400 becomes true without code.
- Shipped **alert-rule examples** (Prometheus rule YAML on `failures.total`, `scheduled_jobs.tick_failed`, `persistence.dropped`) live in
  the "Connecting your platform" page as snippets — not a shipped Alertmanager.

## 8. Notifications — one outbox, many routes [CALL: generalize 442b's outbox rather than add a second]
442b (in flight) builds a scheduler-owned outbox (`schedule_notification_events` + `schedule_notification_deliveries`, §3.5), a db-scheduler `schedule-notifier` task and a sender adapter
over the shared `MailSender` (its brief `prompts/442b-schedule-notification-delivery.md:149-235`, record `docs/scheduler.md` §6.1/6.2).
This record does NOT change 442b's lane. It rules that **the failure record (§5) is the second producer for the same delivery
machinery**: after 442b lands, a follow-up lane lifts the outbox's *sender*, *routes* and *delivery state* into a shared
`modules/notifications` (new, no Spring in the core, the §10 "no-JDBC in persistence" discipline respected) with two producers
(schedule events — 442b's rows; failure records — §5) and N routes:

| route | app alone | config | payload |
|---|---|---|---|
| **mail** (exists) | ✓ | `datapipelines.notifications.mail.*` (442b's keys) | subject `[datapipelines] <severity> <kind> — <code>`, body: the §5 record's safe fields, the ids, the authenticated UI link to the Problems page (`datapipelines.auth.base-url`, #207) |
| **webhook** (new) | ✓ | `datapipelines.notifications.webhook.{url, secret, timeout}` | JSON = the §5 record minus stack, HMAC-SHA256 signature header; Slack/Teams/PagerDuty/Opsgenie are receivers of this one shape, documented with their adapters' expected transforms (we ship a Collector-free doc page, not N integrations) |
| **in-app** (new) | ✓ | always on | the Problems view (§9) and the top bar's unseen-count badge for workspace admins / super admins |
| **metric alerts** (their platform) | — | alert-rule examples on `failures.total` in the platform page | for orgs that alert from Prometheus/Datadog; complementary to the record's routes, never a replacement |

Routing policy (config, closed): `datapipelines.notifications.failures.severity-floor` [CALL `error`], `…kinds` allow/deny list,
`…recipients` (default: `datapipelines.mail.ops-to`, which exists today in `MailProperties` [CALL]; members are never mailed in this
record's stages — §14), **grouping**: identical
`(kind, code, workspace_id)` within `…group-window` [CALL 10 min] send one message with a count; **rate bound**: `…max-per-hour` [CALL 60]
then one "notifications suppressed, N more" message. Delivery state lives in its OWN table, `failure_notifications` (record_id, route, state pending|accepted|failed|uncertain, attempts,
next_attempt_at, last_error_code, message_id, group_key), landing with C4 and shaped like 442b's `schedule_notification_deliveries` so the
shared module has two identical producers [CALL]; `failure_records` carries NO delivery columns (C2's migration is not redone); records
written between C2 and C4 are simply never delivered (the Problems view shows them; the first notifier run starts from "now", never
back-fills). Outcomes counted in `notifications.sent{route,outcome}`; a failed delivery is itself a `notification.delivery_failed` record at `warning`, never a mail about a
mail (the loop guard: a record whose kind starts with `notification.` never notifies by mail/webhook, only in-app).

## 9. The Problems view — the app's own answer to "what went wrong" [CALL]
A new UI family page `/problems` (workspace admin, super admin; viewer/author/promoter see none — roles table §14): a list of failure
records (newest first, filter by kind, code, severity, time, acknowledged), a record page showing every field of §5 with the ids as
copyable chips, the redacted stack folded, links "all records with this correlation_id / execution_id", and an **Acknowledge** verb.
Rendered server-side like every other family page (ui-screens §3 pattern), HTMX list, no new JS. REST twin `GET /api/v1/problems`,
`GET /api/v1/problems/{failure_id}`, `POST /api/v1/problems/{failure_id}/acknowledge` with the §14 rows. Plus the top bar's unseen-count
badge for workspace admins / super admins (the in-app route of §8). No MCP tool (deferred, §12). This page is the demo's ending.

## 10. Bounds, outage behaviour, cost
- Stdout: the synchronous console appender stays (a container's stdout is the one sink that cannot be "down"); no network sink exists in
  the app for telemetry, so nothing blocks on one.
- Database records: bounded per §5 (per-kind per-minute cap, retention sweep); one INSERT per failure on the boundary's own thread under
  the boundary's existing statement bound (#326).
- Notifications: §8 grouping and rate bound; mail/webhook sends happen on the notifier task only (never a request or executor thread);
  webhook connect/read timeouts `datapipelines.notifications.webhook.timeout` [CALL 5 s]; retries per 442b's backoff shape.
- Memory: the Prometheus registry's series count is bounded by the closed tag sets — a §13 test asserts the number of distinct series
  after the §15 demo stays under a ceiling [CALL 2000].
- Cost measured once in §15.6: p95 request latency and execution wall clock with the recorder on vs. a no-op recorder, within +5 % [CALL].

## 11. Configuration surface (documented in configuration.md §3.15 + a new §3.36 Notifications; every default works for the app alone)
`datapipelines.observability.environment` (string, default `development`; becomes the log member `environment`), `.failures.retention-days`
(90), `.failures.max-per-minute-per-kind` (600), `.failures.stack-frames` (40); `datapipelines.observability.logging.format` (exists;
image/compose/Helm `json`, dev `console` — §6); Spring's own `management.prometheus.metrics.export.enabled` (true),
`management.endpoints.web.exposure.include=health,prometheus`, `management.server.port` (9090, exists), `management.server.address` (exists);
`datapipelines.notifications.*` per §8 (`failures.enabled`, `failures.severity-floor`, `failures.kinds`, `failures.recipients`,
`failures.group-window`, `failures.max-per-hour`, `webhook.{url,secret,timeout}`). The existing `datapipelines.observability.tracing.enabled`
(application.yml:378-379, no production consumer) and the documented-only `tracing.endpoint` (configuration.md:244, never in yml) are
REMOVED [CALL — no deprecation window: owner rule 2026-10-18]; `compose-env-audit.sh` and `defaults.env:275` follow. New
`*ConfigKeysSpecDriftTest` classes per the house pattern (§3.6). Helm: the deployment gains the management `containerPort`, a second
headless `Service` for it with no ingress, a `NetworkPolicy` template allowing only the scraper namespace/labels, an optional `ServiceMonitor`
(off by default), and `notifications.webhook.*` values with the secret from a Kubernetes `Secret`; `helm-check.sh` extends — the management-port pieces are lane C1b (§16, split from C1 at C1's brief 2026-10-25), the webhook values are C4's. Compose:
`deploy/compose.yml` unchanged except the `DATAPIPELINES_NOTIFICATIONS_*` passthrough; no observability compose is shipped.

## 12. DEFERRED (ruled out of scope by the owner 2026-10-25; each returns as its own record when a prospect or an incident asks for it)
| What | Why deferred | What it would take when it returns |
|---|---|---|
| **Distributed tracing + OTLP export** (Micrometer tracing bridge `micrometer-tracing-bridge-otel` 1.5.12, `opentelemetry-exporter-otlp` 1.49.0, `opentelemetry-logback-appender-1.0` 2.33.0-alpha, Observation spans per execution/node/run/refresh/tool, `trace_id`/`span_id` in MDC and record, `traceparent`) | the most expensive stage (an L lane), an alpha artifact in the image, and the correlation id already joins log, record and notification; nobody has asked for Tempo/Jaeger | the draft-2 §7 design (store history 34c34122): Spring's documented route, NOT the community starter; sampling 1.0; a redacting wrapper appender; `TelemetryEgressTest` extended to OTLP endpoints |
| **Reference stack compose** (Collector v0.163.2, Loki v3.7.9, Tempo v3.1.1, Prometheus v3.15.1, Grafana v13.2.4; dashboards; alert rules; CI `docker compose config` check) | demo polish, not product; the "Connecting your platform" snippets cover the hook-up | an M lane after tracing |
| **Per-destination Collector verification** (Loki, OpenSearch live; Elastic/Datadog/Splunk documented) | the app has no Collector-facing surface without OTLP | with tracing/OTLP |
| **Tenant-scoped telemetry export** (#270's second half: per-workspace routes, destination credentials, queues) | no customer; design outline: a *route* on a per-workspace outbox in §8's sense, never a second pipeline | its own record |
| **MCP `problems_list` tool** | agents already get `error` from `executions_get`; the roles row would be ws_admin-only | an S lane with `MCP_TOOL_MIN_SCOPE`/`MCP_TOOL_MIN_PERMISSION` rows |
| **Silent-catch classification** (29 strict + 66 `runCatching` sites, §4.3) | #336's territory; valuable but orthogonal to the flow | an M lane producing the allowlist-with-reasons and `SilentCatchInventoryTest` |
| **Workspace-admin mail for workspace-scoped kinds** | recipients are operators; member mail needs a lookup at send time and consent rules | with the tenant export |
| `telemetry.export_degraded` kind, `telemetry.dropped{signal}` meter | no export path exists | with OTLP |

## 13. Guards this creates (each falsified at birth)
| Guard | What goes red |
|---|---|
| `FailureKindCatalogueTest` | the CHECK constraint, the Kotlin enum and observability's §8 table disagree |
| `FailureBoundaryCoverageTest` | a §4 boundary file lacks a `FailureRecorder` call, or a `log.error(` exists outside the §4 allowlist (`LogLevelDisciplineTest`) |
| `MeterCatalogueParityTest` | a meter constant in code is missing from observability §4.1 or vice versa (#568/#708's class of drift) |
| `LogContextKeysTest` | an MDC key outside the closed `LogContext` list is written anywhere |
| `StructuredLogRedactionTest` (extends #337's) | a planted secret in message/arg/MDC/cause reaches stdout JSON, the failure row, the mail or the webhook body |
| `OutboundEgressTest` | with the webhook unset and mail off, the app makes any outbound connection other than its database during a failing request (socket-level probe) |
| `FailureFlowE2eTest` (integration) | the §15 scenario 1 chain breaks at any hop |
| `BackstopParityTest` | the API and UI handlers map the same throwable to different levels/kinds (G5), or MCP's severity rule differs from REST's (G6) |
| `ObservabilityConfigDocTest` | a `management.*`/`datapipelines.observability.*` key used in code or yml lacks its configuration.md row |
| Helm/compose checks | `helm-check.sh` extends to the management port Service/NetworkPolicy/ServiceMonitor and webhook values; `compose-env-audit.sh` to the new env names |

## 14. Roles and security
| Action (route / tool / verb) | viewer | author | promoter | ws_admin | super_admin | `RestOperation` / tool row | Guard |
|---|---|---|---|---|---|---|---|
| `GET /problems`, `GET /problems/{failure_id}` (UI) | ✗ | ✗ | ✗ | ✓ (own workspace's records + workspace-less records? **no** — only records with its `workspace_id`) | ✓ (all) | NEW `READ_PROBLEMS` — `ScopeMatrix` + auth.md §7.6 same commit | `RoleWalkE2eTest`, `RoleVisibilityRenderTest`, `ReadFloorTest`; filter in SQL (D11), never in the template |
| `GET /api/v1/problems`, `GET /api/v1/problems/{failure_id}` | ✗ | ✗ | ✗ | ✓ (scoped) | ✓ | `READ_PROBLEMS` | `ScopeMatrixSpecDriftTest`, `MatrixRowReachabilityTest`, `RoleWalkE2eTest` |
| `POST /api/v1/problems/{failure_id}/acknowledge` (+ UI verb) | ✗ | ✗ | ✗ | ✓ (scoped) | ✓ | NEW `ACKNOWLEDGE_PROBLEMS` (CSRF path for the HTMX verb) | same three + audit event `problem.acknowledged` |
| `/actuator/prometheus`, `/actuator/health` on the MANAGEMENT port | anonymous (§17 row 11) — the port is the boundary | | | | | no `ScopeMatrix` route and **no `PublicPaths` entry** (amended 2026-10-25 02:2x UTC at C1's brief: `PublicPaths` is auth.md §8.3's APPLICATION-port contract; the permit is scoped to the management context — Boot's `EndpointRequest` matcher or a management-only chain — with the reason "management port, loopback-bound; NetworkPolicy/Service isolation documented" in its KDoc and the §8.6.1 row) | `EntryInventoryE2eTest` (auth.md §8.6.1 rows `anonymous 200`), `ApplicationSmokeTest` (application port stays closed), `PublicPathsTest` unchanged |
| Webhook send (outbound) | no new inbound action | | | | | — | `OutboundEgressTest` (no egress with routes off) |
Notification recipients are operators (`datapipelines.mail.ops-to`, the webhook) — not members; a workspace-scoped record does not mail workspace admins in this record's stages (deferred, §12; the in-app badge covers them).
Security: the webhook secret and mail credentials are env-only, redacted in `toString`; the webhook body carries no stack, no SQL, no
parameter values; the Problems page escapes every field (`th:text`), the stack is pre-redacted text; `/actuator/prometheus` is on the
management port only, never on `PublicPaths`; the webhook URL is operator config, never request input, and is refused unless https (or http to a loopback/RFC1918 host under the development posture) [CALL]; the record's `details` keys are a closed allowlist per kind, enforced at the recorder (unknown key → dropped and counted).

## 15. Acceptance scenarios (the demo IS the test)
1. **One failing pipeline, end to end (integration test):** a pipeline whose node SQL fails on a disposable datasource. Assert, in order:
   the execution row is `failed` with the mapped code; one `failure_records` row with `kind=execution.node_failed`, the same `execution_id`,
   `node_id`, `pipeline_id`, `correlation_id`; the stdout JSON line at ERROR carries `failure_id`, `execution_id`, `node_id`,
   `correlation_id`, the redacted message; `datapipelines.failures.total{kind=execution.node_failed}` incremented by exactly 1 on
   `/actuator/prometheus` (management port, anonymous); GreenMail received one mail whose body contains the `failure_id` and the UI link;
   the webhook stub received one signed JSON with the same id and a valid HMAC; the Problems page lists it and its record page shows the
   identical ids; Acknowledge works and is audited.
2. **The unhandled 500:** a planted `Throwable` behind a test-only route → `kind=request.unhandled`, `code=internal.unhandled`, the REST
   envelope and the UI page carry `failure_id` + `correlation_id`; the client-disconnect case (#562) writes NO record; the same throwable
   through MCP yields `mcp.tool_failed` with a code (no more code-less internal error).
3. **Secrets:** planted `dpk_…`, a password in a JDBC URL and a bearer token in the exception cause → absent from stdout, the mail, the
   webhook body and the database row (the #337 planted-secret method, four sinks).
4. **Egress off:** with the webhook unset and mail off, a failing request causes no outbound connection other than the database
   (socket-level probe); with the webhook set to an unreachable host, scenario 1's app-side assertions still pass and
   `notifications.sent{route=webhook,outcome=failed}` counts.
5. **Storm:** 1 000 identical node failures in a minute → ≤ `max-per-minute-per-kind` rows + `failures.suppressed` counts the rest; one
   grouped mail with the count; no second mail inside the group window; `notifications.suppressed` after `max-per-hour`.
6. **Cost:** p95 request latency and execution wall clock over 50 runs with the recorder on vs. a no-op recorder, within +5 % [CALL].
7. **Hops:** a scheduled run and a dashboard refresh fail → their records carry `schedule_id` / `dashboard_id` and the correlation id the
   run already uses today (the run id / the refresh id), proven by the worker thread's log lines carrying the same MDC set
   (`correlation_id`, `schedule_id` or `dashboard_id`, `execution_id` where one exists) as the record and the `schedule_runs` /
   `dashboard_refreshes` row.
8. **The platform page is honest:** the three "Connecting your platform" snippets are configuration text; the page states they are
   examples, not verified runs, and names the field each filter uses (a docs-audit anchor check, nothing more).

## 16. Lane cut (cut along verification lines; sizes and order are proposals)
| # | Lane | Size | Depends on | Verifies |
|---|---|---|---|---|
| C1 | **Metrics scrape** — Prometheus registry pinned (cold lock/metadata regeneration), `prometheus` exposed on the management port, #231 item 1 decided (anonymous on the management port: a management-context permit + reason — not a `PublicPaths` row, see §14 as amended —, auth.md §8.6.1 rows, `EntryInventoryE2eTest` expectation), the two gauge renames, observability §4.1 cut to the meters that exist + `MeterCatalogueParityTest`, `ObservabilityConfigKeysSpecDriftTest`, the tracing keys removed (row 12), the record copied to `docs/superpowers/specs/` (row 15); smoke test reads a series; #708 answered. Brief `prompts/708-prometheus-scrape-management-port.md` (2026-10-25). **Amended at the brief: size M, not S** (the §8.6.1 rows, the config guard and the cold regeneration) | M | — | §15.1's metric hop |
| C1b | **Helm: the management port reachable by a scraper** — §11's Helm pieces split out of C1 so C1 stays one verification (the JVM scrape): management `containerPort`, a headless Service without ingress, a `NetworkPolicy` template, an optional `ServiceMonitor` (off), `MANAGEMENT_SERVER_ADDRESS` opt-open value, `helm-check.sh` cases, a `HelmManagementValuesSpecDriftTest`, deployment.md §9 rows. Disjoint fence (`deploy/helm/**`, `scripts/helm-check.sh`, one app test, deployment.md); may run beside C1 | S | — (C1 for the scrape to be real) | §15.1's metric hop in Kubernetes |
| C2 | **Failure record + recorder + kinds + codes** — V5x, `FailureRecorder` port (Spring-free module, JDBC impl in web beside `WebEventEmitter`), wired at the two HTTP backstops + MCP + `WebEventEmitter`'s terminal events; one shared `logAt`/severity rule for REST, UI, MCP (G5, G6); `internal.unhandled`, `request.method_not_allowed`; MCP adopts the filter's correlation id (G11); envelope gains `failure_id`; `failures.total`; guards `FailureKindCatalogueTest`, `FailureBoundaryCoverageTest`, `BackstopParityTest`; observability §8.1 rewritten (#568 closed) | L | C1 | §15.2, §15.1 record hop |
| C3 | **Boundaries + log context** — scheduler run, dashboard refresh, housekeeping jobs write records with their identities (G2, G3, G7, G8); the ten-key `LogContext` in one module, the four `MdcTask` copies collapsed (G12); launcher scope carries the context; `service`/`version`/`environment` members; format defaults agree per shape (G14); `LogLevelDisciplineTest`, `LogContextKeysTest`, `StructuredLogRedactionTest` extended to the ten keys | M | C2 | §15.1 log hop, §15.7 |
| C4 | **Notifications** — after 442b lands: shared `modules/notifications` lifted from 442b's sender/routes/delivery state, `failure_notifications` table, mail + webhook (HMAC) routes, grouping and rate bounds, `notifications.sent/suppressed`, `OutboundEgressTest`; Helm/compose webhook values | L | C3, 442b | §15.1 mail/webhook hops, §15.4, §15.5 |
| C5 | **Problems view** — family page + REST twin + Acknowledge + roles rows + in-app unseen badge + ui-screens | M | C2 | §15.1 UI hop |
| C6 | **Docs convergence + "Connecting your platform" + demo runbook** — observability.md rewritten to the record (every section true, §5 tracing marked deferred), configuration §3.15/§3.36, rest-api §4.2, deployment.md (management port scrape, Helm values), the platform page with three snippets + alert-rule examples, the demo runbook, the marketing-site "when something goes wrong" copy (coordinate with #699's lane, which owns site copy); #270 updated with the deferred list | M | all | §15.8, the reader test of §1 |

C1 is dispatchable the day §17 is ratified; C2 → C3 follow; C5 can run beside C3 (disjoint fences: UI family vs. dag/scheduler/web
boundaries); C4 waits for 442b; C6 last. Six lanes (1 S, 2 M + 1 M, 2 L) against the eight of draft 2 (3 M, 5 L).

## 17. Calls to ratify (the owner rules each row; "yes" = as written; the owner may rule "all as recommended" and veto by number)
**RATIFIED 2026-10-25 02:06 UTC** — all 17 rows ruled by the owner, each as recommended, asked one at a time with options in chat: rows 6, 9, 11, 13 at 01:25 UTC; rows 1–5, 7, 8, 10, 12, 14 between ~01:3x and ~02:0x UTC; rows 15–17 at 02:06 UTC. C1 is dispatchable; the record moves to `docs/superpowers/specs/` in C1's commit (row 15).
| # | Call | Alternatives considered | Recommendation |
|---|---|---|---|
| 1 | A durable **failure record table** is the spine; the log is the fallback, never the primary | log-only (grep is the record); audit_log reuse (wrong purpose, §7 of observability) | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 2 | `failure_id` in the REST 5xx envelope and the UI 500 page beside the correlation id | correlation id only | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 3 | New codes `internal.unhandled` and `request.method_not_allowed`; the backstops' `Execution.ABORTED` stand-in retired; MCP's internal error gets a code | keep the stand-in | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 4 | **Keep #337's logstash-shaped JSON and encoder**; add `service`/`version`/`environment` members; defaults agree per shape (image/compose/Helm `json`, dev `console`) | switch to ECS (re-earns five guard classes for a naming preference) | keep **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 5 | Snake_case **ten** MDC keys = DB column names, in ONE shared constants module | camelCase; keep per-module copies | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 6 | The executor never calls the recorder; `WebEventEmitter` writes execution records from the events it already persists; record first, terminal UPDATE second; no atomicity promise between them | a `FailureRecorder` port injected into dag and called in `failNode` (dag gains a write dependency; two writers of execution failure state) | as written **RULED 2026-10-25 01:25 UTC (owner: recommended)** |
| 7 | Notifications generalize **442b's outbox** into `modules/notifications` after it lands; failure records are the second producer; delivery state in its own `failure_notifications` table shaped like 442b's deliveries | separate failure notifier; notify from the log appender; delivery columns on the record | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 8 | Routes: mail + **webhook (HMAC-SHA256)** + in-app; no vendor paging integrations; alert-rule examples as docs snippets | PagerDuty/Slack SDKs | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 9 | Defaults: severity floor `error`; recipients = `datapipelines.mail.ops-to` (today the "new user" notice sink, application.yml:507 — reusing it widens its meaning to "operators"; configuration.md §3.27 says so); group window 10 min; 60/h cap; 600 rows/min/kind; 90-day retention; 40 frames; webhook timeout 5 s | mail the super admins' addresses (member lookup at send time) | as written (all tunable config) **RULED 2026-10-25 01:25 UTC (owner: recommended)** |
| 10 | **Problems view**: family page + REST twin + Acknowledge + in-app badge; no MCP tool (deferred) | UI only; with MCP tool | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 11 | **#231 decided**: on the MANAGEMENT port (loopback-bound by default) `/actuator/health` and `/actuator/prometheus` are anonymous — the port is the boundary; Helm adds the management containerPort, a headless Service without ingress and a NetworkPolicy template; auth.md §8.6.1 row rewritten | keep the port authenticated and give scrapers an API key with a new `METRICS_READ` scope (a key in every Prometheus config, outliving rotation discipline) | anonymous on the management port **RULED 2026-10-25 01:25 UTC (owner: recommended)** |
| 12 | Remove `datapipelines.observability.tracing.*` keys (never wired), no deprecation window | keep as aliases | remove **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 13 | observability §4.1 cut to the 44 meters that exist; the two un-prefixed gauges renamed under `datapipelines.`; the 13 documented-only meters dropped, not marked "planned" | keep a "planned" table | as written **RULED 2026-10-25 01:25 UTC (owner: recommended)** |
| 14 | "Connecting your platform" page = three shipper snippets (Alloy, Fluent Bit, Datadog agent) + Prometheus scrape + alert-rule examples, stated as examples, not verified runs | ship a verified compose (deferred, §12) | as written **RULED 2026-10-25 (owner: recommended; asked one at a time ~01:3x–02:0x UTC, recorded 02:06 UTC)** |
| 15 | Record lives in the store until ratified, then `docs/superpowers/specs/…` in C1's commit | product repo now | as written **RULED 2026-10-25 02:06 UTC (owner: recommended)** |
| 16 | Lane order C1→C2→C3→(C4 after 442b ∥ C5)→C6; C1 dispatchable on ratification | — | as written **RULED 2026-10-25 02:06 UTC (owner: recommended)** |
| 17 | The §12 DEFERRED list is deferred as a whole (ratifies the owner's 2026-10-25 cut in writing); #270 stays open as the umbrella carrying it | — | as written **RULED 2026-10-25 02:06 UTC (owner: recommended)** |

## 18. Corrections to existing documents this record implies
observability.md: §4.1 (cut to the 44 existing meters + the new ones; phantom `errors.total` removed — #568), §4.2 (true after C1,
with the #231 answer), §5 Tracing (rewritten to "not provided; deferred, see the record's §12" — never "declared and not wired"), §8.1
(rewritten around the failure record and `failures.total{kind}`), §9 (tracing keys removed; notification keys added), new "Connecting
your platform" page; configuration.md §3.15 + new §3.36; rest-api.md §4.2 (`failure_id`); auth.md §7.6 rows for §14 and §8.6.1's actuator
row (#231); ui-screens.md (Problems family); deployment.md (management port scrape, Helm values, no observability compose); scheduler.md
§6.1/6.2 (a note that the outbox generalizes after 442b — not a change to 442b's contract); pipeline-contract §13 + enums §16
(`internal.unhandled`, `request.method_not_allowed`); module-structure.md §5.16 + the module-boundary tests for any new module. #568 and
#708 are resolved by C2 and C1; #231 by C1; #270 stays the umbrella and gets the §12 list as its deferred half; this record is linked from
all four.

## 19. Source register
Read this session (2026-10-24/25) unless marked: GitHub #270 (open umbrella; acceptance list), #568 (phantom counter), #708 (no Prometheus registry; filed this session), #231 (open, management-port auth; `gh issue view 231 --json`), #336/#337 (closed; #337 merge `1f00f1bd9`), #562 (client disconnect), #266 (audit durability), #310 (retention), #442a/#442b (schedule notifications; 442b IN FLIGHT, brief `prompts/442b-schedule-notification-delivery.md`), #137 (mail notices), #207 (`auth.base-url`); store `design-records/2026-09-07-scheduler-requirements.md` §6.1 :686-746 / §6.2 :748-790 (notification policy and content :721-726); `docs/scheduler.md:74`; `docs/research/2026-09-28-exceptions-and-telemetry-audit.md` (201 lines; §6 export shape, §9 evidence list); `docs/observability.md` v1.45 (697 lines; sections and lines per §3/§4 above); `docs/configuration.md` (§1 :13, §3.14 :221-237, §3.15 :239-245, §3.27 :454-474, §3.29 :522, §7 :1030/:1070/:1073); `docs/rest-api.md` §4.2 :109-140; `docs/pipeline-contract.md` §13 :1085-1639; `docs/enums.md` §16 :509-547; `docs/dag-executor.md` §8 :1064-1303; `docs/auth.md` §7.6, §8.3, §8.6.1 :1381-1382; `docs/ROADMAP.md` §3.10 :196-201, :241; `docs/deployment.md:784`; `docs/superpowers/specs/2026-10-13-release-dependencies-design.md` (the house structure exemplar, 496 lines); the product files cited by file:line throughout §3–§4 (read by the three fenced inventory subagents on 0d4ddd5b5; no worktree read). External, read 2026-10-24: Spring Boot 3.5.16 reference (actuator tracing/metrics/OTLP/structured-logging keys as listed in §7 and §11), Boot 3.5 managed versions (micrometer 1.15.12, micrometer-tracing 1.5.12, opentelemetry 1.49.0, logback 1.5.34, prometheus-metrics 1.3.10); `opentelemetry-logback-appender-1.0` 2.33.0-alpha (Maven Central / instrumentation README: `OpenTelemetryAppender.install(openTelemetry)`, `mdcAttributesIncluded/Excluded`, `captureCodeAttributes`, `numLogsCapturedBeforeOtelInstall=1000`); `opentelemetry-spring-boot-starter` 2.31.1/2.32.0 compatibility note (overrides Boot's BOM pins); GitHub releases: Loki v3.7.9 (2026-10-20), Tempo v3.1.1 (2026-10-21), Prometheus v3.15.1 (2026-10-21), Grafana v13.2.4 (2026-10-13), OTel Collector contrib v0.163.2 (2026-10-21); Grafana Agent EOL 2025-11-01 (Alloy is its successor). Toolchain: Spring Boot 3.5.16, Kotlin 2.4.21, JDK 21 (`buildSrc/…/CommonConventionsPlugin.kt:87-92`; `gradle/libs.versions.toml`).
