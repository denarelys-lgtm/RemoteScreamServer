package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class ScreenCastService : Service() {

    companion object {
        @Volatile
        var latestFrameProvider: FrameProvider? = null

        private var cipher: Cipher? = null
        private var secretKey: SecretKeySpec? = null

        fun updateCipherKey(key: SecretKeySpec) {
            secretKey = key
            cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val iv = ByteArray(16) { 0 }
            cipher?.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var outStream: DataOutputStream? = null
    private val isStreaming = AtomicBoolean(false)
    private var clientIp = "127.0.0.1"
    private var captureThread: Thread? = null
    private var reconnectThread: Thread? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    private var latestFrameBytes: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenCastService::WakeLock")
        wakeLock.acquire(10 * 60 * 1000L)

        val prefs = getSharedPreferences("server_prefs", MODE_PRIVATE)
        clientIp = prefs.getString("client_ip", "127.0.0.1") ?: "127.0.0.1"

        latestFrameProvider = object : FrameProvider {
            override fun getLatestFrame(): ByteArray? = latestFrameBytes
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")
        startForeground(2, createNotification())
        if (data != null && mediaProjection == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
            setupCapture()
            startStreamingLoop()
            startReconnectLoop()
        }
        return START_STICKY
    }

    private fun setupCapture() {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = 0.6f
        val width = (metrics.widthPixels * scale).toInt()
        val height = (metrics.heightPixels * scale).toInt()

        imageReader?.close()
        virtualDisplay?.release()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 5)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Cast", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d("ScreenCast", "VirtualDisplay creado: ${width}x${height}")
    }

    private fun startStreamingLoop() {
        if (isStreaming.get()) return
        isStreaming.set(true)
        captureThread = thread(start = true) {
            while (isStreaming.get() && imageReader != null) {
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                        image.close()
                    } else {
                        Thread.sleep(10)
                    }
                } catch (e: Exception) {
                    Thread.sleep(500)
                }
            }
        }
    }

    private fun startReconnectLoop() {
        reconnectThread = thread(start = true) {
            while (isStreaming.get()) {
                if (outStream == null) {
                    try {
                        val socket = Socket(clientIp, 9000)
                        socket.tcpNoDelay = true
                        outStream = DataOutputStream(socket.getOutputStream())
                    } catch (e: Exception) {
                        Thread.sleep(5000)
                    }
                } else {
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun processImage(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - width * pixelStride

        val bitmap = if (rowPadding == 0) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(width * height)
                buffer.rewind()
                var offset = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val a = buffer.get(offset + 3).toInt() and 0xFF
                        pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        offset += pixelStride
                    }
                    offset += rowPadding
                }
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val bytes = baos.toByteArray()
        bitmap.recycle()

        latestFrameBytes = bytes
        sendData(bytes)
    }

    private fun sendData(bytes: ByteArray) {
        // Enviar al relay (remoto)
        RelayService.getInstance()?.sendFrame("SCREEN", bytes)

        // Enviar al cliente local
        val dataToSend = if (secretKey != null) {
            cipher?.doFinal(bytes) ?: bytes
        } else {
            bytes
        }
        try {
            if (outStream == null) return
            outStream?.writeInt(dataToSend.size)
            outStream?.write(dataToSend)
            outStream?.flush()
        } catch (e: Exception) {
            outStream = null
        }
    }

    private fun createNotification(): Notification {
        val chan = NotificationChannel("ch1", "Stream", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, "ch1")
            .setContentTitle("Transmitiendo pantalla")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        isStreaming.set(false)
        captureThread?.interrupt()
        reconnectThread?.interrupt()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        outStream?.close()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
