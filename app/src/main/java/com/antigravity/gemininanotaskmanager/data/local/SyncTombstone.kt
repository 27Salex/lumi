package com.antigravity.gemininanotaskmanager.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Tareas borradas en local que aún hay que borrar en Google Tasks en la próxima sincronización. */
@Entity(tableName = "sync_tombstones")
data class SyncTombstone(@PrimaryKey val googleTaskId: String)

@Dao
interface SyncTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: SyncTombstone)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun all(): List<SyncTombstone>

    @Query("DELETE FROM sync_tombstones WHERE googleTaskId = :id")
    suspend fun delete(id: String)
}
