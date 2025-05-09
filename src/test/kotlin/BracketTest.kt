import dataTypes.Field
import dataTypes.MatchMVP
import dataTypes.Team
import dataTypes.Tournament
import dataTypes.enums.Division
import dataTypes.enums.FieldType
import io.openruntimes.kotlin.src.*
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.days

class BracketTest {

    /**
     * Build a tournament+teams+fields fixture with [numFields] fields.
     */
    private fun makeFixture(
        numTeams: Int,
        doubleElim: Boolean,
        numFields: Int
    ): Triple<Tournament, Map<String, Team>, Map<String, Field>> {
        val div = Division.valueOf("OPEN")
        val start = Instant.parse("2025-01-01T00:00:00Z")
        val end   = start + 1.days

        // Base Tournament
        val base = Tournament()
        val tour = base.copy(
            doubleElimination            = doubleElim,
            winnerSetCount               = 3,
            loserSetCount                = 3,
            winnerBracketPointsToVictory = listOf(1,1,1),
            loserBracketPointsToVictory  = listOf(1,1,1),
            winnerScoreLimitsPerSet      = listOf(25,25,15),
            loserScoreLimitsPerSet       = listOf(25,25,15),
            id                   = "t1",
            name                 = "Test Tourney",
            divisions            = listOf(div),
            start                = start,
            end                  = end,
            teamSignup           = false,
            singleDivision       = true,
        )

        // Create teams
        val teams = (1..numTeams).map { idx ->
            Team(
                id            = "T$idx",
                division      = div,
                seed          = numTeams - idx + 1,
                tournamentIds = listOf(tour.id),
                eventIds      = emptyList(),
                wins          = 0,
                losses        = 0,
                name          = "Team$idx",
                captainId     = "cap$idx",
                players       = emptyList(),
                pending       = emptyList(),
                teamSize      = 2
            )
        }.associateBy { it.id }

        // Create 1..numFields fields
        val fields = (1..numFields).associate { i ->
            val fid = "F$i"
            fid to Field(
                id           = fid,
                divisions    = listOf(div),
                inUse        = false,
                fieldNumber  = i,
                matches      = mutableListOf(),
                tournamentId = tour.id
            )
        }

        return Triple(tour, teams, fields)
    }

    @ParameterizedTest(name = "{0} teams, doubleElim={1}")
    @CsvSource(
        // single‐elim
        "1,false", "2,false", "3,false", "4,false", "8,false", "16,false", "32,false",
        // double‐elim
        "1,true",  "2,true",  "3,true",  "4,true",  "8,true",  "16,true",  "32,true"
    )
    fun buildBrackets_allSizesAndModes(
        numTeams: Int,
        doubleElim: Boolean
    ) {
        for (numFields in 1..10) {
            // GIVEN
            val (tourney, teamsMap, fieldsMap) = makeFixture(numTeams, doubleElim, numFields)
            val matchesMap = mutableMapOf<String, MatchMVP>()

            // WHEN
            val bracket = Bracket(
                tournament = tourney,
                matchesMap = matchesMap,
                teamsMap   = teamsMap.toMutableMap(),
                fieldsMap  = fieldsMap.toMutableMap()
            )
            bracket.buildBrackets()
            val matches = bracket.getMatches().values

            // THEN: no matches for fewer than 3 teams
            if (numTeams < 4) {
                assertTrue(matches.isEmpty(),
                    "[$numFields fields] Expected no matches for $numTeams teams, got ${'$'}{matches.size}")
                continue
            }

            // total match count
            if (!doubleElim) {
                assertEquals(
                    numTeams - 1,
                    matches.size,
                    "[$numFields fields] Single-elim with $numTeams teams should have ${'$'}{numTeams - 1} matches"
                )
            } else {
                assertTrue(
                    matches.size > numTeams - 1,
                    "[$numFields fields] Double-elim with $numTeams teams should have >${'$'}{numTeams - 1} matches"
                )
            }

            // validate per-field scheduling: no overlaps & even distribution
            val counts = fieldsMap.values.map { field ->
                val evts = field.getEvents().sortedBy { it.start }
                for ((a, b) in evts.zipWithNext()) {
                    assertTrue(
                        a.end <= b.start,
                        "[$numFields fields] Overlap on ${'$'}{field.id}: ${'$'}{a.id}[${'$'}{a.start}–${'$'}{a.end}] vs ${'$'}{b.id}[${'$'}{b.start}–${'$'}{b.end}]"
                    )
                }
                evts.size
            }

            // all scheduled matches accounted for
            assertEquals(
                matches.size,
                counts.sum(),
                "[$numFields fields] Sum of per-field schedules (${counts.sum()}) != total matches (${matches.size})"
            )

            // even distribution: no field differs by more than 1 match
            val min = counts.minOrNull()!!
            val max = counts.maxOrNull()!!
            assertTrue(
                max - min <= 1,
                "[$numFields fields] Uneven distribution counts=$counts"
            )
        }
    }
}
