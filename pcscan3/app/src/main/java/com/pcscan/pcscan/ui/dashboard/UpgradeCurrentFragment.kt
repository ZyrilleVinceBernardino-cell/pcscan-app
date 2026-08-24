package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUpgradeCurrentBinding

class UpgradeCurrentFragment : Fragment() {

    private var _binding: FragmentUpgradeCurrentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    private var hasNavigated = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpgradeCurrentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        val category = if (selectedComponent.isNotBlank()) {
            RecommendationMockData.resolveCategory(selectedComponent)
        } else {
            ""
        }

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.tvBasedOn.text = "Based on: $selectedComponent"

        if (selectedComponent.isNotBlank()) {
            when (category) {
                "cpu" -> binding.etCpu.setText(selectedComponent)
                "gpu" -> binding.etGpu.setText(selectedComponent)
                "motherboard" -> binding.etMotherboard.setText(selectedComponent)
                "ram" -> binding.etRam.setText(selectedComponent)
                "storage" -> binding.etStorage.setText(selectedComponent)
                "psu" -> binding.etPsu.setText(selectedComponent)
                else -> {
                    val n = selectedComponent.lowercase()
                    val hasIntelCore = Regex("""\bi[3579]\s*[- ]?\s*\d""").containsMatchIn(n)
                    val hasRyzen = n.contains("ryzen") || n.contains("athlon")
                    val hasIntel = n.contains("intel") && !n.contains("ssd") && !n.contains("arc")
                    val hasCpuWord = n.contains("cpu") || n.contains("processor")
                    val likelyCPU = hasIntelCore || hasRyzen || hasIntel || hasCpuWord
                    val likelyMobo = listOf(
                        "b450","b550","x570","b650","x670","h610","b660","b760","z690","z790"
                    ).any { n.contains(it) }
                    val likelyRAM = n.contains("ddr") || n.contains("ram") || n.contains("memory")
                    val likelyStorage = listOf("ssd","nvme","hdd","samsung ","wd ").any { n.contains(it) }
                    val likelyPSU = n.contains("w ") && listOf(
                        "gold","bronze","platinum","titanium","80+"
                    ).any { n.contains(it) }
                    when {
                        likelyCPU -> binding.etCpu.setText(selectedComponent)
                        likelyMobo -> binding.etMotherboard.setText(selectedComponent)
                        likelyRAM -> binding.etRam.setText(selectedComponent)
                        likelyStorage -> binding.etStorage.setText(selectedComponent)
                        likelyPSU -> binding.etPsu.setText(selectedComponent)
                        else -> binding.etGpu.setText(selectedComponent)
                    }
                }
            }
        }

        viewModel.upgradePrepareProgress.observe(viewLifecycleOwner) { progress ->
            binding.pbLoadingProgress.progress = progress
            binding.tvLoadingPercent.text = "$progress%"

            if (progress >= 50) {
                if (binding.tvLoadingTitle.text != "Generating...") {
                    binding.tvLoadingTitle.text = "Generating..."
                    binding.tvLoadingSubtitle.text = "Preparing upgrade tiers..."
                }
            }

            if (progress == 100 && !hasNavigated) {
                binding.root.postDelayed({
                    if (isAdded && !hasNavigated) {
                        hideLoadingOverlay()
                        navigateToReco(selectedComponent)
                    }
                }, 300)
            }
        }

        viewModel.buildCompatibilityResult.observe(viewLifecycleOwner) { result ->
            if (result != null && !result.isCompatible) {
                hideLoadingOverlay()
                showIncompatibilityDialog()
            }
        }

