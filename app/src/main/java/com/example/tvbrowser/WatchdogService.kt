package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class WatchdogService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
        val cameraFacing = prefs.getInt("last_camera_facing", -1)

        if (cameraFacing != -1) {
            val cameraIntent = Intent(this, CameraService::class.java).apply {
                putExtra("FACING", cameraFacing)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(cameraIntent)
                } else {
                    startService(cameraIntent)
                }
                Log.d("Watchdog", "CameraService reiniciado")
            } catch (e: Exception) {
                Log.e("Watchdog", "Error al reiniciar CameraService", e)
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
