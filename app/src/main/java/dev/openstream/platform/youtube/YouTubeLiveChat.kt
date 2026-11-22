package dev.openstream.platform.youtube

import dev.openstream.platform.ChatMessage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** One answer of the chat endpoint: the new messages, where to ask next, and how long to wait before asking. */
data class LiveChatPage(val messages: List<ChatMessage>, val next: String?, val waitMs: Long)

fun parseLiveChat(json: String): LiveChatPage {
    val chat = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
        ?.child("continuationContents")?.child("liveChatContinuation") ?: return LiveChatPage(emptyList(), null, 0)
    val messages = chat.list("actions").mapNotNull { action ->
        val message = action.child("addChatItemAction")?.child("item")?.child("liveChatTextMessageRenderer") ?: return@mapNotNull null
        // A message is a row of text pieces and emoji; an emoji is written as its shortcut, such as ":smile:".
        val text = message.child("message")?.list("runs").orEmpty().joinToString("") { run ->
            run.text("text") ?: run.child("emoji")?.let { emoji ->
                emoji.text("emojiId")?.takeIf { it.length <= 4 } ?: (emoji["shortcuts"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            }.orEmpty()
        }
        if (text.isBlank()) null
        else ChatMessage(message.text("id").orEmpty(), message.child("authorName")?.text("simpleText").orEmpty(), text)
    }
    // The continuation comes under one of several names; whichever is there has the same two fields.
    val continuation = chat.list("continuations").firstOrNull()?.values?.firstNotNullOfOrNull { it as? JsonObject }
    val wait = (continuation?.get("timeoutMs") as? JsonPrimitive)?.longOrNull ?: 5_000
    return LiveChatPage(messages, continuation?.text("continuation"), wait)
}

/** Reads a YouTube live chat by asking again and again from where the last answer ended. */
fun youtubeLiveChat(client: OkHttpClient, first: String): Flow<ChatMessage> = flow {
    var token: String? = first
    while (token != null) {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20260101.00.00")
                    put("hl", "ko")
                    put("gl", "KR")
                }
            }
            put("continuation", token)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?prettyPrint=false")
            .header("User-Agent", USER_AGENT).post(body).build()
        val page = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            parseLiveChat(response.body.string())
        }
        page.messages.forEach { emit(it) }
        token = page.next
        delay(page.waitMs.coerceIn(1_000, 8_000))
    }
}.flowOn(Dispatchers.IO)
