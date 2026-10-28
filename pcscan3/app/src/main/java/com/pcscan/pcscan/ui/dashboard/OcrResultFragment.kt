package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.navOptions
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentOcrResultBinding

class OcrResultFragment : Fragment() {

    private var _binding: FragmentOcrResultBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackHandling()

        val detectedText = arguments?.getString(ARG_DETECTED_TEXT).orEmpty()
        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        val searchQuery = arguments?.getString(ARG_SEARCH_QUERY).orEmpty()
        
        val isValid = selectedComponent.isNotBlank() && 
                      !selectedComponent.contains("None detected", ignoreCase = true) &&
                      !selectedComponent.contains("(None)", ignoreCase = true)

        if (isValid) {
            viewModel.restoreSearch(
                query = searchQuery.ifBlank { selectedComponent },
                selected = selectedComponent
            )
            viewModel.ensureSelectedComponent(selectedComponent)
            val category = RecommendationMockData.resolveCategory(selectedComponent)
                .replaceFirstChar { it.uppercase() }
            viewModel.addRecentActivity(
                type = RecentActivityType.OCR,
                name = selectedComponent,
                category = category
            )
            val ts = arguments?.getLong("scan_ts") ?: System.currentTimeMillis()
            val dedup = "ocr_${ts}_$selectedComponent"
            viewModel.incrementScanCount(dedup)
        }

