package com.pcscan.pcscan.ui.dashboard

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
            findNavController().popBackStack()
        }

        binding.btnSaveUpgrade.setIconResource(R.drawable.upgrade_save_all_icon)
        binding.btnSaveUpgrade.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSaveUpgrade.iconPadding = (10 * resources.displayMetrics.density).toInt()

        binding.btnBackToDashboard.setIconResource(R.drawable.ic_arrow_back)
        binding.btnBackToDashboard.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnBackToDashboard.iconPadding = (10 * resources.displayMetrics.density).toInt()

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
                    val uiState = state.data
                    binding.tvBasedOn.text = uiState.basedOnText

                    val tierLabel = uiState.currentBuildTierLabel
                    val hasTier = tierLabel.isNotBlank()
                    if (hasTier) {
                        binding.tvCurrentBuildTier.visibility = View.VISIBLE
                        binding.tvCurrentBuildTier.text =
                            "Your build tier: $tierLabel"
                    } else {
                        binding.tvCurrentBuildTier.visibility = View.GONE
                    }

                    val count = uiState.tierCount.coerceAtLeast(1)
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
                        binding.layoutHighComponents.removeAllViews()
                        uiState.highPath.components.forEach { comp ->
                            binding.layoutHighComponents.addView(buildComponentRow(comp))
                        }
                    } else {
                        binding.layoutHighCard.visibility = View.GONE
                    }
                }
                is ScreenState.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSaveUpgrade.setOnClickListener {
            val savedCount = viewModel.saveAllUpgradeRecommendations()
            val message = when {
                savedCount == 0 -> "All recommendations are already saved"
                savedCount == 1 -> "1 recommendation saved"
                else -> "$savedCount recommendations saved"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        binding.btnBackToDashboard.setOnClickListener {
            findNavController().popBackStack(R.id.dashboardFragment, false)
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
        const val ARG_HAS_EXISTING_BUILD = "hasExistingBuild"
        const val ARG_CURRENT_BUILD_CPU = "currentBuildCpu"
        const val ARG_CURRENT_BUILD_GPU = "currentBuildGpu"
        const val ARG_CURRENT_BUILD_MOTHERBOARD = "currentBuildMotherboard"
        const val ARG_CURRENT_BUILD_RAM = "currentBuildRam"
        const val ARG_CURRENT_BUILD_STORAGE = "currentBuildStorage"
        const val ARG_CURRENT_BUILD_PSU = "currentBuildPsu"
    }
}
