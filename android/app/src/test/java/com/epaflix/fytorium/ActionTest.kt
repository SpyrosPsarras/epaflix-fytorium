package com.epaflix.fytorium

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionTest {
    private val owner = Member("member-one", Role.owner)
    private val sitter = Member("member-one", Role.sitter)
    @Test fun futureTasksOfferDoneAndSkipButNeverSnooze() {
        val task = parseTaskList(taskListFixture("upcoming", due = "2030-06-04")).sections.single().tasks.single()
        assertEquals(listOf("complete", "skip"), task.actions("2030-06-02", owner))
        assertEquals(listOf("complete", "skip"), task.actions("2030-06-02", sitter))
    }
    @Test fun dueAndLateTasksOfferSnoozeUsingTheHouseholdDate() {
        for (due in listOf("2030-06-02", "2030-06-01")) {
            val task = parseTaskList(taskListFixture("today", due)).sections.single().tasks.single()
            assertEquals(listOf("complete", "snooze", "skip"), task.actions("2030-06-02", owner))
        }
    }
    @Test fun undoUsesOnlyTheServerFlagForEveryRole() {
        val task = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        for (role in Role.entries) for (allowed in listOf(false, true)) {
            val event = parseEvent(JSONObject(eventFixture).put("can_undo", allowed))
            assertEquals(if (allowed) listOf("undo") else emptyList<String>(),
                task.actions("2030-06-02", Member("unrelated-member", role), event))
        }
        assertFalse(task.copy(taskType = "repot").actions("2030-06-02", sitter).contains("repot"))
        assertTrue(task.copy(taskType = "repot").actions("2030-06-02", owner).contains("repot"))
    }
}
