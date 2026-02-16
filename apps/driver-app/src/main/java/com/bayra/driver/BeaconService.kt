package com.bayra.driver

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BeaconService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "bayra_beacon"
        val channel = NotificationChannel(channelId, "Driver Live Tracking", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bayra Beacon Active")
            .setContentText("Your location is being shared with the passenger")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
