package ai.deepseek.dshphone.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SettingsSecretView(val path: List<String> = emptyList(), val set: Boolean = false)

@Serializable
data class SettingsNamespaceView(
    val ns: String,
    val revision: Long,
    val schema: JsonElement = JsonNull,
    val value: JsonElement = JsonNull,
    val base: JsonElement? = null,
    val user: JsonElement? = null,
    val autoGenerate: Boolean = false,
    val applies: String = "live",
    val secrets: List<SettingsSecretView> = emptyList(),
)

@Serializable
data class SettingsDescribeValue(
    val writable: Boolean = false,
    val hasDocument: Boolean = false,
    val namespaces: List<SettingsNamespaceView> = emptyList(),
)

@Serializable
data class ConfigurableProviderView(
    val provider: String,
    val displayName: String = "",
    val settingsNs: String,
    val settingsPath: List<String> = emptyList(),
    val declared: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class CredentialInfo(
    val configured: Boolean = false,
    val source: String? = null,
    val writable: Boolean = false,
)

@Serializable
data class AgentPresetDocument(val agentPreset: String, val content: String, val name: String? = null, val description: String? = null)

/** Never replace a redacted namespace. Only the named catalog subtree is written. */
@Serializable
data class SettingsPathOp(val op: String, val path: List<String>, val value: JsonElement? = null)

data class SettingsMutation(val ns: String, val expectedRevision: Long, val ops: List<SettingsPathOp>)

fun settingsValueAt(root: JsonElement?, path: List<String>): JsonElement? {
    var cursor = root
    for (segment in path) cursor = when (val node = cursor) {
        is JsonObject -> node[segment]
        is JsonArray -> segment.toIntOrNull()?.let { node.getOrNull(it) }
        else -> return null
    }
    return cursor
}

/** schemastery uses numeric references, not a JSON-schema properties tree. */
fun settingsSchemaNodeAt(schema: JsonElement, path: List<String>): JsonObject? {
    val envelope = schema as? JsonObject ?: return null
    val refs = envelope["refs"] as? JsonObject
    fun resolve(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> value.intOrNull?.let { refs?.get(it.toString()) as? JsonObject }
        else -> null
    }
    var node = resolve(envelope["uid"] ?: envelope) ?: return null
    for (segment in path) {
        node = resolve(when ((node["type"] as? JsonPrimitive)?.contentOrNull) {
            "object" -> (node["dict"] as? JsonObject)?.get(segment)
            "dict", "array" -> node["inner"]
            else -> null
        }) ?: return null
    }
    return node
}

fun inheritedProviderModels(namespace: SettingsNamespaceView, profilePath: List<String>): JsonArray {
    val path = profilePath + "models"
    val inherited = settingsValueAt(namespace.base, path)
        ?: (settingsSchemaNodeAt(namespace.schema, path)?.get("meta") as? JsonObject)?.get("default")
    return inherited as? JsonArray ?: JsonArray(emptyList())
}

fun deriveCredentialRef(provider: String): String = provider.replace(Regex("[^a-zA-Z0-9]+"), "_").uppercase(java.util.Locale.ROOT) + "_API_KEY"

data class ProviderSettingsRow(
    val entry: ConfigurableProviderView,
    val namespace: SettingsNamespaceView?,
    val credential: CredentialInfo? = null,
) {
    val namedCredentialRef: String? get() = if (entry.provider == "deepseek-account") null else
        (settingsValueAt(namespace?.value, entry.settingsPath + "apiKeyEnv") as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    val credentialRef: String? get() = if (entry.provider == "deepseek-account") null else namedCredentialRef ?: deriveCredentialRef(entry.provider)
    val derivedRef: Boolean get() = credentialRef != null && namedCredentialRef == null
    val modelsPath: List<String> get() = entry.settingsPath + "models"
    /** Public supported array only; secret-redacted arrays may never be restated. */
    val catalogSupported: Boolean get() {
        val ns = namespace ?: return false
        if (ns.secrets.any { secret -> secret.path.take(modelsPath.size) == modelsPath || modelsPath.take(secret.path.size) == secret.path }) return false
        val node = settingsSchemaNodeAt(ns.schema, modelsPath) ?: return false
        if ((node["type"] as? JsonPrimitive)?.contentOrNull != "array") return false
        val fields = settingsSchemaNodeAt(ns.schema, modelsPath + "0")?.get("dict") as? JsonObject ?: return false
        return "id" in fields && (settingsSchemaNodeAt(ns.schema, modelsPath + listOf("0", "id"))?.get("type") as? JsonPrimitive)?.contentOrNull == "string"
    }
}

/** raw preserves compat/reasoning/input/unknown metadata; only four public fields are owned. */
data class CatalogModelDraft(
    val raw: JsonObject,
    val id: String = (raw["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    val name: String = (raw["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    val contextWindow: String = (raw["contextWindow"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    val maxTokens: String = (raw["maxTokens"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
) {
    fun toModel(): JsonObject = JsonObject(raw.toMutableMap().apply {
        fun field(key: String, text: String, numeric: Boolean = false) {
            val before = (raw[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (text == before) return // untouched fields, including unusual host values, remain byte-faithful
            if (text.isBlank()) remove(key)
            else put(key, if (numeric) JsonPrimitive(text.trim().toLong()) else JsonPrimitive(text.trim()))
        }
        field("id", id); field("name", name)
        field("contextWindow", contextWindow, true); field("maxTokens", maxTokens, true)
    })
}

data class ProviderCatalogDraft(
    val entry: ConfigurableProviderView,
    val openedNamespace: SettingsNamespaceView,
    val rows: List<CatalogModelDraft>,
    val owned: Boolean,
    val touched: Boolean = false,
) {
    val expectedRevision: Long get() = openedNamespace.revision
    val path: List<String> get() = entry.settingsPath + "models"
    fun reset(): ProviderCatalogDraft = copy(
        rows = inheritedProviderModels(openedNamespace, entry.settingsPath).mapNotNull { model -> (model as? JsonObject)?.let { CatalogModelDraft(it) } },
        owned = false, touched = true,
    )
    fun validationError(): String? {
        if (!touched || !owned) return null
        if (rows.any { it.id.isBlank() }) return "Model id is required"
        if (rows.map { it.id.trim() }.distinct().size != rows.size) return "Duplicate model id"
        if (rows.any { row -> listOf(row.contextWindow, row.maxTokens).any { it.isNotBlank() && (it.trim().toLongOrNull()?.let { n -> n > 0 } != true) } }) return "Token limits must be positive integers"
        return null
    }
    fun mutation(): SettingsMutation? {
        if (!touched) return null
        require(validationError() == null) { validationError().orEmpty() }
        val original = settingsValueAt(openedNamespace.user, path)
        val op = if (!owned) {
            if (original == null) return null
            SettingsPathOp("unset", path)
        } else {
            val models = JsonArray(rows.map { it.toModel() })
            if (models == original) return null
            SettingsPathOp("set", path, models)
        }
        return SettingsMutation(entry.settingsNs, expectedRevision, listOf(op))
    }
    companion object {
        fun open(row: ProviderSettingsRow): ProviderCatalogDraft {
            require(row.catalogSupported)
            val ns = requireNotNull(row.namespace)
            val models = settingsValueAt(ns.value, row.modelsPath) as? JsonArray ?: JsonArray(emptyList())
            require(models.all { it is JsonObject }) { "Unsupported model catalog" }
            return ProviderCatalogDraft(row.entry, ns, models.map { CatalogModelDraft(it as JsonObject) }, settingsValueAt(ns.user, row.modelsPath) != null)
        }
    }
}
