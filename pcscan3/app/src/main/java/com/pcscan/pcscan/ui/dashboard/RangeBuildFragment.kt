package com.pcscan.pcscan.ui.dashboard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentRangeBuildBinding
import kotlin.random.Random

class RangeBuildFragment : Fragment() {

    private enum class BudgetTier { ENTRY, MID, HIGH, FLAGSHIP }
    private enum class UsageOption { GAMING, PRODUCTIVITY, STREAMING, CONTENT, OFFICE, RENDERING }

    private var _binding: FragmentRangeBuildBinding? = null
    private val binding get() = _binding!!

    private var selectedTier: BudgetTier? = null
    private var selectedUsage: UsageOption? = null
    private var currentBudgetAmount: Long = 0L

    companion object {
        private const val ARG_FROM_RANGE_BUILD = "fromRangeBuild"
        private const val ARG_RANGE_BUDGET_LABEL = "rangeBudgetLabel"
        private const val ARG_RANGE_USAGE_LABEL = "rangeUsageLabel"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRangeBuildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { safePopBackToDashboard() }
        binding.btnBackToDashboard.setOnClickListener { safePopBackToDashboard() }

        // Budget input: detect tier as user types (no real-time comma)
        binding.etBudget.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.filter { it.isDigit() } ?: ""
                val amount = raw.toLongOrNull() ?: 0L
                currentBudgetAmount = amount
                selectedTier = tierFromBudget(amount)
                binding.btnClearBudget.visibility = if (raw.isEmpty()) View.GONE else View.VISIBLE
                applyTierHighlight()
                refreshSelectionState()
            }
        })

        // Format with commas only when user finishes typing (loses focus)
        binding.etBudget.setOnFocusChangeListener { _, hasFocus ->
            val text = binding.etBudget.text?.toString() ?: ""
            val raw = text.filter { it.isDigit() }
            if (!hasFocus) {
                if (raw.isNotEmpty()) {
                    binding.etBudget.setText(formatWithCommas(raw))
                }
            } else {
                // Remove commas when re-focusing so user can edit cleanly
                if (text != raw) {
                    binding.etBudget.setText(raw)
                    binding.etBudget.setSelection(raw.length)
                }
            }
        }

        binding.btnClearBudget.setOnClickListener {
            binding.etBudget.text?.clear()
            binding.etBudget.requestFocus()
        }

        // Usage selection
        val usageButtons = listOf(
            binding.chipUseGaming to UsageOption.GAMING,
            binding.chipUseProductivity to UsageOption.PRODUCTIVITY,
            binding.chipUseStreaming to UsageOption.STREAMING,
            binding.chipUseContent to UsageOption.CONTENT,
            binding.chipUseOffice to UsageOption.OFFICE,
            binding.chipUse3d to UsageOption.RENDERING
        )
        usageButtons.forEach { (btn, usage) ->
            btn.setOnClickListener { selectUsage(usage) }
        }

        // Generate button
        binding.btnGenerateBuild.isEnabled = false
        binding.btnGenerateBuild.setOnClickListener {
            val seed = buildSeedComponent(selectedTier, selectedUsage)
            val budgetLabel = budgetDisplayLabel(selectedTier)
            val usageLabel = usageDisplayLabel(selectedUsage)
            if (seed == null || budgetLabel == null || usageLabel == null) {
                showToast("Enter a budget and pick a usage first")
                return@setOnClickListener
            }
            val bundle = Bundle().apply {
                putString(YourBuildFragment.ARG_SEED_COMPONENT, seed)
                putString(YourBuildFragment.ARG_BUDGET_TIER, selectedTier?.name)
                putLong(YourBuildFragment.ARG_BUDGET_AMOUNT, currentBudgetAmount)
                putString(YourBuildFragment.ARG_BUDGET_LABEL, budgetLabel)
                putString(YourBuildFragment.ARG_USAGE_LABEL, usageLabel)
            }
            try {
                findNavController().navigate(
                    R.id.action_rangeBuildFragment_to_yourBuildFragment,
                    bundle
                )
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Navigation error, please try again")
            }
        }

        refreshSelectionState()
    }

    private fun formatWithCommas(digits: String): String {
        val sb = StringBuilder(digits.length + digits.length / 3)
        var i = 0
        while (i < digits.length) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(digits[i])
            i++
        }
        return sb.toString()
    }

    private fun tierFromBudget(amount: Long): BudgetTier? {
        return when {
            amount <= 0L -> null
            amount < 50_000L -> BudgetTier.ENTRY
            amount < 100_000L -> BudgetTier.MID
            amount < 200_000L -> BudgetTier.HIGH
            else -> BudgetTier.FLAGSHIP
        }
    }

    private fun applyTierHighlight() {
        val tiers = listOf(
            BudgetTier.ENTRY to (binding.tierEntry to binding.tierEntryDetail),
            BudgetTier.MID to (binding.tierMid to binding.tierMidDetail),
            BudgetTier.HIGH to (binding.tierHigh to binding.tierHighDetail),
            BudgetTier.FLAGSHIP to (binding.tierFlagship to binding.tierFlagshipDetail)
        )
        tiers.forEach { (tier, views) ->
            val (row, detail) = views
            if (tier == selectedTier) {
                row.setBackgroundResource(R.drawable.bg_tier_row_selected)
                detail.visibility = View.VISIBLE
            } else {
                row.setBackgroundResource(R.drawable.bg_card)
                detail.visibility = View.GONE
            }
        }
    }

    private fun selectUsage(usage: UsageOption) {
        selectedUsage = usage
        val usageButtons = listOf(
            binding.chipUseGaming to UsageOption.GAMING,
            binding.chipUseProductivity to UsageOption.PRODUCTIVITY,
            binding.chipUseStreaming to UsageOption.STREAMING,
            binding.chipUseContent to UsageOption.CONTENT,
            binding.chipUseOffice to UsageOption.OFFICE,
            binding.chipUse3d to UsageOption.RENDERING
        )
        usageButtons.forEach { (btn, key) ->
            applyUsageChipStyle(btn, selected = (key == usage))
        }
        refreshSelectionState()
    }

    private fun applyUsageChipStyle(chip: MaterialButton, selected: Boolean) {
        val ctx = requireContext()
        chip.isSelected = selected
        if (selected) {
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.white))
            chip.setIconTintResource(R.color.white)
        } else {
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
            chip.setIconTintResource(R.color.textSecondary)
        }
    }

    private fun refreshSelectionState() {
        val bothChosen = selectedTier != null && selectedUsage != null
        val tierOnly = selectedTier != null && selectedUsage == null
        val usageOnly = selectedTier == null && selectedUsage != null

        val btn = binding.btnGenerateBuild
        btn.isEnabled = bothChosen
        if (bothChosen) {
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            btn.setIconTintResource(R.color.white)
        } else {
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.textMuted))
            btn.setIconTintResource(R.color.textMuted)
        }

        val ctx = requireContext()
        binding.tvSelectionHint.text = when {
            bothChosen -> "Ready - ${tierLabel(selectedTier)} . ${usageDisplayLabel(selectedUsage)}"
            tierOnly -> "Now pick a usage purpose to generate your build."
            usageOnly -> "Enter your budget above to detect the right tier."
            else -> "Enter a budget and choose a usage purpose to continue."
        }
        binding.tvSelectionHint.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (bothChosen) R.color.success else R.color.textMuted
            )
        )
    }

    private fun tierLabel(tier: BudgetTier?): String {
        return when (tier) {
            BudgetTier.ENTRY -> "Entry Level"
            BudgetTier.MID -> "Mid-Range"
            BudgetTier.HIGH -> "High-End"
            BudgetTier.FLAGSHIP -> "Flagship"
            null -> ""
        }
    }

    private fun buildSeedComponent(
        tier: BudgetTier?,
        usage: UsageOption?
    ): String? {
        if (tier == null || usage == null) return null

        // Each branch picks uniformly from a per-category pool so all three GPU vendors
        // (NVIDIA, AMD Radeon, Intel Arc) get fair exposure. Intel Arc has no high-end GPU,
        // so it only appears in the ENTRY and MID pools.
        return when (tier) {
            BudgetTier.ENTRY -> when (usage) {
                UsageOption.GAMING -> pickRandom(
                    listOf("GTX 1650", "Radeon RX 6500 XT", "Intel Arc A380")
                )
                UsageOption.PRODUCTIVITY,
                UsageOption.STREAMING,
                UsageOption.CONTENT,
                UsageOption.RENDERING -> pickRandom(listOf("Intel i5-12400F", "Ryzen 5 5600"))
                UsageOption.OFFICE -> pickRandom(listOf("Intel i3-12100F", "Ryzen 3 4100"))
            }
            BudgetTier.MID -> when (usage) {
                UsageOption.GAMING -> pickRandom(
                    listOf("RTX 4060 Ti", "Radeon RX 7600", "Intel Arc A750")
                )
                UsageOption.PRODUCTIVITY,
                UsageOption.STREAMING,
                UsageOption.CONTENT -> pickRandom(listOf("Intel i5-13600K", "Ryzen 7 7700"))
                UsageOption.RENDERING -> pickRandom(
                    listOf("RTX 4070", "Radeon RX 7700 XT", "Intel Arc A770")
                )
                UsageOption.OFFICE -> pickRandom(listOf("Intel i5-12400", "Ryzen 5 5600"))
            }
            BudgetTier.HIGH -> when (usage) {
                UsageOption.GAMING,
                UsageOption.CONTENT -> pickRandom(
                    listOf("RTX 4080 Super", "Radeon RX 7900 XT", "Radeon RX 7900 XTX")
                )
                UsageOption.RENDERING -> pickRandom(listOf("RTX 4090", "Radeon RX 7900 XTX"))
                UsageOption.PRODUCTIVITY,
                UsageOption.STREAMING -> pickRandom(listOf("Intel i9-13900K", "Ryzen 9 7900"))
                UsageOption.OFFICE -> pickRandom(listOf("Intel i7-13700", "Ryzen 7 7700"))
            }
            BudgetTier.FLAGSHIP -> when (usage) {
                UsageOption.GAMING,
                UsageOption.CONTENT,
                UsageOption.RENDERING -> pickRandom(listOf("RTX 4090", "Radeon RX 7900 XTX"))
                UsageOption.PRODUCTIVITY,
                UsageOption.STREAMING -> pickRandom(listOf("Intel i9-14900K", "Ryzen 9 9950X"))
                UsageOption.OFFICE -> pickRandom(listOf("Intel i9-13900", "Ryzen 9 7900"))
            }
        }
    }

    private fun pickRandom(options: List<String>): String = options[Random.nextInt(options.size)]

    private fun budgetDisplayLabel(tier: BudgetTier?): String? {
        return when (tier) {
            BudgetTier.ENTRY -> "PHP 30K-49K"
            BudgetTier.MID -> "PHP 50K-99K"
            BudgetTier.HIGH -> "PHP 100K-199K"
            BudgetTier.FLAGSHIP -> "PHP 200K+"
            null -> null
        }
    }

    private fun usageDisplayLabel(usage: UsageOption?): String? {
        return when (usage) {
            UsageOption.GAMING -> "Gaming"
            UsageOption.PRODUCTIVITY -> "Productivity"
            UsageOption.STREAMING -> "Streaming"
            UsageOption.CONTENT -> "Content Creation"
            UsageOption.OFFICE -> "Office / Home"
            UsageOption.RENDERING -> "3D Rendering"
            null -> null
        }
    }

    private fun showToast(msg: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
