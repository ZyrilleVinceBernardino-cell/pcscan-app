package com.pcscan.pcscan.ui.profile

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentProfileBinding
import com.pcscan.pcscan.ui.dashboard.RecommendationViewModel
import com.yalantis.ucrop.UCrop
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val recommendationViewModel: RecommendationViewModel by activityViewModels()

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var currentProfileName: String = ""

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            startCrop(it)
        }
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == androidx.fragment.app.FragmentActivity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let {
                try {
                    val inputStream = requireActivity().contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        saveProfilePicture(bitmap)
                        loadProfilePicture()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to load cropped image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR && result.data != null) {
            val cropError = UCrop.getError(result.data!!)
            Toast.makeText(requireContext(), "Crop error: ${cropError?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            pickImageLauncher.launch("image/*")
        } else {
            Toast.makeText(requireContext(), "Permission needed to select image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.ivProfilePicture.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivProfilePicture.clipToOutline = true
        
        bindActions()
        observeProfileStats()
        binding.tvVersion.text = getVersionDisplayText()
        loadProfile()
        loadProfilePicture()
    }

    private fun bindActions() {
        binding.actionEditProfile.setOnClickListener {
            showEditProfileOptionsDialog()
        }

        binding.actionSavedRecommendations.setOnClickListener {
            navigateToTopLevelDestination(R.id.savedBuildFragment)
        }

        binding.actionLogout.setOnClickListener {
            confirmLogout()
        }

        binding.navHome.setOnClickListener {
            goToDashboard()
        }

        binding.navSaved.setOnClickListener {
            navigateToTopLevelDestination(R.id.savedBuildFragment)
        }
    }

    private fun startCrop(sourceUri: Uri) {
        val user = auth.currentUser ?: return
        val destinationUri = Uri.fromFile(File(requireContext().cacheDir, "cropped_profile_${user.uid}.jpg"))

        val uCrop = UCrop.of(sourceUri, destinationUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(400, 400)
            .withOptions(UCrop.Options().apply {
                setToolbarTitle("Crop Profile Picture")
                setCircleDimmedLayer(true)
                setShowCropFrame(true)
                setShowCropGrid(true)
                setHideBottomControls(false)
                setFreeStyleCropEnabled(false)
            })

        cropLauncher.launch(uCrop.getIntent(requireContext()))
    }

    private fun observeProfileStats() {
        recommendationViewModel.savedBuilds.observe(viewLifecycleOwner) { savedBuilds ->
            binding.tvBuildCount.text = savedBuilds.size.toString()
            updateSavedCount(savedBuilds.size, recommendationViewModel.savedUpgrades.value.orEmpty().size)
        }

        recommendationViewModel.savedUpgrades.observe(viewLifecycleOwner) { savedUpgrades ->
            updateSavedCount(recommendationViewModel.savedBuilds.value.orEmpty().size, savedUpgrades.size)
        }

        recommendationViewModel.scanCount.observe(viewLifecycleOwner) { count ->
            binding.tvScanCount.text = count.toString()
        }
        recommendationViewModel.refreshScanCount()
    }

    private fun updateSavedCount(buildCount: Int, upgradeCount: Int) {
        binding.tvSavedCount.text = (buildCount + upgradeCount).toString()
    }

    private fun loadProfile() {
        val user = auth.currentUser
        val userId = user?.uid.orEmpty()
        if (userId.isBlank()) {
            Toast.makeText(requireContext(), "Please login first.", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val email = user?.email.orEmpty()
        binding.tvProfileEmail.text = email

        // First load from cache for instant display
        val prefs = requireContext().getSharedPreferences("user_prefs", 0)
        val cachedName = prefs.getString("user_name", null)
        if (cachedName != null) {
            currentProfileName = cachedName
            updateHeaderName(cachedName, email)
        } else {
            updateHeaderName(name = "", email = email)
        }

        // Then load from Firestore to get latest and update cache
        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                val name = document.getString("fullName")
                    ?: document.getString("name").orEmpty()
                currentProfileName = name
                updateHeaderName(name, email)
                // Save to cache
                if (name.isNotBlank()) {
                    prefs.edit().putString("user_name", name).apply()
                }
            }
            .addOnFailureListener { e ->
                // If Firestore fails, we still have cache as fallback
            }
    }

    private fun showEditProfileOptionsDialog() {
        findNavController().navigate(R.id.action_profileFragment_to_editProfileFragment)
    }

    private fun checkPermissionAndPickImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            pickImageLauncher.launch("image/*")
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun showEditNameDialog() {
        val context = requireContext()
        val spacing = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, 0)
        }

        val nameInput = EditText(context).apply {
            hint = "Enter your full name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(currentProfileName)
            setTextColor(resources.getColor(R.color.text_white, context.theme))
            setHintTextColor(resources.getColor(R.color.text_secondary, context.theme))
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(spacing, spacing, spacing, spacing)
        }

        container.addView(nameInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Edit Name")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    nameInput.error = "Name is required"
                    return@setOnClickListener
                }
                saveProfile(name)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun saveProfile(name: String) {
        val user = auth.currentUser
        val userId = user?.uid.orEmpty()
        if (userId.isBlank()) {
            Toast.makeText(requireContext(), "Please login first.", Toast.LENGTH_SHORT).show()
            return
        }

        val email = user?.email.orEmpty()

        // First update UI and cache instantly
        currentProfileName = name
        updateHeaderName(name, email)
        val prefs = requireContext().getSharedPreferences("user_prefs", 0)
        prefs.edit().putString("user_name", name).apply()

        // Then save to Firestore
        val userData = hashMapOf<String, Any>(
            "userId" to userId,
            "fullName" to name,
            "name" to FieldValue.delete(),
            "email" to email,
            "phone" to FieldValue.delete()
        )

        db.collection("users").document(userId)
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Profile saved.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed to save profile: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveProfilePicture(bitmap: Bitmap) {
        val user = auth.currentUser
        val userId = user?.uid ?: return

        try {
            val imageBytes = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.toByteArray()
            }
            cacheProfilePicture(userId, imageBytes)
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            db.collection("users").document(userId)
                .set(mapOf(FIELD_PROFILE_IMAGE to base64Image), SetOptions.merge())
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save profile picture: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadProfilePicture() {
        val user = auth.currentUser
        val userId = user?.uid ?: return

        try {
            val file = File(requireContext().filesDir, "profile_$userId.jpg")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                binding.ivProfilePicture.setImageBitmap(bitmap)
                binding.tvProfileInitial.visibility = View.GONE
            } else {
                binding.tvProfileInitial.visibility = View.VISIBLE
                binding.ivProfilePicture.setImageDrawable(null)
                restoreProfilePictureFromFirestore(userId)
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to load profile picture: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cacheProfilePicture(userId: String, imageBytes: ByteArray) {
        val file = File(requireContext().filesDir, "profile_$userId.jpg")
        FileOutputStream(file).use { outputStream ->
            outputStream.write(imageBytes)
            outputStream.flush()
        }
    }

    private fun restoreProfilePictureFromFirestore(userId: String) {
        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                val base64Image = document.getString(FIELD_PROFILE_IMAGE).orEmpty()
                if (base64Image.isBlank()) return@addOnSuccessListener
                runCatching {
                    val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                    cacheProfilePicture(userId, imageBytes)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    binding.ivProfilePicture.setImageBitmap(bitmap)
                    binding.tvProfileInitial.visibility = View.GONE
                }
            }
    }

    private fun updateHeaderName(name: String, email: String) {
        val displayName = name.ifBlank { "PC Scan User" }
        binding.tvProfileName.text = displayName

        val initialSource = if (name.isNotBlank()) name else email
        binding.tvProfileInitial.text = initialSource.firstOrNull()?.uppercase() ?: "A"
    }

    private fun getScanCount(): Int {
        val prefs = requireContext().getSharedPreferences(PREF_DASHBOARD, 0)
        return prefs.getInt(KEY_SCAN_COUNT, 0)
    }

    private fun getVersionDisplayText(): String {
        val context = requireContext()
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

        val versionName = packageInfo.versionName.orEmpty().ifBlank { "1.0" }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return "PC Scan v$versionName | Build $versionCode"
    }

    private fun goToDashboard() {
        val navController = findNavController()
        val returnedToDashboard = navController.popBackStack(R.id.dashboardFragment, false)
        if (!returnedToDashboard) {
            navController.navigate(R.id.dashboardFragment)
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

    private fun confirmLogout() {
        val context = context ?: return
        val dialogTheme = com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert
        MaterialAlertDialogBuilder(context, dialogTheme)
            .setTitle("Log out")
            .setMessage("Are you sure you want to log out of your account?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Log out") { _, _ -> logout() }
            .show()
    }

    private fun logout() {
        recommendationViewModel.clearLocalUserDataOnLogout()
        auth.signOut()
        findNavController().navigate(
            R.id.loginFragment,
            null,
            NavOptions.Builder()
                .setPopUpTo(R.id.dashboardFragment, true)
                .build()
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PREF_DASHBOARD = "dashboard_prefs"
        private const val KEY_SCAN_COUNT = "key_scan_count"
        private const val FIELD_PROFILE_IMAGE = "profileImageBase64"
    }
}
