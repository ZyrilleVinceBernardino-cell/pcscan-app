package com.pcscan.pcscan.ui.dashboard

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentUpgradeBuildPathBinding

class UpgradeBuildPathFragment : Fragment() {

    private var _binding: FragmentUpgradeBuildPathBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()

    private var selectedTier: BuildTier = BuildTier.ENTRY
    private var entryPath: UpgradePath? = null
    private var midPath: UpgradePath? = null
    private var highPath: UpgradePath? = null

    private var progressReached100 = false
    private var stateIsSuccess = false
    private var loadingHidden = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpgradeBuildPathBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            safePopBackToDashboard()
        }

        binding.btnGenerateBuild.setIconResource(R.drawable.upgrade_save_all_icon)
        binding.btnGenerateBuild.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnGenerateBuild.iconPadding = (10 * resources.displayMetrics.density).toInt()

        binding.btnSavePath.setIconResource(R.drawable.ic_arrow_back)
        binding.btnSavePath.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSavePath.iconPadding = (10 * resources.displayMetrics.density).toInt()

        val argComponent = arguments?.getString(ARG_SELECTED_COMPONENT)
        val argHasExistingBuild = arguments?.getBoolean(ARG_HAS_EXISTING_BUILD, false) ?: false

        val stateMatches =
            argComponent?.isNotBlank() == true &&
                viewModel.selectedComponent.value == argComponent &&
                viewModel.hasExistingBuild.value == argHasExistingBuild &&
                viewModel.upgradeRecoScreenState.value is ScreenState.Success

        progressReached100 = false
        stateIsSuccess = false
        loadingHidden = false

        if (!stateMatches) {
            resetLoadingLabels()
            showLoadingOverlay(instant = true)
            argComponent
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::ensureSelectedComponent)
            viewModel.setUpgradeMode(argHasExistingBuild)
            viewModel.loadUpgradeRecommendations()
        } else {
            progressReached100 = true
            stateIsSuccess = true
        }

        viewModel.upgradePrepareProgress.observe(viewLifecycleOwner) { progress ->
            if (!isAdded || _binding == null) return@observe
            if (progress >= 50 && progress < 100) {
                if (binding.tvLoadingTitle.text != "Generating...") {
                    binding.tvLoadingTitle.text = "Generating..."
                    binding.tvLoadingSubtitle.text = "Building upgrade recommendations..."
                }
            }

            if (progress >= 100) {
                progressReached100 = true
                tryHideLoadingOverlay()
            }
        }

        viewModel.upgradeRecoScreenState.observe(viewLifecycleOwner) { state ->
            when (state) {
                ScreenState.Idle -> Unit
                ScreenState.Loading -> binding.tvBasedOn.text = "Loading..."
                is ScreenState.Success -> {
                    val uiState = state.data
                    binding.tvBasedOn.text = uiState.basedOnText
                    entryPath = uiState.entryPath
                    midPath = uiState.midPath
                    highPath = uiState.highPath

                    binding.chipEntry.visibility = if (entryPath == null) View.GONE else View.VISIBLE
                    binding.chipMid.visibility = if (midPath == null) View.GONE else View.VISIBLE
                    binding.chipHigh.visibility = if (highPath == null) View.GONE else View.VISIBLE

                    val firstNonNullTier = when {
                        entryPath != null -> BuildTier.ENTRY
                        midPath != null -> BuildTier.MID
                        highPath != null -> BuildTier.HIGH
                        else -> selectedTier
                    }
                    if (selectedTier != firstNonNullTier) {
                        selectedTier = firstNonNullTier
                    }
                    if (getCurrentTierPath() == null) {
                        selectedTier = firstNonNullTier
                    }

                    updateChipsAppearance()
                    renderTier(selectedTier)
                    stateIsSuccess = true
                    tryHideLoadingOverlay()
                }

                is ScreenState.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    progressReached100 = true
                    stateIsSuccess = true
                    tryHideLoadingOverlay()
                }
            }
        }

        binding.chipEntry.setOnClickListener { selectTier(BuildTier.ENTRY) }
        binding.chipMid.setOnClickListener { selectTier(BuildTier.MID) }
        binding.chipHigh.setOnClickListener { selectTier(BuildTier.HIGH) }

        binding.btnGenerateBuild.setOnClickListener {
            val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
            findNavController().navigate(
                R.id.action_upgradeBuildPathFragment_to_buildRecoFragment,
                Bundle().apply {
                    putString(BuildRecoFragment.ARG_SELECTED_COMPONENT, selectedComponent)
                }
            )
        }

        binding.btnSavePath.setOnClickListener {
            val path = getCurrentTierPath()
            if (path == null) {
                Toast.makeText(requireContext(), "No tier loaded yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (path.title.startsWith("Your component is already")) {
                Toast.makeText(requireContext(), "Nothing to save - your component is already high-end top tier.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (path.title.startsWith("Your build is already")) {
                Toast.makeText(requireContext(), "Nothing to save - your build is already high-end top tier.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val component = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
                .ifBlank { viewModel. selectedComponent.value.orEmpty() }
            val hasExistingBuild = arguments?.getBoolean(ARG_HAS_EXISTING_BUILD, false) ?: false

            val savedCount = viewModel.saveUpgradePaths(
                component = component,
                hasExistingBuild = hasExistingBuild,
                paths = listOf(path),
                displayTitle = ""
            )
            Toast.makeText(
                requireContext(),
                if (savedCount == 1) "Saved" else "Already saved",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (stateMatches && !loadingHidden) {
            hideLoadingOverlay()
        }
    }

    private fun resetLoadingLabels() {
        binding.tvLoadingTitle.text = "Verifying..."
        binding.tvLoadingSubtitle.text = "Determining component type and tier..."
    }

    private fun tryHideLoadingOverlay() {
        if (!isAdded || _binding == null) return
        if (loadingHidden) return
        if (progressReached100 && stateIsSuccess) {
            binding.root.postDelayed({
                if (isAdded && _binding != null && !loadingHidden) {
                    hideLoadingOverlay()
                }
            }, 300L)
        }
    }

    private fun selectTier(tier: BuildTier) {
        if (selectedTier == tier) return
        selectedTier = tier
        updateChipsAppearance()
        renderTier(tier)
    }

    private fun updateChipsAppearance() {
        applyChipAppearance(binding.chipEntry, selectedTier == BuildTier.ENTRY)
        applyChipAppearance(binding.chipMid, selectedTier == BuildTier.MID)
        applyChipAppearance(binding.chipHigh, selectedTier == BuildTier.HIGH)
    }

    private fun applyChipAppearance(button: MaterialButton, isSelected: Boolean) {
        val textColor = if (isSelected) R.color.white else R.color.text_white
        val background = if (isSelected) R.drawable.bg_primary_button else R.drawable.bg_card
        button.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        button.background = ContextCompat.getDrawable(requireContext(), background)
        button.backgroundTintList = null
        button.cornerRadius = (18 * resources.displayMetrics.density).toInt()
        button.strokeWidth = 0
        button.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun getCurrentTierPath(): UpgradePath? = when (selectedTier) {
        BuildTier.ENTRY -> entryPath
        BuildTier.MID -> midPath
        BuildTier.HIGH -> highPath
    }

    private fun renderTier(tier: BuildTier) {
        val path = getCurrentTierPath() ?: return
        val container = binding.layoutComponents
        container.removeAllViews()
        path.components.forEach { comp ->
            container.addView(buildComponentRow(comp))
        }
    }

    private fun spToPx(sp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp,
        resources.displayMetrics
    )

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

        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
            setPadding(
                (16 * density).toInt(),
                (18 * density).toInt(),
                (16 * density).toInt(),
                (18 * density).toInt()
            )
        }

        val topRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        val categoryRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val categoryBadge = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (14 * density).toInt(),
                (6 * density).toInt(),
                (14 * density).toInt(),
                (6 * density).toInt()
            )
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card_selected)
            text = comp.category.uppercase()
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }

        val percentBadge = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (18 * density)
                setColor(ContextCompat.getColor(requireContext(), R.color.primary))
                alpha = 64
            }
            text = "${comp.compatibility}%"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_light))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        categoryRow.addView(categoryBadge)
        categoryRow.addView(spacer)
        categoryRow.addView(percentBadge)

        val compName = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
            text = comp.name
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
        }

        val compDesc = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            text = comp.description
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 15f
        }

        topRow.addView(categoryRow)
        topRow.addView(compName)
        topRow.addView(compDesc)

        val progressBar = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * density).toInt()
            ).apply {
                topMargin = (22 * density).toInt()
            }
            isIndeterminate = false
            max = 100
            progress = (comp.progressPercent * 100).toInt()
            progressDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (5 * density)
                val bgColor = ContextCompat.getColor(requireContext(), R.color.card)
                val progressLeft = ContextCompat.getColor(requireContext(), R.color.chip_cpu)
                val progressRight = ContextCompat.getColor(requireContext(), R.color.chip_gpu)
                val positions = floatArrayOf(
                    0f,
                    (comp.progressPercent).coerceAtMost(1f),
                    (comp.progressPercent + 0.01f).coerceAtMost(1f),
                    1f
                )
                setColors(
                    intArrayOf(progressLeft, progressRight, bgColor, bgColor),
                    positions
                )
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
        }

        row.addView(topRow)
        row.addView(progressBar)
        return row
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        if (!isAdded || _binding == null) return
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
        if (!isAdded || _binding == null) return
        if (loadingHidden) return
        loadingHidden = true
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
        const val ARG_HAS_EXISTING_BUILD = "hasExistingBuild"
    }
}
