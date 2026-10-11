package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec

const val DOCS_READ = "docs_read"

/** The docs_read tool: documents (plans, rules, pages) of a project root, their versions and links. */
fun docsReadTool(): SelectToolSpec =
    SelectToolSpec(
        name = DOCS_READ,
        description =
            "Read the versioned documents of a project root. select: get (one document or one stored version), " +
                "list (metadata only), history (versions) or links (what the document links to and what links to it).",
        selects = listOf(docsGetSelect, docsListSelect, docsHistorySelect, docsLinksSelect)
    )
