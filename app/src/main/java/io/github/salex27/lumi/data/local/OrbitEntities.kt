package io.github.salex27.lumi.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * An AI agent the user talks with in Orbit (v9). [backend] says who answers (see `domain/orbit/AgentBackendKind`);
 * secrets (API keys) never live here: they stay in a private prefs file outside the backup.
 */
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** LUMI (Lumi's own brain) | CLAUDE_PC (Claude Code through Lumi Hub) | more with the selectable brain. */
    @ColumnInfo(name = "backend") val backend: String,
    /** Key of the agent palette (AgentPalette). */
    @ColumnInfo(name = "color") val color: String,
    /** Key of the face style (AgentFaceStyle). */
    @ColumnInfo(name = "face") val face: String,
    /** Optional purpose / system prompt. */
    @ColumnInfo(name = "purpose") val purpose: String = "",
    /** May read the user's open tasks as context. */
    @ColumnInfo(name = "can_read_tasks") val canReadTasks: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** Backend settings as JSON (e.g. which Hub agent), never secrets. */
    @ColumnInfo(name = "config") val config: String = ""
)

/** Which agents take part in an Orbit (a chat session of kind ORBIT). */
@Entity(
    tableName = "orbit_members",
    primaryKeys = ["session_id", "agent_id"],
    foreignKeys = [
        ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AgentEntity::class, parentColumns = ["id"], childColumns = ["agent_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("agent_id")]
)
data class OrbitMemberEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "agent_id") val agentId: Long
)

@Dao
interface OrbitDao {
    @Query("SELECT * FROM agents ORDER BY id")
    fun observeAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY id")
    suspend fun agents(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun agent(id: Long): AgentEntity?

    @Insert
    suspend fun insertAgent(agent: AgentEntity): Long

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteAgent(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: OrbitMemberEntity)

    @Query("DELETE FROM orbit_members WHERE session_id = :sessionId AND agent_id = :agentId")
    suspend fun removeMember(sessionId: Long, agentId: Long)

    @Query("SELECT a.* FROM agents a JOIN orbit_members m ON m.agent_id = a.id WHERE m.session_id = :sessionId ORDER BY a.id")
    fun observeMembers(sessionId: Long): Flow<List<AgentEntity>>

    @Query("SELECT a.* FROM agents a JOIN orbit_members m ON m.agent_id = a.id WHERE m.session_id = :sessionId ORDER BY a.id")
    suspend fun members(sessionId: Long): List<AgentEntity>

    @Query("SELECT * FROM orbit_members")
    suspend fun allMembers(): List<OrbitMemberEntity>
}
