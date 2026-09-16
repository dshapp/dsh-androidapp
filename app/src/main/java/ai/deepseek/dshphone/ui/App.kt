package ai.deepseek.dshphone.ui

import ai.deepseek.dshphone.state.PhoneModel
import ai.deepseek.dshphone.state.PairingPayload
import ai.deepseek.dshphone.ui.screen.ChatScreen
import ai.deepseek.dshphone.ui.screen.OnboardingScreen
import ai.deepseek.dshphone.ui.screen.PairingScreen
import ai.deepseek.dshphone.ui.screen.ServersScreen
import ai.deepseek.dshphone.ui.screen.SessionListScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun DshApp(model: PhoneModel, deepLink: String?) {
    val navController = rememberNavController()
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.connectCurrent() }

    LaunchedEffect(deepLink) {
        val payload = deepLink ?: return@LaunchedEffect
        val profile = model.profiles.add(PairingPayload.suggestedName(payload), payload) ?: return@LaunchedEffect
        scanning = false
        model.connect(profile)
    }

    NavHost(navController = navController, startDestination = "root") {
        composable("root") {
            if (model.profiles.profiles.isEmpty()) {
                OnboardingScreen(onScan = { scanning = true })
            } else {
                SessionListScreen(
                    model = model,
                    onOpenSession = { navController.navigate("chat/$it") },
                    onOpenServers = { navController.navigate("servers") },
                )
            }
        }
        composable("servers") {
            ServersScreen(
                model = model,
                onBack = { navController.popBackStack() },
                onScan = { scanning = true },
            )
        }
        composable("chat/{sessionId}") { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            ChatScreen(
                model = model,
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                onOpenSession = { navController.navigate("chat/$it") },
            )
        }
    }

    if (scanning) {
        PairingScreen(
            onDismiss = { scanning = false },
            onPaired = { name, payload ->
                val profile = model.profiles.add(name, payload)
                scanning = false
                if (profile != null) model.connect(profile)
            },
        )
    }
}
