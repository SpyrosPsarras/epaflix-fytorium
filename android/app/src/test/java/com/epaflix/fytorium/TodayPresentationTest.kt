package com.epaflix.fytorium

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayPresentationTest {
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

    @Test fun siteSortRetainsServerRowOrderInsideCareTypeCards() {
        val row = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        val water = listOf(row.copy(plantId = "two"), row.copy(plantId = "one"))
        val list = TaskList("2030-06-02", "today", TaskSettings(sort = "site"),
            mapOf("fertilize" to listOf(row.copy(taskType = "fertilize")), "remove_dead" to emptyList(), "water" to water),
            listOf(TaskSection("Living room", "Living room", water)))
        val sections = CareState(today = list).todayPresentation().sections
        assertEquals(listOf("Water", "Fertilize"), sections.map { it.label })
        assertEquals(listOf("two", "one"), sections.first().tasks.map { it.plantId })
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
