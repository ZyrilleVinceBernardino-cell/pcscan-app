package com.pcscan.pcscan.ui.dashboard

import com.pcscan.pcscan.data.api.LocalSpecDatabase
import com.pcscan.pcscan.data.api.SmartQuerySpecGuesser

data class CpuSpec(
    val brand: String,
    val model: String,
    val generation: String,
    val socket: String,
    val memoryType: String,
    val cores: Int,
    val threads: Int,
    val tdp: Int,
    val performanceTier: Int
)

data class MoboSpec(
    val brand: String,
    val model: String,
    val socket: String,
    val chipset: String,
    val memoryType: String,
    val maxRam: Int,
    val ramSlots: Int,
    val m2Slots: Int,
    val pcieVersion: String,
    val biosSupport: Map<String, Boolean> = emptyMap()
)

data class GpuSpec(
    val brand: String,
    val model: String,
    val generation: String,
    val vram: Int,
    val memoryType: String,
    val recommendedPsu: Int,
    val performanceTier: Int,
    val targetResolution: String,
    val powerDraw: Int
)

data class RamSpec(
    val type: String,
    val capacity: Int,
    val speed: Int,
    val kitConfiguration: String,
    val latency: String = ""
)

data class StorageSpec(
    val type: String,
    val iface: String,
    val capacity: Int,
    val pcieGeneration: Int,
    val readSpeed: Int = 0,
    val writeSpeed: Int = 0
)

data class PsuSpec(
    val wattage: Int,
    val efficiency: String,
    val atxVersion: String = "",
    val gpuConnector: String = "",
    val qualityTier: Int
)

data class ComponentScores(
    val compatibility: Int,
    val performanceGain: String,
    val value: String,
    val upgradePriority: String
) {
    companion object {
        fun neutral() = ComponentScores(100, "Medium", "Medium", "Medium")
    }
}

data class HardwareCompatibilityResult(
    val isHardwareCompatible: Boolean,
    val performanceBalance: String,
    val bottleneckRisk: String,
    val bottleneckComponent: String = "",
    val issues: List<String> = emptyList()
)

data class BuildValidationResult(
    val isDisplayable: Boolean,
    val criticalIssues: List<String>,
    val warnings: List<String>,
    val info: List<String>
) {
    val summaryLabel: String
        get() = when {
            criticalIssues.isNotEmpty() -> "🔴 Incompatible"
            warnings.isNotEmpty() -> "🟡 Compatible but not ideal"
            else -> "🟢 Compatible"
        }
}

data class UpgradeLabel(val label: String) {
    val status: String
        get() = when (label) {
            "HIGH" -> "🟢 Recommended"
            "MEDIUM" -> "🟡 Optional"
            else -> "🔴 Not recommended"
        }
}

object HardwareDatabase {

    fun cpuTdpWatts(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("i3-12100") -> 58
            n.contains("ryzen 5 5500") -> 65
            n.contains("ryzen 5 5600") -> 65
            n.contains("ryzen 7 5700x3d") -> 105
            n.contains("ryzen 7 5800x3d") -> 105
            n.contains("ryzen 5 7600") -> 65
            n.contains("ryzen 7 7800x3d") -> 120
            n.contains("ryzen 7 8700") -> 65
            n.contains("ryzen 9 7900") -> 65
            n.contains("ryzen 9 9950x") -> 170
            n.contains("i5-14400f") -> 65
            n.contains("i7-14700k") -> 125
            n.contains("i7-14700kf") -> 125
            n.contains("i9-14900k") -> 125
            n.contains("ultra") && n.contains("2") -> 125
            else -> 65
        }
    }

    fun gpuTdpWatts(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("rtx 2060") -> 160
            n.contains("rtx 3050") -> 130
            n.contains("rtx 3060") -> 170
            n.contains("rtx 4060") -> 115
            n.contains("rtx 5060") -> 130
            n.contains("rtx 5060 ti") -> 180
            n.contains("rtx 4070") || n.contains("4070 super") -> 220
            n.contains("rtx 5070") -> 250
            n.contains("rtx 5070 ti") -> 300
            n.contains("rtx 4080") || n.contains("4080 super") -> 320
            n.contains("rtx 5080") -> 360
            n.contains("rtx 3090") -> 350
            n.contains("rtx 4090") -> 450
            n.contains("rtx 5090") -> 575
            n.contains("rx 6600") -> 132
            n.contains("rx 6700") -> 175
            n.contains("rx 7600") -> 165
            n.contains("rx 7800 xt") -> 263
            n.contains("rx 7900 xt") -> 315
            n.contains("rx 7900 xtx") -> 355
            n.contains("rx 9060") -> 240
            n.contains("rx 9070") -> 320
            n.contains("rx 9070 xt") -> 400
            n.contains("rx 9090") -> 600
            else -> 150
        }
    }

    fun gpuVramGb(name: String): Int {
        val n = name.lowercase()
        val explicit = Regex("""(\d+)\s*gb""").find(n)?.groupValues?.get(1)?.toIntOrNull()
        if (explicit != null) return explicit
        return when {
            n.contains("rtx 2060") -> 6
            n.contains("rtx 3050") -> 8
            n.contains("rtx 3060") -> 12
            n.contains("rtx 4060") || n.contains("rtx 5060") -> 8
            n.contains("rtx 5060 ti") -> 16
            n.contains("rtx 4070") || n.contains("4070 super") || n.contains("rtx 5070") -> 12
            n.contains("rtx 5070 ti") -> 16
            n.contains("rtx 4080") || n.contains("4080 super") || n.contains("rtx 5080") -> 16
            n.contains("rtx 3090") || n.contains("rtx 4090") -> 24
            n.contains("rtx 5090") -> 32
            else -> 8
        }
    }

    fun moboMemoryType(name: String): String? {
        val n = name.lowercase()
        val forceDdr5 = listOf("b650", "x670", "x870", "a620", "z890", "b860", "h810")
        val forceDdr4 = listOf("b450", "b550", "x570", "a520", "x470", "b350", "h610", "b660", "z690", "h670")
        return when {
            n.contains("ddr5") -> "DDR5"
            n.contains("ddr4") -> "DDR4"
            forceDdr5.any { n.contains(it) } -> "DDR5"
            forceDdr4.any { n.contains(it) } -> "DDR4"
            else -> null
        }
    }

    fun psuWattage(name: String): Int {
        val match = Regex("""(\d+)\s*w""").find(name.lowercase())
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 550
    }

    fun psuQualityTier(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("titanium") -> 5
            n.contains("platinum") -> 4
            n.contains("gold") -> 3
            n.contains("bronze") -> 2
            else -> 1
        }
    }
}

object RecommendationMockData {

    private val searchResults = mapOf(
        "gpu" to listOf(
            "NVIDIA RTX 4090",
            "NVIDIA RTX 4080",
            "AMD Radeon RX 7900 XTX",
            "NVIDIA RTX 3080 Ti"
        ),
        "cpu" to listOf(
            "Intel Core i9-14900K",
            "AMD Ryzen 9 7950X",
            "Intel Core i7-14700K",
            "AMD Ryzen 7 7800X3D"
        ),
        "ram" to listOf(
            "Corsair Vengeance DDR5 32GB",
            "G.Skill Trident Z5 RGB 64GB",
            "Kingston Fury Beast 32GB"
        ),
        "motherboard" to listOf(
            "ASUS ROG Strix Z790-E",
            "MSI MPG X670E Edge WiFi",
            "GIGABYTE AORUS Z790 Master"
        ),
        "storage" to listOf(
            "Samsung 990 Pro 2TB",
            "WD Black SN850X 2TB",
            "Crucial P5 Plus 1TB"
        ),
        "psu" to listOf(
            "Corsair RM1000x",
            "EVGA SuperNOVA 850 G7",
            "Seasonic Focus GX-850"
        )
    )

    fun getSearchResults(query: String): List<String> {
        val normalized = resolveCategory(query)
        val baseResults = searchResults[normalized] ?: listOf(
            "Sample Component 1",
            "Sample Component 2",
            "Sample Component 3"
        )
        // If the query isn't already in the base results, add it at the beginning
        return if (baseResults.any { it.equals(query, ignoreCase = true) }) {
            baseResults
        } else {
            listOf(query) + baseResults
        }
    }

    fun getSeedCategories(): List<String> = listOf(
        "gpu",
        "cpu",
        "ram",
        "motherboard",
        "storage",
        "psu"
    )

    fun getSearchResultsSeed(category: String): Map<String, Any> {
        return mapOf("items" to getSearchResults(category))
    }

    fun getBuildRecommendations(component: String): List<BuildRecommendation> {
        val category = resolveCategory(component)
        val componentPerformanceTier = estimateComponentPerformanceTier(component, category)

        val searchedPlatform = detectComponentPlatform(component, category)
        val searchedSocket = if (searchedPlatform != Platform.UNKNOWN) {
            platformSocket(searchedPlatform)
        } else {
            detectSocketByName(component)
        }
        val searchedRamIsDdr5 = category == "ram" && component.lowercase().contains("ddr5")
        val searchedRamIsDdr4 = category == "ram" && component.lowercase().contains("ddr4")

        val effectivePlatform = when {
            searchedPlatform != Platform.UNKNOWN -> searchedPlatform
            category == "cpu" && searchedSocket == Socket.AM5 -> Platform.AM5_DDR5
            category == "cpu" && searchedSocket == Socket.AM4 -> Platform.AM4_DDR4
            category == "cpu" && searchedSocket == Socket.LGA1700 -> Platform.UNKNOWN
            category == "cpu" && searchedSocket == Socket.LGA1851 -> Platform.LGA1851_DDR5
            category == "motherboard" && searchedSocket == Socket.AM5 -> Platform.AM5_DDR5
            category == "motherboard" && searchedSocket == Socket.AM4 -> Platform.AM4_DDR4
            category == "motherboard" && searchedSocket == Socket.LGA1700 -> {
                if (component.lowercase().contains("ddr5")) Platform.LGA1700_DDR5 else Platform.LGA1700_DDR4
            }
            category == "motherboard" && searchedSocket == Socket.LGA1851 -> Platform.LGA1851_DDR5
            searchedRamIsDdr5 -> Platform.UNKNOWN
            searchedRamIsDdr4 -> Platform.UNKNOWN
            else -> Platform.UNKNOWN
        }

        val isAm4 = effectivePlatform == Platform.AM4_DDR4
        val isAm5 = effectivePlatform == Platform.AM5_DDR5
        val isLga1700Ddr4 = effectivePlatform == Platform.LGA1700_DDR4
        val isLga1700Ddr5 = effectivePlatform == Platform.LGA1700_DDR5
        val isLga1851Ddr5 = effectivePlatform == Platform.LGA1851_DDR5
        val currentDdr5 = isAm5 || isLga1700Ddr5 || isLga1851Ddr5

        data class TierBuildParts(
            val cpus: List<String>,
            val motherboards: List<String>,
            val rams: List<String>,
            val storages: List<String>,
            val psus: List<String>,
            val wattages: List<String>,
            val wattageNotes: List<String>,
            val bottlenecks: List<String>,
            val bottleneckNotes: List<String>
        )

        val cpusByPlatform = when {
            isAm4 -> listOf(
                "Ryzen 5 5600",
                "Ryzen 7 5700X3D",
                "Ryzen 7 5800X3D"
            )
            isAm5 -> listOf(
                "Ryzen 5 7600",
                "Ryzen 7 8700",
                "Ryzen 9 7900"
            )
            isLga1700Ddr4 -> listOf(
                "Intel Core i3-12100F",
                "Intel Core i5-14400F",
                "Intel Core i7-14700KF"
            )
            isLga1700Ddr5 -> listOf(
                "Intel Core i5-14400F",
                "Intel Core i7-14700K",
                "Intel Core i9-14900K"
            )
            isLga1851Ddr5 -> listOf(
                "Intel Core i5-14500",
                "Intel Core i7-14700K",
                "Intel Core i9-14900K"
            )
            else -> listOf(
                "Ryzen 5 7600",
                "Ryzen 7 8700",
                "Ryzen 9 7900"
            )
        }

        val mobosByPlatform = when {
            isAm4 -> listOf(
                "B550M Pro4",
                "B550 AORUS Elite V2",
                "X570 AORUS Elite"
            )
            isAm5 -> listOf(
                "B650M DS3H",
                "B650 AORUS Elite",
                "X670E Steel Legend"
            )
            isLga1700Ddr4 -> listOf(
                "H610M DDR4",
                "B660M DDR4",
                "B760 Tomahawk DDR4"
            )
            isLga1700Ddr5 -> listOf(
                "B760M-A WiFi DDR5",
                "B760 Tomahawk WiFi DDR5",
                "ROG MAXIMUS Z790 HERO"
            )
            isLga1851Ddr5 -> listOf(
                "B860M-A WiFi DDR5",
                "B860 Tomahawk WiFi DDR5",
                "ROG MAXIMUS Z890 HERO"
            )
            else -> listOf(
                "B650M DS3H",
                "B650 AORUS Elite",
                "X670E Steel Legend"
            )
        }

        val ramsByPlatform = when {
            isAm4 || isLga1700Ddr4 -> listOf(
                "16GB DDR4-3200",
                "32GB DDR4-3600",
                "64GB DDR4-3600"
            )
            isAm5 || isLga1700Ddr5 || isLga1851Ddr5 -> listOf(
                "16GB DDR5-5600",
                "32GB DDR5-6000",
                "64GB DDR5-6400"
            )
            else -> listOf(
                "16GB DDR4-3200",
                "32GB DDR4-3600",
                "64GB DDR4-3600"
            )
        }

        val parts = when (componentPerformanceTier) {
            ComponentTier.LEGACY_BUDGET -> TierBuildParts(
                cpus = cpusByPlatform,
                motherboards = mobosByPlatform,
                rams = ramsByPlatform,
                storages = listOf("512GB SATA SSD", "1TB NVMe SSD", "2TB NVMe SSD"),
                psus = listOf("500W 80+ Bronze", "550W 80+ Bronze", "650W 80+ Gold"),
                wattages = listOf("320W", "380W", "450W"),
                wattageNotes = listOf(
                    "PSU: 500W recommended. Matches entry-tier companion components.",
                    "PSU: 550W recommended. Matches mid-tier companion components.",
                    "PSU: 650W recommended. Matches high-tier companion components."
                ),
                bottlenecks = listOf("5%", "3%", "2%"),
                bottleneckNotes = listOf(
                    "Balanced with recommended entry-tier companion upgrades",
                    "Balanced with recommended mid-tier companion upgrades",
                    "Balanced with recommended high-tier companion upgrades. Minimal bottleneck."
                )
            )
            ComponentTier.MIDRANGE -> TierBuildParts(
                cpus = cpusByPlatform,
                motherboards = mobosByPlatform,
                rams = ramsByPlatform,
                storages = listOf("1TB NVMe SSD", "1TB PCIe 4.0 NVMe", "2TB PCIe 4.0 NVMe"),
                psus = if (currentDdr5) {
                    listOf("550W 80+ Bronze", "650W 80+ Gold", "750W 80+ Gold ATX 3.0")
                } else {
                    listOf("550W 80+ Bronze", "650W 80+ Gold", "750W 80+ Gold")
                },
                wattages = listOf("380W", "460W", "540W"),
                wattageNotes = listOf(
                    "PSU: 550W recommended. Matches entry-tier companion components.",
                    "PSU: 650W recommended. Matches mid-tier companion components.",
                    "PSU: 750W recommended. Matches high-tier companion components."
                ),
                bottlenecks = listOf("4%", "3%", "2%"),
                bottleneckNotes = listOf(
                    "Balanced with recommended entry-tier companion upgrades",
                    "Balanced with recommended mid-tier companion upgrades",
                    "Balanced with recommended high-tier companion upgrades. Near-zero bottleneck."
                )
            )
            ComponentTier.HIGH_END_CURRENT -> TierBuildParts(
                cpus = cpusByPlatform,
                motherboards = mobosByPlatform,
                rams = ramsByPlatform,
                storages = listOf("1TB PCIe 4.0 NVMe", "2TB PCIe 4.0 NVMe", "4TB PCIe 4.0 NVMe"),
                psus = if (currentDdr5) {
                    listOf("650W 80+ Gold", "750W 80+ Gold ATX 3.0", "850W 80+ Gold ATX 3.0")
                } else {
                    listOf("650W 80+ Gold", "750W 80+ Gold", "850W 80+ Gold")
                },
                wattages = listOf("490W", "590W", "690W"),
                wattageNotes = listOf(
                    "PSU: 650W recommended based on actual CPU+GPU TDP with headroom.",
                    "PSU: 750W recommended based on actual CPU+GPU TDP with headroom.",
                    "PSU: 850W recommended for high-end GPU with 20% headroom."
                ),
                bottlenecks = listOf("3%", "2%", "1%"),
                bottleneckNotes = listOf(
                    "Balanced with recommended entry-tier companion upgrades",
                    "Balanced with recommended mid-tier companion upgrades",
                    "Balanced with recommended high-tier companion upgrades. 4K ready."
                )
            )
            ComponentTier.ENTHUSIAST_FLAGSHIP -> TierBuildParts(
                cpus = cpusByPlatform,
                motherboards = mobosByPlatform,
                rams = ramsByPlatform,
                storages = listOf("2TB PCIe 4.0 NVMe", "4TB PCIe 4.0 NVMe", "4TB PCIe 5.0 NVMe"),
                psus = if (currentDdr5) {
                    listOf("850W 80+ Gold ATX 3.0", "1000W 80+ Platinum ATX 3.1", "1200W 80+ Platinum ATX 3.1")
                } else {
                    listOf("850W 80+ Gold", "1000W 80+ Platinum", "1200W 80+ Platinum")
                },
                wattages = listOf("680W", "790W", "920W"),
                wattageNotes = listOf(
                    "PSU: 850W for entry flagship. Matches CPU+GPU+20% headroom.",
                    "PSU: 1000W for balanced flagship builds.",
                    "PSU: 1200W for multi-GPU or heavy overclock builds."
                ),
                bottlenecks = listOf("2%", "1%", "1%"),
                bottleneckNotes = listOf(
                    "Balanced with recommended entry-tier companion upgrades",
                    "Balanced with recommended mid-tier companion upgrades",
                    "Flagship balance. Matches recommended high-tier companion upgrades."
                )
            )
        }

        val entryCompanions = resolveCompanionUpgrades(category, componentPerformanceTier, BuildTier.ENTRY, effectivePlatform)
        val midCompanions = resolveCompanionUpgrades(category, componentPerformanceTier, BuildTier.MID, effectivePlatform)
        val highCompanions = resolveCompanionUpgrades(category, componentPerformanceTier, BuildTier.HIGH, effectivePlatform)
        val tierCompanions = listOf(entryCompanions, midCompanions, highCompanions)

        val names = when (category) {
            "gpu" -> listOf(
                "Balanced GPU Build (Entry)",
                "Balanced GPU Build (Mid)",
                "Maximum Balanced GPU Build"
            )
            "cpu" -> listOf(
                "Balanced CPU Build (Entry)",
                "Balanced CPU Build (Mid)",
                "Maximum Balanced CPU Build"
            )
            else -> listOf(
                "Balanced Build (Entry)",
                "Balanced Build (Mid)",
                "Maximum Balanced Build"
            )
        }

        val baseBuilds = listOf(
            Triple("ENTRY BUILD", names[0], 0),
            Triple("MIDRANGE BUILD", names[1], 1),
            Triple("HIGH-END BUILD", names[2], 2)
        )

        return baseBuilds.map { (label, name, index) ->
            val companions = tierCompanions[index]
            val companionByCat = companions.associateBy {
                it.category.uppercase()
            }

            val companionCpu = when {
                category == "cpu" -> component
                companionByCat.containsKey("CPU") -> companionByCat["CPU"]!!.name
                else -> parts.cpus[index]
            }

            val companionGpu = when {
                category == "gpu" -> component
                companionByCat.containsKey("GPU") -> companionByCat["GPU"]!!.name
                else -> when (componentPerformanceTier) {
                    ComponentTier.LEGACY_BUDGET -> "RTX 3060"
                    ComponentTier.MIDRANGE -> "RTX 4060"
                    ComponentTier.HIGH_END_CURRENT -> "RTX 4070 Super"
                    ComponentTier.ENTHUSIAST_FLAGSHIP -> "RTX 4090"
                }
            }

            val companionMotherboard = when {
                category == "motherboard" -> component
                companionByCat.containsKey("MOTHERBOARD") -> companionByCat["MOTHERBOARD"]!!.name
                else -> parts.motherboards[index]
            }

            val companionRam = when {
                category == "ram" -> component
                companionByCat.containsKey("RAM") -> companionByCat["RAM"]!!.name
                else -> parts.rams[index]
            }

            val companionStorage = when {
                category == "storage" -> component
                companionByCat.containsKey("STORAGE") -> companionByCat["STORAGE"]!!.name
                else -> parts.storages[index]
            }

            val companionPsu = when {
                category == "psu" -> component
                companionByCat.containsKey("PSU") -> companionByCat["PSU"]!!.name
                else -> parts.psus[index]
            }

            val rawValues = listOf(
                "CPU" to companionCpu,
                "GPU" to companionGpu,
                "MOTHERBOARD" to companionMotherboard,
                "RAM" to companionRam,
                "STORAGE" to companionStorage,
                "PSU" to companionPsu
            )
            val seenNames = mutableSetOf<String>()
            val dedupedFields = mutableMapOf<String, String>()
            val fallbackByCat = mapOf(
                "CPU" to parts.cpus[index],
                "GPU" to (when (componentPerformanceTier) {
                    ComponentTier.LEGACY_BUDGET -> "RTX 3060"
                    ComponentTier.MIDRANGE -> "RTX 4060 Ti"
                    ComponentTier.HIGH_END_CURRENT -> "RTX 4070 Ti Super"
                    ComponentTier.ENTHUSIAST_FLAGSHIP -> "RTX 4090"
                }),
                "MOTHERBOARD" to parts.motherboards[index],
                "RAM" to parts.rams[index],
                "STORAGE" to parts.storages[index],
                "PSU" to parts.psus[index]
            )
            for ((cat, rawName) in rawValues) {
                val trimmed = rawName.trim()
                if (trimmed !in seenNames) {
                    dedupedFields[cat] = trimmed
                    seenNames.add(trimmed)
                } else {
                    val fb = fallbackByCat[cat] ?: trimmed
                    if (fb !in seenNames) {
                        dedupedFields[cat] = fb
                        seenNames.add(fb)
                    } else {
                        dedupedFields[cat] = trimmed
                        seenNames.add(trimmed)
                    }
                }
            }

            BuildRecommendation(
                label = label,
                name = name,
                cpu = dedupedFields["CPU"]!!,
                gpu = dedupedFields["GPU"]!!,
                motherboard = dedupedFields["MOTHERBOARD"]!!,
                ram = dedupedFields["RAM"]!!,
                storage = dedupedFields["STORAGE"]!!,
                psu = dedupedFields["PSU"]!!,
                wattage = parts.wattages[index],
                wattageNote = parts.wattageNotes[index],
                bottleneck = parts.bottlenecks[index],
                bottleneckNote = parts.bottleneckNotes[index]
            )
        }
    }

