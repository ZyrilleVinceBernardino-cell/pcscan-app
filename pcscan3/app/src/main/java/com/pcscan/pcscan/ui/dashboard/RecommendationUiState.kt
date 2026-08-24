package com.pcscan.pcscan.ui.dashboard

data class CurrentBuild(
    val cpu: String = "",
    val gpu: String = "",
    val motherboard: String = "",
    val ram: String = "",
    val storage: String = "",
    val psu: String = ""
) {
    val isEmpty: Boolean
        get() = cpu.isBlank() && gpu.isBlank() && motherboard.isBlank() &&
            ram.isBlank() && storage.isBlank() && psu.isBlank()

    fun componentByName(category: String): String {
        return when (category.lowercase()) {
            "cpu" -> cpu
            "gpu" -> gpu
            "motherboard" -> motherboard
            "ram" -> ram
            "storage" -> storage
            "psu" -> psu
            else -> ""
        }
    }
}

data class SearchResultsUiState(
    val title: String = "Results",
    val results: List<String> = emptyList(),
    val selectedComponent: String? = null,
    val componentDetail: ComponentDetail? = null
)

data class BuildRecommendationUiState(
    val basedOnText: String = "Based on: GPU",
    val selectedTier: BuildTier = BuildTier.ENTRY,
    val currentBuildTier: BuildTier? = null,
    val build: BuildRecommendation? = null
)

data class UpgradePathUiState(
    val basedOnText: String = "Based on: Component",
    val hasExistingBuild: Boolean? = null,
    val isSummaryVisible: Boolean = false,
    val pathModeText: String = "",
    val currentBuild: CurrentBuild = CurrentBuild(),
    val currentBuildTier: BuildTier? = null,
    val path: UpgradePath? = null
)

data class UpgradeRecoUiState(
    val basedOnText: String = "Based on: Component",
    val currentBuild: CurrentBuild = CurrentBuild(),
    val currentBuildTier: BuildTier? = null,
    val currentBuildTierLabel: String = "",
    val tierCount: Int = 3,
    val entryPath: UpgradePath? = null,
    val midPath: UpgradePath? = null,
    val highPath: UpgradePath? = null
)
