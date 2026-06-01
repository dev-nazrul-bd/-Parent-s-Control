package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var locationTracker: LocationTracker
    private var locationJob: Job? = null

    // Automation Control Flags to prevent duplicate executions
    private var isProcessingMic = false
    private var isProcessingImage = false
    private var isProcessingVideo = false

    private var systemOverlayView: ComposeView? = null

    fun updateSystemOverlayBlock(show: Boolean, message: String, activeEmail: String) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w("MainActivity", "Cannot show system overlay: permission SYSTEM_ALERT_WINDOW is not granted yet.")
                return
            }
            if (systemOverlayView == null) {
                val overlay = ComposeView(this).apply {
                    setViewTreeLifecycleOwner(this@MainActivity)
                    setViewTreeViewModelStoreOwner(this@MainActivity)
                    setViewTreeSavedStateRegistryOwner(this@MainActivity)
                    
                    setContent {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .clickable(enabled = false) {},
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .background(Color(0xFFD32F2F).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = "Lock",
                                        tint = Color(0xFFEF5350),
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(28.dp))
                                Text(
                                    text = "Device Controlled!",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = message,
                                    fontSize = 14.sp,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(30.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFFEF5350),
                                    trackColor = Color(0xFF333333)
                                )
                                Spacer(modifier = Modifier.height(30.dp))
                                Text(
                                    text = "Monitored Client: $activeEmail",
                                    color = Color.Yellow,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                
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
                    windowManager.addView(overlay, params)
                    systemOverlayView = overlay
                    Log.d("MainActivity", "Successfully added system alert overlay screen block window.")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to add system alert overlay view", e)
                }
            } else {
                // View currently active
            }
        } else {
            if (systemOverlayView != null) {
                try {
                    windowManager.removeView(systemOverlayView)
                    Log.d("MainActivity", "Successfully removed system alert overlay screen block window.")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed removing system alert overlay view", e)
                } finally {
                    systemOverlayView = null
                }
            }
        }
    }

    fun getForegroundPackageName(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 15, time)
        if (stats != null && stats.isNotEmpty()) {
            val sorted = stats.sortedByDescending { it.lastTimeUsed }
            return sorted.firstOrNull()?.packageName
        }
        
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            @Suppress("DEPRECATION")
            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks != null && runningTasks.isNotEmpty()) {
                return runningTasks[0].topActivity?.packageName
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (systemOverlayView != null) {
            try {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(systemOverlayView)
            } catch (e: Exception) {
                // ignore
            }
            systemOverlayView = null
        }
    }

    fun showLocalControlNotification(context: Context, title: String, message: String, photoUrl: String, actionUrl: String) {
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
        
        lifecycleScope.launch(Dispatchers.IO) {
            var bigPictureBitmap: Bitmap? = null
            if (!photoUrl.isNullOrEmpty()) {
                try {
                    val loader = coil.ImageLoader(context)
                    val imgReq = coil.request.ImageRequest.Builder(context)
                        .data(photoUrl)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(imgReq)
                    if (result is coil.request.SuccessResult) {
                        bigPictureBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to load notification photo thumbnail", e)
                }
            }
            
            val intent = if (!actionUrl.isNullOrEmpty()) {
                try {
                    Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                } catch (e: Exception) {
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), intent, pendingFlags)
            
            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                
            if (bigPictureBitmap != null) {
                builder.setLargeIcon(bigPictureBitmap)
                builder.setStyle(
                    androidx.core.app.NotificationCompat.BigPictureStyle()
                        .bigPicture(bigPictureBitmap)
                        .bigLargeIcon(null as Bitmap?)
                )
            }
            
            withContext(Dispatchers.Main) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            Log.w("MainActivity", "Cannot post notification: POST_NOTIFICATIONS permission not granted.")
                            return@withContext
                        }
                    }
                    notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
                    Log.d("MainActivity", "Notification posted successfully.")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to display notification manager notify", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init managers
        FirebaseManager.initFirebase(this)
        audioRecorder = AudioRecorder(this)
        locationTracker = LocationTracker(this)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ParentControlApp(
                        activity = this,
                        audioRecorder = audioRecorder,
                        locationTracker = locationTracker,
                        executeLocationJob = { email, run -> manageLocationTracking(email, run) }
                    )
                }
            }
        }
    }

    // continuous GPS logging during Command location = "on"
    private fun manageLocationTracking(email: String, run: Boolean) {
        if (!run) {
            locationJob?.cancel()
            locationJob = null
            Log.d(TAG, "Location logging stopped")
            return
        }

        if (locationJob != null && locationJob?.isActive == true) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start location tracking - permission missing")
            return
        }

        locationJob = lifecycleScope.launch {
            Log.d(TAG, "Location logging coroutine started")
            try {
                locationTracker.getLocationFlow(15000L) // updates every 15s
                    .flowOn(Dispatchers.IO)
                    .collect { location ->
                        try {
                            updateDeviceLocationInDatabase(email, location)
                        } catch (dbEx: Exception) {
                            Log.e(TAG, "Error updating device location in db", dbEx)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting location flow", e)
            }
        }
    }

    private fun updateDeviceLocationInDatabase(email: String, location: Location) {
        val sanitized = FirebaseManager.getSanitizedEmail(email)
        val ref = FirebaseManager.database.getReference("Location").child(sanitized)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val data = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "speed" to location.speed,
            "updatedAt" to sdf.format(Date(location.time))
        )
        ref.setValue(data)
            .addOnSuccessListener { Log.d(TAG, "Realtime location logged for child database: $data") }
            .addOnFailureListener { Log.e(TAG, "Failed logging location to database", it) }
    }

    // Automated Command Observers called once signed in
    fun observeFirebaseCommands(
        email: String,
        coroutineScope: CoroutineScope,
        lifecycleOwner: LifecycleOwner,
        onCommandStateUpdated: (CommandState) -> Unit,
        onLogAdded: (String) -> Unit
    ) {
        val sanitized = FirebaseManager.getSanitizedEmail(email)
        val ref = FirebaseManager.database.getReference("Command").child(sanitized)

        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "Command state does not exist. Creating default path.")
                    FirebaseManager.createDefaultCommandStructure(email)
                    return
                }

                val commandState = parseCommandSnapshot(snapshot)
                onCommandStateUpdated(commandState)
                onLogAdded("Received Command Update: Block=${commandState.screenBlock}, Mic=${commandState.mic}, CamPhoto=${commandState.image}, CamVideo=${commandState.video}, GPSLocation=${commandState.location}")

                // 1. Evaluate Screen Block Status (Managed natively in UI flow overlay)

                // 2. Evaluate Continuous Location Logging
                if (commandState.location.lowercase() == "on") {
                    manageLocationTracking(email, true)
                    onLogAdded("📡 Background Real-time Location logging activated.")
                } else {
                    manageLocationTracking(email, false)
                    onLogAdded("📡 Background Location tracking idle.")
                }

                // 3. Audio Recording Automation Trigger
                if (commandState.mic.lowercase() == "on" && !isProcessingMic) {
                    isProcessingMic = true
                    coroutineScope.launch {
                        try {
                            onLogAdded("🎙️ Automatic Mic trigger received! Initializing recording...")
                            val durationMs = parseDurationToMs(commandState.micTime)
                            onLogAdded("🎙️ Recording mic audio for ${durationMs / 1000} seconds...")
                            
                            val audioFile = audioRecorder.startRecording()
                            if (audioFile != null) {
                                delay(durationMs)
                                audioRecorder.stopRecording()
                                onLogAdded("🎙️ Recording ended. File saved: ${audioFile.name}. Starting Cloudinary upload...")
                                
                                val url = CloudinaryUploader.uploadFile(audioFile, "raw")
                                onLogAdded("🎙️ Cloudinary upload package success: $url")
                                
                                FirebaseManager.submitUpload(email, url, "audio", "Mic recording duration: ${commandState.micTime}")
                                onLogAdded("🎙️ Upload meta published to Firebase 'Upload's' child segment!")
                            } else {
                                onLogAdded("🎙️ Audio recorder initialization failed.")
                            }
                        } catch (e: Exception) {
                            onLogAdded("🎙️ Audio capture error: ${e.message}")
                        } finally {
                            ref.child("mic").setValue("off")
                            isProcessingMic = false
                        }
                    }
                }

                // 4. Image Capture Photo Automation Trigger
                if (commandState.image.lowercase() == "on" && !isProcessingImage) {
                    isProcessingImage = true
                    coroutineScope.launch {
                        try {
                            onLogAdded("📸 Automatic camera frame trigger received! Capturing ${commandState.imageLimits} photo(s)...")
                            captureCameraHeadlessImage(
                                context = this@MainActivity,
                                lifecycleOwner = lifecycleOwner,
                                cameraSource = commandState.imageCamera,
                                limits = commandState.imageLimits,
                                onPhotoFileReady = { file ->
                                    coroutineScope.launch {
                                        onLogAdded("📸 Capture Success: ${file.name}. Starting Cloudinary Upload...")
                                        val url = CloudinaryUploader.uploadFile(file, "image")
                                        onLogAdded("📸 Cloudinary photo published: $url")
                                        FirebaseManager.submitUpload(email, url, "image", "Camera frame (${commandState.imageCamera}) target")
                                    }
                                },
                                onLogMsg = { onLogAdded("📸 Headless-Camera: $it") }
                            )
                        } catch (e: Exception) {
                            onLogAdded("📸 Image Automation failure: ${e.message}")
                        } finally {
                            delay(4000) // cool down
                            ref.child("image").setValue("off")
                            isProcessingImage = false
                        }
                    }
                }

                // 5. Video Capture Automation Trigger
                if (commandState.video.lowercase() == "on" && !isProcessingVideo) {
                    isProcessingVideo = true
                    coroutineScope.launch {
                        try {
                            onLogAdded("🎥 Automatic video trigger received! Source camera: ${commandState.videoCamera}")
                            val durationMs = parseDurationToMs(commandState.videoTime)
                            captureCameraHeadlessVideo(
                                context = this@MainActivity,
                                lifecycleOwner = lifecycleOwner,
                                cameraSource = commandState.videoCamera,
                                durationMs = durationMs,
                                onVideoFileReady = { file ->
                                    coroutineScope.launch {
                                        onLogAdded("🎥 Video Frame Ready: ${file.name}. Starting Cloudinary upload...")
                                        val url = CloudinaryUploader.uploadFile(file, "video")
                                        onLogAdded("🎥 Cloudinary Video URL link: $url")
                                        FirebaseManager.submitUpload(email, url, "video", "Captured video duration: ${commandState.videoTime}")
                                    }
                                },
                                onLogMsg = { onLogAdded("🎥 Headless-Video: $it") }
                            )
                        } catch (e: Exception) {
                            onLogAdded("🎥 Video capture error: ${e.message}")
                        } finally {
                            delay(5000) // cool down
                            ref.child("video").setValue("off")
                            isProcessingVideo = false
                        }
                    }
                }

                // 6. Notification Command Listener Trigger
                val notificationSnapshot = snapshot.child("notification")
                if (notificationSnapshot.exists()) {
                    val notifStatus = notificationSnapshot.child("status").value?.toString() ?: ""
                    if (notifStatus == "send") {
                        val title = notificationSnapshot.child("title").value?.toString() ?: "Supervisor Alert"
                        val body = notificationSnapshot.child("body").value?.toString()
                            ?: notificationSnapshot.child("message").value?.toString()
                            ?: "A new device supervisor notification was received."
                        val photo = notificationSnapshot.child("photo").value?.toString() ?: ""
                        val action = notificationSnapshot.child("action").value?.toString() ?: ""
                        
                        coroutineScope.launch {
                            try {
                                notificationSnapshot.ref.child("status").setValue("sent")
                                onLogAdded("🔔 Received administrator notification: '$title'")
                                showLocalControlNotification(this@MainActivity, title, body, photo, action)
                                onLogAdded("🔔 Notification successfully displayed on device.")
                            } catch (e: Exception) {
                                Log.e(TAG, "Notification trigger error", e)
                                onLogAdded("🔔 Notification display error: ${e.message}")
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command database subscription cancelled", error.toException())
            }
        })
    }

    private fun parseCommandSnapshot(snapshot: DataSnapshot): CommandState {
        var video = "off"
        var videoTime = "5:30"
        var videoCamera = "back"
        
        var mic = "off"
        var micTime = "5:30"
        
        var image = "off"
        var imageLimits = 3
        var imageCamera = "front"
        
        var location = "off"
        var screenBlock = "off"

        // Video fields
        video = snapshot.child("video").value?.toString() ?: "off"
        videoTime = snapshot.child("time").value?.toString() ?: "5:30"
        videoCamera = snapshot.child("camera").value?.toString() ?: "back"

        // Mic fields
        mic = snapshot.child("mic").value?.toString() ?: "off"
        // Check nesting or flat variables which user can edit differently in Firebase UI
        micTime = snapshot.child("mic_time").value?.toString() 
            ?: snapshot.child("time").value?.toString() 
            ?: "5:30"

        // Image fields
        image = snapshot.child("image").value?.toString() ?: "off"
        imageLimits = snapshot.child("limits").value?.toString()?.toIntOrNull() ?: 3
        imageCamera = snapshot.child("camera").value?.toString() ?: "front"

        // Global Location and Screen Blocks
        location = snapshot.child("location").value?.toString() ?: "off"
        screenBlock = snapshot.child("screen_block").value?.toString() 
            ?: snapshot.child("screenBlock").value?.toString() 
            ?: "off"

        return CommandState(
            video = video,
            videoTime = videoTime,
            videoCamera = videoCamera,
            mic = mic,
            micTime = micTime,
            image = image,
            imageLimits = imageLimits,
            imageCamera = imageCamera,
            location = location,
            screenBlock = screenBlock
        )
    }

    private fun parseDurationToMs(durationStr: String?): Long {
        if (durationStr == null) return 10000L // 10s default
        return try {
            val parts = durationStr.split(":")
            if (parts.size == 2) {
                val min = parts[0].trim().toLongOrNull() ?: 0L
                val sec = parts[1].trim().toLongOrNull() ?: 0L
                (min * 60 + sec) * 1000L
            } else {
                val sec = durationStr.trim().toLongOrNull() ?: 10L
                sec * 1000L
            }
        } catch (e: Exception) {
            10000L
        }
    }
}