    enum class ComponentTier {
        LEGACY_BUDGET, MIDRANGE, HIGH_END_CURRENT, ENTHUSIAST_FLAGSHIP
    }

    fun estimateComponentPerformanceTier(component: String, category: String): ComponentTier {
        val normalized = component.lowercase()
        if (category == "gpu") {
            return when {
                normalized.contains("rtx 20") || normalized.contains("gtx 16") ||
                    normalized.contains("rx 5") || normalized.contains("rx 4") ||
                    normalized.contains("rx vega") || normalized.contains("gtx 10") ->
                    ComponentTier.LEGACY_BUDGET

                normalized.contains("rtx 3050") || normalized.contains("rtx 3060") ||
                    normalized.contains("rtx 4060") || normalized.contains("rx 66") ||
                    normalized.contains("rx 67") || normalized.contains("rx 76") ||
                    normalized.contains("rx 7700") ->
                    ComponentTier.MIDRANGE

                normalized.contains("rtx 3070") || normalized.contains("rtx 3080") ||
                    normalized.contains("rtx 4070") || normalized.contains("rtx 4080") ||
                    normalized.contains("rx 68") || normalized.contains("rx 69") ||
                    normalized.contains("rx 7800") || normalized.contains("rx 7900 xt") ||
                    normalized.contains("rx 9060") || normalized.contains("rx 9070") ->
                    ComponentTier.HIGH_END_CURRENT

                normalized.contains("rtx 3090") || normalized.contains("rtx 4090") ||
                    normalized.contains("rtx 5080") || normalized.contains("rtx 5090") ||
                    normalized.contains("rx 7900 xtx") || normalized.contains("rx 9090") ->
                    ComponentTier.ENTHUSIAST_FLAGSHIP

                else -> ComponentTier.MIDRANGE
            }
        }
        if (category == "cpu") {
            return when {
                normalized.contains("ryzen 5 2") || normalized.contains("ryzen 5 3") ||
                    normalized.contains("i3-10") || normalized.contains("i3-12") ||
                    normalized.contains("ryzen 3 4") || normalized.contains("ryzen 3 5") ||
                    normalized.contains("ryzen 5 5500") || normalized.contains("ryzen 5 5600") ||
                    normalized.contains("ryzen 5 5600g") || normalized.contains("ryzen 5 5600x") ->
                    ComponentTier.LEGACY_BUDGET

                normalized.contains("ryzen 5 5") || normalized.contains("ryzen 5 7") ||
                    normalized.contains("ryzen 5 8") ||
                    normalized.contains("i5-12") || normalized.contains("i5-13") ||
                    normalized.contains("i5-14") || normalized.contains("ryzen 7 5700x") ->
                    ComponentTier.MIDRANGE

                normalized.contains("ryzen 7 7") || normalized.contains("ryzen 7 8") ||
                    normalized.contains("ryzen 7 9") || normalized.contains("ryzen 9 7900") ||
                    normalized.contains("i7-13") || normalized.contains("i7-14") ->
                    ComponentTier.HIGH_END_CURRENT

                normalized.contains("ryzen 9 8") || normalized.contains("ryzen 9 9") ||
                    normalized.contains("ryzen 9 7950") ||
                    normalized.contains("i9-13") || normalized.contains("i9-14") ||
                    normalized.contains("threadripper") ->
                    ComponentTier.ENTHUSIAST_FLAGSHIP

                else -> ComponentTier.MIDRANGE
            }
        }
        return ComponentTier.MIDRANGE
    }

    fun getBuildRecommendationsSeed(category: String): Map<String, Any> {
        return mapOf(
            "items" to getBuildRecommendations(category).map { recommendation ->
                mapOf(
                    "label" to recommendation.label,
                    "name" to recommendation.name,
                    "cpu" to recommendation.cpu,
                    "gpu" to recommendation.gpu,
                    "motherboard" to recommendation.motherboard,
                    "ram" to recommendation.ram,
                    "storage" to recommendation.storage,
                    "psu" to recommendation.psu,
                    "wattage" to recommendation.wattage,
                    "wattageNote" to recommendation.wattageNote,
                    "bottleneck" to recommendation.bottleneck,
                    "bottleneckNote" to recommendation.bottleneckNote
                )
            }
        )
    }

    fun getUpgradePath(component: String, hasExistingBuild: Boolean, tier: BuildTier = BuildTier.ENTRY): UpgradePath {
        val category = resolveCategory(component)
        val componentTier = estimateComponentPerformanceTier(component, category)
        return when (tier) {
            BuildTier.ENTRY -> buildEntryUpgradePath(category, component, hasExistingBuild, componentTier)
            BuildTier.MID -> buildMidUpgradePath(category, component, hasExistingBuild, componentTier)
            BuildTier.HIGH -> buildHighUpgradePath(category, component, hasExistingBuild, componentTier)
        }
    }

    fun detectBuildTier(currentBuild: CurrentBuild): BuildTier {
        fun rankOf(name: String, category: String): Double {
            if (name.isBlank()) return 2.0
            val t = estimateComponentPerformanceTier(name, category)
            return when (t) {
                ComponentTier.LEGACY_BUDGET -> 1.0
                ComponentTier.MIDRANGE -> 2.0
                ComponentTier.HIGH_END_CURRENT -> 3.0
                ComponentTier.ENTHUSIAST_FLAGSHIP -> 4.0
            }
        }
        val gpuR = rankOf(currentBuild.gpu, "gpu")
        val cpuR = rankOf(currentBuild.cpu, "cpu")
        val moboR = rankOf(currentBuild.motherboard, "motherboard")
        val ramR = rankOf(currentBuild.ram, "ram")
        val stR = rankOf(currentBuild.storage, "storage")
        val psuR = rankOf(currentBuild.psu, "psu")
        val weighted = (gpuR * 0.60) + (cpuR * 0.30) + ((moboR + ramR + stR + psuR) / 4.0) * 0.10
        return when {
            weighted < 1.6 -> BuildTier.ENTRY
            weighted < 2.6 -> BuildTier.MID
            else -> BuildTier.HIGH
        }
    }

    fun capRecommendationTier(requested: BuildTier, currentBuildTier: BuildTier): BuildTier {
        return when (currentBuildTier) {
            BuildTier.ENTRY -> BuildTier.ENTRY
            BuildTier.MID -> if (requested == BuildTier.HIGH) BuildTier.MID else requested
            BuildTier.HIGH -> requested
        }
    }

    private enum class Platform {
        AM4_DDR4, AM5_DDR5, LGA1700_DDR4, LGA1700_DDR5, LGA1851_DDR5, UNKNOWN
    }

    private enum class Socket {
        AM4, AM5, LGA1700, LGA1851, UNKNOWN
    }

    private fun platformSocket(p: Platform): Socket = when (p) {
        Platform.AM4_DDR4 -> Socket.AM4
        Platform.AM5_DDR5 -> Socket.AM5
        Platform.LGA1700_DDR4, Platform.LGA1700_DDR5 -> Socket.LGA1700
        Platform.LGA1851_DDR5 -> Socket.LGA1851
        Platform.UNKNOWN -> Socket.UNKNOWN
    }

    private fun calculateRequiredPsu(cpuName: String, gpuName: String, overclock: Boolean = false): Int {
        val cpu = HardwareDatabase.cpuTdpWatts(cpuName)
        val gpu = HardwareDatabase.gpuTdpWatts(gpuName)
        val rest = 75
        val base = cpu + gpu + rest
        val headroom = if (overclock) 1.35 else 1.20
        val raw = (base * headroom).toInt()
        return listOf(550, 650, 750, 850, 1000, 1200).firstOrNull { it >= raw } ?: raw
    }

    private fun checkBiosSupport(moboName: String, cpuName: String): Pair<Boolean, String> {
        val m = moboName.lowercase()
        val c = cpuName.lowercase()
        val b450Like = listOf("b450", "a520", "b350", "x470")
        val b550x570Like = listOf("b550", "x570")

        val isAm4Board = b450Like.any { m.contains(it) } || b550x570Like.any { m.contains(it) }
        if (!isAm4Board) return true to ""

        val ryzen5000 = c.contains("ryzen 7 5700x3d") || c.contains("ryzen 7 5800x3d") ||
            c.contains("ryzen 5 5600") || c.contains("ryzen 7 5700x") || c.contains("ryzen 5 5500")

        if (b450Like.any { m.contains(it) } && ryzen5000) {
            return false to "B450/A520/X470/B350 motherboard may require a BIOS update before supporting Ryzen 5000 / 5000X3D CPUs. Check manufacturer BIOS page for latest AGESA."
        }
        return true to ""
    }

    private fun sameModelName(a: String, b: String): Boolean {
        fun normalize(s: String) = s.lowercase()
            .replace(Regex("""[^\p{Alnum}]"""), "")
            .replace("nvidia", "")
            .replace("amd", "")
            .replace("intel", "")
            .replace("radeon", "")
            .replace("geforce", "")
            .replace("core", "")
            .trim()
        return normalize(a) == normalize(b) && normalize(a).isNotBlank()
    }

    fun isActualUpgrade(category: String, currentName: String, targetName: String): Boolean {
        if (currentName.isBlank() || targetName.isBlank()) return true
        if (sameModelName(currentName, targetName)) return false
        val currentTier = estimateComponentPerformanceTier(currentName, category)
        val targetTier = estimateComponentPerformanceTier(targetName, category)
        val rank = mapOf(
            ComponentTier.LEGACY_BUDGET to 1,
            ComponentTier.MIDRANGE to 2,
            ComponentTier.HIGH_END_CURRENT to 3,
            ComponentTier.ENTHUSIAST_FLAGSHIP to 4
        )
        val curr = rank[currentTier] ?: 1
        val tgt = rank[targetTier] ?: 1
        if (category == "gpu") {
            val curVram = HardwareDatabase.gpuVramGb(currentName)
            val tgtVram = HardwareDatabase.gpuVramGb(targetName)
            val curTdp = HardwareDatabase.gpuTdpWatts(currentName)
            val tgtTdp = HardwareDatabase.gpuTdpWatts(targetName)
            if (tgt > curr) return true
            if (tgt == curr && (tgtVram > curVram || tgtTdp > curTdp + 20)) return true
            return false
        }
        return tgt >= curr
    }

    fun assessCpuGpuBalance(cpuName: String, gpuName: String): HardwareCompatibilityResult {
        val issues = mutableListOf<String>()
        val cpuTier = estimateComponentPerformanceTier(cpuName, "cpu")
        val gpuTier = estimateComponentPerformanceTier(gpuName, "gpu")
        val rank = mapOf(
            ComponentTier.LEGACY_BUDGET to 1,
            ComponentTier.MIDRANGE to 2,
            ComponentTier.HIGH_END_CURRENT to 3,
            ComponentTier.ENTHUSIAST_FLAGSHIP to 4
        )
        val cr = rank[cpuTier] ?: 2
        val gr = rank[gpuTier] ?: 2
        val delta = gr - cr
        val (balance, risk, bottleneck) = when {
            delta <= -2 -> Triple("POOR / GPU-limited / CPU overkill", "Low GPU bottleneck", "GPU")
            delta == -1 -> Triple("FAIR / Mild CPU overkill", "Very low", "GPU")
            delta == 0 -> Triple("GOOD / Balanced for gaming", "None", "None")
            delta == 1 -> Triple("GOOD / Slight CPU headroom", "Low", "CPU (minor)")
            delta == 2 -> Triple("POOR / CPU-limited risk in many scenarios", "Medium", "CPU")
            else -> Triple("POOR / Severe CPU bottleneck at 1080p/1440p", "High", "CPU")
        }
        if (delta >= 2) issues.add("Low-end CPU paired with high-end GPU — expect CPU bottleneck at 1080p and 1440p.")
        if (delta <= -2) issues.add("High-end CPU paired with weak GPU — GPU will be the bottleneck.")
        return HardwareCompatibilityResult(
            isHardwareCompatible = true,
            performanceBalance = balance,
            bottleneckRisk = risk,
            bottleneckComponent = bottleneck,
            issues = issues
        )
    }

