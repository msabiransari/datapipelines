package co.datapipelines.mcp

import java.io.File

/**
 * `docsExport`'s entry point: renders the served document set (see [SkillDocsExport]) and
 * writes it as `<name>.md` files plus an `index.md` — the audit's input
 * (`scripts/docs-audit.sh` runs its checks A–C over these files) and a human's readable copy
 * of what the server serves. Replaces `SkillToolsDocMain` (242a: the tools file is one
 * generated document among nineteen).
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    require(args.isNotEmpty() && out.isAbsolute) { "usage: docsExport <absolute output directory>" }
    val docSet = SkillDocsExport.render()
    for (doc in docSet.docs) {
        File(out, "${doc.name}.md").writeText(doc.markdown, Charsets.UTF_8)
    }
    val index =
        buildString {
            append("# The served manual — export\n\n")
            append("Rendered by `:modules:mcp-server:docsExport`; the audit (checks A–C) reads this directory.\n\n")
            append("| Name | Area | Layer | Chars | Purpose |\n|---|---|---|---|---|\n")
            for (doc in docSet.docs) {
                val purpose = doc.purpose.replace("|", "\\|")
                append("| `${doc.name}` | ${doc.area.wire} | ${doc.layer.name.lowercase()} | ${doc.chars} | $purpose |\n")
            }
        }
    File(out, "index.md").writeText(index, Charsets.UTF_8)
    println("docsExport: ${docSet.docs.size} documents + index -> ${out.absolutePath}")
}
