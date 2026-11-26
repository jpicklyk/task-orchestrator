package io.github.jpicklyk.mcptask.current.knowledge

import io.github.jpicklyk.mcptask.current.dependency.DependencyStoreProvider
import io.github.jpicklyk.mcptask.current.item.HierarchyStore
import io.github.jpicklyk.mcptask.current.item.ItemStore
import java.util.UUID

/**
 * The note facts the knowledge slice reads: which item a note sits on, and which notes sit on an item. Knowledge owns
 * this port so it never names the note store; the note store implements it.
 */
interface NoteItems {
    /** The item each note of [noteIds] sits on, for the notes that exist; absent ids are omitted. */
    suspend fun itemsOfNotes(noteIds: Set<UUID>): Map<UUID, UUID>

    /** The ids of the notes on [itemId], in the store's order. */
    suspend fun noteIdsOn(itemId: UUID): List<UUID>

    /** The id of the note [key] on [itemId], or null when there is none. */
    suspend fun noteIdOf(
        itemId: UUID,
        key: String
    ): UUID?
}

/**
 * The stores the knowledge slice (documents, links, plan documents, related reads) reads and writes.
 * `RepositoryProvider` implements it.
 */
interface KnowledgeStoreProvider : DependencyStoreProvider {
    fun itemStore(): ItemStore

    fun hierarchyStore(): HierarchyStore

    fun planDocumentRepository(): PlanDocumentStore

    fun documentStore(): DocumentStore

    fun linkStore(): LinkStore

    fun noteItems(): NoteItems
}
