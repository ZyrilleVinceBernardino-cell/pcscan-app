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
import androidx.navigation.fragment.findNavController
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentSavedUpgradeBinding
import com.pcscan.pcscan.ui.dashboard.RecommendationViewModel
import com.pcscan.pcscan.ui.dashboard.SavedUpgradeItem
import com.pcscan.pcscan.ui.dashboard.UpgradePathFragment

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

        // Tab click listeners
        binding.tabBuildRecommendations.setOnClickListener {
            findNavController().navigate(R.id.action_savedUpgradeFragment_to_savedBuildFragment)
        }
        binding.tabUpgradePaths.setOnClickListener {
            // Already on this tab
        }

        // Bottom navigation listeners
        binding.navHome.setOnClickListener {
            val navController = findNavController()
            val returnedToDashboard = navController.popBackStack(R.id.dashboardFragment, false)
            if (!returnedToDashboard) {
                navController.navigate(R.id.dashboardFragment)
            }
        }
        binding.navSaved.setOnClickListener {
            // Already here
        }
        binding.navProfile.setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }

        // Observe saved upgrades
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

    private fun createUpgradeCard(item: SavedUpgradeItem): View {
        val density = resources.displayMetrics.density
        val spacing = (16 * density).toInt()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundResource(R.drawable.bg_card)

            // Header row (name + upgrade badge)
            val headerLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            headerLayout.addView(
                createTextView(item.path.title, 18f, R.color.text_white, true).apply {
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

            // Subtext: tier and number of steps
            val subtext = "${if (item.hasExistingBuild) "High-End" else "Midrange"} • 3 components"
            addView(
                createTextView(subtext, 14f, R.color.text_secondary, false)
            )
            addView(createSpacer(16))

            // Buttons row: View and Delete
            val buttonsLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Use the provided button artwork so the saved card matches the mockup exactly.
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
                    findNavController().navigate(
                        R.id.upgradePathFragment,
                        Bundle().apply {
                            putString(UpgradePathFragment.ARG_SELECTED_COMPONENT, item.component)
                        }
                    )
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
