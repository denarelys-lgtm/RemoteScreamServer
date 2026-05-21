package com.example.tvbrowser

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var shareButton: Button
    private lateinit var panicButton: Button
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val SCREEN_CAPTURE_REQUEST_CODE = 1000
    private val REQUEST_CAMERA_PERMISSION = 1001
    private val REQUEST_IGNORE_BATTERY = 1002
    private var isDimmed = false
    private var isServiceRunning = false
    private var pendingCameraFacing = CameraCharacteristics.LENS_FACING_BACK

    private var serverSocket: ServerSocket? = null
    private var listeningThread: Thread? = null

    private val prefs by lazy { getSharedPreferences("server_prefs", MODE_PRIVATE) }

    // --- mDNS ---
    private lateinit var nsdManager: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val SERVICE_TYPE = "_screenstream._tcp."
    private val SERVICE_NAME = "ScreenSharePro"

    // --- Relé ---
    private var currentRoomId: String? = null

    private val cameraEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CameraService.ACTION_CAMERA_AVAILABLE -> {
                    val facing = intent.getIntExtra(CameraService.EXTRA_CAMERA_FACING, -1)
                    val cameraName = if (facing == CameraCharacteristics.LENS_FACING_BACK) "trasera" else "frontal"
                    Toast.makeText(this@MainActivity, "Cámara $cameraName disponible", Toast.LENGTH_SHORT).show()
                }
                CameraService.ACTION_CAMERA_UNAVAILABLE -> {
                    val facing = intent.getIntExtra(CameraService.EXTRA_CAMERA_FACING, -1)
                    val cameraName = if (facing == CameraCharacteristics.LENS_FACING_BACK) "trasera" else "frontal"
                    Toast.makeText(this@MainActivity, "Cámara $cameraName en uso por otra app", Toast.LENGTH_SHORT).show()
                }
                "STOP_CAMERA_FROM_NOTIFICATION" -> {
                    stopService(Intent(this@MainActivity, CameraService::class.java))
                    prefs.edit().remove("last_camera_facing").apply()
                    Toast.makeText(this@MainActivity, "Cámara detenida", Toast.LENGTH_SHORT).show()
                }
                "SWITCH_CAMERA_FROM_NOTIFICATION" -> {
                    val currentFacing = prefs.getInt("last_camera_facing", CameraCharacteristics.LENS_FACING_BACK)
                    val newFacing = if (currentFacing == CameraCharacteristics.LENS_FACING_BACK)
                        CameraCharacteristics.LENS_FACING_FRONT
                    else
                        CameraCharacteristics.LENS_FACING_BACK
                    startCameraWithPermissionCheck(newFacing)
                    Toast.makeText(this@MainActivity, "Cambiando cámara...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainLayout = findViewById(R.id.mainLayout)
        shareButton = findViewById(R.id.shareButton)
        panicButton = findViewById(R.id.panicButton)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        // Generar o recuperar Room ID para acceso remoto
        currentRoomId = prefs.getString("room_id", null)
        if (currentRoomId == null) {
            currentRoomId = generateRoomId()
            prefs.edit().putString("room_id", currentRoomId).apply()
        }
        Toast.makeText(this, "Código de sala: $currentRoomId", Toast.LENGTH_LONG).show()
        Log.d("MainActivity", "Código de sala: $currentRoomId")

        requestBatteryOptimizationExemption()
        scheduleWatchdog()

        val filter = IntentFilter().apply {
            addAction(CameraService.ACTION_CAMERA_AVAILABLE)
            addAction(CameraService.ACTION_CAMERA_UNAVAILABLE)
            addAction("STOP_CAMERA_FROM_NOTIFICATION")
            addAction("SWITCH_CAMERA_FROM_NOTIFICATION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraEventReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cameraEventReceiver, filter)
        }

        startCommandListener()
        registerMdnsService()

        startService(Intent(this, WebServerService::class.java))
        startRelayService()

        shareButton.setOnClickListener {
            if (!isServiceRunning) {
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
            }
        }

        panicButton.setOnClickListener {
            stopStreamingProcess()
            finishAndRemoveTask()
        }

        mainLayout.setOnClickListener { if (isDimmed) toggleDimMode(false) }
        restoreServices()
    }

    private fun generateRoomId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun startRelayService() {
        val intent = Intent(this, RelayService::class.java).apply {
            putExtra("ROOM_ID", currentRoomId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun registerMdnsService() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ScreenShare::mDNS").apply {
            setReferenceCounted(true)
            acquire()
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = 9001
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Servicio mDNS registrado: ${nsdServiceInfo.serviceName}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fallo al registrar mDNS (código $errorCode)", Toast.LENGTH_SHORT).show()
                }
                multicastLock?.release()
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterMdnsService() {
        registrationListener?.let {
            nsdManager.unregisterService(it)
        }
        multicastLock?.release()
    }

    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WatchdogService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 5 * 60 * 1000,
            5 * 60 * 1000,  // 5 minutos
            pendingIntent
        )
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_IGNORE_BATTERY)
            }
        }
    }

    private fun restoreServices() {
        val cameraFacing = prefs.getInt("last_camera_facing", -1)
        if (cameraFacing != -1) {
            startCameraWithPermissionCheck(cameraFacing)
        }
    }

    private fun startCommandListener() {
        listeningThread = thread(start = true) {
            try {
                serverSocket = ServerSocket(9001, 50, InetAddress.getByName("0.0.0.0"))
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        val clientIp = client.inetAddress.hostAddress
                        if (clientIp != null && clientIp != "127.0.0.1") {
                            prefs.edit().putString("client_ip", clientIp).apply()
                            Log.d("MainActivity", "Cliente conectado desde IP: $clientIp")
                        }

                        val command = client.getInputStream().bufferedReader().readLine()
                        runOnUiThread {
                            when {
                                command == "START_SCREEN" -> if (!isServiceRunning) shareButton.performClick()
                                command == "START_BACK" -> startCameraWithPermissionCheck(CameraCharacteristics.LENS_FACING_BACK)
                                command == "START_FRONT" -> startCameraWithPermissionCheck(CameraCharacteristics.LENS_FACING_FRONT)
                                command == "STOP_CAMERA" -> {
                                    stopService(Intent(this, CameraService::class.java))
                                    prefs.edit().remove("last_camera_facing").apply()
                                }
                                command == "STOP" -> stopStreamingProcess()
                            }
                        }
                        client.close()
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startCameraWithPermissionCheck(facing: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCameraFacing = facing
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startCameraService(facing)
        }
    }

    private fun startCameraService(facing: Int) {
        val intent = Intent(this, CameraService::class.java).apply {
            putExtra("FACING", facing)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        prefs.edit().putInt("last_camera_facing", facing).apply()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startCameraService(pendingCameraFacing)
                }, 100)
            } else {
                Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopStreamingProcess() {
        stopService(Intent(this, ScreenCastService::class.java))
        stopService(Intent(this, CameraService::class.java))
        stopService(Intent(this, RelayService::class.java))
        prefs.edit().remove("last_camera_facing").remove("screen_streaming").apply()
        toggleDimMode(false)
        isServiceRunning = false
    }

    private fun toggleDimMode(activate: Boolean) {
        isDimmed = activate
        val params = window.attributes
        if (activate) {
            params.screenBrightness = 0.01f
            mainLayout.setBackgroundColor(Color.BLACK)
            shareButton.visibility = View.INVISIBLE
            isServiceRunning = true
            prefs.edit().putBoolean("screen_streaming", true).apply()
        } else {
            params.screenBrightness = -1f
            mainLayout.setBackgroundColor(Color.parseColor("#1A1A1A"))
            shareButton.visibility = View.VISIBLE
            isServiceRunning = false
            prefs.edit().putBoolean("screen_streaming", false).apply()
        }
        window.attributes = params
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SCREEN_CAPTURE_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                        putExtra("RESULT_CODE", resultCode)
                        putExtra("DATA", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    toggleDimMode(true)
                    prefs.edit().putString("media_projection_token", "stored").apply()
                }
            }
            REQUEST_IGNORE_BATTERY -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Optimización de batería desactivada", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cameraEventReceiver)
        listeningThread?.interrupt()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        unregisterMdnsService()
    }
}
