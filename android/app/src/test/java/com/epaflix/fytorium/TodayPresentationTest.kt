package com.epaflix.fytorium

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayPresentationTest {
    @Test fun aHeldListKeepsItsCardsRowOrderNamesAndHeadingsAndTakesEachRowsNewState() {
        val list = parseTaskList(taskListFixture("today"))
        val row = list.sections.single().tasks.single()
        val done = row.copy(completedEvent = parseEvent(org.json.JSONObject(eventFixture)))
        val next = row.copy(dueOn = "2030-06-02", postponed = "skipped")
        val gone = row.copy(plantId = "gone")
        val shown = list.copy(sections = listOf(TaskSection("water", "Water", listOf(done, next, gone)), TaskSection("repot", "Repot", listOf(row.copy(taskType = "repot")))))
        val live = list.copy(sections = listOf(TaskSection("fertilize", "Fertilize", listOf(row.copy(taskType = "fertilize"))),
            TaskSection("water", "Watering", listOf(row.copy(plantId = "new"), done.copy(plantName = "Renamed", lateDays = 0),
                next.copy(plantName = "Renamed", siteName = "Greenhouse", lateDays = 4)))))
        val held = live.keepingLayoutOf(shown)
        assertEquals(listOf("water" to "Water", "repot" to "Repot"), held.sections.map { it.key to it.label })
        assertEquals(listOf(done.copy(lateDays = 0), next.copy(lateDays = 4), gone), held.sections.first().tasks)
        assertEquals(shown.sections.last(), held.sections.last())
        assertSame(live, live.keepingLayoutOf(null))
        assertSame("A new task view applies at once", live, live.keepingLayoutOf(shown.copy(settings = TaskSettings(sort = "plant"))))
        assertSame("Yesterday's rows never stand in for a new day's", live, live.keepingLayoutOf(shown.copy(today = "2030-06-01")))
    }
    @Test fun aHeldRowFollowsOnlyThisDevicesTransitionsThroughEveryStepBetweenTwoRedraws() {
        val list = parseTaskList(taskListFixture("today"))
        val pending = list.sections.single().tasks.single()
        val event = parseEvent(org.json.JSONObject(eventFixture))
        val done = pending.copy(completedEvent = event)
        val again = pending.copy(dueOn = "2030-05-31", completedEvent = event.copy(id = "event-two"))
        fun live(row: CareTask) = list.copy(sections = listOf(TaskSection("water", "Water", listOf(row))))
        fun held(row: CareTask, shown: CareTask, transitions: Map<String, String>) = live(row).keepingLayoutOf(live(shown), transitions).sections.single().tasks.single()
        val restored = pending.copy(dueOn = "2030-05-31")
        assertEquals("Another occurrence of the task never takes the row", done, held(restored, done, emptyMap()))
        assertEquals("Undo, then Done again, under the name shown", again, held(again.copy(plantName = "Renamed"), done, mapOf(event.id to restored.occurrence, restored.occurrence to again.occurrence)))
        assertEquals("A loop of Done and Undo ends", done, held(restored, done, mapOf(event.id to pending.occurrence, pending.occurrence to event.id)))
        // Undoing an earlier completion leaves the next occurrence pending, which keeps its own row.
        val next = pending.copy(dueOn = "2030-06-08")
        val shown = list.copy(sections = listOf(TaskSection("water", "Water", listOf(done, next))))
        assertEquals(listOf(done, next), list.copy(sections = listOf(TaskSection("water", "Water", listOf(next)))).keepingLayoutOf(shown, mapOf(event.id to next.occurrence))
            .sections.single().tasks)
    }
    @Test fun cardsKeepServerRowsIncludingHandledTasksAndOmitEmptyGroups() {
        val row = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        val event = parseEvent(org.json.JSONObject(eventFixture))
        val list = TaskList("2030-06-02", "today", TaskSettings(), emptyMap(), listOf(
            TaskSection("water", "Water", listOf(row, row.copy(plantId = "two"), row.copy(plantId = "three"), row.copy(completedEvent = event))),
            TaskSection("fertilize", "Fertilize", listOf(row.copy(taskType = "fertilize"))),
            TaskSection("remove_dead", "Remove dead flowers and leaves", listOf(row.copy(taskType = "remove_dead"), row.copy(taskType = "remove_dead", plantId = "two"))),
            TaskSection("clean_leaves", "Clean leaves", emptyList())))
        val shown = CareState(today = list).todayPresentation()
        assertEquals(listOf(4, 1, 2), shown.sections.map { it.tasks.size })
        assertEquals(event, shown.sections.first().tasks.last().completedEvent)
        assertEquals(listOf("water", "fertilize", "remove_dead"), shown.sections.map { it.key })
        assertTrue(CareState(today = list.copy(sections = emptyList())).todayPresentation().sections.isEmpty())
    }

    @Test fun plantAndSiteSortsKeepTheServersPlantAndSiteCards() {
        val row = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        val spider = listOf(row.copy(plantId = "spider", plantName = "Spider Plant"), row.copy(plantId = "spider", plantName = "Spider Plant", taskType = "fertilize"))
        val cheese = row.copy(plantId = "cheese", plantName = "Swiss Cheese Plant")
        val groups = mapOf("water" to listOf(spider.first(), cheese), "fertilize" to listOf(spider.last()), "remove_dead" to emptyList())
        val byPlant = TaskList("2030-06-02", "today", TaskSettings(sort = "plant"), groups,
            listOf(TaskSection("spider", "Spider Plant", spider), TaskSection("cheese", "Swiss Cheese Plant", listOf(cheese)), TaskSection("empty", "Empty", emptyList())))
        assertEquals(byPlant.sections.take(2), CareState(today = byPlant).todayPresentation().sections)
        val bySite = byPlant.copy(settings = TaskSettings(sort = "site"), sections = listOf(TaskSection("Living room", "Living room", spider + cheese)))
        assertEquals(bySite.sections, CareState(today = bySite).todayPresentation().sections)
    }
    @Test fun backgroundReadsAndAWritingRowDoNotDisableOtherTaskActions() {
        val state = CareState(member = Member("one", Role.owner), busy = true)
        assertTrue(state.writable)
        assertTrue(state.copy(pending = setOf(BusyKey.Row("plant-one:water:2030-06-01"))).writable)
        assertFalse(state.copy(offline = true).writable)
        assertFalse(state.copy(serverUnavailable = true).writable)
        assertFalse(state.copy(updateRequired = true).writable)
    }
    @Test fun aBannerForTasksFromAnEarlierDayNamesTheDay() {
        val updated = java.time.LocalDateTime.of(2030, 6, 2, 8, 12).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val state = CareState(lastUpdated = updated)
        assertEquals("Offline. Showing tasks from Sun 2 Jun 08:12. Changes need a connection.", state.copy(offline = true).todayBanner())
        assertEquals("Fytorium isn't responding. Showing tasks from Sun 2 Jun 08:12. Pull down to try again.", state.copy(serverUnavailable = true).todayBanner())
        assertNull(state.todayBanner())
    }
    @Test fun emptyDayUsesNextServerTaskAndNoPlantsHasItsOwnState() {
        val empty = parseTaskList(taskListFixture("today")).copy(sections = emptyList())
        val plant = parsePlant(org.json.JSONObject(plantFixture))
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04"))
        val state = CareState(member = Member("one", Role.owner), today = empty, upcoming = upcoming, plants = listOf(plant))
        assertEquals("Nothing to do today", state.todayPresentation().emptyTitle)
        assertEquals("Next: Water Synthetic fern · Tuesday 4 Jun", state.todayPresentation().nextTask)
        assertEquals("Add your first plant", state.copy(plants = emptyList()).todayPresentation().emptyTitle)
        assertEquals("Nothing is shared with you yet", state.copy(plants = emptyList(), member = Member("s", Role.sitter)).todayPresentation().emptyTitle)
        assertEquals("Ask the household owner to share plants.", state.copy(plants = emptyList(), member = Member("s", Role.sitter)).todayPresentation().emptyHint)
        assertNull(state.copy(plants = emptyList()).todayPresentation().emptyHint)
        assertEquals("No outdoor tasks today", state.copy(today = empty.copy(settings = TaskSettings(filter = "outdoor"))).todayPresentation().emptyTitle)
        assertEquals("Loading tasks…", CareState(busy = true).todayPresentation().emptyTitle)
        assertEquals("Connect to load your tasks", CareState(offline = true).todayPresentation().emptyTitle)
        assertEquals("Connect to load your tasks", CareState(serverUnavailable = true).todayPresentation().emptyTitle)
        assertEquals("Before the cache loads, an online start is loading", "Loading tasks…", CareState().todayPresentation().emptyTitle)
        assertEquals("Loading tasks…", CareState(member = Member("one", Role.owner)).todayPresentation().emptyTitle)
        assertNull(state.copy(upcoming = empty).todayPresentation().nextTask)
    }
    @Test fun nextTaskSkipsHandledRowsAndUsesTheEarliestServerDate() {
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04"))
        val task = upcoming.sections.single().tasks.single()
        val event = parseEvent(org.json.JSONObject(eventFixture))
        val rows = listOf(task.copy(plantId = "later", dueOn = "2030-06-06"), task.copy(completedEvent = event),
            task.copy(plantId = "next", plantName = "Synthetic palm", dueOn = "2030-06-05"), task.copy(plantId = "no-date", dueOn = null))
        val state = CareState(upcoming = upcoming.copy(sections = listOf(TaskSection("2030-06-04", "2030-06-04", rows))))
        assertEquals("Next: Water Synthetic palm · Wednesday 5 Jun", state.todayPresentation().nextTask)
        val done = state.upcoming!!.copy(sections = listOf(TaskSection("2030-06-04", "2030-06-04", rows.map { if (it.plantId == "next") it.copy(completedEvent = event) else it })))
        assertEquals("Next: Water Synthetic fern · Thursday 6 Jun", state.copy(upcoming = done).todayPresentation().nextTask)
    }
}
