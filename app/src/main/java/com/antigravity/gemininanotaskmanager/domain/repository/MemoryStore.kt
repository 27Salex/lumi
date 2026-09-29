package com.antigravity.gemininanotaskmanager.domain.repository

import com.antigravity.gemininanotaskmanager.domain.assistant.MemoryRetriever
import kotlinx.coroutines.flow.Flow

/** Memoria personal de Lumi. Nunca se envía entera a un modelo: ver [MemoryRetriever.relevant]. */
interface MemoryStore {
    fun observe(): Flow<List<MemoryRetriever.Memory>>
    suspend fun all(): List<MemoryRetriever.Memory>
    suspend fun add(text: String): Long
    suspend fun delete(id: Long)
}
