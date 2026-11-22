package dev.openstream.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The update row of the settings screen. Work is launched on [scope], which outlives the screen,
 * so a download carries on when the settings are closed.
 */
@Composable
fun UpdateSection(updater: Updater, installedVersion: String, scope: CoroutineScope) {
    val state by updater.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val note = MaterialTheme.typography.bodySmall

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("버전 $installedVersion")
            when (val st = state) {
                UpdateState.Idle, UpdateState.Checking -> {}
                UpdateState.UpToDate -> Text("최신 버전입니다.", style = note, color = colors.onSurfaceVariant)
                is UpdateState.Available -> {
                    Text("새 버전 ${st.release.version}", style = note, color = colors.onSurface)
                    if (st.release.notes.isNotEmpty()) {
                        Text(st.release.notes, style = note, color = colors.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
                is UpdateState.Downloading -> {
                    Text("${st.release.version} 받는 중 ${(st.fraction * 100).toInt()}%", style = note, color = colors.onSurfaceVariant)
                    LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                is UpdateState.Installing -> Text("설치 화면을 여는 중", style = note, color = colors.onSurfaceVariant)
                is UpdateState.Failed -> Text(
                    if (st.release != null) "받지 못했습니다." else "확인하지 못했습니다.",
                    style = note,
                    color = colors.error,
                )
            }
            if (!updater.enabled) Text("디버그 빌드는 업데이트를 받지 않습니다.", style = note, color = colors.onSurfaceVariant)
        }
        if (updater.enabled) {
            val width = Modifier.widthIn(min = 112.dp)
            when (val st = state) {
                is UpdateState.Available -> {
                    val megabytes = (st.release.apkSize / 1_048_576.0).roundToInt().coerceAtLeast(1)
                    Button(onClick = { scope.launch { updater.downloadAndInstall() } }, modifier = width) { Text("설치 ${megabytes}MB") }
                }
                is UpdateState.Failed -> OutlinedButton(
                    onClick = { scope.launch { if (st.release != null) updater.downloadAndInstall() else updater.check() } },
                    modifier = width,
                ) { Text("다시 시도") }
                is UpdateState.Downloading, is UpdateState.Installing -> {}
                else -> OutlinedButton(
                    onClick = { scope.launch { updater.check() } },
                    enabled = st != UpdateState.Checking,
                    modifier = width,
                ) { Text(if (st == UpdateState.Checking) "확인 중" else "업데이트 확인") }
            }
        }
    }
}
