package com.pcscan.pcscan.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentSearchResultBinding

class SearchResultFragment : Fragment() {

    private var _binding: FragmentSearchResultBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()
    private var currentSelectedComponent: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val searchQuery = arguments?.getString("searchQuery")
            ?: viewModel.searchQuery.value
            ?: "Results"
        val selectedFromArgs = arguments?.getString("selectedComponent")

        val currentVmQuery = viewModel.searchQuery.value.orEmpty()
        val state = viewModel.searchResultsScreenState.value
        val stateAlreadyValid =
            currentVmQuery == searchQuery &&
                (state is ScreenState.Success || state is ScreenState.Loading) &&
                viewModel.selectedComponent.value != null

        val shouldRestore = !stateAlreadyValid
        val shouldShowLoading = shouldRestore && viewModel.searchResultsReady.value != true

        if (shouldRestore) {
            if (shouldShowLoading) {
                showLoadingOverlay(instant = true)
            }
            viewModel.restoreSearch(searchQuery, selectedFromArgs)
        }

        viewModel.searchProgress.observe(viewLifecycleOwner) { progress ->
            binding.pbLoadingProgress.progress = progress.coerceIn(0, 100)
            binding.tvLoadingPercent.text = "${progress.coerceIn(0, 100)}%"

            if (progress >= 55) {
                if (binding.tvLoadingTitle.text != "Generating...") {
                    binding.tvLoadingTitle.text = "Generating..."
                    binding.tvLoadingSubtitle.text = "Preparing recommendations and build options..."
                }
            } else {
                if (binding.tvLoadingTitle.text != "Searching for exact component...") {
                    binding.tvLoadingTitle.text = "Searching for exact component..."
                    binding.tvLoadingSubtitle.text = "Identifying via AI and verifying against component database..."
                }
            }
        }

        viewModel.searchResultsReady.observe(viewLifecycleOwner) { ready ->
            val currentProgress = viewModel.searchProgress.value ?: 0
            if (ready && currentProgress >= 100 && binding.loadingOverlay.visibility == View.VISIBLE) {
                view.postDelayed({ hideLoadingOverlay() }, 300L)
            }
        }

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        viewModel.searchResultsScreenState.observe(viewLifecycleOwner) { state ->
            when (state) {
                ScreenState.Idle -> {
                    binding.tvTitle.text = "Results"
                    renderResults(emptyList(), null, null)
                    setActionButtonsEnabled(false)
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        hideLoadingOverlay()
                    }
                }

                ScreenState.Loading -> {
                    binding.tvTitle.text = "Loading results..."
                    binding.tvLoadingTitle.text = "Searching for exact component..."
                    binding.tvLoadingSubtitle.text = "Identifying via AI and verifying against component database..."
                    setActionButtonsEnabled(false)
                    if (binding.loadingOverlay.visibility != View.VISIBLE) {
                        showLoadingOverlay()
                    }
                }

                is ScreenState.Success -> {
                    val uiState = state.data
                    binding.tvTitle.text = uiState.title
                    currentSelectedComponent = uiState.selectedComponent
                    renderResults(uiState.results, uiState.selectedComponent, uiState.componentDetail)
                    setActionButtonsEnabled(uiState.selectedComponent != null)
                }

                is ScreenState.Error -> {
                    binding.tvTitle.text = "Results for: $searchQuery"
                    currentSelectedComponent = null
                    val notFoundMessage = state.message.ifBlank { "Exact component not found." }
                    renderNotFound(notFoundMessage)
                    setActionButtonsEnabled(false)
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        hideLoadingOverlay()
                    }
                    Toast.makeText(requireContext(), notFoundMessage, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewModel.invalidSearchEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { invalid ->
                if (binding.loadingOverlay.visibility == View.VISIBLE) {
                    hideLoadingOverlay()
                }
                currentSelectedComponent = null
                setActionButtonsEnabled(false)
                showInvalidSearchDialog(invalid.rejectionCategory, invalid.query, invalid.message)
            }
        }

        binding.btnBuildRecommendation.setOnClickListener {
            val component = currentSelectedComponent ?: selectedFromArgs ?: return@setOnClickListener
            viewModel.selectComponent(component)
            findNavController().navigate(
                R.id.action_searchResultFragment_to_buildRecoFragment,
                createSelectionBundle(searchQuery, component)
            )
        }

        binding.btnUpgradePath.setOnClickListener {
            val component = currentSelectedComponent ?: selectedFromArgs ?: return@setOnClickListener
            viewModel.selectComponent(component)
            findNavController().navigate(
                R.id.action_searchResultFragment_to_upgradePathFragment,
                createSelectionBundle(searchQuery, component)
            )
        }
    }

