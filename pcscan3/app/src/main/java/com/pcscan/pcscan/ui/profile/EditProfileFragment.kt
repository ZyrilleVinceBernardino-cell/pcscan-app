package com.pcscan.pcscan.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentEditProfileBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class EditProfileFragment : Fragment() {

    private var _binding: FragmentEditProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var currentName: String = ""
    private var hasChanges = false

    companion object {
        private const val FIELD_PROFILE_IMAGE = "profileImageBase64"
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(it)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val scaledBitmap = scaleBitmapToSquare(originalBitmap)
                binding.ivAvatar.setImageBitmap(scaledBitmap)
                binding.tvAvatarInitial.visibility = View.GONE
                saveProfilePicture(scaledBitmap)
                hasChanges = true
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            pickImageLauncher.launch("image/*")
        } else {
            Toast.makeText(requireContext(), "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditProfileBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up custom outline for avatar
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        binding.ivAvatar.clipToOutline = true

        // Load user data
        loadUserData()

        // Back button
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        // Avatar click
        binding.avatarContainer.setOnClickListener {
            checkPermissionAndPickImage()
        }

        // Save button (top right)
        binding.btnSave.setOnClickListener {
            saveChanges()
        }

        // Save Changes button
        binding.btnSaveChanges.setOnClickListener {
            saveChanges()
        }

        // Reset Password button
        binding. btnResetPassword.setOnClickListener {
            sendPasswordReset()
        }

        // Delete Account button
        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }

        // Listen for name changes
        binding.etFullName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                hasChanges = true
            }
        })
    }

    private fun loadUserData() {
        val user = auth.currentUser ?: run {
            findNavController().popBackStack()
            return
        }

        // Set email
        binding.tvEmail.text = user.email

        // Load cached name
        val prefs = requireContext().getSharedPreferences("user_prefs", 0)
        val cachedName = prefs.getString("user_name", "")
        if (!cachedName.isNullOrEmpty()) {
            binding.etFullName.setText(cachedName)
            currentName = cachedName
            updateAvatarInitial(cachedName)
        } else {
            updateAvatarInitial(user.email ?: "")
        }

        // Load profile picture
        loadProfilePicture()

        // Load from Firestore for latest
        db.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                val name = document.getString("fullName")
                    ?: document.getString("name").orEmpty()
                if (name.isNotEmpty()) {
                    binding.etFullName.setText(name)
                    currentName = name
                    updateAvatarInitial(name)
                    // Update cache
                    prefs.edit().putString("user_name", name).apply()
                }
            }
    }

    private fun updateAvatarInitial(nameOrEmail: String) {
        val initial = nameOrEmail.firstOrNull()?.uppercase() ?: "A"
        binding.tvAvatarInitial.text = initial
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

    private fun scaleBitmapToSquare(bitmap: Bitmap): Bitmap {
        val size = kotlin.math.min(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    private fun saveProfilePicture(bitmap: Bitmap) {
        val user = auth.currentUser ?: return
        try {
            val imageBytes = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.toByteArray()
            }
            cacheProfilePicture(user.uid, imageBytes)
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            db.collection("users").document(user.uid)
                .set(mapOf(FIELD_PROFILE_IMAGE to base64Image), SetOptions.merge())
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save profile picture: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadProfilePicture() {
        val user = auth.currentUser ?: return
        try {
            val file = File(requireContext().filesDir, "profile_${user.uid}.jpg")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                binding.ivAvatar.setImageBitmap(bitmap)
                binding.tvAvatarInitial.visibility = View.GONE
            } else {
                binding.tvAvatarInitial.visibility = View.VISIBLE
                restoreProfilePictureFromFirestore(user.uid)
            }
        } catch (e: Exception) {
            binding.tvAvatarInitial.visibility = View.VISIBLE
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
                    binding.ivAvatar.setImageBitmap(bitmap)
                    binding.tvAvatarInitial.visibility = View.GONE
                }
            }
    }

    private fun saveChanges() {
        val user = auth.currentUser ?: run {
            findNavController().popBackStack()
            return
        }

        val newName = binding.etFullName.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your name", Toast.LENGTH_SHORT).show()
            return
        }

        // Update cache immediately
        val prefs = requireContext().getSharedPreferences("user_prefs", 0)
        prefs.edit().putString("user_name", newName).apply()

        // Update Firestore
        val userData = hashMapOf<String, Any>(
            "fullName" to newName,
            "name" to com.google.firebase.firestore.FieldValue.delete()
        )

        db.collection("users").document(user.uid)
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Changes saved successfully!", Toast.LENGTH_SHORT).show()
                hasChanges = false
                findNavController().popBackStack()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed to save changes: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun sendPasswordReset() {
        val user = auth.currentUser ?: return
        auth.sendPasswordResetEmail(user.email!!)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(requireContext(), "Password reset email sent to ${user.email}!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to send reset email: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to delete your account? This action cannot be undone and all your data will be permanently lost.")
            .setPositiveButton("Delete") { _, _ ->
                deleteAccount()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAccount() {
        val user = auth.currentUser ?: run {
            findNavController().popBackStack()
            return
        }

        // First delete user's data from Firestore
        db.collection("users").document(user.uid)
            .delete()
            .addOnSuccessListener {
                // Now delete the auth account
                user.delete()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            // Clear cache
                            val prefs = requireContext().getSharedPreferences("user_prefs", 0)
                            prefs.edit().clear().apply()
                            // Delete profile picture
                            try {
                                val file = File(requireContext().filesDir, "profile_${user.uid}.jpg")
                                file.delete()
                            } catch (_: Exception) {}
                            // Navigate back to login
                            Toast.makeText(requireContext(), "Account deleted successfully", Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack(R.id.loginFragment, false)
                        } else {
                            Toast.makeText(requireContext(), "Failed to delete account: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed to delete account: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
