package com.pcscan.pcscan.data.api

import com.google.gson.annotations.SerializedName

object SearchApiDto {

    data class SearchRequestDto(
        @SerializedName("query") val query: String
    )

    data class SearchComponentDto(
        @SerializedName("id") val id: String,
        @SerializedName("category") val category: String,
        @SerializedName("brand") val brand: String,
        @SerializedName("model") val model: String,
        @SerializedName("canonical_name") val canonicalName: String,
        @SerializedName("image_url") val imageUrl: String? = null,
        @SerializedName("specifications") val specifications: Map<String, @JvmSuppressWildcards Any?>? = emptyMap()
    )

    data class SearchResponseDto(
        @SerializedName("found") val found: Boolean,
        @SerializedName("exact_match") val exactMatch: Boolean,
        @SerializedName("query") val query: String? = null,
        @SerializedName("message") val message: String? = null,
        @SerializedName("component") val component: SearchComponentDto? = null,
        @SerializedName("ai_confidence") val aiConfidence: Float? = 0f,
        @SerializedName("processing_time_ms") val processingTimeMs: Int? = 0,
        @SerializedName("is_valid_pc_part") val isValidPcPart: Boolean? = null,
        @SerializedName("rejection_reason") val rejectionReason: String? = null,
        @SerializedName("form_factor") val formFactor: String? = null
    )

    data class AnalyzeTextRequestDto(
        @SerializedName("text") val text: String,
        @SerializedName("include_recommendations") val includeRecommendations: Boolean = true,
        @SerializedName("include_upgrade_path") val includeUpgradePath: Boolean = true,
        @SerializedName("build_guidelines") val buildGuidelines: String? = null,
        @SerializedName("temperature") val temperature: Float = 0.0f,
        @SerializedName("max_tokens") val maxTokens: Int = 1024,
        @SerializedName("response_format") val responseFormat: String = "json",
        @SerializedName("concise_mode") val conciseMode: Boolean = true
    )

    data class AnalyzeTextResponseDto(
        @SerializedName("success") val success: Boolean,
        @SerializedName("query") val query: String,
        @SerializedName("identified_components") val identifiedComponents: List<IdentifiedComponentDto>? = emptyList(),
        @SerializedName("hardware_specs") val hardwareSpecs: HardwareSpecsDto? = null,
        @SerializedName("identification_confidence") val identificationConfidence: Float? = 0f,
        @SerializedName("identification_summary") val identificationSummary: String? = null,
        @SerializedName("analysis") val analysis: AnalysisResponseDto? = null,
        @SerializedName("total_processing_time_ms") val totalProcessingTimeMs: Int? = 0,
        @SerializedName("source") val source: String? = null,
        @SerializedName("error_message") val errorMessage: String? = null
    )

    data class AnalysisResponseDto(
        @SerializedName("success") val success: Boolean? = null,
        @SerializedName("query") val query: String? = null,
        @SerializedName("compatibility") val compatibility: CompatibilityReportDto? = null,
        @SerializedName("watts_calculator") val wattsCalculator: WattsCalculatorResultDto? = null,
        @SerializedName("bottleneck") val bottleneck: BottleneckAnalysisDto? = null,
        @SerializedName("upgrade_path") val upgradePath: UpgradePathResultDto? = null,
        @SerializedName("build_recommendations") val buildRecommendations: BuildRecommendationResultDto? = null,
        @SerializedName("overall_confidence") val overallConfidence: Float? = 0f,
        @SerializedName("analysis_summary") val analysisSummary: String? = null,
        @SerializedName("processing_time_ms") val processingTimeMs: Int? = 0,
        @SerializedName("error_message") val errorMessage: String? = null
    )

    data class CompatibilityIssueDto(
        @SerializedName("severity") val severity: String? = null,
        @SerializedName("components_involved") val componentsInvolved: List<String>? = emptyList(),
        @SerializedName("issue") val issue: String? = null,
        @SerializedName("resolution") val resolution: String? = null
    )

    data class CompatibilityReportDto(
        @SerializedName("is_compatible") val isCompatible: Boolean? = null,
        @SerializedName("overall_score") val overallScore: Float? = 0f,
        @SerializedName("issues") val issues: List<CompatibilityIssueDto>? = emptyList(),
        @SerializedName("summary") val summary: String? = null
    )

    data class WattsBreakdownDto(
        @SerializedName("component") val component: String,
        @SerializedName("estimated_watts") val estimatedWatts: Int,
        @SerializedName("notes") val notes: String? = null
    )

