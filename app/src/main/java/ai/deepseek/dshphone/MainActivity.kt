package ai.deepseek.dshphone

import ai.deepseek.dshphone.state.AttentionSignal
import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.ui.DshApp
import ai.deepseek.dshphone.ui.theme.DshTheme
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private lateinit var model: PhoneModel
    private lateinit var notifications: AgentNotifications

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[PhoneModel::class.java]
        notifications = AgentNotifications(this)
        model.notifier = { signal -> notifications.handle(signal, model) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val initialLink = intent?.data?.toString()
        setContent {
            var deepLink by remember0(initialLink)
            DshTheme { DshApp(model, deepLink) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.onForeground()
    }
}

/** 只是为了让 setContent 里的 deepLink 有个初值。 */
@androidx.compose.runtime.Composable
private fun remember0(value: String?) = androidx.compose.runtime.remember { mutableStateOf(value) }
