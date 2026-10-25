package com.eliyardo.omnia

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class OmniaApp : Application() {
    
    companion object {
        private const val TAG = "OmniaApp"
        const val NOTIFICATION_CHANNEL_ID = "omnia_service"
        const val CRASH_LOG_FILE = "last_crash.txt"
        const val MAX_CRASH_LOG_BYTES = 32768 // 32 KB cap to prevent unbounded file growth

        fun writeCrashLog(context: android.content.Context, log: String) {
            try {
                val crashFile = File(context.filesDir, CRASH_LOG_FILE)
                val capped = log.take(MAX_CRASH_LOG_BYTES)
                crashFile.writeText(capped)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing crash log", e)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        createNotificationChannel()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                Log.e(TAG, "FATAL CRASH on thread ${thread.name}: $stackTrace")

                val logContent = "Thread: ${thread.name}\nTime: ${System.currentTimeMillis()}\nError: ${throwable.message}\n\n$stackTrace"
                writeCrashLog(this@OmniaApp, logContent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }
}
