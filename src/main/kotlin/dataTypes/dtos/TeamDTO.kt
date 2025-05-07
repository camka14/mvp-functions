package dataTypes.dtos

import dataTypes.enums.Division
import dataTypes.Team
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TeamDTO (
    var name: String?,
    var tournamentIds: List<String>,
    val eventIds: List<String>,
    var seed: Int,
    var division: String,
    var wins: Int,
    var losses: Int,
    val players: List<String> = emptyList(),
    val captainId: String,
    val pending: List<String> = emptyList(),
    val teamSize: Int,
    @Transient
    val id: String = ""
)

fun TeamDTO.toTeam(id: String): Team {
    return Team(
        name = name,
        tournamentIds = tournamentIds,
        eventIds = eventIds,
        seed = seed,
        division = Division.valueOf(division),
        wins = wins,
        losses = losses,
        players = players,
        captainId = captainId,
        pending = pending,
        teamSize = teamSize,
        id = id
    )
}