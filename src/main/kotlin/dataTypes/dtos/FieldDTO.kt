package io.openruntimes.kotlin.src.dataTypes.dtos

import dataTypes.Field
import dataTypes.enums.Division
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


@Serializable
data class FieldDTO(
    val inUse: Boolean?,
    val fieldNumber: Int,
    val divisions: List<String>,
    var matches: List<String>,
    val tournamentId: String,
    @Transient
    val id: String = "",
) {
    fun toField(id: String): Field {
        return Field(
            inUse = inUse,
            fieldNumber = fieldNumber,
            divisions = divisions.map { Division.valueOf(it) },
            matches = matches,
            tournamentId = tournamentId,
            id = id
        )
    }
}