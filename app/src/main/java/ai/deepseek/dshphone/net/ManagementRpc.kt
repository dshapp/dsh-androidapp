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
