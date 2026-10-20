package ai.deepseek.dshphone

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import ai.deepseek.dshphone.state.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagementAlpha2Test {
    private fun obj(text: String) = DshJson.parseToJsonElement(text) as JsonObject
    private fun entry(kind: String = "every") = ManagedAutomation(obj("""{"id":"task","sessionId":"session","status":"active","kind":"$kind","title":"original","prompt":"old prompt","scheduledAt":"2026-10-14T02:00:00.000Z","everySeconds":60,"time":"09:00:00.000","timeZone":"Asia/Shanghai","weekdays":[1,3],"expression":"0 9 * * *","afterSeconds":60,"future":"retained"}"""))
    private class Fake : ManagementTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var update: JsonElement = JsonObject(emptyMap())
        var history: JsonElement = JsonObject(emptyMap())
        var failReads = false
        var catalog: JsonElement = JsonArray(emptyList())
        override suspend fun call(method: String, args: JsonObject): JsonElement {
            calls += method to args
            if (failReads && method == "schedule/catalog") throw IllegalStateException("unavailable")
            return when (method) {
                "schedule/catalog" -> catalog
                "schedule/update" -> update
                "schedule/history" -> history
                else -> JsonObject(emptyMap())
            }
        }
    }
    @Test fun expectedHasExactDiscriminatedRecordFields() {
        val expected = scheduleExpected(entry().raw)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "everySeconds"), expected.keys)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "time", "timeZone", "weekdays"), scheduleExpected(entry("weekly").raw).keys)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt"), scheduleExpected(entry("at").raw).keys)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "afterSeconds"), scheduleExpected(entry("after").raw).keys)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "time", "timeZone"), scheduleExpected(entry("daily").raw).keys)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "expression", "timeZone"), scheduleExpected(entry("cron").raw).keys)
    }
    @Test fun missingExpectedFieldsFailClosed() {
        assertThrows(IllegalStateException::class.java) { scheduleExpected(obj("""{"id":"x","kind":"weekly","title":"title","prompt":"p","scheduledAt":"2026-10-14T02:00:00Z"}""")) }
    }
    @Test fun updateUsesRequestAndOriginalExpectedWithoutReanchoring() = runBlocking {
        val fake = Fake(); val rpc = ManagementRpc(fake)
        val draft = AutomationDraft(entry()).copy(title = " new title ", prompt = " new prompt ")
        rpc.update(draft)
        val (method, args) = fake.calls.single()
        assertEquals("schedule/update", method)
        assertEquals(setOf("request"), args.keys)
        assertEquals(draft.expected, args["request"]["expected"])
        assertNull(args["request"]["change"])
        assertEquals("new title", args["request"]["title"].str)
    }
    @Test fun timingUsesSnakeCaseOnlyForInputAndValidates() = runBlocking {
        val fake = Fake(); val rpc = ManagementRpc(fake)
        val draft = AutomationDraft(entry("weekly")).copy(timingChanged = true, weekdays = "7,1", time = "08:00:00")
        rpc.update(draft)
        val change = fake.calls.single().second["request"]["change"]
        assertEquals("weekly", change["kind"].str)
        assertEquals("Asia/Shanghai", change["weekly"]["time_zone"].str)
        assertEquals(listOf(1,7), change["weekly"]["weekdays"].arr!!.map { it.int })
        assertNotNull(draft.copy(weekdays = "1,1").validate())
        assertNotNull(draft.copy(zone = "GMT+8").validate())
        assertNotNull(draft.copy(time = "24:00:00").validate())
        assertNotNull(AutomationDraft(entry()).copy(timingChanged = true, seconds = "59").validate())
    }
    @Test fun conflictAndRefreshRetainDraftAndExpected() = runBlocking {
        val fake = Fake().apply { update = obj("""{"id":"task","updated":false,"code":"schedule_conflict"}""") }
        val rpc = ManagementRpc(fake); val state = AutomationManagementState()
        fake.catalog = JsonArray(listOf(entry().raw)); state.refresh(rpc)
        state.edit(entry()); state.draft = state.draft!!.copy(prompt = "my draft")
        val before = state.draft
        state.save(rpc); state.refresh(rpc)
        assertEquals(before, state.draft)
        assertEquals("schedule_conflict", state.notice)
    }
    @Test fun historyUsesExclusiveMessageCursorAndHostNextBefore() = runBlocking {
        val fake = Fake().apply { history = obj("""{"id":"task","records":[{"messageId":"m2","scheduledAt":"t","deliveredAt":"d"}],"nextBefore":"m2","earlierRecordsUnavailable":true,"earlierRecordsPruned":true,"retention":{"days":30,"records":100}}""") }
        val rpc = ManagementRpc(fake); val state = AutomationManagementState()
        state.loadHistory(rpc, entry())
        fake.history = obj("""{"id":"task","records":[{"messageId":"m1","scheduledAt":"t","deliveredAt":"d"}],"retention":{"days":30,"records":100}}""")
        state.loadHistory(rpc, entry(), true)
        assertEquals("m2", fake.calls.last().second["request"]["before"].str)
        assertEquals(listOf("m2","m1"), state.history.map { it["messageId"].str })
        assertNull(state.nextBefore)
        assertEquals("schedule/history", fake.calls.last().first)
        assertEquals(20, fake.calls.last().second["request"]["limit"].int)
    }
    @Test fun cursorMissingPreservesHistoryAndStopsPaging() = runBlocking {
        val fake = Fake().apply { history = obj("""{"id":"task","records":[{"messageId":"m2"}],"nextBefore":"m2"}""") }
        val state = AutomationManagementState(); val rpc = ManagementRpc(fake)
        state.loadHistory(rpc, entry())
        fake.history = obj("""{"id":"task","code":"delivery_cursor_not_found"}""")
        state.loadHistory(rpc, entry(), true)
        assertEquals(1, state.history.size); assertNull(state.nextBefore); assertEquals("delivery_cursor_not_found", state.error)
    }
    @Test fun scheduleMethodsNeverInventCreate() = runBlocking {
        val fake = Fake(); val rpc = ManagementRpc(fake)
        rpc.catalog(); rpc.list("session"); rpc.delete(entry()); rpc.history(entry(), "message-id", 100)
        assertEquals(listOf("schedule/catalog", "schedule/list", "schedule/delete", "schedule/history"), fake.calls.map { it.first })
        assertEquals(obj("""{"request":{"sessionId":"session"}}"""), fake.calls[1].second)
        assertEquals(obj("""{"request":{"sessionId":"session","id":"task"}}"""), fake.calls[2].second)
    }
    @Test fun staleFactsDisableWritesButRetainDraft() = runBlocking {
        val fake = Fake().apply { catalog = JsonArray(listOf(entry().raw)) }; val rpc = ManagementRpc(fake)
        val automations = AutomationManagementState()
        automations.refresh(rpc); automations.edit(entry())
        val original = automations.draft
        fake.failReads = true; automations.refresh(rpc)
        assertFalse(automations.factsReady)
        automations.save(rpc); automations.delete(rpc, entry())
        assertEquals(original, automations.draft)
        assertFalse(fake.calls.any { it.first in listOf("schedule/update", "schedule/delete") })
    }
    @Test fun malformedEditorDoesNotThrowOrCreateDraft() = runBlocking {
        val malformed = ManagedAutomation(obj("""{"id":"task","sessionId":"session","kind":"weekly","status":"active"}"""))
        val fake = Fake().apply { catalog = JsonArray(listOf(malformed.raw)) }; val state = AutomationManagementState()
        state.refresh(ManagementRpc(fake)); state.edit(malformed)
        assertNull(state.draft); assertNotNull(state.error)
    }
    @Test fun cronMatchesNumericFiveFieldDialect() {
        assertTrue(validManagementCron("*/15 9-17 * * 1-5"))
        assertTrue(validManagementCron("0 9 * * 0,7"))
        listOf("@daily", "0 9 * * MON", "0 0 9 * * *", "60 9 * * *", "0 9 * * 1/2", "*/0 9 * * *", " 0 9 * * *").forEach { assertFalse(it, validManagementCron(it)) }
    }
}
