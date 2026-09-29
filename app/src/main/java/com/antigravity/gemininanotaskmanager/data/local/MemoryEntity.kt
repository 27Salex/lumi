package com.antigravity.gemininanotaskmanager.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.antigravity.gemininanotaskmanager.domain.assistant.MemoryRetriever
import com.antigravity.gemininanotaskmanager.domain.repository.MemoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un recuerdo personal («el wifi de la oficina es Lumi2024»). Solo en el móvil. */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observe(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)
}

class RoomMemoryStore(private val dao: MemoryDao) : MemoryStore {
    override fun observe(): Flow<List<MemoryRetriever.Memory>> = dao.observe().map { list -> list.map { MemoryRetriever.Memory(it.id, it.text) } }
    override suspend fun all(): List<MemoryRetriever.Memory> = dao.all().map { MemoryRetriever.Memory(it.id, it.text) }
    override suspend fun add(text: String): Long = dao.insert(MemoryEntity(text = text.trim()))
    override suspend fun delete(id: Long) = dao.delete(id)
}
