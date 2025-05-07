package io.openruntimes.kotlin.src

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dataTypes.Field
import dataTypes.MatchMVP
import dataTypes.Team
import dataTypes.Tournament
import io.openruntimes.kotlin.RuntimeContext
import io.openruntimes.kotlin.RuntimeOutput
import io.openruntimes.kotlin.src.util.Times
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.time.format.DateTimeParseException
import kotlin.math.ceil
import kotlin.time.times

class Main {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _remoteTournament = MutableStateFlow<Result<Tournament?>>(Result.success(null))
    private var _remoteMatches = MutableStateFlow<Result<Map<String, MatchMVP>?>>(Result.success(null))
    private var _remoteTeams = MutableStateFlow<Result<Map<String, Team>?>>(Result.success(null))
    private var _remoteFields = MutableStateFlow<Result<Map<String, Field>?>>(Result.success(null))

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
        val getterJob = scope.launch {
            _remoteTournament.value = db.getTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get tournament: $tournamentId")) }
            _remoteMatches.value = db.getMatchesForTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get matches of tournament: $tournamentId\n${it.message}")) }
            _remoteTeams.value = db.getTeamsForTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get teams of tournament: $tournamentId\n${it.message}")) }
            _remoteFields.value = db.getFieldsOfTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get fields of tournament: $tournamentId\n${it.message}")) }
        }
        runBlocking { getterJob.join() }

        val tournament = _remoteTournament.value.onFailure { return context.res.text("Fetching Tournament Failed") }
            .getOrNull() ?: return context.res.text("Tournament not found")
        val matches = _remoteMatches.value.onFailure { return context.res.text("Fetching Matches Failed") }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        val teams = _remoteTeams.value.onFailure { return context.res.text("Fetching Teams Failed") }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        val fields = _remoteFields.value.onFailure { return context.res.text("Fetching Teams Failed") }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()

        val brackets = Bracket(tournament, matches, teams, fields)
        brackets.buildBrackets()

        val updateJob = scope.launch {
            db.updateMatches(brackets.getMatches().values.toList())
        }
        runBlocking { updateJob.join() }
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
        } ?: Clock.System.now()

        val db = Database()
        val getterJob = scope.launch {
            _remoteTournament.value = db.getTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get tournament: $tournamentId")) }
            _remoteMatches.value = db.getMatchesForTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get matches of tournament: $tournamentId\n${it.message}")) }
            _remoteTeams.value = db.getTeamsForTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get teams of tournament: $tournamentId\n${it.message}")) }
            _remoteFields.value = db.getFieldsOfTournament(tournamentId)
                .onFailure { context.logger.write(arrayOf("Failed to get fields of tournament: $tournamentId\n${it.message}")) }
        }
        runBlocking { getterJob.join() }

        val tournament = _remoteTournament.value.onFailure { return context.res.text("Fetching Tournament Failed") }
            .getOrNull() ?: return context.res.text("Tournament not found")
        val matches = _remoteMatches.value.onFailure { return context.res.text("Fetching Matches Failed") }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        val teams = _remoteTeams.value.onFailure { return context.res.text("Fetching Teams Failed") }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        val fields = _remoteFields.value.onFailure { return context.res.text("Fetching Teams Failed") }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()

        // Locate the updated match
        val updated = matches[matchId] ?: return context.res.text("No match with ID '${'$'}matchId'")
        val currentDivision = updated.division

        // Schedule helper
        val scheduler = Scheduler(
            startTime = tournament.start,
            resources = fields,
            participants = teams,
            groups = tournament.divisions.map { Group(it.name) },
            currentTime = currentTime,
        )

        // Winner/loser logic
        val team1Wins = updated.setResults.count { it == 1 }
        val team2Wins = updated.setResults.count { it == 2 }
        val (winner, loser) = if (team1Wins > team2Wins) {
            teams[updated.team1]!! to teams[updated.team2]!!
        } else {
            teams[updated.team2]!! to teams[updated.team1]!!
        }
        winner.wins += 1
        loser.losses += 1
        updated.advanceTeams(winner, loser)

        // 1) Find the root match (highest matchNumber)
        val rootMatch = matches.values.maxByOrNull { it.matchNumber }
            ?: return context.res.text("No matches to update")

