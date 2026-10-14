package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.net.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** Observe only an existing mux. Do not open another $events subscriber and compete for asks. */
@Composable
internal fun ManagementRefresh(visible: Boolean, api: ApiClient, events: Flow<MuxEvent>?, eventName: String, onActive: suspend () -> Unit = {}, refresh: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(refresh)
    val activate by rememberUpdatedState(onActive)
    LaunchedEffect(visible, api, events, owner) {
        if (!visible) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { activate() }
            launch { while (isActive) { latest(); delay(15_000) } }
            if (events != null) launch {
                events.collect { frame ->
                    if (frame is MuxEvent.Opened || (frame is MuxEvent.Item && frame.value["type"].str == "emit" && frame.value["event"].str == eventName)) latest()
                }
            }
        }
    }
}
