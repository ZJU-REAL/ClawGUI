package com.clawgui.ng.runtime.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for OpenAI-compatible speech-to-text APIs.
 *
 * Works with:
 *   - OpenAI Whisper (/v1/audio/transcriptions)
 *   - Groq Whisper (fast, free tier available)
 *   - Any OpenAI-compatible STT endpoint (SiliconFlow, etc.)
 *
 * Usage:
 *   val client = SttClient(baseUrl, apiKey, model)
 *   val text = client.transcribe(wavFile)
 */
class SttClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribe an audio file (WAV/MP3/M4A/WEBM) to text.
     * Returns the transcribed text, or throws on error.
     */
    suspend fun transcribe(
        audioFile: File,
        language: String = "zh",
        prompt: String? = null,
    ): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val base = baseUrl.trimEnd('/')
            val url = if (base.endsWith("/audio/transcriptions")) {
                base
            } else {
                "$base/audio/transcriptions"
            }

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType()),
                )
                .addFormDataPart("model", model)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")

            if (!prompt.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("prompt", prompt)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            val call = http.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }

            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(
                                SttException("HTTP ${resp.code}: ${body.take(400)}")
                            )
                            return
                        }
                        val text = runCatching {
                            json.parseToJsonElement(body)
                                .jsonObject["text"]
                                ?.jsonPrimitive?.content
                        }.getOrNull().orEmpty()
                        cont.resume(text)
                    }
                }
            })
        }
    }
}

class SttException(message: String) : RuntimeException(message)
