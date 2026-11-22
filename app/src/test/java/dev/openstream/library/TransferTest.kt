package dev.openstream.library

import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferTest {
    private val nasa = "https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ"

    @Test fun readsTakeoutCsvWhateverTheHeaderLanguage() {
        val csv = "﻿채널 ID,채널 URL,채널 제목\n" +
            "UCLA_DiR1FfKNvjuUpBHmylQ,http://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ,NASA\n" +
            "UCBR8-60-B28hp2BmDPdntcQ,http://www.youtube.com/channel/UCBR8-60-B28hp2BmDPdntcQ,\"Tube, You \"\"HQ\"\"\"\n\n"
        val channels = parseImport(csv).subscriptions
        assertEquals(listOf(nasa, "https://www.youtube.com/channel/UCBR8-60-B28hp2BmDPdntcQ"), channels.map { it.id })
        assertEquals(listOf("NASA", "Tube, You \"HQ\""), channels.map { it.name })
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): java.io.InputStream {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray().inputStream()
    }

    @Test fun findsTheSubscriptionsInsideATakeoutZipWhateverTheFileIsCalled() {
        val csv = "채널 ID,채널 URL,채널 제목\nUCLA_DiR1FfKNvjuUpBHmylQ,http://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ,NASA\n"
        val takeout = zip(
            "Takeout/보관 파일 둘러보기.html" to "<html>".toByteArray(),
            "Takeout/YouTube 및 YouTube Music/재생목록/재생목록.csv" to "재생목록 ID,제목\nPL123,나중에\n".toByteArray(),
            "Takeout/YouTube 및 YouTube Music/구독정보/구독정보.csv" to csv.toByteArray(),
        )
        assertEquals(listOf(Channel(PlatformId.YOUTUBE, nasa, "NASA")), readImport(takeout) { emptyList() }.subscriptions)
    }

    @Test fun handsADatabaseInsideAZipToTheDatabaseReader() {
        val export = zip("newpipe.db" to byteArrayOf(1, 2, 3), "preferences.json" to "{\"a\":1}".toByteArray())
        val channel = Channel(PlatformId.YOUTUBE, nasa, "NASA")
        assertEquals(listOf(channel), readImport(export) { bytes -> if (bytes.size == 3) listOf(channel) else emptyList() }.subscriptions)
    }

    @Test fun aZipWithoutSubscriptionsSaysSo() {
        assertThrows(ImportException::class.java) { readImport(zip("notes.txt" to "hi".toByteArray())) { emptyList() } }
    }

    @Test fun aPlainFileStillReadsThroughTheSameDoor() {
        val csv = "id,url,title\nUCLA_DiR1FfKNvjuUpBHmylQ,x,NASA\n"
        assertEquals(listOf(nasa), readImport(csv.byteInputStream()) { emptyList() }.subscriptions.map { it.id })
    }

    @Test fun readsNewPipeExportAndSkipsOtherServices() {
        val json = """{"app_version":"0.27","subscriptions":[
            {"service_id":0,"url":"$nasa","name":"NASA"},
            {"service_id":1,"url":"https://soundcloud.com/x","name":"X"}]}"""
        assertEquals(listOf(Channel(PlatformId.YOUTUBE, nasa, "NASA")), parseImport(json).subscriptions)
    }

    @Test fun ownBackupComesBackTheSame() {
        val channel = Channel(PlatformId.YOUTUBE, nasa, "NASA", "https://a/avatar.jpg")
        val item = StreamItem(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abc", ItemKind.SHORT, "제목", channel, "https://a/t.jpg", 61, 1700000000000)
        val back = parseImport(backupJson(listOf(channel), listOf(item), listOf(item)))
        assertEquals(Imported(listOf(channel), listOf(item), listOf(item)), back)
    }

    @Test fun refusesFilesItCannotUse() {
        assertThrows(ImportException::class.java) { parseImport("") }
        assertThrows(ImportException::class.java) { parseImport("just,some\ntext") }
        assertThrows(ImportException::class.java) { parseImport("{broken") }
    }
}
