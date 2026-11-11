package com.epaflix.fytorium

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.TextLayoutResult
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

internal fun inRow(plant: String, key: String = "water") = hasAnyAncestor(hasTestTag("task:$plant:$key"))

internal fun SemanticsNode.textLayout(): TextLayoutResult =
    mutableListOf<TextLayoutResult>().also { config[SemanticsActions.GetTextLayoutResult].action!!(it) }.single()

internal fun CareTask.postponed(kind: String, until: String) =
    copy(postponedUntil = until, completedEvent = parseEvent(JSONObject(eventFixture)).copy(id = "event-$plantId", plantId = plantId, kind = kind, dueOn = dueOn))

// A screen fed states directly sees this device's write or Undo only with the transitions the repository records for it: to the row with the same plant, task and due date.
internal fun CareState.written(next: CareState): CareState {
    fun rows(state: CareState) = listOfNotNull(state.today, state.upcoming).flatMap { list -> list.sections.flatMap { it.tasks } }
    fun CareTask.acted() = Triple(plantId, key, completedEvent?.dueOn ?: dueOn)
    val after = rows(next)
    return next.copy(transitions = transitions + rows(this).filter { row -> after.none { it.occurrence == row.occurrence } }.mapNotNull { row ->
        after.firstOrNull { it.acted() == row.acted() }?.let { row.occurrence to it.occurrence } })
}

internal fun withCache(memberId: String = "member-one", test: (CareCache, ApiClient, MockWebServer) -> Unit) {
    val db = androidx.room.Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
    val server = MockWebServer(); server.start()
    try {
        val cache = CareCache(db).apply { bind("""{"member":{"id":"$memberId","role":"owner"}}""") }
        test(cache, ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, server)
    } finally { server.shutdown(); db.close() }
}
