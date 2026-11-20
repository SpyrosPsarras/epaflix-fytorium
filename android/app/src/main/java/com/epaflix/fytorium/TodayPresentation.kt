package com.epaflix.fytorium

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun dayLabel(date: String, today: String): String = when (val day = LocalDate.parse(date)) {
    LocalDate.parse(today) -> "Today"
    LocalDate.parse(today).plusDays(1) -> "Tomorrow"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
}

data class TodayPresentation(val sections: List<TaskSection>, val emptyTitle: String, val nextTask: String?, val emptyHint: String?)

// A held row takes a new occurrence only through this device's own write or Undo, so no row ever takes over another occurrence's controls.
internal fun TaskList.keepingLayoutOf(shown: TaskList?, transitions: Map<String, String> = emptyMap()): TaskList {
    if (shown == null || shown.settings != settings || shown.today != today) return this
    val left = sections.flatMap { it.tasks }.associateByTo(mutableMapOf()) { it.occurrence }
    val held = shown.sections.flatMap { it.tasks }
    val rows = held.mapTo(mutableListOf()) { left.remove(it.occurrence) }
    val paths = held.map { row -> generateSequence(transitions[row.occurrence]) { transitions[it] }.take(transitions.size).toList() }
    // A row one change away takes an occurrence before a row that reaches it through more of this device's changes.
    for (step in 0 until transitions.size) held.indices.forEach { i -> if (rows[i] == null) paths[i].getOrNull(step)?.let { rows[i] = left.remove(it) } }
    val next = rows.iterator()
    return copy(sections = shown.sections.map { section -> section.copy(tasks = section.tasks.map { shownRow ->
        next.next()?.copy(plantName = shownRow.plantName, siteName = shownRow.siteName, customTaskName = shownRow.customTaskName) ?: shownRow
    }) })
}

internal fun TaskSettings.filterLabel(): String? = mapOf("indoor" to "Indoor", "outdoor" to "Outdoor")[filter]
internal fun TaskList.hidingFilter(): String? = settings.filterLabel()?.lowercase()

fun CareState.todayPresentation(noPlants: Boolean = today != null && plants.isEmpty()): TodayPresentation {
    val sections = today?.sections.orEmpty().filter { it.tasks.isNotEmpty() }
    val sitter = member?.role == Role.sitter
    val emptyTitle = when {
        today == null -> if (offline || serverUnavailable) "Connect to load your tasks" else "Loading tasks…"
        noPlants -> if (sitter) "Nothing is shared with you yet" else "Add your first plant"
        else -> today.hidingFilter()?.let { "No $it tasks today" } ?: "Nothing to do today"
    }
    val next = upcoming?.sections.orEmpty().flatMap { it.tasks }
        .filter { it.dueOn != null && it.completedEvent == null }
        .minByOrNull { it.dueOn!! }
    return TodayPresentation(sections, emptyTitle, next?.let { "Next: ${it.label} ${it.plantName} · ${dayLabel(it.dueOn!!, upcoming!!.today)}" },
        "Ask the household owner to share plants.".takeIf { noPlants && sitter })
}