    data class WattsCalculatorResultDto(
        @SerializedName("total_system_watts") val totalSystemWatts: Int? = 0,
        @SerializedName("breakdown") val breakdown: List<WattsBreakdownDto>? = emptyList(),
        @SerializedName("recommended_psu_watts") val recommendedPsuWatts: Int? = 0,
        @SerializedName("psu_efficiency_tip") val psuEfficiencyTip: String? = null,
        @SerializedName("notes") val notes: String? = null
    )

    data class BottleneckAnalysisDto(
        @SerializedName("has_bottleneck") val hasBottleneck: Boolean? = null,
        @SerializedName("bottleneck_component") val bottleneckComponent: String? = null,
        @SerializedName("bottleneck_percentage") val bottleneckPercentage: Float? = 0f,
        @SerializedName("description") val description: String? = null,
        @SerializedName("affected_tasks") val affectedTasks: List<String>? = emptyList(),
        @SerializedName("recommended_fix") val recommendedFix: String? = null
    )

    data class UpgradeStepDto(
        @SerializedName("step_order") val stepOrder: Int,
        @SerializedName("title") val title: String,
        @SerializedName("description") val description: String,
        @SerializedName("component_category") val componentCategory: String,
        @SerializedName("old_component") val oldComponent: String? = null,
        @SerializedName("new_component") val newComponent: String,
        @SerializedName("price_range_php") val priceRangePhp: String? = null,
        @SerializedName("performance_gain") val performanceGain: String? = null,
        @SerializedName("priority") val priority: String? = null
    )

    data class UpgradePathResultDto(
        @SerializedName("use_case") val useCase: String? = null,
        @SerializedName("budget_php") val budgetPhp: String? = null,
        @SerializedName("current_performance_score") val currentPerformanceScore: Float? = 0f,
        @SerializedName("projected_performance_score") val projectedPerformanceScore: Float? = 0f,
        @SerializedName("steps") val steps: List<UpgradeStepDto>? = emptyList(),
        @SerializedName("total_estimated_cost_php") val totalEstimatedCostPhp: String? = null,
        @SerializedName("summary") val summary: String? = null
    )

    data class TieredBuildDto(
        @SerializedName("tier") val tier: String,
        @SerializedName("tier_name") val tierName: String,
        @SerializedName("description") val description: String,
        @SerializedName("target_performance") val targetPerformance: String,
        @SerializedName("hardware") val hardware: HardwareSpecsDto,
        @SerializedName("estimated_total_price_php") val estimatedTotalPricePhp: String? = null,
        @SerializedName("best_for") val bestFor: List<String>? = emptyList(),
        @SerializedName("pros") val pros: List<String>? = emptyList(),
        @SerializedName("cons") val cons: List<String>? = emptyList(),
        @SerializedName("total_system_watts") val totalSystemWatts: Int? = null,
        @SerializedName("recommended_psu_watts") val recommendedPsuWatts: Int? = null,
        @SerializedName("psu_efficiency_tip") val psuEfficiencyTip: String? = null,
        @SerializedName("has_bottleneck") val hasBottleneck: Boolean? = null,
        @SerializedName("bottleneck_component") val bottleneckComponent: String? = null,
        @SerializedName("bottleneck_percentage") val bottleneckPercentage: Float? = null,
        @SerializedName("bottleneck_description") val bottleneckDescription: String? = null,
        @SerializedName("compatibility_score") val compatibilityScore: Float? = null,
        @SerializedName("is_compatible") val isCompatible: Boolean? = null
    )

    data class BuildRecommendationResultDto(
        @SerializedName("selected_tier") val selectedTier: String? = null,
        @SerializedName("entry") val entry: TieredBuildDto? = null,
        @SerializedName("mid") val mid: TieredBuildDto? = null,
        @SerializedName("high") val high: TieredBuildDto? = null,
        @SerializedName("overall_score") val overallScore: Float? = 0f,
        @SerializedName("best_for_games") val bestForGames: List<String>? = emptyList(),
        @SerializedName("summary") val summary: String? = null
    )

    data class IdentifiedComponentDto(
        @SerializedName("component_type") val componentType: String,
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("confidence") val confidence: Float? = 0f,
        @SerializedName("raw_text") val rawText: String? = null
    )

