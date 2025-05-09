package dataTypes

import dataTypes.enums.Division
import io.openruntimes.kotlin.src.Group
import io.openruntimes.kotlin.src.Resource
import io.openruntimes.kotlin.src.ScheduleEvent
import io.openruntimes.kotlin.src.dataTypes.dtos.FieldDTO
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


@Serializable
class Field(
    override val id: String,
    val divisions: List<Division>,
    var inUse: Boolean? = false,
    var matches: MutableList<MatchMVP> = mutableListOf(),
    val fieldNumber: Int,
    val tournamentId: String
) : MVPDocument, Resource {
    // Use a Set internally to prevent duplicate entries

    override fun getGroups(): List<Group> = divisions.map { Group(it.name) }
    override fun getEvents(): List<ScheduleEvent> = matches.toList()

    override fun addEvent(event: ScheduleEvent) {
        matches.add(event as MatchMVP)
    }

    override fun removeEvent(event: ScheduleEvent) {
        matches.remove(event)
    }

    fun toFieldDTO(): FieldDTO {
        return FieldDTO(
            inUse = inUse,
            fieldNumber = fieldNumber,
            divisions = divisions.map { it.name },
            matches = matches.map { it.id },
            tournamentId = tournamentId,
            id = id
        )
    }
}