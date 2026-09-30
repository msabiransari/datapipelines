# Boundary-module exception audit

[Main report](2026-09-28-exceptions-and-telemetry-audit.md). This is a source snapshot, not a delivery tracker.

Read-only source audit on 2026-09-28, HEAD `e4cd69e2d358ed61e9e40a583c8bf470665f477e`.

## Scope and method

Git-tracked production Kotlin under `modules/{app,application,auth,mcp-server,web}/src/main`. There is no metadata module; metadata persistence is distributed across existing modules. No implementation changes, tests or deployment performed. Working tree had pre-existing design documents; they were not touched.

Enumerated all source matches of `\bcatch\s*\(|\brunCatching\b` through `git grep -n -E` with an explicit `modules/<name>/src/main/**/*.kt` pathspec for each module. Checked `getOrNull`, `getOrDefault`, `getOrElse`, `recover`, `recoverCatching`, `onFailure`, reactive error operators, `SwallowedException`, and `Result.failure` separately. Read every candidate with surrounding code and traced the important caller consequences. Excluded three comment-only matches (JwtAuthenticationFilter:38, WebEventEmitter:195, ApiKeyForm:165); collection getOrNull/getOrDefault/getOrElse calls are not exception handling. Result consumers paired with runCatching are counted once, at runCatching. Five independent Result.getOrElse consumers are listed separately.

Complete inventory: the complete table below, 230 rows: 163 catch clauses, 62 runCatching calls, 5 independent Result.getOrElse consumers. Catch/runCatching module counts: app 3, application 18, auth 29, mcp-server 34, web 141. These are lexical sites, not runtime failures or proven bugs. No runtime fault injection was performed; library-internal handling and failures outside these source boundaries are outside this sub-audit.

Classification (225 catch/runCatching sites): **S 13** silent operational/diagnostic-loss candidates; **F 33** narrow/intentional fallback candidates; **L 52** logged suppression/degradation; **H 127** explicit error translation/rethrow/refusal/error-bearing outcome. Five independent Result consumers are H, bringing H to 132 for the 230-row inventory. These classes are a triage aid: H does not imply complete diagnostics; S can still expose degraded status (e.g. health DOWN) while losing cause. F still deserves narrowing of broad runCatching where feasible. L includes debug-only logs, which ordinarily will not reach production collectors.

## Main findings and caller impact