        binding.btnAnalyze.setOnClickListener {
            val cpuText = binding.etCpu.text?.toString().orEmpty()
            val gpuText = binding.etGpu.text?.toString().orEmpty()
            val motherboardText = binding.etMotherboard.text?.toString().orEmpty()
            val ramText = binding.etRam.text?.toString().orEmpty()
            val storageText = binding.etStorage.text?.toString().orEmpty()
            val psuText = binding.etPsu.text?.toString().orEmpty()

            val fullBuild = CurrentBuild(
                cpu = cpuText,
                gpu = gpuText,
                motherboard = motherboardText,
                ram = ramText,
                storage = storageText,
                psu = psuText
            )

            val primaryComponent = when {
                selectedComponent.isNotBlank() -> selectedComponent
                gpuText.isNotBlank() -> gpuText
                cpuText.isNotBlank() -> cpuText
                motherboardText.isNotBlank() -> motherboardText
                ramText.isNotBlank() -> ramText
                storageText.isNotBlank() -> storageText
                psuText.isNotBlank() -> psuText
                else -> ""
            }

            if (primaryComponent.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Please enter at least one component to analyze.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            hasNavigated = false
            resetLoadingLabels()
            showLoadingOverlay(instant = true)

            viewModel.prepareBuildForUpgrade(
                build = fullBuild,
                primaryComponent = primaryComponent,
                hasExistingBuild = true
            )
        }
    }

    private fun resetLoadingLabels() {
        binding.tvLoadingTitle.text = "Verifying..."
        binding.tvLoadingSubtitle.text = "Validating CPU, Motherboard, and RAM..."
        binding.pbLoadingProgress.progress = 0
        binding.tvLoadingPercent.text = "0%"
    }

    private fun navigateToReco(selectedComponent: String) {
        if (hasNavigated) return
        hasNavigated = true

        val cpuText = binding.etCpu.text?.toString().orEmpty()
        val gpuText = binding.etGpu.text?.toString().orEmpty()
        val motherboardText = binding.etMotherboard.text?.toString().orEmpty()
        val ramText = binding.etRam.text?.toString().orEmpty()
        val storageText = binding.etStorage.text?.toString().orEmpty()
        val psuText = binding.etPsu.text?.toString().orEmpty()

        val primaryComponent = when {
            selectedComponent.isNotBlank() -> selectedComponent
            gpuText.isNotBlank() -> gpuText
            cpuText.isNotBlank() -> cpuText
            motherboardText.isNotBlank() -> motherboardText
            ramText.isNotBlank() -> ramText
            storageText.isNotBlank() -> storageText
            psuText.isNotBlank() -> psuText
            else -> ""
        }

        findNavController().navigate(
            R.id.action_upgradeCurrentFragment_to_upgradeRecoFragment,
            Bundle().apply {
                putString(UpgradeRecoFragment.ARG_SELECTED_COMPONENT, primaryComponent)
                putBoolean(UpgradeRecoFragment.ARG_HAS_EXISTING_BUILD, true)
                putString(UpgradeRecoFragment.ARG_CURRENT_BUILD_CPU, cpuText)
                putString(UpgradeRecoFragment.ARG_CURRENT_BUILD_GPU, gpuText)
                putString(UpgradeRecoFragment.ARG_CURRENT_BUILD_MOTHERBOARD, motherboardText)
                putString(UpgradeRecoFragment.ARG_CURRENT_BUILD_RAM, ramText)
                putString(UpgradeRecoFragment.ARG_CURRENT_BUILD_STORAGE, storageText)
                putString(UpgradeRecoFragment.ARG_CURRENT_BUILD_PSU, psuText)
            }
        )
    }

    private fun showIncompatibilityDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Incompatible Build Detected")
            .setMessage("Some parts in your build list are incompatible with each other. Please check your CPU, Motherboard, and RAM for compatibility.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        binding.loadingOverlay.clearAnimation()
        binding.loadingOverlay.visibility = View.VISIBLE
        if (!instant) {
            val anim = AlphaAnimation(0f, 1f)
            anim.duration = 150
            binding.loadingOverlay.startAnimation(anim)
        }
        binding.llMainContent.visibility = View.GONE
    }

    private fun hideLoadingOverlay() {
        if (!isAdded) return
        binding.llMainContent.visibility = View.VISIBLE
        val anim = AlphaAnimation(1f, 0f)
        anim.duration = 200
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                if (_binding != null) {
                    binding.loadingOverlay.visibility = View.GONE
                }
            }
        })
        binding.loadingOverlay.startAnimation(anim)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
    }
}
