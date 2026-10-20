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
            TaskSection("mist", "Mist", listOf(row.copy(taskType = "mist"), row.copy(taskType = "mist", plantId = "two"))),
            TaskSection("clean_leaves", "Clean leaves", emptyList())))
        val shown = CareState(today = list).todayPresentation()
        assertEquals(listOf(4, 1, 2), shown.sections.map { it.tasks.size })
        assertEquals(event, shown.sections.first().tasks.last().completedEvent)
        assertEquals(listOf("water", "fertilize", "mist"), shown.sections.map { it.key })
        assertTrue(CareState(today = list.copy(sections = emptyList())).todayPresentation().sections.isEmpty())
    }

    @Test fun siteSortRetainsServerRowOrderInsideCareTypeCards() {
        val row = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        val water = listOf(row.copy(plantId = "two"), row.copy(plantId = "one"))
        val list = TaskList("2030-06-02", "today", TaskSettings(sort = "site"),
            mapOf("fertilize" to listOf(row.copy(taskType = "fertilize")), "mist" to emptyList(), "water" to water),
            listOf(TaskSection("Living room", "Living room", water)))
        val sections = CareState(today = list).todayPresentation().sections
        assertEquals(listOf("Water", "Fertilize"), sections.map { it.label })
        assertEquals(listOf("two", "one"), sections.first().tasks.map { it.plantId })
    }
    @Test fun backgroundReadsDoNotDisableTaskActionsButAWritingRequestDoes() {
        val state = CareState(member = Member("one", Role.owner), busy = true)
        assertTrue(state.writable)
        assertFalse(state.copy(saving = true).writable)
        assertFalse(state.copy(offline = true).writable)
        assertFalse(state.copy(serverUnavailable = true).writable)
        assertFalse(state.copy(updateRequired = true).writable)
    }
    @Test fun emptyDayUsesNextServerTaskAndNoPlantsHasItsOwnState() {
        val empty = parseTaskList(taskListFixture("today")).copy(sections = emptyList())
        val plant = parsePlant(org.json.JSONObject(plantFixture))
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04"))
        val state = CareState(member = Member("one", Role.owner), today = empty, upcoming = upcoming, plants = listOf(plant))
        assertEquals("Nothing to do today", state.todayPresentation().emptyTitle)
        assertEquals("Next: Water Synthetic fern · Tuesday, 4 June", state.todayPresentation().nextTask)
        assertEquals("Add your first plant", state.copy(plants = emptyList()).todayPresentation().emptyTitle)
        assertEquals("No shared plants yet", state.copy(plants = emptyList(), member = Member("s", Role.sitter)).todayPresentation().emptyTitle)
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
        assertEquals("Next: Water Synthetic palm · Wednesday, 5 June", state.todayPresentation().nextTask)
        assertEquals("Next: Water Synthetic fern · Thursday, 6 June",
            state.copy(handled = mapOf("next:water" to event)).todayPresentation().nextTask)
    }
    @Test fun presentationKeepsServerDueDatesRegardlessOfPhoneTimezone() {
        val state = CareState(today = parseTaskList(taskListFixture("today")), upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04")))
        val shown = state.copy(timezone = "Europe/Oslo").todayPresentation()
        assertEquals("2030-06-01", shown.sections.single().tasks.single().dueOn)
        assertEquals("Next: Water Synthetic fern · Tuesday, 4 June", shown.nextTask)
        assertEquals(shown, state.copy(timezone = "America/Los_Angeles").todayPresentation())
    }
}
