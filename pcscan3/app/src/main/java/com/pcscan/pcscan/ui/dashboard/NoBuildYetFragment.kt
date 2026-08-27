package com.pcscan.pcscan.ui.dashboard

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentNoBuildYetBinding

class NoBuildYetFragment : Fragment() {

    private var _binding: FragmentNoBuildYetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoBuildYetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        val selectedComponent = arguments?.getString(ARG_SELECTED_COMPONENT).orEmpty()
        if (selectedComponent.isNotBlank()) {
            binding.tvBasedOn.text = "Based on: $selectedComponent"
        }

        val componentTier = detectComponentTier(selectedComponent)
        val categoryType = detectCategoryType(selectedComponent)
        val categoryLabel = categoryType.uppercase()

        binding.tvIntroTiers.text =
            "AI has analyzed your $categoryLabel and prepared 3 upgrade tiers:"

        binding.layoutEntryComponents.removeAllViews()
        binding.layoutMidComponents.removeAllViews()
        binding.layoutHighComponents.removeAllViews()

        val tierData = buildTierData(componentTier, selectedComponent, categoryType)

        tierData.entry?.let { entry ->
            binding.layoutEntryCard.visibility = View.VISIBLE
            binding.tvEntryTitle.text = entry.title
            binding.tvEntryDescription.text = entry.description
            entry.components.forEach { comp ->
                binding.layoutEntryComponents.addView(buildComponentRow(comp))
            }
        } ?: run { binding.layoutEntryCard.visibility = View.GONE }

        tierData.mid?.let { mid ->
            binding.layoutMidCard.visibility = View.VISIBLE
            binding.tvMidTitle.text = mid.title
            binding.tvMidDescription.text = mid.description
            mid.components.forEach { comp ->
                binding.layoutMidComponents.addView(buildComponentRow(comp))
            }
        } ?: run { binding.layoutMidCard.visibility = View.GONE }

        tierData.high?.let { high ->
            binding.layoutHighCard.visibility = View.VISIBLE
            binding.tvHighTitle.text = high.title
            binding.tvHighDescription.text = high.description
            high.components.forEach { comp ->
                binding.layoutHighComponents.addView(buildComponentRow(comp))
            }
        } ?: run { binding.layoutHighCard.visibility = View.GONE }

        val density = resources.displayMetrics.density
        val iconPaddingPx = (10 * density).toInt()

        binding.btnGenerateBuild.setIconResource(R.drawable.upload_build_reco_icon)
        binding.btnGenerateBuild.iconGravity =
            com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnGenerateBuild.iconPadding = iconPaddingPx
        binding.btnGenerateBuild.iconSize = (24 * density).toInt()

        binding.btnSaveUpgrade.setIconResource(R.drawable.saved_upgrade_save_icon)
        binding.btnSaveUpgrade.iconGravity =
            com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        binding.btnSaveUpgrade.iconPadding = iconPaddingPx
        binding.btnSaveUpgrade.iconSize = (24 * density).toInt()

        binding.btnGenerateBuild.setOnClickListener {
            findNavController().navigate(R.id.buildRecoFragment)
        }

