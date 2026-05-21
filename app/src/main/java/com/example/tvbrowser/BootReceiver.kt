package com.example.tvbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)

            // Restaurar servicio de cámara si estaba activo
            val cameraFacing = prefs.getInt("last_camera_facing", -1)
            if (cameraFacing != -1) {
                val serviceIntent = Intent(context, CameraService::class.java).apply {
                    putExtra("FACING", cameraFacing)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("BootReceiver", "CameraService restaurado")
            }

            // Para ScreenCastService no podemos restaurarlo automáticamente sin la interacción del usuario
            // (el token de MediaProjection no es válido tras reinicio)
        }
    }
}
