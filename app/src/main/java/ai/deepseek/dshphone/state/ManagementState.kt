package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** Retain this above App navigation, keyed by API instance (never share pending ids across servers). */
class PluginManagementState {
    var factsReady by mutableStateOf(false); private set
    var bundles by mutableStateOf<List<ManagedBundle>>(emptyList()); private set
    var plugins by mutableStateOf<List<JsonElement>>(emptyList()); private set
    var exemptions by mutableStateOf<JsonElement?>(null); private set
    var result by mutableStateOf<JsonElement?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var requestId by mutableStateOf<String?>(null); private set
    var pendingSpec by mutableStateOf<String?>(null); private set
    var recovering by mutableStateOf(false); private set
    private val refreshLock = Mutex()
    suspend fun refresh(rpc: ManagementRpc) = refreshLock.withLock {
        try { val next = rpc.bundles(); val rows = rpc.plugins(); val ex = rpc.exemptions(); bundles = next; plugins = rows.arr ?: kotlin.error("Invalid plugin entries"); exemptions = ex; factsReady = true }
        catch (e: CancellationException) { throw e } catch (e: Exception) { factsReady = false; error = e.message }
    }
    suspend fun change(rpc: ManagementRpc, action: suspend () -> JsonElement) {
        if (busy || requestId != null || !factsReady) return
        busy = true; error = null
        try { result = action(); refresh(rpc) } catch (e: CancellationException) { throw e } catch (e: Exception) { factsReady = false; error = e.message } finally { busy = false }
    }
    suspend fun install(rpc: ManagementRpc, spec: String, enabled: Boolean) {
        if (busy || requestId != null || !factsReady) return
        val id = UUID.randomUUID().toString(); requestId = id; pendingSpec = spec; busy = true; error = null; result = null
        try { val outcome = rpc.install(spec, id, enabled); result = outcome; requestId = null; pendingSpec = null; refresh(rpc) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { factsReady = false; error = e.message /* original id remains, outcome is uncertain */ }
        finally { busy = false }
    }
    suspend fun recover(rpc: ManagementRpc) {
        val id = requestId ?: return
        if (recovering || busy) return
        recovering = true; error = null
        try {
            val outcome = rpc.waitForInstall(id)
            result = outcome
            // null does NOT establish success/cancellation. Keep the id and require explicit dismissal.
            if (outcome !is JsonNull) { requestId = null; pendingSpec = null }
            refresh(rpc)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { recovering = false }
    }
    suspend fun cancel(rpc: ManagementRpc) {
        val id = requestId ?: return
        if (recovering) return
        recovering = true
        try { val outcome = rpc.cancelInstall(id); result = outcome; if (outcome["status"].str == "cancelled") { requestId = null; pendingSpec = null }; refresh(rpc) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { recovering = false }
    }
    fun dismissUnknown() { if (factsReady && !busy && !recovering && result is JsonNull) { requestId = null; pendingSpec = null; result = null } }
}

class AutomationManagementState {
    var factsReady by mutableStateOf(false); private set
    var entries by mutableStateOf<List<ManagedAutomation>>(emptyList()); private set
    var draft by mutableStateOf<AutomationDraft?>(null)
    var historyEntry by mutableStateOf<ManagedAutomation?>(null); private set
    var history by mutableStateOf<List<JsonElement>>(emptyList()); private set
    var historyMeta by mutableStateOf<JsonElement?>(null); private set
    var nextBefore by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    private val refreshLock = Mutex()
    suspend fun refresh(rpc: ManagementRpc) = refreshLock.withLock {
        try { entries = rpc.catalog(); factsReady = true /* never replace an editor's expected or draft */ }
        catch (e: CancellationException) { throw e } catch (e: Exception) { factsReady = false; error = e.message }
    }
    fun edit(entry: ManagedAutomation) {
        if (!factsReady || !entry.active || busy || entries.none { it.key == entry.key && it.active }) return
        try { draft = AutomationDraft(entry); notice = null; error = null }
        catch (e: Exception) { error = e.message }
    }
    suspend fun save(rpc: ManagementRpc) {
        val observed = draft ?: return
        if (busy || !factsReady || observed.validate() != null || entries.none { it.key == observed.entry.key && it.active }) return
        busy = true; error = null; notice = null
        try {
            val outcome = rpc.update(observed)
            if (outcome["record"] is JsonObject) { draft = null; notice = "saved" }
            else { notice = outcome["code"].str ?: outcome["message"].str ?: outcome.toString() }
            refresh(rpc)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    }
    suspend fun delete(rpc: ManagementRpc, entry: ManagedAutomation) {
        if (busy || !factsReady || entries.none { it.key == entry.key && it.active }) return
        busy = true; error = null
        try { val outcome = rpc.delete(entry); notice = if (outcome["deleted"].bool == true) "deleted" else outcome["code"].str ?: outcome.toString(); refresh(rpc) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    }
    suspend fun loadHistory(rpc: ManagementRpc, entry: ManagedAutomation, more: Boolean = false) {
        if (busy) return
        val cursor = if (more) nextBefore ?: return else null
        busy = true; error = null
        if (!more) {
            if (historyEntry?.key != entry.key) { history = emptyList(); historyMeta = null }
            historyEntry = entry; nextBefore = null
        }
        try {
            val outcome = rpc.history(entry, cursor)
            val code = outcome["code"].str
            if (code != null) { error = code; nextBefore = null }
            else { history = (if (more) history else emptyList()) + outcome["records"].arr.orEmpty(); history = history.distinctBy { it["messageId"].str }; historyMeta = outcome; nextBefore = outcome["nextBefore"].str }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    }
    fun closeHistory() { if (!busy) { historyEntry = null; history = emptyList(); nextBefore = null; historyMeta = null } }
}
