# datapipelines — Claude Code plugin

Connects Claude Code to **the MCP server of your own deployment** — the server that runs the
pipelines and serves the agent manual, so what your session reads is always what it runs.

```
/plugin marketplace add msabiransari/datapipelines
/plugin install datapipelines@datapipelines
```

The install asks for two values:

| Setting | What to enter |
|---|---|
| **Deployment URL** | The base URL of *your* deployment, no trailing slash — `https://dp.example.com`, or `http://localhost:8080` for a local `./app.sh --start`. This product is self-hosted; there is no default endpoint. |
| **API key** | An MCP key from your deployment's **`/settings/api-keys`** page, shaped `dpk_<id>.<secret>`. Keys carry a role — ask for the lowest that covers the work (`author` to create, a viewer role to inspect). No MCP tool needs `admin`. |

Both are stored by Claude Code as plugin user config and substituted into the MCP server
entry (`${user_config.url}` / `${user_config.api_key}`); the key never enters the repository
or a chat transcript.

## What you get

- **The MCP server.** 42 tools, 3 prompts and the `datapipelines://` resource surface,
  scoped to the workspace your key is pinned to.
- **A pointer skill.** `skills/datapipelines/SKILL.md` carries no content — it says to call
  `docs_get {"name": "skill"}`. The manual itself is rendered by the deployment at boot
  (the operating core, one guide per functional area, the generated tool references), so it
  can never drift from the server it describes. It is also served at `GET /skill.md` for
  clients that speak no MCP.

## Not using Claude Code?

Point any agent at your deployment's manual directly:

```bash
curl -sS https://dp.example.com/skill.md -o SKILL.md
```

…plus `GET /skill/<name>.md` for each document the core's index lists. The deployment
serves them unauthenticated: it is the manual, and it holds no secrets.

## Licence

AGPL-3.0-only, same as the product. Source: <https://github.com/msabiransari/datapipelines>
