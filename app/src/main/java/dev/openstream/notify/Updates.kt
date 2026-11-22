package dev.openstream.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.openstream.App
import dev.openstream.MainActivity
import dev.openstream.R
import dev.openstream.platform.ItemKind
import dev.openstream.platform.StreamItem
import java.util.concurrent.TimeUnit

private const val WORK = "updates"
private const val CHANNEL = "updates"
private const val DAY_MS = 24 * 60 * 60_000L

/** Turns the half-hourly look at the subscribed channels on or off. */
fun scheduleUpdates(context: Context, on: Boolean) {
    val work = WorkManager.getInstance(context)
    if (!on) {
        work.cancelUniqueWork(WORK)
        return
    }
    val request = PeriodicWorkRequestBuilder<UpdateWorker>(30, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

/** Refreshes the feed in the background and tells about what is new in it. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (!app.settings.notifyUpdates.value) return Result.success()
        val fresh = try {
            app.feed.refreshNow()
        } catch (e: Exception) {
            return Result.retry()
        }
        notifyUpdates(applicationContext, fresh)
        return Result.success()
    }
}

/** One notification per broadcast that started; uploads one by one when few, as a count when many. */
fun notifyUpdates(context: Context, fresh: List<StreamItem>) {
    val now = System.currentTimeMillis()
    // A channel that was just added brings its whole backlog; only what is recent is news. Shorts are left out.
    val items = fresh.filter { it.kind != ItemKind.SHORT && (it.publishedAt ?: 0) > now - DAY_MS }
    if (items.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "새 영상과 방송", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    fun post(id: Int, title: String, text: String) {
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    val (lives, videos) = items.partition { it.kind == ItemKind.LIVE }
    // The platform is named too: the same streamer often has a channel of the same name on two of them.
    lives.forEach { post("${it.platform.id}|${it.id}".hashCode(), "${it.channel.name} 방송 시작 · ${it.platform.label}", it.title) }
    if (videos.size <= 3) {
        videos.forEach { post("${it.platform.id}|${it.id}".hashCode(), "${it.channel.name} · ${it.platform.label}", it.title) }
    } else {
        post(1, "새 영상 ${videos.size}개", videos.map { it.channel.name }.distinct().take(5).joinToString())
    }
}
