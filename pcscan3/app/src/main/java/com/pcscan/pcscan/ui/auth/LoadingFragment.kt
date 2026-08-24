package com.pcscan.pcscan.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.pcscan.pcscan.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoadingFragment : Fragment() {

    private lateinit var auth: FirebaseAuth

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        auth = FirebaseAuth.getInstance()
        return inflater.inflate(R.layout.fragment_loading, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            delay(1500)
            if (!isAdded) return@launch
            checkUserAuth()
        }
    }

    private fun checkUserAuth() {
        val navController = runCatching { findNavController() }.getOrNull() ?: return
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            navController.navigate(R.id.action_loadingFragment_to_dashboardFragment)
        } else {
            navController.navigate(R.id.action_loadingFragment_to_loginFragment)
        }
    }
}
