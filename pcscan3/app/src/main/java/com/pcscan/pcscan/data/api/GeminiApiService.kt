package com.pcscan.pcscan.data.api

import com.pcscan.pcscan.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface GeminiApiService {

    @POST("v1/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiDto.GenerateContentRequest
    ): retrofit2.Response<GeminiDto.GenerateContentResponse>

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/"

        private val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        @Volatile
        private var INSTANCE: GeminiApiService? = null

        fun getInstance(): GeminiApiService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(baseClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(GeminiApiService::class.java)
                    .also { INSTANCE = it }
            }
        }

        fun hasApiKey(): Boolean = BuildConfig.GOOGLE_GEMINI_API_KEY.isNotBlank()
        fun apiKey(): String = BuildConfig.GOOGLE_GEMINI_API_KEY
    }
}

object GeminiDto {

    data class GenerateContentRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig? = null
    )

    data class Content(
        val role: String = "user",
        val parts: List<Part>
    )

    sealed class Part {
        data class TextPart(val text: String) : Part()
        data class InlineDataPart(val inline_data: InlineData) : Part()
    }

    data class InlineData(
        val mime_type: String,
        val data: String
    )

    data class GenerationConfig(
        val temperature: Double = 0.1,
        val responseMimeType: String? = null
    )

    data class GenerateContentResponse(
        val candidates: List<Candidate>?,
        val promptFeedback: PromptFeedback? = null
    )

    data class Candidate(
        val content: CandidateContent,
        val finishReason: String?,
        val safetyRatings: List<SafetyRating>?
    )

    data class CandidateContent(
        val role: String,
        val parts: List<CandidatePart>
    )

    data class CandidatePart(
        val text: String? = null
    )

    data class PromptFeedback(
        val blockReason: String?,
        val safetyRatings: List<SafetyRating>?
    )

    data class SafetyRating(
        val category: String,
        val probability: String
    )

    data class JsonOcrResult(
        val components: List<String>,
        val rawText: String,
        val notes: String?
    )

    data class JsonComponentMatch(
        val displayName: String,
        val category: String,
        val manufacturer: String,
        val confidence: Int
    )
}
