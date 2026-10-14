package ai.deepseek.dshphone.net

import ai.deepseek.dshphone.model.*
import kotlinx.serialization.json.*

private inline fun <reified T : Any> JsonElement.decodeSettings(): T =
    requireNotNull(decodeOrNull<T>()) { "Malformed settings response" }

/** alpha.2 settings reads and a narrow path mutation; intentionally no credential writes. */
suspend fun ApiClient.describeHostSettings(): SettingsDescribeValue = call("settings/describe").decodeSettings()
suspend fun ApiClient.listConfigurableProviders(): List<ConfigurableProviderView> = call("llm/listConfigurableProviders").decodeSettings()
suspend fun ApiClient.describeCredentialRefs(refs: List<String>): Map<String, CredentialInfo> =
    call("credentials/describe", buildJsonObject { put("refs", JsonArray(refs.map(::JsonPrimitive))) }).decodeSettings()
suspend fun ApiClient.readAgentPresetDocument(id: String): AgentPresetDocument =
    call("agentPresets/read", buildJsonObject { put("agentPreset", id) }).decodeSettings()

fun SettingsMutation.toRpcArgs(): JsonObject = buildJsonObject {
    put("ns", ns)
    put("expectedRevision", expectedRevision)
    put("ops", JsonArray(ops.map { op -> buildJsonObject {
        put("op", op.op); put("path", JsonArray(op.path.map(::JsonPrimitive)))
        if (op.op == "set") put("value", requireNotNull(op.value))
    } }))
}
suspend fun ApiClient.mutateHostSettings(mutation: SettingsMutation): SettingsNamespaceView =
    call("settings/mutate", mutation.toRpcArgs()).decodeSettings()
