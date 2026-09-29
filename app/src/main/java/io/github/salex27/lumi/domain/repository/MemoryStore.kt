package io.github.salex27.lumi.domain.repository

import io.github.salex27.lumi.domain.assistant.MemoryRetriever
import kotlinx.coroutines.flow.Flow

/** Lumi's personal memory. Never sent to a model in full: see [MemoryRetriever.relevant]. */
interface MemoryStore {
    fun observe(): Flow<List<MemoryRetriever.Memory>>
    suspend fun all(): List<MemoryRetriever.Memory>
    suspend fun add(text: String): Long
    suspend fun delete(id: Long)
}