    fun validateBuildRecommendation(build: BuildRecommendation, currentBuild: CurrentBuild? = null): BuildValidationResult {
        val criticals = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val info = mutableListOf<String>()

        val cpuSocket = detectSocketByName(build.cpu)
        val moboSocket = detectSocketByName(build.motherboard)
        if (cpuSocket != Socket.UNKNOWN && moboSocket != Socket.UNKNOWN && cpuSocket != moboSocket) {
            criticals.add("CHECK 1 FAIL: CPU socket ($cpuSocket) != Motherboard socket ($moboSocket). CPU=${build.cpu}, Mobo=${build.motherboard}")
        }
        val cpuMem = when (cpuSocket) {
            Socket.AM5, Socket.LGA1851 -> "DDR5"
            Socket.AM4 -> "DDR4"
            Socket.LGA1700 -> null
            Socket.UNKNOWN -> null
        }
        val moboMem = HardwareDatabase.moboMemoryType(build.motherboard)
        if (cpuMem != null && moboMem != null && cpuMem != moboMem) {
            criticals.add("CHECK 2 FAIL: CPU memory support ($cpuMem) != Motherboard RAM type ($moboMem).")
        }
        val ramLower = build.ram.lowercase()
        val ramType = when {
            ramLower.contains("ddr5") -> "DDR5"
            ramLower.contains("ddr4") -> "DDR4"
            else -> null
        }
        if (moboMem != null && ramType != null && moboMem != ramType) {
            criticals.add("CHECK 3 FAIL: Motherboard requires $moboMem but RAM is $ramType (${build.ram}).")
        }
        if (currentBuild != null) {
            val cpuIsSame = sameModelName(currentBuild.cpu, build.cpu)
            val gpuIsSame = sameModelName(currentBuild.gpu, build.gpu)
            val mbIsSame = sameModelName(currentBuild.motherboard, build.motherboard)
            val ramIsSame = sameModelName(currentBuild.ram, build.ram)
            val stIsSame = sameModelName(currentBuild.storage, build.storage)
            val psuIsSame = sameModelName(currentBuild.psu, build.psu)
            if (!cpuIsSame && !isActualUpgrade("cpu", currentBuild.cpu, build.cpu)) {
                criticals.add("CHECK 9/10 FAIL: CPU upgrade '${build.cpu}' is not better than current '${currentBuild.cpu}'.")
            }
            if (!gpuIsSame && !isActualUpgrade("gpu", currentBuild.gpu, build.gpu)) {
                criticals.add("CHECK 9/10 FAIL: GPU upgrade '${build.gpu}' is not better than current '${currentBuild.gpu}'.")
            }
            if (!mbIsSame && !isActualUpgrade("motherboard", currentBuild.motherboard, build.motherboard)) {
                criticals.add("CHECK 9/10 FAIL: Motherboard upgrade '${build.motherboard}' is not better than current '${currentBuild.motherboard}'.")
            }
            if (!ramIsSame && !isActualUpgrade("ram", currentBuild.ram, build.ram)) {
                warnings.add("CHECK 9 WARN: RAM upgrade capacity is same or lower than current.")
            }
            if (!stIsSame && !isActualUpgrade("storage", currentBuild.storage, build.storage)) {
                warnings.add("CHECK 9 WARN: Storage capacity is not larger than current.")
            }
            val curMbSocket = detectSocketByName(currentBuild.motherboard)
            val newMbSocket = detectSocketByName(build.motherboard)
            val newCpuSocket = detectSocketByName(build.cpu)
            val canKeep = curMbSocket != Socket.UNKNOWN && newCpuSocket == curMbSocket &&
                moboMem == HardwareDatabase.moboMemoryType(currentBuild.motherboard)
            if (canKeep && !mbIsSame) {
                warnings.add("CHECK 11 WARN: Current motherboard (${currentBuild.motherboard}) appears compatible with new CPU (${build.cpu}). Consider keeping it instead of '${build.motherboard}'.")
            }
        }
        val requiredPsu = calculateRequiredPsu(build.cpu, build.gpu)
        val provided = HardwareDatabase.psuWattage(build.psu)
        if (provided < requiredPsu) {
            criticals.add("CHECK 6 FAIL: PSU ${provided}W is insufficient. Estimated required = ${requiredPsu}W (CPU+GPU+rest with headroom).")
        } else if (provided >= requiredPsu * 2) {
            warnings.add("CHECK 6 WARN: PSU ${provided}W is oversized for ~${requiredPsu}W actual need. Consider value downgrade.")
        }
        val (biosOk, biosMsg) = checkBiosSupport(build.motherboard, build.cpu)
        if (!biosOk) warnings.add("BIOS: $biosMsg")
        val balance = assessCpuGpuBalance(build.cpu, build.gpu)
        if (balance.bottleneckRisk == "High" || balance.bottleneckRisk == "Medium") {
            warnings.add("BALANCE: ${balance.performanceBalance} (${balance.bottleneckRisk} ${balance.bottleneckComponent} risk).")
        }
        if (balance.issues.isNotEmpty()) warnings.addAll(balance.issues)
        info.add("Estimated system PSU requirement: ~${requiredPsu}W; provided=${provided}W.")
        info.add("CPU↔GPU balance: ${balance.performanceBalance}. Bottleneck: ${balance.bottleneckRisk}.")
        return BuildValidationResult(
            isDisplayable = criticals.isEmpty(),
            criticalIssues = criticals,
            warnings = warnings,
            info = info
        )
    }

    private fun isSameSocket(a: Platform, b: Platform): Boolean {
        val sa = platformSocket(a)
        val sb = platformSocket(b)
        return sa != Socket.UNKNOWN && sb != Socket.UNKNOWN && sa == sb
    }

    private fun detectSocketByName(name: String): Socket {
        val n = name.lowercase()
        return when {
            n.contains("ryzen 5 7") || n.contains("ryzen 7 7") || n.contains("ryzen 9 7") ||
                n.contains("ryzen 5 8") || n.contains("ryzen 7 8") || n.contains("ryzen 9 8") ||
                n.contains("ryzen 5 9") || n.contains("ryzen 7 9") || n.contains("ryzen 9 9") ||
                n.contains("b650") || n.contains("x670") || n.contains("x870") || n.contains("a620") -> Socket.AM5
            n.contains("ryzen 5 5") || n.contains("ryzen 7 5") || n.contains("ryzen 3 3") ||
                n.contains("ryzen 5 3") || n.contains("ryzen 7 5700x") || n.contains("ryzen 3 2") ||
                n.contains("ryzen 5 2") ||
                n.contains("b450") || n.contains("b550") || n.contains("x570") ||
                n.contains("a520") || n.contains("x470") || n.contains("b350") -> Socket.AM4
            (n.contains("ultra") && (n.contains(" 2") || n.contains("-2"))) ||
                n.contains("i5-2") || n.contains("i7-2") || n.contains("i9-2") ||
                n.contains("z890") || n.contains("b860") || n.contains("h810") -> Socket.LGA1851
            n.contains("i3-12") || n.contains("i3-13") || n.contains("i3-14") ||
                n.contains("i5-12") || n.contains("i5-13") || n.contains("i5-14") ||
                n.contains("i7-12") || n.contains("i7-13") || n.contains("i7-14") ||
                n.contains("i9-12") || n.contains("i9-13") || n.contains("i9-14") ||
                n.contains("z790") || n.contains("z690") || n.contains("b760") ||
                n.contains("b660") || n.contains("h770") || n.contains("h610") ||
                n.contains("h670") -> Socket.LGA1700
            else -> Socket.UNKNOWN
        }
    }

    private data class PlatformJumpInfo(
        val needsJump: Boolean,
        val message: String,
        val targetPlatform: Platform,
        val requiresCpu: Boolean,
        val requiresMotherboard: Boolean,
        val requiresRam: Boolean
    )

    private fun dedupeUpgradeComponents(
        components: List<UpgradeComponent>,
        excludeCategories: Set<String> = emptySet(),
        maxSize: Int = 6
    ): List<UpgradeComponent> {
        val result = mutableListOf<UpgradeComponent>()
        val seenCats = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        val excl = excludeCategories.map { it.uppercase() }.toSet()
        for (c in components) {
            if (result.size >= maxSize) break
            val cat = c.category.uppercase().trim()
            val nm = c.name.trim().lowercase()
            if (cat in excl) continue
            if (cat in seenCats) continue
            if (nm in seenNames) continue
            result.add(c)
            seenCats.add(cat)
            seenNames.add(nm)
        }
        return result
    }

    private fun enforceOnePerCategory(components: List<UpgradeComponent>): List<UpgradeComponent> {
        val result = mutableListOf<UpgradeComponent>()
        val seenCats = mutableSetOf<String>()
        for (c in components) {
            val cat = c.category.uppercase().trim()
            if (cat in seenCats) continue
            val resolvedCat = resolveCategory(cat)
            if (resolvedCat in seenCats) continue
            result.add(c)
            seenCats.add(cat)
            seenCats.add(resolvedCat.uppercase())
        }
        return result
    }

    private fun normalizeRamCapacity(name: String): Int {
        val n = name.lowercase()
        val gbMatch = Regex("""(\d+)\s*gb""").find(n)
        val totalMatch = Regex("""\((\d+)\s*[x×]\s*\d+\)""").find(n)
        val gb = gbMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val perStick = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val stickCount = Regex("""[x×]\s*(\d+)""").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when {
            gb > 0 -> gb
            perStick > 0 && stickCount > 0 -> perStick * stickCount
            else -> 0
        }
    }

