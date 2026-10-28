package com.pcscan.pcscan.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentSearchResultBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchResultFragment : Fragment() {

    private var _binding: FragmentSearchResultBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()
    private var currentSelectedComponent: String? = null
    private var errorSnackbar: Snackbar? = null

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
        val selectedMatches = selectedFromArgs.isNullOrBlank() ||
            selectedFromArgs == viewModel.selectedComponent.value
        val stateAlreadyValid =
            currentVmQuery == searchQuery &&
                (state is ScreenState.Success || state is ScreenState.Loading || state is ScreenState.Error) &&
                selectedMatches

        val shouldRestore = !stateAlreadyValid
        val shouldShowLoading = shouldRestore && viewModel.searchResultsReady.value != true

        if (shouldRestore) {
            if (shouldShowLoading) {
                showLoadingOverlay(instant = true)
            }
            viewModel.restoreSearch(searchQuery, selectedFromArgs)
        } else if (state is ScreenState.Success) {
            binding.loadingOverlay.visibility = View.GONE
            binding.llMainContent.visibility = View.VISIBLE
            val uiState = state.data
            currentSelectedComponent = uiState.selectedComponent
            val detail = uiState.componentDetail
                ?: currentSelectedComponent?.let { RecommendationMockData.getComponentDetails(it) }
            renderSuccess(
                component = currentSelectedComponent,
                componentDetail = detail,
                searchQuery = searchQuery
            )
            setActionButtonsEnabled(currentSelectedComponent != null)
        }

        viewModel.searchProgress.observe(viewLifecycleOwner) { progress ->
            if (_binding == null) return@observe
            if (progress >= 55) {
                if (binding.tvLoadingTitle.text != "Almost done...") {
                    binding.tvLoadingTitle.text = "Almost done..."
                    binding.tvLoadingSubtitle.text = "Preparing compatibility checks..."
                }
            } else {
                if (binding.tvLoadingTitle.text != "Checking component...") {
                    binding.tvLoadingTitle.text = "Checking component..."
                    binding.tvLoadingSubtitle.text = "Verifying with AI if this is a valid desktop PC part"
                }
            }
        }

        viewModel.searchResultsReady.observe(viewLifecycleOwner) { ready ->
            if (_binding == null) return@observe
            val currentProgress = viewModel.searchProgress.value ?: 0
            if (ready && currentProgress >= 100 && binding.loadingOverlay.visibility == View.VISIBLE) {
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(300L)
                    if (_binding == null) return@launch
                    val currState = viewModel.searchResultsScreenState.value
                    if (currState is ScreenState.Success) {
                        hideLoadingOverlay()
                    }
                }
            }
        }

        binding.btnBack.setOnClickListener {
            safePopBack()
        }

        binding.btnBackToDashboard.setOnClickListener {
            safePopBackToDashboard()
        }

        viewModel.searchResultsScreenState.observe(viewLifecycleOwner) { state ->
            if (_binding == null) return@observe
            when (state) {
                ScreenState.Idle -> {
                    binding.tvTitle.text = "Results"
                    setActionButtonsEnabled(false)
                }

                ScreenState.Loading -> {
                    errorSnackbar?.dismiss()
                    binding.tvTitle.text = "Checking..."
                    binding.tvLoadingTitle.text = "Checking component..."
                    binding.tvLoadingSubtitle.text = "Verifying with AI if this is a valid desktop PC part"
                    setActionButtonsEnabled(false)
                    if (binding.loadingOverlay.visibility != View.VISIBLE) {
                        showLoadingOverlay()
                    }
                }

                is ScreenState.Success -> {
                    errorSnackbar?.dismiss()
                    val uiState = state.data
                    binding.tvTitle.text = "Search Result"
                    currentSelectedComponent = uiState.selectedComponent
                    val detail = uiState.componentDetail
                        ?: currentSelectedComponent?.let { RecommendationMockData.getComponentDetails(it) }
                    renderSuccess(
                        component = currentSelectedComponent,
                        componentDetail = detail,
                        searchQuery = searchQuery
                    )
                    setActionButtonsEnabled(currentSelectedComponent != null)
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        hideLoadingOverlay()
                    }
                }

                is ScreenState.Error -> {
                    val notFoundMessage = state.message.ifBlank { "Exact component not found." }
                    currentSelectedComponent = null
                    setActionButtonsEnabled(false)
                    binding.loadingOverlay.visibility = View.GONE
                    binding.llMainContent.visibility = View.VISIBLE
                    binding.tvTitle.text = "Search Result"
                    binding.tvStatus.text = "Search Unavailable"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.metric_warning))
                    binding.tvSelectedComponent.text = searchQuery
                    binding.tvComponentMeta.text = notFoundMessage
                    binding.tvComponentMeta.maxLines = 8
                    binding.tvConfidence.text = ""
                    errorSnackbar?.dismiss()
                    errorSnackbar = Snackbar.make(binding.root, "Search could not finish. Please retry.", Snackbar.LENGTH_INDEFINITE)
                        .setAction("Retry") { viewModel.submitSearch(searchQuery) }
                    errorSnackbar?.show()
                }
            }
        }

        viewModel.invalidSearchEvent.observe(viewLifecycleOwner) { event ->
            if (_binding == null) return@observe
            event.getContentIfNotHandled()?.let { invalid ->
                if (binding.loadingOverlay.visibility == View.VISIBLE) {
                    binding.loadingOverlay.visibility = View.GONE
                }
                currentSelectedComponent = null
                setActionButtonsEnabled(false)

                val prefix = when (invalid.rejectionCategory) {
                    "laptop_part" -> "LAPTOP PART DETECTED"
                    "not_pc_part" -> "NOT A PC COMPONENT"
                    "wrong_model" -> "WRONG MODEL"
                    "unknown_model" -> "UNKNOWN COMPONENT"
                    else -> "INVALID SEARCH"
                }
                val color = when (invalid.rejectionCategory) {
                    "laptop_part" -> ContextCompat.getColor(requireContext(), R.color.metric_warning)
                    "not_pc_part" -> ContextCompat.getColor(requireContext(), R.color.danger)
                    "wrong_model" -> ContextCompat.getColor(requireContext(), R.color.metric_warning)
                    "unknown_model" -> ContextCompat.getColor(requireContext(), R.color.metric_warning)
                    else -> ContextCompat.getColor(requireContext(), R.color.danger)
                }
                binding.llMainContent.visibility = View.VISIBLE
                binding.tvTitle.text = "Search Rejected"
                binding.tvStatus.text = prefix
                binding.tvStatus.setTextColor(color)
                binding.tvSelectedComponent.text = invalid.query.ifBlank { "Your search" }
                val details = buildString {
                    append("ISSUE: ").append(prefix).append('\n')
                    append("REASON: ").append(invalid.message).append('\n')
                    append("NEXT: Try an exact desktop PC DIY hardware model name.\n")
                    append("EXAMPLES: 'Ryzen 5 5600' (CPU), 'RTX 4060' (GPU), 'Corsair Vengeance DDR5 32GB' (RAM)")
                }
                binding.tvComponentMeta.text = details
                binding.tvComponentMeta.maxLines = 6
                binding.tvComponentMeta.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_body))
                binding.tvComponentMeta.alpha = 1f
                binding.tvConfidence.text = ""
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    "$prefix -- returning to Dashboard in 4 seconds...",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
                    .setDuration(4000)
                    .setBackgroundTint(color)
                    .setTextColor(android.graphics.Color.WHITE)
                    .show()

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(4000L)
                    if (_binding == null) return@launch
                    if (viewModel.searchResultsScreenState.value != ScreenState.Idle ||
                        viewModel.searchQuery.value != searchQuery
                    ) return@launch
                    viewModel.resetAllSearchAndBuildState()
                    safePopBackToDashboard()
                }
            }
        }

        binding.btnBuildRecommendation.setOnClickListener {
            val component = currentSelectedComponent ?: selectedFromArgs ?: return@setOnClickListener
            viewModel.selectComponent(component)
            try {
                findNavController().navigate(
                    R.id.buildRecoFragment,
                    Bundle().apply {
                        putString("searchQuery", searchQuery)
                        putString("selectedComponent", component)
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        binding.btnUpgradePath.setOnClickListener {
            val component = currentSelectedComponent ?: selectedFromArgs ?: return@setOnClickListener
            viewModel.selectComponent(component)
            try {
                findNavController().navigate(
                    R.id.noBuildYetFragment,
                    Bundle().apply {
                        putString("searchQuery", searchQuery)
                        putString(NoBuildYetFragment.ARG_SELECTED_COMPONENT, component)
                        putBoolean(NoBuildYetFragment.ARG_HAS_EXISTING_BUILD, false)
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showToastSafe(message: String, duration: Int = Toast.LENGTH_SHORT) {
        try {
            val ctx: Context? = context ?: activity?.applicationContext
            if (ctx != null && isAdded) {
                Toast.makeText(ctx, message, duration).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        if (_binding == null) return
        binding.btnBuildRecommendation.isEnabled = enabled
        binding.btnUpgradePath.isEnabled = enabled
        binding.btnBuildRecommendation.alpha = if (enabled) 1f else 0.4f
        binding.btnUpgradePath.alpha = if (enabled) 1f else 0.4f
    }

    private fun renderSuccess(component: String?, componentDetail: ComponentDetail?, searchQuery: String) {
        if (_binding == null) return
        val hasMatch = !component.isNullOrBlank()
        val resolvedDetail = componentDetail
        val finalName = if (hasMatch) component!! else searchQuery.ifBlank { "No supported component found" }

        binding.tvStatus.text = if (hasMatch) "Component Identified" else "No Match Found"
        binding.tvStatus.setTextColor(
            if (hasMatch) ContextCompat.getColor(requireContext(), R.color.success)
            else ContextCompat.getColor(requireContext(), R.color.danger)
        )
        binding.tvSelectedComponent.text = finalName

        val category = (resolvedDetail?.category?.ifBlank { null }
            ?: component?.let { RecommendationMockData.resolveCategory(it) }
            ?: "Component"
        ).replaceFirstChar { it.uppercase() }

        if (hasMatch) {
            val icon = when (category.uppercase()) {
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

        val brand = resolvedDetail?.manufacturer?.ifBlank { null }
            ?: component?.substringBefore(' ')?.replaceFirstChar { it.uppercase() }
            ?: "Detected"

        binding.tvComponentMeta.text = "$category | $brand"

        val confidencePct = resolvedDetail?.aiConfidence?.coerceIn(0, 100)
            ?: if (hasMatch) 85 else 0
        val text = "Confidence: $confidencePct%"
        val spannable = android.text.SpannableString(text)
        val startIdx = text.indexOf(confidencePct.toString())
        if (startIdx >= 0) {
            val endIdx = (startIdx + confidencePct.toString().length + 1).coerceAtMost(text.length)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.success)),
                startIdx,
                endIdx,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvConfidence.text = spannable
    }

    private fun safePopBack() {
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
            safePopBack()
        }
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        if (_binding == null) return
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
        if (_binding == null) return
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (_binding == null) return
                    binding.loadingOverlay.visibility = View.GONE
                    binding.llMainContent.visibility = View.VISIBLE
                }
            })
            .start()
    }

    override fun onDestroyView() {
        errorSnackbar?.dismiss()
        errorSnackbar = null
        super.onDestroyView()
        _binding = null
        currentSelectedComponent = null
    }
}
