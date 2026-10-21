package com.epaflix.fytorium

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun taskDate(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

data class TodayPresentation(val sections: List<TaskSection>, val emptyTitle: String, val nextTask: String?)

fun CareState.todayPresentation(): TodayPresentation {
    val sections = today?.let { list ->
        if (list.settings.sort == "task" || list.groups.isEmpty()) list.sections.filter { it.tasks.isNotEmpty() }
        else list.groups.filterValues { it.isNotEmpty() }.map { (key, rows) -> TaskSection(key, rows.first().label, rows) }
            .sortedWith(compareBy<TaskSection> { taskNames.keys.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it.label })
    }.orEmpty()
    val emptyTitle = when {
        today == null -> if (busy) "Loading tasks…" else "Connect to load your tasks"
        plants.isEmpty() -> if (member?.role == Role.sitter) "No shared plants yet" else "Add your first plant"
        else -> "Nothing to do today"
    }
    val next = upcoming?.sections.orEmpty().flatMap { it.tasks }
        .filter { it.dueOn != null && (handled[it.plantId + ":" + it.key] ?: it.completedEvent) == null }
        .minByOrNull { it.dueOn!! }
    return TodayPresentation(sections, emptyTitle, next?.let { "Next: ${it.label} ${it.plantName} · ${taskDate(it.dueOn!!)}" })
}
