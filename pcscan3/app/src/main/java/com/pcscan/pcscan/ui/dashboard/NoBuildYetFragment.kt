package com.pcscan.pcscan.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentNoBuildYetBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NoBuildYetFragment : Fragment() {

    private enum class UserCompTier { ENTRY, MIDRANGE, HIGH_END }

    private var _binding: FragmentNoBuildYetBinding? = null
    private val binding get() = _binding!!

    private var currentTierData: NbThreeTiers? = null
    private val viewModel: RecommendationViewModel by activityViewModels()

    // The upgrade tiers are built locally, so the overlay only waits for the content to render.
    private var contentRendered = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoBuildYetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            safePopBack()
        }

        val isViewingSaved = arguments?.getBoolean(ARG_IS_VIEWING_SAVED, false) == true
        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        val fromRangeBuild = arguments?.getBoolean("fromRangeBuild", false) == true
        val rangeBudgetLabel = arguments?.getString("rangeBudgetLabel").orEmpty()
        val rangeUsageLabel = arguments?.getString("rangeUsageLabel").orEmpty()

        // Show the branding loading screen while the upgrade path for this component is prepared.
        val loadingComponent = selectedComponent.takeIf { it.isNotBlank() }
        val shouldShowLoading = !isViewingSaved && loadingComponent != null &&
            !(viewModel.allBuildsReady.value == true && viewModel.isBuildCacheReadyFor(loadingComponent))
        if (shouldShowLoading) {
            showLoadingOverlay(instant = true)
            // Warm the build cache for the screens that follow, but never block this screen on it.
            viewModel.preloadAllBuildsFor(loadingComponent.orEmpty())
        }

        when {
            fromRangeBuild && rangeBudgetLabel.isNotBlank() && rangeUsageLabel.isNotBlank() -> {
                binding.tvBasedOn.text = "Based on: $rangeBudgetLabel Budget, $rangeUsageLabel Build"
            }
            selectedComponent.isNotBlank() -> {
                binding.tvBasedOn.text = "Based on: $selectedComponent"
            }
        }

        val categoryType = detectCategoryType(selectedComponent)
        val categoryLabel = categoryType.uppercase()

        binding.layoutEntryComponents.removeAllViews()
        binding.layoutMidComponents.removeAllViews()
        binding.layoutHighComponents.removeAllViews()

        val tierData: NbThreeTiers
        if (isViewingSaved) {
            val entryComps = deserializeViewingComponents(arguments?.getString(ARG_VIEWING_ENTRY_PATH_COMPS).orEmpty())
            val midComps = deserializeViewingComponents(arguments?.getString(ARG_VIEWING_MID_PATH_COMPS).orEmpty())
            val highComps = deserializeViewingComponents(arguments?.getString(ARG_VIEWING_HIGH_PATH_COMPS).orEmpty())
            val hasAnyComponents = entryComps.isNotEmpty() || midComps.isNotEmpty() || highComps.isNotEmpty()
            val entryTitle = arguments?.getString(ARG_VIEWING_ENTRY_PATH_TITLE)
            val midTitle = arguments?.getString(ARG_VIEWING_MID_PATH_TITLE)
            val highTitle = arguments?.getString(ARG_VIEWING_HIGH_PATH_TITLE)
            val hasAnyTitles = entryTitle != null || midTitle != null || highTitle != null
            if (hasAnyComponents && hasAnyTitles) {
                val entryPath = entryTitle?.let { t ->
                    val desc = arguments?.getString(ARG_VIEWING_ENTRY_PATH_DESCRIPTION, "").orEmpty()
                    NbTierPath(title = t, description = desc, components = entryComps)
                }
                val midPath = midTitle?.let { t ->
                    val desc = arguments?.getString(ARG_VIEWING_MID_PATH_DESCRIPTION, "").orEmpty()
                    NbTierPath(title = t, description = desc, components = midComps)
                }
                val highPath = highTitle?.let { t ->
                    val desc = arguments?.getString(ARG_VIEWING_HIGH_PATH_DESCRIPTION, "").orEmpty()
                    NbTierPath(title = t, description = desc, components = highComps)
                }
                tierData = NbThreeTiers(entry = entryPath, mid = midPath, high = highPath)
            } else if (selectedComponent.isNotBlank()) {
                val userTier = classifyUserCompTier(selectedComponent, categoryType)
                val generated = buildTierData(userTier, selectedComponent, categoryType)
                val effectiveEntry = if (entryTitle != null) {
                    generated.entry?.copy(
                        title = entryTitle,
                        description = arguments?.getString(ARG_VIEWING_ENTRY_PATH_DESCRIPTION, "").orEmpty()
                    ) ?: generated.entry
                } else generated.entry
                val effectiveMid = if (midTitle != null) {
                    generated.mid?.copy(
                        title = midTitle,
                        description = arguments?.getString(ARG_VIEWING_MID_PATH_DESCRIPTION, "").orEmpty()
                    ) ?: generated.mid
                } else generated.mid
                val effectiveHigh = if (highTitle != null) {
                    generated.high?.copy(
                        title = highTitle,
                        description = arguments?.getString(ARG_VIEWING_HIGH_PATH_DESCRIPTION, "").orEmpty()
                    ) ?: generated.high
                } else generated.high
                tierData = NbThreeTiers(entry = effectiveEntry, mid = effectiveMid, high = effectiveHigh)
            } else {
                tierData = NbThreeTiers(null, null, null)
            }
        } else {
            val userTier = classifyUserCompTier(selectedComponent, categoryType)
            tierData = buildTierData(userTier, selectedComponent, categoryType)
        }
        currentTierData = tierData

        val shownTiers = listOfNotNull(
            tierData.entry, tierData.mid, tierData.high
        )
        val countLabel = when (shownTiers.size) {
            0 -> "AI has analyzed your $categoryLabel:"
            1 -> "AI has analyzed your $categoryLabel and prepared 1 upgrade tier:"
            else -> "AI has analyzed your $categoryLabel and prepared ${shownTiers.size} upgrade tiers:"
        }
        binding.tvIntroTiers.text = countLabel

        tierData.entry?.let { entry ->
            binding.layoutEntryCard.visibility = View.VISIBLE
            binding.tvEntryTitle.text = entry.title
            binding.tvEntryDescription.text = entry.description
            entry.components.forEach { comp ->
                binding.layoutEntryComponents.addView(buildComponentRow(comp))
            }
        } ?: run { binding.layoutEntryCard.visibility = View.GONE }

        tierData.mid?.let { mid ->
            binding.layoutMidCard.visibility = View.VISIBLE
            binding.tvMidTitle.text = mid.title
            binding.tvMidDescription.text = mid.description
            mid.components.forEach { comp ->
                binding.layoutMidComponents.addView(buildComponentRow(comp))
            }
        } ?: run { binding.layoutMidCard.visibility = View.GONE }

        tierData.high?.let { high ->
            binding.layoutHighCard.visibility = View.VISIBLE
            binding.tvHighTitle.text = high.title
            binding.tvHighDescription.text = high.description
            high.components.forEach { comp ->
                binding.layoutHighComponents.addView(buildComponentRow(comp))
            }
        } ?: run { binding.layoutHighCard.visibility = View.GONE }

        val density = resources.displayMetrics.density
        val iconPaddingPx = (10 * density).toInt()

        binding.btnSaveUpgrade.setIconResource(R.drawable.ic_arrow_back)
        binding.btnSaveUpgrade.iconGravity =
            com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSaveUpgrade.iconPadding = iconPaddingPx
        binding.btnSaveUpgrade.iconSize = (24 * density).toInt()

        binding.btnGenerateBuild.iconGravity =
            com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnGenerateBuild.iconPadding = iconPaddingPx
        binding.btnGenerateBuild.iconSize = (24 * density).toInt()

        if (isViewingSaved) {
            binding.btnGenerateBuild.visibility = View.GONE
        } else {
            binding.btnGenerateBuild.visibility = View.VISIBLE
            showSaveUpgradeButton()
        }

        updateSaveButtonVisibility(tierData)
        viewModel.savedUpgrades.observe(viewLifecycleOwner) {
            if (_binding == null) return@observe
            currentTierData?.let { updateSaveButtonVisibility(it) }
        }

        binding.btnSaveUpgrade.setOnClickListener {
            safePopBackToDashboard()
        }

        // The upgrade tiers are now rendered behind the overlay, so the loading screen may finish.
        contentRendered = true
        maybeFinishLoading()
    }

    /**
     * Saves every generated upgrade tier for the selected component. Owned by this fragment so the
     * button handler is always attached whenever the button shows the "Save Upgrade Path" state.
     */
    private fun performUpgradePathSave() {
        if (_binding == null) return
        val tierData = currentTierData ?: return
        val component = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        if (component.isBlank()) {
            Toast.makeText(requireContext(), "No component selected", Toast.LENGTH_SHORT).show()
            return
        }
        val hasExistingBuild = arguments?.getBoolean(ARG_HAS_EXISTING_BUILD, false) ?: false
        val tiersToSave = listOfNotNull(
            tierData.entry?.let { "entry" to it },
            tierData.mid?.let { "midrange" to it },
            tierData.high?.let {
                if (it.title.startsWith("Your component is already", ignoreCase = true)) {
                    null
                } else {
                    "high-end" to it
                }
            }
        )
        if (tiersToSave.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Nothing to save - your component is already high-end top tier.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val paths = tiersToSave.map { (tierLabel, nbTier) -> convertNbtierToRepo(nbTier, tierLabel) }
        val saved = viewModel.saveUpgradePaths(
            component = component,
            hasExistingBuild = hasExistingBuild,
            paths = paths,
            displayTitle = ""
        )
        val msg = when {
            saved > 0 -> "Saved $saved upgrade ${if (saved == 1) "tier" else "tiers"}"
            else -> "Upgrade recommendations already saved for this component."
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        // After saving, refresh the generated recommendation and return to the dashboard.
        safePopBackToDashboard()
    }

    /**
     * The upgrade tiers are built locally, so the overlay must never wait on the background build
     * preload. Reveal the content as soon as it is rendered and let the preload finish silently.
     */
    private fun maybeFinishLoading() {
        if (_binding == null) return
        if (!contentRendered) return
        if (binding.loadingOverlay.visibility != View.VISIBLE) return
        viewLifecycleOwner.lifecycleScope.launch {
            delay(350L)
            if (_binding == null) return@launch
            if (!contentRendered) return@launch
            if (binding.loadingOverlay.visibility == View.VISIBLE) hideLoadingOverlay()
        }
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        if (_binding == null) return
        binding.svNoBuildYetContent.visibility = View.GONE
        binding.loadingOverlay.alpha = if (instant) 1f else 0f
        binding.loadingOverlay.visibility = View.VISIBLE
        if (!instant) {
            binding.loadingOverlay.animate()
                .alpha(1f)
                .setDuration(150L)
                .setListener(null)
                .start()
        }
    }

    private fun hideLoadingOverlay() {
        if (_binding == null) return
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (_binding == null) return
                    binding.loadingOverlay.visibility = View.GONE
                    binding.svNoBuildYetContent.visibility = View.VISIBLE
                }
            })
            .start()
    }

    private fun updateSaveButtonVisibility(tierData: NbThreeTiers) {
        if (_binding == null) return
        val isViewingSaved = arguments?.getBoolean(ARG_IS_VIEWING_SAVED, false) == true
        if (isViewingSaved) {
            binding.btnGenerateBuild.visibility = View.GONE
            return
        }

        val component = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        val hasExistingBuild = arguments?.getBoolean(ARG_HAS_EXISTING_BUILD, false) ?: false
        val entryTier = tierData.entry
        val midTier = tierData.mid
        val highTier = tierData.high
        val tiersToSave = listOfNotNull(
            entryTier?.let { "entry" to it },
            midTier?.let { "midrange" to it },
            highTier?.let {
                if (it.title.startsWith("Your component is already", ignoreCase = true)) {
                    null
                } else {
                    "high-end" to it
                }
            }
        )
        if (tiersToSave.isEmpty()) {
            binding.btnGenerateBuild.visibility = View.GONE
            return
        }
        val paths = tiersToSave.map { (tierLabel, nbTier) -> convertNbtierToRepo(nbTier, tierLabel) }
        val isSaved = viewModel.isUpgradePathsSaved(component, hasExistingBuild, paths)
        binding.btnGenerateBuild.visibility = View.VISIBLE
        if (isSaved) {
            showSavedUpgradeButton()
        } else {
            showSaveUpgradeButton()
        }
    }

    private fun showSaveUpgradeButton() {
        if (_binding == null) return
        val button = binding.btnGenerateBuild
        button.visibility = View.VISIBLE
        button.text = "Save Upgrade Path"
        button.setIconResource(R.drawable.upgrade_save_all_icon)
        button.isClickable = true
        button.isFocusable = true
        // Always (re)attach the handler: showSavedUpgradeButton() clears it, so relying on a single
        // attach point left the button unresponsive once the saved state flipped back.
        button.setOnClickListener { performUpgradePathSave() }
    }

    private fun showSavedUpgradeButton() {
        if (_binding == null) return
        binding.btnGenerateBuild.visibility = View.VISIBLE
        binding.btnGenerateBuild.text = "Saved"
        binding.btnGenerateBuild.setIconResource(R.drawable.saved_upgrade_save_icon)
        binding.btnGenerateBuild.setOnClickListener(null)
        binding.btnGenerateBuild.isClickable = false
        binding.btnGenerateBuild.isFocusable = false
    }

    private fun deserializeViewingComponents(raw: String): List<NbUpgradeComponent> {
        if (raw.isBlank()) return emptyList()
        val items = raw.split("||").filter { it.isNotBlank() }
        return items.mapNotNull { item ->
            val parts = item.split("|~|")
            if (parts.size < 5) return@mapNotNull null
            runCatching {
                NbUpgradeComponent(
                    category = parts[0],
                    name = parts[1],
                    description = parts[2],
                    compatibility = parts[3].toIntOrNull() ?: 0,
                    progressPercent = parts[4].toFloatOrNull() ?: 0f
                )
            }.getOrNull()
        }
    }

    private fun convertNbtierToRepo(nb: NbTierPath, tierLabel: String): UpgradePath {
        return UpgradePath(
            title = nb.title,
            description = nb.description,
            tier = tierLabel,
            components = nb.components.map { local ->
                UpgradeComponent(
                    category = local.category,
                    name = local.name,
                    description = local.description,
                    compatibility = local.compatibility,
                    progressPercent = local.progressPercent,
                    scores = null
                )
            },
            stepOne = "",
            stepTwo = "",
            stepThree = ""
        )
    }

    private data class NbUpgradeComponent(
        val category: String,
        val name: String,
        val description: String,
        val compatibility: Int,
        val progressPercent: Float = compatibility / 100f
    )

    private data class NbTierPath(
        val title: String,
        val description: String,
        val components: List<NbUpgradeComponent>
    )

    private data class NbThreeTiers(
        val entry: NbTierPath?,
        val mid: NbTierPath?,
        val high: NbTierPath?
    )

    private fun detectCategoryType(componentName: String): String {
        val resolved = RecommendationMockData.resolveCategory(componentName)
        if (resolved.isNotBlank()) return resolved
        val name = componentName.lowercase()
        return when {
            name.contains("rtx") || name.contains("gtx") ||
                name.contains("rx ") || name.contains("radeon") ||
                name.contains("gpu") || name.contains("graphics") -> "gpu"
            name.contains("ryzen") || name.contains("core i") ||
                name.contains("cpu") || name.contains("processor") -> "cpu"
            listOf("b450","b550","x570","b650","x670","h610","b660","b760","z690","z790","motherboard","socket","am4","am5","lga")
                .any { name.contains(it) } -> "motherboard"
            name.contains("ddr") || name.contains("ram") || name.contains("dimm") || name.contains("memory") -> "ram"
            listOf("ssd","nvme","hdd","m.2","sata","storage","samsung","wd","crucial","kingston","adata","seagate","lexar")
                .any { name.contains(it) } -> "storage"
            listOf("w","80+","gold","bronze","silver","platinum","titanium","seasonic","evga","corsair rm","corsair sf","psu","tecware","fsp","be quiet","cooler master","fractal","nzxt")
                .any { name.contains(it) } -> "psu"
            else -> "gpu"
        }
    }

    private fun classifyUserCompTier(componentName: String, categoryType: String): UserCompTier {
        val mockTier = RecommendationMockData.classifyUserComponentTier3(componentName, categoryType)
        return when (mockTier) {
            RecommendationMockData.UserCompTier3.ENTRY -> UserCompTier.ENTRY
            RecommendationMockData.UserCompTier3.MIDRANGE -> UserCompTier.MIDRANGE
            RecommendationMockData.UserCompTier3.HIGH_END -> UserCompTier.HIGH_END
        }
    }

    /** Resolves the motherboard platform so the recommended board stays on the same vendor/socket. */
    private fun moboPlatformOf(componentName: String): String {
        val n = componentName.lowercase()
        return when {
            n.contains("x870") || n.contains("x670") || n.contains("b850") || n.contains("b650") ||
                n.contains("a620") || n.contains("am5") -> "AM5"
            n.contains("x570") || n.contains("b550") || n.contains("b450") || n.contains("a520") ||
                n.contains("x470") || n.contains("b350") || n.contains("am4") -> "AM4"
            n.contains("z890") || n.contains("b860") || n.contains("h810") || n.contains("lga1851") -> "LGA1851"
            n.contains("z590") || n.contains("z490") || n.contains("b560") || n.contains("b460") ||
                n.contains("h510") || n.contains("h470") || n.contains("lga1200") -> "LGA1200"
            n.contains("z790") || n.contains("z690") || n.contains("b760") || n.contains("b660") ||
                n.contains("h610") || n.contains("h670") || n.contains("h770") || n.contains("lga1700") -> "LGA1700"
            else -> "AM5"
        }
    }

    private fun findStrongerHighEndOptionOrNull(
        componentName: String,
        categoryType: String,
        categoryLabel: String
    ): NbUpgradeComponent? {
        val name = componentName.lowercase()
        val cat = categoryType.lowercase()
        if (cat == "gpu") {
            return when {
                name.contains("rtx 5090") -> null
                name.contains("rtx 4090") -> NbUpgradeComponent(
                    categoryLabel, "RTX 5090",
                    "Next-gen Blackwell flagship GPU - future-proof 4K+ gaming and RT with DLSS 4.",
                    99
                )
                name.contains("rtx 5080") -> NbUpgradeComponent(
                    categoryLabel, "RTX 5090",
                    "Blackwell flagship - more CUDA cores, higher VRAM, fastest 4K/8K rasterization.",
                    99
                )
                name.contains("rtx 4080 super") || name.contains("rtx 4080") -> NbUpgradeComponent(
                    categoryLabel, "RTX 5090",
                    "Largest generational jump - full next-gen Blackwell with massive ray tracing uplift.",
                    98
                )
                name.contains("rtx 4070 ti super") || name.contains("rtx 4070 ti") || name.contains("rtx 4070 super") -> NbUpgradeComponent(
                    categoryLabel, "RTX 4080 Super",
                    "Premium 4K gaming with top-tier ray tracing and massive raster performance.",
                    97
                )
                name.contains("rx 7900 xtx") || name.contains("rx 7900 gre") -> NbUpgradeComponent(
                    categoryLabel, "RTX 5090",
                    "Blackwell flagship - unmatched ray tracing, DLSS 4, and 4K high-refresh performance.",
                    98
                )
                name.contains("rx 9060 xt") || name.contains("rx 9060xt") ||
                    name.contains("rx 9070 xt") || name.contains("rx 9070xt") -> NbUpgradeComponent(
                    categoryLabel, "AMD Radeon RX 9070 XT",
                    "Top-tier real RDNA4 high-end GPU - highest raster and streaming performance from the confirmed AMD RX 9000 lineup (9060 XT / 9070 XT only).",
                    99
                )
                else -> null
            }
        }
        if (cat == "cpu") {
            return when {
                name.contains("9950x") -> null
                name.contains("9900x") -> NbUpgradeComponent(
                    categoryLabel, "Ryzen 9 9950X",
                    "Top-tier 16-core Zen 5 flagship for extreme productivity and gaming.",
                    99
                )
                name.contains("7950x") || name.contains("7950x3d") -> NbUpgradeComponent(
                    categoryLabel, "Ryzen 9 9950X",
                    "New-gen 16-core Zen 5 - higher IPC and clocks vs 7000-series.",
                    99
                )
                name.contains("7900x") || name.contains("7900x3d") || name.contains("7900 ") || name.contains("ryzen 9 7900") -> NbUpgradeComponent(
                    categoryLabel, "Ryzen 9 9950X",
                    "Jump to 16 cores - maximum multithreaded creation and future-proof gaming.",
                    98
                )
                name.contains("7800x3d") || name.contains("7700x") || name.contains("7700 ") || name.contains("ryzen 7 7700") -> NbUpgradeComponent(
                    categoryLabel, "Ryzen 9 7900X",
                    "12-core flagship - smooth streaming, heavy creation, and top gaming performance.",
                    97
                )
                name.contains("i7") && name.contains("14700") -> NbUpgradeComponent(
                    categoryLabel, "Intel Core Ultra 9 285K",
                    "Arrow Lake flagship - top hybrid IPC + efficiency for all workloads.",
                    97
                )
                name.contains("i9") || name.contains("ultra 7") || name.contains("ultra 9") -> NbUpgradeComponent(
                    categoryLabel, "Intel Core Ultra 9 285K",
                    "Flagship Arrow Lake - highest clocks and best hybrid gaming + creation.",
                    98
                )
                else -> null
            }
        }
        if (cat == "ram") {
            return when {
                name.contains("ddr5") && (name.contains("6400") || name.contains("6800") || name.contains("7200") || name.contains("7600") || name.contains("8000")) &&
                    (name.contains("32gb") || name.contains("48gb") || name.contains("64gb") || name.contains("96gb") || name.contains("128gb")) -> null
                name.contains("ddr4") || name.contains("3200") || name.contains("3600") -> NbUpgradeComponent(
                    categoryLabel, "DDR5-6400 32GB Kit (2x16GB)",
                    "Modern DDR5 upgrade - higher bandwidth and lower power for new platforms.",
                    95
                )
                name.contains("16gb") || name.contains("8gb") -> NbUpgradeComponent(
                    categoryLabel, "DDR5-6400 64GB Kit (2x32GB)",
                    "Larger DDR5 kit - multitasking, content creation, and future-proofing.",
                    96
                )
                else -> NbUpgradeComponent(
                    categoryLabel, "DDR5-7200 64GB Kit (2x32GB) EXPO/XMP",
                    "High-speed low-latency DDR5 flagship kit tuned for AMD EXPO / Intel XMP.",
                    95
                )
            }
        }
        if (cat == "motherboard") {
            // Keep the recommendation on the same vendor/socket as the user's board (AMD -> AMD, Intel -> Intel).
            return when (moboPlatformOf(componentName)) {
                "AM4" -> if (name.contains("x570") || name.contains("x470")) null else NbUpgradeComponent(
                    categoryLabel, "X570 Motherboard (AM4, DDR4, PCIe 4.0)",
                    "AM4 flagship chipset - full PCIe 4.0 lane count, stronger VRM, premium I/O.",
                    96
                )
                "LGA1200" -> if (name.contains("z590")) null else NbUpgradeComponent(
                    categoryLabel, "Z590 Motherboard (LGA1200, DDR4, PCIe 4.0)",
                    "Flagship LGA1200 board - stronger VRM, PCIe 4.0 support, and premium Intel platform I/O.",
                    95
                )
                "LGA1700" -> if (name.contains("z790") || name.contains("z690")) null else NbUpgradeComponent(
                    categoryLabel, "Z790 Motherboard (LGA1700, DDR5, PCIe 5.0)",
                    "Flagship LGA1700 board - best overclocking, PCIe 5.0, max RAM slots.",
                    95
                )
                "LGA1851" -> if (name.contains("z890")) null else NbUpgradeComponent(
                    categoryLabel, "Z890 Motherboard (LGA1851, DDR5, PCIe 5.0)",
                    "Flagship LGA1851 board - new-gen VRM, PCIe 5.0 x16, premium connectivity.",
                    96
                )
                else -> if (name.contains("x670") || name.contains("x870")) null else NbUpgradeComponent(
                    categoryLabel, "X670E Motherboard (AM5, DDR5, PCIe 5.0)",
                    "Flagship AM5 chipset - full PCIe 5.0 x16, robust VRM, maximum connectivity.",
                    95
                )
            }
        }
        if (cat == "storage") {
            return when {
                name.contains("2tb") || name.contains("4tb") || name.contains("8tb") -> NbUpgradeComponent(
                    categoryLabel, "Samsung 990 PRO 4TB NVMe Gen4 M.2 SSD",
                    "Huge high-speed NVMe upgrade - games, projects, and scratch storage all on one drive.",
                    96
                )
                name.contains("1tb") || name.contains("512gb") || name.contains("sata") || name.contains("hdd") -> NbUpgradeComponent(
                    categoryLabel, "Samsung 990 PRO 2TB NVMe Gen4 M.2 SSD",
                    "Fast NVMe Gen4 upgrade - massive sequential/random speeds for boot, games, and projects.",
                    95
                )
                else -> NbUpgradeComponent(
                    categoryLabel, "Samsung 990 PRO 8TB NVMe Gen4 M.2 SSD",
                    "Top-tier massive NVMe - largest consumer capacity with flagship speeds.",
                    97
                )
            }
        }
        if (cat == "psu") {
            return when {
                name.contains("1000w") || name.contains("1200w") || name.contains("1300w") || name.contains("1600w") -> null
                name.contains("850w") || name.contains("750w") -> NbUpgradeComponent(
                    categoryLabel, "Corsair RM1000x (1000W 80+ Gold Fully Modular)",
                    "High-wattage flagship Gold-rated PSU - headroom for high-end GPUs, quiet fan.",
                    96
                )
                name.contains("650w") || name.contains("550w") || name.contains("bronze") || name.contains("non-modular") -> NbUpgradeComponent(
                    categoryLabel, "Seasonic FOCUS GX-850 (850W 80+ Gold Fully Modular)",
                    "Solid Gold-rated fully-modular PSU upgrade - cleaner build, quieter fan, higher efficiency.",
                    95
                )
                else -> NbUpgradeComponent(
                    categoryLabel, "Seasonic PRIME TX-1000 (1000W 80+ Titanium Fully Modular)",
                    "Titanium-efficiency flagship PSU - best efficiency, longest warranty, and quietest cooling.",
                    97
                )
            }
        }
        return null
    }

    private fun buildTierData(
        userTier: UserCompTier,
        selectedComponent: String,
        categoryType: String
    ): NbThreeTiers {
        val categoryLabel = categoryType.uppercase()

        val midCpuComp = NbUpgradeComponent(
            categoryLabel, "Ryzen 7 7700",
            "Solid 8-core Zen 4 upgrade - big IPC gains, fast gaming and multitasking.",
            94
        )
        val highCpuComp = NbUpgradeComponent(
            categoryLabel, "Ryzen 9 7900",
            "12-core Zen 4 powerhouse - heavy multitasking, streaming, creation.",
            97
        )
        val midGpuComp = NbUpgradeComponent(
            categoryLabel, "RTX 4060 Ti",
            "Solid 1080p/1440p card with frame generation and great efficiency.",
            91
        )
        val highGpuComp = NbUpgradeComponent(
            categoryLabel, "RTX 4070 Super",
            "Excellent 1440p ray tracing and rasterization performance uplift.",
            95
        )
        val highOnlyCpuComp = NbUpgradeComponent(
            categoryLabel, "Ryzen 9 7900X",
            "12-core flagship Zen 4 - smooth streaming and content creation.",
            96
        )
        val highOnlyGpuComp = NbUpgradeComponent(
            categoryLabel, "RTX 4080 Super",
            "Premium 4K gaming with top-tier ray tracing.",
            97
        )
        val midRamComp = NbUpgradeComponent(
            categoryLabel, "DDR5-6000 32GB Kit (2x16GB) EXPO/XMP",
            "Midrange DDR5 upgrade - comfortable capacity and solid speed for most workloads.",
            93
        )
        val highRamComp = NbUpgradeComponent(
            categoryLabel, "DDR5-6800 64GB Kit (2x32GB) EXPO/XMP",
            "High-end DDR5 - large capacity + low latency for creation and future-proofing.",
            96
        )
        // Motherboard upgrades must match the user's board vendor/socket (AMD -> AMD, Intel -> Intel).
        val midMoboComp: NbUpgradeComponent
        val highMoboComp: NbUpgradeComponent
        when (moboPlatformOf(selectedComponent)) {
            "AM4" -> {
                midMoboComp = NbUpgradeComponent(
                    categoryLabel, "B550 Motherboard (AM4, DDR4, PCIe 4.0)",
                    "Modern AM4 board - DDR4 and PCIe 4.0 while keeping the same socket for your current CPU.",
                    92
                )
                highMoboComp = NbUpgradeComponent(
                    categoryLabel, "X570 Motherboard (AM4, DDR4, PCIe 4.0)",
                    "AM4 flagship chipset - more PCIe 4.0 lanes, stronger VRM, premium I/O on the same socket.",
                    97
                )
            }
            "LGA1200" -> {
                midMoboComp = NbUpgradeComponent(
                    categoryLabel, "B560 Motherboard (LGA1200, DDR4)",
                    "Modern LGA1200 board - keeps the Intel platform while adding better connectivity and a cleaner upgrade path.",
                    92
                )
                highMoboComp = NbUpgradeComponent(
                    categoryLabel, "Z590 Motherboard (LGA1200, DDR4, PCIe 4.0)",
                    "Flagship LGA1200 board - stronger VRM, PCIe 4.0 support, and premium Intel I/O on the same socket.",
                    97
                )
            }
            "LGA1700" -> {
                midMoboComp = NbUpgradeComponent(
                    categoryLabel, "B760 Motherboard (LGA1700, DDR5)",
                    "Feature-rich LGA1700 board - DDR5 support and modern connectivity on the same socket.",
                    92
                )
                highMoboComp = NbUpgradeComponent(
                    categoryLabel, "Z790 Motherboard (LGA1700, DDR5, PCIe 5.0)",
                    "Flagship LGA1700 board - best overclocking, PCIe 5.0, maximum I/O on the same socket.",
                    97
                )
            }
            "LGA1851" -> {
                midMoboComp = NbUpgradeComponent(
                    categoryLabel, "B860 Motherboard (LGA1851, DDR5)",
                    "Modern LGA1851 board - DDR5 and full Core Ultra connectivity on the same socket.",
                    92
                )
                highMoboComp = NbUpgradeComponent(
                    categoryLabel, "Z890 Motherboard (LGA1851, DDR5, PCIe 5.0)",
                    "Flagship LGA1851 board - new-gen VRM, PCIe 5.0 x16, premium I/O on the same socket.",
                    97
                )
            }
            else -> {
                midMoboComp = NbUpgradeComponent(
                    categoryLabel, "B650 Motherboard (AM5, DDR5, PCIe 4.0)",
                    "Well-featured AM5 board - DDR5, PCIe 4.0 M.2 slots, solid VRM on the same socket.",
                    92
                )
                highMoboComp = NbUpgradeComponent(
                    categoryLabel, "X670E Motherboard (AM5, DDR5, PCIe 5.0)",
                    "AM5 flagship chipset - robust VRM, PCIe 5.0 x16, multiple M.2 slots, premium I/O.",
                    97
                )
            }
        }
        val midStorageComp = NbUpgradeComponent(
            categoryLabel, "Crucial T700 / Samsung 990 PRO 2TB NVMe Gen4 M.2",
            "Midrange NVMe upgrade - enough speed and size for OS + big game library.",
            93
        )
        val highStorageComp = NbUpgradeComponent(
            categoryLabel, "Samsung 990 PRO 4TB NVMe Gen4 M.2 SSD",
            "High-end 4TB NVMe - flagship speed with huge capacity for games and projects.",
            96
        )
        val midPsuComp = NbUpgradeComponent(
            categoryLabel, "Seasonic FOCUS GX-750 (750W 80+ Gold Fully Modular)",
            "Solid Gold-rated PSU - clean power, modular cables, quiet 120mm fan.",
            93
        )
        val highPsuComp = NbUpgradeComponent(
            categoryLabel, "Corsair RM1000x Shift (1000W 80+ Gold Fully Modular)",
            "High-wattage flagship Gold PSU - headroom for high-end GPU + overclocking.",
            96
        )

        val cat = categoryType.lowercase()
        val midComp = when (cat) {
            "gpu" -> midGpuComp
            "cpu" -> midCpuComp
            "ram" -> midRamComp
            "motherboard" -> midMoboComp
            "storage" -> midStorageComp
            "psu" -> midPsuComp
            else -> midGpuComp
        }
        val highComp = when (cat) {
            "gpu" -> highGpuComp
            "cpu" -> highCpuComp
            "ram" -> highRamComp
            "motherboard" -> highMoboComp
            "storage" -> highStorageComp
            "psu" -> highPsuComp
            else -> highGpuComp
        }
        val highOnlyComp = when (cat) {
            "gpu" -> highOnlyGpuComp
            "cpu" -> highOnlyCpuComp
            "ram" -> highRamComp
            "motherboard" -> highMoboComp
            "storage" -> highStorageComp
            "psu" -> highPsuComp
            else -> highOnlyGpuComp
        }

        return when (userTier) {
            UserCompTier.ENTRY -> {
                NbThreeTiers(
                    entry = null,
                    mid = NbTierPath(
                        title = "Midrange Upgrade Path",
                        description = "Sweet-spot upgrade that delivers excellent value, power, and longevity for most users.",
                        components = listOf(midComp)
                    ),
                    high = NbTierPath(
                        title = "High-End Upgrade Path",
                        description = "No-compromise upgrade for maximum gaming, productivity, and future-proof performance.",
                        components = listOf(highComp)
                    )
                )
            }
            UserCompTier.MIDRANGE -> {
                NbThreeTiers(
                    entry = null,
                    mid = null,
                    high = NbTierPath(
                        title = "High-End Upgrade Path",
                        description = "Maximum-performance upgrade for extreme gaming, creation, and future-proof longevity.",
                        components = listOf(highOnlyComp)
                    )
                )
            }
            UserCompTier.HIGH_END -> {
                val stronger = findStrongerHighEndOptionOrNull(selectedComponent, categoryType, categoryLabel)
                if (stronger != null) {
                    NbThreeTiers(
                        entry = null,
                        mid = null,
                        high = NbTierPath(
                            title = "High-End Upgrade Path",
                            description = "Even-stronger next-step upgrade for maximum gaming and productivity beyond your current high-end component.",
                            components = listOf(stronger)
                        )
                    )
                } else {
                    NbThreeTiers(
                        entry = null,
                        mid = null,
                        high = NbTierPath(
                            title = "Your component is already a high-end component",
                            description = "No stronger component is currently recommended for this category. Your part is already at the top tier - keeping it is the best value.",
                            components = listOf(
                                NbUpgradeComponent(
                                    categoryLabel, selectedComponent,
                                    "Current high-end component - already top tier for its category.",
                                    99
                                )
                            )
                        )
                    )
                }
            }
        }
    }

    private fun buildComponentRow(comp: NbUpgradeComponent): View {
        val density = resources.displayMetrics.density
        val categoryColor = when (comp.category.uppercase()) {
            "CPU" -> R.color.chip_cpu
            "GPU" -> R.color.chip_gpu
            "MOTHERBOARD" -> R.color.chip_motherboard
            "RAM" -> R.color.chip_ram
            "STORAGE" -> R.color.chip_storage
            "PSU" -> R.color.chip_psu
            else -> R.color.text_secondary
        }
        val categoryIcon = when (comp.category.uppercase()) {
            "CPU" -> R.drawable.build_reco_cpu_icon
            "GPU" -> R.drawable.build_reco_gpu_icon
            "MOTHERBOARD" -> R.drawable.build_reco_mobo_icon
            "RAM" -> R.drawable.build_reco_ram_icon
            "STORAGE" -> R.drawable.build_reco_storage_icon
            "PSU" -> R.drawable.build_reco_psu_icon
            else -> R.drawable.build_reco_cpu_icon
        }

        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
            background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_card_selected)
            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
        }

        val topRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                (30 * density).toInt(),
                (30 * density).toInt()
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(AppCompatResources.getDrawable(requireContext(), categoryIcon))
            contentDescription = comp.category
        }

        val textContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = (12 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }

        val categoryLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = comp.category.uppercase()
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }

        val compName = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
            text = comp.name
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_white))
            textSize = 16f
        }

        val compDesc = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
            text = comp.description
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 12f
        }

        textContainer.addView(categoryLabel)
        textContainer.addView(compName)
        textContainer.addView(compDesc)

        val compatValue = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "${comp.compatibility}%"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        topRow.addView(badge)
        topRow.addView(textContainer)
        topRow.addView(compatValue)

        val progressContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }

        val progressLabelRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val progressLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = "Compatibility Score"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        }

        val progressPercent = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "${(comp.progressPercent * 100).toInt()}%"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 11f
        }

        progressLabelRow.addView(progressLabel)
        progressLabelRow.addView(progressPercent)

        val progressBar = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            ).apply {
                topMargin = (4 * density).toInt()
            }
            isIndeterminate = false
            max = 100
            progress = (comp.progressPercent * 100).toInt()
            progressDrawable?.let { drawable ->
                drawable.setTint(ContextCompat.getColor(requireContext(), categoryColor))
            }
        }

        progressContainer.addView(progressLabelRow)
        progressContainer.addView(progressBar)

        row.addView(topRow)
        row.addView(progressContainer)
        return row
    }

    /** Normal back: return to the previous result screen WITHOUT resetting the generated state. */
    private fun safePopBack() {
        try {
            if (_binding == null || !isAdded) return
            val navController = findNavController()
            if (!navController.popBackStack()) {
                navController.navigate(R.id.dashboardFragment)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun safePopBackToDashboard() {
        try {
            if (_binding == null || !isAdded) return
            viewModel.resetAllSearchAndBuildState()
            val navController = findNavController()
            if (!navController.popBackStack(R.id.dashboardFragment, false)) {
                navController.navigate(R.id.dashboardFragment)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
        const val ARG_HAS_EXISTING_BUILD = "hasExistingBuild"
        const val ARG_IS_VIEWING_SAVED = "isViewingSaved"
        const val ARG_VIEWING_ENTRY_PATH_TITLE = "viewing_entry_title"
        const val ARG_VIEWING_ENTRY_PATH_DESCRIPTION = "viewing_entry_desc"
        const val ARG_VIEWING_ENTRY_PATH_COMPS = "viewing_entry_comps"
        const val ARG_VIEWING_MID_PATH_TITLE = "viewing_mid_title"
        const val ARG_VIEWING_MID_PATH_DESCRIPTION = "viewing_mid_desc"
        const val ARG_VIEWING_MID_PATH_COMPS = "viewing_mid_comps"
        const val ARG_VIEWING_HIGH_PATH_TITLE = "viewing_high_title"
        const val ARG_VIEWING_HIGH_PATH_DESCRIPTION = "viewing_high_desc"
        const val ARG_VIEWING_HIGH_PATH_COMPS = "viewing_high_comps"
        const val ARG_VIEWING_CATEGORY = "viewing_category"
    }
}

enum class ComponentTier {
    LEGACY_BUDGET,
    MIDRANGE,
    HIGH_END_CURRENT,
    ENTHUSIAST_FLAGSHIP
}
