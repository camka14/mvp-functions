package dataTypes

import dataTypes.dtos.UserDataDTO
import kotlinx.serialization.Serializable

@Serializable
data class UserData(
    val firstName: String,
    val lastName: String,
    val tournamentIds: List<String>,
    val eventIds: List<String>,
    val teamIds: List<String>,
    val friendIds: List<String>,
    val userName: String,
    val teamInvites: List<String>,
    val eventInvites: List<String>,
    val tournamentInvites: List<String>,
    override val id: String,
) : MVPDocument {
    companion object {
        operator fun invoke(): UserData {
            return UserData(
                firstName = "",
                lastName = "",
                tournamentIds = emptyList(),
                eventIds = emptyList(),
                teamIds = emptyList(),
                friendIds = emptyList(),
                userName = "",
                teamInvites = emptyList(),
                eventInvites = emptyList(),
                tournamentInvites = emptyList(),
                id = ""
            )
        }
    }

    fun toUserDataDTO(): UserDataDTO {
        return UserDataDTO(
            firstName,
            lastName,
            tournamentIds,
            eventIds,
            teamIds,
            friendIds,
            userName,
            teamInvites,
            eventInvites,
            tournamentInvites,
            id
        )
    }
}