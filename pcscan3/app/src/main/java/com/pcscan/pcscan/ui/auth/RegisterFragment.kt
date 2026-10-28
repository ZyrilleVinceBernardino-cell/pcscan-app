package com.pcscan.pcscan.ui.auth

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pcscan.pcscan.R
import com.pcscan.pcscan.databinding.FragmentRegisterBinding

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigate to Login screen
        binding.tvLogin.setOnClickListener {
            findNavController().navigate(R.id.action_registerFragment_to_loginFragment)
        }

        // Handle register button click
        binding.btnRegister.setOnClickListener {
            registerUser()
        }

        setupPasswordToggle(binding.etPassword, binding.btnTogglePassword)
        setupPasswordToggle(binding.etConfirmPassword, binding.btnToggleConfirmPassword)
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

    private fun registerUser() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your name", Toast.LENGTH_SHORT).show()
            return
        }

        if (email.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your email", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter your password", Toast.LENGTH_SHORT).show()
            return
        }

        if (confirmPassword.isEmpty()) {
            Toast.makeText(requireContext(), "Please confirm your password", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(requireContext(), "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.length < 6) {
            Toast.makeText(requireContext(), "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let {
                        saveUserToFirestore(it.uid, name, email)
                        sendEmailVerification()
                    }
                } else {
                    Toast.makeText(requireContext(), "Registration failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun saveUserToFirestore(userId: String, name: String, email: String) {
        // First save to cache for instant access
        val prefs = requireContext().getSharedPreferences("user_prefs", 0)
        prefs.edit().putString("user_name", name).apply()

        // Then save to Firestore
        val user = hashMapOf<String, Any>(
            "userId" to userId,
            "fullName" to name,
            "email" to email
        )

        db.collection("users").document(userId)
            .set(user)
            .addOnSuccessListener {
                // User saved successfully
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Failed to save user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun sendEmailVerification() {
        val user = auth.currentUser
        user?.sendEmailVerification()
            ?.addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    Toast.makeText(requireContext(), "Verification email sent to ${user.email}. Please verify your email before logging in.", Toast.LENGTH_LONG).show()
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.registerFragment) {
                        navController.navigate(R.id.action_registerFragment_to_loginFragment)
                    }
                } else {
                    Toast.makeText(requireContext(), "Failed to send verification email", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
