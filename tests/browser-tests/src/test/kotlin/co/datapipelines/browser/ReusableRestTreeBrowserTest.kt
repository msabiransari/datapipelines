package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Real Chromium on an isolated application: DOM identities and network witnesses, with no screenshots as an oracle. */
class ReusableRestTreeBrowserTest : BrowserSuite() {
    private fun ready() {
        val user = seedLocalUser(uniqueEmail("resttree" + generatedPassword("id")), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("resttree-" + generatedPassword("ws").lowercase())
        page.setViewportSize(1440, 1000)
        page.waitForFunction("() => window.DatapipelinesSidebarTrees instanceof Map")
    }

    @Test
    @Suppress("LongMethod") // sequential browser workflow: identity, history, resize and reload share one fixture
    fun `real REST browsing preserves rows across artifact catalog and cached history and the rail can consume the app`() {
        ready()
        val id = seedPipeline("scope/deep/first")
        seedPipeline("scope/deep/second")
        val requests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/api/v1/pipelines/tree")) requests += it.url() }
        page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='folder:scope']")
        requests.size shouldBe 1
        page.locator("#nav-tree-pipelines [aria-expanded=true]").count() shouldBe 0
        expand("scope")
        expand("scope/deep")
        page.waitForSelector("#nav-tree-pipelines a[href='/pipelines/$id']")
        requests.size shouldBe 3
        page.evaluate(
            """() => {
            window.__railWitness = document.getElementById('app-rail');
            window.__rowWitness = document.querySelector('#nav-tree-pipelines [data-tree-key="folder:scope"]');
            window.__documentWitness = document;
        }""",
        )
        page.click("#nav-tree-pipelines a[href='/pipelines/$id']")
        page.waitForURL("**/pipelines/$id")
        page.waitForFunction("() => !!window.__peInstance")
        sameRail()
        requests.size shouldBe 3
        page.click("[data-nav-branch=pipelines] .app-nav-link")
        page.waitForURL("**/pipelines")
        sameRail()
        page.goBack()
        page.waitForURL("**/pipelines/$id")
        page.waitForFunction("() => !!window.__peInstance")
        sameRail()
        requests.size shouldBe 3
        // A quiescent loaded tree performs no DOM work over sixty actual animation frames.
        page.evaluate(
            """async () => {
              let count=0;const observer=new MutationObserver(records=>count+=records.length);
              observer.observe(document.getElementById('nav-tree-pipelines'),{subtree:true,childList:true,attributes:true});
              for(let i=0;i<60;i++) await new Promise(resolve=>requestAnimationFrame(resolve));
              observer.disconnect();window.__idleTreeMutations=count;
            }""",
        )
        page.evaluate("() => window.__idleTreeMutations") shouldBe 0
        page.locator("#rail-resize").focus()
        page.keyboard().press("End")
        page.waitForFunction("() => document.getElementById('app-main').getBoundingClientRect().width < 1")
        (page.evaluate("() => document.getElementById('app-rail').getBoundingClientRect().width") as Number).toInt() shouldBe 1440
        page.keyboard().press("Home")
        page.waitForFunction("() => document.getElementById('app-main').getBoundingClientRect().width > 1000")
        page.evaluate(
            "() => window.__rowWitness === document.querySelector('#nav-tree-pipelines [data-tree-key=\"folder:scope\"]')",
        ) shouldBe
            true
        // Pointer resizing goes beyond the retired cap and remains authoritative when folders close/reopen.
        val handle = checkNotNull(page.locator("#rail-resize").boundingBox())
        page.mouse().move(handle.x + handle.width / 2, handle.y + handle.height / 2)
        page.mouse().down()
        page.mouse().move(900.0, 500.0)
        page.mouse().up()
        (page.evaluate("() => document.getElementById('app-rail').getBoundingClientRect().width") as Number).toInt() shouldBe 900
        page.locator("#nav-tree-pipelines [data-tree-key='folder:scope'] > .dp-tree-line button").click()
        expand("scope")
        requests.size shouldBe 3
        (page.evaluate("() => document.getElementById('app-rail').getBoundingClientRect().width") as Number).toInt() shouldBe 900
        page.reload()
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='folder:scope']")
        page.locator("#nav-tree-pipelines [aria-expanded=true]").count() shouldBe 0
        (page.evaluate("() => document.getElementById('app-rail').getBoundingClientRect().width") as Number).toInt() shouldBe 900
    }

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // one transport fixture drives all adapters and bounded pages
    fun `all five adapters exhaust large levels and search paths then clear to closed roots`() {
        ready()
        val workspace = page.locator("html").getAttribute("data-dp-workspace-id")
        val requests = mutableListOf<String>()
        page.route(java.util.function.Predicate { url -> url.contains("/api/v1/") && url.contains("/tree") }, { route ->
            val uri = URI(route.request().url())
            requests += uri.path + "?" + uri.query
            val family = uri.path.split('/')[3]
            val parameters =
                uri.query.orEmpty().split('&').associate { part ->
                    part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8)
                }
            val search = uri.path.endsWith("/search")
            val cursor = parameters["cursor"]
            val parent = parameters["parent"].orEmpty()
            val nodes =
                when {
                    search -> {
                        listOf(node("root", null, true), node("root/deep", "folder:root", true)) +
                            List(
                                if (cursor ==
                                    null
                                ) {
                                    200
                                } else {
                                    5
                                },
                            ) { index -> node("root/deep/match_${cursor.orEmpty()}$index", "folder:root/deep", false) }
                    }

                    parent.isEmpty() -> {
                        listOf(node("root", null, true))
                    }

                    cursor == null -> {
                        List(200) { node("root/f$it", "folder:root", true) }
                    }

                    else -> {
                        if (cursor == "next") {
                            List(5) { node("root/f${200 + it}", "folder:root", true) } +
                                List(195) { node("root/item$it", "folder:root", false) }
                        } else {
                            List(10) { node("root/item${195 + it}", "folder:root", false) }
                        }
                    }
                }
            val next =
                if ((search || parent.isNotEmpty()) && cursor == null) {
                    "next"
                } else if (!search && cursor == "next") {
                    "end"
                } else {
                    null
                }
            val data =
                mapOf(
                    "family" to family,
                    "mode" to if (search) "search" else "browse",
                    "root" to "",
                    "parent" to parent,
                    "query" to parameters["q"],
                    "workspace_id" to workspace,
                    "view_token" to "fixture-view",
                    "nodes" to nodes,
                    "next_cursor" to next,
                )
            val encoded =
                page.evaluate(
                    """value => {
                    value.data.query ??= null; value.data.next_cursor ??= null;
                    value.data.nodes.forEach(node => {node.parent_key ??= null;node.href ??= null});
                    return JSON.stringify(value);
                }""",
                    mapOf("schema_version" to 1, "data" to data),
                ) as String
            route.fulfill(Route.FulfillOptions().setContentType("application/json").setBody(encoded))
        })
        listOf("pipelines", "templates", "dashboards", "visualizations", "parameter-sets").forEach { family ->
            val panel = "#nav-tree-$family"
            page.click("[data-nav-branch='$family'] [data-nav-tree-toggle]")
            page.waitForFunction(
                "selector => !!document.querySelector(selector + ' [data-tree-key]') || " +
                    "!!document.querySelector(selector + ' .dp-tree-status button')",
                panel,
            )
            withClue({
                "root-status $family ${page.locator("$panel .dp-tree-status").allTextContents()} requests=$requests error=" +
                    page.evaluate(
                        "family => window.DatapipelinesSidebarTrees.get(family).state.levels.get(null)?.error?.message",
                        family,
                    )
            }) {
                named(page, "$family root folder loaded") {
                    page.waitForSelector("$panel [data-tree-key='folder:root']")
                }
            }
            page.click("$panel [data-tree-key='folder:root'] > .dp-tree-line button")
            page.waitForFunction(
                "selector => document.querySelector(selector)?.getAttribute('aria-busy') === 'false'",
                "$panel .dp-tree-group",
            )
            page.locator("$panel .dp-tree-group > [role=treeitem]").count() shouldBe 410
            page.fill("$panel input[type=search]", "match")
            page.waitForFunction("selector => document.querySelectorAll(selector).length === 205", "$panel [data-tree-key^='artifact:']")
            page.locator("$panel [aria-expanded=true]").count() shouldBe 2
            page.locator("$panel button[aria-label='Clear search']").click()
            page.locator("$panel [aria-expanded=true]").count() shouldBe 0
            page.locator("$panel .dp-tree-rows > [role=treeitem]").count() shouldBe 1
        }
        requests.count { it.contains("/tree/search") } shouldBe 10
        requests.count { it.contains("parent=root") } shouldBe 15
    }

    @Test
    fun `selection-only reusable instances keep independent source search clear and disposal`() {
        ready()
        page.evaluate(
            """async () => {
          const {mountSearchTree} = await import('/js/tree/component.mjs');
          const folder = {key:'folder:a',parentKey:null,kind:'folder',path:'a',name:'a',hasChildren:true};
          const leaf = {key:'artifact:one',parentKey:'folder:a',kind:'artifact',path:'a/one',name:'one',hasChildren:false};
          const source = {async loadChildren(parent) {return {nodes: parent ? [leaf] : [folder],nextCursor:null}},
            async search() {return {nodes:[folder,leaf],nextCursor:null}}};
          window.__pickers = [];
          for(let i=0;i<3;i++){const target=document.createElement('output');target.id='target-'+i;document.getElementById('app-main').append(target)}
          for(let i=0;i<3;i++) {
            const host=document.createElement('div');host.id='picker-'+i;document.getElementById('app-main').append(host);
            window.__pickers.push(mountSearchTree(host,{source:i===2 ? {async loadChildren(){return {nodes:[{key:'artifact:two',parentKey:null,kind:'artifact',path:'b/two',name:'two',hasChildren:false}],nextCursor:null}},async search(){return {nodes:[],nextCursor:null}}} : source,label:'Picker '+i,debounceMs:0,
              onActivate(node){window.__picked=node.key;document.getElementById('target-'+i).textContent=node.key}}));
          }
        }""",
        )
        page.waitForSelector("#picker-0 [data-tree-key='folder:a']")
        page.click("#picker-0 [data-tree-key='folder:a'] > .dp-tree-line button")
        page.waitForSelector("#picker-0 [data-tree-key='artifact:one']")
        page.locator("#picker-0 a").count() shouldBe 0
        val url = page.url()
        page.click("#picker-0 [data-tree-key='artifact:one'] button")
        page.evaluate("() => window.__picked") shouldBe "artifact:one"
        page.url() shouldBe url
        page.locator("#target-0").textContent() shouldBe "artifact:one"
        page.locator("#target-1").textContent() shouldBe ""
        page.locator("#picker-1 [aria-expanded=true]").count() shouldBe 0
        page.fill("#picker-1 input", "one")
        page.waitForSelector("#picker-1 [data-tree-key='artifact:one']")
        page.click("#picker-1 button[aria-label='Clear search']")
        page.locator("#picker-1 [aria-expanded=true]").count() shouldBe 0
        page.click("#picker-2 [data-tree-key='artifact:two'] button")
        page.locator("#target-2").textContent() shouldBe "artifact:two"
        page.locator("#target-0").textContent() shouldBe "artifact:one"
        page.evaluate(
            """async () => window.__pickers[1].update({root:'scope',context:{workspace:'new'},source:{
              async loadChildren(){return {nodes:[{key:'folder:scope/c',parentKey:null,kind:'folder',path:'scope/c',name:'c',hasChildren:true}],nextCursor:null}},
              async search(){return {nodes:[],nextCursor:null}}
            }})""",
        )
        page.locator("#picker-1 [data-tree-key='folder:a']").count() shouldBe 0
        page.locator("#picker-1 [data-tree-key='folder:scope/c']").count() shouldBe 1
        page.locator("#picker-1 [aria-expanded=true]").count() shouldBe 0
        page.evaluate("() => window.__pickers[0].dispose()")
        page.locator("#picker-2 [data-tree-key='artifact:two']").count() shouldBe 1
    }

    @Test
    @Suppress("LongMethod") // one held refresh: the witnesses must stay live from the refocus to the reloaded child level
    fun `a refocus refresh keeps rows focus and scroll while held and reloads the open level without a toggle`() {
        ready()
        val workspace = page.locator("html").getAttribute("data-dp-workspace-id")
        var added = false
        var hold = false
        val held = mutableListOf<() -> Unit>()
        val parents = mutableListOf<String>()
        page.route(java.util.function.Predicate { url -> url.contains("/api/v1/pipelines/tree") }, { route ->
            val parent =
                URI(route.request().url())
                    .query
                    .orEmpty()
                    .split('&')
                    .firstOrNull { it.startsWith("parent=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                    .orEmpty()
            parents += parent
            val answer = {
                val nodes =
                    if (parent.isEmpty()) {
                        listOf(node("root", null, true))
                    } else {
                        List(80) { node("root/item%02d".format(it), "folder:root", false) } +
                            if (added) listOf(node("root/added", "folder:root", false)) else emptyList()
                    }
                fulfillTree(route, "pipelines", parent, nodes, workspace)
            }
            if (hold) held += answer else answer()
        })
        page.click("[data-nav-branch=pipelines] [data-nav-tree-toggle]")
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='folder:root']")
        page.click("#nav-tree-pipelines [data-tree-key='folder:root'] > .dp-tree-line button")
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='artifact:root/item79']")
        page.evaluate(
            """() => {
              const scroll = document.querySelector('#nav-tree-pipelines .dp-tree-scroll');
              const row = document.querySelector('#nav-tree-pipelines [data-tree-key="artifact:root/item40"]');
              row.focus(); scroll.scrollTop = 600;
              const folder = document.querySelector('#nav-tree-pipelines [data-tree-key="folder:root"]');
              const offset = () => Math.round(row.getBoundingClientRect().top - scroll.getBoundingClientRect().top);
              window.__refresh = { scroll, row, top: scroll.scrollTop, offset, at: offset(), folder, loading: 0, removed: 0 };
              new MutationObserver(records => records.forEach(record => {
                if ((record.target.textContent || '').includes('Loading')) window.__refresh.loading += 1;
                record.removedNodes.forEach(node => {
                  if (node.nodeType === 1 && node.hasAttribute('data-tree-key')) window.__refresh.removed += 1;
                });
              })).observe(document.getElementById('nav-tree-pipelines'), { subtree: true, childList: true, characterData: true });
            }""",
        )
        (page.evaluate("() => window.__refresh.top") as Number).toInt() shouldBe 600
        page.evaluate("() => document.hidden") shouldBe false
        added = true
        hold = true
        parents.clear()
        page.evaluate("() => document.dispatchEvent(new Event('visibilitychange'))")
        page.waitForCondition { held.size == 1 }
        // Held: the refetch is in flight and the reader still has every row, the focus and the scroll.
        page.locator("#nav-tree-pipelines [data-tree-key^='artifact:root/item']").count() shouldBe 80
        page.evaluate(
            "() => ({ focus: document.activeElement === window.__refresh.row, scroll: Math.round(window.__refresh.scroll.scrollTop) })",
        ) shouldBe mapOf("focus" to true, "scroll" to 600)
        page.locator("#nav-tree-pipelines .dp-tree-status").allTextContents().none { it.contains("Loading") } shouldBe true
        hold = false
        held.removeAt(0).invoke()
        page.waitForSelector("#nav-tree-pipelines [data-tree-key='artifact:root/added']")
        parents shouldBe listOf("", "root")
        // One field per witness, so a red names the one that moved. "added" sorts above the focused row: the
        // browser's scroll anchoring moves scrollTop by that row so the reader's rows stay put on screen —
        // the witness is the focused row's place in the viewport, not the raw scrollTop.
        page.evaluate(
            """() => ({ focus: document.activeElement === window.__refresh.row, rowAt: window.__refresh.offset() - window.__refresh.at,
              folder: window.__refresh.folder === document.querySelector('#nav-tree-pipelines [data-tree-key="folder:root"]'),
              expanded: window.__refresh.folder.getAttribute('aria-expanded') })""",
        ) shouldBe mapOf("focus" to true, "rowAt" to 0, "folder" to true, "expanded" to "true")
        (page.evaluate("() => window.__refresh.loading + window.__refresh.removed") as Number).toInt() shouldBe 0
    }

    @Test
    fun `a saved wide rail keeps the main gutter at phone width`() {
        ready()
        val workspace = page.locator("html").getAttribute("data-dp-workspace")
        page.evaluate("workspace => localStorage.setItem('dp-rail-width:' + workspace, '2000')", workspace)
        page.setViewportSize(390, 844)
        page.reload()
        page.waitForSelector("#app-main")
        // The preference is in force (the drawer reads it as ~100vw), yet main keeps the phone band's gutter.
        page.evaluate("() => document.documentElement.style.getPropertyValue('--app-rail-preferred')") shouldBe "2000px"
        val gutter =
            page.evaluate(
                """() => {
                  const probe = document.createElement('div'); probe.style.setProperty('padding-left', 'var(--gap-md)');
                  document.body.append(probe); const expected = parseFloat(getComputedStyle(probe).paddingLeft); probe.remove();
                  const main = getComputedStyle(document.getElementById('app-main'));
                  return [expected, parseFloat(main.paddingLeft), parseFloat(main.paddingRight)];
                }""",
            ) as List<*>
        println("465-phone-gutter expected/left/right=$gutter")
        ((gutter[0] as Number).toDouble() > 0) shouldBe true
        (gutter[1] as Number).toDouble() shouldBe (gutter[0] as Number).toDouble()
        (gutter[2] as Number).toDouble() shouldBe (gutter[0] as Number).toDouble()
    }

    private fun fulfillTree(
        route: Route,
        family: String,
        parent: String,
        nodes: List<Map<String, Any?>>,
        workspace: String?,
    ) {
        val data =
            mapOf(
                "family" to family,
                "mode" to "browse",
                "root" to "",
                "parent" to parent,
                "workspace_id" to workspace,
                "view_token" to "fixture-view",
                "nodes" to nodes,
            )
        val encoded =
            page.evaluate(
                """value => {
                  value.data.query = null; value.data.next_cursor = null;
                  value.data.nodes.forEach(node => {node.parent_key ??= null;node.href ??= null});
                  return JSON.stringify(value);
                }""",
                mapOf("schema_version" to 1, "data" to data),
            ) as String
        route.fulfill(Route.FulfillOptions().setContentType("application/json").setBody(encoded))
    }

    private fun node(
        path: String,
        parent: String?,
        folder: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "key" to if (folder) "folder:$path" else "artifact:$path",
            "parent_key" to parent,
            "kind" to if (folder) "folder" else "artifact",
            "name" to path.substringAfterLast('/'),
            "path" to path,
            "has_children" to folder,
            "match" to !folder,
            "href" to null,
        )

    private fun expand(path: String) {
        page.click("#nav-tree-pipelines [data-tree-key='folder:$path'] > .dp-tree-line button")
        page.waitForFunction(
            "key => document.querySelector('[data-tree-key=\"'+key+'\"] > .dp-tree-group')?.getAttribute('aria-busy') === 'false'",
            "folder:$path",
        )
    }

    private fun sameRail() {
        page.evaluate(
            "() => window.__railWitness === document.getElementById('app-rail') && window.__documentWitness === document",
        ) shouldBe
            true
        page.evaluate(
            "() => window.__rowWitness === document.querySelector('#nav-tree-pipelines [data-tree-key=\"folder:scope\"]')",
        ) shouldBe
            true
    }

    private fun seedPipeline(name: String): String {
        val body =
            """{"name":"$name","display_name":"First","nodes":[{"id":"n1","type":"CALCULATOR", """ +
                """"kind":"fiscal_quarter","context_key":"run_q_n1", """ +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}"""
        val created = api("POST", "/api/v1/pipelines", body, null)
        val id = page.evaluate("text => JSON.parse(text).data.id", created) as String
        val hash = page.evaluate("text => JSON.parse(text).data.body_hash", created) as String
        api("POST", "/api/v1/pipelines/$id/release", null, hash)
        return id
    }

    private fun api(
        method: String,
        path: String,
        body: String?,
        hash: String?,
    ): String =
        page.evaluate(
            """async ([method,path,body,hash]) => {
          const csrf=document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
          const headers={'Content-Type':'application/json','DP-CSRF-Token':csrf?decodeURIComponent(csrf[1]):''};
          if(hash)headers['If-Match']=hash;
          const response=await fetch(path,{method,headers,body,credentials:'same-origin'});
          if(!response.ok)throw new Error('Fixture refused: '+response.status+' '+await response.text());
          return response.text();
        }""",
            arrayOf(method, path, body, hash),
        ) as String
}
