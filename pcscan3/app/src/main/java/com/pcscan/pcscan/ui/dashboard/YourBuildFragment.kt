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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentYourBuildBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class YourBuildFragment : Fragment() {

    private var _binding: FragmentYourBuildBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecommendationViewModel by activityViewModels()

    private var currentBottleneckAnalysis: BottleneckAnalysis? = null
    private var currentBottleneckNote: String = ""

    companion object {
        const val ARG_SEED_COMPONENT = "seedComponent"
        const val ARG_BUDGET_TIER = "budgetTier"
        const val ARG_BUDGET_AMOUNT = "budgetAmount"
        const val ARG_BUDGET_LABEL = "budgetLabel"
        const val ARG_USAGE_LABEL = "usageLabel"
        const val ARG_VIEWING_SAVED_BUILD_ID = "viewingSavedBuildId"

        private const val TIER_ENTRY = "ENTRY"
        private const val TIER_MID = "MID"
        private const val TIER_HIGH = "HIGH"
        private const val TIER_FLAGSHIP = "FLAGSHIP"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentYourBuildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val seedComponent = arguments?.getString(ARG_SEED_COMPONENT).orEmpty()
        val budgetTier = arguments?.getString(ARG_BUDGET_TIER).orEmpty()
        val budgetAmount = arguments?.getLong(ARG_BUDGET_AMOUNT, 0L) ?: 0L
        val budgetLabel = arguments?.getString(ARG_BUDGET_LABEL).orEmpty()
        val usageLabel = arguments?.getString(ARG_USAGE_LABEL).orEmpty()
        val viewingSavedBuildId = arguments?.getString(ARG_VIEWING_SAVED_BUILD_ID).orEmpty()

        binding.btnBack.setOnClickListener { safePopBackToDashboard() }
        binding.btnBackToDashboard.setOnClickListener { safePopBackToDashboard() }
        binding.cardBottleneck.setOnClickListener { showBottleneckDialog() }

        // Offline viewing mode: hydrate UI directly from the saved range build, no regeneration.
        if (viewingSavedBuildId.isNotBlank()) {
            val existing = viewModel.savedBuilds.value?.firstOrNull { it.id == viewingSavedBuildId }
            if (existing != null) {
                hydrateSavedBuild(existing)
            } else {
                showLoadingOverlay(instant = true)
                viewModel.savedBuilds.observe(viewLifecycleOwner) { items ->
                    if (_binding == null) return@observe
                    val found = items.firstOrNull { it.id == viewingSavedBuildId }
                    if (found != null) {
                        hydrateSavedBuild(found)
                    } else if (items.isNotEmpty()) {
                        // Saved build no longer exists (e.g. deleted) - leave gracefully.
                        showToastSafe("Saved build not found")
                        findNavController().popBackStack()
                    }
                }
            }
            return
        }

        // Header + budget card
        binding.tvScreenTitle.text = "Your Build"
        binding.cardCompatibilityBanner.visibility = View.VISIBLE
        binding.tvTierLabel.text = tierDisplayLabel(budgetTier)
        binding.tvBudgetAmount.text = formatPeso(budgetAmount)
        binding.tvUsageAndCurrency.text = "$usageLabel \u00b7 PHP Budget"
        binding.tvCompatibilityMessage.text =
            "All components fit within your ${formatPeso(budgetAmount)} budget and are fully compatible."

        // Wire up ViewModel pipeline for the seed component at the matching tier
        val targetTier = mapBudgetTierToBuildTier(budgetTier)
        val hasSeed = seedComponent.isNotBlank()

        // Show loading overlay while the build is being generated.
        // Only skip loading if a build recommendation is already successfully loaded for this component.
        val currentState = viewModel.buildRecommendationScreenState.value
        val buildAlreadyLoaded = currentState is ScreenState.Success &&
            currentState.data.build != null
        val shouldShowLoading = hasSeed && !buildAlreadyLoaded
        if (shouldShowLoading) {
            showLoadingOverlay(instant = true)
            // Safety timeout: hide loading after 45s even if no state update arrives.
            viewLifecycleOwner.lifecycleScope.launch {
                delay(45_000L)
                if (_binding == null) return@launch
                if (binding.loadingOverlay.visibility == View.VISIBLE) {
                    hideLoadingOverlay()
                }
            }
        }

        if (hasSeed) {
            viewModel.ensureSelectedComponent(seedComponent)
            viewModel.selectBuildTier(targetTier)
            viewModel.preloadAllBuildsFor(seedComponent)
        }

        viewModel.buildRecommendationScreenState.observe(viewLifecycleOwner) { state ->
            if (_binding == null) return@observe
            when (state) {
                is ScreenState.Loading -> {
                    if (binding.loadingOverlay.visibility != View.VISIBLE && shouldShowLoading) {
                        showLoadingOverlay()
                    }
                }
                is ScreenState.Success -> {
                    state.data.build?.let(::showBuild)
                    // Only hide loading once the actual build data has been delivered.
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(300L)
                            if (_binding == null) return@launch
                            hideLoadingOverlay()
                        }
                    }
                }
                is ScreenState.Error -> {
                    if (binding.loadingOverlay.visibility == View.VISIBLE) {
                        hideLoadingOverlay()
                    }
                    showToastSafe(state.message)
                }
                else -> Unit
            }
        }

        // Hide the Save button once this exact build is stored. The id is resolved by the ViewModel
        // so it always matches what saveCurrentBuild persists (the tier can auto-resolve away from
        // a placeholder). Also re-check when the resolved tier changes.
        val refreshSaveButton: () -> Unit = {
            if (_binding != null) {
                val displayName = getBuildDisplayName(budgetAmount, budgetTier)
                binding.btnSaveBuild.visibility =
                    if (viewModel.isBuildSavedForDisplayName(displayName)) View.GONE else View.VISIBLE
            }
        }
        viewModel.savedBuilds.observe(viewLifecycleOwner) { refreshSaveButton() }
        viewModel.selectedBuildTier.observe(viewLifecycleOwner) { refreshSaveButton() }

        binding.btnSaveBuild.setOnClickListener {
            val displayName = getBuildDisplayName(budgetAmount, budgetTier)
            val saved = viewModel.saveCurrentBuild(
                displayNameOverride = displayName,
                isRangeBuild = true,
                budgetAmount = budgetAmount,
                budgetTierLabel = budgetTier,
                usageLabel = usageLabel
            )
            if (saved) {
                showToastSafe("Build saved")
                // Saved range build returns the user straight to the dashboard.
                safePopBackToDashboard()
            } else {
                showToastSafe("This build is already saved")
            }
        }

        binding.btnBackToDashboard.setOnClickListener { safePopBackToDashboard() }

        binding.cardBottleneck.setOnClickListener { showBottleneckDialog() }
    }

    private fun getBuildDisplayName(budgetAmount: Long, budgetTier: String): String {
        return if (budgetAmount > 0L) {
            "${formatPeso(budgetAmount)} Range Build"
        } else {
            "${tierDisplayLabel(budgetTier)} Range Build"
        }
    }

    private fun hydrateSavedBuild(item: SavedBuildItem) {
        if (_binding == null) return

        if (item.resolvesAsRangeBuild) {
            binding.tvScreenTitle.text = "Your Build"
            binding.cardCompatibilityBanner.visibility = View.VISIBLE
            val resolvedBudget = item.resolvedBudgetAmount
            binding.tvTierLabel.text = tierDisplayLabel(item.resolvedTierKey)
            binding.tvBudgetAmount.text = formatPeso(resolvedBudget)
            binding.tvUsageAndCurrency.text = if (item.usageLabel.isBlank()) {
                "\u00b7 PHP Budget"
            } else {
                "${item.usageLabel} \u00b7 PHP Budget"
            }
            binding.tvCompatibilityMessage.text =
                "All components fit within your ${formatPeso(resolvedBudget)} budget and are fully compatible."
        } else {
            // Saved build recommendation: reuse the same layout but present it as a build recommendation.
            binding.tvScreenTitle.text = "Build Recommendation"
            binding.cardCompatibilityBanner.visibility = View.GONE
            binding.tvTierLabel.text = tierDisplayLabel(tierKeyFor(item.tier))
            binding.tvBudgetAmount.text = item.component
            binding.tvUsageAndCurrency.text = buildRecommendationSubtitle(item.component)
        }

        // Saved builds are already persisted: no Save action needed.
        binding.btnSaveBuild.visibility = View.GONE

        showBuild(item.build)
        binding.loadingOverlay.visibility = View.GONE
        binding.svYourBuildContent.visibility = View.VISIBLE
    }

    private fun tierKeyFor(tier: BuildTier): String = when (tier) {
        BuildTier.ENTRY -> TIER_ENTRY
        BuildTier.MID -> TIER_MID
        BuildTier.HIGH -> TIER_HIGH
    }

    private fun buildRecommendationSubtitle(component: String): String {
        val category = RecommendationMockData.resolveCategory(component).uppercase()
        return when (category) {
            "CPU", "GPU", "RAM", "STORAGE", "PSU" -> "\u00b7 $category"
            "MOTHERBOARD" -> "\u00b7 MOTHERBOARD"
            else -> "\u00b7 COMPONENT"
        }
    }

    private fun showLoadingOverlay(instant: Boolean = false) {
        if (_binding == null) return
        binding.svYourBuildContent.visibility = View.GONE
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
                    binding.svYourBuildContent.visibility = View.VISIBLE
                }
            })
            .start()
    }

    private fun showBuild(build: BuildRecommendation) {
        if (_binding == null) return
        binding.tvCpuName.text = build.cpu
        binding.tvGpuName.text = build.gpu
        binding.tvMotherboardName.text = build.motherboard
        binding.tvRamName.text = build.ram
        binding.tvStorageName.text = build.storage
        binding.tvPsuName.text = build.psu

        // Short category descriptors as subtitles (component blurbs not in data model)
        binding.tvCpuDesc.text = "Processor"
        binding.tvGpuDesc.text = "Graphics Card"
        binding.tvMotherboardDesc.text = "Mainboard"
        binding.tvRamDesc.text = "System Memory"
        binding.tvStorageDesc.text = "Solid State Drive"
        binding.tvPsuDesc.text = "Power Supply"

        // Wattage
        val wattageDisplay = formatPsuToNumberWOnly(build.wattage)
        binding.tvWattageValue.text = wattageDisplay
        val psuWOnly = formatPsuToNumberWOnly(build.psu)
        binding.tvWattageSubtext.text = "PSU: $psuWOnly rec."

        // Bottleneck
        val bn = build.bottleneckAnalysis
        currentBottleneckAnalysis = bn
        currentBottleneckNote = build.bottleneck
        val displayPct: String = if (bn != null) {
            val p = bn.percentage.coerceIn(0, 20)
            "${p}%"
        } else {
            val pctOnly = Regex("""(\d{1,2})""").find(build.bottleneck)?.groupValues?.getOrNull(1)
            pctOnly?.let { "${it}%" } ?: build.bottleneck.ifBlank { "0%" }
        }
        val pctValue = Regex("""(\d{1,2})""").find(displayPct)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val bottleneckSub = when {
            pctValue <= 10 -> "Well balanced"
            pctValue <= 15 -> "Good balance"
            else -> "Acceptable"
        }
        binding.tvBottleneckValue.text = displayPct
        binding.tvBottleneckSubtext.text = bottleneckSub

        // Color bottleneck value: green <=15, amber otherwise
        val bnColor = if (pctValue <= 15) R.color.success else R.color.metric_warning
        binding.tvBottleneckValue.setTextColor(ContextCompat.getColor(requireContext(), bnColor))
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
                e2.printStackTrace()
                showToastSafe("Unable to show bottleneck analysis.")
            }
        }
    }

    private fun buildLocalFallbackBottleneckAnalysis(): BottleneckAnalysis {
        val cpu = binding.tvCpuName.text?.toString().orEmpty().ifBlank { "AMD Ryzen 5 7600" }
        val gpu = binding.tvGpuName.text?.toString().orEmpty().ifBlank { "NVIDIA GeForce RTX 4060" }
        val pctMatch = Regex("""(\d{1,2})""").find(binding.tvBottleneckValue.text?.toString().orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
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
            "At 1080p the CPU may become more noticeable at high FPS targets such as 144Hz or 240Hz. Turn-based / strategy titles are unlikely to show the difference, but competitive shooters often scale harder into CPU performance at this resolution."
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

    private fun formatPeso(amount: Long): String {
        if (amount <= 0L) return "PHP"
        val raw = amount.toString()
        val sb = StringBuilder(raw.length + raw.length / 3)
        var i = 0
        while (i < raw.length) {
            if (i > 0 && (raw.length - i) % 3 == 0) sb.append(',')
            sb.append(raw[i])
            i++
        }
        return "P${sb}"
    }

    private fun tierDisplayLabel(tier: String): String {
        return when (tier) {
            TIER_ENTRY -> "ENTRY-LEVEL BUILD"
            TIER_MID -> "MID-RANGE BUILD"
            TIER_HIGH -> "HIGH-END BUILD"
            TIER_FLAGSHIP -> "FLAGSHIP BUILD"
            else -> "YOUR BUILD"
        }
    }

    private fun mapBudgetTierToBuildTier(tier: String): BuildTier {
        return when (tier) {
            TIER_ENTRY -> BuildTier.ENTRY
            TIER_MID -> BuildTier.MID
            TIER_HIGH, TIER_FLAGSHIP -> BuildTier.HIGH
            else -> BuildTier.MID
        }
    }

    private fun formatPsuToNumberWOnly(raw: String): String {
        if (raw.isBlank()) return raw
        val matchResult = Regex("""(\d+\s*[wW])""").find(raw) ?: return raw.trim()
        return matchResult.groupValues[1].replace("\\s+".toRegex(), "").uppercase()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
