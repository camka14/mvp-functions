package io.openruntimes.kotlin.src.util

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

object DbConstants {
    const val DATABASE_NAME = "mvp"
    const val EVENT_COLLECTION = "pickupEvents"
    const val CHAT_GROUP_COLLECTION = "chatGroup"
    const val MESSAGES_COLLECTION = "messages"
    const val TOURNAMENT_COLLECTION = "tournaments"
    const val USER_DATA_COLLECTION = "userData"
    const val VOLLEYBALL_TEAMS_COLLECTION = "volleyballTeams"
    const val MATCHES_COLLECTION = "matches"
    const val FIELDS_COLLECTION = "fields"
    const val TOURNAMENT_ATTRIBUTE = "tournamentId"
    const val TOURNAMENTS_ATTRIBUTE = "tournamentIds"
    const val EVENTS_ATTRIBUTE = "eventIds"
    const val LAT_ATTRIBUTE = "lat"
    const val LONG_ATTRIBUTE = "long"
    const val ERROR_TAG = "Database"
    const val MATCHES_CHANNEL = "databases.$DATABASE_NAME.collections.$MATCHES_COLLECTION.documents"
    const val CHAT_GROUPS_CHANNEL = "databases.$DATABASE_NAME.collections.$CHAT_GROUP_COLLECTION.documents"
    const val MESSAGES_CHANNEL = "databases.$DATABASE_NAME.collections.$MESSAGES_COLLECTION.documents"
    const val USER_CHANNEL = "databases.$DATABASE_NAME.collections.$USER_DATA_COLLECTION.documents"
}

object Times {
    val REST = 5.minutes
    val SET = 20.minutes
}