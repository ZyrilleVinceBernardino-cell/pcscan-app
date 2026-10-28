package com.pcscan.pcscan.ui.saved

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentSavedUpgradeBinding
import com.pcscan.pcscan.ui.dashboard.NoBuildYetFragment
import com.pcscan.pcscan.ui.dashboard.RecommendationViewModel
import com.pcscan.pcscan.ui.dashboard.SavedUpgradeItem
import com.pcscan.pcscan.ui.dashboard.UpgradeComponent
import com.pcscan.pcscan.ui.dashboard.UpgradePath
import com.pcscan.pcscan.ui.dashboard.UpgradeRecoFragment

class SavedUpgradeFragment : Fragment() {

    private var _binding: FragmentSavedUpgradeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedUpgradeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tabBuildRecommendations.setOnClickListener {
            findNavController().navigate(R.id.action_savedUpgradeFragment_to_savedBuildFragment)
        }
        binding.tabUpgradePaths.setOnClickListener {
        }

        binding.navHome.setOnClickListener {
            val navController = findNavController()
            val returnedToDashboard = navController.popBackStack(R.id.dashboardFragment, false)
            if (!returnedToDashboard) {
                navController.navigate(R.id.dashboardFragment)
            }
        }
        binding.navSaved.setOnClickListener {
        }
        binding.navProfile.setOnClickListener {
            navigateToTopLevelDestination(R.id.profileFragment)
        }

