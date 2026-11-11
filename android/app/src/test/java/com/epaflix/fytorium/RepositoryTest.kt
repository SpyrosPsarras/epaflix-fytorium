package com.epaflix.fytorium

import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import com.epaflix.fytorium.plants.currentTask
import kotlinx.coroutines.launch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    @Test fun typeBatchRetriesReuseRequestIdAndKeepEveryRowsUndo() {
        withRepository { repository, server, cache ->
            val events = listOf("plant-one", "plant-two").mapIndexed { index, plant ->
                org.json.JSONObject(eventFixture).put("id", "batch-event-$index").put("plant_id", plant)
                    .put("task_type", "clean_leaves").put("batch_id", "batch-one").put("batch_plant_count", 2).put("batch_event_count", 2)
                    .put("batch_action", "complete-type").put("batch_task", "clean_leaves")
            }
            fun list(view: String, completed: Boolean = false): String {
                val json = org.json.JSONObject(taskListFixture(view).replace("water", "clean_leaves"))
                val rows = json.getJSONArray("sections").getJSONObject(0).getJSONArray("tasks")
                rows.put(org.json.JSONObject(rows.getJSONObject(0).toString()).put("plant_id", "plant-two"))
                if (completed) events.forEachIndexed { index, event -> rows.getJSONObject(index).put("completed_event", event) }
                json.getJSONObject("groups").put("clean_leaves", rows)
                return json.toString()
            }
            fun refreshResponses() {
                server.enqueue(MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"}}"""))
                server.enqueue(MockResponse().setBody(list("today")))
                server.enqueue(MockResponse().setBody(list("upcoming")))
                server.enqueue(MockResponse().setBody("[]"))
                server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Oslo"}"""))
            }
            refreshResponses(); repository.refresh()
            repeat(5) { server.takeRequest() }
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(repository.handleType("clean_leaves"))
            val failed = server.takeRequest()
            refreshResponses(); repository.refresh()
            repeat(5) { server.takeRequest() }
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "batch-one").put("events", org.json.JSONArray(events)).toString()))
            server.enqueue(MockResponse().setBody(list("today", completed = true)))
            server.enqueue(MockResponse().setBody(list("upcoming", completed = true)))
            assertTrue(repository.handleType("clean_leaves"))
            val success = server.takeRequest()
            assertEquals("POST", success.method)
            assertEquals("/api/tasks/clean_leaves/complete-all", success.path)
            val requestId = org.json.JSONObject(success.body.readUtf8()).getString("request_id")
            java.util.UUID.fromString(requestId)
            assertEquals(requestId, org.json.JSONObject(failed.body.readUtf8()).getString("request_id"))
            assertEquals("/api/tasks?view=today", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/tasks?view=upcoming", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals(listOf("batch-event-0", "batch-event-1"), repository.state.value.today!!.sections.single().tasks.map { it.completedEvent?.id })
            val receipt = repository.state.value.receipt!!
            assertEquals("Cleaned leaves of 2 plants", receipt.text)
            assertEquals("batch-one", receipt.undo!!.batchId)
            for (row in repository.state.value.today!!.sections.single().tasks) {
                assertEquals(listOf("undo"), row.actions("2030-06-02", Member("member-one", Role.owner)))
            }
            assertTrue(cache.get("member-one", "today")!!.json.contains("batch-event-1"))
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "batch-two").put("events", org.json.JSONArray()).toString()))
            server.enqueue(MockResponse().setBody(list("today", completed = true)))
            server.enqueue(MockResponse().setBody(list("upcoming", completed = true)))
            assertTrue(repository.handleType("clean_leaves"))
            val nextId = org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("request_id")
            java.util.UUID.fromString(nextId)
            assertNotEquals(requestId, nextId)
            repeat(2) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals("No tasks were due", repository.state.value.receipt!!.text)
            assertNull(repository.state.value.receipt!!.undo)
            val row = repository.state.value.today!!.sections.single().tasks.first()
            val requestsBefore = server.requestCount
            assertTrue(repository.act(row, "undo"))
            assertEquals(requestsBefore, server.requestCount)
            val batchReceipt = repository.state.value.receipt!!
            assertEquals("Cleaned leaves of 2 plants", batchReceipt.text)
            assertEquals("batch-one", batchReceipt.undo!!.batchId)
            server.enqueue(MockResponse().setBody(undoResponse(""""batchId":"batch-one","undoneAt":"2030-06-02T10:00:00Z"""") { list(it) }))
            assertTrue(repository.undoReceipt(batchReceipt))
            assertEquals("/api/task-batches/batch-one/undo", server.takeRequest().path)
            // The undo response carries the lists, so no refresh follows it.
            assertNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Undid leaf cleaning for 2 plants", repository.state.value.receipt!!.text)
            assertTrue(repository.state.value.today!!.sections.single().tasks.all { it.completedEvent == null })
        }
    }
    @Test fun typeBatchSupportsCustomTaskKeysAndRejectsSitterAndOfflineWrites() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, sitter = true); repository.refresh()
            assertFalse(repository.handleType("clean_leaves"))
            assertEquals(5, server.requestCount)
            enqueueRefresh(server); repository.refresh()
            repeat(10) { server.takeRequest() }
            repository.connectivityChanged(false)
            assertFalse(repository.handleType("clean_leaves"))
            assertEquals(10, server.requestCount)
            enqueueRefresh(server); repository.refresh()
            repeat(5) { server.takeRequest() }
            val customKey = "91000000-0000-4000-8000-000000000001"
            val event = org.json.JSONObject(eventFixture).put("task_type", org.json.JSONObject.NULL).put("custom_task_id", customKey).put("batch_id", "custom-batch")
                .put("custom_task_name", "Synthetic misting").put("batch_plant_count", 1).put("batch_event_count", 1)
                .put("batch_action", "complete-type").put("batch_task", customKey)
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "custom-batch").put("events", org.json.JSONArray().put(event)).toString()))
            server.enqueue(MockResponse().setBody(taskListFixture("today")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
            assertTrue(repository.handleType(customKey))
            assertEquals("/api/tasks/$customKey/complete-all", server.takeRequest().path)
            assertEquals(customKey, repository.state.value.receipt!!.undo!!.customTaskId)
            assertEquals("Synthetic misting done for 1 plant", repository.state.value.receipt!!.text)
            repeat(2) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
    }
    @Test fun customTaskReceiptsNameTheTask() {
        assertEquals("Synthetic misting done for Synthetic fern", doneText(null, "Synthetic misting", "Synthetic fern"))
        assertEquals("Synthetic misting done for 9 plants", doneText(null, "Synthetic misting", "9 plants", watered = true))
        withRepository { repository, server, _ ->
            val customKey = "91000000-0000-4000-8000-000000000001"
            enqueueRefresh(server, plants = "[$plantFixture]"); repository.refresh()
            repeat(5) { server.takeRequest() }
            val task = repository.state.value.today!!.sections.single().tasks.single()
                .copy(taskType = null, customTaskId = customKey, customTaskName = "Synthetic misting")
            val event = org.json.JSONObject(eventFixture).put("task_type", org.json.JSONObject.NULL).put("custom_task_id", customKey)
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("event", event).put("linked_events", org.json.JSONArray()).put("task", org.json.JSONObject(nextWater)).toString()))
            assertTrue(repository.act(task, "complete"))
            assertEquals("Synthetic misting done for Synthetic fern", repository.state.value.receipt!!.text)
        }
    }
    @Test fun fertilizeCardBatchWithLinkedWaterSaysFertilizedAndWatered() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, today = fertilizeAndWaterToday(), plants = "[$plantFixture]"); repository.refresh()
            repeat(5) { server.takeRequest() }
            fun event(id: String, plant: String, type: String, linked: String? = null) = org.json.JSONObject(eventFixture).put("id", id).put("plant_id", plant)
                .put("task_type", type).put("batch_id", "batch-fertilize").put("batch_plant_count", 2).put("batch_event_count", 4)
                .put("batch_action", "complete-type").put("batch_task", "fertilize").put("linked_event_id", linked ?: org.json.JSONObject.NULL)
            val events = listOf(event("fert-one", "plant-one", "fertilize"), event("fert-two", "plant-two", "fertilize"),
                event("water-one", "plant-one", "water", "fert-one"), event("water-two", "plant-two", "water", "fert-two"))
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "batch-fertilize").put("events", org.json.JSONArray(events)).toString()))
            server.enqueue(MockResponse().setBody(listed(fertilizeAndWaterToday(), *events.toTypedArray())))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
            assertTrue(repository.handleType("fertilize"))
            assertEquals("Fertilized and watered 2 plants", repository.state.value.receipt!!.text)
            assertEquals(BatchReceipt("Fertilized and watered 2 plants", "2 plants", 2, "Undid fertilizing and watering for 2 plants"), repository.state.value.batches.getValue("batch-fertilize"))
            repeat(3) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            server.enqueue(MockResponse().setBody(undoResponse(""""batchId":"batch-fertilize"""") { if (it == "today") fertilizeAndWaterToday() else taskListFixture(it) }))
            assertTrue(repository.undoReceipt(repository.state.value.receipt!!))
            assertEquals("Undid fertilizing and watering for 2 plants", repository.state.value.receipt!!.text)
        }
    }
    @Test fun aWritePatchesOnlyTheActedRowUntilTheRefreshListsTheLinkedWater() {
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        withRepository { repository, server, _ -> ServedLists(server, today = fertilizeAndWaterToday(plants = listOf("plant-one")), plants = "[$plantFixture]",
            hold = { hold.get() && it.requestUrl!!.encodedPath == "/api/tasks" }).use { served ->
            repository.refresh()
            fun rows() = repository.state.value.today!!.sections.associate { it.key to it.tasks.map { task -> task.completedEvent?.id } }
            val event = org.json.JSONObject(eventFixture).put("task_type", "fertilize")
            val water = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-one").put("can_undo", false)
            served.responses["/api/plants/plant-one/tasks/fertilize/complete"] = org.json.JSONObject().put("event", event)
                .put("linked_events", org.json.JSONArray().put(water)).put("task", org.json.JSONObject(nextWater)).toString()
            served.today = listed(fertilizeAndWaterToday(plants = listOf("plant-one")), event, water)
            hold.set(true)
            assertTrue(repository.act(repository.state.value.today!!.sections.first { it.key == "fertilize" }.tasks.single(), "complete"))
            assertEquals("Fertilized and watered Synthetic fern", repository.state.value.receipt!!.text)
            assertEquals(mapOf("fertilize" to listOf("event-one"), "water" to listOf(null)), rows())
            served.release()
            served.await { rows() == mapOf("fertilize" to listOf("event-one"), "water" to listOf("event-water")) }
        } }
    }
    @Test fun parsedEventsKeepTheBatchActionTaskAndLinkedEvent() {
        val event = parseEvent(org.json.JSONObject(eventFixture).put("batch_id", "batch-one").put("batch_action", "complete-type")
            .put("batch_task", "fertilize").put("linked_event_id", "event-zero"))
        assertEquals(listOf("complete-type", "fertilize", "event-zero"), listOf(event.batchAction, event.batchTask, event.linkedEventId))
        val older = parseEvent(org.json.JSONObject(eventFixture))
        assertEquals(listOf(null, null, null), listOf(older.batchAction, older.batchTask, older.linkedEventId))
    }
    @Test fun fertilizeDoneReloadsWaterScheduleWithoutManualRefresh() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            repeat(5) { server.takeRequest() }
            fun detail(due: String) {
                server.enqueue(MockResponse().setBody(plantFixture))
                server.enqueue(MockResponse().setBody("[]"))
                server.enqueue(MockResponse().setBody("""{"water":{"status":"due","dueOn":"$due","intervalDays":7,"lateDays":0,"basedOn":[]},"customTasks":{}}"""))
            }
            detail("2030-06-01"); repository.openPlant("plant-one")
            repeat(3) { server.takeRequest() }
            val task = repository.state.value.today!!.sections.single().tasks.single().copy(taskType = "fertilize")
            val event = org.json.JSONObject(eventFixture).put("task_type", "fertilize")
            val water = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-one")
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("event", event).put("linked_events", org.json.JSONArray().put(water))
                .put("task", org.json.JSONObject().put("status", "due").put("dueOn", "2030-06-16").put("intervalDays", 14).put("lateDays", 0).put("basedOn", org.json.JSONArray())).toString()))
            detail("2030-06-09")
            server.enqueue(MockResponse().setBody(taskListFixture("today", "2030-06-09", event = ""","completed_event":$water""")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming", "2030-06-09", event = ""","completed_event":$water""")))
            assertTrue(repository.act(task, "complete"))
            assertEquals("2030-06-09", repository.state.value.detail!!.tasks["water"]!!.dueOn)
            assertEquals("/api/plants/plant-one/tasks/fertilize/complete", server.takeRequest().path)
            assertEquals("/api/plants/plant-one", server.takeRequest().path)
            assertEquals("/api/plants/plant-one/history", server.takeRequest().path)
            assertEquals("/api/plants/plant-one/tasks", server.takeRequest().path)
            assertEquals("/api/tasks?view=today", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/tasks?view=upcoming", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) {
                    while (repository.state.value.upcoming!!.sections.none { it.key == "2030-06-09" }) kotlinx.coroutines.delay(10)
                }
            }
            // The server lists the linked water completion as its own row.
            assertEquals("event-water", repository.state.value.today!!.sections.single().tasks.single().completedEvent!!.id)
            assertEquals("event-water", repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single().completedEvent!!.id)
            assertTrue(cache.get("member-one", "upcoming")!!.json.contains("2030-06-09"))
            assertEquals("Fertilized and watered Synthetic fern", repository.state.value.receipt!!.text)
        }
    }
    @Test fun fertilizeReceiptMentionsWaterOnlyWhenTheServerLinkedIt() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, plants = "[$plantFixture]"); repository.refresh()
            repeat(5) { server.takeRequest() }
            val task = repository.state.value.today!!.sections.single().tasks.single().copy(taskType = "fertilize")
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("event", org.json.JSONObject(eventFixture).put("task_type", "fertilize"))
                .put("linked_events", org.json.JSONArray()).put("task", org.json.JSONObject(nextWater)).toString()))
            server.enqueue(MockResponse().setBody(taskListFixture("today")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
            assertTrue(repository.act(task, "complete"))
            assertEquals("Fertilized Synthetic fern", repository.state.value.receipt!!.text)
            assertEquals("/api/plants/plant-one/tasks/fertilize/complete", server.takeRequest().path)
            repeat(2) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
    }
    @Test fun batchReceiptCountsTheServerBatchNotTheVisibleRows() {
        val event = parseEvent(org.json.JSONObject(eventFixture).put("batch_id", "batch-one"))
        val card = event.copy(batchAction = "complete-type", batchTask = "water")
        assertNull(batchReceipt(card, "Synthetic fern"))
        assertNull(batchReceipt(event.copy(batchPlantCount = 9, batchEventCount = 9), "Synthetic fern"))
        assertEquals(BatchReceipt("Watered 9 plants", "9 plants", 9, "Undid watering for 9 plants"), batchReceipt(card.copy(batchPlantCount = 9, batchEventCount = 9), "Synthetic fern"))
        assertEquals(BatchReceipt("Watered 1 plant", "1 plant", 1, "Undid watering for 1 plant"), batchReceipt(card.copy(batchPlantCount = 1, batchEventCount = 1), "Synthetic fern"))
        // The water row of a fertilize card batch still names the fertilizing.
        assertEquals(BatchReceipt("Fertilized and watered 9 plants", "9 plants", 9, "Undid fertilizing and watering for 9 plants"),
            batchReceipt(card.copy(batchTask = "fertilize", linkedEventId = "event-zero", batchPlantCount = 9, batchEventCount = 18), "Synthetic fern"))
        assertEquals(BatchReceipt("Fertilized 9 plants", "9 plants", 9, "Undid fertilizing for 9 plants"), batchReceipt(card.copy(batchTask = "fertilize", batchPlantCount = 9, batchEventCount = 9), "Synthetic fern"))
        assertEquals(BatchReceipt("Synthetic misting done for 2 plants", "2 plants", 2, "Undid Synthetic misting for 2 plants"),
            batchReceipt(card.copy(batchTask = "custom-one", batchPlantCount = 2, batchEventCount = 2), "Synthetic fern", "Synthetic misting"))
        val plant = event.copy(batchAction = "complete-all")
        assertEquals(BatchReceipt("Handled 3 tasks for Synthetic fern", "3 tasks", 3, "Undid 3 tasks for Synthetic fern"), batchReceipt(plant.copy(batchPlantCount = 1, batchEventCount = 3), "Synthetic fern"))
        assertEquals(BatchReceipt("Handled 1 task for Synthetic fern", "1 task", 1, "Undid 1 task for Synthetic fern"), batchReceipt(plant.copy(batchPlantCount = 1, batchEventCount = 1), "Synthetic fern"))
        assertEquals(BatchReceipt("Watered 3 outdoor plants", "3 outdoor plants", 3, "Undid watering for 3 outdoor plants"),
            batchReceipt(event.copy(batchAction = "rain", batchPlantCount = 3, batchEventCount = 3), "Synthetic fern"))
    }
    @Test fun recoveredBatchReceiptKeepsTheServerScopeWhenRowsAreFiltered() {
        withRepository { repository, server, _ ->
            val batch = org.json.JSONObject(eventFixture).put("batch_id", "batch-one").put("batch_plant_count", 9).put("batch_event_count", 9)
                .put("batch_action", "complete-type").put("batch_task", "water")
            val list = org.json.JSONObject(taskListFixture("today"))
            val rows = list.getJSONArray("sections").getJSONObject(0).getJSONArray("tasks")
            rows.getJSONObject(0).put("completed_event", batch)
            rows.put(org.json.JSONObject(rows.getJSONObject(0).toString()).put("plant_id", "plant-two").put("plant_name", "Second fern")
                .put("completed_event", org.json.JSONObject(batch.toString()).put("id", "event-two").put("plant_id", "plant-two")))
            enqueueRefresh(server, today = list.toString()); repository.refresh()
            assertEquals(BatchReceipt("Watered 9 plants", "9 plants", 9, "Undid watering for 9 plants"), repository.state.value.batches.getValue("batch-one"))
            val requests = server.requestCount
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.first(), "undo"))
            assertEquals(requests, server.requestCount)
            assertEquals("Watered 9 plants", repository.state.value.receipt!!.text)
            assertEquals("batch-one", repository.state.value.receipt!!.undo!!.batchId)
        }
    }
    @Test fun recoveredReceiptsUseTheBatchActionWhicheverRowSuppliesThem() {
        withRepository { repository, server, _ ->
            val list = org.json.JSONObject(taskListFixture("today"))
            val rows = list.getJSONArray("sections").getJSONObject(0).getJSONArray("tasks")
            // Only the linked water rows of a fertilize card batch and a rain batch are visible.
            rows.getJSONObject(0).put("completed_event", org.json.JSONObject(eventFixture).put("id", "water-one").put("linked_event_id", "fert-one")
                .put("batch_id", "batch-fertilize").put("batch_plant_count", 2).put("batch_event_count", 4)
                .put("batch_action", "complete-type").put("batch_task", "fertilize"))
            rows.put(org.json.JSONObject(rows.getJSONObject(0).toString()).put("plant_id", "plant-two").put("plant_name", "Second fern")
                .put("completed_event", org.json.JSONObject(eventFixture).put("id", "rain-two").put("plant_id", "plant-two")
                    .put("batch_id", "batch-rain").put("batch_plant_count", 3).put("batch_event_count", 3)
                    .put("batch_action", "rain").put("batch_task", org.json.JSONObject.NULL)))
            enqueueRefresh(server, today = list.toString()); repository.refresh()
            assertEquals("Fertilized and watered 2 plants", repository.state.value.batches.getValue("batch-fertilize").text)
            assertEquals("Watered 3 outdoor plants", repository.state.value.batches.getValue("batch-rain").text)
            val visible = repository.state.value.today!!.sections.single().tasks
            assertTrue(repository.act(visible.last(), "undo"))
            assertEquals("Watered 3 outdoor plants", repository.state.value.receipt!!.text)
            server.enqueue(MockResponse().setBody(undoResponse(""""batchId":"batch-rain"""") { taskListFixture(it) }))
            assertTrue(repository.undoReceipt(repository.state.value.receipt!!))
            assertEquals("Undid watering for 3 outdoor plants", repository.state.value.receipt!!.text)
        }
    }
    @Test fun plantBatchReceiptNamesThePlantAndReportsEmptyAndFailedBatches() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, plants = "[$plantFixture]"); repository.refresh()
            repeat(5) { server.takeRequest() }
            fun batch(plant: String, size: Int) = org.json.JSONObject().put("batch_id", "batch-$plant").put("tasks", org.json.JSONObject())
                .put("events", org.json.JSONArray((0 until size).map { org.json.JSONObject(eventFixture).put("id", "event-$plant-$it").put("plant_id", plant)
                    .put("task_type", listOf("water", "fertilize", "clean_leaves")[it]).put("batch_id", "batch-$plant")
                    .put("batch_plant_count", 1).put("batch_event_count", size).put("batch_action", "complete-all").put("batch_task", org.json.JSONObject.NULL) })).toString()
            server.enqueue(MockResponse().setBody(batch("plant-one", 3)))
            assertTrue(repository.handleAll("plant-one", plantsScreen = true))
            assertEquals("/api/plants/plant-one/tasks/complete-all", server.takeRequest().path)
            assertEquals("Handled 3 tasks for Synthetic fern", repository.state.value.receipt!!.text)
            assertEquals("batch-plant-one", repository.state.value.receipt!!.undo!!.batchId)
            assertEquals(BatchReceipt("Handled 3 tasks for Synthetic fern", "3 tasks", 3, "Undid 3 tasks for Synthetic fern"), repository.state.value.batches.getValue("batch-plant-one"))
            val handled = repository.state.value.receipt!!
            server.enqueue(MockResponse().setBody(batch("plant-unknown", 1)))
            assertTrue(repository.handleAll("plant-unknown", plantsScreen = true))
            server.takeRequest()
            assertEquals("Handled 1 task for this plant", repository.state.value.receipt!!.text)
            server.enqueue(MockResponse().setBody(org.json.JSONObject(plantFixture).put("id", "plant-two").put("display_name", "Detail fern").toString()))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"customTasks":{}}"""))
            repository.openPlant("plant-two")
            repeat(3) { server.takeRequest() }
            server.enqueue(MockResponse().setBody(batch("plant-two", 2)))
            assertTrue(repository.handleAll("plant-two", plantsScreen = true))
            server.takeRequest()
            assertEquals("Handled 2 tasks for Detail fern", repository.state.value.receipt!!.text)
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "batch-empty").put("tasks", org.json.JSONObject()).put("events", org.json.JSONArray()).toString()))
            assertTrue(repository.handleAll("plant-one", plantsScreen = true))
            server.takeRequest()
            assertEquals("No tasks were due", repository.state.value.receipt!!.text)
            assertNull(repository.state.value.receipt!!.undo)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Graveyard plants have no tasks. Restore the plant first."}"""))
            assertFalse(repository.handleAll("plant-one", plantsScreen = true))
            server.takeRequest()
            assertEquals("Graveyard plants have no tasks. Restore the plant first.", repository.state.value.receipt!!.text)
            assertNull(repository.state.value.receipt!!.undo)
            assertNull(repository.plantState.value.errors["_form"])
            server.enqueue(MockResponse().setBody(undoResponse(""""batchId":"batch-plant-one"""") { taskListFixture(it) }))
            assertTrue(repository.undoReceipt(handled))
            assertEquals("/api/task-batches/batch-plant-one/undo", server.takeRequest().path)
            assertEquals("Undid 3 tasks for Synthetic fern", repository.state.value.receipt!!.text)
        }
    }
    @Test fun plantPageCareWriteLeavesThePlantScreenUsableAndQueuesTheNextTap() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, plants = "[$plantFixture]"); repository.refresh()
            val handleAll = "/api/plants/plant-one/tasks/complete-all"
            HeldServer(server, held = { it.requestUrl!!.encodedPath == handleAll }) { request ->
                when (request.requestUrl!!.encodedPath) {
                    handleAll -> MockResponse().setBody("""{"batch_id":"batch-empty","tasks":{},"events":[]}""")
                    "/api/care-events/event-one/undo" -> MockResponse().setBody(undoResponse(""""event":$eventFixture,"task":$nextWater""") { taskListFixture(it) })
                    "/api/tasks" -> MockResponse().setBody(taskListFixture(request.requestUrl!!.queryParameter("view")!!))
                    "/api/plants" -> MockResponse().setBody("[$plantFixture]")
                    else -> MockResponse().setResponseCode(404)
                }
            }.use { held ->
                val all = held.submit { repository.handleAll("plant-one", plantsScreen = true) }
                held.await { held.paths.isNotEmpty() }
                assertFalse(repository.plantState.value.saving)
                assertTrue(repository.plantState.value.enabled(repository.state.value.member, false))
                assertEquals(setOf(BusyKey.HandleAll("plant-one")), repository.state.value.pending)
                val undo = held.submit { repository.undoHistory(parseEvent(org.json.JSONObject(eventFixture))) }
                held.await { repository.state.value.pending == setOf(BusyKey.HandleAll("plant-one"), BusyKey.History("event-one")) }
                assertEquals(1, held.paths.size)
                held.release()
                assertTrue(all.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(undo.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(listOf("/api/care-events/event-one/undo", "/api/plants"), held.paths.drop(1))
            }
        }
    }
    @Test fun upcomingCompletionShowsTheServerRowAfterRefreshAndRestart() {
        withRepository { repository, server, cache -> ServedLists(server).use { served ->
            repository.refresh()
            served.responses["/api/plants/plant-one/tasks/water/complete"] = """{"event":$eventFixture,"linked_events":[],"task":$nextWater}"""
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.single().tasks.single(), "complete"))
            assertEquals("event-one", repository.state.value.upcoming!!.sections.single().tasks.single().completedEvent!!.id)
            // The server lists your completion at the due date of the occurrence it completed, whatever Show others' completed tasks says.
            served.today = emptyToday()
            served.upcoming = taskListFixture("upcoming", event = ""","postponed":null,"completed_event":$eventFixture""")
            repository.refresh()
            fun check(state: CareState) = assertEquals(listOf("2030-06-02" to "event-one"),
                state.upcoming!!.sections.flatMap { section -> section.tasks.map { section.key to it.completedEvent?.id } })
            check(repository.state.value)
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            check(restarted.state.value)
        } }
    }
    @Test fun aLaterSkipKeepsItsOwnUpcomingRowBesideTheCompletion() {
        val skipped = taskListFixture("upcoming", "2030-06-09", event = ""","postponed":"skipped"""")
        // The server lists today's completion and the skipped occurrence as separate rows.
        val withCompleted = org.json.JSONObject(skipped).also { list ->
            val done = org.json.JSONObject(taskListFixture("upcoming", "2030-06-09", event = ""","postponed":null,"completed_event":$eventFixture"""))
                .getJSONArray("sections").getJSONObject(0).put("key", "2030-06-02").put("label", "2030-06-02")
            list.put("sections", org.json.JSONArray().put(done).put(list.getJSONArray("sections").getJSONObject(0)))
        }.toString()
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":$nextWater}"""))
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.single().tasks.single(), "complete"))
            enqueueRefresh(server, today = emptyToday(), upcoming = withCompleted); repository.refresh()
            val sections = repository.state.value.upcoming!!.sections.filter { it.tasks.isNotEmpty() }
            assertEquals(listOf("2030-06-02", "2030-06-09"), sections.map { it.key })
            val (done, next) = sections.map { it.tasks.single() }
            assertEquals("event-one", done.completedEvent!!.id)
            assertNull(next.completedEvent)
            assertEquals("Skipped", next.postponedLabel)
            // Undone on another device: the server lists only the skipped occurrence, and so does the app.
            enqueueRefresh(server, today = emptyToday(), upcoming = skipped); repository.refresh()
            assertEquals(listOf("Skipped" to null), repository.state.value.upcoming!!.sections.flatMap { it.tasks }.map { it.postponedLabel to it.completedEvent })
        }
    }
    @Test fun completingTheSkippedOccurrenceKeepsEachCompletionWithItsOwnRowAndUndo() {
        withRepository { repository, server, cache -> ServedLists(server).use { served ->
            repository.refresh()
            val complete = "/api/plants/plant-one/tasks/water/complete"
            served.responses[complete] = """{"event":$eventFixture,"linked_events":[],"task":$nextWater}"""
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.single().tasks.single(), "complete"))
            val second = org.json.JSONObject(eventFixture).put("id", "event-two").put("due_on", "2030-06-09")
            fun section(due: String, row: String) = org.json.JSONObject(taskListFixture("upcoming", due, event = row)).getJSONArray("sections").getJSONObject(0)
            fun upcoming(later: String) = org.json.JSONObject(taskListFixture("upcoming")).put("sections", org.json.JSONArray()
                .put(section("2030-06-02", ""","postponed":null,"completed_event":$eventFixture""")).put(section("2030-06-09", later))).toString()
            // Another member skips the next occurrence, which the server lists as its own row.
            served.today = emptyToday(); served.upcoming = upcoming(""","postponed":"skipped"""")
            repository.refresh()
            val skipped = repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.completedEvent == null }
            served.responses[complete] = """{"event":$second,"linked_events":[],"task":{"status":"due","dueOn":"2030-06-16","intervalDays":7,"lateDays":0,"basedOn":[],"postponed":null}}"""
            assertTrue(repository.act(skipped, "complete"))
            assertEquals("event-two", repository.state.value.receipt!!.undo!!.id)
            fun check(state: CareState) {
                val rows = state.upcoming!!.sections.flatMap { it.tasks }
                assertEquals(listOf("event-one", "event-two"), rows.map { it.completedEvent?.id })
                for (row in rows) assertEquals(listOf("undo"), row.actions("2030-06-02", Member("member-one", Role.owner)))
            }
            check(repository.state.value)
            // The server lists each completion at the due date of the occurrence it completed.
            served.upcoming = upcoming(""","postponed":null,"completed_event":$second""")
            repository.refresh()
            check(repository.state.value)
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            check(restarted.state.value)
            served.responses["/api/care-events/event-two/undo"] = undoResponse(""""event":$second,"task":{"status":"due","dueOn":"2030-06-09","intervalDays":7,"lateDays":0,"basedOn":[],"postponed":"skipped"}""") { view ->
                if (view == "today") emptyToday() else upcoming(""","postponed":"skipped"""") }
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.flatMap { it.tasks }.last(), "undo"))
            assertEquals("/api/care-events/event-two/undo", served.paths.last { it.startsWith("/api/care-events/") })
            assertEquals(listOf("event-one", null), repository.state.value.upcoming!!.sections.flatMap { it.tasks }.map { it.completedEvent?.id })
            assertEquals(3, served.paths.count { it == "/api/tasks?view=upcoming" })
        } }
    }
    @Test fun aCompletionUndoneElsewhereDisappearsOnRefreshAndRestart() {
        withRepository { repository, server, cache -> ServedLists(server, today = emptyToday(), upcoming = taskListFixture("upcoming", "2030-06-05")).use { served ->
            repository.refresh()
            served.responses["/api/plants/plant-one/tasks/water/complete"] = """{"event":${org.json.JSONObject(eventFixture).put("due_on", "2030-06-05")},"linked_events":[],"task":$nextWater}"""
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.single().tasks.single(), "complete"))
            assertEquals("event-one", repository.state.value.upcoming!!.sections.single().tasks.single().completedEvent!!.id)
            // Undone on another device: the server lists the occurrence as due again.
            repository.refresh()
            fun check(state: CareState) {
                val row = state.upcoming!!.sections.flatMap { it.tasks }.single()
                assertNull(row.completedEvent)
                assertEquals(listOf("complete", "skip"), row.actions("2030-06-02", Member("member-one", Role.owner)))
            }
            check(repository.state.value)
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            check(restarted.state.value)
        } }
    }
    @Test fun liquidFertilizeAfterWateringShowsTheLinkedWaterAsItsOwnServerRow() {
        val noRows = org.json.JSONObject(taskListFixture("upcoming")).put("sections", org.json.JSONArray()).put("groups", org.json.JSONObject()).toString()
        withRepository { repository, server, cache -> ServedLists(server, today = fertilizeAndWaterToday(listOf("plant-one")), upcoming = noRows).use { served ->
            repository.refresh()
            fun row(type: String) = repository.state.value.today!!.sections.first { it.key == type }.tasks.single { it.completedEvent == null }
            fun event(id: String, type: String) = org.json.JSONObject(eventFixture).put("id", id).put("task_type", type)
            val linked = event("water-two", "water").put("linked_event_id", "fert-one").put("can_undo", false)
            served.responses["/api/plants/plant-one/tasks/water/complete"] = """{"event":${event("water-one", "water")},"linked_events":[],"task":$nextWater}"""
            served.responses["/api/plants/plant-one/tasks/fertilize/complete"] = """{"event":${event("fert-one", "fertilize")},"linked_events":[$linked],"task":$nextWater}"""
            fun today(events: Map<String, List<org.json.JSONObject>>) = org.json.JSONObject(fertilizeAndWaterToday(listOf("plant-one"))).also { list ->
                list.getJSONArray("sections").mapObjects { it }.forEach { section ->
                    val template = section.getJSONArray("tasks").getJSONObject(0)
                    val rows = events[section.getString("key")]?.let { completions -> org.json.JSONArray(completions.map { org.json.JSONObject(template.toString()).put("completed_event", it).put("dueOn", "2030-06-09").put("lateDays", 0) }) }
                        ?: section.getJSONArray("tasks")
                    section.put("tasks", rows)
                    list.getJSONObject("groups").put(section.getString("key"), org.json.JSONArray(rows.toString()))
                }
            }.toString()
            fun shown(state: CareState) = listOf(state.today!!.sections.associate { it.key to it.tasks }, state.today.groups)
                .map { rows -> rows.filterKeys { it in listOf("fertilize", "water") }.mapValues { it.value.map { task -> task.completedEvent?.id } } }
            assertTrue(repository.act(row("water"), "complete"))
            served.today = today(mapOf("fertilize" to listOf(event("fert-one", "fertilize")), "water" to listOf(event("water-one", "water"), linked)))
            assertTrue(repository.act(row("fertilize"), "complete"))
            assertEquals("Fertilized and watered Synthetic fern", repository.state.value.receipt!!.text)
            val both = mapOf("fertilize" to listOf("fert-one"), "water" to listOf("water-one", "water-two"))
            served.await { shown(repository.state.value) == listOf(both, both) }
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            assertEquals(listOf(both, both), shown(restarted.state.value))
            // Fertilizing is undone elsewhere, which also undoes its linked water.
            served.today = today(mapOf("water" to listOf(event("water-one", "water"))))
            repository.refresh()
            val undone = mapOf("fertilize" to listOf(null), "water" to listOf("water-one"))
            assertEquals(listOf(undone, undone), shown(repository.state.value))
        } }
    }
    @Test fun recoveredPlantBatchReceiptUsesTheRefreshedPlantName() {
        withRepository { repository, server, _ -> ServedLists(server, plants = "[$plantFixture]").use { served ->
            repository.refresh()
            served.responses["/api/plants/plant-one"] = plantFixture
            served.responses["/api/plants/plant-one/history"] = "[]"
            served.responses["/api/plants/plant-one/tasks"] = """{"water":$nextWater,"customTasks":{}}"""
            repository.openPlant("plant-one")
            val batch = org.json.JSONObject(eventFixture).put("batch_id", "batch-all").put("batch_plant_count", 1).put("batch_event_count", 1)
                .put("batch_action", "complete-all").put("batch_task", org.json.JSONObject.NULL)
            served.responses["/api/plants/plant-one/tasks/complete-all"] = """{"batch_id":"batch-all","tasks":{"water":$nextWater},"events":[$batch]}"""
            assertTrue(repository.handleAll("plant-one", plantsScreen = true))
            assertEquals("Handled 1 task for Synthetic fern", repository.state.value.receipt!!.text)
            // Renamed on another device while the plant page stays loaded.
            served.plants = "[${org.json.JSONObject(plantFixture).put("display_name", "Renamed fern")}]"
            served.today = taskListFixture("today", event = ""","completed_event":$batch""").replace("Synthetic fern", "Renamed fern")
            served.upcoming = taskListFixture("upcoming", "2030-06-09").replace("Synthetic fern", "Renamed fern")
            repository.refresh()
            val row = repository.state.value.today!!.sections.single().tasks.single()
            assertEquals("Renamed fern", row.plantName)
            assertEquals("Synthetic fern", repository.state.value.detail!!.plant.displayName)
            assertTrue(repository.act(row, "undo"))
            assertEquals("Handled 1 task for Renamed fern", repository.state.value.receipt!!.text)
        } }
    }
    private fun CareState.openedTask() = currentTask(openedOccurrence)
    @Test fun aSnoozeMovesTheOpenedOccurrenceToItsSnoozedRow() {
        val holding = java.util.concurrent.atomic.AtomicBoolean(false)
        withRepository { repository, server, _ -> ServedLists(server, hold = { holding.get() && it.requestUrl!!.encodedPath == "/api/tasks" }).use { served ->
            repository.refresh()
            val opened = repository.state.value.today!!.sections.single().tasks.single()
            repository.open(opened.occurrence)
            val snooze = org.json.JSONObject(eventFixture).put("id", "event-snooze").put("kind", "snoozed")
            served.responses["/api/plants/plant-one/tasks/water/snooze"] = """{"event":$snooze,"task":{"status":"due","dueOn":"2030-06-04","intervalDays":7,"lateDays":0,"basedOn":[],"postponed":"snoozed"}}"""
            postponedLists(snooze, "2030-06-04").let { (today, upcoming) -> served.today = today; served.upcoming = upcoming }
            holding.set(true)
            assertTrue(repository.act(opened, "snooze"))
            assertEquals("event-snooze", repository.state.value.receipt!!.undo!!.id)
            val snoozed = repository.state.value.openedTask()!!
            assertEquals("event-snooze" to "2030-06-04", snoozed.completedEvent?.id to snoozed.postponedUntil)
            served.release()
            served.await { served.paths.count { it == "/api/tasks?view=upcoming" } == 2 }
            repository.refresh()
            assertEquals(snoozed.occurrence, repository.state.value.openedOccurrence)
            assertEquals("event-snooze", repository.state.value.openedTask()!!.completedEvent?.id)
            // The snooze is undone elsewhere, so the server lists the original occurrence again and the snoozed one is gone.
            served.today = taskListFixture("today"); served.upcoming = taskListFixture("upcoming")
            repository.refresh()
            assertNull(repository.state.value.openedTask())
            assertNull(repository.state.value.currentTask(opened.occurrence)!!.postponed)
        } }
    }
    // Today's snooze or skip as the server lists it: in the acted row's place, beside the occurrence it postponed.
    private fun postponedLists(event: org.json.JSONObject, until: String): Pair<String, String> {
        val row = ""","postponed":null,"postponed_until":"$until","completed_event":$event"""
        fun section(due: String, fields: String) = org.json.JSONObject(taskListFixture("upcoming", due, event = fields)).getJSONArray("sections").getJSONObject(0)
        return taskListFixture("today", event = row) to org.json.JSONObject(taskListFixture("upcoming")).put("sections", org.json.JSONArray()
            .put(section("2030-06-02", row)).put(section(until, ""","postponed":"${event.getString("kind")}""""))).toString()
    }
    @Test fun aSnoozedRowStaysOnTodayThroughRefreshAndRestartAndItsUndoRestoresThePendingRow() =
        postponedRowStays("snooze", "snoozed", "2030-06-04", "Snoozed Synthetic fern until Tue 4 Jun", "Undid snooze for Synthetic fern")
    @Test fun aSkippedRowStaysOnTodayThroughRefreshAndRestartAndItsUndoRestoresThePendingRow() =
        postponedRowStays("skip", "skipped", "2030-06-09", "Skipped Synthetic fern until Sun 9 Jun", "Undid skip for Synthetic fern")
    private fun postponedRowStays(action: String, kind: String, until: String, receipt: String, undone: String) {
        withRepository { repository, server, cache -> ServedLists(server).use { served ->
            repository.refresh()
            val pending = repository.state.value.today!!.sections.single().tasks.single()
            val event = org.json.JSONObject(eventFixture).put("id", "event-$kind").put("kind", kind)
            served.responses["/api/plants/plant-one/tasks/water/$action"] =
                """{"event":$event,"linked_events":[],"task":{"status":"due","dueOn":"$until","intervalDays":7,"lateDays":0,"basedOn":[],"postponed":"$kind"}}"""
            postponedLists(event, until).let { (today, upcoming) -> served.today = today; served.upcoming = upcoming }
            assertTrue(repository.act(pending, action))
            assertEquals(receipt, repository.state.value.receipt!!.text)
            fun check(state: CareState) {
                assertEquals(listOf(Triple("event-$kind", kind, until)), state.today!!.sections.flatMap { it.tasks }.map { Triple(it.completedEvent?.id, it.completedEvent?.kind, it.postponedUntil) })
                assertEquals(listOf("undo"), state.today.sections.single().tasks.single().actions("2030-06-02", Member("member-one", Role.owner)))
            }
            check(repository.state.value)
            served.await { served.paths.count { it == "/api/tasks?view=upcoming" } == 2 }
            repository.refresh()
            check(repository.state.value)
            // The occurrence it postponed keeps its own row at its new date.
            assertEquals(listOf(Triple("2030-06-02", "event-$kind", null), Triple(until, null, kind)),
                repository.state.value.upcoming!!.sections.flatMap { section -> section.tasks.map { Triple(section.key, it.completedEvent?.id, it.postponed) } })
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            check(restarted.state.value)
            served.responses["/api/care-events/event-$kind/undo"] = undoResponse(""""event":$event,"task":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[],"postponed":null}""") { taskListFixture(it) }
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "undo"))
            assertEquals(undone, repository.state.value.receipt!!.text)
            val restored = repository.state.value.today!!.sections.single().tasks.single()
            assertEquals(pending.occurrence to null, restored.occurrence to restored.completedEvent)
            assertEquals(listOf("complete", "snooze", "skip"), restored.actions("2030-06-02", Member("member-one", Role.owner)))
        } }
    }
    @Test fun theOpenedOccurrenceFollowsThisDevicesDoneAndUndo() {
        withRepository { repository, server, _ -> ServedLists(server).use { served ->
            repository.refresh()
            val opened = repository.state.value.today!!.sections.single().tasks.single()
            repository.open(opened.occurrence)
            served.responses["/api/plants/plant-one/tasks/water/complete"] = """{"event":$eventFixture,"linked_events":[],"task":$nextWater}"""
            served.responses["/api/care-events/event-one/undo"] = undoResponse(""""event":$eventFixture,"task":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[]},$restoredWater""") { taskListFixture(it) }
            assertTrue(repository.act(opened, "complete"))
            val done = repository.state.value.openedTask()!!
            assertEquals("event-one", done.completedEvent!!.id)
            assertTrue(repository.act(done, "undo"))
            val pending = repository.state.value.openedTask()!!
            assertNull(pending.completedEvent)
            assertEquals(opened.occurrence, pending.occurrence)
            assertEquals(1, served.paths.count { it == "/api/tasks?view=upcoming" })
        } }
    }
    @Test fun aRefreshThatLandsBeforeTheScreenRedrawsKeepsTheOpenedOccurrence() {
        withRepository { repository, server, _ -> ServedLists(server).use { served ->
            repository.refresh()
            val opened = repository.state.value.today!!.sections.single().tasks.single()
            repository.open(opened.occurrence)
            served.responses["/api/plants/plant-one/tasks/water/complete"] = """{"event":$eventFixture,"linked_events":[],"task":$nextWater}"""
            served.today = taskListFixture("today", event = ""","completed_event":$eventFixture""")
            served.upcoming = taskListFixture("upcoming", event = ""","completed_event":$eventFixture""")
            assertTrue(repository.act(opened, "complete"))
            // The server's rows replace this device's patch before anything has read it.
            repository.refresh()
            assertEquals("event-one", repository.state.value.openedOccurrence)
            assertEquals("event-one", repository.state.value.openedTask()!!.completedEvent!!.id)
        } }
    }
    @Test fun aScheduleOnlyRefreshGivesTheRecoveredPlantBatchReceiptTheRefreshedName() {
        withRepository { repository, server, _ -> ServedLists(server, plants = "[$plantFixture]").use { served ->
            repository.refresh()
            val batch = org.json.JSONObject(eventFixture).put("batch_id", "batch-all").put("batch_plant_count", 1).put("batch_event_count", 1)
                .put("batch_action", "complete-all").put("batch_task", org.json.JSONObject.NULL)
            served.responses["/api/plants/plant-one/tasks/complete-all"] = """{"batch_id":"batch-all","tasks":{"water":$nextWater},"events":[$batch]}"""
            assertTrue(repository.handleAll("plant-one"))
            assertEquals("Handled 1 task for Synthetic fern", repository.state.value.receipt!!.text)
            // Renamed on another device. A card write refreshes only Today and Upcoming, so the cached Plants header keeps the old name.
            served.plants = "[${org.json.JSONObject(plantFixture).put("display_name", "Renamed fern")}]"
            served.today = taskListFixture("today", event = ""","completed_event":$batch""").replace("Synthetic fern", "Renamed fern")
            served.upcoming = taskListFixture("upcoming", "2030-06-09").replace("Synthetic fern", "Renamed fern")
            served.responses["/api/tasks/fertilize/complete-all"] = """{"batch_id":"batch-empty","events":[]}"""
            assertTrue(repository.handleType("fertilize"))
            served.await { repository.state.value.today!!.sections.single().tasks.single().plantName == "Renamed fern" }
            assertEquals("Synthetic fern", repository.state.value.plants.single().displayName)
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "undo"))
            assertEquals("Handled 1 task for Renamed fern", repository.state.value.receipt!!.text)
        } }
    }
    @Test fun aPlantsOnlyRefreshGivesTheNextPlantBatchReceiptTheRefreshedName() {
        val db = database()
        var time = 1_900_000_000_000L
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, CareCache(db), { true }, { time++ })
            ServedLists(server, plants = "[$plantFixture]").use { served ->
                repository.refresh()
                // Renamed on another device, then only the Plants tab refreshes, so the Today rows keep the old name.
                served.plants = "[${org.json.JSONObject(plantFixture).put("display_name", "Renamed fern")}]"
                repository.refreshPlants()
                val batch = org.json.JSONObject(eventFixture).put("batch_id", "batch-all").put("batch_plant_count", 1).put("batch_event_count", 1)
                    .put("batch_action", "complete-all").put("batch_task", org.json.JSONObject.NULL)
                served.responses["/api/plants/plant-one/tasks/complete-all"] = """{"batch_id":"batch-all","tasks":{"water":$nextWater},"events":[$batch]}"""
                assertTrue(repository.handleAll("plant-one", plantsScreen = true))
                assertEquals("Synthetic fern", repository.state.value.today!!.sections.single().tasks.single().plantName)
                assertEquals("Handled 1 task for Renamed fern", repository.state.value.receipt!!.text)
            }
        }
        db.close()
    }
    @Test fun rowsFollowTheServersOrderAfterRefresh() {
        withRepository { repository, server, _ ->
            fun list(view: String, order: List<String>, done: String? = null): String {
                val json = org.json.JSONObject(taskListFixture(view))
                val section = json.getJSONArray("sections").getJSONObject(0)
                val template = section.getJSONArray("tasks").getJSONObject(0)
                val rows = org.json.JSONArray(order.map { plant ->
                    org.json.JSONObject(template.toString()).put("plant_id", plant).put("plant_name", plant).also {
                        if (plant == done) it.put("completed_event", org.json.JSONObject(eventFixture)).put("dueOn", "2030-06-09").put("lateDays", 0)
                    }
                })
                section.put("tasks", rows)
                json.getJSONObject("groups").put("water", org.json.JSONArray(rows.toString()))
                return json.toString()
            }
            val plants = listOf("plant-one", "plant-two")
            enqueueRefresh(server, today = list("today", plants), upcoming = list("upcoming", plants)); repository.refresh()
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":$nextWater}"""))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.first(), "complete"))
            assertEquals(plants, repository.state.value.today!!.sections.single().tasks.map { it.plantId })
            // The app keeps no positions of its own: the next refresh shows the server's order.
            enqueueRefresh(server, today = list("today", plants.reversed(), done = "plant-one"), upcoming = list("upcoming", plants.reversed(), done = "plant-one"))
            repository.refresh()
            for (view in listOf(repository.state.value.today!!, repository.state.value.upcoming!!)) {
                assertEquals(plants.reversed(), view.sections.single().tasks.map { it.plantId })
                assertEquals(plants.reversed(), view.groups.getValue("water").map { it.plantId })
                assertEquals("event-one", view.sections.single().tasks.last().completedEvent!!.id)
            }
        }
    }
    @Test fun logoutAndMemberChangeEraseEveryCachedResource() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        val cache = CareCache(db)
        val first = """{"member":{"id":"member-one","role":"owner"}}"""
        cache.bind(first)
        cache.save("member-one", mapOf("today" to taskListFixture("today"), "upcoming" to taskListFixture("upcoming"),
            "plants" to "[]", "plant/plant-one" to "{\"synthetic\":true}"), 1234)
        cache.bind("""{"member":{"id":"member-two","role":"sitter"}}""")
        for (resource in listOf("today", "upcoming", "plants", "plant/plant-one")) assertNull(cache.get("member-one", resource))
        assertEquals("member-two", cache.member()!!.id)
        cache.wipe()
        assertNull(cache.member())
        db.close()
    }
    @Test fun staleSaveAfterMemberRebindIsDiscarded() {
        val db = database()
        try {
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val pendingMember = cache.member()!!.id
            cache.bind("""{"member":{"id":"member-two","role":"family"}}""")
            cache.save("member-two", mapOf("plants" to "[]"), 1234)

            cache.save(pendingMember, mapOf("plants" to "[$plantFixture]", "today" to taskListFixture("today")), 5678)

            assertEquals("member-two", cache.member()!!.id)
            assertNull(cache.get(pendingMember, "plants"))
            assertNull(cache.get(pendingMember, "today"))
            assertNull(cache.get("member-two", "today"))
            assertEquals(CachedCare("member-two", "plants", "[]", 1234), cache.get("member-two", "plants"))
        } finally { db.close() }
    }
    @Test fun staleSaveAfterWipeIsDiscarded() {
        val db = database()
        try {
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val pendingMember = cache.member()!!.id
            cache.wipe()

            cache.save(pendingMember, mapOf("plants" to "[$plantFixture]", "today" to taskListFixture("today")), 5678)

            assertNull(cache.member())
            assertNull(cache.get(pendingMember, "plants"))
            assertNull(cache.get(pendingMember, "today"))
        } finally { db.close() }
    }
    @Test fun pendingPlantOptionsAfterCacheRebindAreDiscardedWithoutThrowing() {
        withRepository { repository, server, cache ->
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            repository.restore()
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody("""{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":["good"]},"nameLanguages":{}}""")
                }
            }
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<com.epaflix.fytorium.plants.PlantOptions?> { repository.plantOptions() }
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                cache.bind("""{"member":{"id":"member-two","role":"family"}}""")
                release.countDown()

                assertNull(pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("member-two", cache.member()!!.id)
                assertNull(cache.get("member-one", "plant-choices"))
                assertNull(cache.get("member-two", "plant-choices"))
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }
    @Test fun incompatibleCacheSchemaIsDiscardedRatherThanBlockingStartup() {
        val context = RuntimeEnvironment.getApplication()
        val name = "synthetic-cache-migration.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CareDatabase::class.java, name)
            .fallbackToDestructiveMigration(dropAllTables = true).allowMainThreadQueries().build()
        val original = open()
        CareCache(original).bind("""{"member":{"id":"member-one","role":"owner"}}""")
        original.openHelper.writableDatabase.execSQL("PRAGMA user_version = 2")
        original.close()
        val migrated = open()
        assertNull(CareCache(migrated).member())
        migrated.close()
        context.deleteDatabase(name)
    }
    @Test fun thumbnailCacheEvictsLeastRecentlyUsedAndRejectsOversizedEntries() {
        val dir = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "synthetic-thumbnails").apply { deleteRecursively() }
        val cache = ThumbnailCache(dir, 10)
        cache.put("first", ByteArray(4) { 1 }); cache.put("second", ByteArray(4) { 2 })
        assertArrayEquals(ByteArray(4) { 1 }, cache.get("first"))
        cache.put("third", ByteArray(4) { 3 })
        assertNull(cache.get("second"))
        assertNotNull(cache.get("first")); assertNotNull(cache.get("third"))
        cache.put("huge", ByteArray(11))
        assertNull(cache.get("huge"))
        assertTrue(cache.sizeBytes() <= 10)
        cache.wipe(); assertEquals(0L, cache.sizeBytes())
    }
    @Test fun cachedPlantDetailsAndHistorySurviveProcessRestoreAndNetworkFailure() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            server.enqueue(MockResponse().setBody(plantFixture))
            server.enqueue(MockResponse().setBody("[$eventFixture]"))
            server.enqueue(MockResponse().setBody("""{"water":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[]},"customTasks":{}}"""))
            val detail = repository.openPlant("plant-one")!!
            assertEquals("Synthetic fern", detail.plant.displayName)
            assertEquals("event-one", detail.history.single().id)
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val restored = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, cache, { false })
            restored.restore()
            val offline = restored.openPlant("plant-one")!!
            assertFalse(restored.plantState.value.online)
            assertFalse(restored.state.value.offline)
            assertEquals(detail, offline)
            assertEquals(8, server.requestCount)
        }
    }
    @Test fun updateRequiredBlocksWritesWithoutCallingTheServerAgain() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setResponseCode(426).setBody("""{"error":"update_required","minAppVersion":"2.0.0"}"""))
            repository.refresh()
            assertTrue(repository.state.value.updateRequired)
            assertFalse(repository.act(task, "complete"))
            assertEquals(6, server.requestCount)
            for (status in listOf(503, 422)) {
                server.enqueue(MockResponse().setResponseCode(status)); repository.refresh()
                assertTrue(repository.state.value.updateRequired)
                assertFalse(repository.state.value.writable)
            }
            enqueueRefresh(server); repository.refresh()
            assertFalse(repository.state.value.updateRequired)
            assertTrue(repository.state.value.writable)
        }
    }
    @Test fun aRefreshInFlightCannotRestoreDataAfterLogout() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"}}""")
                }
            }
            val thread = Thread { repository.refresh() }.apply { start() }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repository.wipe()
            release.countDown(); thread.join(5000)
            assertFalse(thread.isAlive)
            assertNull(repository.state.value.today)
            assertNull(cache.member())
            assertEquals(6, server.requestCount)
        }
    }
    @Test fun completeKeepsUndoAndUndoRestoresTheServerTask() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server)
            repository.refresh()
            val original = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":{"status":"due","dueOn":"2030-06-09","intervalDays":7,"lateDays":0,"basedOn":[]}}"""))
            assertTrue(repository.act(original, "complete"))
            val done = repository.state.value.today!!.sections.single().tasks.single()
            assertEquals(listOf("undo"), done.actions("2030-06-02", Member("member-one", Role.owner)))
            assertEquals("event-one", done.completedEvent!!.id)
            assertTrue(cache.get("member-one", "today")!!.json.contains("event-one"))
            assertEquals("Watered Synthetic fern", repository.state.value.receipt!!.text)
            assertEquals("event-one", repository.state.value.receipt!!.undo!!.id)
            server.enqueue(MockResponse().setBody(undoResponse(""""event":$eventFixture,"task":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[]}""") { taskListFixture(it) }))
            assertTrue(repository.act(done, "undo"))
            assertNull(repository.state.value.today!!.sections.single().tasks.single().completedEvent)
            assertEquals(1, repository.state.value.today!!.sections.single().tasks.single().lateDays)
            assertEquals("Undid watering for Synthetic fern", repository.state.value.receipt!!.text)
            val requests = (1..7).map { server.takeRequest() }
            // The undo response's lists replace the cached ones, so no Undo stays in the cache and no refresh follows.
            assertNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(cache.get("member-one", "today")!!.json.contains("event-one"))
            assertEquals("/api/plants/plant-one/tasks/water/complete", requests[5].path)
            assertEquals("Bearer synthetic-access", requests[5].getHeader("Authorization"))
            java.util.UUID.fromString(org.json.JSONObject(requests[5].body.readUtf8()).getString("request_id"))
            assertEquals("/api/care-events/event-one/undo", requests[6].path)
        }
    }
    // Every write is followed by refreshes that fail here, so the rows show only what the write responses carried.
    private fun undoServer(server: MockWebServer, rows: (String) -> String, vararg responses: Pair<String, String>, plants: String = "[]") {
        val written = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (val path = request.requestUrl!!.encodedPath) {
                "/api/me" -> MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"}}""")
                "/api/tasks" -> if (written.get()) MockResponse().setResponseCode(503) else MockResponse().setBody(rows(request.requestUrl!!.queryParameter("view")!!))
                "/api/plants" -> MockResponse().setBody(plants)
                "/api/meta" -> MockResponse().setBody("""{"timezone":"Europe/Oslo"}""")
                else -> responses.toMap()[path]?.let { if (request.method == "POST") written.set(true); MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
    }
    private fun withRows(view: String, vararg rows: org.json.JSONObject, due: String = "2030-06-09"): String {
        val json = org.json.JSONObject(taskListFixture(view, due))
        val template = json.getJSONArray("sections").getJSONObject(0).getJSONArray("tasks").getJSONObject(0)
        json.getJSONArray("sections").getJSONObject(0).put("tasks", org.json.JSONArray(rows.map { row ->
            org.json.JSONObject(template.toString()).put("lateDays", 0).also { copy -> row.keys().forEach { copy.put(it, row.get(it)) } }
        }))
        return json.toString()
    }
    private val lateWater = """{"status":"due","dueOn":"2030-05-27","intervalDays":7,"lateDays":6,"basedOn":[],"postponed":null}"""
    private fun late(view: String, vararg plants: String) = withRows(view, *plants.map { org.json.JSONObject(lateWater).put("plant_id", it) }.toTypedArray(), due = "2030-05-27")
    // An undo response carries the member's lists as the server lists them after the undo.
    private fun undoResponse(fields: String, lists: (String) -> String) = """{$fields,"today":${lists("today")},"upcoming":${lists("upcoming")}}"""
    private val restoredWater = """"restored":[{"event_id":"event-one","occurrence":{"plant_id":"plant-one","task_type":"water","custom_task_id":null,"due_on":"2030-06-01"}}]"""
    private fun assertShows(repository: Repository, lists: (String) -> String) {
        assertEquals(parseTaskList(lists("today")), repository.state.value.today)
        assertEquals(parseTaskList(lists("upcoming")), repository.state.value.upcoming)
    }
    private fun assertLate(rows: List<CareTask>, plants: List<String>) {
        assertEquals(plants, rows.map { it.plantId })
        for (row in rows) {
            assertNull(row.completedEvent)
            assertEquals(6, row.lateDays)
            assertEquals(listOf("complete", "snooze", "skip"), row.actions("2030-06-02", Member("member-one", Role.owner)))
        }
    }
    @Test fun aCareActionForTheOpenPlantShowsInItsHistoryAndItsUndoMarksItUndone() {
        withRepository { repository, server, _ ->
            val history = java.util.concurrent.atomic.AtomicReference("[]")
            val undone = org.json.JSONObject(eventFixture).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    "/api/tasks" -> taskListFixture(request.requestUrl!!.queryParameter("view")!!)
                    "/api/plants" -> "[$plantFixture]"
                    "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    "/api/plants/plant-one" -> plantFixture
                    "/api/plants/plant-one/history" -> history.get()
                    "/api/plants/plant-one/tasks" -> """{"water":$nextWater,"customTasks":{}}"""
                    "/api/plants/plant-one/tasks/water/complete" -> { history.set("[$eventFixture]"); """{"event":$eventFixture,"task":$nextWater}""" }
                    "/api/care-events/event-one/undo" -> { history.set("[$undone]"); undoResponse(""""event":$undone,"task":$lateWater""") { late(it, "plant-one") } }
                    else -> "{}"
                })
            }
            repository.refresh()
            repository.openPlant("plant-one")
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "complete"))
            assertEquals(listOf("event-one" to null), repository.state.value.detail!!.history.map { it.id to it.undoneAt })
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "undo"))
            assertEquals(listOf("event-one" to "2030-06-02T10:05:00Z"), repository.state.value.detail!!.history.map { it.id to it.undoneAt })
        }
    }
    @Test fun undoingACardBatchReloadsTheOpenPageOfAnotherPlantInIt() {
        withRepository { repository, server, _ ->
            val events = listOf("plant-one", "plant-two").map { plant -> org.json.JSONObject(eventFixture).put("id", "event-$plant").put("plant_id", plant)
                .put("batch_id", "batch-one").put("batch_plant_count", 2).put("batch_event_count", 2).put("batch_action", "complete-type").put("batch_task", "water") }
            val undone = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    "/api/tasks" -> withRows(request.requestUrl!!.queryParameter("view")!!, *events.map { org.json.JSONObject().put("plant_id", it.getString("plant_id")).put("completed_event", it) }.toTypedArray())
                    "/api/plants" -> "[]"
                    "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    "/api/plants/plant-two" -> org.json.JSONObject(plantFixture).put("id", "plant-two").toString()
                    "/api/plants/plant-two/history" -> "[${org.json.JSONObject(events[1].toString()).apply { if (undone.get()) put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false) }}]"
                    "/api/plants/plant-two/tasks" -> """{"water":$lateWater,"customTasks":{}}"""
                    "/api/task-batches/batch-one/undo" -> { undone.set(true); undoResponse(""""batchId":"batch-one","undoneAt":"2030-06-02T10:05:00Z"""") { late(it, "plant-one", "plant-two") } }
                    else -> "{}"
                })
            }
            repository.refresh()
            repository.openPlant("plant-two")
            // The receipt's event belongs to the first plant of the batch.
            assertTrue(repository.undoReceipt(Receipt("Watered 2 plants", parseEvent(events[0]))))
            assertEquals(listOf("event-plant-two" to "2030-06-02T10:05:00Z"), repository.state.value.detail!!.history.map { it.id to it.undoneAt })
        }
    }
    @Test fun anUndoneBatchShowsEveryRowsLateBadgeAndSnoozeBeforeTheNextRefresh() {
        withRepository { repository, server, cache ->
            val events = listOf("plant-one", "plant-two").map { plant -> org.json.JSONObject(eventFixture).put("id", "event-$plant").put("plant_id", plant)
                .put("batch_id", "batch-one").put("batch_plant_count", 2).put("batch_event_count", 2).put("batch_action", "complete-type").put("batch_task", "water") }
            val restored = { view: String -> late(view, "plant-one", "plant-two") }
            undoServer(server, { view -> withRows(view, *events.map { org.json.JSONObject().put("plant_id", it.getString("plant_id")).put("completed_event", it) }.toTypedArray()) },
                "/api/task-batches/batch-one/undo" to undoResponse(""""batchId":"batch-one","undoneAt":"2030-06-02T10:00:00Z"""", restored))
            repository.refresh()
            assertTrue(repository.undoReceipt(Receipt("Watered 2 plants", parseEvent(events[0]))))
            for (list in listOf(repository.state.value.today!!, repository.state.value.upcoming!!)) assertLate(list.sections.flatMap { it.tasks }, listOf("plant-one", "plant-two"))
            // The app shows exactly the server's lists from the undo response, also after a restart.
            assertShows(repository, restored)
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            assertShows(restarted, restored)
        }
    }
    @Test fun undoingLiquidFertilizeShowsItsLinkedWaterAsLateAgainBeforeTheNextRefresh() {
        withRepository { repository, server, _ ->
            val fertilize = org.json.JSONObject(eventFixture).put("task_type", "fertilize")
            val water = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-one")
            val lateFertilize = org.json.JSONObject(lateWater).put("intervalDays", 30)
            undoServer(server, { view -> withRows(view, org.json.JSONObject().put("task_type", "fertilize").put("completed_event", fertilize),
                org.json.JSONObject().put("completed_event", water)) },
                "/api/care-events/event-one/undo" to undoResponse(""""event":$fertilize,"task":$lateFertilize""") { view ->
                    withRows(view, org.json.JSONObject(lateFertilize.toString()).put("task_type", "fertilize"), org.json.JSONObject(lateWater), due = "2030-05-27") })
            repository.refresh()
            assertTrue(repository.undo(parseEvent(fertilize)))
            assertEquals("Undid fertilizing and watering for Synthetic fern", repository.state.value.receipt!!.text)
            for (list in listOf(repository.state.value.today!!, repository.state.value.upcoming!!)) {
                val rows = list.sections.flatMap { it.tasks }
                assertEquals(listOf("fertilize", "water"), rows.map { it.key }.sorted())
                assertLate(rows, listOf("plant-one", "plant-one"))
            }
        }
    }
    @Test fun anUndoneCompletionBesideItsPostponedOccurrenceLeavesOnePendingRow() {
        withRepository { repository, server, _ ->
            val skipped = org.json.JSONObject(lateWater).put("dueOn", "2030-06-09").put("lateDays", 0).put("postponed", "skipped")
            val done = org.json.JSONObject().put("postponed", org.json.JSONObject.NULL).put("completed_event", org.json.JSONObject(eventFixture))
            // Today never lists a postponed occurrence, which is due after today.
            undoServer(server, { view -> withRows(view, *listOfNotNull(done, skipped.takeIf { view == "upcoming" }).toTypedArray()) },
                "/api/care-events/event-one/undo" to undoResponse(""""event":$eventFixture,"task":$skipped""") { view ->
                    withRows(view, *listOfNotNull(skipped.takeIf { view == "upcoming" }).toTypedArray()) })
            repository.refresh()
            assertTrue(repository.undo(parseEvent(org.json.JSONObject(eventFixture))))
            assertTrue(repository.state.value.today!!.sections.flatMap { it.tasks }.isEmpty())
            val row = repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single()
            assertNull(row.completedEvent)
            assertEquals("skipped", row.postponed)
        }
    }
    @Test fun undoingASnoozeOrSkipBringsItsTodayRowBackBeforeTheNextRefresh() {
        withRepository { repository, server, _ ->
            fun postponed(kind: String) = org.json.JSONObject(eventFixture).put("id", "event-$kind").put("kind", kind).put("due_on", "2030-05-27")
            fun later(kind: String) = org.json.JSONObject(lateWater).put("dueOn", "2030-06-03").put("lateDays", 0).put("postponed", kind)
            undoServer(server, { view -> late(view, "plant-one") },
                *listOf("snooze" to "snoozed", "skip" to "skipped").flatMap { (action, kind) -> listOf(
                    "/api/plants/plant-one/tasks/water/$action" to """{"event":${postponed(kind)},"task":${later(kind)}}""",
                    "/api/care-events/event-$kind/undo" to undoResponse(""""event":${postponed(kind)},"task":$lateWater""") { late(it, "plant-one") }) }.toTypedArray())
            repository.refresh()
            fun today() = repository.state.value.today!!.sections.flatMap { it.tasks }
            fun assertRestored() {
                assertLate(today(), listOf("plant-one"))
                val upcoming = repository.state.value.upcoming!!.sections.filter { it.tasks.isNotEmpty() }
                assertEquals(listOf("2030-06-02"), upcoming.map { it.key })
                assertLate(upcoming.single().tasks, listOf("plant-one"))
            }
            assertTrue(repository.act(today().single(), "snooze"))
            assertEquals(listOf("event-snoozed" to "2030-06-03"), today().map { it.completedEvent?.id to it.postponedUntil })
            assertTrue(repository.undoReceipt(repository.state.value.receipt!!))
            assertEquals("Undid snooze for Synthetic fern", repository.state.value.receipt!!.text)
            assertRestored()
            assertTrue(repository.act(today().single(), "skip"))
            assertEquals(listOf("event-skipped" to "2030-06-03"), today().map { it.completedEvent?.id to it.postponedUntil })
            assertTrue(repository.undoHistory(parseEvent(postponed("skipped"))))
            assertEquals("Undid skip for Synthetic fern", repository.state.value.receipt!!.text)
            assertRestored()
        }
    }
    @Test fun undoingAnEarlierCompletionMovesItsUpcomingRowToTodayAndAddsItToToday() {
        withRepository { repository, server, _ ->
            val earlier = org.json.JSONObject(eventFixture).put("occurred_on", "2030-05-27")
            val next = org.json.JSONObject(lateWater).put("dueOn", "2030-06-03").put("lateDays", 0)
            undoServer(server, { view -> if (view == "today") withRows(view) else withRows(view, next, due = "2030-06-03") },
                "/api/care-events/event-one/undo" to undoResponse(""""event":$earlier,"task":$lateWater""") { late(it, "plant-one") })
            repository.refresh()
            assertTrue(repository.undoHistory(parseEvent(earlier)))
            assertLate(repository.state.value.today!!.sections.flatMap { it.tasks }, listOf("plant-one"))
            val upcoming = repository.state.value.upcoming!!.sections.filter { it.tasks.isNotEmpty() }
            assertEquals(listOf("2030-06-02"), upcoming.map { it.key })
            assertLate(upcoming.single().tasks, listOf("plant-one"))
        }
    }
    @Test fun anUndoneSkipOfAPlantWithoutRowsShowsTheServersRestoredRowInAnyFilter() {
        for (filter in listOf("all", "indoor")) withRepository { repository, server, _ ->
            val skipped = org.json.JSONObject(eventFixture).put("id", "event-skipped").put("kind", "skipped")
            fun filtered(list: String) = list.replace("\"filter\":\"all\"", "\"filter\":\"$filter\"")
            undoServer(server, { view -> filtered(withRows(view)) },
                "/api/care-events/event-skipped/undo" to undoResponse(""""event":$skipped,"task":$lateWater""") { view -> filtered(late(view, "plant-one")) },
                plants = "[$plantFixture]")
            repository.refresh()
            assertTrue(repository.undoHistory(parseEvent(skipped)))
            val rows = repository.state.value.today!!.sections.flatMap { it.tasks }
            assertLate(rows, listOf("plant-one"))
            assertEquals("Synthetic fern, Synthetic site", rows.single().plantAndSite)
        }
    }
    private val rainEvents = listOf("plant-one", "plant-two").map { plant -> org.json.JSONObject(eventFixture).put("id", "rain-$plant").put("plant_id", plant)
        .put("batch_id", "batch-rain").put("batch_plant_count", 2).put("batch_event_count", 2).put("batch_action", "rain") }
    private val rainWeather = org.json.JSONObject(com.epaflix.fytorium.feed.weatherFixture).put("lastRainBatch", org.json.JSONObject("""{"id":"batch-rain","plantCount":2}""")).toString()
    private fun rainRows(view: String, vararg more: org.json.JSONObject) =
        withRows(view, *rainEvents.map { org.json.JSONObject().put("plant_id", it.getString("plant_id")).put("completed_event", it) }.toTypedArray(), *more)
    private fun rainFeed(server: MockWebServer, repository: Repository) = com.epaflix.fytorium.feed.FeedRepository(
        ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, { true }, repository::undoBatch)
            .apply { refresh(Member("member-one", Role.owner)) }
    private fun rainServer(request: okhttp3.mockwebserver.RecordedRequest, rows: (String) -> String, extra: (String) -> String?): MockResponse =
        MockResponse().setBody(extra(request.requestUrl!!.encodedPath) ?: when (request.requestUrl!!.encodedPath) {
        "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
        "/api/plants" -> "[]"
        "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
        "/api/feed" -> com.epaflix.fytorium.feed.feedFixture
        "/api/weather" -> rainWeather
        "/api/tasks" -> rows(request.requestUrl!!.queryParameter("view")!!)
        else -> "{}"
    })
    @Test fun undoingRainFromTheFeedShowsItsRowsLateBeforeTheNextRefresh() {
        withRepository { repository, server, _ ->
            val restored = { view: String -> late(view, "plant-one", "plant-two") }
            undoServer(server, { view -> rainRows(view) },
                "/api/feed" to com.epaflix.fytorium.feed.feedFixture, "/api/weather" to rainWeather,
                "/api/task-batches/batch-rain/undo" to undoResponse(""""batchId":"batch-rain","undoneAt":"2030-06-02T10:00:00Z"""", restored))
            repository.refresh()
            val feed = rainFeed(server, repository)
            assertEquals("batch-rain", feed.state.value.weather!!.lastBatchId)
            assertTrue(feed.undoRain())
            assertNull("The Undo leaves no lasting text above the feed", feed.state.value.message)
            assertEquals("Undid watering for 2 outdoor plants" to true, repository.state.value.receipt?.let { it.text to it.short })
            for (list in listOf(repository.state.value.today!!, repository.state.value.upcoming!!)) assertLate(list.sections.flatMap { it.tasks }, listOf("plant-one", "plant-two"))
            assertShows(repository, restored)
        }
    }
    @Test fun taskViewSavesAndRainUndoLeaveTheFeedRefreshToTheirCallers() {
        withRepository { repository, server, _ ->
            val restored = { view: String -> late(view, "plant-one", "plant-two") }
            undoServer(server, { view -> rainRows(view) }, "/api/tasks/settings" to "{}",
                "/api/task-batches/batch-rain/undo" to undoResponse(""""batchId":"batch-rain","undoneAt":"2030-06-02T10:00:00Z"""", restored),
                "/api/care-events/event-one/undo" to undoResponse(""""event":$eventFixture,"task":$lateWater""") { late(it, "plant-one") })
            repository.refresh()
            val changes = java.util.concurrent.atomic.AtomicInteger()
            val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { repository.careChanged.collect { changes.incrementAndGet() } }
            try {
                assertTrue(repository.saveSettings(TaskSettings(sort = "plant")))
                assertTrue(repository.undoBatch("batch-rain"))
                assertEquals(0, changes.get())
                assertTrue(repository.undoHistory(parseEvent(org.json.JSONObject(eventFixture))))
                assertEquals("Other care writes still ask for a fresh feed", 1, changes.get())
            } finally { collector.cancel() }
        }
    }
    @Test fun rainUndoShowsItsListsWhileTheFeedRefreshIsHeld() {
        withRepository { repository, server, _ ->
            val restored = { view: String -> late(view, "plant-one", "plant-two") }
            val undone = java.util.concurrent.atomic.AtomicBoolean(false)
            HeldServer(server, held = { it.path == "/api/feed" && undone.get() }) { request ->
                rainServer(request, { rainRows(it) }) { path ->
                    if (path == "/api/task-batches/batch-rain/undo") { undone.set(true); undoResponse(""""batchId":"batch-rain","undoneAt":"2030-06-02T10:00:00Z"""", restored) } else null
                }
            }.use { held ->
                repository.refresh()
                val feed = rainFeed(server, repository)
                val undo = held.submit { feed.undoRain() }
                held.await { held.paths.count { it == "/api/feed" } == 2 }
                assertShows(repository, restored)
                held.release()
                assertTrue(undo.get(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }
    @Test fun aCompletionMadeWhileARainUndoIsInFlightSurvivesIt() {
        withRepository { repository, server, _ ->
            val third = org.json.JSONObject().put("plant_id", "plant-three").put("plant_name", "Third fern").put("status", "due").put("dueOn", "2030-06-02").put("lateDays", 0)
            val completed = org.json.JSONObject(eventFixture).put("id", "event-three").put("plant_id", "plant-three").put("due_on", "2030-06-02")
            val undo = "/api/task-batches/batch-rain/undo"
            HeldServer(server, held = { it.path == undo }) { request ->
                rainServer(request, { rainRows(it, third) }) { path -> when (path) {
                    undo -> undoResponse(""""batchId":"batch-rain","undoneAt":"2030-06-02T10:00:00Z"""") { view ->
                        withRows(view, *listOf("plant-one", "plant-two").map { org.json.JSONObject(lateWater).put("plant_id", it) }.toTypedArray(), third, due = "2030-05-27") }
                    "/api/plants/plant-three/tasks/water/complete" -> """{"event":$completed,"task":$nextWater}"""
                    else -> null
                } }
            }.use { held ->
                repository.refresh()
                val feed = rainFeed(server, repository)
                val rain = held.submit { feed.undoRain() }
                held.await { undo in held.paths }
                val row = repository.state.value.today!!.sections.flatMap { it.tasks }.single { it.plantId == "plant-three" }
                val complete = held.submit { repository.act(row, "complete") }
                runCatching { complete.get(1, java.util.concurrent.TimeUnit.SECONDS) }
                held.release()
                assertTrue(rain.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(complete.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("event-three", repository.state.value.today!!.sections.flatMap { it.tasks }.single { it.plantId == "plant-three" }.completedEvent?.id)
            }
        }
    }
    @Test fun undoingRainReloadsTheOpenPageOfAPlantInTheBatch() {
        withRepository { repository, server, _ ->
            val undone = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    rainServer(request, { rainRows(it) }) { path -> when (path) {
                        "/api/plants/plant-two" -> org.json.JSONObject(plantFixture).put("id", "plant-two").toString()
                        "/api/plants/plant-two/history" -> "[${org.json.JSONObject(rainEvents[1].toString()).apply { if (undone.get()) put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false) }}]"
                        "/api/plants/plant-two/tasks" -> """{"water":$lateWater,"customTasks":{}}"""
                        "/api/task-batches/batch-rain/undo" -> { undone.set(true); undoResponse(""""batchId":"batch-rain","undoneAt":"2030-06-02T10:05:00Z"""") { late(it, "plant-one", "plant-two") } }
                        else -> null
                    } }
            }
            repository.refresh()
            repository.openPlant("plant-two")
            assertTrue(rainFeed(server, repository).undoRain())
            assertEquals(listOf("rain-plant-two" to "2030-06-02T10:05:00Z"), repository.state.value.detail!!.history.map { it.id to it.undoneAt })
        }
    }
    @Test fun logoutWipesDataEvenWhenTheRevokeRequestFails() {
        val db = database()
        val cache = CareCache(db)
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            lateinit var repository: Repository
            val api = ApiClient(server.url("").toString().trimEnd('/'), credentials) { repository.wipe() }
            val disk = ThumbnailCache(java.io.File(RuntimeEnvironment.getApplication().cacheDir, "logout-thumbnails"))
            repository = Repository(api, cache, { true }, wipeThumbnails = disk::wipe)
            enqueueRefresh(server); repository.refresh()
            disk.put("synthetic-thumb", ByteArray(8))
            server.enqueue(MockResponse().setResponseCode(503))
            api.logout()
            assertNull(credentials.tokens)
            assertNull(repository.state.value.today)
            assertNull(cache.member())
            assertNull(cache.get("member-one", "today"))
            assertNull(disk.get("synthetic-thumb"))
        }
        db.close()
    }
    @Test fun memberSwitchWipesPreviousDataBeforeAnOfflineRefreshCanPublishIt() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            server.enqueue(MockResponse().setBody("""{"member":{"id":"member-two","role":"family"}}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            repository.refresh()
            assertEquals("member-two", repository.state.value.member!!.id)
            assertNull(repository.state.value.today)
            assertNull(cache.get("member-one", "today"))
        }
    }
    @Test fun connectivityLossBlocksWritesAndAConnectedTransportNeedsSuccessfulRefresh() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            repository.connectivityChanged(false)
            assertFalse(repository.act(task, "complete"))
            repository.connectivityChanged(true)
            assertTrue(repository.state.value.offline)
            enqueueRefresh(server); repository.refresh()
            assertFalse(repository.state.value.offline)
            assertEquals(10, server.requestCount)
        }
    }
    @Test fun sitterRefreshReadsAllowedTaskAndSharedPlantProjections() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, sitter = true); repository.refresh()
            assertEquals(Role.sitter, repository.state.value.member!!.role)
            assertTrue(repository.state.value.plants.isEmpty())
            assertEquals(listOf("/api/me", "/api/tasks?view=today", "/api/tasks?view=upcoming", "/api/plants", "/api/meta"), (1..5).map { server.takeRequest().path })
        }
    }
    @Test fun offlineBeforeFirstRefreshDoesNotInventATimestamp() {
        val db = database()
        MockWebServer().use { server ->
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, CareCache(db), { false })
            repository.refresh()
            assertEquals("Offline, not updated yet", repository.state.value.offlineBanner())
            assertEquals("Offline. Changes need a connection.", repository.state.value.todayBanner())
            assertTrue(repository.state.value.offline)
            assertFalse(repository.state.value.serverUnavailable)
            assertEquals(0, server.requestCount)
        }
        db.close()
    }
    @Test fun serverErrorsAndMalformedResponsesKeepCacheAndBlockWritesUntilSuccessfulRefresh() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            val before = repository.state.value.today
            for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("not json"))) {
                val timestamp = repository.state.value.lastUpdated
                server.enqueue(response); repository.refresh()
                assertFalse(repository.state.value.offline)
                assertTrue(repository.state.value.serverUnavailable)
                assertEquals(before, repository.state.value.today)
                assertEquals(timestamp, repository.state.value.lastUpdated)
                assertTrue(repository.state.value.serverBanner().startsWith("Fytorium isn't responding. Showing data from "))
                assertTrue(repository.state.value.todayBanner()!!.startsWith("Fytorium isn't responding. Showing tasks from "))
                val calls = server.requestCount
                assertFalse(repository.act(before!!.sections.single().tasks.single(), "complete"))
                assertEquals(calls, server.requestCount)
                assertNotNull(cache.get("member-one", "today"))
                enqueueRefresh(server); repository.refresh()
                assertFalse(repository.state.value.serverUnavailable)
                assertTrue(repository.state.value.writable)
            }
        }
    }
    @Test fun serverFailureBeforeFirstRefreshSaysNotUpdatedYet() {
        withRepository { repository, server, _ ->
            server.enqueue(MockResponse().setResponseCode(500)); repository.refresh()
            assertEquals("Fytorium isn't responding. Not updated yet", repository.state.value.serverBanner())
            assertEquals("Fytorium isn't responding. Pull down to try again.", repository.state.value.todayBanner())
            assertFalse(repository.state.value.offline)
            assertTrue(repository.state.value.serverUnavailable)
        }
    }
    @Test fun validatedNetworkTreatsDnsRefusedAndUnansweredConnectionsAsServerFailures() {
        MockWebServer().use { server ->
            server.start()
            val timeoutUrl = server.url("").toString().trimEnd('/')
            val closed = MockWebServer().apply { start() }
            val closedUrl = closed.url("").toString().trimEnd('/')
            closed.shutdown()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            for (url in listOf("http://fytorium-does-not-exist.invalid", closedUrl, timeoutUrl)) {
                val db = database()
                val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
                val repository = Repository(ApiClient(url, credentials) {}, CareCache(db), { true })
                repository.refresh()
                assertFalse(repository.state.value.offline)
                assertTrue(repository.state.value.serverUnavailable)
                db.close()
            }
        }
    }
    @Test fun tokenRefreshFailuresUseTheValidatedConnectivitySeamAndKeepTheSession() {
        for (validated in listOf(true, false)) {
            val db = database()
            MockWebServer().use { server ->
                server.start()
                val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
                var online = true
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        if (request.path == "/api/app/token") {
                            online = validated
                            return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        return MockResponse().setResponseCode(401)
                    }
                }
                val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, CareCache(db), { online })
                repository.refresh()
                assertEquals(!validated, repository.state.value.offline)
                assertEquals(validated, repository.state.value.serverUnavailable)
                assertEquals("synthetic-access", credentials.tokens!!.access)
                assertEquals("synthetic-refresh", credentials.tokens!!.refresh)
                assertEquals(2, server.requestCount)
            }
            db.close()
        }
        for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("malformed"),
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))) {
            withRepository { repository, server, _ ->
                server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(response)
                repository.refresh()
                assertFalse(repository.state.value.offline)
                assertTrue(repository.state.value.serverUnavailable)
            }
        }
    }

    private fun emptyToday(date: String = "2030-06-02") = """{"today":"$date","view":"today","settings":{"sort":"task","filter":"all","show_completed":false},
        "groups":{"water":[]},"sections":[{"key":"water","label":"Water","tasks":[]}]}"""
    // The list as the server returns it after a write: each event on the row of its plant and task.
    private fun listed(list: String, vararg events: org.json.JSONObject): String {
        val json = org.json.JSONObject(list)
        fun mark(rows: org.json.JSONArray) = rows.mapObjects { row ->
            events.firstOrNull { it.getString("plant_id") == row.getString("plant_id") && it.taskKey() == row.taskKey() }?.let { row.put("completed_event", it) }
        }
        json.getJSONArray("sections").mapObjects { mark(it.getJSONArray("tasks")) }
        val groups = json.getJSONObject("groups")
        groups.keys().forEach { mark(groups.getJSONArray(it)) }
        return json.toString()
    }
    private val nextWater = """{"status":"due","dueOn":"2030-06-09","intervalDays":7,"lateDays":0,"basedOn":[]}"""
    private fun fertilizeAndWaterToday(plants: List<String> = listOf("plant-one", "plant-two")): String {
        val list = org.json.JSONObject(emptyToday())
        val template = org.json.JSONObject(taskListFixture("today")).getJSONArray("sections").getJSONObject(0).getJSONArray("tasks").getJSONObject(0)
        val sections = listOf("fertilize" to "Fertilize", "water" to "Water").map { (type, label) ->
            val rows = org.json.JSONArray(plants.map { org.json.JSONObject(template.toString()).put("task_type", type).put("plant_id", it) })
            list.getJSONObject("groups").put(type, org.json.JSONArray(rows.toString()))
            org.json.JSONObject().put("key", type).put("label", label).put("tasks", rows)
        }
        return list.put("sections", org.json.JSONArray(sections)).toString()
    }
    @Test fun doneRowShowsTheServerRowAfterRefreshAndRestart() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":$nextWater}"""))
            assertTrue(repository.act(task, "complete"))
            // With Show others' completed tasks off, the server still lists your own completion.
            enqueueRefresh(server, today = taskListFixture("today", event = ""","completed_event":$eventFixture"""), upcoming = taskListFixture("upcoming", "2030-06-09"))
            repository.refresh()
            val kept = repository.state.value.today!!.sections.single().tasks.single()
            assertEquals("event-one", kept.completedEvent!!.id)
            assertEquals(listOf("undo"), kept.actions("2030-06-02", Member("member-one", Role.owner)))
            val restarted = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            restarted.restore()
            assertEquals("event-one", restarted.state.value.today!!.sections.single().tasks.single().completedEvent!!.id)
            enqueueRefresh(server, today = emptyToday("2030-06-03")); repository.refresh()
            assertTrue(repository.state.value.today!!.sections.single().tasks.isEmpty())
        }
    }
    @Test fun snoozeKeepsItsTodayRowWithAReceiptAndUpcomingListsThePostponedOccurrenceWithoutACheck() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            repeat(5) { server.takeRequest() }
            val task = repository.state.value.today!!.sections.single().tasks.single()
            val snoozed = org.json.JSONObject(eventFixture).put("id", "event-snooze").put("kind", "snoozed")
            val (today, upcoming) = postponedLists(snoozed, "2030-06-04")
            server.enqueue(MockResponse().setBody("""{"event":$snoozed,"task":{"status":"due","dueOn":"2030-06-04","intervalDays":7,"lateDays":0,"basedOn":[],"postponed":"snoozed"}}"""))
            server.enqueue(MockResponse().setBody(today))
            server.enqueue(MockResponse().setBody(upcoming))
            assertTrue(repository.act(task, "snooze"))
            fun todayRows() = repository.state.value.today!!.sections.flatMap { it.tasks }.map { it.completedEvent?.id to it.postponedUntil }
            assertEquals(listOf("event-snooze" to "2030-06-04"), todayRows())
            val receipt = repository.state.value.receipt!!
            assertEquals("Snoozed Synthetic fern until Tue 4 Jun", receipt.text)
            assertEquals("event-snooze", receipt.undo!!.id)
            assertEquals("/api/plants/plant-one/tasks/water/snooze", server.takeRequest().path)
            assertEquals("/api/tasks?view=today", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/tasks?view=upcoming", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            enqueueRefresh(server, today = today, upcoming = upcoming); repository.refresh()
            assertEquals(listOf("event-snooze" to "2030-06-04"), todayRows())
            val rows = repository.state.value.upcoming!!.sections.flatMap { it.tasks }
            assertEquals(listOf("event-snooze", null), rows.map { it.completedEvent?.id })
            assertEquals("Snoozed", rows.last().postponedLabel)
        }
    }
    @Test fun failedCareWriteShowsTheServerMessageWithoutUndo() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Only due or late tasks can be snoozed."}"""))
            assertFalse(repository.act(task, "snooze"))
            assertEquals("Only due or late tasks can be snoozed.", repository.state.value.receipt!!.text)
            assertNull(repository.state.value.receipt!!.undo)
            assertNull(repository.state.value.message)
            assertNull(repository.state.value.today!!.sections.single().tasks.single().completedEvent)
        }
    }
    @Test fun plantHistoryUndoClearsTodaysCompletedRow() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":$nextWater}"""))
            assertTrue(repository.act(task, "complete"))
            val event = repository.state.value.today!!.sections.single().tasks.single().completedEvent!!
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/api/care-events/event-one/undo" -> MockResponse().setBody(undoResponse(""""event":$eventFixture,"task":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[]}""") { taskListFixture(it) })
                    "/api/tasks" -> MockResponse().setBody(taskListFixture(request.requestUrl!!.queryParameter("view")!!))
                    "/api/plants" -> MockResponse().setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            assertTrue(repository.undoHistory(event))
            assertNull(repository.state.value.today!!.sections.single().tasks.single().completedEvent)
            assertEquals("Undid watering for Synthetic fern", repository.state.value.receipt!!.text)
        }
    }
    @Test fun undoingLiquidFertilizeBeforeTheWaterIsListedNamesBothFromTheWriteResponse() {
        withRepository { repository, server, cache ->
            val fertilize = org.json.JSONObject(eventFixture).put("task_type", "fertilize").put("due_on", "2030-06-09")
            val water = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-one").put("can_undo", false)
            val pending = { view: String -> withRows(view, org.json.JSONObject().put("task_type", "fertilize")) }
            undoServer(server, pending,
                "/api/plants/plant-one/tasks/fertilize/complete" to org.json.JSONObject().put("event", fertilize)
                    .put("linked_events", org.json.JSONArray().put(water)).put("task", org.json.JSONObject(nextWater)).toString(),
                "/api/care-events/event-one/undo" to undoResponse(""""event":$fertilize,"task":$nextWater""", pending))
            repository.refresh()
            fun row() = repository.state.value.today!!.sections.flatMap { it.tasks }.single()
            // The refresh after each write fails, so only the write response knows about the water.
            assertTrue(repository.act(row(), "complete"))
            assertTrue(cache.get("member-one", "today")!!.json.contains("event-water"))
            assertTrue(repository.act(row(), "undo"))
            assertEquals("Undid fertilizing and watering for Synthetic fern", repository.state.value.receipt!!.text)
            assertTrue(repository.act(row(), "complete"))
            assertTrue(repository.undoReceipt(repository.state.value.receipt!!))
            assertEquals("Undid fertilizing and watering for Synthetic fern", repository.state.value.receipt!!.text)
        }
    }
    @Test fun historyUndoOfLiquidFertilizeNamesTheLinkedWater() {
        withRepository { repository, server, _ ->
            val fertilize = org.json.JSONObject(eventFixture).put("task_type", "fertilize").put("occurred_on", "2030-05-20")
            val water = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-one").put("occurred_on", "2030-05-20")
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    "/api/tasks" -> taskListFixture(request.requestUrl!!.queryParameter("view")!!)
                    "/api/plants" -> "[$plantFixture]"
                    "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    "/api/plants/plant-one" -> plantFixture
                    "/api/plants/plant-one/history" -> "[$fertilize,$water]"
                    "/api/plants/plant-one/tasks" -> """{"water":$nextWater,"customTasks":{}}"""
                    "/api/care-events/event-one/undo" -> undoResponse(""""event":$fertilize,"task":$nextWater""") { taskListFixture(it) }
                    else -> "{}"
                })
            }
            repository.refresh()
            repository.openPlant("plant-one")
            assertTrue(repository.undoHistory(repository.state.value.detail!!.history.first()))
            assertEquals("Undid fertilizing and watering for Synthetic fern", repository.state.value.receipt!!.text)
        }
    }
    private val liquidFertilize = org.json.JSONObject(eventFixture).put("task_type", "fertilize")
    private val linkedWater = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-one")
    private fun completed(view: String, vararg events: org.json.JSONObject) =
        withRows(view, *events.map { org.json.JSONObject().put("task_type", it.getString("task_type")).put("completed_event", it) }.toTypedArray())
    private fun ServedLists.plantPage(vararg history: org.json.JSONObject) {
        responses["/api/plants/plant-one"] = plantFixture
        responses["/api/plants/plant-one/history"] = history.joinToString(",", "[", "]")
        responses["/api/plants/plant-one/tasks"] = """{"water":$nextWater,"customTasks":{}}"""
    }
    private fun ServedLists.undoes(event: org.json.JSONObject, vararg left: org.json.JSONObject) {
        responses["/api/care-events/${event.getString("id")}/undo"] =
            undoResponse(""""event":$event,"task":$nextWater""") { if (it == "today") completed(it, *left) else taskListFixture(it) }
    }
    @Test fun linkedWaterUndoneElsewhereIsNotNamedFromAnOpenedPlantsHistory() {
        withRepository { repository, server, _ -> ServedLists(server, today = completed("today", liquidFertilize, linkedWater)).use { served ->
            served.plantPage(liquidFertilize, linkedWater)
            served.undoes(liquidFertilize)
            repository.refresh()
            repository.openPlant("plant-one")
            served.today = completed("today", liquidFertilize)
            repository.refresh()
            val row = repository.state.value.today!!.sections.single().tasks.single()
            assertFalse(repository.state.value.linkedWater(row.completedEvent!!))
            assertTrue(repository.act(row, "undo"))
            assertEquals("Undid fertilizing for Synthetic fern", repository.state.value.receipt!!.text)
        } }
    }
    @Test fun undoingTheLinkedWaterHereStopsNamingItBeforeThePlantHistoryReloads() {
        withRepository { repository, server, _ -> ServedLists(server, today = completed("today", liquidFertilize, linkedWater)).use { served ->
            served.plantPage(liquidFertilize, linkedWater)
            served.undoes(linkedWater, liquidFertilize)
            served.undoes(liquidFertilize)
            repository.refresh()
            repository.openPlant("plant-one")
            served.responses.remove("/api/plants/plant-one/history")
            fun row(id: String) = repository.state.value.today!!.sections.single().tasks.single { it.completedEvent?.id == id }
            assertTrue(repository.act(row("event-water"), "undo"))
            assertEquals("Undid watering for Synthetic fern", repository.state.value.receipt!!.text)
            assertNull(repository.state.value.detail!!.history.single { it.id == "event-water" }.undoneAt)
            assertFalse(repository.state.value.linkedWater(row("event-one").completedEvent!!))
            assertTrue(repository.act(row("event-one"), "undo"))
            assertEquals("Undid fertilizing for Synthetic fern", repository.state.value.receipt!!.text)
        } }
    }
    @Test fun aKeptReceiptStopsNamingLinkedWaterOnceServerListsReplaceThePatchedRow() {
        withRepository { repository, server, _ -> ServedLists(server, today = withRows("today", org.json.JSONObject().put("task_type", "fertilize"))).use { served ->
            served.responses["/api/plants/plant-one/tasks/fertilize/complete"] = org.json.JSONObject().put("event", liquidFertilize)
                .put("linked_events", org.json.JSONArray().put(linkedWater)).put("task", org.json.JSONObject(nextWater)).toString()
            served.undoes(liquidFertilize)
            repository.refresh()
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "complete"))
            val receipt = repository.state.value.receipt!!
            assertEquals("Fertilized and watered Synthetic fern", receipt.text)
            // Another device undoes only the linked water before this one refreshes.
            served.today = completed("today", liquidFertilize)
            repository.refresh()
            assertTrue(repository.undoReceipt(receipt))
            assertEquals("Undid fertilizing for Synthetic fern", repository.state.value.receipt!!.text)
        } }
    }
    @Test fun aWriteBusiesOnlyItsRowAndQueuesTapsOnOtherRows() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val first = repository.state.value.today!!.sections.single().tasks.single()
            val second = first.copy(plantId = "plant-two", plantName = "Second fern")
            HeldServer(server, held = { true }) { request ->
                val plant = request.path!!.split("/")[3]
                MockResponse().setBody("""{"event":${org.json.JSONObject(eventFixture).put("plant_id", plant).put("id", "event-$plant")},"task":$nextWater}""")
            }.use { held ->
                val a = held.submit { repository.act(first, "complete") }
                held.await { server.requestCount == 6 }
                assertEquals(setOf(BusyKey.Row(first.occurrence)), repository.state.value.pending)
                assertTrue(repository.state.value.writable)
                assertFalse(repository.state.value.busy)
                val b = held.submit { repository.act(second, "complete") }
                held.await { repository.state.value.pending == setOf(BusyKey.Row(first.occurrence), BusyKey.Row(second.occurrence)) }
                assertEquals(6, server.requestCount)
                held.release()
                assertTrue(a.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(b.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(7, server.requestCount)
                assertTrue(repository.state.value.pending.isEmpty())
                assertEquals("Watered Second fern", repository.state.value.receipt!!.text)
            }
        }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
    private fun withRepository(block: (Repository, MockWebServer, CareCache) -> Unit) {
        val db = database(); val cache = CareCache(db)
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            block(Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, cache, { true }), server, cache)
        }
        db.close()
    }
    private fun enqueueRefresh(server: MockWebServer, sitter: Boolean = false, today: String = taskListFixture("today"),
                               upcoming: String = taskListFixture("upcoming"), plants: String = "[]") {
        server.enqueue(MockResponse().setBody("""{"member":{"id":"member-one","role":"${if (sitter) "sitter" else "owner"}"}}"""))
        server.enqueue(MockResponse().setBody(today))
        server.enqueue(MockResponse().setBody(upcoming))
        server.enqueue(MockResponse().setBody(plants))
        server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Oslo"}"""))
    }
    @Test fun successfulRefreshSurvivesOfflineAndNeverQueuesWrites() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val cache = CareCache(db)
            val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, cache, { true }, { 1_900_000_000_000L })
            server.enqueue(MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"},"household":{"id":"household-one","name":"Synthetic"}}"""))
            server.enqueue(MockResponse().setBody(taskListFixture("today")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Oslo"}"""))
            repository.refresh()
            assertEquals("Synthetic fern", repository.state.value.today!!.sections.single().tasks.single().plantName)
            assertFalse(repository.state.value.offline)
            server.enqueue(MockResponse().setResponseCode(503))
            repository.refresh()
            assertTrue(repository.state.value.serverUnavailable)
            assertFalse(repository.state.value.offline)
            assertEquals(1_900_000_000_000L, repository.state.value.lastUpdated)
            assertEquals("Synthetic fern", repository.state.value.today!!.sections.single().tasks.single().plantName)
            val requests = server.requestCount
            assertFalse(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "complete"))
            assertEquals(requests, server.requestCount)
        }
        db.close()
    }
}

