package com.example

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val TAG = "AudioRecorder"

    fun startRecording(): File? {
        try {
            val file = File(context.cacheDir, "audio_capture_${System.currentTimeMillis()}.m4a")
            outputFile = file
            Log.d(TAG, "Starting audio recording to: ${file.absolutePath}")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            return null
        }
    }

    fun stopRecording() {
        try {
            Log.d(TAG, "Stopping audio recording")
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }
}