// 2) BFS‐walk the bracket, exactly like Python _apply_match_ids
        val queue = ArrayDeque<MatchMVP>().apply { add(rootMatch) }
        val walkMatches = mutableListOf<MatchMVP>()
        while (queue.isNotEmpty()) {
            val m = queue.removeFirst()
            if (m in walkMatches) continue
            walkMatches += m

            // get immediate predecessors
            val preds = listOfNotNull(m.previousLeftMatch, m.previousRightMatch)
            for (prev in preds) {
                // skip cross‐bracket
                if (m.losersBracket && !prev.losersBracket) continue
                queue += prev

                // if exactly one side is in the losers bracket, enqueue that “flipped” branch
                val left = prev.previousLeftMatch
                val right = prev.previousRightMatch
                if (prev.losersBracket && left != null && right != null &&
                    (left.losersBracket xor right.losersBracket)
                ) {
                    queue += (if (left.losersBracket) left else right)
                }
            }
        }

        processMatches(walkMatches, scheduler, updated, matches)

        scheduler.getParticipantConflicts().forEach { (participant, conflicts) ->
            conflicts.forEach { conflictMatch ->
                conflictMatch as MatchMVP
                if (participant.id == conflictMatch.refId) {
                    scheduler.freeParticipants(
                        Group(currentDivision.name),
                        conflictMatch.start,
                        conflictMatch.end
                    ).firstOrNull { (it as Team).losses == 0 && !isTeamInPreviousMatch(it, conflictMatch) }
                        ?.let { freeTeam ->
                            conflictMatch.refId = freeTeam.id
                        }
                }
            }
        }

        val upcoming = if (updated.losersBracket) {
            getUpcomingMatchesInTimeRange(
                updated.end,
                updated.winnerNextMatch!!.start,
                matches,
                true
            ).also { list ->
                list.firstOrNull { it.refId == null }?.apply {
                    refId = winner.id
                }
            } + getUpcomingMatchesInTimeRange(
                updated.end, rootMatch.end, matches, false
            )
        } else {
            getUpcomingMatchesInTimeRange(
                updated.end,
                updated.loserNextMatch!!.start,
                matches,
                true
            )
        }

        upcoming.firstOrNull { it.refId == null }?.apply {
            refId = if (updated.losersBracket) winner.id else loser.id
        }

        teamsWaitingToStart(teams.values, matches.values.toList(), currentTime).forEach { (team, teamMatches) ->
            if (team.losses == 0) {
                getUpcomingMatchesInTimeRange(
                    currentTime,
                    teamMatches.minByOrNull { it.start }!!.start,
                    matches,
                    true
                ).firstOrNull { it.refId == null }?.also { next ->
                    next.refId = team.id
                }
            }
        }

        runBlocking {
            db.updateMatches(matches.values.toList())
        }

        return context.res.text("")
    }

    /**
     * Find all upcoming matches in a time window.
     *
     * @param beginning The start of the interval.
     * @param end The end of the interval.
     * @param matches Map of match IDs to MatchMVP.
     * @param mustBeNextMatch If true, only include matches whose predecessors are complete.
     * @return A list of matches sorted by start time (asc), then duration (desc).
     */
    private fun getUpcomingMatchesInTimeRange(
        beginning: Instant,
        end: Instant,
        matches: Map<String, MatchMVP>,
        mustBeNextMatch: Boolean
    ): List<MatchMVP> {
        val inRange = mutableListOf<MatchMVP>()
        for (m in matches.values) {
            var isNext = true
            if (mustBeNextMatch) {
                m.previousLeftMatch?.let { if (!isMatchOver(it)) isNext = false }
                m.previousRightMatch?.let { if (!isMatchOver(it)) isNext = false }
            }
            if (isNext && m.start >= beginning && m.end <= end) {
                inRange += m
            }
        }
        // sort by start ascending, then by duration descending
        inRange.sortBy { it.start }
        inRange.sortByDescending { it.start - it.end }
        return inRange
    }

    /**
     * Returns true if a match has reached its deciding sets.
     *
     * @param match The match to check.
     */
    private fun isMatchOver(match: MatchMVP): Boolean {
        val wins1 = match.setResults.count { it == 1 }
        val wins2 = match.setResults.count { it == 2 }
        val toWin = ceil(match.setResults.size / 2.0).toInt()
        return wins1 >= toWin || wins2 >= toWin
    }

    /**
     * Returns teams which have a match scheduled after `currentTime` but are not in it.
     *
     * @param teams Collection of teams to inspect.
     * @param currentTime Reference time.
     */
    private fun teamsWaitingToStart(
        teams: Collection<Team>,
        matches: List<MatchMVP>,
        currentTime: Instant
    ): List<Pair<Team, List<MatchMVP>>> {
        val waiting = mutableListOf<Pair<Team, List<MatchMVP>>>()
        for (team in teams) {
            for (m in matches.filter { it.team1 == team.id || it.team2 == team.id }) {
                if (m.start > currentTime && m.refId != team.id) {
                    waiting += Pair(team, matches)
                    break
                }
            }
        }
        return waiting
    }

    /**
     * Checks if a given team appears in either predecessor of a match.
     *
     * @param team The team to look for.
     * @param match The match whose predecessors to scan.
     */
    private fun isTeamInPreviousMatch(team: Team, match: MatchMVP): Boolean {
        val deps = listOfNotNull(match.previousLeftMatch, match.previousRightMatch)
        return deps.any { it.team1 == team.id || it.team2 == team.id }
    }

    /**
     * Unschedules any matches still booked on the updated match’s field.
     *
     * @param updated The match just updated.
     * @param allMatches Map of all matches by ID.
     */
    private fun unscheduleMatchesOnField(
        updated: MatchMVP,
        allMatches: Map<String, MatchMVP>
    ) {
        val field = updated.field ?: return
        // field.matches holds IDs of matches on that field
        for (mid in field.matches) {
            allMatches[mid]?.let { m ->
                if (m.start > updated.start || m.refId == null) {
                    m.field = null
                }
            }
        }
    }

    /**
     * Reschedules a list of matches around one updated match.
     *
     * @param walkMatches The BFS‐collected matches in bracket order.
     * @param scheduler Your Scheduler instance.
     * @param updated The match that just finished.
     * @param allMatches Map of all matches by ID.
     */
    private fun processMatches(
        walkMatches: List<MatchMVP>,
        scheduler: Scheduler,
        updated: MatchMVP,
        allMatches: Map<String, MatchMVP>
    ) {
        unscheduleMatchesOnField(updated, allMatches)
        // schedule winner‐first
        for (m in walkMatches.asReversed()) {
            if (m.field == null) {
                // assume Times.SET * m.team1Points.size is how you compute Duration
                val duration = (m.team1Points.size * Times.SET)
                scheduler.scheduleEvent(m, duration)
            }
        }
    }
}
