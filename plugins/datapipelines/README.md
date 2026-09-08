# datapipelines — Claude Code plugin

Gives Claude Code two things at once: the **datapipelines skill** (how to author, run and
debug pipelines on this product) and the **MCP connection** to your own deployment.

```
/plugin marketplace add msabiransari/datapipelines
/plugin install datapipelines@datapipelines
```

The install asks for two values:

| Setting | What to enter |
|---|---|
| **Deployment URL** | The base URL of *your* deployment, no trailing slash — `https://dp.example.com`, or `http://localhost:8080` for a local `./app.sh --start`. This product is self-hosted; there is no default endpoint. |
| **API key** | A `user`-kind key from your deployment's **`/settings/api-keys`** page, shaped `dpk_<id>.<secret>`. Ask for the lowest scope that covers your work — `read` to inspect, `execute` to run, `author` to create or change. No MCP tool needs `admin`. |

Both are stored by Claude Code as plugin user config and substituted into the MCP server
entry (`${user_config.url}` / `${user_config.api_key}`); the key never enters the repository
or a chat transcript.

## What you get

- **The skill.** `skills/datapipelines/SKILL.md` is the operating core — the naming grammar,
  the golden path, execution semantics, error handling. `skills/datapipelines/references/`
  holds the detail Claude opens only when it needs it, `tools.md` among them (generated from
  the server's own tool catalog, so it cannot describe a surface your server does not ship).
- **The MCP server.** 30 tools, 3 prompts and the `datapipelines://` resource surface,
  scoped to the workspace your key is pinned to.

The server also briefs the agent at connect time, and serves the same manual at
`datapipelines://docs/skill`, so a session is never working from a stale copy.

## Not using Claude Code?

The skill is a plain `.agents/skills/` directory — every other agent reads it as-is:

```bash
mkdir -p .agents/skills/datapipelines/references
curl -sS https://dp.example.com/skill.md -o .agents/skills/datapipelines/SKILL.md
```

…plus `GET /skill/<name>.md` for each reference the map in `SKILL.md` lists. The deployment
serves them unauthenticated: it is the manual, and it holds no secrets.

## Licence

AGPL-3.0-only, same as the product. Source: <https://github.com/msabiransari/datapipelines>
