package com.pcscan.pcscan.data.api

import com.pcscan.pcscan.data.api.SearchApiDto.AnalyzeTextResponseDto
import com.pcscan.pcscan.data.api.SearchApiDto.CpuSpecDto
import com.pcscan.pcscan.data.api.SearchApiDto.GpuSpecDto
import com.pcscan.pcscan.data.api.SearchApiDto.RamSpecDto
import com.pcscan.pcscan.data.api.SearchApiDto.StorageSpecDto
import com.pcscan.pcscan.data.api.SearchApiDto.MotherboardSpecDto
import com.pcscan.pcscan.data.api.SearchApiDto.PsuSpecDto
import com.pcscan.pcscan.ui.dashboard.ComponentDetail
import com.pcscan.pcscan.ui.dashboard.RecommendationMockData
import kotlin.math.roundToInt

object ComponentDetailMapper {

    private fun shortCategory(raw: String?): String {
        val r = (raw ?: "component").trim().uppercase()
        return when (r) {
            "CENTRAL PROCESSING UNIT", "PROCESSOR" -> "CPU"
            "GRAPHICS CARD", "GRAPHICS PROCESSING UNIT", "VIDEO CARD", "VGA" -> "GPU"
            "MEMORY", "RANDOM ACCESS MEMORY" -> "RAM"
            "MAINBOARD", "MOTHERBOARD" -> "MOBO"
            "STORAGE DEVICE", "SSD", "HDD", "NVME", "STORAGE" -> "STOR"
            "POWER SUPPLY", "POWER SUPPLY UNIT" -> "PSU"
            "AIR COOLER", "WATER COOLER", "CPU COOLER" -> "COOL"
            "COMPUTER CASE", "PC CASE", "CHASSIS" -> "CASE"
            else -> when {
                r.startsWith("MOTH") || r.startsWith("MAIN") -> "MOBO"
                r.startsWith("STOR") || r == "SSD" || r == "HDD" -> "STOR"
                r.startsWith("COOL") -> "COOL"
                r.length <= 4 -> r
                else -> r.take(4)
            }
        }
    }

    fun toComponentDetail(
        componentName: String,
        dto: AnalyzeTextResponseDto?
    ): ComponentDetail? {
        if (dto == null) return null
        val hardware = dto.hardwareSpecs ?: return null
        val ident = dto.identifiedComponents?.firstOrNull()

        val rawCategory = ident?.componentType
            ?: RecommendationMockData.resolveCategory(componentName)
        val category = shortCategory(rawCategory)

        val displayName = ident?.name?.takeIf { it.isNotBlank() }
            ?: hardware.cpu?.name
            ?: hardware.gpu?.name
            ?: hardware.storage?.name
            ?: hardware.ram?.name
            ?: hardware.motherboard?.name
            ?: hardware.psu?.name
            ?: componentName

        val manufacturer = (ident?.brand
            ?: hardware.cpu?.brand
            ?: hardware.gpu?.brand
            ?: hardware.storage?.brand
            ?: hardware.ram?.brand
            ?: hardware.motherboard?.brand
            ?: hardware.psu?.brand
            ?: extractBrand(displayName))
            .takeIf { it?.isNotBlank() == true }
            ?: "Manufacturer"

        val score = ((dto.identificationConfidence ?: 0f) * 100).toInt()
            .coerceAtLeast(70)
            .coerceAtMost(99)

        val aiConfidence = ((dto.identificationConfidence ?: 0f) * 100).toInt()
            .coerceAtLeast(90)
            .coerceAtMost(99)

        val specs: List<String> = when (category.uppercase()) {
            "CPU" -> buildCpuSpecs(hardware.cpu)
            "GPU" -> buildGpuSpecs(hardware.gpu)
            "RAM" -> buildRamSpecs(hardware.ram)
            "STORAGE", "STOR" -> buildStorageSpecs(hardware.storage)
            "MOTHERBOARD", "MOBO" -> buildMotherboardSpecs(hardware.motherboard)
            "PSU" -> buildPsuSpecs(hardware.psu)
            "COOL", "COOLER" -> buildFallbackSpecs(componentName, category)
            "CASE", "CHASSIS" -> buildFallbackSpecs(componentName, category)
            else -> buildFallbackSpecs(componentName, category)
        }.take(4)

        return ComponentDetail(
            name = displayName,
            category = shortCategory(category),
            score = score,
            specs = specs,
            aiConfidence = aiConfidence,
            manufacturer = manufacturer
        )
    }

