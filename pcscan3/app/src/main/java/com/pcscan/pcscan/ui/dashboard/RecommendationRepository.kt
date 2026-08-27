package com.pcscan.pcscan.ui.dashboard

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.pcscan.pcscan.data.api.ComponentDetailMapper
import com.pcscan.pcscan.data.api.LocalSpecDatabase
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
    val bottleneckNote: String
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

interface RecommendationRepository {
    suspend fun getSearchResults(query: String): List<String>
    suspend fun getExactSearchResult(query: String): ExactSearchResult
    suspend fun getBuildRecommendations(component: String): List<BuildRecommendation>
    suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean): UpgradePath
    suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean, tier: BuildTier): UpgradePath
    suspend fun getUpgradePath(
        searchedComponent: String,
        currentBuild: CurrentBuild,
        tier: BuildTier
    ): UpgradePath
    suspend fun getComponentDetails(componentName: String): ComponentDetail
}

object MockRecommendationRepository : RecommendationRepository {
    override suspend fun getSearchResults(query: String): List<String> {
        return RecommendationMockData.getSearchResults(query)
    }

    override suspend fun getExactSearchResult(query: String): ExactSearchResult {
        // Same OFFLINE-PRECHECK regex as Firestore impl - mock must also reject laptop parts.
        val qTrim = query.trim().also { if (it.isBlank()) return ExactSearchResult.InvalidQuery(query, "Search query is empty.", "wrong_model", 0f, "unknown") }
        val qLow = qTrim.lowercase()
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
        val cpuMobileRe = Regex("(^|[\\s\\-_])(\\d{4,5}[hHuUyY]\\b|hs\\b|hx\\b|hq\\b|mq\\b|lm\\b|um\\b|g[1-7]\\b|[a-z0-9]m\\b|mobile(?!\\s*legend))", RegexOption.IGNORE_CASE)
        val gpuMobileRe = Regex("(^|[\\s\\-_])(mx\\d+\\b|\\d{3,4}m\\b|max[\\s\\-]?q\\b|(rtx|gtx|rx|radeon|geforce|arc)\\s.*\\blaptop\\b|laptop\\s.*\\bgpu\\b|\\bgt\\s?1\\b|(super|ti)\\s+mobile\\b|quadro\\s+\\w+\\s*m\\b)", RegexOption.IGNORE_CASE)
        if (cpuMobileRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE CPU DETECTED - '$qTrim' uses a mobile/laptop CPU suffix (H,HS,HX,U,Y,G1-G7,MQ,M etc). Only DESKTOP CPU models (suffix K/KF/F/X/XT/G/T or no suffix) are accepted in this app.", "laptop_part", 0f, "laptop")
        }
        if (gpuMobileRe.containsMatchIn(qLow)) {
            return ExactSearchResult.InvalidQuery(query, "LAPTOP / MOBILE GPU DETECTED - '$qTrim' uses a mobile/laptop GPU suffix (M/MX/Max-Q/Laptop etc). Only DESKTOP GPU models (Ti/Super/XT or standard suffixes) are accepted in this app.", "laptop_part", 0f, "laptop")
        }

        val local = SmartQuerySpecGuesser.guess(query)
            ?: LocalSpecDatabase.find(query)
            ?: runCatching { RecommendationMockData.getComponentDetails(query) }
                .getOrNull()?.takeIf { it.aiConfidence >= 70 && it.name != "Unknown Component" }

        // ===== CATEGORY ALIAS NORMALIZATION (F1c): Fix MOBO/STOR aliases before any whitelist guard =====
        val localNorm = local?.let { comp ->
            val rawCat = comp.category.trim().uppercase()
            val normalizedCat = when (rawCat) {
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
                    else -> comp.category.uppercase()
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
            ExactSearchResult.Success(
                component = localNorm,
                aiConfidence = localNorm.aiConfidence / 100f,
                componentId = "local-${localNorm.category}-${localNorm.name}",
                specifications = emptyMap()
            )
        } else {
            ExactSearchResult.NotFound(
                query = query,
                reason = "Exact component not found."
            )
        }
    }

    override suspend fun getBuildRecommendations(component: String): List<BuildRecommendation> {
        return RecommendationMockData.getBuildRecommendations(component)
    }

    override suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean): UpgradePath {
        return RecommendationMockData.getUpgradePath(component, hasExistingBuild)
    }

