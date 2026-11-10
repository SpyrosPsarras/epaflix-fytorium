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

internal fun TaskSettings.filterLabel(): String? = mapOf("indoor" to "Indoor", "outdoor" to "Outdoor")[filter]
internal fun TaskList.hidingFilter(): String? = settings.filterLabel()?.lowercase()

fun CareState.todayPresentation(): TodayPresentation {
    val sections = today?.let { list ->
        if (list.settings.sort == "task" || list.groups.isEmpty()) list.sections.filter { it.tasks.isNotEmpty() }
        else list.groups.filterValues { it.isNotEmpty() }.map { (key, rows) -> TaskSection(key, rows.first().label, rows) }
            .sortedWith(compareBy<TaskSection> { taskNames.keys.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it.label })
    }.orEmpty()
    val sitter = member?.role == Role.sitter
    val emptyTitle = when {
        today == null -> if (busy) "Loading tasks…" else "Connect to load your tasks"
        plants.isEmpty() -> if (sitter) "Nothing is shared with you yet" else "Add your first plant"
        else -> today.hidingFilter()?.let { "No $it tasks today" } ?: "Nothing to do today"
    }
    val next = upcoming?.sections.orEmpty().flatMap { it.tasks }
        .filter { it.dueOn != null && it.completedEvent == null }
        .minByOrNull { it.dueOn!! }
    return TodayPresentation(sections, emptyTitle, next?.let { "Next: ${it.label} ${it.plantName} · ${dayLabel(it.dueOn!!, upcoming!!.today)}" },
        "Ask the household owner to share plants.".takeIf { today != null && plants.isEmpty() && sitter })
}
