package io.github.jpicklyk.mcptask.current.application.api.docs

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.knowledge.link.LinkService
import io.github.jpicklyk.mcptask.current.application.knowledge.related.RelatedService
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.KnowledgeStoreProvider
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.platform.port.BodySource
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/**
 * The services `docs_read`, `docs_write` and the knowledge reads of `items_read` (`search`, `related`) take as `env`.
 * A lazy member stays unbuilt until a handler reads it (a provider without a search index fails only when a search
 * runs), and building the holder touches no store.
 */
class KnowledgeToolServices(
    val stores: KnowledgeStoreProvider,
    val unitOfWork: UnitOfWork<RepositoryProvider>,
    val bodySource: BodySource,
    private val document: Lazy<DocumentService>,
    private val link: Lazy<LinkService>,
    private val search: Lazy<SearchService>,
    private val related: Lazy<RelatedService>
) : ItemLookup {
    val documentService: DocumentService by document
    val linkService: LinkService by link
    val searchService: SearchService by search
    val relatedService: RelatedService by related

    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()
}
