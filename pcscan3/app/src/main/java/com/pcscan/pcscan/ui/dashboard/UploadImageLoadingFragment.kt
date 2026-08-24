package com.pcscan.pcscan.ui.dashboard

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.navOptions
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUploadImageLoadingBinding
import kotlinx.coroutines.launch

class UploadImageLoadingFragment : Fragment() {

    private var _binding: FragmentUploadImageLoadingBinding? = null
    private val binding get() = _binding!!
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val repository: RecommendationRepository = RecommendationRepositoryProvider.current

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUploadImageLoadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imageUri = arguments?.getString(ARG_SELECTED_IMAGE_URI).orEmpty()
        if (imageUri.isBlank()) {
            Toast.makeText(requireContext(), "No image selected.", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        binding.tvLoadingTitle.text = "Build Smarter. Upgrade Better."
        binding.tvLoadingMessage.text = "Initializing AI Engine..."

        if (savedInstanceState == null) {
            processSelectedImage(imageUri)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    private fun processSelectedImage(imageUri: String) {
        val parsedUri = Uri.parse(imageUri)
        val inputImage = runCatching {
            InputImage.fromFilePath(requireContext(), parsedUri)
        }.getOrElse {
            showFriendlyError("Unable to open the selected image.")
            findNavController().popBackStack()
            return
        }

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val scannedText = result.text
                val detectedCandidate = extractDetectionCandidate(scannedText)

                viewLifecycleOwner.lifecycleScope.launch {
                    val resolvedComponent = detectedCandidate?.let { resolveDetectedComponent(it) }
                    if (resolvedComponent == null) {
                        // No component detected, show toast and go back
                        showFriendlyError("No computer parts detected. Please upload a clear image of RAM or PSU.")
                        findNavController().popBackStack()
                    } else {
                        // Proceed to result
                        navigateToResult(
                            imageUri = imageUri,
                            detectedText = detectedCandidate?.displayText ?: scannedText,
                            selectedComponent = resolvedComponent,
                            searchQuery = detectedCandidate?.displayText.orEmpty()
                        )
                    }
                }
            }
            .addOnFailureListener {
                showFriendlyError("Unable to read text from the selected image.")
                findNavController().popBackStack()
            }
    }

    private fun navigateToResult(
        imageUri: String,
        detectedText: String,
        selectedComponent: String?,
        searchQuery: String
    ) {
        if (!isAdded) return
        findNavController().navigate(
            R.id.action_uploadImageLoadingFragment_to_uploadImageResultFragment,
            Bundle().apply {
                putString(ARG_SELECTED_IMAGE_URI, imageUri)
                putString(ARG_DETECTED_TEXT, detectedText)
                putString(ARG_SELECTED_COMPONENT, selectedComponent.orEmpty())
                putString(ARG_SEARCH_QUERY, searchQuery)
            },
            navOptions {
                popUpTo(R.id.uploadImageLoadingFragment) { inclusive = true }
            }
        )
    }

    private fun extractDetectionCandidate(scannedText: String): UploadDetectionCandidate? {
        val displayText = extractComponentName(scannedText) ?: return null
        return UploadDetectionCandidate(
            displayText = displayText,
            category = detectCategory(scannedText, displayText)
        )
    }

    private fun extractComponentName(scannedText: String): String? {
        val normalizedText = scannedText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val compactNormalizedText = normalizedText
            .uppercase()
            .replace(Regex("""[\s:;,.|/_-]+"""), "")

        val psuLabel = extractPsuLabel(normalizedText, compactNormalizedText)
        if (psuLabel != null) {
            return psuLabel
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

        return null
    }

    private fun detectCategory(scannedText: String, displayText: String): String {
        val normalized = (scannedText + " " + displayText).lowercase()
        val looksLikePsu = Regex("""(?i)\b(?:psu|power\s+supply|80\+|80\s+plus)\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b\d{3,4}\s?(?:w|watt|watts)\b""").containsMatchIn(normalized)

        return when {
            looksLikePsu -> "psu"
            else -> RecommendationMockData.resolveCategory(displayText)
        }
    }

    private suspend fun resolveDetectedComponent(detectedCandidate: UploadDetectionCandidate): String? {
        return runCatching {
            val searchKey = when (detectedCandidate.category) {
                "psu" -> "psu"
                else -> detectedCandidate.displayText
            }
            val searchResults = repository.getSearchResults(searchKey)
            findBestMatch(detectedCandidate.displayText, searchResults)
        }.getOrNull()
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

        return listOfNotNull(
            wattage,
            efficiencyLabel,
            model
        ).joinToString(" ").trim().takeIf { it.isNotBlank() }
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

    private fun showFriendlyError(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_SELECTED_IMAGE_URI = "selectedImageUri"
        private const val ARG_DETECTED_TEXT = "detectedText"
        private const val ARG_SELECTED_COMPONENT = "selectedComponent"
        private const val ARG_SEARCH_QUERY = "searchQuery"
        private val VALID_RAM_MODULE_CAPACITIES = setOf(4, 8, 16, 24, 32, 48, 64)
        private val VALID_RAM_TOTAL_CAPACITIES = setOf(4, 8, 16, 24, 32, 48, 64, 96, 128)
    }

    private data class UploadDetectionCandidate(
        val displayText: String,
        val category: String
    )

    private data class RamProgress(
        val type: String,
        val speed: String?,
        val moduleConfig: String?,
        val totalCapacity: String?
    )
}
