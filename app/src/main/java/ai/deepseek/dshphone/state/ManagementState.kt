package ai.deepseek.dshphone.state

import ai.deepseek.dshphone.model.*
import ai.deepseek.dshphone.net.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

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
    /** 结果提示交给 snackbar 展示一次后清掉，避免同一条提示反复弹出。 */
    fun consumeNotice() { notice = null }
    fun closeHistory() { if (!busy) { historyEntry = null; history = emptyList(); nextBefore = null; historyMeta = null } }
}
