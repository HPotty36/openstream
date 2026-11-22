package dev.openstream.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.openstream.platform.Channel
import dev.openstream.platform.PlatformId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "bring my subscriptions in" action, wherever a screen offers it. */
class Importer internal constructor(private val open: () -> Unit) {
    var busy by mutableStateOf(false); internal set
    /** What the last import did, or why it failed; null before the first one. */
    var message by mutableStateOf<String?>(null); internal set
    var failed by mutableStateOf(false); internal set

    /** Opens the phone's file picker. */
    fun pick() = open()
}

/** A NewPipe "export database" zip keeps the subscriptions in an SQLite file; service 0 is YouTube. */
private fun newPipeDatabase(context: Context, bytes: ByteArray): List<Channel> {
    val file = File.createTempFile("import", ".db", context.cacheDir)
    try {
        file.writeBytes(bytes)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT url, name, avatar_url FROM subscriptions WHERE service_id = 0", null).use { rows ->
                val found = mutableListOf<Channel>()
                while (rows.moveToNext()) {
                    val url = youtubeChannelUrl(rows.getString(0).orEmpty()) ?: continue
                    found += Channel(PlatformId.YOUTUBE, url, rows.getString(1) ?: url, rows.getString(2))
                }
                return found
            }
        }
    } finally {
        file.delete()
    }
}

@Composable
fun rememberImporter(library: Library, onImported: () -> Unit): Importer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    lateinit var importer: Importer
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importer.busy = true
            importer.failed = false
            importer.message = try {
                val imported = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("파일을 열 수 없습니다.")
                    input.use { readImport(it) { bytes -> newPipeDatabase(context, bytes) } }
                }
                val added = library.subscribe(imported.subscriptions)
                // Oldest first, so the newest entry of the backup ends up on top again.
                if (imported.later.isNotEmpty()) library.put(ItemList.LATER, imported.later.reversed())
                if (imported.history.isNotEmpty()) library.put(ItemList.HISTORY, imported.history.reversed())
                onImported()
                val already = imported.subscriptions.size - added
                when {
                    already == 0 -> "채널 ${added}개를 구독했습니다."
                    added == 0 -> "새 채널이 없습니다. ${already}개 모두 이미 구독 중입니다."
                    else -> "채널 ${added}개를 구독했습니다. ${already}개는 이미 구독 중이었습니다."
                }
            } catch (e: ImportException) {
                importer.failed = true
                e.message
            } catch (e: Exception) {
                importer.failed = true
                "파일을 읽지 못했습니다."
            }
            importer.busy = false
        }
    }
    importer = remember { Importer { launcher.launch(arrayOf("*/*")) } }
    return importer
}
