package ai.deepseek.dshphone.ui.screen

import ai.deepseek.dshphone.i18n.Strings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 未配对时的引导页：告诉用户去 Mac 端打开二维码。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onScan: () -> Unit) {
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(Strings.appName) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(56.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(Strings.onboardingTitle, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                Strings.onboardingBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onScan) { Text(Strings.scanQR) }
            TextButton(onClick = onScan) { Text(Strings.pastePairing) }
        }
    }
}
