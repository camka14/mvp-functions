package io.openruntimes.kotlin.src

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dataTypes.MatchMVP
import dataTypes.Team
import dataTypes.Tournament
import io.openruntimes.kotlin.RuntimeContext
import io.openruntimes.kotlin.RuntimeOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class Main {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _remoteTournament = MutableStateFlow<Result<Tournament?>>(Result.success(null))
    private var _remoteMatches = MutableStateFlow<Result<Map<String, MatchMVP>?>>(Result.success(null))
    private var _remoteTeams = MutableStateFlow<Result<Map<String, Team>?>>(Result.success(null))

    fun main(context: RuntimeContext): RuntimeOutput {
        val bodyString = when (val rawBody = context.req.body) {
            is String -> rawBody
            is JsonElement -> rawBody.toString()
            else -> gson.toJson(rawBody)
        }

        val payload: JsonObject = try {
            JsonParser.parseString(bodyString).asJsonObject
        } catch (e: Exception) {
            return context.res.text("Invalid JSON: ${e.message}")
        }

        val action = payload.get("action")?.asString ?: return context.res.text("Missing 'action'")

        return when (action) {
            "buildBracket" -> handleBuildBracket(payload, context)
            "updateMatch" -> handleUpdateMatch(payload, context)
            else -> context.res.text("Unknown action: $action")
        }
    }

    fun handleBuildBracket(payload: JsonObject, context: RuntimeContext): RuntimeOutput {
        val tournamentId = payload.get("tournament")?.asString ?: return context.res.text("Missing 'tournament'")

        val db = Database()
        val job = scope.launch {
            _remoteTournament.value = db.getTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get tournament: $tournamentId")) }
            _remoteMatches.value = db.getMatchesForTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get matches of tournament: $tournamentId\n${it.message}")) }
            _remoteTeams.value = db.getTeamsForTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get teams of tournament: $tournamentId\n${it.message}")) }
        }
        runBlocking { job.join() }

        val tournament = _remoteTournament.value.onFailure { return context.res.text("Fetching Tournament Failed") }
                .getOrNull() ?: return context.res.text("Tournament not found")
        val matches = _remoteMatches.value.onFailure { return context.res.text("Fetching Matches Failed") }
            .getOrNull() ?: emptyMap()
        val teams = _remoteTeams.value.onFailure { return context.res.text("Fetching Teams Failed") }
                .getOrNull() ?: emptyMap()

        val brackets = Bracket(tournament, matches, teams)
        brackets.buildBrackets()

        db.updateTournament(brackets.tournament)
        db.updateMatches()
        context.logger.write(arrayOf("Built brackets for ${'$'}tournamentId"))

        return context.res.text("")
    }

    fun handleUpdateMatch(payload: JsonObject, context: RuntimeContext): RuntimeOutput {
        val tournamentId = payload.get("tournament")?.asString ?: return context.res.text("Missing 'tournament'")
        val matchId = payload.get("matchId")?.asString ?: return context.res.text("Missing 'matchId'")

        val currentTime = payload.get("time")?.asString?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                return context.res.text("Invalid 'time' format: ${'$'}{e.message}")
            }
        } ?: Instant.now()

        val db = Database()
        val job = scope.launch {
            _remoteTournament.value = db.getTournament(tournamentId).getOrElse { null }
            _remoteMatches.value = db.getMatchesForTournament(tournamentId).getOrElse { null }
            _remoteTeams.value = db.getTeamsForTournament(tournamentId).getOrElse { null }
        }
        runBlocking { job.join() }

        val doc = _remoteTournament.value ?: return context.res.text("Tournament not found")
        val matchesMap = _remoteMatches.value ?: emptyMap()
        val teamsMap = _remoteTeams.value ?: emptyMap()

        // Build a mutable tournament
        val tournament = doc.copy(
            matches = matchesMap, teams = teamsMap
        )

        // Locate the updated match
        val updated = tournament.matches[matchId] ?: return context.res.text("No match with ID '${'$'}matchId'")

        // Schedule helper
        val schedule = Schedule(
            start = tournament.start,
            fields = tournament.fields,
            teams = tournament.teams,
            divisions = tournament.divisions,
            now = currentTime
        )

        // Winner/loser logic
        val team1Wins = updated.setResults.count { it == 1 }
        val team2Wins = updated.setResults.count { it == 2 }
        val (winner, loser) = if (team1Wins > team2Wins) {
            updated.team1 to updated.team2
        } else {
            updated.team2 to updated.team1
        }
        winner.wins += 1
        loser.losses += 1
        updated.advanceTeams(winner, loser)

        // Walk bracket
        val rootMatch = tournament.matches.values.sortedBy { it.matchNumber }.last()
        val queue = ArrayDeque<MatchMVP>().apply { add(rootMatch) }
        val walkMatches = mutableListOf<MatchMVP>()

        while (queue.isNotEmpty()) {
            val m = queue.removeFirst()
            walkMatches += m
            for (prev in m.previousMatches()) {
                if (prev == null || walkMatches.contains(prev) || queue.contains(prev)) continue
                if (m.losersBracket && prev.losersBracket != m.losersBracket) continue
                queue += prev
                if (prev.losersBracket && (prev.leftPrevious!!.losersBracket xor prev.rightPrevious!!.losersBracket)) {
                    queue += if (prev.leftPrevious!!.losersBracket) prev.leftPrevious!! else prev.rightPrevious!!
                }
            }
        }

        // Reschedule
        processMatches(walkMatches, schedule, updated, tournament)

        // Resolve conflicts
        schedule.getParticipantConflicts().forEach { (team, conflicts) ->
            conflicts.forEach { match ->
                if (team == match.refId) {
                    team.matches.remove(match)
                    schedule.freeParticipants(match.division, currentTime, match.end)
                        .firstOrNull { it.losses == 0 && !isTeamInPreviousMatch(it, match) }?.also { freeTeam ->
                            match.refId = freeTeam
                            freeTeam.matches += match
                        }
                }
            }
        }

        // Next slots
        val nextMatches = if (updated.losersBracket) {
            getUpcomingMatchesInTimeRange(
                updated.end, updated.winnerNextMatch!!.start, tournament.matches, true
            ).also { list ->
                list.firstOrNull { it.refId == null }?.apply {
                    refId = winner
                    winner.matches += this
                }
            } + getUpcomingMatchesInTimeRange(
                updated.end, tournament.end, tournament.matches, false
            )
        } else {
            getUpcomingMatchesInTimeRange(
                updated.end, updated.loserNextMatch!!.start, tournament.matches, true
            )
        }
        nextMatches.firstOrNull { it.refId == null }?.apply {
            refId = if (updated.losersBracket) winner else loser
            (if (updated.losersBracket) winner else loser).matches += this
        }

        // Teams waiting
        teamsWaitingToStart(tournament.teams.values, currentTime).forEach { team ->
            if (team.losses == 0) {
                getUpcomingMatchesInTimeRange(
                    currentTime, team.matches.last().start, tournament.matches, true
                ).firstOrNull { it.refId == null }?.also { next ->
                    next.refId = team
                    team.matches += next
                }
            }
        }

        // Persist
        db.updateTournament(tournament)
        context.logger.write(arrayOf("Updated match ${'$'}matchId in tournament ${'$'}tournamentId"))
        return context.res.text("")
    }
}
