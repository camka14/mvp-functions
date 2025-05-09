import io.appwrite.ID
import io.openruntimes.kotlin.src.*
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds



class TestParticipant(override val id: String, private val group: Group) : Participant {
    private val evts = mutableListOf<ScheduleEvent>()
    override fun getGroup(): Group {
        return group
    }
    override fun getEvents(): List<ScheduleEvent> = evts
    fun add(evt: ScheduleEvent) { evts += evt }
}

class TestResource(override val id: String, private val groups: List<Group>) : Resource {
    private val evts = mutableListOf<ScheduleEvent>()
    override fun getGroups(): List<Group> = groups
    override fun getEvents(): List<ScheduleEvent> = evts
    override fun addEvent(event: ScheduleEvent) { evts += event }
    override fun removeEvent(event: ScheduleEvent) { evts -= event }
}


class TestEvent(
    override var start: Instant,
    override var end: Instant,
    override val buffer: Duration,
    private val group: Group,
    private val parts: List<TestParticipant>,
    private val deps: List<ScheduleEvent> = emptyList()
) : ScheduleEvent {
    private var resource: Resource? = null
    private val dependantsList = mutableListOf<ScheduleEvent>()
    override val id: String = java.util.UUID.randomUUID().toString()

    init {
        // Register self as dependant on each dependency
        deps.forEach { dep ->
            if (dep is TestEvent) dep.dependantsList += this
        }
    }

    override fun getDependencies(): List<ScheduleEvent> = deps
    override fun getDependants(): List<ScheduleEvent> = dependantsList

    override fun getGroup(): Group = group
    override fun getResource(): Resource? = resource
    override fun participantIds(): List<String> = parts.map { it.id }

    override fun setResource(r: Resource) {
        resource = r
        r.addEvent(this)
        parts.forEach { it.add(this) }
    }
}

class SchedulerTest {
    private val groupA = Group("A")
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    private fun makeScheduler(): Triple<Scheduler, List<TestParticipant>, List<TestResource>> {
        val p1 = TestParticipant("p1", groupA)
        val p2 = TestParticipant("p2", groupA)
        val r1 = TestResource("r1", listOf(groupA))
        val r2 = TestResource("r2", listOf(groupA))
        val sched = Scheduler(
            startTime    = now,
            participants = mapOf(p1.id to p1, p2.id to p2),
            groups       = listOf(groupA),
            currentTime  = now,
            resources    = mapOf(r1.id to r1, r2.id to r2)
        )
        // **ensure currentGroup is set** before any method that uses it
        sched.selectGroup(groupA)
        return Triple(sched, listOf(p1, p2), listOf(r1, r2))
    }

    @Test
    fun testFreeParticipants_noEvents_allFree() {
        val (sched, parts, _) = makeScheduler()
        val free = sched.freeParticipants(groupA, now, now + 60.seconds)
        assertEquals(parts.toSet(), free.toSet())
    }

    @Test
    fun testFreeParticipants_oneEvent_excludesBusyParticipant() {
        val (sched, parts, resources) = makeScheduler()
        val e = TestEvent(
            start  = now + 10.seconds,
            end    = now + 50.seconds,
            buffer = Duration.ZERO,
            group  = groupA,
            parts  = listOf(parts[0])
        )
        e.setResource(resources[0])
        val free = sched.freeParticipants(groupA, now + 20.seconds, now + 30.seconds)
        assertEquals(setOf(parts[1]), free.toSet())
    }

    @Test
    fun testIsResourceAvailable_overlap_false() {
        val r = TestResource("r", listOf(groupA))
        val busy = TestEvent(
            start  = now + 10.seconds,
            end    = now + 20.seconds,
            buffer = Duration.ZERO,
            group  = groupA,
            parts  = emptyList()
        )
        r.addEvent(busy)
        val sched = Scheduler(
            startTime    = now,
            participants = emptyMap(),
            groups       = listOf(groupA),
            currentTime  = now,
            resources    = mapOf(r.id to r)
        ).apply { selectGroup(groupA) }
        val available = sched.run { isResourceAvailable(r, now + 5.seconds, now + 15.seconds) }
        assertFalse(available)
    }

    @Test
    fun testFindAvailableResource_prefersLeastLoaded() {
        val (sched, parts, resources) = makeScheduler()
        repeat(2) { i ->
            val e = TestEvent(
                start  = now + (100 * i).seconds,
                end    = now + (100 * i + 10).seconds,
                buffer = Duration.ZERO,
                group  = groupA,
                parts  = parts
            )
            e.setResource(resources[0])
        }
        val chosen = sched.run { findAvailableResource(now, 30.seconds) }
        assertEquals(resources[1], chosen)
    }

    @Test
    fun testScheduleEvent_schedulesAtStartTime() {
        val (sched, parts, resources) = makeScheduler()
        val event = TestEvent(
            start  = now,
            end    = now + 10.seconds,
            buffer = 5.minutes,
            group  = groupA,
            parts  = parts
        )
        sched.scheduleEvent(event, 30.seconds)
        assertEquals(now, event.start)
        assertEquals(now + 30.seconds, event.end)
        assertTrue(resources.any { it.getEvents().contains(event) })
    }

    @Test
    fun testScheduleEvent_afterDependency_andDependants() {
        val (sched, parts, resources) = makeScheduler()
        val e1 = TestEvent(
            start  = now,
            end    = now + 10.seconds,
            buffer = 5.minutes,
            group  = groupA,
            parts  = parts
        )
        sched.scheduleEvent(e1, 10.seconds)

        val e2 = TestEvent(
            start  = now,
            end    = now + 5.seconds,
            buffer = 5.minutes,
            group  = groupA,
            parts  = parts,
            deps   = listOf(e1)
        )
        sched.scheduleEvent(e2, 20.seconds)

        // Should schedule e2 after dependency + buffer
        assertEquals(e1.end + 5.minutes, e2.start)
        assertEquals(e2.start + 20.seconds, e2.end)

        // And e1.getDependants() should include e2
        assertEquals(listOf(e2), e1.getDependants())
    }
}
