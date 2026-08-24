package com.pcscan.pcscan.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentBuildRecoBinding

class BuildRecoFragment : Fragment() {

    private var _binding: FragmentBuildRecoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBuildRecoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnSaveBuild.setIconResource(R.drawable.saved_upgrade_save_icon)
        binding.btnSaveBuild.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSaveBuild.iconPadding = (10 * resources.displayMetrics.density).toInt()

        val argComponent = arguments?.getString(ARG_SELECTED_COMPONENT)
            ?.takeIf { it.isNotBlank() }

        argComponent?.let(viewModel::ensureSelectedComponent)

        val shouldShowLoading = when {
            viewModel.allBuildsReady.value == true -> false
            argComponent != null -> true
            else -> false
        }

        if (shouldShowLoading) {
            showLoadingOverlay(instant = true)
            viewModel.preloadAllBuildsFor(argComponent.orEmpty())
        }

        viewModel.allBuildsCacheProgress.observe(viewLifecycleOwner) { progress ->
            binding.pbLoadingProgress.progress = progress.coerceIn(0, 100)
            binding.tvLoadingPercent.text = "${progress.coerceIn(0, 100)}%"
        }

        viewModel.allBuildsReady.observe(viewLifecycleOwner) { ready ->
            if (ready && shouldShowLoading && binding.loadingOverlay.visibility == View.VISIBLE) {
                view.postDelayed({ hideLoadingOverlay() }, 350L)
            }
        }

        viewModel.buildRecommendationScreenState.observe(viewLifecycleOwner) { state ->
            when (state) {
                ScreenState.Idle -> clearBuild()
                ScreenState.Loading -> {
                    val currentTier = viewModel.selectedBuildTier.value ?: BuildTier.ENTRY
                    binding.tvSelectedComponent.text = "Generating build..."
                    updateTierTab(binding.tvTierEntry, currentTier == BuildTier.ENTRY)
                    updateTierTab(binding.tvTierMid, currentTier == BuildTier.MID)
                    updateTierTab(binding.tvTierHigh, currentTier == BuildTier.HIGH)
                    clearBuild(tierLabel = when (currentTier) {
                        BuildTier.ENTRY -> "ENTRY BUILD"
                        BuildTier.MID -> "MIDRANGE BUILD"
                        BuildTier.HIGH -> "HIGH-END BUILD"
                    })
                }
                is ScreenState.Success -> {
                    val uiState = state.data
                    binding.tvSelectedComponent.text = uiState.basedOnText
                    updateTierTab(binding.tvTierEntry, uiState.selectedTier == BuildTier.ENTRY)
                    updateTierTab(binding.tvTierMid, uiState.selectedTier == BuildTier.MID)
                    updateTierTab(binding.tvTierHigh, uiState.selectedTier == BuildTier.HIGH)
                    uiState.build?.let(::showBuild)
                }

                is ScreenState.Error -> {
                    clearBuild()
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        hideLoadingOverlay()
                    }
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.tvTierEntry.setOnClickListener { viewModel.selectBuildTier(BuildTier.ENTRY) }
        binding.tvTierMid.setOnClickListener { viewModel.selectBuildTier(BuildTier.MID) }
        binding.tvTierHigh.setOnClickListener { viewModel.selectBuildTier(BuildTier.HIGH) }

        binding.btnSaveBuild.setOnClickListener {
            val saved = viewModel.saveCurrentBuild()
            Toast.makeText(
                requireContext(),
                if (saved) "Build saved" else "This build is already saved",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        binding.svBuildRecoContent.visibility = View.GONE
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
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.svBuildRecoContent.visibility = View.VISIBLE
                }
            })
            .start()
    }

    private fun showBuild(build: BuildRecommendation) {
        binding.tvBuildLabel.text = build.label
        binding.tvBuildName.text = build.name
        binding.tvCpuValue.text = build.cpu
        binding.tvGpuValue.text = build.gpu
        binding.tvMotherboardValue.text = build.motherboard
        binding.tvRamValue.text = build.ram
        binding.tvStorageValue.text = build.storage
        binding.tvPsuValue.text = build.psu
        binding.tvWattageValue.text = build.wattage
        binding.tvWattageSubtext.text = build.wattageNote
        binding.tvBottleneckValue.text = build.bottleneck
        binding.tvBottleneckSubtext.text = build.bottleneckNote
    }

    private fun clearBuild(tierLabel: String? = null) {
        binding.tvBuildLabel.text = tierLabel
        binding.tvBuildName.text = ""
        binding.tvCpuValue.text = ""
        binding.tvGpuValue.text = ""
        binding.tvMotherboardValue.text = ""
        binding.tvRamValue.text = ""
        binding.tvStorageValue.text = ""
        binding.tvPsuValue.text = ""
        binding.tvWattageValue.text = ""
        binding.tvWattageSubtext.text = ""
        binding.tvBottleneckValue.text = ""
        binding.tvBottleneckSubtext.text = ""
    }

    private fun updateTierTab(tab: android.widget.TextView, isSelected: Boolean) {
        val textColor = if (isSelected) R.color.white else R.color.text_secondary
        val background = if (isSelected) R.drawable.bg_button_primary else R.drawable.bg_card

        tab.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        tab.setBackgroundResource(background)
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
