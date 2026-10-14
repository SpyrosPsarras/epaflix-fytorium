package com.epaflix.fytorium

import java.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayPresentationTest {
    @Test fun summaryCountsServerRowsAndOmitsEmptyGroupsAndCompletedRows() {
        val row = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        val event = parseEvent(org.json.JSONObject(eventFixture))
        val list = TaskList("2030-06-02", "today", TaskSettings(), emptyMap(), listOf(
            TaskSection("water", "Water", listOf(row, row.copy(plantId = "two"), row.copy(plantId = "three"), row.copy(completedEvent = event))),
            TaskSection("fertilize", "Fertilize", listOf(row.copy(taskType = "fertilize"))),
            TaskSection("mist", "Mist", listOf(row.copy(taskType = "mist"), row.copy(taskType = "mist", plantId = "two"))),
            TaskSection("clean_leaves", "Clean leaves", emptyList())))
        val shown = CareState(today = list).todayPresentation()
        assertEquals("Today's tasks: 3 watering, 1 fertilizing + 2 more", shown.summary)
        assertEquals(listOf("water", "fertilize", "mist"), shown.sections.map { it.key })
        assertEquals("Nothing to do today", CareState(today = list.copy(sections = emptyList())).todayPresentation().summary)
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
    @Test fun tipIsStableForTheHouseholdDayAndRotatesAtItsMidnight() {
        val oslo = CareState(timezone = "Europe/Oslo")
        val early = Instant.parse("2030-06-01T22:00:00Z")
        val late = Instant.parse("2030-06-02T21:59:59Z")
        val midnight = Instant.parse("2030-06-02T22:00:00Z")
        assertEquals(oslo.todayPresentation(early).tip, oslo.todayPresentation(late).tip)
        assertNotEquals(oslo.todayPresentation(late).tip, oslo.todayPresentation(midnight).tip)
        val la = oslo.copy(timezone = "America/Los_Angeles")
        assertNotEquals(oslo.todayPresentation(early).tip, la.todayPresentation(early).tip)
        assertEquals(oslo.todayPresentation(early).tip, oslo.todayPresentation(early.plusSeconds(5 * 86_400)).tip)
        assertEquals(CareState(timezone = "UTC").todayPresentation(early).tip, CareState().todayPresentation(early).tip)
    }
    @Test fun greetingUsesHouseholdTimeRatherThanPhoneTime() {
        val state = CareState(timezone = "Europe/Oslo")
        assertEquals("Good morning", state.todayPresentation(Instant.parse("2030-06-02T03:00:00Z")).greeting)
        assertEquals("Good afternoon", state.todayPresentation(Instant.parse("2030-06-02T10:00:00Z")).greeting)
        assertEquals("Good evening", state.todayPresentation(Instant.parse("2030-06-02T16:00:00Z")).greeting)
        assertEquals("Good evening", state.copy(timezone = "America/Los_Angeles").todayPresentation(Instant.parse("2030-06-02T03:00:00Z")).greeting)
    }
}