// Data class representing firebase controller schema
data class CommandState(
    val video: String = "off",
    val videoTime: String = "5:30",
    val videoCamera: String = "back",
    val mic: String = "off",
    val micTime: String = "5:30",
    val image: String = "off",
    val imageLimits: Int = 3,
    val imageCamera: String = "front",
    val location: String = "off",
    val screenBlock: String = "off"
)

// Global Headless Camera helpers using modern CameraX
fun captureCameraHeadlessImage(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    cameraSource: String,
    limits: Int,
    onPhotoFileReady: (File) -> Unit,
    onLogMsg: (String) -> Unit
) {
    try {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder().build()
                
                val selector = if (cameraSource.lowercase() == "front") {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                onLogMsg("Bound CameraX successfully for capture source ($cameraSource)")

                // Perform capture(s) up to requested limits
                val targetExecutor = ContextCompat.getMainExecutor(context)
                
                CoroutineScope(Dispatchers.Main).launch {
                    for (i in 1..limits) {
                        delay(1500L) // gap between snaps
                        val outputFile = File(context.cacheDir, "parents_control_image_${System.currentTimeMillis()}_$i.jpg")
                        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()

                        imageCapture.takePicture(
                            options,
                            targetExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    onLogMsg("Photo #$i successfully snapped.")
                                    onPhotoFileReady(outputFile)
                                    if (i == limits) {
                                        cameraProvider.unbindAll()
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    onLogMsg("Photo capture failed: ${exception.message}")
                                    if (i == limits) {
                                        cameraProvider.unbindAll()
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                onLogMsg("Initialize error inside future framework: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    } catch (e: Exception) {
        onLogMsg("Failure executing instance build framework: ${e.message}")
    }
}

@SuppressLint("MissingPermission")
fun captureCameraHeadlessVideo(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    cameraSource: String,
    durationMs: Long,
    onVideoFileReady: (File) -> Unit,
    onLogMsg: (String) -> Unit
) {
    try {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                val selector = if (cameraSource.lowercase() == "front") {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, videoCapture)
                onLogMsg("Bound VideoCapture framework on: $cameraSource")

                val videoFile = File(context.cacheDir, "parents_control_video_${System.currentTimeMillis()}.mp4")
                val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

                val recording = videoCapture.output
                    .prepareRecording(context, fileOutputOptions)
                    .apply {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            withAudioEnabled()
                        }
                    }
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                onLogMsg("Recording initialized. Duration set is ${durationMs / 1000} seconds.")
                            }
                            is VideoRecordEvent.Finalize -> {
                                if (!event.hasError()) {
                                    onLogMsg("Video record finalize with success")
                                    onVideoFileReady(videoFile)
                                } else {
                                    onLogMsg("Video finalization encounter error format: Code ${event.error}")
                                }
                                cameraProvider.unbindAll()
                            }
                        }
                    }

                // Stop video execution coroutine after duration
                CoroutineScope(Dispatchers.Main).launch {
                    delay(durationMs)
                    onLogMsg("Shutting down camera video active stream.")
                    recording.stop()
                }

            } catch (e: Exception) {
                onLogMsg("Failure initializing CameraX providers: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    } catch (e: Exception) {
        onLogMsg("Failure setting camera context stream: ${e.message}")
    }
}


// --- COMPOSE JETPACK MAIN APPLICATION INTERFACE ---
@Composable
fun ParentControlApp(
    activity: MainActivity,
    audioRecorder: AudioRecorder,
    locationTracker: LocationTracker,
    executeLocationJob: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
 
    var currentUser by remember { mutableStateOf(FirebaseManager.auth.currentUser) }
    var currentEmail by remember { mutableStateOf(currentUser?.email ?: "") }
 
    // Live App Control Variables
    var commandState by remember { mutableStateOf(CommandState()) }
    val logsList = remember { mutableStateListOf<String>() }
 
    // Real-time Storage/Capture Permissions Wizard Handler
    val requiredPermissionsList = remember {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        list
    }
 
    var permissionGrantStates by remember {
        mutableStateOf(
            requiredPermissionsList.associateWith { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
 
    val launcherPermissionsBlock = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGrantStates = results
        val allOk = results.values.all { it }
        if (allOk) {
            Toast.makeText(context, "All capabilities successfully active!", Toast.LENGTH_SHORT).show()
            logsList.add("Permissions Status: All necessary sensors permitted!")
        } else {
            Toast.makeText(context, "Some sensor functions may be limited.", Toast.LENGTH_LONG).show()
            logsList.add("Permissions Status Warning: Some requested permissions was refused.")
        }
        if (currentUser != null) {
            FirebaseManager.uploadDeviceStatus(currentEmail, results)
            FirebaseManager.uploadContacts(context, currentEmail)
            FirebaseManager.uploadSMS(context, currentEmail)
            FirebaseManager.uploadStorageStructure(context, currentEmail)
            FirebaseManager.uploadInstalledApps(context, currentEmail)
        }
    }

    val requestAllPerms = {
        launcherPermissionsBlock.launch(requiredPermissionsList.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (err: Exception) {
                        Log.e("MainActivity", "Error triggering MANAGE_EXTERNAL_STORAGE access settings", err)
                    }
                }
            }
        }
    }
 
    // Continuous dynamic block apps tracking variables
    var blockedPackagesMap by remember { mutableStateOf(mapOf<String, String>()) }
    var isAppBlockedActive by remember { mutableStateOf(false) }
    var currentBlockedAppName by remember { mutableStateOf("") }

    // Sync blocked packages lists from FB real-time database
    LaunchedEffect(currentEmail) {
        if (currentEmail.isNotEmpty()) {
            val sanitized = FirebaseManager.getSanitizedEmail(currentEmail)
            val ref = FirebaseManager.database.getReference("Devices").child(sanitized).child("InstalledApps")
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
                    Log.d("MainActivity", "Sync current blocked apps in real-time: $blockedPackagesMap")
                }

                override fun onCancelled(error: DatabaseError) {}
            })
        } else {
            blockedPackagesMap = emptyMap()
        }
    }

    // Background app verification loop every 600ms if signed in for instant response
    LaunchedEffect(currentEmail, blockedPackagesMap) {
        if (currentEmail.isNotEmpty()) {
            while (true) {
                delay(600)
                try {
                    val fgPackage = activity.getForegroundPackageName(context)
                    if (fgPackage != null && fgPackage != context.packageName) {
                        if (blockedPackagesMap.containsKey(fgPackage)) {
                            isAppBlockedActive = true
                            currentBlockedAppName = blockedPackagesMap[fgPackage] ?: "Selected App"
                        } else {
                            isAppBlockedActive = false
                        }
                    } else {
                        isAppBlockedActive = false
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error polling foreground app context", e)
                }
            }
        } else {
            isAppBlockedActive = false
        }
    }

    // Unified system overlay lock decider logic (Active Lock vs Selective Block)
    val showOverlay = (commandState.screenBlock.lowercase() == "on" || isAppBlockedActive) && currentUser != null
    val overlayMessage = if (isAppBlockedActive) {
        "The Device Supervisor has temporarily restricted access to '$currentBlockedAppName'. Please engage in constructive studies or query your system administrator."
    } else {
        "This device is currently locked. The System Administrator has enabled remote security blocking on this terminal."
    }

    // Real-time overlay manager triggers
    LaunchedEffect(showOverlay, overlayMessage, currentEmail) {
        if (currentUser != null && currentEmail.isNotEmpty()) {
            activity.updateSystemOverlayBlock(showOverlay, overlayMessage, currentEmail)
        } else {
            activity.updateSystemOverlayBlock(false, "", "")
        }
    }

    // Command Listener Subscription handle
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            val email = currentUser?.email ?: ""
            currentEmail = email
            logsList.add("Signed in user account: $email")
            
            // Upload default structures and status information on sign in
            FirebaseManager.createDefaultCommandStructure(email)
            FirebaseManager.uploadDeviceStatus(email, permissionGrantStates)
            
            // Upload structural information immediately on sign in
            FirebaseManager.uploadContacts(context, email)
            FirebaseManager.uploadSMS(context, email)
            FirebaseManager.uploadStorageStructure(context, email)
            FirebaseManager.uploadInstalledApps(context, email)
 
            // Register background observer for file upload triggers from Firebase console
            FirebaseManager.observeStorageUploads(context, email) { logMsg ->
                logsList.add(logMsg)
            }
 
            // Continuous listening database commands
            activity.observeFirebaseCommands(
                email = email,
                coroutineScope = coroutineScope,
                lifecycleOwner = lifecycleOwner,
                onCommandStateUpdated = { commandState = it },
                onLogAdded = { logsList.add(it) }
            )
        }
    }
 
    // Launch Permission request dialogue automatically on start
    LaunchedEffect(Unit) {
        requestAllPerms()
    }
 
    // UI screen block overlay lock screen
    Box(modifier = Modifier.fillMaxSize()) {
        if (currentUser == null) {
            // Authentication screen interface
            AuthenticationUI(onAuthSuccess = { user ->
                currentUser = user
            })
        } else {
            var selectedTab by remember { mutableStateOf(0) }
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0C0E14))) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF161924),
                    contentColor = Color.White
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Monitored Terminal (Client)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Administration Control (Sender)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                
                if (selectedTab == 0) {
                    SimpleBackgroundStatusUI(
                        email = currentEmail,
                        permissionStatsMap = permissionGrantStates,
                        onRequestPermissions = {
                            requestAllPerms()
                        },
                        onLogOutRequested = {
                            FirebaseManager.auth.signOut()
                            currentUser = null
                            currentEmail = ""
                            executeLocationJob("", false)
                            activity.updateSystemOverlayBlock(false, "", "")
                        }
                    )
                } else {
                    ParentNotificationSenderPanel(
                        currentEmail = currentEmail,
                        onLogOutRequested = {
                            FirebaseManager.auth.signOut()
                            currentUser = null
                            currentEmail = ""
                            executeLocationJob("", false)
                            activity.updateSystemOverlayBlock(false, "", "")
                        }
                    )
                }
            }
        }
 
        // --- PARENT OVERLAY SCREEN LOCK OVERLAY COMPOSABLE (Local Fallback visual) ---
        if (showOverlay && currentUser != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.98f))
                    .clickable(enabled = false) {}, // completely absorbs clicks
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Screen Locked",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(54.dp)
                        )
                    }
 
                    Spacer(modifier = Modifier.height(28.dp))
 
                    Text(
                        text = "Device Restricted!",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center
                    )
 
                    Spacer(modifier = Modifier.height(14.dp))
 
                    Text(
                        text = overlayMessage,
                        fontSize = 15.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
 
                    Spacer(modifier = Modifier.height(40.dp))
 
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .clip(RoundedCornerShape(4.dp))
                    )
 
                    Spacer(modifier = Modifier.height(40.dp))
 
                    Text(
                        text = "Locked Target: $currentEmail",
                        color = Color.Yellow.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// --- SECURE AUTHENTICATION SCREEN COMPOSABLE ---
@Composable
fun AuthenticationUI(onAuthSuccess: (FirebaseUser) -> Unit) {
    val context = LocalContext.current
    val networkScope = rememberCoroutineScope()

    var emailState by remember { mutableStateOf("") }
    var passwordState by remember { mutableStateOf("1234567") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var isLoadingState by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF13131A),
                        Color(0xFF1E1E2F)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF232338)),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Display Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = "Shield Control Logo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Control Administrator",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = if (isRegisterMode) "Create a client terminal account" else "Sign-in to secure terminal dashboard",
                    fontSize = 13.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )

                // Input fields
                OutlinedTextField(
                    value = emailState,
                    onValueChange = { emailState = it },
                    label = { Text("Email Address (Google Account)") },
                    placeholder = { Text("username@gmail.com") },
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = "Email") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_email_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = passwordState,
                    onValueChange = { passwordState = it },
                    label = { Text("Security Password") },
                    placeholder = { Text("Min 6 characters") },
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = "Password") },
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        val description = if (passwordVisible) "Hide password" else "Show password"
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = description, tint = Color.LightGray)
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_password_input")
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (isLoadingState) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                } else {
                    Button(
                        onClick = {
                            val email = emailState.trim()
                            val pass = passwordState.trim()
                            if (email.isEmpty() || pass.length < 6) {
                                Toast.makeText(context, "Please enter a valid email and a password of at least 6 characters.", Toast.LENGTH_LONG).show()
                            } else {
                                isLoadingState = true
                                networkScope.launch {
                                    try {
                                        val authResult = if (isRegisterMode) {
                                            FirebaseManager.auth.createUserWithEmailAndPassword(email, pass).await()
                                        } else {
                                            FirebaseManager.auth.signInWithEmailAndPassword(email, pass).await()
                                        }
                                        
                                        val user = authResult.user
                                        if (user != null) {
                                            onAuthSuccess(user)
                                            Toast.makeText(context, "Successfully authorized!", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        // Fallback auto-creates the child details if they can't log in due to missing account (seamless onboarding)
                                        if (!isRegisterMode && e.message?.contains("no user record") == true) {
                                            try {
                                                val created = FirebaseManager.auth.createUserWithEmailAndPassword(email, pass).await()
                                                created.user?.let { onAuthSuccess(it) }
                                                Toast.makeText(context, "New administrator account auto-registered!", Toast.LENGTH_SHORT).show()
                                            } catch (e2: Exception) {
                                                Toast.makeText(context, "Error: ${e2.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        isLoadingState = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("auth_submit_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isRegisterMode) "Register & Connect" else "Authorize & Sign-In",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isRegisterMode) "Already registered? Sign In" else "Create a new supervisor account",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { isRegisterMode = !isRegisterMode }
                            .padding(8.dp)
                    )

                    // User Friendly Direct Google Sign In Demonstration Simulation Badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .clickable {
                                // Direct Fast Google-Auth Simulation helper
                                emailState = "nazrul.islam.uli019@gmail.com"
                                passwordState = "1234567"
                                Toast.makeText(context, "Default credentials mapped. Press Sign-In!", Toast.LENGTH_LONG).show()
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountBalance,
                            contentDescription = "Google Icon",
                            tint = Color.Yellow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Use Google Sign-In Assistant",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// --- SIMPLE AND LIGHTWEIGHT BACKGROUND DAEMON UI ---
@Composable
fun SimpleBackgroundStatusUI(
    email: String,
    permissionStatsMap: Map<String, Boolean>,
    onRequestPermissions: () -> Unit,
    onLogOutRequested: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0E14))
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper Status Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0xFF1B5E20).copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudSync,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Parent's Control",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Remote Daemon Active",
                fontSize = 14.sp,
                color = Color(0xFF4CAF50),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161924)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Information",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Monitored Client: $email",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Connection Status: Online & Synced",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Permissions
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161924)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Core Workspace Permissions",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    val reqPerms = mutableListOf(
                        Manifest.permission.CAMERA to "Camera Hardware Security",
                        Manifest.permission.RECORD_AUDIO to "Audio Microphone Capture",
                        Manifest.permission.ACCESS_FINE_LOCATION to "High-Precision GPS Logging",
                        Manifest.permission.READ_CONTACTS to "Contact Records Feed",
                        Manifest.permission.READ_SMS to "SMS Message Receipts"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        reqPerms.add(Manifest.permission.READ_MEDIA_IMAGES to "Media Photos Storage Access")
                    } else {
                        reqPerms.add(Manifest.permission.READ_EXTERNAL_STORAGE to "External Storage Reader")
                    }

                    reqPerms.forEach { (perm, name) ->
                        val isGranted = permissionStatsMap[perm] ?: false
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = name, fontSize = 12.sp, color = Color.White)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = if (isGranted) Color(0xFF4CAF50) else Color(0xFFE53935),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isGranted) "Authorized" else "Denied",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isGranted) Color(0xFF4CAF50) else Color(0xFFE53935)
                                )
                            }
                        }
                    }

                    Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 10.dp))

                    // Draw Over Other Apps
                    val isOverlayGranted = Settings.canDrawOverlays(context)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Overlay Draw (Attention Interceptor)", fontSize = 12.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isOverlayGranted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (isOverlayGranted) Color(0xFF4CAF50) else Color(0xFFE53935),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOverlayGranted) "Active" else "Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOverlayGranted) Color(0xFF4CAF50) else Color(0xFFE53935)
                            )
                        }
                    }

                    // Usage Access Stats
                    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            context.packageName
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            context.packageName
                        )
                    }
                    val isUsageGranted = mode == android.app.AppOpsManager.MODE_ALLOWED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Usage Logs (Specific App Blocker)", fontSize = 12.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isUsageGranted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (isUsageGranted) Color(0xFF4CAF50) else Color(0xFFE53935),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isUsageGranted) "Active" else "Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isUsageGranted) Color(0xFF4CAF50) else Color(0xFFE53935)
                            )
                        }
                    }

                    // All Files Access (Full Storage Manager)
                    val isStorageManagerGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        android.os.Environment.isExternalStorageManager()
                    } else {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "All Files Access (Full Storage)", fontSize = 12.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isStorageManagerGranted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (isStorageManagerGranted) Color(0xFF4CAF50) else Color(0xFFE53935),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isStorageManagerGranted) "Active" else "Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isStorageManagerGranted) Color(0xFF4CAF50) else Color(0xFFE53935)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onRequestPermissions,
                                modifier = Modifier.weight(1f).height(38.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Sensor Wizard", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            if (!isOverlayGranted) {
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            } catch (err: Exception) {
                                                Toast.makeText(context, "Cannot open Overlay settings", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4527A0)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Grant Overlay", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (!isUsageGranted || !isStorageManagerGranted) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!isUsageGranted) {
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                } catch (err: Exception) {
                                                    Toast.makeText(context, "Cannot open Usage settings", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Grant Usage", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (!isStorageManagerGranted) {
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                try {
                                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                        data = Uri.parse("package:${context.packageName}")
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    try {
                                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (err: Exception) {
                                                        Toast.makeText(context, "Cannot open Storage settings", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } else {
                                                onRequestPermissions()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Grant Storage", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Lower Note and Logout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "The background daemon remains active to execute real-time administrative instructions and sync device actions. The administrator can manage this client remotely from the console dashboard.",
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onLogOutRequested,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(44.dp)
                    .testTag("app_logout_btn"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.ExitToApp, contentDescription = "Sign Out", tint = Color.Red, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign Out", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun ParentNotificationSenderPanel(
    currentEmail: String,
    onLogOutRequested: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var targetEmail by remember { mutableStateOf(currentEmail) }
    var notificationTitle by remember { mutableStateOf("Study Time Announcement! 📚") }
    var notificationMessage by remember { mutableStateOf("Please lock unnecessary screens and return to your books.") }
    var notificationPhotoUrl by remember { mutableStateOf("https://images.unsplash.com/photo-1497633762265-9d179a990aa6?w=600&auto=format&fit=crop") }
    var actionLink by remember { mutableStateOf("https://classroom.google.com") }
    
    var isSending by remember { mutableStateOf(false) }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0E14))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .background(Color(0xFFEF6C00).copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(38.dp)
                )
            }
        }
        
        item {
            Text(
                text = "Remote Notification Panel",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = "Dispatch custom banner alerts with image assets and click destinations",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161924)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How to Use",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF9800),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    
                    Text(
                        text = "• Enter the target client's Google account email address accurately.\n" +
                               "• Provide a high-quality online banner image URL and optional web destination link.\n" +
                               "• When clicked by the client, they will be redirected to the specified destination URL.",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        lineHeight = 16.sp
                    )
                }
            }
        }
        
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161924)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Notification Parameters",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    // Target Email Input
                    OutlinedTextField(
                        value = targetEmail,
                        onValueChange = { targetEmail = it },
                        label = { Text("Target Client Email", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("notif_target_email_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true
                    )
                    
                    // Notification Title Input
                    OutlinedTextField(
                        value = notificationTitle,
                        onValueChange = { notificationTitle = it },
                        label = { Text("Notification Title", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("notif_title_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true
                    )
                    
                    // Notification Body Input
                    OutlinedTextField(
                        value = notificationMessage,
                        onValueChange = { notificationMessage = it },
                        label = { Text("Notification Body Content", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("notif_msg_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        minLines = 2
                    )
                    
                    // Notification Photo URL Input
                    OutlinedTextField(
                        value = notificationPhotoUrl,
                        onValueChange = { notificationPhotoUrl = it },
                        label = { Text("Banner Image URL Link", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("notif_photo_url_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true
                    )
                    
                    // Action Link Input
                    OutlinedTextField(
                        value = actionLink,
                        onValueChange = { actionLink = it },
                        label = { Text("Click Action Destination URL", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("notif_action_link_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Send Button
                    Button(
                        onClick = {
                            if (targetEmail.isEmpty() || notificationTitle.isEmpty() || notificationMessage.isEmpty()) {
                                Toast.makeText(context, "Please fill in all required text fields.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSending = true
                            FirebaseManager.sendNotificationToChild(
                                targetEmail = targetEmail.trim(),
                                title = notificationTitle.trim(),
                                message = notificationMessage.trim(),
                                photoUrl = notificationPhotoUrl.trim(),
                                actionUrl = actionLink.trim(),
                                onSuccess = {
                                    isSending = false
                                    Toast.makeText(context, "Notification request successfully submitted to Firebase!", Toast.LENGTH_LONG).show()
                                },
                                onFailure = { ex ->
                                    isSending = false
                                    Toast.makeText(context, "Dispatch failed: ${ex.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        enabled = !isSending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("send_notif_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF6C00),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Dispatch Mobile Notification", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        
        item {
            OutlinedButton(
                onClick = onLogOutRequested,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(44.dp)
                    .testTag("parent_panel_logout"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.ExitToApp, contentDescription = "Sign Out", tint = Color.Red, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Supervisor Sign Out", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
