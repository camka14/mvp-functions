package dataTypes

import kotlinx.serialization.Serializable

@Serializable
data class ChatGroup (
    override val id: String = "",
    val name: String,
    val userIds: List<String>,
    val hostId: String,
): MVPDocument