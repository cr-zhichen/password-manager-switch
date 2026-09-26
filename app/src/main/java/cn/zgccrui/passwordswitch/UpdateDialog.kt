package cn.zgccrui.passwordswitch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(state: UpdateState, onDismiss: () -> Unit, onRetry: () -> Unit, onDownload: (AppUpdate) -> Unit) {
    val available = state.available
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(available?.let { "发现新版本 ${it.version}" } ?: "检查更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                when {
                    state.checking -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在检查 GitHub Release…")
                    }
                    state.failed -> Text(state.message)
                    available != null -> Text("从本项目的 GitHub Release 下载 APK，按系统提示覆盖安装。")
                    else -> Text(state.message.ifBlank { "未发现更新版本" })
                }
            }
        },
        confirmButton = {
            when {
                state.checking -> TextButton(onDismiss) { Text("关闭") }
                state.failed -> TextButton(onRetry) { Text("重试") }
                available != null -> TextButton({ onDownload(available) }) { Text("下载更新") }
                else -> TextButton(onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            when {
                state.checking -> Unit
                state.failed -> TextButton(onDismiss) { Text("关闭") }
                available != null -> TextButton(onDismiss) { Text("稍后") }
            }
        },
    )
}
