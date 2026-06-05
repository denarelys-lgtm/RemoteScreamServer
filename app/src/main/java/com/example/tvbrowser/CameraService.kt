package com.example.tvbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : LifecycleService() {

    companion object {
        const val TAG = "CameraService"
        const val CAMERA_PORT = 9002
        
        // Almacena el último frame de forma estática para WebServerService
        @JvmStatic
        var latestFrameProvider: ByteArray? = null
    }

    private lateinit var cameraExecutor: ExecutorService
    private var clientIp: String? = null
    private var currentFacing = CameraSelector.LENS_FACING_BACK
    
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isStreaming = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Forzar a la CPU a mantenerse despierta aunque la pantalla se apague
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteScream::CameraWakeLock").apply {
            acquire(10 * 60 * 1000L)
        }

        // Forzar al Wi-Fi a transmitir a máxima capacidad en segundo plano
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteScream::CameraWifiLock").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        clientIp = intent?.getStringExtra("CLIENT_IP")
        val facingExtra = intent?.getIntExtra("FACING", 0) ?: 0
        currentFacing = if (facingExtra == 1) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        if (!clientIp.isNullOrEmpty() && !isStreaming) {
            isStreaming = true
            startCameraStreaming()
        }

        return START_STICKY
    }

    private fun startCameraStreaming() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    enviarFrameAlCliente(imageProxy)
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(currentFacing)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)

                // Enviar la señal de disponibilidad usando las constantes de la app
                val systemFacingId = if (currentFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0
                val broadcastIntent = Intent(MainActivity.ACTION_CAMERA_AVAILABLE).apply {
                    putExtra(MainActivity.EXTRA_CAMERA_FACING, systemFacingId)
                }
                sendBroadcast(broadcastIntent)

            } catch (e: Exception) {
                Log.e(TAG, "Error al inicializar CameraX: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enviarFrameAlCliente(imageProxy: ImageProxy) {
        try {
            if (socket == null || socket?.isClosed == true) {
                socket = Socket(clientIp, CAMERA_PORT)
                outputStream = socket?.getOutputStream()
            }

            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val bytes = baos.toByteArray()

                // Compartir frame con el servidor local HTTP
                latestFrameProvider = bytes

                outputStream?.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                outputStream?.write(bytes)
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error de red enviando frame de cámara: ${e.message}")
            cerrarSockets()
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val pixelStride = imageProxy.planes[0].pixelStride
        val rowStride = imageProxy.planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { 
                postRotate(rotationDegrees.toFloat())
                if (currentFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f)
                }
            }
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun cerrarSockets() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
    }

    override fun onDestroy() {
        isStreaming = false
        cerrarSockets()
        cameraExecutor.shutdown()
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        
        val broadcastIntent = Intent(MainActivity.ACTION_CAMERA_UNAVAILABLE)
        sendBroadcast(broadcastIntent)
        
        super.onDestroy()
    }
}
