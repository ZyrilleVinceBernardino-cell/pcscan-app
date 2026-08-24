package com.pcscan.pcscan.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.navOptions
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentOcrScanningBinding
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

class OcrScanningFragment : Fragment() {

    private var _binding: FragmentOcrScanningBinding? = null
    private val binding get() = _binding!!

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
    private var pendingCandidate: OcrDetectionCandidate? = null
    private var stableDetectionFrames = 0

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
        setupPreviewContainer()
        updateStatus("Point the camera at the PC component box. Tap to focus.")

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
        cameraProvider?.unbindAll()
        camera = null
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
            },
            ContextCompat.getMainExecutor(requireContext())
        )
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
        if (mediaImage == null || isProcessingFrame || isResolvingComponent || hasNavigated) {
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
                    if (ramProgress == null && psuProgress == null) {
                        updateStatus("Scanning for a CPU, GPU, RAM, motherboard, or storage label...")
                    } else if (ramProgress != null) {
                        updateStatus(
                            when {
                                ramProgress.speed == null ->
                                    "Detected ${ramProgress.type}. Looking for speed (ex: 3200MHz)..."

                                ramProgress.moduleConfig == null && ramProgress.totalCapacity == null ->
                                    "Detected ${ramProgress.type} ${ramProgress.speed}. Looking for kit config (ex: 2x8GB, 2x16GB)..."

                                else ->
                                    "Detected ${ramProgress.type} ${ramProgress.speed} ${ramProgress.moduleConfig ?: ""} ${ramProgress.totalCapacity ?: ""}. Hold steady..."
                            }.trim()
                        )
                    } else if (psuProgress != null) {
                        updateStatus(
                            when {
                                psuProgress.efficiencyLabel == null ->
                                    "Detected ${psuProgress.wattage}. Looking for 80 Plus rating (Bronze/Gold/etc)..."

                                else ->
                                    "Detected ${psuProgress.wattage} ${psuProgress.efficiencyLabel}. Hold steady..."
                            }
                        )
                    }
                    return@addOnSuccessListener
                }

                if (!isResolvingComponent && !hasNavigated) {
                    val isStableCandidate = registerDetectedCandidate(detectedCandidate)
                    if (!isStableCandidate) {
                        updateStatus(
                            "Detected \"${pendingCandidate?.displayText ?: detectedCandidate.displayText}\". " +
                                "Hold steady... ($stableDetectionFrames/$REQUIRED_STABLE_FRAMES)"
                        )
                        return@addOnSuccessListener
                    }

                    val confirmedCandidate = pendingCandidate ?: detectedCandidate
                    isResolvingComponent = true
                    updateStatus("Detected \"${confirmedCandidate.displayText}\". Looking for matches...")
                    resolveDetectedComponent(confirmedCandidate)
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
        if (currentPending == null || currentPending.category != candidate.category) {
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
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val searchKey = when (detectedCandidate.category) {
                    "motherboard" -> "motherboard"
                    "psu" -> "psu"
                    else -> detectedCandidate.displayText
                }
                val searchResults = repository.getSearchResults(searchKey)
                val resolvedComponent = findBestMatch(detectedCandidate.displayText, searchResults)
                if (resolvedComponent == null) {
                    updateStatus("No matching component found yet. Try moving closer.")
                    return@launch
                }

                hasNavigated = true
                cameraProvider?.unbindAll()
                val bundle = Bundle().apply {
                    putString("detectedText", detectedCandidate.displayText)
                    putString("selectedComponent", resolvedComponent)
                    putString("searchQuery", detectedCandidate.displayText)
                }
                findNavController().navigate(
                    R.id.action_ocrScanningFragment_to_ocrResultFragment,
                    bundle
                )
            }.onFailure {
                showFriendlyError("We could not search for that component right now.")
            }

            isResolvingComponent = false
        }
    }

    private fun findBestMatch(detectedComponent: String, results: List<String>): String? {
        if (results.isEmpty() || results.all { it.startsWith("Sample Component") }) {
            return null
        }

        val normalizedDetected = detectedComponent.lowercase()
        val detectedTokens = extractMeaningfulTokens(normalizedDetected)

        val scored = results
            .map { candidate ->
                val normalizedCandidate = candidate.lowercase()
                val candidateTokens = extractMeaningfulTokens(normalizedCandidate)
                val score = detectedTokens.count { token ->
                    candidateTokens.any { candidateToken ->
                        candidateToken == token ||
                            candidateToken.contains(token) ||
                            token.contains(candidateToken)
                    }
                }
                candidate to score
            }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        val best = scored.firstOrNull()
        return when {
            best == null -> null
            best.second > 0 -> best.first
            else -> detectedComponent
        }
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

    private fun updateStatus(message: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            _binding?.tvScanningHint?.text = message
        }
    }

    private fun showFriendlyError(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
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
        private val VALID_RAM_MODULE_CAPACITIES = setOf(4, 8, 16, 24, 32, 48, 64)
        private val VALID_RAM_TOTAL_CAPACITIES = setOf(4, 8, 16, 24, 32, 48, 64, 96, 128)
    }
}
