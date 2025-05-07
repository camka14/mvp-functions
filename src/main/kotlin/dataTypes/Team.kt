package dataTypes

import dataTypes.dtos.TeamDTO
import dataTypes.enums.Division
import io.appwrite.ID
import io.openruntimes.kotlin.src.Group
import io.openruntimes.kotlin.src.Participant
import io.openruntimes.kotlin.src.ScheduleEvent
import kotlinx.serialization.Serializable

@Serializable
data class Team(
    val tournamentIds: List<String>,
    val eventIds: List<String>,
    val seed: Int,
    val division: Division,
    var wins: Int,
    var losses: Int,
    val name: String?,
    val captainId: String,
    val players: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val teamSize: Int,
    override val id: String
) : MVPDocument, Participant {

    companion object {
        operator fun invoke(captainId: String): Team {
            return Team(
                tournamentIds = listOf(),
                eventIds = listOf(),
                seed = 0,
                division = Division.NOVICE,
                wins = 0,
                losses = 0,
                name = null,
                players = listOf(captainId),
                teamSize = 2,
                id = ID.unique(),
                captainId = captainId
            )
        }
    }

    override fun getGroup(): Group = Group(division.name)
    override fun getEvents(): List<ScheduleEvent> = emptyList()

    fun toTeamDTO(): TeamDTO {
        return TeamDTO(
            name = name,
            tournamentIds = tournamentIds,
            eventIds = eventIds,
            seed = seed,
            division = division.name,
            wins = wins,
            losses = losses,
            players = players,
            captainId = captainId,
            teamSize = teamSize,
            id = id,
            pending = pending
        )
    }
}