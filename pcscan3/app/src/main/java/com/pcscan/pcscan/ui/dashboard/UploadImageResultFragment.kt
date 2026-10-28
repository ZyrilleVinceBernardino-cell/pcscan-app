package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUploadImageResultBinding

class UploadImageResultFragment : Fragment() {

    private var _binding: FragmentUploadImageResultBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUploadImageResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imageUri = arguments?.getString(ARG_SELECTED_IMAGE_URI).orEmpty()
        val detectedText = arguments?.getString(ARG_DETECTED_TEXT).orEmpty()
        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        val searchQuery = arguments?.getString(ARG_SEARCH_QUERY).orEmpty()
        if (imageUri.isBlank()) {
            Toast.makeText(requireContext(), "No uploaded image found.", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        if (selectedComponent.isNotBlank()) {
            viewModel.restoreSearch(
                query = searchQuery.ifBlank { selectedComponent },
                selected = selectedComponent
            )
            viewModel.ensureSelectedComponent(selectedComponent)
            val category = RecommendationMockData.resolveCategory(selectedComponent)
                .replaceFirstChar { it.uppercase() }
            viewModel.addRecentActivity(
                type = RecentActivityType.UPLOAD,
                name = selectedComponent,
                category = category
            )
            val ts = arguments?.getLong("scan_ts") ?: System.currentTimeMillis()
            val dedup = "upload_${ts}_$selectedComponent"
            viewModel.incrementScanCount(dedup)
        }

        renderResult(detectedText = detectedText, selectedComponent = selectedComponent)
    }

    private fun renderResult(detectedText: String, selectedComponent: String) {
        binding.btnBack.setOnClickListener {
            safePopBackToDashboard()
        }

        val hasMatch = selectedComponent.isNotBlank()
        if (hasMatch) {
            val cat = RecommendationMockData.resolveCategory(selectedComponent).uppercase()
            val icon = when (cat) {
                "CPU" -> R.drawable.build_reco_cpu_icon
                "GPU" -> R.drawable.build_reco_gpu_icon
                "MOTHERBOARD" -> R.drawable.build_reco_mobo_icon
                "RAM" -> R.drawable.build_reco_ram_icon
                "STORAGE" -> R.drawable.build_reco_storage_icon
                "PSU" -> R.drawable.build_reco_psu_icon
                else -> R.drawable.build_reco_cpu_icon
            }
            binding.ivResultIcon.setImageResource(icon)
        }
        binding.tvStatus.text = if (hasMatch) "Confirm Detected Component (review before next step)" else "No Match Found -- Re-upload a clearer photo"
        binding.tvSelectedComponent.text = if (hasMatch) {
            selectedComponent
        } else {
            detectedText.ifBlank { "No supported component found" }
        }
        binding.tvComponentMeta.text = buildMetaText(selectedComponent, detectedText, hasMatch)
        val confidence = estimateConfidence(selectedComponent, detectedText, hasMatch)
        val text = "Confidence: $confidence%"
        val spannable = android.text.SpannableString(text)
        val start = text.indexOf(confidence.toString())
        val end = start + confidence.toString().length + 1
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.success)),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvConfidence.text = spannable

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

        binding.btnReupload.setOnClickListener {
            findNavController().popBackStack(R.id.uploadImageFragment, false)
        }

        if (!hasMatch) {
            Toast.makeText(
                requireContext(),
                "No matching PC component found. Try uploading a clearer, well-lit photo of the box label.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildMetaText(selectedComponent: String, detectedText: String, hasMatch: Boolean): String {
        if (!hasMatch) {
            return "Try a clearer RAM or PSU photo"
        }

        val category = RecommendationMockData.resolveCategory(selectedComponent).replaceFirstChar { it.uppercase() }
        val brand = inferBrand(selectedComponent, detectedText)
        return "$category | $brand"
    }

    private fun inferBrand(selectedComponent: String, detectedText: String): String {
        val combined = "${selectedComponent.uppercase()} ${detectedText.uppercase()}"
        return when {
            "NVIDIA" in combined -> "NVIDIA"
            "AMD" in combined -> "AMD"
            "INTEL" in combined -> "Intel"
            "CORSAIR" in combined -> "Corsair"
            "KINGSTON" in combined -> "Kingston"
            "G.SKILL" in combined || "GSKILL" in combined -> "G.Skill"
            "TEAMGROUP" in combined || "T-FORCE" in combined -> "TeamGroup"
            "CRUCIAL" in combined -> "Crucial"
            "SEASONIC" in combined -> "Seasonic"
            "COOLER MASTER" in combined -> "Cooler Master"
            "MSI" in combined -> "MSI"
            "ASUS" in combined -> "ASUS"
            else -> selectedComponent.substringBefore(' ').ifBlank { "Detected" }
        }
    }

    private fun estimateConfidence(selectedComponent: String, detectedText: String, hasMatch: Boolean): Int {
        if (!hasMatch) return 0

        val componentTokens = selectedComponent
            .lowercase()
            .split(Regex("[^a-z0-9+.-]+"))
            .filter { it.isNotBlank() }
            .distinct()
        val detectedTokens = detectedText
            .lowercase()
            .split(Regex("[^a-z0-9+.-]+"))
            .filter { it.isNotBlank() }
            .distinct()

        if (componentTokens.isEmpty()) return 85

        val overlap = componentTokens.count { token ->
            detectedTokens.any { detected ->
                detected == token || detected.contains(token) || token.contains(detected)
            }
        }
        val ratio = overlap.toFloat() / componentTokens.size.toFloat()
        return (70 + (ratio * 30f)).toInt().coerceIn(70, 99)
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
        private const val ARG_SELECTED_IMAGE_URI = "selectedImageUri"
        private const val ARG_DETECTED_TEXT = "detectedText"
        private const val ARG_SELECTED_COMPONENT = "selectedComponent"
        private const val ARG_SEARCH_QUERY = "searchQuery"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
