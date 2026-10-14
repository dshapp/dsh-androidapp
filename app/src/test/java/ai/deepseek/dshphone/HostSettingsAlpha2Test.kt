package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.toRpcArgs
import ai.deepseek.dshphone.state.HostSettingsStore
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Offline only: no RPC, credential writes, or real settings changes. */
class HostSettingsAlpha2Test {
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun schema(name: String): JsonElement = json(requireNotNull(javaClass.getResource("/settings-alpha2-$name-schema.json")).readText())
    private val entry = ConfigurableProviderView("test-route", "Test", "llm-pi-ai", listOf("providers", "route"))
    private fun namespace(revision: Long = 9) = SettingsNamespaceView(
        ns = entry.settingsNs, revision = revision, schema = schema("pi"),
        base = json("""{"providers":{"route":{"models":[{"id":"base"}]}}}"""),
        user = json("""{"providers":{"route":{"models":[{"id":"override","input":["text","image"],"compat":{"nested":true},"custom":[1,2]}],"headers":{"Authorization":"redacted"}}}}"""),
        value = json("""{"providers":{"route":{"models":[{"id":"override","input":["text","image"],"compat":{"nested":true},"custom":[1,2]}]}}}"""),
    )

    @Test fun actualSchemaTraversalAndInheritedDefaults() {
        val ns = SettingsNamespaceView("llm-deepseek", 1, schema = schema("deepseek"), value = json("""{"models":[{"id":"override"}]}"""))
        val inherited = inheritedProviderModels(ns, emptyList())
        assertEquals(2, inherited.size)
        assertTrue(inherited.all { (it as JsonObject)["id"] != JsonPrimitive("override") })
        assertEquals("array", settingsSchemaNodeAt(ns.schema, listOf("models"))?.get("type")?.jsonPrimitive?.content)
        assertNull(settingsSchemaNodeAt(ns.schema, listOf("missing")))
    }

    @Test fun emptyBaseIsNotReplacedBySchemaDefaultOrEffectiveOverride() {
        val ns = SettingsNamespaceView("llm-deepseek", 1, schema("deepseek"), value = json("""{"models":[{"id":"override"}]}"""), base = json("""{"models":[]}"""))
        assertEquals(JsonArray(emptyList()), inheritedProviderModels(ns, emptyList()))
    }

    @Test fun resetRestoresBaseAndSendsUnsetWithoutValue() {
        val opened = ProviderCatalogDraft.open(ProviderSettingsRow(entry, namespace()))
        val reset = opened.reset()
        assertFalse(reset.owned)
        assertEquals("base", reset.rows.single().id)
        val mutation = requireNotNull(reset.mutation())
        assertEquals(9L, mutation.expectedRevision)
        assertEquals(listOf("providers", "route", "models"), mutation.ops.single().path)
        assertEquals("unset", mutation.ops.single().op)
        assertFalse(mutation.toRpcArgs()["ops"]!!.jsonArray.single().jsonObject.containsKey("value"))
    }

    @Test fun resetAlreadyInheritedModelsIsNoOp() {
        val ns = namespace().copy(user = JsonObject(emptyMap()))
        assertNull(ProviderCatalogDraft.open(ProviderSettingsRow(entry, ns)).reset().mutation())
    }

    @Test fun editsPreserveUnknownMetadataAndInputFieldSpelling() {
        for (field in listOf("input", "inputModalities")) {
            val raw = json("""{"id":"m","name":"Before","$field":["text","image"],"reasoning":{"custom":1},"compat":{"nested":true},"future":[1,2]}""") as JsonObject
            val original = CatalogModelDraft(raw)
            assertEquals(raw, original.toModel())
            val changed = original.copy(name = "After").toModel()
            assertEquals(JsonObject(raw + ("name" to JsonPrimitive("After"))), changed)
            assertFalse(changed.containsKey(if (field == "input") "inputModalities" else "input"))
        }
    }

    @Test fun catalogMutationNeverRestatesHeadersOrOtherProviderFields() {
        val opened = ProviderCatalogDraft.open(ProviderSettingsRow(entry, namespace()))
        val changed = opened.copy(rows = opened.rows.map { it.copy(name = "Changed") }, touched = true)
        val mutation = requireNotNull(changed.mutation())
        assertEquals(1, mutation.ops.size)
        assertEquals(listOf("providers", "route", "models"), mutation.ops.single().path)
        assertFalse(mutation.toRpcArgs().toString().contains("Authorization"))
    }

