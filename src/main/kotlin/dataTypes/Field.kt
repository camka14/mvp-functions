package dataTypes

import dataTypes.enums.Division
import io.openruntimes.kotlin.src.Group
import io.openruntimes.kotlin.src.Resource
import io.openruntimes.kotlin.src.ScheduleEvent
import io.openruntimes.kotlin.src.dataTypes.dtos.FieldDTO
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


@Serializable
data class Field(
    val inUse: Boolean?,
    val fieldNumber: Int,
    val divisions: List<Division>,
    var matches: List<String>,
    val tournamentId: String,
    @Transient override val id: String = "",
) : MVPDocument, Resource {
    override fun getGroups(): List<Group> = divisions.map { Group(it.name) }
    override fun getEvents(): List<ScheduleEvent> = emptyList()
    override fun addEvent(event: ScheduleEvent) {
        matches = matches + event.id
    }

    override fun removeEvent(event: ScheduleEvent) {
        matches = matches.filterNot { it == event.id }
    }

    fun toFieldDTO(): FieldDTO {
        return FieldDTO(
            inUse = inUse,
            fieldNumber = fieldNumber,
            divisions = divisions.map { it.name },
            matches = matches,
            tournamentId = tournamentId,
            id = id
        )
    }
}