package com.example.tvbrowser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

class ScreenCastService : Service() {

    companion object {
        const val TAG = "ScreenCastService"
        const val SCREEN_PORT = 9000
        
        // Almacena el último frame de forma estática para WebServerService
        @JvmStatic
        var latestFrameProvider: ByteArray? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var clientIp: String? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isRunning = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteScream::ScreenWakeLock").apply {
            acquire(10 * 60 * 1000L)
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteScream::ScreenWifiLock").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        clientIp = intent?.getStringExtra("CLIENT_IP")
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>("RESULT_DATA")

        if (!clientIp.isNullOrEmpty() && resultCode != 0 && resultData != null && !isRunning) {
            isRunning = true
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            startProjection()
        }

        return START_STICKY
    }

    private fun startProjection() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val width = 720
        val height = 1280
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCast", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Thread {
            while (isRunning) {
                try {
                    val image = imageReader?.acquireLatestImage() ?: continue
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    if (socket == null || socket?.isClosed == true) {
                        socket = Socket(clientIp, SCREEN_PORT)
                        outputStream = socket?.getOutputStream()
                    }

                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    val bytes = baos.toByteArray()
                    bitmap.recycle()

                    // Compartir frame con el servidor local HTTP
                    latestFrameProvider = bytes

                    outputStream?.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    outputStream?.write(bytes)
                    outputStream?.flush()

                } catch (e: Exception) {
                    Log.e(TAG, "Error transmitiendo pantalla: ${e.message}")
                    destruirSocket()
                    Thread.sleep(1000)
                }
            }
        }.start()
    }

    private fun destruirSocket() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
    }

    override fun onDestroy() {
        isRunning = false
        destruirSocket()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }
}
