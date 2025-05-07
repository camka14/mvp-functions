package dataTypes

import androidx.room.Entity
import androidx.room.PrimaryKey

@Serializable
@Entity
data class ChatGroup (
    @Transient
    @PrimaryKey
    override val id: String = "",
    val name: String,
    val userIds: List<String>,
    val hostId: String,
): MVPDocument