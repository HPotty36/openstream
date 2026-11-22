package dev.openstream.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

const val RELEASE_URL = "https://api.github.com/repos/HPotty36/openstream/releases/latest"
private const val DAY_MS = 24 * 60 * 60 * 1000L

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    /** `release` is set when the failure came after one was found, so it can be retried without checking again. */
    data class Failed(val release: Release?) : UpdateState
}

/** True while an update has been found and is not installed yet. */
val UpdateState.hasUpdate: Boolean
    get() = when (this) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing -> true
        is UpdateState.Failed -> release != null
        else -> false
    }

/**
 * Finds a newer release on GitHub, downloads its APK and hands the file to [install].
 * Knows nothing about Android, so all of it runs in unit tests.
 */
class Updater(
    private val installedVersion: String,
    /** False in debug builds, which install as a different app and must not fetch the release one. */
    val enabled: Boolean,
    /** Downloads go here. The updater owns the directory and clears it. */
    private val dir: File,
    /** Hands the APK to the system; throws when it cannot. */
    private val install: suspend (File) -> Unit,
    private val lastCheck: () -> Long,
    private val saveLastCheck: (Long) -> Unit,
    private val releaseUrl: String = RELEASE_URL,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val busy: Boolean
        get() = _state.value.let {
            it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing
        }

    /** Asks GitHub for the latest release. A silent check leaves no trace when it fails. */
    suspend fun check(silent: Boolean = false) {
        if (!enabled || busy) return
        val before = _state.value
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = withContext(Dispatchers.IO) { fetchRelease() }
            if (release != null && isNewer(release.version, installedVersion)) UpdateState.Available(release)
            else UpdateState.UpToDate
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            // A silent check that fails must not hide an update an earlier check already found.
            if (silent) before else UpdateState.Failed(null)
        }
    }

    /** The check made when the app starts: at most once a day, and quiet about failing. */
    suspend fun checkIfDue(now: Long) {
        if (!enabled || busy) return
        val last = lastCheck()
        if (last in 1..now && now - last < DAY_MS) return
        saveLastCheck(now)
        check(silent = true)
    }

    /** Downloads the release that was found, verifies it and passes it to the installer. */
    suspend fun downloadAndInstall() {
        if (!enabled) return
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            val apk = withContext(Dispatchers.IO) { download(release) }
            _state.value = UpdateState.Installing(release)
            install(apk)
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(release)
        }
    }

    /** The system refused the install, or the user backed out of it; backing out just offers the update again. */
    fun installFailed(cancelled: Boolean = false) {
        val s = _state.value
        if (s is UpdateState.Installing) {
            _state.value = if (cancelled) UpdateState.Available(s.release) else UpdateState.Failed(s.release)
        }
    }

    private fun fetchRelease(): Release? {
        val request = Request.Builder().url(releaseUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return parseRelease(response.body?.string().orEmpty())
        }
    }

    private fun download(release: Release): File {
        // The version comes from a tag; keep it from naming a path outside the directory.
        val name = "openstream-${release.version.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk"
        val apk = File(dir, name)
        val part = File(dir, "$name.part")
        dir.mkdirs()
        dir.listFiles()?.filter { it != apk }?.forEach { it.deleteRecursively() }
        try {
            if (!(apk.isFile && matches(apk, release))) {
                apk.delete()
                http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val source = response.body?.byteStream() ?: throw IOException("empty response")
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            total += read
                            if (release.apkSize > 0) {
                                val fraction = (total.toFloat() / release.apkSize).coerceIn(0f, 1f)
                                _state.value = UpdateState.Downloading(release, fraction)
                            }
                        }
                    }
                }
                if (!matches(part, release)) throw IOException("the download does not match the release")
                if (!part.renameTo(apk)) throw IOException("could not move the download into place")
            }
            return apk
        } catch (e: Throwable) {
            part.delete()
            apk.delete()
            throw e
        }
    }

    private fun matches(file: File, release: Release): Boolean {
        if (file.length() != release.apkSize) return false
        val expected = release.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }
}