    private fun normalizeRamSpeed(name: String): Int {
        val n = name.lowercase()
        val speedMatch = Regex("""ddr[45][- ]?(\d+)""").find(n)
        return speedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun isRamSufficient(currentRam: String, targetTier: BuildTier): Boolean {
        if (currentRam.isBlank()) return false
        val currentGb = normalizeRamCapacity(currentRam)
        val currentSpeed = normalizeRamSpeed(currentRam)
        val requiredGb = when (targetTier) {
            BuildTier.ENTRY -> 16
            BuildTier.MID -> 32
            BuildTier.HIGH -> 32
        }
        val requiredSpeed = when (targetTier) {
            BuildTier.ENTRY -> 3200
            BuildTier.MID -> 3600
            BuildTier.HIGH -> 5600
        }
        val currentIsDdr5 = currentRam.lowercase().contains("ddr5")
        val currentIsDdr4 = currentRam.lowercase().contains("ddr4") || currentSpeed in 2133..4000
        val matchesDdr5Tier = targetTier == BuildTier.HIGH && currentIsDdr5
        val matchesDdr4Tier = targetTier != BuildTier.HIGH && currentIsDdr4
        val gbOk = currentGb >= requiredGb
        val speedOk = currentSpeed >= requiredSpeed || currentGb >= requiredGb * 2
        return gbOk && (speedOk || matchesDdr5Tier || matchesDdr4Tier)
    }

    private fun keepCurrentComponent(category: String, existingName: String, baseScore: Int = 90): UpgradeComponent {
        val catNorm = resolveCategory(category)
        val catLabel = catNorm.uppercase().replaceFirstChar { it.uppercase() }
        val (displayName, description) = when (catNorm) {
            "ram" -> {
                val niceName = if (existingName.isNotBlank()) existingName else "current RAM"
                "Keep the Existing RAM" to
                    "The $niceName has sufficient capacity and speed for this tier — no need to replace to maintain balance and compatibility."
            }
            else -> {
                val niceName = if (existingName.isNotBlank()) existingName else "current $catLabel"
                "Keep the Existing $catLabel" to
                    "The $niceName is sufficient for this tier — no need to replace to maintain balance and compatibility."
            }
        }
        return UpgradeComponent(
            category = category.uppercase(),
            name = displayName,
            description = description,
            compatibility = baseScore.coerceIn(85, 98),
            progressPercent = (baseScore.coerceIn(85, 98)) / 100f
        )
    }

    private fun detectPlatform(currentBuild: CurrentBuild): Platform {
        val mbNorm = currentBuild.motherboard.lowercase()
        val cpuNorm = currentBuild.cpu.lowercase()
        val ramNorm = currentBuild.ram.lowercase()
        val hasDdr5 = ramNorm.contains("ddr5")
        val hasDdr4 = ramNorm.contains("ddr4") || (!hasDdr5 && ramNorm.isNotBlank())

        val hasAm4Mobo = mbNorm.contains("b450") || mbNorm.contains("b550") || mbNorm.contains("x570") ||
            mbNorm.contains("a520") || mbNorm.contains("x470") || mbNorm.contains("b350")
        val hasAm4Cpu = cpuNorm.contains("ryzen 5 5") || cpuNorm.contains("ryzen 7 5") ||
            cpuNorm.contains("ryzen 5 3") || cpuNorm.contains("ryzen 3 3") ||
            cpuNorm.contains("ryzen 7 5700x") || cpuNorm.contains("ryzen 5 2") ||
            cpuNorm.contains("ryzen 3 2")
        val hasAm5Mobo = mbNorm.contains("b650") || mbNorm.contains("x670") || mbNorm.contains("x870") ||
            mbNorm.contains("a620")
        val hasAm5Cpu = cpuNorm.contains("ryzen 5 7") || cpuNorm.contains("ryzen 7 7") || cpuNorm.contains("ryzen 9 7") ||
            cpuNorm.contains("ryzen 5 8") || cpuNorm.contains("ryzen 7 8") || cpuNorm.contains("ryzen 9 8") ||
            cpuNorm.contains("ryzen 5 9") || cpuNorm.contains("ryzen 7 9") || cpuNorm.contains("ryzen 9 9")
        val hasLga1851Mobo = mbNorm.contains("z890") || mbNorm.contains("b860") || mbNorm.contains("h810")
        val hasLga1851Cpu = (cpuNorm.contains("ultra") && (cpuNorm.contains(" 2") || cpuNorm.contains("-2"))) ||
            cpuNorm.contains("i5-2") || cpuNorm.contains("i7-2") || cpuNorm.contains("i9-2")
        val hasDdr4OnlyLgaMobo = mbNorm.contains("h610") || mbNorm.contains("h670") ||
            mbNorm.contains("b660") || mbNorm.contains("z690")
        val hasLga1700Mobo = hasDdr4OnlyLgaMobo ||
            mbNorm.contains("z790") || mbNorm.contains("b760") || mbNorm.contains("h770")
        val hasLga1700Cpu = cpuNorm.contains("i3-12") || cpuNorm.contains("i3-13") || cpuNorm.contains("i3-14") ||
            cpuNorm.contains("i5-12") || cpuNorm.contains("i5-13") ||
            cpuNorm.contains("i5-14") || cpuNorm.contains("i7-12") ||
            cpuNorm.contains("i7-13") || cpuNorm.contains("i7-14") ||
            cpuNorm.contains("i9-12") || cpuNorm.contains("i9-13") || cpuNorm.contains("i9-14")
        val hasExplicitDdr5Mobo = mbNorm.contains("z790") && mbNorm.contains("ddr5") ||
            mbNorm.contains("b760") && mbNorm.contains("ddr5") ||
            mbNorm.contains("h770") && mbNorm.contains("ddr5")

        return when {
            (hasLga1851Mobo || hasLga1851Cpu) -> Platform.LGA1851_DDR5
            (hasAm5Mobo || hasAm5Cpu) -> if (hasDdr4) Platform.UNKNOWN else Platform.AM5_DDR5
            (hasAm4Mobo || hasAm4Cpu) -> Platform.AM4_DDR4
            hasDdr4OnlyLgaMobo -> Platform.LGA1700_DDR4
            (hasLga1700Mobo || hasLga1700Cpu) -> when {
                hasExplicitDdr5Mobo || hasDdr5 -> Platform.LGA1700_DDR5
                else -> Platform.LGA1700_DDR4
            }
            else -> Platform.UNKNOWN
        }
    }

    private fun detectComponentPlatform(componentName: String, category: String): Platform {
        val n = componentName.lowercase()
        when (category) {
            "cpu" -> return when {
                n.contains("ryzen 5 7") || n.contains("ryzen 7 7") || n.contains("ryzen 9 7") ||
                    n.contains("ryzen 5 8") || n.contains("ryzen 7 8") || n.contains("ryzen 9 8") ||
                    n.contains("ryzen 5 9") || n.contains("ryzen 7 9") || n.contains("ryzen 9 9") -> Platform.AM5_DDR5
                n.contains("ryzen 5 5") || n.contains("ryzen 7 5") || n.contains("ryzen 3 3") ||
                    n.contains("ryzen 5 3") || n.contains("ryzen 7 5700x") -> Platform.AM4_DDR4
                (n.contains("ultra") && (n.contains(" 2") || n.contains("-2"))) ||
                    n.contains("i5-2") || n.contains("i7-2") || n.contains("i9-2") -> Platform.LGA1851_DDR5
                n.contains("i3-12") || n.contains("i3-13") || n.contains("i3-14") ||
                    n.contains("i5-12") || n.contains("i5-13") || n.contains("i5-14") ||
                    n.contains("i7-12") || n.contains("i7-13") || n.contains("i7-14") ||
                    n.contains("i9-12") || n.contains("i9-13") || n.contains("i9-14") -> Platform.UNKNOWN
                else -> Platform.UNKNOWN
            }
            "motherboard" -> return when {
                n.contains("z890") || n.contains("b860") || n.contains("h810") -> Platform.LGA1851_DDR5
                n.contains("b650") || n.contains("x670") || n.contains("x870") || n.contains("a620") -> Platform.AM5_DDR5
                n.contains("b450") || n.contains("b550") || n.contains("x570") ||
                    n.contains("a520") || n.contains("x470") -> Platform.AM4_DDR4
                n.contains("h610") || n.contains("h670") || n.contains("b660") || n.contains("z690") -> Platform.LGA1700_DDR4
                n.contains("z790") || n.contains("b760") || n.contains("h770") -> {
                    if (n.contains("ddr5")) Platform.LGA1700_DDR5 else Platform.LGA1700_DDR4
                }
                else -> Platform.UNKNOWN
            }
            "ram" -> return Platform.UNKNOWN
            else -> return Platform.UNKNOWN
        }
    }

    private fun detectPlatformJumpInfo(
        searchedComponent: String,
        currentBuild: CurrentBuild,
        searchedCategory: String,
        searchedCompTier: ComponentTier
    ): PlatformJumpInfo {
        val currentPlatform = detectPlatform(currentBuild)
        val searchedPlatform = detectComponentPlatform(searchedComponent, searchedCategory)
        val currentSocket = platformSocket(currentPlatform)
        val searchedSocket = if (searchedPlatform != Platform.UNKNOWN) {
            platformSocket(searchedPlatform)
        } else {
            detectSocketByName(searchedComponent)
        }

        val searchedRank = when (searchedCompTier) {
            ComponentTier.LEGACY_BUDGET -> 1
            ComponentTier.MIDRANGE -> 2
            ComponentTier.HIGH_END_CURRENT -> 3
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 4
        }
        val cpuNorm = currentBuild.cpu.takeIf { it.isNotBlank() }
        val currentCpuTier = cpuNorm?.let {
            estimateComponentPerformanceTier(it, "cpu")
        }
        val currentCpuRank = when (currentCpuTier) {
            ComponentTier.LEGACY_BUDGET -> 1
            ComponentTier.MIDRANGE -> 2
            ComponentTier.HIGH_END_CURRENT -> 3
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 4
            null -> 0
        }

        val ramNorm = currentBuild.ram.lowercase()
        val hasCurrentDdr5 = ramNorm.contains("ddr5")
        val hasCurrentDdr4 = ramNorm.contains("ddr4") || (!hasCurrentDdr5 && ramNorm.isNotBlank())

        val platformLabel = { p: Platform ->
            when (p) {
                Platform.AM5_DDR5 -> "AM5 + DDR5"
                Platform.AM4_DDR4 -> "AM4 + DDR4"
                Platform.LGA1851_DDR5 -> "LGA1851 + DDR5"
                Platform.LGA1700_DDR5 -> "LGA1700 + DDR5"
                Platform.LGA1700_DDR4 -> "LGA1700 + DDR4"
                Platform.UNKNOWN -> "balanced platform"
            }
        }
        val socketLabel = { s: Socket ->
            when (s) {
                Socket.AM5 -> "AM5"
                Socket.AM4 -> "AM4"
                Socket.LGA1700 -> "LGA1700"
                Socket.LGA1851 -> "LGA1851"
                Socket.UNKNOWN -> "current"
            }
        }

        if (searchedCategory == "cpu" || searchedCategory == "motherboard") {
            val differentSocket = currentSocket != Socket.UNKNOWN &&
                searchedSocket != Socket.UNKNOWN &&
                currentSocket != searchedSocket

            val sameSocketDifferentRam = currentSocket == searchedSocket &&
                currentSocket != Socket.UNKNOWN &&
                searchedPlatform != Platform.UNKNOWN &&
                currentPlatform != Platform.UNKNOWN &&
                currentPlatform != searchedPlatform &&
                isSameSocket(currentPlatform, searchedPlatform)

            if (differentSocket) {
                val searchedIsAm5 = searchedSocket == Socket.AM5
                val searchedIsAm4 = searchedSocket == Socket.AM4
                val searchedIsLga = searchedSocket == Socket.LGA1700
                val targetRam = if (searchedIsAm5) "DDR5" else if (hasCurrentDdr5 && searchedIsLga) "DDR5" else "DDR4"
                val target = when {
                    searchedIsAm5 -> Platform.AM5_DDR5
                    searchedIsAm4 -> Platform.AM4_DDR4
                    searchedIsLga && targetRam == "DDR5" -> Platform.LGA1700_DDR5
                    searchedIsLga -> Platform.LGA1700_DDR4
                    else -> currentPlatform
                }
                val needsCpu = searchedCategory != "cpu"
                val needsMobo = searchedCategory != "motherboard"
                val currentRamGen = when {
                    hasCurrentDdr5 -> "DDR5"
                    hasCurrentDdr4 -> "DDR4"
                    else -> "RAM"
                }
                val targetRamLabel = when (target) {
                    Platform.AM5_DDR5, Platform.LGA1700_DDR5, Platform.LGA1851_DDR5 -> "DDR5"
                    Platform.AM4_DDR4, Platform.LGA1700_DDR4 -> "DDR4"
                    Platform.UNKNOWN -> currentRamGen
                }
                val needsRam = currentRamGen != targetRamLabel
                val fromLabel = "${socketLabel(currentSocket)}${if (currentPlatform != Platform.UNKNOWN) " ${platformLabel(currentPlatform).substringAfter("+ ")}" else ""}"
                val toLabel = "${socketLabel(searchedSocket)} + $targetRamLabel"
                return PlatformJumpInfo(
                    needsJump = true,
                    message = "Platform jump detected: $fromLabel → $toLabel — new platform components needed.",
                    targetPlatform = target,
                    requiresCpu = needsCpu,
                    requiresMotherboard = needsMobo,
                    requiresRam = needsRam
                )
            }

            if (sameSocketDifferentRam) {
                val (fromRam, toRam) = when (currentPlatform to searchedPlatform) {
                    Platform.LGA1700_DDR4 to Platform.LGA1700_DDR5 -> "DDR4" to "DDR5"
                    Platform.LGA1700_DDR5 to Platform.LGA1700_DDR4 -> "DDR5" to "DDR4"
                    Platform.LGA1851_DDR5 to Platform.LGA1700_DDR4 -> "DDR5" to "DDR4"
                    Platform.LGA1700_DDR4 to Platform.LGA1851_DDR5 -> "DDR4" to "DDR5"
                    else -> null to null
                }
                if (fromRam != null && toRam != null) {
                    val motherboardIsBeingSwapped = searchedCategory == "motherboard"
                    if (!motherboardIsBeingSwapped) {
                        return PlatformJumpInfo(
                            needsJump = false,
                            message = "Preserving current RAM generation ($fromRam) since motherboard is not being replaced. Staying on ${platformLabel(currentPlatform)}.",
                            targetPlatform = currentPlatform,
                            requiresCpu = false,
                            requiresMotherboard = false,
                            requiresRam = false
                        )
                    }
                    val needsCpu = false
                    val needsMobo = true
                    val needsRam = true
                    return PlatformJumpInfo(
                        needsJump = true,
                        message = "RAM generation shift: $fromRam → $toRam within ${socketLabel(currentSocket)}. Motherboard + RAM both replaced.",
                        targetPlatform = searchedPlatform,
                        requiresCpu = needsCpu,
                        requiresMotherboard = needsMobo,
                        requiresRam = needsRam
                    )
                }
            }
        }

        if (searchedCategory == "gpu" && currentCpuRank > 0 && searchedRank - currentCpuRank >= 2) {
            val preferCurrentSocket = currentSocket != Socket.UNKNOWN
            val targetPlatform = if (preferCurrentSocket) {
                when (currentSocket) {
                    Socket.LGA1700 -> if (hasCurrentDdr5) Platform.LGA1700_DDR5 else Platform.LGA1700_DDR4
                    Socket.LGA1851 -> Platform.LGA1851_DDR5
                    Socket.AM5 -> Platform.AM5_DDR5
                    Socket.AM4 -> if (searchedRank >= 3) Platform.AM5_DDR5 else Platform.AM4_DDR4
                    Socket.UNKNOWN -> if (searchedRank >= 3) Platform.AM5_DDR5 else Platform.AM4_DDR4
                }
            } else {
                if (searchedRank >= 3) Platform.AM5_DDR5 else Platform.AM4_DDR4
            }
            val targetLabel = platformLabel(targetPlatform)
            val sameSocketAndRam = isSameSocket(targetPlatform, currentPlatform) && targetPlatform == currentPlatform
            val targetSocket = platformSocket(targetPlatform)
            val genuineSocketChange = currentSocket != Socket.UNKNOWN && targetSocket != Socket.UNKNOWN && currentSocket != targetSocket
            if (sameSocketAndRam) {
                return PlatformJumpInfo(
                    needsJump = false,
                    message = "GPU is stronger tier than current CPU — a CPU upgrade within the same $targetLabel platform is recommended to avoid bottleneck (no platform change needed).",
                    targetPlatform = currentPlatform,
                    requiresCpu = true,
                    requiresMotherboard = false,
                    requiresRam = false
                )
            }
            return PlatformJumpInfo(
                needsJump = genuineSocketChange,
                message = if (genuineSocketChange) {
                    "GPU is significantly stronger than current CPU — platform jump to $targetLabel recommended to avoid bottleneck."
                } else {
                    "GPU is stronger tier than current CPU — consider a CPU upgrade for a balanced build."
                },
                targetPlatform = targetPlatform,
                requiresCpu = true,
                requiresMotherboard = genuineSocketChange,
                requiresRam = when {
                    genuineSocketChange -> when (targetPlatform) {
                        Platform.AM5_DDR5, Platform.LGA1700_DDR5, Platform.LGA1851_DDR5 -> !hasCurrentDdr5 || currentSocket == Socket.AM4
                        Platform.AM4_DDR4 -> !hasCurrentDdr4 || currentSocket == Socket.AM5
                        Platform.LGA1700_DDR4 -> !hasCurrentDdr4 || currentSocket == Socket.AM5
                        Platform.UNKNOWN -> true
                    }
                    else -> false
                }
            )
        }

        return PlatformJumpInfo(
            needsJump = false,
            message = detectPlatformJump(searchedComponent, currentBuild, searchedCategory),
            targetPlatform = currentPlatform,
            requiresCpu = false,
            requiresMotherboard = false,
            requiresRam = false
        )
    }

    private fun pickPlatformComponents(
        platform: Platform,
        tier: BuildTier,
        existingCategories: Set<String>
    ): List<UpgradeComponent> {
        val comps = mutableListOf<UpgradeComponent>()
        val compatScore = when (tier) {
            BuildTier.ENTRY -> (60..68)
            BuildTier.MID -> (78..84)
            BuildTier.HIGH -> (90..96)
        }

        val platformParts = when (platform to tier) {
            Platform.AM4_DDR4 to BuildTier.ENTRY -> listOf(
                "Ryzen 5 5600", "B550M Pro4", "16GB DDR4-3200",
                "Balanced 6-core AM4 gaming CPU",
                "Reliable B550 AM4 motherboard",
                "Essential dual-channel DDR4 memory"
            )
            Platform.AM4_DDR4 to BuildTier.MID -> listOf(
                "Ryzen 7 5700X3D", "B550 AORUS Elite V2", "32GB DDR4-3600",
                "Best AM4 gaming CPU with 3D V-Cache",
                "Feature-rich AM4 ATX motherboard",
                "High-bandwidth DDR4 gaming RAM"
            )
            Platform.AM4_DDR4 to BuildTier.HIGH -> listOf(
                "Ryzen 7 5700X3D", "X570 AORUS Elite", "32GB DDR4-3600",
                "Top AM4 3D V-Cache gaming CPU",
                "Premium X570 AM4 motherboard with PCIe 4.0",
                "Large high-bandwidth DDR4 memory kit"
            )
            Platform.AM5_DDR5 to BuildTier.ENTRY -> listOf(
                "Ryzen 5 7600", "B650M DS3H", "16GB DDR5-5600",
                "Entry-level 6-core AM5 Zen 4 CPU",
                "Reliable entry B650 AM5 motherboard",
                "Fast DDR5 dual-channel memory"
            )
            Platform.AM5_DDR5 to BuildTier.MID -> listOf(
                "Ryzen 7 8700", "B650 AORUS Elite", "32GB DDR5-6000",
                "High-performance 8-core Zen 5 AM5 CPU (Ryzen 8000 series)",
                "Feature-rich B650 AM5 ATX motherboard",
                "High-bandwidth DDR5 EXPO memory"
            )
            Platform.AM5_DDR5 to BuildTier.HIGH -> listOf(
                "Ryzen 9 7900", "X670E Steel Legend", "64GB DDR5-6400 (2×32GB)",
                "High-performance 12-core Zen 4 AM5 workstation CPU",
                "Premium X670E AM5 platform with full connectivity",
                "Large enthusiast-grade DDR5 memory kit with high bandwidth"
            )
            Platform.LGA1700_DDR4 to BuildTier.ENTRY -> listOf(
                "Intel Core i5-14400F", "B760M-A DDR4", "16GB DDR4-3200",
                "Solid 10-core entry LGA1700 gaming CPU",
                "Reliable B760 LGA1700 DDR4 motherboard",
                "Standard DDR4 dual-channel memory"
            )
            Platform.LGA1700_DDR4 to BuildTier.MID -> listOf(
                "Intel Core i7-14700KF", "B760 Tomahawk DDR4", "32GB DDR4-3600",
                "Strong 20-core LGA1700 gaming + productivity CPU",
                "Feature-rich B760 DDR4 ATX motherboard",
                "High-bandwidth DDR4 memory kit"
            )
            Platform.LGA1700_DDR4 to BuildTier.HIGH -> listOf(
                "Intel Core i9-14900KF", "Z790 Tomahawk DDR4", "32GB DDR4-3600",
                "Flagship 24-core LGA1700 performance CPU",
                "Premium Z790 LGA1700 DDR4 motherboard",
                "Large high-bandwidth DDR4 memory kit"
            )
            Platform.LGA1700_DDR5 to BuildTier.ENTRY -> listOf(
                "Intel Core i5-14400F", "B760M-A WiFi DDR5", "16GB DDR5-5600",
                "Solid entry LGA1700 10-core CPU",
                "Reliable B760 LGA1700 DDR5 motherboard",
                "Fast DDR5 entry-level memory"
            )
            Platform.LGA1700_DDR5 to BuildTier.MID -> listOf(
                "Intel Core i7-14700K", "B760 Tomahawk WiFi DDR5", "32GB DDR5-6000",
                "Strong 20-core gaming + productivity LGA1700 CPU",
                "Feature-rich B760 DDR5 ATX motherboard",
                "High-bandwidth DDR5 XMP memory"
            )
            Platform.LGA1700_DDR5 to BuildTier.HIGH -> listOf(
                "Intel Core i9-14900K", "ROG MAXIMUS Z790 HERO", "64GB DDR5-6800",
                "Extreme 24-core LGA1700 flagship CPU",
                "Flagship ROG MAXIMUS Z790 HERO motherboard",
                "Maximum performance large DDR5 memory kit"
            )
            Platform.LGA1851_DDR5 to BuildTier.ENTRY -> listOf(
                "Intel Core i5-14500", "B860M-A WiFi DDR5", "16GB DDR5-5600",
                "Solid entry LGA1851 10-core CPU",
                "Reliable B860 LGA1851 DDR5 motherboard",
                "Fast DDR5 entry-level memory"
            )
            Platform.LGA1851_DDR5 to BuildTier.MID -> listOf(
                "Intel Core i7-14700K", "B860 Tomahawk WiFi DDR5", "32GB DDR5-6000",
                "Strong 20-core gaming + productivity LGA1851 CPU",
                "Feature-rich B860 DDR5 ATX motherboard",
                "High-bandwidth DDR5 XMP memory"
            )
            Platform.LGA1851_DDR5 to BuildTier.HIGH -> listOf(
                "Intel Core i9-14900K", "ROG MAXIMUS Z890 HERO", "64GB DDR5-6800",
                "Extreme 24-core LGA1851 flagship CPU",
                "Flagship ROG MAXIMUS Z890 HERO motherboard",
                "Maximum performance large DDR5 memory kit"
            )
            else -> listOf(
                "Intel Core i5-14400F", "B760M-A DDR4", "32GB DDR4-3600",
                "Solid 10-core LGA1700 gaming + productivity CPU",
                "Reliable B760 LGA1700 DDR4 motherboard (broad DDR4 compatibility)",
                "High-bandwidth dual-channel DDR4 memory (widest platform compatibility)"
            )
        }
        val cpuName = platformParts[0]
        val mbName = platformParts[1]
        val ramName = platformParts[2]
        val cpuDesc = platformParts[3]
        val mbDesc = platformParts[4]
        val ramDesc = platformParts[5]

        val cpuScore = compatScore.random()
        val mbScore = (cpuScore - 2).coerceAtLeast(60)
        val ramScore = (cpuScore - 4).coerceAtLeast(60)

        val upperExisting = existingCategories.map { it.uppercase() }.toSet()
        if ("CPU" !in upperExisting) {
            comps.add(UpgradeComponent("CPU", cpuName, cpuDesc, cpuScore, cpuScore / 100f))
        }
        if ("MOTHERBOARD" !in upperExisting) {
            comps.add(UpgradeComponent("Motherboard", mbName, mbDesc, mbScore, mbScore / 100f))
        }
        if ("RAM" !in upperExisting) {
            comps.add(UpgradeComponent("RAM", ramName, ramDesc, ramScore, ramScore / 100f))
        }

        return comps
    }

    private fun swapCompanionsForPlatformJump(
        companions: List<UpgradeComponent>,
        jumpInfo: PlatformJumpInfo,
        cappedTier: BuildTier,
        alreadyUsedCategories: Set<String>
    ): List<UpgradeComponent> {
        val companionCount = 5
        val usedNormalized = alreadyUsedCategories.map { resolveCategory(it).uppercase() }.toMutableSet()
        if (!jumpInfo.needsJump) {
            val list = mutableListOf<UpgradeComponent>()
            for (c in companions) {
                val cat = resolveCategory(c.category).uppercase()
                if (cat !in usedNormalized && list.size < companionCount) {
                    list.add(c)
                    usedNormalized.add(cat)
                }
            }
            return enforceOnePerCategory(list).take(companionCount)
        }

        val platformComps = pickPlatformComponents(jumpInfo.targetPlatform, cappedTier, alreadyUsedCategories)
        val result = mutableListOf<UpgradeComponent>()
        result.addAll(platformComps)
        val usedAfterPlatform = (usedNormalized + result.map { resolveCategory(it.category).uppercase() }).toMutableSet()
        for (c in companions) {
            val cat = resolveCategory(c.category).uppercase()
            if (cat !in usedAfterPlatform && result.size < companionCount) {
                result.add(c)
                usedAfterPlatform.add(cat)
            }
        }
        return enforceOnePerCategory(result).take(companionCount)
    }

    fun getUpgradePath(searchedComponent: String, currentBuild: CurrentBuild, tier: BuildTier): UpgradePath {
        val searchedCategory = if (searchedComponent.isNotBlank()) {
            resolveCategory(searchedComponent)
        } else {
            listOf(currentBuild.gpu, currentBuild.cpu, currentBuild.motherboard, currentBuild.ram, currentBuild.storage, currentBuild.psu)
                .firstOrNull { it.isNotBlank() }
                ?.let { resolveCategory(it) } ?: "gpu"
        }
        val effectiveSearchedComponent = if (searchedComponent.isNotBlank()) {
            searchedComponent
        } else {
            currentBuild.componentByName(searchedCategory).takeIf { it.isNotBlank() } ?: searchedCategory.uppercase()
        }
        val searchedCompTier = estimateComponentPerformanceTier(effectiveSearchedComponent, searchedCategory)
        val detectedBuildTier = detectBuildTier(currentBuild)
        val buildTierContext = if (detectedBuildTier == BuildTier.MID && currentBuild.isEmpty) {
            val rank = when (searchedCompTier) {
                ComponentTier.LEGACY_BUDGET -> 1
                ComponentTier.MIDRANGE -> 2
                ComponentTier.HIGH_END_CURRENT -> 3
                ComponentTier.ENTHUSIAST_FLAGSHIP -> 4
            }
            when {
                rank <= 1 -> BuildTier.ENTRY
                rank == 2 -> BuildTier.MID
                else -> BuildTier.HIGH
            }
        } else {
            detectedBuildTier
        }
        val effectiveTier = tier

        val firstComponentName = if (searchedComponent.isNotBlank()) {
            searchedComponent
        } else {
            currentBuild.componentByName(searchedCategory).takeIf { it.isNotBlank() } ?: effectiveSearchedComponent
        }

        val hasExistingBuild = !currentBuild.isEmpty
        val currentPlatform = if (hasExistingBuild) detectPlatform(currentBuild) else Platform.UNKNOWN

        val jumpInfo = if (hasExistingBuild) {
            detectPlatformJumpInfo(
                effectiveSearchedComponent,
                currentBuild,
                searchedCategory,
                searchedCompTier
            )
        } else {
            PlatformJumpInfo(false, "", Platform.UNKNOWN, false, false, false)
        }

        val searchedCompPlatform = detectComponentPlatform(effectiveSearchedComponent, searchedCategory)
        val targetPlatformForResolve = when {
            jumpInfo.needsJump -> jumpInfo.targetPlatform
            searchedCompPlatform != Platform.UNKNOWN -> searchedCompPlatform
            else -> currentPlatform
        }

        val rawCompanions = resolveCompanionUpgrades(
            searchedCategory, searchedCompTier, effectiveTier,
            targetPlatformForResolve, currentBuild, hasExistingBuild
        )

        val alreadyUsedCats = mutableSetOf(searchedCategory.uppercase(), resolveCategory(searchedCategory).uppercase())
        val adjustedCompanionList = swapCompanionsForPlatformJump(
            rawCompanions, jumpInfo, effectiveTier, alreadyUsedCats
        )

        val uniqueAdjusted = enforceOnePerCategory(adjustedCompanionList)
        val uniqueRaw = enforceOnePerCategory(rawCompanions)
        val mergedCompanionsPool = mutableListOf<UpgradeComponent>()
        mergedCompanionsPool.addAll(uniqueAdjusted)
        val adjustedCats = uniqueAdjusted.map { resolveCategory(it.category).uppercase() }.toSet()
        for (rc in uniqueRaw) {
            val rcCat = resolveCategory(rc.category).uppercase()
            if (rcCat !in adjustedCats && rcCat !in alreadyUsedCats) {
                mergedCompanionsPool.add(rc)
            }
        }
        val dedupedMerged = dedupeUpgradeComponents(
            mergedCompanionsPool, excludeCategories = alreadyUsedCats, maxSize = 5)
        val finalCompanionsList = enforceOnePerCategory(dedupedMerged)

        val effectiveAdjustPlatform = if (jumpInfo.needsJump) jumpInfo.targetPlatform else currentPlatform
        val adjustedCompsPreMap = enforceOnePerCategory(finalCompanionsList).take(5)
        val adjustedCompsRaw = adjustedCompsPreMap.map { comp ->
            adjustCompanionForCurrentBuild(
                comp, effectiveTier, buildTierContext,
                effectiveAdjustPlatform, currentBuild, searchedCompTier
            )
        }
        val adjustedComps = enforceOnePerCategory(adjustedCompsRaw)

        val basePath = when (effectiveTier) {
            BuildTier.ENTRY -> buildEntryUpgradePath(searchedCategory, firstComponentName, hasExistingBuild, searchedCompTier, currentBuild, firstComponentName)
            BuildTier.MID -> buildMidUpgradePath(searchedCategory, firstComponentName, hasExistingBuild, searchedCompTier, currentBuild, firstComponentName)
            BuildTier.HIGH -> buildHighUpgradePath(searchedCategory, firstComponentName, hasExistingBuild, searchedCompTier, currentBuild, firstComponentName)
        }

        val baseTierLabel = when (effectiveTier) {
            BuildTier.ENTRY -> "Entry-Level"
            BuildTier.MID -> "Midrange"
            BuildTier.HIGH -> "High-End"
        }
        val buildTierLabel = when (buildTierContext) {
            BuildTier.ENTRY -> "Entry"
            BuildTier.MID -> "Mid"
            BuildTier.HIGH -> "High"
        }
        val jumpNote = if (jumpInfo.needsJump && jumpInfo.message.isNotBlank()) " | ${jumpInfo.message}" else ""
        val title = when {
            effectiveTier == BuildTier.HIGH && buildTierContext == BuildTier.HIGH ->
                "High-End Flagship Upgrade Path"
            else -> basePath.title
        }
        val description = when {
            effectiveTier == BuildTier.HIGH && buildTierContext == BuildTier.HIGH ->
                "Peak-tier flagship-grade components and maximum platform headroom, bringing your ${firstComponentName.ifBlank { "system" }} to the very top of what the current generation can offer.$jumpNote"
            jumpInfo.needsJump ->
                "${basePath.description}$jumpNote"
            else ->
                basePath.description
        }

        val platformNote = if (jumpInfo.needsJump && jumpInfo.message.isNotBlank()) " | ${jumpInfo.message}" else ""

        val firstComponent = UpgradeComponent(
            category = searchedCategory.uppercase(),
            name = firstComponentName,
            description = resolveDescriptionForComponent(searchedCategory, searchedCompTier) + platformNote,
            compatibility = (basePath.components.firstOrNull()?.compatibility ?: 75),
            progressPercent = (basePath.components.firstOrNull()?.progressPercent ?: 0.75f)
        )

        val combinedPreDedupe = listOf(firstComponent) + adjustedComps
        val dedupedByCat = dedupeUpgradeComponents(combinedPreDedupe, maxSize = 6)
        val adjustedComponents = enforceOnePerCategory(dedupedByCat).take(6)

        return UpgradePath(
            title = title,
            description = description,
            tier = basePath.tier,
            components = adjustedComponents,
            stepOne = basePath.stepOne,
            stepTwo = basePath.stepTwo,
            stepThree = basePath.stepThree
        )
    }

    private fun detectPlatformJump(searchedComponent: String, currentBuild: CurrentBuild, searchedCategory: String): String {
        val searchedNorm = searchedComponent.lowercase()
        val mbNorm = currentBuild.motherboard.lowercase()
        val cpuNorm = currentBuild.cpu.lowercase()
        if (searchedCategory == "gpu") return ""
        val hasAm4 = mbNorm.contains("b450") || mbNorm.contains("b550") || mbNorm.contains("x570") ||
            cpuNorm.contains("ryzen 5 5") || cpuNorm.contains("ryzen 7 5")
        val hasAm5 = mbNorm.contains("b650") || mbNorm.contains("x670") || mbNorm.contains("x870") ||
            cpuNorm.contains("ryzen 5 7") || cpuNorm.contains("ryzen 7 7") || cpuNorm.contains("ryzen 9 7") ||
            cpuNorm.contains("ryzen 5 8") || cpuNorm.contains("ryzen 7 8") || cpuNorm.contains("ryzen 9 8") ||
            cpuNorm.contains("ryzen 5 9") || cpuNorm.contains("ryzen 7 9") || cpuNorm.contains("ryzen 9 9")
        val hasLga1700 = mbNorm.contains("z790") || mbNorm.contains("z690") || mbNorm.contains("b760") ||
            mbNorm.contains("b660") || mbNorm.contains("h770") || mbNorm.contains("h610") || mbNorm.contains("h670") ||
            cpuNorm.contains("i3-12") || cpuNorm.contains("i3-13") || cpuNorm.contains("i3-14") ||
            cpuNorm.contains("i5-12") || cpuNorm.contains("i5-13") || cpuNorm.contains("i5-14") ||
            cpuNorm.contains("i7-12") || cpuNorm.contains("i7-13") || cpuNorm.contains("i7-14") || cpuNorm.contains("i9-12") || cpuNorm.contains("i9-13") || cpuNorm.contains("i9-14")
        val needsAm5 = searchedNorm.contains("ryzen 5 7") || searchedNorm.contains("ryzen 7 7") || searchedNorm.contains("ryzen 9 7") ||
            searchedNorm.contains("ryzen 5 8") || searchedNorm.contains("ryzen 7 8") || searchedNorm.contains("ryzen 9 8") ||
            searchedNorm.contains("ryzen 5 9") || searchedNorm.contains("ryzen 7 9") || searchedNorm.contains("ryzen 9 9") ||
            searchedNorm.contains("b650") || searchedNorm.contains("x670") || searchedNorm.contains("x870")
        val needsLga1700 = searchedNorm.contains("i3-12") || searchedNorm.contains("i3-13") || searchedNorm.contains("i3-14") ||
            searchedNorm.contains("i5-12") || searchedNorm.contains("i5-13") || searchedNorm.contains("i5-14") ||
            searchedNorm.contains("i7-13") || searchedNorm.contains("i7-14") || searchedNorm.contains("i9-13") || searchedNorm.contains("i9-14") ||
            searchedNorm.contains("z790") || searchedNorm.contains("h610") || searchedNorm.contains("b660") || searchedNorm.contains("z690") || searchedNorm.contains("b760")
        val needsAm4 = searchedNorm.contains("ryzen 5 5") || searchedNorm.contains("ryzen 7 5700") ||
            searchedNorm.contains("b450") || searchedNorm.contains("b550") || searchedNorm.contains("x570")
        return when {
            hasAm4 && needsAm5 -> "Platform jump detected: AM4 → AM5 motherboard upgrade required."
            hasAm4 && needsLga1700 -> "Platform jump detected: AM4 → LGA1700 motherboard + CPU swap required."
            hasAm5 && needsAm4 -> "Platform jump detected: AM5 → AM4 motherboard downgrade required (unusual)."
            hasAm5 && needsLga1700 -> "Platform jump detected: AM5 → LGA1700 motherboard + CPU swap required."
            hasLga1700 && needsAm4 -> "Platform jump detected: LGA1700 → AM4 motherboard + CPU swap required."
            hasLga1700 && needsAm5 -> "Platform jump detected: LGA1700 → AM5 motherboard + CPU swap required."
            else -> ""
        }
    }

    private fun adjustCompanionForCurrentBuild(
        companion: UpgradeComponent,
        effectiveTier: BuildTier,
        buildTierContext: BuildTier,
        currentPlatform: Platform = Platform.UNKNOWN,
        currentBuild: CurrentBuild = CurrentBuild(),
        searchedCompTier: ComponentTier = ComponentTier.MIDRANGE
    ): UpgradeComponent {
        val cat = resolveCategory(companion.category)
        val compTier = estimateComponentPerformanceTier(companion.name, cat)
        val compRank = when (compTier) {
            ComponentTier.LEGACY_BUDGET -> 1
            ComponentTier.MIDRANGE -> 2
            ComponentTier.HIGH_END_CURRENT -> 3
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 4
        }
        val maxAllowedRank = when (effectiveTier) {
            BuildTier.ENTRY -> 1
            BuildTier.MID -> 2
            BuildTier.HIGH -> 4
        }
        val existingCompName = currentBuild.componentByName(cat)
        val existingCompTier = if (existingCompName.isNotBlank()) {
            estimateComponentPerformanceTier(existingCompName, cat)
        } else null
        val existingRank = existingCompTier?.let {
            when (it) {
                ComponentTier.LEGACY_BUDGET -> 1
                ComponentTier.MIDRANGE -> 2
                ComponentTier.HIGH_END_CURRENT -> 3
                ComponentTier.ENTHUSIAST_FLAGSHIP -> 4
            }
        }

        val minDesiredRank = when (existingRank) {
            null -> 1
            else -> (existingRank + 1).coerceAtMost(maxAllowedRank).coerceAtLeast(1)
        }

        val targetRank = if (effectiveTier == BuildTier.HIGH && buildTierContext == BuildTier.HIGH) {
            4
        } else {
            maxOf(compRank, minDesiredRank).coerceAtMost(maxAllowedRank)
        }

        val effectivePlatform = if (currentPlatform != Platform.UNKNOWN) currentPlatform else {
            if (currentBuild.isEmpty) Platform.UNKNOWN else detectPlatform(currentBuild)
        }
        val currentDdr4 = effectivePlatform == Platform.AM4_DDR4 || effectivePlatform == Platform.LGA1700_DDR4
        val currentDdr5 = effectivePlatform == Platform.AM5_DDR5 || effectivePlatform == Platform.LGA1700_DDR5 || effectivePlatform == Platform.LGA1851_DDR5
        val currentSocket = platformSocket(effectivePlatform)

        val hasExistingBuild = !currentBuild.isEmpty
        if (hasExistingBuild && existingCompName.isNotBlank()) {
            if (cat == "ram") {
                val ramSufficient = isRamSufficient(existingCompName, effectiveTier)
                val compDdr5 = existingCompName.lowercase().contains("ddr5")
                val compDdr4 = existingCompName.lowercase().contains("ddr4")
                val targetRequiresDdr5 = effectivePlatform == Platform.AM5_DDR5 || effectivePlatform == Platform.LGA1700_DDR5 || effectivePlatform == Platform.LGA1851_DDR5
                val targetRequiresDdr4 = effectivePlatform == Platform.AM4_DDR4 || effectivePlatform == Platform.LGA1700_DDR4
                val ramGenMatches = when {
                    targetRequiresDdr5 -> compDdr5
                    targetRequiresDdr4 -> compDdr4
                    else -> true
                }
                val effectiveRamGb = normalizeRamCapacity(existingCompName)
                val targetGb = when (effectiveTier) {
                    BuildTier.ENTRY -> 16
                    BuildTier.MID -> 32
                    BuildTier.HIGH -> 64
                }
                val hasTwiceCapacity = effectiveRamGb >= targetGb * 2
                if (ramSufficient || (hasTwiceCapacity && ramGenMatches)) {
                    val scoreBase = when {
                        effectiveRamGb >= 64 -> 96
                        effectiveRamGb >= 32 -> 92
                        effectiveRamGb >= 16 -> 89
                        else -> 87
                    }
                    return keepCurrentComponent(cat, existingCompName, scoreBase)
                }
            } else if (existingRank != null) {
                val tierMinRequired = maxAllowedRank
                val keepBecauseAlreadySufficient = existingRank >= tierMinRequired
                val keepBecauseTargetNotBetter = existingRank >= targetRank
                if (keepBecauseAlreadySufficient || keepBecauseTargetNotBetter) {
                    val scoreBase = when (existingRank) {
                        1 -> 88
                        2 -> 91
                        3 -> 94
                        else -> 96
                    }
                    return keepCurrentComponent(cat, existingCompName, scoreBase)
                }
            }
        }

        val companionSocket = detectSocketByName(companion.name)
        val companionRamDdr4 = cat == "ram" && companion.name.lowercase().contains("ddr4")
        val companionRamDdr5 = cat == "ram" && companion.name.lowercase().contains("ddr5")
        val isAmdCpuCompanion = cat == "cpu" && companion.name.lowercase().contains("ryzen")
        val isIntelCpuCompanion = cat == "cpu" && (companion.name.lowercase().contains("intel") || companion.name.lowercase().contains("core i"))
        val isAmdMobo = currentSocket == Socket.AM4 || currentSocket == Socket.AM5
        val isIntelMobo = currentSocket == Socket.LGA1700 || currentSocket == Socket.LGA1851

        val socketMismatch = currentSocket != Socket.UNKNOWN && companionSocket != Socket.UNKNOWN && currentSocket != companionSocket
        val brandMismatch = (isAmdMobo && isIntelCpuCompanion) || (isIntelMobo && isAmdCpuCompanion)
        val ramGenMismatch = (currentDdr4 && companionRamDdr5) || (currentDdr5 && companionRamDdr4)

        if (targetRank == compRank && !socketMismatch && !ramGenMismatch && !brandMismatch) {
            return companion
        }
        val isAm4 = effectivePlatform == Platform.AM4_DDR4
        val isAm5 = effectivePlatform == Platform.AM5_DDR5
        val isLga1700Ddr4 = effectivePlatform == Platform.LGA1700_DDR4
        val isLga1700Ddr5 = effectivePlatform == Platform.LGA1700_DDR5
        val isLga1851Ddr5 = effectivePlatform == Platform.LGA1851_DDR5

        fun fallbackForPlatform(targetRank: Int): UpgradeComponent {
            return when (cat) {
                "cpu" -> when {
                    isAm4 -> when (targetRank) {
                        1 -> UpgradeComponent("CPU", "Ryzen 5 5600", "Balanced 6-core AM4 gaming processor", 68, 0.68f)
                        2 -> UpgradeComponent("CPU", "Ryzen 7 5700X3D", "Best AM4 gaming + multitasking CPU", 81, 0.81f)
                        3 -> UpgradeComponent("CPU", "Ryzen 7 5800X3D", "Flagship AM4 3D V-Cache gaming CPU", 88, 0.88f)
                        else -> UpgradeComponent("CPU", "Ryzen 7 5800X3D", "Flagship AM4 gaming CPU", 89, 0.89f)
                    }
                    isAm5 -> when (targetRank) {
                        1 -> UpgradeComponent("CPU", "Ryzen 5 7600", "Entry 6-core Zen 4 AM5 CPU (Ryzen 7000)", 69, 0.69f)
                        2 -> UpgradeComponent("CPU", "Ryzen 7 8700", "High-performance 8-core Zen 5 AM5 CPU (Ryzen 8000)", 85, 0.85f)
                        3 -> UpgradeComponent("CPU", "Ryzen 9 7900", "12-core Zen 4 AM5 workstation CPU (Ryzen 7000)", 91, 0.91f)
                        else -> UpgradeComponent("CPU", "Ryzen 9 9950X", "Flagship 16-core Zen 5 AM5 CPU (Ryzen 9000)", 97, 0.97f)
                    }
                    isLga1700Ddr4 -> when (targetRank) {
                        1 -> UpgradeComponent("CPU", "Intel Core i5-14400F", "Solid 10-core entry LGA1700 gaming CPU", 68, 0.68f)
                        2 -> UpgradeComponent("CPU", "Intel Core i7-14700KF", "Strong 20-core LGA1700 gaming + productivity CPU", 82, 0.82f)
                        3 -> UpgradeComponent("CPU", "Intel Core i9-14900KF", "Flagship 24-core LGA1700 performance CPU", 90, 0.90f)
                        else -> UpgradeComponent("CPU", "Intel Core i9-14900K", "Extreme 24-core LGA1700 flagship CPU", 93, 0.93f)
                    }
                    isLga1700Ddr5 -> when (targetRank) {
                        1 -> UpgradeComponent("CPU", "Intel Core i5-14400F", "Solid entry LGA1700 10-core CPU", 69, 0.69f)
                        2 -> UpgradeComponent("CPU", "Intel Core i7-14700K", "Strong 20-core gaming + productivity LGA1700 CPU", 83, 0.83f)
                        3 -> UpgradeComponent("CPU", "Intel Core i9-14900KF", "Flagship 24-core LGA1700 performance CPU", 91, 0.91f)
                        else -> UpgradeComponent("CPU", "Intel Core i9-14900K", "Extreme 24-core LGA1700 flagship CPU", 94, 0.94f)
                    }
                    isLga1851Ddr5 -> when (targetRank) {
                        1 -> UpgradeComponent("CPU", "Intel Core i5-14500", "Solid entry LGA1851 10-core CPU", 70, 0.70f)
                        2 -> UpgradeComponent("CPU", "Intel Core i7-14700K", "Strong 20-core gaming + productivity LGA1851 CPU", 84, 0.84f)
                        3 -> UpgradeComponent("CPU", "Intel Core i9-14900KF", "Flagship 24-core LGA1851 performance CPU", 92, 0.92f)
                        else -> UpgradeComponent("CPU", "Intel Core i9-14900K", "Extreme 24-core LGA1851 flagship CPU", 95, 0.95f)
                    }
                    else -> when (targetRank) {
                        1 -> UpgradeComponent("CPU", "Intel Core i5-14400F", "Solid 10-core entry LGA1700 gaming CPU (broad DDR4 compatibility)", 68, 0.68f)
                        2 -> UpgradeComponent("CPU", "Intel Core i7-14700KF", "Strong 20-core LGA1700 gaming + productivity CPU", 83, 0.83f)
                        3 -> UpgradeComponent("CPU", "Intel Core i9-14900KF", "Flagship 24-core LGA1700 performance CPU", 90, 0.90f)
                        else -> UpgradeComponent("CPU", "Intel Core i9-14900K", "Extreme 24-core LGA1700 flagship CPU", 93, 0.93f)
                    }
                }
                "motherboard" -> when {
                    isAm4 -> when (targetRank) {
                        1 -> UpgradeComponent("Motherboard", "B550M Pro4", "Reliable B550 AM4 motherboard", 66, 0.66f)
                        2 -> UpgradeComponent("Motherboard", "B550 AORUS Elite V2", "Feature-rich AM4 ATX board", 80, 0.80f)
                        else -> UpgradeComponent("Motherboard", "X570 AORUS Elite", "Premium X570 AM4 motherboard with PCIe 4.0", 87, 0.87f)
                    }
                    isAm5 -> when (targetRank) {
                        1 -> UpgradeComponent("Motherboard", "B650M DS3H", "Reliable entry B650 AM5 motherboard", 67, 0.67f)
                        2 -> UpgradeComponent("Motherboard", "B650 AORUS Elite", "Feature-rich B650 AM5 ATX motherboard", 82, 0.82f)
                        3 -> UpgradeComponent("Motherboard", "X670E AORUS Elite", "Premium X670E AM5 platform board", 90, 0.90f)
                        else -> UpgradeComponent("Motherboard", "ROG CROSSHAIR X870E HERO", "Flagship ROG X870E AM5 enthusiast board", 97, 0.97f)
                    }
                    isLga1700Ddr4 -> when (targetRank) {
                        1 -> UpgradeComponent("Motherboard", "B760M-A DDR4", "Reliable B760 LGA1700 DDR4 motherboard", 66, 0.66f)
                        2 -> UpgradeComponent("Motherboard", "B760 Tomahawk DDR4", "Feature-rich B760 DDR4 ATX motherboard", 80, 0.80f)
                        else -> UpgradeComponent("Motherboard", "Z790 Tomahawk DDR4", "Premium Z790 LGA1700 DDR4 motherboard", 89, 0.89f)
                    }
                    isLga1700Ddr5 -> when (targetRank) {
                        1 -> UpgradeComponent("Motherboard", "B760M-A WiFi DDR5", "Reliable B760 LGA1700 DDR5 motherboard", 67, 0.67f)
                        2 -> UpgradeComponent("Motherboard", "B760 Tomahawk WiFi DDR5", "Feature-rich B760 DDR5 ATX motherboard", 81, 0.81f)
                        3 -> UpgradeComponent("Motherboard", "Z790 AORUS MASTER", "Premium Z790 LGA1700 DDR5 motherboard", 91, 0.91f)
                        else -> UpgradeComponent("Motherboard", "ROG MAXIMUS Z790 HERO", "Flagship ROG MAXIMUS Z790 HERO motherboard", 95, 0.95f)
                    }
                    isLga1851Ddr5 -> when (targetRank) {
                        1 -> UpgradeComponent("Motherboard", "B860M-A WiFi DDR5", "Reliable B860 LGA1851 DDR5 motherboard", 68, 0.68f)
                        2 -> UpgradeComponent("Motherboard", "B860 Tomahawk WiFi DDR5", "Feature-rich B860 DDR5 ATX motherboard", 82, 0.82f)
                        3 -> UpgradeComponent("Motherboard", "Z890 AORUS MASTER", "Premium Z890 LGA1851 DDR5 motherboard", 92, 0.92f)
                        else -> UpgradeComponent("Motherboard", "ROG MAXIMUS Z890 HERO", "Flagship ROG MAXIMUS Z890 HERO motherboard", 96, 0.96f)
                    }
                    else -> when (targetRank) {
                        1 -> UpgradeComponent("Motherboard", "B760M-A DDR4", "Reliable B760 LGA1700 DDR4 motherboard (broad DDR4 compatibility)", 66, 0.66f)
                        2 -> UpgradeComponent("Motherboard", "B760 Tomahawk DDR4", "Feature-rich B760 DDR4 ATX motherboard", 80, 0.80f)
                        else -> UpgradeComponent("Motherboard", "Z790 Tomahawk DDR4", "Premium Z790 LGA1700 DDR4 motherboard", 89, 0.89f)
                    }
                }
                "ram" -> when {
                    isAm4 || isLga1700Ddr4 -> when (targetRank) {
                        1 -> UpgradeComponent("RAM", "16GB DDR4-3200", "Essential dual-channel DDR4 memory", 62, 0.62f)
                        2 -> UpgradeComponent("RAM", "32GB DDR4-3600", "High-bandwidth DDR4 gaming memory", 78, 0.78f)
                        3 -> UpgradeComponent("RAM", "64GB DDR4-3600", "Large high-bandwidth DDR4 memory kit", 85, 0.85f)
                        else -> UpgradeComponent("RAM", "64GB DDR4-3600 (2×32GB)", "Maximum high-bandwidth DDR4 memory kit", 87, 0.87f)
                    }
                    isAm5 || isLga1700Ddr5 || isLga1851Ddr5 -> when (targetRank) {
                        1 -> UpgradeComponent("RAM", "16GB DDR5-5600", "Fast DDR5 dual-channel memory", 64, 0.64f)
                        2 -> UpgradeComponent("RAM", "32GB DDR5-6000", "High-bandwidth DDR5 EXPO/XMP memory", 80, 0.80f)
                        3 -> UpgradeComponent("RAM", "64GB DDR5-6400", "Large enthusiast-grade DDR5 memory kit", 90, 0.90f)
                        else -> UpgradeComponent("RAM", "128GB DDR5-6400", "Maximum enthusiast-grade DDR5 memory", 95, 0.95f)
                    }
                    else -> when (targetRank) {
                        1 -> UpgradeComponent("RAM", "16GB DDR4-3200", "Essential dual-channel DDR4 memory (widest platform compatibility)", 62, 0.62f)
                        2 -> UpgradeComponent("RAM", "32GB DDR4-3600", "High-bandwidth DDR4 gaming memory", 78, 0.78f)
                        else -> UpgradeComponent("RAM", "64GB DDR4-3600", "Large high-bandwidth DDR4 memory kit", 85, 0.85f)
                    }
                }
                "gpu" -> when (targetRank) {
                    1 -> UpgradeComponent("GPU", "RTX 3060", "Solid 1080p graphics card", 65, 0.65f)
                    2 -> UpgradeComponent("GPU", "RTX 4070 Super", "Great 1440p graphics card", 82, 0.82f)
                    3 -> UpgradeComponent("GPU", "RTX 4080 Super", "Extreme 4K graphics card", 93, 0.93f)
                    else -> UpgradeComponent("GPU", "RTX 4090", "Enthusiast flagship 4K/8K graphics card", 98, 0.98f)
                }
                "storage" -> when (targetRank) {
                    1 -> UpgradeComponent("Storage", "1TB NVMe SSD", "Fast primary storage upgrade", 60, 0.60f)
                    2 -> UpgradeComponent("Storage", "2TB PCIe 4.0 NVMe", "Generous fast game storage", 77, 0.77f)
                    3 -> UpgradeComponent("Storage", "4TB PCIe 4.0 NVMe", "Massive high-speed library storage", 88, 0.88f)
                    else -> UpgradeComponent("Storage", "8TB PCIe 5.0 NVMe", "Maximum-speed PCIe 5.0 flagship storage", 94, 0.94f)
                }
                "psu" -> when (targetRank) {
                    1 -> UpgradeComponent("PSU", "550W 80+ Bronze", "Stable entry-tier power delivery", 60, 0.60f)
                    2 -> if (isAm5 || isLga1700Ddr5 || isLga1851Ddr5) {
                        UpgradeComponent("PSU", "750W 80+ Gold ATX 3.0", "ATX 3.0 PCIe 5-ready stable PSU", 84, 0.84f)
                    } else {
                        UpgradeComponent("PSU", "650W 80+ Gold", "Reliable mid-tier power supply", 79, 0.79f)
                    }
                    3 -> UpgradeComponent("PSU", "850W 80+ Gold ATX 3.0", "High-wattage reliable ATX 3.0 PSU", 92, 0.92f)
                    else -> UpgradeComponent("PSU", "1200W 80+ Platinum ATX 3.1", "Flagship Platinum-rated ATX 3.1 PSU with full headroom", 98, 0.98f)
                }
                else -> companion
            }
        }

        return fallbackForPlatform(targetRank)
    }

    private fun buildEntryUpgradePath(
        category: String,
        searchedComponent: String,
        hasExistingBuild: Boolean,
        componentTier: ComponentTier,
        currentBuild: CurrentBuild = CurrentBuild(),
        searchedComponentOverride: String = ""
    ): UpgradePath {
        val baseComponents: List<UpgradeComponent>
        val displayComponent: String
        val catLabel = category.uppercase().replaceFirstChar { it.uppercase() }

        val baseScore = when (componentTier) {
            ComponentTier.LEGACY_BUDGET -> 58
            ComponentTier.MIDRANGE -> 63
            ComponentTier.HIGH_END_CURRENT -> 68
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 72
        }
        displayComponent = searchedComponent
        val firstComponent = UpgradeComponent(
            category = category.uppercase(),
            name = searchedComponent,
            description = "${resolveDescriptionForComponent(category, componentTier)}",
            compatibility = baseScore + 2,
            progressPercent = (baseScore + 2) / 100f
        )
        val companionsList = resolveCompanionUpgrades(category, componentTier, BuildTier.ENTRY, Platform.UNKNOWN, currentBuild, hasExistingBuild)
        val rawCombined = listOf(firstComponent) + companionsList
        val dedupedCombined = dedupeUpgradeComponents(rawCombined, maxSize = 6)
        baseComponents = enforceOnePerCategory(dedupedCombined).take(6)

        val compsText = baseComponents.joinToString(", ") { it.name }
        return UpgradePath(
            title = "Entry-Level Upgrade Path",
            description = "Budget-friendly improvements for noticeable performance gains, centered around your $displayComponent. Includes upgrades for CPU, GPU, RAM, Storage, PSU, and Motherboard where applicable.",
            tier = "ENTRY",
            components = baseComponents,
            stepOne = "Use the $displayComponent as the foundation of this entry-level build",
            stepTwo = if (baseComponents.size > 2) {
                "Upgrade the key companions: ${baseComponents.getOrNull(1)?.name ?: ""} + ${baseComponents.getOrNull(2)?.name ?: ""} for balanced system performance"
            } else {
                "Pair the companion components for balanced performance"
            },
            stepThree = if (baseComponents.size > 4) {
                "Finish with ${baseComponents.getOrNull(3)?.name ?: ""}, ${baseComponents.getOrNull(4)?.name ?: ""}, and verify PSU wattage headroom for all upgrades"
            } else {
                "Verify PSU wattage headroom and confirm all components are compatible"
            }
        )
    }

    private fun buildMidUpgradePath(
        category: String,
        searchedComponent: String,
        hasExistingBuild: Boolean,
        componentTier: ComponentTier,
        currentBuild: CurrentBuild = CurrentBuild(),
        searchedComponentOverride: String = ""
    ): UpgradePath {
        val baseComponents: List<UpgradeComponent>
        val displayComponent: String

        val baseScore = when (componentTier) {
            ComponentTier.LEGACY_BUDGET -> 72
            ComponentTier.MIDRANGE -> 78
            ComponentTier.HIGH_END_CURRENT -> 83
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 87
        }
        displayComponent = searchedComponent
        val firstComponent = UpgradeComponent(
            category = category.uppercase(),
            name = searchedComponent,
            description = "${resolveDescriptionForComponent(category, componentTier)}",
            compatibility = baseScore + 2,
            progressPercent = (baseScore + 2) / 100f
        )
        val companionsList = resolveCompanionUpgrades(category, componentTier, BuildTier.MID, Platform.UNKNOWN, currentBuild, hasExistingBuild)
        val rawCombined = listOf(firstComponent) + companionsList
        val dedupedCombined = dedupeUpgradeComponents(rawCombined, maxSize = 6)
        baseComponents = enforceOnePerCategory(dedupedCombined).take(6)

        return UpgradePath(
            title = "Midrange Upgrade Path",
            description = "Smooth 1440p gaming and snappy productivity workflows, maximizing your $displayComponent with comprehensive companion upgrades across all component categories.",
            tier = "MIDRANGE",
            components = baseComponents,
            stepOne = "Use the $displayComponent as the main component in this mid-range tier",
            stepTwo = if (baseComponents.size > 2) {
                "Upgrade to ${baseComponents.getOrNull(1)?.name ?: ""} and ${baseComponents.getOrNull(2)?.name ?: ""} to eliminate potential bottlenecks"
            } else {
                "Upgrade the key companion components to eliminate bottlenecks"
            },
            stepThree = if (baseComponents.size > 4) {
                "Finish the build with ${baseComponents.drop(3).joinToString(", ") { it.name }} for a fully balanced system"
            } else {
                "Add Storage, PSU, and Motherboard upgrades if needed for a complete build"
            }
        )
    }

    private fun buildHighUpgradePath(
        category: String,
        searchedComponent: String,
        hasExistingBuild: Boolean,
        componentTier: ComponentTier,
        currentBuild: CurrentBuild = CurrentBuild(),
        searchedComponentOverride: String = ""
    ): UpgradePath {
        val baseComponents: List<UpgradeComponent>
        val displayComponent: String
        val isHighestTier: Boolean

        val baseScore = when (componentTier) {
            ComponentTier.LEGACY_BUDGET -> 85
            ComponentTier.MIDRANGE -> 88
            ComponentTier.HIGH_END_CURRENT -> 93
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 96
        }
        displayComponent = searchedComponent
        isHighestTier = componentTier == ComponentTier.ENTHUSIAST_FLAGSHIP
        val firstComponent = UpgradeComponent(
            category = category.uppercase(),
            name = searchedComponent,
            description = "${resolveDescriptionForComponent(category, componentTier)}",
            compatibility = baseScore + 2,
            progressPercent = (baseScore + 2) / 100f
        )
        val companionsList = resolveCompanionUpgrades(category, componentTier, BuildTier.HIGH, Platform.UNKNOWN, currentBuild, hasExistingBuild)
        val rawCombined = listOf(firstComponent) + companionsList
        val dedupedCombined = dedupeUpgradeComponents(rawCombined, maxSize = 6)
        baseComponents = enforceOnePerCategory(dedupedCombined).take(6)

        val pathTitle = "High-End Upgrade Path"

        return UpgradePath(
            title = pathTitle,
            description = "Maximum gaming and heavy productivity performance, fully unleashing your $displayComponent with flagship-grade companions across every component slot.",
            tier = "HIGHEND",
            components = baseComponents,
            stepOne = if (isHighestTier) {
                "Keep the $displayComponent as the center of the system — this is the highest tier available"
            } else {
                "Use the $displayComponent as the center of the high-end flagship system"
            },
            stepTwo = if (baseComponents.size > 2) {
                "Pair this with top-tier ${baseComponents.getOrNull(1)?.name ?: ""} and ${baseComponents.getOrNull(2)?.name ?: ""} for zero bottleneck compatibility"
            } else {
                "Pair with top-tier companions for zero bottleneck compatibility"
            },
            stepThree = if (baseComponents.size > 4) {
                "Finish with premium ${baseComponents.drop(3).joinToString(", ") { it.name }}, powerful cooling, and sufficient PSU headroom"
            } else {
                "Finish with premium Storage, PSU, and Motherboard upgrades with sufficient cooling and PSU headroom"
            }
        )
    }

    private fun resolveDescriptionForComponent(category: String, tier: ComponentTier): String {
        return when (category) {
            "gpu" -> when (tier) {
                ComponentTier.LEGACY_BUDGET -> "Capable 1080p gaming GPU"
                ComponentTier.MIDRANGE -> "Reliable 1080p/1440p gaming GPU"
                ComponentTier.HIGH_END_CURRENT -> "Excellent 1440p/4K gaming GPU"
                ComponentTier.ENTHUSIAST_FLAGSHIP -> "Flagship 4K/8K enthusiast GPU"
            }
            "cpu" -> when (tier) {
                ComponentTier.LEGACY_BUDGET -> "Budget-friendly everyday processor"
                ComponentTier.MIDRANGE -> "Balanced gaming + productivity CPU"
                ComponentTier.HIGH_END_CURRENT -> "High-performance gaming/workstation CPU"
                ComponentTier.ENTHUSIAST_FLAGSHIP -> "Extreme flagship workstation CPU"
            }
            "ram" -> "System memory for smooth multitasking"
            "motherboard" -> "Reliable motherboard platform foundation"
            "storage" -> "Fast storage for quick boot and load times"
            "psu" -> "Stable power delivery for entire system"
            else -> "Core system component"
        }
    }

    private fun resolveCompanionUpgrades(
        category: String,
        componentTier: ComponentTier,
        tier: BuildTier,
        currentPlatform: Platform = Platform.UNKNOWN,
        currentBuild: CurrentBuild = CurrentBuild(),
        hasExistingBuild: Boolean = false
    ): List<UpgradeComponent> {
        val effectivePlatform = if (currentPlatform != Platform.UNKNOWN) {
            currentPlatform
        } else if (hasExistingBuild) {
            detectPlatform(currentBuild)
        } else {
            Platform.UNKNOWN
        }
        val sameSocketAsCurrent = currentPlatform != Platform.UNKNOWN && effectivePlatform == currentPlatform

        val resolvedCat = resolveCategory(category)
        val existingCats = mutableSetOf(
            category.uppercase(),
            category.lowercase(),
            resolvedCat.uppercase(),
            resolvedCat.lowercase()
        )

        val pool = mutableListOf<UpgradeComponent>()

        val platformLocked = if (!hasExistingBuild) {
            emptyList<UpgradeComponent>()
        } else {
            pickPlatformComponents(effectivePlatform, tier, existingCats)
        }
        pool.addAll(platformLocked)

        val poolCatSet = pool.map { it.category.uppercase() }.toMutableSet()

        val gpuForTier: UpgradeComponent
        val gpuFlagship: UpgradeComponent
        if (!hasExistingBuild) {
            gpuForTier = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("GPU", "NVIDIA RTX 4060 / AMD RX 7600",
                    "Cross-brand entry GPUs: NVIDIA RTX 4060 (DLSS 3) or AMD RX 7600 (great VRAM) — both solid 1080p options.", 65, 0.65f)
                BuildTier.MID -> UpgradeComponent("GPU", "NVIDIA RTX 4070 Super / AMD RX 7800 XT",
                    "Cross-brand mid GPUs: NVIDIA RTX 4070 Super (DLSS + ray tracing) or AMD RX 7800 XT (16GB VRAM) — both great 1440p options.", 82, 0.82f)
                BuildTier.HIGH -> UpgradeComponent("GPU", "NVIDIA RTX 4080 Super / AMD RX 7900 XTX",
                    "Cross-brand high-end: NVIDIA RTX 4080 Super or AMD RX 7900 XTX (24GB) — extreme 4K gaming performance.", 93, 0.93f)
            }
            gpuFlagship = UpgradeComponent("GPU", "NVIDIA RTX 4090 / AMD RX 9090",
                "Cross-brand flagship: NVIDIA RTX 4090 (24GB GDDR6X) or AMD RX 9090 — absolute peak 4K/8K GPU performance.", 97, 0.97f)
        } else {
            gpuForTier = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("GPU", "RTX 3060", "Solid 1080p graphics card", 65, 0.65f)
                BuildTier.MID -> UpgradeComponent("GPU", "RTX 4070 Super", "Great 1440p graphics card", 82, 0.82f)
                BuildTier.HIGH -> UpgradeComponent("GPU", "RTX 4080 Super", "Extreme 4K graphics card", 93, 0.93f)
            }
            gpuFlagship = UpgradeComponent("GPU", "RTX 4090", "Flagship 4K/8K enthusiast GPU", 97, 0.97f)
        }

