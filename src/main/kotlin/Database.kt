package io.openruntimes.kotlin.src

import dataTypes.*
import dataTypes.dtos.*
import io.appwrite.Client
import io.appwrite.Query
import io.appwrite.services.Databases
import io.openruntimes.kotlin.src.dataTypes.dtos.FieldDTO
import io.openruntimes.kotlin.src.util.DbConstants

class Database {
    private val client = Client()
        .setEndpoint("https://cloud.appwrite.io/v1")
        .setProject("6656a4d60016b753f942")
        .setKey(System.getenv("APPWRITE_API_KEY"))

    private val databases = Databases(client)

    suspend fun getTournament(tournamentId: String): Result<Tournament> = runCatching {
        val doc = databases.getDocument(
            DbConstants.DATABASE_NAME,
            DbConstants.TOURNAMENT_COLLECTION,
            tournamentId,
            nestedType = TournamentDTO::class.java
        )
        doc.data.toTournament(doc.id)
    }

    suspend fun getMatchesForTournament(tournamentId: String): Result<Map<String, MatchMVP>> = runCatching {
        val docs = databases.listDocuments(
            DbConstants.DATABASE_NAME,
            DbConstants.MATCHES_COLLECTION,
            queries = listOf(Query.equal(DbConstants.TOURNAMENT_ATTRIBUTE, tournamentId)),
            nestedType = MatchDTO::class.java
        ).documents.map{ it.data }.associateBy { it.id }
        val matches = docs.map { (id, doc) -> doc.toMatch(id) }.associateBy { it.id }
        matches.map { (id, matchMVP) ->
            matchMVP.winnerNextMatch = matches[docs[id]!!.winnerNextMatchId]
            matchMVP.loserNextMatch = matches[docs[id]!!.loserNextMatchId]
            matchMVP.previousLeftMatch = matches[docs[id]!!.previousLeftId]
            matchMVP.previousRightMatch = matches[docs[id]!!.previousRightId]
            id to matchMVP
        }.toMap()
    }

    suspend fun getTeamsForTournament(tournamentId: String): Result<Map<String, Team>> = runCatching {
        val docs = databases.listDocuments(
            DbConstants.DATABASE_NAME,
            DbConstants.VOLLEYBALL_TEAMS_COLLECTION,
            queries = listOf(Query.contains(DbConstants.TOURNAMENTS_ATTRIBUTE, tournamentId)),
            nestedType = TeamDTO::class.java
        )
        docs.documents.map { doc -> doc.data.toTeam(doc.id) }.associateBy { it.id }
    }

    suspend fun getFieldsOfTournament(tournamentId: String, matchesById: Map<String, MatchMVP>): Result<Map<String, Field>> = runCatching {
        databases.listDocuments(
            DbConstants.DATABASE_NAME,
            DbConstants.MATCHES_COLLECTION,
            listOf(Query.contains(DbConstants.TOURNAMENT_ATTRIBUTE, tournamentId)),
            nestedType = FieldDTO::class.java,
        ).documents.map { doc -> doc.data.toField(doc.id, matchesById) }.associateBy { it.id }
    }

    suspend fun updateTournament(tournament: Tournament): Result<Unit> = runCatching {
        databases.updateDocument(
            DbConstants.DATABASE_NAME,
            DbConstants.TOURNAMENT_COLLECTION,
            tournament.id,
            tournament.toTournamentDTO(),
            nestedType = TournamentDTO::class.java
        )
    }

    suspend fun updateFields(fields: List<Field>): Result<Unit> = runCatching {
        fields.forEach {
            databases.updateDocument(
                DbConstants.DATABASE_NAME,
                DbConstants.MATCHES_COLLECTION,
                it.id,
                it.toFieldDTO(),
                nestedType = FieldDTO::class.java,
            )
        }
    }

    suspend fun updateMatches(matches: List<MatchMVP>): Result<Unit> = runCatching {
        matches.forEach {
            databases.updateDocument(
                DbConstants.DATABASE_NAME,
                DbConstants.MATCHES_COLLECTION,
                it.id,
                it.toMatchDTO(),
                nestedType = MatchDTO::class.java,
            )
        }
    }

    suspend fun createMatches(matches: List<MatchMVP>): Result<Unit>  = runCatching {
        matches.forEach {
            databases.createDocument(
                DbConstants.DATABASE_NAME,
                DbConstants.MATCHES_COLLECTION,
                it.id,
                it.toMatchDTO(),
                nestedType = MatchDTO::class.java,
            )
        }
    }
}