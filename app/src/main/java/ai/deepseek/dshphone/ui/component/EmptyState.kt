package ai.deepseek.dshphone.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 空状态：图标 + 标题 + 描述（对应 iOS 的 ContentUnavailableView）。 */
@Composable
fun BoxScope.EmptyStateColumn(icon: ImageVector, title: String, description: String?) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(40.dp), MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
