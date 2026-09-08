package co.datapipelines.pipeline

/**
 * Which lifecycle status version 1 lands on when a pipeline or a template is CREATED
 * (versioning §3.2, ruling D55).
 *
 * There are exactly two create paths, and the difference between them is not a preference —
 * it is who is writing:
 *
 * - [DRAFT] — **authoring**. `POST /pipelines`, `pipelines_create`, `POST /templates`,
 *   `templates_create`, the editor's first save. Version 1 lands DRAFT and the index row's
 *   `current_version` stays NULL: nothing an agent or an engineer authored is released until a
 *   human releases it (D4, without exception). Executable immediately all the same — a draft has
 *   been executable since 039, which is why §3.2's old "so it is executable" justification for
 *   landing RELEASED no longer bought anything.
 * - [RELEASED] — **not authoring**. A promotion import (§9.2) and the seeders that ride the same
 *   import services: the content was already reviewed and released where it came from, and the
 *   system actor is not an agent asking for a review.
 *
 * It is a required argument on both repositories' `create` (no default): a new create path must
 * SAY which of the two it is, and a missed caller is a compile error rather than a pipeline
 * nobody looked at appearing as RELEASED.
 */
enum class CreateLifecycle {
    DRAFT,
    RELEASED,
}
