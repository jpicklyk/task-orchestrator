package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.Note
import java.util.UUID

interface NoteRepository {
    suspend fun getById(id: UUID): Result<Note>
    suspend fun upsert(note: Note): Result<Note>
    suspend fun delete(id: UUID): Result<Boolean>
    suspend fun deleteByItemId(itemId: UUID): Result<Int>
    suspend fun findByItemId(itemId: UUID, role: String? = null): Result<List<Note>>
    suspend fun findByItemIdAndKey(itemId: UUID, key: String): Result<Note?>
}
