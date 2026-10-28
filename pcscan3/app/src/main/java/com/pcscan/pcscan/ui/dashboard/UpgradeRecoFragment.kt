package com.pcscan.pcscan.ui.dashboard

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
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
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUpgradeRecoBinding

class UpgradeRecoFragment : Fragment() {

    private var _binding: FragmentUpgradeRecoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpgradeRecoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            safePopBack()
        }

        binding.btnSaveUpgrade.setIconResource(R.drawable.upgrade_save_all_icon)
        binding.btnSaveUpgrade.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSaveUpgrade.iconPadding = (10 * resources.displayMetrics.density).toInt()

        binding.btnBackToDashboard.setIconResource(R.drawable.ic_arrow_back)
        binding.btnBackToDashboard.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnBackToDashboard.iconPadding = (10 * resources.displayMetrics.density).toInt()

        val isViewingSaved = arguments?.getBoolean(ARG_IS_VIEWING_SAVED, false) == true

        if (isViewingSaved) {
            // Offline viewing mode: hydrate UI state DIRECTLY from saved bundle args.
            // Do NOT call loadUpgradeRecommendations which triggers network/GPT.
            val entryComps = deserializeViewingComponents(arguments?.getString(ARG_VIEWING_ENTRY_PATH_COMPS).orEmpty())
            val midComps = deserializeViewingComponents(arguments?.getString(ARG_VIEWING_MID_PATH_COMPS).orEmpty())
            val highComps = deserializeViewingComponents(arguments?.getString(ARG_VIEWING_HIGH_PATH_COMPS).orEmpty())

            val baseTitleEntry = arguments?.getString(ARG_VIEWING_ENTRY_PATH_TITLE)
            val baseTitleMid = arguments?.getString(ARG_VIEWING_MID_PATH_TITLE)
            val baseTitleHigh = arguments?.getString(ARG_VIEWING_HIGH_PATH_TITLE)
            val hasAny = !listOf(baseTitleEntry, baseTitleMid, baseTitleHigh).all { it.isNullOrBlank() }

            val entryPath = if (entryComps.isNotEmpty()) {
                UpgradePath(
                    title = baseTitleEntry.takeUnless { it.isNullOrBlank() } ?: "Entry Upgrade Path",
                    description = arguments?.getString(ARG_VIEWING_ENTRY_PATH_DESCRIPTION, "").orEmpty(),
                    tier = "entry",
                    components = entryComps,
                    stepOne = "",
                    stepTwo = "",
                    stepThree = ""
                )
            } else if (hasAny && entryComps.isEmpty() && baseTitleEntry != null) {
                UpgradePath(
                    title = baseTitleEntry,
                    description = arguments?.getString(ARG_VIEWING_ENTRY_PATH_DESCRIPTION, "").orEmpty(),
                    tier = "entry",
                    components = emptyList(),
                    stepOne = "",
                    stepTwo = "",
                    stepThree = ""
                )
            } else null

            val midPath = if (midComps.isNotEmpty()) {
                UpgradePath(
                    title = baseTitleMid.takeUnless { it.isNullOrBlank() } ?: "Midrange Upgrade Path",
                    description = arguments?.getString(ARG_VIEWING_MID_PATH_DESCRIPTION, "").orEmpty(),
                    tier = "midrange",
                    components = midComps,
                    stepOne = "",
                    stepTwo = "",
                    stepThree = ""
                )
            } else if (hasAny && baseTitleMid != null) {
                UpgradePath(
                    title = baseTitleMid,
                    description = arguments?.getString(ARG_VIEWING_MID_PATH_DESCRIPTION, "").orEmpty(),
                    tier = "midrange",
                    components = emptyList(),
                    stepOne = "",
                    stepTwo = "",
                    stepThree = ""
                )
            } else null

            val highPath = if (highComps.isNotEmpty()) {
                UpgradePath(
                    title = baseTitleHigh.takeUnless { it.isNullOrBlank() } ?: "High-End Upgrade Path",
                    description = arguments?.getString(ARG_VIEWING_HIGH_PATH_DESCRIPTION, "").orEmpty(),
                    tier = "high-end",
                    components = highComps,
                    stepOne = "",
                    stepTwo = "",
                    stepThree = ""
                )
            } else if (hasAny && baseTitleHigh != null) {
                UpgradePath(
                    title = baseTitleHigh,
                    description = arguments?.getString(ARG_VIEWING_HIGH_PATH_DESCRIPTION, "").orEmpty(),
                    tier = "high-end",
                    components = emptyList(),
                    stepOne = "",
                    stepTwo = "",
                    stepThree = ""
                )
            } else null

            val tierLabelArg = arguments?.getString(ARG_VIEWING_CURRENT_BUILD_TIER_LABEL).orEmpty()
            val basedOnArg = arguments?.getString(ARG_VIEWING_BASED_ON_TEXT).orEmpty()
                .ifBlank { arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty().let { "Based on: $it" } }
            val allPaths = listOfNotNull(entryPath, midPath, highPath)

            viewModel.forceUpgradeRecoUiStateFromSaved(
                basedOnText = basedOnArg,
                currentBuildTierLabel = tierLabelArg,
                entry = entryPath,
                mid = midPath,
                high = highPath
            )

            // Render with observer below, then hide save button
            viewModel.upgradeRecoScreenState.observe(viewLifecycleOwner) { state ->
                when (state) {
                    ScreenState.Idle -> Unit
                    ScreenState.Loading -> binding.tvBasedOn.text = "Loading saved recommendation..."
                    is ScreenState.Success -> renderSuccess(state.data)
                    is ScreenState.Error ->
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnSaveUpgrade.visibility = View.GONE
            binding.btnBack.setOnClickListener { safePopBackToDashboard() }
            @Suppress("UNUSED_VALUE")
            val allTiersCountSnapshot = allPaths.size.coerceAtLeast(1)
            return
        }

        val argComponent = arguments?.getString(ARG_SELECTED_COMPONENT)
        val argHasExistingBuild = arguments?.getBoolean(ARG_HAS_EXISTING_BUILD, true) ?: true

        val argBuildCpu = arguments?.getString(ARG_CURRENT_BUILD_CPU).orEmpty()
        val argBuildGpu = arguments?.getString(ARG_CURRENT_BUILD_GPU).orEmpty()
        val argBuildMotherboard = arguments?.getString(ARG_CURRENT_BUILD_MOTHERBOARD).orEmpty()
        val argBuildRam = arguments?.getString(ARG_CURRENT_BUILD_RAM).orEmpty()
        val argBuildStorage = arguments?.getString(ARG_CURRENT_BUILD_STORAGE).orEmpty()
        val argBuildPsu = arguments?.getString(ARG_CURRENT_BUILD_PSU).orEmpty()

        val hasBuildArgs = listOf(
            argBuildCpu,
            argBuildGpu,
            argBuildMotherboard,
            argBuildRam,
            argBuildStorage,
            argBuildPsu
        ).any { it.isNotBlank() }

        val argBuild = if (hasBuildArgs) {
            CurrentBuild(
                cpu = argBuildCpu,
                gpu = argBuildGpu,
                motherboard = argBuildMotherboard,
                ram = argBuildRam,
                storage = argBuildStorage,
                psu = argBuildPsu
            )
        } else null

        val preCachedSuccess = viewModel.upgradeRecoScreenState.value is ScreenState.Success &&
            argComponent?.isNotBlank() == true &&
            viewModel.selectedComponent.value == argComponent &&
            (argBuild == null || viewModel.currentBuild.value == argBuild)

        if (hasBuildArgs && !preCachedSuccess && argBuild != null) {
            argComponent
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::ensureSelectedComponent)
            viewModel.setCurrentBuild(argBuild)
            viewModel.setUpgradeMode(argHasExistingBuild)
            viewModel.loadUpgradeRecommendations()
        } else if (preCachedSuccess) {
            // Pre-cached by UpgradeCurrentFragment during prepareBuildForUpgrade.
            // Skip reload; observer below will render immediately.
        } else {
            val stateMatches =
                argComponent?.isNotBlank() == true &&
                    viewModel.selectedComponent.value == argComponent &&
                    viewModel.hasExistingBuild.value == argHasExistingBuild &&
                    viewModel.upgradeRecoScreenState.value is ScreenState.Success

            if (!stateMatches) {
                argComponent
                    ?.takeIf { it.isNotBlank() }
                    ?.let(viewModel::ensureSelectedComponent)
                viewModel.setUpgradeMode(argHasExistingBuild)
                viewModel.loadUpgradeRecommendations()
            }
        }

        viewModel.upgradeRecoScreenState.observe(viewLifecycleOwner) { state ->
            when (state) {
                ScreenState.Idle -> Unit
                ScreenState.Loading -> binding.tvBasedOn.text = "Loading recommendations..."
                is ScreenState.Success -> {
                    renderSuccess(state.data)
                    updateSaveUpgradeButtonVisibility()
                }
                is ScreenState.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSaveUpgrade.setOnClickListener {
            val state = viewModel.upgradeRecoScreenState.value as? ScreenState.Success
            val allPaths = listOfNotNull(state?.data?.entryPath, state?.data?.midPath, state?.data?.highPath)
            val isBuildTopTier = allPaths.isNotEmpty() && allPaths.all {
                it.title.startsWith("Your build is already") || it.title.startsWith("Your component is already")
            }
            val isBuildMessage = allPaths.any { it.title.startsWith("Your build is already") }

            if (isBuildTopTier) {
                val msg = if (isBuildMessage) {
                    "Nothing to save - your build is already high-end top tier."
                } else {
                    "Nothing to save - your component is already high-end top tier."
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val (upgradeSavedCount, buildSavedCount) = viewModel.saveAllUpgradeRecommendations()
            val hasCurrentBuildArg = (arguments?.getBoolean(ARG_HAS_EXISTING_BUILD, true) ?: true)
            val upgradeMessage = when {
                upgradeSavedCount == 0 && buildSavedCount == 0 -> "All recommendations are already saved"
                upgradeSavedCount == 0 && buildSavedCount > 0 -> "Your current build was saved (upgrade recommendations already saved)"
                upgradeSavedCount > 0 && buildSavedCount == 0 -> {
                    if (hasCurrentBuildArg) {
                        val tierLabel = if (upgradeSavedCount == 1) "tier" else "tiers"
                        "$upgradeSavedCount upgrade $tierLabel saved (build already saved)"
                    } else {
                        val tierLabel = if (upgradeSavedCount == 1) "tier" else "tiers"
                        "$upgradeSavedCount upgrade $tierLabel saved"
                    }
                }
                else -> {
                    val tierLabel = if (upgradeSavedCount == 1) "tier" else "tiers"
                    "$upgradeSavedCount upgrade $tierLabel + your current build saved"
                }
            }
            Toast.makeText(requireContext(), upgradeMessage, Toast.LENGTH_SHORT).show()
            // After saving, refresh the generated recommendation and return to the dashboard.
            safePopBackToDashboard()
        }

        updateSaveUpgradeButtonVisibility()
        viewModel.savedUpgrades.observe(viewLifecycleOwner) {
            if (_binding == null) return@observe
            updateSaveUpgradeButtonVisibility()
        }

        binding.btnBackToDashboard.setOnClickListener {
            safePopBackToDashboard()
        }
    }

    /** Hides the Save Upgrade button once these recommendations are already stored for the user. */
    private fun updateSaveUpgradeButtonVisibility() {
        if (_binding == null) return
        binding.btnSaveUpgrade.visibility =
            if (viewModel.isCurrentUpgradeSaved()) View.GONE else View.VISIBLE
    }

    private fun buildComponentRow(comp: UpgradeComponent): View {
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
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card_selected)
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
                marginEnd = (8 * density).toInt()
            }
            text = comp.description
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
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

        row.addView(topRow)

        val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * density).toInt()
            ).apply {
                topMargin = (8 * density).toInt()
            }
            max = 100
            progress = comp.compatibility.coerceIn(0, 100)
            isIndeterminate = false
            progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.progress_gradient_bar)
        }
        row.addView(progress)

        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderSuccess(uiState: UpgradeRecoUiState) {
        binding.tvBasedOn.text = uiState.basedOnText
        val baseCompName = uiState.basedOnText.substringAfter("Based on: ", "")
        val cat = if (baseCompName.isNotBlank())
            RecommendationMockData.resolveCategory(baseCompName).uppercase()
        else "CPU"
        val icon = when (cat) {
            "CPU" -> R.drawable.build_reco_cpu_icon
            "GPU" -> R.drawable.build_reco_gpu_icon
            "MOTHERBOARD" -> R.drawable.build_reco_mobo_icon
            "RAM" -> R.drawable.build_reco_ram_icon
            "STORAGE" -> R.drawable.build_reco_storage_icon
            "PSU" -> R.drawable.build_reco_psu_icon
            else -> R.drawable.build_reco_cpu_icon
        }
        val drawable = AppCompatResources.getDrawable(requireContext(), icon)
        binding.tvBasedOn.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
        binding.tvBasedOn.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()

        val tierLabel = uiState.currentBuildTierLabel
        val hasTier = tierLabel.isNotBlank()
        if (hasTier) {
            binding.tvCurrentBuildTier.visibility = View.VISIBLE
            binding.tvCurrentBuildTier.text = "Your build tier: $tierLabel"
        } else {
            binding.tvCurrentBuildTier.visibility = View.GONE
        }

        val hasAnyPath = listOfNotNull(uiState.entryPath, uiState.midPath, uiState.highPath).isNotEmpty()
        val count = (if (hasAnyPath) listOfNotNull(uiState.entryPath, uiState.midPath, uiState.highPath).size else uiState.tierCount).coerceAtLeast(1)
        val hasExistingBuild = !uiState.currentBuild.isEmpty
        val tierSuffix = if (count == 1) "tier:" else "tiers:"
        binding.tvIntroTiers.text = when {
            hasExistingBuild && hasTier ->
                "AI has classified your build as $tierLabel. Prepared $count upgrade $tierSuffix"
            else ->
                "AI has analyzed your component and prepared $count upgrade $tierSuffix"
        }

        if (uiState.entryPath != null) {
            binding.layoutEntryCard.visibility = View.VISIBLE
            binding.tvEntryTitle.text = uiState.entryPath.title
            binding.tvEntryDescription.text = uiState.entryPath.description
            binding.tvEntryDescription.maxLines = 2
            binding.tvEntryDescription.ellipsize = TextUtils.TruncateAt.END
            binding.layoutEntryComponents.removeAllViews()
            uiState.entryPath.components.forEach { comp ->
                binding.layoutEntryComponents.addView(buildComponentRow(comp))
            }
        } else {
            binding.layoutEntryCard.visibility = View.GONE
        }

        if (uiState.midPath != null) {
            binding.layoutMidCard.visibility = View.VISIBLE
            binding.tvMidTitle.text = uiState.midPath.title
            binding.tvMidDescription.text = uiState.midPath.description
            binding.tvMidDescription.maxLines = 2
            binding.tvMidDescription.ellipsize = TextUtils.TruncateAt.END
            binding.layoutMidComponents.removeAllViews()
            uiState.midPath.components.forEach { comp ->
                binding.layoutMidComponents.addView(buildComponentRow(comp))
            }
        } else {
            binding.layoutMidCard.visibility = View.GONE
        }

        if (uiState.highPath != null) {
            binding.layoutHighCard.visibility = View.VISIBLE
            binding.tvHighTitle.text = uiState.highPath.title
            binding.tvHighDescription.text = uiState.highPath.description
            binding.tvHighDescription.maxLines = 2
            binding.tvHighDescription.ellipsize = TextUtils.TruncateAt.END
            binding.layoutHighComponents.removeAllViews()
            uiState.highPath.components.forEach { comp ->
                binding.layoutHighComponents.addView(buildComponentRow(comp))
            }
        } else {
            binding.layoutHighCard.visibility = View.GONE
        }
    }

    private fun deserializeViewingComponents(raw: String): List<UpgradeComponent> {
        if (raw.isBlank()) return emptyList()
        val rows = raw.split("||")
        return rows.mapNotNull { row ->
            if (row.isBlank()) return@mapNotNull null
            val cols = row.split("|~|")
            if (cols.size < 5) return@mapNotNull null
            runCatching {
                val compScore = cols.getOrNull(4)?.toIntOrNull()
                val progressRaw = cols.getOrNull(4)
                val progressPctVal = progressRaw?.toIntOrNull() ?: 0
                val compatInt = cols.getOrNull(3)?.toIntOrNull() ?: 90
                UpgradeComponent(
                    category = cols[0].uppercase(),
                    name = cols[1],
                    description = cols.getOrNull(2).orEmpty(),
                    compatibility = compatInt,
                    progressPercent = if (progressPctVal > 1) progressPctVal / 100f else compScore?.toFloat() ?: 0.90f
                )
            }.getOrNull()
        }
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

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
        const val ARG_IS_VIEWING_SAVED = "isViewingSaved"
        const val ARG_HAS_EXISTING_BUILD = "hasExistingBuild"
        const val ARG_CURRENT_BUILD_CPU = "currentBuildCpu"
        const val ARG_CURRENT_BUILD_GPU = "currentBuildGpu"
        const val ARG_CURRENT_BUILD_MOTHERBOARD = "currentBuildMotherboard"
        const val ARG_CURRENT_BUILD_RAM = "currentBuildRam"
        const val ARG_CURRENT_BUILD_STORAGE = "currentBuildStorage"
        const val ARG_CURRENT_BUILD_PSU = "currentBuildPsu"
        const val ARG_VIEWING_BASED_ON_TEXT = "viewing_based_on_text"
        const val ARG_VIEWING_CURRENT_BUILD_TIER_LABEL = "viewing_current_tier_label"
        const val ARG_VIEWING_ENTRY_PATH_TITLE = "viewing_entry_title"
        const val ARG_VIEWING_ENTRY_PATH_DESCRIPTION = "viewing_entry_desc"
        const val ARG_VIEWING_ENTRY_PATH_COMPS = "viewing_entry_comps"
        const val ARG_VIEWING_MID_PATH_TITLE = "viewing_mid_title"
        const val ARG_VIEWING_MID_PATH_DESCRIPTION = "viewing_mid_desc"
        const val ARG_VIEWING_MID_PATH_COMPS = "viewing_mid_comps"
        const val ARG_VIEWING_HIGH_PATH_TITLE = "viewing_high_title"
        const val ARG_VIEWING_HIGH_PATH_DESCRIPTION = "viewing_high_desc"
        const val ARG_VIEWING_HIGH_PATH_COMPS = "viewing_high_comps"
    }
}
