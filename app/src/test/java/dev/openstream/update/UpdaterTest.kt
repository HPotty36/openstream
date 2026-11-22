package dev.openstream.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdaterTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val apkBytes = ByteArray(4096) { (it % 251).toByte() }
    private val installed = mutableListOf<File>()
    private var installError: Exception? = null
    private var lastCheck = 0L
    private val dir by lazy { File(temp.root, "update") }

    @Before fun start() = server.start()
    @After fun stop() { runCatching { server.shutdown() } }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(tag: String = "v0.5.0", digestOf: ByteArray = apkBytes, withApk: Boolean = true): String {
        val asset = """{"name":"agentic-browser.apk","browser_download_url":"${server.url("/a.apk")}",""" +
            """"size":${apkBytes.size},"digest":"sha256:${sha256(digestOf)}"}"""
        return """{"tag_name":"$tag","body":"Notes","assets":[${if (withApk) asset else ""}]}"""
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("content-type", "application/json")
    private fun apk() = MockResponse().setBody(Buffer().write(apkBytes))

    private fun updater(enabled: Boolean = true) = Updater(
        installedVersion = "0.4.0",
        enabled = enabled,
        dir = dir,
        install = { file -> installError?.let { throw it }; installed += file },
        lastCheck = { lastCheck },
        saveLastCheck = { lastCheck = it },
        releaseUrl = server.url("/latest").toString(),
        // A dropped connection must fail here, not be retried into the next queued response.
        http = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    )

    private fun release() = parseRelease(releaseJson())!!
    private fun files() = dir.listFiles().orEmpty().map { it.name }

    @Test fun newerReleaseIsAvailable() = runBlocking {
        server.enqueue(json(releaseJson()))
        val updater = updater()
        updater.check()
        assertEquals(UpdateState.Available(release()), updater.state.value)
        assertEquals("application/vnd.github+json", server.takeRequest().getHeader("Accept"))
    }

    @Test fun sameVersionIsUpToDate() = runBlocking {
        server.enqueue(json(releaseJson(tag = "v0.4.0")))
        val updater = updater().also { it.check() }
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test fun tagThatIsNotAVersionIsUpToDate() = runBlocking {
        server.enqueue(json(releaseJson(tag = "nightly")))
        val updater = updater().also { it.check() }
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test fun releaseWithoutApkIsUpToDate() = runBlocking {
        server.enqueue(json(releaseJson(withApk = false)))
        val updater = updater().also { it.check() }
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test fun serverErrorFailsAManualCheck() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rate limit"}"""))
        val updater = updater().also { it.check() }
        assertEquals(UpdateState.Failed(null), updater.state.value)
    }

    @Test fun serverErrorIsSilentOnAnAutomaticCheck() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val updater = updater().also { it.check(silent = true) }
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test fun aFailedSilentCheckKeepsAnUpdateAlreadyFound() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(MockResponse().setResponseCode(500))
        val updater = updater()
        updater.check()
        updater.check(silent = true)
        assertEquals(UpdateState.Available(release()), updater.state.value)
    }

    @Test fun automaticCheckRunsOncePerDay() = runBlocking {
        repeat(2) { server.enqueue(json(releaseJson(tag = "v0.4.0"))) }
        val updater = updater()
        val now = 1_800_000_000_000
        updater.checkIfDue(now)
        updater.checkIfDue(now + 60_000)
        assertEquals(1, server.requestCount)
        updater.checkIfDue(now + 24 * 60 * 60 * 1000L)
        assertEquals(2, server.requestCount)
    }

    @Test fun aClockSetBackDoesNotStopChecksForGood() = runBlocking {
        server.enqueue(json(releaseJson(tag = "v0.4.0")))
        lastCheck = 2_000_000_000_000
        updater().checkIfDue(1_800_000_000_000)
        assertEquals(1, server.requestCount)
    }

    @Test fun disabledNeverTouchesTheNetwork() = runBlocking {
        val updater = updater(enabled = false)
        updater.check()
        updater.checkIfDue(1_800_000_000_000)
        updater.downloadAndInstall()
        assertEquals(0, server.requestCount)
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test fun downloadsVerifiesAndInstalls() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(apk())
        dir.mkdirs()
        File(dir, "old.apk").writeText("stale")
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        assertEquals(UpdateState.Installing(release()), updater.state.value)
        assertEquals("openstream-0.5.0.apk", installed.single().name)
        assertArrayEquals(apkBytes, installed.single().readBytes())
        assertEquals(listOf("openstream-0.5.0.apk"), files())
    }

    @Test fun aFinishedDownloadIsNotFetchedAgain() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(apk())
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        updater.installFailed()
        updater.downloadAndInstall()
        assertEquals(2, server.requestCount)
        assertEquals(2, installed.size)
    }

    @Test fun wrongChecksumIsNotInstalled() = runBlocking {
        server.enqueue(json(releaseJson(digestOf = byteArrayOf(1, 2, 3))))
        server.enqueue(apk())
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        assertEquals(UpdateState.Failed(parseRelease(releaseJson(digestOf = byteArrayOf(1, 2, 3)))), updater.state.value)
        assertTrue(installed.isEmpty())
        assertEquals(emptyList<String>(), files())
    }

    @Test fun truncatedDownloadFailsAndCleansUp() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(apk().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(apk())
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        assertEquals(UpdateState.Failed(release()), updater.state.value)
        assertEquals(emptyList<String>(), files())
        updater.downloadAndInstall()
        assertEquals(UpdateState.Installing(release()), updater.state.value)
        assertEquals(1, installed.size)
    }

    @Test fun missingApkFails() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(MockResponse().setResponseCode(404))
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        assertEquals(UpdateState.Failed(release()), updater.state.value)
    }

    @Test fun installFailureCanBeRetried() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(apk())
        installError = IllegalStateException("no installer")
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        assertEquals(UpdateState.Failed(release()), updater.state.value)
        installError = null
        updater.downloadAndInstall()
        assertEquals(UpdateState.Installing(release()), updater.state.value)
        updater.installFailed()
        assertEquals(UpdateState.Failed(release()), updater.state.value)
    }

    @Test fun backingOutOfTheInstallOffersItAgain() = runBlocking {
        server.enqueue(json(releaseJson()))
        server.enqueue(apk())
        val updater = updater()
        updater.check()
        updater.downloadAndInstall()
        updater.installFailed(cancelled = true)
        assertEquals(UpdateState.Available(release()), updater.state.value)
    }

    @Test fun hasUpdateCoversEveryWaitingState() {
        val r = Release("0.5.0", "", "https://x/a.apk", 1, null)
        listOf(UpdateState.Available(r), UpdateState.Downloading(r, 0.5f), UpdateState.Installing(r), UpdateState.Failed(r))
            .forEach { assertTrue(it.toString(), it.hasUpdate) }
        listOf(UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate, UpdateState.Failed(null))
            .forEach { assertFalse(it.toString(), it.hasUpdate) }
    }
}
