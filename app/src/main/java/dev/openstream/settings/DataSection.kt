package dev.openstream.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.openstream.library.ImportException
import dev.openstream.library.ItemList
import dev.openstream.library.Library
import dev.openstream.library.backupJson
import dev.openstream.library.parseImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bringing subscriptions in from elsewhere, and moving this app's own data between devices. */
@Composable
fun DataSection(library: Library, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } ?: throw ImportException("파일을 열 수 없습니다.")
                val imported = parseImport(text)
                val added = library.subscribe(imported.subscriptions)
                // Oldest first, so the newest entry of the backup ends up on top again.
                if (imported.later.isNotEmpty()) library.put(ItemList.LATER, imported.later.reversed())
                if (imported.history.isNotEmpty()) library.put(ItemList.HISTORY, imported.history.reversed())
                onImported()
                "채널 ${imported.subscriptions.size}개 중 ${added}개를 새로 구독했습니다."
            } catch (e: ImportException) {
                e.message
            } catch (e: Exception) {
                "파일을 읽지 못했습니다."
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val json = backupJson(library.subscriptions(), library.items(ItemList.LATER), library.items(ItemList.HISTORY))
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                } ?: throw ImportException("파일을 만들 수 없습니다.")
                "백업 파일을 저장했습니다."
            } catch (e: Exception) {
                e.message ?: "백업하지 못했습니다."
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("가져오기") }
        OutlinedButton(onClick = { save.launch("openstream-backup.json") }) { Text("백업 내보내기") }
    }
    Text(
        message ?: "가져오기: Google 테이크아웃의 subscriptions.csv, NewPipe 구독 내보내기 파일, 이 앱의 백업 파일. 백업에는 구독, 나중에 볼 영상, 시청 기록이 들어가고 API 키는 들어가지 않습니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
