package io.github.jpicklyk.mcptask.current.application.api.notes

import io.github.jpicklyk.mcptask.current.application.port.ItemLookup
import io.github.jpicklyk.mcptask.current.application.port.NoteStoreProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.platform.port.BodySource
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/** The services `notes_read` and `notes_write` take as `env`. Building the holder touches no store. */
class NoteToolServices(
    val stores: NoteStoreProvider,
    val unitOfWork: UnitOfWork<NoteStoreProvider>,
    val bodySource: BodySource,
    private val noteCommand: Lazy<NoteCommandService>
) : ItemLookup {
    val noteCommandService: NoteCommandService by noteCommand

    override fun workItemRepository(): WorkItemRepository = stores.workItemRepository()
}
