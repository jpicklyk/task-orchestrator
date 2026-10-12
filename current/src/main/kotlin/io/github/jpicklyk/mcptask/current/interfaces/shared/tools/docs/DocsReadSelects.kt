package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.api.docs.DocGetResult
import io.github.jpicklyk.mcptask.current.application.api.docs.DocHandlers
import io.github.jpicklyk.mcptask.current.application.api.docs.DocHistoryEntry
import io.github.jpicklyk.mcptask.current.application.api.docs.DocLinkEntry
import io.github.jpicklyk.mcptask.current.application.api.docs.DocListEntry
import io.github.jpicklyk.mcptask.current.application.api.docs.DocsGetRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocsHistoryRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocsLinksRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocsListRequest
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import kotlinx.serialization.serializer

private val READ_ERRORS = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN)

/** `docs_read get`: one document with its body, or one stored version of it. */
internal val docsGetSelect =
    SelectSpec.Single<DocsGetRequest, DocGetResult>(
        name = "get",
        doc =
            "Read one document by (root, kind, slug), with its body. With version n the answer is that stored version; " +
                "with ifVersion equal to the current version it is {unchanged: true, version} and no body.",
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DocsGetRequest>(),
        errors = READ_ERRORS,
        handler = { env, ctx, req -> DocHandlers.get(env, ctx, req) },
        data = { DocJson.getResult(it) }
    )

/** `docs_read list`: the documents of a root, metadata only. */
internal val docsListSelect =
    SelectSpec.Paged<DocsListRequest, DocListEntry>(
        name = "list",
        doc =
            "List the documents of a root without their bodies, ordered by kind then slug. Archived documents are left " +
                "out unless includeArchived. adoptedBy names the item that adopted a plan, when the caller may see it.",
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DocsListRequest>(),
        errors = READ_ERRORS,
        handler = { env, ctx, req -> DocHandlers.list(env, ctx, req) },
        item = { DocJson.entry(it) }
    )

/** `docs_read history`: the versions of one document. */
internal val docsHistorySelect =
    SelectSpec.Paged<DocsHistoryRequest, DocHistoryEntry>(
        name = "history",
        doc = "List the stored versions of one document, oldest first, without bodies. Read one with get and version.",
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DocsHistoryRequest>(),
        errors = READ_ERRORS,
        handler = { env, ctx, req -> DocHandlers.history(env, ctx, req) },
        item = { DocJson.history(it) }
    )

/** `docs_read links`: the links of one document. */
internal val docsLinksSelect =
    SelectSpec.Paged<DocsLinksRequest, DocLinkEntry>(
        name = "links",
        doc =
            "List the links of one document, oldest first: {id, relation, from, to | toRef, anchor?}. toRef is the text " +
                "of a reference that names no document yet. Links to items or notes outside the caller's scope are left out.",
        profile = ToolProfile.KNOWLEDGE,
        request = serializer<DocsLinksRequest>(),
        errors = READ_ERRORS,
        handler = { env, ctx, req -> DocHandlers.links(env, ctx, req) },
        item = { DocJson.link(it) }
    )