        renderResultContent(detectedText, selectedComponent, isValid)
    }

    private fun renderResultContent(detectedText: String, selectedComponent: String, hasMatch: Boolean) {
        binding.btnBack.setOnClickListener {
            safePopBackToDashboard()
        }

        binding.tvDetectedText.text = if (detectedText.isNotBlank()) {
            detectedText
        } else {
            "Unable to read the text clearly. Try scanning again."
        }

        if (hasMatch) {
            binding.tvSelectedComponent.text = selectedComponent
            binding.tvCategory.text = "CATEGORY: ${resolveCategoryLabel(selectedComponent)}"
            binding.labelComponentMatch.text = "Confirm Detected Component (review before next step)"
            binding.labelComponentMatch.setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
            binding.labelComponentMatch.visibility = View.VISIBLE
            binding.tvSelectedComponent.visibility = View.VISIBLE
        } else {
            binding.tvSelectedComponent.text = "No matching component model found"
            binding.tvCategory.text = "CATEGORY: --"
            binding.labelComponentMatch.visibility = View.GONE
        }

        binding.btnUpgradePath.isEnabled = hasMatch
        binding.btnBuildRecommendation.isEnabled = hasMatch
        binding.btnUpgradePath.alpha = if (hasMatch) 1f else 0.5f
        binding.btnBuildRecommendation.alpha = if (hasMatch) 1f else 0.5f

        binding.btnUpgradePath.setOnClickListener {
            if (!hasMatch) return@setOnClickListener
            findNavController().navigate(
                R.id.noBuildYetFragment,
                Bundle().apply {
                    putString(NoBuildYetFragment.ARG_SELECTED_COMPONENT, selectedComponent)
                    putBoolean(NoBuildYetFragment.ARG_HAS_EXISTING_BUILD, false)
                }
            )
        }

        binding.btnBuildRecommendation.setOnClickListener {
            if (!hasMatch) return@setOnClickListener
            findNavController().navigate(
                R.id.buildRecoFragment,
                Bundle().apply {
                    putString(ARG_SELECTED_COMPONENT, selectedComponent)
                }
            )
        }

        binding.btnScanAgain.setOnClickListener {
            val navController = findNavController()
            navController.navigate(
                R.id.ocrScanningFragment,
                null,
                navOptions {
                    popUpTo(R.id.ocrScanningFragment) { inclusive = true }
                }
            )
        }

        if (!hasMatch) {
            Toast.makeText(
                requireContext(),
                "No component match found. Try scanning again.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveCategoryLabel(selectedComponent: String): String {
        val normalized = selectedComponent.lowercase()
        return when {
            normalized.contains("ryzen") || normalized.contains("core i") || normalized.contains("core(tm)") || normalized.contains("amd ryzen") || normalized.contains("intel core") || normalized.contains("processor") ->
                "CPU (Processor)"
            normalized.contains("rtx") || normalized.contains("rx ") || normalized.contains("geforce") || normalized.contains("nvidia") || normalized.contains("graphics card") || normalized.contains("arc") ->
                "GPU (Graphics Card)"
            normalized.contains("b550") || normalized.contains("x570") || normalized.contains("x670") || normalized.contains("b650") || normalized.contains("b760") || normalized.contains("z790") || normalized.contains("z890") || normalized.contains("h610") || normalized.contains("b850") || normalized.contains("x870") || normalized.contains("motherboard") || normalized.contains("msi mag") || normalized.contains("msi pro") || normalized.contains("asus tuf") || normalized.contains("asus prime") || normalized.contains("asus rog") || normalized.contains("gigabyte aorus") || normalized.contains("asrock") ->
                "Motherboard"
            normalized.contains("ddr4") || normalized.contains("ddr5") || normalized.contains("ddr3") || normalized.contains("ram") || normalized.contains("memory") || normalized.contains("vengeance") || normalized.contains("trident") || normalized.contains("gskill") || normalized.contains("g.skill") || normalized.contains("corsair") || normalized.contains("dominator") || normalized.contains("a-data") || normalized.contains("adata") || normalized.contains("kingston fury") || normalized.contains("lexar thor") ->
                "RAM (Memory)"
            normalized.contains("nvme") || normalized.contains("m.2") || normalized.contains("ssd") || normalized.contains("hdd") || normalized.contains("samsung 9") || normalized.contains("wd_black") || normalized.contains("wd black") || normalized.contains("crucial") || normalized.contains("seagate") || normalized.contains("storage") || normalized.contains("firecuda") || normalized.contains("sn850") || normalized.contains("sn770") || normalized.contains("980 pro") || normalized.contains("990 pro") || normalized.contains("850 evo") || normalized.contains("870 evo") || normalized.contains("sabrent") ->
                "Storage (SSD / HDD)"
            normalized.contains("80+") || normalized.contains("80plus") || normalized.contains("w ") || normalized.contains("watts") || normalized.contains("watt") || normalized.contains("gold") || normalized.contains("bronze") || normalized.contains("silver") || normalized.contains("titanium") || normalized.contains("platinum") || normalized.contains("seasonic") || normalized.contains("evga") || normalized.contains("corsair rm") || normalized.contains("be quiet!") || normalized.contains("bequiet") || normalized.contains("cooler master") || normalized.contains("psu") || normalized.contains("power supply") ->
                "PSU (Power Supply Unit)"
            else -> {
                val fromLibrary = RecommendationMockData.resolveCategory(selectedComponent)
                if (fromLibrary.isNotBlank()) {
                    when (fromLibrary.lowercase()) {
                        "cpu" -> "CPU (Processor)"
                        "gpu" -> "GPU (Graphics Card)"
                        "motherboard" -> "Motherboard"
                        "ram" -> "RAM (Memory)"
                        "storage" -> "Storage (SSD / HDD)"
                        "psu" -> "PSU (Power Supply Unit)"
                        else -> fromLibrary.replaceFirstChar { it.uppercase() }
                    }
                } else {
                    "PC Component"
                }
            }
        }
    }

    /** Keeps the system/gesture back button on the dashboard path instead of closing the app. */
    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    safePopBackToDashboard()
                }
            }
        )
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
        private const val ARG_DETECTED_TEXT = "detectedText"
        private const val ARG_SELECTED_COMPONENT = "selectedComponent"
        private const val ARG_SEARCH_QUERY = "searchQuery"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
