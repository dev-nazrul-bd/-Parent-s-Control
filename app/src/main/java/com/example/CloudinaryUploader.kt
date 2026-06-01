package com.example

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object CloudinaryUploader {
    private val client = OkHttpClient()
    private const val TAG = "CloudinaryUploader"

    suspend fun uploadFile(file: File, resourceType: String = "auto"): String {
        return suspendCancellableCoroutine { continuation ->
            val mediaType = when (resourceType) {
                "image" -> "image/*".toMediaTypeOrNull()
                "video" -> "video/*".toMediaTypeOrNull()
                "audio" -> "audio/*".toMediaTypeOrNull()
                else -> "application/octet-stream".toMediaTypeOrNull()
            }

            val fileBody = file.asRequestBody(mediaType)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, fileBody)
                .addFormDataPart("upload_preset", "Dev Nazrul")
                .build()

            // Cloudinary API endpoint for unsigned uploads
            val url = "https://api.cloudinary.com/v1_1/ddtf1d2yk/$resourceType/upload"
            Log.d(TAG, "Uploading ${file.name} to $url")

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Request failed for ${file.name}", e)
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: ""
                            Log.e(TAG, "Upload failed for ${file.name}: $errorBody")
                            continuation.resumeWithException(IOException("Cloudinary upload failed: Code ${resp.code}, $errorBody"))
                            return
                        }
                        try {
                            val bodyStr = resp.body?.string() ?: ""
                            Log.d(TAG, "Upload successful response: $bodyStr")
                            val json = JSONObject(bodyStr)
                            val secureUrl = json.optString("secure_url", json.optString("url", ""))
                            if (secureUrl.isNotEmpty()) {
                                continuation.resume(secureUrl)
                            } else {
                                continuation.resumeWithException(IOException("No secure_url found in Cloudinary response JSON"))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "JSON parsing error for ${file.name}", e)
                            continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }
}