        viewModel.savedUpgrades.observe(viewLifecycleOwner) { savedUpgrades ->
            renderSavedUpgrades(savedUpgrades)
        }
    }

    private fun renderSavedUpgrades(savedUpgrades: List<SavedUpgradeItem>) {
        binding.upgradesListContainer.removeAllViews()

        if (savedUpgrades.isEmpty()) {
            binding.upgradesListContainer.addView(
                createTextView(
                    "No saved upgrades yet. Save one from the Upgrade Path screen first.",
                    16f,
                    R.color.text_white,
                    false
                )
            )
        } else {
            savedUpgrades.forEachIndexed { index, item ->
                binding.upgradesListContainer.addView(createUpgradeCard(item))
                if (index != savedUpgrades.lastIndex) {
                    binding.upgradesListContainer.addView(createSpacer(16))
                }
            }
        }
    }

    private fun classifyPathTierLabel(path: UpgradePath): String {
        val t = (path.tier.ifBlank { path.title }).lowercase()
        return when {
            t.contains("entry") -> "Entry"
            t.contains("midrange") || t.contains("mid") -> "Midrange"
            t.contains("high") -> "High-End"
            else -> "Upgrade"
        }
    }

    private fun buildSubtitleForSavedItem(item: SavedUpgradeItem): String {
        val paths = if (item.paths.isNotEmpty()) item.paths else listOf(item.path)
        val tierCount = item.tierCount.takeIf { it > 0 } ?: paths.size
        val tierLabels = paths.map { classifyPathTierLabel(it) }.distinct()
        val tierPart = when {
            tierLabels.isEmpty() -> "Upgrade"
            tierLabels.size == 1 -> tierLabels[0]
            else -> tierLabels.joinToString(" + ")
        }
        val compCount = item.totalComponentCount.takeIf { it > 0 }
            ?: paths.sumOf { it.components.size }
        val compPart = if (compCount <= 1) "1 component" else "$compCount components"
        val tierCountLabel = if (tierCount <= 1) "1 tier" else "$tierCount tiers"
        return "$tierPart | $tierCountLabel | $compPart"
    }

    private fun serializeViewingComponents(comps: List<UpgradeComponent>): String {
        return comps.joinToString("||") { c ->
            val cat = c.category.ifBlank { "COMPONENT" }
            val name = c.name.replace("||", "").replace("|~|", "")
            val desc = c.description.replace("||", "").replace("|~|", "")
            listOf(cat, name, desc, c.compatibility.toString(), c.progressPercent.toString())
                .joinToString("|~|")
        }
    }

    private fun buildViewingBundle(item: SavedUpgradeItem): Bundle {
        val rawPaths = if (item.paths.isNotEmpty()) item.paths else listOf(item.path)
        val remaining = rawPaths.toMutableList()
        fun consume(predicate: (UpgradePath) -> Boolean): UpgradePath? {
            val idx = remaining.indexOfFirst(predicate)
            if (idx == -1) return null
            return remaining.removeAt(idx)
        }
        fun consumeAny(): UpgradePath? = if (remaining.isNotEmpty()) remaining.removeAt(0) else null
        val entry = consume { p ->
            val l = classifyPathTierLabel(p).lowercase()
            l == "entry" || p.title.contains("entry", true)
        } ?: consumeAny().takeIf { rawPaths.size >= 3 }
        val mid = consume { p ->
            val l = classifyPathTierLabel(p).lowercase()
            l == "midrange" || p.title.contains("mid", true)
        } ?: consumeAny().takeIf { rawPaths.size >= 2 }
        val high = consume { p ->
            val l = classifyPathTierLabel(p).lowercase()
            l == "high-end" || p.title.contains("high", true)
        } ?: consumeAny()
        val args = Bundle()
        val targetDestinationId = if (item.hasExistingBuild) R.id.upgradeRecoFragment else R.id.noBuildYetFragment
        if (item.hasExistingBuild) {
            args.putString(UpgradeRecoFragment.ARG_SELECTED_COMPONENT, item.component)
            args.putBoolean(UpgradeRecoFragment.ARG_IS_VIEWING_SAVED, true)
            args.putBoolean(UpgradeRecoFragment.ARG_HAS_EXISTING_BUILD, true)
            args.putString(UpgradeRecoFragment.ARG_VIEWING_BASED_ON_TEXT, item.basedOnText.ifBlank { "Based on: ${item.component}" })
            args.putString(UpgradeRecoFragment.ARG_VIEWING_CURRENT_BUILD_TIER_LABEL, item.currentBuildTierLabel)
            entry?.let { p ->
                args.putString(UpgradeRecoFragment.ARG_VIEWING_ENTRY_PATH_TITLE, p.title)
                args.putString(UpgradeRecoFragment.ARG_VIEWING_ENTRY_PATH_DESCRIPTION, p.description)
                args.putString(UpgradeRecoFragment.ARG_VIEWING_ENTRY_PATH_COMPS, serializeViewingComponents(p.components))
            }
            mid?.let { p ->
                args.putString(UpgradeRecoFragment.ARG_VIEWING_MID_PATH_TITLE, p.title)
                args.putString(UpgradeRecoFragment.ARG_VIEWING_MID_PATH_DESCRIPTION, p.description)
                args.putString(UpgradeRecoFragment.ARG_VIEWING_MID_PATH_COMPS, serializeViewingComponents(p.components))
            }
            high?.let { p ->
                args.putString(UpgradeRecoFragment.ARG_VIEWING_HIGH_PATH_TITLE, p.title)
                args.putString(UpgradeRecoFragment.ARG_VIEWING_HIGH_PATH_DESCRIPTION, p.description)
                args.putString(UpgradeRecoFragment.ARG_VIEWING_HIGH_PATH_COMPS, serializeViewingComponents(p.components))
            }
        } else {
            args.putString(NoBuildYetFragment.ARG_SELECTED_COMPONENT, item.component)
            args.putBoolean(NoBuildYetFragment.ARG_IS_VIEWING_SAVED, true)
            entry?.let { p ->
                args.putString(NoBuildYetFragment.ARG_VIEWING_ENTRY_PATH_TITLE, p.title)
                args.putString(NoBuildYetFragment.ARG_VIEWING_ENTRY_PATH_DESCRIPTION, p.description)
                args.putString(NoBuildYetFragment.ARG_VIEWING_ENTRY_PATH_COMPS, serializeViewingComponents(p.components))
            }
            mid?.let { p ->
                args.putString(NoBuildYetFragment.ARG_VIEWING_MID_PATH_TITLE, p.title)
                args.putString(NoBuildYetFragment.ARG_VIEWING_MID_PATH_DESCRIPTION, p.description)
                args.putString(NoBuildYetFragment.ARG_VIEWING_MID_PATH_COMPS, serializeViewingComponents(p.components))
            }
            high?.let { p ->
                args.putString(NoBuildYetFragment.ARG_VIEWING_HIGH_PATH_TITLE, p.title)
                args.putString(NoBuildYetFragment.ARG_VIEWING_HIGH_PATH_DESCRIPTION, p.description)
                args.putString(NoBuildYetFragment.ARG_VIEWING_HIGH_PATH_COMPS, serializeViewingComponents(p.components))
            }
        }
        return args.apply { putInt(VIEWING_DESTINATION_ID_KEY, targetDestinationId) }
    }

    companion object {
        private const val VIEWING_DESTINATION_ID_KEY = "viewing_destination_id"
    }

    private fun createUpgradeCard(item: SavedUpgradeItem): View {
        val density = resources.displayMetrics.density
        val spacing = (16 * density).toInt()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundResource(R.drawable.bg_card)

            val headerLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val componentLabel = item.component.ifBlank {
                item.displayTitle.ifBlank { item.path.title.ifBlank { "Component" } }
            }
            val displayTitle = "Upgrade for $componentLabel"
            headerLayout.addView(
                createTextView(displayTitle, 18f, R.color.text_white, true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
            )

            val upgradeBadge = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setImageResource(R.drawable.saved_upgrade_label)
                contentDescription = "Upgrade Badge"
            }
            headerLayout.addView(upgradeBadge)

            addView(headerLayout)
            addView(createSpacer(8))

            val subtext = buildSubtitleForSavedItem(item)
            addView(
                createTextView(subtext, 14f, R.color.text_secondary, false)
            )
            addView(createSpacer(16))

            val buttonsLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val viewBtn = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * density).toInt()
                ).apply {
                    marginEnd = (12 * density).toInt()
                }
                adjustViewBounds = true
                setImageResource(R.drawable.saved_view_btn)
                contentDescription = "View"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    viewModel.restoreSavedUpgrade(item)
                    val bundle = buildViewingBundle(item)
                    val destinationId = bundle.getInt(VIEWING_DESTINATION_ID_KEY, R.id.noBuildYetFragment)
                    bundle.remove(VIEWING_DESTINATION_ID_KEY)
                    findNavController().navigate(destinationId, bundle)
                }
            }
            buttonsLayout.addView(viewBtn)

            val deleteBtn = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * density).toInt()
                )
                adjustViewBounds = true
                setImageResource(R.drawable.saved_delete_btn)
                contentDescription = "Delete"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    viewModel.removeSavedUpgrade(item.id)
                }
            }
            buttonsLayout.addView(deleteBtn)

            addView(buttonsLayout)
        }
    }

    private fun createTextView(
        text: String,
        textSizeSp: Float,
        colorRes: Int,
        isBold: Boolean
    ): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            if (isBold) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
    }

    private fun createSpacer(heightDp: Int): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (heightDp * resources.displayMetrics.density).toInt()
            )
        }
    }

    /**
     * Bottom-nav style navigation: pop back to the dashboard in the same navigation call so
     * switching between top-level screens (Saved, Profile, Home) never stacks duplicates.
     */
    private fun navigateToTopLevelDestination(destinationId: Int) {
        findNavController().navigate(
            destinationId,
            null,
            NavOptions.Builder()
                .setPopUpTo(R.id.dashboardFragment, false)
                .build()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