    private fun buildCpuSpecs(cpu: CpuSpecDto?): List<String> {
        val result = mutableListOf<String>()
        if (cpu != null) {
            val ct = when {
                cpu.cores != null && cpu.threads != null -> "${cpu.cores}C / ${cpu.threads}T"
                cpu.cores != null -> "${cpu.cores} Cores"
                else -> null
            }
            if (ct != null) result += ct

            val clock = cpu.boostClock?.let { "$it Boost" }
                ?: cpu.baseClock?.let { "$it Base" }
            if (clock != null) result += clock

            if (cpu.tdpWatts != null) result += "${cpu.tdpWatts}W TDP"
            if (cpu.socket != null) result += "Socket ${cpu.socket}"

            val brandy = (cpu.brand ?: extractBrand(cpu.name)).takeIf { it.isNotBlank() }
            if (brandy != null && result.size < 4 && result.none { it.contains(brandy, true) }) result += brandy
        }
        return padIfNeeded(result)
    }

    private fun buildGpuSpecs(gpu: GpuSpecDto?): List<String> {
        val result = mutableListOf<String>()
        if (gpu != null) {
            val vram = when {
                gpu.vramGb != null && gpu.memoryType != null -> {
                    val gb = if (gpu.vramGb == gpu.vramGb.toInt().toFloat())
                        gpu.vramGb.toInt().toString() else gpu.vramGb.toString()
                    "${gb}GB ${gpu.memoryType}"
                }
                gpu.vramGb != null -> {
                    val gb = if (gpu.vramGb == gpu.vramGb.toInt().toFloat())
                        gpu.vramGb.toInt().toString() else gpu.vramGb.toString()
                    "${gb}GB VRAM"
                }
                else -> null
            }
            if (vram != null) result += vram

            if (gpu.tdpWatts != null) result += "${gpu.tdpWatts}W TDP"
            val iface = gpu.iface?.takeIf { it.isNotBlank() }
            if (iface != null) result += iface
            if (gpu.lengthMm != null) result += "${gpu.lengthMm}mm"

            val brandy = (gpu.brand ?: extractBrand(gpu.name)).takeIf { it.isNotBlank() }
            if (brandy != null && result.size < 4 && result.none { it.contains(brandy, true) }) result += brandy
        }
        return padIfNeeded(result)
    }

    private fun buildRamSpecs(ram: RamSpecDto?): List<String> {
        val result = mutableListOf<String>()
        if (ram != null) {
            val cap = when {
                ram.totalCapacityGb != null && ram.moduleCount != null -> {
                    val perMod = ram.totalCapacityGb / ram.moduleCount
                    "${ram.totalCapacityGb}GB (${ram.moduleCount}x${perMod}GB)"
                }
                ram.totalCapacityGb != null -> "${ram.totalCapacityGb}GB"
                else -> null
            }
            if (cap != null) result += cap

            val speed = when {
                ram.ramType != null && ram.speedMhz != null -> "${ram.ramType} ${ram.speedMhz}MHz"
                ram.ramType != null -> ram.ramType
                ram.speedMhz != null -> "${ram.speedMhz}MHz"
                else -> null
            }
            if (speed != null) result += speed

            if (ram.casLatency != null) result += "CL${ram.casLatency}"
            val brandy = (ram.brand ?: extractBrand(ram.name)).takeIf { it.isNotBlank() }
            if (brandy != null && result.size < 4 && result.none { it.contains(brandy, true) }) result += brandy
        }
        return padIfNeeded(result)
    }

    private fun buildStorageSpecs(storage: StorageSpecDto?): List<String> {
        val result = mutableListOf<String>()
        if (storage != null) {
            val cap = when {
                storage.capacityGb != null && storage.capacityGb >= 1000 ->
                    "${storage.capacityGb / 1000}TB ${storage.type ?: ""}".trim()
                storage.capacityGb != null ->
                    "${storage.capacityGb}GB ${storage.type ?: ""}".trim()
                storage.type != null -> storage.type
                else -> null
            }
            if (cap != null) result += cap

            storage.iface?.takeIf { it.isNotBlank() }?.let { if (result.size < 4) result += it }
            storage.formFactor?.takeIf { it.isNotBlank() }?.let { if (result.size < 4) result += it }
            val brandy = (storage.brand ?: extractBrand(storage.name)).takeIf { it.isNotBlank() }
            if (brandy != null && result.size < 4 && result.none { it.contains(brandy, true) }) result += brandy
        }
        return padIfNeeded(result)
    }

