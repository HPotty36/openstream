package dev.openstream.translate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Titles and names come by the screenful; gathering them for a moment turns twenty requests into one.
private const val GATHER_MS = 300L
private const val BATCH = 40

/** Translations kept on the device, so a title seen yesterday is not paid for again today. */
private class Store(context: Context) : SQLiteOpenHelper(context, "translations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE t(kind TEXT NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL, PRIMARY KEY(kind, source))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun find(kind: TextKind, texts: Collection<String>): Map<String, String> {
        val found = HashMap<String, String>()
        texts.chunked(200).forEach { part ->
            val marks = part.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT source, target FROM t WHERE kind=? AND source IN ($marks)",
                arrayOf(kind.name) + part.toTypedArray(),
            ).use { c -> while (c.moveToNext()) found[c.getString(0)] = c.getString(1) }
        }
        return found
    }

    fun save(kind: TextKind, pairs: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            pairs.forEach { (source, target) ->
                db.insertWithOnConflict(
                    "t", null,
                    ContentValues().apply { put("kind", kind.name); put("source", source); put("target", target) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/**
 * Shows video titles and channel names that are in neither Korean nor English already translated.
 * Screens ask for a text with [shown]; it answers with the original until the translation arrives.
 * Without a saved API key it does nothing.
 */
class AutoTranslate(context: Context, val translator: Translator, private val scope: CoroutineScope) {
    private val store = Store(context.applicationContext)
    private val done = mutableStateMapOf<Pair<TextKind, String>, String>()
    private val waiting = LinkedHashSet<Pair<TextKind, String>>()
    // Asked for once in this run, found or not; a failed request is not repeated until the app starts again.
    private val asked = HashSet<Pair<TextKind, String>>()
    private var gathering: Job? = null

    fun translationOf(text: String, kind: TextKind): String? = done[kind to text]

    fun want(text: String, kind: TextKind) {
        if (text.isBlank() || !needsAutoTranslation(text) || !translator.hasKey()) return
        val key = kind to text
        synchronized(waiting) {
            if (!asked.add(key)) return
            waiting.add(key)
            if (gathering?.isActive == true) return
            gathering = scope.launch {
                delay(GATHER_MS)
                flush()
            }
        }
    }

    private suspend fun flush() {
        val batch = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        batch.groupBy({ it.first }, { it.second }).forEach { (kind, texts) ->
            val stored = withContext(Dispatchers.IO) { store.find(kind, texts) }
            stored.forEach { (source, target) -> done[kind to source] = target }
            texts.filter { it !in stored }.chunked(BATCH).forEach { part ->
                try {
                    val translated = translator.translate(part, kind)
                    val pairs = part.zip(translated).filter { (from, to) -> to.isNotBlank() && to != from }.toMap()
                    withContext(Dispatchers.IO) { store.save(kind, pairs) }
                    pairs.forEach { (source, target) -> done[kind to source] = target }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A title stays in its own language; the comments' translate buttons report failures instead.
                }
            }
        }
        // Texts that arrived while this batch was out go in the next one.
        synchronized(waiting) {
            if (waiting.isNotEmpty()) gathering = scope.launch { delay(GATHER_MS); flush() }
        }
    }
}

val LocalAutoTranslate = staticCompositionLocalOf<AutoTranslate?> { null }

/** [text] as it should be shown: translated when it is in neither Korean nor English and a translation is ready. */
@Composable
fun autoTranslated(text: String, kind: TextKind = TextKind.TEXT): String {
    val auto = LocalAutoTranslate.current ?: return text
    LaunchedEffect(text, kind) { auto.want(text, kind) }
    return auto.translationOf(text, kind) ?: text
}
