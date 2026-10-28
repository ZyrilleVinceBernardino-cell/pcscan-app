package com.pcscan.pcscan.ui.main

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentDashboardBinding
import com.pcscan.pcscan.ui.dashboard.RecentActivityItem
import com.pcscan.pcscan.ui.dashboard.RecentActivityType
import com.pcscan.pcscan.ui.dashboard.RecommendationViewModel
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val safeBinding: FragmentDashboardBinding? get() = _binding
    private val binding get() = _binding!!
    private inline fun withBinding(block: FragmentDashboardBinding.() -> Unit) {
        val b = safeBinding
        if (b != null && isAdded) {
            block(b)
        }
    }
    private val recommendationViewModel: RecommendationViewModel by activityViewModels()
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recommendationViewModel.resetAllSearchAndBuildState()

        binding.ivProfileBadge.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val radius = Math.min(view.width, view.height) / 2
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivProfileBadge.clipToOutline = true

        binding.tvGreeting.text = getGreetingLabel()
        binding.etSearch.text.clear()
        binding.ivClearSearch.visibility = View.GONE
        loadUserInfo()
        loadProfilePicture()
        observeDashboardStats()

        // Search bar functionality
        binding.ivSearchIcon.setOnClickListener {
            performSearch()
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        binding.ivClearSearch.setOnClickListener {
            binding.etSearch.text.clear()
            binding.ivClearSearch.visibility = View.GONE
        }

        // Show/hide clear button based on text
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.ivClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        binding.cardUpload.setOnClickListener {
            recommendationViewModel.resetAllSearchAndBuildState()
            findNavController().navigate(R.id.action_dashboardFragment_to_uploadImageFragment)
        }

        binding.cardOcr.setOnClickListener {
            recommendationViewModel.resetAllSearchAndBuildState()
            findNavController().navigate(R.id.action_dashboardFragment_to_ocrFragment)
        }

        binding.cardRangeBuild.setOnClickListener {
            recommendationViewModel.resetAllSearchAndBuildState()
            findNavController().navigate(R.id.action_dashboardFragment_to_rangeBuildFragment)
        }

        binding.tvProfileBadge.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_profileFragment)
        }

        binding.navSaved.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_savedBuildFragment)
        }

        binding.navProfile.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_profileFragment)
        }
    }

    private fun performSearch() {
        val searchQuery = binding.etSearch.text.toString().trim()
        if (searchQuery.isNotEmpty()) {
            val navController = findNavController()
            val currentDestId = navController.currentDestination?.id
            if (currentDestId != R.id.dashboardFragment) {
                return
            }

            recommendationViewModel.submitSearch(searchQuery)
            
            val bundle = Bundle()
            bundle.putString("searchQuery", searchQuery)
            try {
                navController.navigate(R.id.searchResultFragment, bundle)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Navigation error, please try again", Toast.LENGTH_SHORT).show()
            }
            
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        }
    }

    private fun loadProfilePicture() {
        val user = auth.currentUser
        val userId = user?.uid ?: return
        try {
            val file = File(requireContext().filesDir, "profile_$userId.jpg")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                binding.ivProfileBadge.setImageBitmap(bitmap)
                binding.tvProfileInitial.visibility = View.GONE
            }
        } catch (e: Exception) {
            // Ignore errors loading profile pic
        }
    }

    override fun onResume() {
        super.onResume()
        loadProfilePicture()
        recommendationViewModel.resetAllSearchAndBuildState()
        withBinding {
            etSearch.text.clear()
            ivClearSearch.visibility = View.GONE
        }
    }

    private fun observeDashboardStats() {
        recommendationViewModel.savedBuilds.observe(viewLifecycleOwner) { savedBuilds ->
            binding.tvBuildCount.text = savedBuilds.size.toString()
            updateSavedCount(savedBuilds.size, recommendationViewModel.savedUpgrades.value.orEmpty().size)
        }

        recommendationViewModel.savedUpgrades.observe(viewLifecycleOwner) { savedUpgrades ->
            updateSavedCount(recommendationViewModel.savedBuilds.value.orEmpty().size, savedUpgrades.size)
        }

        recommendationViewModel.recentActivities.observe(viewLifecycleOwner) { recentActivities ->
            renderRecentActivities(recentActivities)
        }

        recommendationViewModel.scanCount.observe(viewLifecycleOwner) { count ->
            binding.tvScanCount.text = count.toString()
        }
        recommendationViewModel.refreshScanCount()
    }

    private fun renderRecentActivities(activities: List<RecentActivityItem>) {
        binding.recentActivityContainer.removeAllViews()

        if (activities.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "No recent activity yet"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
                gravity = Gravity.CENTER
                setPadding(0, 16.dpToPx(), 0, 0)
            }
            binding.recentActivityContainer.addView(emptyText)
            return
        }

        for (activity in activities) {
            val activityView = createRecentActivityView(activity)
            binding.recentActivityContainer.addView(activityView)
        }
    }

    private fun createRecentActivityView(activity: RecentActivityItem): View {
        val density = resources.displayMetrics.density
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_action_card)
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            isClickable = true
            isFocusable = true

            val iconContainer = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_stats_card)
            }

            val icon = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (24 * density).toInt(),
                    (24 * density).toInt()
                )
                val iconRes = when (activity.type) {
                    RecentActivityType.SEARCH -> R.drawable.dashboard_search_icon
                    RecentActivityType.UPLOAD -> R.drawable.dashboard_upload_icon
                    RecentActivityType.OCR -> R.drawable.dashboard_ocr_icon
                    RecentActivityType.SAVED_BUILD -> {
                        if (activity.name.contains("Range Build", ignoreCase = true)) {
                            R.drawable.dashboard_range_build_icon
                        } else {
                            R.drawable.buildrecoiconactivity
                        }
                    }
                    RecentActivityType.SAVED_UPGRADE -> R.drawable.dashboard_save_count_icon
                    RecentActivityType.RANGE_BUILD -> R.drawable.dashboard_range_build_icon
                }
                setImageResource(iconRes)
                contentDescription = activity.type.name
            }
            iconContainer.addView(icon)
            addView(iconContainer)

            val textContainer = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = (16 * density).toInt()
            }
                orientation = LinearLayout.VERTICAL
            }

            val nameText = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = activity.name
                textSize = 16f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_white))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            textContainer.addView(nameText)

            val categoryTimeText = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
                text = "${activity.category} | ${formatTime(activity.timestamp)}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
            }
            textContainer.addView(categoryTimeText)

            addView(textContainer)
        }
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)

        return when {
            seconds < 60 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours hours ago"
            else -> "$days days ago"
        }
    }

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }

    private fun updateSavedCount(buildCount: Int, upgradeCount: Int) {
        withBinding {
            tvSavedCount.text = (buildCount + upgradeCount).toString()
        }
    }

    private fun loadUserInfo() {
        val userId = auth.currentUser?.uid
        val prefs = requireContext().getSharedPreferences("user_prefs", 0)

        // First load from cache for instant display
        val cachedName = prefs.getString("user_name", null)
        if (cachedName != null) {
            withBinding {
                tvWelcome.text = "Hello, ${cachedName.substringBefore(' ').ifBlank { cachedName }}"
                tvProfileInitial.text = cachedName.firstOrNull()?.uppercase() ?: "A"
            }
        } else {
            val email = auth.currentUser?.email
            if (email != null) {
                withBinding {
                    tvWelcome.text = "Hello there"
                    tvProfileInitial.text = email.firstOrNull()?.uppercase() ?: "A"
                }
            }
        }

        if (userId != null) {
            db.collection("users").document(userId)
                .get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val name = document.getString("fullName")
                            ?: document.getString("name")
                        if (name != null) {
                            withBinding {
                                tvWelcome.text = "Hello, ${name.substringBefore(' ').ifBlank { name }}"
                                tvProfileInitial.text = name.firstOrNull()?.uppercase() ?: "A"
                            }
                            prefs.edit().putString("user_name", name).apply()
                        }
                    }
                }
                .addOnFailureListener {
                    // If Firestore fails, we still have cache as fallback
                }
        }
    }

    private fun incrementScanCount() {
        val prefs = requireContext().getSharedPreferences(PREF_DASHBOARD, 0)
        val updated = prefs.getInt(KEY_SCAN_COUNT, 0) + 1
        prefs.edit().putInt(KEY_SCAN_COUNT, updated).apply()
        withBinding {
            tvScanCount.text = updated.toString()
        }
    }

    private fun getScanCount(): Int {
        val prefs = requireContext().getSharedPreferences(PREF_DASHBOARD, 0)
        return prefs.getInt(KEY_SCAN_COUNT, 0)
    }

    private fun getGreetingLabel(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..11 -> "GOOD MORNING"
            in 12..17 -> "GOOD AFTERNOON"
            else -> "GOOD EVENING"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREF_DASHBOARD = "dashboard_prefs"
        private const val KEY_SCAN_COUNT = "key_scan_count"
    }
}