    override suspend fun getUpgradePath(component: String, hasExistingBuild: Boolean, tier: BuildTier): UpgradePath {
        return RecommendationMockData.getUpgradePath(component, hasExistingBuild, tier)
    }

    override suspend fun getUpgradePath(
        searchedComponent: String,
        currentBuild: CurrentBuild,
        tier: BuildTier
    ): UpgradePath {
        return RecommendationMockData.getUpgradePath(searchedComponent, currentBuild, tier)
    }

    override suspend fun getComponentDetails(componentName: String): ComponentDetail {
        return RecommendationMockData.getComponentDetails(componentName)
    }
}

class FirestoreRecommendationRepository(
    private val firestore: FirebaseFirestore,
    private val fallback: RecommendationRepository = MockRecommendationRepository
) : RecommendationRepository {

    private val seedMutex = Mutex()
    @Volatile
    private var hasCheckedSeedData = false

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

                        val details = runCatching { getComponentDetails(canonical) }
                            .getOrNull()
                            ?: fallback.getComponentDetails(canonical)

                        // F1d: Final category = always use normalized first, then fallback chain
                        val finalCategory = normCompCat.ifBlank { compCategory.ifBlank { details.category } }

                        return ExactSearchResult.Success(
                            component = details.copy(
                                name = canonical,
                                category = finalCategory,
                                manufacturer = compBrand.ifBlank { details.manufacturer },
                                aiConfidence = (aiConf * 100).toInt().coerceIn(0, 100).takeIf { it > 0 } ?: details.aiConfidence
                            ),
                            aiConfidence = aiConf,
                            componentId = body.component.id,
                            specifications = body.component.specifications
                        )
                    } else {
                        val msg = body.message?.takeIf { it.isNotBlank() } ?: "Exact component not found."
                        val rejectReason = body.rejectionReason
                        val isValid = body.isValidPcPart
                        // Invalidation = explicit: return INVALID QUERY (laptop/not pc/wrong model) vs regular not-found (valid desktop pc part but no exact in db)
                        if (isValid == false && rejectReason != null) {
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
                        Log.w(tag, "WARN: exact-search NOT FOUND (but valid PC part): query='$query' reason='$msg' (${dt}ms)")
                        return ExactSearchResult.NotFound(
                            query = query,
                            reason = msg,
                            aiConfidence = body.aiConfidence ?: 0f
                        )
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

    override suspend fun getBuildRecommendations(component: String): List<BuildRecommendation> {
        ensureSeedData()

        val tag = "BuildRecoAPI"
        val t0 = System.currentTimeMillis()
        val category = RecommendationMockData.resolveCategory(component)
        Log.d(tag, "AI: [$component] (category=$category) -> LEVEL 1 PRIORITY: Backend / OpenAI Build-Recommendations API call...")

        Log.i(tag, "PASS: LAYER-1 HIT: /api/build-recommendation NEW ENDPOINT (OpenAI direct pick) - component=[$component] cat=[$category] - THIS IS THE NEW PIPELINE WE WANT")
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
                        fun mapTier(tier: SearchApiDto.BuildRecoTierBundleDto, tierLabel: String, tierName: String): BuildRecommendation {
                            val concatGpu = buildString { append("NVIDIA: ").append(tier.gpuNvidia).append("\nAMD: ").append(tier.gpuAmd) }
                            val base = BuildRecommendation(
                                label = tierLabel,
                                name = "$tierName Build for ${body.anchorComponent}",
                                cpu = tier.cpu,
                                gpu = concatGpu,
                                gpuNvidia = tier.gpuNvidia,
                                gpuAmd = tier.gpuAmd,
                                motherboard = tier.motherboard,
                                ram = tier.ram,
                                storage = tier.storage,
                                psu = tier.psu,
                                wattage = "${tier.estimatedWattage}W",
                                wattageNote = "",
                                bottleneck = "${tier.bottleneckPct}%",
                                bottleneckNote = "CPU/GPU balance checked."
                            )
                            val enforced = enforceComponentInBuild(base, component, category)
                            val v = RecommendationMockData.validateBuildRecommendation(enforced)
                            return enforced.copy(
                                label = "${enforced.label} ${v.summaryLabel}".trim(),
                                wattageNote = v.summaryLabel,
                                bottleneckNote = if (v.info.isNotEmpty()) v.info.joinToString(" ") else ""
                            )
                        }
                        val list = listOf(
                            mapTier(body.entry, "ENTRY BUILD", "Entry"),
                            mapTier(body.mid, "MIDRANGE BUILD", "Midrange"),
                            mapTier(body.high, "HIGH-END BUILD", "High-End")
                        )
                        Log.i(tag, "OK NEW /api/build-recommendation returned 3/3 tiers for [$component] anchor=${body.anchorCategory}")
                        Log.i(tag, "SUCCESS: LAYER-1 /api/build-recommendation SUCCESS - OpenAI picked actual components! Builds: Entry GPU(NV)=${list[0].gpuNvidia}/(AMD)=${list[0].gpuAmd} | Mid=${list[1].gpuNvidia}/${list[1].gpuAmd} | High=${list[2].gpuNvidia}/${list[2].gpuAmd} | CPU locked anchor=${list[0].cpu} (NEVER REPLACED)")
                        return@runCatching list
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
BUILD RECOMMENDATION RULES (STRICT - FOR LLM ONLY):
1. GPT-4o-mini ROLE: Recommend BALANCED companion components for '$component' and WRITE CLEAR EXPLANATIONS. YOU are NOT the final authority on compatibility. The app's DETERMINISTIC ENGINE will validate socket/RAM/PSU. Even if you suggest an incorrect pairing, the app will correct it before display.
2. ALL 3 Tiers (ENTRY/MIDRANGE/HIGH-END) MUST USE the component: '$component' in the CORRECT category slot. DO NOT replace it.
3. BALANCED only - DO NOT pair a low-cost component with overly expensive parts. Ex: RTX 2060 -> ENTRY: Ryzen 5 5600/B550/16GB DDR4; MID: Ryzen 7 5700X3D/B550/32GB DDR4; HIGH: Ryzen 7 5700X3D + mid-high GPU companion.
4. NEVER INVENT hardware models. Only use well-known mainstream names.
5. NEVER claim socket/RAM compatibility. Only make suggestions. The app will determine if it is truly compatible.
6. NEVER call the same component an upgrade.
7. TIERS = RELATIVE to the searched component. NOT absolute max parts. HIGH-END = the highest BALANCED build that includes '$component'.
8. PSU wattage: reasonable only, suggest with ~20% headroom.
9. NO PRICES / PHP / CURRENCY SYMBOLS anywhere.
10. TIER LABELS: ENTRY / MIDRANGE / HIGH-END exactly.
11. RETURN JSON IMMEDIATELY. No intro. No commentary. Direct output.
12. EXPLAIN tradeoffs clearly. Distinguish compatible vs recommended vs best value vs high-end vs overkill.
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
                        val rawList = listOfNotNull(rawEntry, rawMid, rawHigh)

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

                        if (validated.size == 3) {
                            Log.i(tag, "OK1 [$component] -> API/OPENAI BUILD RECO + DETERMINISTIC VALIDATION done (enforced $category): entry='${validated[0].name.take(45)}...', mid='${validated[1].name.take(45)}...', high='${validated[2].name.take(45)}...' (${dtApi}ms)")
                            return@runCatching validated
                        } else {
                            Log.w(tag, "WARN: [$component] -> API returned only ${validated.size}/3 tiers. result=$validated. Falling back to Mock.")
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
        return raw.map { build ->
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
    }

    private fun enforceComponentInBuild(build: BuildRecommendation, component: String, category: String): BuildRecommendation {
        return when (category) {
            "gpu" -> build.copy(gpu = component)
            "cpu" -> build.copy(cpu = component)
            "ram" -> build.copy(ram = component)
            "motherboard" -> build.copy(motherboard = component)
            "storage" -> build.copy(storage = component)
            "psu" -> build.copy(psu = component)
            else -> build
        }
    }

    private fun mapTieredBuildToRecommendation(tier: SearchApiDto.TieredBuildDto): BuildRecommendation {
        val hw = tier.hardware
        val tierLabel = when (tier.tier.lowercase()) {
            "entry" -> "ENTRY BUILD"
            "mid" -> "MIDRANGE BUILD"
            "high" -> "HIGH-END BUILD"
            else -> tier.tier.replaceFirstChar { it.uppercase() }
        }

        val cleanedTierName = run {
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
            cleaned.replace(Regex("""\s+"""), " ").trim().ifBlank { "Custom Build" }
        }

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

    private suspend fun ensureSeedData() {
        if (hasCheckedSeedData) return

        seedMutex.withLock {
            if (hasCheckedSeedData) return

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
