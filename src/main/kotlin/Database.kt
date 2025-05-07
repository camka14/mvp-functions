package io.openruntimes.kotlin.src

import dataTypes.*
import dataTypes.dtos.*
import io.appwrite.Client
import io.appwrite.Query
import io.appwrite.services.Databases
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
        )
        docs.documents.map { doc -> doc.data.toMatch(doc.id) }.associateBy { it.id }
    }

    suspend fun getTeamsForTournament(tournamentId: String): Result<Map<String, Team>> = runCatching {
        val docs = databases.listDocuments(
            DbConstants.DATABASE_NAME,
            DbConstants.MATCHES_COLLECTION,
            queries = listOf(Query.contains(DbConstants.TOURNAMENTS_ATTRIBUTE, tournamentId)),
            nestedType = TeamDTO::class.java
        )
        docs.documents.map { doc -> doc.data.toTeam(doc.id) }.associateBy { it.id }
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

    suspend fun updateFields(matches: List<Field>): Result<Unit> = runCatching {
        matches.forEach {
            databases.updateDocument(
                DbConstants.DATABASE_NAME,
                DbConstants.MATCHES_COLLECTION,
                it.id,
                it,
                nestedType = Field::class.java,
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