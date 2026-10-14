package ai.deepseek.dshphone.net

import ai.deepseek.dshphone.model.*
import kotlinx.serialization.json.*

fun interface ManagementTransport {
    suspend fun call(method: String, args: JsonObject): JsonElement
}
fun ApiClient.managementRpc() = ManagementRpc(ManagementTransport { method, args -> call(method, args) })

/** Exact alpha.2 named-argument contracts. No schedule/create or guessed namespace. */
class ManagementRpc(private val transport: ManagementTransport) {
    private suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap())) = transport.call(method, args)
    suspend fun bundles() = managedBundles(call("pluginManager/listBundles"))
    suspend fun plugins() = call("pluginManager/listPlugins")
    suspend fun exemptions() = call("pluginManager/listVersionExemptions")
    suspend fun inspect(spec: String) = call("pluginManager/inspect", buildJsonObject { put("spec", spec) })
    suspend fun bundleEnabled(name: String, enabled: Boolean) = call("pluginManager/setBundleEnabled", buildJsonObject { put("name", name); put("enabled", enabled) })
    suspend fun pluginEnabled(id: String, enabled: Boolean) = call("pluginManager/setPluginEnabled", buildJsonObject { put("id", id); put("enabled", enabled) })
    suspend fun removeBundle(name: String) = call("pluginManager/removeBundle", buildJsonObject { put("name", name) })
    suspend fun install(spec: String, requestId: String, enabled: Boolean, saveExact: Boolean = true) = call("pluginManager/installBundle", buildJsonObject {
        require(spec.isNotBlank() && requestId.isNotBlank())
        put("spec", spec)
        put("options", buildJsonObject { put("requestId", requestId); put("enabled", enabled); put("saveExact", saveExact) })
    })
    suspend fun waitForInstall(requestId: String) = call("pluginManager/waitForInstall", buildJsonObject { require(requestId.isNotBlank()); put("requestId", requestId) })
    suspend fun cancelInstall(requestId: String) = call("pluginManager/cancelInstall", buildJsonObject { require(requestId.isNotBlank()); put("requestId", requestId) })
    suspend fun versionExemption(packageVersion: String, runtimeVersion: String, enabled: Boolean, acceptRisk: Boolean = false): JsonElement {
        require(packageVersion.matches(Regex("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+@[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?")))
        require(runtimeVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?")))
        require(!enabled || acceptRisk)
        return call("pluginManager/setVersionExemption", buildJsonObject { put("packageVersion", packageVersion); put("runtimeVersion", runtimeVersion); put("enabled", enabled); if (enabled) put("acceptRisk", true) })
    }
    suspend fun catalog() = managedAutomations(call("schedule/catalog"))
    suspend fun list(sessionId: String) = call("schedule/list", request { put("sessionId", sessionId) })
    suspend fun update(draft: AutomationDraft): JsonElement {
        require(draft.validate() == null)
        return call("schedule/update", request {
            put("sessionId", draft.entry.sessionId); put("id", draft.entry.id); put("expected", draft.expected)
            put("title", draft.title.trim()); put("prompt", draft.prompt.trim()); draft.timing()?.let { put("change", it) }
        })
    }
    suspend fun delete(entry: ManagedAutomation) = call("schedule/delete", request { put("sessionId", entry.sessionId); put("id", entry.id) })
    suspend fun history(entry: ManagedAutomation, before: String? = null, limit: Int = 20): JsonElement {
        require(limit in 1..100 && (before == null || before.isNotBlank()))
        return call("schedule/history", request { put("sessionId", entry.sessionId); put("id", entry.id); put("limit", limit); before?.let { put("before", it) } })
    }
    private fun request(block: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("request", buildJsonObject(block)) }
}
