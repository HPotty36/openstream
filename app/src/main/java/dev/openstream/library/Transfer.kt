package dev.openstream.library

import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What a file brought in. The lists other than [subscriptions] are only in this app's own backups. */
data class Imported(
    val subscriptions: List<Channel>,
    val later: List<StreamItem> = emptyList(),
    val history: List<StreamItem> = emptyList(),
)

class ImportException(message: String) : Exception(message)

private val CHANNEL_ID = Regex("/channel/(UC[\\w-]{10,})")

/** The one spelling of a YouTube channel's address that the rest of the app uses, or null without a channel id. */
fun youtubeChannelUrl(url: String): String? =
    CHANNEL_ID.find(url)?.let { "https://www.youtube.com/channel/${it.groupValues[1]}" }

fun youtubeChannelId(url: String): String? = CHANNEL_ID.find(url)?.groupValues?.get(1)

/** Reads a Google Takeout subscriptions.csv, a NewPipe subscriptions export or this app's own backup. */
fun parseImport(text: String): Imported {
    val trimmed = text.trim().removePrefix("﻿").trim()
    if (trimmed.isEmpty()) throw ImportException("빈 파일입니다.")
    if (!trimmed.startsWith("{")) return Imported(parseTakeoutCsv(trimmed))
    val root = try {
        Json.parseToJsonElement(trimmed).jsonObject
    } catch (e: Exception) {
        throw ImportException("읽을 수 없는 파일입니다.")
    }
    return if (root["app"]?.jsonPrimitive?.contentOrNull == "openstream") parseBackup(root) else Imported(parseNewPipe(root))
}

// A subscriptions file is a few hundred kilobytes; anything far larger inside a zip is something else.
private const val ENTRY_LIMIT = 32 shl 20

/**
 * Reads a picked file: any of the files [parseImport] knows, or a zip holding them. A Google Takeout
 * zip names its subscriptions file in the account's language, and a NewPipe database export keeps
 * them in an SQLite file, so every csv, json and db inside is tried and whatever yields channels counts.
 * [readDatabase] turns such an SQLite file into channels; it needs Android, so the caller brings it.
 */
fun readImport(input: InputStream, readDatabase: (ByteArray) -> List<Channel>): Imported {
    val stream = BufferedInputStream(input)
    stream.mark(4)
    val head = ByteArray(2)
    val read = stream.read(head)
    stream.reset()
    val zipped = read == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    if (!zipped) return parseImport(stream.readBytes().decodeToString())

    val found = mutableListOf<Imported>()
    ZipInputStream(stream).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val kind = entry.name.substringAfterLast('.', "").lowercase()
            if (entry.isDirectory || kind !in setOf("csv", "json", "db")) continue
            val bytes = zip.readUpTo(ENTRY_LIMIT) ?: continue
            val part = runCatching {
                if (kind == "db") Imported(readDatabase(bytes)) else parseImport(bytes.decodeToString())
            }.getOrNull() ?: continue
            found += part
        }
    }
    val channels = found.flatMap { it.subscriptions }.distinctBy { it.platform to it.id }
    val later = found.flatMap { it.later }
    val history = found.flatMap { it.history }
    if (channels.isEmpty() && later.isEmpty() && history.isEmpty()) {
        throw ImportException("zip 파일 안에서 구독 목록을 찾지 못했습니다.")
    }
    return Imported(channels, later, history)
}

/** The whole entry, or null when it is larger than [limit]. */
private fun InputStream.readUpTo(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 shl 10)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
}

/** Three columns: channel id, channel address, channel title. The header's wording depends on the account's language. */
private fun parseTakeoutCsv(text: String): List<Channel> = text.lineSequence().drop(1).mapNotNull { line ->
    val cells = csvCells(line)
    if (cells.size < 3) return@mapNotNull null
    val url = youtubeChannelUrl("/channel/${cells[0].trim()}") ?: youtubeChannelUrl(cells[1]) ?: return@mapNotNull null
    Channel(PlatformId.YOUTUBE, url, cells[2].trim().ifEmpty { url })
}.toList().ifEmpty { throw ImportException("구독 채널을 찾지 못했습니다. Google 테이크아웃의 subscriptions.csv인지 확인하세요.") }

internal fun csvCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> { cells.add(cell.toString()); cell.clear() }
            else -> cell.append(ch)
        }
        i++
    }
    cells.add(cell.toString())
    return cells
}

/** NewPipe numbers its services; 0 is YouTube, the only one this app can take over. */
private fun parseNewPipe(root: JsonObject): List<Channel> {
    val list = root["subscriptions"] as? JsonArray ?: throw ImportException("구독 목록이 없는 파일입니다.")
    return list.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        if (entry["service_id"]?.jsonPrimitive?.intOrNull != 0) return@mapNotNull null
        val url = entry["url"]?.jsonPrimitive?.contentOrNull?.let(::youtubeChannelUrl) ?: return@mapNotNull null
        Channel(PlatformId.YOUTUBE, url, entry["name"]?.jsonPrimitive?.contentOrNull ?: url)
    }
}

fun backupJson(subscriptions: List<Channel>, later: List<StreamItem>, history: List<StreamItem>): String = buildJsonObject {
    put("app", "openstream")
    put("version", 1)
    put("subscriptions", buildJsonArray { subscriptions.forEach { add(channelJson(it)) } })
    put("later", buildJsonArray { later.forEach { add(itemJson(it)) } })
    put("history", buildJsonArray { history.forEach { add(itemJson(it)) } })
}.toString()

private fun channelJson(channel: Channel) = buildJsonObject {
    put("platform", channel.platform.id)
    put("id", channel.id)
    put("name", channel.name)
    channel.avatarUrl?.let { put("avatar", it) }
}

private fun itemJson(item: StreamItem) = buildJsonObject {
    put("platform", item.platform.id)
    put("id", item.id)
    put("kind", item.kind.name)
    put("title", item.title)
    put("channel", channelJson(item.channel))
    item.thumbnailUrl?.let { put("thumb", it) }
    item.durationSeconds?.let { put("duration", it) }
    item.publishedAt?.let { put("publishedAt", it) }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun platformOf(id: String?): PlatformId? = PlatformId.entries.firstOrNull { it.id == id }

private fun channelOf(json: JsonObject?): Channel? {
    val platform = platformOf(json?.text("platform")) ?: return null
    return Channel(platform, json?.text("id") ?: return null, json.text("name").orEmpty(), json.text("avatar"))
}

private fun itemOf(json: JsonObject): StreamItem? {
    val channel = channelOf(json["channel"] as? JsonObject) ?: return null
    return StreamItem(
        platform = platformOf(json.text("platform")) ?: return null,
        id = json.text("id") ?: return null,
        kind = runCatching { ItemKind.valueOf(json.text("kind").orEmpty()) }.getOrDefault(ItemKind.VIDEO),
        title = json.text("title").orEmpty(),
        channel = channel,
        thumbnailUrl = json.text("thumb"),
        durationSeconds = (json["duration"] as? JsonPrimitive)?.longOrNull,
        publishedAt = (json["publishedAt"] as? JsonPrimitive)?.longOrNull,
    )
}

private fun parseBackup(root: JsonObject): Imported {
    fun objects(key: String) = (root[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return Imported(
        subscriptions = objects("subscriptions").mapNotNull(::channelOf),
        later = objects("later").mapNotNull(::itemOf),
        history = objects("history").mapNotNull(::itemOf),
    )
}
