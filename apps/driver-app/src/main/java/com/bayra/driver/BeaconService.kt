package com.bayra.driver

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat

class BeaconService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "bayra_driver_v1"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Bayra Active Radar", NotificationManager.IMPORTANCE_LOW)
            val service = getSystemService(NotificationManager::class.java)
            service.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bayra Radar Active")
            .setContentText("Your location is being updated for Arba Minch passengers")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        try {
            startForeground(1001, notification)
        } catch (e: Exception) {
            // Handle Android 14 foreground service start exceptions
        }
        
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