1. **False empty state in workspace UI**: `WorkspacesUiController.kt:103,114` wraps service/database access with runCatching and returns null/emptySet. The listing builder then constructs empty member/invitation lists and reports all shown members as not holding a live login key. Permission refusals and database/programming failures collapse to the same state, with no local log. This is an operationally significant place to distinguish unavailable from empty.
2. **Silent loss of execution evidence**: `WebEventEmitter.kt:280` discards resolved context serialization failures, retaining insert-time context while still completing the row; `:311` turns any read failure deriving aborted duration into 0 ms. Both erase diagnostic information. Separately, `:188,209,281` log and suppress durable event creation, execution-row creation, and terminal-row completion failures. Ordinary executions continue; the scheduled launch opts into failClosedOnRecord and refuses before nodes run if initial row creation fails. The fail-closed switch does not make later events/completion durable. The result-column writes in ExecutionStreamLauncher:345, RecordingExecutionRunner:168 and SubPipelineExecutionRunner:661 also log and suppress failed persistence.
3. **Persisted data corruption hidden in views and scheduler classification**: `NodeOperationHistory.kt:50` silently drops unreadable progress events. `PipelineBrowseModel.kt:253` renders no parameters when stored pipeline parsing fails. `PipelineLifecycleDialogModel.kt:167` builds a release dialog with no pins/checks and `refusal=null` after a malformed draft; the subsequent POST is still independently guarded, so this audit does not claim an invalid release is accepted. `PipelineJobExecutor.kt:420,428` defaults away malformed persisted error/abort payloads: a lost `instance_lost` marker can classify ABORTED instead of UNKNOWN, and a lost cancelled reason can classify ABORTED instead of CANCELLED.
4. **Optional MCP checks silently skipped**: `SemanticsTools.kt:153` catches every Exception during catalog introspection and skips text/ref agreement and automatic reference addition. Explicit reference resolution in `service.record` remains authoritative. `EntryPointChecks.kt:108,112` intentionally skips the learn-before-use check when the datasource/catalog cannot be read. None logs the skipped check locally. Treat these as explicit fail-open policy decisions with missing observability, not a claim that datasource access control is bypassed.
5. **Health and startup diagnostics disappear**: `HealthController.kt:83` and `StagingHealthIndicator.kt:37` correctly return DOWN but drop the failure cause without a local log. `VendoredThemes.kt:33` turns any classpath enumeration failure into absent assets; settings shows no themes and ConfigValidator defers startup theme validation. A dependency outage should be visible without exposing internals in public health responses.
6. **Logged does not mean recovered**: auth AuditLogger:49 and MCP audit wrappers (McpResourceReader:291, McpToolDispatcher:220, PipelineExecuteTool:306) let the user action/read complete without an audit record. Redis invalidation publishers (DatasourceInvalidationConfiguration:124, EndpointsConfiguration:291) let committed changes succeed while peers continue with stale cached state until a later refresh/reconciliation. Datasource reconciliation failure :206 waits for a later subscription. DemoEndpointSeeder:292 skips publishing; :386 leaves stale demo endpoints. These have logs but no reliable-delivery proof in this audit.
7. **Async mail failure boundary loses interruption/recording detail**: `MailNotifier.kt:202` logs only `e.toString()` and ends the task after `record(..., sendWithRetry(...))` fails. Its try includes retry `Thread.sleep` at :229, so an InterruptedException is consumed without restoring interruption. The preexisting claim prevents duplicate delivery; a failure recording a successful send can leave uncertain delivery status. Do not add unbounded retries: post-send failures can be ambiguous.
8. **Overbroad Throwable handling**: 62 runCatching calls catch Throwable by Kotlin semantics; `CookieOAuth2AuthorizationRequestRepository.kt:123` explicitly catches Throwable (including Errors), logs at DEBUG, and treats the cookie as absent. Most pure UUID/date parsing sites are low risk, but broad handling around I/O/persistence and callbacks can also hide interruption, cancellation, programming defects, and serious VM errors. Narrow by boundary rather than mechanically rethrowing every catch.

## Additional diagnostic gaps in handled outcomes

`application/checks/PipelineCheckRunner.kt:193` returns an explicit failed-check outcome, so it is H rather than silent success, but stores only a generic resolution-error message and never consumes/logs `resolution.exceptionOrNull()`. Original DB/programming cause is lost. Protocol translations such as `PromotionTargetClient.kt:203,208` and several input-validation sites replace parse failures with safe catalogued refusals without retaining a cause; appropriate for expected hostile input but worth distinguishing from upstream schema defects.

`DemoWorkspaceSeeder.kt:107` is intended duplicate-insert race recovery, but the try spans not only workspace creation but datasource grants and content seeding (:85–105). A DuplicateKeyException from those later operations also rereads the already-created workspace and is consumed. This is a static scope-risk observation, not a reproduced seeding defect.


## Complete classified inventory

Every discovered handler is listed, including explicit failures and rethrows. Source paths are repository-relative.

