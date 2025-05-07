package dataTypes

import dataTypes.dtos.MessageMVPDTO
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MessageMVP (
    @Transient
    override val id: String = "",
    val userId: String,
    val body: String,
    val attachmentUrls: List<String>,
    val chatId: String,
    val readByIds: List<String>,
    val sentTime: Instant
): MVPDocument {
    fun toMessageMVPDTO() = MessageMVPDTO(
        id = id,
        userId = userId,
        body = body,
        attachmentUrls = attachmentUrls,
        chatId = chatId,
        readByIds = readByIds,
        sentTime = sentTime.toString()
    )
}