    data class HardwareSpecsDto(
        @SerializedName("cpu") val cpu: CpuSpecDto? = null,
        @SerializedName("gpu") val gpu: GpuSpecDto? = null,
        @SerializedName("motherboard") val motherboard: MotherboardSpecDto? = null,
        @SerializedName("ram") val ram: RamSpecDto? = null,
        @SerializedName("storage") val storage: StorageSpecDto? = null,
        @SerializedName("secondary_storage") val secondaryStorage: StorageSpecDto? = null,
        @SerializedName("psu") val psu: PsuSpecDto? = null,
        @SerializedName("cooler") val cooler: CoolerSpecDto? = null,
        @SerializedName("case") val case: CaseSpecDto? = null
    )

    data class CpuSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("cores") val cores: Int? = null,
        @SerializedName("threads") val threads: Int? = null,
        @SerializedName("base_clock") val baseClock: String? = null,
        @SerializedName("boost_clock") val boostClock: String? = null,
        @SerializedName("socket") val socket: String? = null,
        @SerializedName("tdp_watts") val tdpWatts: Int? = null
    )

    data class GpuSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("vram_gb") val vramGb: Float? = null,
        @SerializedName("memory_type") val memoryType: String? = null,
        @SerializedName("interface") val iface: String? = null,
        @SerializedName("tdp_watts") val tdpWatts: Int? = null,
        @SerializedName("length_mm") val lengthMm: Int? = null
    )

    data class MotherboardSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("socket") val socket: String? = null,
        @SerializedName("chipset") val chipset: String? = null,
        @SerializedName("form_factor") val formFactor: String? = null,
        @SerializedName("memory_slots") val memorySlots: Int? = null,
        @SerializedName("max_ram_gb") val maxRamGb: Int? = null,
        @SerializedName("ram_type") val ramType: String? = null
    )

    data class RamSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("total_capacity_gb") val totalCapacityGb: Int? = null,
        @SerializedName("module_count") val moduleCount: Int? = null,
        @SerializedName("ram_type") val ramType: String? = null,
        @SerializedName("speed_mhz") val speedMhz: Int? = null,
        @SerializedName("cas_latency") val casLatency: Int? = null
    )

    data class StorageSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("capacity_gb") val capacityGb: Int? = null,
        @SerializedName("interface") val iface: String? = null,
        @SerializedName("form_factor") val formFactor: String? = null
    )

    data class PsuSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("wattage") val wattage: Int? = null,
        @SerializedName("efficiency_rating") val efficiencyRating: String? = null,
        @SerializedName("modular_type") val modularType: String? = null
    )

    data class CoolerSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("tdp_rating_watts") val tdpRatingWatts: Int? = null,
        @SerializedName("height_mm") val heightMm: Int? = null
    )

    data class CaseSpecDto(
        @SerializedName("name") val name: String,
        @SerializedName("brand") val brand: String? = null,
        @SerializedName("form_factor_support") val formFactorSupport: List<String>? = emptyList(),
        @SerializedName("max_gpu_length_mm") val maxGpuLengthMm: Int? = null,
        @SerializedName("max_cooler_height_mm") val maxCoolerHeightMm: Int? = null
    )

    data class BuildRecoRequestDto(
        @SerializedName("component_name") val componentName: String,
        @SerializedName("component_category") val componentCategory: String? = null
    )

    data class BuildRecoTierBundleDto(
        @SerializedName("cpu") val cpu: String,
        @SerializedName("motherboard") val motherboard: String,
        @SerializedName("ram") val ram: String,
        @SerializedName("gpu_nvidia") val gpuNvidia: String,
        @SerializedName("gpu_amd") val gpuAmd: String,
        @SerializedName("storage") val storage: String,
        @SerializedName("psu") val psu: String,
        @SerializedName("estimated_wattage") val estimatedWattage: Int,
        @SerializedName("bottleneck_pct") val bottleneckPct: Int
    )

    data class BuildRecoResponseDto(
        @SerializedName("success") val success: Boolean,
        @SerializedName("anchor_component") val anchorComponent: String,
        @SerializedName("anchor_category") val anchorCategory: String,
        @SerializedName("entry") val entry: BuildRecoTierBundleDto? = null,
        @SerializedName("mid") val mid: BuildRecoTierBundleDto? = null,
        @SerializedName("high") val high: BuildRecoTierBundleDto? = null,
        @SerializedName("processing_time_ms") val processingTimeMs: Int? = 0,
        @SerializedName("error_message") val errorMessage: String? = null
    )
}
