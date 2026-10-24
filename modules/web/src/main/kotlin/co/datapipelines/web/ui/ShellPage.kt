package co.datapipelines.web.ui

/**
 * A controller whose views render through one of the page layouts — the app shell
 * (`layouts/default`), the auth layout or the site chrome. The four shell advices —
 * [AppShellAdvice], [UiWorkspaceAdvice], [ReleaseFlashAdvice] and [SiteOriginAdvice] —
 * select their `@ModelAttribute` methods by THIS marker
 * (`assignableTypes = [ShellPage::class]`, #652), so they run for `ShellPage` beans and
 * for nothing else.
 *
 * Three kinds of `@Controller` deliberately do NOT implement it:
 *
 * - a `@RestController` never does — its handlers pay no shell read (the #574
 *   measurement: before #652 the selector `annotations = [Controller::class]` matched
 *   `@RestController` through its meta-annotation, so every JSON API request paid two
 *   `users` reads and a `workspaces` list inside one `GET /api/v1/pipelines/tree`);
 * - a text-only `@Controller` — every handler `@ResponseBody` (`SkillController`,
 *   `SitemapController`, `LlmsTxtController`) — does not;
 * - the one standalone page, [co.datapipelines.web.visualizations.VisualizationPreviewController]
 *   (session-less, capability-authenticated, self-supplied `activeTheme`), does not.
 *
 * Selection is per bean. A marked page controller's `@ResponseBody` methods still run
 * the advices: the markdown read on DocsController, the secret read on
 * ApiKeysPartialController and the edit POST on TemplateEditorController.
 *
 * `ShellPageGuardTest` pins the partition over the sources; `ShellAdviceSelectionTest`
 * pins the Spring-side selection (`ControllerAdviceBean.isApplicableToBeanType`, the
 * class the dispatcher itself consults).
 */
interface ShellPage