val plantFixture = """{"id":"plant-one","display_name":"Synthetic fern","scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida","status":"active","site_name":"Synthetic site","cover_photo_id":null,"pot_type":"plastic","pot_size_cm":14,"soil_type":"all_purpose","site_id":"site-one","nickname":"Synthetic fern","cultivar":null,"birthday":null,"drainage":true,"plant_size":"small","growth_stage":"young","window_distance_cm":null,"grow_light":false,"near_ac":false,"near_heater":false,"fertilizer_type":"liquid","health":null,"household_id":"household-one","gbif_key":910001,"created_at":"2030-06-01T10:00:00Z","attributes_as_of":"2030-06-01","graveyard_on":null,"rank":"SPECIES"}"""
val eventFixture = """{"id":"event-one","plant_id":"plant-one","task_type":"water","custom_task_id":null,"kind":"completed","occurred_on":"2030-06-02","by_member":"member-one","by_name":"Synthetic member","batch_id":null,"undone_at":null,"can_undo":true,"due_on":"2030-06-01","postponed_until":null}"""

fun taskListFixture(view: String, due: String = "2030-06-01", event: String = "", basis: String = "[]"): String = """{
 "today":"2030-06-02","view":"$view","settings":{"sort":"task","filter":"all","show_completed":false},
 "groups":{},"sections":[{"key":"${if (view == "upcoming") maxOf(due, "2030-06-02") else "water"}","label":"${if (view == "upcoming") maxOf(due, "2030-06-02") else "Water"}","tasks":[{
 "status":"due","dueOn":"$due","intervalDays":7,"lateDays":1,"basedOn":$basis,
 "task_type":"water","custom_task_id":null,"plant_id":"plant-one","plant_name":"Synthetic fern","site_name":"Synthetic site"$event}]}]}"""