    private fun showInvalidSearchDialog(rejectionCategory: String, query: String, message: String) {
        val (iconRes, dialogTitle) = when (rejectionCategory) {
            "laptop_part" -> android.R.drawable.ic_dialog_info to "Laptop / Mobile Part Not Allowed"
            "not_pc_part" -> android.R.drawable.ic_dialog_alert to "Not a PC Component"
            "wrong_model" -> android.R.drawable.ic_dialog_alert to "Invalid or Wrong Model"
            "unknown_model" -> android.R.drawable.ic_dialog_alert to "Cannot Identify Component"
            else -> android.R.drawable.ic_dialog_alert to "Invalid Search"
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setIcon(iconRes)
            .setTitle(dialogTitle)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("PROCEED") { _: DialogInterface, _: Int ->
                // Clear state so we don't restore this invalid search
                viewModel.resetAllSearchAndBuildState()
                findNavController().popBackStack()
            }
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.btnBuildRecommendation.isEnabled = enabled
        binding.btnUpgradePath.isEnabled = enabled
        binding.btnBuildRecommendation.alpha = if (enabled) 1f else 0.4f
        binding.btnUpgradePath.alpha = if (enabled) 1f else 0.4f
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        binding.llMainContent.visibility = View.GONE
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
                    binding.llMainContent.visibility = View.VISIBLE
                }
            })
            .start()
    }

    private fun renderResults(samples: List<String>, selectedComponent: String?, componentDetail: ComponentDetail?) {
        binding.llContent.removeAllViews()

        val componentToShow = selectedComponent ?: samples.firstOrNull()
        val detail = componentDetail ?: componentToShow?.let { name ->
            val mockFallback = RecommendationMockData.getComponentDetails(name)
            val detectedCategory = when {
                name.contains("GPU", ignoreCase = true) -> "GPU"
                name.contains("CPU", ignoreCase = true) -> "CPU"
                name.contains("RAM", ignoreCase = true) -> "RAM"
                name.contains("Motherboard", ignoreCase = true) -> "MOBO"
                name.contains("Storage", ignoreCase = true) -> "STORAGE"
                name.contains("PSU", ignoreCase = true) -> "PSU"
                else -> mockFallback.category
            }
            ComponentDetail(
                name = mockFallback.name.ifBlank { name },
                category = detectedCategory,
                score = mockFallback.score.coerceAtLeast(85),
                specs = mockFallback.specs,
                aiConfidence = mockFallback.aiConfidence.coerceAtLeast(90),
                manufacturer = mockFallback.manufacturer
            )
        }

        if (componentToShow != null && detail != null) {
            val cardView = LayoutInflater.from(requireContext()).inflate(R.layout.item_search_result_detail, null)

            val tvComponentName = cardView.findViewById<TextView>(R.id.tvComponentName)
            val tvComponentSubtitle = cardView.findViewById<TextView>(R.id.tvComponentSubtitle)
            val tvComponentType = cardView.findViewById<TextView>(R.id.tvComponentType)
            val tvScore = cardView.findViewById<TextView>(R.id.tvScore)
            val progressBar = cardView.findViewById<android.widget.ProgressBar>(R.id.progressBar)
            val tvAiConfidence = cardView.findViewById<TextView>(R.id.tvAiConfidence)
            val tvSpec1 = cardView.findViewById<TextView>(R.id.tvSpec1)
            val tvSpec2 = cardView.findViewById<TextView>(R.id.tvSpec2)
            val tvSpec3 = cardView.findViewById<TextView>(R.id.tvSpec3)
            val tvSpec4 = cardView.findViewById<TextView>(R.id.tvSpec4)

            tvComponentName.text = detail.name
            tvComponentType.text = detail.category
            tvComponentSubtitle.text = "${detail.manufacturer} • ${detail.category}"
            tvScore.text = "${detail.score}/100"
            progressBar.progress = detail.score
            tvAiConfidence.text = "AI identified this component with ${detail.aiConfidence}% confidence. Compatibility is checked automatically before generating recommendations."

            val specs = detail.specs
            if (specs.isNotEmpty()) tvSpec1.text = specs[0]
            if (specs.size > 1) tvSpec2.text = specs[1]
            if (specs.size > 2) tvSpec3.text = specs[2]
            if (specs.size > 3) tvSpec4.text = specs[3]

            binding.llContent.addView(cardView)
        }
    }

    private fun renderNotFound(message: String) {
        binding.llContent.removeAllViews()

        val notFoundCard = LayoutInflater.from(requireContext()).inflate(R.layout.item_search_result_detail, null).apply {
            val nameTv = findViewById<TextView>(R.id.tvComponentName)
            val subTv = findViewById<TextView>(R.id.tvComponentSubtitle)
            val typeTv = findViewById<TextView>(R.id.tvComponentType)
            val scoreTv = findViewById<TextView>(R.id.tvScore)
            val progress = findViewById<android.widget.ProgressBar>(R.id.progressBar)
            val confTv = findViewById<TextView>(R.id.tvAiConfidence)
            listOf<TextView>(
                findViewById(R.id.tvSpec1),
                findViewById(R.id.tvSpec2),
                findViewById(R.id.tvSpec3),
                findViewById(R.id.tvSpec4)
            ).forEach { it.visibility = View.GONE }

            nameTv.text = message
            subTv.text = "No matching component in verified database"
            typeTv.text = "N/A"
            typeTv.setBackgroundResource(R.drawable.bg_card)
            scoreTv.text = "0/100"
            progress.progress = 0
            confTv.text = "Tip: Check spelling, include exact model suffix (e.g. 5600X not 5600G), or try mainstream component names."
        }

        binding.llContent.addView(notFoundCard)
    }

    private fun createSelectionBundle(searchQuery: String, selectedComponent: String?): Bundle {
        return Bundle().apply {
            putString("searchQuery", searchQuery)
            putString("selectedComponent", selectedComponent ?: searchQuery)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        currentSelectedComponent = null
    }
}
