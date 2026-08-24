package com.pcscan.pcscan.data.api

import android.util.Log
import com.pcscan.pcscan.data.api.SearchApiDto.AnalyzeTextRequestDto
import com.pcscan.pcscan.data.api.SearchApiDto.AnalyzeTextResponseDto
import com.pcscan.pcscan.data.api.SearchApiDto.BuildRecoRequestDto
import com.pcscan.pcscan.data.api.SearchApiDto.BuildRecoResponseDto
import com.pcscan.pcscan.data.api.SearchApiDto.SearchRequestDto
import com.pcscan.pcscan.data.api.SearchApiDto.SearchResponseDto
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

interface PcScanApiService {

    @POST("api/analyze-text")
    suspend fun analyzeText(@Body request: AnalyzeTextRequestDto): Response<AnalyzeTextResponseDto>

    @POST("api/search")
    suspend fun searchExact(@Body request: SearchRequestDto): Response<SearchResponseDto>

    @POST("api/build-recommendation")
    suspend fun buildRecommendation(@Body request: BuildRecoRequestDto): Response<BuildRecoResponseDto>

    companion object {
        private const val TAG = "PcScanApi"

        // =============================================================
        // 🌐 PUBLIC / PRODUCTION URL (Always-online backend, NO ngrok)
        // =============================================================
        // PARA HINDI NA KAILANGAN NG NGROK PALAGI at LAGING ONLINE ang backend:
        // OPTION 1 (RECOMMENDED — FREE, LAGING ONLINE):
        //   Deploy backend sa RENDER.COM (free tier). Copy-paste the
        //   rendered public URL dito. Ex:
        //   "https://pcscan-backend-xxxx.onrender.com"
        //   (Render = always on HTTPS, no PC needed, walang ngrok issues)
        // OPTION 2 (Local PC always-on):
        //   Use NSSM Windows Service + DDNS (no-ip) + router port
        //   forwarding 8000. Ilagay ang domain dito:
        //   "http://pcscan.ddns.net:8000"
        // =============================================================
        // ILAGAY ANG FINAL URL SA LOOB NG "" BELOW:
        private const val BASE_URL_PUBLIC: String = ""
        // Examples:
        // private const val BASE_URL_PUBLIC: String = "https://pcscan-backend-ab12.onrender.com"
        // private const val BASE_URL_PUBLIC: String = "http://mypcscan.ddns.net:8000"

        // Local fallback URLs (used only when BASE_URL_PUBLIC is empty or unreachable)
        private const val BASE_URL_LAN = "http://192.168.100.51:8000/"
        private const val BASE_URL_EMULATOR = "http://10.0.2.2:8000/"
        private const val BASE_URL_LOCALHOST = "http://127.0.0.1:8000/"

        private val URL_FALLBACK_ORDER: List<String> = buildList {
            if (BASE_URL_PUBLIC.isNotBlank()) {
                val normalized = BASE_URL_PUBLIC.trim()
                    .let { if (it.contains("://")) it else "http://$it" }
                    .trimEnd('/') + "/"
                add(normalized)
            }
            add(BASE_URL_LAN)
            add(BASE_URL_EMULATOR)
            add(BASE_URL_LOCALHOST)
        }

        private val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        @Volatile
        private var lastWorkingIndex: Int = 0

        private val serviceCache = java.util.concurrent.ConcurrentHashMap<String, PcScanApiService>()

        fun getInstance(): PcScanApiService = buildForUrl(URL_FALLBACK_ORDER[lastWorkingIndex])

        suspend fun analyzeTextWithFallback(request: AnalyzeTextRequestDto): Response<AnalyzeTextResponseDto> {
            val startIdx = lastWorkingIndex
            val tryOrder = (startIdx until URL_FALLBACK_ORDER.size) + (0 until startIdx)
            var lastException: Throwable? = null
            var lastHttpErrorResponse: Response<AnalyzeTextResponseDto>? = null
            for (idx in tryOrder) {
                val url = URL_FALLBACK_ORDER[idx]
                try {
                    Log.d(TAG, "Trying base URL=$url (lastWorkingIdx=$lastWorkingIndex)")
                    val response = buildForUrl(url).analyzeText(request)
                    when {
                        response.isSuccessful -> {
                            lastWorkingIndex = idx
                            Log.i(TAG, "✅ URL=$url working (HTTP ${response.code()}), saved as primary")
                            return response
                        }
                        response.code() in 400..499 -> {
                            Log.w(TAG, "⚠️  URL=$url returned client error HTTP ${response.code()}. Request invalid, will NOT retry other URLs.")
                            lastWorkingIndex = idx
                            return response
                        }
                        else -> {
                            Log.w(TAG, "🔄 URL=$url returned server error HTTP ${response.code()}, trying next fallback")
                            lastHttpErrorResponse = response
                            lastException = IOException("Server error HTTP ${response.code()} on $url")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "⏱️  Timeout on $url")
                    lastException = e
                } catch (e: ConnectException) {
                    Log.w(TAG, "🔌 Connect fail on $url")
                    lastException = e
                } catch (e: IOException) {
                    Log.w(TAG, "📡 IO fail on $url: ${e.message}")
                    lastException = e
                }
            }
            if (lastHttpErrorResponse != null) return lastHttpErrorResponse
            throw lastException ?: IOException("All base URLs failed. Start backend or check WIFI. Tried: $URL_FALLBACK_ORDER")
        }

        suspend fun searchExactWithFallback(request: SearchRequestDto): Response<SearchResponseDto> {
            val startIdx = lastWorkingIndex
            val tryOrder = (startIdx until URL_FALLBACK_ORDER.size) + (0 until startIdx)
            var lastException: Throwable? = null
            var lastHttpErrorResponse: Response<SearchResponseDto>? = null
            for (idx in tryOrder) {
                val url = URL_FALLBACK_ORDER[idx]
                try {
                    Log.d(TAG, "Trying search base URL=$url (lastWorkingIdx=$lastWorkingIndex)")
                    val response = buildForUrl(url).searchExact(request)
                    when {
                        response.isSuccessful -> {
                            lastWorkingIndex = idx
                            Log.i(TAG, "✅ Search URL=$url working (HTTP ${response.code()}), saved as primary")
                            return response
                        }
                        response.code() in 400..499 -> {
                            Log.w(TAG, "⚠️  Search URL=$url returned client error HTTP ${response.code()}. Request invalid, will NOT retry other URLs.")
                            lastWorkingIndex = idx
                            return response
                        }
                        else -> {
                            Log.w(TAG, "🔄 Search URL=$url returned server error HTTP ${response.code()}, trying next fallback")
                            lastHttpErrorResponse = response
                            lastException = IOException("Search server error HTTP ${response.code()} on $url")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "⏱️  Search timeout on $url")
                    lastException = e
                } catch (e: ConnectException) {
                    Log.w(TAG, "🔌 Search connect fail on $url")
                    lastException = e
                } catch (e: IOException) {
                    Log.w(TAG, "📡 Search IO fail on $url: ${e.message}")
                    lastException = e
                }
            }
            if (lastHttpErrorResponse != null) return lastHttpErrorResponse
            throw lastException ?: IOException("All search base URLs failed. Start backend or check WIFI. Tried: $URL_FALLBACK_ORDER")
        }

        suspend fun buildRecommendationWithFallback(request: BuildRecoRequestDto): Response<BuildRecoResponseDto> {
            val startIdx = lastWorkingIndex
            val tryOrder = (startIdx until URL_FALLBACK_ORDER.size) + (0 until startIdx)
            var lastException: Throwable? = null
            var lastHttpErrorResponse: Response<BuildRecoResponseDto>? = null
            for (idx in tryOrder) {
                val url = URL_FALLBACK_ORDER[idx]
                try {
                    Log.d(TAG, "Trying build-reco base URL=$url (lastWorkingIdx=$lastWorkingIndex)")
                    val response = buildForUrl(url).buildRecommendation(request)
                    when {
                        response.isSuccessful -> {
                            lastWorkingIndex = idx
                            Log.i(TAG, "✅ Build-reco URL=$url working (HTTP ${response.code()}), saved as primary")
                            return response
                        }
                        response.code() in 400..499 -> {
                            Log.w(TAG, "⚠️  Build-reco URL=$url returned client error HTTP ${response.code()}. Request invalid, will NOT retry other URLs.")
                            lastWorkingIndex = idx
                            return response
                        }
                        else -> {
                            Log.w(TAG, "🔄 Build-reco URL=$url returned server error HTTP ${response.code()}, trying next fallback")
                            lastHttpErrorResponse = response
                            lastException = IOException("Build-reco server error HTTP ${response.code()} on $url")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "⏱️  Build-reco timeout on $url")
                    lastException = e
                } catch (e: ConnectException) {
                    Log.w(TAG, "🔌 Build-reco connect fail on $url")
                    lastException = e
                } catch (e: IOException) {
                    Log.w(TAG, "📡 Build-reco IO fail on $url: ${e.message}")
                    lastException = e
                }
            }
            if (lastHttpErrorResponse != null) return lastHttpErrorResponse
            throw lastException ?: IOException("All build-reco base URLs failed. Start backend or check WIFI. Tried: $URL_FALLBACK_ORDER")
        }

        private fun buildForUrl(url: String): PcScanApiService {
            return serviceCache.getOrPut(url) {
                Retrofit.Builder()
                    .baseUrl(url)
                    .client(baseClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(PcScanApiService::class.java)
            }
        }
    }
}
