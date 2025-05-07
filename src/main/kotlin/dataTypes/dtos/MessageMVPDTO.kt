package dataTypes.dtos

import dataTypes.MessageMVP
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MessageMVPDTO (
    @Transient
    val id: String = "",
    val userId: String,
    val body: String,
    val attachmentUrls: List<String>,
    val chatId: String,
    val readByIds: List<String>,
    val sentTime: String
) {
    fun toMessageMVP(id: String): MessageMVP {
        return MessageMVP(
            id = id,
            userId = userId,
            body = body,
            attachmentUrls = attachmentUrls,
            chatId = chatId,
            readByIds = readByIds,
            sentTime = Instant.parse(sentTime)
        )
    }
}