    private fun buildMotherboardSpecs(mobo: MotherboardSpecDto?): List<String> {
        val result = mutableListOf<String>()
        if (mobo != null) {
            val chip = when {
                mobo.socket != null && mobo.chipset != null ->
                    "${mobo.socket} • ${mobo.chipset}"
                mobo.socket != null -> "Socket ${mobo.socket}"
                mobo.chipset != null -> mobo.chipset
                else -> null
            }
            if (chip != null) result += chip

            mobo.formFactor?.takeIf { it.isNotBlank() }?.let { if (result.size < 4) result += it }
            val mem = when {
                mobo.ramType != null && mobo.memorySlots != null ->
                    "${mobo.ramType} • ${mobo.memorySlots} Slots"
                mobo.ramType != null -> mobo.ramType
                mobo.memorySlots != null -> "${mobo.memorySlots} Slots"
                else -> null
            }
            if (mem != null) result += mem
            if (mobo.maxRamGb != null) result += "Max ${mobo.maxRamGb}GB"

            val brandy = (mobo.brand ?: extractBrand(mobo.name)).takeIf { it.isNotBlank() }
            if (brandy != null && result.size < 4 && result.none { it.contains(brandy, true) }) result += brandy
        }
        return padIfNeeded(result)
    }

    private fun buildPsuSpecs(psu: PsuSpecDto?): List<String> {
        val result = mutableListOf<String>()
        if (psu != null) {
            val watts = when {
                psu.wattage != null && psu.efficiencyRating != null ->
                    "${psu.wattage}W ${psu.efficiencyRating}"
                psu.wattage != null -> "${psu.wattage}W"
                psu.efficiencyRating != null -> psu.efficiencyRating
                else -> null
            }
            if (watts != null) result += watts
            psu.modularType?.takeIf { it.isNotBlank() }?.let { if (result.size < 4) result += it }
            val brandy = (psu.brand ?: extractBrand(psu.name)).takeIf { it.isNotBlank() }
            if (brandy != null && result.size < 4 && result.none { it.contains(brandy, true) }) result += brandy
        }
        return padIfNeeded(result)
    }

    private fun buildFallbackSpecs(componentName: String, @Suppress("UNUSED_PARAMETER") category: String): List<String> {
        val base = RecommendationMockData.getComponentDetails(componentName).specs
        return padIfNeeded(base.take(4).toMutableList())
    }

    private fun padIfNeeded(specs: MutableList<String>): List<String> {
        val real = specs.filter { it.isNotBlank() }.distinct().toMutableList()
        return real.take(4)
    }

    private fun extractBrand(name: String?): String {
        if (name.isNullOrBlank()) return "Manufacturer"
        val n = name.lowercase()
        return when {
            n.contains("ryzen") || n.contains("radeon") || n.startsWith("amd ") -> "AMD"
            n.contains("intel") || n.contains("core i") -> "Intel"
            n.contains("nvidia") || n.contains("geforce rtx") || n.contains("rtx ") -> "NVIDIA"
            n.contains("sapphire") -> "SAPPHIRE"
            n.contains("asus") || n.contains("rog strix") || n.contains("tuf") -> "ASUS"
            n.contains("msi") || n.contains("mag ") || n.contains("mpg ") -> "MSI"
            n.contains("gigabyte") || n.contains("aorus") -> "GIGABYTE"
            n.contains("g.skill") || n.contains("trident") -> "G.Skill"
            n.contains("corsair") || n.startsWith("corsair ") -> "Corsair"
            n.contains("kingston") || n.contains("fury") || n.contains("nv3") -> "Kingston"
            n.contains("samsung") || n.contains("990 pro") -> "Samsung"
            n.contains("wd ") || n.contains("western digital") || n.contains("sn850") -> "WD"
            n.contains("crucial") || n.contains("p5 ") || n.contains("t700") || n.contains("mx500") || n.contains("bx500") -> "Crucial"
            n.contains("lexar ") || n.contains("nm610") || n.contains("nm620") || n.contains("nm710") || n.contains("nm790") || n.contains(" ares ")-> "Lexar"
            n.contains("seasonic") -> "Seasonic"
            n.contains("evga") -> "EVGA"
            n.contains("nzxt") -> "NZXT"
            n.contains("lian li") || n.contains("lancool") || n.contains("o11 ") -> "Lian Li"
            n.contains("deepcool") -> "DeepCool"
            n.contains("rakk ") -> "Rakk"
            n.contains("noctua") -> "Noctua"
            else -> name.split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: "Manufacturer"
        }
    }
}
