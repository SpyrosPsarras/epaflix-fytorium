package com.epaflix.fytorium

import org.json.JSONArray
import org.json.JSONObject

enum class Role { owner, family, sitter }
data class Member(val id: String, val role: Role)
data class TaskSettings(val sort: String = "task", val filter: String = "all", val showCompleted: Boolean = false) {
    fun json() = JSONObject().put("sort", sort).put("filter", filter).put("show_completed", showCompleted)
}
data class Basis(val input: String, val value: Any?, val source: String, val adjustment: String,
                 val ruleId: String?, val fetchedAt: String?, val factor: Double?, val reason: String?)
data class CareEvent(val id: String, val plantId: String, val taskType: String?, val customTaskId: String?,
                     val customTaskName: String?, val kind: String, val occurredOn: String, val byMember: String?,
                     val byName: String, val batchId: String?, val undoneAt: String?, val previousPot: Boolean, val canUndo: Boolean) {
    val label get() = when (kind) { "completed" -> "Done"; "snoozed" -> "Snoozed"; else -> "Skipped" }
}
data class CareTask(val status: String, val dueOn: String?, val intervalDays: Int?, val lateDays: Int,
                    val basedOn: List<Basis>, val taskType: String?, val customTaskId: String?, val customTaskName: String?,
                    val plantId: String, val plantName: String, val siteName: String, val completedEvent: CareEvent?, val coverPhotoId: String? = null) {
    val key get() = customTaskId ?: taskType ?: error("Missing task identity")
    val label get() = taskLabel(taskType, customTaskName)
    val actionLabel get() = taskNames[taskType]?.second ?: customTaskName ?: "custom task"
    fun actions(today: String, member: Member, event: CareEvent? = completedEvent): List<String> {
        if (event != null) return if (event.canUndo) listOf("undo") else emptyList()
        if (status != "due") return emptyList()
        return buildList {
            add("complete")
            // Today.svelte compares the server's household date, not the phone clock.
            if (dueOn != null && dueOn <= today) add("snooze")
            add("skip")
            if (taskType == "repot" && member.role != Role.sitter) add("repot")
        }
    }
}
data class TaskSection(val key: String, val label: String, val tasks: List<CareTask>)
data class TaskList(val today: String, val view: String, val settings: TaskSettings,
                    val groups: Map<String, List<CareTask>>, val sections: List<TaskSection>)
data class Plant(val id: String, val displayName: String, val scientificName: String, val status: String,
                 val siteName: String?, val coverPhotoId: String?, val potType: String?, val potSizeCm: Int?, val soilType: String?,
                 val attributesJson: String = "{}")
data class PlantDetail(val plant: Plant, val history: List<CareEvent>, val tasks: Map<String, CareTask>,
                       val careSettingsJson: String? = null, val speciesChangesJson: String = "[]")
data class PotDetails(val potType: String, val potSizeCm: Int?, val soilType: String) {
    fun json() = JSONObject().put("pot_type", potType).put("pot_size_cm", potSizeCm ?: JSONObject.NULL).put("soil_type", soilType)
}

internal val taskNames = mapOf(
    "water" to ("Water" to "watering"), "fertilize" to ("Fertilize" to "fertilizing"), "mist" to ("Mist" to "misting"),
    "clean_leaves" to ("Clean leaves" to "leaf cleaning"), "remove_dead" to ("Remove dead flowers and leaves" to "removing dead flowers and leaves"),
    "cut_back" to ("Seasonal cut-back" to "seasonal cut-back"), "repot" to ("Repot" to "repotting")
)
fun taskLabel(type: String?, custom: String? = null): String = taskNames[type]?.first ?: custom ?: "Deleted custom task"
internal fun JSONObject.nullable(name: String): String? = if (isNull(name)) null else getString(name)
internal fun <T> JSONArray.mapObjects(parse: (JSONObject) -> T): List<T> = (0 until length()).map { parse(getJSONObject(it)) }
internal fun parseMember(json: String): Member = JSONObject(json).getJSONObject("member").let { Member(it.getString("id"), Role.valueOf(it.getString("role"))) }
internal fun parseEvent(j: JSONObject) = CareEvent(j.getString("id"), j.getString("plant_id"), j.nullable("task_type"),
    j.nullable("custom_task_id"), j.nullable("custom_task_name"), j.getString("kind"), j.getString("occurred_on"),
    j.nullable("by_member"), j.optString("by_name"), j.nullable("batch_id"), j.nullable("undone_at"), !j.isNull("previous_pot"), j.getBoolean("can_undo"))
internal fun parseTask(j: JSONObject) = CareTask(j.getString("status"), j.nullable("dueOn"),
    if (j.isNull("intervalDays")) null else j.getInt("intervalDays"), j.optInt("lateDays"),
    j.getJSONArray("basedOn").mapObjects { b -> Basis(b.getString("input"), b.opt("value").takeUnless { it == JSONObject.NULL },
        b.getString("source"), b.getString("adjustment"), b.nullable("ruleId"), b.nullable("fetchedAt"),
        if (b.isNull("factor")) null else b.getDouble("factor"), b.nullable("reason")) },
    j.nullable("task_type"), j.nullable("custom_task_id"), j.nullable("custom_task_name") ?: j.nullable("name"),
    j.optString("plant_id"), j.optString("plant_name"), j.optString("site_name"), j.optJSONObject("completed_event")?.let(::parseEvent), j.nullable("cover_photo_id"))
internal fun parseTaskList(json: String): TaskList {
    val j = JSONObject(json); val s = j.getJSONObject("settings"); val g = j.getJSONObject("groups")
    return TaskList(j.getString("today"), j.getString("view"), TaskSettings(s.getString("sort"), s.getString("filter"), s.getBoolean("show_completed")),
        g.keys().asSequence().associateWith { g.getJSONArray(it).mapObjects(::parseTask) },
        j.getJSONArray("sections").mapObjects { TaskSection(it.getString("key"), it.getString("label"), it.getJSONArray("tasks").mapObjects(::parseTask)) })
}
internal fun parsePlant(j: JSONObject) = Plant(j.getString("id"), j.getString("display_name"), j.getString("scientific_name"),
    j.getString("status"), j.nullable("site_name"), j.nullable("cover_photo_id"), j.nullable("pot_type"),
    if (j.isNull("pot_size_cm")) null else j.getInt("pot_size_cm"), j.nullable("soil_type"), j.toString())
internal fun parseSchedule(j: JSONObject): Map<String, CareTask> = buildMap {
    j.keys().asSequence().filter { it != "customTasks" }.forEach { key -> put(key, parseTask(j.getJSONObject(key))) }
    j.optJSONObject("customTasks")?.let { custom -> custom.keys().forEach { key -> put(key, parseTask(custom.getJSONObject(key))) } }
}
