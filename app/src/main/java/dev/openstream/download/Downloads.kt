package dev.openstream.download

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.withContext
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.openstream.platform.DownloadOption
import dev.openstream.platform.StreamItem
import dev.openstream.platform.youtube.USER_AGENT
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** One download, as the list shows it. */
class DownloadTask(val title: String, val label: String) {
    var progress by mutableFloatStateOf(0f)
    var finished by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** A file already saved on the phone. */
data class SavedFile(val uri: Uri, val title: String, val bytes: Long, val durationMs: Long?, val audioOnly: Boolean)

// The servers slow a connection down when it asks for a whole file at once, so it is fetched in pieces.
private const val PIECE = 8L shl 20

/**
 * Saves videos to the phone's Download/OpenStream folder. Runs while the app is alive;
 * a download in progress is lost when the system closes the app.
 */
class Downloads(private val context: Context, private val client: OkHttpClient, private val scope: CoroutineScope) {
    val tasks = mutableStateListOf<DownloadTask>()

    fun start(item: StreamItem, option: DownloadOption) {
        val task = DownloadTask(item.title, option.label)
        tasks.add(0, task)
        scope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "download").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val audio = File(dir, "$id.m4a")
            val video = File(dir, "$id.video")
            val joined = File(dir, "$id.mp4")
            try {
                val name = item.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(120).ifEmpty { "video" }
                val sound = option.audioUrl
                if (option.fileUrl != null) {
                    fetch(option.fileUrl, joined) { task.progress = it }
                    publish(joined, "$name.mp4", "video/mp4")
                } else if (sound == null) {
                    throw IllegalStateException("nothing to fetch")
                } else if (option.videoUrl == null) {
                    fetch(sound, audio) { task.progress = it }
                    publish(audio, "$name.m4a", "audio/mp4")
                } else {
                    fetch(sound, audio) { task.progress = it * 0.1f }
                    fetch(option.videoUrl, video) { task.progress = 0.1f + it * 0.85f }
                    join(video, audio, joined)
                    publish(joined, "$name.mp4", "video/mp4")
                }
                task.progress = 1f
                task.finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                task.error = if (e is IOException) "받지 못했습니다. 네트워크를 확인하세요." else "파일을 만들지 못했습니다."
            } finally {
                listOf(audio, video, joined).forEach { it.delete() }
            }
        }
    }

    private fun fetch(url: String, target: File, onProgress: (Float) -> Unit) {
        var offset = 0L
        var total = -1L
        val buffer = ByteArray(64 shl 10)
        target.outputStream().use { out ->
            while (total < 0 || offset < total) {
                val request = Request.Builder().url(url)
                    .header("Range", "bytes=$offset-${offset + PIECE - 1}")
                    .header("User-Agent", USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 206 && response.code != 200) throw IOException("HTTP ${response.code}")
                    if (total < 0) {
                        total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: response.body.contentLength()
                    }
                    val input = response.body.byteStream()
                    var got = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        got += n
                        if (total > 0) onProgress(((offset + got).toFloat() / total).coerceAtMost(1f))
                    }
                    if (got == 0L) throw IOException("empty piece")
                    offset += got
                    // A server that ignores the range sends everything in one answer.
                    if (response.code == 200) total = offset
                }
            }
        }
    }

    /** Puts the picture stream and the sound stream into one MP4 without re-encoding either. */
    private fun join(video: File, audio: File, target: File) {
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = listOf(video, audio).map { MediaExtractor().apply { setDataSource(it.path); selectTrack(0) } }
        try {
            val formats = extractors.map { it.getTrackFormat(0) }
            val tracks = formats.map(muxer::addTrack)
            muxer.start()
            val largest = formats.maxOf { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
            val buffer = ByteBuffer.allocate(maxOf(largest, 8 shl 20))
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { index, extractor ->
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(tracks[index], buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            extractors.forEach { it.release() }
            muxer.release()
        }
    }

    /** Goes up whenever a file is saved or deleted, so the list of saved files knows to read again. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * The files this app saved, newest first. Android shows an app only the files it made itself,
     * so files saved before a reinstall are still in the folder but not in this list.
     */
    suspend fun saved(): List<SavedFile> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext context.getExternalFilesDir("download")?.listFiles().orEmpty()
                .sortedByDescending { it.lastModified() }
                .map { SavedFile(Uri.fromFile(it), it.nameWithoutExtension, it.length(), null, it.extension == "m4a") }
        }
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.MIME_TYPE,
        )
        val found = mutableListOf<SavedFile>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Download/OpenStream%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { rows ->
            while (rows.moveToNext()) {
                found += SavedFile(
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rows.getLong(0)),
                    title = rows.getString(1).orEmpty().substringBeforeLast('.'),
                    bytes = rows.getLong(2),
                    durationMs = if (rows.isNull(3)) null else rows.getLong(3).takeIf { it > 0 },
                    audioOnly = rows.getString(4).orEmpty().startsWith("audio"),
                )
            }
        }
        found
    }

    suspend fun delete(file: SavedFile) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (file.uri.scheme == "file") File(file.uri.path!!).delete()
                else context.contentResolver.delete(file.uri, null, null)
            }
        }
        version++
    }

    private fun publish(file: File, name: String, mime: String) {
        try {
            store(file, name, mime)
        } finally {
            version++
        }
    }

    private fun store(file: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Before Android 10 the shared Download folder needs a permission; the app's own folder does not.
            file.copyTo(File(context.getExternalFilesDir("download"), name), overwrite = true)
            return
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/OpenStream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no place to save")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
