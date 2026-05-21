package com.example.tvbrowser

import android.util.Log

class MotionDetector {
    private var previousYData: ByteArray? = null
    private val threshold = 0.05f // 5% de cambio en luminancia
    private var lastDetectionTime = 0L
    private val detectionCooldown = 2000L // 2 segundos entre detecciones

    fun detectMotion(yData: ByteArray, width: Int, height: Int): Boolean {
        if (previousYData == null) {
            previousYData = yData.copyOf()
            return false
        }

        var changedPixels = 0
        val totalPixels = width * height
        for (i in yData.indices) {
            if (i < previousYData!!.size && yData[i] != previousYData!![i]) {
                changedPixels++
            }
        }
        val diff = changedPixels.toFloat() / totalPixels.toFloat()

        previousYData = yData.copyOf()

        if (diff >= threshold) {
            val now = System.currentTimeMillis()
            if (now - lastDetectionTime >= detectionCooldown) {
                lastDetectionTime = now
                Log.d("MotionDetector", "Movimiento detectado! Cambio: ${"%.2f".format(diff * 100)}%")
                return true
            }
        }
        return false
    }

    fun reset() {
        previousYData = null
    }
}
