package com.pcscan.pcscan.ui.dashboard

import android.util.JsonReader
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.pcscan.pcscan.data.api.ComponentDetailMapper
import com.pcscan.pcscan.data.api.GeminiApiService
import com.pcscan.pcscan.data.api.LocalSpecDatabase
import com.pcscan.pcscan.data.api.OpenAiApiService
import com.pcscan.pcscan.data.api.OpenAiDto
import com.pcscan.pcscan.data.api.PcScanApiService
import com.pcscan.pcscan.data.api.SearchApiDto
import com.pcscan.pcscan.data.api.SmartQuerySpecGuesser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

data class BottleneckAnalysis(
    val percentage: Int,
    val status: String,
    val limitingComponent: String,
    val summary: String,
    val detailedAnalysis: String,
    val gaming1080p: String,
    val gaming1440p: String,
    val gaming4k: String,
    val reason: String,
    val withinLimit: Boolean
) {
    companion object {
        val EMPTY = BottleneckAnalysis(0, "UNKNOWN", "Balanced", "", "", "", "", "", "", true)
    }
}

data class BuildRecommendation(
    val label: String,
    val name: String,
    val cpu: String,
    val gpu: String,
    val gpuNvidia: String? = null,
    val gpuAmd: String? = null,
    val motherboard: String,
    val ram: String,
    val storage: String,
    val psu: String,
    val wattage: String,
    val wattageNote: String,
    val bottleneck: String,
    val bottleneckNote: String,
    val gpuInfoNote: String = "",
    val pcCase: String = "Deepcool MATREXX 55 MESH 4F ARGB (ATX, Mid Tower, Black)",
    val cpuCooler: String = "Stock CPU Cooler (Included)",
    val bottleneckAnalysis: BottleneckAnalysis? = null
)

data class UpgradeComponent(
    val category: String,
    val name: String,
    val description: String,
    val compatibility: Int,
    val progressPercent: Float,
    val scores: ComponentScores? = null
)

data class UpgradePath(
    val title: String,
    val description: String,
    val tier: String = "",
    val components: List<UpgradeComponent> = emptyList(),
    val stepOne: String = "",
    val stepTwo: String = "",
    val stepThree: String = ""
)

data class ComponentDetail(
    val name: String,
    val category: String,
    val score: Int,
    val specs: List<String>,
    val aiConfidence: Int,
    val manufacturer: String
)

sealed class ExactSearchResult {
    data class Success(
        val component: ComponentDetail,
        val aiConfidence: Float = 0f,
        val componentId: String = "",
        val specifications: Map<String, Any?>? = emptyMap()
    ) : ExactSearchResult()

    data class NotFound(
        val query: String,
        val reason: String,
        val aiConfidence: Float = 0f
    ) : ExactSearchResult()

    data class InvalidQuery(
        val query: String,
        val message: String,
        val rejectionCategory: String,
        val aiConfidence: Float = 0f,
        val formFactor: String? = null
    ) : ExactSearchResult()

    data class ApiOrNetworkError(
        val reason: String,
        val httpCode: Int? = null,
        val isNetworkError: Boolean = true
    ) : ExactSearchResult()
}

data class SpecSheetStep1Result(
    val is_valid_pc_build: Boolean,
    val is_compatible: Boolean,
    val issues: List<String> = emptyList(),
    val parsed_cpu: String = "",
    val parsed_gpu: String = "",
    val parsed_motherboard: String = "",
    val parsed_ram: String = "",
    val parsed_storage: String = "",
    val parsed_psu: String = ""
)

data class SpecSheetStep2Result(
    val build_tier: String,
    val reason: String = ""
)

data class SpecSheetStep3Result(
    val mid_upgrade_title: String? = null,
    val mid_upgrade_description: String? = null,
    val mid_upgrade_components: List<UpgradeComponent>? = null,
    val high_upgrade_title: String = "High-End Upgrade Path",
    val high_upgrade_description: String = "",
    val high_upgrade_components: List<UpgradeComponent> = emptyList(),
    val no_upgrade_available: Boolean = false,
    val no_upgrade_message: String = ""
)

data class SpecSheetPipelineResult(
    val parsedBuild: CurrentBuild,
    val compatibility: RecommendationMockData.BuildCompatibilityResult,
    val buildTier3: RecommendationMockData.UserCompTier3,
    val step3: SpecSheetStep3Result
)

interface RecommendationRepository {
    suspend fun getSearchResults(query: String): List<String>
    suspend fun getExactSearchResult(query: String): ExactSearchResult
    suspend fun getBuildRecommendations(component: String): List<BuildRecommendation>
    suspend fun getDetectedComponentTier(component: String): String
    suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean): UpgradePath
    suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean, tier: BuildTier): UpgradePath
    suspend fun getUpgradePath(
        searchedComponent: String,
        currentBuild: CurrentBuild,
        tier: BuildTier
    ): UpgradePath
    suspend fun getComponentDetails(componentName: String): ComponentDetail
    suspend fun analyzeImageForComponents(imageBytes: ByteArray): List<String>
    suspend fun analyzeImageForComponentsVisionOnly(imageBytes: ByteArray): List<String>
    suspend fun validateComponentWithGpt(input: String): OpenAiDto.JsonComponentValidation?
    suspend fun analyzeSpecSheetPipelineOrNull(
        specSheetText: String,
        fallbackBuild: CurrentBuild,
        onProgress: (Int, String, String) -> Unit,
        primaryComponent: String = ""
    ): SpecSheetPipelineResult?
}

private val BRAND_ONLY_SET: Set<String> = setOf(
    "INTEL","AMD","NVIDIA","ASUS","MSI","GIGABYTE","EVGA","SAPPHIRE","XFX","POWERCOLOR","ZOTAC","GALAX","PNY","CORSAIR","G.SKILL","GSKILL","KINGSTON","CRUCIAL","SAMSUNG","WD","WESTERN DIGITAL","SEA GATE","SEAGATE","THERMALTAKE","BE QUIET","BEQUIET","NOCTUA","COOLER MASTER","COOLERMASTER","LIAN LI","LIANLI","FRACTAL DESIGN","NZXT","HYTE","ANTEC","PHANTEKS","DEEPCOOL","MONTECH","LEXAR","ADATA","TEAMGROUP","TEAM GROUP","CORSAIR","GIGABYTE","AORUS","ROG STRIX","TUF GAMING","MEG","MPG","MAG","PROART","PRO Z","PRO B","TOMAHAWK","TUF","ROG MAXIMUS","ROG CROSSHAIR","AORUS ELITE","AORUS MASTER","AORUS XTREME","VAPOR","STEALTH","GAMING X","GAMING PRO CARBON","GAMING PLUS","MORTAR","BAZOOKA","EDGE","MPG SEKIRA","MPERIUM","HISILICON","MEDIATEK","BROADCOM","REALTEK","QUALCOMM"
).map { it.uppercase() }.toSet()

private val CATEGORY_ONLY_SET: Set<String> = setOf(
    "CPU","GPU","MOTHERBOARD","MAINBOARD","MOBO","RAM","MEMORY","STORAGE","HDD","SSD","NVME","M.2","HARD DRIVE","PSU","POWER SUPPLY","POWERSUPPLY","CASE","CHASSIS","CASING","TOWER","COOLER","CPU COOLER","AIR COOLER","AIO","LIQUID COOLER","WATER COOLER","GRAPHICS CARD","VIDEOCARD","VIDEO CARD","PROCESSOR","GRAPHICS","PSU/POWER","MOTHER BOARD"
).map { it.uppercase() }.toSet()

private fun isGenericOrPlaceholderComponentName(name: String): Boolean {
    val raw = name.trim()
    if (raw.isBlank()) return true
    val shortTrim = raw.trim().replace(Regex("""[\s\-_:/]+"""),"")
    if (shortTrim.length <= 4) return true
    val upper = raw.uppercase()
    val tokensUpper = Regex("""[A-Za-z0-9]{2,}""").findAll(raw).map { it.value.uppercase() }.toList()
    if (tokensUpper.isEmpty()) return true
    if (tokensUpper.all { it in BRAND_ONLY_SET }) return true
    val strippedUpper = tokensUpper.joinToString(" ")
    if (strippedUpper in CATEGORY_ONLY_SET) return true
    if (tokensUpper.size <= 2 && (tokensUpper.intersect(CATEGORY_ONLY_SET).isNotEmpty() || tokensUpper.intersect(BRAND_ONLY_SET).isNotEmpty())) {
        if (tokensUpper.size == 1) return true
        val hasNums = Regex("""\d{3,}""").containsMatchIn(strippedUpper)
        val hasGen = Regex("""DDR[45]|PCIe\s*[345]|LGA|AM[45]|RX\s*\d|RTX\s*\d|CORE\s*i\d|RYZEN\s*\d|ULTRA\s*\d|Ti|SUPER|XT|XTX|GRE|X3D|\d+GB|\d+TB|80\s*\+\s*BRONZE|80\s*\+\s*GOLD|80\s*\+\s*PLATINUM|80\s*PLUS|ATX\s*3|PCI\s*EXPRESS|ATX|MID\s*TOWER|FULL\s*TOWER|MESH|AIRFLOW""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (!hasNums && !hasGen) return true
    }
    return false
}

private fun anyComponentPlaceholderDetected(cpu: String, gpu: String, motherboard: String, ram: String, storage: String, psu: String, pcCase: String, cooler: String, tierLabel: String): Boolean {
    val list = listOf("cpu" to cpu, "gpu" to gpu, "motherboard" to motherboard, "ram" to ram, "storage" to storage, "psu" to psu, "case" to pcCase, "cooler" to cooler)
    for ((label, value) in list) {
        if (isGenericOrPlaceholderComponentName(value)) {
            Log.w("V2_PLACEHOLDER", "V2 build reco build=$tierLabel component=$label value=[$value] classified as BRAND-ONLY / CATEGORY-ONLY / EMPTY placeholder -> hard reject V1, fallback V1")
            return true
        }
    }
    return false
}

private val BOARD_PARTNER_BRANDS: Set<String> = setOf(
    "ZOTAC", "ZOTAC GAMING", "ASUS", "ROG", "ROG STRIX", "ROG CROSSHAIR", "ROG MAXIMUS", "TUF", "TUF GAMING", "PROART",
    "MSI", "MEG", "MPG", "MAG", "MPOWER",
    "GIGABYTE", "AORUS", "AORUS ELITE", "AORUS MASTER", "AORUS XTREME",
    "SAPPHIRE", "NITRO+", "PULSE", "POWERCOLOR", "RED DEVIL", "HELLHOUND", "XFX", "SPEEDSTER", "MERC",
    "GALAX", "KFA2", "INNO3D", "ICHILL", "PNY", "VERTO", "XLR8", "EVGA", "PALIT", "GAINWARD", "PHANTOM",
    "ASROCK", "STEEL LEGEND", "PRO RS", "TAICHI", "BIOSTAR", "RACING",
    "CORSAIR", "VENGEANCE", "DOMINATOR", "MP700", "RMX", "RM850X", "RM1000X",
    "G.SKILL", "GSKILL", "TRIDENT", "RIPJAWS",
    "KINGSTON", "FURY", "RENEGADE", "HYPERX", "BEAST",
    "LEXAR", "THOR", "NM", "ARES",
    "CRUCIAL", "P3", "P5", "T700", "T705", "MX", "BX",
    "TEAMGROUP", "TEAM GROUP", "T-FORCE", "DELTA", "VULCAN",
    "ADATA", "XPG", "SPECTRIX", "GAMMIX",
    "SAMSUNG", "970", "980", "990", "870", "990 PRO", "990 PRO EVO",
    "WD", "WESTERN DIGITAL", "BLACK", "BLUE", "RED", "SN850", "SN770",
    "SEAGATE", "BARRACUDA", "FIRECUDA",
    "SABRENT", "ROCKET",
    "SEASONIC", "FOCUS", "PRIME", "CONNECT", "VERTEX",
    "BE QUIET", "BEQUIET", "STRAIGHT POWER", "DARK POWER", "SYSTEM POWER", "DARK ROCK", "SHADOW ROCK", "SILENT ROCK",
    "COOLER MASTER", "COOLERMASTER", "MWE", "MASTERLIQUID", "HYPER",
    "LIAN LI", "LIANLI", "SP", "EDGE", "O11", "LANCOOL", "UNI",
    "NZXT", "KRAKEN", "H5", "H7", "H9", "C", "N7",
    "FRACTAL", "FRACTAL DESIGN", "NORTH", "POP", "MESHIFY", "DEFINE",
    "HYTE", "REVOLT", "Y60", "Y70",
    "PHANTEKS", "ENTHOO", "EVOLV",
    "MONTECH", "AIR", "SKY", "KING",
    "DEEPCOOL", "AK", "AG", "LS", "LT", "DQ", "PM", "PK", "ANCIENT",
    "NOCTUA", "NH", "THERMALRIGHT", "ARCTIC", "LIQUID FREEZER",
    "THERMALTAKE", "TOUGHPOWER", "GRAND", "SMART", "VIEW",
    "ANTEC",
    "NZXT H", "CORSAIR 4000D", "CORSAIR 5000D", "CORSAIR 7000D", "LIAN LI O11",
    "PHANTEKS ENTHOO", "FRACTAL NORTH", "MONTECH AIR 903", "MONTECH SKY TWO",
    "MSI MAG", "MSI MPG", "MSI MEG", "ASUS TUF", "ASUS PRIME", "ASUS PROART",
    "GIGABYTE B650", "GIGABYTE Z790", "GIGABYTE X670", "ASROCK B650",
    "CORSAIR RM", "CORSAIR SF", "CORSAIR HX", "CORSAIR AX",
    "SEASONIC FOCUS", "SEASONIC PRIME",
    "EVGA SUPERNOVA",
    "BE QUIET STRAIGHT POWER", "BEQUIET DARK POWER",
    "COOLER MASTER MWE", "COOLERMASTER MASTERLIQUID",
    "THERMALRIGHT PEERLESS", "THERMALRIGHT PHANTOM", "THERMALRIGHT FROST SPIRIT",
    "NOCTUA NH-D15", "ARCTIC LIQUID FREEZER",
    "SAPPHIRE PULSE", "SAPPHIRE NITRO+",
    "POWERCOLOR RED DEVIL", "POWERCOLOR HELLHOUND",
    "XFX MERC", "XFX SPEEDSTER",
    "ZOTAC GAMING",
    "GALAX", "INNO3D ICHILL",
    "PNY VERTO", "PNY XLR8",
    "PALIT", "GAINWARD PHANTOM"
).map { it.uppercase() }.toSet()

private fun userQueryContainsBoardPartnerBrand(userQuery: String): Boolean {
    val upper = userQuery.uppercase().replace(Regex("""[\s\-_,()\[\]/]+"""), " ")
    val upperWithDash = userQuery.uppercase()
    for (brand in BOARD_PARTNER_BRANDS) {
        val brandNormalized = brand.replace(Regex("""[\s\-_,()\[\]/]+"""), " ")
        val re = Regex("""(^|[^A-Z0-9])""" + Regex.escape(brandNormalized) + """($|[^A-Z0-9])""")
        if (re.containsMatchIn(upper)) return true
        if (upperWithDash.contains(brand)) return true
    }
    return false
}

/** Desktop core-model matchers (family + model number + variant). Shared by the name-preservation
 *  logic and the model-identity guard so the model the user actually searched always wins. */
private val CORE_MODEL_MATCHERS: List<Regex> = listOf(
    Regex("""\bRTX\s*(\d{3,4})\s*(TI|SUPER|XT|XTX|GRE|SE)?\b""", RegexOption.IGNORE_CASE),
    Regex("""\bGTX\s*(\d{3,4})\s*(TI|SUPER)?\b""", RegexOption.IGNORE_CASE),
    Regex("""\bRX\s*(\d{3,4})\s*(XT|XTX|GRE|PRO)?\b""", RegexOption.IGNORE_CASE),
    Regex("""\bRYZEN\s*[3579]\s*(\d{3,4}[A-Z0-9]{0,4})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bCORE\s*(I[3579]|ULTRA\s*[579])\s*(\d[A-Z0-9]{3,6})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bARC\s*[AB]\d{2,3}\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(B|X|Z|H|A)\d{2,3}[M]?\b""", RegexOption.IGNORE_CASE),
    Regex("""\bDDR[45]\b""", RegexOption.IGNORE_CASE),
    Regex("""\b\d{3,4}\s*W\b""", RegexOption.IGNORE_CASE),
    Regex("""\b\d{1,2}\s*(TB|GB)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(AM4|AM5|LGA1151|LGA1700|LGA1851|LGA1200)\b""", RegexOption.IGNORE_CASE)
)

/** Model signatures found in a name, whitespace-stripped and uppercased: "rx 9070xt" -> {"RX9070XT"}. */
private fun coreModelSignatures(text: String): Set<String> =
    CORE_MODEL_MATCHERS.flatMap { re ->
        re.findAll(text.uppercase()).map { it.value.uppercase().replace(Regex("""\s+"""), "") }
    }.toSet()

/** Family prefix + digits only so the variant suffix can be compared separately:
 *  "RX9070XT" -> "RX9070", "RTX3080TI" -> "RTX3080". */
private fun coreModelNumber(signature: String): String =
    signature.takeWhile { !it.isDigit() } + signature.filter { it.isDigit() }

/** TRUE when query and candidate both name a desktop model of the SAME family but a DIFFERENT model
 *  number (e.g. query 'rtx 5050' vs candidate 'RTX 3080 Ti'). Such a candidate must NOT be shown,
 *  otherwise the search result displays a component the user never searched for. */
private fun coreModelNumberMismatch(query: String, candidate: String): Boolean {
    val q = coreModelSignatures(query)
    val c = coreModelSignatures(candidate)
    if (q.isEmpty() || c.isEmpty()) return false
    val qFamilies = q.map { it.takeWhile { ch -> ch.isLetter() } }.toSet()
    val cFamilies = c.map { it.takeWhile { ch -> ch.isLetter() } }.toSet()
    if ((qFamilies intersect cFamilies).isEmpty()) return false
    return (q.map(::coreModelNumber).toSet() intersect c.map(::coreModelNumber).toSet()).isEmpty()
}

private fun preserveUserBrandedNameIfProvided(userQuery: String, gptOrDbName: String): String {
    val userTrim = userQuery.trim()
    if (userTrim.isBlank()) return gptOrDbName.trim()
    if (userTrim.equals(gptOrDbName.trim(), ignoreCase = true)) return userTrim

    val userCoreSignatures = coreModelSignatures(userTrim)
    val gptCoreSignatures = coreModelSignatures(gptOrDbName.trim())
    if (userCoreSignatures.isEmpty() || gptCoreSignatures.isEmpty()) return gptOrDbName.trim()
    val overlap = userCoreSignatures intersect gptCoreSignatures
    if (overlap.isEmpty()) {
        // Candidate dropped the variant suffix the user explicitly typed while keeping the same core
        // model number (e.g. user 'RX 9070XT' vs candidate 'RX 9070') -> trust the user's input.
        val sameCoreNumber = userCoreSignatures.map(::coreModelNumber).toSet() intersect
            gptCoreSignatures.map(::coreModelNumber).toSet()
        return if (sameCoreNumber.isNotEmpty()) userTrim else gptOrDbName.trim()
    }

    val userHasBrand = userQueryContainsBoardPartnerBrand(userTrim)
    if (userHasBrand) return userTrim

    val userHasVariant = run {
        val qSig = extractVariantSignature(userTrim)
        val mSig = extractVariantSignature(gptOrDbName.trim())
        qSig.size >= mSig.size && !mSig.any { it !in qSig }
    }
    if (userHasVariant && userTrim.length >= gptOrDbName.trim().length) return userTrim

    return gptOrDbName.trim()
}


private val VARIANT_SUFFIXES: List<String> = listOf(
    "XTX", "XT", "Ti", "Super", "GRE", "OC", "GAMING X", "GAMING", "X3D2", "X3D", "KF", "KS", "GT", "X", "F", "G", "H", "K", "T", "S", "EVO", "PRO", "CLASSIC BLACK", "LE", "SE"
)

private fun extractVariantSignature(text: String): Set<String> {
    val upper = text.uppercase().replace(Regex("""[\s\-_,()\[\]/]+"""), " ")
    val out = mutableSetOf<String>()
    for (suffix in VARIANT_SUFFIXES) {
        val re = Regex("""(^|[^A-Z0-9])""" + Regex.escape(suffix.uppercase()) + """($|[^A-Z0-9])""")
        if (re.containsMatchIn(upper)) out += suffix.uppercase()
    }
    out += Regex("""\bRTX\s*(\d{4})\s*(TI|SUPER|XT|XTX|GRE)?\b""", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] + "_" + it.groupValues[2].ifBlank { "BASE" }.uppercase() }.toList()
    out += Regex("""\bRX\s*(\d{4})\s*(XT|XTX|GRE)?\b""", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] + "_" + it.groupValues[2].ifBlank { "BASE" }.uppercase() }.toList()
    return out
}

private fun variantSufficesMismatchHardReject(query: String, matchName: String): Boolean {
    val qSig = extractVariantSignature(query)
    val mSig = extractVariantSignature(matchName)
    val suffixList = listOf("XT", "TI", "SUPER", "GRE", "XTX", "X3D2", "X3D", "KF", "KS", "GT", "F", "G", "H", "K", "T", "S")
    for (s in suffixList) {
        val qHas = qSig.contains(s) || Regex("""(^|[^A-Z0-9])${s}($|[^A-Z0-9])""").containsMatchIn(query.uppercase())
        val mHas = mSig.contains(s) || Regex("""(^|[^A-Z0-9])${s}($|[^A-Z0-9])""").containsMatchIn(matchName.uppercase())
        if (qHas && !mHas) return true
        if (!qHas && mHas) {
            val strippedMatch = matchName.uppercase().replace(Regex("""(^|[^A-Z0-9])${s}($|[^A-Z0-9])"""), "$1$2")
            val queryNum = Regex("""\b(\d{4})\b""").find(query.uppercase())?.groupValues?.get(1)
            val matchNum = Regex("""\b(\d{4})\b""").find(strippedMatch)?.groupValues?.get(1)
            if (queryNum != null && matchNum != null && queryNum == matchNum) return true
        }
    }
    return false
}

private suspend fun tryGptDbFirstLayerZeroOrNull(
    query: String,
    qTrim: String,
    qLower: String,
    tag: String
): ExactSearchResult.Success? {
    if (qTrim.isBlank()) return null
    // Optional enrichment must leave time for exact lookup and local validation.
    val dbCandidates = withTimeoutOrNull(3_000L) {
        runCatching {
            val request = com.pcscan.pcscan.data.api.SearchApiDto.FuzzySearchRequestDto(query = qTrim, limit = 10, includeRawSpecs = true)
            val resp = com.pcscan.pcscan.data.api.PcScanApiService.searchFuzzyWithFallback(request)
            if (!resp.isSuccessful) {
                Log.w(tag, "DB_FIRST L0: search-fuzzy HTTP ${resp.code()} (endpoint missing?), skipping to L1 standalone GPT validator.")
                return@runCatching emptyList<com.pcscan.pcscan.data.api.SearchApiDto.DbComponentCandidateDto>()
            }
            resp.body()?.candidates.orEmpty()
        }.onFailure { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(tag, "DB_FIRST L0: search-fuzzy call failed (backend offline?): ${t.javaClass.simpleName} ${t.message}")
        }.getOrElse { emptyList() }
    }.orEmpty()
    if (dbCandidates.isEmpty()) {
        Log.i(tag, "DB_FIRST L0: DATABASE_MATCHES list empty -> skip L0, go L1 standalone GPT validator")
        return null
    }
    val match = runCatching {
        withTimeoutOrNull(8000L) { AiBuildOrchestrator.runGptDbFirstMatchOrNull(qTrim, dbCandidates) }
    }.onFailure { t -> Log.e(tag, "DB_FIRST L0: runGptDbFirstMatchOrNull EXCEPTION: ${t.javaClass.simpleName} ${t.message}", t) }
        .getOrNull() ?: return null
    if (!match.match_found) {
        Log.i(tag, "DB_FIRST L0: GPT match_found=false reason=${match.reason_if_not_found} -> skip L0, go L1 standalone GPT validator")
        return null
    }
    val conf = (match.confidence ?: 0.0).coerceIn(0.0, 1.0)
    if (conf < 0.6) {
        Log.i(tag, "DB_FIRST L0: match confidence=${String.format("%.2f", conf)} <0.6 -> skip L0 accept (will show did-you-mean fallback), go L1 standalone GPT validator")
        return null
    }
    val matchedDb = dbCandidates.firstOrNull { it.id == match.component_id }
    val matchedNameRaw = match.component_name?.takeIf { it.isNotBlank() } ?: matchedDb?.name ?: qTrim
    val matchedName = preserveUserBrandedNameIfProvided(query, matchedNameRaw)
    if (variantSufficesMismatchHardReject(qTrim, matchedName)) {
        Log.w(tag, "DB_FIRST L0: VARIANT SUFFIX MISMATCH HARD-REJECT. query=[$qTrim] matched=[$matchedName] (query has XT/Ti/Super/GRE but matched name doesn't, or vice versa on same core number). Dropping GPT DB-match -> fallback L1 standalone GPT.")
        return null
    }
    if (coreModelNumberMismatch(qTrim, matchedName)) {
        Log.w(tag, "DB_FIRST L0: MODEL NUMBER MISMATCH HARD-REJECT. query=[$qTrim] matched=[$matchedName] names a DIFFERENT model number of the same family. Dropping GPT DB-match -> fallback L1 standalone GPT.")
        return null
    }
    val rawCat = (match.category?.trim()?.uppercase() ?: matchedDb?.category?.trim()?.uppercase() ?: "UNKNOWN").let {
        when (it) {
            "MOTHERBOARD","MAINBOARD","MOBO" -> "MOTHERBOARD"
            "STORAGE","SSD","HDD","NVME" -> "STORAGE"
            "COOLER","CPU COOLER","AIO","LIQUID COOLER" -> "COOLER"
            "CASE","CHASSIS","CASING" -> "CASE"
            "POWERSUPPLY","POWER SUPPLY" -> "PSU"
            else -> it
        }
    }
    if (rawCat !in setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")) {
        Log.w(tag, "DB_FIRST L0: category=$rawCat not in 8-set for q=$qTrim -> skip L0")
        return null
    }
    val mf = matchedDb?.brand?.takeIf { it.isNotBlank() }
        ?: matchedName.split(" ").firstOrNull { w -> w.length >= 3 }?.take(16) ?: ""
    val tierText = match.tier ?: matchedDb?.tier
    val score = com.pcscan.pcscan.data.api.SmartQuerySpecGuesser.guess(matchedName)?.score ?: 70
    val specs = buildList<String> {
        add("Component type: $rawCat")
        add("Identification method: Database-first GPT match")
        add("Source: internal database DATABASE_MATCHES (fuzzy search + GPT best-of match)")
        tierText?.let { add("Performance tier (${match.tier_source ?: "database"}): $it") }
        match.component_id?.let { add("Database id: $it") }
        matchedDb?.chipsetSocket?.let { add("Chipset/Socket: $it") }
        matchedDb?.memoryType?.let { add("Memory type: $it") }
        matchedDb?.tdp?.let { add("TDP: ${it}W") }
        matchedDb?.wattageRating?.let { add("Wattage rating: ${it}W") }
        matchedDb?.releaseYear?.let { add("Release year: $it") }
        if (mf.isNotBlank()) add("Manufacturer: $mf")
        if (conf < 0.8) add("Verification hint: Low-confidence DB match - confirm specs manually")
    }
    val aiConfPct = (conf * 100.0).toFloat().coerceIn(0f, 100f)
    val compIdDb = match.component_id?.takeIf { it.isNotBlank() }
        ?: "db0-${rawCat.lowercase()}-${matchedName.replace("""[\s\-_/()]+""".toRegex(), "_").lowercase()}"
    Log.i(tag, "DB_FIRST L0 ACCEPT: q=$qTrim -> component='$matchedName' (id=$compIdDb, category=$rawCat, tier=${tierText ?: "(unset)"}, tier_source=${match.tier_source}, conf=${aiConfPct.toInt()}%)")
    return ExactSearchResult.Success(
        component = ComponentDetail(
            name = matchedName,
            category = rawCat,
            score = score,
            specs = specs,
            aiConfidence = aiConfPct.toInt(),
            manufacturer = mf
        ),
        aiConfidence = aiConfPct,
        componentId = compIdDb
    )
}

private suspend fun tryGptValidateAndReturnValidMatchOrNull(
    query: String,
    qTrim: String,
    qLower: String,
    tag: String
): ExactSearchResult? {
    if (qTrim.isBlank()) return null
    if (!OpenAiApiService.hasApiKey()) {
        Log.w(tag, "GPT_VALIDATOR L1 WARN: No OpenAI API key configured in local.properties -- still attempting call; expect 401 then fallthrough")
    }
    val validation = runCatching {
        withTimeoutOrNull(8000L) { AiBuildOrchestrator.validateComponentWithGPT(qTrim) }
    }
        .onFailure { t -> Log.e(tag, "GPT_VALIDATOR EXCEPTION: ${t.javaClass.simpleName} ${t.message}", t) }
        .getOrNull()
        ?: run {
            tryGptDbFirstLayerZeroOrNull(query, qTrim, qLower, tag)?.let { return it }
            return null
        }
    if (validation.is_pc_component == true) {
        tryGptDbFirstLayerZeroOrNull(query, qTrim, qLower, tag)?.let { return it }
    }
    if (validation.is_pc_component != true) {
        val confPct = ((validation.confidence ?: 0.0) * 100.0).toFloat().coerceIn(0f, 100f)
        Log.i(tag, "GPT_VALIDATOR NOT_PC: q=$qTrim is_pc_component=false (conf=${confPct.toInt()}%) -> returning InvalidQuery(not_pc_part)")
        return ExactSearchResult.InvalidQuery(
            query = query,
            message = "NOT A DESKTOP PC COMPONENT - GPT 5.6 Luna validation classified '$qTrim' as NOT a valid desktop PC DIY hardware part (category: CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case for desktop PCs only). Try exact brand+model e.g. 'Ryzen 5 5600' or 'RTX 4060'.",
            rejectionCategory = "not_pc_part",
            aiConfidence = confPct,
            formFactor = null
        )
    }
    val normalizedName = preserveUserBrandedNameIfProvided(query, validation.normalized_name?.takeIf { it.isNotBlank() } ?: qTrim)
    if (coreModelNumberMismatch(query, normalizedName)) {
        Log.w(tag, "GPT_VALIDATOR: MODEL NUMBER MISMATCH - query='$qTrim' but GPT name='$normalizedName' is a DIFFERENT model of the same family -> not displaying, falling through to DB/backend layers")
        return null
    }
    val rawCompType = validation.component_type?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: run {
        val local = LocalSpecDatabase.find(normalizedName)
            ?: SmartQuerySpecGuesser.guess(normalizedName)
        local?.category?.trim()?.uppercase() ?: "UNKNOWN"
    }
    val compType = when (rawCompType) {
        "CENTRAL PROCESSING UNIT", "PROCESSOR" -> "CPU"
        "GRAPHICS CARD", "GRAPHICS PROCESSING UNIT", "VIDEO CARD", "VGA", "GRAPHICS ADAPTER", "DISPLAY ADAPTER", "GPU CARD" -> "GPU"
        "MEMORY", "RANDOM ACCESS MEMORY", "MEM", "DDR", "DDR4", "DDR5", "DDR3", "SYSTEM MEMORY", "RAM MODULE", "DIMM" -> "RAM"
        "MAINBOARD", "MOTHERBOARD", "MOBO", "MB", "MOTHER BOARD", "MAIN BOARD", "SYSTEM BOARD" -> "MOTHERBOARD"
        "STORAGE DEVICE", "SSD", "HDD", "NVME", "STORAGE", "STOR", "M.2", "NVME SSD", "SOLID STATE DRIVE", "HARD DISK DRIVE", "DISK", "SECONDARY STORAGE" -> "STORAGE"
        "POWER SUPPLY", "POWER SUPPLY UNIT", "POWER", "POWER SUPPY", "POWER SUPPLIES", "POWERSUPPLY", "PS UNIT" -> "PSU"
        "AIR COOLER", "WATER COOLER", "CPU COOLER", "COOL", "COOLER", "AIO COOLER", "LIQUID COOLER", "COOLERS", "HEATSINK", "COOLING SOLUTION", "CPU AIR COOLER", "CPU LIQUID COOLER" -> "COOLER"
        "COMPUTER CASE", "PC CASE", "CHASSIS", "CASING", "TOWER", "ENCLOSURE", "CASE", "SYSTEM UNIT CASE" -> "CASE"
        else -> when {
            rawCompType.startsWith("MOTH") || rawCompType.startsWith("MAIN") -> "MOTHERBOARD"
            rawCompType.startsWith("STOR") || rawCompType == "SSD" || rawCompType == "HDD" || rawCompType == "NVME" -> "STORAGE"
            rawCompType.startsWith("COOL") || rawCompType.contains("COOLER") || rawCompType.contains("HEATSINK") || rawCompType.contains("AIO") -> "COOLER"
            rawCompType.startsWith("MEM") || rawCompType.startsWith("DDR") || rawCompType.contains("RAM") -> "RAM"
            rawCompType.startsWith("POW") || rawCompType.contains("POWER") -> "PSU"
            rawCompType.startsWith("CHAS") || rawCompType.contains("CASE") || rawCompType.contains("CASING") || rawCompType.contains("TOWER") -> "CASE"
            rawCompType.startsWith("GRAPH") || rawCompType.contains("GPU") || rawCompType.contains("GRAPHICS") || rawCompType.contains("VIDEO") -> "GPU"
            rawCompType.contains("PROCESSOR") || rawCompType.startsWith("CPU") || rawCompType.contains("CPU") -> "CPU"
            else -> rawCompType
        }
    }
    val in8Set = compType in setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")
    val finalCategory = if (in8Set) compType else rawCompType
    if (finalCategory !in setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")) {
        Log.w(tag, "GPT_VALIDATOR: returned non-8-set component_type raw='$rawCompType' normalized='$compType' final='$finalCategory' for q=$qTrim -> still rejecting (peripheral? mobile?)")
        return ExactSearchResult.InvalidQuery(
            query = query,
            message = "NOT A DESKTOP PC COMPONENT - '$qTrim' (detected category: $finalCategory) is NOT in the 8 supported desktop PC DIY categories (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case). Hint: peripherals like Monitor/KB/Mouse/UPS are NOT supported by the PC build compatibility engine.",
            rejectionCategory = "not_pc_part",
            aiConfidence = ((validation.confidence ?: 0.0) * 100.0).toFloat().coerceIn(0f, 100f),
            formFactor = null
        )
    }
    val mf = validation.manufacturer?.takeIf { it.isNotBlank() }
        ?: normalizedName.split(" ").firstOrNull { w -> w.length >= 3 }?.take(16) ?: ""
    val conf = (validation.confidence?.times(100.0))?.toFloat()?.coerceIn(0f, 100f) ?: 90f
    Log.i(tag, "GPT_VALIDATOR ACCEPT: q=$qTrim -> final_display_name='$normalizedName' (type=$finalCategory, conf=$conf%, needsVerification=${validation.needs_verification})")
    val score = com.pcscan.pcscan.data.api.SmartQuerySpecGuesser.guess(normalizedName)?.score ?: 60
    return ExactSearchResult.Success(
        component = ComponentDetail(
            name = normalizedName,
            category = finalCategory,
            score = score,
            specs = listOfNotNull(
                validation.manufacturer?.let { "Manufacturer: $it" },
                validation.model?.let { "Model: $it" },
                "Component type: $finalCategory",
                "User query preserved: if your search included a board partner brand (Zotac/ASUS/MSI/Gigabyte/etc.), the exact full model name above is preserved.",
                if (validation.needs_verification == true) "Verification hint: New or unreleased component - specs may be tentative" else null
            ),
            aiConfidence = conf.toInt(),
            manufacturer = mf
        ),
        aiConfidence = conf,
        componentId = normalizedName.replace("""[\s\-_/()]+""".toRegex(), "_").lowercase()
    )
}

/** PATTERN-BASED GENERIC PC COMPONENT SOFT-PASS (LAST RESORT before not_pc_part rejection).
 *  FIRES ONLY WHEN: GPT validator is null (no OpenAI key configured) + backend/local DB missed the component.
 *  Uses DESKTOP PC HARDWARE NAMING REGEX PATTERNS (generic generation-agnostic).
 *  NEVER adds individual models -- instead catches REGEX NAMING PATTERNS shared across all generations.
 *  If pattern matches -> returns ExactSearchResult.Success with aiConfidence=70, needsVerification=true. Else null. */
private fun tryPatternBasedGenericPcComponentPassOrNull(
    query: String,
    qTrim: String,
    qLower: String,
    tag: String
): ExactSearchResult.Success? {
    if (qTrim.isBlank()) return null
    // --- GENERIC REGEX NAMING PATTERNS (NOT per-model!) ---
    val gpuPattern = Regex("(^|[\\s\\-_])(nvidia\\s*geforce\\s*)?rtx\\s*\\d{3,4}(\\s*ti|\\s*super|\\s*xt|\\s*se)?|" +
        "(^|[\\s\\-_])(nvidia\\s*)?gtx\\s*\\d{3,4}(\\s*ti|\\s*super)?|" +
        "(^|[\\s\\-_])(amd\\s*radeon\\s*)?rx\\s*\\d{3,4}(\\s*xt|\\s*xtx|\\s*gre|\\s*pro)?|" +
        "(^|[\\s\\-_])intel\\s*arc\\s*[ab]\\d{2,3}", RegexOption.IGNORE_CASE)
    val cpuPattern = Regex("(^|[\\s\\-_])(amd\\s*)?ryzen\\s*[3579]\\b|" +
        "(^|[\\s\\-_])intel\\s*core\\s*i[3579]\\b|" +
        "(^|[\\s\\-_])intel\\s*core\\s*ultra\\s*[579]\\b|" +
        "(^|[\\s\\-_])(intel\\s*)?celeron\\b|(^|[\\s\\-_])(intel\\s*)?pentium\\b|" +
        "(^|[\\s\\-_])(amd\\s*)?athlon\\b|(^|[\\s\\-_])(amd\\s*)?threadripper\\b", RegexOption.IGNORE_CASE)
    val moboPattern = Regex("(^|[\\s\\-_])[bxzh]\\d{2,3}[m]?\\b|am4|am5|lga1[278]\\d{2}|rog\\s+strix|mag\\s+b\\d|aorus\\s+elite|tomahawk|mortar|taichi|steel\\s+legend", RegexOption.IGNORE_CASE)
    val ramPattern = Regex("ddr[345]\\s*\\d{3,4}|vengeance|trident\\s+z|ripjaws|g\\.skill|fury\\s+beast|expo|xmp|kingston\\s+fury|team\\s+force|vulcan\\s+z|t-force\\s+delta|dominator\\s+platinum", RegexOption.IGNORE_CASE)
    val storagePattern = Regex("\\d{1,2}\\s*tb\\s*(nvme|ssd)|\\d{3,4}\\s*gb\\s*(nvme|ssd)|nvme\\s*ssd|pcie\\s*gen[45]\\s*ssd|9[789]0\\s*(pro|evo)|sn\\d{3}x?|kc3000|kc2500|sabrent\\s+rocket|firecuda|barracuda|wd\\s+black|wd\\s+blue|crucial\\s+(p3|p5|mx|t)|samsung\\s+(970|980|990|870)", RegexOption.IGNORE_CASE)
    val psuPattern = Regex("\\d{3,4}\\s*w\\s*(80\\+|80\\s*plus|gold|silver|bronze|platinum|titanium)|80\\+\\s*(gold|silver|bronze|platinum|titanium)|full\\s+modular|atx\\s*3\\.?0|corsair\\s+(rm|rmx|hxi|axi|sf)|seasonic\\s+(focus|prime|connect)|evga\\s+supernova|be\\s*quiet\\s+(straight|dark|system)\\s+power|cooler\\s+master\\s+mwe|toughpower\\s+grand|lian\\s+li\\s+sp\\s+\\d+", RegexOption.IGNORE_CASE)
    val coolerPattern = Regex("noctua\\s+nh-|nzxt\\s+kraken|corsair\\s+h\\d{3}i?|h\\d{2,3}\\s*(mm\\s*)?aio|arctic\\s+(liquid\\s+)?freezer|deepcool\\s+(ak|ag|ls|lt)\\d+|thermalright\\s+(peerless|phantom|frost)\\s+(assassin|spirit)|be\\s*quiet\\s+(dark|shadow|silent)\\s+rock|hyper\\s+212|masterliquid\\s+ml\\d+|\\d{3}\\s*mm\\s*(aio|liquid\\s+cooler|air\\s+cooler|radiator)|cpu\\s+cooler|air\\s+cooler|liquid\\s+cooler|aio\\s+cooler", RegexOption.IGNORE_CASE)
    val casePattern = Regex("o11\\s+(dynamic|evo)|lancool\\s+\\w+|nzxt\\s+h\\d|corsair\\s+(4000|5000|7000)[dxt]|4000d|5000d|fractal\\s+(north|pop|meshify|define)|phanteks\\s+(enthoo|evolv)|lian\\s+li\\s+o11|montech\\s+(air|sky|king)\\s*\\d*|mid\\s+tower|full\\s+tower|super\\s+tower|mini\\s+tower|sff|small\\s+form\\s+factor|pc\\s+case|computer\\s+case|chassis|mesh\\s+front|tempered\\s+glass", RegexOption.IGNORE_CASE)

    val (cat, patternName) = when {
        gpuPattern.containsMatchIn(qLower) -> "GPU" to "desktop GPU naming pattern"
        cpuPattern.containsMatchIn(qLower) -> "CPU" to "desktop CPU naming pattern"
        moboPattern.containsMatchIn(qLower) -> "MOTHERBOARD" to "desktop motherboard chipset/socket pattern"
        ramPattern.containsMatchIn(qLower) -> "RAM" to "desktop RAM DIMM naming pattern"
        storagePattern.containsMatchIn(qLower) -> "STORAGE" to "desktop SSD/NVMe/storage pattern"
        psuPattern.containsMatchIn(qLower) -> "PSU" to "desktop PSU wattage/rating pattern"
        coolerPattern.containsMatchIn(qLower) -> "COOLER" to "desktop cooler/AIO pattern"
        casePattern.containsMatchIn(qLower) -> "CASE" to "desktop PC case/chassis pattern"
        else -> return null
    }

    // Second safety gate: make sure it doesn't MATCH obvious laptops / peripherals
    val laptopBad = Regex("laptop|notebook|macbook|surface\\s*pro|thinkpad|spectre|envy\\s*x360|\\d{4,5}[hhuuyyg]\\b|hs\\b|hx\\b|hq\\b|mq\\b", RegexOption.IGNORE_CASE)
    val peripheralBad = Regex("monitor|display|screen|tv|television|keyboard|\\bkb\\b|mouse|headset|headphone|earbud|speaker|soundbar|microphone|\\bmic\\b|webcam|printer|scanner|projector|\\bups\\b|(^|[\\s])avr([\\s]|\$)|usb\\s+hub|docking\\s+station|controller|gamepad|joystick|vr\\s+headset", RegexOption.IGNORE_CASE)
    if (laptopBad.containsMatchIn(qLower) || peripheralBad.containsMatchIn(qLower)) {
        Log.i(tag, "PATTERN_PASS: block q=$qTrim matched laptop/peripheral bad regex")
        return null
    }

    // Build normalized name using manufacturer prefix hints
    val normalizedNameRaw = when (cat) {
        "GPU" -> when {
            qLower.contains("radeon") || qLower.contains("rx ") || qLower.contains("amd ") -> qTrim.replaceFirst("(?i)^amd\\s*radeon\\s*".toRegex(), "AMD Radeon ").ifBlank { qTrim }
            qLower.contains("arc") -> qTrim.replaceFirst("(?i)^intel\\s*arc\\s*".toRegex(), "Intel Arc ").ifBlank { qTrim }
            qLower.contains("geforce") || qLower.contains("rtx") || qLower.contains("gtx") || qLower.contains("nvidia ") -> qTrim.replaceFirst("(?i)^nvidia\\s*(geforce\\s*)?".toRegex(), "NVIDIA GeForce ").ifBlank { qTrim }
            else -> qTrim
        }
        "CPU" -> when {
            qLower.contains("ryzen") || qLower.contains("athlon") || qLower.contains("threadripper") || qLower.contains("amd ") -> qTrim.replaceFirst("(?i)^amd\\s*".toRegex(), "AMD ").ifBlank { qTrim }
            qLower.contains("core") || qLower.contains("celeron") || qLower.contains("pentium") || qLower.contains("ultra") || qLower.contains("intel ") -> qTrim.replaceFirst("(?i)^intel\\s*".toRegex(), "Intel ").ifBlank { qTrim }
            else -> qTrim
        }
        else -> qTrim
    }
    val normalizedName = preserveUserBrandedNameIfProvided(query, normalizedNameRaw)
    val score = SmartQuerySpecGuesser.guess(normalizedName)?.score ?: 60
    val mf = when (cat) {
        "GPU" -> if (normalizedName.lowercase().contains("amd") || normalizedName.lowercase().contains("radeon") || normalizedName.lowercase().contains("rx ")) "AMD" else if (normalizedName.lowercase().contains("arc")) "Intel" else "NVIDIA"
        "CPU" -> if (normalizedName.lowercase().contains("ryzen") || normalizedName.lowercase().contains("amd")) "AMD" else "Intel"
        else -> normalizedName.split(" ").firstOrNull { it.length >= 3 }?.take(16) ?: ""
    }
    Log.i(tag, "PATTERN_PASS ACCEPT [$patternName]: q=$qTrim -> normalized='$normalizedName' (cat=$cat, aiConfidence=70)")
    return ExactSearchResult.Success(
        component = ComponentDetail(
            name = normalizedName,
            category = cat,
            score = score,
            specs = listOfNotNull(
                "Component type: $cat",
                "Identification method: Generic pattern-based validation ($patternName)",
                "Verification hint: This component was recognized by desktop PC hardware naming patterns. For exact spec-level compatibility, verify model manually.",
                "Manufacturer (detected): $mf"
            ),
            aiConfidence = 70,
            manufacturer = mf
        ),
        aiConfidence = 0.70f,
        componentId = "pat-${cat.lowercase()}-${normalizedName.replace("""[\s\-_/()]+""".toRegex(), "_").lowercase()}"
    )
}

object MockRecommendationRepository : RecommendationRepository {
    override suspend fun getSearchResults(query: String): List<String> {
        return RecommendationMockData.getSearchResults(query)
    }

    override suspend fun validateComponentWithGpt(input: String): OpenAiDto.JsonComponentValidation? {
        return AiBuildOrchestrator.validateComponentWithGPT(input)
    }

    override suspend fun getExactSearchResult(query: String): ExactSearchResult {
        // Same OFFLINE-PRECHECK regex as Firestore impl - mock must also reject laptop parts.
        val qTrim = query.trim().also { if (it.isBlank()) return ExactSearchResult.InvalidQuery(query, "Search query is empty.", "wrong_model", 0f, "unknown") }
        val qLow = qTrim.lowercase()
        val tag = "MockRecoRepo"
        val laptopKeywords = listOf("laptop","notebook","macbook","surface pro","thinkpad","spectre","envy x360")
        if (laptopKeywords.any { it in qLow }) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE PART DETECTED - '$qTrim' mentions a laptop device or product line. This app supports DESKTOP PC DIY parts only (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case for desktop PCs).", "laptop_part", 0f, "laptop")
        }
        // PERIPHERAL / NON-PC KEYWORD HARD REJECT (matches backend LAYER 0B list - catches Monitor/KB/Mouse/UPS/Webcam/Speaker even if AI/SmartGuess mislabels as GPU/CPU)
        val peripheralKeywords = listOf(
            "monitor","display","screen","tv","television",
            "keyboard","mech kb",
            "mouse","mice","trackpad","trackball",
            "headset","headphone","earphone","earbuds","speaker","soundbar","subwoofer",
            "microphone","webcam","camera",
            "printer","scanner","projector",
            "uninterruptible power supply",
            "usb hub","docking station","dock station",
            "chair","table","desk","fan","aircon",
            "controller","gamepad","joystick","wheel","vr headset"
        )
        peripheralKeywords.firstOrNull { it in qLow }?.let { kw ->
            return ExactSearchResult.InvalidQuery(
                query,
                "NOT A DESKTOP PC COMPONENT - '$qTrim' looks like '$kw' (peripheral/accessory/non-PC-part). This app ONLY accepts DESKTOP PC DIY HARDWARE: CPU, GPU, Motherboard, RAM, Storage (SSD/HDD), PSU, Cooler, Case. Peripherals like Monitor/KB/Mouse/UPS/Headset are NOT supported by the PC compatibility engine.",
                "not_pc_part",
                0f,
                "unknown"
            )
        }
        // UPS/AVR special pattern (with surrounding word boundaries to avoid false positives like 'cupS' etc.)
        val upsAvrRe = Regex("(^|[\\s\\-_])(ups|avr)(\$|[\\s\\-_])", RegexOption.IGNORE_CASE)
        if (upsAvrRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(
                query,
                "NOT A DESKTOP PC COMPONENT - '$qTrim' (UPS/AVR) is a power accessory NOT supported by the PC DIY build engine. Only CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case (desktop PC parts) are accepted.",
                "not_pc_part",
                0f,
                "unknown"
            )
        }
        // Also kb / mic / mouse with word boundaries
        val shortPeripheralRe = Regex("(^|[\\s\\-_])(kb|mic)(\$|[\\s\\-_])", RegexOption.IGNORE_CASE)
        if (shortPeripheralRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(
                query,
                "NOT A DESKTOP PC COMPONENT - '$qTrim' (kb/mic) is a peripheral/accessory. Only CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case (desktop PC parts) accepted.",
                "not_pc_part",
                0f,
                "unknown"
            )
        }
        // ===== ARCHITECTURE CHANGE: GPT VALIDATOR FIRST (new PC component generic whitelist-free pass for RTX 5050 etc.) =====
        //   Safety regex guards (laptop/peripheral/UPS/kb/mic) run BEFORE GPT validator.
        //   If GPT says is_pc_component=true & category is in 8-set -> BYPASS LocalSpecDatabase/SmartQuery/guess/CATEGORY WHITELIST local rejection entirely.
        //   This allows ANY valid future/current desktop PC component name through WITHOUT requiring manual whitelist addition.
        //   Falls through below to old local logic if GPT null (no key) OR GPT says not PC component (iPhone/basketball/random text).
        tryGptValidateAndReturnValidMatchOrNull(query, qTrim, qLow, tag)?.let { return it }
        val cpuMobileRe = Regex("(^|[\\s\\-_])(\\d{4,5}[hHuUyY]\\b|hs\\b|hx\\b|hq\\b|mq\\b|lm\\b|um\\b|g[1-7]\\b|[a-z0-9]m\\b|mobile(?!\\s*legend))", RegexOption.IGNORE_CASE)
        val gpuMobileRe = Regex("(^|[\\s\\-_])(mx\\d+\\b|\\d{3,4}m\\b|max[\\s\\-]?q\\b|(rtx|gtx|rx|radeon|geforce|arc)\\s.*\\blaptop\\b|laptop\\s.*\\bgpu\\b|\\bgt\\s?1\\b|(super|ti)\\s+mobile\\b|quadro\\s+\\w+\\s*m\\b)", RegexOption.IGNORE_CASE)
        if (cpuMobileRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE CPU DETECTED - '$qTrim' uses a mobile/laptop CPU suffix (H,HS,HX,U,Y,G1-G7,MQ,M etc). Only DESKTOP CPU models (suffix K/KF/F/X/XT/G/T or no suffix) are accepted in this app.", "laptop_part", 0f, "laptop")
        }
        if (gpuMobileRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE GPU DETECTED - '$qTrim' uses a mobile/laptop GPU suffix (M/MX/Max-Q/Laptop etc). Only DESKTOP GPU models (Ti/Super/XT or standard suffixes) are accepted in this app.", "laptop_part", 0f, "laptop")
        }

        val local = LocalSpecDatabase.find(query)
            ?: SmartQuerySpecGuesser.guess(query)
            ?: runCatching { RecommendationMockData.getComponentDetails(query) }
                .getOrNull()?.takeIf { it.aiConfidence >= 70 && it.name != "Unknown Component" }

        // ===== CATEGORY ALIAS NORMALIZATION (F1c): Fix MOBO/STOR aliases before any whitelist guard =====
        val localNorm = local?.let { comp ->
            val rawCat = comp.category.trim().uppercase()
            var normalizedCat: String
            if (rawCat == "CENTRAL PROCESSING UNIT" || rawCat == "PROCESSOR") {
                normalizedCat = "CPU"
            } else if (rawCat == "GRAPHICS CARD" || rawCat == "GRAPHICS PROCESSING UNIT" || rawCat == "VIDEO CARD" || rawCat == "VGA") {
                normalizedCat = "GPU"
            } else if (rawCat == "MEMORY" || rawCat == "RANDOM ACCESS MEMORY" || rawCat == "MEM" || rawCat == "DDR" || rawCat == "DDR4" || rawCat == "DDR5" || rawCat == "DDR3") {
                normalizedCat = "RAM"
            } else if (rawCat == "MAINBOARD" || rawCat == "MOTHERBOARD" || rawCat == "MOBO" || rawCat == "MB" || rawCat == "MOTHER BOARD" || rawCat == "MAIN BOARD") {
                normalizedCat = "MOTHERBOARD"
            } else if (rawCat == "STORAGE DEVICE" || rawCat == "SSD" || rawCat == "HDD" || rawCat == "NVME" || rawCat == "STORAGE" || rawCat == "STOR" || rawCat == "M.2" || rawCat == "NVME SSD") {
                normalizedCat = "STORAGE"
            } else if (rawCat == "POWER SUPPLY" || rawCat == "POWER SUPPLY UNIT" || rawCat == "POWER" || rawCat == "POWER SUPPY") {
                normalizedCat = "PSU"
            } else if (listOf("AIR COOLER", "WATER COOLER", "CPU COOLER", "COOL", "COOLER", "AIO COOLER", "LIQUID COOLER", "COOLERS").contains(rawCat)) {
                normalizedCat = "COOLER"
            } else if (listOf("COMPUTER CASE", "PC CASE", "CHASSIS", "CASING").contains(rawCat)) {
                normalizedCat = "CASE"
            } else {
                // FALLBACK: prefix rule checks (previously nested-when inside outer-when else branch)
                if (rawCat.startsWith("MOTH") || rawCat.startsWith("MAIN")) {
                    normalizedCat = "MOTHERBOARD"
                } else if (rawCat.startsWith("STOR") || rawCat == "SSD" || rawCat == "HDD" || rawCat == "NVME") {
                    normalizedCat = "STORAGE"
                } else if (rawCat.startsWith("COOL")) {
                    normalizedCat = "COOLER"
                } else if (rawCat.startsWith("MEM") || rawCat.startsWith("DDR")) {
                    normalizedCat = "RAM"
                } else if (rawCat.startsWith("POW")) {
                    normalizedCat = "PSU"
                } else if (rawCat.startsWith("CHAS") || rawCat.contains("CASE")) {
                    normalizedCat = "CASE"
                } else {
                    normalizedCat = comp.category.uppercase()
                }
            }
            comp.copy(category = normalizedCat)
        }

        // CATEGORY WHITELIST AFTER GUESS (LAYER 0): Only 8 DESKTOP PC DIY categories allowed
        val validPCCategoriesUpper = setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")
        // LEMONADE BUG FIX (F1c expanded): Helper - returns TRUE if user query has any recognizable hardware-indicating tokens (ALL 8 categories, 250+ tokens)
        fun hasHardwareIndicatingTokens(qn: String): Boolean {
            val hwWords = listOf(
                // ===== CPU brands =====
                "ryzen", "amd", "intel", "core", "pentium", "celeron", "athlon", "threadripper",
                // ===== GPU brands / key series =====
                "nvidia", "geforce", "rtx", "gtx", "radeon", "rx", "arc", "sapphire", "xfx", "powercolor", "zotac", "gigabyte", "asus", "msi", "evga", "inno3d", "palit", "gainward",
                // ===== Motherboard BRANDS + CHIPSETS + SOCKETS =====
                "msi", "mag", "mpg", "meg", "asus", "rog", "tuf", "prime", "asrock", "steel legend", "pro rs", "taichi", "gigabyte", "aorus", "elite", "ud", "ds3h", "b450m", "mortar", "tomahawk", "evga", "nzxt", "biostar", "ecson", "jginys", "maxsun", "huabosi", "machinist",
                "b450", "b550", "x570", "a520", "a620", "b650", "x670", "b760", "b660", "h610", "h670", "z690", "z790", "h770", "b760m", "z890", "x870", "b850", "x299", "w480", "q670", "z490", "b460", "h410", "z590", "b560", "h510",
                "am4", "am5", "lga1200", "lga1700", "lga1851", "lga1151", "lga1150", "lga2066", "sWRX8", "tr4",
                // ===== Storage BRANDS + SERIES =====
                "samsung", "990 pro", "980 pro", "970 evo", "870 evo", "wd", "western digital", "sn850x", "sn770", "sn580", "sn570", "blue", "black", "crucial", "p5", "p3", "t700", "t500", "mx500", "kingston", "kc3000", "kc2500", "nv2", "a400", "lexar", "nm790", "nm710", "nm620", "teamgroup", "mp34", "mp44", "solidigm", "p41", "p44", "adata", "sx8200", "sx6000", "swordfish", "seagate", "firecuda", "barracuda", "sabrent", "rocket",
                // ===== RAM BRANDS + SERIES + PERF MARKERS =====
                "g.skill", "gskill", "trident z5", "trident z", "ripjaws", "corsair", "vengeance", "dominator", "kingston fury", "fury beast", "fury renegade", "team", "t-force", "delta", "vulcan", "night hawk", "adata xpg", "lancer", "spectrix", "patriot", "viper", "venom",
                "xmp", "expo", "cl14", "cl15", "cl16", "cl18", "cl20", "cl22", "5600mt", "6000mt", "6400mt", "7200mt", "7600mt", "8000mt",
                // ===== PSU BRANDS + SERIES + EFFICIENCY + WATTAGE MARKERS =====
                "seasonic", "focus", "prime", "connect", "corsair", "rm", "rmx", "rme", "sf", "sf750", "sf1000l", "hxi", "axi", "evga", "supernova", "ga", "g5", "g6", "g7", "p2", "t2", "be quiet", "bequiet", "straight power", "dark power", "system power", "cooler master", "mwe", "v", "g", "thermaltake", "toughpower", "grand", "smart", "lian li", "sp", "edge", "nzxt", "c", "xfx", "speedster", "pro", "deepcool", "dq", "pm", "pk", "ancient",
                "550w", "650w", "750w", "850w", "1000w", "1200w", "1300w", "1500w", "1600w", "80 plus", "80+", "bronze", "gold", "platinum", "titanium", "full modular", "semi-modular", "non-modular", "atx 3.0", "atx3.0", "pcie 5.0", "12vhpwr",
                // ===== COOLER BRANDS + SERIES + AIO SIZES =====
                "noctua", "nh-d15", "nh-u12a", "nh-u14s", "nh-l12", "chromax", "deepcool", "ak620", "ak500", "ag620", "ls720", "lt720", "ls520", "lt520", "castle", "nzxt", "kraken", "elite", "rgb", "corsair", "h100i", "h115i", "h150i", "h170i", "icue link", "lian li", "galahad", "aio", "sl", "infiniti", "thermalright", "peerless assassin", "phantom spirit", "frost spirit", "dark rock", "be quiet", "shadow rock", "silent loop", "arctic", "liquid freezer", "freezer", "cooler master", "hyper 212", "masterliquid", "ml360", "ml280", "evga", "clc",
                "240mm", "280mm", "360mm", "420mm", "120mm", "140mm", "aio cooler", "air cooler", "liquid cooler", "cpu cooler", "heatsink",
                // ===== CASE BRANDS + SERIES + FORM FACTORS =====
                "lian li", "lancool", "o11", "dynamic", "evo", "vision", "nzxt", "h flow", "h5", "h7", "h9", "h510", "h510i", "s340", "cooler master", "haf", "masterbox", "nr200", "corsair", "4000d", "4000x", "5000d", "5000x", "7000d", "220t", "fractal", "north", "pop", "meshify", "define", "phanteks", "enthoo", "evolv", "tecware", "forge", "nexus", "hyte", "y40", "y60", "y70", "touch", "montech", "air 903", "air 1000", "sky", "king", "rakk", "kasa", "kisig", "damysus", "anyto", "nv5", "nv7", "jupiter", "apnx", "fsp", "casing",
                "mid tower", "full tower", "super tower", "mini tower", "sff", "small form factor", "mesh front", "tempered glass", "tg", "argb", "rgb", "fan hub",
                // ===== Generic hardware markers =====
                "ddr4", "ddr5", "ddr3", "udimm", "dimm", "nvme", "pcie", "pci-e", "gen4", "gen5", "sata", "psu",
                "atx", "matx", "itx", "e-atx", "eatx", "sff", "mid tower", "full tower", "super tower", "mesh front", "tempered glass", "argb"
            )
            for (w in hwWords) if (w in qn) return true
            return """\d{2,}""".toRegex().containsMatchIn(qn)
        }
        // LEMONADE BUG FIX: Helper - returns non-null error if guessed component and user query have ZERO overlap (arbitrary word)
        fun queryGuessedSimilarityFail(queryTrim: String, guess: ComponentDetail): String? {
            val qTok = queryTrim.lowercase().split("""[\s\-_]+""".toRegex()).filter { it.length >= 2 }.toSet()
            val nameTok = guess.name.lowercase().split("""[\s\-_]+""".toRegex()).filter { it.length >= 2 }.toSet()
            val mfTok = guess.manufacturer.lowercase().split("""[\s\-_]+""".toRegex()).filter { it.length >= 2 }.toSet()
            val catTok = guess.category.lowercase().split("""[\s\-_]+""".toRegex()).filter { it.length >= 2 }.toSet()
            val qNums = """\d+""".toRegex().findAll(queryTrim.lowercase()).map { it.value }.toSet()
            val guessNums = """\d+""".toRegex().findAll(("${guess.name} ${guess.manufacturer}").lowercase()).map { it.value }.toSet()
            // PASS if any overlap in: word tokens or numeric models or known manufacturers
            val numOverlap = qNums intersect guessNums
            val wordOverlap = qTok intersect (nameTok union mfTok union catTok)
            val mfrOverlap = mfTok intersect qTok
            if (numOverlap.isNotEmpty() || wordOverlap.isNotEmpty() || mfrOverlap.isNotEmpty()) return null
            // FAIL: zero overlap -> random word that SmartGuesser tried to fill in (e.g. "lemonade" -> 4090 GPU has NO tokens in common with query)
            return "Query '${queryTrim}' and identified component '${guess.name}' share NO recognizable hardware tokens (no matching digits/numbers and no matching brand/model words)."
        }
        return if (localNorm != null) {
            if (localNorm.category.uppercase() !in validPCCategoriesUpper) {
                return ExactSearchResult.InvalidQuery(
                    query,
                    "NOT A DESKTOP PC COMPONENT - '${localNorm.name}' (category ${localNorm.category}) is NOT in the 8 supported desktop PC DIY categories (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case). Hint: peripherals like Monitor/KB/Mouse/UPS are not supported by the PC build compatibility engine.",
                    "not_pc_part",
                    aiConfidence = localNorm.aiConfidence / 100f,
                    formFactor = null
                )
            }
            // 2ND NAME GUARD: If the guessed component NAME (lowercase) contains peripheral keyword -> still reject even if category looks ok
            val nameLow = localNorm.name.lowercase()
            val matchedNameKw = peripheralKeywords.firstOrNull { it in nameLow }
            if (matchedNameKw != null || upsAvrRe.containsMatchIn(nameLow) || shortPeripheralRe.containsMatchIn(nameLow)) {
                return ExactSearchResult.InvalidQuery(
                    query,
                    "NOT A DESKTOP PC COMPONENT - '${localNorm.name}' looks like a peripheral/accessory (${matchedNameKw ?: "ups/avr/kb/mic"}). Only DESKTOP PC DIY hardware (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case) is supported by the PC build compatibility engine.",
                    "not_pc_part",
                    aiConfidence = localNorm.aiConfidence / 100f,
                    formFactor = null
                )
            }
            // LEMONADE BUG FIX LAYER 3A: Query not hardware-indicating at all AND guess returned -> INVALID
            if (!hasHardwareIndicatingTokens(qLow)) {
                return ExactSearchResult.InvalidQuery(
                    query,
                    "NOT A DESKTOP PC COMPONENT - '$qTrim' doesn't contain any recognizable PC hardware model numbers or brand/series words (e.g. Ryzen/Core/RTX/DDR4/500-series/etc.). Only CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case DESKTOP parts are accepted.",
                    "not_pc_part",
                    aiConfidence = 0f,
                    formFactor = "unknown"
                )
            }
            // LEMONADE BUG FIX LAYER 3B: Guessed name and user query have ZERO token/number overlap -> INVALID
            queryGuessedSimilarityFail(qTrim, localNorm)?.let { reason ->
                val conf = localNorm.aiConfidence.coerceAtMost(40)
                return ExactSearchResult.InvalidQuery(
                    query,
                    "NOT A DESKTOP PC COMPONENT - '$qTrim' produced an unrelated guess. $reason. Only DESKTOP PC DIY hardware (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case) model searches are supported.",
                    "not_pc_part",
                    aiConfidence = conf / 100f,
                    formFactor = "unknown"
                )
            }
            // LEMONADE BUG FIX LAYER 3C (F1c threshold LOWERED 75->50): aiConfidence threshold below -> reject local fallback guesses
            if (localNorm.aiConfidence < 50) {
                return ExactSearchResult.InvalidQuery(
                    query,
                    "NOT A DESKTOP PC COMPONENT - '$qTrim' could not be reliably identified as a supported DESKTOP PC DIY part (low local confidence ${localNorm.aiConfidence}/100). Try exact brand+model e.g. 'Ryzen 5 5600' or 'RTX 4060'.",
                    "not_pc_part",
                    aiConfidence = localNorm.aiConfidence / 100f,
                    formFactor = "unknown"
                )
            }
            if (coreModelNumberMismatch(qTrim, localNorm.name)) {
                Log.w(tag, "MOCK EXACT: LOCAL MODEL NUMBER MISMATCH. query=[$qTrim] local=[${localNorm.name}] -> ignore local fuzzy match and try generic pattern validation")
                tryPatternBasedGenericPcComponentPassOrNull(query, qTrim, qLow, "MockExactSearch")?.let { return it }
                return ExactSearchResult.NotFound(
                    query = query,
                    reason = "Exact component not found."
                )
            }
            val displayComponent = preserveUserBrandedNameIfProvided(query, localNorm.name).let { preservedName ->
                if (preservedName == localNorm.name) localNorm else localNorm.copy(name = preservedName)
            }
            ExactSearchResult.Success(
                component = displayComponent,
                aiConfidence = localNorm.aiConfidence / 100f,
                componentId = "local-${localNorm.category}-${localNorm.name}",
                specifications = emptyMap()
            )
        } else {
            tryPatternBasedGenericPcComponentPassOrNull(query, qTrim, qLow, "MockExactSearch")?.let { return it }
            ExactSearchResult.NotFound(
                query = query,
                reason = "Exact component not found."
            )
        }
    }

    override suspend fun getBuildRecommendations(component: String): List<BuildRecommendation> {
        return RecommendationMockData.getBuildRecommendations(component)
    }

    private data class GptUpgradeJson(
        val upgrade_component_name: String,
        val upgrade_component_category: String,
        val upgrade_title: String,
        val upgrade_description: String,
        val compatibility_score: Int,
        val upgrade_description_short: String? = null
    )

    private fun buildDeterministicBottleneckAnalysis(
        cpu: String,
        gpu: String,
        pctIn: Int,
        noteRaw: String,
        limitingRaw: String
    ): BottleneckAnalysis {
        val pct = pctIn.coerceIn(0, 20)
        val status = when {
            pct <= 10 -> "EXCELLENT"
            pct <= 15 -> "GOOD"
            else -> "ACCEPTABLE"
        }
        val cpuShort = cpu.ifBlank { "Unknown CPU" }
        val gpuShort = gpu.ifBlank { "Unknown GPU" }
        val limiting = when {
            limitingRaw.contains("cpu", ignoreCase = true) -> "CPU"
            limitingRaw.contains("gpu", ignoreCase = true) -> "GPU"
            else -> "Balanced"
        }
        val summary = "Estimated bottleneck: approximately ${pct}%. (Status: $status.) The $cpuShort and $gpuShort are reasonably balanced. Neither the CPU nor the GPU is expected to significantly restrict the other in typical gaming workloads."
        val prefix = if (noteRaw.isBlank()) "" else noteRaw.trim() + " "
        val detailed = buildString {
            append(prefix)
            append("This estimate reflects the typical performance difference between the two chips across a representative suite of modern titles. ")
            append("If the CPU has fewer cores, lower IPC, or smaller L3 cache than a flagship class, it can limit high-end GPU throughput at low resolutions. ")
            append("If the GPU has fewer compute units or lower VRAM capacity, it becomes the limiting factor as resolution and texture detail increase. ")
            append("Actual results vary depending on five factors: Game, Resolution, Graphics settings, FPS target, and Workload. ")
            append("Estimated bottleneck; actual result varies by workload.")
        }
        val g1080 = if (limiting == "CPU") {
            "At 1080p the Ryzen/i5-class CPU may become more noticeable at high FPS targets such as 144Hz or 240Hz. Turn-based / strategy titles are unlikely to show the difference, but competitive shooters often scale harder into CPU performance at this resolution."
        } else {
            "At 1080p this pairing is typically GPU-bound, though fast-paced competitive titles with uncapped frame rates can still stress the CPU enough to keep the GPU waiting. For 60 FPS casual gameplay the experience is generally smooth."
        }
        val g1440 = "At 1440p this pairing usually lands in the 1440p sweet spot where load is distributed between CPU and GPU. Bottleneck becomes rare in most AAA titles; CPU-limited scenarios occur mainly in simulation-heavy games with many units or heavy draw-call overhead."
        val g4k = "At 4K rendering the GPU is almost always the limiting component regardless of the CPU for most AAA titles. Unless running extremely high-FPS simulation or strategy workloads, the CPU will rarely be the bottleneck at 4K resolution."
        val reason = when (limiting) {
            "CPU" -> "The CPU is the limitation because its per-thread performance, core count, or cache size are not high enough to keep up with the GPU at low resolutions when the GPU is lightly loaded."
            "GPU" -> "The GPU is the limitation because its raw CUDA/shader count or onboard VRAM capacity cannot push the pixel throughput needed at the target resolution before the CPU runs out of work to hand it."
            else -> "Neither component substantially limits the other in this pairing; their relative performance tiers are well matched for balanced 1440p and typical 4K gaming workloads."
        }
        return BottleneckAnalysis(
            percentage = pct,
            status = status,
            limitingComponent = limiting,
            summary = summary,
            detailedAnalysis = detailed.trim(),
            gaming1080p = g1080,
            gaming1440p = g1440,
            gaming4k = g4k,
            reason = reason,
            withinLimit = pct <= 20
        )
    }

    private fun jsonBottleneckToDomainOrNull(
        json: com.pcscan.pcscan.data.api.OpenAiDto.JsonBottleneckAnalysis?,
        cpu: String,
        gpu: String,
        fallbackPct: Int,
        fallbackNote: String,
        fallbackLimitingRaw: String
    ): BottleneckAnalysis {
        if (json == null) {
            return buildDeterministicBottleneckAnalysis(cpu, gpu, fallbackPct, fallbackNote, fallbackLimitingRaw)
        }
        val pct = json.percentage ?: fallbackPct
        val pctSafe = pct.coerceIn(0, 20)
        val limiting = json.limiting_component.orEmpty().ifBlank { fallbackLimitingRaw }
        val summaryIn = json.summary.orEmpty()
        val summary = summaryIn.ifBlank {
            val status = when {
                pctSafe <= 10 -> "EXCELLENT"
                pctSafe <= 15 -> "GOOD"
                else -> "ACCEPTABLE"
            }
            "Estimated bottleneck: approximately ${pctSafe}%. (Status: $status.) The $cpu and $gpu are reasonably balanced."
        }
        return BottleneckAnalysis(
            percentage = pctSafe,
            status = json.status?.uppercase()?.takeIf { it.isNotBlank() } ?: when {
                pctSafe <= 10 -> "EXCELLENT"
                pctSafe <= 15 -> "GOOD"
                else -> "ACCEPTABLE"
            },
            limitingComponent = when {
                limiting.contains("cpu", ignoreCase = true) -> "CPU"
                limiting.contains("gpu", ignoreCase = true) -> "GPU"
                limiting.equals("balanced", ignoreCase = true) -> "Balanced"
                else -> "Balanced"
            },
            summary = summary,
            detailedAnalysis = (json.detailed_analysis ?: fallbackNote).ifBlank {
                "Estimated bottleneck; actual result varies by workload."
            },
            gaming1080p = json.gaming_1080p.orEmpty().ifBlank {
                "1080p behavior: higher FPS targets in competitive shooters can shift limitation toward the CPU."
            },
            gaming1440p = json.gaming_1440p.orEmpty().ifBlank {
                "1440p behavior: typically the balanced sweet spot between CPU and GPU workload."
            },
            gaming4k = json.gaming_4k.orEmpty().ifBlank {
                "4K behavior: overwhelmingly GPU-limited in most modern AAA titles."
            },
            reason = json.reason.orEmpty().ifBlank {
                "Relative performance between current CPU and GPU tiers determines the balance."
            },
            withinLimit = json.within_limit ?: (pctSafe <= 20)
        )
    }

    private val gson = Gson()

    private val PCSCAN_MASTER_ENGINE_SPEC: String = """
==================================================
PCSCAN MASTER ENGINE SPEC (ALWAYS APPLIES -- NO EXCEPTIONS)
==================================================

PcScan ONLY handles these 6 main PC components:
1. CPU
2. GPU
3. RAM
4. SSD / Storage
5. Motherboard
6. PSU

Do NOT recommend, list, mention, describe, placeholder, or JSON-encode any other parts:
- NO CPU Cooler, PC Case, Case Fans, Monitor, Keyboard, Mouse, Headset, Peripherals.
- The final output / JSON arrays MUST contain only the 6 components above.

PRIMARY GOAL:
COMPATIBLE + BALANCED + PRACTICAL + HIGH-PERFORMANCE 6-component PC configuration.
Do NOT recommend the MOST powerful or MOST expensive component automatically.
Consider the ENTIRE 6-component system as one balanced configuration.

BOTTLENECK REQUIREMENT (HARD LIMIT):
- Maximum acceptable CPU/GPU bottleneck = 20%.
- Target: 0-10% Excellent, 10-15% Good, 15-20% Acceptable.
- ABOVE 20% = REJECT. NEVER intentionally recommend a CPU/GPU combo >20% estimated bottleneck.
- If your first pick exceeds 20%, SELECT A DIFFERENT CPU or GPU combination.
- Bottleneck % is an ESTIMATE; actual varies by game/resolution/settings/FPS/workload.
- If you cannot compute exact benchmarks, include text: "Estimated bottleneck; actual result varies by workload."

COMPATIBILITY REQUIREMENT (100% before finalize):
CPU <-> Motherboard: socket, chipset, CPU gen, BIOS when relevant.
Motherboard <-> RAM: DDR4 vs DDR5, supported speed/type.
CPU <-> RAM: platform support, appropriate config.
GPU <-> Motherboard: PCIe compatibility / clearance.
GPU <-> PSU: GPU wattage + required power connectors.
SSD <-> Motherboard: M.2 / SATA, PCIe generation when relevant.
CPU + GPU <-> PSU: estimated total system draw + reasonable headroom.
If any component is INCOMPATIBLE -> DO NOT recommend that combination, pick a compatible alternative.

NEW BUILD MODE (when asked for a build):
- Select ALL 6 components: CPU / GPU / RAM / SSD / Motherboard / PSU as ONE BALANCED system.
- Never pick independently (example BAD: "4090 strongest therefore 4090"; example GOOD: "which GPU keeps bottleneck <=20% with chosen CPU?").

UPGRADE MODE (when given an existing current build):
- FIRST analyze the 6 CURRENT components.
- Only recommend upgrades that are actually USEFUL / MEANINGFUL.
- Already-sufficient components remain UNCHANGED.
- Use KEEP (no mention needed; omission from upgrade list = implicit KEEP) vs UPGRADE.
- DO NOT upgrade everything: i5-14400F + Arc A380 build -> CPU KEEP, GPU UPGRADE, RAM maybe, SSD KEEP, Mobo KEEP, PSU only if new GPU needs it.

NO UNNECESSARY PLATFORM UPGRADES:
- Do NOT change motherboard simply because Z790/X570/X670 exists.
- KEEP existing motherboard if it is 100% compatible with the recommended CPU, RAM, GPU, SSD, PSU.

RAM RULE:
- Modern gaming: 16GB usable, 32GB preferred.
- Do NOT auto-recommend 64GB/128GB unless the workload benefits it (production user explicitly needs it).

SSD RULE:
- Do NOT replace fast SSD because another has marginally higher seq-read.
- KEEP SSD when already sufficiently fast + enough capacity.
- Upgrade storage ONLY when: capacity missing, current SSD very slow, compatibility requires it, user needs additional storage.

PSU RULE:
- PSU wattage = CPU draw + GPU draw + ~50-100W baseline/system + 1.3-1.5x headroom.
- Round to common step 450/550/650/750/850/1000W.
- Do NOT recommend 1000W for high-end builds unless genuinely needed (~600W draw = 750-850W, not 1000W).
- Only recommend PSU upgrade when the new TDP genuinely requires it.

CPU/GPU BALANCE:
- CPU & GPU reasonably balanced.
- Do NOT pair entry/mid CPU (Ryzen3/Ryzen5/i3/i5 below i7-13700K class) with flagship GPU (RTX 4090/5080/5090 or RX 7900XTX+/RX 9070+XT class) because that will produce >20% CPU bottleneck -> REJECTED.
- If flagship GPU is needed, upgrade the CPU ALONGSIDE (same-socket preferred, no unnecessary mobo swap).
- Vice-versa: do not pair Ryzen 9 / i9 with unnecessarily weak GPU if goal is gaming performance.

WHEN INFORMATION IS MISSING:
- Do NOT invent user hardware.
- Use reasonable assumptions ONLY when strictly necessary, and EXPLICITLY state that they are assumptions + explain limitations.

RECOMMENDATION PROCESS (internal):
STEP 1: Understand goal.
STEP 2: Identify NEW BUILD vs UPGRADE.
STEP 3: Analyze the 6 components.
STEP 4: Map compatibility requirements (socket, DDR gen, PCIe, TDP).
STEP 5: Lock CPU/GPU BALANCED COMBO first.
STEP 6: Estimate CPU/GPU bottleneck %.
STEP 7: If bottleneck >20% -> REJECT, go back to STEP5 with different combo.
STEP 8: Select compatible motherboard.
STEP 9: Select compatible RAM (DDR gen + size rule above).
STEP 10: Select compatible SSD.
STEP 11: Select appropriate PSU (calculated headroom).
STEP 12: FINAL VALIDATION check (see below).
STEP 13: Return ONLY the valid recommendation.

FINAL VALIDATION CHECKLIST (must PASS ALL):
[ ] CPU compatible with motherboard
[ ] CPU compatible with RAM platform (DDR4/DDR5)
[ ] GPU compatible with motherboard (PCIe slot + physical clearance)
[ ] SSD compatible with motherboard (M.2/SATA/PCIe gen)
[ ] PSU sufficient for CPU + GPU + system + headroom
[ ] CPU/GPU bottleneck <= 20%
[ ] No unnecessary upgrades (KEEP components that are fine)
[ ] No unsupported / incompatible hardware combinations
[ ] Output contains ONLY the 6 PcScan components (no cooler/case/monitor/peripherals)

If ANY requirement FAILS -> DO NOT return the configuration.
Change the recommendation until ALL requirements are SATISFIED.
==================================================
END OF PCSCAN MASTER ENGINE SPEC
==================================================
""".trimIndent()

    private val UPGRADE_AI_SYSTEM_PROMPT: String = """
$PCSCAN_MASTER_ENGINE_SPEC

You are a PC component upgrade recommendation engine. You are given a CURRENT USER COMPONENT (the part a user currently has in their PC, which is also the component the user SEARCHED / SCANNED / UPLOADED = PRIMARY FOCUS COMPONENT), and a TARGET UPGRADE TIER (MIDRANGE or HIGH-END). Your job is to recommend the SINGLE STRONGEST SPECIFIC REAL PC COMPONENT OF THE SAME CATEGORY (CPU -> CPU upgrade; GPU -> GPU upgrade; RAM -> RAM upgrade, etc.) that fits the target tier, strictly stronger than the user's current part, AND such that the UPGRADED COMPONENT will form a BALANCED COMBO (<20% BOTTLENECK MAX) when the rest of a typical user PC build is upgraded alongside it.

Strict rules -- NO EXCEPTIONS:
1. CATEGORY LOCK: You MUST recommend a component OF EXACTLY THE SAME CATEGORY as input. If input is GPU -> upgrade is GPU. If input is CPU -> upgrade CPU. If input is RAM kit -> upgrade RAM kit. NO cross-category upgrades.
2. GENERIC PLACEHOLDER REJECT: You MUST return an EXACT, REAL, SPECIFIC CURRENT-GEN RETAIL MODEL NAME (example: "NVIDIA GeForce RTX 4080 Super", "AMD Ryzen 9 9950X"). NEVER return brand-only ("NVIDIA", "AMD", "Intel"), NEVER category-only ("GPU"/"CPU"), NEVER empty, NEVER vague description without model number.
3. VARIANT PRECISION: Do not drop Ti / Super / XT / XTX / X3D / GRE suffixes. RTX 4070 != RTX 4070 Ti Super != RTX 4070. If current is RTX 4080 Super -> upgrade must be a STRICTLY STRONGER GPU with correct suffix (e.g., RTX 5090, not RTX 4090).
4. STRENGTH ORDER: You MUST recommend a component that is UNAMBIGUOUSLY STRONGER (higher tier / newer generation / more cores or CUDA) than user's current part. If current is ENTRY/MID tier -> target tier MIDRANGE should be the next mid-gen sweet spot; target tier HIGH-END should be the current flagship class high-end part.
5. PRIMARY FOCUS RULE (searched/scanned/uploaded component = user's intent): The user input component IS the component they searched, scanned, or uploaded. Your upgrade recommendation for it MUST be strong enough so that when the rest of the PC build also gets upgraded to the SAME target tier, this component will NOT create a bottleneck exceeding 20% with typical flagship tier peers.
   EXPLICIT EXAMPLE (WRONG vs CORRECT):
   [HIDDEN] WRONG (user screenshot scenario - never recommend if target tier = HIGH-END and user current = Ryzen 5 5600 CPU category upgrade slot exists): If user searched Ryzen 5 5600 (CPU), TARGET TIER = HIGH-END -- but you pick a high-end GPU upgrade elsewhere to RTX 4090/5090 and LEAVE the CPU recommendation KEPT / WEAK, then CPU bottleneck vs 4090 >50% -> FORBIDDEN.
   [SHOWN] CORRECT: If user current CPU = Ryzen 5 5600 and target tier = HIGH-END (meaning user wants a high-end capable CPU that won't bottleneck flagship GPUs like RTX 4090/5080/5090), pick a SAME-SOCKET compatible upgrade like Ryzen 7 5700X3D (AM4) -- NOT keep 5600. When the rest of the build pairs it with RTX 4090/5090, final CPU vs GPU bottleneck MUST be BELOW 20%.
6. BOTTLENECK COMPATIBILITY (<20% MAX WITH TARGET TIER PEERS): Think about what CPU a flagship HIGH-END GPU (RTX 4090/5080/5090/RX 7900 XTX/RX 9070 XT and above) actually needs to avoid bottleneck. If target tier = HIGH-END and input category = CPU and current is below Ryzen 7 7800X3D / i7-13700K class -> upgrade must be a CPU strong enough to pair with RTX 4090/5090 BELOW 20% bottleneck. Vice-versa: if target tier = HIGH-END and category = GPU, pick GPU that pairs with a typical high-end CPU (Ryzen 9 / i9 class) without exceeding 20% GPU-bottlenecking-CPU.
7. TOP-OF-LINE RULE: If user component is already the absolute flagship (e.g., RTX 5090, Ryzen 9 9950X) AND target tier = HIGH-END AND no stronger real same-category part exists -> output upgrade_component_name EXACTLY EQUAL to the user's current component name (do not invent non-existent parts), upgrade_title = "Your component is already a high-end component", upgrade_description = "No stronger recommended... already top tier."
8. ABSOLUTELY NO HALLUCINATED INVENTED MODELS: Only recommend components that genuinely exist in 2024-2026 retail DIY PC market.

Return ONLY a single strict JSON object following this exact schema with no prose, no markdown, no fences, no code block:
{ "upgrade_component_name": string, "upgrade_component_category": string ("cpu"/"gpu"/"ram"/"storage"/"psu"/"motherboard"), "upgrade_title": string, "upgrade_description": string, "compatibility_score": integer 0-100, "upgrade_description_short": string optional }
""".trimIndent()

    private suspend fun tryGptStep1ValidateOrNull(
        specSheetText: String,
        fallbackBuild: CurrentBuild
    ): SpecSheetStep1Result? = withContext(Dispatchers.IO) {
        runCatching {
            if (!OpenAiApiService.hasApiKey()) return@runCatching null
            val sys = """
$PCSCAN_MASTER_ENGINE_SPEC

You are a PC spec sheet VALIDATION engine (Step 1 of 3 pipeline). Job: 1) Parse the user-provided build spec sheet free-text input AND/or the 6 individual fallback part fields into specific real PC component models. 2) Verify EVERY listed part is an ACTUAL REAL SPECIFIC PC COMPONENT MODEL (not a brand name, not a category, not a generic). 3) Check cross-compatibility: CPU socket must match motherboard socket, RAM generation (DDR4/DDR5) must match motherboard, PSU wattage must be at least 1.3x the sum of CPU TDP + GPU TDP (flag if dangerously underwatt). 4) If any part is unrecognizable/generic/placeholder/not a real model -> is_valid_pc_build = false, add issue. If parts are real but incompatible -> is_compatible = false, add issue.

Return ONLY JSON object with schema:
{
  "is_valid_pc_build": boolean,
  "is_compatible": boolean,
  "issues": string[],
  "parsed_cpu": string,
  "parsed_gpu": string,
  "parsed_motherboard": string,
  "parsed_ram": string,
  "parsed_storage": string,
  "parsed_psu": string
}
If a category has no part -> set empty string. Never invent non-existent models. Output ONLY JSON, no fences, no prose, no markdown.
""".trimIndent()
            val user = """
=== INDIVIDUAL FIELD INPUTS (6 categories) ===
CPU field: [${fallbackBuild.cpu}]
GPU field: [${fallbackBuild.gpu}]
Motherboard field: [${fallbackBuild.motherboard}]
RAM field: [${fallbackBuild.ram}]
Storage field: [${fallbackBuild.storage}]
PSU field: [${fallbackBuild.psu}]
=== FREE-TEXT SPEC SHEET PASTE (priority above individual fields if conflict) ===
${specSheetText.ifBlank { "<user did not paste spec sheet; use individual fields above only>" }}
=== END INPUTS ===
Output required JSON only.
""".trimIndent()
            val request = OpenAiDto.ChatRequest(
                model = "gpt-5.6-luna",
                messages = listOf(
                    OpenAiDto.Message("system", sys),
                    OpenAiDto.Message("user", user)
                ),
                response_format = OpenAiDto.ResponseFormat("json_object")
            )
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) return@runCatching null
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: return@runCatching null
            val json = if (content.startsWith("```")) content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim() else content
            gson.fromJson(json, SpecSheetStep1Result::class.java)
        }.onFailure {
            Log.w("UpgradeAI-S1", "step1 ex ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private suspend fun tryGptStep2ClassifyTierOrNull(
        build: CurrentBuild,
        primaryComponent: String = ""
    ): SpecSheetStep2Result? = withContext(Dispatchers.IO) {
        runCatching {
            if (!OpenAiApiService.hasApiKey()) return@runCatching null
            val primaryHint = if (primaryComponent.isNotBlank()) """
PRIMARY COMPONENT CONTEXT (CRITICAL): The user specifically searched/scanned/uploaded this component: [${primaryComponent.trim()}]. Use it as an important anchor when understanding the build, but still classify the ENTIRE SYSTEM as a whole. Do NOT force the whole build into HIGH_END just because one searched part is high-end if the rest of the system is much weaker. Example: RTX 4080 Super + weak older CPU can still be a MIDRANGE system overall if CPU/RAM/PSU hold it back.
""".trimIndent() else ""
            val sys = """
$PCSCAN_MASTER_ENGINE_SPEC

You are a PC BUILD TIER CLASSIFICATION engine (Step 2 of 3 pipeline). Classify the ENTIRE USER PC BUILD into EXACTLY ONE of these 3 tiers based on its CPU + GPU + RAM tier as a COMBINED system performance tier:
- ENTRY = budget consumer gaming (CPU Ryzen 3/5 older gen / i3/i5 older below i5 10th, GPU GTX 1660 / RTX 2060 below or equal, less than ~$800 total build value, DDR4 3200 or below, 650W or below typical PSU, NO midrange parts present)
- MIDRANGE = solid 1440p gaming (CPU Ryzen 5 3600+ / 5600 / 7600 / Ryzen 7 older / i5 10400F+ 12-13-14th / i7 older, GPU RX 6600, RTX 3060-3070, RTX 4060-4070 class / RX 7000 XT class, ~$900-$1800 total build, balanced system)
- HIGH_END = enthusiast 4K gaming/production (CPU Ryzen 7 7800X3D/ Ryzen 9 any / i7/i9 13-14th / Ultra 7-9, GPU RTX 4080/4090/5080/5090 class / RX 7900 XTX/9000 XTX, flagship class parts combo, $1900+ build)

$primaryHint

Rules: 
1. Classify the SYSTEM as a whole, not individual components. A build with Ryzen 9 + RTX 2060 = MIDRANGE (not HIGH_END) because GPU bottlenecks. A build with RTX 4080 Super + weak older CPU can still be MIDRANGE overall if the GPU is being held back.
2. Use the searched/scanned/uploaded component as context, but do not let it override the real combined system tier.
3. Return ONLY JSON with keys build_tier (string enum: "ENTRY" / "MIDRANGE" / "HIGH_END" EXACT case and spelling) + reason (1 short sentence). NO other keys, NO prose, NO markdown.
{ "build_tier": string, "reason": string }
""".trimIndent()
            val user = """
CPU: ${build.cpu}
GPU: ${build.gpu}
Motherboard: ${build.motherboard}
RAM: ${build.ram}
Storage: ${build.storage}
PSU: ${build.psu}
Classify into ENTRY / MIDRANGE / HIGH_END. Return required JSON only.
""".trimIndent()
            val request = OpenAiDto.ChatRequest(
                model = "gpt-5.6-luna",
                messages = listOf(
                    OpenAiDto.Message("system", sys),
                    OpenAiDto.Message("user", user)
                ),
                response_format = OpenAiDto.ResponseFormat("json_object")
            )
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) return@runCatching null
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: return@runCatching null
            val json = if (content.startsWith("```")) content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim() else content
            gson.fromJson(json, SpecSheetStep2Result::class.java)
        }.onFailure {
            Log.w("UpgradeAI-S2", "step2 ex ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private suspend fun tryGptStep3GenerateUpgradesOrNull(
        parsedBuild: CurrentBuild,
        buildTier3: RecommendationMockData.UserCompTier3,
        primaryComponent: String = ""
    ): SpecSheetStep3Result? = withContext(Dispatchers.IO) {
        runCatching {
            if (!OpenAiApiService.hasApiKey()) return@runCatching null
            val tierInstruction = when (buildTier3) {
                RecommendationMockData.UserCompTier3.ENTRY -> """
USER CURRENT BUILD TIER = ENTRY. You MUST recommend EXACTLY ONE UPGRADE PATH: MIDRANGE only. Upgrade CPU/GPU/RAM/PSU bottleneck parts so the resulting build becomes a solid MIDRANGE balanced 1440p gaming system. Do NOT produce or fill a HIGH-END target. Focus ONLY on one (1) upgrade target: MIDRANGE. Provide ONLY mid_upgrade fields (title, description, components). Leave high fields blank/empty or skip (not used for ENTRY).
""".trimIndent()
                RecommendationMockData.UserCompTier3.MIDRANGE -> """
USER CURRENT BUILD TIER = MIDRANGE. Do NOT recommend midrange/entry tier upgrade targets (already mid). Recommend ONLY ONE upgrade path target: HIGH-END. Upgrade the bottleneck components that when swapped, transform this MIDRANGE build into a HIGH-END 4K flagship class build.

PLATFORM-AWARENESS RULE (MANDATORY FOR MID->HIGH): Prefer staying within the same CPU socket/platform whenever possible. Do NOT force a full motherboard + new RAM gen swap unless ABSOLUTELY required to reach HIGH-END. Keep compatible existing parts (motherboard, RAM, SSD) if not bottlenecking. Upgrade only the bottleneck parts.

EXAMPLE (reference this pattern):
Current Mid build: CPU Ryzen 5 5600 (AM4) / GPU RX 6600 / Mobo MSI B550M (AM4) / 16GB DDR4-3600 / 1TB NVMe / 650W Bronze
Correct HIGH upgrade output for this build: CPU -> Ryzen 7 5700X3D (STILL AM4 same socket), GPU -> RTX 5080, PSU -> 850W 80+ Gold, KEEP motherboard (B550), KEEP RAM (DDR4 16GB), KEEP SSD (still good). You don't need to swap mobo to X570 or switch socket to AM5 -- same socket compatible upgrade with X3D CPU is preferred.
Only list components you actually want to SWAP in the upgrade components array.
Provide ONLY high fields (title/description/components). Leave mid fields null.
""".trimIndent()
                RecommendationMockData.UserCompTier3.HIGH_END -> """
USER CURRENT BUILD TIER = HIGH-END. Do NOT recommend lower tier targets. First check if there are ANY MINOR ADJUSTMENTS / STRONGER POSSIBLE UPGRADES still available (e.g. RTX 4090 -> RTX 5090, Ryzen 9 7950X -> 9950X, DDR5 6000 CL36 -> 6400 CL30 if platform allows, 850W Gold -> 1000W Platinum, 2TB SSD -> 4TB/8TB NVMe gen5, etc.).
If you can find at least 1 real upgrade adjustment -> output those into high_upgrade_components (high title = "High-End Adjustments").
If you CANNOT find any reasonable stronger upgrade (absolute top-of-the-line already, all components are latest generation, no meaningful swap possible), SET: no_upgrade_available = true AND no_upgrade_message = "Your build is already using the latest components, no more upgrades can be recommended." (USER EXACT REQUIRED PHRASE - VERBATIM). Do not invent nonexistent hardware.
""".trimIndent()
            }
            val sys = """
$PCSCAN_MASTER_ENGINE_SPEC

--- TASK SPECIFIC INSTRUCTIONS (Step 3 of 3 pipeline) ---
You are a PC BUILD UPGRADE RECOMMENDATION engine. Based on the current user build tier you must recommend upgrade component swaps to hit the target upgrade tier specified in your instructions.

STRICT RULES -- NO EXCEPTIONS:
1. SPECIFIC REAL RETAIL MODEL ONLY for every upgrade component (Ryzen 7 5700X3D, RTX 5080, Corsair RM850e 850W 80+ Gold, etc.). NEVER brand/category/generic placeholders.
2. UPGRADE SWAPS ONLY: output ONLY parts you ACTUALLY WANT TO UPGRADE from the current user build. If user current part already matches target tier and not bottlenecking, DO NOT include it in the upgrade list (keep it implicitly, no mention needed). No same-tier unnecessary swaps.
3. PLATFORM AWARENESS / COMPATIBILITY MANDATORY: Stay within the same CPU socket/platform whenever feasible for the target tier. Do not recommend full platform swap (new mobo + new RAM gen) unless absolutely required. Ensure upgrades are 100% compatible with the KEPT existing parts (CPU socket matches kept/new mobo, RAM gen matches, PSU wattage adequate for new TDP).
4. VARIANT PRECISION: keep Ti / Super / XT / XTX / X3D / GRE / DDR4/DDR5 suffixes. 4070 != 4070 Ti Super. 5600 != 5700X3D.
5. BOTTLENECK RULE (HARD LIMIT -- LESS THAN 20% MAXIMUM, never higher):
   You MUST calculate the expected CPU vs GPU bottleneck after applying ALL your recommended upgrade swaps. FINAL bottleneck MUST BE LESS THAN 20% (below 20% only, so max 19% acceptable). If your first GPU upgrade choice creates >20% CPU bottleneck -> you MUST UPGRADE THE CPU as well (to a stronger compatible same-socket CPU). NEVER keep a weak CPU paired with a flagship GPU.
   EXPLICIT EXAMPLE OF WRONG/CORRECT:
   [HIDDEN] WRONG (user screenshot scenario - NEVER allow this): Current build = Ryzen 5 5600 (AM4) + RX 6600. Upgrade = GPU -> RTX 4090/5090, CPU = KEPT Ryzen 5 5600. BOTTLENECK = >50% (CPU holding GPU back massively). FORBIDDEN.
   [SHOWN] CORRECT: Same current build + RTX 5090 GPU upgrade -> YOU MUST ALSO INCLUDE CPU upgrade row: CPU -> Ryzen 7 5700X3D / Ryzen 9 5900X / Ryzen 9 5950X (AM4 compatible). Final bottleneck <18%.
   If upgrading the GPU to flagship level (RTX 4090/RTX 5090/RX 7900 XTX/RX 9070 XT or above), the CPU MUST ALSO be upgraded alongside it if the current CPU is not high-end tier.
6. UNNECESSARY MOBO SWAP PROHIBITED: If user current motherboard chipset (e.g., B550M for AM4) is fully compatible with the upgrade CPU socket, supports overclocking if needed, and is NOT a bottleneck -> DO NOT include a motherboard swap in the upgrade components list. KEEP B550M/B650 motherboards unless there is a genuine reason (new CPU socket requirement). For the AM4 Ryzen 5 5600 scenario above with new 5700X3D CPU: KEEP B550M motherboard, do not recommend X570 chipset upgrade unless user specifically asked for it.
7. SEARCHED/SCANNED/UPLOADED COMPONENT PRIORITY RULE: If the PRIMARY COMPONENT that user searched/scanned/uploaded appears in the current build (ex: user searched Ryzen 5 5600, current build CPU = Ryzen 5 5600), THIS COMPONENT IS THE FOCUS OF YOUR UPGRADE RECOMMENDATIONS. Make sure you either UPGRADE IT (stronger same-category part) OR if it's already strong enough for the target tier, explicitly KEEP IT and upgrade the surrounding bottleneck parts around it. It must never be a silent unchanged bystander in a recommendation where other expensive parts are upgraded.
8. GPU-ANCHOR RULE: If the searched primary component is a strong GPU already in the build (example: RTX 4080 Super) and the overall system tier is held back by CPU/RAM/PSU, KEEP that GPU and recommend the supporting upgrades needed to unlock it within the less-than-20% bottleneck rule. Do not force a weaker sidegrade GPU swap.

Return JSON ONLY (no fences, no prose) with the exact schema:
{
  "mid_upgrade_title": string|null (ENTRY build only e.g. "Midrange Upgrade Path"),
  "mid_upgrade_description": string|null,
  "mid_upgrade_components": UpgradeComponent[]|null (ENTRY build only = upgrade list to hit MIDRANGE target tier),
  "high_upgrade_title": string (MIDRANGE and HIGH_END builds set this; ENTRY builds you can leave ""/empty unused),
  "high_upgrade_description": string,
  "high_upgrade_components": UpgradeComponent[] (MIDRANGE build: HIGH-END target upgrade list; HIGH_END build: minor adjustments list; ENTRY builds leave empty),
  "no_upgrade_available": boolean (HIGH_END build only: true only if genuinely no upgrade possible),
  "no_upgrade_message": string (if no_upgrade_available true, MUST be exactly the required user message string verbatim)
}
Each UpgradeComponent in array is object EXACTLY:
{ "category": string ("cpu"/"gpu"/"motherboard"/"ram"/"storage"/"psu"/"case"/"cooler"), "name": string (REAL MODEL NAME), "description": string (1 line why upgrade), "compatibility": integer (85-100) }
category in UpgradeComponent must be lowercase 1-word (exactly the list).
""".trimIndent()
            val user = """
USER CURRENT BUILD:
CPU: ${parsedBuild.cpu.ifBlank { "<none>" }}
GPU: ${parsedBuild.gpu.ifBlank { "<none>" }}
Motherboard: ${parsedBuild.motherboard.ifBlank { "<none>" }}
RAM: ${parsedBuild.ram.ifBlank { "<none>" }}
Storage: ${parsedBuild.storage.ifBlank { "<none>" }}
PSU: ${parsedBuild.psu.ifBlank { "<none>" }}

USER SEARCHED/SCANNED/UPLOADED PRIMARY COMPONENT FOCUS: ${primaryComponent.ifBlank { "<none provided>" }}
(Apply Rule #7: if primaryComponent matches any current build part -> that component MUST be the focus of upgrade, never kept silently same if upgraded parts around it.)

=== TIER-SPECIFIC UPGRADE INSTRUCTIONS ===
$tierInstruction

Return required JSON only.
""".trimIndent()
            val request = OpenAiDto.ChatRequest(
                model = "gpt-5.6-luna",
                messages = listOf(
                    OpenAiDto.Message("system", sys),
                    OpenAiDto.Message("user", user)
                ),
                response_format = OpenAiDto.ResponseFormat("json_object")
            )
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) return@runCatching null
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: return@runCatching null
            val json = if (content.startsWith("```")) content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim() else content
            gson.fromJson(json, SpecSheetStep3Result::class.java)
        }.onFailure {
            Log.w("UpgradeAI-S3", "step3 ex ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    override suspend fun analyzeSpecSheetPipelineOrNull(
        specSheetText: String,
        fallbackBuild: CurrentBuild,
        onProgress: (Int, String, String) -> Unit,
        primaryComponent: String
    ): SpecSheetPipelineResult? = withContext(Dispatchers.IO) {
        if (!OpenAiApiService.hasApiKey() && specSheetText.isBlank()) return@withContext null

        onProgress(8, "Verifying...", "Analyzing spec sheet with AI...")
        val step1 = withTimeoutOrNull(12000L) {
            tryGptStep1ValidateOrNull(specSheetText, fallbackBuild)
        }
        val step1Safe = step1 ?: run {
            val local = RecommendationMockData.checkBuildCompatibility(fallbackBuild)
            SpecSheetStep1Result(
                is_valid_pc_build = fallbackBuild.isEmpty.not(),
                is_compatible = local.isCompatible,
                issues = local.issues,
                parsed_cpu = fallbackBuild.cpu,
                parsed_gpu = fallbackBuild.gpu,
                parsed_motherboard = fallbackBuild.motherboard,
                parsed_ram = fallbackBuild.ram,
                parsed_storage = fallbackBuild.storage,
                parsed_psu = fallbackBuild.psu
            )
        }
        onProgress(33, "Validating...", if (step1Safe.is_valid_pc_build && step1Safe.is_compatible) "Specs real and compatible [OK]" else "Checking real parts & compatibility...")
        val parsedBuild = CurrentBuild(
            cpu = step1Safe.parsed_cpu.ifBlank { fallbackBuild.cpu },
            gpu = step1Safe.parsed_gpu.ifBlank { fallbackBuild.gpu },
            motherboard = step1Safe.parsed_motherboard.ifBlank { fallbackBuild.motherboard },
            ram = step1Safe.parsed_ram.ifBlank { fallbackBuild.ram },
            storage = step1Safe.parsed_storage.ifBlank { fallbackBuild.storage },
            psu = step1Safe.parsed_psu.ifBlank { fallbackBuild.psu }
        )
        val compatResult = RecommendationMockData.BuildCompatibilityResult(
            isCompatible = step1Safe.is_valid_pc_build && step1Safe.is_compatible,
            issues = step1Safe.issues
        )

        onProgress(42, "Classifying...", "Determining build performance tier...")
        val step2 = withTimeoutOrNull(10000L) {
            tryGptStep2ClassifyTierOrNull(parsedBuild, primaryComponent)
        }
        val buildTierString = step2?.build_tier?.uppercase()?.trim()

        fun RecommendationMockData.ComponentTier?.toBucketOrNull(): Int? {
            return when (this) {
                RecommendationMockData.ComponentTier.LEGACY_BUDGET -> 0
                RecommendationMockData.ComponentTier.MIDRANGE -> 1
                RecommendationMockData.ComponentTier.HIGH_END_CURRENT,
                RecommendationMockData.ComponentTier.ENTHUSIAST_FLAGSHIP -> 2
                null -> null
            }
        }

        fun classifyBuildTierBalanced(build: CurrentBuild): RecommendationMockData.UserCompTier3 {
            val parts: List<Pair<String, String>> = listOfNotNull(
                build.cpu.takeIf { it.isNotBlank() }?.let { it to (RecommendationMockData.resolveCategory(it) ?: "") },
                build.gpu.takeIf { it.isNotBlank() }?.let { it to (RecommendationMockData.resolveCategory(it) ?: "") },
                build.motherboard.takeIf { it.isNotBlank() }?.let { it to (RecommendationMockData.resolveCategory(it) ?: "") },
                build.ram.takeIf { it.isNotBlank() }?.let { it to (RecommendationMockData.resolveCategory(it) ?: "") },
                build.storage.takeIf { it.isNotBlank() }?.let { it to (RecommendationMockData.resolveCategory(it) ?: "") },
                build.psu.takeIf { it.isNotBlank() }?.let { it to (RecommendationMockData.resolveCategory(it) ?: "") }
            )
            if (parts.isEmpty()) return RecommendationMockData.UserCompTier3.ENTRY
            val buckets: List<Int> = parts.mapNotNull { (name, cat) ->
                if (cat.isBlank()) null
                else RecommendationMockData.estimateComponentPerformanceTier(name, cat).toBucketOrNull()
            }
            val avg: Double = if (buckets.isEmpty()) 0.0 else buckets.sumOf { it.toDouble() } / buckets.size.toDouble()
            val rounded: Int = kotlin.math.round(avg).toInt().coerceIn(0, 2)
            return when (rounded) {
                0 -> RecommendationMockData.UserCompTier3.ENTRY
                1 -> RecommendationMockData.UserCompTier3.MIDRANGE
                else -> RecommendationMockData.UserCompTier3.HIGH_END
            }
        }

        var buildTier3 = when (buildTierString) {
            "ENTRY" -> RecommendationMockData.UserCompTier3.ENTRY
            "MIDRANGE", "MID" -> RecommendationMockData.UserCompTier3.MIDRANGE
            "HIGH_END", "HIGH-END", "HIGHEND", "HIGH" -> RecommendationMockData.UserCompTier3.HIGH_END
            else -> classifyBuildTierBalanced(parsedBuild)
        }
        onProgress(66, "Generating...", "Preparing tier-based upgrade targets...")

        val step3 = withTimeoutOrNull(18000L) {
            tryGptStep3GenerateUpgradesOrNull(parsedBuild, buildTier3, primaryComponent)
        }
        val defaultStep3 = when (buildTier3) {
            RecommendationMockData.UserCompTier3.ENTRY -> SpecSheetStep3Result(
                mid_upgrade_title = "Midrange Upgrade Path",
                mid_upgrade_description = "Balanced upgrades to convert your entry build into a solid 1440p midrange gaming system.",
                mid_upgrade_components = emptyList(),
                high_upgrade_title = "",
                high_upgrade_description = "",
                high_upgrade_components = emptyList()
            )
            RecommendationMockData.UserCompTier3.MIDRANGE -> SpecSheetStep3Result(
                mid_upgrade_title = null,
                mid_upgrade_description = null,
                mid_upgrade_components = null,
                high_upgrade_title = "High-End Upgrade Path",
                high_upgrade_description = "Upgrades to transform your midrange build into a 4K high-end flagship class system. Platform-aware: keep compatible parts (same socket mobo/RAM/SSD), upgrade bottleneck CPU/GPU/PSU only."
            )
            RecommendationMockData.UserCompTier3.HIGH_END -> SpecSheetStep3Result(
                mid_upgrade_title = null,
                mid_upgrade_description = null,
                mid_upgrade_components = null,
                high_upgrade_title = "High-End Adjustments",
                high_upgrade_description = "Optional minor adjustments and strongest possible upgrades for your already high-end build.",
                no_upgrade_available = false,
                no_upgrade_message = "Your build is already using the latest components, no more upgrades can be recommended."
            )
        }
        val step3Safe = step3 ?: defaultStep3

        fun List<UpgradeComponent>?.sanitize(): List<UpgradeComponent> {
            if (this == null) return emptyList()
            return this.mapNotNull { raw ->
                val cat = raw.category.ifBlank { "" }.lowercase().trim()
                val name = raw.name.trim()
                if (name.isBlank() || cat.isBlank()) return@mapNotNull null
                UpgradeComponent(
                    category = cat.uppercase(),
                    name = name,
                    description = raw.description.takeIf { it.isNotBlank() } ?: "Upgraded component for target build tier.",
                    compatibility = raw.compatibility.coerceIn(82, 100),
                    progressPercent = 1.0f,
                    scores = raw.scores
                )
            }
        }

        fun enforceBottleneckAndPlatformSanity(
            components: List<UpgradeComponent>,
            userBuild: CurrentBuild,
            primaryComp: String
        ): List<UpgradeComponent> {
            val list = components.toMutableList()
            fun hasNameCategory(n: String, cat: String): UpgradeComponent? =
                list.firstOrNull { it.category.equals(cat, ignoreCase = true) && it.name.contains(n, ignoreCase = true) }
            val hasGpuUpgrade = list.firstOrNull { it.category.equals("GPU", ignoreCase = true) }
            val gpuUpgradeName = hasGpuUpgrade?.name?.lowercase().orEmpty()
            val isFlagshipGpuUpgrade = (gpuUpgradeName.contains("rtx 4090") || gpuUpgradeName.contains("rtx 5090") ||
                gpuUpgradeName.contains("rtx 5080") || gpuUpgradeName.contains("rx 7900 xtx") ||
                gpuUpgradeName.contains("rx 9070 xt") || gpuUpgradeName.contains("rx 9060 xt"))
            if (isFlagshipGpuUpgrade) {
                val gpu = hasGpuUpgrade ?: return components
                val cpuUpgrade = list.firstOrNull { it.category.equals("CPU", ignoreCase = true) }
                val currentCpuLower = userBuild.cpu.lowercase()
                val primaryLower = primaryComp.lowercase()
                val cpuIsWeak = cpuUpgrade == null && (
                    currentCpuLower.contains("ryzen 3") || currentCpuLower.contains("ryzen 5 ") ||
                    currentCpuLower.contains("i3 ") || currentCpuLower.contains("i5 ")
                ) || (cpuUpgrade != null && (
                    cpuUpgrade.name.lowercase().contains("ryzen 3") ||
                    cpuUpgrade.name.lowercase().contains("ryzen 5 ") ||
                    cpuUpgrade.name.lowercase().contains("i3 ") ||
                    cpuUpgrade.name.lowercase().contains("i5 ")
                ))
                if (cpuIsWeak || (primaryLower.contains("ryzen 5 5600") && !currentCpuLower.contains("ryzen 7") && !currentCpuLower.contains("ryzen 9"))) {
                    if (cpuUpgrade != null) {
                        val idx = list.indexOf(cpuUpgrade)
                        list[idx] = UpgradeComponent(
                            category = "CPU",
                            name = "AMD Ryzen 7 5700X3D",
                            description = "Replaces current Ryzen 5 series CPU with same-AM4-socket X3D flagship gaming CPU to eliminate bottleneck with ${gpu.name}.",
                            compatibility = 95,
                            progressPercent = 1.0f,
                            scores = null
                        )
                    } else {
                        val moboLower = userBuild.motherboard.lowercase()
                        val cpuName = when {
                            moboLower.contains("am5") || moboLower.contains("b650") || moboLower.contains("x670") || currentCpuLower.contains("7600") || currentCpuLower.contains("7700") -> "AMD Ryzen 7 7800X3D"
                            moboLower.contains("lga1700") || moboLower.contains("b760") || moboLower.contains("z790") || currentCpuLower.contains("12400") || currentCpuLower.contains("13400") || currentCpuLower.contains("14400") -> "Intel Core i9-14900K"
                            else -> "AMD Ryzen 7 5700X3D"
                        }
                        val cpuDesc = when (cpuName) {
                            "AMD Ryzen 7 7800X3D" -> "Same-socket AM5 flagship X3D gaming CPU upgrade paired with ${gpu.name} to eliminate >20% CPU bottleneck."
                            "Intel Core i9-14900K" -> "LGA1700 flagship CPU to unlock full performance of ${gpu.name}, well under 20% final bottleneck."
                            else -> "Same-socket AM4 8-core X3D gaming CPU upgrade paired with ${gpu.name} to keep bottleneck below 20%."
                        }
                        list.add(
                            UpgradeComponent(
                                category = "CPU",
                                name = cpuName,
                                description = cpuDesc,
                                compatibility = 95,
                                progressPercent = 1.0f,
                                scores = null
                            )
                        )
                    }
                }
            }
            val moboCurrentLower = userBuild.motherboard.lowercase()
            val moboUpgrade = list.firstOrNull { it.category.equals("Motherboard", ignoreCase = true) }
            val sameSocketKeep = when {
                moboCurrentLower.contains("b550") && moboUpgrade != null -> {
                    val upgradeLower = moboUpgrade.name.lowercase()
                    (upgradeLower.contains("x570") || upgradeLower.contains("b550"))
                }
                moboCurrentLower.contains("b650") && moboUpgrade != null -> {
                    val upgradeLower = moboUpgrade.name.lowercase()
                    (upgradeLower.contains("x670") || upgradeLower.contains("b650") || upgradeLower.contains("x870"))
                }
                else -> false
            }
            if (sameSocketKeep && moboUpgrade != null) {
                val idx = list.indexOf(moboUpgrade)
                list.removeAt(idx)
            }
            return list
        }

        val midSanitized = step3Safe.mid_upgrade_components.sanitize().let { raw ->
            enforceBottleneckAndPlatformSanity(raw, parsedBuild, primaryComponent)
        }
        val highSanitized = step3Safe.high_upgrade_components.sanitize().let { raw ->
            enforceBottleneckAndPlatformSanity(raw, parsedBuild, primaryComponent)
        }

        val safeStep3 = SpecSheetStep3Result(
            mid_upgrade_title = step3Safe.mid_upgrade_title?.takeIf { it.isNotBlank() },
            mid_upgrade_description = step3Safe.mid_upgrade_description?.takeIf { it.isNotBlank() },
            mid_upgrade_components = if (midSanitized.isEmpty()) step3Safe.mid_upgrade_components else midSanitized,
            high_upgrade_title = step3Safe.high_upgrade_title.takeIf { it.isNotBlank() } ?: defaultStep3.high_upgrade_title,
            high_upgrade_description = step3Safe.high_upgrade_description.takeIf { it.isNotBlank() } ?: defaultStep3.high_upgrade_description,
            high_upgrade_components = if (highSanitized.isEmpty()) step3Safe.high_upgrade_components else highSanitized,
            no_upgrade_available = step3Safe.no_upgrade_available,
            no_upgrade_message = step3Safe.no_upgrade_message.takeIf { it.isNotBlank() }
                ?: "Your build is already using the latest components, no more upgrades can be recommended."
        )

        onProgress(92, "Finalizing...", "Applying compatibility guardrails...")
        SpecSheetPipelineResult(
            parsedBuild = parsedBuild,
            compatibility = compatResult,
            buildTier3 = buildTier3,
            step3 = safeStep3
        )
    }

    @JvmStatic
    val realAmdRx5thGen: Set<String> = setOf("rx 9060 xt", "rx 9060xt", "rx 9070 xt", "rx 9070xt")
    @JvmStatic
    val realAmdRx3rdGen: Set<String> = setOf(
        "rx 7900 xtx", "rx 7900 xt", "rx 7900 gre", "rx 7900gre",
        "rx 7800 xt", "rx 7700 xt", "rx 7600", "rx 7600 xt", "rx 7500 f", "rx 7500f",
        "rx 7800xt", "rx 7700xt", "rx 7600xt"
    )
    @JvmStatic
    val realNvidiaRtx40: Set<String> = setOf(
        "rtx 4090", "rtx 4080 super", "rtx 4080super", "rtx 4080",
        "rtx 4070 ti super", "rtx 4070tisuper", "rtx 4070 ti", "rtx 4070ti",
        "rtx 4070 super", "rtx 4070super", "rtx 4070",
        "rtx 4060 ti", "rtx 4060ti", "rtx 4060", "rtx 4050"
    )
    @JvmStatic
    val realNvidiaRtx50: Set<String> = setOf(
        "rtx 5090", "rtx 5080", "rtx 5070 ti", "rtx 5070ti",
        "rtx 5070", "rtx 5060 ti", "rtx 5060ti", "rtx 5060", "rtx 5050"
    )
    @JvmStatic
    val realGpuMaster: Set<String> = realAmdRx5thGen.union(realAmdRx3rdGen).union(realNvidiaRtx40).union(realNvidiaRtx50)

    @JvmStatic
    fun normalizeGpuForMatch(raw: String): String {
        val n = raw.trim().lowercase()
            .replace("radeon", "")
            .replace("geforce", "")
            .replace("nvidia", "")
            .replace("amd", "")
            .replace(Regex("""[^a-z0-9 ]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val clean = n
            .replace("xtx", " xtx ")
            .replace(" gre", " gre ")
            .replace(" ti super", " ti super ")
            .replace(" super", " super ")
            .replace(" ti", " ti ")
            .replace(" xt", " xt ")
            .replace(" f ", " f ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val alts = mutableListOf(clean)
        val noSpace = clean.replace(" ", "")
        alts.add(noSpace)
        alts.add(clean.replace("ti super", "tisuper"))
        alts.add(clean.replace("ti super", "ti super"))
        return clean
    }

    @JvmStatic
    fun isRealGpu(gpuName: String): Boolean {
        val n = gpuName.trim().lowercase()
        if (n.isBlank()) return true
        if (n.contains("gtx")) return true
        if (n.contains("arc")) return true
        if (n.contains("rx 5")) return true
        if (n.contains("rx 6")) return true
        if (n.contains("rx vega")) return true
        var matchAny = false
        for (canonical in realGpuMaster) {
            val c = canonical.trim()
            if (n.contains(c) || n.replace(" ", "").contains(c.replace(" ", ""))) {
                matchAny = true
                break
            }
        }
        return matchAny
    }

    private suspend fun tryGptUpgradePathOrNull(
        component: String,
        categoryHint: String,
        tier: BuildTier,
        hasExistingBuild: Boolean,
        fallbackTitle: String,
        fallbackDescription: String,
        fallbackComponentName: String = ""
    ): UpgradePath? = withContext(Dispatchers.IO) {
        runCatching {
            if (!OpenAiApiService.hasApiKey()) return@runCatching null
            val tierLabel = when (tier) {
                BuildTier.ENTRY -> "ENTRY-level"
                BuildTier.MID -> "MIDRANGE"
                BuildTier.HIGH -> "HIGH-END"
            }
            val userPrompt = """
Current user component: [${component.trim()}]
Component category hint: [${categoryHint.trim()}]
Target upgrade tier this recommendation is for: [${tierLabel}]
User has existing build to upgrade: [${hasExistingBuild}]
Default fallback tier title (only for JSON schema guidance, do not copy if inappropriate): [${fallbackTitle}]
Default fallback tier description (only for guidance): [${fallbackDescription}]

Recommend EXACTLY ONE real upgrade component of THE SAME CATEGORY following your strict rules. Output ONLY the requested JSON object. No other text.
""".trimIndent()
            val request = OpenAiDto.ChatRequest(
                model = "gpt-5.6-luna",
                messages = listOf(
                    OpenAiDto.Message("system", UPGRADE_AI_SYSTEM_PROMPT),
                    OpenAiDto.Message("user", userPrompt)
                ),
                response_format = OpenAiDto.ResponseFormat("json_object")
            )

            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) {
                val err = response.errorBody()?.string().orEmpty().take(300)
                Log.w("UpgradeAI", "AI upgrade FAIL HTTP ${response.code()}: $err")
                return@runCatching null
            }
            val body = response.body()
            val rawContent = body?.choices?.firstOrNull()?.message?.content?.trim()
            if (rawContent.isNullOrBlank()) {
                Log.w("UpgradeAI", "AI upgrade FAIL empty content")
                return@runCatching null
            }
            val jsonContent = if (rawContent.startsWith("```")) {
                rawContent
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
            } else rawContent

            val parsed = runCatching {
                gson.fromJson(jsonContent, GptUpgradeJson::class.java)
            }.getOrNull() ?: run {
                var name: String? = null
                var cat: String? = null
                var title: String? = null
                var desc: String? = null
                var score = 92
                var shortDesc: String? = null
                runCatching {
                    JsonReader(java.io.StringReader(jsonContent)).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "upgrade_component_name" -> name = reader.nextString()
                                "upgrade_component_category" -> cat = reader.nextString()
                                "upgrade_title" -> title = reader.nextString()
                                "upgrade_description" -> desc = reader.nextString()
                                "compatibility_score" -> score = runCatching { reader.nextInt().coerceIn(70, 100) }.getOrDefault(92)
                                "upgrade_description_short" -> shortDesc = reader.nextString()
                                else -> runCatching { reader.skipValue() }
                            }
                        }
                        reader.endObject()
                    }
                }
                if (name.isNullOrBlank()) return@runCatching null
                GptUpgradeJson(name!!, cat ?: categoryHint, title ?: fallbackTitle, desc ?: fallbackDescription, score, shortDesc)
            }

            val categoryForCard = if (parsed.upgrade_component_category.isNotBlank()) parsed.upgrade_component_category.uppercase() else categoryHint.uppercase()
            var parsedName = parsed.upgrade_component_name.trim()
            if (categoryForCard == "GPU" && parsedName.isNotBlank() && !isRealGpu(parsedName)) {
                Log.w(
                    "UpgradeAI",
                    "AI GPU HALLUCINATION DETECTED: '$parsedName' is not in real GPU catalog (only RX 9060 XT / RX 9070 XT exist for RX 9000 lineup). Replacing with fallback GPU: '$fallbackComponentName'"
                )
                val safeFallback = fallbackComponentName.takeIf { it.isNotBlank() } ?: run {
                    val tierTag = tier
                    when (tierTag) {
                        BuildTier.HIGH -> "AMD Radeon RX 9070 XT"
                        BuildTier.MID -> "AMD Radeon RX 9060 XT"
                        else -> "AMD Radeon RX 7600"
                    }
                }
                parsedName = safeFallback
            }

            val comps = listOf(
                UpgradeComponent(
                    category = categoryForCard,
                    name = parsedName,
                    description = parsed.upgrade_description_short?.takeIf { it.isNotBlank() }
                        ?: parsed.upgrade_description.takeIf { it.isNotBlank() }
                        ?: "${if (tier == BuildTier.HIGH) "High-end" else if (tier == BuildTier.MID) "Midrange" else "Entry-level"} upgrade replacement for your current ${component.trim()}.",
                    compatibility = parsed.compatibility_score.coerceIn(72, 100),
                    progressPercent = 1.0f
                )
            )
            UpgradePath(
                title = parsed.upgrade_title.takeIf { it.isNotBlank() } ?: fallbackTitle,
                description = parsed.upgrade_description.takeIf { it.isNotBlank() } ?: fallbackDescription,
                tier = when (tier) {
                    BuildTier.ENTRY -> "ENTRY"
                    BuildTier.MID -> "MIDRANGE"
                    BuildTier.HIGH -> "HIGH-END"
                },
                components = comps
            )
        }.onFailure { t ->
            Log.w("UpgradeAI", "AI upgrade EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
        }.getOrNull()
    }

    override suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean): UpgradePath {
        return getUpgradePath(component, hasExistingBuild, BuildTier.ENTRY)
    }

    override suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean, tier: BuildTier): UpgradePath {
        val category = RecommendationMockData.resolveCategory(component)
        val fallback = RecommendationMockData.getUpgradePath(component, hasExistingBuild, tier)
        val fallbackCompName = fallback.components.firstOrNull()?.name.orEmpty()
        return withTimeoutOrNull(4500L) {
            tryGptUpgradePathOrNull(
                component = component,
                categoryHint = category,
                tier = tier,
                hasExistingBuild = hasExistingBuild,
                fallbackTitle = fallback.title,
                fallbackDescription = fallback.description,
                fallbackComponentName = fallbackCompName
            )
        } ?: fallback
    }

    override suspend fun getUpgradePath(
        searchedComponent: String,
        currentBuild: CurrentBuild,
        tier: BuildTier
    ): UpgradePath {
        val category = RecommendationMockData.resolveCategory(searchedComponent)
        val fallback = RecommendationMockData.getUpgradePath(searchedComponent, currentBuild, tier)
        val fallbackCompName = fallback.components.firstOrNull()?.name.orEmpty()
        return withTimeoutOrNull(4500L) {
            tryGptUpgradePathOrNull(
                component = searchedComponent,
                categoryHint = category,
                tier = tier,
                hasExistingBuild = true,
                fallbackTitle = fallback.title,
                fallbackDescription = fallback.description,
                fallbackComponentName = fallbackCompName
            )
        } ?: fallback
    }

    override suspend fun getComponentDetails(componentName: String): ComponentDetail {
        return RecommendationMockData.getComponentDetails(componentName)
    }

    override suspend fun getDetectedComponentTier(component: String): String {
        val category = RecommendationMockData.resolveCategory(component)
        return detectTierMock(component, category)
    }

    private fun detectTierMock(component: String, category: String): String {
        val lower = component.lowercase()
        val highEndCpu = listOf(
            "ryzen 9", "ryzen 7 7800x3d", "ryzen 7 9700x", "ryzen 7 9800x3d",
            "i9-", "core i9", "ultra 9", "core ultra 9",
            "i7-14700k", "i7-13700k", "i7-12700k"
        )
        val midrangeCpu = listOf(
            "ryzen 5 9600x", "ryzen 7 5700x", "ryzen 7 7700", "ryzen 7 8700f",
            "i5-13600k", "i5-14600k", "i5-12600k",
            "i7-13700", "i7-14700", "i7-12700"
        )
        val entryCpu = listOf(
            "ryzen 3", "ryzen 5 5500", "ryzen 5 5600", "ryzen 5 5600g",
            "i3-", "core i3",
            "i5-12400f", "i5-13400f", "i5-12400", "i5-13400",
            "ryzen 5 7500f", "ryzen 5 7600",
            "i5-14400", "i5-14500",
            "pentium", "celeron"
        )
        val highEndGpu = listOf(
            "rtx 4080", "rtx 4090", "rtx 5080", "rtx 5090",
            "rx 7900 xtx", "rx 7900gre", "rx 7900 gre",
            "rx 9070 xt", "rx 9070xt"
        )
        val midrangeGpu = listOf(
            "rtx 4060 ti", "rtx 4070 ti super", "rtx 4070 ti", "rtx 4070 super", "rtx 4070",
            "rx 7700 xt", "rx 7800 xt", "rx 7700xt", "rx 7800xt",
            "rtx 5060", "rtx 5060 ti", "rtx 5070", "rtx 5070 ti",
            "rx 9060 xt", "rx 9060xt",
            "arc a750", "arc a770", "arc b570", "arc b580"
        )
        val entryGpu = listOf(
            "gtx 1650", "gtx 1660", "gtx 1660 super", "gtx 1660ti", "gtx 1660 ti",
            "rtx 3050", "rtx 3060", "rtx 4060",
            "rx 6500", "rx 6600", "rx 6500xt", "rx 6600xt", "rx 6500 xt", "rx 6600 xt",
            "rtx 5050",
            "arc a380", "arc a580"
        )
        if (highEndCpu.any { it in lower } || highEndGpu.any { it in lower }) {
            return "high"
        }
        if (midrangeCpu.any { it in lower } || midrangeGpu.any { it in lower }) {
            return "mid"
        }
        if (entryCpu.any { it in lower } || entryGpu.any { it in lower }) {
            return "entry"
        }
        return "mid"
    }

    override suspend fun analyzeImageForComponents(imageBytes: ByteArray): List<String> {
        if (imageBytes.isEmpty()) return emptyList()
        if (!OpenAiApiService.hasApiKey()) return emptyList()
        val rawNames = AiBuildOrchestrator.ocrImageAndExtractComponents(imageBytes)
        if (rawNames.isEmpty()) return emptyList()
        val preValidateOut = mutableListOf<Pair<String, String>>()
        val seen = hashSetOf<String>()
        for (raw in rawNames) {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            val validation = AiBuildOrchestrator.validateComponentWithGPT(trimmed) ?: continue
            if (!validation.is_pc_component) continue
            val compType = validation.component_type?.trim()?.uppercase() ?: continue
            if (compType !in setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")) continue
            val normalizedName = validation.normalized_name?.takeIf { it.isNotBlank() } ?: trimmed
            val key = normalizedName.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
            if (key in seen) continue
            seen.add(key)
            preValidateOut.add(normalizedName to compType)
        }
        val groundedOut = mutableListOf<String>()
        val groundedSeen = hashSetOf<String>()
        for ((candidate, _) in preValidateOut) {
            val dbMatched = runCatching { groundComponentNameToDatabase(candidate) }.getOrNull()
            if (dbMatched != null) {
                val keyDb = dbMatched.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
                if (keyDb !in groundedSeen) {
                    groundedSeen.add(keyDb)
                    groundedOut.add(dbMatched)
                }
            }
        }
        if (groundedOut.isNotEmpty()) return groundedOut
        for ((candidate, compType) in preValidateOut) {
            val clean = candidate.filter(Char::isDigit)
            val hasNumericModel = clean.isNotBlank() && clean.length >= 3
            val hasCategoryKeyword = when (compType) {
                "GPU" -> candidate.lowercase().let { it.contains("rtx") || it.contains("gtx") || it.contains("rx ") || it.contains("radeon") || it.contains("arc") || it.contains("nvidia") || it.contains("geforce") }
                "CPU" -> candidate.lowercase().let { it.contains("ryzen") || it.contains("core i") || it.contains("core(tm)") || it.contains("intel") || it.contains("amd") }
                "RAM" -> candidate.lowercase().let { it.contains("ddr") || it.contains("mhz") || it.contains("gb ") || it.contains("kit") }
                "MOTHERBOARD" -> candidate.lowercase().let { it.contains("b550") || it.contains("x570") || it.contains("b650") || it.contains("x670") || it.contains("z790") || it.contains("b760") || it.contains("motherboard") }
                "STORAGE" -> candidate.lowercase().let { it.contains("ssd") || it.contains("nvme") || it.contains("m.2") || it.contains("hdd") || it.contains("tb") || it.contains("gb") }
                "PSU" -> candidate.lowercase().let { it.contains("w ") || it.contains("watts") || it.contains("80+") || it.contains("80plus") || it.contains("gold") || it.contains("bronze") || it.contains("psu") || it.contains("power") }
                else -> false
            }
            if (hasNumericModel || hasCategoryKeyword) {
                val keyLenient = candidate.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
                if (keyLenient !in groundedSeen) {
                    groundedSeen.add(keyLenient)
                    groundedOut.add(candidate)
                }
            }
        }
        return groundedOut
    }

    override suspend fun analyzeImageForComponentsVisionOnly(imageBytes: ByteArray): List<String> {
        if (imageBytes.isEmpty()) return emptyList()
        if (!GeminiApiService.hasApiKey()) return emptyList()
        return runCatching {
            AiBuildOrchestrator.runGeminiVisionExtractOnly(imageBytes)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase().replace("""[\s\-_/()]+""".toRegex(), "") }
                .take(6)
        }.getOrElse { emptyList() }
    }

    private suspend fun groundComponentNameToDatabase(candidate: String): String? {
        val candidateClean = candidate.trim().takeIf { it.isNotBlank() } ?: return null
        val candidateTokens = extractMeaningfulTokens(candidateClean.lowercase())
        if (candidateTokens.isEmpty()) return null
        val rawSearch = runCatching { getSearchResults(candidateClean) }.getOrDefault(emptyList())
        val filteredSearch = rawSearch.filter { s -> !s.startsWith("Sample Component") && s.isNotBlank() }
        if (filteredSearch.isEmpty()) return null
        val scored = filteredSearch.map { dbName ->
            val dbTokens = extractMeaningfulTokens(dbName.lowercase())
            val hit = candidateTokens.count { tok ->
                dbTokens.any { dbt -> dbt == tok || dbt.contains(tok) || tok.contains(dbt) }
            }
            val rawDigitMatch = candidateClean.filter(Char::isDigit).takeIf { it.isNotBlank() }?.let { d ->
                if (dbName.filter(Char::isDigit).contains(d)) 3 else 0
            } ?: 0
            Triple(dbName, hit + rawDigitMatch, candidateTokens.size)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.first })
        val best = scored.firstOrNull() ?: return null
        val minHits = (candidateTokens.size.coerceAtLeast(1) * 0.40).toInt().coerceAtLeast(2)
        val strictOk = best.second >= minHits
        val scoreRatio = candidateTokens.size.coerceAtLeast(1).let { tot ->
            best.second.toDouble() / tot.toDouble()
        }
        val bonusPart = best.second - (best.second - 3).coerceAtLeast(0)
        val flexibleOk = best.second >= 1 && (scoreRatio >= 0.33 || best.second >= 4)
        return best.first.takeIf { strictOk || flexibleOk }
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
}

class FirestoreRecommendationRepository(
    private val firestore: FirebaseFirestore,
    private val fallback: RecommendationRepository = MockRecommendationRepository
) : RecommendationRepository {

    private val seedMutex = Mutex()
    @Volatile
    private var hasCheckedSeedData = false

    override suspend fun validateComponentWithGpt(input: String): OpenAiDto.JsonComponentValidation? {
        return AiBuildOrchestrator.validateComponentWithGPT(input)
    }

    override suspend fun getSearchResults(query: String): List<String> {
        ensureSeedData()
        return runCatching {
            val category = RecommendationMockData.resolveCategory(query)
            val snapshot = firestore.collection(SEARCH_RESULTS_COLLECTION)
                .document(category)
                .get()
                .await()

            snapshot.getStringList(SEARCH_RESULTS_FIELD)
                ?.takeIf { it.isNotEmpty() }
                ?: fallback.getSearchResults(query)
        }.getOrElse {
            fallback.getSearchResults(query)
        }
    }

    override suspend fun getExactSearchResult(query: String): ExactSearchResult {
        ensureSeedData()
        val tag = "ExactSearch"
        val t0 = System.currentTimeMillis()

        // Local OFFLINE-PRECHECK (regex) for obvious invalid parts - even if backend is down.
        // Matches backend's LAYER B regex: reject laptop/mobile suffixes and non-pc keywords.
        val qTrim = query.trim().also { if (it.isBlank()) return ExactSearchResult.InvalidQuery(query, "Search query is empty.", "wrong_model", 0f, "unknown") }
        val qLow = qTrim.lowercase()
        val laptopKeywords = listOf("laptop","notebook","macbook","surface pro","thinkpad","spectre","envy x360")
        if (laptopKeywords.any { it in qLow }) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE PART DETECTED - '$qTrim' mentions a laptop device or product line. This app supports DESKTOP PC DIY parts only (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case for desktop PCs).", "laptop_part", 0f, "laptop")
        }
        // PERIPHERAL / NON-PC KEYWORD HARD REJECT (matches backend LAYER 0B list - catches Monitor/KB/Mouse/UPS/Webcam/Speaker even if AI mislabels as GPU/CPU)
        val peripheralKeywords = listOf(
            "monitor","display","screen","tv","television",
            "keyboard","mech kb",
            "mouse","mice","trackpad","trackball",
            "headset","headphone","earphone","earbuds","speaker","soundbar","subwoofer",
            "microphone","webcam","camera",
            "printer","scanner","projector",
            "uninterruptible power supply",
            "usb hub","docking station","dock station",
            "chair","table","desk","fan","aircon",
            "controller","gamepad","joystick","wheel","vr headset"
        )
        peripheralKeywords.firstOrNull { it in qLow }?.let { kw ->
            return ExactSearchResult.InvalidQuery(
                query,
                "NOT A DESKTOP PC COMPONENT - '$qTrim' looks like '$kw' (peripheral/accessory/non-PC-part). This app ONLY accepts DESKTOP PC DIY HARDWARE: CPU, GPU, Motherboard, RAM, Storage (SSD/HDD), PSU, Cooler, Case. Peripherals like Monitor/KB/Mouse/UPS/Headset are NOT supported by the PC compatibility engine.",
                "not_pc_part",
                0f,
                "unknown"
            )
        }
        // UPS/AVR special pattern (word boundaries - no false positives like 'cups')
        val upsAvrRe = Regex("(^|[\\s\\-_])(ups|avr)(\$|[\\s\\-_])", RegexOption.IGNORE_CASE)
        if (upsAvrRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(
                query,
                "NOT A DESKTOP PC COMPONENT - '$qTrim' (UPS/AVR) is a power accessory NOT supported by the PC DIY build engine. Only CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case (desktop PC parts) are accepted.",
                "not_pc_part",
                0f,
                "unknown"
            )
        }
        // Short-form peripherals (kb, mic) word-boundary safe
        val shortPeripheralRe = Regex("(^|[\\s\\-_])(kb|mic)(\$|[\\s\\-_])", RegexOption.IGNORE_CASE)
        if (shortPeripheralRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(
                query,
                "NOT A DESKTOP PC COMPONENT - '$qTrim' (kb/mic) is a peripheral/accessory. Only CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case (desktop PC parts) accepted.",
                "not_pc_part",
                0f,
                "unknown"
            )
        }
        // ===== ARCHITECTURE CHANGE: GPT VALIDATOR FIRST BEFORE backend/DB/CATEGORY WHITELIST =====
        //   Safety regex guards (laptop/peripheral/UPS/kb/mic) run BEFORE GPT validator.
        //   If GPT says is_pc_component=true & category in 8-set -> RETURN EARLY bypassing LOCAL GUARD 1/2/3 (CATEGORY WHITELIST rejection).
        //   Allows ANY valid current/FUTURE desktop PC component name through WITHOUT manual whitelist.
        //   Falls through to old backend/LocalSpec logic if GPT null (no key) or GPT says not-PC-component.
        tryGptValidateAndReturnValidMatchOrNull(query, qTrim, qLow, tag)?.let { return it }
        // Known laptop CPU suffix regex (matches backend pattern)
        val cpuMobileRe = Regex("(^|[\\s\\-_])(\\d{4,5}[hHuUyY]\\b|hs\\b|hx\\b|hq\\b|mq\\b|lm\\b|um\\b|g[1-7]\\b|[a-z0-9]m\\b|mobile(?!\\s*legend))", RegexOption.IGNORE_CASE)
        val gpuMobileRe = Regex("(^|[\\s\\-_])(mx\\d+\\b|\\d{3,4}m\\b|max[\\s\\-]?q\\b|(rtx|gtx|rx|radeon|geforce|arc)\\s.*\\blaptop\\b|laptop\\s.*\\bgpu\\b|\\bgt\\s?1\\b|(super|ti)\\s+mobile\\b|quadro\\s+\\w+\\s*m\\b)", RegexOption.IGNORE_CASE)
        if (cpuMobileRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE CPU DETECTED - '$qTrim' uses a mobile/laptop CPU suffix (H,HS,HX,U,Y,G1-G7,MQ,M etc). Only DESKTOP CPU models (suffix K/KF/F/X/XT/G/T or no suffix) are accepted in this app.", "laptop_part", 0f, "laptop")
        }
        if (gpuMobileRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE GPU DETECTED - '$qTrim' uses a mobile/laptop GPU suffix (M/MX/Max-Q/Laptop etc). Only DESKTOP GPU models (Ti/Super/XT or standard suffixes) are accepted in this app.", "laptop_part", 0f, "laptop")
        }

        Log.d(tag, "SEARCH: exact-search query='$query' -> LEVEL 1 PRIORITY: Backend /api/search + OpenAI identify + exact DB match...")

        runCatching {
            val response = PcScanApiService.searchExactWithFallback(
                SearchApiDto.SearchRequestDto(query = query)
            )
            val dt = System.currentTimeMillis() - t0
            Log.d(tag, "TIMING: exact-search HTTP ${dt}ms code=${response.code()}")

            val body = response.body()
            when {
                response.isSuccessful && body != null -> {
                    if (body.found && body.component != null) {
                        val canonical = body.component.canonicalName
                        val compCategory = body.component.category
                        val compBrand = body.component.brand
                        val aiConf = body.aiConfidence ?: 0f
                        val validCatUpper = setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")

                        // ===== CATEGORY ALIAS NORMALIZATION (F1d): Fix backend MOBO/STOR aliases before any whitelist guard =====
                        val normCompCat = run {
                            val rawCat = (compCategory ?: "").trim().uppercase()
                            when (rawCat) {
                                "CENTRAL PROCESSING UNIT", "PROCESSOR" -> "CPU"
                                "GRAPHICS CARD", "GRAPHICS PROCESSING UNIT", "VIDEO CARD", "VGA" -> "GPU"
                                "MEMORY", "RANDOM ACCESS MEMORY", "MEM", "DDR", "DDR4", "DDR5", "DDR3" -> "RAM"
                                "MAINBOARD", "MOTHERBOARD", "MOBO", "MB", "MOTHER BOARD", "MAIN BOARD" -> "MOTHERBOARD"
                                "STORAGE DEVICE", "SSD", "HDD", "NVME", "STORAGE", "STOR", "M.2", "NVME SSD" -> "STORAGE"
                                "POWER SUPPLY", "POWER SUPPLY UNIT", "POWER", "POWER SUPPY" -> "PSU"
                                "AIR COOLER", "WATER COOLER", "CPU COOLER", "COOL", "COOLER", "AIO COOLER", "LIQUID COOLER", "COOLERS" -> "COOLER"
                                "COMPUTER CASE", "PC CASE", "CHASSIS", "CASING" -> "CASE"
                                else -> when {
                                    rawCat.startsWith("MOTH") || rawCat.startsWith("MAIN") -> "MOTHERBOARD"
                                    rawCat.startsWith("STOR") || rawCat == "SSD" || rawCat == "HDD" || rawCat == "NVME" -> "STORAGE"
                                    rawCat.startsWith("COOL") -> "COOLER"
                                    rawCat.startsWith("MEM") || rawCat.startsWith("DDR") -> "RAM"
                                    rawCat.startsWith("POW") -> "PSU"
                                    rawCat.startsWith("CHAS") || rawCat.contains("CASE") -> "CASE"
                                    else -> compCategory ?: ""
                                }
                            }
                        }

                        // LOCAL GUARD 1 (after backend): Explicit is_valid_pc_part=false -> reject even if found=true
                        if (body.isValidPcPart == false) {
                            val rej = body.rejectionReason ?: "not_pc_part"
                            val msg = body.message?.takeIf { it.isNotBlank() } ?: "NOT A DESKTOP PC COMPONENT (backend rejected '$qTrim'). Only CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case (desktop PC parts) accepted."
                            val form = body.formFactor
                            Log.w(tag, "GUARD: exact-search LOCAL GUARD 1 (isValidPcPart=false): rej=$rej msg='$msg'")
                            return ExactSearchResult.InvalidQuery(query, msg, rej, aiConf, form)
                        }

                        // LOCAL GUARD 2 (after backend): Category WHITELIST 8-set (unknown category = reject even if AI mislabels) - USES NORMALIZED CATEGORY
                        if (normCompCat.uppercase() !in validCatUpper) {
                            Log.w(tag, "GUARD: exact-search LOCAL GUARD 2 CATEGORY WHITELIST: category='$compCategory' (normalized='$normCompCat') not in 8 allowed PC DIY categories")
                            return ExactSearchResult.InvalidQuery(
                                query,
                                "NOT A DESKTOP PC COMPONENT - '$canonical' (category $normCompCat) is NOT in the 8 supported desktop PC DIY categories (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case). Hint: peripherals like Monitor/KB/Mouse/UPS are not supported by the PC build compatibility engine.",
                                "not_pc_part",
                                aiConf,
                                formFactor = null
                            )
                        }

                        // LOCAL GUARD 3 (after backend): NAME peripheral keyword guard (category=GPU ok but name="Monitor" -> reject) - USES NORMALIZED CATEGORY
                        val compNameCheckStr = "$canonical $compBrand $normCompCat".lowercase()
                        val matchedKw = peripheralKeywords.firstOrNull { it in compNameCheckStr }
                        if (matchedKw != null || upsAvrRe.containsMatchIn(compNameCheckStr) || shortPeripheralRe.containsMatchIn(compNameCheckStr)) {
                            val m = matchedKw ?: "ups/avr/kb/mic"
                            Log.w(tag, "GUARD: exact-search LOCAL GUARD 3 NAME PERIPHERAL: component name '$canonical' matched keyword=[$m]")
                            return ExactSearchResult.InvalidQuery(
                                query,
                                "NOT A DESKTOP PC COMPONENT - '$canonical' looks like a peripheral/accessory ($m). Only DESKTOP PC DIY hardware (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case) is supported by the PC build compatibility engine.",
                                "not_pc_part",
                                aiConf,
                                formFactor = null
                            )
                        }

                        Log.i(tag, "OK1 exact-search FOUND + 3 LOCAL GUARDS PASSED: canonical='$canonical' cat='$normCompCat' (exact=${body.exactMatch} aiConf=$aiConf)")

                        val displayName = preserveUserBrandedNameIfProvided(query, canonical)
                        if (coreModelNumberMismatch(query, displayName)) {
                            Log.w(tag, "GUARD: exact-search MODEL IDENTITY MISMATCH: query='$qTrim' backend name='$displayName' names a DIFFERENT model -> not displaying, falling through to local fallback")
                        } else {
                            val details = runCatching { getComponentDetails(canonical) }
                                .getOrNull()
                                ?: fallback.getComponentDetails(canonical)

                            // F1d: Final category = always use normalized first, then fallback chain
                            val finalCategory = normCompCat.ifBlank { compCategory.ifBlank { details.category } }

                            return ExactSearchResult.Success(
                                component = details.copy(
                                    name = displayName,
                                    category = finalCategory,
                                    manufacturer = compBrand.ifBlank { details.manufacturer },
                                    aiConfidence = (aiConf * 100).toInt().coerceIn(0, 100).takeIf { it > 0 } ?: details.aiConfidence
                                ),
                                aiConfidence = aiConf,
                                componentId = body.component.id,
                                specifications = body.component.specifications
                            )
                        }
                    } else {
                        val msg = body.message?.takeIf { it.isNotBlank() } ?: "Exact component not found."
                        val rejectReason = body.rejectionReason
                        val isValid = body.isValidPcPart
                        // Invalidation = explicit: return INVALID QUERY (laptop/not pc/wrong model) vs regular not-found (valid desktop pc part but no exact in db)
                        if (isValid == false && rejectReason != null) {
                            // LAST RESORT PATTERN SOFT-PASS: if backend says not_pc_part BUT query matches GENERIC desktop PC hardware naming patterns, ACCEPT.
                            if (rejectReason.trim().equals("not_pc_part", true) || rejectReason.trim().equals("wrong_model", true)) {
                                val softPass = tryPatternBasedGenericPcComponentPassOrNull(query, qTrim, qLow, tag)
                                if (softPass != null) {
                                    Log.i(tag, "PATTERN_PASS OVERRODE backend ${rejectReason} for query='$query'")
                                    return softPass
                                }
                            }
                            val finalCategory = rejectReason
                            Log.w(tag, "FAIL: exact-search INVALID QUERY: query='$query' category=$finalCategory reason='$msg' (${dt}ms)")
                            return ExactSearchResult.InvalidQuery(
                                query = query,
                                message = msg,
                                rejectionCategory = finalCategory,
                                aiConfidence = body.aiConfidence ?: 0f,
                                formFactor = body.formFactor
                            )
                        }
                        Log.w(tag, "WARN: exact-search NOT FOUND in backend DB (but valid PC part): query='$query' -> FALLBACK to local SmartGuesser/LocalDB pipeline")
                    }
                }
                response.code() in 400..499 -> {
                    val err = response.errorBody()?.string().orEmpty()
                    Log.e(tag, "FAIL: exact-search client error HTTP ${response.code()}: $err")
                    return ExactSearchResult.ApiOrNetworkError(
                        reason = "Request error (${response.code()})",
                        httpCode = response.code(),
                        isNetworkError = false
                    )
                }
                else -> {
                    val err = response.errorBody()?.string().orEmpty()
                    Log.w(tag, "RETRY: exact-search server error HTTP ${response.code()}: $err -> fallback local")
                }
            }
        }.onFailure { e ->
            when (e) {
                is HttpException -> {
                    Log.e(tag, "EXCEPTION: exact-search HTTP exception (${e.code()}): ${e.message} -> fallback local")
                }
                is IOException, is ConnectException, is SocketTimeoutException -> {
                    Log.w(tag, "ERROR: exact-search NETWORK exception (${e.javaClass.simpleName}): ${e.message} -> fallback local")
                }
                else -> {
                    Log.e(tag, "ERROR: exact-search UNEXPECTED (${e.javaClass.simpleName}): ${e.message} -> fallback local")
                }
            }
        }

        Log.w(tag, "WARN: exact-search backend unreachable -> local SmartGuesser/DB exact-only fallback (no firstOrNull category list)")
        return fallback.getExactSearchResult(query)
    }

    // =============================================================================
    // NEW STEP 0 / STEP 3: CPU + GPU Performance Score Lookups + Bottleneck Calculation
    // =============================================================================

    /** STEP 0: CPU Performance Score 1-100.
     *  Explicit tier ranges:
     *  Entry CPUs = 30-50 | Midrange CPUs = 55-75 | High-End CPUs = 80-100
     *  Uses ordered keyword matching -- MORE SPECIFIC models FIRST to avoid Ryzen 5 "5600" vs "7600" overlap bugs.
     *  Default fallback if no match = 50 (Entry ceiling / Midrange floor). */
    private fun scoreCpuPerformance(rawCpu: String): Int {
        val s = rawCpu.lowercase()
        // HIGH-END (80-100): Ryzen 9 series, Ryzen 7 X3D/9000-series, Intel i9 / Core Ultra 9
        when {
            listOf("ryzen 9 9950x3d", "9950x3d").any { it in s } -> return 100
            listOf("ryzen 9 9900x3d", "9900x3d").any { it in s } -> return 98
            listOf("ryzen 9 7950x3d", "7950x3d", "ryzen 9 7950x").any { it in s } -> return 95
            listOf("ryzen 9 9950x", "9950x").any { it in s } -> return 97
            listOf("ryzen 9 9900x", "9900x").any { it in s } -> return 94
            listOf("ryzen 9 7900x3d", "7900x3d", "ryzen 9 7900x", "7900x").any { it in s } -> return 92
            listOf("ryzen 7 9800x3d", "9800x3d").any { it in s } -> return 96
            listOf("ryzen 7 7800x3d", "7800x3d").any { it in s } -> return 93
            listOf("ryzen 7 9700x", "9700x").any { it in s } -> return 88
            listOf("core ultra 9", "ultra 9").any { it in s } -> return 87
            listOf("i9-14900ks", "i9 14900ks").any { it in s } -> return 91
            listOf("i9-14900k", "i9 14900k").any { it in s } -> return 89
            listOf("i9-13900k", "i9 13900k").any { it in s } -> return 86
            listOf("i9-12900k", "i9 12900k").any { it in s } -> return 83
            s.contains("ryzen 9") -> return 90
            s.contains("i9-15") || s.contains("i9 15") -> return 90
            s.contains("i9-") || s.contains("core i9") -> return 85
        }
        // MIDRANGE (55-75): Ryzen 5 (7600 / 9600X), Ryzen 7 (5700X/7700/8700F non-X3D), Intel i5-K, i7
        when {
            listOf("ryzen 7 8700g", "8700g", "ryzen 7 8700f", "8700f").any { it in s } -> return 74
            listOf("ryzen 7 7700x", "7700x", "ryzen 7 7700", "7700 ").any { it in s } -> return 76
            listOf("ryzen 7 5700x3d", "5700x3d").any { it in s } -> return 75
            listOf("ryzen 7 5700x", "5700x", "ryzen 7 5700", "5700 ").any { it in s } -> return 68
            listOf("ryzen 7 5800", "5800x").any { it in s } && !s.contains("5800x3d") -> return 70
            listOf("ryzen 5 9600x", "9600x").any { it in s } -> return 73
            listOf("ryzen 5 8600g", "8600g", "ryzen 5 8600f", "8600f").any { it in s } -> return 67
            listOf("ryzen 5 7600x", "7600x", "ryzen 5 7600", "7600 ").any { it in s } -> return 65
            listOf("i7-14700k", "i7 14700k").any { it in s } -> return 78
            listOf("i7-13700k", "i7 13700k").any { it in s } -> return 75
            listOf("i7-12700k", "i7 12700k").any { it in s } -> return 72
            listOf("i7-14700", "14700f").any { it in s } -> return 70
            listOf("i7-13700", "13700f").any { it in s } -> return 67
            listOf("i5-14600k", "14600k", "i5-13600k", "13600k", "i5-12600k", "12600k").any { it in s } -> return 66
            s.contains("i7-") || s.contains("core i7") -> return 70
            s.contains("ryzen 7") -> return 72
        }
        // ENTRY (30-50): Ryzen 3, Ryzen 5 4500/5500/5600, Intel i3, i5-12400F / 13400F / 14400 (non-K)
        when {
            listOf("ryzen 5 5600x3d", "5600x3d").any { it in s } -> return 60
            listOf("ryzen 5 5600", "5600 ", "ryzen 5 5600g", "5600g").any { it in s } -> return 50
            listOf("ryzen 5 5500", "5500 ").any { it in s } -> return 43
            listOf("ryzen 5 4500", "4500 ").any { it in s } -> return 38
            listOf("i5-14400f", "i5-14400 ", "14400f", "14400 ").any { it in s } -> return 55
            listOf("i5-13400f", "i5-13400 ", "13400f", "13400 ").any { it in s } -> return 50
            listOf("i5-12400f", "i5-12400 ", "12400f", "12400 ").any { it in s } -> return 47
            listOf("i5-12500", "12500").any { it in s } -> return 49
            listOf("i5-13500", "13500").any { it in s } -> return 52
            listOf("i3-12100f", "12100f", "i3-12100", "12100 ").any { it in s } -> return 33
            listOf("i3-13100f", "13100f", "i3-13100", "13100 ").any { it in s } -> return 35
            listOf("i3-14100f", "14100f", "i3-14100", "14100 ").any { it in s } -> return 38
            s.contains("ryzen 5 3600") || s.contains("3600 ") -> return 46
            s.contains("ryzen 3") -> return 32
            s.contains("i3-") || s.contains("core i3") -> return 33
            s.contains("i5-") || s.contains("core i5") -> return 50
            s.contains("ryzen 5") -> return 50
        }
        return 50
    }

    /** STEP 3A: GPU Performance Score 1-100.
     *  Entry GPUs = 25-45 | Midrange GPUs = 50-70 | High-End GPUs = 75-100.
     *  MORE SPECIFIC models matched first. */
    private fun scoreGpuPerformance(rawGpu: String): Int {
        val s = rawGpu.lowercase()
        // HIGH-END NVIDIA (>=75): 4070 Ti+ / 5080+
        when {
            listOf("rtx 5090", "5090 ti", "5090ti").any { it in s } -> return 100
            s.contains("rtx 4090") -> return 95
            listOf("rtx 5080 ti", "5080ti").any { it in s } -> return 92
            s.contains("rtx 5080") -> return 88
            s.contains("rtx 4080 super") -> return 86
            s.contains("rtx 4080") -> return 84
            s.contains("rtx 4070 ti super") -> return 78
            s.contains("rtx 4070 ti") -> return 76
            s.contains("rtx 4070 super") -> return 72
        }
        // HIGH-END AMD (>=75): RX 7900 XTX / GRE / XT
        when {
            listOf("rx 7950 xtx", "7950 xtx").any { it in s } -> return 96
            s.contains("rx 7900 xtx") -> return 90
            s.contains("rx 7900 gre") -> return 83
            s.contains("rx 7900 xt") -> return 80
        }
        // MIDRANGE NVIDIA (50-70): 4060 Ti-4070 / 4070 non-Super covered above
        when {
            s.contains("rtx 4070") -> return 70
            s.contains("rtx 4060 ti 16gb") -> return 63
            s.contains("rtx 4060 ti 8gb") -> return 58
            s.contains("rtx 4060 ti") -> return 60
            s.contains("rtx 5070") -> return 70
            s.contains("rtx 5060") -> return 58
            s.contains("rtx 3070") -> return 57
            s.contains("rtx 3060 ti") -> return 52
        }
        // MIDRANGE AMD (50-70): RX 7700 XT / 7800 XT
        when {
            s.contains("rx 7800 xt") -> return 66
            s.contains("rx 7700 xt") -> return 58
            s.contains("rx 6800 xt") -> return 60
            s.contains("rx 6800") -> return 55
            s.contains("rx 6750 gre") -> return 54
            s.contains("rx 6750 xt") -> return 52
            s.contains("rx 6700 xt") -> return 50
        }
        // INTEL ARC (entry + midrange only): A380 (entry) / A580 / A750-A770 / B580 (midrange)
        when {
            s.contains("arc b580") -> return 62
            s.contains("arc a770") -> return 55
            s.contains("arc a750") -> return 52
            s.contains("arc a580") -> return 48
            s.contains("arc b570") -> return 44
            s.contains("arc a380") -> return 30
        }
        // ENTRY NVIDIA (25-45): 1650-1660S / 3050-4060 (non-Ti)
        when {
            s.contains("rtx 4060") -> return 47
            s.contains("rtx 3060") -> return 42
            s.contains("rtx 3050") -> return 33
            s.contains("gtx 1660 super") || s.contains("1660s") -> return 37
            s.contains("gtx 1660 ti") -> return 35
            s.contains("gtx 1660") -> return 32
            s.contains("gtx 1650 super") -> return 29
            s.contains("gtx 1650") -> return 25
        }
        // ENTRY AMD (25-45): RX 6500 XT-6600 XT / 6650 XT
        when {
            s.contains("rx 6650 xt") -> return 43
            s.contains("rx 6600 xt") -> return 41
            s.contains("rx 6600") -> return 38
            s.contains("rx 6500 xt") -> return 28
            s.contains("rx 6400") -> return 22
        }
        // Generic / unknown brand score fallbacks by tier keywords
        when {
            listOf("4090", "5090", "7900 xtx", "titan").any { it in s } -> return 90
            listOf("4080", "5080", "7900 xt").any { it in s } -> return 82
            listOf("4070", "5070", "7800 xt").any { it in s } -> return 70
            listOf("4060 ti", "5060 ti", "7700 xt").any { it in s } -> return 60
            listOf("4060", "5060", "6700 xt", "6800").any { it in s } -> return 48
            listOf("3060", "1660", "6600").any { it in s } -> return 38
            listOf("3050", "1650", "6500").any { it in s } -> return 28
            s.contains("nvidia") || s.contains("geforce") -> return 50
            s.contains("radeon") || s.contains("amd radeon") -> return 50
        }
        return 50
    }

    /** STEP 3C: Default resolution factor -- always 1080p (standard for tiered build). */
    private val DEFAULT_RESOLUTION = "1080p"

    /** STEP 3B-3E: Bottleneck calculation using user's exact formulas.
     *  Returns quadruple: (bottleneckPercentageRaw, rangeLabel, bottleneckPartLabel, detailedNote).
     *  @param res one of "1080p" / "1440p" / "4K" */
    private fun computeCpuGpuBottleneck(cpuScore: Int, gpuScore: Int, res: String = DEFAULT_RESOLUTION): BottleneckResult {
        val base = kotlin.math.abs(cpuScore - gpuScore).toDouble()
        val cpuLtGpu = cpuScore < gpuScore
        val lowerRes = res.lowercase().trim()
        val resolutionFactor: Double
        if (lowerRes == "4k" || lowerRes == "2160p") {
            resolutionFactor = if (cpuLtGpu) 0.8 else 1.2
        } else if (lowerRes == "1440p" || lowerRes == "2k" || lowerRes == "qhd") {
            resolutionFactor = 1.0
        } else {
            // 1080p default
            resolutionFactor = if (cpuLtGpu) 1.2 else 0.8
        }
        val adjustedPctDouble = base * resolutionFactor
        val adjustedPctInt = adjustedPctDouble.toInt().coerceIn(0, 100)
        val rangeDetailPair: Pair<String, String>
        if (adjustedPctInt < 5) {
            rangeDetailPair = Pair(
                "0-5% (No significant bottleneck)",
                "CPU and GPU are very well balanced for 1080p gaming -- neither part noticeably holds the other back."
            )
        } else if (adjustedPctInt in 5..10) {
            rangeDetailPair = Pair(
                "5-10% (Minor bottleneck)",
                "Slight imbalance, well within acceptable range for real-world gaming. Frame rate variance will be minimal."
            )
        } else if (adjustedPctInt in 11..20) {
            rangeDetailPair = Pair(
                "10-20% (Moderate bottleneck)",
                "Noticeable but not severe. Upgrading the weaker side would yield measurable FPS gains in CPU-heavy or GPU-heavy titles."
            )
        } else {
            val weaker = if (cpuLtGpu) "CPU" else "GPU"
            val rl = ">20% (Significant bottleneck -- consider upgrading $weaker)"
            val dn = "Large imbalance detected -- $weaker is severely limiting the other component's real-world performance. Upgrade $weaker first to unlock more frames."
            rangeDetailPair = Pair(rl, dn)
        }
        val rangeLabel = rangeDetailPair.first
        val detailNote = rangeDetailPair.second
        val partLabel: String
        if (cpuLtGpu) {
            partLabel = "CPU Bottleneck"
        } else if (gpuScore < cpuScore) {
            partLabel = "GPU Bottleneck"
        } else {
            partLabel = "Balanced / No significant bottleneck"
        }
        val outcome = BottleneckResult(
            percentInt = adjustedPctInt,
            displayPercent = "$adjustedPctInt%",
            rangeLabel = rangeLabel,
            partLabel = partLabel,
            detailedNote = detailNote,
            cpuScore = cpuScore,
            gpuScore = gpuScore
        )
        return outcome
    }

    private data class BottleneckResult(
        val percentInt: Int,
        val displayPercent: String,
        val rangeLabel: String,
        val partLabel: String,
        val detailedNote: String,
        val cpuScore: Int,
        val gpuScore: Int
    )

    private fun buildDeterministicBottleneckAnalysis(
        cpu: String,
        gpu: String,
        pctIn: Int,
        noteRaw: String,
        limitingRaw: String
    ): BottleneckAnalysis {
        val pct = pctIn.coerceIn(0, 20)
        val status = when {
            pct <= 10 -> "EXCELLENT"
            pct <= 15 -> "GOOD"
            else -> "ACCEPTABLE"
        }
        val cpuShort = cpu.ifBlank { "Unknown CPU" }
        val gpuShort = gpu.ifBlank { "Unknown GPU" }
        val limiting = when {
            limitingRaw.contains("cpu", ignoreCase = true) -> "CPU"
            limitingRaw.contains("gpu", ignoreCase = true) -> "GPU"
            else -> "Balanced"
        }
        val summary = "Estimated bottleneck: approximately ${pct}%. (Status: $status.) The $cpuShort and $gpuShort are reasonably balanced. Neither the CPU nor the GPU is expected to significantly restrict the other in typical gaming workloads."
        val prefix = if (noteRaw.isBlank()) "" else noteRaw.trim() + " "
        val detailed = buildString {
            append(prefix)
            append("This estimate reflects the typical performance difference between the two chips across a representative suite of modern titles. ")
            append("If the CPU has fewer cores, lower IPC, or smaller L3 cache than a flagship class, it can limit high-end GPU throughput at low resolutions. ")
            append("If the GPU has fewer compute units or lower VRAM capacity, it becomes the limiting factor as resolution and texture detail increase. ")
            append("Actual results vary depending on five factors: Game, Resolution, Graphics settings, FPS target, and Workload. ")
            append("Estimated bottleneck; actual result varies by workload.")
        }
        val g1080 = if (limiting == "CPU") {
            "At 1080p the Ryzen/i5-class CPU may become more noticeable at high FPS targets such as 144Hz or 240Hz. Turn-based / strategy titles are unlikely to show the difference, but competitive shooters often scale harder into CPU performance at this resolution."
        } else {
            "At 1080p this pairing is typically GPU-bound, though fast-paced competitive titles with uncapped frame rates can still stress the CPU enough to keep the GPU waiting. For 60 FPS casual gameplay the experience is generally smooth."
        }
        val g1440 = "At 1440p this pairing usually lands in the 1440p sweet spot where load is distributed between CPU and GPU. Bottleneck becomes rare in most AAA titles; CPU-limited scenarios occur mainly in simulation-heavy games with many units or heavy draw-call overhead."
        val g4k = "At 4K rendering the GPU is almost always the limiting component regardless of the CPU for most AAA titles. Unless running extremely high-FPS simulation or strategy workloads, the CPU will rarely be the bottleneck at 4K resolution."
        val reason = when (limiting) {
            "CPU" -> "The CPU is the limitation because its per-thread performance, core count, or cache size are not high enough to keep up with the GPU at low resolutions when the GPU is lightly loaded."
            "GPU" -> "The GPU is the limitation because its raw CUDA/shader count or onboard VRAM capacity cannot push the pixel throughput needed at the target resolution before the CPU runs out of work to hand it."
            else -> "Neither component substantially limits the other in this pairing; their relative performance tiers are well matched for balanced 1440p and typical 4K gaming workloads."
        }
        return BottleneckAnalysis(
            percentage = pct,
            status = status,
            limitingComponent = limiting,
            summary = summary,
            detailedAnalysis = detailed.trim(),
            gaming1080p = g1080,
            gaming1440p = g1440,
            gaming4k = g4k,
            reason = reason,
            withinLimit = pct <= 20
        )
    }

    private fun jsonBottleneckToDomainOrNull(
        json: com.pcscan.pcscan.data.api.OpenAiDto.JsonBottleneckAnalysis?,
        cpu: String,
        gpu: String,
        fallbackPct: Int,
        fallbackNote: String,
        fallbackLimitingRaw: String
    ): BottleneckAnalysis {
        if (json == null) {
            return buildDeterministicBottleneckAnalysis(cpu, gpu, fallbackPct, fallbackNote, fallbackLimitingRaw)
        }
        val pct = json.percentage ?: fallbackPct
        val pctSafe = pct.coerceIn(0, 20)
        val limiting = json.limiting_component.orEmpty().ifBlank { fallbackLimitingRaw }
        val summaryIn = json.summary.orEmpty()
        val summary = summaryIn.ifBlank {
            val status = when {
                pctSafe <= 10 -> "EXCELLENT"
                pctSafe <= 15 -> "GOOD"
                else -> "ACCEPTABLE"
            }
            "Estimated bottleneck: approximately ${pctSafe}%. (Status: $status.) The $cpu and $gpu are reasonably balanced."
        }
        return BottleneckAnalysis(
            percentage = pctSafe,
            status = json.status?.uppercase()?.takeIf { it.isNotBlank() } ?: when {
                pctSafe <= 10 -> "EXCELLENT"
                pctSafe <= 15 -> "GOOD"
                else -> "ACCEPTABLE"
            },
            limitingComponent = when {
                limiting.contains("cpu", ignoreCase = true) -> "CPU"
                limiting.contains("gpu", ignoreCase = true) -> "GPU"
                limiting.equals("balanced", ignoreCase = true) -> "Balanced"
                else -> "Balanced"
            },
            summary = summary,
            detailedAnalysis = (json.detailed_analysis ?: fallbackNote).ifBlank {
                "Estimated bottleneck; actual result varies by workload."
            },
            gaming1080p = json.gaming_1080p.orEmpty().ifBlank {
                "1080p behavior: higher FPS targets in competitive shooters can shift limitation toward the CPU."
            },
            gaming1440p = json.gaming_1440p.orEmpty().ifBlank {
                "1440p behavior: typically the balanced sweet spot between CPU and GPU workload."
            },
            gaming4k = json.gaming_4k.orEmpty().ifBlank {
                "4K behavior: overwhelmingly GPU-limited in most modern AAA titles."
            },
            reason = json.reason.orEmpty().ifBlank {
                "Relative performance between current CPU and GPU tiers determines the balance."
            },
            withinLimit = json.within_limit ?: (pctSafe <= 20)
        )
    }

    /** STEP 0 CPU ANALYSIS: Returns structured platform specs from the user-selected CPU string.
     *  (fullName, socket, requiredRam, maxPcie, cpuScore100, detectedTierLabel). */
    private fun analyzeCpuSpecs(rawCpu: String): CpuSpecAnalysis {
        val name = rawCpu.ifBlank { "Unknown CPU" }
        val lower = name.lowercase()
        val platformGuess = detectPlatform(rawCpu, "cpu")
        val platformTriple: Triple<String, String, String> = if (platformGuess != null) {
            val socket: String = when {
                platformGuess.name.startsWith("AMD AM4") -> "AM4"
                platformGuess.name.startsWith("AMD AM5") -> "AM5"
                platformGuess.name.contains("GEN 6-11") -> "LGA1151"
                (platformGuess.name.contains("ULTRA") && lower.contains("ultra 9 2")) -> "LGA1851"
                (platformGuess.name.contains("ULTRA") && lower.contains("ultra 7 1")) -> "LGA1851"
                (platformGuess.name.contains("GEN 12-14") || platformGuess.name.contains("1700")) -> "LGA1700"
                else -> "LGA1700"
            }
            val requiredRam: String = when {
                platformGuess.name.startsWith("AMD AM5") || platformGuess.name.contains("GEN 12-14") || platformGuess.name.contains("ULTRA") -> "DDR5 ONLY"
                else -> "DDR4 ONLY"
            }
            val maxPcie: String = when {
                platformGuess.name.startsWith("AMD AM5") -> "PCIe 4.0 / 5.0"
                platformGuess.name.startsWith("AMD AM4") -> "PCIe 3.0 / 4.0"
                platformGuess.name.contains("GEN 6-11") -> "PCIe 3.0 / 4.0"
                else -> "PCIe 4.0 / 5.0"
            }
            Triple(socket, requiredRam, maxPcie)
        } else if (listOf("ryzen 7 7", "ryzen 7 8", "ryzen 7 9", "ryzen 9 7", "ryzen 9 8", "ryzen 9 9", "am5", "x670", "b650").any { it in lower }) {
            Triple("AM5", "DDR5 ONLY", "PCIe 4.0 / 5.0")
        } else if (listOf("ryzen 5 5", "ryzen 7 5", "ryzen 9 5", "am4", "b550", "b450", "x570", "ryzen 5 3", "ryzen 3").any { it in lower }) {
            Triple("AM4", "DDR4 ONLY", "PCIe 3.0 / 4.0")
        } else if (listOf("i3-6", "i3-7", "i3-8", "i3-9", "i5-6", "i5-7", "i5-8", "i5-9", "i5-10", "i5-11", "i7-6", "i7-7", "i7-8", "i7-9", "lga1151").any { it in lower }) {
            Triple("LGA1151", "DDR4 ONLY", "PCIe 3.0 / 4.0")
        } else if (listOf("core ultra", "ultra 5 2", "ultra 7 2", "ultra 9 2", "lga1851").any { it in lower }) {
            Triple("LGA1851", "DDR5 ONLY", "PCIe 4.0 / 5.0")
        } else if (listOf("i3-12", "i3-13", "i3-14", "i5-12", "i5-13", "i5-14", "i7-12", "i7-13", "i7-14", "i9-12", "i9-13", "i9-14", "lga1700", "b760", "z790", "h770", "z690", "b660", "h610").any { it in lower }) {
            Triple("LGA1700", "DDR5 ONLY", "PCIe 4.0 / 5.0")
        } else {
            Triple("LGA1700", "DDR5 ONLY", "PCIe 4.0 / 5.0")
        }
        val socket = platformTriple.first
        val requiredRam = platformTriple.second
        val maxPcie = platformTriple.third
        val score = scoreCpuPerformance(name)
        val tier: String
        if (score >= 80) {
            tier = "high"
        } else if (score >= 55) {
            tier = "mid"
        } else {
            tier = "entry"
        }
        val result = CpuSpecAnalysis(name, socket, requiredRam, maxPcie, score, tier)
        return result
    }

    private data class CpuSpecAnalysis(
        val fullName: String,
        val socket: String,
        val requiredRam: String,
        val maxPcie: String,
        val cpuScore100: Int,
        val detectedTierShort: String
    )

    private fun lookupCpuTdpWatts(cpuForCalc: String): Int {
        val lower = cpuForCalc.lowercase()
        val ryzen5x3d = listOf("7800x3d", "9800x3d", "7950x3d", "7900x3d", "9900x3d", "9950x3d")
        if (ryzen5x3d.any { it in lower }) return 120
        if (listOf("ryzen 9 9950x", "ryzen 9 9900x", "ryzen 9 7950x", "ryzen 9 7900x", "i9-14900k", "core i9-14900k", "i9-13900k", "core i9-13900k", "i9-12900k", "core i9-12900k", "ultra 9 285k", "ultra 9 275k").any { it in lower }) return 170
        if (listOf("ryzen 7 9800x3d", "ryzen 7 9700x", "ryzen 7 8700g", "ryzen 7 8700f", "ryzen 7 7700x", "ryzen 7 7700", "ryzen 7 5800x3d", "ryzen 7 5800x", "ryzen 7 5700x3d", "ryzen 7 5700x", "i7-14700k", "i7-13700k", "i7-12700k", "i7-14700", "i7-13700", "i7-12700", "ultra 7 265k", "ultra 7 265kf", "ultra 7 155h").any { it in lower }) return 120
        if (listOf("ryzen 5 9600x", "ryzen 5 9500x", "ryzen 5 8600g", "ryzen 5 8400f", "ryzen 5 7600x", "ryzen 5 7600", "ryzen 5 5600x3d", "ryzen 5 5600x", "ryzen 5 5600", "ryzen 5 5500", "ryzen 5 3600", "i5-14600k", "i5-14500", "i5-14400", "i5-13600k", "i5-13500", "i5-13400", "i5-13400f", "i5-12600k", "i5-12500", "i5-12400", "i5-12400f", "i5-11400", "ultra 5 245k", "ultra 5 225f", "ultra 5 135h", "ryzen 5 7500f").any { it in lower }) return 65
        if (listOf("ryzen 3 4100", "ryzen 3 3100", "ryzen 3 5300g", "i3-12100", "i3-12100f", "i3-13100", "i3-13100f", "i3-14100", "i3-10100", "pentium", "celeron").any { it in lower }) return 60
        if (lower.contains("ryzen 9")) return 170
        if (lower.contains("ryzen 7")) return 120
        if (lower.contains("ryzen 5")) return 65
        if (lower.contains("ryzen 3")) return 60
        if (lower.contains("i9-") || lower.contains("core i9")) return 170
        if (lower.contains("i7-") || lower.contains("core i7")) return 120
        if (lower.contains("i5-") || lower.contains("core i5")) return 65
        if (lower.contains("i3-") || lower.contains("core i3")) return 60
        if (lower.contains("ultra 9")) return 170
        if (lower.contains("ultra 7")) return 120
        if (lower.contains("ultra 5")) return 65
        return 65
    }

    private fun lookupGpuTdpWatts(gpuForCalc: String): Int {
        val lower = gpuForCalc.lowercase()
        if (listOf("rtx 5090", "rtx5090", "rtx 4090 ti", "rx 7900 xtx").any { it in lower }) return 550
        if (listOf("rtx 5080", "rtx5080", "rtx 4090", "rtx4090", "rx 7900 xt", "rx 7900gre", "rx 7900 gre").any { it in lower }) return 425
        if (listOf("rtx 5070 ti", "rtx 4080 super", "rtx 4080", "rtx 4070 ti super", "rtx 4070 ti", "rx 7800 xt", "rx 7800xt", "rx 7900").any { it in lower }) return 320
        if (listOf("rtx 5070", "rtx 5060 ti", "rtx 4070 super", "rtx 4070", "rtx 4060 ti 16gb", "rtx 4060 ti 8gb", "rtx 4060 ti", "rx 7700 xt", "rx 7700xt").any { it in lower }) return 230
        if (listOf("rtx 5060", "rtx 4060", "rtx 4060 8gb", "rx 8600 xt", "rx 7600 xt", "rx 7600x", "rx 7600", "rx 6750 gre", "rx 6700 xt", "rx 6700").any { it in lower }) return 170
        if (listOf("rtx 4050", "rtx 3070", "rtx 3060 ti", "rtx 3060 12gb", "rtx 3060", "rx 6650 xt", "rx 6600 xt", "rx 6600", "rx 6500 xt", "rx 6500xt").any { it in lower }) return 130
        if (listOf("rtx 2060 super", "rtx 2060", "rtx 2070", "gtx 1660 ti", "gtx 1660 super", "gtx 1660ti", "gtx 1660", "gtx 1650 super", "gtx 1650", "rx 580", "rx 590", "rx 6400").any { it in lower }) return 90
        if (lower.contains("rtx 4090") || lower.contains("rtx5090")) return 425
        if (lower.contains("rtx 4080")) return 320
        if (lower.contains("rtx 4070")) return 230
        if (lower.contains("rtx 4060")) return 170
        if (lower.contains("rtx 3060")) return 130
        if (lower.contains("rx 7900")) return 425
        if (lower.contains("rx 7800")) return 320
        if (lower.contains("rx 7700")) return 230
        if (lower.contains("rx 7600")) return 170
        if (lower.contains("rx 6600")) return 130
        return 170
    }

    private fun calculateSystemWattage(cpu: String, gpu: String): Int {
        val cpuTdp = lookupCpuTdpWatts(cpu)
        val gpuTdp = lookupGpuTdpWatts(gpu)
        val motherboardRamStorageOverhead = 80
        val total = cpuTdp + gpuTdp + motherboardRamStorageOverhead
        return total.coerceIn(100, 1200)
    }

    private data class CpuAnchoredGpuCandidate(
        val gpu: String,
        val gpuTgpWatts: Int,
        val gpuNeededCpuScore: Int,
        val vendorMinPsuWatts: Int,
        val usesPcieX8: Boolean = false
    )

    private fun roundUpTo50(value: Double): Int {
        val raw = kotlin.math.ceil(value).toInt().coerceAtLeast(0)
        return ((raw + 49) / 50) * 50
    }

    private fun lookupCpuPeakWattsForCpuAnchoredBuild(cpu: String): Int {
        val lower = cpu.trim().lowercase()
        return when {
            "5500gt" in lower -> 88
            "5600g" in lower -> 88
            "5700g" in lower -> 88
            else -> lookupCpuTdpWatts(cpu)
        }
    }

    private fun isCpuPcie3Only(cpu: String): Boolean {
        val lower = cpu.trim().lowercase()
        if (listOf("5500gt", "5600g", "5700g", "4600g", "5600gt").any { it in lower }) {
            return true
        }
        val maxPcie = analyzeCpuSpecs(cpu).maxPcie.lowercase()
        return maxPcie.contains("pcie 3.0") && !maxPcie.contains("5.0") && !maxPcie.contains("4.0 / 5.0")
    }

    private fun buildCpuAnchoredGpuPool(cpu: String, cpuTier: String, socket: String): List<CpuAnchoredGpuCandidate> {
        val lower = cpu.trim().lowercase()
        if (socket == "AM4" && cpuTier == "entry" && listOf("5500gt", "5500", "5600g", "5700g").any { it in lower }) {
            return listOf(
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4060", 115, 45, 550, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7600", 165, 47, 550, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4060 Ti", 160, 49, 600, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7700 XT", 245, 52, 700),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4070", 200, 53, 650)
            )
        }

        return when (cpuTier) {
            "high" -> listOf(
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4070", 200, 55, 650),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7800 XT", 263, 60, 700),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7900 GRE", 260, 64, 750),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4070 Ti Super", 285, 70, 750),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4080 Super", 320, 78, 850)
            )
            "mid", "midrange" -> listOf(
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4060 Ti", 160, 50, 600, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7700 XT", 245, 56, 700),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4070", 200, 58, 650),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7800 XT", 263, 63, 700),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4070 Super", 220, 65, 650)
            )
            else -> listOf(
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4060", 115, 45, 550, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7600", 165, 47, 550, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4060 Ti", 160, 49, 600, usesPcieX8 = true),
                CpuAnchoredGpuCandidate("AMD Radeon RX 7700 XT", 245, 52, 700),
                CpuAnchoredGpuCandidate("NVIDIA GeForce RTX 4070", 200, 53, 650)
            )
        }
    }

    private fun computeCpuAnchoredGpuBottleneckPct(cpuScore: Int, gpuNeededCpuScore: Int): Int {
        if (gpuNeededCpuScore <= 0) return 0
        val raw = (1.0 - (cpuScore.toDouble() / gpuNeededCpuScore.toDouble())) * 100.0
        return kotlin.math.round(raw.coerceIn(0.0, 60.0)).toInt()
    }

    private fun cpuAnchoredMotherboard(socket: String, tierShort: String): String {
        val tier = tierShort.trim().lowercase()
        return when (socket.uppercase()) {
            "AM4" -> when (tier) {
                "entry" -> "Gigabyte B550M DS3H"
                "mid", "midrange" -> "MSI MAG B550 TOMAHAWK"
                else -> "ASUS TUF GAMING B550-PLUS WIFI II"
            }
            "AM5" -> when (tier) {
                "entry" -> "Gigabyte B650M DS3H (AM5 DDR5)"
                "mid", "midrange" -> "MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)"
                else -> "ASUS TUF GAMING B650-PLUS WIFI (AM5 DDR5)"
            }
            "LGA1700", "LGA1851" -> when (tier) {
                "entry" -> "Gigabyte B760M DS3H AX DDR5 (LGA1700 DDR5)"
                "mid", "midrange" -> "MSI MAG B760 TOMAHAWK WIFI DDR5 (LGA1700)"
                else -> "ASUS TUF GAMING B760-PLUS WIFI DDR5 (LGA1700)"
            }
            "LGA1151" -> when (tier) {
                "entry" -> "Gigabyte B365M DS3H (LGA1151 DDR4)"
                "mid", "midrange" -> "MSI B365M MORTAR (LGA1151 DDR4)"
                else -> "ASUS TUF B365M-PLUS GAMING WIFI (LGA1151 DDR4)"
            }
            else -> when (tier) {
                "entry" -> "Gigabyte B650M DS3H (AM5 DDR5)"
                "mid", "midrange" -> "MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)"
                else -> "ASUS TUF GAMING B650-PLUS WIFI (AM5 DDR5)"
            }
        }
    }

    private fun cpuAnchoredRam(requiredRam: String, tierShort: String): String {
        val isDdr5 = requiredRam.uppercase().contains("DDR5")
        return when (tierShort.trim().lowercase()) {
            "entry" -> if (isDdr5) "16GB DDR5-5600 (2x8GB)" else "16GB DDR4-3200 (2x8GB)"
            "mid", "midrange" -> if (isDdr5) "32GB DDR5-6000 (2x16GB)" else "32GB DDR4-3200 (2x16GB)"
            else -> if (isDdr5) "32GB DDR5-6000 CL30 (2x16GB)" else "32GB DDR4-3600 CL16 (2x16GB)"
        }
    }

    private fun cpuAnchoredStorage(cpu: String, tierShort: String): String {
        val pcieGen = if (analyzeCpuSpecs(cpu).socket.uppercase() == "AM4") "3.0" else if (isCpuPcie3Only(cpu)) "3.0" else "4.0"
        return when (tierShort.trim().lowercase()) {
            "entry" -> "1TB NVMe PCIe $pcieGen SSD"
            "mid", "midrange" -> "1TB NVMe PCIe $pcieGen SSD"
            else -> "2TB NVMe PCIe $pcieGen SSD"
        }
    }

    private fun validateCpuAnchoredTierProgression(builds: List<BuildRecommendation>): Boolean {
        if (builds.size != 3) return false

        fun wattageOf(build: BuildRecommendation): Int {
            return Regex("""(\d{2,4})\s*W""", RegexOption.IGNORE_CASE)
                .find(build.wattage)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
        }

        val entry = builds[0]
        val mid = builds[1]
        val high = builds[2]

        val distinctEntryMid = !entry.ram.equals(mid.ram, ignoreCase = true) ||
            !entry.motherboard.equals(mid.motherboard, ignoreCase = true)
        val distinctMidHigh = !mid.ram.equals(high.ram, ignoreCase = true) ||
            !mid.motherboard.equals(high.motherboard, ignoreCase = true)
        val wattageAscending = wattageOf(entry) < wattageOf(mid) && wattageOf(mid) < wattageOf(high)
        val gpuAscending =
            scoreGpuPerformance(entry.gpu) < scoreGpuPerformance(mid.gpu) &&
            scoreGpuPerformance(mid.gpu) < scoreGpuPerformance(high.gpu)
        val bottleneckValid = builds.all {
            Regex("""(\d{1,2})""").find(it.bottleneck)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { pct -> pct <= 20 } == true
        }

        return distinctEntryMid && distinctMidHigh && wattageAscending && gpuAscending && bottleneckValid
    }

    private fun buildCpuAnchoredTierRecommendation(
        cpu: String,
        candidate: CpuAnchoredGpuCandidate,
        cpuSpec: CpuSpecAnalysis,
        tierShort: String,
        cpuTier: String,
        platform: LockedPlatform?
    ): BuildRecommendation {
        val cpuPeakWatts = lookupCpuPeakWattsForCpuAnchoredBuild(cpu)
        val totalWatts = cpuPeakWatts + candidate.gpuTgpWatts + 75
        val psuRecWatts = maxOf(roundUpTo50(totalWatts * 1.5), candidate.vendorMinPsuWatts)
        val psu = if (psuRecWatts >= 850) {
            "${psuRecWatts}W 80+ Gold Full Modular ATX 3.0"
        } else {
            "${psuRecWatts}W 80+ Gold Full Modular"
        }
        val bottleneckPct = computeCpuAnchoredGpuBottleneckPct(cpuSpec.cpuScore100, candidate.gpuNeededCpuScore)
        val limiting = if (bottleneckPct == 0) "Balanced" else "CPU"
        val pcieNote = if (isCpuPcie3Only(cpu) && candidate.usesPcieX8) {
            "This GPU uses PCIe x8. On PCIe 3.0 you may lose ~2-5% performance in some games."
        } else {
            ""
        }
        val tierLabel = when (tierShort.trim().lowercase()) {
            "entry" -> "ENTRY BUILD"
            "mid", "midrange" -> "MIDRANGE BUILD"
            else -> "HIGH-END BUILD"
        }
        val tierName = when (tierShort.trim().lowercase()) {
            "entry" -> "Entry"
            "mid", "midrange" -> "Midrange"
            else -> "High-End"
        }
        val rawBuild = BuildRecommendation(
            label = tierLabel,
            name = "$tierName Build for ${cleanCpuDisplayName(cpu)}",
            cpu = cleanCpuDisplayName(cpu),
            gpu = cleanGpuDisplayName(candidate.gpu),
            motherboard = cleanMoboDisplayName(cpuAnchoredMotherboard(cpuSpec.socket, tierShort)),
            ram = cleanRamDisplayName(cpuAnchoredRam(cpuSpec.requiredRam, tierShort)),
            storage = cleanStorageDisplayName(cpuAnchoredStorage(cpu, tierShort)),
            psu = cleanPsuDisplayName(psu),
            wattage = "${totalWatts}W",
            wattageNote = "Power calc: ${cpuPeakWatts}W CPU peak + ${candidate.gpuTgpWatts}W GPU + 75W system overhead = ${totalWatts}W; PSU recommendation ${psuRecWatts}W.",
            bottleneck = "${bottleneckPct}%",
            bottleneckNote = listOf(
                "CPU-anchored validation: bottleneck formula = clamp((1 - cpuScore / gpuNeededCpuScore) * 100, 0, 60).",
                "Computed bottleneck: ${bottleneckPct}% (must stay <=20%).",
            ).filter { it.isNotBlank() }.joinToString(" | "),
            gpuInfoNote = pcieNote,
            bottleneckAnalysis = buildDeterministicBottleneckAnalysis(
                cpu = cpu,
                gpu = candidate.gpu,
                pctIn = bottleneckPct.coerceIn(0, 20),
                noteRaw = "CPU anchor fixed. Socket/RAM/PSU validated for ${cpuSpec.socket}.",
                limitingRaw = limiting
            )
        )
        val (compatBuild, warnings) = finalPlatformCompatibilityGuard(rawBuild, platform)
        val compatNote = warnings.distinct().joinToString(" | ")
        return compatBuild.copy(
            wattageNote = listOf(rawBuild.wattageNote, compatBuild.wattageNote, compatNote)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" | "),
            bottleneckNote = listOf(rawBuild.bottleneckNote, compatBuild.bottleneckNote)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" | ")
        )
    }

    private fun buildCpuAnchoredRecommendations(
        component: String,
        componentTier: String,
        platform: LockedPlatform?
    ): List<BuildRecommendation> {
        val cpuSpec = analyzeCpuSpecs(component)
        val pool = buildCpuAnchoredGpuPool(component, componentTier, cpuSpec.socket)
        val validCandidates = pool.filter { candidate ->
            computeCpuAnchoredGpuBottleneckPct(cpuSpec.cpuScore100, candidate.gpuNeededCpuScore) <= 20
        }
        if (validCandidates.isEmpty()) return emptyList()

        val entryCandidate = validCandidates.first()
        val midCandidate = when {
            validCandidates.size >= 5 -> validCandidates[2]
            validCandidates.size >= 3 -> validCandidates[1]
            validCandidates.size == 2 -> validCandidates[1]
            else -> validCandidates.first()
        }
        val highCandidate = validCandidates.last()

        val tiered = listOf(
            buildCpuAnchoredTierRecommendation(component, entryCandidate, cpuSpec, "entry", componentTier, platform),
            buildCpuAnchoredTierRecommendation(component, midCandidate, cpuSpec, "mid", componentTier, platform),
            buildCpuAnchoredTierRecommendation(component, highCandidate, cpuSpec, "high", componentTier, platform)
        )
        return if (validateCpuAnchoredTierProgression(tiered)) tiered else emptyList()
    }

    private fun extractBnPercent(displayPercent: String): Int {
        val num = Regex("(\\d{1,3})").find(displayPercent)?.groupValues?.get(1)?.toIntOrNull()
        return num ?: 50
    }

    private val gpuDowngradeRanked = listOf(
        "NVIDIA GeForce RTX 5090",
        "AMD Radeon RX 9070 XTX",
        "NVIDIA GeForce RTX 5080",
        "NVIDIA GeForce RTX 4090",
        "AMD Radeon RX 9070 XT",
        "AMD Radeon RX 7900 XTX",
        "NVIDIA GeForce RTX 4080 Super",
        "NVIDIA GeForce RTX 4080",
        "NVIDIA GeForce RTX 5070 Ti",
        "AMD Radeon RX 9060 XT",
        "NVIDIA GeForce RTX 4070 Ti Super",
        "NVIDIA GeForce RTX 4070 Ti",
        "NVIDIA GeForce RTX 5070",
        "NVIDIA GeForce RTX 5060 Ti",
        "AMD Radeon RX 7800 XT",
        "NVIDIA GeForce RTX 4070 Super",
        "NVIDIA GeForce RTX 4070",
        "AMD Radeon RX 7700 XT",
        "NVIDIA GeForce RTX 5060",
        "NVIDIA GeForce RTX 4060 Ti",
        "AMD Radeon RX 7600 XT",
        "Intel Arc B580",
        "NVIDIA GeForce RTX 4060",
        "Intel Arc A770",
        "Intel Arc A750",
        "AMD Radeon RX 6650 XT",
        "NVIDIA GeForce RTX 3060",
        "Intel Arc A580",
        "NVIDIA GeForce RTX 5050",
        "AMD Radeon RX 6600",
        "NVIDIA GeForce GTX 1660 Super",
        "Intel Arc A380"
    )

    private val cpuUpgradeRanked = listOf(
        "Intel Core i3-12100F",
        "AMD Ryzen 5 5500",
        "AMD Ryzen 5 5600",
        "Intel Core i5-12400F",
        "AMD Ryzen 5 7500F",
        "AMD Ryzen 5 7600",
        "Intel Core i5-13600KF",
        "Intel Core i5-14600KF",
        "AMD Ryzen 7 5700X",
        "AMD Ryzen 7 5700X3D",
        "AMD Ryzen 7 7700",
        "AMD Ryzen 7 7700X",
        "Intel Core i7-13700KF",
        "Intel Core i7-14700KF",
        "Intel Core i9-12900KS",
        "AMD Ryzen 7 7800X3D",
        "Intel Core i9-13900KS",
        "AMD Ryzen 9 7900X",
        "AMD Ryzen 9 7900X3D",
        "AMD Ryzen 9 7950X",
        "Intel Core i9-14900KS",
        "AMD Ryzen 9 7950X3D",
        "AMD Ryzen 9 9900X",
        "AMD Ryzen 9 9950X"
    )

    private fun nextLowerGpu(currentGpu: String): String {
        val lower = currentGpu.trim().lowercase()
        val currentIdx = gpuDowngradeRanked.indexOfFirst { gpu -> lower.contains(gpu.trim().lowercase().replace("geforce ", "").replace("nvidia ", "").replace("amd radeon ", "")) }
        if (currentIdx < 0 || currentIdx >= gpuDowngradeRanked.size - 1) {
            return gpuDowngradeRanked.last()
        }
        return gpuDowngradeRanked[currentIdx + 1]
    }

    private fun nextHigherCpu(currentCpu: String): String {
        val lower = currentCpu.trim().lowercase().replace(Regex("\\([^)]*\\)"), "").trim()
        val currentIdx = cpuUpgradeRanked.indexOfFirst { cpu -> lower.contains(cpu.trim().lowercase().replace("intel core ", "").replace("amd ryzen ", "")) }
        if (currentIdx < 0) {
            return cpuUpgradeRanked.first()
        }
        if (currentIdx >= cpuUpgradeRanked.size - 1) {
            return cpuUpgradeRanked.last()
        }
        return cpuUpgradeRanked[currentIdx + 1]
    }

    private fun nextLowerCpu(currentCpu: String): String {
        val lower = currentCpu.trim().lowercase().replace(Regex("\\([^)]*\\)"), "").trim()
        val currentIdx = cpuUpgradeRanked.indexOfFirst { cpu -> lower.contains(cpu.trim().lowercase().replace("intel core ", "").replace("amd ryzen ", "")) }
        if (currentIdx <= 0) {
            return cpuUpgradeRanked.first()
        }
        return cpuUpgradeRanked[currentIdx - 1]
    }

    private fun enforcePlatformLockedRamAndSocket(
        preBuild: BuildRecommendation,
        category: String,
        component: String,
        platform: LockedPlatform?
    ): BuildRecommendation {
        val markerUnavailLower = "not available for this component tier"
        if (preBuild.cpu.trim().lowercase() == markerUnavailLower) return preBuild
        val refCpuRaw = if (category.equals("cpu", true) && component.isNotBlank()) component.trim() else preBuild.cpu.ifBlank { platform?.defaultCpu ?: "AMD Ryzen 5 7600" }
        val cpuSpec = analyzeCpuSpecs(refCpuRaw)
        val requiredRamGenShort = cpuSpec.requiredRam.trim().uppercase()
        val requiredSocket = cpuSpec.socket.trim().uppercase()
        var b = preBuild
        val moboLower = b.motherboard.trim().lowercase()
        val hasSocketInMobo = moboLower.contains(requiredSocket.lowercase())
            || ((requiredSocket == "AM5" || requiredSocket == "AM4") && moboLower.contains("amd") && moboLower.contains(requiredSocket.lowercase()))
            || ((requiredSocket == "LGA1700" || requiredSocket == "LGA1851" || requiredSocket == "LGA1151") && moboLower.contains("lga") && moboLower.contains(requiredSocket.lowercase().replace("lga", "")))
        val matchesSocket = when {
            requiredSocket == "AM5" && (moboLower.contains("am5") || moboLower.contains("b650") || moboLower.contains("x670") || moboLower.contains("x870") || moboLower.contains("a620")) -> true
            requiredSocket == "AM4" && (moboLower.contains("am4") || moboLower.contains("b550") || moboLower.contains("x570") || moboLower.contains("a520") || moboLower.contains("b450")) -> true
            requiredSocket == "LGA1700" && (moboLower.contains("lga1700") || moboLower.contains("b760") || moboLower.contains("z790") || moboLower.contains("h770") || moboLower.contains("b660") || moboLower.contains("z690")) -> true
            requiredSocket == "LGA1851" && (moboLower.contains("lga1851") || moboLower.contains("z890") || moboLower.contains("x890") || moboLower.contains("b860") || moboLower.contains("z880")) -> true
            requiredSocket == "LGA1151" && (moboLower.contains("lga1151") || moboLower.contains("b360") || moboLower.contains("z390") || moboLower.contains("b460") || moboLower.contains("z490") || moboLower.contains("h310") || moboLower.contains("h410")) -> true
            else -> hasSocketInMobo
        }
        if (!matchesSocket) {
            val fallbackMobo = when (requiredSocket) {
                "AM5" -> "MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)"
                "AM4" -> "MSI B550M PRO-VDH WIFI (AM4 DDR4)"
                "LGA1700" -> "ASUS TUF GAMING B760M-PLUS WIFI (LGA1700 DDR5)"
                "LGA1851" -> "ASUS ROG Maximus Z890 Hero (LGA1851 DDR5)"
                else -> b.motherboard.ifBlank { "Gigabyte B760M DS3H AX DDR5 (LGA1700)" }
            }
            b = b.copy(motherboard = fallbackMobo)
        }
        val ramLower = b.ram.trim().lowercase()
        val wrongDdr4 = requiredRamGenShort.startsWith("DDR5") && (ramLower.contains("ddr4") || !ramLower.contains("ddr5"))
        val wrongDdr5 = requiredRamGenShort.startsWith("DDR4") && ramLower.contains("ddr5")
        if (wrongDdr4 || wrongDdr5) {
            val correctRamGen = if (requiredRamGenShort.startsWith("DDR5")) "DDR5" else "DDR4"
            val cap = Regex("""(\d+)\s*(?:gb|mb)""").find(b.ram)?.groupValues?.get(1)?.toIntOrNull() ?: 16
            val cfg = Regex("""(2\s*[xx]\s*\d+GB|2\s*[xx]\s*\d+)""", RegexOption.IGNORE_CASE).find(b.ram)?.value?.replace(Regex("\\s+"), "")?.replace("x", "x")?.uppercase() ?: "2x${cap / 2}GB".takeIf { cap % 2 == 0 && cap >= 16 } ?: "2x8GB"
            val speed = when (correctRamGen) {
                "DDR5" -> when {
                    cap >= 64 -> "DDR5-6400"
                    cap >= 32 -> "DDR5-6000"
                    else -> "DDR5-5600"
                }
                else -> when {
                    cap >= 32 -> "DDR4-3600"
                    else -> "DDR4-3200"
                }
            }
            val corrected = "${cap}GB $correctRamGen-$speed $cfg"
            b = b.copy(ram = corrected)
        }
        val storageLower = b.storage.trim().lowercase()
        val maxPcieShort = cpuSpec.maxPcie.trim()
        val pcieNumRequested = Regex("""pcie\s*([0-9])""", RegexOption.IGNORE_CASE).find(b.storage)?.groupValues?.get(1)?.toIntOrNull() ?: 4
        val pcieMaxAllowedNum = when {
            maxPcieShort.contains("5.0") || maxPcieShort.contains("5") -> 5
            maxPcieShort.contains("4.0") || maxPcieShort.contains("4") -> 4
            else -> 3
        }
        if (pcieNumRequested > pcieMaxAllowedNum) {
            val cap = Regex("""(\d+)\s*(?:tb|gb)""", RegexOption.IGNORE_CASE).find(b.storage)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val isTb = b.storage.lowercase().contains("tb") || cap >= 1000
            val displayCap = if (isTb) "${cap}TB" else "${cap}GB"
            b = b.copy(storage = "$displayCap PCIe $pcieMaxAllowedNum.0 NVMe SSD")
        }
        return b
    }

    private fun enforceMaxBottleneck20Pct(
        tierEnforcedBuild: BuildRecommendation,
        searchedCategory: String,
        searchedComponent: String,
        lockedPlatform: LockedPlatform?,
        lockSearchedCpu: Boolean = true
    ): Pair<BuildRecommendation, String> {
        var iterating = tierEnforcedBuild
        var noteAppend = ""
        val sCatNorm = searchedCategory.trim().lowercase()
        val searchedPreserved = searchedComponent.trim()
        // The searched CPU is only used for the bottleneck target inside its OWN detected tier.
        // Higher tiers use their ascended CPU so the tiers do not collapse into the same build.
        val useSearchedCpuForBn = sCatNorm == "cpu" && searchedPreserved.isNotBlank() && lockSearchedCpu
        for (i in 0 until 4) {
            val cpuIter = if (useSearchedCpuForBn) searchedPreserved else iterating.cpu.ifBlank { lockedPlatform?.defaultCpu ?: "AMD Ryzen 5 7600" }
            val gpuIter = if (sCatNorm == "gpu" && searchedPreserved.isNotBlank()) searchedPreserved else iterating.gpu.ifBlank { "NVIDIA GeForce RTX 4060" }
            val bnIter = computeCpuGpuBottleneck(analyzeCpuSpecs(cpuIter).cpuScore100, scoreGpuPerformance(gpuIter), DEFAULT_RESOLUTION)
            val pct = extractBnPercent(bnIter.displayPercent)
            if (pct <= 20) {
                if (i > 0) noteAppend = "Auto-balanced (iter $i) to keep bottleneck <=20% target: GPU=${iterating.gpu} / CPU=${iterating.cpu}"
                return iterating to noteAppend
            }
            val cpuScore = analyzeCpuSpecs(cpuIter).cpuScore100
            val gpuScore = scoreGpuPerformance(gpuIter)
            val gpuIsTheBottleneck = gpuScore < cpuScore
            val cpuIsTheBottleneck = cpuScore < gpuScore
            if (sCatNorm == "gpu" && searchedPreserved.isNotBlank()) {
                if (gpuIsTheBottleneck) {
                    val nextCpu = nextLowerCpu(iterating.cpu.ifBlank { cpuIter })
                    iterating = iterating.copy(cpu = nextCpu)
                } else if (cpuIsTheBottleneck) {
                    val nextCpu = nextHigherCpu(iterating.cpu.ifBlank { cpuIter })
                    iterating = iterating.copy(cpu = nextCpu)
                } else {
                    val nextCpu = nextLowerCpu(iterating.cpu.ifBlank { cpuIter })
                    iterating = iterating.copy(cpu = nextCpu)
                }
            } else {
                if (sCatNorm != "cpu" && !cpuIsTheBottleneck) {
                    val nextGpu = nextLowerGpu(iterating.gpu.ifBlank { gpuIter })
                    iterating = iterating.copy(gpu = nextGpu)
                } else if (sCatNorm != "cpu") {
                    val nextGpu = nextLowerGpu(iterating.gpu.ifBlank { gpuIter })
                    iterating = iterating.copy(gpu = nextGpu)
                } else {
                    val nextGpu = nextLowerGpu(iterating.gpu.ifBlank { gpuIter })
                    iterating = iterating.copy(gpu = nextGpu)
                }
            }
        }
        noteAppend = "Auto-balanced (final clamp) to keep bottleneck <=20% target: GPU=${iterating.gpu} / CPU=${iterating.cpu}"
        return iterating to noteAppend
    }

    private fun applyFinalBottleneckAndCpuSpecs(build: BuildRecommendation, component: String, category: String, tierShort: String, platform: LockedPlatform?, searchedComponentTier: String): BuildRecommendation {
        val markerUnavail = "NOT AVAILABLE for this component tier"
        if (build.name.equals(markerUnavail, ignoreCase = true) || build.cpu.equals(markerUnavail, ignoreCase = true)) {
            return build.copy(
                bottleneckAnalysis = build.bottleneckAnalysis ?: BottleneckAnalysis.EMPTY.copy(
                    limitingComponent = "Balanced",
                    summary = build.bottleneck,
                    detailedAnalysis = build.bottleneckNote,
                    reason = "Placeholder tier-unavailable build.",
                    withinLimit = true
                )
            )
        }
        val platformLockedBuild = enforcePlatformLockedRamAndSocket(build, category, component, platform)
        val tierEnforcedBuild = enforceMinimumTierStandardsPerSearchedTier(platformLockedBuild, searchedComponentTier, tierShort, category, platform)
        // ANCHOR TIER RULE: the searched component stays locked to its OWN detected tier only.
        // Every tier ABOVE it must upgrade the anchor slot (CPU) so mid/high are genuinely different.
        val isHigherThanAnchorTier = searchedComponentTier.isNotBlank() &&
            tierRankOfShort(tierShort) > tierRankOfShort(searchedComponentTier)
        val anchorSlotLocked = !isHigherThanAnchorTier
        val anchorAdjustedBuild = if (isHigherThanAnchorTier && category.equals("cpu", true) && component.isNotBlank()) {
            tierEnforcedBuild.copy(cpu = cpuAscensionTargetForTier(analyzeCpuSpecs(component).socket, tierShort))
        } else {
            tierEnforcedBuild
        }
        val (bnBalancedBuild, balanceNote) = enforceMaxBottleneck20Pct(anchorAdjustedBuild, category, component, platform, lockSearchedCpu = anchorSlotLocked)
        val cpuForCalc = if (category.equals("cpu", true) && component.isNotBlank() && anchorSlotLocked) component else bnBalancedBuild.cpu.ifBlank { platform?.defaultCpu ?: "AMD Ryzen 5 7600" }
        val gpuForCalc = if (category.equals("gpu", true) && component.isNotBlank()) component else bnBalancedBuild.gpu.ifBlank { "NVIDIA GeForce RTX 4060" }
        val cpuSpec = analyzeCpuSpecs(cpuForCalc)
        val cpuScore = cpuSpec.cpuScore100
        val gpuScore = scoreGpuPerformance(gpuForCalc)
        val bn = computeCpuGpuBottleneck(cpuScore, gpuScore, DEFAULT_RESOLUTION)
        val bottleneckOut = bn.displayPercent
        val pctInt = bn.percentInt.coerceIn(0, 20)
        val limitingRaw = if (bn.partLabel.contains("cpu", true)) "CPU" else if (bn.partLabel.contains("gpu", true)) "GPU" else "Balanced"
        val bottleneckNoteOut = "${bn.rangeLabel}. ${bn.detailedNote} (CPU $cpuScore | GPU $gpuScore @ 1080p)".trim()
        val analysis = buildDeterministicBottleneckAnalysis(
            cpu = cpuForCalc,
            gpu = gpuForCalc,
            pctIn = pctInt,
            noteRaw = listOfNotNull(balanceNote.takeIf { it.isNotBlank() }, bottleneckNoteOut).joinToString(" | "),
            limitingRaw = limitingRaw
        )
        val balancePrefix = if (balanceNote.isNotBlank()) "$balanceNote | " else ""
        val specPrefix = "${balancePrefix}CPU: ${cpuSpec.socket} | RAM: ${cpuSpec.requiredRam} | PCIe: ${cpuSpec.maxPcie} | CPU Score $cpuScore/100 | GPU Score $gpuScore/100 | Bottleneck: ${bn.partLabel} ${bn.displayPercent} (${bn.rangeLabel})"
        val oldNote = bnBalancedBuild.wattageNote.orEmpty().trim()
        val wattageNoteFinal = if (oldNote.isBlank()) specPrefix else "$oldNote | $specPrefix"
        val systemW = calculateSystemWattage(cpuForCalc, gpuForCalc)
        val wattageOut = "${systemW}W"
        val markerUnavailLower = markerUnavail.trim().lowercase()
        val tierEnfCpu = bnBalancedBuild.cpu
        val tierEnfGpu = bnBalancedBuild.gpu
        val tierEnfMobo = bnBalancedBuild.motherboard
        val tierEnfRam = bnBalancedBuild.ram
        val tierEnfStorage = bnBalancedBuild.storage
        val tierEnfPsu = bnBalancedBuild.psu
        val cpuDisplayClean = if (tierEnfCpu.trim().lowercase() == markerUnavailLower) tierEnfCpu else cleanCpuDisplayName(tierEnfCpu)
        val gpuDisplayClean = if (tierEnfGpu.trim().lowercase() == markerUnavailLower) tierEnfGpu else cleanGpuDisplayName(tierEnfGpu)
        val moboDisplayClean = if (tierEnfMobo.trim().lowercase() == markerUnavailLower) tierEnfMobo else cleanMoboDisplayName(tierEnfMobo)
        val ramDisplayClean = if (tierEnfRam.trim().lowercase() == markerUnavailLower) tierEnfRam else cleanRamDisplayName(tierEnfRam)
        val storageDisplayClean = if (tierEnfStorage.trim().lowercase() == markerUnavailLower) tierEnfStorage else cleanStorageDisplayName(tierEnfStorage)
        val psuDisplayClean = if (tierEnfPsu.trim().lowercase() == markerUnavailLower) tierEnfPsu else cleanPsuDisplayName(tierEnfPsu)
        val nameDisplayClean = cleanBuildDisplayName(bnBalancedBuild.name)
        return bnBalancedBuild.copy(
            cpu = cpuDisplayClean,
            gpu = gpuDisplayClean,
            motherboard = moboDisplayClean,
            ram = ramDisplayClean,
            storage = storageDisplayClean,
            psu = psuDisplayClean,
            name = nameDisplayClean,
            wattage = wattageOut,
            bottleneck = bottleneckOut,
            bottleneckNote = bottleneckNoteOut,
            wattageNote = wattageNoteFinal,
            bottleneckAnalysis = analysis
        )
    }

    private fun cleanCpuDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        s = Regex("""\([^)]*\)""").replace(s, "")
        s = Regex("""\[[^\]]*\]""").replace(s, "")
        s = Regex("""\s*[-\u2013\u2014]+\s+(?=[A-Za-z\s]*?(?:cache|ghz|mhz|nm|watts?|tdp))[^.]*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s*,\s*(?=[A-Za-z\s]*?(?:cache|ghz|mhz|nm|watts?|tdp))[^.]*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*C\s*/\s*\d+\s*T\s*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*C\s*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*T\s*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*-\s*\d+\s*(?:cores?|threads?)\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        // Only strip a trailing core/thread count when it is preceded by a number.
        // Without the digit anchor this deletes the whole model from Intel names,
        // e.g. "Intel Core i5-14400F" -> "Intel".
        s = Regex("""\s+\d+\s*[-+]?\s*(?:\d+\s*)?(?:cores?|threads?|core|thread)\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+@?\s*\d+(?:\.\d+)?\s*(?:ghz|mhz)\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*(?:mb|gb)\s*(?:l[123]\s*)?cache\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*nm\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*w(?:atts?)?\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+tdp\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+(?:AM4|AM5|LGA\s*\d{3,4}|sTR5|sWRX8|Socket\s*[A-Za-z0-9\-]+)\s*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = s.trim().trimEnd(',', '-', ':', ';', '/').trim()
        return s
    }

    private fun cleanGpuDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        s = Regex("""\([^)]*\)""").replace(s, "")
        s = Regex("""\[[^\]]*\]""").replace(s, "")
        s = Regex("""\s+\d+\s*GB\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+GDDR[0-9A-Za-z]*\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*-\s*bit\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+@?\s*\d+(?:\.\d+)?\s*(?:ghz|mhz)\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+\d+\s*w(?:atts?)?\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+tdp\b.*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s*[-\u2013\u2014]+\s+(?=.*?(?:vram|memory|bus|bandwidth|cuda|cores?|stream|processors))[^.]*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s*,\s+(?=.*?(?:vram|memory|bus|bandwidth|cuda|cores?|stream|processors))[^.]*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = s.trim().trimEnd(',', '-', ':', ';', '/').trim()
        return s
    }

    private fun cleanMoboDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        s = Regex("""\([^)]*\)""").replace(s, "")
        s = Regex("""\[[^\]]*\]""").replace(s, "")
        s = s.trim().trimEnd(',', '-', ':', ';', '/').trim()
        return s
    }

    private fun cleanRamDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        s = Regex("""\s*\([^)]*(?:amd\s*expo|intel\s*xmp|exp0|xmp3?|jedec|expo\s*only|xmp\s*only)[^)]*\)""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+EXPO\s*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""\s+XMP(?:\s*\d)?\s*$""", RegexOption.IGNORE_CASE).replace(s, "")
        s = s.trim()
        return s
    }

    private fun cleanStorageDisplayName(raw: String): String {
        return raw.trim()
    }

    private fun cleanPsuDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        s = Regex("""\([^)]*\)""").replace(s, "")
        s = s.trim()
        return s
    }

    private fun cleanBuildDisplayName(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.trim()
        val hasFor = Regex("""\bBuild\s+for\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)
        if (!hasFor) return s
        val parts = Regex("""\bBuild\s+for\b""", RegexOption.IGNORE_CASE).split(s, limit = 2)
        if (parts.size != 2) return s
        val prefix = parts[0].trim()
        val anchorRaw = parts[1].trim()
        val anchorLower = anchorRaw.lowercase()
        val cleanedAnchor: String = when {
            Regex("""(rtx|gtx|rx|arc|radeon|geforce)""", RegexOption.IGNORE_CASE).containsMatchIn(anchorLower) -> cleanGpuDisplayName(anchorRaw)
            Regex("""(ryzen|core\s+ultra|core\s+i[3579]|athlon|threadripper|celeron|pentium)""", RegexOption.IGNORE_CASE).containsMatchIn(anchorLower) -> cleanCpuDisplayName(anchorRaw)
            Regex("""(nvme|ssd|hdd|hard\s*drive|storage)""", RegexOption.IGNORE_CASE).containsMatchIn(anchorLower) -> cleanStorageDisplayName(anchorRaw)
            Regex("""(ddr|ram|memory)""", RegexOption.IGNORE_CASE).containsMatchIn(anchorLower) -> cleanRamDisplayName(anchorRaw)
            Regex("""(\d{3,4}\s*w|80\s*\+|psu|power\s*supply)""", RegexOption.IGNORE_CASE).containsMatchIn(anchorLower) -> cleanPsuDisplayName(anchorRaw)
            Regex("""(am4|am5|lga|motherboard|mobo|chipset|prime|tuf|tomahawk|carbon|strix|proart|mpg|mag|rog)""", RegexOption.IGNORE_CASE).containsMatchIn(anchorLower) -> cleanMoboDisplayName(anchorRaw)
            else -> anchorRaw
        }
        s = "$prefix Build for $cleanedAnchor"
        return s
    }

    private data class LockedPlatform(
        val name: String,
        val cpuKeyContains: List<String>,
        val moboMustContain: String,
        val moboForbiddenContains: List<String>,
        val ramMustContain: String,
        val ramForbiddenContains: List<String>,
        val storageForbiddenContains: List<String>,
        val defaultCpu: String,
        val defaultMotherboard: String,
        val defaultRam: String,
        val defaultStorage: String
    ) {
        val socket: String
            get() = when {
                name.contains("AM5", ignoreCase = true) -> "AM5"
                name.contains("AM4", ignoreCase = true) -> "AM4"
                name.contains("LGA1851", ignoreCase = true) -> "LGA1851"
                name.contains("LGA1700", ignoreCase = true) -> "LGA1700"
                else -> "N/A"
            }
        val ramGen: String
            get() = when {
                ramMustContain.contains("DDR5", ignoreCase = true) -> "DDR5"
                ramMustContain.contains("DDR4", ignoreCase = true) -> "DDR4"
                else -> "DDR5"
            }
    }

    private val LOCKED_PLATFORMS: List<LockedPlatform> = listOf(
        LockedPlatform(
            name = "AMD AM4",
            cpuKeyContains = listOf("am4", "ryzen 5 5", "ryzen 7 5", "ryzen 9 5", "ryzen 3 5", "ryzen 7 5800x3d", "a520", "b450", "b550", "x470", "x570"),
            moboMustContain = "AM4",
            moboForbiddenContains = listOf("AM5", "X670", "B650", "X870", "LGA", "Z790", "B760", "Z690", "H770", "Z890"),
            ramMustContain = "DDR4",
            ramForbiddenContains = listOf("DDR5"),
            storageForbiddenContains = listOf("PCIe 5"),
            defaultCpu = "AMD Ryzen 5 5600 (6C/12T AM4)",
            defaultMotherboard = "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)",
            defaultRam = "32GB DDR4-3600 (2x16GB)",
            defaultStorage = "2TB NVMe PCIe 4.0 SSD"
        ),
        LockedPlatform(
            name = "AMD AM5",
            cpuKeyContains = listOf("am5", "ryzen 5 7", "ryzen 7 7", "ryzen 9 7", "ryzen 7 7800x3d", "ryzen 5 8600", "ryzen 7 8700", "ryzen 9 8900", "b650", "x670", "x870"),
            moboMustContain = "AM5",
            moboForbiddenContains = listOf("AM4", "A520", "B450", "B550", "X470", "X570", "LGA", "Z790", "B760", "Z690"),
            ramMustContain = "DDR5",
            ramForbiddenContains = listOf("DDR4"),
            storageForbiddenContains = listOf("PCIe 3"),
            defaultCpu = "AMD Ryzen 5 7600 (6C/12T AM5)",
            defaultMotherboard = "ASUS TUF GAMING B650-PLUS WIFI (AM5 DDR5)",
            defaultRam = "32GB DDR5-6000 (2x16GB) EXPO",
            defaultStorage = "2TB NVMe PCIe 4.0 SSD"
        ),
        LockedPlatform(
            name = "INTEL GEN 6-11",
            cpuKeyContains = listOf("i5-6", "i5-7", "i5-8", "i5-9", "i5-10", "i5-11", "i7-6", "i7-7", "i7-8", "i7-9", "i7-10", "i7-11", "i3-6", "i3-7", "i3-8", "i3-9", "i3-10", "i3-11", "i9-10", "i9-11", "lga1151", "b460", "z490", "h510", "b560", "z590"),
            moboMustContain = "LGA1151",
            moboForbiddenContains = listOf("AM4", "AM5", "LGA1700", "LGA1851", "Z790", "B760", "Z690"),
            ramMustContain = "DDR4",
            ramForbiddenContains = listOf("DDR5"),
            storageForbiddenContains = listOf("PCIe 5"),
            defaultCpu = "Intel Core i5-10400F (6C/12T LGA1151)",
            defaultMotherboard = "MSI MAG B560 TOMAHAWK WIFI (LGA1151 DDR4)",
            defaultRam = "16GB DDR4-3200 (2x8GB)",
            defaultStorage = "1TB NVMe PCIe 3.0 SSD"
        ),
        LockedPlatform(
            name = "INTEL GEN 12-14 + ULTRA",
            cpuKeyContains = listOf("i3-12", "i3-13", "i3-14", "i5-12", "i5-13", "i5-14", "i7-12", "i7-13", "i7-14", "i9-12", "i9-13", "i9-14", "ultra 5 2", "ultra 7 2", "ultra 9 2", "ultra 5 1", "ultra 7 1", "ultra 9 1", "lga1700", "lga1851", "b760", "z790", "h770", "z890", "b860", "h610", "b660", "z690"),
            moboMustContain = "LGA",
            moboForbiddenContains = listOf("AM4", "AM5", "LGA1151", "LGA1200", "B550", "B450", "X570", "X670", "B650"),
            ramMustContain = "DDR5",
            ramForbiddenContains = listOf("DDR4"),
            storageForbiddenContains = listOf("PCIe 3"),
            defaultCpu = "Intel Core i5-14400F (10C/16T LGA1700)",
            defaultMotherboard = "ASUS PRIME Z790-P WIFI (LGA1700 DDR5)",
            defaultRam = "32GB DDR5-6400 (2x16GB) XMP",
            defaultStorage = "2TB NVMe PCIe 4.0 SSD"
        )
    )

    private fun detectPlatform(component: String, category: String): LockedPlatform? {
        val lower = component.lowercase()
        // Allow detection from ANY category - match keywords regardless of category.
        // PSU/Mobo/RAM/Storage base component search -> still lock platform correctly.
        for (p in LOCKED_PLATFORMS) {
            if (p.cpuKeyContains.any { it in lower }) return p
        }
        // Extra fallback: DDR-only detection if category == RAM or query has DDR
        if (lower.contains("ddr4")) return LOCKED_PLATFORMS.firstOrNull { it.name == "AMD AM4" } ?: LOCKED_PLATFORMS[0]
        if (lower.contains("ddr5")) return LOCKED_PLATFORMS.firstOrNull { it.name == "AMD AM5" } ?: LOCKED_PLATFORMS[1]
        return null
    }

    /** REMOVE SLASH SPLITS EVERYWHERE (Choose-Only-One generic rule for ALL fields always).
     *  Splits on "/" or " / " and returns FIRST non-blank item trimmed (best value pick).
     *  This fixes the PSU/RAM/Storage base-category bug where detectedPlatform is null -> previous code skipped sanitize, leaving "RTX 4060 / RX 7600" doubles.
     *  Also fixes CPU rows having "Ryzen 5 7600 / Intel i5-14400F" two CPUs visible. */
    private fun sanitizeChooseOnlyOneGeneric(raw: String, defaultIfBlank: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return defaultIfBlank
        val multiSlash = trimmed.split(" / ", "/").map { it.trim() }.filter { it.isNotBlank() }
        return if (multiSlash.isEmpty()) defaultIfBlank else multiSlash.first()
    }

    private fun sanitizeGpuFieldOnlyOne(nvidia: String, amd: String): String {
        val nv = nvidia.trim()
        val am = amd.trim()
        if (nv.isNotBlank()) return nv
        if (am.isNotBlank()) return am
        return "NVIDIA GeForce RTX 4060"
    }

    /** PSU MODEL NAME GUESSER: Detects if a string looks like a PSU model even WITHOUT explicit "80+" or "W".
     *  Example: "MSI MAG A650GL" (no W in model name, but MAG A650GL = 650W Gold MSI PSU series) */
    private fun looksLikePsuModel(s: String): Boolean {
        val lower = s.lowercase()
        // Classic watt + cert patterns (with or without W)
        val hasWattOrCert = ("\\d{3,4}\\s*w".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lower)
            || lower.contains("80+")
            || lower.contains("bronze") || lower.contains("silver") || lower.contains("gold")
            || lower.contains("platinum") || lower.contains("titanium")
            || lower.contains("modular"))
        if (hasWattOrCert) return true
        // PSU SERIES BRAND + MODEL KEYWORDS (catches cases like "MSI MAG A650GL" with no W/80+):
        // Corsair RM/HX/CV/CX/SF series, Seasonic Focus, MSI MAG A/MAG MPG, ASUS Prime/ROG Strix/ROG THOR, EVGA SuperNOVA, be quiet! Straight Power, NZXT C, Cooler Master MWE, Thermaltake Toughpower, XFX, FSP, Antec NE/HSG/HCG, Deepcool PK/DQ
        val psuSeriesHit = listOf(
            " rm", "hx1000", "hx850", "hx750", "hx650",
            "cv550", "cv650", "cv750", "cx450", "cx550", "cx650", "cx750",
            "sf450", "sf600", "sf750", "sf850", "sf1000",
            "focus gx", "focus px", "focus gm", "focus ga", "focus sgx",
            "mag a550bn", "mag a650bn", "mag a650gl", "mag a750gl", "mag a850gl", "mag a1000gl",
            "mpg a750g", "mpg a850g", "mpg a1000g",
            "prime 450w", "prime 550w", "prime 650w", "prime 750w", "prime 850w",
            "rog strix 550w", "rog strix 650w", "rog strix 750w", "rog strix 850w", "rog strix 1000w",
            "rog thor", "rog loki",
            "supernova", "mwe bronze", "mwe gold", "mwe v2",
            "straight power", "pure power", "dark power",
            "nzxt c450", "nzxt c550", "nzxt c650", "nzxt c750", "nzxt c850", "nzxt c1000",
            "toughpower", "xfx speedster psu", "xfmr", "core pwr",
            "antec neo eco", "antec hcg", "antec ne ",
            "deepcool dq", "deepcool pk", "deepcool pm", "deepcool da",
            " be quiet", "evga supernova", "seasonic focus"
        ).any { it.lowercase() in lower }
        if (psuSeriesHit) return true
        // Last resort: MAG A <3digits> BN/GL/GF pattern, RM650x without space:
        if ("\\b(rm|hx|cv|cx|sf|mag|mpg)[\\s-]?[a-z]?\\d{3,4}[a-z]{0,2}\\b".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lower)) return true
        return false
    }

    /** For non CPU/GPU based searches (PSU/Mobo/RAM/Storage anchor): STRONG GUARD if LLM hallucinated anchor model name into wrong category row!
     *  Example: user searched "MSI MAG A650GL" (PSU category) -> mobo row contains "MSI MAG A650GL" -> WRONG -> TRUE, replace mobo with default valid mobo.
     *  Also catches if RAM/Storage/GPU/CPU rows incorrectly contain the searched PSU/RAM/Storage model. */
    private fun fieldProbablyWrongCategory(field: String, anchorComponent: String, anchorCategory: String, expectedFieldCategory: String): Boolean {
        val lowerField = field.trim().lowercase()
        val lowerAnchor = anchorComponent.trim().lowercase()
        if (anchorCategory == expectedFieldCategory) return false // correct row allowed
        // 1. Exact or partial contains (case 99% hallucination match for short unique model numbers)
        if (lowerField == lowerAnchor) return true
        if (lowerField.isNotBlank() && lowerAnchor.isNotBlank() && (lowerField.contains(lowerAnchor) || lowerAnchor.contains(lowerField))) {
            // Extra check: model number overlap. Extract all numbers/digits and compare core digits (for A650GL 650 vs anchor A650GL)
            val digitsField = "\\d{3,4}".toRegex().find(lowerField)?.value
            val digitsAnchor = "\\d{3,4}".toRegex().find(lowerAnchor)?.value
            if (digitsField != null && digitsField == digitsAnchor && lowerField.length <= lowerAnchor.length + 15) {
                return true
            }
            // For non CPU/GPU anchor categories, even if digits not same, contains the whole model = definitely wrong
            if (anchorCategory !in listOf("cpu","gpu") && lowerAnchor in lowerField) return true
        }
        // 2. Wrong row contains PSU-like model string but expected row not PSU field
        if (expectedFieldCategory != "psu" && looksLikePsuModel(field)) return true
        // 3. Expected RAM field but string doesn't contain DDR/size (likely wrong category part)
        if (expectedFieldCategory == "ram" && anchorCategory != "ram" && !lowerField.contains("ddr") && !lowerField.contains(" dimm") && !lowerField.contains("ram")) {
            val digitsField = "\\d{1,2}\\s*gb".toRegex(RegexOption.IGNORE_CASE).find(lowerField)
            if (digitsField == null && !lowerField.contains("memory")) {
                if (anchorCategory in listOf("psu","motherboard","storage")) return true
            }
        }
        return false
    }

    private fun sanitizeMotherboardLocked(raw: String, platform: LockedPlatform,
                                           entryMidHigh: String, capacity: String = "mid"): String {
        val lower = raw.lowercase()
        val hasForbidden = platform.moboForbiddenContains.any { token ->
            token.lowercase() in lower
        }
        val missingMust = !(platform.moboMustContain.split("|").first().lowercase() in lower
                || (platform.name.startsWith("INTEL GEN 12") && ("lga" in lower)))
        if (hasForbidden || missingMust) {
            val pName = platform.name
            if (pName == "AMD AM4") {
                if (entryMidHigh == "entry") return "GIGABYTE B550M DS3H (AM4 DDR4)"
                if (entryMidHigh == "mid") return "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
                return "ASUS ROG STRIX X570-E GAMING WIFI II (AM4 DDR4)"
            }
            if (pName == "AMD AM5") {
                if (entryMidHigh == "entry") return "GIGABYTE B650M DS3H (AM5 DDR5)"
                if (entryMidHigh == "mid") return "ASUS TUF GAMING B650-PLUS WIFI (AM5 DDR5)"
                return "ASUS ROG STRIX X670E-F GAMING WIFI (AM5 DDR5)"
            }
            if (pName == "INTEL GEN 6-11") {
                if (entryMidHigh == "entry") return "GIGABYTE B560M DS3H V2 (LGA1151 DDR4)"
                if (entryMidHigh == "mid") return "MSI MAG B560 TOMAHAWK WIFI (LGA1151 DDR4)"
                return "ASUS ROG STRIX Z590-E GAMING WIFI (LGA1151 DDR4)"
            }
            if (entryMidHigh == "entry") return "Gigabyte B760M DS3H AX DDR5 (LGA1700 DDR5)"
            if (entryMidHigh == "mid") return "ASUS PRIME Z790-P WIFI (LGA1700 DDR5)"
            return "ASUS ROG MAXIMUS Z790 HERO (LGA1700 DDR5)"
        }
        return raw.trim().ifBlank { platform.defaultMotherboard }
    }

    private fun sanitizeRamLocked(raw: String, platform: LockedPlatform, tier: String): String {
        val lower = raw.lowercase()
        val hasMust = platform.ramMustContain.isBlank() || platform.ramMustContain.lowercase() in lower
        val wrongGen = platform.ramForbiddenContains.isNotEmpty() && platform.ramForbiddenContains.any { it.lowercase() in lower }
        val needsOverride = wrongGen || !hasMust
        if (platform.name.startsWith("INTEL GEN 12") || platform.name.contains("LGA1700") || platform.name.contains("GEN 12-14") || platform.name.contains("ULTRA")) {
            if (tier == "entry") return "16GB DDR5-5600 (2x8GB) XMP"
            if (tier == "mid") return "32GB DDR5-6000 (2x16GB) XMP"
            return "64GB DDR5-6400 (2x32GB) XMP"
        }
        if (needsOverride) {
            val pName = platform.name
            if (pName == "AMD AM4") {
                if (tier == "entry") return "16GB DDR4-3200 (2x8GB)"
                if (tier == "mid") return "32GB DDR4-3600 (2x16GB)"
                return "64GB DDR4-3600 (2x32GB)"
            }
            if (pName == "AMD AM5") {
                if (tier == "entry") return "16GB DDR5-5600 (2x8GB) EXPO"
                if (tier == "mid") return "32GB DDR5-6000 (2x16GB) EXPO"
                return "64GB DDR5-6400 (2x32GB) EXPO"
            }
            if (pName == "INTEL GEN 6-11") {
                if (tier == "entry") return "16GB DDR4-3200 (2x8GB)"
                if (tier == "mid") return "32GB DDR4-3200 (2x16GB)"
                return "64GB DDR4-3200 (2x32GB)"
            }
            if (tier == "entry") return "16GB DDR5-5600 (2x8GB) XMP"
            if (tier == "mid") return "32GB DDR5-6000 (2x16GB) XMP"
            return "64GB DDR5-6400 (2x32GB) XMP"
        }
        return raw.trim().ifBlank { platform.defaultRam }
    }

    private fun sanitizeStorageLocked(raw: String, platform: LockedPlatform, tier: String): String {
        val lower = raw.lowercase()
        val badGen = platform.storageForbiddenContains.any { it.lowercase() in lower }
        if (badGen) {
            if (tier == "entry") return "1TB NVMe PCIe 4.0 SSD"
            if (tier == "mid") return "2TB NVMe PCIe 4.0 SSD"
            return "4TB NVMe PCIe 4.0 SSD"
        }
        return raw.trim().ifBlank { platform.defaultStorage }
    }

    // =============================================================================
    // BUILD ACCURACY HELPERS (per user request: correct tier parts, real models only,
    // PSU minimum watts for high-end GPUs like RTX 5090, match tier labels)
    // =============================================================================

    /** FIX 1 + 6: CPU matches tier exactly.
     * Midrange build NEVER Ryzen9 7950X (high-end only).
     * Midrange CPUs allowed: AMD Ryzen 7 7700X or Ryzen 7 7800X3D.
     * High-end only: Ryzen 9 7950X / Ryzen 9 7900X / Intel i9-13900K / i9-14900K allowed */
    private fun enforceCpuTierOnly(rawCpu: String, tierShort: String): String {
        val lower = rawCpu.trim().lowercase()
        val tier = tierShort.trim().lowercase()
        val highEndOnlyCpus = listOf("ryzen 9 7950x", "ryzen 9 7900x", "ryzen 9 9950x", "ryzen 9 9900x",
            "core i9-14900k", "core i9-13900k", "core i9-12900k", "core ultra 9 285k",
            "i9-14900k", "i9-13900k", "i9-12900k", "i9 14900k", "i9 13900k")
        val isHighEndCpu = highEndOnlyCpus.any { keyword -> keyword in lower }
        if (tier != "high" && isHighEndCpu) {
            if (tier == "entry") return "AMD Ryzen 5 5600 (6C/12T)"
            return "AMD Ryzen 7 7800X3D (8C/16T)"
        }
        val trimmed = rawCpu.trim()
        if (trimmed.isNotBlank()) return trimmed
        if (tier == "entry") return "AMD Ryzen 5 5600 (6C/12T)"
        if (tier == "mid") return "AMD Ryzen 7 7700X (8C/16T)"
        return "AMD Ryzen 9 7950X (16C/32T)"
    }

    /** FIX 3: MOTHERBOARD REAL MODELS ONLY. No "Generic B650M".
     * If "generic" / "placeholder" / blank / starts with just chipset -> replace with EXACT real brand names
     * User specified examples: MSI B650M-A Pro / ASUS TUF GAMING B650M-E / Gigabyte B650M DS3H */
    private fun enforceMotherboardRealModelsOnly(rawMobo: String, tierShort: String, platform: LockedPlatform?): String {
        val lower = rawMobo.trim().lowercase()
        val tier = tierShort.trim().lowercase()
        val hasGeneric = (lower.contains("generic")
            || lower.contains("placeholder")
            || rawMobo.isBlank()
            || Regex("^(b650m|x670e|b550m|z790|z690|b760m|h610m|h610|b660m|h770|z890|b860)[\\s-]*\$", RegexOption.IGNORE_CASE).matches(lower))
        if (!hasGeneric) return rawMobo.trim()
        val socketName = (platform?.name ?: "").lowercase()
        if (socketName.contains("am5")) {
            if (tier == "entry") {
                return "Gigabyte B650M DS3H (AM5 DDR5)"
            }
            if (tier == "mid") {
                return "MSI B650M-A Pro (AM5 DDR5)"
            }
            val anchorLower = rawMobo.trim().lowercase()
            val highEndAm5List = listOf(
                "MSI MEG X670E ACE (AM5 DDR5)",
                "ASUS ROG CROSSHAIR X670E HERO (AM5 DDR5)",
                "Gigabyte X670E AORUS MASTER (AM5 DDR5)"
            )
            if ("crosshair" in anchorLower || "rog " in anchorLower || "hero" in anchorLower) {
                return highEndAm5List[1]
            }
            if ("aorus" in anchorLower || "gigabyte x670" in anchorLower || "master" in anchorLower) {
                return highEndAm5List[2]
            }
            if ("msi meg" in anchorLower || "ace" in anchorLower) {
                return highEndAm5List[0]
            }
            return highEndAm5List[0]
        }
        if (socketName.contains("am4")) {
            if (tier == "entry") {
                return "Gigabyte B450M DS3H WIFI (AM4 DDR4)"
            }
            if (tier == "mid") {
                return "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
            }
            return "ASUS ROG STRIX X570-E GAMING WIFI II (AM4 DDR4)"
        }
        if (socketName.contains("lga1700") || socketName.contains("gen12") || socketName.contains("ultra")) {
            if (tier == "entry") {
                return "Gigabyte B760M DS3H AX DDR5 (LGA1700 DDR5)"
            }
            if (tier == "mid") {
                return "MSI B760M-A PRO WIFI DDR5 (LGA1700)"
            }
            return "ASUS TUF GAMING Z790-PLUS WIFI (LGA1700 DDR5)"
        }
        if (socketName.contains("lga1151") || socketName.contains("gen6")) {
            return "MSI B360M-A PRO (LGA1151 DDR4)"
        }
        if (tier == "entry") {
            return "Gigabyte B650M DS3H (AM5 DDR5)"
        }
        if (tier == "mid") {
            return "MSI B650M-A Pro (AM5 DDR5)"
        }
        val fallbackHigh = listOf(
            "MSI MEG X670E ACE (AM5 DDR5)",
            "ASUS ROG CROSSHAIR X670E HERO (AM5 DDR5)",
            "Gigabyte X670E AORUS MASTER (AM5 DDR5)"
        )
        return fallbackHigh.first()
    }

    /** FIX 4 + 5 + 6: PSU MIN WATTAGE RULES.
     * RTX 5090 = MIN 850W 80+ Gold ATX 3.0 (DANGEROUS to run on 650W).
     * Base component searched = RTX 5090 = HIGH-END TIER ONLY.
     * Entry = 550W-650W / Mid = 650W-750W / High-End GPU = 850W minimum. */
    /** NEW STRICT RULE #1 PSU: Always calculate PSU minimum = build.estimatedWattage * 1.30 (30% safe headroom), round up to standard 50W steps, then MAX(that rule, GPU tier minimum rule). */
    private fun calculatePsuMinWithHeadroomRule(estimatedBuildWattageW: Int, rawGpu: String, tierShort: String, component: String, category: String): Int {
        // Rule #1: Estimated system wattage * 1.30 (30% safe headroom)
        val withHeadroomRaw = if (estimatedBuildWattageW > 0) (estimatedBuildWattageW * 1.30) else 0.0
        // Round up to nearest 50W (standard PSU steps 500/550/600/650/700/750/800/850/900/1000/1200)
        val roundedHeadroom = if (withHeadroomRaw > 0) {
            val step = 50
            val raw = withHeadroomRaw.toInt()
            ((raw + step - 1) / step) * step
        } else 0
        // Existing GPU tier minimum rule (RTX 5090 = 850W etc)
        val lowGpu = rawGpu.trim().lowercase()
        val lowComp = component.trim().lowercase()
        val tier = tierShort.trim().lowercase()
        val needs850 = listOf("rtx 5090", "rtx5090", "rtx 4090", "rtx4090", "rx 7900 xtx", "rx 9070 xt")
            .any { it in lowGpu || it in lowComp }
        val needs750 = !needs850 && listOf("rtx 5080", "rtx 4080 super", "rtx 4080 super", "rx 7900 gre", "rx 7800 xtx").any { it in lowGpu || it in lowComp }
        val baseIsHighEndGpu = category.equals("gpu", true) && ("rtx 5090" in lowComp || "rtx 4090" in lowComp || "rx 7900 xtx" in lowComp)
        val gpuTierMinimum: Int
        when {
            baseIsHighEndGpu -> gpuTierMinimum = 850
            needs850 -> gpuTierMinimum = 850
            needs750 -> gpuTierMinimum = 750
            tier == "high" -> gpuTierMinimum = 850
            tier == "mid" -> gpuTierMinimum = 650
            else -> gpuTierMinimum = 550
        }
        // STRICT: PSU must satisfy BOTH rules! Take the MAXIMUM value of (wattage-with-30%-headroom) vs (GPU tier minimum).
        return maxOf(roundedHeadroom, gpuTierMinimum)
    }

    private fun enforcePsuMinimumForGpu(rawPsu: String, rawGpu: String, tierShort: String, component: String, category: String, estimatedBuildWattageW: Int = 0): String {
        val lowPsu = rawPsu.trim().lowercase()
        val minW = calculatePsuMinWithHeadroomRule(estimatedBuildWattageW, rawGpu, tierShort, component, category)
        val wattsInPsu = Regex("(\\d{3,4})\\s*w", RegexOption.IGNORE_CASE).find(lowPsu)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val hasCert = lowPsu.contains("80+") || lowPsu.contains("bronze") || lowPsu.contains("silver")
            || lowPsu.contains("gold") || lowPsu.contains("platinum") || lowPsu.contains("titanium")
        val tier = tierShort.trim().lowercase()
        val certText: String
        if (minW >= 850) {
            certText = "80+ Gold"
        } else if (tier == "entry") {
            certText = "80+ Bronze"
        } else {
            certText = "80+ Gold"
        }
        val atx3Text = if (minW >= 850) " ATX 3.0" else ""
        if (wattsInPsu >= minW && hasCert) return rawPsu.trim()
        return "${minW}W $certText Full Modular$atx3Text"
    }

    /** STRICT USER RULE #2 + RULE #3: FINAL PLATFORM COMPATIBILITY CHECK right before returning each build.
     *  Detects ANY mismatches (DDR5 on AM4 / DDR4 on AM5 / wrong CPU socket mobo etc).
     *  If ANY incompatibility is found: AUTO-CORRECT via running platform sanitizers (mandatory).
     *  Returns: <corrected build, list<String> incompatibility reasons> (for wattage note / UI warning purposes). */
    private fun finalPlatformCompatibilityGuard(build: BuildRecommendation, platform: LockedPlatform?): Pair<BuildRecommendation, List<String>> {
        val warnings = mutableListOf<String>()
        val cpuL = build.cpu.lowercase()
        val moboL = build.motherboard.lowercase()
        val ramL = build.ram.lowercase()
        val storageL = build.storage.lowercase()
        // Platform == null case: cannot check (unknown platform, searched GPU/PSU/Storage universal part)
        if (platform == null) return build to warnings
        val platformNameLower = platform.name.lowercase()
        // --- RULE #2 CRITICAL INCOMPATIBILITY DETECTORS ---
        // CASE A: AMD AM4 PLATFORM (DDR4 only, AM4 socket mobo only, Ryzen 1-5xxx AM4 CPU only)
        if (platformNameLower.contains("am4")) {
            if ("ddr5" in ramL) warnings.add("[WARN] INCOMPATIBLE: DDR5 RAM on AM4 platform (AM4 only supports DDR4). Replaced with DDR4 kit (Rule #2)")
            if (listOf("am5", "lga1700", "lga1851", "b650", "x670", "z790", "z690").any { it in moboL })
                warnings.add("[WARN] INCOMPATIBLE: Wrong socket motherboard on AM4 CPU. Replaced with AM4 motherboard.")
            if (listOf("ryzen 7 7", "ryzen 7 8", "ryzen 7 9", "ryzen 9 7", "ryzen 9 8", "core ultra", "i5-12", "i5-13", "i5-14").any { it in cpuL })
                warnings.add("[WARN] INCOMPATIBLE: Non-AM4 CPU in AM4 build. Replaced with correct AM4 Ryzen 5 5600 entry CPU.")
            if (listOf("pcie 5.0", "pcie5").any { it in storageL }) warnings.add("Note: PCIe 5.0 storage runs at PCIe 4.0 on AM4 platform.")
        }
        // CASE B: AMD AM5 PLATFORM (DDR5 only, AM5 socket mobo only, Ryzen 7xxx/8xxx/9xxx CPU only)
        if (platformNameLower.contains("am5")) {
            if ("ddr4" in ramL) warnings.add("[WARN] INCOMPATIBLE: DDR4 RAM on AM5 platform (AM5 only supports DDR5). Replaced with DDR5 kit (Rule #2)")
            if (listOf("am4", "lga1151", "b450", "b550", "x570", "z390", "b360").any { it in moboL })
                warnings.add("[WARN] INCOMPATIBLE: Wrong socket motherboard on AM5 CPU. Replaced with correct AM5 motherboard.")
            if (listOf("ryzen 5 5", "ryzen 5 3", "i5-6", "i5-7", "i5-8", "i5-9", "i5-10", "i5-11").any { it in cpuL })
                warnings.add("[WARN] INCOMPATIBLE: Non-AM5 CPU in AM5 build. Replaced with correct AM5 platform CPU.")
        }
        // CASE C: INTEL GEN6-11 LGA1151 (DDR4 only, LGA1151 mobo only, Core i3/i5/i7/i9 -6xxx up to -11xxx CPU only)
        if (platformNameLower.contains("gen 6") || platformNameLower.contains("gen6") || platformNameLower.contains("1151")) {
            if ("ddr5" in ramL) warnings.add("[WARN] INCOMPATIBLE: DDR5 RAM on Intel Gen6-11. Replaced with DDR4 kit.")
            if (listOf("am4", "am5", "lga1700", "b650", "x670", "z790", "b760").any { it in moboL })
                warnings.add("[WARN] INCOMPATIBLE: Wrong socket motherboard LGA1151 platform. Replaced with B560M DS3H LGA1151.")
            if (listOf("ryzen", "core ultra", "i5-12", "i5-13", "i5-14", "i7-12", "i7-13", "i9-12", "i9-14", "ryzen 5 7", "ryzen 7 7").any { it in cpuL })
                warnings.add("[WARN] INCOMPATIBLE: Non-Intel 6-11 gen CPU. Replaced with correct LGA1151 CPU.")
        }
        // CASE D: INTEL GEN12-14 + CORE ULTRA (LGA1700/LGA1851). DDR5 ONLY per Step 0 spec!
        if (platformNameLower.contains("1700") || platformNameLower.contains("1851") || platformNameLower.contains("gen 12") || platformNameLower.contains("ultra")) {
            if ("ddr4" in ramL) warnings.add("[WARN] INCOMPATIBLE: DDR4 RAM on Intel Gen12+/Core Ultra (DDR5 only). Replaced with DDR5 kit.")
            if (listOf("am4", "am5", "lga1151", "b450", "b550", "x570", "z390", "b360", "b560").any { it in moboL })
                warnings.add("[WARN] INCOMPATIBLE: Wrong socket motherboard Gen12+/LGA1700. Replaced with correct B760M/Z790 LGA1700 DDR5.")
            if (listOf("ryzen 5 5", "ryzen 7 5", "ryzen 9 5", "i5-6", "i5-7", "i5-8", "i5-9", "i5-10", "i5-11", "ryzen 3 3", "ryzen 5 3").any { it in cpuL })
                warnings.add("[WARN] INCOMPATIBLE: Non-Gen12+/Core Ultra CPU. Replaced with correct LGA1700 Core CPU.")
        }
        // --- AUTO-CORRECT VIA MANDATORY PLATFORM SANITIZERS IF ANY WARNING FOUND ---
        if (warnings.isNotEmpty()) {
            val lowerBuildLabel = build.label.lowercase()
            val tierShort: String
            if (lowerBuildLabel.contains("entry")) {
                tierShort = "entry"
            } else if (lowerBuildLabel.contains("high")) {
                tierShort = "high"
            } else {
                tierShort = "mid"
            }
            val moboOk = sanitizeMotherboardLocked(build.motherboard, platform, tierShort)
            val ramOk = sanitizeRamLocked(build.ram, platform, tierShort)
            val storageOk = sanitizeStorageLocked(build.storage, platform, tierShort)
            val cpuOk = if (warnings.any { it.contains("CPU") }) platform.defaultCpu else build.cpu
            val wattageNote = build.wattageNote.ifBlank { "" } + " " + warnings.joinToString(" | ")
            return build.copy(
                cpu = cpuOk,
                motherboard = moboOk,
                ram = ramOk,
                storage = storageOk,
                wattageNote = wattageNote.trim()
            ) to warnings
        }
        return build to emptyList()
    }

    /** FIX 1: FINAL LABEL/NAME CONSISTENCY CHECK (after all validation + enforce):
     * - Build label ALL UPPERCASE EXACT: ENTRY BUILD / MIDRANGE BUILD / HIGH-END BUILD (no validation suffix appended; matches "tab" label exact)
     * - Build name sentence case: e.g. "High-End Build for NVIDIA GeForce RTX 5090" (not ALL CAPS / not random case)
     */
    private fun finalizeLabelNameMatch(build: BuildRecommendation, anchorComponent: String): BuildRecommendation {
        val lowLabel = build.label.lowercase()
        val labelTitlePair: Pair<String, String> = when {
            lowLabel.contains("entry") -> Pair("ENTRY BUILD", "Entry")
            lowLabel.contains("mid") || lowLabel.contains("midrange") -> Pair("MIDRANGE BUILD", "Midrange")
            lowLabel.contains("high") || lowLabel.contains("highend") || lowLabel.contains("high-end") -> Pair("HIGH-END BUILD", "High-End")
            else -> Pair("MIDRANGE BUILD", "Midrange")
        }
        val finalLabelAllCaps = labelTitlePair.first
        val titleWord = labelTitlePair.second
        val finalNameSentenceCase = "$titleWord Build for $anchorComponent"
        return build.copy(
            label = finalLabelAllCaps,
            name = finalNameSentenceCase
        )
    }

    /** EXPLICIT VERSION that forces EXACT desired tier (entry/mid/high) IGNORES build.label completely.
     * Fixes case when backend returns 1-tier list with ENTRY BUILD label but searched GPU is RTX 5090 (HIGH-END desired): we overwrite to HIGH-END even if build says ENTRY. */
    private fun finalizeLabelNameMatchForcedTier(build: BuildRecommendation, anchorComponent: String, desiredTier: String): BuildRecommendation {
        val tier = desiredTier.trim().lowercase()
        val labelTitlePair2: Pair<String, String> = when (tier) {
            "entry" -> Pair("ENTRY BUILD", "Entry")
            "mid", "midrange" -> Pair("MIDRANGE BUILD", "Midrange")
            "high", "high-end", "highend", "high end" -> Pair("HIGH-END BUILD", "High-End")
            else -> Pair("MIDRANGE BUILD", "Midrange")
        }
        val labelAllCaps = labelTitlePair2.first
        val titleWord = labelTitlePair2.second
        return build.copy(
            label = labelAllCaps,
            name = "$titleWord Build for $anchorComponent"
        )
    }

    /** NEW USER REQUEST (Sept 2): Sync label + title BASED ON ACTUAL BUILD TIER LEVEL (from actual CPU+GPU parts that AI actually generated and returned to user) -- not forced desired tier).
     *  Detects tier level from build.content (cpu: Ryzen9/Ryzen7 7800X3D or GPU: RTX 5090 -> HIGH END) then sets label exactly to that detected tier, not to search component forced tier.
     *  Falls back to forceTierIfHighEndGpu override ONLY IF explicitly RTX 5090/4090 was searched GPU (searched category == GPU && forceTier needed rule kept from Sept 2 earlier rule.)
     */
    private fun finalizeLabelNameMatchFromBuildContent(build: BuildRecommendation, anchorComponent: String, searchedCategory: String, overrideTier: String): BuildRecommendation {
        val tierFromContent = detectTier(build.cpu + " " + build.gpu)
        val finalTier = forceTierIfHighEndGpu(anchorComponent, searchedCategory, tierFromContent)
        val finalTierNormalized = finalTier.trim().lowercase()
        val labelTitlePair3: Pair<String, String> = when (finalTierNormalized) {
            "entry" -> Pair("ENTRY BUILD", "Entry")
            "mid", "midrange" -> Pair("MIDRANGE BUILD", "Midrange")
            "high", "high-end", "highend", "high end" -> Pair("HIGH-END BUILD", "High-End")
            else -> Pair("MIDRANGE BUILD", "Midrange")
        }
        val labelAllCaps = labelTitlePair3.first
        val titleWord = labelTitlePair3.second
        return build.copy(
            label = labelAllCaps,
            name = "$titleWord Build for $anchorComponent"
        )
    }

    /** FIX #3: PLATFORM FALLBACK FROM BUILD CONTENT! GPU searches have no CPU so detectedPlatform NULL -> here scan actual build rows!
     *  NOTE: LockedPlatform is NOT an enum, it's a data class, instances are LOCKED_PLATFORMS list: idx 0=AMD AM4, 1=AMD AM5, 2=INTEL GEN6-11, 3=INTEL GEN 12-14 + ULTRA.
     *  Extracted into separate function for explicit return types (IDE was sometimes inferring a "Missing return statement" inside the inline run{} lambda). */
    private fun detectPlatformFromBuildContent(cpuRaw: String, moboRaw: String, ramRaw: String): LockedPlatform {
        val cpuL = cpuRaw.lowercase()
        val moboL = moboRaw.lowercase()
        val ramL = ramRaw.lowercase()
        val combined = "$cpuL $moboL $ramL"
        val am5Markers1 = listOf("ryzen 7 7", "ryzen 7 8", "ryzen 7 9", "ryzen 9 7", "ryzen 9 8", "ryzen 9 9", "am5", "x670", "b650", "ddr5 5600", "ddr5 6000", "ddr5 6400")
        if (am5Markers1.any { it in combined }) {
            return LOCKED_PLATFORMS[1]
        }
        val am4Markers1 = listOf("ryzen 5 5600", "ryzen 5 3600", "ryzen 7 5800", "ryzen 7 3700", "ryzen 3 3300", "ryzen 5 2600", "am4", "b550", "b450", "x570", "ddr4 3200", "ddr4 3600")
        if (am4Markers1.any { it in combined }) {
            return LOCKED_PLATFORMS[0]
        }
        val lga1700Markers1 = listOf("lga1700", "z790", "z690", "b760", "b660", "h610", "i3-12", "i3-13", "i3-14", "i5-12", "i5-13", "i5-14", "i7-12", "i7-13", "i7-14", "i9-12", "i9-13", "i9-14", "ultra 7 2", "ultra 9 2")
        if (lga1700Markers1.any { it in combined }) {
            return LOCKED_PLATFORMS[3]
        }
        val lga1151Markers1 = listOf("lga1151", "b360", "z390", "h310", "i3-6", "i3-7", "i3-8", "i3-9", "i5-6", "i5-7", "i5-8", "i5-9", "i7-6", "i7-7", "i7-8", "i7-9")
        if (lga1151Markers1.any { it in combined }) {
            return LOCKED_PLATFORMS[2]
        }
        val intelStringCatchAll: List<String> = listOf("intel core", "core i3", "core i5", "core i7", "core i9", "core ultra")
        val intelRegexStrings: List<String> = listOf("^i3-", "^i5-", "^i7-", "^i9-")
        var intelCatchAllHit = false
        for (s in intelStringCatchAll) {
            if (s in combined) {
                intelCatchAllHit = true
                break
            }
        }
        if (!intelCatchAllHit) {
            for (r in intelRegexStrings) {
                val rx = r.toRegex()
                if (rx.containsMatchIn(combined)) {
                    intelCatchAllHit = true
                    break
                }
            }
        }
        if (intelCatchAllHit) {
            return LOCKED_PLATFORMS[3]
        }
        val amdCatchAll: List<String> = listOf("amd ryzen", "amd ")
        if (amdCatchAll.any { it in combined }) {
            return LOCKED_PLATFORMS[1]
        }
        return LOCKED_PLATFORMS[1]
    }

    /** FINAL LAST-MILE SANITIZER on the SINGLE RETURNED BUILD after idx filter.
     * Fixes screenshot bug: RTX 5090 GPU search desired HIGH-END, but backend gave 1 ENTRY BUILD tier with mismatched label + midrange motherboard.
     * This function: (1) Forces label/title to EXPLICIT desiredTier (ignore build.label mismatch)
     *               (2) Re-applies enforceCpuTierOnly with CORRECT desired tier (CPU parts now match actual tier)
     *               (3) Re-applies enforceMotherboardRealModelsOnly with CORRECT desired tier (X670E for high-end AM5 now not B650M)
     *               (4) Re-applies enforcePsuMinimumForGpu with CORRECT desired tier (PSU minimums right tier)
     *               (5) GPU kept exact (searched part protected by enforceComponentInBuild before reaching this)
     */
    private fun finalizeSingleBuildAccuracy(build: BuildRecommendation, component: String, category: String, desiredTier: String, platform: LockedPlatform?): BuildRecommendation {
        val tierNorm = desiredTier.trim().lowercase()
        val tierShort: String
        if (tierNorm == "entry") {
            tierShort = "entry"
        } else if (tierNorm == "mid" || tierNorm == "midrange") {
            tierShort = "mid"
        } else if (tierNorm == "high" || tierNorm == "high-end" || tierNorm == "highend" || tierNorm == "high end") {
            tierShort = "high"
        } else {
            tierShort = "mid"
        }
        val platformForThisBuild: LockedPlatform? = platform ?: detectPlatform(build.cpu + " " + build.motherboard + " " + component, "cpu")
        val platformFinal: LockedPlatform = platformForThisBuild ?: detectPlatformFromBuildContent(build.cpu, build.motherboard, build.ram)
        // 1: Fix title + label FORCED to desired tier (mismatch ENTRY/HIGH case from screenshot fix)
        val step1 = finalizeLabelNameMatchForcedTier(build, component, tierShort)
        // 2: CPU correct for desired tier (if High-End, Ryzen9 7950X allowed; if Midrange -> Ryzen7 7800X3D)
        val cpuOk = enforceCpuTierOnly(step1.cpu, tierShort)
        // 3: Motherboard correct for DESIRED TIER + CORRECT PLATFORM: HIGH END AM5 NOW X670E not B650M anymore!
        val moboOk = enforceMotherboardRealModelsOnly(step1.motherboard, tierShort, platformFinal)
        // 4: PSU tier min watts correct
        val psuOk = enforcePsuMinimumForGpu(step1.psu, step1.gpu, tierShort, component, category)
        // 5: Platform correct RAM/Storage for socket (just in case - protect)
        val ramOk = sanitizeRamLocked(step1.ram, platformFinal, tierShort)
        val storageOk = sanitizeStorageLocked(step1.storage, platformFinal, tierShort)
        // Keep GPU exact (searched part!)
        val gpuExact = if (category.equals("gpu", true) && component.isNotBlank()) component else step1.gpu
        // STEP 0 / STEP 3 NEW: Analyze CPU specs, score CPU+GPU, calculate bottleneck with user's exact formula.
        val cpuForAnalysis = cpuOk.ifBlank { platformFinal.defaultCpu }
        val gpuForAnalysis = gpuExact.ifBlank { step1.gpu }
        val cpuSpec = analyzeCpuSpecs(cpuForAnalysis)
        val cpuScoreFinal = cpuSpec.cpuScore100
        val gpuScoreFinal = scoreGpuPerformance(gpuForAnalysis)
        val bottleneck = computeCpuGpuBottleneck(cpuScoreFinal, gpuScoreFinal, DEFAULT_RESOLUTION)
        // Step 4 Final Validation: Append CPU analysis + bottleneck detail to wattageNote so it's visible to UI.
        val cpuSummaryPrefix = "CPU: ${cpuSpec.socket} | RAM: ${cpuSpec.requiredRam} | PCIe: ${cpuSpec.maxPcie} | CPU Score $cpuScoreFinal/100 | GPU Score $gpuScoreFinal/100 | 1080p Bottleneck: ${bottleneck.partLabel} ${bottleneck.displayPercent} (${bottleneck.rangeLabel})"
        val baseWattNote = build.wattageNote.ifBlank { "" }
        val wattageNoteFinal = if (baseWattNote.isBlank()) cpuSummaryPrefix else "$baseWattNote | $cpuSummaryPrefix"
        val result = step1.copy(
            cpu = cpuOk,
            gpu = gpuExact,
            motherboard = moboOk,
            ram = ramOk,
            storage = storageOk,
            psu = psuOk,
            bottleneck = "${bottleneck.displayPercent} -- ${bottleneck.partLabel}",
            bottleneckNote = "${bottleneck.rangeLabel}. ${bottleneck.detailedNote} (CPU $cpuScoreFinal | GPU $gpuScoreFinal @ 1080p)",
            wattageNote = wattageNoteFinal
        )
        // FINAL STEP NEW USER REQUEST (Sept 2): LABEL + TITLE = based on ACTUAL BUILD CONTENT TIER (from CPU + GPU parts what AI actually generated/recommended in the build)!
        // Keep forceTierIfHighEndGpu search rule inside the helper for RTX 5090 searches.
        return finalizeLabelNameMatchFromBuildContent(result, component, category, tierShort)
    }

    /** FIX 6: If user searched RTX 5090 (GPU category) = FORCE HIGH-END TIER */
    private fun forceTierIfHighEndGpu(component: String, category: String, currentTier: String): String {
        val low = component.trim().lowercase()
        if (!category.equals("gpu", true)) return currentTier
        if (listOf("rtx 5090", "rtx 4090", "rx 7900 xtx", "rtx5090", "rtx4090").any { it in low }) return "high"
        return currentTier
    }

    private fun detectTier(component: String): String {
        val lower = component.lowercase()

        val highEndCpu = listOf(
            "ryzen 9", "ryzen 7 7800x3d", "ryzen 7 9700x", "ryzen 7 9800x3d",
            "i9-", "core i9", "ultra 9", "core ultra 9",
            "i7-14700k", "i7-13700k", "i7-12700k",
            "ryzen 7 8700", "ryzen 9 7900", "ryzen 9 8900", "ryzen 9 9900x", "ryzen 9 9950x"
        )
        val midrangeCpu = listOf(
            "ryzen 5 9600x", "ryzen 7 5700x", "ryzen 7 7700", "ryzen 7 8700f",
            "i5-13600k", "i5-14600k", "i5-12600k",
            "i7-13700", "i7-14700", "i7-12700"
        )
        val entryCpu = listOf(
            "ryzen 3", "ryzen 5 5500", "ryzen 5 5600", "ryzen 5 5600g",
            "i3-", "core i3",
            "i5-12400f", "i5-13400f", "i5-12400", "i5-13400",
            "ryzen 5 7500f", "ryzen 5 7600",
            "i5-14400", "i5-14500",
            "pentium", "celeron"
        )

        val highEndGpu = listOf(
            "rtx 4080", "rtx 4090", "rtx 5080", "rtx 5090",
            "rx 7900 xtx", "rx 7900gre", "rx 7900 gre",
            "rx 9070 xt", "rx 9070xt"
        )
        val midrangeGpu = listOf(
            "rtx 4060 ti", "rtx 4070 ti super", "rtx 4070 ti", "rtx 4070 super", "rtx 4070",
            "rx 7700 xt", "rx 7800 xt", "rx 7700xt", "rx 7800xt",
            "rtx 5060", "rtx 5060 ti", "rtx 5070", "rtx 5070 ti",
            "rx 9060 xt", "rx 9060xt",
            "arc a750", "arc a770", "arc b570", "arc b580"
        )
        val entryGpu = listOf(
            "gtx 1650", "gtx 1660", "gtx 1660 super", "gtx 1660ti", "gtx 1660 ti",
            "rtx 3050", "rtx 3060", "rtx 4060",
            "rx 6500", "rx 6600", "rx 6500xt", "rx 6600xt", "rx 6500 xt", "rx 6600 xt",
            "rtx 5050",
            "arc a380", "arc a580"
        )

        val cpuHigh = highEndCpu.any { it in lower }
        val cpuMid = midrangeCpu.any { it in lower }
        val cpuEntry = entryCpu.any { it in lower }
        val gpuHigh = highEndGpu.any { it in lower }
        val gpuMid = midrangeGpu.any { it in lower }
        val gpuEntry = entryGpu.any { it in lower }

        if (cpuHigh || gpuHigh) return "high"
        if (cpuMid || gpuMid) return "mid"
        if (cpuEntry || gpuEntry) return "entry"
        // General fallback keywords
        val generalHighKeywords = listOf("x3d", "9950x", "9900x", "5090", "4090", "xtx", "ultra 9")
        val generalMidKeywords = listOf(
            "rtx 5060", "rtx 5070", "rx 9060 xt", "rx 9060xt", "rx 9070 xt", "rx 9070xt"
        )
        val generalEntryKeywords = listOf(
            "5500", "12400", "13400", "1650", "3050", "6500", "celeron", "pentium",
            "rtx 5050", "rx 7500", "rx 7600"
        )
        if (generalHighKeywords.any { it in lower }) return "high"
        if (generalMidKeywords.any { it in lower }) return "mid"
        if (generalEntryKeywords.any { it in lower }) return "entry"
        return "mid"
    }

    private fun sharedHiddenTierPlaceholder(): BuildRecommendation {
        val unavailableText = "NOT AVAILABLE for this component tier"
        return BuildRecommendation(
            label = "ENTRY BUILD",
            name = unavailableText,
            cpu = unavailableText,
            gpu = unavailableText,
            motherboard = unavailableText,
            ram = unavailableText,
            storage = unavailableText,
            psu = unavailableText,
            wattage = "",
            wattageNote = unavailableText,
            bottleneck = "",
            bottleneckNote = unavailableText
        )
    }

    private fun applyTierVisibilityFilter(
        builds: List<BuildRecommendation>,
        detectedComponentTier: String,
        searchedComponent: String
    ): List<BuildRecommendation> {
        val unavailable = "NOT AVAILABLE for this component tier"
        val entry = builds.getOrNull(0)
        val mid = builds.getOrNull(1)
        val high = builds.getOrNull(2)
        val tierNorm = detectedComponentTier.trim().lowercase()

        fun hiddenEntryPlaceholder(): BuildRecommendation {
            return BuildRecommendation(
                label = "ENTRY BUILD",
                name = unavailable,
                cpu = unavailable,
                gpu = unavailable,
                motherboard = unavailable,
                ram = unavailable,
                storage = unavailable,
                psu = unavailable,
                wattage = "",
                wattageNote = unavailable,
                bottleneck = "",
                bottleneckNote = unavailable
            )
        }

        fun hiddenMidPlaceholder(): BuildRecommendation {
            return hiddenEntryPlaceholder().copy(label = "MIDRANGE BUILD")
        }

        fun hiddenHighPlaceholder(): BuildRecommendation {
            return hiddenEntryPlaceholder().copy(label = "HIGH-END BUILD")
        }

        val anchorName = searchedComponent.trim()

        fun overwriteHighEndLabels(build: BuildRecommendation): BuildRecommendation {
            val isUnavailable = build.name.equals(unavailable, ignoreCase = true) || build.cpu.equals(unavailable, ignoreCase = true)
            if (isUnavailable) return build
            return build.copy(
                label = "HIGH-END BUILD",
                name = "High-End Build for $anchorName"
            )
        }

        val ordered = when {
            tierNorm == "entry" -> listOf(
                entry ?: hiddenEntryPlaceholder(),
                mid ?: hiddenMidPlaceholder(),
                high ?: hiddenHighPlaceholder()
            )
            tierNorm == "mid" -> listOf(
                hiddenEntryPlaceholder(),
                mid ?: hiddenMidPlaceholder(),
                high ?: hiddenHighPlaceholder()
            )
            tierNorm == "high" -> listOf(
                overwriteHighEndLabels(hiddenEntryPlaceholder()),
                overwriteHighEndLabels(hiddenMidPlaceholder()),
                overwriteHighEndLabels(high ?: hiddenHighPlaceholder())
            )
            else -> listOf(
                entry ?: hiddenEntryPlaceholder(),
                mid ?: hiddenMidPlaceholder(),
                high ?: hiddenHighPlaceholder()
            )
        }
        return finalizeVisibleTiers(ordered, tierNorm, anchorName)
    }

    /**
     * Post-pass over the 3 tier builds the UI will actually display.
     *  1. A tier the UI shows must never render as the "--" unavailable placeholder while any real
     *     build exists in the list; the nearest real build is used as the template instead.
     *  2. Midrange and High-End must never be the exact same build: when they are identical the
     *     High-End slot is escalated to a socket-compatible stronger CPU so the tabs differ.
     */
    private fun finalizeVisibleTiers(
        tiers: List<BuildRecommendation>,
        tierNorm: String,
        anchorName: String
    ): List<BuildRecommendation> {
        val unavailable = "NOT AVAILABLE for this component tier"
        fun isPlaceholder(b: BuildRecommendation?): Boolean {
            if (b == null) return true
            return b.name.equals(unavailable, ignoreCase = true) || b.cpu.equals(unavailable, ignoreCase = true)
        }
        fun nearestReal(from: Int): BuildRecommendation? {
            if (!isPlaceholder(tiers.getOrNull(from))) return tiers.getOrNull(from)
            for (distance in 1..2) {
                val lower = from - distance
                val upper = from + distance
                if (lower >= 0 && !isPlaceholder(tiers.getOrNull(lower))) return tiers[lower]
                if (upper < tiers.size && !isPlaceholder(tiers.getOrNull(upper))) return tiers[upper]
            }
            return null
        }

        val visibleFrom = when (tierNorm) {
            "high", "high-end", "highend", "high end" -> 2
            "mid", "midrange" -> 1
            else -> 0
        }
        val out = tiers.toMutableList()
        for (idx in visibleFrom..(out.size - 1)) {
            if (!isPlaceholder(out[idx])) continue
            val template = nearestReal(idx) ?: continue
            val label = when (idx) {
                0 -> "ENTRY BUILD"
                1 -> "MIDRANGE BUILD"
                else -> "HIGH-END BUILD"
            }
            val tierName = when (idx) {
                0 -> "Entry"
                1 -> "Midrange"
                else -> "High-End"
            }
            out[idx] = template.copy(label = label, name = "$tierName Build for $anchorName")
        }

        if (out.size >= 3 && !isPlaceholder(out[1]) && !isPlaceholder(out[2])) {
            val midBuild = out[1]
            val highBuild = out[2]
            val identical = midBuild.cpu.trim().equals(highBuild.cpu.trim(), ignoreCase = true) &&
                midBuild.gpu.trim().equals(highBuild.gpu.trim(), ignoreCase = true) &&
                midBuild.motherboard.trim().equals(highBuild.motherboard.trim(), ignoreCase = true) &&
                midBuild.ram.trim().equals(highBuild.ram.trim(), ignoreCase = true) &&
                midBuild.storage.trim().equals(highBuild.storage.trim(), ignoreCase = true) &&
                midBuild.psu.trim().equals(highBuild.psu.trim(), ignoreCase = true)
            if (identical) {
                val socket = analyzeCpuSpecs(highBuild.cpu.ifBlank { "AMD Ryzen 5 7600" }).socket
                val strongerCpu = cpuAscensionTargetForTier(socket, "high")
                if (strongerCpu.isNotBlank() && !strongerCpu.equals(highBuild.cpu, ignoreCase = true)) {
                    out[2] = highBuild.copy(
                        cpu = strongerCpu,
                        bottleneckNote = "High-End tier escalated to a stronger CPU so it differs from Midrange."
                    )
                }
            }
        }
        return out
    }

    private suspend fun convertDirectAiBuilds(
        aiResult: OpenAiDto.JsonBuildRecommendation,
        searchedComponent: String,
        category: String,
        detectedPlatform: LockedPlatform?,
        searchedComponentTier: String
    ): List<BuildRecommendation> {
        val tierBuildsIn = aiResult.tierBuilds
        if (tierBuildsIn.isNullOrEmpty()) {
            return emptyList()
        }
        val outBuilds = mutableListOf<BuildRecommendation>()
        val idxMax = tierBuildsIn.size.coerceAtMost(3)
        var loopIdx = 0
        while (loopIdx < idxMax) {
            val tb = tierBuildsIn[loopIdx]
            var tierShortIn = "entry"
            val tbLabelNorm = tb.tierLabel.trim().lowercase()
            if (tbLabelNorm.contains("entry")) {
                tierShortIn = "entry"
            } else if (tbLabelNorm.contains("mid")) {
                tierShortIn = "mid"
            } else if (tbLabelNorm.contains("high")) {
                tierShortIn = "high"
            } else {
                val fallbackIdx = outBuilds.size
                if (fallbackIdx == 0) {
                    tierShortIn = "entry"
                } else if (fallbackIdx == 1) {
                    tierShortIn = "mid"
                } else {
                    tierShortIn = "high"
                }
            }
            val onlyOneGpu = tb.gpu
            val safeMoboPlatformLocked = platformSafeMoboOrOriginal(tb.motherboard, detectedPlatform, tierShortIn)
            val safeRamPlatformLocked = platformSafeRamOrOriginal(tb.ram, detectedPlatform, tierShortIn)
            val safeStoragePlatformLocked = platformSafeStorageOrOriginal(tb.storage, detectedPlatform, tierShortIn)
            val cpuOnlyOneRaw = sanitizeChooseOnlyOneGeneric(tb.cpu, "AMD Ryzen 5 5600")
            val gpuOnlyOneRaw = sanitizeChooseOnlyOneGeneric(onlyOneGpu, "NVIDIA GeForce RTX 4060")
            val defaultMobo = detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
            val defaultRam = detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"
            val defaultStorage = detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"
            val moboOnlyOneRaw = sanitizeChooseOnlyOneGeneric(safeMoboPlatformLocked, defaultMobo)
            val ramOnlyOneRaw = sanitizeChooseOnlyOneGeneric(safeRamPlatformLocked, defaultRam)
            val storageOnlyOneRaw = sanitizeChooseOnlyOneGeneric(safeStoragePlatformLocked, defaultStorage)
            val psuOnlyOneRaw = sanitizeChooseOnlyOneGeneric(tb.psu, "650W 80+ Gold Full Modular")
            var cpuOnlyOne = cpuOnlyOneRaw
            if (fieldProbablyWrongCategory(cpuOnlyOneRaw, searchedComponent, category, "cpu")) {
                cpuOnlyOne = "AMD Ryzen 5 5600"
            } else {
                cpuOnlyOne = cpuOnlyOneRaw
            }
            var gpuOnlyOne = gpuOnlyOneRaw
            if (fieldProbablyWrongCategory(gpuOnlyOneRaw, searchedComponent, category, "gpu")) {
                gpuOnlyOne = "NVIDIA GeForce RTX 4060"
            } else {
                gpuOnlyOne = gpuOnlyOneRaw
            }
            var moboOnlyOne = moboOnlyOneRaw
            if (fieldProbablyWrongCategory(moboOnlyOneRaw, searchedComponent, category, "motherboard")) {
                moboOnlyOne = defaultMobo
            } else {
                moboOnlyOne = moboOnlyOneRaw
            }
            var ramOnlyOne = ramOnlyOneRaw
            if (fieldProbablyWrongCategory(ramOnlyOneRaw, searchedComponent, category, "ram")) {
                ramOnlyOne = defaultRam
            } else {
                ramOnlyOne = ramOnlyOneRaw
            }
            var storageOnlyOne = storageOnlyOneRaw
            if (fieldProbablyWrongCategory(storageOnlyOneRaw, searchedComponent, category, "storage")) {
                storageOnlyOne = defaultStorage
            } else {
                storageOnlyOne = storageOnlyOneRaw
            }
            val defaultPsu = "650W 80+ Gold Full Modular"
            var psuOnlyOneRaw2 = psuOnlyOneRaw
            if (fieldProbablyWrongCategory(psuOnlyOneRaw, searchedComponent, category, "psu")) {
                psuOnlyOneRaw2 = defaultPsu
            } else {
                psuOnlyOneRaw2 = psuOnlyOneRaw
            }
            val cpuTierMatched = enforceCpuTierOnly(cpuOnlyOne, tierShortIn)
            val moboRealModel = enforceMotherboardRealModelsOnly(moboOnlyOne, tierShortIn, detectedPlatform)
            val estimatedW = tb.estimatedWattage.coerceAtLeast(0)
            val psuTierCorrect = enforcePsuMinimumForGpu(psuOnlyOneRaw2, gpuOnlyOne, tierShortIn, searchedComponent, category, estimatedW)
            val bnPct = tb.bottleneckPct.coerceIn(0, 20)
            val bnAnalysis = jsonBottleneckToDomainOrNull(
                json = tb.bottleneck,
                cpu = cpuTierMatched,
                gpu = gpuOnlyOne,
                fallbackPct = bnPct,
                fallbackNote = tb.bottleneckNote,
                fallbackLimitingRaw = tb.bottleneckPartLabel
            )
            val buildBase = BuildRecommendation(
                label = tb.tierLabel.uppercase().trim() + " BUILD",
                name = tb.tierLabel.trim() + " Build for $searchedComponent",
                cpu = cpuTierMatched,
                gpu = gpuOnlyOne,
                gpuNvidia = null,
                gpuAmd = null,
                motherboard = moboRealModel,
                ram = ramOnlyOne,
                storage = storageOnlyOne,
                psu = psuTierCorrect,
                wattage = "${estimatedW}W",
                wattageNote = "",
                bottleneck = "${bnPct}% -- ${tb.bottleneckPartLabel}",
                bottleneckNote = tb.bottleneckNote,
                bottleneckAnalysis = bnAnalysis
            )
            val enforced = enforceComponentInBuild(buildBase, searchedComponent, category)
            val v = RecommendationMockData.validateBuildRecommendation(enforced)
            val guardPair = finalPlatformCompatibilityGuard(enforced, detectedPlatform)
            val compat = guardPair.first
            val warnings = guardPair.second
            val noteMerged = listOf(v.summaryLabel, compat.wattageNote, warnings.joinToString(" | ")).filter { it.isNotBlank() }.distinct().joinToString(" | ")
            val bottleneckMergedBuilder = mutableListOf<String>()
            val vInfoJoined = v.info.joinToString(" ")
            if (v.info.isNotEmpty()) {
                bottleneckMergedBuilder.add(vInfoJoined)
            }
            val compatBnNote = compat.bottleneckNote
            if (compatBnNote.isNotBlank()) {
                bottleneckMergedBuilder.add(compatBnNote)
            }
            val bottleneckMerged = bottleneckMergedBuilder.distinct().joinToString(" ")
            val postCompat = compat.copy(
                label = compat.label.trim(),
                wattageNote = noteMerged.trim(),
                bottleneckNote = bottleneckMerged.trim().ifBlank { tb.bottleneckNote }
            )
            val final = applyFinalBottleneckAndCpuSpecs(postCompat, searchedComponent, category, tierShortIn, detectedPlatform, searchedComponentTier)
            outBuilds.add(final)
            loopIdx++
        }
        if (outBuilds.isEmpty()) {
            return emptyList()
        }
        // Pick exactly one build per tier BY LABEL. The previous index-based loops copied the
        // Midrange build into the High-End slot whenever the High label was not the next entry
        // (e.g. [Entry, Midrange, High-End] picked Midrange again for High-End at index 1), which
        // is why the Midrange and High-End tabs showed the exact same build on every component.
        val resultList = mutableListOf<BuildRecommendation>()
        val entryPick = outBuilds.firstOrNull { it.label.lowercase().contains("entry") } ?: outBuilds.getOrNull(0)
        val midPick = outBuilds.firstOrNull { it.label.lowercase().contains("mid") } ?: outBuilds.getOrNull(1)
        val highPick = outBuilds.firstOrNull { it.label.lowercase().contains("high") } ?: outBuilds.getOrNull(2)
        resultList.add((entryPick ?: sharedHiddenTierPlaceholder()).copy(label = "ENTRY BUILD"))
        resultList.add((midPick ?: entryPick ?: sharedHiddenTierPlaceholder()).copy(label = "MIDRANGE BUILD"))
        resultList.add((highPick ?: midPick ?: entryPick ?: sharedHiddenTierPlaceholder()).copy(label = "HIGH-END BUILD"))
        while (resultList.size < 3) {
            resultList.add(resultList.last())
        }
        val finalThree = resultList.take(3).toMutableList()
        val catNorm = category.trim().lowercase()
        val searchedTierNorm = searchedComponentTier.trim().lowercase()
        val markerUnavailLower = "not available for this component tier"
        val isGpuAscensionCase = (catNorm == "gpu" || catNorm.contains("gpu")) && searchedTierNorm in listOf("entry", "low")
        if (isGpuAscensionCase && finalThree.size >= 3) {
            val entryBuild = finalThree[0]
            val entryCpuLower = entryBuild.cpu.trim().lowercase()
            if (entryCpuLower != markerUnavailLower) {
                val cpuSpec = analyzeCpuSpecs(entryBuild.cpu.ifBlank { "AMD Ryzen 5 7600" })
                val socket = cpuSpec.socket.trim().uppercase()
                val ddrGen = if (cpuSpec.requiredRam.startsWith("DDR5")) "DDR5" else "DDR4"
                val ramCapGbEntry = Regex("""(\d+)\s*GB""", RegexOption.IGNORE_CASE).find(entryBuild.ram)?.groupValues?.get(1)?.toIntOrNull() ?: 16
                val storageCapEntry = runCatching {
                    val regex = Regex("""(\d+)\s*(TB|GB)""", RegexOption.IGNORE_CASE)
                    val m = regex.find(entryBuild.storage)
                    val num = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val unit = m?.groupValues?.get(2)?.uppercase() ?: "TB"
                    if (unit == "TB") num else 1
                }.getOrDefault(1)
                val psuWEntry = Regex("""(\d+)\s*W""", RegexOption.IGNORE_CASE).find(entryBuild.psu)?.groupValues?.get(1)?.toIntOrNull() ?: 550
                val pcieStorage = cpuSpec.maxPcie.let {
                    if (it.contains("5")) "5.0" else if (it.contains("4")) "4.0" else "3.0"
                }
                val ramCfgBase = if (ramCapGbEntry >= 64) "2x32GB" else if (ramCapGbEntry >= 32) "2x16GB" else "2x8GB"
                val midMoboSwap = when (socket) {
                    "AM5" -> "MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)"
                    "AM4" -> "ASUS TUF GAMING B550-PLUS WIFI II (AM4 DDR4)"
                    "LGA1700" -> "ASUS TUF GAMING B760M-PLUS WIFI (LGA1700 DDR5)"
                    "LGA1851" -> "ASUS ROG STRIX B860-F GAMING WIFI (LGA1851 DDR5)"
                    else -> entryBuild.motherboard
                }
                val highMoboSwap = when (socket) {
                    "AM5" -> "ASUS ROG Crosshair X670E Hero (AM5 DDR5)"
                    "AM4" -> "ASUS ROG Crosshair VIII Hero WIFI (AM4 DDR4)"
                    "LGA1700" -> "ASUS ROG Maximus Z790 Hero (LGA1700 DDR5)"
                    "LGA1851" -> "ASUS ROG Maximus Z890 Hero (LGA1851 DDR5)"
                    else -> midMoboSwap
                }
                val midRamCap = if (ramCapGbEntry >= 32) ramCapGbEntry else 32
                val midRamSpeed = when (ddrGen) {
                    "DDR5" -> if (midRamCap >= 64) "6400" else "6000"
                    else -> "3600"
                }
                val midRam = "${midRamCap}GB $ddrGen-$midRamSpeed 2x${midRamCap/2}GB"
                val midStorageCapTb = (storageCapEntry * 2).coerceAtLeast(2)
                val midStorage = "${midStorageCapTb}TB PCIe $pcieStorage NVMe SSD"
                val midPsuW = (psuWEntry + 100).coerceAtLeast(650)
                val midPsu = when (midPsuW) {
                    in 0..649 -> "650W 80+ Gold Full Modular"
                    in 650..799 -> "${midPsuW}W 80+ Gold Full Modular"
                    in 800..999 -> "${midPsuW}W 80+ Gold Full Modular ATX 3.0"
                    else -> "${midPsuW}W 80+ Platinum Full Modular ATX 3.1"
                }
                val highRamCap = (ramCapGbEntry * 2).coerceAtLeast(32).coerceAtMost(64)
                val highRamSpeed = when (ddrGen) {
                    "DDR5" -> if (highRamCap >= 64) "6400" else "6400"
                    else -> "3600"
                }
                val highRam = "${highRamCap}GB $ddrGen-$highRamSpeed 2x${highRamCap/2}GB"
                val highStorageCapTb = (storageCapEntry * 4).coerceAtLeast(4)
                val highStorage = "${highStorageCapTb}TB PCIe $pcieStorage NVMe SSD"
                val highPsuW = (psuWEntry + 200).coerceAtLeast(750)
                val highPsu = when {
                    highPsuW >= 1000 -> "${highPsuW}W 80+ Platinum Full Modular ATX 3.1"
                    highPsuW >= 850 -> "${highPsuW}W 80+ Platinum Full Modular ATX 3.0"
                    else -> "${highPsuW}W 80+ Gold Full Modular ATX 3.0"
                }
                val preservedGpu = searchedComponent.ifBlank { entryBuild.gpu }.trim()
                val existingMid = finalThree[1]
                val existingMidHidden = existingMid.cpu.trim().lowercase() == markerUnavailLower
                if (!existingMidHidden) {
                    val ascendedMidRaw = BuildRecommendation(
                        label = "MIDRANGE BUILD",
                        name = "Midrange Build for $preservedGpu",
                        cpu = entryBuild.cpu,
                        gpu = preservedGpu,
                        gpuNvidia = existingMid.gpuNvidia,
                        gpuAmd = existingMid.gpuAmd,
                        motherboard = midMoboSwap,
                        ram = midRam,
                        storage = midStorage,
                        psu = midPsu,
                        wattage = existingMid.wattage.ifBlank { entryBuild.wattage },
                        wattageNote = "GPU tier ascension (entry->mid): same CPU/GPU, upgraded mobo/ram/storage/psu only. Preserved compatibility + bottleneck integrity.",
                        bottleneck = existingMid.bottleneck.ifBlank { entryBuild.bottleneck },
                        bottleneckNote = existingMid.bottleneckNote.ifBlank { entryBuild.bottleneckNote }
                    )
                    val ascendedMidFinal = applyFinalBottleneckAndCpuSpecs(ascendedMidRaw, searchedComponent, category, "mid", detectedPlatform, searchedComponentTier)
                    finalThree[1] = ascendedMidFinal
                }
                val existingHigh = finalThree[2]
                val existingHighHidden = existingHigh.cpu.trim().lowercase() == markerUnavailLower
                if (!existingHighHidden) {
                    val oneTierUpCpu = runCatching {
                        val current = entryBuild.cpu.ifBlank { "AMD Ryzen 5 7600" }
                        val gpuScore = scoreGpuPerformance(preservedGpu.ifBlank { "NVIDIA GeForce RTX 4060" })
                        val up = nextHigherCpu(current)
                        val testBn = computeCpuGpuBottleneck(analyzeCpuSpecs(up).cpuScore100, gpuScore, DEFAULT_RESOLUTION)
                        val testPct = extractBnPercent(testBn.displayPercent)
                        val sameBn = computeCpuGpuBottleneck(analyzeCpuSpecs(current).cpuScore100, gpuScore, DEFAULT_RESOLUTION)
                        val samePct = extractBnPercent(sameBn.displayPercent)
                        if (testPct in 0..19 && testPct >= samePct - 5) up else current
                    }.getOrDefault(entryBuild.cpu.ifBlank { "AMD Ryzen 5 7600" })
                    val ascendedHighRaw = BuildRecommendation(
                        label = "HIGH-END BUILD",
                        name = "High-End Build for $preservedGpu",
                        cpu = oneTierUpCpu,
                        gpu = preservedGpu,
                        gpuNvidia = existingHigh.gpuNvidia,
                        gpuAmd = existingHigh.gpuAmd,
                        motherboard = highMoboSwap,
                        ram = highRam,
                        storage = highStorage,
                        psu = highPsu,
                        wattage = existingHigh.wattage.ifBlank { entryBuild.wattage },
                        wattageNote = "GPU tier ascension (entry->high): CPU +0/+1 tier upgrade only (kept bottleneck <20% same socket). Premium mobo/ram/storage/psu upgrade.",
                        bottleneck = existingHigh.bottleneck.ifBlank { entryBuild.bottleneck },
                        bottleneckNote = existingHigh.bottleneckNote.ifBlank { entryBuild.bottleneckNote }
                    )
                    val ascendedHighFinal = applyFinalBottleneckAndCpuSpecs(ascendedHighRaw, searchedComponent, category, "high", detectedPlatform, searchedComponentTier)
                    finalThree[2] = ascendedHighFinal
                }
            }
        }
        val finalWithContentLabels = mutableListOf<BuildRecommendation>()
        var fIdx = 0
        while (fIdx < 3) {
            val src = finalThree[fIdx]
            var lblFix = "HIGH-END BUILD"
            if (fIdx == 0) {
                lblFix = "ENTRY BUILD"
            } else if (fIdx == 1) {
                lblFix = "MIDRANGE BUILD"
            } else {
                lblFix = "HIGH-END BUILD"
            }
            if (src.name.startsWith("NOT AVAILABLE")) {
                finalWithContentLabels.add(src.copy(label = lblFix))
            } else {
                val matchedName = finalizeLabelNameMatch(src, searchedComponent).name
                val named = src.copy(
                    label = lblFix,
                    name = matchedName
                )
                finalWithContentLabels.add(named)
            }
            fIdx++
        }
        return finalWithContentLabels
    }

    private fun platformSafeMoboOrOriginal(
        moboRaw: String,
        platform: LockedPlatform?,
        tierShort: String
    ): String {
        if (platform == null) {
            return moboRaw
        }
        return sanitizeMotherboardLocked(moboRaw, platform, tierShort)
    }

    private fun platformSafeRamOrOriginal(
        ramRaw: String,
        platform: LockedPlatform?,
        tierShort: String
    ): String {
        if (platform == null) {
            return ramRaw
        }
        return sanitizeRamLocked(ramRaw, platform, tierShort)
    }

    private fun platformSafeStorageOrOriginal(
        storageRaw: String,
        platform: LockedPlatform?,
        tierShort: String
    ): String {
        if (platform == null) {
            return storageRaw
        }
        return sanitizeStorageLocked(storageRaw, platform, tierShort)
    }

    private suspend fun callDirectAiApiWrappedTimeout(
        component: String,
        category: String
    ): OpenAiDto.JsonBuildRecommendation? {
        val result: OpenAiDto.JsonBuildRecommendation? = kotlinx.coroutines.withTimeoutOrNull(25_000) {
            AiBuildOrchestrator.requestBuildRecommendationsDirect(component, category)
        }
        return result
    }

    private fun extractAnyString(map: Map<String, Any?>?, keyCandidates: List<String>, fallback: String? = null): String? {
        if (map == null) return fallback
        for (k in keyCandidates) {
            val raw = map[k]
            if (raw != null) {
                val s = raw.toString().trim()
                if (s.isNotBlank()) return s
            }
        }
        val nameRaw = map["name"]?.toString()?.trim()
        if (nameRaw.isNullOrBlank()) return fallback
        val brandRaw = map["brand"]?.toString()?.trim()
        val modelRaw = map["model"]?.toString()?.trim()
        val sb = StringBuilder()
        if (!brandRaw.isNullOrBlank() && nameRaw.startsWith(brandRaw, ignoreCase = true).not()) sb.append(brandRaw).append(" ")
        sb.append(nameRaw)
        if (!modelRaw.isNullOrBlank() && !nameRaw.contains(modelRaw, ignoreCase = true)) sb.append(" ").append(modelRaw)
        return sb.toString().trim().ifBlank { fallback }
    }

    private fun extractFirstComponent(map: Map<String, Any?>?, category: String, defaultPart: String): String {
        val str = extractAnyString(map, listOf("name","model","display_name","full_name","canonical_name","label","part_name","id"))
        if (str.isNullOrBlank()) return defaultPart
        return sanitizeChooseOnlyOneGeneric(str, defaultPart)
    }

    private fun buildCatalogPartsFromExistingLocal(): List<OpenAiDto.JsonCatalogPart> {
        val out = mutableListOf<OpenAiDto.JsonCatalogPart>()
        var idx = 0
        data class CpuRow(val name: String, val tier: String, val tdp: Int)
        val cpus: List<CpuRow> = listOf(
            CpuRow("AMD Ryzen 5 7600 (AM5)", "entry", 65),
            CpuRow("AMD Ryzen 5 9600X (AM5)", "midrange", 65),
            CpuRow("AMD Ryzen 7 7800X3D (AM5)", "high-end", 120),
            CpuRow("AMD Ryzen 9 7950X (AM5)", "high-end", 170),
            CpuRow("AMD Ryzen 5 5600 (AM4)", "entry", 65),
            CpuRow("AMD Ryzen 7 5700X3D (AM4)", "midrange", 105),
            CpuRow("Intel Core i5-14400F (LGA1700)", "entry", 65),
            CpuRow("Intel Core i5-14600K (LGA1700)", "midrange", 125),
            CpuRow("Intel Core i7-14700K (LGA1700)", "high-end", 253),
            CpuRow("Intel Core Ultra 7 265K (LGA1851)", "high-end", 125),
            CpuRow("Intel Core Ultra 9 285K (LGA1851)", "high-end", 250)
        )
        for (r in cpus) {
            val socket = listOf("AM5","AM4","LGA1700","LGA1851").firstOrNull { r.name.contains(it, ignoreCase = true) } ?: "N/A"
            out += OpenAiDto.JsonCatalogPart(id = "cpu_${idx++}", name = r.name, category = "CPU", tier = r.tier, socket = socket, tdp = r.tdp)
        }
        data class GpuRow(val name: String, val tier: String, val tdp: Int)
        val gpus: List<GpuRow> = listOf(
            GpuRow("NVIDIA GeForce RTX 5050", "entry", 130),
            GpuRow("NVIDIA GeForce RTX 5060", "midrange", 170),
            GpuRow("NVIDIA GeForce RTX 5070", "midrange", 250),
            GpuRow("NVIDIA GeForce RTX 5080", "high-end", 320),
            GpuRow("NVIDIA GeForce RTX 5090", "high-end", 500),
            GpuRow("AMD Radeon RX 9060 XT", "midrange", 225),
            GpuRow("AMD Radeon RX 9070 XT", "high-end", 320),
            GpuRow("AMD Radeon RX 9070 XTX", "high-end", 400),
            GpuRow("AMD Radeon RX 9080 XTX", "high-end", 525),
            GpuRow("NVIDIA GeForce RTX 4060", "entry", 115),
            GpuRow("NVIDIA GeForce RTX 4070 Super", "midrange", 220),
            GpuRow("NVIDIA GeForce RTX 4080 Super", "high-end", 320),
            GpuRow("NVIDIA GeForce RTX 4090", "high-end", 450),
            GpuRow("AMD Radeon RX 7600", "entry", 165),
            GpuRow("AMD Radeon RX 7700 XT", "midrange", 245),
            GpuRow("AMD Radeon RX 7800 XT", "midrange", 263),
            GpuRow("AMD Radeon RX 7900 XTX", "high-end", 355),
            GpuRow("Intel Arc A380", "entry", 75),
            GpuRow("Intel Arc A750", "entry", 225),
            GpuRow("Intel Arc A770", "midrange", 225),
            GpuRow("Intel Arc B580", "midrange", 190)
        )
        for (r in gpus) {
            out += OpenAiDto.JsonCatalogPart(id = "gpu_${idx++}", name = r.name, category = "GPU", tier = r.tier, tdp = r.tdp)
        }
        data class MoboRow(val name: String, val tier: String, val chip: String, val socket: String)
        val mobos: List<MoboRow> = listOf(
            MoboRow("MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)", "midrange", "B650", "AM5"),
            MoboRow("ASUS ROG Crosshair X870E Hero (AM5 DDR5)", "high-end", "X870E", "AM5"),
            MoboRow("ASUS TUF GAMING B550-PLUS WIFI II (AM4 DDR4)", "midrange", "B550", "AM4"),
            MoboRow("ASUS ROG Crosshair X670E Hero (AM5 DDR5)", "high-end", "X670E", "AM5"),
            MoboRow("ASUS TUF GAMING B760M-PLUS WIFI (LGA1700 DDR5)", "midrange", "B760", "LGA1700"),
            MoboRow("ASUS ROG Maximus Z890 Hero (LGA1851 DDR5)", "high-end", "Z890", "LGA1851")
        )
        for (r in mobos) {
            val mem = if (r.socket == "AM4") "DDR4" else "DDR5"
            out += OpenAiDto.JsonCatalogPart(id = "mobo_${idx++}", name = r.name, category = "Motherboard", tier = r.tier, socket = r.socket, chipset = r.chip, memory_type = mem)
        }
        data class RamRow(val name: String, val tier: String, val mem: String)
        val rams: List<RamRow> = listOf(
            RamRow("16GB DDR4-3200 (2x8GB)", "entry", "DDR4"),
            RamRow("32GB DDR4-3600 (2x16GB)", "midrange", "DDR4"),
            RamRow("32GB DDR5-6000 (2x16GB)", "midrange", "DDR5"),
            RamRow("64GB DDR5-6400 (2x32GB)", "high-end", "DDR5"),
            RamRow("Lexar Thor DDR4-3200 16GB Kit (2x8GB)", "entry", "DDR4"),
            RamRow("Lexar Thor DDR5-6000 32GB Kit (2x16GB)", "midrange", "DDR5")
        )
        for (r in rams) {
            out += OpenAiDto.JsonCatalogPart(id = "ram_${idx++}", name = r.name, category = "RAM", tier = r.tier, memory_type = r.mem)
        }
        data class StorRow(val name: String, val tier: String)
        val storages: List<StorRow> = listOf(
            StorRow("1TB NVMe PCIe 4.0 SSD", "entry"),
            StorRow("2TB NVMe PCIe 4.0 SSD", "midrange"),
            StorRow("2TB NVMe PCIe 5.0 SSD (Crucial T705)", "high-end"),
            StorRow("4TB NVMe PCIe 5.0 SSD (Samsung 990 PRO EVO Plus)", "high-end")
        )
        for (r in storages) {
            out += OpenAiDto.JsonCatalogPart(id = "stor_${idx++}", name = r.name, category = "Storage", tier = r.tier)
        }
        data class PsuRow(val name: String, val tier: String, val w: Int)
        val psus: List<PsuRow> = listOf(
            PsuRow("550W 80+ Bronze", "entry", 550),
            PsuRow("650W 80+ Gold Full Modular", "midrange", 650),
            PsuRow("750W 80+ Gold Full Modular ATX 3.0", "midrange", 750),
            PsuRow("850W 80+ Platinum Full Modular ATX 3.1", "high-end", 850),
            PsuRow("1000W 80+ Platinum Full Modular ATX 3.1", "high-end", 1000)
        )
        for (r in psus) {
            out += OpenAiDto.JsonCatalogPart(id = "psu_${idx++}", name = r.name, category = "PSU", tier = r.tier, wattage = r.w)
        }
        data class CaseRow(val name: String, val tier: String)
        val cases: List<CaseRow> = listOf(
            CaseRow("Deepcool MATREXX 55 MESH 4F ARGB (ATX, Mid Tower)", "entry"),
            CaseRow("Corsair 4000D AIRFLOW (ATX, Mid Tower, Black)", "midrange"),
            CaseRow("Lian Li O11 Dynamic EVO (ATX, Mid Tower, White)", "high-end")
        )
        for (r in cases) {
            out += OpenAiDto.JsonCatalogPart(id = "case_${idx++}", name = r.name, category = "Case", tier = r.tier)
        }
        data class CoolRow(val name: String, val tier: String)
        val cools: List<CoolRow> = listOf(
            CoolRow("Stock CPU Cooler", "entry"),
            CoolRow("Noctua NH-U12S chromax.black (Tower Air Cooler)", "midrange"),
            CoolRow("Corsair iCUE H100i ELITE CAPELLIX XT (240mm AIO)", "high-end")
        )
        for (r in cools) {
            out += OpenAiDto.JsonCatalogPart(id = "cool_${idx++}", name = r.name, category = "Cooler", tier = r.tier)
        }
        return out
    }

    private fun buildAnchorComponent(component: String, category: String, tier: String, platform: LockedPlatform?): OpenAiDto.JsonAnchorComponent {
        val cat = category.ifBlank { RecommendationMockData.resolveCategory(component) }.ifBlank { "GPU" }
        val tdpGuess = when (cat.uppercase()) {
            "GPU" -> {
                when {
                    component.contains("4090", ignoreCase = true) || component.contains("5090", ignoreCase = true) || component.contains("9080", ignoreCase = true) -> 450
                    component.contains("4080", ignoreCase = true) || component.contains("5080", ignoreCase = true) || component.contains("9070", ignoreCase = true) -> 320
                    component.contains("7800 XT", ignoreCase = true) || component.contains("5070", ignoreCase = true) -> 260
                    else -> 130
                }
            }
            "CPU" -> {
                when {
                    component.contains("9 7950", ignoreCase = true) || component.contains("i9", ignoreCase = true) || component.contains("Ultra 9", ignoreCase = true) -> 170
                    component.contains("7 7800X3D", ignoreCase = true) || component.contains("i7", ignoreCase = true) || component.contains("Ultra 7", ignoreCase = true) -> 120
                    else -> 65
                }
            }
            else -> null
        }
        val memoryType = when {
            platform != null -> platform.ramGen.uppercase()
            component.contains("DDR5", ignoreCase = true) -> "DDR5"
            component.contains("DDR4", ignoreCase = true) -> "DDR4"
            else -> null
        }
        val socket = when {
            platform != null -> platform.socket.uppercase()
            component.contains("AM5", ignoreCase = true) -> "AM5"
            component.contains("AM4", ignoreCase = true) -> "AM4"
            component.contains("LGA1851", ignoreCase = true) -> "LGA1851"
            component.contains("LGA1700", ignoreCase = true) -> "LGA1700"
            else -> null
        }
        val tierNorm = tier.trim().lowercase().let {
            if (it == "mid") "midrange" else it
        }
        return OpenAiDto.JsonAnchorComponent(
            id = "anchor_${component.replace("""[\s\-_/()]+""".toRegex(), "_").lowercase()}_${System.currentTimeMillis() % 100000}",
            name = component,
            category = cat,
            tier = tierNorm,
            specs = OpenAiDto.JsonAnchorSpecs(
                socket = socket,
                memory_type = memoryType,
                tdp = tdpGuess,
                release_year = if (component.contains("5090", ignoreCase = true) || component.contains("9060", ignoreCase = true) || component.contains("RX 90", ignoreCase = true) || component.contains("RTX 50", ignoreCase = true) || component.contains("Z890", ignoreCase = true) || component.contains("X870", ignoreCase = true) || component.contains("Thor DDR5", ignoreCase = true)) 2025 else 2024
            )
        )
    }

    private fun buildTargetTiers(componentTier: String): List<String> {
        return when (componentTier.trim().lowercase()) {
            "high", "high-end" -> listOf("high-end")
            "mid", "midrange" -> listOf("midrange", "high-end")
            else -> listOf("entry", "midrange", "high-end")
        }
    }

    private suspend fun callDirectAiV2WrappedTimeoutOrNull(
        component: String,
        category: String,
        componentTier: String,
        detectedPlatform: LockedPlatform?
    ): OpenAiDto.JsonBuildsV2Wrapper? {
        return kotlinx.coroutines.withTimeoutOrNull(25_000) {
            val anchor = buildAnchorComponent(component, category, componentTier, detectedPlatform)
            val tiers = buildTargetTiers(componentTier)
            val catalog = buildCatalogPartsFromExistingLocal()
            AiBuildOrchestrator.requestBuildRecommendationsDirect_V2(component, category, anchor, tiers, catalog)
        }
    }

    private fun tierIndexFromLabelOrTier(tierText: String?): Int {
        val lower = tierText?.trim()?.lowercase().orEmpty()
        return when {
            lower.startsWith("entry") -> 0
            lower.startsWith("mid") -> 1
            lower.startsWith("high") -> 2
            else -> -1
        }
    }

    private fun convertDirectAiBuildsV2OrNull(
        wrapper: OpenAiDto.JsonBuildsV2Wrapper?,
        searchedComponent: String,
        category: String,
        detectedPlatform: LockedPlatform?,
        searchedComponentTier: String
    ): List<BuildRecommendation>? {
        val buildsIn = wrapper?.builds
        if (buildsIn.isNullOrEmpty()) return null
        val targetTiers = buildTargetTiers(searchedComponentTier)
        val targetSet = targetTiers.toSet()
        val outByIdx = hashMapOf<Int, BuildRecommendation>()
        val markerUnavail = "NOT AVAILABLE for this component tier"
        val hiddenPlaceholder = sharedHiddenTierPlaceholder()
        for (b in buildsIn) {
            val idx = tierIndexFromLabelOrTier(b.tier)
            if (idx < 0) continue
            val tierName = when (idx) {
                0 -> "Entry"
                1 -> "Midrange"
                else -> "High-End"
            }
            val tierShort = when (idx) {
                0 -> "entry"
                1 -> "mid"
                else -> "high"
            }
            val comps = b.components
            val defaultMobo = detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
            val defaultRam = detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"
            val defaultStorage = detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"
            val defaultPsu = "650W 80+ Gold Full Modular"
            val defaultCase = "Deepcool MATREXX 55 MESH 4F ARGB (ATX, Mid Tower, Black)"
            val defaultCooler = "Stock CPU Cooler (Included)"
            val cpuRaw = extractFirstComponent(comps?.cpu, "CPU", "AMD Ryzen 5 5600")
            val gpuRaw = extractFirstComponent(comps?.gpu, "GPU", "NVIDIA GeForce RTX 4060")
            val moboRawPlatformSafe = platformSafeMoboOrOriginal(extractFirstComponent(comps?.motherboard, "Motherboard", defaultMobo), detectedPlatform, tierShort)
            val ramRawPlatformSafe = platformSafeRamOrOriginal(extractFirstComponent(comps?.ram, "RAM", defaultRam), detectedPlatform, tierShort)
            val storageRawPlatformSafe = platformSafeStorageOrOriginal(extractFirstComponent(comps?.storage, "Storage", defaultStorage), detectedPlatform, tierShort)
            val psuRawSanitize = extractFirstComponent(comps?.psu, "PSU", defaultPsu)
            val caseRaw = extractFirstComponent(comps?.`case`, "Case", defaultCase)
            val coolerRaw = extractFirstComponent(comps?.cooler, "Cooler", defaultCooler)
            var cpuOnlyOne = sanitizeChooseOnlyOneGeneric(cpuRaw, "AMD Ryzen 5 5600")
            if (fieldProbablyWrongCategory(cpuOnlyOne, searchedComponent, category, "cpu")) cpuOnlyOne = "AMD Ryzen 5 5600"
            var gpuOnlyOne = sanitizeChooseOnlyOneGeneric(gpuRaw, "NVIDIA GeForce RTX 4060")
            if (fieldProbablyWrongCategory(gpuOnlyOne, searchedComponent, category, "gpu")) gpuOnlyOne = "NVIDIA GeForce RTX 4060"
            var moboOnlyOne = sanitizeChooseOnlyOneGeneric(moboRawPlatformSafe, defaultMobo)
            if (fieldProbablyWrongCategory(moboOnlyOne, searchedComponent, category, "motherboard")) moboOnlyOne = defaultMobo
            var ramOnlyOne = sanitizeChooseOnlyOneGeneric(ramRawPlatformSafe, defaultRam)
            if (fieldProbablyWrongCategory(ramOnlyOne, searchedComponent, category, "ram")) ramOnlyOne = defaultRam
            var storageOnlyOne = sanitizeChooseOnlyOneGeneric(storageRawPlatformSafe, defaultStorage)
            if (fieldProbablyWrongCategory(storageOnlyOne, searchedComponent, category, "storage")) storageOnlyOne = defaultStorage
            var psuOnlyOne = sanitizeChooseOnlyOneGeneric(psuRawSanitize, defaultPsu)
            if (fieldProbablyWrongCategory(psuOnlyOne, searchedComponent, category, "psu")) psuOnlyOne = defaultPsu
            cpuOnlyOne = enforceCpuTierOnly(cpuOnlyOne, tierShort)
            moboOnlyOne = enforceMotherboardRealModelsOnly(moboOnlyOne, tierShort, detectedPlatform)
            val estimatedW = (b.estimated_total_draw_watts ?: 0).coerceAtLeast(0)
            psuOnlyOne = enforcePsuMinimumForGpu(psuOnlyOne, gpuOnlyOne, tierShort, searchedComponent, category, estimatedW)
            if (anyComponentPlaceholderDetected(cpuOnlyOne, gpuOnlyOne, moboOnlyOne, ramOnlyOne, storageOnlyOne, psuOnlyOne, caseRaw, coolerRaw, tierName)) {
                Log.w("V2_BUILD", "convertDirectAiBuildsV2OrNull($tierName): HARD REJECT due to NO-GENERIC-PLACEHOLDER client guard failing -> V2 invalid, caller will fallback V1")
                return null
            }
            val bnPct = (b.bottleneck_percent ?: 0.0).coerceIn(0.0, 100.0).toInt()
            val bnWarn = b.bottleneck_warning == true
            val compatCheck = b.compatibility_check
            val issues = compatCheck?.issues.orEmpty().filter { it.isNotBlank() }
            val notesText = b.notes.orEmpty().trim()
            val headroomText = b.psu_headroom_used.orEmpty().trim()
            val wattageNoteBuild = buildList<String> {
                if (estimatedW > 0) add("Estimated total draw: ${estimatedW}W")
                val recPsu = (b.recommended_psu_watts ?: 0).coerceAtLeast(0)
                if (recPsu > 0) add("Recommended PSU (${headroomText.ifBlank { "1.3x" }}): ${recPsu}W")
                if (issues.isNotEmpty()) add("Compat checks: " + issues.joinToString("; "))
                if (notesText.isNotBlank()) add(notesText)
            }.distinct().joinToString(" | ")
            val buildBase = BuildRecommendation(
                label = tierName.uppercase() + " BUILD",
                name = "$tierName Build for $searchedComponent",
                cpu = cpuOnlyOne,
                gpu = gpuOnlyOne,
                gpuNvidia = null,
                gpuAmd = null,
                motherboard = moboOnlyOne,
                ram = ramOnlyOne,
                storage = storageOnlyOne,
                psu = psuOnlyOne,
                wattage = "${estimatedW}W",
                wattageNote = wattageNoteBuild,
                bottleneck = "${bnPct}% -- " + (if (bnPct < 5) "Balanced" else if (estimatedW == 0 && bnPct == 0) "Balanced" else if (searchedComponent.trim().lowercase().contains("gpu") || category.lowercase().contains("gpu")) "GPU Bottleneck" else "CPU Bottleneck"),
                bottleneckNote = buildList<String> {
                    if (bnWarn) add("[WARN] Warning: Bottleneck ${bnPct}% exceeds 20% target (closest catalog option used).")
                    if (notesText.isNotBlank()) add(notesText)
                }.distinct().joinToString(" "),
                pcCase = caseRaw,
                cpuCooler = coolerRaw
            )
            val enforced = enforceComponentInBuild(buildBase, searchedComponent, category)
            val v = RecommendationMockData.validateBuildRecommendation(enforced)
            val guardPair = finalPlatformCompatibilityGuard(enforced, detectedPlatform)
            val compatStep = guardPair.first
            val warningsStep = guardPair.second
            val wattMerged = listOf(v.summaryLabel, compatStep.wattageNote, warningsStep.joinToString(" | "), buildBase.wattageNote).filter { it.isNotBlank() }.distinct().joinToString(" | ")
            val bnMerged = listOf(v.info.joinToString(" "), compatStep.bottleneckNote, buildBase.bottleneckNote).filter { it.isNotBlank() }.distinct().joinToString(" ")
            val postCompat = compatStep.copy(wattageNote = wattMerged, bottleneckNote = bnMerged.ifBlank { buildBase.bottleneckNote })
            val final = applyFinalBottleneckAndCpuSpecs(postCompat, searchedComponent, category, tierShort, detectedPlatform, searchedComponentTier)
            outByIdx[idx] = final.copy(label = "${tierName.uppercase()} BUILD")
        }
        val finalThree = mutableListOf<BuildRecommendation>()
        for (idx in 0..2) {
            val expectedTierLabel = when (idx) {
                0 -> "entry"
                1 -> "midrange"
                else -> "high-end"
            }
            val t = outByIdx[idx]
            val shouldShow = targetSet.contains(expectedTierLabel)
            if (shouldShow && t != null) {
                finalThree.add(t)
            } else if (shouldShow) {
                // Build a genuinely real fallback (never the sentinel) so a visible tier never
                // renders as "--" just because the AI omitted one tier.
                val catLower = category.trim().lowercase()
                val realBase = outByIdx[0] ?: outByIdx[1] ?: outByIdx[2]
                val cpuFallback = if (catLower.contains("cpu")) searchedComponent else "AMD Ryzen 5 5600"
                val gpuFallback = if (catLower.contains("gpu")) searchedComponent else "NVIDIA GeForce RTX 4060"
                val alt = (realBase ?: hiddenPlaceholder).copy(
                    label = if (idx == 0) "ENTRY BUILD" else if (idx == 1) "MIDRANGE BUILD" else "HIGH-END BUILD",
                    name = (if (idx == 0) "Entry" else if (idx == 1) "Midrange" else "High-End") + " Build for $searchedComponent",
                    cpu = cpuFallback,
                    gpu = gpuFallback,
                    motherboard = realBase?.motherboard ?: (detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"),
                    ram = realBase?.ram ?: (detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"),
                    storage = realBase?.storage ?: (detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"),
                    psu = realBase?.psu ?: "650W 80+ Gold Full Modular"
                )
                finalThree.add(alt)
            } else {
                finalThree.add(hiddenPlaceholder.copy(label = if (idx == 0) "ENTRY BUILD" else if (idx == 1) "MIDRANGE BUILD" else "HIGH-END BUILD", name = markerUnavail, cpu = markerUnavail, gpu = markerUnavail, motherboard = markerUnavail, ram = markerUnavail, storage = markerUnavail, psu = markerUnavail, wattage = "0W", bottleneck = "0% -- Balanced", bottleneckNote = ""))
            }
        }
        while (finalThree.size < 3) finalThree.add(hiddenPlaceholder.copy(label = if (finalThree.size == 1) "MIDRANGE BUILD" else "HIGH-END BUILD"))
        val catNorm = category.trim().lowercase()
        val searchedTierNorm = searchedComponentTier.trim().lowercase()
        val markerUnLower = markerUnavail.lowercase()
        val isGpuAscensionCase = (catNorm == "gpu" || catNorm.contains("gpu")) && searchedTierNorm in listOf("entry", "low")
        if (isGpuAscensionCase && finalThree.size >= 3) {
            val entryBuild = finalThree[0]
            val entryCpuLower = entryBuild.cpu.trim().lowercase()
            if (entryCpuLower != markerUnLower) {
                val cpuSpec = analyzeCpuSpecs(entryBuild.cpu.ifBlank { "AMD Ryzen 5 7600" })
                val socket = cpuSpec.socket.trim().uppercase()
                val ddrGen = if (cpuSpec.requiredRam.startsWith("DDR5")) "DDR5" else "DDR4"
                val ramCapGbEntry = Regex("""(\d+)\s*GB""", RegexOption.IGNORE_CASE).find(entryBuild.ram)?.groupValues?.get(1)?.toIntOrNull() ?: 16
                val storageCapEntry = runCatching {
                    val m = Regex("""(\d+)\s*(TB|GB)""", RegexOption.IGNORE_CASE).find(entryBuild.storage)
                    val num = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val unit = m?.groupValues?.get(2)?.uppercase() ?: "TB"
                    if (unit == "TB") num else 1
                }.getOrDefault(1)
                val psuWEntry = Regex("""(\d+)\s*W""", RegexOption.IGNORE_CASE).find(entryBuild.psu)?.groupValues?.get(1)?.toIntOrNull() ?: 550
                val pcieStorage = cpuSpec.maxPcie.let {
                    if (it.contains("5")) "5.0" else if (it.contains("4")) "4.0" else "3.0"
                }
                val midMoboSwap = when (socket) {
                    "AM5" -> "MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)"
                    "AM4" -> "ASUS TUF GAMING B550-PLUS WIFI II (AM4 DDR4)"
                    "LGA1700" -> "ASUS TUF GAMING B760M-PLUS WIFI (LGA1700 DDR5)"
                    "LGA1851" -> "ASUS ROG STRIX B860-F GAMING WIFI (LGA1851 DDR5)"
                    else -> entryBuild.motherboard
                }
                val highMoboSwap = when (socket) {
                    "AM5" -> "ASUS ROG Crosshair X670E Hero (AM5 DDR5)"
                    "AM4" -> "ASUS ROG Crosshair VIII Hero WIFI (AM4 DDR4)"
                    "LGA1700" -> "ASUS ROG Maximus Z790 Hero (LGA1700 DDR5)"
                    "LGA1851" -> "ASUS ROG Maximus Z890 Hero (LGA1851 DDR5)"
                    else -> midMoboSwap
                }
                val midRamCap = if (ramCapGbEntry >= 32) ramCapGbEntry else 32
                val midRamSpeed = when (ddrGen) {
                    "DDR5" -> if (midRamCap >= 64) "6400" else "6000"
                    else -> "3600"
                }
                val midRam = "${midRamCap}GB $ddrGen-$midRamSpeed 2x${midRamCap/2}GB"
                val midStorageCapTb = (storageCapEntry * 2).coerceAtLeast(2)
                val midStorage = "${midStorageCapTb}TB PCIe $pcieStorage NVMe SSD"
                val midPsuW = (psuWEntry + 100).coerceAtLeast(650)
                val midPsu = when (midPsuW) {
                    in 0..649 -> "650W 80+ Gold Full Modular"
                    in 650..799 -> "${midPsuW}W 80+ Gold Full Modular"
                    in 800..999 -> "${midPsuW}W 80+ Gold Full Modular ATX 3.0"
                    else -> "${midPsuW}W 80+ Platinum Full Modular ATX 3.1"
                }
                val highRamCap = (ramCapGbEntry * 2).coerceAtLeast(32).coerceAtMost(64)
                val highRamSpeed = when (ddrGen) {
                    "DDR5" -> if (highRamCap >= 64) "6400" else "6400"
                    else -> "3600"
                }
                val highRam = "${highRamCap}GB $ddrGen-$highRamSpeed 2x${highRamCap/2}GB"
                val highStorageCapTb = (midStorageCapTb * 2).coerceAtLeast(4)
                val highStorage = "${highStorageCapTb}TB PCIe $pcieStorage NVMe SSD"
                val highPsuW = (midPsuW + 100).coerceAtMost(1000)
                val highPsu = "${highPsuW}W 80+ Platinum Full Modular ATX 3.1"
                val existingMid = finalThree[1]
                if (existingMid.cpu.lowercase() != markerUnLower) {
                    val ascendedMidRaw = existingMid.copy(
                        cpu = entryBuild.cpu,
                        gpu = searchedComponent,
                        motherboard = midMoboSwap,
                        ram = midRam,
                        storage = midStorage,
                        psu = midPsu,
                        bottleneckNote = "GPU-tier ascension mid build: CPU preserved from entry build (avoids overkill flagship CPU), supporting components upgraded to midrange quality."
                    )
                    val ascendedMidFinal = applyFinalBottleneckAndCpuSpecs(ascendedMidRaw, searchedComponent, category, "mid", detectedPlatform, searchedComponentTier)
                    finalThree[1] = ascendedMidFinal
                }
                val existingHigh = finalThree[2]
                if (existingHigh.cpu.lowercase() != markerUnLower) {
                    val ascendedHighRaw = existingHigh.copy(
                        cpu = entryBuild.cpu,
                        gpu = searchedComponent,
                        motherboard = highMoboSwap,
                        ram = highRam,
                        storage = highStorage,
                        psu = highPsu,
                        bottleneckNote = "GPU-tier ascension high build: CPU preserved from entry build (no Ryzen 9 / i9 / Ultra 9 overkill -- entry GPU can't utilize), supporting components upgraded to premium high-end quality (X670E / X870E / Z790 / Z890 flagship mobo, 64GB RAM, 4TB PCIe $pcieStorage, ATX 3.1 Platinum PSU)."
                    )
                    val ascendedHighFinal = applyFinalBottleneckAndCpuSpecs(ascendedHighRaw, searchedComponent, category, "high", detectedPlatform, searchedComponentTier)
                    finalThree[2] = ascendedHighFinal
                }
            }
        }
        return finalThree.toList()
    }

    private suspend fun tryRunDirectAiLayerZero(
        component: String,
        category: String,
        detectedPlatform: LockedPlatform?,
        componentTier: String,
        tag: String
    ): List<BuildRecommendation>? {
        val hasKey = OpenAiApiService.hasApiKey()
        if (!hasKey) {
            Log.w(tag, "LAYER-0 DIRECT AI WARN: No OpenAI API key yet configured in local.properties (still blank / not starting with sk-). WILL STILL ATTEMPT API CALL (fails with 401 -> graceful fallthrough to L1/L2/L3). User priority: AI-first, NEVER skip straight to local mock fallback.")
        } else {
            Log.i(tag, "LAYER-0 DIRECT AI: OpenAI API key configured (starts with sk-) -- firing AI build recommendation call as USER #1 PRIORITY (AI = first, local fallback = only last resort)")
        }
        var effectiveTier = componentTier
        kotlinx.coroutines.withTimeoutOrNull(26_000) {
            runCatching {
                Log.i(tag, "LAYER-0 DIRECT AI V2 (Pc Scan engine): FIRING /w ANCHOR_COMPONENT object + TARGET_TIERS list + COMPONENT_CATALOG parts array + strict JSON wrapper = {builds:[...]}.")
                val v2Wrapper = callDirectAiV2WrappedTimeoutOrNull(component, category, componentTier, detectedPlatform)
                if (v2Wrapper != null) {
                    val convertedV2 = convertDirectAiBuildsV2OrNull(v2Wrapper, component, category, detectedPlatform, componentTier)
                    // A mid/high anchor intentionally hides its Entry slot (sentinel placeholder). Do NOT
                    // reject the AI result just because index 0 is a placeholder -- accept the run as long as
                    // at least one real build exists, since applyTierVisibilityFilter/finalizeVisibleTiers
                    // sanitize the visible slots afterwards.
                    val tierNormV2 = componentTier.trim().lowercase()
                    val anchorMidOrHighV2 = tierNormV2 == "mid" || tierNormV2 == "midrange" || tierNormV2.contains("high")
                    val hasRealMidOrHighV2 = convertedV2?.let { list ->
                        list.getOrNull(1)?.let { !it.name.startsWith("NOT AVAILABLE") } == true ||
                            list.getOrNull(2)?.let { !it.name.startsWith("NOT AVAILABLE") } == true
                    } ?: false
                    val entryIsPlaceholderV2 = convertedV2?.getOrNull(0)?.name?.startsWith("NOT AVAILABLE") == true
                    val v2Acceptable = convertedV2 != null && convertedV2.size == 3 &&
                        (!entryIsPlaceholderV2 || (anchorMidOrHighV2 && hasRealMidOrHighV2))
                    if (v2Acceptable) {
                        val b0 = convertedV2[0]; val b1 = convertedV2[1]; val b2 = convertedV2[2]
                        Log.i(tag, "LAYER-0 DIRECT AI V2 SUCCESS. [0]=${b0.label} CPU=${b0.cpu.take(25)} GPU=${b0.gpu.take(30)} [1]=${b1.label} CPU=${b1.cpu.take(25)} [2]=${b2.label} CPU=${b2.cpu.take(25)}.")
                        val b0GpuLower = b0.gpu.trim().lowercase()
                        val b1GpuLower = b1.gpu.trim().lowercase()
                        val b2GpuLower = b2.gpu.trim().lowercase()
                        if (category.trim().lowercase().contains("gpu")) {
                            val anchorLower = component.trim().lowercase()
                            val anchorOk0 = b0GpuLower == anchorLower || b0GpuLower.contains(anchorLower) || anchorLower.contains(b0GpuLower.take(10)) || b0.gpu.trim().equals(component.trim(), ignoreCase = true)
                            val anchorOk1 = b1GpuLower == anchorLower || b1GpuLower.contains(anchorLower) || anchorLower.contains(b1GpuLower.take(10)) || b1.gpu.trim().equals(component.trim(), ignoreCase = true)
                            val anchorOk2 = b2GpuLower == anchorLower || b2GpuLower.contains(anchorLower) || anchorLower.contains(b2GpuLower.take(10)) || b2.gpu.trim().equals(component.trim(), ignoreCase = true)
                            if (anchorOk0 && anchorOk1 && anchorOk2) Log.i(tag, "LAYER-0 DIRECT AI V2: ANCHOR LOCK (GPU) VERIFIED OK in all 3 builds.")
                            else Log.w(tag, "LAYER-0 DIRECT AI V2: ANCHOR LOCK discrepancy (expected GPU exact match in 3 builds) -- will pass through applyFinal layer which enforces final enforceComponentInBuild lock anyway.")
                        }
                        val filteredV2 = applyTierVisibilityFilter(convertedV2, componentTier, component)
                        return@withTimeoutOrNull filteredV2
                    } else {
                        Log.w(tag, "LAYER-0 DIRECT AI V2: parse/3-item check fail (size=${convertedV2?.size}, b0.name starts UNAV=${convertedV2?.getOrNull(0)?.name?.startsWith("NOT AVAILABLE")}) -> falling through to V1 prompt/chain.")
                    }
                }
            }.onFailure { t -> Log.e(tag, "LAYER-0 DIRECT AI V2 try-caught exception: ${t.javaClass.simpleName} ${t.message} -- will retry V1 prompt.", t) }
        }
        var aiJson: OpenAiDto.JsonBuildRecommendation? = null
        var rawAiOut: OpenAiDto.JsonBuildRecommendation? = null
        try {
            Log.i(tag, "LAYER-0 DIRECT AI V1 (legacy COMPLETE ENGINE 7 steps): FIRING gpt-5.6-luna response_format=json_object fallback.")
            rawAiOut = callDirectAiApiWrappedTimeout(component, category)
        } catch (t: Throwable) {
            Log.e(tag, "LAYER-0 DIRECT AI V1 EXCEPTION (requestBuildRecommendationsDirect): ${t.javaClass.simpleName} ${t.message}", t)
            return null
        }
        aiJson = rawAiOut
        if (aiJson == null) {
            Log.w(tag, "LAYER-0 DIRECT AI V1: null result from API (HTTP fail / parse fail / timeout >25s). Falling through to Layer-1 Render backend.")
            return null
        }
        val aiTierRaw = aiJson.detectedComponentTier.trim().lowercase()
        if (aiTierRaw == "entry" || aiTierRaw == "mid" || aiTierRaw == "high") {
            effectiveTier = aiTierRaw
        } else {
            effectiveTier = componentTier
        }
        var converted: List<BuildRecommendation> = emptyList()
        try {
            converted = convertDirectAiBuilds(aiJson, component, category, detectedPlatform, effectiveTier)
        } catch (t: Throwable) {
            Log.e(tag, "LAYER-0 DIRECT AI V1 EXCEPTION during convertDirectAiBuilds: ${t.javaClass.simpleName} ${t.message}", t)
            return null
        }
        val sizeConverted = converted.size
        if (sizeConverted != 3) {
            Log.w(tag, "LAYER-0 DIRECT AI V1: converted list size=$sizeConverted (must be exactly 3). Falling through Layer-1 Render.")
            return null
        }
        val b0 = converted[0]
        val firstBuildUnavailable = b0.name.startsWith("NOT AVAILABLE")
        // A mid/high anchor intentionally hides its Entry slot. Only treat the sentinel as a failure when
        // no real mid/high build exists to show; otherwise let the result through and let
        // applyTierVisibilityFilter/finalizeVisibleTiers sanitize the visible tiers.
        val effectiveTierNorm = effectiveTier.trim().lowercase()
        val anchorMidOrHigh = effectiveTierNorm == "mid" || effectiveTierNorm == "midrange" || effectiveTierNorm.contains("high")
        val hasRealMidOrHigh = converted.getOrNull(1)?.let { !it.name.startsWith("NOT AVAILABLE") } == true ||
            converted.getOrNull(2)?.let { !it.name.startsWith("NOT AVAILABLE") } == true
        if (firstBuildUnavailable && !(anchorMidOrHigh && hasRealMidOrHigh)) {
            Log.w(tag, "LAYER-0 DIRECT AI V1: returned first build = UNAVAILABLE sentinel with no real mid/high build. Bad AI output. Fall through Layer-1.")
            return null
        }
        val b1 = converted[1]
        val b2 = converted[2]
        Log.i(tag, "LAYER-0 DIRECT AI V1 SUCCESS: aiTier=$aiTierRaw effectiveTier=$effectiveTier. [0]=${b0.label} CPU=${b0.cpu.take(25)} [1]=${b1.label} CPU=${b1.cpu.take(25)} [2]=${b2.label} CPU=${b2.cpu.take(25)}.")
        val filtered = applyTierVisibilityFilter(converted, effectiveTier, component)
        return filtered
    }

    override suspend fun getBuildRecommendations(component: String): List<BuildRecommendation> {
        ensureSeedData()

        val tag = "BuildRecoAPI"
        val t0 = System.currentTimeMillis()
        val category = RecommendationMockData.resolveCategory(component)
        val detectedPlatform = detectPlatform(component, category)
        val componentTier = detectTier(component)
        Log.d(tag, "AI: [$component] (cat=$category platformDetected=${detectedPlatform?.name} tierDetected=$componentTier) -> 4-LAYER FALLBACK CHAIN")

        if (category.equals("cpu", true)) {
            val cpuAnchored = buildCpuAnchoredRecommendations(component, componentTier, detectedPlatform)
            if (cpuAnchored.size == 3) {
                val filteredCpuAnchored = applyTierVisibilityFilter(cpuAnchored, componentTier, component)
                val dt = System.currentTimeMillis() - t0
                Log.i(tag, "CPU-ANCHORED BUILDER HIT: returning 3 deterministic CPU-tier builds after ${dt}ms.")
                return filteredCpuAnchored
            }
        }

        val aiMaybe = tryRunDirectAiLayerZero(component, category, detectedPlatform, componentTier, tag)
        if (aiMaybe != null) {
            val dt = System.currentTimeMillis() - t0
            Log.i(tag, "FINAL EXIT LAYER-0 DIRECT AI HIT: returning 3-build list after ${dt}ms.")
            return aiMaybe
        }
        Log.i(tag, "LAYER-0 DIRECT AI SKIPPED / FAILED: Fall through to Layer-1 Render backend.")
        val layer1Job = kotlinx.coroutines.withTimeoutOrNull(20_000) {
            runCatching {
                Log.d(tag, "AI: NEW /api/build-recommendation endpoint path (HIGHEST PRIORITY) component=[$component]")
                val apiResponse = PcScanApiService.buildRecommendationWithFallback(
                    SearchApiDto.BuildRecoRequestDto(
                        componentName = component,
                        componentCategory = category.takeIf { it.isNotBlank() }
                    )
                )
                Log.d(tag, "TIMING: /api/build-recommendation HTTP code=${apiResponse.code()}")
                if (apiResponse.isSuccessful) {
                    val body = apiResponse.body()
                    if (body != null && body.success && body.entry != null && body.mid != null && body.high != null) {
                        fun mapTier(tier: SearchApiDto.BuildRecoTierBundleDto, tierLabel: String, tierName: String, tierShort: String): BuildRecommendation {
                            val onlyOneGpu = sanitizeGpuFieldOnlyOne(tier.gpuNvidia, tier.gpuAmd)
                            val safeMoboPlatformLocked = platformSafeMoboOrOriginal(tier.motherboard, detectedPlatform, tierShort)
                            val safeRamPlatformLocked = platformSafeRamOrOriginal(tier.ram, detectedPlatform, tierShort)
                            val safeStoragePlatformLocked = platformSafeStorageOrOriginal(tier.storage, detectedPlatform, tierShort)
                            // ALWAYS apply Choose-Only-One rule (remove "/" splits, pick first option) REGARDLESS of platform lock.
                            // This fixes PSU base category (detectedPlatform null) -> still 1 CPU/1 Mobo/1 RAM/1 Storage/1 PSU row.
                            val cpuOnlyOneRaw = sanitizeChooseOnlyOneGeneric(tier.cpu, "AMD Ryzen 5 5600")
                            val gpuOnlyOneRaw = sanitizeChooseOnlyOneGeneric(onlyOneGpu, "NVIDIA GeForce RTX 4060")
                            val moboOnlyOneRaw = sanitizeChooseOnlyOneGeneric(safeMoboPlatformLocked,
                                detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)")
                            val ramOnlyOneRaw = sanitizeChooseOnlyOneGeneric(safeRamPlatformLocked,
                                detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)")
                            val storageOnlyOneRaw = sanitizeChooseOnlyOneGeneric(safeStoragePlatformLocked,
                                detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD")
                            val psuOnlyOneRaw = sanitizeChooseOnlyOneGeneric(tier.psu, "650W 80+ Gold Full Modular")
                            // STRONG GUARD: Force default correct part if the field contains the user SEARCHED PSU/Mobo/RAM/Storage (anchor) MODEL in WRONG CATEGORY ROW!
                            // Example case: user searched PSU "MSI MAG A650GL" -> LLM hallucinated "MSI MAG A650GL" into motherboard row -> fieldProbablyWrongCategory returns true -> FORCE mobo to default B550 Tomahawk instead.
                            val defaultMoboFinal = detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
                            val defaultRamFinal = detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"
                            val defaultStorageFinal = detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"
                            val defaultPsuFinal = "650W 80+ Gold Full Modular"
                            var cpuOnlyOne = cpuOnlyOneRaw
                            if (fieldProbablyWrongCategory(cpuOnlyOneRaw, component, category, "cpu")) {
                                cpuOnlyOne = "AMD Ryzen 5 5600"
                            } else {
                                cpuOnlyOne = cpuOnlyOneRaw
                            }
                            var gpuOnlyOne = gpuOnlyOneRaw
                            if (fieldProbablyWrongCategory(gpuOnlyOneRaw, component, category, "gpu")) {
                                gpuOnlyOne = "NVIDIA GeForce RTX 4060"
                            } else {
                                gpuOnlyOne = gpuOnlyOneRaw
                            }
                            var moboOnlyOne = moboOnlyOneRaw
                            if (fieldProbablyWrongCategory(moboOnlyOneRaw, component, category, "motherboard")) {
                                moboOnlyOne = defaultMoboFinal
                            } else {
                                moboOnlyOne = moboOnlyOneRaw
                            }
                            var ramOnlyOne = ramOnlyOneRaw
                            if (fieldProbablyWrongCategory(ramOnlyOneRaw, component, category, "ram")) {
                                ramOnlyOne = defaultRamFinal
                            } else {
                                ramOnlyOne = ramOnlyOneRaw
                            }
                            var storageOnlyOne = storageOnlyOneRaw
                            if (fieldProbablyWrongCategory(storageOnlyOneRaw, component, category, "storage")) {
                                storageOnlyOne = defaultStorageFinal
                            } else {
                                storageOnlyOne = storageOnlyOneRaw
                            }
                            var psuOnlyOneRaw2 = psuOnlyOneRaw
                            if (fieldProbablyWrongCategory(psuOnlyOneRaw, component, category, "psu")) {
                                psuOnlyOneRaw2 = defaultPsuFinal
                            } else {
                                psuOnlyOneRaw2 = psuOnlyOneRaw
                            }
                            // ============== NEW ACCURACY PIPELINE (PER USER REQUEST 2026-09-02) ==============
                            // FIX 1 + FIX 6: CPU matches EXACT tier - midrange never Ryzen9 7950X
                            val cpuTierMatched = enforceCpuTierOnly(cpuOnlyOne, tierShort)
                            // FIX 3: MOTHERBOARD real brand model names, no Generic
                            val moboRealModel = enforceMotherboardRealModelsOnly(moboOnlyOne, tierShort, detectedPlatform)
                            // FIX 4 + 6: PSU MIN watts rule (NOW APPLIES BOTH: GPU tier minimum + estimatedWattage*1.30 headroom rule STRICT USER RULE #1)
                            var estimatedW = 0
                            try {
                                val wStr = tier.estimatedWattage?.toString()
                                val parsed = wStr?.toIntOrNull()
                                if (parsed != null) {
                                    estimatedW = parsed
                                } else {
                                    estimatedW = 0
                                }
                            } catch (t: Throwable) {
                                estimatedW = 0
                            }
                            val psuTierCorrect = enforcePsuMinimumForGpu(psuOnlyOneRaw2, gpuOnlyOne, tierShort, component, category, estimatedW)
                            val buildBase = BuildRecommendation(
                                label = tierLabel,
                                name = "$tierName Build for ${body.anchorComponent}",
                                cpu = cpuTierMatched,
                                gpu = gpuOnlyOne,
                                gpuNvidia = null,
                                gpuAmd = null,
                                motherboard = moboRealModel,
                                ram = ramOnlyOne,
                                storage = storageOnlyOne,
                                psu = psuTierCorrect,
                                wattage = "${tier.estimatedWattage}W",
                                wattageNote = "",
                                bottleneck = "${tier.bottleneckPct}%",
                                bottleneckNote = "CPU/GPU balance checked."
                            )
                            val enforced = enforceComponentInBuild(buildBase, component, category)
                            val v = RecommendationMockData.validateBuildRecommendation(enforced)
                            // USER STRICT RULES #2 + #3: FINAL PLATFORM COMPATIBILITY GUARD!
                            // (AUTO-CORRECT: DDR5 on AM4 / DDR4 on AM5 / wrong socket mobo etc) -> runs on EVERY build, detectedPlatform from user searched component category if 100% known.
                            val guardPair = finalPlatformCompatibilityGuard(enforced, detectedPlatform)
                            val compat = guardPair.first
                            val warnings = guardPair.second
                            val noteMerged = listOf(v.summaryLabel, compat.wattageNote, warnings.joinToString(" | ")).filter { it.isNotBlank() }.distinct().joinToString(" | ")
                            val bnMergeBuilder = mutableListOf<String>()
                            val vInfoJoined = v.info.joinToString(" ")
                            if (v.info.isNotEmpty()) {
                                bnMergeBuilder.add(vInfoJoined)
                            }
                            val compatBn = compat.bottleneckNote
                            if (compatBn.isNotBlank()) {
                                bnMergeBuilder.add(compatBn)
                            }
                            val bottleneckMerged = bnMergeBuilder.distinct().joinToString(" ")
                            val postCompat = compat.copy(
                                label = "${compat.label}".trim(),
                                wattageNote = noteMerged.trim(),
                                bottleneckNote = bottleneckMerged.trim().ifBlank { "CPU/GPU balance checked." }
                            )
                            val final = applyFinalBottleneckAndCpuSpecs(postCompat, component, category, tierShort, detectedPlatform, componentTier)
                            return final
                        }
                        val allTiers = listOf(
                            mapTier(body.entry, "ENTRY BUILD", "Entry", "entry"),
                            mapTier(body.mid, "MIDRANGE BUILD", "Midrange", "mid"),
                            mapTier(body.high, "HIGH-END BUILD", "High-End", "high")
                        )
                        // FINALIZE LABEL MATCH (rule 1): LABEL ALL UPPERCASE / NAME SENTENCE CASE
                        val allTiersFinal = allTiers.map { b -> finalizeLabelNameMatch(b, body.anchorComponent.ifBlank { component }) }
                        // === REVERTED BACK TO ORIGINAL LOGIC (USER SEPT 3 REQUEST):
                        //   RETURN ALL 3 BUILDS (ENTRY + MIDRANGE + HIGH-END) FULL LIST.
                        //   UI user switches via 3 tab buttons. NO forced single tier filter anymore!
                        Log.i(tag, "OK NEW /api/build-recommendation for [$component] platformLocked=${detectedPlatform?.name} -> returning FULL 3 TIERS (Entry/Midrange/High-End size=${allTiersFinal.size}).")
                        Log.i(tag, "SUCCESS: LAYER-1 /api/build-recommendation sanitized 3-tier return: [0]=${allTiersFinal[0].label} CPU=${allTiersFinal[0].cpu.take(30)} [1]=${allTiersFinal[1].label} CPU=${allTiersFinal[1].cpu.take(30)} [2]=${allTiersFinal[2].label} CPU=${allTiersFinal[2].cpu.take(30)}")
                        return@runCatching applyTierVisibilityFilter(allTiersFinal, componentTier, component)
                    } else {
                        Log.w(tag, "WARN: /api/build-recommendation success=${body?.success} tiers entry=${body?.entry != null} mid=${body?.mid != null} high=${body?.high != null} err=${body?.errorMessage}. Falling back to analyze-text then mock.")
                    }
                }
                null
            }.onFailure { e ->
                Log.e(tag, "FATAL: LAYER-1 FAILED with ${e.javaClass.simpleName}: ${e.message}. Cause: ${e.cause?.message ?: "unknown"}. WILL TRY LAYER-2 ANALYZE-TEXT FALLBACK.")
                Log.e(tag, "ERROR: /api/build-recommendation exception ${e.javaClass.simpleName}: ${e.message}. Falling back to analyze-text then mock.")
            }
        }
        if (layer1Job == null) {
            Log.w(tag, "TIMEOUT: LAYER-1 TIMEOUT after 20s. Backend unreachable or slow. Will try Layer-2 then Layer-3.")
        } else {
            val l1Result = layer1Job.getOrNull()
            if (l1Result != null) {
                return l1Result
            }
        }

        Log.w(tag, "FALLBACK-WARN: LAYER-2 HIT: analyze-text OLD GENERIC ENDPOINT fallback - Layer-1 probably failed.")
        val layer2Job = kotlinx.coroutines.withTimeoutOrNull(20_000) {
            runCatching {
                val apiResponse = PcScanApiService.analyzeTextWithFallback(
                    SearchApiDto.AnalyzeTextRequestDto(
                        text = component,
                        includeRecommendations = true,
                        includeUpgradePath = false,
                        buildGuidelines = """
BUILD RECOMMENDATION RULES (100% STRICT - ZERO DEVIATIONS):
0. ALL OUTPUT MUST BE 100% ENGLISH. ASCII ONLY. NO EMOJI. NO SPECIAL SYMBOLS. NO NON-ASCII. Use letters, digits, hyphens, colons, periods, parentheses only.

---- MANDATORY PRE-FLIGHT CHECK 0 (HIGHEST PRIORITY FIRST STEP BEFORE YOU WRITE ANYTHING ELSE): DETERMINE COMPONENT TIER -> ONLY 1 BUILD, MATCH THAT EXACT TIER.
FIRST: Classify '$component' into EXACTLY ONE TIER (Entry / Midrange / High-End). Internal only. Do not output this classification.
DETERMINE TIER USING KEYWORDS:
- Entry tier keywords: ryzen 3, ryzen 5 3600/4500/5500/5600 GT, gtx 1660, rtx 3050, rx 6500 xt, 500W bronze 550 650 bronze, b450, 8GB/16GB DDR4, pny cs900, wd green, kingston nv2, 512GB/1TB budget ssd
- Midrange tier keywords: ryzen 5 5600/7600, ryzen 7 5700X/7700, intel core i5-12400/13400/14400, core i5 ultra, rtx 4060/4060 ti/4070, rx 6600/6650 XT/6700 XT/7600/7700 XT, 750W 850W gold, b550/b650, b660/b760, 32GB ddr4/ddr5, samsung 980 pro 2TB, wd sn570/sn770/sn850x, crucial p3/p5 plus
- High-End tier keywords: ryzen 7 7800X3D, ryzen 9 7900X/7950X, intel core i7-13700K/14700K, core i9-12900K/13900K/14900K/KF/KS, core ultra 9, rtx 4080 super/4090/4090 ti, rx 7900 XTX/7900 GRE, 1000W 1200W 1600W platinum/titanium, x570/x670e/z790, 64GB/128GB ddr5, samsung 990 pro 4TB/8TB, t700 pcie 5, sabrent rocket 4 plus 8TB
IF UNCERTAIN: Default to Midrange tier.
FINAL LOCK: ONLY GENERATE EXACTLY ONE BUILD RECOMMENDATION (matching that detected component tier). NEVER create builds for other tiers. If component is Entry, output ONLY the Entry tier build (null or omit Mid/High). If component is Mid, output ONLY Mid build. If component is High-End, output ONLY High-End build. THIS IS NON-NEGOTIABLE. USER ONLY WANTS ONE TIER (THE SAME TIER AS THEIR SEARCHED COMPONENT).

---- MANDATORY PRE-FLIGHT CHECK 1 (BEFORE YOU WRITE ANY OUTPUT): DETERMINE AND LOCK THE PLATFORM GLOBALLY FIRST
FIRST: Detect which EXACT platform '$component' belongs to, using the 4 platform definitions below. Write it down internally (you don't need to output it - just LOCK it).
THIS PLATFORM LOCK APPLIES TO THE SINGLE BUILD YOU RETURN (matching detected tier only).
YOU ARE FORBIDDEN TO EVER MENTION, WRITE, OR INCLUDE ANY PART FROM A DIFFERENT PLATFORM, EVEN AS A SECOND OPTION.
IF INPUT = AMD AM4 CPU (example Ryzen 5 5600), THEN BUILD = AM4 MOTHERBOARDS ONLY + DDR4 RAM ONLY + PCIe 3/4 STORAGE ONLY. NEVER AM5. NEVER DDR5. NEVER LGA1700. NEVER MENTION THOSE WORDS.

---- MANDATORY PRE-FLIGHT CHECK 2 (BEFORE YOU WRITE ANY OUTPUT): EXACTLY 1 PART PER CATEGORY
EVERY CATEGORY BELOW (CPU / GPU / MOTHERBOARD / RAM / STORAGE / PSU) MUST CONTAIN EXACTLY ONE SINGLE MODEL NAME ONLY.
NEVER WRITE SLASH '/' SPLIT LISTS. NEVER TWO OPTIONS. NEVER 'NVIDIA RTX 4060 / AMD RX 7600'. NEVER 2 DIFFERENT MOTHERBOARDS. NEVER 2 DIFFERENT PSU MODELS.
GPU = CHOOSE ONLY ONE (either best-fitting NVIDIA OR best-fitting AMD, based on which offers best balanced performance/value for the tier).
If you think 2 GPUs are tied, PICK ONLY ONE - the NVIDIA option by default unless AMD is clearly better for that tier. JUST ONE MODEL.
REPEAT THIS RULE THREE TIMES BEFORE WRITING ANY COMPONENT LINE: CHOOSE ONLY ONE. CHOOSE ONLY ONE. CHOOSE ONLY ONE.

---- STEP 1: FINAL 1 BUILD TIER LOCK (ALREADY DONE IN PRE-FLIGHT 0)
Single build only. Return exactly one buildRecommendation for the detected tier only. Leave tiers not matching detected component as null or empty arrays. Do not generate other tiers.
  - Entry Tier = budget / basic performance
  - Mid-Range Tier = balanced overall performance
  - High-End Tier = flagship / maximum performance
This tier controls how many builds you return (see Step 2).
In ALL builds you return, '$component' MUST appear inserted into the correct category field AS-IS. DO NOT replace it. NEVER invent models. Only well-known mainstream production hardware.

---- STEP 2: TIER-DRIVEN BUILD MATRIX (EXACT):
  IF '$component' is Entry Tier -> return 2 builds: (1) Mid-Range build AND (2) High-End build. Do NOT return Entry build for the input component itself.
  IF '$component' is Mid-Range Tier -> return 1 build ONLY: High-End build. Nothing else.
  IF '$component' is High-End Tier -> return 1 build ONLY: best balanced High-End build (still includes '$component').
EXCEPTION: ONLY if the deterministic validation wrapper engine explicitly asks for 3 tiers (entry/mid/high) in the same call, you may return all three - otherwise follow the 1 or 2 tier rule above.

---- STEP 3: 4 PLATFORM DEFINITIONS (EXACT VERBATIM SPEC). APPLY THE LOCKED PLATFORM TO EVERY PART.
[PLATFORM 1 = AMD AM4 PLATFORM] -> lock if CPU/mobo is AMD AM4 (Ryzen 5 5600, Ryzen 7 5800X3D, A520, B450, B550, X470, X570 etc.)
  - CPU -> AMD AM4 processors only. CHOOSE ONLY ONE.
  - GPU -> NVIDIA OR AMD Radeon desktop discrete GPU. CHOOSE ONLY ONE (NOT BOTH).
  - MOTHERBOARD -> AMD AM4 socket ONLY, DDR4 slots ONLY. CHOOSE ONLY ONE.
  - RAM -> DDR4 ONLY. CHOOSE ONLY ONE.
  - STORAGE -> NVMe PCIe Gen 3 OR Gen 4 ONLY. CHOOSE ONLY ONE.
  - PSU -> CHOOSE ONLY ONE, sized with ~20% headroom above peak draw.
[PLATFORM 2 = AMD AM5 PLATFORM] -> lock if CPU/mobo is AMD AM5 (Ryzen 5 7600, Ryzen 7 7800X3D, B650, X670 etc.)
  - CPU -> AMD AM5 processors only. CHOOSE ONLY ONE.
  - GPU -> NVIDIA OR AMD Radeon desktop discrete GPU. CHOOSE ONLY ONE.
  - MOTHERBOARD -> AMD AM5 socket ONLY, DDR5 slots ONLY. CHOOSE ONLY ONE.
  - RAM -> DDR5 ONLY. CHOOSE ONLY ONE.
  - STORAGE -> NVMe PCIe Gen 4 OR Gen 5 ONLY. CHOOSE ONLY ONE.
  - PSU -> CHOOSE ONLY ONE, sized ~20% headroom.
[PLATFORM 3 = INTEL GEN 6 TO GEN 11 PLATFORM] -> lock if CPU is Intel desktop 6th gen up to and including 11th gen only (i5-6500 to i7-11700K; LGA 1151, B460, Z490, H510 etc.)
  - CPU -> Intel desktop Gen 6 through Gen 11 only. CHOOSE ONLY ONE.
  - GPU -> NVIDIA OR AMD Radeon desktop discrete GPU. CHOOSE ONLY ONE.
  - MOTHERBOARD -> matching LGA socket for Gen6-11 ONLY, DDR4 slots ONLY. CHOOSE ONLY ONE.
  - RAM -> DDR4 ONLY. CHOOSE ONLY ONE.
  - STORAGE -> NVMe PCIe Gen 3 OR Gen 4 ONLY. CHOOSE ONLY ONE.
  - PSU -> CHOOSE ONLY ONE, sized ~20% headroom.
[PLATFORM 4 = INTEL GEN 12 TO CORE ULTRA PLATFORM] -> lock if CPU is Intel Gen 12 / Gen 13 / Gen 14 OR ANY Intel Core Ultra series desktop processor (i5-12400, i7-13700K, i9-14900K, Ultra 5 245K, Ultra 7 265K, Ultra 9 285K; B760/Z790/Z890; LGA1700 / LGA1851)
  - CPU -> Intel desktop Gen 12 through 14 OR Intel Core Ultra only. CHOOSE ONLY ONE.
  - GPU -> NVIDIA OR AMD Radeon desktop discrete GPU. CHOOSE ONLY ONE.
  - MOTHERBOARD -> matching LGA socket for Gen12+/Ultra ONLY, DDR5 slots ONLY. CHOOSE ONLY ONE.
  - RAM -> DDR5 ONLY. CHOOSE ONLY ONE.
  - STORAGE -> NVMe PCIe Gen 4 OR Gen 5 ONLY. CHOOSE ONLY ONE.
  - PSU -> CHOOSE ONLY ONE, sized ~20% headroom.
PLATFORM FALLBACK (only when no CPU/mobo context: example input is an NVIDIA GPU alone): Assume the most popular balanced mainstream platform for that GPU class, then clearly state in bottleneckNote: "Platform was auto-inferred because only a GPU was provided; deterministic engine performs final socket and RAM generation validation."

---- STEP 4: PSU VALUE FORMAT (EXACT, NO EXTRA TEXT):
PSU component string (the one shown next to PSU icon) MUST contain:
  - EXACTLY: the PSU wattage number, uppercase letter W, OPTIONALLY the 80+ rating and brand name TEXT (this is allowed per component row rules, unlike earlier version). Examples CORRECT: "750W 80+ Gold", "EVGA 850W SuperNOVA 80+ Gold", "Corsair RM1000x 1000W 80+ Gold"
Wattage headroom rule: PSU wattage number must be ~20% above estimated peak system draw.
NO currency. NO prices. NO PHP anywhere.

---- STEP 5: BOTTLENECK ANALYSIS CONTENT (POPUP DIALOG TEXT):
Write clear plain-English bottleneck explanation into bottleneckNote field of EACH build you return. User taps card -> popup dialog shows this text. Rules:
  - NAME the ACTUAL limiting model, not just category. Example WRONG: "CPU bottleneck". Example CORRECT: "The Ryzen 5 5600 6-core CPU will bottleneck the RTX 4090 at 1080p and 1440p high settings. At 4K resolution the GPU becomes the primary limiter and this gap shrinks significantly."
  - Explain WHY limited (draw call latency / VRAM capacity / memory bandwidth / core count mismatch).
  - Distinguish 1080p vs 1440p vs 4K resolution behavior where CPU/GPU bottlenecks switch places.
  - Even if well-balanced (bottleneck < 5%): still write a short note confirming the match. NEVER leave empty.

---- HOUSEKEEPING FINAL CHECKLIST BEFORE RETURNING JSON:
[x] I locked 1 platform (1/2/3/4) globally and no part violates it.
[x] CPU field contains EXACTLY 1 model, no slash splits.
[x] GPU field contains EXACTLY 1 model (NVIDIA or AMD chosen by best fit, NEVER a slash list with both).
[x] MOTHERBOARD field EXACTLY 1 model, correct socket + DDR generation for the locked platform.
[x] RAM field EXACTLY 1 model, correct DDR generation only.
[x] STORAGE field EXACTLY 1 model NVMe, correct PCIe gen for the locked platform.
[x] PSU field EXACTLY 1 model, ~20% headroom wattage.
[x] TIER JSON KEYS = ENTRY / MIDRANGE / HIGH-END exactly.
[x] RETURN JSON DIRECTLY. No intro commentary. No markdown. No extra text after JSON.
[x] Balanced builds only: no absurd $50 CPU + $2500 GPU pairings, no vice versa. Build must be a realistic system a typical builder would assemble in 2025.
[x] '$component' was inserted verbatim into its matching category in every build returned (never replaced, never removed)
""".trimIndent()
                    )
                )
                val dtApi = System.currentTimeMillis() - t0
                Log.d(tag, "TIMING: [$component] -> API HTTP took ${dtApi}ms | code=${apiResponse.code()}")

                if (apiResponse.isSuccessful) {
                    val body = apiResponse.body()
                    val tierResult = body?.analysis?.buildRecommendations
                    if (tierResult != null) {
                        val rawEntry = tierResult.entry?.let(::mapTieredBuildToRecommendation)?.let { enforceComponentInBuild(it, component, category) }
                        val rawMid = tierResult.mid?.let(::mapTieredBuildToRecommendation)?.let { enforceComponentInBuild(it, component, category) }
                        val rawHigh = tierResult.high?.let(::mapTieredBuildToRecommendation)?.let { enforceComponentInBuild(it, component, category) }
                        val rawListRaw = listOfNotNull(rawEntry, rawMid, rawHigh)
                        val rawList = rawListRaw.map { build ->
                            val lowBuildLabel = build.label
                            val tierShort: String
                            if (lowBuildLabel.contains("ENTRY", true)) {
                                tierShort = "entry"
                            } else if (lowBuildLabel.contains("MID", true)) {
                                tierShort = "mid"
                            } else {
                                tierShort = "high"
                            }
                            val gpu1 = sanitizeGpuFieldOnlyOne(
                                build.gpuNvidia.takeIf { !it.isNullOrBlank() } ?: "",
                                build.gpuAmd.takeIf { !it.isNullOrBlank() } ?: build.gpu
                            )
                            val mbPlatformLocked = detectedPlatform?.let { sanitizeMotherboardLocked(build.motherboard, it, tierShort) } ?: build.motherboard
                            val ramPlatformLocked = detectedPlatform?.let { sanitizeRamLocked(build.ram, it, tierShort) } ?: build.ram
                            val storagePlatformLocked = detectedPlatform?.let { sanitizeStorageLocked(build.storage, it, tierShort) } ?: build.storage
                            // DEFAULTS
                            val defaultMoboFinal = detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
                            val defaultRamFinal = detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"
                            val defaultStorageFinal = detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"
                            val defaultPsuFinal = "650W 80+ Gold Full Modular"
                            val defaultCpuFinal = "AMD Ryzen 5 5600"
                            val defaultGpuFinal = "NVIDIA GeForce RTX 4060"
                            // 1) CHOOSE-ONLY-ONE remove slash splits
                            val cpuRaw = sanitizeChooseOnlyOneGeneric(build.cpu, defaultCpuFinal)
                            val gpuRaw = sanitizeChooseOnlyOneGeneric(gpu1, defaultGpuFinal)
                            val moboRaw = sanitizeChooseOnlyOneGeneric(mbPlatformLocked, defaultMoboFinal)
                            val ramRaw = sanitizeChooseOnlyOneGeneric(ramPlatformLocked, defaultRamFinal)
                            val storageRaw = sanitizeChooseOnlyOneGeneric(storagePlatformLocked, defaultStorageFinal)
                            val psuRaw = sanitizeChooseOnlyOneGeneric(build.psu, defaultPsuFinal)
                            // 2) STRONG WRONG CATEGORY GUARD (if PSU model name ended up in mobo/RAM/storage rows -> force defaults)
                            val cpuOnlyRaw = if (fieldProbablyWrongCategory(cpuRaw, component, category, "cpu")) defaultCpuFinal else cpuRaw
                            val gpuOnlyRaw = if (fieldProbablyWrongCategory(gpuRaw, component, category, "gpu")) defaultGpuFinal else gpuRaw
                            val moboOnlyRaw = if (fieldProbablyWrongCategory(moboRaw, component, category, "motherboard")) defaultMoboFinal else moboRaw
                            val ramOnlyRaw = if (fieldProbablyWrongCategory(ramRaw, component, category, "ram")) defaultRamFinal else ramRaw
                            val storageOnlyRaw = if (fieldProbablyWrongCategory(storageRaw, component, category, "storage")) defaultStorageFinal else storageRaw
                            val psuOnlyPre = if (fieldProbablyWrongCategory(psuRaw, component, category, "psu")) defaultPsuFinal else psuRaw
                            // 3) NEW ACCURACY PIPELINE 2026-09-02: CPU MIDRANGE=No Ryzen9 / Mobo no Generic / PSU min watts RTX5090=850W
                            val cpuTierOk = enforceCpuTierOnly(cpuOnlyRaw, tierShort)
                            val moboReal = enforceMotherboardRealModelsOnly(moboOnlyRaw, tierShort, detectedPlatform)
                            val psuTierOk = enforcePsuMinimumForGpu(psuOnlyPre, gpuOnlyRaw, tierShort, component, category)
                            build.copy(
                                cpu = cpuTierOk, gpu = gpuOnlyRaw, gpuNvidia = null, gpuAmd = null,
                                motherboard = moboReal, ram = ramOnlyRaw, storage = storageOnlyRaw, psu = psuTierOk
                            )
                        }

                        val validated = rawList.map { build ->
                            val v = RecommendationMockData.validateBuildRecommendation(build)
                            val summary = v.summaryLabel
                            val newNote = buildString {
                                append(build.wattageNote)
                                if (isNotBlank()) append(" ")
                                append(summary)
                                if (v.criticalIssues.isNotEmpty()) {
                                    append(" | CRITICAL: ").append(v.criticalIssues.joinToString("; "))
                                }
                                if (v.warnings.isNotEmpty()) {
                                    append(" | WARN: ").append(v.warnings.take(2).joinToString("; "))
                                }
                            }
                            val newBnNote = buildString {
                                append(build.bottleneckNote)
                                if (v.info.isNotEmpty()) {
                                    append(" ").append(v.info.joinToString(" "))
                                }
                            }
                            build.copy(
                                label = "${build.label} $summary",
                                wattageNote = newNote.trim(),
                                bottleneckNote = newBnNote.trim()
                            )
                        }
                        // L2 FINALIZE LABEL+NAME consistency (rule 1): LABEL ALL UPPERCASE, NAME SENTENCE CASE = EXACT MATCH
                        val validatedFinal = validated.map { b -> finalizeLabelNameMatch(b, component) }
                        // NEW USER STRICT RULES (Sept 3): Apply to each 3 build: RULE #1 PSU headroom 30% + RULE #2/#3 final platform compatibility guard (auto-correct any remaining DDR5 on AM4 etc)
                        val tierShortL2 = listOf("entry", "mid", "high")
                        val validatedFinalRulesApplied = validatedFinal.mapIndexed { idx, build ->
                            val tierS = if (tierShortL2.size > idx) tierShortL2[idx] else "mid"
                            // Extract estimated wattage from "310W" regex
                            val estW = Regex("(\\d{3,4})\\s*w", RegexOption.IGNORE_CASE).find(build.wattage)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            // Rule 1: PSU headroom 30% + GPU tier minimum
                            val psuOk = enforcePsuMinimumForGpu(build.psu, build.gpu, tierS, component, category, estW)
                            val buildAfterPsu = build.copy(psu = psuOk)
                            // Rules 2 + 3: final platform compat guard AUTO-CORRECT any incompats
                            val (compat, warnings) = finalPlatformCompatibilityGuard(buildAfterPsu, detectedPlatform)
                            val noteMerged = listOf(build.wattageNote, compat.wattageNote, warnings.joinToString(" | ")).filter { it.isNotBlank() }.distinct().joinToString(" | ")
                            val step = compat.copy(wattageNote = noteMerged.trim())
                            // NEW STEP 3: Consistent bottleneck calc + CPU specs + tier minimum standards across ALL layers
                            applyFinalBottleneckAndCpuSpecs(step, component, category, tierS, detectedPlatform, componentTier)
                        }
                        // === REVERTED BACK TO ORIGINAL LOGIC (USER SEPT 3 REQUEST):
                        //   RETURN ALL 3 BUILDS (ENTRY + MIDRANGE + HIGH-END) FULL LIST (not filtered 1 tier only).
                        //   User can switch via 3 tab UI buttons.
                        if (validatedFinalRulesApplied.size >= 3) {
                            Log.i(tag, "OK LAYER-2 [$component] FULL 3 TIERS returned (size=${validatedFinalRulesApplied.size}). [0]=${validatedFinalRulesApplied[0].label} [1]=${validatedFinalRulesApplied[1].label} [2]=${validatedFinalRulesApplied[2].label} (platform rules applied).")
                            return@runCatching applyTierVisibilityFilter(validatedFinalRulesApplied, componentTier, component)
                        } else {
                            Log.w(tag, "WARN: [$component] -> Layer2 analyze text returned <3 tiers (size=${validatedFinalRulesApplied.size}). Falling back to Mock fallback for complete 3 build list.")
                        }
                    } else {
                        Log.w(tag, "WARN: [$component] -> API successful but analysis.buildRecommendations is NULL. body.analysis=${body?.analysis?.analysisSummary}. Falling back.")
                    }
                } else {
                    val errBody = runCatching { apiResponse.errorBody()?.string() }.getOrDefault("")
                    Log.e(tag, "FAIL: [$component] -> API failed code=${apiResponse.code()} body=$errBody. Falling back to Mock.")
                }
                null
            }.onFailure { e ->
                Log.e(tag, "FATAL: LAYER-2 FAILED: ${e.javaClass.simpleName}: ${e.message}. WILL USE LAYER-3 MOCK FALLBACK.")
                Log.e(tag, "EXCEPTION: [$component] -> API exception (${e.javaClass.simpleName}): ${e.message}. Falling back to Mock.")
            }
        }
        if (layer2Job == null) {
            Log.w(tag, "TIMEOUT: LAYER-2 TIMEOUT after 20s. Backend unreachable or slow. Will try Layer-3.")
        } else {
            val l2Result = layer2Job.getOrNull()
            if (l2Result != null) {
                return l2Result
            }
        }

        Log.wtf(tag, "FALLBACK: LAYER-3 HIT: MOCK / LOCAL HARDCODED FALLBACK - backend is probably DOWN or unreachable! Both Layer-1 and Layer-2 API calls FAILED.")
        Log.i(tag, "OK3 [$component] -> MOCK FALLBACK (with component enforcement + deterministic validation).")
        val raw = fallback.getBuildRecommendations(component)
        val sanitizedMock = raw.map { build ->
            val gpu1 = sanitizeGpuFieldOnlyOne(
                build.gpuNvidia.takeIf { !it.isNullOrBlank() } ?: "",
                build.gpuAmd.takeIf { !it.isNullOrBlank() } ?: build.gpu
            )
            val buildLabelLow = build.label
            val tierShort: String
            if (buildLabelLow.contains("ENTRY", true)) {
                tierShort = "entry"
            } else if (buildLabelLow.contains("MID", true)) {
                tierShort = "mid"
            } else {
                tierShort = "high"
            }
            val mbPlatformLocked = detectedPlatform?.let { sanitizeMotherboardLocked(build.motherboard, it, tierShort) } ?: build.motherboard
            val ramPlatformLocked = detectedPlatform?.let { sanitizeRamLocked(build.ram, it, tierShort) } ?: build.ram
            val storagePlatformLocked = detectedPlatform?.let { sanitizeStorageLocked(build.storage, it, tierShort) } ?: build.storage
            // DEFAULTS
            val defaultMoboFinal = detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
            val defaultRamFinal = detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"
            val defaultStorageFinal = detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"
            val defaultPsuFinal = "650W 80+ Gold Full Modular"
            val defaultCpuFinal = "AMD Ryzen 5 5600"
            val defaultGpuFinal = "NVIDIA GeForce RTX 4060"
            // 1) CHOOSE-ONLY-ONE remove slash splits
            val cpuRaw = sanitizeChooseOnlyOneGeneric(build.cpu, defaultCpuFinal)
            val gpuRaw = sanitizeChooseOnlyOneGeneric(gpu1, defaultGpuFinal)
            val moboRaw = sanitizeChooseOnlyOneGeneric(mbPlatformLocked, defaultMoboFinal)
            val ramRaw = sanitizeChooseOnlyOneGeneric(ramPlatformLocked, defaultRamFinal)
            val storageRaw = sanitizeChooseOnlyOneGeneric(storagePlatformLocked, defaultStorageFinal)
            val psuRaw = sanitizeChooseOnlyOneGeneric(build.psu, defaultPsuFinal)
            // 2) STRONG WRONG CATEGORY GUARD (PSU model name ended up in mobo/RAM/storage rows -> force correct default parts)
            val cpuOnlyPre = if (fieldProbablyWrongCategory(cpuRaw, component, category, "cpu")) defaultCpuFinal else cpuRaw
            val gpuOnlyPre = if (fieldProbablyWrongCategory(gpuRaw, component, category, "gpu")) defaultGpuFinal else gpuRaw
            val moboOnlyPre = if (fieldProbablyWrongCategory(moboRaw, component, category, "motherboard")) defaultMoboFinal else moboRaw
            val ramOnlyPre = if (fieldProbablyWrongCategory(ramRaw, component, category, "ram")) defaultRamFinal else ramRaw
            val storageOnlyPre = if (fieldProbablyWrongCategory(storageRaw, component, category, "storage")) defaultStorageFinal else storageRaw
            val psuOnlyRaw = if (fieldProbablyWrongCategory(psuRaw, component, category, "psu")) defaultPsuFinal else psuRaw
            // 3) NEW ACCURACY PIPELINE 2026-09-02: midrange NO Ryzen9 7950X / Mobo REAL models / PSU MIN watts RTX5090 850W
            val cpuOk = enforceCpuTierOnly(cpuOnlyPre, tierShort)
            val moboOk = enforceMotherboardRealModelsOnly(moboOnlyPre, tierShort, detectedPlatform)
            val psuOk = enforcePsuMinimumForGpu(psuOnlyRaw, gpuOnlyPre, tierShort, component, category)
            build.copy(
                cpu = cpuOk, gpu = gpuOnlyPre, gpuNvidia = null, gpuAmd = null,
                motherboard = moboOk, ram = ramOnlyPre, storage = storageOnlyPre, psu = psuOk
            )
        }
        val mapped = sanitizedMock.map { build ->
            val enforced = enforceComponentInBuild(build, component, category)
            val v = RecommendationMockData.validateBuildRecommendation(enforced)
            val summary = v.summaryLabel
            val newNote = buildString {
                append(enforced.wattageNote)
                if (isNotBlank()) append(" ")
                append(summary)
                if (v.criticalIssues.isNotEmpty()) append(" | CRITICAL: ").append(v.criticalIssues.joinToString("; "))
                if (v.warnings.isNotEmpty()) append(" | WARN: ").append(v.warnings.take(2).joinToString("; "))
            }
            val newBnNote = buildString {
                append(enforced.bottleneckNote)
                if (v.info.isNotEmpty()) append(" ").append(v.info.joinToString(" "))
            }
            enforced.copy(
                label = "${enforced.label} $summary",
                wattageNote = newNote.trim(),
                bottleneckNote = newBnNote.trim()
            )
        }
        // L3 FINALIZE LABEL+NAME consistency (rule 1): ALL UPPERCASE label, sentence case title exactly match
        val mappedFinal = mapped.map { b -> finalizeLabelNameMatch(b, component) }
        // NEW USER STRICT RULES (Sept 3): Apply RULE #1 PSU 30% headroom + RULE #2/#3 platform compat guard to each 3 mock build
        val tierShortL3 = listOf("entry", "mid", "high")
        val mappedFinalRulesApplied = mappedFinal.mapIndexed { idx, build ->
            val tierS = if (tierShortL3.size > idx) tierShortL3[idx] else "mid"
            val estW = Regex("(\\d{3,4})\\s*w", RegexOption.IGNORE_CASE).find(build.wattage)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val psuOk = enforcePsuMinimumForGpu(build.psu, build.gpu, tierS, component, category, estW)
            val buildAfterPsu = build.copy(psu = psuOk)
            val (compat, warnings) = finalPlatformCompatibilityGuard(buildAfterPsu, detectedPlatform)
            val noteMerged = listOf(build.wattageNote, compat.wattageNote, warnings.joinToString(" | ")).filter { it.isNotBlank() }.distinct().joinToString(" | ")
            val step = compat.copy(wattageNote = noteMerged.trim())
            // NEW STEP 3: Consistent bottleneck calc + CPU specs + tier minimum standards across ALL layers
            applyFinalBottleneckAndCpuSpecs(step, component, category, tierS, detectedPlatform, componentTier)
        }
        // === REVERTED BACK TO ORIGINAL LOGIC (USER SEPT 3 REQUEST):
        //   RETURN ALL 3 BUILDS (ENTRY + MIDRANGE + HIGH-END) FULL LIST from mock hardcoded data.
        //   User can switch via 3 tab UI buttons at the top of the build card.
        Log.i(tag, "OK LAYER-3 MOCK FALLBACK [$component] -> FULL 3 TIERS returned (size=${mappedFinalRulesApplied.size}). [0]=${mappedFinalRulesApplied[0].label} [1]=${mappedFinalRulesApplied[1].label} [2]=${mappedFinalRulesApplied[2].label} (strict platform + PSU rules applied).")
        val filtered = applyTierVisibilityFilter(mappedFinalRulesApplied, componentTier, component)
        if (filtered.size < 3) {
            Log.w(tag, "PROXY FALLBACK TRIGGERED: Layer-3 returned only ${filtered.size} builds < 3 for [$component]. Using tier-equivalent proxy component.")
            val proxy = tierEquivalentProxyComponent(category, componentTier)
            Log.i(tag, "PROXY FALLBACK: category=$category tier=$componentTier -> using proxy component=[$proxy] as template.")
            val proxyRaw = fallback.getBuildRecommendations(proxy)
            val proxySanitized = proxyRaw.map { build ->
                val gpu1 = sanitizeGpuFieldOnlyOne(
                    build.gpuNvidia.takeIf { !it.isNullOrBlank() } ?: "",
                    build.gpuAmd.takeIf { !it.isNullOrBlank() } ?: build.gpu
                )
                val buildLabelLow = build.label
                val tierShort: String
                if (buildLabelLow.contains("ENTRY", true)) {
                    tierShort = "entry"
                } else if (buildLabelLow.contains("MID", true)) {
                    tierShort = "mid"
                } else {
                    tierShort = "high"
                }
                val mbPlatformLocked = detectedPlatform?.let { sanitizeMotherboardLocked(build.motherboard, it, tierShort) } ?: build.motherboard
                val ramPlatformLocked = detectedPlatform?.let { sanitizeRamLocked(build.ram, it, tierShort) } ?: build.ram
                val storagePlatformLocked = detectedPlatform?.let { sanitizeStorageLocked(build.storage, it, tierShort) } ?: build.storage
                val defaultMoboFinal = detectedPlatform?.defaultMotherboard ?: "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)"
                val defaultRamFinal = detectedPlatform?.defaultRam ?: "32GB DDR4-3600 (2x16GB)"
                val defaultStorageFinal = detectedPlatform?.defaultStorage ?: "2TB NVMe PCIe 4.0 SSD"
                val defaultPsuFinal = "650W 80+ Gold Full Modular"
                val defaultCpuFinal = "AMD Ryzen 5 5600"
                val defaultGpuFinal = "NVIDIA GeForce RTX 4060"
                val cpuF = if (category.trim().lowercase() == "cpu") component else (build.cpu.ifBlank { defaultCpuFinal })
                val gpuF = if (category.trim().lowercase() == "gpu") component else (gpu1.ifBlank { defaultGpuFinal })
                val mbF = if (category.trim().lowercase() == "motherboard") component else (mbPlatformLocked.ifBlank { defaultMoboFinal })
                val ramF = if (category.trim().lowercase() == "ram") component else (ramPlatformLocked.ifBlank { defaultRamFinal })
                val storageF = if (category.trim().lowercase() == "storage") component else (storagePlatformLocked.ifBlank { defaultStorageFinal })
                val psuF = if (category.trim().lowercase() == "psu") component else (build.psu.ifBlank { defaultPsuFinal })
                val interim = build.copy(
                    cpu = cpuF,
                    gpu = gpuF,
                    motherboard = mbF,
                    ram = ramF,
                    storage = storageF,
                    psu = psuF
                )
                enforceComponentInBuild(interim, component, category)
            }
            val proxyTiered = proxySanitized.mapIndexed { idx, build ->
                val tierShortL3P = listOf("entry", "mid", "high")
                val tierSP = if (tierShortL3P.size > idx) tierShortL3P[idx] else "mid"
                val estWP = Regex("(\\d{3,4})\\s*w", RegexOption.IGNORE_CASE).find(build.wattage)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val psuOkP = enforcePsuMinimumForGpu(build.psu, build.gpu, tierSP, component, category, estWP)
                val buildAfterPsuP = build.copy(psu = psuOkP)
                val (compatP, warningsP) = finalPlatformCompatibilityGuard(buildAfterPsuP, detectedPlatform)
                val noteMergedP = listOf(build.wattageNote, compatP.wattageNote, warningsP.joinToString(" | ")).filter { it.isNotBlank() }.distinct().joinToString(" | ")
                val stepP = compatP.copy(wattageNote = noteMergedP.trim())
                applyFinalBottleneckAndCpuSpecs(stepP, component, category, tierSP, detectedPlatform, componentTier)
            }
            val proxyFiltered = applyTierVisibilityFilter(proxyTiered, componentTier, component)
            Log.i(tag, "PROXY FALLBACK SUCCESS: Final ${proxyFiltered.size} builds for component=[$component] from proxy=[$proxy]. User's actual component preserved via enforceComponentInBuild swap.")
            return proxyFiltered
        }
        return filtered
    }

    private fun tierEquivalentProxyComponent(category: String, componentTier: String): String {
        val cat = category.trim().lowercase()
        val tier = componentTier.trim().lowercase()
        return when (cat) {
            "gpu" -> when (tier) {
                "high" -> "NVIDIA GeForce RTX 4090"
                "mid" -> "NVIDIA GeForce RTX 4070 Ti Super"
                else -> "NVIDIA GeForce RTX 4060"
            }
            "cpu" -> when (tier) {
                "high" -> "AMD Ryzen 7 7800X3D"
                "mid" -> "AMD Ryzen 7 5700X"
                else -> "AMD Ryzen 5 5600"
            }
            "motherboard" -> when (tier) {
                "high" -> "MSI MPG X670E CARBON WIFI (AM5)"
                "mid" -> "MSI MAG B650 TOMAHAWK WIFI (AM5)"
                else -> "ASUS PRIME B550M-A (AM4)"
            }
            "ram" -> when (tier) {
                "high" -> "64GB DDR5-6400 (2x32GB)"
                "mid" -> "32GB DDR5-6000 (2x16GB)"
                else -> "32GB DDR4-3600 (2x16GB)"
            }
            "storage" -> when (tier) {
                "high" -> "4TB NVMe PCIe 5.0 SSD"
                "mid" -> "2TB NVMe PCIe 4.0 SSD"
                else -> "1TB NVMe PCIe 4.0 SSD"
            }
            "psu" -> when (tier) {
                "high" -> "1200W 80+ Platinum Full Modular ATX 3.0"
                "mid" -> "850W 80+ Gold Full Modular ATX 3.0"
                else -> "650W 80+ Gold Full Modular"
            }
            else -> when (tier) {
                "high" -> "AMD Ryzen 7 7800X3D"
                "mid" -> "AMD Ryzen 7 5700X"
                else -> "AMD Ryzen 5 5600"
            }
        }
    }

    /** Rank of a tier short-name: entry=0, mid=1, high=2. Unknown/blank names default to mid. */
    private fun tierRankOfShort(tierShort: String): Int = when (tierShort.trim().lowercase()) {
        "entry", "low" -> 0
        "mid", "midrange" -> 1
        "high", "high-end", "highend", "high end" -> 2
        else -> 1
    }

    /** Socket-aware CPU ascension target used for tiers ABOVE the searched CPU's detected tier.
     *  The searched CPU never changes; only higher tiers receive a genuinely stronger,
     *  socket-compatible CPU so each tier produces a DISTINCT build instead of a duplicate. */
    private fun cpuAscensionTargetForTier(socket: String, tierShort: String): String {
        val s = socket.trim().uppercase()
        val isHigh = tierShort.trim().lowercase() == "high"
        return when {
            s.contains("AM5") -> if (isHigh) "AMD Ryzen 7 7800X3D (8C/16T)" else "AMD Ryzen 5 7600X (6C/12T)"
            s.contains("AM4") -> if (isHigh) "AMD Ryzen 7 5700X3D (8C/16T)" else "AMD Ryzen 7 5700X (8C/16T)"
            s.contains("LGA1851") -> if (isHigh) "Intel Core Ultra 7 265K (20C/20T)" else "Intel Core Ultra 5 245K (14C/14T)"
            s.contains("LGA1700") -> if (isHigh) "Intel Core i7-14700KF (20C/28T)" else "Intel Core i5-13600KF (14C/20T)"
            s.contains("LGA1151") -> if (isHigh) "Intel Core i7-9700K (8C/8T)" else "Intel Core i5-9400F (6C/6T)"
            else -> if (isHigh) "AMD Ryzen 7 7800X3D (8C/16T)" else "AMD Ryzen 7 5700X (8C/16T)"
        }
    }

    private fun enforceComponentInBuild(build: BuildRecommendation, component: String, category: String): BuildRecommendation {
        if (category == "gpu") return build.copy(gpu = component)
        if (category == "cpu") return build.copy(cpu = component)
        if (category == "ram") return build.copy(ram = component)
        if (category == "motherboard") return build.copy(motherboard = component)
        if (category == "storage") return build.copy(storage = component)
        if (category == "psu") return build.copy(psu = component)
        return build
    }

    private fun enforceMinimumTierStandardsPerSearchedTier(
        build: BuildRecommendation,
        searchedComponentTier: String,
        slotTier: String,
        searchedCategory: String,
        detectedPlatform: FirestoreRecommendationRepository.LockedPlatform?
    ): BuildRecommendation {
        val searchedTier = searchedComponentTier.trim().lowercase()
        val slot = slotTier.trim().lowercase()
        val sCat = searchedCategory.trim().lowercase()
        val markerUnavail = "NOT AVAILABLE for this component tier"
        if (build.cpu.equals(markerUnavail, ignoreCase = true) || build.name.equals(markerUnavail, ignoreCase = true)) return build
        val gpuLow = build.gpu.trim().lowercase()
        val ramLow = build.ram.trim().lowercase()
        val moboLow = build.motherboard.trim().lowercase()
        val psuLow = build.psu.trim().lowercase()
        val storageLow = build.storage.trim().lowercase()
        val platformName = detectedPlatform?.name.orEmpty().lowercase()

        fun gpuBelow(minKeywords: List<String>): Boolean {
            return minKeywords.none { it in gpuLow }
        }

        fun ramBelowMinGb(minGb: Int): Boolean {
            val gb = Regex("""(\d+)\s*GB""", RegexOption.IGNORE_CASE).find(build.ram)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*GB""", RegexOption.IGNORE_CASE).find(build.ram)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val match = Regex("""(\d+)\s*GB""", RegexOption.IGNORE_CASE).find(build.ram)
            val cap = if (match != null) match.groupValues[1].toIntOrNull() ?: 0 else 0
            val useCap = if (cap > 0) cap else gb
            return useCap in 1 until minGb
        }

        fun psuBelowMinWatts(minW: Int): Boolean {
            val w = Regex("""(\d{3,4})\s*W""", RegexOption.IGNORE_CASE).find(build.psu)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return w in 1 until minW
        }

        fun storageBelowMinTb(minTb: Int): Boolean {
            val tb = Regex("""(\d+)\s*TB""", RegexOption.IGNORE_CASE).find(build.storage)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val gb = Regex("""(\d{3,4})\s*GB""", RegexOption.IGNORE_CASE).find(build.storage)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val actualTb = if (tb > 0) tb else (gb / 1000)
            return actualTb in 0 until minTb
        }

        val slotRank = when (slot) {
            "entry" -> 0
            "mid", "midrange" -> 1
            else -> 2
        }
        val searchedRank = when (searchedTier) {
            "entry" -> 0
            "mid", "midrange" -> 1
            else -> 2
        }
        val effectiveMinRank = maxOf(searchedRank - 1, 0)
        val minTierForParts = when (maxOf(slotRank, effectiveMinRank)) {
            0 -> "entry"
            1 -> "mid"
            else -> "high"
        }

        var gpuOut = build.gpu
        var ramOut = build.ram
        var moboOut = build.motherboard
        var psuOut = build.psu
        var storageOut = build.storage

        val gpuTierMin = listOf(
            listOf("1650", "3050", "6500", "6600", "4060", "7600"),
            listOf("4060", "4070", "6600 xt", "6700", "6750", "7700", "7800", "5060"),
            listOf("4070 ti", "4080", "4090", "5070 ti", "5080", "5090", "7800 xt", "7900 xt", "7900 xtx", "9060 xt", "9070 xt")
        )
        val gpuMinDefaults = listOf(
            "NVIDIA GeForce RTX 4060",
            "NVIDIA GeForce RTX 4070",
            "NVIDIA GeForce RTX 4090"
        )
        val tierIdx = when (minTierForParts) {
            "entry" -> 0
            "mid" -> 1
            else -> 2
        }
        if (sCat != "gpu" && gpuBelow(gpuTierMin[tierIdx])) {
            gpuOut = gpuMinDefaults[tierIdx]
        }

        val ramMinGb = when {
            searchedTier == "high" -> 32
            searchedTier == "mid" -> 32
            else -> 16
        }
        if (ramBelowMinGb(ramMinGb)) {
            val ddrVer = when {
                ramLow.contains("ddr5") -> "DDR5"
                ramLow.contains("ddr4") -> "DDR4"
                platformName.contains("am5") || platformName.contains("ultra") || platformName.contains("gen 12") || platformName.contains("gen12") -> "DDR5"
                else -> "DDR4"
            }
            val speed = when (ddrVer) {
                "DDR5" -> if (searchedTier == "high") "6400" else "6000"
                else -> if (searchedTier == "high") "3600" else "3200"
            }
            val kit = if (ramMinGb == 32) "2x16GB" else "2x8GB"
            ramOut = "$ramMinGb GB $ddrVer-$speed ($kit)"
        }

        val moboTierBelow: Boolean = when (minTierForParts) {
            "entry" -> false
            "mid" -> (listOf("ds3h", "b450m ds3h", "prime a520", "a520", "h610", "h770").any { it in moboLow })
            else -> (listOf("ds3h", "b650m ds3h", "b450m ds3h", "a520", "b550m", "b650m-a pro", "b760m ds3h", "h610", "h770", "b550 tomahawk", "b650 tomahawk", "b760m-a pro", "prime b550", "prime b760").any { it in moboLow })
        }
        if (moboTierBelow || (minTierForParts == "high" && moboLow.isBlank())) {
            val highEndMobos = listOf(
                "MSI MEG X670E ACE (AM5 DDR5)",
                "ASUS ROG CROSSHAIR X670E HERO (AM5 DDR5)",
                "Gigabyte X670E AORUS MASTER (AM5 DDR5)",
                "MSI MPG X670E CARBON WIFI (AM5 DDR5)",
                "ASUS ROG Strix X570-E Gaming WiFi II (AM4 DDR4)",
                "MSI MEG X570 ACE (AM4 DDR4)",
                "Gigabyte X570 AORUS MASTER (AM4 DDR4)",
                "ASUS ROG STRIX Z790-A GAMING WIFI II (LGA1700 DDR5)",
                "ASUS TUF GAMING Z790-PLUS WIFI (LGA1700 DDR5)"
            )
            val midMobos = listOf(
                "MSI MAG B650 TOMAHAWK WIFI (AM5 DDR5)",
                "ASUS TUF GAMING B650-PLUS WIFI (AM5 DDR5)",
                "MSI MAG B550 TOMAHAWK WIFI (AM4 DDR4)",
                "ASUS TUF GAMING B550-PLUS WIFI II (AM4 DDR4)",
                "MSI B760M-A PRO WIFI DDR5 (LGA1700)",
                "ASUS TUF GAMING Z790-PLUS WIFI (LGA1700 DDR5)"
            )
            val pickList = if (minTierForParts == "high") highEndMobos else midMobos
            val moboSocPreferred = when {
                platformName.contains("am5") -> pickList.filter { it.contains("AM5", ignoreCase = true) }
                platformName.contains("am4") -> pickList.filter { it.contains("AM4", ignoreCase = true) }
                platformName.contains("lga1700") || platformName.contains("ultra") || platformName.contains("gen 12") -> pickList.filter { it.contains("LGA1700", ignoreCase = true) }
                else -> emptyList()
            }
            moboOut = (moboSocPreferred.ifEmpty { pickList }).first()
        }

        val psuMinW = when {
            searchedTier == "high" && minTierForParts == "high" -> 1000
            searchedTier == "high" -> 850
            searchedTier == "mid" && minTierForParts == "high" -> 850
            searchedTier == "mid" -> 650
            else -> 550
        }
        val psuMinCert = when {
            searchedTier == "high" -> "Platinum"
            minTierForParts == "high" -> "Gold"
            else -> "Gold"
        }
        val psuCertBelow = if (psuMinCert == "Platinum") {
            !psuLow.contains("platinum") && !psuLow.contains("titanium")
        } else {
            !psuLow.contains("gold") && !psuLow.contains("platinum") && !psuLow.contains("titanium")
        }
        val psuWBelow = psuBelowMinWatts(psuMinW)
        if (sCat != "psu" && (psuWBelow || psuCertBelow || psuLow.isBlank())) {
            val certLabel = psuMinCert
            val modular = if (psuMinW >= 850) "Full Modular ATX 3.0" else "Full Modular"
            psuOut = "$psuMinW W 80+ $certLabel $modular"
        }

        val storageMinTb = when {
            searchedTier == "high" -> 2
            else -> 1
        }
        if (sCat != "storage" && storageBelowMinTb(storageMinTb)) {
            val pcieVer = when {
                platformName.contains("am5") || platformName.contains("ultra") || platformName.contains("gen 12") || searchedTier == "high" -> "PCIe 4.0"
                else -> "PCIe 4.0"
            }
            storageOut = "$storageMinTb TB NVMe $pcieVer SSD"
        }

        return build.copy(
            gpu = gpuOut,
            ram = ramOut,
            motherboard = moboOut,
            psu = psuOut,
            storage = storageOut
        )
    }

    private fun mapTieredBuildToRecommendation(tier: SearchApiDto.TieredBuildDto): BuildRecommendation {
        val hw = tier.hardware
        val tierKeyLower = tier.tier.lowercase()
        val tierLabel: String
        if (tierKeyLower == "entry") {
            tierLabel = "ENTRY BUILD"
        } else if (tierKeyLower == "mid") {
            tierLabel = "MIDRANGE BUILD"
        } else if (tierKeyLower == "high") {
            tierLabel = "HIGH-END BUILD"
        } else {
            tierLabel = tier.tier.replaceFirstChar { it.uppercase() }
        }

        var cleaned = tier.tierName
        val patternsToRemove = listOf(
            Regex("""\(PHP\s*\d[\d,]*\+?[^)]*\)""", RegexOption.IGNORE_CASE),
            Regex("""PHP\s*\d[\d,]*\+?\w*""", RegexOption.IGNORE_CASE),
            Regex("""\$\s*\d[\d,]*\+?\w*"""),
            Regex("""P\s*\d[\d,]*\+?\w*"""),
            Regex("""\(.*price.*\)""", RegexOption.IGNORE_CASE),
            Regex("""\(.*budget.*\)""", RegexOption.IGNORE_CASE),
            Regex("""\(.*cost.*\)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patternsToRemove) {
            cleaned = cleaned.replace(pattern, "")
        }
        val cleanedTierName = cleaned.replace(Regex("""\s+"""), " ").trim().ifBlank { "Custom Build" }

        val wattsText = buildString {
            val total = tier.totalSystemWatts
            val rec = tier.recommendedPsuWatts
            if (total != null && rec != null) {
                append("${total}W Total / ${rec}W Rec PSU")
            } else if (total != null) {
                append("${total}W Total")
            } else if (rec != null) {
                append("${rec}W Recommended PSU")
            } else {
                val hwPsu = hw.psu?.wattage
                if (hwPsu != null) append("${hwPsu}W") else append("See PSU")
            }
        }

        val wattsNote = buildString {
            val tip = tier.psuEfficiencyTip
            if (!tip.isNullOrBlank()) append(tip)
            val score = tier.compatibilityScore
            val compat = tier.isCompatible
            if (score != null || compat != null) {
                if (isNotEmpty()) append(" ")
                append("Compat: ")
                if (compat == true) append("OK") else append("WARN")
                if (score != null) append(" ${score.toInt()}/100")
            }
        }

        val bottleneckText = buildString {
            val hasBn = tier.hasBottleneck
            val comp = tier.bottleneckComponent
            val pct = tier.bottleneckPercentage
            when {
                hasBn == true && comp != null && pct != null -> {
                    append("${comp} @ ${String.format("%.0f", pct)}%")
                }
                hasBn == true && comp != null -> append(comp)
                hasBn == true -> append("Yes")
                else -> append("None detected")
            }
        }

        val bottleneckNote = buildString {
            val desc = tier.bottleneckDescription
            if (!desc.isNullOrBlank()) {
                append(desc)
            } else {
                append("Performance balanced across components.")
            }
        }

        return BuildRecommendation(
            label = tierLabel,
            name = cleanedTierName,
            cpu = hw.cpu?.name?.takeIf { it.isNotBlank() } ?: "TBD CPU",
            gpu = hw.gpu?.name?.takeIf { it.isNotBlank() } ?: "TBD GPU",
            motherboard = hw.motherboard?.name?.takeIf { it.isNotBlank() } ?: "TBD Motherboard",
            ram = hw.ram?.name?.takeIf { it.isNotBlank() } ?: "TBD RAM",
            storage = hw.storage?.name?.takeIf { it.isNotBlank() } ?: "TBD Storage",
            psu = hw.psu?.name?.takeIf { it.isNotBlank() } ?: "TBD PSU",
            wattage = wattsText,
            wattageNote = wattsNote,
            bottleneck = bottleneckText,
            bottleneckNote = bottleneckNote
        )
    }

    override suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean): UpgradePath {
        ensureSeedData()
        return runCatching {
            val category = RecommendationMockData.resolveCategory(component)
            val snapshot = firestore.collection(UPGRADE_PATHS_COLLECTION)
                .document(category)
                .get()
                .await()

            val key = if (hasExistingBuild) EXISTING_BUILD_FIELD else NEW_BUILD_FIELD
            snapshot.getMapValue(key)
                ?.let(::mapUpgradePath)
                ?: fallback.getUpgradePath(component, hasExistingBuild)
        }.getOrElse {
            fallback.getUpgradePath(component, hasExistingBuild)
        }
    }

    override suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean, tier: BuildTier): UpgradePath {
        ensureSeedData()
        return runCatching {
            fallback.getUpgradePath(component, hasExistingBuild, tier)
        }.getOrElse {
            fallback.getUpgradePath(component, hasExistingBuild, tier)
        }
    }

    override suspend fun getUpgradePath(
        searchedComponent: String,
        currentBuild: CurrentBuild,
        tier: BuildTier
    ): UpgradePath {
        ensureSeedData()
        return runCatching {
            fallback.getUpgradePath(searchedComponent, currentBuild, tier)
        }.getOrElse {
            fallback.getUpgradePath(searchedComponent, currentBuild, tier)
        }
    }

    override suspend fun getComponentDetails(componentName: String): ComponentDetail {
        ensureSeedData()
        return withContext(Dispatchers.IO) {
            val tag = "SearchSpecs"
            val t0 = System.currentTimeMillis()

            Log.d(tag, "SEARCH: [$componentName] -> LEVEL 1 PRIORITY: Backend API / OpenAI specs lookup...")
            runCatching {
                val tApiStart = System.currentTimeMillis()
                val response = PcScanApiService.analyzeTextWithFallback(
                    SearchApiDto.AnalyzeTextRequestDto(
                        text = componentName,
                        includeRecommendations = false,
                        includeUpgradePath = false
                    )
                )
                val tApiDone = System.currentTimeMillis() - tApiStart
                Log.d(tag, "TIMING: [$componentName] -> API HTTP took ${tApiDone}ms | code=${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    val mapped = ComponentDetailMapper.toComponentDetail(componentName, body)
                    val realSpecs = mapped?.specs?.filter { it.isNotBlank() }.orEmpty()
                    if (mapped != null && realSpecs.size >= 2) {
                        Log.i(tag, "OK1 [$componentName] -> API/OPENAI SUCCESS: \"${mapped.name}\" specs=$realSpecs (${tApiDone}ms)")
                        return@withContext mapped
                    } else {
                        Log.w(tag, "WARN: [$componentName] -> API OK but only ${realSpecs.size} real specs (need >=2); fallback to SmartGuesser. real=$realSpecs body=$body")
                    }
                } else {
                    val errBody = runCatching { response.errorBody()?.string() }.getOrDefault("")
                    Log.e(tag, "FAIL: [$componentName] -> API failed code=${response.code()} body=$errBody. Falling back to SmartGuesser.")
                }
            }.getOrElse { e ->
                Log.e(tag, "EXCEPTION: [$componentName] -> API exception (${e.javaClass.simpleName}): ${e.message}. Falling back to SmartGuesser.")
            }

            SmartQuerySpecGuesser.guess(componentName)?.let { guess ->
                val dt = System.currentTimeMillis() - t0
                Log.i(tag, "OK2 [$componentName] -> SMART GUESS FALLBACK in ${dt}ms: \"${guess.name}\" [${guess.category}] specs=${guess.specs}")
                return@withContext guess
            }
            Log.d(tag, "WARN: [$componentName] -> Smart Guesser miss. Trying LocalSpecDatabase fallback...")

            LocalSpecDatabase.find(componentName)?.let { hit ->
                val dt = System.currentTimeMillis() - t0
                Log.i(tag, "OK3 [$componentName] -> LOCAL DB FALLBACK in ${dt}ms: \"${hit.name}\" specs=${hit.specs}")
                return@withContext hit
            }
            Log.d(tag, "WARN: [$componentName] -> Local DB miss. Using mock fallback.")

            fallback.getComponentDetails(componentName).also {
                val dtTotal = System.currentTimeMillis() - t0
                Log.d(tag, "FINAL: [$componentName] -> FINAL \"${it.name}\" [${it.category}] score=${it.score} specs=${it.specs} (TOTAL ${dtTotal}ms)")
            }
        }
    }

    override suspend fun getDetectedComponentTier(component: String): String {
        val category = RecommendationMockData.resolveCategory(component)
        val baseTier = detectTier(component)
        return forceTierIfHighEndGpu(component, category, baseTier)
    }

    override suspend fun analyzeImageForComponents(imageBytes: ByteArray): List<String> {
        val rawNames = AiBuildOrchestrator.ocrImageAndExtractComponents(imageBytes)
        if (rawNames.isEmpty()) return emptyList()
        if (!OpenAiApiService.hasApiKey()) return rawNames
        val preValidateOut = mutableListOf<Pair<String, String>>()
        val seen = hashSetOf<String>()
        for (raw in rawNames) {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            val validation = AiBuildOrchestrator.validateComponentWithGPT(trimmed) ?: continue
            if (!validation.is_pc_component) continue
            val compType = validation.component_type?.trim()?.uppercase() ?: continue
            if (compType !in setOf("CPU","GPU","MOTHERBOARD","RAM","STORAGE","PSU","COOLER","CASE")) continue
            val normalizedName = validation.normalized_name?.takeIf { it.isNotBlank() } ?: trimmed
            val key = normalizedName.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
            if (key in seen) continue
            seen.add(key)
            preValidateOut.add(normalizedName to compType)
        }
        val groundedOut = mutableListOf<String>()
        val groundedSeen = hashSetOf<String>()
        for ((candidate, _) in preValidateOut) {
            val dbMatched = runCatching { groundComponentNameFsToDatabase(candidate) }.getOrNull()
            if (dbMatched != null) {
                val keyDb = dbMatched.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
                if (keyDb !in groundedSeen) {
                    groundedSeen.add(keyDb)
                    groundedOut.add(dbMatched)
                }
            }
        }
        if (groundedOut.isEmpty() && preValidateOut.isNotEmpty()) {
            for ((candidate, _) in preValidateOut) {
                val fb = runCatching { fallback.groundComponentNameFsFallback(candidate) }.getOrNull()
                if (fb != null) {
                    val k = fb.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
                    if (k !in groundedSeen) {
                        groundedSeen.add(k)
                        groundedOut.add(fb)
                    }
                }
            }
        }
        for ((candidate, compType) in preValidateOut) {
            val clean = candidate.filter(Char::isDigit)
            val hasNumericModel = clean.isNotBlank() && clean.length >= 3
            val hasCategoryKeyword = when (compType) {
                "GPU" -> candidate.lowercase().let { it.contains("rtx") || it.contains("gtx") || it.contains("rx ") || it.contains("radeon") || it.contains("arc") || it.contains("nvidia") || it.contains("geforce") }
                "CPU" -> candidate.lowercase().let { it.contains("ryzen") || it.contains("core i") || it.contains("core(tm)") || it.contains("intel") || it.contains("amd") }
                "RAM" -> candidate.lowercase().let { it.contains("ddr") || it.contains("mhz") || it.contains("gb ") || it.contains("kit") }
                "MOTHERBOARD" -> candidate.lowercase().let { it.contains("b550") || it.contains("x570") || it.contains("b650") || it.contains("x670") || it.contains("z790") || it.contains("b760") || it.contains("motherboard") }
                "STORAGE" -> candidate.lowercase().let { it.contains("ssd") || it.contains("nvme") || it.contains("m.2") || it.contains("hdd") || it.contains("tb") || it.contains("gb") }
                "PSU" -> candidate.lowercase().let { it.contains("w ") || it.contains("watts") || it.contains("80+") || it.contains("80plus") || it.contains("gold") || it.contains("bronze") || it.contains("psu") || it.contains("power") }
                else -> false
            }
            if (hasNumericModel || hasCategoryKeyword) {
                val keyLenient = candidate.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
                if (keyLenient !in groundedSeen) {
                    groundedSeen.add(keyLenient)
                    groundedOut.add(candidate)
                }
            }
        }
        return groundedOut
    }

    override suspend fun analyzeImageForComponentsVisionOnly(imageBytes: ByteArray): List<String> {
        if (imageBytes.isEmpty()) return emptyList()
        if (!GeminiApiService.hasApiKey()) return emptyList()
        return runCatching {
            AiBuildOrchestrator.runGeminiVisionExtractOnly(imageBytes)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase().replace("""[\s\-_/()]+""".toRegex(), "") }
                .take(6)
        }.getOrElse { emptyList() }
    }

    private suspend fun groundComponentNameFsToDatabase(candidate: String): String? {
        val candidateClean = candidate.trim().takeIf { it.isNotBlank() } ?: return null
        val candidateTokens = extractFsMeaningfulTokens(candidateClean.lowercase())
        if (candidateTokens.isEmpty()) return null
        val rawSearch = runCatching { getSearchResults(candidateClean) }.getOrDefault(emptyList())
        val filteredSearch = rawSearch.filter { s -> !s.startsWith("Sample Component") && s.isNotBlank() }
        if (filteredSearch.isEmpty()) return null
        val scored = filteredSearch.map { dbName ->
            val dbTokens = extractFsMeaningfulTokens(dbName.lowercase())
            val hit = candidateTokens.count { tok ->
                dbTokens.any { dbt -> dbt == tok || dbt.contains(tok) || tok.contains(dbt) }
            }
            val rawDigitMatch = candidateClean.filter(Char::isDigit).takeIf { it.isNotBlank() }?.let { d ->
                if (dbName.filter(Char::isDigit).contains(d)) 3 else 0
            } ?: 0
            Triple(dbName, hit + rawDigitMatch, candidateTokens.size)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.first })
        val best = scored.firstOrNull() ?: return null
        val minHits = (candidateTokens.size.coerceAtLeast(1) * 0.40).toInt().coerceAtLeast(2)
        val strictOk = best.second >= minHits
        val scoreRatio = candidateTokens.size.coerceAtLeast(1).let { tot ->
            best.second.toDouble() / tot.toDouble()
        }
        val flexibleOk = best.second >= 1 && (scoreRatio >= 0.33 || best.second >= 4)
        return best.first.takeIf { strictOk || flexibleOk }
    }

    private suspend fun RecommendationRepository.groundComponentNameFsFallback(candidate: String): String? {
        return runCatching {
            val candidateClean = candidate.trim().ifBlank { return@runCatching null }
            val candidateTokens = extractFsMeaningfulTokens(candidateClean.lowercase())
            if (candidateTokens.isEmpty()) return@runCatching null
            val rawSearch = this.getSearchResults(candidateClean).filter { !it.startsWith("Sample Component") && it.isNotBlank() }
            if (rawSearch.isEmpty()) return@runCatching null
            val scored = rawSearch.map { dbName ->
                val dbTokens = extractFsMeaningfulTokens(dbName.lowercase())
                val hit = candidateTokens.count { tok ->
                    dbTokens.any { dbt -> dbt == tok || dbt.contains(tok) || tok.contains(dbt) }
                }
                val rawDigitMatch = candidateClean.filter(Char::isDigit).takeIf { it.isNotBlank() }?.let { d ->
                    if (dbName.filter(Char::isDigit).contains(d)) 3 else 0
                } ?: 0
                dbName to (hit + rawDigitMatch)
            }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            val best = scored.firstOrNull() ?: return@runCatching null
            val minHits = (candidateTokens.size.coerceAtLeast(1) * 0.40).toInt().coerceAtLeast(2)
            val strictOk = best.second >= minHits
            val scoreRatio = candidateTokens.size.coerceAtLeast(1).let { tot ->
                best.second.toDouble() / tot.toDouble()
            }
            val flexibleOk = best.second >= 1 && (scoreRatio >= 0.33 || best.second >= 4)
            best.first.takeIf { strictOk || flexibleOk }
        }.getOrNull()
    }

    private fun extractFsMeaningfulTokens(value: String): List<String> {
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

    private suspend fun ensureSeedData() {
        if (hasCheckedSeedData) return

        seedMutex.withLock {
            if (hasCheckedSeedData) return

            val seedResult = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                runCatching {
                    val categories = RecommendationMockData.getSeedCategories()
                    val searchCollection = firestore.collection(SEARCH_RESULTS_COLLECTION)
                    val buildCollection = firestore.collection(BUILD_RECOMMENDATIONS_COLLECTION)
                    val upgradeCollection = firestore.collection(UPGRADE_PATHS_COLLECTION)

                    categories.forEach { category ->
                        val hasSearchDoc = searchCollection.document(category).get().await().exists()
                        if (!hasSearchDoc) {
                            searchCollection.document(category)
                                .set(RecommendationMockData.getSearchResultsSeed(category))
                                .await()
                        }

                        val hasBuildDoc = buildCollection.document(category).get().await().exists()
                        if (!hasBuildDoc) {
                            buildCollection.document(category)
                                .set(RecommendationMockData.getBuildRecommendationsSeed(category))
                                .await()
                        }

                        val hasUpgradeDoc = upgradeCollection.document(category).get().await().exists()
                        if (!hasUpgradeDoc) {
                            upgradeCollection.document(category)
                                .set(RecommendationMockData.getUpgradePathSeed(category))
                                .await()
                        }
                    }
                }
            }
            if (seedResult == null) {
                Log.w("FirestoreSeed", "ensureSeedData timed out after 10s -- continuing without seed (mock fallback will handle queries)")
            } else {
                seedResult.exceptionOrNull()?.let { t ->
                    Log.w("FirestoreSeed", "ensureSeedData failed: ${t.javaClass.simpleName} ${t.message} -- continuing without seed (mock fallback will handle queries)")
                }
            }

            hasCheckedSeedData = true
        }
    }

    private fun mapBuildRecommendation(data: Map<String, Any?>): BuildRecommendation? {
        val label = data.string("label") ?: return null
        val name = data.string("name") ?: return null

        return BuildRecommendation(
            label = label,
            name = name,
            cpu = data.string("cpu").orEmpty(),
            gpu = data.string("gpu").orEmpty(),
            motherboard = data.string("motherboard").orEmpty(),
            ram = data.string("ram").orEmpty(),
            storage = data.string("storage").orEmpty(),
            psu = data.string("psu").orEmpty(),
            wattage = data.string("wattage").orEmpty(),
            wattageNote = data.string("wattageNote").orEmpty(),
            bottleneck = data.string("bottleneck").orEmpty(),
            bottleneckNote = data.string("bottleneckNote").orEmpty()
        )
    }

    private fun mapUpgradePath(data: Map<String, Any?>): UpgradePath? {
        val title = data.string("title") ?: return null
        val description = data.string("description") ?: return null

        return UpgradePath(
            title = title,
            description = description,
            stepOne = data.string("stepOne").orEmpty(),
            stepTwo = data.string("stepTwo").orEmpty(),
            stepThree = data.string("stepThree").orEmpty()
        )
    }

    private fun DocumentSnapshot.getStringList(field: String): List<String>? {
        @Suppress("UNCHECKED_CAST")
        return get(field) as? List<String>
    }

    private fun DocumentSnapshot.getMapList(field: String): List<Map<String, Any?>>? {
        @Suppress("UNCHECKED_CAST")
        return get(field) as? List<Map<String, Any?>>
    }

    private fun DocumentSnapshot.getMapValue(field: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return get(field) as? Map<String, Any?>
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    override suspend fun analyzeSpecSheetPipelineOrNull(
        specSheetText: String,
        fallbackBuild: CurrentBuild,
        onProgress: (Int, String, String) -> Unit,
        primaryComponent: String
    ): SpecSheetPipelineResult? {
        return fallback.analyzeSpecSheetPipelineOrNull(specSheetText, fallbackBuild, onProgress, primaryComponent)
    }

    companion object {
        private const val SEARCH_RESULTS_COLLECTION = "searchResults"
        private const val SEARCH_RESULTS_FIELD = "items"
        private const val BUILD_RECOMMENDATIONS_COLLECTION = "buildRecommendations"
        private const val BUILD_RECOMMENDATIONS_FIELD = "items"
        private const val UPGRADE_PATHS_COLLECTION = "upgradePaths"
        private const val EXISTING_BUILD_FIELD = "existingBuild"
        private const val NEW_BUILD_FIELD = "newBuild"
    }
}

object RecommendationRepositoryProvider {
    val current: RecommendationRepository by lazy {
        FirestoreRecommendationRepository(FirebaseFirestore.getInstance())
    }
}
