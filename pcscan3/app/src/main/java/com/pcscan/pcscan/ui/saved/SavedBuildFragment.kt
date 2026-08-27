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
import com.pcscan.pcscan.databinding.FragmentSavedBuildBinding
import com.pcscan.pcscan.ui.dashboard.BuildRecoFragment
import com.pcscan.pcscan.ui.dashboard.RecommendationViewModel
import com.pcscan.pcscan.ui.dashboard.SavedBuildItem

class SavedBuildFragment : Fragment() {

    private var _binding: FragmentSavedBuildBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecommendationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedBuildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Tab click listeners
        binding.tabBuildRecommendations.setOnClickListener {
            // Already on this tab
        }
        binding.tabUpgradePaths.setOnClickListener {
            findNavController().navigate(R.id.action_savedBuildFragment_to_savedUpgradeFragment)
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

        // Observe saved builds
        viewModel.savedBuilds.observe(viewLifecycleOwner) { savedBuilds ->
            renderSavedBuilds(savedBuilds)
        }
    }

    private fun renderSavedBuilds(savedBuilds: List<SavedBuildItem>) {
        binding.buildsListContainer.removeAllViews()

        if (savedBuilds.isEmpty()) {
            binding.buildsListContainer.addView(
                createTextView(
                    "No saved builds yet. Save one from the Build Recommendation screen first.",
                    16f,
                    R.color.text_white,
                    false
                )
            )
        } else {
            savedBuilds.forEachIndexed { index, item ->
                binding.buildsListContainer.addView(createBuildCard(item))
                if (index != savedBuilds.lastIndex) {
                    binding.buildsListContainer.addView(createSpacer(16))
                }
            }
        }
    }

    private fun createBuildCard(item: SavedBuildItem): View {
        val density = resources.displayMetrics.density
        val spacing = (16 * density).toInt()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundResource(R.drawable.bg_card)

            // Header row (name + build badge)
            val headerLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            headerLayout.addView(
                createTextView(item.build.name, 18f, R.color.text_white, true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
            )

            val buildBadge = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setImageResource(R.drawable.saved_build_label)
                contentDescription = "Build Badge"
            }
            headerLayout.addView(buildBadge)

            addView(headerLayout)
            addView(createSpacer(8))

            // Subtext: CPU, GPU, RAM
            val subtext = "${item.build.cpu} | ${item.build.gpu} | ${item.build.ram}"
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
                    viewModel.restoreSavedBuild(item)
                    findNavController().navigate(
                        R.id.buildRecoFragment,
                        Bundle().apply {
                            putString(BuildRecoFragment.ARG_SELECTED_COMPONENT, item.component)
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
                    viewModel.removeSavedBuild(item.id)
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
