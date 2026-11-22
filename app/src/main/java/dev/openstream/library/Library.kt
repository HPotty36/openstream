package dev.openstream.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The three lists of videos the app keeps. */
enum class ItemList(val id: String) { FEED("feed"), HISTORY("history"), LATER("later") }

/**
 * Everything an account would otherwise hold, kept on the device: subscriptions, the feed built
 * from them, what was watched and how far, and the videos saved for later.
 */
class Library(context: Context, name: String? = "library.db") : SQLiteOpenHelper(context, name, null, 1) {
    private val _changes = MutableStateFlow(0)
    /** Goes up after every write; screens read their lists again when it does. */
    val changes: StateFlow<Int> = _changes

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE subscriptions(
                platform TEXT NOT NULL, channel_id TEXT NOT NULL, name TEXT NOT NULL, avatar TEXT,
                added_at INTEGER NOT NULL, PRIMARY KEY(platform, channel_id))""",
        )
        // sort_at: publication time in the feed, time watched in the history, time saved in the later list.
        db.execSQL(
            """CREATE TABLE items(
                list TEXT NOT NULL, platform TEXT NOT NULL, item_id TEXT NOT NULL, kind TEXT NOT NULL,
                title TEXT NOT NULL, channel_id TEXT NOT NULL, channel_name TEXT NOT NULL, channel_avatar TEXT,
                thumb TEXT, duration INTEGER, published_at INTEGER, published_text TEXT,
                position_ms INTEGER NOT NULL DEFAULT 0, sort_at INTEGER NOT NULL,
                PRIMARY KEY(list, platform, item_id))""",
        )
        db.execSQL("CREATE INDEX items_sort ON items(list, sort_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { block(readableDatabase) }

    private suspend fun <T> write(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }.also { _changes.value++ }

    suspend fun subscriptions(): List<Channel> = read { db ->
        db.rawQuery("SELECT platform, channel_id, name, avatar FROM subscriptions ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Channel(platform(c.getString(0)), c.getString(1), c.getString(2), c.getString(3))) }
        }
    }

    suspend fun isSubscribed(channel: Channel): Boolean = read { db ->
        db.rawQuery("SELECT 1 FROM subscriptions WHERE platform=? AND channel_id=?", arrayOf(channel.platform.id, channel.id))
            .use { it.moveToFirst() }
    }

    /** Returns how many of [channels] were new. */
    suspend fun subscribe(channels: List<Channel>): Int = write { db ->
        val now = System.currentTimeMillis()
        channels.count { channel ->
            val values = ContentValues().apply {
                put("platform", channel.platform.id)
                put("channel_id", channel.id)
                put("name", channel.name)
                put("avatar", channel.avatarUrl)
                put("added_at", now)
            }
            db.insertWithOnConflict("subscriptions", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
    }

    /** For channels that came from an import file, which carries names but no pictures. */
    suspend fun setAvatar(channel: Channel, avatarUrl: String) = write { db ->
        db.update(
            "subscriptions", ContentValues().apply { put("avatar", avatarUrl) },
            "platform=? AND channel_id=?", arrayOf(channel.platform.id, channel.id),
        )
    }

    suspend fun unsubscribe(channel: Channel) = write { db ->
        val key = arrayOf(channel.platform.id, channel.id)
        db.delete("subscriptions", "platform=? AND channel_id=?", key)
        db.delete("items", "list='feed' AND platform=? AND channel_id=?", key)
    }

    /** Adds or replaces [items] in [list]. In the feed an item keeps the place its publication time gives it. */
    suspend fun put(list: ItemList, items: List<StreamItem>, positionMs: Long = 0) = write { db ->
        val now = System.currentTimeMillis()
        items.forEach { item ->
            val values = ContentValues().apply {
                put("list", list.id)
                put("platform", item.platform.id)
                put("item_id", item.id)
                put("kind", item.kind.name)
                put("title", item.title)
                put("channel_id", item.channel.id)
                put("channel_name", item.channel.name)
                put("channel_avatar", item.channel.avatarUrl)
                put("thumb", item.thumbnailUrl)
                put("duration", item.durationSeconds)
                put("published_at", item.publishedAt)
                put("published_text", item.publishedText)
                put("position_ms", positionMs)
                put("sort_at", if (list == ItemList.FEED) item.publishedAt ?: now else now)
            }
            db.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun remove(list: ItemList, item: StreamItem) = write { db ->
        db.delete("items", "list=? AND platform=? AND item_id=?", arrayOf(list.id, item.platform.id, item.id))
    }

    /** "platform|id" of everything in the feed, to tell afterwards what a refresh added. */
    suspend fun feedKeys(): Set<String> = read { db ->
        db.rawQuery("SELECT platform, item_id FROM items WHERE list='feed'", null).use { c ->
            buildSet { while (c.moveToNext()) add("${c.getString(0)}|${c.getString(1)}") }
        }
    }

    /** Replaces what the feed knows about live broadcasts, so one that has ended does not stay listed. */
    suspend fun refreshFeed(items: List<StreamItem>) {
        write { db -> db.delete("items", "list='feed' AND kind='LIVE'", null) }
        put(ItemList.FEED, items)
    }

    suspend fun clear(list: ItemList) = write { db -> db.delete("items", "list=?", arrayOf(list.id)) }

    suspend fun contains(list: ItemList, item: StreamItem): Boolean = read { db ->
        db.rawQuery("SELECT 1 FROM items WHERE list=? AND platform=? AND item_id=?", arrayOf(list.id, item.platform.id, item.id))
            .use { it.moveToFirst() }
    }

    /** Where the video was left, in milliseconds; 0 when it was never watched. */
    suspend fun position(item: StreamItem): Long = read { db ->
        db.rawQuery("SELECT position_ms FROM items WHERE list='history' AND platform=? AND item_id=?", arrayOf(item.platform.id, item.id))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    /** Newest first. [shorts]: null for everything, otherwise only shorts or only the rest. */
    suspend fun items(list: ItemList, shorts: Boolean? = null, limit: Int = 400): List<StreamItem> = read { db ->
        val kind = when (shorts) {
            null -> ""
            true -> " AND kind='SHORT'"
            false -> " AND kind<>'SHORT'"
        }
        db.rawQuery("SELECT * FROM items WHERE list=?$kind ORDER BY sort_at DESC LIMIT $limit", arrayOf(list.id)).use { c ->
            buildList { while (c.moveToNext()) add(item(c)) }
        }
    }

    private fun item(c: Cursor): StreamItem {
        fun text(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        fun number(name: String): Long? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getLong(it) }
        val platform = platform(text("platform")!!)
        return StreamItem(
            platform = platform,
            id = text("item_id")!!,
            kind = runCatching { ItemKind.valueOf(text("kind")!!) }.getOrDefault(ItemKind.VIDEO),
            title = text("title")!!,
            channel = Channel(platform, text("channel_id")!!, text("channel_name")!!, text("channel_avatar")),
            thumbnailUrl = text("thumb"),
            durationSeconds = number("duration"),
            publishedAt = number("published_at"),
            publishedText = text("published_text"),
        )
    }

    private fun platform(id: String): PlatformId = PlatformId.entries.firstOrNull { it.id == id } ?: PlatformId.YOUTUBE
}
