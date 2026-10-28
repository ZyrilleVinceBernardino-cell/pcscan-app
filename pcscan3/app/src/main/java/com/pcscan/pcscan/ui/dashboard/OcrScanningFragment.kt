package com.pcscan.pcscan.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.navOptions
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentOcrScanningBinding
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class OcrScanningFragment : Fragment() {

    private var _binding: FragmentOcrScanningBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val repository: RecommendationRepository = RecommendationRepositoryProvider.current

    @Volatile
    private var isProcessingFrame = false

    @Volatile
    private var isResolvingComponent = false

    @Volatile
    private var hasNavigated = false

    /** True while the fragment view still exists; background camera callbacks must check this. */
    @Volatile
    private var viewAlive = false

    private var pendingCandidate: OcrDetectionCandidate? = null
    private var stableDetectionFrames = 0

    @Volatile
    private var lastFrameBitmapLock = Any()
    private var lastFrameJpegBytes: ByteArray? = null

    @Volatile
    private var consecutiveLocalMisses = 0
    private var didScheduleAutoGemini = false
    @Volatile
    private var geminiAutoRetryCount = 0
    private var didWarnGeminiCap = false
    @Volatile
    private var lastStatusMessage: String? = null

    private fun isValidDetection(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return !lower.contains("none detected") &&
                !lower.contains("no match") &&
                !lower.contains("unable to read") &&
                !lower.contains("unreadable") &&
                !lower.contains("tbd") &&
                !lower.contains("(none)")
    }

    /** Cheap local check: the reading must structurally look like a real PC component model. */
    private fun looksLikePcComponent(name: String): Boolean {
        if (!isValidDetection(name)) return false
        val lower = name.lowercase()
        // Broaden regex to catch more hardware patterns (CPU, GPU, RAM, etc.)
        return Regex("""(?i)\b(rtx|gtx|rx|arc)\s*[^\d]{0,4}\d{3,4}""").containsMatchIn(lower) ||
                Regex("""(?i)\b(ryzen|threadripper|core\s?i\d?|celeron|pentium|xeon|ultra)\s*[^\d]{0,5}\d{3,5}""").containsMatchIn(lower) ||
                Regex("""(?i)\b(am[45]|lga\s?\d{3,4}|s[123]|tr4)\b""").containsMatchIn(lower) ||
                Regex("""(?i)\b[abhzx]\d{3}[a-z]{0,2}\b""").containsMatchIn(lower) ||
                Regex("""(?i)\bddr[345]\b""").containsMatchIn(lower) ||
                Regex("""(?i)\b(nvme|ssd|hdd|m\.2|sata)\b""").containsMatchIn(lower) ||
                Regex("""(?i)\b\d{3,4}\s?w\b""").containsMatchIn(lower) ||
                Regex("""(?i)\b\d{1,4}\s?(gb|tb|mhz|mt/s)\b""").containsMatchIn(lower) ||
                Regex("""(?i)\b[a-z]{1,4}-?\d{3,4}[a-z]{0,3}\b""").containsMatchIn(lower)
    }

    /**
     * Gate before the OCR result screen. A reading must at least look like a real PC component.
     * When the component catalogue is reachable the reading must also resolve to a catalogue
     * entry (canonical DB name is then used). If the database check is slow, times out, or
     * no match is found, the structurally valid reading is still accepted to prevent stalling.
     * Returns null only when nothing component-like was identified.
     */
    private suspend fun verifyComponentAgainstDb(rawName: String): String? {
        val trimmed = rawName.trim()
        if (!looksLikePcComponent(trimmed)) return null

        // Try exact match first
        val exact = withContext(Dispatchers.IO) {
            runCatching { withTimeoutOrNull(2_000) { repository.getExactSearchResult(trimmed) } }.getOrNull()
        }
        val exactName = (exact as? ExactSearchResult.Success)?.component?.name
        if (exactName != null && isValidDetection(exactName)) return exactName

        // Try fuzzy match
        val results = withContext(Dispatchers.IO) {
            runCatching { withTimeoutOrNull(2_000) { repository.getSearchResults(trimmed) } }.getOrNull()
        }
        val best = results?.let { findBestMatch(trimmed, it, RecommendationMockData.resolveCategory(trimmed)) }
        if (best != null && isValidDetection(best)) return best

        // If it looks like a component but no DB match was found (or DB timed out), accept raw text.
        return trimmed
    }

    /** Returns the first candidate that is verified against the database, or null when none match. */
    private suspend fun firstVerifiedComponent(candidates: List<String>?): String? {
        if (candidates == null) return null
        for (candidate in candidates) {
            if (hasNavigated) return null
            val verified = verifyComponentAgainstDb(candidate)
            if (verified != null) return verified
        }
        return null
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            showFriendlyError("Camera permission is needed to scan a PC component.")
            findNavController().popBackStack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrScanningBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewAlive = true
        setupPreviewContainer()
        setupBackHandling()
        updateStatus("Starting camera and AI detection...")

        binding.btnGeminiAIScan.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (hasNavigated) return@launch
                geminiAutoRetryCount = 0
                didWarnGeminiCap = false
                didScheduleAutoGemini = true
                consecutiveLocalMisses = 0
                focusAtCenter()
                kotlinx.coroutines.delay(500)
                if (!hasNavigated && !geminiPrimaryInFlight) {
                    runGeminiPrimaryScanOnce()
                }
            }
        }
        binding.btnRescanOCR.visibility = View.GONE

        if (hasCameraPermission()) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupPreviewContainer() {
        previewView = binding.previewView.apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    focusAt(event.x, event.y)
                    view.performClick()
                    true
                } else {
                    false
                }
            }
        }
        binding.btnBack.setOnClickListener { navigateBackToDashboard() }
    }

    /** Keeps the system/gesture back button on the dashboard path instead of closing the app. */
    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateBackToDashboard()
                }
            }
        )
    }

    /**
     * View-scoped coroutine scope, or null once the fragment view is destroyed.
     * Camera and ML Kit callbacks run on background threads and can still fire after
     * onDestroyView(), where touching viewLifecycleOwner would throw and crash the app.
     */
    private fun viewScopeOrNull(): kotlinx.coroutines.CoroutineScope? =
        if (viewAlive) viewLifecycleOwner.lifecycleScope else null

    private fun resetScanning() {
        if (hasNavigated) return
        cameraProvider?.unbindAll()
        clearPendingCandidate()
        isProcessingFrame = false
        isResolvingComponent = false
        updateStatus("Point the camera at the PC component box. Tap to focus.")
        if (hasCameraPermission()) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun fullRefreshScanning() {
        if (hasNavigated) return
        cameraProvider?.unbindAll()
        camera = null
        clearPendingCandidate()
        isProcessingFrame = false
        isResolvingComponent = false

        val navController = findNavController()
        val destinationId = navController.currentDestination?.id ?: return
        navController.navigate(
            destinationId,
            null,
            navOptions {
                popUpTo(destinationId) { inclusive = true }
            }
        )
    }

    private fun navigateBackToDashboard() {
        // Stop every in-flight scan path first so no background callback can navigate or crash after teardown.
        hasNavigated = true
        cameraProvider?.unbindAll()
        camera = null
        try {
            if (isAdded) {
                viewModel.resetAllSearchAndBuildState()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val navController = findNavController()
        val returnedToDashboard = navController.popBackStack(R.id.dashboardFragment, false)
        if (!returnedToDashboard) {
            navController.navigate(R.id.dashboardFragment)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            analyzeFrame(imageProxy)
                        }
                    }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                focusAtCenter()
                scheduleGeminiPrimaryIdentificationNow()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    @Volatile
    private var geminiPrimaryStarted = false
    @Volatile
    private var geminiPrimaryInFlight = false

    private fun scheduleGeminiPrimaryIdentificationNow() {
        if (geminiPrimaryStarted) return
        geminiPrimaryStarted = true
        updateStatus("Point camera at the PC component box. Hold steady. Tap to focus, or tap the blue button below to run AI identify immediately.")
        viewScopeOrNull()?.launch {
            var waitedFrames = 0
            while (waitedFrames < 10 && !hasNavigated && !geminiPrimaryInFlight) {
                val bytes = synchronized(lastFrameBitmapLock) { lastFrameJpegBytes }
                val bytesNullOrEmpty = bytes == null || bytes.isEmpty()
                val previewBmp = if (bytesNullOrEmpty && ::previewView.isInitialized) previewView.bitmap else null
                val haveFrame = !bytesNullOrEmpty || previewBmp != null
                val mlKitHasCandidate = pendingCandidate != null && stableDetectionFrames >= 1
                if (haveFrame && (waitedFrames >= 2 || mlKitHasCandidate)) {
                    runGeminiPrimaryScanOnce()
                    break
                }
                kotlinx.coroutines.delay(300)
                waitedFrames += 1
            }
            if (!hasNavigated && !geminiPrimaryInFlight && geminiAutoRetryCount == 0) {
                kotlinx.coroutines.delay(1800)
                if (!hasNavigated && !geminiPrimaryInFlight && geminiAutoRetryCount == 0) {
                    runGeminiPrimaryScanOnce()
                }
            }
        }
    }

    private suspend fun runGeminiPrimaryScanOnce() {
        if (hasNavigated || geminiPrimaryInFlight) return
        if (geminiAutoRetryCount >= MAX_GEMINI_AUTO_RETRIES) {
            if (!didWarnGeminiCap) {
                didWarnGeminiCap = true
                updateStatus("Auto AI-tries done. Reposition for better lighting, then tap \"Use AI (Gemini) to Identify\" below for fresh retries (button resets the counter, giving you 5 fresh retries each press). Local ML Kit text preview continues running.")
            }
            return
        }
        geminiPrimaryInFlight = true
        geminiAutoRetryCount += 1
        val job = triggerGeminiVisionOnlyScanFromCamera(true)
        job.invokeOnCompletion { _ ->
            geminiPrimaryInFlight = false
            if (!hasNavigated && geminiAutoRetryCount < MAX_GEMINI_AUTO_RETRIES) {
                viewScopeOrNull()?.launch {
                    kotlinx.coroutines.delay(2200)
                    if (!hasNavigated && !geminiPrimaryInFlight && geminiAutoRetryCount < MAX_GEMINI_AUTO_RETRIES) {
                        runGeminiPrimaryScanOnce()
                    } else if (geminiAutoRetryCount >= MAX_GEMINI_AUTO_RETRIES && !didWarnGeminiCap) {
                        didWarnGeminiCap = true
                        updateStatus("Auto AI-tries done. Reposition for better lighting, then tap \"Use AI (Gemini) to Identify\" below for fresh retries (button resets the counter, giving you 5 fresh retries each press). Local ML Kit text preview continues running.")
                    }
                }
            }
        }
    }

    private fun focusAtCenter() {
        if (!this::previewView.isInitialized) return
        val x = previewView.width / 2f
        val y = previewView.height / 2f
        if (x > 0f && y > 0f) {
            focusAt(x, y)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()
        activeCamera.cameraControl.startFocusAndMetering(action)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || hasNavigated) {
            imageProxy.close()
            return
        }

        runCatching { captureFrameAsJpeg(imageProxy) }

        if (isProcessingFrame || isResolvingComponent) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val detectedCandidate = extractDetectionCandidate(result.text)
                if (detectedCandidate == null) {
                    clearPendingCandidate()
                    val ramProgress = parseRamProgress(result.text)
                    val psuProgress = parsePsuProgress(result.text)
                    val brandHint = extractFirstBrandHint(result.text)
                    when {
                        ramProgress != null && (ramProgress.speed != null || ramProgress.totalCapacity != null) -> {
                            val displayParts = listOfNotNull(
                                brandHint,
                                ramProgress.type,
                                ramProgress.speed,
                                ramProgress.moduleConfig,
                                ramProgress.totalCapacity?.let { "${it}GB" }
                            )
                            val synthDisplay = displayParts.joinToString(" ").trim().ifBlank {
                                "${ramProgress.type} ${ramProgress.speed.orEmpty()}".trim()
                            }
                            val synth = OcrDetectionCandidate(displayText = synthDisplay, category = "RAM")
                            val synthStable = registerDetectedCandidate(synth)
                            val hasEnoughInfo = ramProgress.speed != null &&
                                (ramProgress.totalCapacity != null || ramProgress.moduleConfig != null)
                            if (synthStable && hasEnoughInfo && !hasNavigated && !isResolvingComponent) {
                                isResolvingComponent = true
                                val final = pendingCandidate ?: synth
                                updateStatus("ML Kit Fast-Lane: detected \"${final.displayText}\". Navigating to confirmation now...")
                                navigateFromMlKitCandidate(final)
                                return@addOnSuccessListener
                            }
                            if (stableDetectionFrames >= REQUIRED_STABLE_FRAMES && hasEnoughInfo && !hasNavigated && !isResolvingComponent) {
                                isResolvingComponent = true
                                val final = pendingCandidate ?: synth
                                updateStatus("ML Kit Fast-Lane (${stableDetectionFrames} frames): detected \"${final.displayText}\". Navigating to confirmation now...")
                                navigateFromMlKitCandidate(final)
                                return@addOnSuccessListener
                            }
                            updateStatus(
                                when {
                                    ramProgress.speed == null ->
                                        "ML Kit preview -- RAM: ${brandHint?.let { "$it " } ?: ""}${ramProgress.type}. Looking for speed (ex: 3200MHz). Fast-lane or Gemini confirm in 2s max..."
                                    ramProgress.moduleConfig == null && ramProgress.totalCapacity == null ->
                                        "ML Kit preview -- RAM: ${brandHint?.let { "$it " } ?: ""}${ramProgress.type} ${ramProgress.speed}. Looking for kit config (ex: 2x8GB). Fast-lane at 3 stable frames..."
                                    else ->
                                        "ML Kit preview -- RAM: ${brandHint?.let { "$it " } ?: ""}${ramProgress.type} ${ramProgress.speed} ${ramProgress.moduleConfig ?: ""} ${ramProgress.totalCapacity?.let { "${it}GB" } ?: ""}. Stable: $stableDetectionFrames/$REQUIRED_STABLE_FRAMES. Fast-lane triggers at $REQUIRED_STABLE_FRAMES, or Gemini..."
                                }.trim()
                            )
                        }
                        psuProgress != null && psuProgress.efficiencyLabel != null -> {
                            val displayParts = listOfNotNull(
                                brandHint,
                                psuProgress.wattage,
                                psuProgress.efficiencyLabel
                            )
                            val synthDisplay = displayParts.joinToString(" ").trim().ifBlank {
                                "${psuProgress.wattage} ${psuProgress.efficiencyLabel}"
                            }
                            val synth = OcrDetectionCandidate(displayText = synthDisplay, category = "PSU")
                            val synthStable = registerDetectedCandidate(synth)
                            if (synthStable && !hasNavigated && !isResolvingComponent) {
                                isResolvingComponent = true
                                val final = pendingCandidate ?: synth
                                updateStatus("ML Kit Fast-Lane: detected \"${final.displayText}\". Navigating to confirmation now...")
                                navigateFromMlKitCandidate(final)
                                return@addOnSuccessListener
                            }
                            if (stableDetectionFrames >= REQUIRED_STABLE_FRAMES && !hasNavigated && !isResolvingComponent) {
                                isResolvingComponent = true
                                val final = pendingCandidate ?: synth
                                updateStatus("ML Kit Fast-Lane (${stableDetectionFrames} frames): detected \"${final.displayText}\". Navigating to confirmation now...")
                                navigateFromMlKitCandidate(final)
                                return@addOnSuccessListener
                            }
                            updateStatus(
                                "ML Kit preview -- PSU: ${brandHint?.let { "$it " } ?: ""}${psuProgress.wattage} ${psuProgress.efficiencyLabel}. Stable: $stableDetectionFrames/$REQUIRED_STABLE_FRAMES. Fast-lane or Gemini..."
                            )
                        }
                        psuProgress != null -> {
                            updateStatus(
                                "ML Kit preview -- PSU: ${brandHint?.let { "$it " } ?: ""}${psuProgress.wattage}. Looking for 80 Plus rating (Bronze/Gold/etc). Fast-lane or Gemini..."
                            )
                        }
                        else -> {
                            val previewHint = bestPreviewFragment(result.text)
                            updateStatus(
                                if (previewHint != null) "ML Kit preview -- Reading: \"$previewHint\". Hold steady, Fast-lane at 3 stable frames or Gemini auto in <2s..."
                                else "Scanning for a CPU, GPU, RAM, motherboard, or storage label. Hold steady..."
                            )
                        }
                    }
                    if (geminiAutoRetryCount == 0 && !geminiPrimaryInFlight && !hasNavigated && !isResolvingComponent && pendingCandidate != null && stableDetectionFrames >= 1) {
                        viewScopeOrNull()?.launch {
                            runGeminiPrimaryScanOnce()
                        }
                    }
                    return@addOnSuccessListener
                }

                val fastTrack = isFastTrackCandidate(detectedCandidate.displayText)
                val justBecameStable = registerDetectedCandidate(detectedCandidate)
                val stableCount = stableDetectionFrames
                val previewName = pendingCandidate?.displayText ?: detectedCandidate.displayText
                val expectedCategory = detectedCandidate.category.replaceFirstChar { it.uppercase() }
                if ((justBecameStable || fastTrack || stableCount >= REQUIRED_STABLE_FRAMES) && !hasNavigated && !isResolvingComponent) {
                    isResolvingComponent = true
                    val final = pendingCandidate ?: detectedCandidate
                    val laneLabel = when {
                        fastTrack -> "ML Kit Fast-Track"
                        justBecameStable -> "ML Kit Fast-Lane"
                        else -> "ML Kit (${stableCount} stable frames)"
                    }
                    updateStatus("$laneLabel: $expectedCategory \"${final.displayText}\". Navigating to confirmation now...")
                    navigateFromMlKitCandidate(final)
                    return@addOnSuccessListener
                }
                val stableText = if (fastTrack) "Fast-track candidate found -> will trigger Gemini + fast-lane now" else "Stable frames: $stableCount/$REQUIRED_STABLE_FRAMES. Fast-lane triggers at $REQUIRED_STABLE_FRAMES, or Gemini auto-run now"
                updateStatus("ML Kit preview -- $expectedCategory: \"$previewName\". $stableText...")
                if (geminiAutoRetryCount == 0 && !geminiPrimaryInFlight && !hasNavigated && !isResolvingComponent) {
                    viewScopeOrNull()?.launch {
                        runGeminiPrimaryScanOnce()
                    }
                }
            }
            .addOnFailureListener {
                updateStatus("Unable to read the text clearly yet. Try a steadier angle.")
            }
            .addOnCompleteListener {
                isProcessingFrame = false
                imageProxy.close()
            }
    }

    private fun captureFrameAsJpeg(imageProxy: ImageProxy) {
        runCatching {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val width = imageProxy.width
            val height = imageProxy.height
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 92, out)
            synchronized(lastFrameBitmapLock) {
                lastFrameJpegBytes = out.toByteArray()
            }
        }
    }

    private fun navigateFromMlKitCandidate(candidate: OcrDetectionCandidate) {
        if (hasNavigated) return
        val rawCandidate = candidate.displayText.trim()
        if (!isValidDetection(rawCandidate)) {
            isResolvingComponent = false
            updateStatus("Reading \"$rawCandidate\" is not clear enough. Reposition and hold steady...")
            return
        }
        viewScopeOrNull()?.launch {
            updateStatus("Verifying \"$rawCandidate\" against the component database...")
            val verified = verifyComponentAgainstDb(rawCandidate)
            if (hasNavigated) return@launch
            // Stay in scan mode until a real component is identified in the database.
            if (verified == null) {
                isResolvingComponent = false
                consecutiveLocalMisses += 1
                updateStatus("\"$rawCandidate\" is not a recognized component yet. Keep the box in frame, hold steady, or tap \"Use AI (Gemini) to Identify\".")
                return@launch
            }
            hasNavigated = true
            cameraProvider?.unbindAll()
            val finalCategory = RecommendationMockData.resolveCategory(verified).lowercase()
            val display = appendCategorySuffix(verified, finalCategory)
            val bundle = Bundle().apply {
                putString("detectedText", display)
                putString("selectedComponent", verified)
                putString("searchQuery", verified)
                if (finalCategory.isNotBlank()) putString("detectedCategoryHint", finalCategory)
                putBoolean("from_mlkit_fastlane", true)
                putLong("scan_ts", System.currentTimeMillis())
            }
            findNavController().navigate(
                R.id.action_ocrScanningFragment_to_ocrResultFragment,
                bundle
            )
        }
    }

    private suspend fun triggerGeminiAIScanFromCamera(updateStatus: Boolean = true): kotlinx.coroutines.Job {
        val scope = viewScopeOrNull() ?: return kotlinx.coroutines.Job()
        return scope.launch(Dispatchers.Main) {
            if (hasNavigated) return@launch
            val bytes = synchronized(lastFrameBitmapLock) { lastFrameJpegBytes }
            if (bytes == null || bytes.isEmpty()) {
                val bmp = if (::previewView.isInitialized) previewView.bitmap else null
                if (bmp != null) {
                    val outStream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, outStream)
                    runGeminiScanInternal(outStream.toByteArray(), updateStatus)
                } else {
                    if (updateStatus) {
                        updateStatus("No frame available yet. Keep camera pointed at the box...")
                    }
                }
                return@launch
            }
            runGeminiScanInternal(bytes, updateStatus)
        }
    }

    private suspend fun runGeminiScanInternal(jpegBytes: ByteArray, updateStatus: Boolean = true) = withContext(Dispatchers.IO) {
        if (hasNavigated) return@withContext
        if (updateStatus) {
            withContext(Dispatchers.Main) {
                updateStatus("AI (Gemini) is identifying the PC component from the frame...")
            }
        }
        val components = runCatching {
            repository.analyzeImageForComponents(jpegBytes)
        }.getOrElse { emptyList() }
        val verifiedName: String? = firstVerifiedComponent(components)
        withContext(Dispatchers.Main) {
            if (verifiedName == null) {
                consecutiveLocalMisses = 0
                if (updateStatus) {
                    updateStatus(
                        if (components.isNotEmpty()) "AI read ${components.size} candidate(s) but none matched a known PcScan component yet. Keep the box in frame..."
                        else "AI has not yet verified a known PcScan component. Keep camera steady, reposition, or tap \"Use AI (Gemini) to Identify\" button."
                    )
                }
            } else {
                hasNavigated = true
                cameraProvider?.unbindAll()
                val display = appendCategorySuffix(verifiedName, RecommendationMockData.resolveCategory(verifiedName))
                val bundle = Bundle().apply {
                    putString("detectedText", display)
                    putString("selectedComponent", verifiedName)
                    putString("searchQuery", verifiedName)
                    putLong("scan_ts", System.currentTimeMillis())
                }
                findNavController().navigate(
                    R.id.action_ocrScanningFragment_to_ocrResultFragment,
                    bundle
                )
            }
        }
    }

    private suspend fun triggerGeminiVisionOnlyScanFromCamera(updateStatus: Boolean = true): kotlinx.coroutines.Job {
        val scope = viewScopeOrNull() ?: return kotlinx.coroutines.Job()
        return scope.launch(Dispatchers.Main) {
            if (hasNavigated) return@launch
            val bytes = synchronized(lastFrameBitmapLock) { lastFrameJpegBytes }
            if (bytes == null || bytes.isEmpty()) {
                val bmp = if (::previewView.isInitialized) previewView.bitmap else null
                if (bmp != null) {
                    val outStream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, outStream)
                    runGeminiVisionOnlyScanInternal(outStream.toByteArray(), updateStatus)
                } else {
                    if (updateStatus) {
                        updateStatus("No frame available yet. Keep camera pointed at the box...")
                    }
                }
                return@launch
            }
            runGeminiVisionOnlyScanInternal(bytes, updateStatus)
        }
    }

    private suspend fun runGeminiVisionOnlyScanInternal(jpegBytes: ByteArray, updateStatus: Boolean = true) = withContext(Dispatchers.IO) {
        if (hasNavigated) return@withContext
        val mlKitFallback: OcrDetectionCandidate? = synchronized(lastFrameBitmapLock) { pendingCandidate }
        if (updateStatus) {
            withContext(Dispatchers.Main) {
                updateStatus(
                    if (mlKitFallback != null) "AI (Gemini Vision) reading the PC component box -- max 4s or ML Kit fast-fallback to \"${mlKitFallback.displayText}\"..."
                    else "AI (Gemini Vision) reading the PC component box -- please wait 2-3s (4s timeout max)..."
                )
            }
        }
        val visionComponents = runCatching {
            withTimeoutOrNull(4_000) {
                repository.analyzeImageForComponentsVisionOnly(jpegBytes)
            }
        }.getOrElse { null }
        val verifiedVision: String? = firstVerifiedComponent(visionComponents)
        if (verifiedVision != null) {
            withContext(Dispatchers.Main) {
                if (hasNavigated) return@withContext
                hasNavigated = true
                cameraProvider?.unbindAll()
                val display = appendCategorySuffix(verifiedVision, RecommendationMockData.resolveCategory(verifiedVision))
                val bundle = Bundle().apply {
                    putString("detectedText", display)
                    putString("selectedComponent", verifiedVision)
                    putString("searchQuery", verifiedVision)
                    putLong("scan_ts", System.currentTimeMillis())
                }
                findNavController().navigate(
                    R.id.action_ocrScanningFragment_to_ocrResultFragment,
                    bundle
                )
            }
            return@withContext
        }
        if (mlKitFallback != null && (stableDetectionFrames >= REQUIRED_STABLE_FRAMES || mlKitFallback.displayText.length >= 10)) {
            withContext(Dispatchers.Main) {
                if (hasNavigated) return@withContext
                updateStatus("Gemini Vision timed out. Verifying ML Kit candidate \"${mlKitFallback.displayText}\"...")
                navigateFromMlKitCandidate(mlKitFallback)
            }
            return@withContext
        }
        val strictComponents = runCatching {
            withTimeoutOrNull(5_000) {
                repository.analyzeImageForComponents(jpegBytes)
            }
        }.getOrElse { null }
        val verifiedStrict: String? = firstVerifiedComponent(strictComponents)
        withContext(Dispatchers.Main) {
            if (verifiedStrict == null) {
                if (mlKitFallback != null && stableDetectionFrames >= REQUIRED_STABLE_FRAMES) {
                    updateStatus("Gemini slow/no response. Verifying ML Kit candidate \"${mlKitFallback.displayText}\"...")
                    navigateFromMlKitCandidate(mlKitFallback)
                } else if (updateStatus) {
                    val msg = if (!strictComponents.isNullOrEmpty()) "AI read ${strictComponents.size} candidate(s) but none matched a known PcScan component. Reposition and hold steady..."
                    else "AI couldn't read a clear component from this frame yet. Hold still, reposition for better lighting, or tap \"Use AI (Gemini) to Identify\" (gives 5 fresh retries per tap)."
                    updateStatus(msg)
                }
            } else {
                if (hasNavigated) return@withContext
                hasNavigated = true
                cameraProvider?.unbindAll()
                val display = appendCategorySuffix(verifiedStrict, RecommendationMockData.resolveCategory(verifiedStrict))
                val bundle = Bundle().apply {
                    putString("detectedText", display)
                    putString("selectedComponent", verifiedStrict)
                    putString("searchQuery", verifiedStrict)
                    putLong("scan_ts", System.currentTimeMillis())
                }
                findNavController().navigate(
                    R.id.action_ocrScanningFragment_to_ocrResultFragment,
                    bundle
                )
            }
        }
    }

    private fun extractDetectionCandidate(scannedText: String): OcrDetectionCandidate? {
        val displayText = extractComponentName(scannedText) ?: return null
        return OcrDetectionCandidate(
            displayText = displayText,
            category = detectCategory(scannedText, displayText)
        )
    }

    private fun detectCategory(scannedText: String, displayText: String): String {
        val normalized = (scannedText + " " + displayText).lowercase()

        val looksLikeMotherboard = Regex("""(?i)\b(?:amd\s+motherboard|socket\s+am[45]|am[45]\b|motherboard)\b""")
            .containsMatchIn(normalized) ||
            Regex("""(?i)\b(?:b[0-9o]{3,4}m?|x[0-9o]{3,4}|z[0-9o]{3,4}|h[0-9o]{3,4})\b""")
                .containsMatchIn(normalized)

        val looksLikePsu = Regex("""(?i)\b(?:psu|power\s+supply|80\+)\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b\d{3,4}\s?w\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b[a-z]{1,4}\d{3,4}w\b""").containsMatchIn(normalized)

        return when {
            looksLikeMotherboard -> "motherboard"
            looksLikePsu -> "psu"
            else -> RecommendationMockData.resolveCategory(displayText)
        }
    }

    private fun registerDetectedCandidate(candidate: OcrDetectionCandidate): Boolean {
        val currentPending = pendingCandidate
        if (currentPending == null || 
            currentPending.category != candidate.category ||
            currentPending.displayText.lowercase().trim() != candidate.displayText.lowercase().trim()) {
            pendingCandidate = candidate
            stableDetectionFrames = 1
            return false
        }

        stableDetectionFrames += 1
        if (candidateQualityScore(candidate) > candidateQualityScore(currentPending)) {
            pendingCandidate = candidate
        }

        return stableDetectionFrames >= REQUIRED_STABLE_FRAMES
    }

    private fun clearPendingCandidate() {
        pendingCandidate = null
        stableDetectionFrames = 0
    }

    private fun candidateQualityScore(candidate: OcrDetectionCandidate): Int {
        val tokens = extractMeaningfulTokens(candidate.displayText.lowercase()).size
        return tokens * 10 + candidate.displayText.length
    }

    private fun resolveDetectedComponent(detectedComponent: String) {
        resolveDetectedComponent(
            OcrDetectionCandidate(
                displayText = detectedComponent,
                category = RecommendationMockData.resolveCategory(detectedComponent)
            )
        )
    }

    private fun resolveDetectedComponent(detectedCandidate: OcrDetectionCandidate) {
        viewScopeOrNull()?.launch {
            runCatching {
                val searchKey = detectedCandidate.displayText
                val searchResults = repository.getSearchResults(searchKey)
                val resolvedComponent = findBestMatch(detectedCandidate.displayText, searchResults, detectedCandidate.category)
                if (resolvedComponent != null && isValidDetection(resolvedComponent)) {
                    consecutiveLocalMisses = 0
                    hasNavigated = true
                    cameraProvider?.unbindAll()
                    val displayName = appendCategorySuffix(resolvedComponent, detectedCandidate.category)
                    val bundle = Bundle().apply {
                        putString("detectedText", displayName)
                        putString("selectedComponent", resolvedComponent)
                        putString("searchQuery", detectedCandidate.displayText)
                    }
                    findNavController().navigate(
                        R.id.action_ocrScanningFragment_to_ocrResultFragment,
                        bundle
                    )
                    return@launch
                }
                if (!didScheduleAutoGemini) {
                    didScheduleAutoGemini = true
                    launch { triggerGeminiAIScanFromCamera() }
                    updateStatus(if (resolvedComponent != null) "Local match \"$resolvedComponent\" unclear. Auto-running AI..." else "Local match unclear. Auto-running AI (Gemini) identify...")
                } else {
                    updateStatus("Still no verified match. AI (Gemini) will continue after next frame, or tap \"Use AI (Gemini)\" below.")
                }
                return@launch
            }.onFailure {
                showFriendlyError("We could not search for that component right now.")
            }

            isResolvingComponent = false
        }
    }

    private data class ScoredMatch(
        val candidate: String,
        val totalScore: Int,
        val minTokens: Int,
        val brandModelPass: Boolean,
        val hardPenalty: Int,
        val hitCount: Int
    )

    private fun findBestMatch(detectedComponent: String, results: List<String>, detectedCategory: String? = null): String? {
        if (results.isEmpty() || results.all { it.startsWith("Sample Component") }) {
            return null
        }

        val normalizedDetected = detectedComponent.lowercase()
        val allTokens = extractMeaningfulTokens(normalizedDetected)
        val detectedTokens = filterModelTokensOnly(allTokens)
        val detectedDigitGroups = extractDigitGroups(normalizedDetected)

        val scored = results.map { candidate ->
            val normalizedCandidate = candidate.lowercase()
            val candidateTokens = extractMeaningfulTokens(normalizedCandidate)
            val candidateDigitGroups = extractDigitGroups(normalizedCandidate)

            val hit: Int = detectedTokens.count { token ->
                candidateTokens.any { candidateToken ->
                    candidateToken == token ||
                        candidateToken.contains(token) ||
                        token.contains(candidateToken)
                }
            }

            val digitBonus: Int = detectedDigitGroups.sumOf { dg ->
                val match = candidateDigitGroups.any { cdg -> cdg == dg || cdg.contains(dg) || dg.contains(cdg) }
                (if (match) 3 else 0).toInt()
            }

            val exactBrandModelPass = passesExactBrandModel(detectedTokens, detectedDigitGroups, candidateTokens, candidateDigitGroups)
            val bonusForBrandModel: Int = if (exactBrandModelPass) 1000 else 0
            val hardPenalty: Int = computeHardPenalties(normalizedDetected, normalizedCandidate, detectedDigitGroups, candidateDigitGroups, detectedCategory)

            ScoredMatch(
                candidate = candidate,
                totalScore = hit + digitBonus + bonusForBrandModel - hardPenalty,
                minTokens = detectedTokens.size.coerceAtLeast(1),
                brandModelPass = exactBrandModelPass && hardPenalty < 20,
                hardPenalty = hardPenalty,
                hitCount = hit
            )
        }.sortedWith(Comparator<ScoredMatch> { a, b ->
            val c = b.totalScore.compareTo(a.totalScore)
            if (c != 0) c else a.candidate.compareTo(b.candidate)
        })

        val best: ScoredMatch = scored.firstOrNull() ?: return null
        val detectedCategoryUpper = detectedCategory?.uppercase()

        if (best.brandModelPass && best.totalScore > 0 && best.hardPenalty < 20) {
            return best.candidate
        }

        if (best.hardPenalty >= 20 || best.hitCount == 0) {
            return null
        }

        val categoryMismatchHard = when {
            detectedCategoryUpper == "GPU" && !Regex("""(?i)rtx|gtx|radeon|rx\s|geforce|arc|graphics|gpu""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "CPU" && !Regex("""(?i)ryzen|core\s+i|celeron|pentium|processor|cpu""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "RAM" && !Regex("""(?i)ddr|ram|mhz|mt/s|dimm|kit|gb|xmp|expo""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "STORAGE" && !Regex("""(?i)ssd|hdd|nvme|m\.2|sata|tb|gb|storage|crucial|samsung|wd|kingston|adata""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "PSU" && !Regex("""(?i)\d{3,4}\s?w\b|watts?|80\s*\+|gold|bronze|silver|platinum|titanium|seasonic|evga|corsair|rm|sf|focus|fractal|be\s*quiet|coolermaster|fsp|tecware|nzxt|psu""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "MOTHERBOARD" && !Regex("""(?i)\b[bhzx]\d{2,3}|[am]\d{2,3}\b|am4|am5|lga\s?1700|lga\s?1200|lga\s?1851|atx|matx|itx|motherboard|socket""").containsMatchIn(best.candidate) -> true
            else -> false
        }
        if (categoryMismatchHard) return null

        val minRequiredHitStrict: Int = (best.minTokens * 0.40).toInt().coerceAtLeast(2)
        val scoreRatio = if (best.minTokens > 0) best.hitCount.toDouble() / best.minTokens.toDouble() else 0.0
        val hasDigitBonusStrong = (best.totalScore - best.hitCount) >= 3
        val brandOrDigitPass = hasDigitBonusStrong || best.brandModelPass || scoreRatio >= 0.33
        val flexibleHitOk = best.hitCount >= 1 && brandOrDigitPass
        val strictHitOk = best.hitCount >= minRequiredHitStrict
        val strictScoreOk = best.totalScore >= minRequiredHitStrict
        val passed = (strictHitOk && strictScoreOk) || flexibleHitOk
        return best.candidate.takeIf { passed }
    }

    private fun passesExactBrandModel(
        detectedTokens: List<String>,
        detectedDigits: List<String>,
        candidateTokens: List<String>,
        candidateDigits: List<String>
    ): Boolean {
        val brands = setOf("ryzen", "intel", "core", "amd", "nvidia", "geforce", "rtx", "rx", "asus", "msi", "gigabyte", "asrock", "biostar", "corsair", "gskill", "trident", "vengeance", "samsung", "wd", "western digital", "crucial", "seagate", "kingston", "seasonic", "evga", "cooler", "bequiet", "nzxt", "zotac", "palit", "powercolor", "sapphire", "xfx", "inno3d", "galax", "pny", "adata", "lexar", "teamgroup", "thor", "aorus", "strix", "tuf", "tforce", "ripjaws", "ballistix", "fury", "predator", "fractal", "lianli", "noctua", "deepcool", "thermalright", "arctic", "phanteks", "montech", "hyte", "tecware", "fsp")
        val hasBrand = detectedTokens.any { brands.contains(it) } && candidateTokens.any { brands.contains(it) }
        val modelDigitMatch = detectedDigits.isNotEmpty() &&
            candidateDigits.isNotEmpty() &&
            detectedDigits.any { dd -> candidateDigits.any { cd -> cd == dd || cd.contains(dd) || dd.contains(cd) } }
        val modelTokenLike = detectedDigits.isNotEmpty() && detectedDigits == candidateDigits
        return (hasBrand && modelDigitMatch) || modelTokenLike ||
            (detectedDigits.isNotEmpty() && candidateDigits.containsAll(detectedDigits) &&
                detectedTokens.any { dt -> candidateTokens.any { ct -> ct == dt || ct.contains(dt) } })
    }

    private fun filterModelTokensOnly(tokens: List<String>): List<String> {
        val noise = setOf(
            "socket", "amd", "intel", "nvidia", "geforce", "watt", "watts", "gold", "bronze", "silver", "titanium", "platinum",
            "ddr", "pcie", "gen", "generation", "80plus", "80+", "plus",
            "mhz", "ghz", "pin", "pinout", "atx", "matx", "itx", "mini", "micro",
            "am4", "am5", "lga", "1700", "1200", "1151", "1851",
            "sata", "nvme", "m2", "m.2", "gen3", "gen4", "gen5",
            "desktop", "processor", "graphics", "card", "memory", "motherboard", "storage", "power", "supply",
            "cache", "cores", "threads", "tdp", "boost", "base", "clock", "box", "new", "sealed", "oem", "gaming"
        )
        return tokens
            .filterNot { noise.contains(it) || it.length == 1 }
            .ifEmpty { tokens.filter { it.any(Char::isDigit) || it.length >= 4 } }
    }

    private fun computeHardPenalties(
        detectedRawText: String,
        candidate: String,
        detectedDigitGroups: List<String>,
        candidateDigitGroups: List<String>,
        detectedCategory: String? = null
    ): Int {
        val rawLower = detectedRawText.lowercase()
        val candLower = candidate.lowercase()
        var penalty = 0

        val rawHasGpu = Regex("""(?i)rtx|gtx|radeon|rx\s|geforce|arc|graphics\s+card|gpu\b""").containsMatchIn(rawLower)
        val candHasGpu = Regex("""(?i)rtx|gtx|radeon|rx\s|geforce|arc|graphics|gpu\b""").containsMatchIn(candLower)
        val rawHasCpu = Regex("""(?i)\bryzen\b|core\s+i[3579]|celeron|pentium|processor|cpu\b""").containsMatchIn(rawLower)
        val candHasCpu = Regex("""(?i)\bryzen\b|core\s+i[3579]|celeron|pentium|processor|cpu\b""").containsMatchIn(candLower)
        if ((rawHasGpu && candHasCpu) || (rawHasCpu && candHasGpu)) penalty += 100

        val rawHasDdr3 = rawLower.contains("ddr3") || rawLower.contains("ddr 3")
        val hasDdr4Raw = rawLower.contains("ddr4") || rawLower.contains("ddr 4")
        val hasDdr5Raw = rawLower.contains("ddr5") || rawLower.contains("ddr 5")
        val candHasDdr3 = candLower.contains("ddr3") || candLower.contains("ddr 3")
        val hasDdr4Cand = candLower.contains("ddr4") || candLower.contains("ddr 4")
        val hasDdr5Cand = candLower.contains("ddr5") || candLower.contains("ddr 5")
        if (rawHasDdr3 && (hasDdr4Cand || hasDdr5Cand)) penalty += 50
        if (hasDdr4Raw && (candHasDdr3 || hasDdr5Cand)) penalty += 50
        if (hasDdr5Raw && (candHasDdr3 || hasDdr4Cand)) penalty += 50

        val rawWattage = Regex("""(?i)(\d{3,4})\s?w\b""").find(rawLower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val candWattage = Regex("""(?i)(\d{3,4})\s?w\b""").find(candLower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (rawWattage != null && candWattage != null && rawWattage != candWattage) {
            val diff = kotlin.math.abs(rawWattage - candWattage)
            if (diff >= 150) penalty += 80 else if (diff >= 100) penalty += 50 else penalty += 25
        }

        val capacityGroups = setOf("4", "8", "16", "24", "32", "48", "64", "96", "128", "256", "512", "1", "2", "6", "10", "20")
        val detectedCapacities = detectedDigitGroups.filter { dg -> capacityGroups.contains(dg) }
        val candidateCapacities = candidateDigitGroups.filter { dg -> capacityGroups.contains(dg) }
        if (detectedCapacities.isNotEmpty() && candidateCapacities.isNotEmpty()) {
            val overlap = detectedCapacities.intersect(candidateCapacities.toSet())
            if (overlap.isEmpty()) penalty += 30
        }

        val gpuModelRaw = Regex("""(?i)(?:rtx\s*|gtx\s*|rx\s*|arc\s*)(\d{3,4})""").find(rawLower)?.groupValues?.getOrNull(1)
        val gpuModelCand = Regex("""(?i)(?:rtx\s*|gtx\s*|rx\s*|arc\s*)(\d{3,4})""").find(candLower)?.groupValues?.getOrNull(1)
        if (gpuModelRaw != null && gpuModelCand != null && gpuModelRaw != gpuModelCand) penalty += 80

        val cpuModelRaw = Regex("""(?i)(?:ryzen\s+[3579]\s+|core\s+i[3579][- ]?)(\d{4,5})""").find(rawLower)?.groupValues?.getOrNull(1)
        val cpuModelCand = Regex("""(?i)(?:ryzen\s+[3579]\s+|core\s+i[3579][- ]?)(\d{4,5})""").find(candLower)?.groupValues?.getOrNull(1)
        if (cpuModelRaw != null && cpuModelCand != null && cpuModelRaw != cpuModelCand) penalty += 80

        val has3200Raw = detectedDigitGroups.any { it == "3200" }
        val has6400Raw = detectedDigitGroups.any { it == "6400" } || detectedDigitGroups.any { it == "6000" } || detectedDigitGroups.any { it == "5600" }
        val has3200Cand = candidateDigitGroups.any { it == "3200" } || candidateDigitGroups.any { it == "3600" } || candidateDigitGroups.any { it == "2666" } || candidateDigitGroups.any { it == "2133" } || candidateDigitGroups.any { it == "2400" }
        val has6400Cand = candidateDigitGroups.any { it == "6400" } || candidateDigitGroups.any { it == "6000" } || candidateDigitGroups.any { it == "5600" } || candidateDigitGroups.any { it == "5200" } || candidateDigitGroups.any { it == "4800" }
        if ((has3200Raw && has6400Cand) || (has6400Raw && has3200Cand)) penalty += 20

        if (detectedCategory != null) {
            val detUp = detectedCategory.uppercase()
            if (detUp == "GPU" && !candHasGpu) penalty += 60
            if (detUp == "CPU" && !candHasCpu) penalty += 60
            if (detUp == "RAM" && !Regex("""(?i)ddr|ram|mhz|dimm|gb|xmp|expo""").containsMatchIn(candLower)) penalty += 60
            if (detUp == "PSU" && candWattage == null && !Regex("""(?i)80\s*\+|seasonic|evga|corsair\s+(?:rm|sf|hx|cx)|fractal\s+(?:ion|define)|be\s*quiet|coolermaster|tecware|focus""").containsMatchIn(candLower)) penalty += 60
            if (detUp == "MOTHERBOARD" && !Regex("""(?i)\b[bhzx]\d{2,3}|am4|am5|lga|atx|matx|itx|socket|motherboard""").containsMatchIn(candLower)) penalty += 60
            if (detUp == "STORAGE" && !Regex("""(?i)ssd|hdd|nvme|m\.2|sata|tb|gb|samsung|crucial|wd\b|kingston|adata|seagate|lexar""").containsMatchIn(candLower)) penalty += 60
        }

        return penalty
    }

    private fun extractDigitGroups(value: String): List<String> {
        return Regex("""\d{2,}""")
            .findAll(value)
            .map { it.value }
            .distinct()
            .toList()
    }

    private fun extractComponentName(scannedText: String): String? {
        val normalizedText = scannedText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val compactNormalizedText = normalizedText
            .uppercase()
            .replace(Regex("""[\s:;,.|/_-]+"""), "")

        val motherboardLabel = extractMotherboardLabel(normalizedText, compactNormalizedText)
        if (motherboardLabel != null) {
            return motherboardLabel
        }

        val psuLabel = extractPsuLabel(normalizedText, compactNormalizedText)
        if (psuLabel != null) {
            return psuLabel
        }

        val primaryPatterns = listOf(
            Regex("""(?i)\bintel\s+core\s+i[3579][- ]?\d{4,5}[a-z]{0,2}\b"""),
            Regex("""(?i)\bryzen\s+[3579]\s+\d{4,5}[a-z0-9]{0,3}\b"""),
            Regex("""(?i)\brtx\s+\d{3,4}(?:\s?(?:ti|super))?\b"""),
            Regex("""(?i)\brx\s+\d{4}\s?(?:xt|xtx)?\b""")
        )

        val directMatch = primaryPatterns.asSequence()
            .mapNotNull { pattern -> pattern.find(normalizedText)?.value }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull()

        if (directMatch != null) {
            return directMatch
        }

        val ramProgress = parseRamProgress(scannedText)
        val hasRamKeyword = Regex("""(?i)\bram\b""").containsMatchIn(normalizedText)
        val hasKitKeyword = Regex("""(?i)\bkit\b""").containsMatchIn(normalizedText) || compactNormalizedText.contains("KIT")
        val hasXmpKeyword = Regex("""(?i)\bxmp\b""").containsMatchIn(normalizedText)
        val ramSignalScore =
            (if (ramProgress != null) 4 else 0) +
                (if (ramProgress?.moduleConfig != null) 3 else 0) +
                (if (ramProgress?.totalCapacity != null) 2 else 0) +
                (if (ramProgress?.speed != null) 1 else 0) +
                (if (hasKitKeyword) 1 else 0) +
                (if (hasXmpKeyword) 1 else 0) +
                (if (hasRamKeyword) 1 else 0)

        if (ramSignalScore >= 3 && ramProgress?.speed != null && ramProgress.totalCapacity != null) {
            return if (ramProgress.moduleConfig != null) {
                listOf(
                    ramProgress.type,
                    ramProgress.speed,
                    ramProgress.moduleConfig,
                    ramProgress.totalCapacity,
                    "KIT"
                ).joinToString(" ").trim()
            } else {
                listOf(
                    ramProgress.type,
                    ramProgress.speed,
                    ramProgress.totalCapacity,
                    "SINGLE"
                ).joinToString(" ").trim()
            }
        }

        val storageTypeMatches = Regex("""(?i)\b(?:nvme|ssd|m\.2|sata)\b""")
            .findAll(normalizedText)
            .map { it.value.uppercase().replace(".", "") }
            .distinct()
            .toList()
        val storageCapacity = Regex("""(?i)\b(?:120|128|240|250|256|480|500|512|960|1000|1024|1|2|4|8)\s?(?:gb|tb)\b""")
            .find(normalizedText)
            ?.value
            ?.uppercase()
            ?.replace(" ", "")

        val storageSignalScore = storageTypeMatches.size * 3 + if (storageCapacity != null) 1 else 0

        if (storageSignalScore > ramSignalScore && storageTypeMatches.isNotEmpty()) {
            return (storageTypeMatches + listOfNotNull(storageCapacity))
                .joinToString(" ")
                .trim()
        }

        return null
    }

    private fun extractMotherboardLabel(normalizedText: String, compactNormalizedText: String): String? {
        val hasMotherboardSignals =
            Regex("""(?i)\b(?:motherboard|amd\s+motherboard|intel\s+motherboard|socket\s+am[45]|am[45]\b|pcie)\b""")
                .containsMatchIn(normalizedText) ||
                Regex("""(?i)\b(?:msi|asus|gigabyte|asrock|aorus|strix|tuf|tomahawk|mortar|pro)\b""")
                    .containsMatchIn(normalizedText)

        val chipsetRaw = Regex("""(?i)\b([bhzx][0-9o]{3}m?)\b""")
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""([BHZX][0-9O]{3}M?)""")
                .find(compactNormalizedText)
                ?.groupValues
                ?.getOrNull(1)

        val chipset = chipsetRaw
            ?.uppercase()
            ?.replace('O', '0')
            ?.takeIf { it.length >= 4 }

        if (chipset == null) {
            return null
        }

        val suffixCandidates = Regex("""(?i)\b(?:A|PRO|MAX|II|III|WIFI|WIFI6|WIFI6E|PLUS|EDGE|CARBON|MORTAR|TOMAHAWK|GAMING|ELITE|MASTER|AORUS|STRIX|TUF|PRIME)\b""")
            .findAll(normalizedText)
            .map { it.value.uppercase() }
            .distinct()
            .toList()

        if (!hasMotherboardSignals && suffixCandidates.isEmpty()) {
            return null
        }

        val hasChipsetSignal = true
        val motherboardSignalScore =
            (if (hasChipsetSignal) 4 else 0) +
                (if (hasMotherboardSignals) 2 else 0) +
                (suffixCandidates.size.coerceAtMost(4))

        if (motherboardSignalScore < 4) {
            return null
        }

        val suffix = suffixCandidates.take(5).joinToString(" ").trim()
        return if (suffix.isBlank()) chipset else "$chipset $suffix"
    }

    private fun extractPsuLabel(normalizedText: String, compactNormalizedText: String): String? {
        val wattage = extractPsuWattage(normalizedText, compactNormalizedText)
        val wattageDigits = wattage?.filter(Char::isDigit)

        val rawModel = Regex("""(?i)\b([a-z]{1,4}\d{3,4}w)\b""")
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()
            ?: Regex("""([A-Z]{1,4}\d{3,4}W)""")
                .find(compactNormalizedText)
                ?.groupValues
                ?.getOrNull(1)
        val model = rawModel?.takeUnless { candidate ->
            val candidateDigits = candidate.filter(Char::isDigit)
            val prefix = candidate.takeWhile(Char::isLetter)
            wattageDigits != null &&
                candidateDigits == wattageDigits &&
                prefix.isNotBlank()
        }

        val efficiencyLabel = extractPsuEfficiencyLabel(normalizedText, compactNormalizedText)
        val hasEightyPlus = efficiencyLabel != null

        val hasPsuSignal = Regex("""(?i)\b(?:psu|power\s+supply)\b""").containsMatchIn(normalizedText)
        val psuSignalScore =
            (if (wattage != null) 4 else 0) +
                (if (model != null) 2 else 0) +
                (if (hasEightyPlus) 3 else 0) +
                (if (hasPsuSignal) 1 else 0)

        if (wattage == null || psuSignalScore < 4) {
            return null
        }

        val labelParts = listOfNotNull(
            wattage,
            efficiencyLabel,
            model
        )

        return labelParts.joinToString(" ").trim().takeIf { it.isNotBlank() }
    }

    private fun extractRamKitCapacity(normalizedText: String, compactNormalizedText: String): String? {
        val spacedMatch = Regex("""(?i)\b(\d{1,4})\s?gb\s*kit\b""")
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
        if (spacedMatch != null) {
            return normalizeRamCapacity(spacedMatch, VALID_RAM_TOTAL_CAPACITIES)?.let { "${it}GB" }
        }

        val compactMatch = Regex("""(?:KIT)?(\d{1,4})GB(?:KIT)?""")
            .find(compactNormalizedText)
            ?.groupValues
            ?.getOrNull(1)
        return compactMatch?.let {
            normalizeRamCapacity(it, VALID_RAM_TOTAL_CAPACITIES)?.let { normalized -> "${normalized}GB" }
        }
    }

    private fun extractRamModuleConfig(normalizedText: String, compactNormalizedText: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("""(?i)\b(\d)\s*[xX*]\s*(\d{1,3})\s?gb\b"""),
            Regex("""(?i)\b(\d)\s*[xX*]\s*(\d{1,3})\b""")
        )

        val normalizedMatch = patterns.asSequence()
            .mapNotNull { regex -> regex.find(normalizedText)?.groupValues }
            .firstOrNull()
            ?.let(::toRamModuleConfig)
        if (normalizedMatch != null) {
            return normalizedMatch
        }

        val compactMatch = Regex("""(\d)[X*](\d{1,3})(?:GB)?""")
            .find(compactNormalizedText)
            ?.groupValues
            ?.let(::toRamModuleConfig)
        return compactMatch
    }

    private fun toRamModuleConfig(values: List<String>): Pair<String, String>? {
        val modules = values.getOrNull(1)?.toIntOrNull()
        val eachCapacity = values.getOrNull(2)?.let {
            normalizeRamCapacity(it, VALID_RAM_MODULE_CAPACITIES)
        }
        if (modules == null || eachCapacity == null) {
            return null
        }

        val totalCapacity = normalizeRamCapacity(
            (modules * eachCapacity).toString(),
            VALID_RAM_TOTAL_CAPACITIES
        ) ?: return null
        return "${modules}x${eachCapacity}GB" to "${totalCapacity}GB"
    }

    private fun extractLargestCapacityGb(normalizedText: String): String? {
        return Regex("""(?i)\b(\d{1,4})\s?gb\b""")
            .findAll(normalizedText)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .mapNotNull { normalizeRamCapacity(it, VALID_RAM_TOTAL_CAPACITIES) }
            .maxOrNull()
            ?.let { "${it}GB" }
    }

    private fun normalizeRamCapacity(rawValue: String, validCapacities: Set<Int>): Int? {
        val digits = rawValue.filter(Char::isDigit)
        if (digits.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates += digits

        val collapsedRepeats = digits.fold(StringBuilder()) { builder, char ->
            if (builder.isEmpty() || builder.last() != char) {
                builder.append(char)
            }
            builder
        }.toString()
        if (collapsedRepeats.isNotBlank()) {
            candidates += collapsedRepeats
        }

        if (digits.length > 1) {
            digits.indices.forEach { index ->
                val shortened = digits.removeRange(index, index + 1)
                if (shortened.isNotBlank()) {
                    candidates += shortened
                }
            }
        }

        val numericCandidates = candidates
            .mapNotNull { it.toIntOrNull() }
            .distinct()

        numericCandidates.firstOrNull { it in validCapacities }?.let { return it }

        return numericCandidates
            .flatMap { candidate -> validCapacities.map { valid -> valid to kotlin.math.abs(valid - candidate) } }
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()
            ?.first
    }

    private fun normalizeSpeedValue(value: String): String {
        val compact = value.uppercase().replace(" ", "")
        val numericSpeed = Regex("""(2[1-9]\d{2}|[3-8]\d{3})""")
            .find(compact)
            ?.value
        return when {
            compact.endsWith("MHZ") || compact.endsWith("MT/S") -> compact
            numericSpeed != null -> "${numericSpeed}MHZ"
            compact.all(Char::isDigit) -> "${compact}MHZ"
            else -> compact.filter(Char::isDigit).takeIf { it.length == 4 }?.let { "${it}MHZ" } ?: compact
        }
    }

    private fun extractPsuWattage(normalizedText: String, compactNormalizedText: String): String? {
        val wattageDigits = Regex("""(?i)\b(\d{3,4})\s?(?:w|watt|watts)\b""")
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""(\d{3,4})(?:W|WATT|WATTS)""")
                .find(compactNormalizedText)
                ?.groupValues
                ?.getOrNull(1)

        return wattageDigits?.let { "${it}W" }
    }

    private fun extractPsuEfficiencyLabel(normalizedText: String, compactNormalizedText: String): String? {
        val rating = Regex("""(?i)\b(?:bronze|silver|gold|platinum|titanium)\b""")
            .find(normalizedText)
            ?.value
            ?.uppercase()
            ?: Regex("""(BRONZE|SILVER|GOLD|PLATINUM|TITANIUM)""")
                .find(compactNormalizedText)
                ?.groupValues
                ?.getOrNull(1)

        val hasEightyPlusBase = Regex("""(?i)\b80\s*(?:\+|plus)\b""").containsMatchIn(normalizedText) ||
            compactNormalizedText.contains("80PLUS")

        return when {
            rating != null && hasEightyPlusBase -> "80+ $rating"
            rating != null -> rating
            hasEightyPlusBase -> "80+"
            else -> null
        }
    }

    private fun parseRamProgress(scannedText: String): RamProgress? {
        val normalizedText = scannedText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val compactNormalizedText = normalizedText
            .uppercase()
            .replace(Regex("""[\s:;,.|/_-]+"""), "")

        val type = Regex("""(?i)\bddr[345]\b""")
            .find(normalizedText)
            ?.value
            ?.uppercase()
            ?: return null

        val speed = Regex("""(?i)\bB?(?:2[1-9]\d{2}|[3-8]\d{3})\s?(?:mhz|mt/s)?\b""")
            .findAll(normalizedText)
            .map { normalizeSpeedValue(it.value) }
            .firstOrNull()

        val moduleConfigMatch = extractRamModuleConfig(normalizedText, compactNormalizedText)
        val moduleConfig = moduleConfigMatch?.first
        val kitCapacity = extractRamKitCapacity(normalizedText, compactNormalizedText)
        val totalCapacity = moduleConfigMatch?.second ?: kitCapacity ?: extractLargestCapacityGb(normalizedText)

        return RamProgress(
            type = type,
            speed = speed,
            moduleConfig = moduleConfig,
            totalCapacity = totalCapacity
        )
    }

    private fun parsePsuProgress(scannedText: String): PsuProgress? {
        val normalizedText = scannedText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val compactNormalizedText = normalizedText
            .uppercase()
            .replace(Regex("""[\s:;,.|/_-]+"""), "")

        val wattage = extractPsuWattage(normalizedText, compactNormalizedText) ?: return null
        val efficiencyLabel = extractPsuEfficiencyLabel(normalizedText, compactNormalizedText)

        return PsuProgress(
            wattage = wattage,
            efficiencyLabel = efficiencyLabel
        )
    }

    private fun extractMeaningfulTokens(value: String): List<String> {
        return value
            .replace(".", "")
            .split(Regex("[^a-z0-9+/.-]+"))
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    (it.any(Char::isDigit) || it.length >= 3)
            }
            .distinct()
    }

    private fun appendCategorySuffix(name: String, category: String?): String {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return cleanName
        val catUpper = (category ?: RecommendationMockData.resolveCategory(cleanName)).uppercase()
        val suffix = when (catUpper) {
            "CPU" -> " CPU"
            "GPU" -> " GPU"
            "MOTHERBOARD" -> " Motherboard"
            "RAM" -> " RAM"
            "STORAGE" -> " SSD"
            "PSU" -> " PSU"
            else -> ""
        }
        if (suffix.isBlank()) return cleanName
        val alreadyHas = Regex("(?i)\\b${suffix.trim()}\\b").containsMatchIn(cleanName)
        return if (alreadyHas) cleanName else "$cleanName$suffix"
    }

    private fun isFastTrackCandidate(displayText: String): Boolean {
        val lower = displayText.lowercase()
        val hasBrand = FAST_TRACK_BRANDS.any { brand -> lower.contains(brand) }
        val hasModelDigits = Regex("""\d{3,}""").containsMatchIn(lower)
        return hasBrand && hasModelDigits
    }

    private fun extractFirstBrandHint(scannedText: String): String? {
        val lower = scannedText.lowercase()
        val tokens = extractMeaningfulTokens(lower)
        for (brand in FAST_TRACK_BRANDS) {
            if (tokens.any { it == brand || it.contains(brand) || brand.contains(it) }) {
                val re = Regex("""(?i)\b${Regex.escape(brand)}\w*\b""")
                val match = re.find(scannedText) ?: continue
                return match.value
            }
        }
        val tokenWithCap = Regex("""(?i)\b([a-z][a-z0-9]{2,}(?:thor|aorus|strix|tuf|tforce|vengeance|trident|gskill|ripjaws|ballistix|kingston|fury|predator|corsair|samsung|wd|western digital|lexar|adata|teamgroup|crucial|seagate|seasonic|evga|asus|msi|gigabyte|asrock|zotac|sapphire|powercolor|xfx|palit|inno3d|galax|pny)[a-z0-9]*)\b""")
            .find(scannedText)
        return tokenWithCap?.groupValues?.getOrNull(1)
    }

    private fun bestPreviewFragment(text: String): String? {
        val cleaned = text.trim().replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank()) return null
        val tokens = extractMeaningfulTokens(cleaned.lowercase())
        val hasAnyMeaning = tokens.size >= 2 || tokens.any { it.any(Char::isDigit) && it.length >= 2 }
        if (!hasAnyMeaning) return null
        val firstLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: cleaned
        val frag = if (firstLine.length <= 60) firstLine else firstLine.take(57) + "..."
        if (frag.isBlank()) return null
        return frag
    }

    private fun updateStatus(message: String) {
        if (!isAdded) return
        if (lastStatusMessage == message) return
        lastStatusMessage = message
        requireActivity().runOnUiThread {
            _binding?.tvScanningHint?.text = message
        }
    }

    private fun showFriendlyError(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        viewAlive = false
        cameraProvider?.unbindAll()
        camera = null
        clearPendingCandidate()
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        recognizer.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private data class OcrDetectionCandidate(
        val displayText: String,
        val category: String
    )

    private data class RamProgress(
        val type: String,
        val speed: String?,
        val moduleConfig: String?,
        val totalCapacity: String?
    )

    private data class PsuProgress(
        val wattage: String,
        val efficiencyLabel: String?
    )

    companion object {
        private const val REQUIRED_STABLE_FRAMES = 3
        private const val MAX_GEMINI_AUTO_RETRIES = 5
        private val VALID_RAM_MODULE_CAPACITIES = setOf(4, 8, 16, 24, 32, 48, 64)
        private val VALID_RAM_TOTAL_CAPACITIES = setOf(4, 8, 16, 24, 32, 48, 64, 96, 128)
        private val FAST_TRACK_BRANDS = setOf("ryzen","intel","core","amd","nvidia","geforce","rtx","rx","arc","asus","msi","gigabyte","asrock","aorus","strix","tuf","corsair","gskill","trident","vengeance","samsung","wd","crucial","seagate","kingston","seasonic","evga","zotac","palit","powercolor","sapphire","xfx","inno3d","galax","pny","adata","lexar","teamgroup","fractal","nzxt","lianli","noctua","bequiet","coolermaster","deepcool","thermalright","arctic","phanteks","montech","hyte","tecware","fsp")
    }
}
