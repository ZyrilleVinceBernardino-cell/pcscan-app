package com.pcscan.pcscan.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import android.content.res.ColorStateList
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentBuildRecoBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BuildRecoFragment : Fragment() {

    private var _binding: FragmentBuildRecoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecommendationViewModel by activityViewModels()
    private var currentBottleneckNote: String = ""
    private var currentBottleneckValue: String = ""
    private var currentDetectedTierRaw: String = ""
    private var currentBottleneckAnalysis: BottleneckAnalysis? = null

    // Loading overlay only finishes once the build is fully generated AND rendered.
    private var buildsReady = false
    private var buildRendered = false

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
            safePopBack()
        }

        binding.btnSaveBuild.setIconResource(R.drawable.saved_upgrade_save_icon)
        binding.btnSaveBuild.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSaveBuild.iconPadding = (10 * resources.displayMetrics.density).toInt()

        binding.cardBottleneck.setOnClickListener {
            showBottleneckDialog()
        }

        val argComponent = arguments?.getString(ARG_SELECTED_COMPONENT)
            ?.takeIf { it.isNotBlank() }

        argComponent?.let(viewModel::ensureSelectedComponent)

        // Only trust a stale "ready" flag when the cache actually belongs to THIS component.
        // Otherwise (e.g. arriving from the OCR/upload flow) the build must be generated again,
        // else the screen would render empty.
        val cacheReadyForArg = argComponent != null && viewModel.isBuildCacheReadyFor(argComponent)
        val shouldShowLoading = argComponent != null &&
            !(viewModel.allBuildsReady.value == true && cacheReadyForArg)

        if (shouldShowLoading) {
            showLoadingOverlay(instant = true)
            viewModel.preloadAllBuildsFor(argComponent.orEmpty())
        }

        viewModel.allBuildsReady.observe(viewLifecycleOwner) { ready ->
            if (_binding == null) return@observe
            buildsReady = ready
            maybeFinishLoading()
        }

        viewModel.buildRecommendationScreenState.observe(viewLifecycleOwner) { state ->
            if (_binding == null) return@observe
            when (state) {
                ScreenState.Idle -> clearBuild()
                ScreenState.Loading -> {
                    val currentTier = viewModel.selectedBuildTier.value ?: BuildTier.ENTRY
                    // A new build is being generated: do not let the overlay finish until it renders.
                    buildRendered = false
                    binding.tvBasedOnText.text = "Generating build..."
                    clearBuild(tierLabel = when (currentTier) {
                        BuildTier.ENTRY -> "ENTRY BUILD"
                        BuildTier.MID -> "MIDRANGE BUILD"
                        BuildTier.HIGH -> "HIGH-END BUILD"
                    })
                }
                is ScreenState.Success -> {
                    val uiState = state.data
                    binding.tvBasedOnText.text = uiState.basedOnText
                    val baseCompName = uiState.basedOnText.substringAfter("Based on: ", "")
                    val cat = if (baseCompName.isNotBlank())
                        RecommendationMockData.resolveCategory(baseCompName).uppercase()
                    else "CPU"
                    val recoIcon = when (cat) {
                        "CPU" -> R.drawable.build_reco_cpu_icon
                        "GPU" -> R.drawable.build_reco_gpu_icon
                        "MOTHERBOARD" -> R.drawable.build_reco_mobo_icon
                        "RAM" -> R.drawable.build_reco_ram_icon
                        "STORAGE" -> R.drawable.build_reco_storage_icon
                        "PSU" -> R.drawable.build_reco_psu_icon
                        else -> R.drawable.build_reco_cpu_icon
                    }
                    binding.icBasedOn.setImageResource(recoIcon)
                    if (uiState.detectedTierLabel.isNotBlank()) {
                        binding.tvDetectedTier.text = uiState.detectedTierLabel
                        binding.tvDetectedTier.visibility = View.VISIBLE
                    } else {
                        binding.tvDetectedTier.visibility = View.GONE
                    }
                    currentDetectedTierRaw = uiState.detectedComponentTier
                    val build = uiState.build
                    if (build != null) {
                        showBuild(build)
                    } else {
                        // Never leave the screen blank: tell the user instead of rendering nothing.
                        clearBuild()
                        showToastSafe("Build data unavailable. Please go back and try again.")
                    }
                    val tier = viewModel.selectedBuildTier.value ?: BuildTier.ENTRY
                    updateTabButtonStyles(tier)
                    updateSaveBuildButtonVisibility()
                    // The build is now generated and rendered into the (still hidden) content view.
                    buildRendered = true
                    maybeFinishLoading()
                }

                is ScreenState.Error -> {
                    clearBuild()
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        hideLoadingOverlay()
                    }
                    showToastSafe(state.message)
                }
            }
        }

        // === REVERTED ORIGINAL UI: 3 TIER TABS CLICK LISTENERS (USER SEPT 3 REQUEST) ===
        //   Tab 0 Entry / Tab 1 Midrange / Tab 2 High-End.
        //   Clicks call viewModel.selectBuildTier(BuildTier.X) -> ViewModel refreshes screen state.
        binding.btnTabEntry.setOnClickListener {
            if (isTierAvailable(BuildTier.ENTRY)) {
                viewModel.selectBuildTier(BuildTier.ENTRY)
            } else {
                showToastSafe("Entry build is NOT AVAILABLE for this component tier.")
            }
        }
        binding.btnTabMid.setOnClickListener {
            if (isTierAvailable(BuildTier.MID)) {
                viewModel.selectBuildTier(BuildTier.MID)
            } else {
                showToastSafe("Midrange build is NOT AVAILABLE for this component tier.")
            }
        }
        binding.btnTabHigh.setOnClickListener {
            if (isTierAvailable(BuildTier.HIGH)) {
                viewModel.selectBuildTier(BuildTier.HIGH)
            } else {
                showToastSafe("High-End build is NOT AVAILABLE for this component tier.")
            }
        }
        // Observe selectedBuildTier LiveData for style updates immediately (even before state loads):
        viewModel.selectedBuildTier.observe(viewLifecycleOwner) { tier ->
            if (_binding == null) return@observe
            updateTabButtonStyles(tier)
        }
        // Initial default tab style:
        updateTabButtonStyles(viewModel.selectedBuildTier.value ?: BuildTier.ENTRY)
        // === END REVERT 3 TIER TABS LOGIC ===

        binding.btnSaveBuild.setOnClickListener {
            val saved = viewModel.saveCurrentBuild()
            showToastSafe(if (saved) "Build saved" else "This build is already saved")
            // After saving, refresh the generated recommendation and return to the dashboard.
            safePopBackToDashboard()
        }

        viewModel.savedBuilds.observe(viewLifecycleOwner) {
            if (_binding == null) return@observe
            updateSaveBuildButtonVisibility()
        }

        binding.btnBackToDashboard.setOnClickListener {
            safePopBackToDashboard()
        }
    }

    /** Hides the loading overlay only when the build is fully generated and rendered. */
    private fun maybeFinishLoading() {
        if (_binding == null) return
        if (!buildsReady || !buildRendered) return
        if (binding.loadingOverlay.visibility != View.VISIBLE) return
        viewLifecycleOwner.lifecycleScope.launch {
            delay(350L)
            if (_binding == null) return@launch
            if (!buildsReady || !buildRendered) return@launch
            if (binding.loadingOverlay.visibility == View.VISIBLE) hideLoadingOverlay()
        }
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        if (_binding == null) return
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
        if (_binding == null) return
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(200L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (_binding == null) return
                    binding.loadingOverlay.visibility = View.GONE
                    binding.svBuildRecoContent.visibility = View.VISIBLE
                }
            })
            .start()
    }

    private fun formatPsuToNumberWOnly(raw: String): String {
        if (raw.isBlank()) return raw
        val matchResult = Regex("""(\d+\s*[wW])""").find(raw) ?: return raw.trim()
        return matchResult.groupValues[1].replace("\\s+".toRegex(), "").uppercase()
    }

    private fun showBuild(build: BuildRecommendation) {
        if (_binding == null) return
        val unavailableMarker = "NOT AVAILABLE for this component tier"
        val isUnavailable = build.name.equals(unavailableMarker, ignoreCase = true) ||
            build.cpu.equals(unavailableMarker, ignoreCase = true)
        if (isUnavailable) {
            binding.tvBuildLabel.text = build.label
            binding.tvBuildName.text = unavailableMarker
            binding.tvCpuValue.text = "--"
            binding.tvGpuValue.text = "--"
            binding.tvGpuInfoNote.text = ""
            binding.tvGpuInfoNote.visibility = View.GONE
            binding.tvMotherboardValue.text = "--"
            binding.tvRamValue.text = "--"
            binding.tvStorageValue.text = "--"
            binding.tvPsuValue.text = "--"
            binding.tvWattageValue.text = "--"
            binding.tvWattageSubtext.text = unavailableMarker
            currentBottleneckValue = ""
            currentBottleneckNote = unavailableMarker
            currentBottleneckAnalysis = build.bottleneckAnalysis ?: BottleneckAnalysis.EMPTY.copy(
                status = "UNAVAILABLE",
                detailedAnalysis = unavailableMarker,
                summary = unavailableMarker,
                reason = "Tier unavailable"
            )
            binding.tvBottleneckValue.text = "--"
            binding.tvBottleneckSubtext.text = unavailableMarker
            return
        }
        binding.tvBuildLabel.text = build.label
        binding.tvBuildName.text = build.name
        binding.tvCpuValue.text = build.cpu
        binding.tvGpuValue.text = build.gpu
        binding.tvGpuInfoNote.text = ""
        binding.tvGpuInfoNote.visibility = View.GONE
        binding.tvMotherboardValue.text = build.motherboard
        binding.tvRamValue.text = build.ram
        binding.tvStorageValue.text = build.storage
        binding.tvPsuValue.text = build.psu
        binding.tvWattageValue.text = formatPsuToNumberWOnly(build.wattage)
        val psuWOnly = formatPsuToNumberWOnly(build.psu)
        binding.tvWattageSubtext.text = "PSU: $psuWOnly rec."

        val bn = build.bottleneckAnalysis
        val displayPct: String = if (bn != null) {
            val p = bn.percentage.coerceIn(0, 20)
            "~${p}%"
        } else {
            val pctOnly = Regex("""(\d{1,2})""").find(build.bottleneck)?.groupValues?.getOrNull(1)
            pctOnly?.let { "${it}%" } ?: build.bottleneck.ifBlank { "0%" }
        }
        val statusText = bn?.status?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
            ?: when {
                bn != null && bn.percentage <= 10 -> "EXCELLENT"
                bn != null && bn.percentage <= 15 -> "GOOD"
                bn != null -> "ACCEPTABLE"
                else -> ""
            }
        val sub = buildString {
            append("Tap to analyze")
            if (statusText.isNotBlank()) {
                append("  \u00b7  Status: ")
                append(statusText)
            }
            if (build.gpuInfoNote.isNotBlank()) {
                append("\n")
                append(build.gpuInfoNote)
            }
        }
        currentBottleneckValue = build.bottleneck
        currentBottleneckNote = listOf(build.bottleneckNote, build.gpuInfoNote)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        currentBottleneckAnalysis = bn
        binding.tvBottleneckValue.text = displayPct
        binding.tvBottleneckSubtext.text = sub
    }

    /** REVERTED ORIGINAL UI: 3 TAB STYLES (USER SEPT 3).
     *  Selected tab = SOLID BLUE fill (@color/primary_light) + WHITE text.
     *  Unselected tabs = DARK CARD fill (@color/card_surface) + text_white text.
     *  Tiers that have no generated build are REMOVED from the row (View.GONE), never left visible as a
     *  disabled "--" tab, so the user can only switch to tiers that actually exist.
     *  FIX: use ViewCompat.setBackgroundTintList (valid AndroidX API) instead of View.setBackgroundTint() (non-existent View property causing compile errors). */
    private fun updateTabButtonStyles(selected: BuildTier) {
        val ctx = context ?: return
        val selectedBg = ContextCompat.getColor(ctx, R.color.primary_light)
        val unselectedBg = ContextCompat.getColor(ctx, R.color.card_surface)
        val selectedTextColor = ContextCompat.getColor(ctx, R.color.white)
        val unselectedTextColor = ContextCompat.getColor(ctx, R.color.text_white)

        fun styleButton(btn: MaterialButton, isSelected: Boolean) {
            ViewCompat.setBackgroundTintList(
                btn,
                ColorStateList.valueOf(if (isSelected) selectedBg else unselectedBg)
            )
            btn.setTextColor(if (isSelected) selectedTextColor else unselectedTextColor)
            btn.alpha = 1.0f
            btn.isClickable = true
            btn.isFocusable = true
        }

        val entryAvail = isTierAvailable(BuildTier.ENTRY)
        val midAvail = isTierAvailable(BuildTier.MID)
        val highAvail = isTierAvailable(BuildTier.HIGH)

        // Unavailable tiers are taken out of the row entirely instead of being greyed out.
        binding.btnTabEntry.visibility = if (entryAvail) View.VISIBLE else View.GONE
        binding.btnTabMid.visibility = if (midAvail) View.VISIBLE else View.GONE
        binding.btnTabHigh.visibility = if (highAvail) View.VISIBLE else View.GONE
        binding.tabRowTiers.weightSum =
            listOf(entryAvail, midAvail, highAvail).count { it }.coerceAtLeast(1).toFloat()

        styleButton(binding.btnTabEntry, selected == BuildTier.ENTRY && entryAvail)
        styleButton(binding.btnTabMid, selected == BuildTier.MID && midAvail)
        styleButton(binding.btnTabHigh, selected == BuildTier.HIGH && highAvail)

        // Zero the outer edges so the remaining tab(s) stay flush with the row.
        val gap = (4 * resources.displayMetrics.density).toInt()
        applyTabMargins(binding.btnTabEntry, start = 0, end = if (midAvail) gap else 0)
        applyTabMargins(binding.btnTabMid, start = if (entryAvail) gap else 0, end = if (highAvail) gap else 0)
        applyTabMargins(binding.btnTabHigh, start = if (midAvail) gap else 0, end = 0)
    }

    private fun applyTabMargins(btn: View, start: Int, end: Int) {
        val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.marginStart == start && lp.marginEnd == end) return
        lp.marginStart = start
        lp.marginEnd = end
        btn.layoutParams = lp
    }

    private fun isTierAvailable(tier: BuildTier): Boolean {
        // A tier whose build was never generated must not be reachable by the user at all: its tab is
        // hidden instead of rendering an empty "--" build. Fall back to the detected-tier rule only
        // while the build cache has not been populated yet (the content view is hidden at that point).
        if (viewModel.isTierBuildAvailable(tier)) return true
        if (viewModel.allBuildsReady.value == true) return false
        val tierNorm = currentDetectedTierRaw.trim().lowercase()
        return when (tierNorm) {
            "entry" -> true
            "mid", "midrange" -> tier != BuildTier.ENTRY
            "high", "high-end", "highend", "high end" -> tier == BuildTier.HIGH
            else -> true
        }
    }

    /** Hides the Save Build button once the build on the selected tier tab is already stored.
     *  Only the build currently displayed is ever the save target. */
    private fun updateSaveBuildButtonVisibility() {
        if (_binding == null) return
        binding.btnSaveBuild.visibility =
            if (viewModel.isCurrentBuildSaved()) View.GONE else View.VISIBLE
    }

    private fun clearBuild(tierLabel: String? = null) {
        if (_binding == null) return
        updateSaveBuildButtonVisibility()
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
        binding.tvDetectedTier.visibility = View.GONE
        currentDetectedTierRaw = ""
        currentBottleneckValue = ""
        currentBottleneckNote = ""
        currentBottleneckAnalysis = null
    }
    private fun showBottleneckDialog() {
        if (_binding == null || !isAdded) return
        val ctx: Context = context ?: activity?.applicationContext ?: return

        val unavailableMarker = "NOT AVAILABLE for this component tier"
        val isUnavailable = currentBottleneckNote.equals(unavailableMarker, ignoreCase = true) ||
            currentBottleneckAnalysis?.status.equals("UNAVAILABLE", ignoreCase = true)

        val analysis: BottleneckAnalysis = if (isUnavailable) {
            currentBottleneckAnalysis ?: BottleneckAnalysis.EMPTY.copy(
                status = "UNAVAILABLE",
                detailedAnalysis = unavailableMarker,
                summary = unavailableMarker,
                reason = "Tier unavailable for this component tier selection."
            )
        } else {
            val a = currentBottleneckAnalysis
            val blank = a == null || a.detailedAnalysis.isBlank() || a.detailedAnalysis.length <= 10
            if (blank) {
                val rebuild = buildLocalFallbackBottleneckAnalysis()
                currentBottleneckAnalysis = rebuild
                showToastSafe("Analysis missing, regenerated locally.")
                rebuild
            } else {
                a
            }
        }

        val pctSafe = analysis.percentage.coerceIn(0, 20)
        val titleText = "Bottleneck Analysis (~${pctSafe}% \u2014 Status: ${analysis.status})"
        val recommendationText = if (analysis.withinLimit && pctSafe <= 20) {
            "This configuration is within PcScan's 20% acceptable threshold. No adjustment required."
        } else {
            "The estimated bottleneck exceeds PcScan's maximum 20% rule. Adjust CPU or GPU pairing."
        }
        val bodyText = buildString {
            if (isUnavailable) {
                append("\u25b8 Summary:\n")
                appendLine(unavailableMarker)
                appendLine()
                append("\u25b8 Note:\n")
                append("The selected tier is not available for the detected component tier. Switch to a supported tier (e.g. Midrange or High-End) to see a full structured bottleneck analysis.")
            } else {
                append("\u25b8 Summary:\n")
                appendLine(analysis.summary.ifBlank { "Summary unavailable." })
                appendLine()
                append("\u25b8 Primary Limitation:\n")
                appendLine(analysis.limitingComponent.ifBlank { "Balanced" })
                appendLine()
                append("\u25b8 Why:\n")
                appendLine(analysis.reason.ifBlank { "Relative performance between the current CPU and GPU tiers determines the balance." })
                appendLine()
                append("\u25b8 Detailed Breakdown:\n")
                appendLine(analysis.detailedAnalysis.ifBlank { "Estimated bottleneck; actual result varies by workload." })
                appendLine()
                append("\u25b8 Gaming 1080p:\n")
                appendLine(analysis.gaming1080p.ifBlank { "At 1080p, high FPS competitive titles can shift the limitation toward the CPU." })
                appendLine()
                append("\u25b8 Gaming 1440p:\n")
                appendLine(analysis.gaming1440p.ifBlank { "At 1440p, the workload is typically the balanced sweet spot between CPU and GPU." })
                appendLine()
                append("\u25b8 Gaming 4K:\n")
                appendLine(analysis.gaming4k.ifBlank { "At 4K, the GPU is overwhelmingly the limiting factor in most modern AAA titles." })
                appendLine()
                append("\u25b8 Recommendation:\n")
                append(recommendationText)
            }
        }.trim()

        try {
            val wrappedCtx = com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert
            MaterialAlertDialogBuilder(ctx, wrappedCtx)
                .setTitle(titleText)
                .setMessage(bodyText)
                .setPositiveButton("Close", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(titleText)
                    .setMessage(bodyText)
                    .setPositiveButton("Close", null)
                    .setCancelable(true)
                    .show()
            } catch (e2: Exception) {
                showToastSafe("Unable to open bottleneck dialog right now.")
            }
        }
    }

    private fun buildLocalFallbackBottleneckAnalysis(): BottleneckAnalysis {
        val cpu = binding.tvCpuValue.text?.toString().orEmpty().ifBlank {
            val b = currentBottleneckValue
            Regex("""(\d{1,2})""").find(b)?.groupValues?.getOrNull(1).orEmpty()
            "AMD Ryzen 5 7600"
        }
        val gpu = binding.tvGpuValue.text?.toString().orEmpty().ifBlank { "NVIDIA GeForce RTX 4060" }
        val pctMatch = Regex("""(\d{1,2})""").find(currentBottleneckValue)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fallbackPct = (pctMatch ?: 12).coerceIn(0, 20)
        val limitingRaw = when {
            currentBottleneckNote.contains("cpu", ignoreCase = true) -> "CPU"
            currentBottleneckNote.contains("gpu", ignoreCase = true) -> "GPU"
            else -> "Balanced"
        }
        val status = when {
            fallbackPct <= 10 -> "EXCELLENT"
            fallbackPct <= 15 -> "GOOD"
            else -> "ACCEPTABLE"
        }
        val limiting = when {
            limitingRaw.contains("cpu", ignoreCase = true) -> "CPU"
            limitingRaw.contains("gpu", ignoreCase = true) -> "GPU"
            else -> "Balanced"
        }
        val summary = "Estimated bottleneck: approximately ${fallbackPct}%. (Status: $status.) The $cpu and $gpu are reasonably balanced. Neither the CPU nor the GPU is expected to significantly restrict the other in typical gaming workloads."
        val prefix = if (currentBottleneckNote.isBlank()) "" else currentBottleneckNote.trim() + " "
        val detailed = buildString {
            append(prefix)
            append("This estimate reflects the typical performance difference between the two chips across a representative suite of modern titles. ")
            append("If the CPU has fewer cores, lower IPC, or smaller L3 cache than a flagship class, it can limit high-end GPU throughput at low resolutions. ")
            append("If the GPU has fewer compute units or lower VRAM capacity, it becomes the limiting factor as resolution and texture detail increase. ")
            append("Actual results vary depending on five factors: Game, Resolution, Graphics settings, FPS target, and Workload. ")
            append("Estimated bottleneck; actual result varies by workload.")
        }
        val g1080 = if (limiting == "CPU") {
            "At 1080p the Ryzen/i5-class CPU may become more noticeable at high FPS targets such as 144Hz or 240Hz. Turn-based / strategy titles are unlikely to show the difference, but competitive shooters often scale harder into CPU performance at this resolution."
        } else {
            "At 1080p this pairing is typically GPU-bound, though fast-paced competitive titles with uncapped frame rates can still stress the CPU enough to keep the GPU waiting. For 60 FPS casual gameplay the experience is generally smooth."
        }
        val g1440 = "At 1440p this pairing usually lands in the 1440p sweet spot where load is distributed between CPU and GPU. Bottleneck becomes rare in most AAA titles; CPU-limited scenarios occur mainly in simulation-heavy games with many units or heavy draw-call overhead."
        val g4k = "At 4K rendering the GPU is almost always the limiting component regardless of the CPU for most AAA titles. Unless running extremely high-FPS simulation or strategy workloads, the CPU will rarely be the bottleneck at 4K resolution."
        val reason = when (limiting) {
            "CPU" -> "The CPU is the limitation because its per-thread performance, core count, or cache size are not high enough to keep up with the GPU at low resolutions when the GPU is lightly loaded."
            "GPU" -> "The GPU is the limitation because its raw CUDA/shader count or onboard VRAM capacity cannot push the pixel throughput needed at the target resolution before the CPU runs out of work to hand it."
            else -> "Neither component substantially limits the other in this pairing; their relative performance tiers are well matched for balanced 1440p and typical 4K gaming workloads."
        }
        return BottleneckAnalysis(
            percentage = fallbackPct,
            status = status,
            limitingComponent = limiting,
            summary = summary,
            detailedAnalysis = detailed.trim(),
            gaming1080p = g1080,
            gaming1440p = g1440,
            gaming4k = g4k,
            reason = reason,
            withinLimit = fallbackPct <= 20
        )
    }

    private fun showToastSafe(message: String, duration: Int = android.widget.Toast.LENGTH_SHORT) {
        try {
            val ctx: Context? = context ?: activity?.applicationContext
            if (ctx != null && isAdded) {
                android.widget.Toast.makeText(ctx, message, duration).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Normal back: return to the previous result screen WITHOUT resetting the generated state. */
    private fun safePopBack() {
        try {
            if (_binding == null || !isAdded) return
            val navController = findNavController()
            if (!navController.popBackStack()) {
                navController.navigate(R.id.dashboardFragment)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToastSafe("Unable to go back right now.")
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
            showToastSafe("Unable to go back to dashboard right now.")
        }
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