    @Test fun broadcastUpdatesReadOnlyFactsButRetainsDraftAndOpenedRevision() {
        val store = HostSettingsStore()
        store.applyFacts(SettingsDescribeValue(true, namespaces = listOf(namespace())), listOf(entry), emptyMap())
        store.beginCatalog(store.rows.single())
        store.editCatalog(0) { it.copy(name = "Unsaved") }
        val draft = store.draft
        store.applyFacts(SettingsDescribeValue(false, namespaces = listOf(namespace(99))), listOf(entry), mapOf("TEST_ROUTE_API_KEY" to CredentialInfo(false, writable = false)))
        assertSame(draft, store.draft)
        assertEquals(9L, store.draft!!.expectedRevision)
        assertEquals("Unsaved", store.draft!!.rows.single().name)
        assertFalse(store.catalogWritable)
        assertFalse(store.rows.single().credential!!.writable)
    }

    @Test fun derivedRefIsNotAProviderAuthenticationDeclaration() {
        val row = ProviderSettingsRow(entry, namespace())
        assertEquals("TEST_ROUTE_API_KEY", row.credentialRef)
        assertTrue(row.derivedRef)
        assertNull(row.namedCredentialRef)
        val ns = namespace().copy(value = json("""{"providers":{"route":{"apiKeyEnv":"NAMED_REF"}}}"""))
        val named = ProviderSettingsRow(entry, ns, CredentialInfo(true, "environment", false))
        assertEquals("NAMED_REF", named.credentialRef)
        assertFalse(named.derivedRef)
        assertFalse(named.credential!!.writable)
        assertNull(ProviderSettingsRow(entry.copy(provider = "deepseek-account"), ns).credentialRef)
    }

    @Test fun blankCredentialRefUsesDerivedFacts() {
        val ns = namespace().copy(value = json("""{"providers":{"route":{"apiKeyEnv":"   "}}}"""))
        assertTrue(ProviderSettingsRow(entry, ns).derivedRef)
    }

    @Test fun secretRedactedCatalogIsNeverEditable() {
        val ns = namespace().copy(secrets = listOf(SettingsSecretView(listOf("providers", "route", "models", "0", "secret"), true)))
        assertFalse(ProviderSettingsRow(entry, ns).catalogSupported)
        assertTrue(ProviderSettingsRow(entry, namespace().copy(secrets = listOf(SettingsSecretView(listOf("providers", "route", "apiKey"), true)))).catalogSupported)
    }

    @Test fun duplicateIdsAndInvalidTokenLimitsAreRejected() {
        val draft = ProviderCatalogDraft.open(ProviderSettingsRow(entry, namespace()))
        assertNotNull(draft.copy(rows = draft.rows + draft.rows, touched = true).validationError())
        assertNotNull(draft.copy(rows = draft.rows.map { it.copy(maxTokens = "12.5") }, touched = true).validationError())
        assertNotNull(draft.copy(rows = draft.rows.map { it.copy(contextWindow = "-1") }, touched = true).validationError())
    }

    @Test fun pauseHidesSettingsButKeepsDraftAndRevision() {
        val store = HostSettingsStore()
        store.applyFacts(SettingsDescribeValue(true, namespaces = listOf(namespace())), listOf(entry), emptyMap())
        store.beginCatalog(store.rows.single())
        store.editCatalog(0) { it.copy(name = "Pause-safe") }
        val draft = store.draft
        store.visible = true
        store.suspendReads()
        assertFalse(store.visible)
        assertSame(draft, store.draft)
        assertEquals(9L, store.draft!!.expectedRevision)
    }

    @Test fun hostResetDropsOldDraftAndAllowsNewLowRevision() {
        val store = HostSettingsStore()
        store.applyFacts(SettingsDescribeValue(true, namespaces = listOf(namespace(999))), listOf(entry), emptyMap())
        store.beginCatalog(store.rows.single())
        store.reset()
        assertNull(store.draft)
        assertFalse(store.writable)
        store.applyFacts(SettingsDescribeValue(true, namespaces = listOf(namespace(1))), listOf(entry), emptyMap())
        store.beginCatalog(store.rows.single())
        assertEquals(1L, store.draft!!.expectedRevision)
    }
}
