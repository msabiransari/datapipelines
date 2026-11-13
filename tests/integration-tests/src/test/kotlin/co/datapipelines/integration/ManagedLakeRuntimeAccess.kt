package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.context.ApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Fault-drill access to the running application's objects. Integration tests may compile against app only
 * (module-structure §4.2); P3 has no REST surface yet, and the stripped-grant drill must bypass its entry guard.
 * Runtime lookup deliberately fails on a missing/ambiguous method rather than introducing test-only exports or
 * leaking internal module edges. Ordinary author/viewer execution still travels over HTTP.
 */
internal class ManagedLakeRuntimeAccess(
    private val context: ApplicationContext,
) {
    fun restoreStartupSeeds() {
        call(bean("co.datapipelines.web.config.SystemActorSeeder"), "afterSingletonsInstantiated")
        call(bean("co.datapipelines.auth.DemoWorkspaceSeeder"), "ensureDemoWorkspace")
    }

    fun authorKey(
        workspace: UUID,
        name: String,
        creator: UUID,
    ): String {
        val member =
            construct(
                "co.datapipelines.auth.WorkspaceContext",
                mapOf(
                    "id" to workspace,
                    "name" to name,
                    "role" to enum("co.datapipelines.auth.WorkspaceRole", "AUTHOR"),
                ),
            )
        val principal =
            construct(
                "co.datapipelines.auth.AuthenticatedPrincipal",
                mapOf(
                    "userId" to creator,
                    "email" to "author@p4delivery.test",
                    "displayName" to "P4 author",
                    "authMethod" to enum("co.datapipelines.auth.AuthMethod", "OIDC"),
                    "workspaceName" to name,
                    "workspace" to member,
                ),
            )
        val key =
            call(
                bean("co.datapipelines.auth.ApiKeyService"),
                "issue",
                principal,
                "P4 author key",
                workspace,
                null,
                enum("co.datapipelines.auth.ApiKeyKind", "MCP"),
                enum("co.datapipelines.auth.KeyRole", "AUTHOR"),
            )
        return property(key, "Plaintext").toString()
    }

    fun templateIds(
        workspace: UUID,
        id: UUID,
    ): List<String> {
        val pipeline = property(executable(workspace, id), "Pipeline")
        return (property(pipeline, "Nodes") as List<*>).map { property(property(requireNotNull(it), "Template"), "Id").toString() }
    }

    fun invalidateMemberships(user: UUID) {
        call(bean("co.datapipelines.auth.AuthCache"), "invalidateMemberships", user)
    }

    fun sweepThenReap() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val sweep = CompletableFuture.runAsync({ call(bean("co.datapipelines.web.config.LakeSettlementScheduler"), "sweep") }, worker)
            val reaper =
                CompletableFuture.runAsync(
                    { call(bean("co.datapipelines.web.config.DatasourcePoolReaperScheduler"), "reap") },
                    worker,
                )
            sweep.get(12, TimeUnit.SECONDS)
            reaper.get(1, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    fun createTable(actor: UUID) {
        val definitions = bean("co.datapipelines.lake.definitions.LakeDefinitionService")
        val surface = enum("co.datapipelines.pipeline.WriteSurface", "SESSION")
        val draft =
            call(
                definitions,
                "createDraft",
                "p4delivery",
                "orders",
                """{"fields":[{"id":1,"name":"id","type":"BIGINTEGER","required":true}],"sort_field_ids":[1]}""",
                actor,
                surface,
            )
        call(definitions, "release", property(draft, "TableId"), property(draft, "Version"), actor, surface, null)
    }

    fun executable(
        workspace: UUID,
        id: UUID,
    ): Any {
        val service = bean("co.datapipelines.pipeline.PipelineService")
        val lens = Class.forName("co.datapipelines.pipeline.ReadLens${'$'}Everything").getField("INSTANCE").get(null)
        val record = call(service, "findRecord", workspace, lens, id)
        return call(service, "findExecutable", workspace, lens, record, 1)
    }

    fun withoutGrant(
        workspace: UUID,
        id: UUID,
        author: UUID,
        values: Map<String, JsonNode>,
    ): String {
        val executable = executable(workspace, id)
        val metadata =
            construct(
                "co.datapipelines.pipeline.LakeExecutionMetadata",
                mapOf(
                    "bodyJson" to property(executable, "BodyJson"),
                    "bodyHash" to property(executable, "BodyHash"),
                    "draft" to true,
                ),
            )
        val request =
            construct(
                "co.datapipelines.executor.ExecuteRequest",
                mapOf(
                    "pipelineId" to id,
                    "pipelineVersion" to 1,
                    "pipeline" to property(executable, "Pipeline"),
                    "userId" to author,
                    "workspaceId" to workspace,
                    "parameters" to values,
                    "lakeExecutionMetadata" to metadata,
                ),
            )
        val runner = bean("co.datapipelines.web.pipelines.RecordingExecutionRunner")
        val trigger = enum("co.datapipelines.executor.ExecutionTrigger", "MCP")
        val block: (Any, Continuation<Any>) -> Any = { _, continuation ->
            call(runner, "run", request, workspace, trigger, false, { _: UUID -> }, continuation)
        }
        return try {
            val result = callStatic("kotlinx.coroutines.BuildersKt", "runBlocking", EmptyCoroutineContext, block)
            property(result, "Status").toString()
        } catch (failure: RuntimeException) {
            check(failure.javaClass.name == "co.datapipelines.executor.PipelineExecutionFailed")
            check(property(failure, "ErrorCode") == "lake.append.grant_missing")
            "FAILED"
        }
    }

    fun schedule(
        workspace: UUID,
        id: UUID,
        values: JsonNode,
    ): UUID {
        val executable = executable(workspace, id)
        val mapper =
            com.fasterxml.jackson.databind
                .ObjectMapper()
        val snapshot =
            mapper
                .createObjectNode()
                .put("pipeline_id", id.toString())
                .put("version", 1)
                .put("body_sha256", property(executable, "BodyHash").toString())
        snapshot.set<JsonNode>("resolved_parameters", values)
        val executor = bean("co.datapipelines.web.schedules.PipelineJobExecutor")
        // #786 D.2: the run row's own payload schema travels on the admission. A row the scheduler admits today is
        // stored in the schema the executor writes, so the drill asks the executor for it rather than spelling a
        // number; an older schema stays the executor's read-time refusal (SchedulerE2eTest A33), never this drill's.
        val admission =
            construct(
                "co.datapipelines.scheduler.Admission",
                mapOf(
                    "runId" to UUID.randomUUID(),
                    "workspaceId" to workspace,
                    "scheduleId" to null,
                    "origin" to enum("co.datapipelines.scheduler.RunOrigin", "MANUAL"),
                    "scheduledAt" to null,
                    "referenceAt" to Instant.now(),
                    "referenceTimezone" to "UTC",
                    "payload" to mapper.createObjectNode(),
                    "parameters" to values,
                    "payloadSchemaVersion" to property(executor, "PayloadSchemaVersion"),
                ),
            )
        val capacityType = Class.forName("co.datapipelines.scheduler.CapacityLease")
        val capacity =
            Proxy.newProxyInstance(capacityType.classLoader, arrayOf(capacityType)) { _, method, _ ->
                check(method.name == "close") { "Unexpected capacity method ${method.name}" }
                null
            }
        val execution = UUID.randomUUID()
        val launch =
            construct(
                "co.datapipelines.scheduler.Launch",
                mapOf(
                    "admission" to admission,
                    "executionId" to execution,
                    "snapshot" to snapshot,
                    "capacity" to capacity,
                ),
            )
        val result = call(executor, "start", launch)
        check(result.javaClass.simpleName == "Started") { "Scheduler did not establish a durable start: ${result.javaClass.simpleName}" }
        return execution
    }

    private fun bean(name: String): Any = context.getBean(Class.forName(name))

    private fun enum(
        type: String,
        name: String,
    ): Any = Class.forName(type).enumConstants.single { (it as Enum<*>).name == name }

    private fun construct(
        type: String,
        values: Map<String, Any?>,
    ): Any {
        val constructor =
            Class
                .forName(type)
                .kotlin.constructors
                .single()
        check(values.keys.all { key -> constructor.parameters.any { it.name == key } })
        return constructor.callBy(constructor.parameters.filter { it.name in values }.associateWith { values[it.name] })
    }

    private fun property(
        value: Any,
        name: String,
    ): Any = call(value, "get$name")

    private fun call(
        receiver: Any,
        name: String,
        vararg arguments: Any?,
    ): Any = invoke(receiver.javaClass, receiver, name, arguments)

    private fun callStatic(
        type: String,
        name: String,
        vararg arguments: Any?,
    ): Any = invoke(Class.forName(type), null, name, arguments)

    private fun invoke(
        type: Class<*>,
        receiver: Any?,
        name: String,
        arguments: Array<out Any?>,
    ): Any =
        try {
            type.methods.single { it.name == name && it.parameterCount == arguments.size }.invoke(receiver, *arguments) ?: Unit
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
