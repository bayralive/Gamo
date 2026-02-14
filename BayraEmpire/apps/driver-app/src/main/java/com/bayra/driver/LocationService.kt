package com.bayra.driver

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase

class LocationService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rideId = intent?.getStringExtra("rideId") ?: return START_NOT_STICKY
        
        // 1. Create Notification Channel
        val channelId = "bayra_active"
        val channel = NotificationChannel(channelId, "Active Trip", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // 2. Build Notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bayra: Trip Active")
            .setContentText("Your location is being shared with the passenger")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // 3. Start GPS Beacon
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        
        try {
            fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
                override fun onLocationResult(p0: LocationResult) {
                    val loc = p0.lastLocation ?: return
                    FirebaseDatabase.getInstance().getReference("rides/$rideId")
                        .updateChildren(mapOf("dLat" to loc.latitude, "dLon" to loc.longitude))
                }
            }, mainLooper)
        } catch (e: SecurityException) { }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
