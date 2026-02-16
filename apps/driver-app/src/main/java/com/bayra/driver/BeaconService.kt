package com.bayra.driver
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BeaconService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chan = NotificationChannel("bayra_b", "Bayra Beacon", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        startForeground(1, NotificationCompat.Builder(this, "bayra_b").setContentTitle("Bayra Radar Active").setSmallIcon(android.R.drawable.ic_menu_mylocation).build())
        return START_STICKY
    }
    override fun onBind(i: Intent?): IBinder? = null
}