| Site | Construct | Class | Disposition |
|---|---|---|---|
| `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt:769` | runCatching | F | Calendar day parser returns false for invalid input; caller records config violation. |
| `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt:1001` | runCatching | H | Base64 decode becomes explicit configuration violation. |
| `modules/app/src/main/kotlin/co/datapipelines/health/HealthController.kt:83` | runCatching | S | Probe failure becomes DOWN with no local log or retained cause. |
| `modules/application/src/main/kotlin/co/datapipelines/application/ExecutionLauncher.kt:158` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/CheckExpectationComparator.kt:210` | runCatching | F | Non-numeric text becomes null numeric value and expectation mismatch. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/PipelineCheckRunner.kt:193` | runCatching | H | Datasource resolution error becomes check error outcome; underlying Throwable is discarded with no local log. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/PipelineCheckRunner.kt:226` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/PipelineCheckRunner.kt:228` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/PipelineCheckRunner.kt:230` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/PipelineCheckRunner.kt:232` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/checks/PipelineCheckRunner.kt:234` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeManifestUrl.kt:162` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeManifestUrl.kt:193` | runCatching | F | Non-URI catalog.ref does not contribute a URI authority/root. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeManifestUrl.kt:255` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeManifestUrl.kt:257` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeManifestUrl.kt:259` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeManifestUrl.kt:264` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeTableRegistryService.kt:110` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/EndpointMatcher.kt:57` | runCatching | L | Unparseable stored pattern omitted; EndpointRegistry.reload logs ERROR count mismatch at line 73. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/EndpointRequestValidator.kt:259` | runCatching | F | Invalid decimal becomes null for parameter validation refusal. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/PublishedEndpointRepository.kt:113` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/ApiKeyFilter.kt:117` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/ApiKeyFilter.kt:142` | catch | L | WARN class-only; usage timestamp lost, authentication continues. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/AuditLogger.kt:49` | catch | L | WARN with Throwable; audit insertion lost, caller continues. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthErrorWriter.kt:101` | runCatching | F | Malformed inbound correlation UUID discarded; fresh ID used. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/ClientAddressResolver.kt:175` | runCatching | F | Invalid literal IP becomes null and is not trusted. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/CookieOAuth2AuthorizationRequestRepository.kt:123` | catch | L | DEBUG string-only catches Throwable including Errors; invalid/unreadable cookie treated as absent. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/DemoWorkspaceSeeder.kt:107` | catch | F | DuplicateKey race rereads demo workspace; try also encloses grants/content seed so duplicate there can be misclassified. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtAuthenticationFilter.kt:120` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtAuthenticationFilter.kt:122` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtAuthenticationFilter.kt:124` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtAuthenticationFilter.kt:126` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtService.kt:133` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtService.kt:135` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtService.kt:146` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/LocalPasswordService.kt:169` | catch | H | Duplicate account becomes EmailTaken; DEBUG exception log. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/MailNotifier.kt:202` | catch | L | WARN string-only; async dispatch/record failure ends task and may leave claim without outcome; InterruptedException from retry sleep also consumed. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcSignedInBounceFilter.kt:70` | catch | L | DEBUG with cause; unusable session permits normal login flow. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcSignedInBounceFilter.kt:73` | catch | L | DEBUG with cause; unusable session permits normal login flow. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcSignedInBounceFilter.kt:76` | catch | L | DEBUG with cause; unusable session permits normal login flow. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcSuccessHandler.kt:158` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionServerKeyFilter.kt:122` | runCatching | F | Only AuthException becomes null/rejected auth; all other throwables rethrown. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionServerKeyFilter.kt:139` | runCatching | L | WARN class-only; usage timestamp lost, promotion continues. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/SpringMailSender.kt:46` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/SpringMailSender.kt:48` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/UserService.kt:78` | catch | F | Expected concurrent insert race rereads winner, checks row exists; no local exception log. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/UserService.kt:182` | catch | F | Expected concurrent insert race rereads winner, checks row exists; no local exception log. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/UserService.kt:231` | catch | F | Expected concurrent insert race rereads winner, checks row exists; no local exception log. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/WorkspaceResolutionFilter.kt:85` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/auth/src/main/kotlin/co/datapipelines/auth/WorkspaceService.kt:250` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/DatasourcePreviewRowsTool.kt:163` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/DatasourcePreviewRowsTool.kt:170` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/DatasourceSchemaTools.kt:329` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/DatasourceSchemaTools.kt:336` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/EntryPointChecks.kt:108` | catch | F | Unreadable/invisible datasource skips learn-before-use gate; execution/probe retains validation authority. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/EntryPointChecks.kt:112` | catch | F | Unreadable/invisible datasource skips learn-before-use gate; execution/probe retains validation authority. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpArguments.kt:33` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpAuthFilter.kt:131` | runCatching | F | Invalid correlation UUID replaced with random UUID. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpPromptCatalog.kt:48` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceCursor.kt:47` | runCatching | F | Invalid base64 becomes null cursor; caller rejects invalid cursor. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceReader.kt:72` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceReader.kt:75` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceReader.kt:291` | catch | L | WARN Throwable; resource-read audit lost, read result retained. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceRequestHandler.kt:67` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceRequestHandler.kt:70` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpResourceUri.kt:234` | runCatching | F | Invalid UUID means URI cannot parse. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpToolDispatcher.kt:85` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpToolDispatcher.kt:97` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpToolDispatcher.kt:100` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpToolDispatcher.kt:220` | catch | L | WARN Throwable; tool audit lost, result retained. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/PipelineExecuteTool.kt:306` | catch | L | WARN Throwable; launch audit lost, launch continues. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/PipelineExecuteTool.kt:318` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/PipelinesExecuteNodeTool.kt:100` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SemanticsTools.kt:153` | catch | S | Any Exception from catalog introspection skips text/reference consistency and auto-added refs; service record still validates explicit refs. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SemanticsTools.kt:206` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SemanticsTools.kt:278` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SqlProbeTool.kt:176` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SqlProbeTool.kt:183` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SqlProbeTool.kt:185` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SqlProbeTool.kt:187` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/SqlProbeTool.kt:204` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/TemplateAuthoringTools.kt:97` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/TemplatesEvaluateTool.kt:75` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/TemplatesEvaluateTool.kt:87` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/api/CorrelationId.kt:46` | runCatching | F | Invalid inbound correlation UUID replaced with random UUID. |
| `modules/web/src/main/kotlin/co/datapipelines/web/api/RequestBodies.kt:34` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/DemoEndpointSeeder.kt:292` | catch | L | ERROR message-only; endpoint publish skipped, boot and remaining endpoints continue. |
| `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/DemoEndpointSeeder.kt:386` | catch | L | ERROR message-only; stale endpoint retirement skipped, stale route remains, boot continues. |
| `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/ExampleContentSeeder.kt:178` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/ExampleContentSeeder.kt:207` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/ExampleContentSeeder.kt:213` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/config/DatasourceInvalidationConfiguration.kt:124` | catch | L | WARN message-only; committed save succeeds while peers may retain stale pools until restart/reconciliation. |
| `modules/web/src/main/kotlin/co/datapipelines/web/config/DatasourceInvalidationConfiguration.kt:164` | catch | L | WARN message-only; malformed invalidation dropped. |
| `modules/web/src/main/kotlin/co/datapipelines/web/config/DatasourceInvalidationConfiguration.kt:206` | catch | L | WARN message-only; reconciliation deferred until next subscription. |
| `modules/web/src/main/kotlin/co/datapipelines/web/config/EndpointsConfiguration.kt:291` | catch | L | WARN message-only; committed write succeeds while peers retain previous registry until reload. |
| `modules/web/src/main/kotlin/co/datapipelines/web/config/EndpointsConfiguration.kt:318` | catch | L | WARN message-only; malformed invalidation dropped. |
| `modules/web/src/main/kotlin/co/datapipelines/web/datasources/DatasourceSchemaController.kt:183` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/datasources/DatasourceSchemaController.kt:190` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/datasources/DatasourceWorkspaceRules.kt:184` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/endpoints/PublishedEndpointController.kt:202` | runCatching | F | Invalid paging UUID falls through to ordinary served path matching. |
| `modules/web/src/main/kotlin/co/datapipelines/web/endpoints/PublishedEndpointServeService.kt:266` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/endpoints/PublishedEndpointServeService.kt:273` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/executions/ExecutionsController.kt:274` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/health/StagingHealthIndicator.kt:37` | catch | S | Any probe/create/close Exception becomes DOWN without cause/log. |
| `modules/web/src/main/kotlin/co/datapipelines/web/parameters/ParameterSetTransferService.kt:105` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/parameters/ParameterSetTransferService.kt:194` | runCatching | F | Unparseable UUID/body becomes null; surrounding import validation refuses malformed payload. |
| `modules/web/src/main/kotlin/co/datapipelines/web/parameters/ParameterSetTransferService.kt:321` | runCatching | F | Unparseable UUID/body becomes null; surrounding import validation refuses malformed payload. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/ExecutionStreamLauncher.kt:297` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/ExecutionStreamLauncher.kt:301` | catch | H | Known failure/abortion already streamed; pre-stream failure propagated through failBeforeStart. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/ExecutionStreamLauncher.kt:303` | catch | H | Known failure/abortion already streamed; pre-stream failure propagated through failBeforeStart. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/ExecutionStreamLauncher.kt:311` | catch | L | Before stream starts error propagated; after any event log ERROR and close without synthesizing failure event here. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/ExecutionStreamLauncher.kt:345` | runCatching | L | WARN Throwable; result size/row metadata write lost, execution result returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineExecuteController.kt:166` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineExecuteController.kt:181` | catch | F | Serializer probe returns false so caller identifies invalid parameter and raises refusal. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineImportService.kt:90` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineImportService.kt:172` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineImportService.kt:231` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineImportService.kt:289` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionTargetClient.kt:93` | catch | L | WARN structured code/reason; cached inventory Unreachable used instead of throwing from lens. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionTargetClient.kt:184` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionTargetClient.kt:186` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionTargetClient.kt:203` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionTargetClient.kt:208` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/RecordingExecutionRunner.kt:168` | runCatching | L | WARN Throwable; result size/row metadata write lost, execution result returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/SubPipelineExecutionRunner.kt:336` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/SubPipelineExecutionRunner.kt:339` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/SubPipelineExecutionRunner.kt:407` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/SubPipelineExecutionRunner.kt:661` | runCatching | L | WARN Throwable; child result size/row metadata lost, result returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ratelimit/RateLimiter.kt:145` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/requestlimits/RequestBodyCapFilter.kt:73` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:165` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:212` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:234` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:334` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:337` | catch | H | Pre-record exception completes deferred exceptionally; post-record logs DEBUG, durable execution record is outcome authority. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:350` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:354` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:358` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:420` | runCatching | S | Malformed persisted error JSON loses instance_lost discrimination; terminal outcome may become ABORTED rather than UNKNOWN. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:428` | runCatching | S | Malformed persisted abort event loses cancelled reason; may become ABORTED rather than CANCELLED. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/PipelineJobExecutor.kt:585` | catch | F | Invalid stored timezone becomes null; prepare explicitly refuses inputs. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/SchedulesController.kt:271` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/schedules/SlotCapacityGate.kt:37` | catch | F | Capacity exhaustion becomes null lease; scheduler records capacity retry. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStream.kt:126` | catch | L | DEBUG; disconnected/already-completed emitter marked disconnected, false returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStream.kt:131` | catch | L | DEBUG; disconnected/already-completed emitter marked disconnected, false returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStream.kt:156` | catch | L | DEBUG; disconnected/already-completed emitter marked disconnected, false returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStream.kt:160` | catch | L | DEBUG; disconnected/already-completed emitter marked disconnected, false returned. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStream.kt:209` | runCatching | F | Revocation comment send Throwable discarded; stream still closed, revocation logged before attempt. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStream.kt:219` | runCatching | L | DEBUG Throwable; emitter completion failure retained only in log. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStreamAuthority.kt:101` | catch | H | WARN Throwable; authority recheck fails closed by revoking stream. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStreamRegistry.kt:121` | runCatching | L | WARN Throwable; metrics hook failure does not prevent stream close. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStreamRegistry.kt:149` | catch | L | WARN Throwable; tick stops at failed stream but scheduler remains for next tick. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseEventLog.kt:54` | catch | L | WARN Throwable; Redis replay append failure causes incomplete replay, execution continues. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseEventLog.kt:73` | catch | L | WARN Throwable; Redis replay read failure returns null like expired/absent log. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseEventLog.kt:79` | runCatching | L | WARN Throwable; corrupt replay event skipped. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseLogStreamer.kt:142` | catch | L | WARN Throwable; follow tick failure cancels/completes stream. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseLogStreamer.kt:224` | runCatching | F | Revocation comment send Throwable discarded; stream still cancelled/completed, revocation logged beforehand. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseLogStreamer.kt:249` | catch | L | DEBUG message; disconnected/completed emitter returns false. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseLogStreamer.kt:253` | catch | L | DEBUG message; disconnected/completed emitter returns false. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/SseLogStreamer.kt:263` | runCatching | L | DEBUG Throwable; completion failure ignored after follow removal. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:149` | runCatching | L | WARN Throwable; stream registration/start hook failure does not stop run. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:183` | runCatching | L | WARN Throwable; durable-row notification hook failure does not stop run. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:188` | runCatching | L | WARN Throwable; durable execution event missing, processing continues. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:209` | runCatching | L | ERROR Throwable; missing RUNNING row tolerated by ordinary execution, scheduled failClosedOnRecord raises explicit exception. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:280` | runCatching | S | Resolved context serialization failure becomes null; terminal update keeps original context, cause lost. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:281` | runCatching | L | ERROR Throwable; terminal row update failure tolerated, row can stay RUNNING until reconciliation. |
| `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt:311` | runCatching | S | DB read failure deriving aborted duration becomes 0 ms without log. |
| `modules/web/src/main/kotlin/co/datapipelines/web/templates/TemplatesController.kt:613` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/templates/TemplatesController.kt:627` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/templates/TemplatesController.kt:798` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/templates/TemplatesController.kt:816` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AdminUsersPartialController.kt:235` | catch | H | WARN code and explicit partial-success user message after account created but workspace assignment refused. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/ApiKeyForm.kt:168` | runCatching | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:123` | runCatching | L | Bad URI logged as avatar.refused; no remote fetch. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:273` | catch | L | INFO reason; avatar fetch failure returns null/fallback; interrupt flag restored at 276. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:276` | catch | L | INFO reason; avatar fetch failure returns null/fallback; interrupt flag restored at 276. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:296` | runCatching | F | Best-effort InputStream.close Throwable discarded during deadline/shutdown/finally cleanup. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:297` | catch | L | INFO reason; avatar fetch failure returns null/fallback; interrupt flag restored at 276. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:301` | runCatching | F | Best-effort InputStream.close Throwable discarded during deadline/shutdown/finally cleanup. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:311` | runCatching | F | Best-effort InputStream.close Throwable discarded during deadline/shutdown/finally cleanup. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:322` | catch | L | INFO reason; avatar fetch failure returns null/fallback; interrupt flag restored at 276. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt:360` | catch | L | INFO reason; avatar fetch failure returns null/fallback; interrupt flag restored at 276. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourcePartialController.kt:181` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourcePartialController.kt:305` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourcePartialController.kt:377` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourceSchemaTreeBrowseModel.kt:128` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourceSchemaTreeBrowseModel.kt:130` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourceSchemaTreeBrowseModel.kt:132` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/ExecutionDetailPartialController.kt:49` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/ExecutionHistoryBrowseModel.kt:59` | runCatching | F | Invalid status filter silently ignored; all statuses eligible. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/ExecutionHistoryBrowseModel.kt:120` | runCatching | F | Instant parse falls back to date; invalid date silently removes date filter. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/ExecutionHistoryBrowseModel.kt:121` | runCatching | F | Instant parse falls back to date; invalid date silently removes date filter. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/NavCounts.kt:84` | runCatching | L | WARN string-only; DB count failure yields unavailable counts. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/NodeOperationHistory.kt:50` | runCatching | S | Malformed persisted progress event silently dropped from operation history. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineBrowseModel.kt:253` | runCatching | S | Stored body parse failure renders detail with no parameters rather than warning; comment says editor repairs body. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineChecksPartialsController.kt:263` | runCatching | F | Stored observed JSON parse failure displays original JSON string. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineLifecycleDialogModel.kt:167` | runCatching | S | Malformed draft becomes no pins/no checks and refusal=null in release dialog; POST remains separate guard. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineLifecycleDialogModel.kt:218` | catch | F | Only template-not-found race returns zero other pinners; all other domain exceptions rethrow. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineNodeSqlPartialController.kt:102` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineNodeSqlPartialController.kt:172` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PromotionUiController.kt:79` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/PromotionUiController.kt:109` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplateEditorController.kt:151` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplateEditorController.kt:215` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplateEditorController.kt:217` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplateEditorController.kt:224` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplatePartialController.kt:231` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplatePartialController.kt:246` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplateTransformFaceController.kt:144` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TemplateTransformFaceController.kt:146` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TransformFaceModel.kt:198` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/TransformSuiteRun.kt:58` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/VendoredThemes.kt:33` | runCatching | S | Classpath enumeration Throwable becomes absent theme assets; settings empty and startup theme check deferred. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:103` | runCatching | S | Membership/invitation service failure becomes null listing then empty members/invitations with no log. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:114` | runCatching | S | Key-owner query failure becomes empty set; UI reports no members holding live login keys. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:259` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:261` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:267` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:363` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:394` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/WorkspacesUiController.kt:398` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/ui/site/SiteDemoData.kt:171` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/web/src/main/kotlin/co/datapipelines/web/workspace/RedisLastUsedWorkspaceStore.kt:33` | catch | L | WARN Throwable; login falls back to first membership. |
| `modules/web/src/main/kotlin/co/datapipelines/web/workspace/RedisLastUsedWorkspaceStore.kt:44` | catch | L | WARN Throwable; workspace switch succeeds without remembered preference. |
| `modules/web/src/main/kotlin/co/datapipelines/web/workspaces/WorkspacesController.kt:170` | catch | H | Explicit error translation, rethrow, refusal, or error-bearing outcome; not silently successful. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/EndpointKeyService.kt:245` | Result.getOrElse | H | Failed path parse Result translated to domain error. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/EndpointPath.kt:229` | Result.getOrElse | H | Failed segment parse Result propagated as Result.failure. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/EndpointPublishService.kt:82` | Result.getOrElse | H | Failed path parse Result translated to domain error. |
| `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/PublishedEndpointRepository.kt:205` | Result.getOrElse | H | Failed stored path parse Result becomes explicit Legacy row retaining reason, not normal enabled endpoint. |
| `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/DemoEndpointPaths.kt:30` | Result.getOrElse | H | Failed demo path parse Result translated to IllegalArgumentException. |

## Explicit non-findings

Do not equate every catch with swallowing. Examples reviewed: domain exceptions translated to REST/MCP errors; malformed input reported as refusal; SpringMailSender returns SendOutcome.Failed; rate limiter :145 logs and fails closed via failClosed; SSE authority recheck :101 logs and revokes; cancellation is rethrown in SubPipelineExecutionRunner :336 and PublishedEndpointServeService :266; duplicate races in UserService re-read and validate the winner. EndpointMatcher :57 is locally silent but **EndpointRegistry.reload :73 logs ERROR when the matchable count drops**. That caller tracing prevents falsely reporting it as invisible. Source suppression annotations also occur around valid error translations.

Handling should have a declared outcome: propagate to a boundary, translate to a visible refusal/failure, retry within an idempotent bounded policy, or degrade with a log/metric and a visible unavailable state. Sending logs externally cannot recover an exception discarded before a log record exists, and a successful exporter cannot repair a missing execution/audit record.
