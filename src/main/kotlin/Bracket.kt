package io.openruntimes.kotlin.src

import dataTypes.Field
import dataTypes.MatchMVP
import dataTypes.Team
import dataTypes.Tournament
import dataTypes.enums.Division
import dataTypes.enums.Side
import io.appwrite.ID
import io.openruntimes.kotlin.src.dataTypes.enums.Side
import io.openruntimes.kotlin.src.util.Times
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.times

class Brackets(
    private val tournament: Tournament,
    private val matchesMap: MutableMap<String, MatchMVP>,
    private val teamsMap: MutableMap<String, Team>,
    private val fieldsMap: MutableMap<String, Field>
) {
    // preserve original matchNumbers for ID reuse
    private val existingByNumber = matchesMap.values.associateBy { it.matchNumber }

    private lateinit var currentDivision: Division
    private var numberOfRounds = 0
    private lateinit var seedsWithByes: MutableList<Team>
    private lateinit var remainingTeams: MutableList<Team>
    private lateinit var currentFieldIds: List<String>
    private lateinit var bracketSchedule: Schedule

    init {
        // clear previous assignments on fields and teams
        fieldsMap.values.forEach { it.matches = listOf() }
        teamsMap.values.forEach { t -> t.wins = 0; t.losses = 0 }
        bracketSchedule = Schedule(
            start = tournament.start,
            fields = fieldsMap,
            teams = teamsMap,
            divisions = tournament.divisions
        )
    }

    private fun multiplier(m: MatchMVP) = m.team1Points.size

    fun buildBrackets() {
        matchesMap.clear()
        for (division in tournament.divisions) {
            setupDivision(division)
            val teams = teamsMap.values.filter { it.division == division }.toMutableList()
            if (teams.size < 3) continue
            prepareTeams(teams)
            val rootId = createSubMatches(null, numberOfRounds, seedsWithByes.size, Side.RIGHT)
            val finalId = handleDoubleElimination(rootId)
            assignMatchNumbers(finalId)
        }
    }

    private fun setupDivision(div: Division) {
        currentDivision = div
        currentFieldIds = fieldsMap.filterValues { f -> div.name in f.divisions }.keys.toList()
        bracketSchedule.updateFields(currentFieldIds)
    }

    private fun prepareTeams(list: MutableList<Team>) {
        list.sortByDescending { it.seed }
        val byes = calculateByes(list.size)
        seedsWithByes = list.take(byes).toMutableList()
        remainingTeams = list.drop(byes).toMutableList()
    }

    private fun calculateByes(n: Int): Int {
        val (rem, pow) = remainderPowerOfTwo(n)
        numberOfRounds = pow + if (rem > 0) 1 else 0
        val maxPerfect = if (rem > (1.shl(pow) - 1)) rem % (1.shl(pow) - 1) else 0
        return if (rem > 0) rem - maxPerfect else 0
    }

    private fun createSubMatches(
        prevId: String?,
        round: Int,
        byes: Int,
        side: Side
    ): String {
        val matchId = if ((byes > 0 && round <= 2) || (byes == 0 && round <= 1))
            createFinalMatch(prevId, byes, side)
        else
            createIntermediate(prevId, round, byes, side)
        if (tournament.doubleElimination) linkLoserMatch(matchId, side)
        return matchId
    }

    private fun handleDoubleElimination(rootId: String): String {
        if (!tournament.doubleElimination) return rootId
        // create final and semifinal matches
        val finalId = createMatch(null, null, null, null, false, Side.RIGHT)
        val semiId = createMatch(null, null, null, finalId, false, Side.RIGHT)
        // link semifinal
        val rootM = matchesMap[rootId]!!
        val loserOfRoot = rootM.loserNextMatchId
        matchesMap[semiId] = matchesMap[semiId]!!.copy(
            previousLeftMatchId = rootId,
            previousRightMatchId = loserOfRoot
        )
        // link root to semifinal
        matchesMap[rootId] = rootM.copy(winnerNextMatchId = semiId)
        loserOfRoot?.let { lid ->
            val loserM = matchesMap[lid]!!
            matchesMap[lid] = loserM.copy(winnerNextMatchId = semiId)
        }
        // final links
        matchesMap[finalId] = matchesMap[finalId]!!.copy(
            previousLeftMatchId = semiId,
            previousRightMatchId = semiId
        )
        return finalId
    }

    private fun assignMatchNumbers(rootId: String) {
        // BFS for dependency ordering
        val queue = ArrayDeque<String>().apply { add(rootId) }
        val visited = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            visited += id
            matchesMap[id]?.let { m ->
                listOfNotNull(m.previousLeftMatchId, m.previousRightMatchId).forEach {
                    if (it !in visited) queue += it
                }
            }
        }
        // schedule and renumber
        var num = 1
        for (id in visited.reversed()) {
            val m0 = matchesMap[id]!!
            bracketSchedule.scheduleEvent(id, multiplier(m0) * Times.SET)
            // reuse old ID mapping if exists
            val oldMatch = existingByNumber[num]?.id
            if (oldMatch != null && oldMatch != id) {
                matchesMap.remove(oldMatch)
                matchesMap[id] = m0.copy(id = oldMatch)
            }
            // set new matchNumber
            matchesMap[id] = matchesMap[id]!!.copy(matchNumber = num)
            num++
        }
        // assign refs
        visited.forEach { id ->
            val m = matchesMap[id]!!
            if (m.team1 != null && m.team2 != null && m.refId == null) {
                bracketSchedule.freeParticipants(currentDivision, m.start, m.end).firstOrNull()?.let { freeId ->
                    matchesMap[id] = m.copy(refId = freeId)
                    teamsMap[freeId]?.matches?.add(matchesMap[id]!!)
                }
            }
        }
    }

    private fun createMatch(
        t1: Team?,
        t2: Team?,
        ref: Team?,
        nextWinId: String?,
        isLoser: Boolean,
        side: Side
    ): String {
        val mult = if (isLoser) tournament.loserSetCount else tournament.winnerSetCount
        val id = ID.unique()
        matchesMap[id] = MatchMVP(
            matchNumber = 0,
            team1 = t1?.id,
            team2 = t2?.id,
            tournamentId = tournament.id,
            refId = ref?.id,
            field = null,
            start = tournament.start,
            end = tournament.end,
            division = currentDivision,
            team1Points = List(mult) { 0 },
            team2Points = List(mult) { 0 },
            losersBracket = isLoser,
            winnerNextMatchId = nextWinId,
            loserNextMatchId = null,
            previousLeftMatchId = null,
            previousRightMatchId = null,
            setResults = List(mult) { 0 },
            refCheckedIn = false,
            id = id
        )
        return id
    }

    private fun createFinalMatch(
        prevId: String?,
        byes: Int,
        side: Side
    ): String {
        return when (byes) {
            1 -> {
                val high = seedsWithByes.removeAt(0)
                val leftSeed = if (side == Side.LEFT) high else null
                val rightSeed = if (side == Side.RIGHT) high else null
                val newId = createMatch(leftSeed, rightSeed, null, prevId, false, side)
                val low1 = remainingTeams.removeAt(0)
                val low2 = remainingTeams.removeAt(0)
                val lowId = createMatch(low1, low2, high, newId, false, side.opposite())
                val m = matchesMap[newId]!!
                matchesMap[newId] = if (side == Side.LEFT)
                    m.copy(previousRightMatchId = lowId)
                else
                    m.copy(previousLeftMatchId = lowId)
                newId
            }
            2 -> {
                val newId = createMatch(null, null, null, prevId, false, side)
                val h1 = seedsWithByes.removeAt(0)
                val h2 = seedsWithByes.removeAt(0)
                val l1 = remainingTeams.removeAt(0)
                val l2 = remainingTeams.removeAt(0)
                val m1 = createMatch(h1, l1, null, newId, false, side)
                val m2 = createMatch(h2, l2, null, newId, false, side.opposite())
                val m = matchesMap[newId]!!
                matchesMap[newId] = if (side == Side.LEFT)
                    m.copy(previousLeftMatchId = m1, previousRightMatchId = m2)
                else
                    m.copy(previousLeftMatchId = m2, previousRightMatchId = m1)
                newId
            }
            else -> {
                val hiIndex = 0
                val loRaw = -2 + 2 * seedsWithByes.size
                val loIndex = if (loRaw >= 0) loRaw else remainingTeams.size + loRaw
                val t1 = remainingTeams.removeAt(hiIndex)
                val t2 = remainingTeams.removeAt(loIndex)
                createMatch(t1, t2, null, prevId, false, side)
            }
        }
    }

    private fun createIntermediate(
        prevId: String?,
        round: Int,
        byes: Int,
        side: Side
    ): String {
        val (leftByes, rightByes) =
            if (side == Side.LEFT) (floor(byes / 2.0).toInt() to ceil(byes / 2.0).toInt())
            else (ceil(byes / 2.0).toInt() to floor(byes / 2.0).toInt())
        val newId = createMatch(null, null, null, prevId, false, side)
        val leftId = createSubMatches(newId, round - 1, leftByes, Side.LEFT)
        val rightId = createSubMatches(newId, round - 1, rightByes, Side.RIGHT)
        val m = matchesMap[newId]!!
        matchesMap[newId] = m.copy(
            previousLeftMatchId = leftId,
            previousRightMatchId = rightId
        )
        return newId
    }

    private fun linkLoserMatch(matchId: String, side: Side) {
        val m = matchesMap[matchId]!!
        val prevs = listOfNotNull(m.previousLeftMatchId, m.previousRightMatchId)
        val loserNextId = when (prevs.size) {
            1 -> {
                val prevId = prevs[0]
                val lnId = createMatch(null, null, null, null, true, side)
                val ln = matchesMap[lnId]!!
                matchesMap[lnId] = if (side == Side.LEFT)
                    ln.copy(previousRightMatchId = prevId)
                else
                    ln.copy(previousLeftMatchId = prevId)
                val prev = matchesMap[prevId]!!
                matchesMap[prevId] = prev.copy(loserNextMatchId = lnId)
                lnId
            }
            2 -> {
                val lnId = createMatch(null, null, null, null, true, side)
                val lpId = createMatch(null, null, null, lnId, true, side.opposite())
                // link loser-next
                val ln = matchesMap[lnId]!!
                matchesMap[lnId] = if (side == Side.LEFT)
                    ln.copy(previousLeftMatchId = matchId, previousRightMatchId = lpId)
                else
                    ln.copy(previousRightMatchId = matchId, previousLeftMatchId = lpId)
                // connect lpId to previous left
                val leftPrevId = m.previousLeftMatchId!!
                val leftPrev = matchesMap[leftPrevId]!!
                if (leftPrev.loserNextMatchId != null) {
                    val target = leftPrev.loserNextMatchId!!
                    val lp = matchesMap[lpId]!!
                    matchesMap[lpId] = lp.copy(winnerNextMatchId = target)
                    val tgt = matchesMap[target]!!
                    matchesMap[target] = if (side == Side.LEFT)
                        tgt.copy(previousLeftMatchId = lpId)
                    else
                        tgt.copy(previousRightMatchId = lpId)
                } else {
                    val lp = matchesMap[lpId]!!
                    matchesMap[lpId] = lp.copy(loserNextMatchId = leftPrevId)
                    val prev = matchesMap[leftPrevId]!!
                    matchesMap[leftPrevId] = if (side == Side.LEFT)
                        prev.copy(previousLeftMatchId = lpId)
                    else
                        prev.copy(previousRightMatchId = lpId)
                }
                // connect to previous right
                val rightPrevId = m.previousRightMatchId!!
                val rightPrev = matchesMap[rightPrevId]!!
                if (rightPrev.loserNextMatchId != null) {
                    val target = rightPrev.loserNextMatchId!!
                    val lp = matchesMap[lpId]!!
                    matchesMap[lpId] = lp.copy(winnerNextMatchId = target)
                    val tgt = matchesMap[target]!!
                    matchesMap[target] = if (side == Side.RIGHT)
                        tgt.copy(previousLeftMatchId = lpId)
                    else
                        tgt.copy(previousRightMatchId = lpId)
                } else {
                    val lp = matchesMap[lpId]!!
                    matchesMap[lpId] = lp.copy(loserNextMatchId = rightPrevId)
                    val prev = matchesMap[rightPrevId]!!
                    matchesMap[rightPrevId] = if (side == Side.RIGHT)
                        prev.copy(previousLeftMatchId = lpId)
                    else
                        prev.copy(previousRightMatchId = lpId)
                }
                lnId
            }
            else -> return
        }
        // update original match
        matchesMap[matchId] = m.copy(loserNextMatchId = loserNextId)
    }

    private fun findLargestPowerOfTwo(n: Int): Int {
        var x = 0
        while (n shr x != 0) x++
        return x - 1
    }

    private fun remainderPowerOfTwo(n: Int): Pair<Int, Int> {
        val p = findLargestPowerOfTwo(n)
        val lp = 1 shl p
        return (n - lp) to p
    }

    // getters
    fun getTournament() = tournament
    fun getMatches() = matchesMap
    fun getTeams() = teamsMap
    fun getFields() = fieldsMap
}
