package dataTypes

import dataTypes.dtos.MatchDTO
import dataTypes.enums.Division
import dataTypes.enums.Side
import io.openruntimes.kotlin.src.Group
import io.openruntimes.kotlin.src.Resource
import io.openruntimes.kotlin.src.ScheduleEvent
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Serializable
data class MatchMVP(
    var matchNumber: Int,
    var team1: String?,
    var team2: String?,
    val tournamentId: String,
    var refId: String?,
    var field: Field?,
    override var start: Instant,
    override var end: Instant,
    val division: Division,
    var team1Points: List<Int>,
    var team2Points: List<Int>,
    val losersBracket: Boolean,
    val setResults: List<Int>,
    val refCheckedIn: Boolean?,
    val side: Side = Side.LEFT,
    override var id: String,
) : MVPDocument, ScheduleEvent {
    var previousLeftMatch: MatchMVP? = null
    var previousRightMatch: MatchMVP? = null
    var winnerNextMatch:   MatchMVP? = null
    var loserNextMatch:    MatchMVP? = null
    override val buffer: Duration = (team1Points.size * 5).toLong().minutes

    override fun getGroup(): Group = Group(division.name)
    override fun getResource(): Resource? = field
    override fun setResource(resource: Resource) {
        this.field = resource as Field
    }

    override fun participantIds(): List<String?> = listOf(team1, team2) // stub
    override fun getDependencies(): List<ScheduleEvent> {
        val dependencies = mutableListOf<ScheduleEvent>()
        if (previousLeftMatch != null) {
            dependencies.add(previousLeftMatch!!)
        }
        if (previousRightMatch != null) {
            dependencies.add(previousRightMatch!!)
        }
        return dependencies
    }

    override fun getDependants(): List<ScheduleEvent> {
        val dependants = mutableListOf<ScheduleEvent>()
        if (winnerNextMatch != null) {
            dependants.add(winnerNextMatch!!)
        }
        if (loserNextMatch != null) {
            dependants.add(loserNextMatch!!)
        }
        return dependants
    }

    fun advanceTeams(winner: Team, loser: Team) {
        if (winnerNextMatch == loserNextMatch) {
            if (winner.losses > 0) {
                winnerNextMatch!!.team1 = winner.id
                winnerNextMatch!!.team2 = loser.id
                winnerNextMatch!!.refId = refId
            }
            return
        }
        // assign winner slot
        winnerNextMatch?.let { next ->
            if (side == Side.LEFT && next.team1 == null) next.team1 = winner.id
            else next.team2 = winner.id
        }
        // assign loser slot
        loserNextMatch?.let { next ->
            if (side == Side.LEFT && next.team1 == null) next.team1 = loser.id
            else next.team2 = loser.id
        }
    }

    fun toMatchDTO(): MatchDTO {
        return MatchDTO(
            id = id,
            matchId = matchNumber,
            team1 = team1,
            team2 = team2,
            tournamentId = tournamentId,
            refId = refId,
            field = field?.id,
            start = start.toString(),
            end = end.toString(),
            division = division.name,
            team1Points = team1Points,
            team2Points = team2Points,
            losersBracket = losersBracket,
            winnerNextMatchId = winnerNextMatch?.id,
            loserNextMatchId = loserNextMatch?.id,
            previousLeftId = previousLeftMatch?.id,
            previousRightId = previousRightMatch?.id,
            setResults = setResults,
            refereeCheckedIn = refCheckedIn,
        )
    }

    override fun toString(): String {
        return buildString {
            append("MatchMVP(")
            append("id=$id")
            append(", num=$matchNumber")
            team1?.let { append(", team1=$it") }
            team2?.let { append(", team2=$it") }
            append(", prevLeft=${previousLeftMatch?.id}")
            append(", prevRight=${previousRightMatch?.id}")
            append(", nextWin=${winnerNextMatch?.id}")
            append(", nextLose=${loserNextMatch?.id}")
            append(")")
        }
    }
}