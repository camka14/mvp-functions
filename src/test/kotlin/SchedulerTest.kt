import io.openruntimes.kotlin.src.Group
import io.openruntimes.kotlin.src.Scheduler
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Minimal interfaces to match your Scheduler expectations
interface Participant {
    val id: String;
    fun getGroups(): List<Group>;
    fun getEvents(): List<ScheduleEvent>;
}

interface Resource {
    val id: String
    fun getGroups(): List<Group>
    fun getEvents(): List<ScheduleEvent>
    fun addEvent(evt: ScheduleEvent)
}

interface ScheduleEvent {
    var start: Instant
    var end: Instant
    val buffer: Duration
    fun getDependencies(): List<ScheduleEvent>
    fun getGroup(): Group
    fun participantIds(): List<String>
    fun setResource(r: Resource)
}

class TestParticipant(override val id: String, private val group: Group) : Participant {
    private val evts = mutableListOf<ScheduleEvent>()
    override fun getGroups() = listOf(group)
    override fun getEvents() = evts
    fun add(evt: ScheduleEvent) {
        evts += evt
    }
}

class TestResource(override val id: String, private val groups: List<Group>) : Resource {
    private val evts = mutableListOf<ScheduleEvent>()
    override fun getGroups() = groups
    override fun getEvents() = evts
    override fun addEvent(evt: ScheduleEvent) {
        evts += evt
    }
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
    override fun getDependencies() = deps
    override fun getGroup() = group
    override fun participantIds() = parts.map { it.id }
    override fun setResource(r: Resource) {
        resource = r
        // register onto the resource
        r.addEvent(this)
        // register onto each participant
        parts.forEach { it.add(this) }
    }
}

class SchedulerTest {

    private val groupA = Group("A")
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    // helper to build a scheduler with 2 participants and 2 resources
    private fun makeScheduler(): Triple<Scheduler, List<TestParticipant>, List<TestResource>> {
        val p1 = TestParticipant("p1", groupA)
        val p2 = TestParticipant("p2", groupA)
        val r1 = TestResource("r1", listOf(groupA))
        val r2 = TestResource("r2", listOf(groupA))
        val sched = Scheduler(
            startTime = now,
            participants = mapOf(p1.id to p1, p2.id to p2),
            groups = listOf(groupA),
            currentTime = now,
            resources = mapOf(r1.id to r1, r2.id to r2)
        )
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
        // create an overlapping event for p1
        val e = TestEvent(
            start = now + 10.seconds,
            end = now + 50.seconds,
            buffer = 0.seconds,
            group = groupA,
            parts = listOf(parts[0])
        )
        // manually assign it onto r1 and p1
        e.setResource(resources[0])
        val free = sched.freeParticipants(groupA, now + 20.seconds, now + 30.seconds)
        assertEquals(setOf(parts[1]), free.toSet())
    }

    @Test
    fun testIsResourceAvailable_overlap_false() {
        val r = TestResource("r", listOf(groupA))
        val busy = TestEvent(
            start = now + 10.seconds,
            end = now + 20.seconds,
            buffer = 0.seconds,
            group = groupA,
            parts = emptyList()
        )
        r.addEvent(busy)
        // ask for [5,15) → overlap
        val impl = Scheduler(
            startTime = now,
            participants = emptyMap(),
            groups = listOf(groupA),
            currentTime = now,
            resources = mapOf(r.id to r)
        )
        val available = impl.run {
            // use reflection or call private via wrapper; but we can test via scheduleEvent fallback:
            isResourceAvailable(r, now + 5.seconds, now + 15.seconds)
        }
        assertFalse(available)
    }

    @Test
    fun testFindAvailableResource_prefersLeastLoaded() {
        val (sched, parts, resources) = makeScheduler()
        // put two events on r1
        repeat(2) {
            val e = TestEvent(
                start = now + (100 * it).seconds,
                end = now + (100 * it + 10).seconds,
                buffer = 0.seconds,
                group = groupA,
                parts = parts
            )
            e.setResource(resources[0])
        }
        // r2 is empty, so it should pick r2
        val chosen = sched.run {
            // expose via findAvailableResource
            findAvailableResource(now, 30.seconds)
        }
        assertEquals(resources[1], chosen)
    }

    @Test
    fun testScheduleEvent_schedulesAtStartTime() {
        val (sched, parts, resources) = makeScheduler()
        val event = TestEvent(
            start = now,
            end = now + 10.seconds,
            buffer = 5.minutes,
            group = groupA,
            parts = parts
        )
        sched.scheduleEvent(event, 30.seconds)
        // must have been placed at exactly `now`
        assertEquals(now, event.start)
        assertEquals(now + 30.seconds, event.end)
        // must be on one of the resources
        assertTrue(resources.any { it.getEvents().contains(event) })
    }

    @Test
    fun testScheduleEvent_afterDependency() {
        val (sched, parts, resources) = makeScheduler()
        // first event, duration 10
        val e1 = TestEvent(
            start = now,
            end = now + 10.seconds,
            buffer = 5.minutes,
            group = groupA,
            parts = parts
        )
        sched.scheduleEvent(e1, 10.seconds)
        // second event depends on e1
        val e2 = TestEvent(
            start = now,
            end = now + 5.seconds,
            buffer = 5.minutes,
            group = groupA,
            parts = parts,
            deps = listOf(e1)
        )
        sched.scheduleEvent(e2, 20.seconds)
        // earliest possible is e1.end + buffer = now+10+5 = now+15
        assertEquals(e1.end + (5 * 60).seconds, e2.start)
        assertEquals(e2.start + 20.seconds, e2.end)
    }
}