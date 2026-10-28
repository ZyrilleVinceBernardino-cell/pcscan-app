package com.pcscan.pcscan.data.api

import com.pcscan.pcscan.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface OpenAiApiService {

    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authBearer: String,
        @Body request: OpenAiDto.ChatRequest
    ): retrofit2.Response<OpenAiDto.ChatResponse>

    companion object {
        private const val BASE_URL = "https://api.openai.com/"

        private val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(OpenAiAuthInterceptor())
                .build()
        }

        @Volatile
        private var INSTANCE: OpenAiApiService? = null

        fun getInstance(): OpenAiApiService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(baseClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OpenAiApiService::class.java)
                    .also { INSTANCE = it }
            }
        }

        fun hasApiKey(): Boolean =
            BuildConfig.OPENAI_API_KEY.isNotBlank() &&
            BuildConfig.OPENAI_API_KEY.startsWith("sk-")

        fun authHeader(): String = "Bearer ${BuildConfig.OPENAI_API_KEY}"
    }
}

private class OpenAiAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("Content-Type", "application/json")
        if (original.header("Authorization") == null) {
            requestBuilder.header("Authorization", OpenAiApiService.authHeader())
        }
        return chain.proceed(requestBuilder.build())
    }
}

object OpenAiDto {

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val response_format: ResponseFormat? = null
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class ResponseFormat(
        val type: String
    )

    data class ChatResponse(
        val id: String?,
        val choices: List<Choice>?,
        val usage: Usage?
    )

    data class Choice(
        val index: Int,
        val message: ChoiceMessage,
        val finish_reason: String?
    )

    data class ChoiceMessage(
        val role: String,
        val content: String
    )

    data class Usage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int
    )

    data class JsonBuildRecommendation(
        val detectedComponentTier: String,
        val detectedCpuScore: Int,
        val detectedGpuScore: Int,
        val detectedSocket: String,
        val detectedRamGen: String,
        val detectedPcieGen: String,
        val tierBuilds: List<JsonTierBuild>
    )

    data class JsonBottleneckAnalysis(
        val percentage: Int? = 0,
        val status: String? = "UNKNOWN",
        val limiting_component: String? = "Balanced",
        val summary: String? = "",
        val detailed_analysis: String? = "",
        val gaming_1080p: String? = "",
        val gaming_1440p: String? = "",
        val gaming_4k: String? = "",
        val reason: String? = "",
        val within_limit: Boolean? = true
    )

    data class JsonTierBuild(
        val tierLabel: String,
        val cpu: String,
        val gpu: String,
        val motherboard: String,
        val ram: String,
        val storage: String,
        val psu: String,
        val estimatedWattage: Int,
        val bottleneckPct: Int,
        val bottleneckPartLabel: String,
        val bottleneckNote: String,
        val bottleneck: JsonBottleneckAnalysis? = null
    )

    data class JsonComponentAnalysis(
        val displayName: String,
        val category: String,
        val tier: String,
        val performanceScore: Int,
        val socket: String,
        val ramGen: String,
        val pcieGen: String,
        val specs: List<String>
    )

    data class JsonOcrExtraction(
        val extractedComponents: List<String>,
        val rawText: String,
        val confidence: Int
    )

    data class JsonComponentValidation(
        val is_pc_component: Boolean,
        val component_type: String?,
        val normalized_name: String?,
        val manufacturer: String?,
        val model: String?,
        val confidence: Double?,
        val needs_verification: Boolean?
    )

    data class JsonDbFirstMatchResult(
        val match_found: Boolean,
        val component_id: String?,
        val component_name: String?,
        val category: String?,
        val tier: String?,
        val tier_source: String?,
        val confidence: Double?,
        val reason_if_not_found: String?
    )

    data class JsonAnchorSpecs(
        val socket: String? = null,
        val chipset: String? = null,
        val memory_type: String? = null,
        val tdp: Int? = null,
        val wattage_rating: Int? = null,
        val vram_gb: Int? = null,
        val cores: Int? = null,
        val threads: Int? = null,
        val base_clock_ghz: Double? = null,
        val boost_clock_ghz: Double? = null,
        val release_year: Int? = null,
        val raw_specs: @JvmSuppressWildcards Map<String, Any?>? = emptyMap()
    )

    data class JsonAnchorComponent(
        val id: String,
        val name: String,
        val category: String,
        val tier: String,
        val specs: JsonAnchorSpecs = JsonAnchorSpecs()
    )

    data class JsonCatalogPart(
        val id: String,
        val name: String,
        val category: String,
        val tier: String,
        val brand: String? = null,
        val socket: String? = null,
        val chipset: String? = null,
        val memory_type: String? = null,
        val tdp: Int? = null,
        val wattage: Int? = null,
        val price_usd: Double? = null,
        val raw_specs: @JvmSuppressWildcards Map<String, Any?>? = emptyMap()
    )

    data class JsonBuildComponents(
        val cpu: @JvmSuppressWildcards Map<String, Any?>?,
        val gpu: @JvmSuppressWildcards Map<String, Any?>?,
        val motherboard: @JvmSuppressWildcards Map<String, Any?>?,
        val ram: @JvmSuppressWildcards Map<String, Any?>?,
        val storage: @JvmSuppressWildcards Map<String, Any?>?,
        val psu: @JvmSuppressWildcards Map<String, Any?>?,
        val case: @JvmSuppressWildcards Map<String, Any?>?,
        val cooler: @JvmSuppressWildcards Map<String, Any?>?
    )

    data class JsonCompatibilityCheck(
        val socket_match: Boolean? = null,
        val ram_memory_type_match: Boolean? = null,
        val issues: List<String>? = emptyList()
    )

    data class JsonBuildV2(
        val tier: String,
        val components: JsonBuildComponents,
        val compatibility_check: JsonCompatibilityCheck? = null,
        val bottleneck_percent: Double? = 0.0,
        val bottleneck_warning: Boolean? = false,
        val estimated_total_draw_watts: Int? = 0,
        val recommended_psu_watts: Int? = 0,
        val psu_headroom_used: String? = null,
        val notes: String? = null,
        val bottleneck: JsonBottleneckAnalysis? = null
    )

    data class JsonBuildsV2Wrapper(
        val builds: List<JsonBuildV2>?
    )
}
