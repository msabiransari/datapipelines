# Repository working agreements

## Issue tracking and roadmap

- Use [GitHub Issues](https://github.com/msabiransari/datapipelines/issues) for all new
  product bugs, features, improvements and actionable follow-ups. Search for an existing
  issue first; update it instead of creating a duplicate.
- Use the [Datapipelines Roadmap project](https://github.com/users/msabiransari/projects/1)
  for roadmap and delivery status. Add tracked product issues to it and keep their status
  current: Backlog, Ready, In progress, In review, Done. Set an appropriate Area; assign
  priority, release targets and dates only when supported by an actual decision.
- GitHub is authoritative for migrated and new product work. Do not start or maintain a
  competing Markdown issue tracker or roadmap board. Existing historical documents may
  retain context and links; they do not override GitHub status.
- Before implementation, connect the work to its issue. Record the problem, intended
  behavior and acceptance criteria. After review, record validation and link the fixing
  commit or pull request. Close as completed only after the change reaches the target
  branch, and mark the project item Done. Distinguish integration from release or deployment.
- Unfinished acceptance work remains explicitly tracked; do not claim behavioral evidence
  from code tests alone. Record newly discovered product follow-ups in GitHub before handback.
- Keep internal dispatch prompts, agent evaluations, raw logs and private operational
  details in their existing private records. Link those records to the public issue when
  appropriate; do not copy private content into public issues.
- Use Discussions for questions and early ideas, and private security advisories for
  vulnerability reports. Follow SECURITY.md for disclosure.

The issue-management policy was adopted on 2026-09-15 and supersedes the earlier plan to
wait until after beta. Historical imports carry legacy IDs, implementation links and
original integration dates; their GitHub timestamps represent the import date.

## Authorization: a handler or tool lands with its row (adopted 2026-09-20, roles design §4.9)

- A new REST handler or MCP tool lands **in the same commit** as (1) its `ScopeMatrix` row —
  the `RestOperation` it declares in `@RequiredScope`, or its entries in
  `MCP_TOOL_MIN_SCOPE` / `MCP_TOOL_MIN_PERMISSION` — (2) its `docs/auth.md` §7.6 row, role-first
  (the five role cells, the constant named in the REST row, the key scope in the scope table),
  and (3) its `RoleWalkE2eTest` expectation, which is that doc row: the walk parses §7.6 and
  asserts every route and tool against it, so a row with no code or code with no row is red.
  `ScopeMatrixSpecDriftTest`, `MatrixRowReachabilityTest` and `ReadFloorTest` are the
  build-time guards; a GET declares the LOWEST operation whose row admits it.
- A lane prompt for any feature that adds an action states the **roles** for every new action
  (which of viewer / author / promoter / workspace admin / super admin may perform it, and which
  guard pins it) — the store's `prompts/_TEMPLATE.md` carries the mandatory section. A prompt
  without it is not ready to dispatch.
- Every lane prompt and handback carries the store template's **Security** section; a diff touching
  auth, the serve path, MCP tools, files, user-data rendering, config or outbound calls gets the
  orchestrator's security pass before merge (roles design §4.9, adopted 2026-09-21).
- Vocabulary: a **role** is what a member holds (one per workspace); a **permission** is a row of
  the matrix — an action a role may perform, never a free string. The word "flags" does not
  describe roles anywhere in UI text, docs or identifiers.

