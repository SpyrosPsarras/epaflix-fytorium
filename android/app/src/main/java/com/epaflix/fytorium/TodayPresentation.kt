package com.epaflix.fytorium

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun taskDate(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

data class TodayPresentation(val greeting: String, val summary: String, val sections: List<TaskSection>,
    val emptyTitle: String, val nextTask: String?, val tip: String)

fun CareState.todayPresentation(now: Instant = Instant.now()): TodayPresentation {
    val localTime = now.atZone(ZoneId.of(timezone ?: "UTC"))
    val hour = timezone?.let { localTime.hour }
    val tips = listOf("Check the soil before watering.", "Empty standing water from saucers.",
        "Turn pots for more even light.", "Check leaf undersides for pests.", "Use clean tools when pruning.")
    val tip = tips[Math.floorMod(localTime.toLocalDate().toEpochDay(), tips.size.toLong()).toInt()]
    val sections = today?.let { list ->
        if (list.settings.sort == "task" || list.groups.isEmpty()) list.sections.filter { it.tasks.isNotEmpty() }
        else list.groups.filterValues { it.isNotEmpty() }.map { (key, rows) -> TaskSection(key, rows.first().label, rows) }
            .sortedWith(compareBy<TaskSection> { taskNames.keys.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it.label })
    }.orEmpty()
    val pending = sections.flatMap { it.tasks }.filter { (handled[it.plantId + ":" + it.key] ?: it.completedEvent) == null }
    val counts = pending.groupBy { it.key }.values
    val first = counts.take(2).joinToString(", ") { "${it.size} ${it.first().actionLabel}" }
    val more = counts.drop(2).sumOf { it.size }
    val summary = if (pending.isEmpty()) "Nothing to do today" else "Today's tasks: $first" + if (more > 0) " + $more more" else ""
    val emptyTitle = when {
        today == null -> if (busy) "Loading tasks…" else "Connect to load your tasks"
        plants.isEmpty() -> if (member?.role == Role.sitter) "No shared plants yet" else "Add your first plant"
        else -> "Nothing to do today"
    }
    val next = upcoming?.sections.orEmpty().flatMap { it.tasks }
        .filter { it.dueOn != null && (handled[it.plantId + ":" + it.key] ?: it.completedEvent) == null }
        .minByOrNull { it.dueOn!! }
    return TodayPresentation(when (hour) {
        null -> "Hello, plant lover"
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }, summary, sections, emptyTitle, next?.let { "Next: ${it.label} ${it.plantName} · ${taskDate(it.dueOn!!)}" }, tip)
}
