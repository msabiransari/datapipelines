-- V18__draft_first_create.sql
--
-- Draft-first creation (ruling D55, versioning.md §3.2, implemented 099).
--
-- Creating a pipeline or a template now lands version 1 as DRAFT, and DRAFT → RELEASED is a
-- human step (D4, without exception). The index row's pointer therefore has to be able to say
-- "nothing is released yet", which `NOT NULL DEFAULT 0` could only express as a sentinel that
-- every JOIN would have to know about.
--
--   current_version  the latest RELEASED version, or NULL when none has been released yet.
--                    It still does not move while a draft exists (§3.4).
--
-- Nothing else about the column changes: `v.version = p.current_version` joins already answer
-- "no released version" with zero rows, which is exactly what a NULL pointer produces, so every
-- released-only read (execute of a release, promotion inventory, endpoint publish, the
-- datasource joins) keeps its meaning by construction.
--
-- The two places that did ARITHMETIC on the pointer — the version-less import's
-- `current_version + 1` and the preserved-version import's `GREATEST(current_version, :version)`
-- — are wrapped in COALESCE(…, 0) in the repositories, so importing onto a never-released
-- pipeline or template allocates version 1 as it did when the column read 0.
--
-- The DEFAULT is dropped rather than set to NULL: every writer names the column explicitly
-- (PipelineRepository/TemplateRepository's create and both import paths), and a column whose
-- absence means "not released" is one an accidental INSERT could get right by luck.

ALTER TABLE pipelines
    ALTER COLUMN current_version DROP DEFAULT,
    ALTER COLUMN current_version DROP NOT NULL;

ALTER TABLE templates
    ALTER COLUMN current_version DROP DEFAULT,
    ALTER COLUMN current_version DROP NOT NULL;

-- 0 was the pre-V18 "no version" default. No writer ever left it in place (both create paths
-- wrote 1), so this is expected to match zero rows; it exists so that a database which somehow
-- holds the sentinel reads as NULL afterwards rather than pointing at a version that cannot exist.
UPDATE pipelines SET current_version = NULL WHERE current_version = 0;
UPDATE templates SET current_version = NULL WHERE current_version = 0;
