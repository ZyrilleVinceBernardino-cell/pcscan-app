package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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

        val detectedText = arguments?.getString(ARG_DETECTED_TEXT).orEmpty()
        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        val searchQuery = arguments?.getString(ARG_SEARCH_QUERY).orEmpty()

        if (selectedComponent.isNotBlank()) {
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
        }

        renderResultContent(detectedText, selectedComponent)
    }

    private fun renderResultContent(detectedText: String, selectedComponent: String) {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.tvDetectedText.text = if (detectedText.isNotBlank()) {
            detectedText
        } else {
            "Unable to read the text clearly. Try scanning again."
        }

        binding.tvSelectedComponent.text = if (selectedComponent.isNotBlank()) {
            selectedComponent
        } else {
            "No matching component found"
        }

        val hasMatch = selectedComponent.isNotBlank()
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
