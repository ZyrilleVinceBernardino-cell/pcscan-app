package com.pcscan.pcscan.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentSearchResultBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            if (_binding == null) return@observe
            binding.pbLoadingProgress.progress = progress.coerceIn(0, 100)
            binding.tvLoadingPercent.text = "${progress.coerceIn(0, 100)}%"

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
                    binding.tvTitle.text = "Checking..."
                    binding.tvLoadingTitle.text = "Checking component..."
                    binding.tvLoadingSubtitle.text = "Verifying with AI if this is a valid desktop PC part"
                    setActionButtonsEnabled(false)
                    if (binding.loadingOverlay.visibility != View.VISIBLE) {
                        showLoadingOverlay()
                    }
                }

                is ScreenState.Success -> {
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
                    setActionButtonsEnabled(false)
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        binding.loadingOverlay.visibility = View.GONE
                    }
                    showToastSafe("ERROR: $notFoundMessage. Try a different search or check spelling.", Toast.LENGTH_LONG)
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(500L)
                        if (_binding == null) return@launch
                        viewModel.resetAllSearchAndBuildState()
                        safePopBack()
                    }
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
                    "laptop_part" -> "LAPTOP PART DETECTED: "
                    "not_pc_part" -> "NOT A PC COMPONENT: "
                    "wrong_model" -> "WRONG MODEL: "
                    "unknown_model" -> "UNKNOWN COMPONENT: "
                    else -> "INVALID SEARCH: "
                }
                showToastSafe("$prefix${invalid.message}", Toast.LENGTH_LONG)

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(600L)
                    if (_binding == null) return@launch
                    viewModel.resetAllSearchAndBuildState()
                    safePopBack()
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
                    R.id.upgradePathFragment,
                    Bundle().apply {
                        putString("searchQuery", searchQuery)
                        putString("selectedComponent", component)
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
            if (hasMatch) android.graphics.Color.parseColor("#19D39A")
            else android.graphics.Color.parseColor("#FF6B6B")
        )
        binding.tvSelectedComponent.text = finalName

        val category = (resolvedDetail?.category?.ifBlank { null }
            ?: component?.let { RecommendationMockData.resolveCategory(it) }
            ?: "Component"
        ).replaceFirstChar { it.uppercase() }

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
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#19D39A")),
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
        super.onDestroyView()
        _binding = null
        currentSelectedComponent = null
    }
}
