package dev.openstream.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.openstream.App
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands an APK to the system installer. Android then asks the user to confirm, and refuses the file
 * unless it is signed with the same key as the installed app.
 */
suspend fun installApk(context: Context, apk: File) = withContext(Dispatchers.IO) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        setSize(apk.length())
    }
    val sessionId = installer.createSession(params)
    try {
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, sessionId, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    } catch (e: Throwable) {
        runCatching { installer.abandonSession(sessionId) }
        throw e
    }
}

/** Receives what the system says about an install session started by [installApk]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as App).updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                val shown = confirm != null && runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!shown) updater.installFailed()
            }
            // On success the system replaces the running app, so there is nothing left to update here.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.installFailed(cancelled = true)
            else -> updater.installFailed()
        }
    }
}
