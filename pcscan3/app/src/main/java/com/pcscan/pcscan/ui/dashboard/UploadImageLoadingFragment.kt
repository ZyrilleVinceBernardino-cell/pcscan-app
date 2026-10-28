package com.pcscan.pcscan.ui.dashboard

import android.graphics.BitmapFactory
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
import com.pcscan.pcscan.data.api.GeminiApiService
import com.pcscan.pcscan.databinding.FragmentUploadImageLoadingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

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

        setLoadingProgress(progress = 4)

        if (savedInstanceState == null) {
            viewLifecycleOwner.lifecycleScope.launch {
                processSelectedImageGeminiFirst(imageUri)
            }
        }
    }

    private fun setLoadingProgress(
        progress: Int,
        titleOverride: String? = null,
        subtitleOverride: String? = null
    ) {
        if (!isAdded || _binding == null) return
        val clamped = progress.coerceIn(0, 100)
        if (titleOverride != null) {
            binding.tvLoadingTitle.text = titleOverride
        } else {
            binding.tvLoadingTitle.text = if (clamped >= 55) "Almost done..." else "Checking component..."
        }
        if (subtitleOverride != null) {
            binding.tvLoadingSubtitle.text = subtitleOverride
        } else {
            binding.tvLoadingSubtitle.text = if (clamped >= 55) {
                "Preparing confirmation screen..."
            } else {
                "Identifying PC component from your uploaded image"
            }
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

    private suspend fun processSelectedImageGeminiFirst(imageUri: String) = withContext(Dispatchers.IO) {
        val parsedUri = runCatching { Uri.parse(imageUri) }.getOrNull()
        if (parsedUri == null) {
            withContext(Dispatchers.Main) {
                showFriendlyError("Unable to open the selected image.")
                findNavController().popBackStack()
            }
            return@withContext
        }
        val inputImage = runCatching {
            InputImage.fromFilePath(requireContext(), parsedUri)
        }.getOrNull()
        if (inputImage == null) {
            withContext(Dispatchers.Main) {
                showFriendlyError("Unable to open the selected image.")
                findNavController().popBackStack()
            }
            return@withContext
        }
        val imageBytes = readImageUriToBytesCompressed(parsedUri)
        val hasGeminiKey = runCatching { GeminiApiService.hasApiKey() }.getOrDefault(false)

        if (hasGeminiKey && imageBytes != null) {
            withContext(Dispatchers.Main) {
                setLoadingProgress(progress = 35)
            }
            val visionComponents = runCatching {
                withTimeoutOrNull(15_000) {
                    repository.analyzeImageForComponentsVisionOnly(imageBytes)
                }
            }.getOrNull()
            val visionPick = visionComponents?.firstOrNull()
            if (visionPick != null && visionPick.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    setLoadingProgress(
                        progress = 92,
                        subtitleOverride = "Found: \"$visionPick\". Preparing confirmation screen..."
                    )
                    val displayNames = visionComponents.map { appendCategorySuffix(it, null) }
                    val pickedDisplay = displayNames.firstOrNull()
                        ?: appendCategorySuffix(visionPick, null)
                    navigateToResult(
                        imageUri = imageUri,
                        detectedText = pickedDisplay,
                        selectedComponent = visionPick,
                        searchQuery = visionPick,
                        category = null
                    )
                }
                return@withContext
            }
        }

        withContext(Dispatchers.Main) {
            setLoadingProgress(progress = 65)
        }
        val mlKitResult = runCatching {
            withTimeoutOrNull(10_000) {
                recognizer.process(inputImage).await()
            }
        }.getOrNull()
        val scannedText = mlKitResult?.text.orEmpty()
        val detectedCandidate = if (scannedText.isNotBlank()) extractDetectionCandidate(scannedText) else null

        withContext(Dispatchers.Main) {
            setLoadingProgress(progress = 68)
        }
        val resolvedFromMl = if (detectedCandidate != null) {
            runCatching { withContext(Dispatchers.IO) { resolveDetectedComponent(detectedCandidate) } }.getOrNull()
        } else null

        if (resolvedFromMl != null && resolvedFromMl.isNotBlank()) {
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                setLoadingProgress(
                    progress = 92,
                    subtitleOverride = "DB match found: \"$resolvedFromMl\". Preparing confirmation screen..."
                )
                navigateToResult(
                    imageUri = imageUri,
                    detectedText = detectedCandidate?.displayText ?: scannedText,
                    selectedComponent = resolvedFromMl,
                    searchQuery = detectedCandidate?.displayText.orEmpty(),
                    category = detectedCandidate?.category
                )
            }
            return@withContext
        }

        if (hasGeminiKey && imageBytes != null) {
            withContext(Dispatchers.Main) {
                setLoadingProgress(progress = 78)
            }
            val strictResult = runCatching {
                withTimeoutOrNull(30_000) {
                    repository.analyzeImageForComponents(imageBytes)
                }
            }.getOrNull()
            val strictPick = strictResult?.firstOrNull()
            if (strictPick != null && strictPick.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    setLoadingProgress(
                        progress = 92,
                        subtitleOverride = "AI pipeline identified \"$strictPick\". Preparing confirmation screen..."
                    )
                    val displayNames = strictResult.map { appendCategorySuffix(it, null) }
                    val pickedDisplay = displayNames.firstOrNull()
                        ?: appendCategorySuffix(strictPick, null)
                    navigateToResult(
                        imageUri = imageUri,
                        detectedText = pickedDisplay,
                        selectedComponent = strictPick,
                        searchQuery = strictPick,
                        category = null
                    )
                }
                return@withContext
            }
        }

        if (detectedCandidate?.displayText?.isNotBlank() == true) {
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                setLoadingProgress(
                    progress = 92,
                    subtitleOverride = "Exact DB match not found, but local OCR saw \"${detectedCandidate.displayText}\". Showing for your confirmation..."
                )
                val display = appendCategorySuffix(detectedCandidate.displayText, detectedCandidate.category)
                navigateToResult(
                    imageUri = imageUri,
                    detectedText = display,
                    selectedComponent = detectedCandidate.displayText,
                    searchQuery = detectedCandidate.displayText,
                    category = detectedCandidate.category
                )
            }
            return@withContext
        }

        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            showFriendlyError("AI Vision + ML Kit OCR both couldn't read a clear component. Try re-uploading a clearer, well-lit photo of the box label.")
            val didPop = runCatching { findNavController().popBackStack(R.id.uploadImageFragment, false) }.getOrDefault(false)
            if (!didPop) {
                runCatching { findNavController().popBackStack() }
            }
        }
    }

    private suspend fun readImageUriToBytesCompressed(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = runCatching {
                val stream = requireContext().contentResolver.openInputStream(uri) ?: return@runCatching -1 to -1
                stream.use { input ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    opts.outWidth to opts.outHeight
                }
            }.getOrDefault(-1 to -1)
            val w = bounds.first.coerceAtLeast(1)
            val h = bounds.second.coerceAtLeast(1)
            val maxDim = 2400
            val reopen = requireContext().contentResolver.openInputStream(uri) ?: return@runCatching null
            reopen.use { input ->
                val opts = BitmapFactory.Options()
                val ratio = maxOf(w, h).toFloat() / maxDim.toFloat()
                opts.inSampleSize = if (ratio <= 1f) 1 else Integer.highestOneBit(ratio.toInt().coerceAtLeast(1)).coerceAtMost(4)
                val bitmap = BitmapFactory.decodeStream(input, null, opts) ?: return@runCatching null
                val baos = ByteArrayOutputStream(bitmap.allocationByteCount.coerceAtMost(8 * 1024 * 1024))
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, baos)
                bitmap.recycle()
                baos.toByteArray()
            }
        }.getOrNull()
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

    private fun navigateToResult(
        imageUri: String,
        detectedText: String,
        selectedComponent: String?,
        searchQuery: String,
        category: String? = null
    ) {
        if (!isAdded) return
        val displayDetected = appendCategorySuffix(
            selectedComponent?.takeIf { it.isNotBlank() } ?: detectedText,
            category
        )
        findNavController().navigate(
            R.id.action_uploadImageLoadingFragment_to_uploadImageResultFragment,
            Bundle().apply {
                putString(ARG_SELECTED_IMAGE_URI, imageUri)
                putString(ARG_DETECTED_TEXT, displayDetected)
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
            Regex("""(?i)\bcore\s+i[3579][- ]?\d{4,5}[a-z]{0,2}\b"""),
            Regex("""(?i)\brtx\s+\d{3,4}(?:\s?(?:ti|super))?\b"""),
            Regex("""(?i)\brx\s+\d{4}\s?(?:xt|xtx|gre)?\b"""),
            Regex("""(?i)\b(?:arc|gtx)\s+\d{3,4}[a-z0-9 ]{0,4}\b""")
        )

        val directMatch = primaryPatterns.asSequence()
            .mapNotNull { pattern -> pattern.find(normalizedText)?.value }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull()

        if (directMatch != null) {
            return directMatch
        }

        val compactPrimaryPatterns = listOf(
            Regex("""(?:RTX|GTX)(\d{3,4})(TI|SUPER)?"""),
            Regex("""(?:RYZEN)([3579])(\d{4,5}[A-Z0-9]{0,3})"""),
            Regex("""(?:COREI[3579])(\d{4,5}[A-Z]{0,2})"""),
            Regex("""RX(\d{4})(XT|XTX|GRE)?"""),
            Regex("""ARC(\d{3,4}[A-Z0-9]{0,4})""")
        )

        val compactDirectMatch = compactPrimaryPatterns.asSequence()
            .mapNotNull { pattern -> pattern.find(compactNormalizedText)?.value }
            .map { raw ->
                when {
                    raw.startsWith("RTX") || raw.startsWith("GTX") -> {
                        val letters = raw.takeWhile(Char::isLetter)
                        val rest = raw.removePrefix(letters)
                        val suffixIdx = rest.indexOfAny(charArrayOf('T', 'S'), 1)
                        if (suffixIdx > 0) {
                            val num = rest.substring(0, suffixIdx)
                            val suf = rest.substring(suffixIdx)
                            "$letters $num $suf".trim()
                        } else {
                            "$letters $rest".trim()
                        }
                    }
                    raw.startsWith("RYZEN") -> {
                        val tier = raw[6].toString()
                        val rest = raw.substring(7)
                        "Ryzen $tier $rest".trim()
                    }
                    raw.startsWith("COREI") && raw.length >= 6 -> {
                        val tier = raw[5].toString()
                        val rest = raw.substring(6)
                        "Core i$tier $rest".trim()
                    }
                    raw.startsWith("RX") -> {
                        val num = raw.drop(2).takeWhile(Char::isDigit)
                        val suf = raw.drop(2 + num.length)
                        if (suf.isNotBlank()) "RX $num $suf".trim() else "RX $num".trim()
                    }
                    raw.startsWith("ARC") -> "ARC ${raw.drop(3)}".trim()
                    else -> raw
                }
            }
            .firstOrNull()

        if (compactDirectMatch != null) {
            return compactDirectMatch
        }

        val ramProgress = parseRamProgress(scannedText)
        val hasRamKeyword = Regex("""(?i)\bram\b""").containsMatchIn(normalizedText)
        val hasKitKeyword = Regex("""(?i)\bkit\b""").containsMatchIn(normalizedText) || compactNormalizedText.contains("KIT")
        val hasXmpKeyword = Regex("""(?i)\bxmp\b""").containsMatchIn(normalizedText)
        val hasExpoKeyword = Regex("""(?i)\bexpo\b""").containsMatchIn(normalizedText)
        val hasDdrKeyword = Regex("""(?i)\bddr[345]\b""").containsMatchIn(normalizedText)
        val ramSignalScore =
            (if (ramProgress != null) 4 else 0) +
                (if (ramProgress?.moduleConfig != null) 3 else 0) +
                (if (ramProgress?.totalCapacity != null) 2 else 0) +
                (if (ramProgress?.speed != null) 1 else 0) +
                (if (hasKitKeyword) 1 else 0) +
                (if (hasXmpKeyword) 1 else 0) +
                (if (hasRamKeyword) 1 else 0) +
                (if (hasExpoKeyword) 1 else 0) +
                (if (hasDdrKeyword && ramProgress != null) 1 else 0)

        val canBuildRamLabel = ramProgress != null &&
            (ramProgress.totalCapacity != null) &&
            (ramProgress.speed != null || hasDdrKeyword)
        if (ramSignalScore >= 2 && canBuildRamLabel) {
            val resolvedSpeed = ramProgress?.speed
                ?: listOfNotNull(ramProgress?.type, "3600MHZ").joinToString(" ").trim()
            return if (ramProgress?.moduleConfig != null) {
                listOf(
                    ramProgress.type,
                    resolvedSpeed,
                    ramProgress.moduleConfig,
                    ramProgress.totalCapacity,
                    "KIT"
                ).joinToString(" ").trim()
            } else {
                listOf(
                    ramProgress?.type,
                    resolvedSpeed,
                    ramProgress?.totalCapacity,
                    "SINGLE"
                ).joinToString(" ").trim()
            }
        }

        val storageTypeMatches = Regex("""(?i)\b(?:nvme|ssd|m\.2|sata|hdd)\b""")
            .findAll(normalizedText)
            .map { it.value.uppercase().replace(".", "") }
            .distinct()
            .toList()
        val storageCapacity = Regex("""(?i)\b(?:120|128|240|250|256|480|500|512|960|1000|1024|1|2|4|6|8|10|12|16|20)\s?(?:gb|tb)\b""")
            .find(normalizedText)
            ?.value
            ?.uppercase()
            ?.replace(" ", "")

        val storageBrandMatches = Regex("""(?i)\b(?:samsung|wd|western\s*digital|crucial|kingston|adata|seagate|lexar|sabrent|firecuda|sn\d{3,4}|9\d{2,3}\s*(?:pro|evo)|8\d{2,3}\s*(?:evo|pro)|wd_black|wd black)\b""")
            .findAll(normalizedText)
            .map { it.value.trim() }
            .distinct()
            .toList()

        val storageSignalScore =
            storageTypeMatches.size * 3 +
                (if (storageCapacity != null) 3 else 0) +
                storageBrandMatches.size.coerceAtMost(2) * 2

        if (storageSignalScore > ramSignalScore && (storageTypeMatches.isNotEmpty() || storageBrandMatches.isNotEmpty())) {
            val brandChunk = storageBrandMatches.firstOrNull()?.uppercase()?.replace("_", " ")
            val firstType = storageTypeMatches.firstOrNull()
            val parts = listOfNotNull(brandChunk, firstType, storageCapacity)
            if (parts.isNotEmpty()) {
                return parts.joinToString(" ").trim()
            }
        }

        val genericBrandPlusModel = extractGenericBrandModel(normalizedText)
        if (genericBrandPlusModel != null) {
            return genericBrandPlusModel
        }

        return null
    }

    private fun extractGenericBrandModel(normalizedText: String): String? {
        val knownBrands = listOf(
            "asus", "msi", "gigabyte", "asrock", "zotac", "palit", "powercolor", "sapphire", "xfx",
            "inno3d", "galax", "pny", "nvidia", "amd", "intel", "corsair", "g.skill", "gskill",
            "kingston", "teamgroup", "t-force", "tforce", "adata", "lexar", "crucial", "samsung",
            "seagate", "wd", "western digital", "seasonic", "evga", "cooler master", "be quiet",
            "bequiet", "nzxt", "fractal", "lian li", "lianli", "tecware", "fsp", "sapphire",
            "aorus", "strix", "tuf", "prime", "proart", "tomahawk", "mortar", "maximus", "crosshair",
            "vengeance", "trident", "ripjaws", "ballistix", "fury", "predator", "thor", "dominator",
            "focus", "rm", "sf", "hx", "cx"
        )

        val lower = normalizedText.lowercase()
        for (brand in knownBrands) {
            val idx = lower.indexOf(brand)
            if (idx < 0) continue
            val afterBrand = normalizedText.substring((idx + brand.length).coerceAtMost(normalizedText.length))
                .trim()
                .takeWhile { it.isLetterOrDigit() || it in " -+/*()." }
                .trim()
                .take(32)
            if (afterBrand.isNotBlank() && afterBrand.any(Char::isDigit)) {
                val normalizedBrand = when (brand.lowercase()) {
                    "gskill" -> "G.Skill"
                    "t-force", "tforce" -> "TeamGroup T-Force"
                    "wd" -> "WD"
                    "western digital" -> "WD"
                    "bequiet" -> "be quiet!"
                    "lian li", "lianli" -> "Lian Li"
                    "cooler master" -> "Cooler Master"
                    "powercolor" -> "PowerColor"
                    else -> brand.replaceFirstChar(Char::uppercase)
                }
                return "$normalizedBrand $afterBrand".trim()
            }
        }
        return null
    }

    private fun extractMotherboardLabel(normalizedText: String, compactNormalizedText: String): String? {
        val hasMotherboardSignals =
            Regex("""(?i)\b(?:motherboard|amd\s+motherboard|intel\s+motherboard|socket\s+am[45]|am[45]\b|lga\s?\d{3,4}|pcie|atx|matx|itx)\b""")
                .containsMatchIn(normalizedText) ||
                Regex("""(?i)\b(?:msi|asus|gigabyte|asrock|aorus|strix|tuf|tomahawk|mortar|prime|pro|proart|gaming)\b""")
                    .containsMatchIn(normalizedText)

        val chipsetRaw = Regex("""(?i)\b([abhzx][0-9o]{3}[m]?)\b""")
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""([ABHZX][0-9O]{3}M?)""")
                .find(compactNormalizedText)
                ?.groupValues
                ?.getOrNull(1)

        val chipset = chipsetRaw
            ?.uppercase()
            ?.replace('O', '0')
            ?.takeIf { it.length >= 4 }

        val suffixCandidates = Regex("""(?i)\b(?:A|PRO|MAX|II|III|WIFI|WIFI6|WIFI6E|PLUS|EDGE|CARBON|MORTAR|TOMAHAWK|GAMING|ELITE|MASTER|AORUS|STRIX|TUF|PRIME|PROART|DS3H|PRO4|ACE|UNIFY|ACE|FORCE|SILVER|STEALTH|SUPER|ULTRA|DREAM|THUNDERBOLT|USB4)\b""")
            .findAll(normalizedText)
            .map { it.value.uppercase() }
            .distinct()
            .toList()

        if (chipset != null) {
            val motherboardSignalScore =
                4 +
                    (if (hasMotherboardSignals) 2 else 0) +
                    (suffixCandidates.size.coerceAtMost(4))

            if (motherboardSignalScore >= 3) {
                val suffix = suffixCandidates.take(5).joinToString(" ").trim()
                return if (suffix.isBlank()) chipset else "$chipset $suffix"
            }
        }

        val brandChipsetCompact = Regex("""(MSI|ASUS|GIGABYTE|ASROCK|AORUS)([BHZX][0-9]{3}[M]?)""")
            .find(compactNormalizedText)
        if (brandChipsetCompact != null) {
            val brand = brandChipsetCompact.groupValues.getOrNull(1).orEmpty()
            val ch = brandChipsetCompact.groupValues.getOrNull(2).orEmpty()
            val suffix = suffixCandidates.take(3).joinToString(" ").trim()
            return listOf(brand, ch, suffix).filter { it.isNotBlank() }.joinToString(" ").trim()
        }

        return null
    }

    private fun detectCategory(scannedText: String, displayText: String): String {
        val normalized = (scannedText + " " + displayText).lowercase()

        val looksLikeMotherboard = Regex("""(?i)\b(?:amd\s+motherboard|socket\s+am[45]|am[45]\b|lga\s?\d{3,4}|motherboard)\b""")
            .containsMatchIn(normalized) ||
            Regex("""(?i)\b(?:b[0-9o]{3}m?|x[0-9o]{3}|z[0-9o]{3}|h[0-9o]{3}|a[0-9o]{3})\b""")
                .containsMatchIn(normalized) ||
            Regex("""(?i)\b(?:msi|asus|gigabyte|asrock|aorus|strix|tuf|tomahawk|mortar|prime|pro|atx|matx|itx)\b""")
                .containsMatchIn(normalized)

        val looksLikePsu = Regex("""(?i)\b(?:psu|power\s+supply|80\+|80\s+plus)\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b\d{3,4}\s?(?:w|watt|watts)\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b[a-z]{1,4}\d{3,4}w\b""").containsMatchIn(normalized)

        val looksLikeCpu = Regex("""(?i)\b(?:ryzen|celeron|pentium|core\s+i[3579]|socket\s+am[45]|processor|cpu\b)""")
            .containsMatchIn(normalized)

        val looksLikeGpu = Regex("""(?i)\b(?:rtx|gtx|radeon|rx\s|geforce|arc|graphics\s+card|gpu\b|vram|dlss|ray\s*tracing)""")
            .containsMatchIn(normalized)

        val looksLikeRam = Regex("""(?i)\b(?:ddr[345]|ram\b|xmp|expo|dimm|mhz|mt/s|kit\s*\d\s*x|memory|vengeance|trident|ripjaws|fury|dominator|ballistix)""")
            .containsMatchIn(normalized)

        val looksLikeStorage = Regex("""(?i)\b(?:nvme|m\.2|ssd|hdd|sata|samsung\s+9|samsung\s+8|wd_black|wd\s+black|crucial\s+(?:t700|t500|mx|p)|firecuda|seagate|lexar|adata|sabrent|sn\d{3,4}|9\d{2,3}\s*pro|8\d{2,3}\s*evo|storage)""")
            .containsMatchIn(normalized)

        val scores = listOf(
            "motherboard" to (if (looksLikeMotherboard) 3 else 0),
            "psu" to (if (looksLikePsu) 3 else 0),
            "cpu" to (if (looksLikeCpu) 3 else 0),
            "gpu" to (if (looksLikeGpu) 3 else 0),
            "ram" to (if (looksLikeRam) 3 else 0),
            "storage" to (if (looksLikeStorage) 3 else 0)
        ).toMutableList()

        val resolvedCategory = RecommendationMockData.resolveCategory(displayText).lowercase()
        if (resolvedCategory.isNotBlank()) {
            val idx = scores.indexOfFirst { it.first == resolvedCategory }
            if (idx >= 0) {
                scores[idx] = scores[idx].first to (scores[idx].second + 2)
            } else {
                scores.add(resolvedCategory to 2)
            }
        }

        val best = scores.maxByOrNull { it.second }
        return if (best != null && best.second > 0) best.first else resolvedCategory.ifBlank { "component" }
    }

    private suspend fun resolveDetectedComponent(detectedCandidate: UploadDetectionCandidate): String? {
        return runCatching {
            val searchKey = detectedCandidate.displayText
            val searchResults = repository.getSearchResults(searchKey)
            findBestMatch(detectedCandidate.displayText, searchResults, detectedCandidate.category)
        }.getOrNull()
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

        if (best.brandModelPass && best.totalScore > 0 && best.hardPenalty < 30) {
            return best.candidate
        }
        if (best.hardPenalty >= 40 || best.hitCount == 0) return null

        val categoryMismatchHard = when {
            detectedCategoryUpper == "GPU" && !Regex("""(?i)rtx|gtx|radeon|rx\s|geforce|arc|graphics|gpu""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "CPU" && !Regex("""(?i)ryzen|core\s+i|celeron|pentium|processor|cpu""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "RAM" && !Regex("""(?i)ddr|ram|mhz|mt/s|dimm|kit|gb|xmp|expo""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "STORAGE" && !Regex("""(?i)ssd|hdd|nvme|m\.2|sata|tb|gb|storage|crucial|samsung|wd|kingston|adata""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "PSU" && !Regex("""(?i)\d{3,4}\s?w\b|watts?|80\s*\+|gold|bronze|silver|platinum|titanium|seasonic|evga|corsair|rm|sf|focus|fractal|be\s*quiet|coolermaster|fsp|tecware|nzxt|psu""").containsMatchIn(best.candidate) -> true
            detectedCategoryUpper == "MOTHERBOARD" && !Regex("""(?i)\b[abhzx]\d{2,3}|[am]\d{2,3}\b|am4|am5|lga\s?1700|lga\s?1200|lga\s?1851|atx|matx|itx|motherboard|socket""").containsMatchIn(best.candidate) -> true
            else -> false
        }
        if (categoryMismatchHard) return null

        val minRequiredHitStrict: Int = (best.minTokens * 0.30).toInt().coerceAtLeast(1)
        val scoreRatio = if (best.minTokens > 0) best.hitCount.toDouble() / best.minTokens.toDouble() else 0.0
        val hasDigitBonusStrong = (best.totalScore - best.hitCount) >= 2
        val brandOrDigitPass = hasDigitBonusStrong || best.brandModelPass || scoreRatio >= 0.25
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
            wattageDigits != null && candidateDigits == wattageDigits
        }

        val efficiencyLabel = extractPsuEfficiencyLabel(normalizedText, compactNormalizedText)
        val hasEightyPlus = efficiencyLabel != null
        val hasPsuSignal = Regex("""(?i)\b(?:psu|power\s+supply|fully\s*modular|semi\s*modular|atx\s*psu|80\s*(?:plus|\+))\b""")
            .containsMatchIn(normalizedText)
        val brandMatches = Regex("""(?i)\b(?:seasonic|evga|corsair|be\s*quiet|coolermaster|tecware|fsp|nzxt|fractal|lian\s*li|deepcool|thermalright|montech|msi|asus|gigabyte)\b""")
            .containsMatchIn(normalizedText)
        val psuSignalScore =
            (if (wattage != null) 4 else 0) +
                (if (model != null) 2 else 0) +
                (if (hasEightyPlus) 3 else 0) +
                (if (hasPsuSignal) 2 else 0) +
                (if (brandMatches) 1 else 0)

        if (wattage == null || psuSignalScore < 3) {
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
