package dev.openstream.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A published release that carries an APK. `version` is the tag without its leading "v". */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lowercase hex, when GitHub lists a digest for the file. */
    val sha256: String?,
)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** Reads GitHub's "latest release" answer. Null when it is not one, or it has no APK attached. */
fun parseRelease(json: String): Release? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val tag = root["tag_name"].text() ?: return null
    val apk = root["assets"]?.jsonArray?.map { it.jsonObject }
        ?.firstOrNull { it["name"].text()?.endsWith(".apk", ignoreCase = true) == true } ?: return null
    Release(
        version = tag.removePrefix("v").removePrefix("V"),
        notes = root["body"].text().orEmpty().trim(),
        apkUrl = apk["browser_download_url"].text() ?: return null,
        apkSize = (apk["size"] as? JsonPrimitive)?.longOrNull ?: return null,
        sha256 = apk["digest"].text()?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
    )
}.getOrNull()