        val storageForTier: UpgradeComponent
        val storageFlagship: UpgradeComponent
        if (!hasExistingBuild) {
            storageForTier = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("Storage", "1TB PCIe 4.0 NVMe / 2TB PCIe 4.0 NVMe",
                    "Storage upgrade options: 1TB PCIe 4.0 NVMe (fast primary) or 2TB PCIe 4.0 NVMe (larger library) — both faster than Gen 3.", 60, 0.60f)
                BuildTier.MID -> UpgradeComponent("Storage", "2TB PCIe 4.0 NVMe / 2TB PCIe 5.0 NVMe",
                    "Storage upgrade: 2TB PCIe 4.0 NVMe or 2TB PCIe 5.0 NVMe — large capacity with Gen 4/5 speed for fast game loads.", 77, 0.77f)
                BuildTier.HIGH -> UpgradeComponent("Storage", "4TB PCIe 5.0 NVMe / 8TB PCIe 5.0 NVMe",
                    "High-end storage: 4TB PCIe 5.0 NVMe or 8TB PCIe 5.0 NVMe — maximum capacity and fastest available generation.", 88, 0.88f)
            }
            storageFlagship = UpgradeComponent("Storage", "8TB PCIe 5.0 NVMe (Highest Tier)",
                "Flagship storage: 8TB PCIe 5.0 NVMe — maximum capacity and fastest PCIe 5.0 speed available.", 94, 0.94f)
        } else {
            storageForTier = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("Storage", "1TB NVMe SSD", "Fast primary storage upgrade", 60, 0.60f)
                BuildTier.MID -> UpgradeComponent("Storage", "2TB PCIe 4.0 NVMe", "Generous fast game storage", 77, 0.77f)
                BuildTier.HIGH -> UpgradeComponent("Storage", "4TB PCIe 4.0 NVMe", "Massive high-speed library storage", 88, 0.88f)
            }
            storageFlagship = UpgradeComponent("Storage", "8TB PCIe 5.0 NVMe", "Maximum-speed PCIe 5.0 flagship storage", 94, 0.94f)
        }

        val psuForTier: UpgradeComponent
        val psuFlagship: UpgradeComponent
        if (!hasExistingBuild) {
            psuForTier = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("PSU", "550W 80+ Bronze / 650W 80+ Gold",
                    "PSU options: 550W 80+ Bronze (stable entry) or 650W 80+ Gold (higher efficiency + headroom).", 60, 0.60f)
                BuildTier.MID -> UpgradeComponent("PSU", "750W 80+ Gold / 750W 80+ Gold ATX 3.0",
                    "Mid PSU upgrade: 750W 80+ Gold or 750W 80+ Gold ATX 3.0 (PCIe 5-ready) — stable power for mid-to-high components.", 79, 0.79f)
                BuildTier.HIGH -> UpgradeComponent("PSU", "850W 80+ Platinum ATX 3.0 / 1000W 80+ Platinum ATX 3.1",
                    "High-end PSU: 850W Platinum ATX 3.0 or 1000W Platinum ATX 3.1 — higher wattage and efficiency rating.", 92, 0.92f)
            }
            psuFlagship = UpgradeComponent("PSU", "1200W 80+ Platinum ATX 3.1 (Highest Tier)",
                "Flagship PSU: 1200W 80+ Platinum/Titanium ATX 3.1 — maximum wattage headroom and highest 80+ rating available.", 98, 0.98f)
        } else {
            psuForTier = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("PSU", "550W 80+ Bronze", "Stable entry-tier power delivery", 60, 0.60f)
                BuildTier.MID -> when (effectivePlatform) {
                    Platform.AM5_DDR5, Platform.LGA1700_DDR5, Platform.LGA1851_DDR5 ->
                        UpgradeComponent("PSU", "750W 80+ Gold ATX 3.0", "ATX 3.0 PCIe 5-ready stable PSU", 84, 0.84f)
                    else -> UpgradeComponent("PSU", "650W 80+ Gold", "Reliable mid-tier power supply", 79, 0.79f)
                }
                BuildTier.HIGH -> when (effectivePlatform) {
                    Platform.AM5_DDR5, Platform.LGA1700_DDR5, Platform.LGA1851_DDR5 ->
                        UpgradeComponent("PSU", "1000W 80+ Platinum ATX 3.1", "Flagship Platinum ATX 3.1 PSU", 95, 0.95f)
                    Platform.LGA1700_DDR4 ->
                        UpgradeComponent("PSU", "850W 80+ Gold", "High-wattage reliable power supply", 92, 0.92f)
                    else -> UpgradeComponent("PSU", "850W 80+ Gold ATX 3.0", "High-wattage reliable ATX 3.0 PSU", 92, 0.92f)
                }
            }
            psuFlagship = UpgradeComponent("PSU", "1200W 80+ Platinum ATX 3.1", "Flagship Platinum-rated ATX 3.1 PSU with full headroom", 98, 0.98f)
        }

        if (!hasExistingBuild && "CPU" !in poolCatSet && "CPU" !in existingCats) {
            val cpuCompanion: UpgradeComponent = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("CPU", "AMD Ryzen 5 7600 / Intel Core i5-14400F",
                    "Cross-brand CPU: AMD Ryzen 5 7600 (6-core Zen 4 AM5) or Intel Core i5-14400F (10-core LGA1700) — both excellent entry CPU options.", 65, 0.65f)
                BuildTier.MID -> UpgradeComponent("CPU", "AMD Ryzen 7 7700 / Intel Core i7-14700K",
                    "Cross-brand CPU: AMD Ryzen 7 7700 (8-core Zen 4 AM5) or Intel Core i7-14700K (20-core LGA1700) — high-performance mid CPU options.", 83, 0.83f)
                BuildTier.HIGH -> UpgradeComponent("CPU", "AMD Ryzen 9 9950X / Intel Core i9-14900K",
                    "Cross-brand flagship CPU: AMD Ryzen 9 9950X (16-core Zen 5 AM5) or Intel Core i9-14900K (24-core LGA1700) — most powerful current-gen CPUs.", 95, 0.95f)
            }
            pool.add(cpuCompanion)
            poolCatSet.add("CPU")
        }

        if (!hasExistingBuild && "MOTHERBOARD" !in poolCatSet && "MOTHERBOARD" !in existingCats) {
            val moboCompanion: UpgradeComponent = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("Motherboard", "B650M DS3H (AM5 DDR5) / B760M-A DDR4 (LGA1700)",
                    "Dual-platform mobo options: B650M DS3H (AM5 DDR5 — Zen 4/Zen 5 ready) or B760M-A DDR4 (LGA1700 — 12th-14th Gen Intel ready). Both have DDR4 and DDR5 variants.", 64, 0.64f)
                BuildTier.MID -> UpgradeComponent("Motherboard", "B650 AORUS Elite (AM5 DDR5) / B760 Tomahawk WiFi DDR5 (LGA1700)",
                    "Mid mobo: B650 AORUS Elite (AM5 DDR5) or B760 Tomahawk WiFi DDR5 (LGA1700) — feature-rich ATX boards with full connectivity, both DDR5 capable.", 81, 0.81f)
                BuildTier.HIGH -> UpgradeComponent("Motherboard", "X670E Steel Legend (AM5 DDR5) / ROG MAXIMUS Z790 HERO (LGA1700 DDR5)",
                    "High-end mobo: X670E Steel Legend (AM5 DDR5 PCIe 5.0) or ROG MAXIMUS Z790 HERO (LGA1700 DDR5 flagship) — both premium platforms with maximum features.", 93, 0.93f)
            }
            pool.add(moboCompanion)
            poolCatSet.add("MOTHERBOARD")
        }

        if (!hasExistingBuild && "RAM" !in poolCatSet && "RAM" !in existingCats) {
            val ramCompanion: UpgradeComponent = when (tier) {
                BuildTier.ENTRY -> UpgradeComponent("RAM", "16GB DDR4-3200 / 16GB DDR5-5600",
                    "Dual-gen RAM: 16GB DDR4-3200 (for DDR4 motherboards) or 16GB DDR5-5600 (for DDR5 motherboards) — essential dual-channel memory options.", 62, 0.62f)
                BuildTier.MID -> UpgradeComponent("RAM", "32GB DDR4-3600 / 32GB DDR5-6000",
                    "Mid RAM upgrade: 32GB DDR4-3600 (high-bandwidth DDR4) or 32GB DDR5-6000 (EXPO/XMP DDR5) — both larger and faster than entry RAM.", 80, 0.80f)
                BuildTier.HIGH -> UpgradeComponent("RAM", "64GB DDR4-3600 / 64GB DDR5-6400",
                    "High-end RAM: 64GB DDR4-3600 (max DDR4 capacity) or 64GB DDR5-6400 (enthusiast DDR5) — large capacity and high bandwidth for heavy workloads.", 91, 0.91f)
            }
            pool.add(ramCompanion)
            poolCatSet.add("RAM")
        }

        val chosenGpu = if (tier == BuildTier.HIGH) gpuFlagship else gpuForTier
        val chosenStorage = if (tier == BuildTier.HIGH && componentTier == ComponentTier.ENTHUSIAST_FLAGSHIP) storageFlagship else storageForTier
        val chosenPsu = if (tier == BuildTier.HIGH && componentTier == ComponentTier.ENTHUSIAST_FLAGSHIP) psuFlagship else psuForTier

        if ("GPU" !in poolCatSet && "GPU" !in existingCats) pool.add(chosenGpu).also { poolCatSet.add("GPU") }
        if ("STORAGE" !in poolCatSet && "STORAGE" !in existingCats) pool.add(chosenStorage).also { poolCatSet.add("STORAGE") }
        if ("PSU" !in poolCatSet && "PSU" !in existingCats) pool.add(chosenPsu).also { poolCatSet.add("PSU") }

        val demoteMobo = category != "motherboard" && sameSocketAsCurrent

        val priorityOrder = buildList {
            when (category) {
                "gpu" -> {
                    add("CPU"); add("RAM"); add("STORAGE"); add("PSU"); add("MOTHERBOARD")
                }
                "cpu" -> {
                    add("GPU"); add("RAM"); add("STORAGE"); add("PSU"); add("MOTHERBOARD")
                }
                "motherboard" -> {
                    add("CPU"); add("RAM"); add("GPU"); add("STORAGE"); add("PSU")
                }
                "ram" -> {
                    add("GPU"); add("STORAGE"); add("CPU"); add("PSU"); add("MOTHERBOARD")
                }
                "storage" -> {
                    add("RAM"); add("GPU"); add("CPU"); add("PSU"); add("MOTHERBOARD")
                }
                "psu" -> {
                    add("GPU"); add("CPU"); add("RAM"); add("STORAGE"); add("MOTHERBOARD")
                }
                else -> {
                    add("CPU"); add("GPU"); add("RAM"); add("STORAGE")
                }
            }
        }

        val selected = mutableListOf<UpgradeComponent>()
        val selectedCats = mutableSetOf<String>()
        val poolByCat = pool.groupBy { it.category.uppercase() }

        if (demoteMobo) {
            for (cat in priorityOrder) {
                if (selected.size >= 5) break
                if (cat == "MOTHERBOARD") continue
                if (cat in existingCats) continue
                if (cat in selectedCats) continue
                val list = poolByCat[cat] ?: continue
                val top = list.firstOrNull() ?: continue
                selected.add(top); selectedCats.add(cat)
            }
            if (selected.size < 5) {
                val remainingCat = "MOTHERBOARD"
                if (remainingCat !in existingCats && remainingCat !in selectedCats) {
                    poolByCat[remainingCat]?.firstOrNull()?.also {
                        if (selected.none { s -> s.category.uppercase() == it.category.uppercase() }) {
                            selected.add(it); selectedCats.add(remainingCat)
                        }
                    }
                }
            }
        } else {
            for (cat in priorityOrder) {
                if (selected.size >= 5) break
                if (cat in existingCats) continue
                if (cat in selectedCats) continue
                val list = poolByCat[cat] ?: continue
                val top = list.firstOrNull() ?: continue
                selected.add(top); selectedCats.add(cat)
            }
        }

        while (selected.size < 5) {
            val fallback = pool.firstOrNull { f ->
                val fc = f.category.uppercase()
                fc !in existingCats && fc !in selectedCats
            }
            if (fallback == null) break
            selected.add(fallback); selectedCats.add(fallback.category.uppercase())
        }

        val fallbacks = listOf(chosenGpu, chosenStorage, chosenPsu) + platformLocked
        var fbIdx = 0
        while (selected.size < 5 && fbIdx < fallbacks.size) {
            val f = fallbacks[fbIdx]
            val fc = f.category.uppercase()
            if (fc !in existingCats && fc !in selectedCats) {
                selected.add(f); selectedCats.add(fc)
            }
            fbIdx++
        }

        val deduped = dedupeUpgradeComponents(selected, excludeCategories = existingCats, maxSize = 5)
        return enforceOnePerCategory(deduped).take(5)
    }

    fun getUpgradePathSeed(category: String): Map<String, Any> {
        return mapOf(
            "existingBuild" to getUpgradePath(category, hasExistingBuild = true).toSeedMap(),
            "newBuild" to getUpgradePath(category, hasExistingBuild = false).toSeedMap()
        )
    }

    fun resolveCategory(value: String): String {
        val normalized = value.lowercase()

        val intelCoreCPUPattern = Regex("""(intel\s+)?core\s*(i[3579])\s*[- ]?\s*\d+""", RegexOption.IGNORE_CASE)
        val intelIPattern = Regex("""\bi[3579]\s*[- ]?\s*\d{3,5}[a-z]*\b""", RegexOption.IGNORE_CASE)
        val isIntelCPU = normalized.contains("intel core") ||
            intelCoreCPUPattern.containsMatchIn(normalized) ||
            intelIPattern.containsMatchIn(normalized) ||
            normalized.contains("pentium") ||
            normalized.contains("celeron")
        val isAMDCPU = normalized.contains("ryzen") ||
            normalized.contains("athlon") ||
            Regex("""\b(ryzen|athlon)\s*\d\s*[0-9]{3,4}[a-z]*\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)

        val isRTX = normalized.contains("rtx") || Regex("""rtx\s*\d{3,4}""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val isRadeon = normalized.contains("radeon") ||
            normalized.contains("rx ") ||
            normalized.startsWith("rx") ||
            Regex("""rx\s*[5-8]\s*\d{2,3}[a-z]*\s*xt\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val isGPU = normalized.contains("gpu") || isRTX || isRadeon

        val isMoboChipset = listOf(
            "b450", "b550", "x570", "x470", "a520", "b350",
            "b650", "x670", "x870", "x670e", "x870e", "a620",
            "h610", "b660", "b760", "z690", "z790", "h770", "h710", "h670",
            "z890", "b860", "h810"
        ).any { normalized.contains(it) }
        val isMoboBrandModels = listOf(
            "pro4", "proart", "aorus", "tomahawk", "ds3h", "mortar",
            "maximus", "crosshair", "prime", "strix", "tuf", "msi ", "-a ddr", "-a wifi"
        ).any { normalized.contains(it) }
        val isMotherboard = normalized.contains("motherboard") || isMoboChipset || isMoboBrandModels

        val isPSUKeywords = listOf(
            "psu", "power supply", "80+", "80 plus",
            "bronze", "platinum", "titanium", "atx 3.0", "atx 3.1",
            "rm1000", "rm850", "watts"
        ).any { normalized.contains(it) }
        val isPSUWattagePattern = (normalized.contains("w ") && normalized.any { it.isDigit() } &&
            listOf("bronze", "gold", "platinum", "titanium").any { normalized.contains(it) }) ||
            normalized.matches(Regex(".*\\d{3,4}w.*(bronze|gold|platinum).*", RegexOption.IGNORE_CASE))
        val isPSU = isPSUKeywords || isPSUWattagePattern

        val isStorageKeywords = listOf(
            "storage", "ssd", "nvme", "hdd", "sn850",
            "990 pro", "980 pro", "samsung ", "wd ", "crucial ",
            "kingston ", "seagate ", "corsair ", "sabrent ",
            "pcie 4.0", "pcie 5.0", "gen4", "gen5", "tb nvme", "tb ssd"
        ).any { normalized.contains(it) }
        val isStorage = isStorageKeywords

        val isRamDDR = normalized.contains("ddr") && !isMoboChipset
        val isRamKeywords = normalized.contains("ram") || normalized.contains("memory")
        val isRam = isRamKeywords || (isRamDDR && !isMotherboard)

        val isCPU = normalized.contains("cpu") || isIntelCPU || isAMDCPU

        return when {
            isCPU -> "cpu"
            isGPU -> "gpu"
            isMotherboard -> "motherboard"
            isPSU -> "psu"
            isStorage -> "storage"
            isRam -> "ram"
            else -> "gpu"
        }
    }

    data class BuildCompatibilityResult(
        val isCompatible: Boolean,
        val issues: List<String>
    )

    fun checkBuildCompatibility(build: CurrentBuild): BuildCompatibilityResult {
        val issues = mutableListOf<String>()

        val cpuSocket = detectSocketByName(build.cpu)
        val moboSocket = detectSocketByName(build.motherboard)
        if (build.cpu.isNotBlank() && build.motherboard.isNotBlank() &&
            cpuSocket != Socket.UNKNOWN && moboSocket != Socket.UNKNOWN &&
            cpuSocket != moboSocket
        ) {
            issues.add("CPU socket does not match Motherboard socket")
        }

        val platform = detectPlatform(build)
        val moboIsDdr5: Boolean? = when (platform) {
            Platform.AM5_DDR5, Platform.LGA1700_DDR5, Platform.LGA1851_DDR5 -> true
            Platform.AM4_DDR4, Platform.LGA1700_DDR4 -> false
            Platform.UNKNOWN -> {
                val mbLower = build.motherboard.lowercase()
                when {
                    listOf("b650", "x670", "x870", "a620", "x670e", "x870e", "z890", "b860", "h810").any { mbLower.contains(it) } -> true
                    listOf(
                        "b450", "b550", "x570", "a520", "x470", "b350",
                        "h610", "b660", "z690", "h670"
                    ).any { mbLower.contains(it) } -> false
                    else -> null
                }
            }
        }

        val ramLower = build.ram.lowercase()
        val ramIsDdr5 = ramLower.contains("ddr5")
        val ramIsDdr4 = ramLower.contains("ddr4")

        if (moboIsDdr5 != null && build.ram.isNotBlank() && (ramIsDdr4 || ramIsDdr5)) {
            if (moboIsDdr5 && ramIsDdr4) {
                issues.add("Motherboard requires DDR5 RAM but DDR4 RAM was provided")
            } else if (!moboIsDdr5 && ramIsDdr5) {
                issues.add("Motherboard uses DDR4 RAM but DDR5 RAM was provided")
            }
        }

        if (cpuSocket != Socket.UNKNOWN && moboSocket != Socket.UNKNOWN) {
            val cpuBrandAmd = build.cpu.lowercase().contains("ryzen") || build.cpu.lowercase().contains("athlon")
            val cpuBrandIntel = build.cpu.lowercase().contains("i3-") || build.cpu.lowercase().contains("i5-") ||
                build.cpu.lowercase().contains("i7-") || build.cpu.lowercase().contains("i9-") ||
                build.cpu.lowercase().contains("intel")
            val moboBrandAmd = moboSocket == Socket.AM4 || moboSocket == Socket.AM5
            val moboBrandIntel = moboSocket == Socket.LGA1700 || moboSocket == Socket.LGA1851

            if (cpuBrandAmd && moboBrandIntel) {
                if (!issues.any { it.contains("socket", ignoreCase = true) }) {
                    issues.add("AMD CPU is not compatible with Intel motherboard")
                }
            } else if (cpuBrandIntel && moboBrandAmd) {
                if (!issues.any { it.contains("socket", ignoreCase = true) }) {
                    issues.add("Intel CPU is not compatible with AMD motherboard")
                }
            }
        }

        return BuildCompatibilityResult(
            isCompatible = issues.isEmpty(),
            issues = issues
        )
    }

    fun getComponentDetails(componentName: String): ComponentDetail {
        SmartQuerySpecGuesser.guess(componentName)?.let { return it }
        LocalSpecDatabase.find(componentName)?.let { return it }

        val category = resolveCategory(componentName)
        val score = when (category) {
            "gpu" -> (85..99).random()
            "cpu" -> (80..98).random()
            "ram" -> (88..97).random()
            "motherboard" -> (82..96).random()
            "storage" -> (86..98).random()
            "psu" -> (84..95).random()
            else -> (75..90).random()
        }
        val manufacturer = when {
            componentName.contains("NVIDIA", ignoreCase = true) -> "NVIDIA"
            componentName.contains("AMD", ignoreCase = true) -> "AMD"
            componentName.contains("Intel", ignoreCase = true) -> "Intel"
            componentName.contains("Corsair", ignoreCase = true) -> "Corsair"
            componentName.contains("G.Skill", ignoreCase = true) -> "G.Skill"
            componentName.contains("Kingston", ignoreCase = true) -> "Kingston"
            componentName.contains("ASUS", ignoreCase = true) -> "ASUS"
            componentName.contains("MSI", ignoreCase = true) -> "MSI"
            componentName.contains("GIGABYTE", ignoreCase = true) -> "GIGABYTE"
            componentName.contains("Samsung", ignoreCase = true) -> "Samsung"
            componentName.contains("WD", ignoreCase = true) || componentName.contains("Western Digital", ignoreCase = true) -> "Western Digital"
            componentName.contains("Crucial", ignoreCase = true) -> "Crucial"
            componentName.contains("EVGA", ignoreCase = true) -> "EVGA"
            componentName.contains("Seasonic", ignoreCase = true) -> "Seasonic"
            else -> "Manufacturer"
        }
        val specs = when (category) {
            "gpu" -> listOf(
                "24GB GDDR6X",
                "2520 MHz Boost",
                "450W TDP",
                "PCIe 4.0 x16"
            )
            "cpu" -> listOf(
                "24 Cores (32 Threads)",
                "5.8 GHz Boost",
                "125W TDP",
                "LGA 1700 Socket"
            )
            "ram" -> listOf(
                "32GB (2x16GB)",
                "DDR5-6000",
                "CL36 Latency",
                "1.35V"
            )
            "motherboard" -> listOf(
                "Z790 Chipset",
                "DDR5 Support",
                "PCIe 5.0 x16",
                "WiFi 7"
            )
            "storage" -> listOf(
                "2TB Capacity",
                "PCIe 4.0 NVMe",
                "7450 MB/s Read",
                "6900 MB/s Write"
            )
            "psu" -> listOf(
                "1000W Capacity",
                "80+ Titanium",
                "Fully Modular",
                "10 Year Warranty"
            )
            else -> listOf(
                "Spec 1",
                "Spec 2",
                "Spec 3",
                "Spec 4"
            )
        }
        val aiConfidence = (90..99).random()

        return ComponentDetail(
            name = componentName,
            category = category.uppercase(),
            score = score,
            specs = specs,
            aiConfidence = aiConfidence,
            manufacturer = manufacturer
        )
    }

    private fun UpgradePath.toSeedMap(): Map<String, Any> {
        return mapOf(
            "title" to title,
            "description" to description,
            "stepOne" to stepOne,
            "stepTwo" to stepTwo,
            "stepThree" to stepThree
        )
    }

    private fun splitDualOptionName(displayName: String): Pair<String, String> {
        val cleaned = displayName.replace(Regex("""\(Highest Tier\)"""), "").trim()
        val parts = cleaned.split(Regex("""\s*/\s*"""), limit = 2)
        return when (parts.size) {
            2 -> parts[0].trim() to parts[1].trim()
            else -> cleaned to ""
        }
    }

    private data class SearchUpgradeResult(
        val displayName: String,
        val description: String,
        val compatScore: Int,
        val isHighestTier: Boolean = false
    )

    private fun generateSearchBasedUpgrade(
        category: String,
        searchedComponent: String,
        searchedTier: ComponentTier,
        targetBuildTier: BuildTier,
        useCrossBrand: Boolean = false
    ): SearchUpgradeResult {
        val searchedRank = when (searchedTier) {
            ComponentTier.LEGACY_BUDGET -> 1
            ComponentTier.MIDRANGE -> 2
            ComponentTier.HIGH_END_CURRENT -> 3
            ComponentTier.ENTHUSIAST_FLAGSHIP -> 4
        }

        val targetRank = when (targetBuildTier) {
            BuildTier.ENTRY -> 1
            BuildTier.MID -> 2
            BuildTier.HIGH -> 3
        }

        val effectiveTargetRank = maxOf(targetRank, searchedRank + 1).coerceAtMost(4)
        val isHighest = searchedRank >= 4 || effectiveTargetRank >= 4

        val baseScore = when (effectiveTargetRank) {
            1 -> 58
            2 -> 72
            3 -> 85
            else -> 95
        }

        val n = searchedComponent.lowercase()

        if (searchedRank >= 4) {
            val highestDesc = "This is the highest tier — the $searchedComponent is one of the most powerful available currently. No need to replace for peak performance."
            return SearchUpgradeResult(searchedComponent, highestDesc, 98, true)
        }

        val pickPrimary: (String, String) -> String = { primary, cross -> if (useCrossBrand) cross else primary }
        val candidateValidator: (String) -> Boolean = { candidate ->
            isActualUpgrade(category, searchedComponent, candidate)
        }

        if (category == "cpu") {
            val isAmd = n.contains("ryzen") || n.contains("athlon")

            fun pickValid(a: String, b: String, desc: String): SearchUpgradeResult {
                val first = pickPrimary(a, b)
                val alt = if (first == a) b else a
                val finalName = when {
                    candidateValidator(first) -> first
                    candidateValidator(alt) -> alt
                    else -> first
                }
                return SearchUpgradeResult(finalName, desc, baseScore, isHighest)
            }

            if (isAmd) {
                return when (effectiveTargetRank) {
                    2 -> pickValid(
                        "Ryzen 7 5700X",
                        "Intel Core i5-14400F",
                        "Mid-range upgrade: Stronger CPU for fast gaming and productivity."
                    )
                    3 -> pickValid(
                        "Ryzen 7 7700",
                        "Intel Core i7-14700K",
                        "High-end upgrade: Large performance jump for all workloads."
                    )
                    else -> pickValid(
                        "Ryzen 9 9950X",
                        "Intel Core i9-14900K",
                        "Flagship CPU: Most powerful current-gen performance."
                    )
                }
            } else {
                return when (effectiveTargetRank) {
                    2 -> pickValid(
                        "Intel Core i5-14400F",
                        "Ryzen 7 5700X",
                        "Mid-range upgrade: Balanced gaming and multitasking performance."
                    )
                    3 -> pickValid(
                        "Intel Core i7-14700K",
                        "Ryzen 7 7700",
                        "High-end upgrade: Significant performance increase."
                    )
                    else -> pickValid(
                        "Intel Core i9-14900K",
                        "Ryzen 9 9950X",
                        "Flagship CPU: Most powerful current-gen options."
                    )
                }
            }
        }

        if (category == "gpu") {
            val isNvidia = n.contains("rtx") || n.contains("gtx") || n.contains("nvidia")

            fun pickValidGpu(a: String, b: String, desc: String): SearchUpgradeResult {
                val first = pickPrimary(a, b)
                val alt = if (first == a) b else a
                val finalName = when {
                    candidateValidator(first) -> first
                    candidateValidator(alt) -> alt
                    else -> first
                }
                return SearchUpgradeResult(finalName, desc, baseScore, isHighest)
            }

            if (isNvidia) {
                return when (effectiveTargetRank) {
                    2 -> pickValidGpu(
                        "NVIDIA RTX 4060",
                        "AMD Radeon RX 7600",
                        "Entry mid-range upgrade: Modern GPU stronger than RTX 2060."
                    )
                    3 -> pickValidGpu(
                        "NVIDIA RTX 5060 Ti",
                        "AMD Radeon RX 9060 XT",
                        "High-end upgrade: Excellent 1440p gaming performance."
                    )
                    else -> pickValidGpu(
                        "NVIDIA RTX 5080",
                        "AMD Radeon RX 9070 XT",
                        "Flagship GPU: Most powerful for 4K/8K gaming and AI."
                    )
                }
            } else {
                return when (effectiveTargetRank) {
                    2 -> pickValidGpu(
                        "AMD Radeon RX 7600",
                        "NVIDIA RTX 4060",
                        "Mid-range upgrade: Top-tier 1080p/1440p gaming experience."
                    )
                    3 -> pickValidGpu(
                        "AMD Radeon RX 9060 XT",
                        "NVIDIA RTX 5060 Ti",
                        "High-end upgrade: Premium 1440p/4K gaming at heavy rendering."
                    )
                    else -> pickValidGpu(
                        "AMD Radeon RX 9070 XT",
                        "NVIDIA RTX 5080",
                        "Flagship GPU: Absolute peak GPU performance."
                    )
                }
            }
        }

        if (category == "motherboard") {
            val isAmdPlatform = n.contains("b450") || n.contains("b550") || n.contains("x570") ||
                n.contains("x470") || n.contains("a520") ||
                n.contains("b650") || n.contains("x670") || n.contains("x870") || n.contains("a620")

            val (ddr4Mobo, ddr5Mobo, desc) = when (effectiveTargetRank) {
                2 -> {
                    if (isAmdPlatform) {
                        Triple(
                            "B550 AORUS Elite V2 (AM4 DDR4)",
                            "B650 AORUS Elite (AM5 DDR5)",
                            "Mid-range upgrade: Budget-friendly with PCIe 4.0 or next-gen Zen ready."
                        )
                    } else {
                        Triple(
                            "B760 Tomahawk DDR4 (LGA1700)",
                            "B760 Tomahawk WiFi DDR5 (LGA1700)",
                            "Mid-range upgrade: Budget-friendly DDR4 or DDR5-ready for Intel."
                        )
                    }
                }
                3 -> {
                    if (isAmdPlatform) {
                        Triple(
                            "X570 AORUS Elite (AM4 DDR4)",
                            "X670E Steel Legend (AM5 DDR5)",
                            "High-end upgrade: Full PCIe 4.0 or premium platform with PCIe 5.0."
                        )
                    } else {
                        Triple(
                            "Z790 Tomahawk DDR4 (LGA1700)",
                            "Z790 AORUS MASTER DDR5 (LGA1700)",
                            "High-end upgrade: Full overclocking or premium Z790 connectivity."
                        )
                    }
                }
                else -> {
                    if (isAmdPlatform) {
                        Triple(
                            "X570 AORUS Master (AM4 DDR4)",
                            "ROG CROSSHAIR X870E HERO (AM5 DDR5)",
                            "Flagship motherboard: Maximum connectivity at overclocking headroom."
                        )
                    } else {
                        Triple(
                            "Z790 Tomahawk DDR4 (LGA1700)",
                            "ROG MAXIMUS Z790 HERO (LGA1700 DDR5)",
                            "Flagship motherboard: Top-tier Intel with max features."
                        )
                    }
                }
            }
            val name = pickPrimary(ddr4Mobo, ddr5Mobo)
            return SearchUpgradeResult(name, desc, baseScore, isHighest)
        }

        if (category == "ram") {
            val currentGb = normalizeRamCapacity(searchedComponent).coerceAtLeast(8)
            val currentSpeed = normalizeRamSpeed(searchedComponent).coerceAtLeast(2666)

            val (ddr4Ram, ddr5Ram, desc) = when (effectiveTargetRank) {
                2 -> {
                    val gb = 32.coerceAtLeast(currentGb + 16)
                    val d4Speed = maxOf(3600, currentSpeed)
                    val d5Speed = maxOf(6000, 5600)
                    Triple(
                        "${gb}GB DDR4-$d4Speed",
                        "${gb}GB DDR5-$d5Speed",
                        "Mid-range RAM upgrade: Higher capacity and speed than current ${currentGb}GB."
                    )
                }
                3 -> {
                    val gb = 32.coerceAtLeast(currentGb + 16).coerceAtMost(64)
                    val d4Speed = maxOf(3600, currentSpeed)
                    val d5Speed = maxOf(6400, 6000)
                    Triple(
                        "${gb}GB DDR4-$d4Speed",
                        "${gb}GB DDR5-$d5Speed",
                        "High-end RAM upgrade: 32GB default for gaming, 64GB optional for productivity workloads."
                    )
                }
                else -> {
                    val gb = 64.coerceAtLeast(currentGb + 32)
                    val d4Speed = maxOf(3600, currentSpeed)
                    val d5Speed = maxOf(6800, 6400)
                    Triple(
                        "${gb}GB DDR4-$d4Speed",
                        "${gb}GB DDR5-$d5Speed",
                        "Flagship RAM: Maximum capacity and speed for heavy multitasking / rendering."
                    )
                }
            }
            val name = pickPrimary(ddr4Ram, ddr5Ram)
            return SearchUpgradeResult(name, desc, baseScore, isHighest)
        }

        if (category == "storage") {
            val isGen5 = n.contains("pcie 5.0") || n.contains("gen5") || n.contains("gen 5")
            val isGen4 = n.contains("pcie 4.0") || n.contains("gen4") || n.contains("gen 4")
            val currentTbMatch = Regex("""(\d+)\s*tb""").find(n)
            val currentTb = currentTbMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val currentGbMatch = Regex("""(\d+)\s*gb""").find(n)
            val currentGb = currentGbMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val effectiveCurrentTb = if (currentTb > 0) currentTb else (currentGb / 1024).coerceAtLeast(1)

            val (option1, option2, desc) = when (effectiveTargetRank) {
                2 -> {
                    val tb = maxOf(2, effectiveCurrentTb)
                    Triple(
                        "${tb}TB PCIe 4.0 NVMe",
                        "${tb}TB PCIe 4.0 NVMe",
                        "Mid-range storage upgrade: PCIe 4.0 default with 1-2TB capacity range for game libraries."
                    )
                }
                3 -> {
                    val tb = maxOf(2, effectiveCurrentTb * 2).coerceAtMost(4)
                    Triple(
                        "${tb}TB PCIe 4.0 NVMe",
                        "${tb}TB PCIe 5.0 NVMe",
                        "High-end storage upgrade: 2TB-4TB PCIe 4.0 default. PCIe 5.0 only if workload justifies."
                    )
                }
                else -> {
                    val tb = maxOf(8, currentTb * 2)
                    Triple(
                        "${tb}TB PCIe 5.0 NVMe",
                        "${tb}TB PCIe 5.0 NVMe",
                        "Flagship storage: Maximum capacity and speed."
                    )
                }
            }
            val name = pickPrimary(option1, option2)
            return SearchUpgradeResult(name, desc, baseScore, isHighest)
        }

        if (category == "psu") {
            val wattageMatch = Regex("""(\d{3,4})\s*w""").find(n)
            val currentWatts = wattageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 500
            val rating = when {
                n.contains("titanium") -> "Titanium"
                n.contains("platinum") -> "Platinum"
                n.contains("gold") -> "Gold"
                else -> "Bronze"
            }

            val (option1, option2, desc) = when (effectiveTargetRank) {
                2 -> {
                    val w = maxOf(650, currentWatts + 100)
                    Triple(
                        "${w}W 80+ Gold",
                        "${w}W 80+ Gold ATX 3.0",
                        "Mid-range PSU upgrade: Higher wattage ($currentWatts W → $w W) and efficiency."
                    )
                }
                3 -> {
                    val w = maxOf(850, currentWatts + 200)
                    Triple(
                        "${w}W 80+ Platinum ATX 3.0",
                        "${w}W 80+ Gold ATX 3.0",
                        "High-end PSU upgrade: Large wattage jump with stable power."
                    )
                }
                else -> {
                    val w = maxOf(1200, currentWatts + 300)
                    Triple(
                        "${w}W 80+ Platinum ATX 3.1",
                        "${w}W 80+ Titanium ATX 3.1",
                        "Flagship PSU: Maximum wattage headroom at highest efficiency."
                    )
                }
            }
            val name = pickPrimary(option1, option2)
            return SearchUpgradeResult(name, desc, baseScore, isHighest)
        }

        return SearchUpgradeResult(
            searchedComponent,
            resolveDescriptionForComponent(category, searchedTier),
            baseScore
        )
    }
}
