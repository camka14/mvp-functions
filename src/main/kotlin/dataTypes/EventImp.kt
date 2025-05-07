package dataTypes

import dataTypes.dtos.EventDTO
import dataTypes.enums.Division
import dataTypes.enums.EventType
import dataTypes.enums.FieldType
import io.appwrite.ID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class EventImp(
    override val hostId: String,
    override val id: String,
    override val location: String,
    override val name: String,
    override val description: String,
    override val divisions: List<Division>,
    override val lat: Double,
    override val long: Double,
    override val fieldType: FieldType,
    override val start: Instant,
    override val end: Instant,
    override val price: Double,
    override val rating: Float?,
    override val imageUrl: String,
    override val teamSizeLimit: Int,
    override val maxParticipants: Int,
    override val teamSignup: Boolean,
    override val singleDivision: Boolean,
    override val waitList: List<String>,
    override val freeAgents: List<String>,
    @Transient override val eventType: EventType = EventType.EVENT,
    @Transient override val lastUpdated: Instant = Clock.System.now(),
): EventAbs {
    companion object {
        operator fun invoke(): EventImp {
            return EventImp(
                hostId = "",
                id = ID.unique(),
                location = "",
                name = "",
                description = "",
                divisions = listOf(),
                lat = 0.0,
                long = 0.0,
                fieldType = FieldType.GRASS,
                start = Clock.System.now(),
                end = Clock.System.now(),
                price = 0.0,
                rating = 0f,
                imageUrl = "",
                teamSizeLimit = 0,
                maxParticipants = 0,
                singleDivision = false,
                teamSignup = false,
                waitList = listOf(),
                freeAgents = listOf()
            )
        }
    }

    fun toEventDTO(): EventDTO {
        return EventDTO(
            id = id,
            location = location,
            name = name,
            description = description,
            divisions = divisions.map { it.name },
            fieldType = fieldType.name,
            start = start.toString(),
            end = end.toString(),
            price = price,
            rating = rating,
            imageUrl = imageUrl,
            lat = lat,
            long = long,
            hostId = hostId,
            teamSizeLimit = teamSizeLimit,
            maxParticipants = maxParticipants,
            singleDivision = singleDivision,
            teamSignup = teamSignup,
            waitList = waitList,
            freeAgents = freeAgents
        )
    }
}