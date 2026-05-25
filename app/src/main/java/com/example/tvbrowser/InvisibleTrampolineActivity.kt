package com.example.tvbrowser

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class InvisibleTrampolineActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val facing = intent.getIntExtra("FACING", 0)
        Log.d("Trampoline", "Evadiendo restricción de fondo de Android 16. Iniciando cámara: $facing")

        val serviceIntent = Intent(this, CameraService::class.java).apply {
            putExtra("FACING", facing)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("Trampoline", "Fallo al heredar el contexto Foreground", e)
        }

        // Se cierra de inmediato para no entorpecer la pantalla
        finish()
    }
}
