package com.pcscan.pcscan.ui.auth

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentLoginBinding
import com.pcscan.pcscan.ui.dashboard.RecommendationViewModel
import java.io.File
import java.io.FileOutputStream

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val recommendationViewModel: RecommendationViewModel by activityViewModels()
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigate to Register screen
        binding.tvRegister.setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_registerFragment)
        }

        // Handle login button click
        binding.btnLogin.setOnClickListener {
            loginUser()
        }

        // Handle forgot password
        binding.tvForgotPassword.setOnClickListener {
            sendPasswordReset()
        }

        setupPasswordToggle(binding.etPassword, binding.btnTogglePassword)
    }

    private fun setupPasswordToggle(field: EditText, toggleButton: ImageButton) {
        toggleButton.setOnClickListener {
            val showingPassword = field.transformationMethod !is PasswordTransformationMethod
            if (showingPassword) {
                field.transformationMethod = PasswordTransformationMethod.getInstance()
                toggleButton.setImageResource(R.drawable.ic_visibility_off)
                toggleButton.contentDescription = "Show password"
            } else {
                field.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggleButton.setImageResource(R.drawable.ic_visibility)
                toggleButton.contentDescription = "Hide password"
            }
            field.setSelection(field.text?.length ?: 0)
        }
    }

    private fun sendPasswordReset() {
        val email = binding.etEmail.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your email first", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    Toast.makeText(
                        requireContext(),
                        "Password reset email sent. Please check your inbox.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to send reset email: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun loginUser() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your email", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your password", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        if (user.isEmailVerified) {
                            // Restore this account's saved data (Firestore) and scan counter.
                            recommendationViewModel.onUserLoggedIn()
                            // Load user name from Firestore and cache it
                            db.collection("users").document(user.uid)
                                .get()
                                .addOnSuccessListener { document ->
                                    val name = document.getString("fullName")
                                        ?: document.getString("name").orEmpty()
                                    if (name.isNotBlank()) {
                                        val prefs = requireContext().getSharedPreferences("user_prefs", 0)
                                        prefs.edit().putString("user_name", name).apply()
                                    }
                                    restoreProfilePictureLocally(user.uid, document.getString(FIELD_PROFILE_IMAGE).orEmpty())
                                    // Go to dashboard (we'll have cached name now or next time)
                                    val navController = findNavController()
                                    if (navController.currentDestination?.id == R.id.loginFragment) {
                                        navController.navigate(R.id.action_loginFragment_to_dashboardFragment)
                                    }
                                }
                                .addOnFailureListener {
                                    // Even if Firestore fails, still go to dashboard
                                    val navController = findNavController()
                                    if (navController.currentDestination?.id == R.id.loginFragment) {
                                        navController.navigate(R.id.action_loginFragment_to_dashboardFragment)
                                    }
                                }
                        } else {
                            // User not verified, send verification email and sign out
                            user.sendEmailVerification()
                                .addOnCompleteListener { verifyTask ->
                                    if (verifyTask.isSuccessful) {
                                        Toast.makeText(requireContext(), "Verification email sent. Please verify your email before logging in.", Toast.LENGTH_LONG).show()
                                        auth.signOut()
                                    } else {
                                        Toast.makeText(requireContext(), "Failed to send verification email", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Login failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun restoreProfilePictureLocally(userId: String, base64Image: String) {
        if (base64Image.isBlank()) return
        runCatching {
            val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
            val file = File(requireContext().filesDir, "profile_$userId.jpg")
            FileOutputStream(file).use { outputStream ->
                outputStream.write(imageBytes)
                outputStream.flush()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val FIELD_PROFILE_IMAGE = "profileImageBase64"
    }
}
