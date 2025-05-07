package dataTypes.dtos

import dataTypes.enums.Division
import dataTypes.enums.FieldType
import dataTypes.EventImp
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class EventDTO(
    @Transient
    val id: String = "",
    val location: String,
    val name: String,
    val description: String,
    val divisions: List<String>,
    val fieldType: String,
    val start: String,
    val end: String,
    val price: Double,
    val rating: Float?,
    val imageUrl: String,
    val lat: Double,
    val long: Double,
    val hostId: String,
    val teamSizeLimit: Int,
    val maxParticipants: Int,
    val teamSignup: Boolean,
    val singleDivision: Boolean,
    val waitList: List<String>,
    val freeAgents: List<String>,
)

fun EventDTO.toEvent(id: String): EventImp {
    return EventImp(
        id = id,
        location = location,
        name = name,
        description = description,
        divisions = divisions.map { Division.valueOf(it)},
        fieldType = FieldType.valueOf(fieldType),
        start = Instant.parse(start),
        end = Instant.parse(end),
        price = price,
        rating = rating,
        imageUrl = imageUrl,
        lat = lat,
        long = long,
        lastUpdated = Clock.System.now(),
        hostId = hostId,
        teamSizeLimit = teamSizeLimit,
        maxParticipants = maxParticipants,
        teamSignup = teamSignup,
        singleDivision = singleDivision,
        waitList = waitList,
        freeAgents = freeAgents,
    )
}