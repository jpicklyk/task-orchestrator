package io.github.jpicklyk.mcptask.current.application.port

/**
 * The stores the note slice reads and writes. It extends [KnowledgeStoreProvider] because note writes record their
 * links through the knowledge link service. [RepositoryProvider] implements it.
 */
interface NoteStoreProvider : KnowledgeStoreProvider {
    fun noteRepository(): NoteStore
}
