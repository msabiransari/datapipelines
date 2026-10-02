-- #331 (lane 332): the dashboard pins path, indexed for the containment probe.
--
-- `DashboardRepository.livePinsOf`'s probe — `body_json -> 'visualizations' @> :probe` — ran a
-- whole-table scan per probe: every visualization purge, discard and restore (the pin guards)
-- and every `visualizations_get`'s `used_by` pays one. The GIN index on that exact expression
-- turns the probe into a bitmap index scan (measured on demo-shaped content — 4 000 current
-- RELEASED dashboards, 3 pins each — 5.3 ms → 0.9 ms; the plans before and after are the lane's
-- evidence). The batched page answer (#331) reads a workspace's pins WHOLE and does not use the
-- index; it is not written for it. The up-and-down rehearsal on the copy: CREATE, DROP, CREATE.

CREATE INDEX idx_dashboard_versions_pins ON dashboard_versions USING GIN ((body_json -> 'visualizations'));
