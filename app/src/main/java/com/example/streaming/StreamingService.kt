package com.example.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class StreamingService : Service() {

    inner class LocalBinder : Binder() {
        val service: StreamingService
            get() = this@StreamingService
    }

    private val binder = LocalBinder()
    private var isForegroundActive = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val duration = intent.getStringExtra(EXTRA_DURATION) ?: "00:00"
                startForegroundNotification(duration)
            }
            ACTION_UPDATE_NOTIFICATION -> {
                val duration = intent.getStringExtra(EXTRA_DURATION) ?: "00:00"
                updateNotification(duration)
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(duration: String) {
        createNotificationChannel()
        val notification = buildNotification(duration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundActive = true
    }

    fun updateNotification(duration: String) {
        if (isForegroundActive) {
            val notification = buildNotification(duration)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(duration: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VJStream")
            .setContentText("🔴 LIVE on YouTube — $duration")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Stream",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VJStream Live Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live broadcast status while streaming"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        isForegroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isForegroundActive = false
    }

    companion object {
        const val CHANNEL_ID = "vjstream_live_channel"
        const val NOTIFICATION_ID = 9901

        const val ACTION_START = "com.example.streaming.ACTION_START"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.streaming.ACTION_UPDATE"
        const val ACTION_STOP = "com.example.streaming.ACTION_STOP"
        const val EXTRA_DURATION = "extra_duration"

        fun start(context: Context, duration: String = "00:00") {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION, duration)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, duration: String) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                putExtra(EXTRA_DURATION, duration)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
