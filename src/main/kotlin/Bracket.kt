package io.openruntimes.kotlin.src

import dataTypes.Field
import dataTypes.MatchMVP
import dataTypes.Team
import dataTypes.Tournament
import dataTypes.enums.Division
import dataTypes.enums.Side
import io.appwrite.ID
import io.openruntimes.kotlin.src.util.Times
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.times

/**
 * Responsible for constructing and scheduling tournament brackets,
 * including seeding, bye handling, double-elimination branches,
 * match numbering, and referee assignment.
 *
 * @param tournament The tournament data model containing settings and initial fields.
 * @param matchesMap Mutable map of match ID to MatchMVP for storage and lookup.
 * @param teamsMap Mutable map of team ID to Team representing participants.
 * @param fieldsMap Mutable map of field ID to Field representing available resources.
 */
class Bracket(
    private val tournament: Tournament,
    private val matchesMap: MutableMap<String, MatchMVP>,
    private val teamsMap: MutableMap<String, Team>,
    private val fieldsMap: MutableMap<String, Field>
) {
    private lateinit var currentDivision: Division
    private var numberOfRounds = 0
    private lateinit var seedsWithByes: MutableList<Team>
    private lateinit var remainingTeams: MutableList<Team>
    private lateinit var currentFieldIds: List<String>
    private val bracketScheduler: Scheduler
    private val existingByNumber: Map<Int, MatchMVP> = matchesMap.map { (_, match) ->
        match.matchNumber to match
    }.toMap()

    init {
        fieldsMap.values.forEach { it.matches = listOf() }
        bracketScheduler = Scheduler(
            startTime = tournament.start,
            resources = fieldsMap,
            participants = teamsMap,
            groups = tournament.divisions.map { Group(it.name) }
        )
    }

    /**
     * Returns the duration multiplier (set count) for a match.
     *
     * @param m The match for which to compute the multiplier.
     * @return Number of sets (multiplier) for this match.
     */
    private fun multiplier(m: MatchMVP): Int = m.team1Points.size

    /**
     * Constructs brackets for all divisions within the tournament.
     * Clears existing matches, seeds teams, and schedules new matches.
     */
    fun buildBrackets() {
        matchesMap.clear()
        for (division in tournament.divisions) {
            setupDivision(division)
            val teams = teamsMap.values.filter { it.division == division }.toMutableList()
            if (teams.size < 3) continue
            prepareTeams(teams)
            val root = createSubMatches(null, numberOfRounds, seedsWithByes.size, Side.RIGHT)
            val final = handleDoubleElimination(root)
            assignMatchNumbers(final)
        }
    }

    /**
     * Sets up the division context by selecting applicable fields.
     *
     * @param div The division for which to initialize scheduling context.
     */
    private fun setupDivision(div: Division) {
        currentDivision = div
        currentFieldIds = fieldsMap
            .filterValues { f -> f.divisions.contains(div) }
            .keys
            .toList()
    }

    /**
     * Sorts teams by seed, calculates byes, and separates seeds from remaining.
     *
     * @param list Mutable list of teams to be prepared for bracket.
     */
    private fun prepareTeams(list: MutableList<Team>) {
        list.sortByDescending { it.seed }
        val byes = calculateByes(list.size)
        seedsWithByes = list.take(byes).toMutableList()
        remainingTeams = list.drop(byes).toMutableList()
    }

    /**
     * Calculates how many byes are needed and total rounds in the bracket.
     *
     * @param n Number of teams.
     * @return Number of byes to assign.
     */
    private fun calculateByes(n: Int): Int {
        val (rem, pow) = remainderPowerOfTwo(n)
        numberOfRounds = pow + if (rem > 0) 1 else 0
        val maxPerfect = if (rem > (1.shl(pow) - 1)) rem % (1.shl(pow) - 1) else 0
        return if (rem > 0) rem - maxPerfect else 0
    }

    /**
     * Recursively creates bracket structure for winner bracket.
     *
     * @param prev Parent match or null if root.
     * @param round Number of rounds remaining.
     * @param byes Count of seeded byes.
     * @param side LEFT or RIGHT branch indicator.
     * @return Newly created MatchMVP node.
     */
    private fun createSubMatches(
        prev: MatchMVP?,
        round: Int,
        byes: Int,
        side: Side
    ): MatchMVP {
        val match = if ((byes > 0 && round <= 2) || (byes == 0 && round <= 1))
            createFinalMatch(prev, byes, side)
        else
            createIntermediate(prev, round, byes, side)
        if (tournament.doubleElimination) linkLoserMatch(match, side)
        return match
    }

    /**
     * Adds double-elimination bracket matches (semifinal and final).
     *
     * @param root The root winner-bracket match.
     * @return The final championship match.
     */
    private fun handleDoubleElimination(root: MatchMVP): MatchMVP {
        if (!tournament.doubleElimination) return root
        val final = createMatch(null, null, null, null, false, Side.RIGHT)
        val semi = createMatch(null, null, null, final, false, Side.RIGHT)
        val loserOfRoot = root.loserNextMatch
        semi.previousLeftMatch = root
        semi.previousRightMatch = loserOfRoot
        root.winnerNextMatch = semi
        loserOfRoot?.winnerNextMatch = semi
        final.previousLeftMatch = semi
        final.previousRightMatch = semi
        return final
    }

    /**
     * Performs BFS traversal to collect and order bracket matches,
     * schedules and renumbers them, and assigns referees.
     *
     * @param root The final match from which to start ordering.
     */
    private fun assignMatchNumbers(root: MatchMVP) {
        val queue = ArrayDeque<MatchMVP>().apply { add(root) }
        val ordered = mutableListOf<MatchMVP>()
        while (queue.isNotEmpty()) {
            val match = queue.removeFirst()
            ordered += match
            val prevs = listOfNotNull(match.previousLeftMatch, match.previousRightMatch)
            for (prev in prevs) {
                if (prev in ordered || prev in queue) continue
                if (match.losersBracket && !prev.losersBracket) continue
                queue += prev
                if (prev.losersBracket && (prev.previousLeftMatch!!.losersBracket xor prev.previousRightMatch!!.losersBracket)) {
                    val flip = if (prev.previousLeftMatch!!.losersBracket)
                        prev.previousLeftMatch!! else prev.previousRightMatch!!
                    queue += flip
                }
            }
        }
        var count = 1
        for (match in ordered.asReversed()) {
            match.matchNumber = count
            bracketScheduler.scheduleEvent(match, multiplier(match) * Times.SET)
            existingByNumber[count]?.let { old ->
                matchesMap.entries.find { it.value === match }?.let { entry ->
                    matchesMap.remove(entry.key)
                }
                match.id = old.id
                matchesMap[old.id] = match
            }
            count++
        }
        for (m in ordered) {
            if (m.team1 != null && m.team2 != null && m.refId == null) {
                bracketScheduler.freeParticipants(
                    Group(currentDivision.name), m.start, m.end
                ).firstOrNull()?.let { free -> m.refId = free.id }
            }
        }
    }

    /**
     * Creates and registers a new match.
     *
     * @param team1 First team or null for placeholder.
     * @param team2 Second team or null.
     * @param ref Referee team or null.
     * @param nextWin Next winner bracket match or null.
     * @param isLoser True if loser bracket match.
     * @param side LEFT or RIGHT branch.
     * @return The created MatchMVP instance.
     */
    private fun createMatch(
        team1: Team?,
        team2: Team?,
        ref: Team?,
        nextWin: MatchMVP?,
        isLoser: Boolean,
        side: Side
    ): MatchMVP {
        val mult = if (isLoser) tournament.loserSetCount else tournament.winnerSetCount
        val m = MatchMVP(
            matchNumber       = 0,
            team1             = team1?.id,
            team2             = team2?.id,
            tournamentId      = tournament.id,
            refId             = ref?.id,
            field             = null,
            start             = tournament.start,
            end               = tournament.end,
            division          = currentDivision,
            team1Points       = MutableList(mult) { 0 },
            team2Points       = MutableList(mult) { 0 },
            losersBracket     = isLoser,
            winnerNextMatch   = nextWin,
            loserNextMatch    = null,
            previousLeftMatch = null,
            previousRightMatch= null,
            setResults        = MutableList(mult) { 0 },
            refCheckedIn      = false,
            side              = side,
            id                = ID.unique()
        )
        matchesMap[m.id] = m
        return m
    }

    /**
     * Creates the first-round matches using bye distributions.
     *
     * @param prev Parent match or null if starting.
     * @param byes Number of byes this round.
     * @param side LEFT or RIGHT branch.
     * @return Created MatchMVP for this bracket node.
     */
    private fun createFinalMatch(
        prev: MatchMVP?,
        byes: Int,
        side: Side
    ): MatchMVP = when (byes) {
        1 -> {
            val high = seedsWithByes.removeAt(0)
            val new = createMatch(
                if (side == Side.LEFT) high else null,
                if (side == Side.RIGHT) high else null,
                null, prev, false, side
            )
            val low1 = remainingTeams.removeAt(0)
            val low2 = remainingTeams.removeAt(0)
            val low = createMatch(low1, low2, high, new, false, side.opposite())
            if (side == Side.LEFT) new.previousRightMatch = low else new.previousLeftMatch = low
            new
        }
        2 -> {
            val new = createMatch(null, null, null, prev, false, side)
            val h1 = seedsWithByes.removeAt(0)
            val h2 = seedsWithByes.removeAt(0)
            val m1 = createMatch(h1, remainingTeams.removeAt(0), null, new, false, side)
            val m2 = createMatch(h2, remainingTeams.removeAt(0), null, new, false, side.opposite())
            if (side == Side.LEFT) {
                new.previousLeftMatch  = m1
                new.previousRightMatch = m2
            } else {
                new.previousLeftMatch  = m2
                new.previousRightMatch = m1
            }
            new
        }
        else -> {
            val t1 = remainingTeams.removeAt(0)
            val t2 = remainingTeams.removeAt(
                if (-2 + 2 * seedsWithByes.size >= 0) -2 + 2 * seedsWithByes.size else remainingTeams.lastIndex
            )
            createMatch(t1, t2, null, prev, false, side)
        }
    }

    /**
     * Recursively creates intermediate-round matches.
     *
     * @param prev Parent match.
     * @param round Current round number.
     * @param byes Number of byes.
     * @param side LEFT or RIGHT branch.
     * @return Created MatchMVP for this node.
     */
    private fun createIntermediate(
        prev: MatchMVP?,
        round: Int,
        byes: Int,
        side: Side
    ): MatchMVP {
        val (leftByes, rightByes) = if (side == Side.LEFT)
            floor(byes / 2.0).toInt() to ceil(byes / 2.0).toInt()
        else
            ceil(byes / 2.0).toInt() to floor(byes / 2.0).toInt()
        val new = createMatch(null, null, null, prev, false, side)
        new.previousLeftMatch  = createSubMatches(new, round - 1, leftByes, Side.LEFT)
        new.previousRightMatch = createSubMatches(new, round - 1, rightByes, Side.RIGHT)
        return new
    }

    /**
     * Adds loser-bracket matches branching off the main bracket.
     *
     * @param newMatch The match from which to branch losers.
     * @param side The side (LEFT/RIGHT) of this branch.
     */
    private fun linkLoserMatch(newMatch: MatchMVP, side: Side) {
        val prevs = listOfNotNull(newMatch.previousLeftMatch, newMatch.previousRightMatch)
        when (prevs.size) {
            1 -> {
                val parent    = prevs[0]
                val loserNext = createMatch(null, null, null, null, true, side)
                if (side == Side.LEFT) loserNext.previousRightMatch = parent
                else loserNext.previousLeftMatch = parent
                parent.loserNextMatch = loserNext
                newMatch.loserNextMatch = loserNext
            }
            2 -> {
                val loserNext = createMatch(null, null, null, null, true, side)
                val loserPrev = createMatch(null, null, null, loserNext, true, side.opposite())
                if (side == Side.LEFT) {
                    loserNext.previousLeftMatch  = newMatch
                    loserNext.previousRightMatch = loserPrev
                } else {
                    loserNext.previousRightMatch = newMatch
                    loserNext.previousLeftMatch  = loserPrev
                }
                newMatch.loserNextMatch = loserNext
                val (lp, rp) = prevs
                if (lp.loserNextMatch != null)
                    connectMatches(loserPrev, lp.loserNextMatch!!, Side.LEFT, false)
                else
                    connectMatches(loserPrev, lp, Side.LEFT, true)
                if (rp.loserNextMatch != null)
                    connectMatches(loserPrev, rp.loserNextMatch!!, Side.RIGHT, false)
                else
                    connectMatches(loserPrev, rp, Side.RIGHT, true)
            }
        }
    }

    /**
     * Connects two matches in either winner or loser branch.
     *
     * @param nextMatch The child match to link.
     * @param prevMatch The parent match from which it branches.
     * @param side LEFT or RIGHT side placement.
     * @param loser True if linking loser branch, false for winner.
     */
    private fun connectMatches(
        nextMatch: MatchMVP,
        prevMatch: MatchMVP,
        side: Side,
        loser: Boolean
    ) {
        if (loser) prevMatch.loserNextMatch = nextMatch
        else prevMatch.winnerNextMatch = nextMatch
        if (side == Side.LEFT) nextMatch.previousLeftMatch = prevMatch
        else nextMatch.previousRightMatch = prevMatch
    }

    /**
     * Finds exponent x such that 2^x <= n < 2^(x+1).
     *
     * @param n Input integer.
     * @return Exponent x.
     */
    private fun findLargestPowerOfTwo(n: Int): Int {
        var x = 0
        while (n shr x != 0) x++
        return x - 1
    }

    /**
     * Splits integer into remainder and its largest power-of-two exponent.
     *
     * @param n Input integer.
     * @return Pair of (remainder, exponent).
     */
    private fun remainderPowerOfTwo(n: Int): Pair<Int, Int> {
        val p = findLargestPowerOfTwo(n)
        val lp = 1 shl p
        return (n - lp) to p
    }

    fun getTournament() = tournament
    fun getMatches() = matchesMap
    fun getTeams() = teamsMap
    fun getFields() = fieldsMap
}