package io.openruntimes.kotlin.src

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class Group(
    val id: String
)

interface Participant {
    val id: String
    fun getGroup(): Group
    fun getEvents(): List<ScheduleEvent>
}

interface ScheduleEvent {
    val id: String
    var start: Instant
    var end: Instant
    val buffer: Duration
    fun getGroup(): Group
    fun getResource(): Resource?
    fun setResource(resource: Resource)
    fun participantIds(): List<String?>
    fun getDependencies(): List<ScheduleEvent>
    fun getDependants(): List<ScheduleEvent>
}

interface Resource {
    val id: String
    fun getGroups(): List<Group>
    fun getEvents(): List<ScheduleEvent>
    fun addEvent(event: ScheduleEvent)
    fun removeEvent(event: ScheduleEvent)
}

/**
 * Generic scheduler that assigns events to resources and participants across groups.
 *
 * @property startTime The earliest time scheduling may begin.
 * @property resources Map of resource ID to Resource instances available for scheduling.
 * @property participants Map of participant ID to Participant instances to allocate.
 * @property groups List of all group identifiers that partition resources and participants.
 * @property currentTime The reference time for determining availability; defaults to now.
 */
class Scheduler(
    private val startTime: Instant,
    private val participants: Map<String, Participant>,
    private val groups: List<Group>,
    private val currentTime: Instant = Clock.System.now(),
    private val resources: Map<String, Resource>,
) {
    private val resourcesByGroup: MutableMap<Group, MutableList<Resource>> = mutableMapOf()
    private val participantsByGroup: MutableMap<Group, MutableList<Participant>> = mutableMapOf()
    private var currentGroups: List<Group> = emptyList()

    init {
        for (group in groups) {
            resourcesByGroup[group] = resources.values.filter { it.getGroups().contains(group) }.toMutableList()
            participantsByGroup[group] = participants.values.filter { it.getGroup() == group }.toMutableList()
        }
    }

    /**
     * Finds scheduling conflicts where a participant is double-booked.
     *
     * @return Map of Participant to list of overlapping ScheduleEvent instances.
     */
    fun getParticipantConflicts(): Map<Participant, List<ScheduleEvent>> {
        val conflicts = mutableMapOf<Participant, MutableList<ScheduleEvent>>()
        for (group in currentGroups) {
            val events = resourcesByGroup[group]?.flatMap { resource -> resource.getEvents() } ?: continue
            for (participant in participantsByGroup[group]!!) {
                for (evt in events) {
                    val overlapping = currentEvents(evt.start, evt.end).filter { it != evt }
                    for (ce in overlapping) {
                        conflicts.computeIfAbsent(participant) { mutableListOf() }
                        if (ce !in conflicts[participant]!!) conflicts[participant]!!.add(ce)
                    }
                }
            }
        }
        return conflicts
    }

    /**
     * Retrieves participants free during a specified time window in a group.
     *
     * @param group The group whose participants to consider.
     * @param start The desired event start time.
     * @param end The desired event end time.
     * @return List of Participant IDs available for the entire interval.
     */
    fun freeParticipants(group: Group, start: Instant, end: Instant): List<Participant> {
        var free = participantsByGroup[group]!!.toList()
        for (res in resourcesByGroup[group]!!) {
            for (evt in res.getEvents()) {
                if (!(evt.start >= end && evt.end > end) && !(evt.start < start && evt.end <= start)) {
                    free = free.filter { participant ->
                        participant.id !in evt.participantIds()
                    }
                }
            }
        }
        return free
    }

    /**
     * Attempts to schedule an event on an available resource, delaying in 5-minute increments.
     *
     * @param event The ScheduleEvent to allocate.
     * @param duration The duration of the event.
     */
    fun scheduleEvent(event: ScheduleEvent, duration: Duration) {
        currentGroups = listOf(event.getGroup())
        var earliest = getEarliestStartTime(event)
        while (true) {
            if (checkParticipants(earliest, earliest.plus(duration), event.participantIds().size)) {
                val res = findAvailableResource(earliest, duration)
                if (res != null) {
                    event.setResource(res)
                    event.start = earliest
                    event.end = earliest.plus(duration)
                    res.addEvent(event)
                    return
                }
            }
            earliest = earliest.plus(duration = 5.minutes)
        }
    }

    /**
     * Determines the earliest start time for an event considering dependencies.
     *
     * @param event The event whose dependencies set minimum start.
     * @return The earliest valid Instant the event can begin.
     */
    private fun getEarliestStartTime(event: ScheduleEvent): Instant {
        var earliest = if (startTime > currentTime) startTime else currentTime
        for (dep in event.getDependencies()) {
            val endBuf = dep.end.plus(dep.buffer)
            if (endBuf > earliest) earliest = endBuf
        }
        return earliest
    }

    /**
     * Checks participant availability in a time window.
     *
     * @param start Desired start Instant.
     * @param end Desired end Instant.
     * @param minCount Minimum participants needed.
     * @return True if enough participants free; false otherwise.
     */
    private fun checkParticipants(start: Instant, end: Instant, minCount: Int): Boolean {
        val active = currentEvents(start, end)
        val total = currentGroups.sumOf { participantsByGroup[it]!!.size }
        val occupied = active.sumOf { it.participantIds().size }
        return (total - occupied) >= minCount
    }

    /**
     * Finds an available resource for the given time window.
     *
     * @param start Desired start Instant.
     * @param duration The Duration of the event.
     * @return A Resource if available; null if none free.
     */
    private fun findAvailableResource(start: Instant, duration: Duration): Resource? {
        val pool = currentGroups.flatMap { resourcesByGroup[it]!! }.toMutableList()
        pool.sortBy { it.getEvents().size }
        val end = start.plus(duration)
        for (r in pool) if (isResourceAvailable(r, start, end)) return r
        return null
    }

    /**
     * Checks if a resource is free for the entire interval.
     *
     * @param resource The Resource to check.
     * @param start Desired start Instant.
     * @param end Desired end Instant.
     * @return True if resource has no conflicting events.
     */
    private fun isResourceAvailable(resource: Resource, start: Instant, end: Instant): Boolean {
        return resource.getEvents().none { evt ->
            !(evt.start >= end && evt.end > end) && !(evt.start < start && evt.end <= start)
        }
    }

    /**
     * Collects events running in the given time window across current groups.
     *
     * @param start Desired start Instant.
     * @param end Desired end Instant.
     * @return List of ScheduleEvent overlapping the interval.
     */
    private fun currentEvents(start: Instant, end: Instant): List<ScheduleEvent> {
        val evts = mutableListOf<ScheduleEvent>()
        for (g in currentGroups) {
            for (res in resourcesByGroup[g]!!) {
                for (evt in res.getEvents()) {
                    if (!(evt.start >= end && evt.end > end) && !(evt.start < start && evt.end <= start)) {
                        evts.add(evt)
                    }
                }
            }
        }
        return evts
    }
}

