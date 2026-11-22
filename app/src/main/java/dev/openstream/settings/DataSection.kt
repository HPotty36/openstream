package dev.openstream.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.openstream.library.ImportException
import dev.openstream.library.ItemList
import dev.openstream.library.Library
import dev.openstream.library.backupJson
import dev.openstream.library.rememberImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bringing subscriptions in from elsewhere, and moving this app's own data between devices. */
@Composable
fun DataSection(library: Library, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberImporter(library, onImported)
    var saved by remember { mutableStateOf<String?>(null) }
    var saveFailed by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            saveFailed = false
            saved = try {
                val json = backupJson(library.subscriptions(), library.items(ItemList.LATER), library.items(ItemList.HISTORY))
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                } ?: throw ImportException("파일을 만들 수 없습니다.")
                "백업 파일을 저장했습니다."
            } catch (e: Exception) {
                saveFailed = true
                e.message ?: "백업하지 못했습니다."
            }
        }
    }

    Task(
        title = "구독 가져오기",
        note = "테이크아웃 zip·csv, NewPipe json·zip, 이 앱의 백업 파일을 읽습니다.",
        button = if (importer.busy) "가져오는 중" else "파일 선택",
        enabled = !importer.busy,
        result = importer.message,
        failed = importer.failed,
        onClick = importer::pick,
    )
    Task(
        title = "백업 내보내기",
        note = "구독·나중에 보기·시청 기록을 파일 하나로 저장합니다. API 키는 빠집니다.",
        button = "파일로 저장",
        enabled = true,
        result = saved,
        failed = saveFailed,
        onClick = { save.launch("openstream-backup.json") },
    )
}

/** One thing to do with a file: what it is on the left, its button on the right, the outcome underneath. */
@Composable
private fun Task(title: String, note: String, button: String, enabled: Boolean, result: String?, failed: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result != null) {
                // A plain report, in the text's own color; only a failure is colored.
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.width(112.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(button, maxLines = 1)
        }
    }
}
