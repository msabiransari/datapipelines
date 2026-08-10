# mcp-server

The **Model Context Protocol** surface of datapipelines.co — the transport LLM agents connect to.
Spec: [mcp-server.md](../../docs/mcp-server.md) v1.2 (frozen).

## What it does

- **Transport (§3):** Streamable HTTP on `POST /mcp`, served by the official Java MCP SDK's
  `HttpServletStatelessServerTransport` (stateless, as §3.3 asks for). Protocol version pinned to
  `2025-06-18`.
- **Auth (§4):** API-key only. `McpAuthFilter` reads the principal `auth`'s `ApiKeyFilter` already
  established (the single §7.3 validation path) and refuses anything that is not an API key — a
  browser session cannot call a tool.
- **Tools (§6):** the 15 tools of §6.1. `McpToolDispatcher` owns the three cross-cutting rules —
  the per-tool scope gate (`ScopeMatrix.requiredScopeForTool`), the §6.3 result envelope with the
  `_meta.correlation_id` echo, and the §9.2 error mapping.
- **Resources (§7):** `datapipelines://…` URIs, listed with a fixed page size of 100 behind an
  opaque cursor and filtered by the caller's scope/ownership, read through `McpResourceReader`.
- **Prompts (§8):** `analyze_pipeline` and `debug_failed_execution`. The third prompt of §8.2 is
  deliberately absent — it needs schema-introspection tools v1 does not have.

No business logic lives here: every tool is a translation onto the same service layer the REST
controllers use (`PipelineRepository`, `TemplateRepository`, `DatasourceRegistry`,
`PipelineExecutor`, `ResultStore`, `ExecutionRepository`).

## Public API

| Type | Role |
|---|---|
| `McpServerAutoConfiguration` | Spring Boot autoconfiguration: tools, prompts, resources, the `/mcp` servlet and the filter |
| `McpServerFactory` | Builds the transport + `McpStatelessSyncServer` (§3.1 gate decisions live in its KDoc) |
| `McpAuthFilter` | Request → `AuthenticatedPrincipal` + correlation id, or `401` in the REST §4.2 envelope |
| `McpToolDispatcher`, `McpTool` | `tools/call` dispatch, scope gate, envelope, error mapping |
| `McpResourceCatalog`, `McpResourceReader`, `McpResourceUri` | `resources/list` + `resources/read` |
| `McpPromptCatalog` | `prompts/list` + `prompts/get` |

## Dependencies

Internal: `typesystem`, `pipeline-contract`, `templates`, `datasources`, `dag`, `auth`
(module-structure §5.8). **Never `web`** — this module must not loop back through HTTP.

External: `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2` 2.0.0; Jakarta Servlet,
Spring Boot servlet registration and Spring Security types are `compileOnly` (supplied at runtime
by `app`/`web`).

## Testing locally

```
./gradlew :modules:mcp-server:build     # ktlint + detekt + unit tests
./gradlew :modules:mcp-server:test      # tests only
```

`McpProtocolIntegrationTest` drives the real SDK request pipeline in process (initialize,
tools/list, tools/call, resources/list, prompts/get) without an HTTP container.
