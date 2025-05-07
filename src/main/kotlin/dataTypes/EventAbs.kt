package dataTypes

import dataTypes.enums.Division
import dataTypes.enums.EventType
import dataTypes.enums.FieldType
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
sealed interface EventAbs : MVPDocument {
    val location: String
    val name: String
    val description: String
    val divisions: List<Division>
    val lat: Double
    val long: Double
    val fieldType: FieldType
    val start: Instant
    val end: Instant
    val price: Double
    val rating: Float?
    val imageUrl: String
    val maxParticipants: Int
    val teamSizeLimit: Int
    val lastUpdated: Instant
    val hostId: String
    val eventType: EventType
    val teamSignup: Boolean
    val singleDivision: Boolean
    val waitList: List<String>
    val freeAgents: List<String>
    override val id: String
}