        binding.btnSaveUpgrade.setOnClickListener {
            Toast.makeText(requireContext(), "Upgrade path saved", Toast.LENGTH_SHORT).show()
        }
    }

    private data class UpgradeComponent(
        val category: String,
        val name: String,
        val description: String,
        val compatibility: Int,
        val progressPercent: Float = compatibility / 100f
    )

    private data class TierPath(
        val title: String,
        val description: String,
        val components: List<UpgradeComponent>
    )

    private data class ThreeTiers(
        val entry: TierPath?,
        val mid: TierPath?,
        val high: TierPath?
    )

    private fun detectCategoryType(componentName: String): String {
        val name = componentName.lowercase()
        return when {
            name.contains("rtx") || name.contains("gtx") ||
                name.contains("rx ") || name.contains("radeon") ||
                name.contains("gpu") || name.contains("graphics") -> "gpu"
            else -> "cpu"
        }
    }

    private fun buildTierData(
        componentTier: ComponentTier,
        selectedComponent: String,
        categoryType: String
    ): ThreeTiers {
        val categoryLabel = categoryType.uppercase()

        val cpuUpgrades = mapOf(
            ComponentTier.LEGACY_BUDGET to Triple(
                UpgradeComponent(categoryLabel, "Ryzen 5 5600", "Great value 6-core for 1080p gaming and daily tasks.", 88),
                UpgradeComponent(categoryLabel, "Ryzen 7 5700X", "Strong 8-core upgrade for fast gaming and productivity.", 92),
                UpgradeComponent(categoryLabel, "Ryzen 7 7700", "Zen 4 8-core - large performance jump for all workloads.", 95)
            ),
            ComponentTier.MIDRANGE to Triple(
                UpgradeComponent(categoryLabel, "Ryzen 5 7600", "Modern Zen 4 6-core - efficient and future-proof.", 90),
                UpgradeComponent(categoryLabel, "Ryzen 7 7700", "Solid 8-core with big IPC gains over previous gen.", 94),
                UpgradeComponent(categoryLabel, "Ryzen 9 7900", "12-core powerhouse for gaming + heavy multitasking.", 97)
            ),
            ComponentTier.HIGH_END_CURRENT to Triple(
                UpgradeComponent(categoryLabel, "Ryzen 7 7700X", "Higher-clocked 8-core for competitive gaming.", 93),
                UpgradeComponent(categoryLabel, "Ryzen 9 7900X", "12-core flagship - smooth streaming and creation.", 96),
                UpgradeComponent(categoryLabel, "Ryzen 9 9950X", "Top-tier 16-core for extreme productivity and gaming.", 98)
            ),
            ComponentTier.ENTHUSIAST_FLAGSHIP to Triple(
                UpgradeComponent(categoryLabel, "Ryzen 9 7900X", "Still excellent - 12 cores for all workloads.", 94),
                UpgradeComponent(categoryLabel, "Ryzen 9 9900X", "New-gen 12-core with increased clock speeds.", 97),
                UpgradeComponent(categoryLabel, "Ryzen 9 9950X", "This is the highest tier - 16 cores for peak performance.", 99)
            )
        )

        val gpuUpgrades = mapOf(
            ComponentTier.LEGACY_BUDGET to Triple(
                UpgradeComponent(categoryLabel, "RTX 4060", "Entry-level RTX 40-series with DLSS 3 and great efficiency.", 87),
                UpgradeComponent(categoryLabel, "RTX 4060 Ti", "Solid 1080p/1440p card with frame generation.", 91),
                UpgradeComponent(categoryLabel, "RTX 4070 Super", "Excellent 1440p ray tracing and gaming performance.", 95)
            ),
            ComponentTier.MIDRANGE to Triple(
                UpgradeComponent(categoryLabel, "RTX 4070", "Great balanced 1440p card with efficient Ada Lovelace.", 90),
                UpgradeComponent(categoryLabel, "RTX 4070 Ti Super", "High-refresh 1440p and entry-level 4K gaming.", 94),
                UpgradeComponent(categoryLabel, "RTX 4080 Super", "Premium 4K gaming with top-tier ray tracing.", 97)
            ),
            ComponentTier.HIGH_END_CURRENT to Triple(
                UpgradeComponent(categoryLabel, "RTX 4080 Super", "Top-tier 4K rasterization and RT performance.", 94),
                UpgradeComponent(categoryLabel, "RTX 4090", "Unmatched 4K/8K gaming and content creation power.", 97),
                UpgradeComponent(categoryLabel, "RTX 5090", "Next-gen Blackwell flagship for future-proof 4K+ gaming.", 99)
            ),
            ComponentTier.ENTHUSIAST_FLAGSHIP to Triple(
                UpgradeComponent(categoryLabel, "RTX 4090", "Already a legendary flagship - still top-tier today.", 95),
                UpgradeComponent(categoryLabel, "RTX 5080", "Next-gen Blackwell with improved RT and efficiency.", 97),
                UpgradeComponent(categoryLabel, "RTX 5090", "Peak-tier GPU - no need to replace for 4K performance.", 99)
            )
        )

        val upgrades = if (categoryType == "gpu") gpuUpgrades else cpuUpgrades
        val (entryComp, midComp, highComp) = upgrades[componentTier] ?: upgrades[ComponentTier.MIDRANGE]!!

        return ThreeTiers(
            entry = TierPath(
                title = "Entry-Level Upgrade Path",
                description = "Budget-friendly replacement for a noticeable performance gain without breaking the bank.",
                components = listOf(entryComp)
            ),
            mid = TierPath(
                title = "Midrange Upgrade Path",
                description = "Sweet-spot upgrade that delivers excellent value, power, and longevity for most users.",
                components = listOf(midComp)
            ),
            high = TierPath(
                title = "High-End Upgrade Path",
                description = "No-compromise upgrade for maximum gaming, productivity, and future-proof performance.",
                components = listOf(highComp)
            )
        )
    }

    private fun detectComponentTier(componentName: String): ComponentTier {
        val name = componentName.lowercase()
        return when {
            name.contains("i3") || name.contains("ryzen 3") ||
                name.contains("gtx 1650") || name.contains("gtx 1660") ||
                name.contains("rx 5500") || name.contains("rx 560") -> ComponentTier.LEGACY_BUDGET

            (name.contains("i5") && !name.contains("i5-14") && !name.contains("i5-13")) ||
                (name.contains("ryzen 5") && !name.contains("ryzen 5 7") && !name.contains("ryzen 5 9")) ||
                name.contains("rtx 3060") || name.contains("rtx 4060") ||
                name.contains("rx 6600") || name.contains("rx 7600") -> ComponentTier.MIDRANGE

            name.contains("i7") || name.contains("ryzen 7") ||
                name.contains("rtx 3070") || name.contains("rtx 3080") || name.contains("rtx 4070") ||
                name.contains("rx 6700") || name.contains("rx 6800") || name.contains("rx 7700") ||
                name.contains("rx 7800") || name.contains("rx 7900") -> ComponentTier.HIGH_END_CURRENT

            name.contains("i9") || name.contains("ryzen 9") ||
                name.contains("rtx 4090") || name.contains("rtx 5080") || name.contains("rtx 5090") ||
                name.contains("rx 7950") || name.contains("rx 9070") -> ComponentTier.ENTHUSIAST_FLAGSHIP

            else -> ComponentTier.MIDRANGE
        }
    }

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
        val categoryIcon = when (comp.category.uppercase()) {
            "CPU" -> R.drawable.build_reco_cpu_icon
            "GPU" -> R.drawable.build_reco_gpu_icon
            "MOTHERBOARD" -> R.drawable.build_reco_mobo_icon
            "RAM" -> R.drawable.build_reco_ram_icon
            "STORAGE" -> R.drawable.build_reco_storage_icon
            "PSU" -> R.drawable.build_reco_psu_icon
            else -> R.drawable.build_reco_cpu_icon
        }

        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
            background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_card_selected)
            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
        }

        val topRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                (30 * density).toInt(),
                (30 * density).toInt()
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(AppCompatResources.getDrawable(requireContext(), categoryIcon))
            contentDescription = comp.category
        }

        val textContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = (12 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }

        val categoryLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = comp.category.uppercase()
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }

        val compName = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
            text = comp.name
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_white))
            textSize = 16f
        }

        val compDesc = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
            text = comp.description
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 12f
        }

        textContainer.addView(categoryLabel)
        textContainer.addView(compName)
        textContainer.addView(compDesc)

        val compatValue = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "${comp.compatibility}%"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        topRow.addView(badge)
        topRow.addView(textContainer)
        topRow.addView(compatValue)

        val progressContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }

        val progressLabelRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val progressLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = "Compatibility Score"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        }

        val progressPercent = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "${(comp.progressPercent * 100).toInt()}%"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 11f
        }

        progressLabelRow.addView(progressLabel)
        progressLabelRow.addView(progressPercent)

        val progressBar = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            ).apply {
                topMargin = (4 * density).toInt()
            }
            isIndeterminate = false
            max = 100
            progress = (comp.progressPercent * 100).toInt()
            progressDrawable?.let { drawable ->
                drawable.setTint(ContextCompat.getColor(requireContext(), categoryColor))
            }
        }

        progressContainer.addView(progressLabelRow)
        progressContainer.addView(progressBar)

        row.addView(topRow)
        row.addView(progressContainer)
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_SELECTED_COMPONENT = "selectedComponent"
    }
}

enum class ComponentTier {
    LEGACY_BUDGET,
    MIDRANGE,
    HIGH_END_CURRENT,
    ENTHUSIAST_FLAGSHIP
}
