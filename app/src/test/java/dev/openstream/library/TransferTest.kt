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
