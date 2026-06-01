package com.example

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileManager {
    private const val TAG = "FileManager"

    fun getRootDirectory(context: Context): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            Environment.getExternalStorageDirectory()
        } else {
            context.filesDir
        }
    }

    fun listFiles(directory: File): List<FileItem> {
        return try {
            val files = directory.listFiles() ?: emptyArray()
            files.map { file ->
                FileItem(
                    name = file.name,
                    absolutePath = file.absolutePath,
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified()
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files for directory: ${directory.absolutePath}", e)
            emptyList()
        }
    }

    fun createFile(directory: File, fileName: String, content: String): File? {
        try {
            val file = File(directory, fileName)
            val writer = FileWriter(file)
            writer.use {
                it.write(content)
            }
            Log.d(TAG, "File created successfully: ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file: $fileName", e)
            return null
        }
    }

    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "File deletion attempt on $filePath: Success=$deleted")
                deleted
            } else {
                Log.d(TAG, "File does not exist: $filePath")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: $filePath", e)
            false
        }
    }
}

data class FileItem(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    val formattedSize: String
        get() {
            if (isDirectory) return "--"
            val kb = size / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
                else -> "$size Bytes"
            }
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }
}
