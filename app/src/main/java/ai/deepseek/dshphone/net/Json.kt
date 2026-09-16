package ai.deepseek.dshphone.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 全局 JSON 编解码：宽松、忽略未知字段，与主机的可扩展 wire 约定一致。 */
val DshJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

// —— JsonElement 便捷读取（对应 Swift 的 AnyJSON 取值器）——

operator fun JsonElement?.get(key: String): JsonElement? =
    (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }

val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 字符串或数字都能读成文本，用于宽松字段。 */
val JsonElement?.text: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

val JsonElement?.int: Int?
    get() = (this as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content.toDoubleOrNull()?.toInt() else p.longOrNull?.toInt() ?: p.doubleOrNull?.toInt()
    }

val JsonElement?.dbl: Double?
    get() = (this as? JsonPrimitive)?.let { p -> if (p.isString) p.content.toDoubleOrNull() else p.doubleOrNull }

val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.booleanOrNull

val JsonElement?.arr: List<JsonElement>?
    get() = (this as? JsonArray)?.toList()

val JsonElement?.obj: Map<String, JsonElement>?
    get() = (this as? JsonObject)

/** 标量的展示文本（对应 PhoneJSONTree.scalar）。 */
fun jsonScalarText(value: JsonElement?): String = when (value) {
    null, JsonNull -> "null"
    is JsonPrimitive -> value.content
    is JsonArray -> "[]"
    is JsonObject -> "{}"
}

/** 缩进后的 JSON 文本；不是合法 JSON 就原样返回。 */
fun prettyJson(raw: String): String = runCatching {
    val element = DshJson.parseToJsonElement(raw)
    Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonElement.serializer(), element)
}.getOrDefault(raw)

inline fun <reified T> JsonElement.decodeOrNull(): T? =
    runCatching { DshJson.decodeFromJsonElement<T>(this) }.getOrNull()
