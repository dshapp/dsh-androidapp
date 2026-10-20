package ai.deepseek.dshphone.model

import ai.deepseek.dshphone.net.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Raw objects retain future Host fields; optimistic writes use only the exact ScheduleRecord. */
private val recordFields = mapOf("after" to listOf("afterSeconds"), "at" to emptyList(), "every" to listOf("everySeconds"), "daily" to listOf("time", "timeZone"), "weekly" to listOf("time", "timeZone", "weekdays"), "cron" to listOf("expression", "timeZone"))
fun scheduleExpected(entry: JsonObject): JsonObject {
    val fields = recordFields[entry["kind"].str] ?: error("Unsupported ScheduleRecord kind")
    return JsonObject((listOf("id", "kind", "title", "prompt", "scheduledAt") + fields).associateWith { key -> entry[key]?.takeUnless { it is JsonNull } ?: error("Missing ScheduleRecord field: $key") })
}
data class ManagedAutomation(val raw: JsonObject) {
    val id get() = raw["id"].str.orEmpty()
    val sessionId get() = raw["sessionId"].str.orEmpty()
    val title get() = raw["title"].str.orEmpty()
    val active get() = raw["status"].str == "active"
    val key get() = sessionId + ":" + id
}
private val localTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 主机给的是 UTC 时间戳，按手机本地时区显示；解析不了就原样给出。 */
fun localTime(raw: String): String =
    runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).format(localTimeFormat) }.getOrDefault(raw)

fun managedAutomations(value: JsonElement): List<ManagedAutomation> = (value as? JsonArray ?: error("Invalid automation catalog")).map { ManagedAutomation(it as? JsonObject ?: error("Invalid automation")) }

/** Snapshot is immutable: broadcasts/conflicts must not replace the original expected record. */
data class AutomationDraft(
    val entry: ManagedAutomation,
    val expected: JsonObject = scheduleExpected(entry.raw),
    val title: String = entry.title,
    val prompt: String = entry.raw["prompt"].str.orEmpty(),
    val kind: String = entry.raw["kind"].str.orEmpty().let { if (it == "after") "at" else it },
    val timingChanged: Boolean = false,
    val at: String = entry.raw["scheduledAt"].str.orEmpty(),
    val seconds: String = entry.raw["everySeconds"].text ?: "3600",
    val time: String = entry.raw["time"].str ?: "09:00:00",
    val zone: String = entry.raw["timeZone"].str ?: "UTC",
    val weekdays: String = entry.raw["weekdays"].arr?.mapNotNull { it.text }?.joinToString(",") ?: "1,2,3,4,5",
    val expression: String = entry.raw["expression"].str ?: "0 9 * * *",
) {
    fun validate(): String? {
        if (title.trim().isEmpty() || title.trim().length > 120) return "title"
        if (prompt.trim().isEmpty()) return "prompt"
        if (!timingChanged) return null
        if (kind == "every") return if (seconds.matches(Regex("[0-9]+")) && seconds.toLongOrNull()?.let { it in 60..9007199254740991L } == true) null else "interval"
        if (kind == "at") return if (runCatching { java.time.Instant.parse(at) }.isSuccess && at.matches(Regex("[0-9]{4}-.*Z"))) null else "UTC timestamp"
        if (zone != "UTC" && !zone.matches(Regex("[A-Za-z_+-]+(/[A-Za-z0-9_+-]+)+"))) return "time zone"
        if (runCatching { ZoneId.of(zone) }.isFailure) return "time zone"
        if (kind == "cron") return if (validManagementCron(expression)) null else "cron"
        if (!time.matches(Regex("[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?")) || runCatching { LocalTime.parse(time) }.isFailure) return "time"
        if (kind == "weekly") {
            val days = weekdays.split(",").map { it.trim().toIntOrNull() }
            if (days.isEmpty() || days.any { it == null || it !in 1..7 } || days.distinct().size != days.size) return "weekdays"
        }
        return if (kind in listOf("daily", "weekly")) null else "kind"
    }
    fun timing(): JsonObject? {
        if (!timingChanged) return null
        require(validate() == null)
        return buildJsonObject {
            put("kind", kind)
            when (kind) {
                "at" -> put("at", at)
                "every" -> put("every_seconds", seconds.toLong())
                else -> put(kind, buildJsonObject {
                    put("time_zone", zone)
                    if (kind == "cron") put("expression", expression) else put("time", time)
                    if (kind == "weekly") put("weekdays", JsonArray(weekdays.split(",").map { JsonPrimitive(it.trim().toInt()) }.sortedBy { it.intOrNull }))
                })
            }
        }
    }
}
fun validManagementCron(expression: String): Boolean {
    if (expression != expression.trim()) return false
    val fields = expression.split(Regex("\\s+"))
    val bounds = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
    if (fields.size != 5) return false
    return fields.indices.all { i -> fields[i].split(",").all partCheck@ { part ->
        val match = Regex("(\\*|[0-9]+(?:-[0-9]+)?)(?:/([0-9]+))?").matchEntire(part) ?: return@partCheck false
        val step = match.groupValues[2]
        if (step.isNotEmpty() && (step.toLongOrNull()?.let { it in 1..9007199254740991L } != true)) return@partCheck false
        val selector = match.groupValues[1]
        if (selector == "*") true else {
            val range = selector.split("-").map { it.toIntOrNull() }
            range.all { it != null && it in bounds[i] } && (range.size == 2 || step.isEmpty()) && (range.size != 2 || range[0]!! <= range[1]!!)
        }
    } }
}
