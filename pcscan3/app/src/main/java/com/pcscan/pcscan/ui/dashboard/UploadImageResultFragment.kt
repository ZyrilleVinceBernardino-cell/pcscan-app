package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
        }

        renderResult(detectedText = detectedText, selectedComponent = selectedComponent)
    }

    private fun renderResult(detectedText: String, selectedComponent: String) {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        val hasMatch = selectedComponent.isNotBlank()
        binding.tvStatus.text = if (hasMatch) "Component Detected" else "No Match Found"
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
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#19D39A")),
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
                R.id.upgradePathFragment,
                Bundle().apply {
                    putString(ARG_SELECTED_COMPONENT, selectedComponent)
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
                "No supported RAM or PSU match found. Try uploading a clearer image.",
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
