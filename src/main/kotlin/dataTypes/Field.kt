package dataTypes

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


@Serializable
data class Field(
    val inUse: Boolean?,
    val fieldNumber: Int,
    val divisions: List<String>,
    var matches: List<String>,
    val tournamentId: String,
    @Transient
    override val id: String = "",
) : MVPDocument